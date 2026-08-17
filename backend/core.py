"""backend.core — the contracts every backend implementation is written against.

Nothing in this module does I/O or imports an impl. Impls live in sibling
modules (one file each, owned by one author/agent):

  backend/localstore.py  FsDumpStore        — LocalDumpStore (the writable source)
  backend/github.py      GitHubSource       — RemoteDumpSource over GitHub releases
  backend/jobs.py        InMemoryJobRegistry — JobRegistry + executors
  backend/mat/         MatQueryEngine     — QueryEngine + LocalIndexer (MAT);
                       package: parsing / payloads / extract / engine
  backend/http.py        serve(app, port)   — thin HTTP adapter
  backend/kernel.py      build_app(root)    — wires the impls into an App

See ARCHITECTURE.md for the design rationale and LAYOUT.md for the on-disk
contract.
"""
from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Callable, Iterator, Optional, Protocol

# A dump id is its directory name under the dumps root == the release tag on
# the remote source, e.g. "run-6-base". Valid: [\w.-]+  (no "/", no "..").
DumpId = str


class DumpState(enum.Enum):
    """Lifecycle of a dump. Allowed transitions (all driven by the store):

        REMOTE      -- start_download --> DOWNLOADING
        DOWNLOADING -- parts complete --> ASSEMBLING
        ASSEMBLING  -- indexes complete (manifest) --> READY
        ASSEMBLING  -- no usable indexes ----------> INDEXING   (local MAT bootstrap)
        INDEXING    -- bootstrap done --------------> READY
        any of DOWNLOADING/ASSEMBLING/INDEXING --error--> FAILED
        any of DOWNLOADING/ASSEMBLING/INDEXING --process death--> (state persists;
            store.recover_interrupted() at server startup resubmits the job,
            or -> FAILED if unresumable)
        FAILED      -- start_download (resume) ----> DOWNLOADING
        READY/FAILED -- delete ---------------------> (gone; REMOTE if a source lists it)

    READY is kept during compact() (the store serializes compact vs queries).
    Only READY dumps serve queries.
    """

    REMOTE = "remote"            # known to a source, not present locally
    DOWNLOADING = "downloading"  # parts landing in .dl/ (resumable)
    ASSEMBLING = "assembling"    # streaming parts through gzip/tar
    INDEXING = "indexing"        # local MAT bootstrap fallback
    READY = "ready"
    FAILED = "failed"


@dataclass
class DumpInfo:
    id: DumpId
    state: DumpState
    source: str = ""                 # source name that listed it ("" = local)
    size: Optional[int] = None       # total expected bytes, if the source knows
    error: Optional[str] = None      # set when state == FAILED
    progress: Optional[tuple[int, int]] = None  # (done, total) bytes while DOWNLOADING
    meta: dict = field(default_factory=dict)   # source-specific extras (title, date…)


# ---------------------------------------------------------------- jobs


class JobState(enum.Enum):
    QUEUED = "queued"
    RUNNING = "running"
    DONE = "done"
    FAILED = "failed"


class JobKind(enum.Enum):
    DOWNLOAD = "download"    # download pool (default 2 workers)
    INDEX = "index"          # serial MAT queue
    ANALYZE = "analyze"      # serial MAT queue
    COMPACT = "compact"      # serial MAT queue (touches index files)


@dataclass
class Job:
    id: int
    kind: JobKind
    dump_id: Optional[DumpId]
    detail: str = ""                             # e.g. class name for ANALYZE
    state: JobState = JobState.QUEUED
    progress: Optional[tuple[int, int]] = None   # (done, total), units kind-specific
    log: list = field(default_factory=list)      # tail only, capped by the registry
    error: Optional[str] = None


class JobRegistry(Protocol):
    """Owns the executors. Concurrency policy is an impl detail, pinned here:
    DOWNLOAD jobs run on a small pool; INDEX/ANALYZE/COMPACT are strictly
    serial (one MAT JVM at a time)."""

    def submit(self, kind: JobKind, dump_id: Optional[DumpId], detail: str,
               fn: Callable[[Job], None]) -> Job:
        """Queue fn for execution. If an identical active job (same kind,
        dump_id, detail) exists, return it instead of queueing a duplicate.
        fn runs on the executor with the Job as a mutable handle (it appends
        to job.log / sets job.progress); an exception marks the job FAILED
        with the exception text — fn must not swallow its own fatal errors."""
        ...

    def get(self, job_id: int) -> Optional[Job]: ...
    def list(self, limit: int = 30) -> list: ...


# ---------------------------------------------------------------- sources


@dataclass(frozen=True)
class Part:
    """One downloadable piece of a dump. `index` defines concatenation order —
    never rely on name sorting."""
    name: str
    index: int
    size: Optional[int]
    url: str                     # source-specific locator (HTTP url, file path…)


@dataclass(frozen=True)
class DownloadPlan:
    dump_id: DumpId
    data_bundle: Optional[Part]      # tiny data.tar.gz (instant overview), if published
    hprof_parts: tuple               # ordered Part tuple, gzip members of daemon.hprof
    index_parts: tuple               # ordered Part tuple, tar of compacted indexes
    manifest: dict                   # completeness info for the index tar
                                     # (file list + sizes); {} if none published


class DumpSource(Protocol):
    """Read-only discovery. Registered sources answer list(); the merged view
    is what the UI shows."""
    name: str

    def init(self) -> None:
        """Lifecycle hook called once at startup: build caches, prefetch."""
        ...

    def list(self) -> list:
        """Dumps this source knows about (state REMOTE for remote sources)."""
        ...


class RemoteDumpSource(DumpSource, Protocol):
    """A source the local store can pull artifacts from."""

    def download_plan(self, dump_id: DumpId) -> Optional[DownloadPlan]:
        """None if this source doesn't have the dump."""
        ...

    def fetch(self, part: Part, offset: int = 0) -> Iterator[bytes]:
        """Stream the part's bytes starting at `offset` (HTTP Range resume).
        One attempt only — no retries inside: the caller (store) owns the
        retry/resume policy and re-calls fetch with a new offset."""
        ...


class LocalDumpStore(DumpSource, Protocol):
    """The one writable source. Owns every mutation of a dump's directory —
    meta.json, parts, indexes (single-writer rule). Queries only read."""

    def get(self, dump_id: DumpId) -> DumpInfo:
        """Raises ApiError('not_found', ...) for unknown ids."""
        ...

    def start_download(self, dump_id: DumpId) -> Job:
        """Idempotent: returns the active job if one exists, resumes kept
        .dl/ parts otherwise. Resolves which registered RemoteDumpSource has
        the dump. Validates the index tar against DownloadPlan.manifest
        BEFORE marking READY (never trust the mere presence of .zst files)."""
        ...

    def delete(self, dump_id: DumpId) -> None: ...

    def compact(self, dump_id: DumpId) -> Job:
        """(Re)compress MAT indexes per LAYOUT.md's mtime convention."""
        ...

    def dir_of(self, dump_id: DumpId) -> str:
        """Absolute path of the dump dir; allowed in READY and INDEXING (the
        store-sanctioned bootstrap job works during INDEXING). READ QUERIES
        must additionally enforce READY themselves (MatQueryEngine._data_dir
        does). Raises ApiError('bad_state', ...) otherwise."""
        ...

    def read_meta(self, dump_id: DumpId) -> dict:
        """Current meta.json contents ({} when absent)."""
        ...

    def update_meta(self, dump_id: DumpId, mutate: Callable[[dict], None]) -> dict:
        """Atomic locked read-modify-write of meta.json; returns the new
        contents. The ONLY way anything (including the query engine recording
        analysis results, or an INDEX job flipping state to READY) writes
        meta."""
        ...


# ---------------------------------------------------------------- queries


class QueryEngine(Protocol):
    """Read queries over READY dumps, plus on-demand MAT analysis jobs.
    Owns all payload caches; invalidates them when a dump's data changes.
    Returned dicts are JSON-serializable payloads (the HTTP layer passes them
    through)."""

    def trees(self, dump_id: DumpId) -> dict:
        """{"stats": …, "trees": …} — the overview tab."""
        ...

    def classes(self, dump_id: DumpId, filter: str = "", sort: str = "-s",
                page: int = 0) -> dict:
        """{"rows": [...], "total", "page", "pages"} — filter/sort/page
        semantics live in the engine."""
        ...

    def composition(self, dump_id: DumpId, cls: str) -> Optional[dict]:
        """None = not analyzed yet (HTTP layer answers 404 {"analyzed": false})."""
        ...

    def anatomy(self, dump_id: DumpId, cls: str, version: int = 1,
                samples: Optional[int] = None) -> Optional[dict]:
        """version 1 = aggregated named-reference tree, 2 = full-graph
        reference tree. None = not analyzed."""
        ...

    def compare(self, a: DumpId, b: DumpId) -> dict: ...

    def analyze(self, dump_id: DumpId, cls: str, samples: int = 32,
                with_anatomy: bool = True) -> Job:
        """Queue on-demand per-class MAT analysis.
        A failed extraction must surface as a FAILED job — never recorded
        in meta.json as if it succeeded."""
        ...


# ---------------------------------------------------------------- errors


class ApiError(Exception):
    """The only error type crossing module boundaries. The HTTP layer maps it
    to {"error": message, "code": code} with `status`."""

    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code = code
        self.status = status


# ---------------------------------------------------------------- wiring


@dataclass
class App:
    """What kernel.build_app(root) returns and http.serve(app, port) consumes."""
    store: LocalDumpStore
    engine: QueryEngine
    jobs: JobRegistry
    sources: list  # all registered DumpSources, store first
