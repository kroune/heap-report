"""backend.core — the contracts every backend implementation is written against.

Nothing in this module does I/O or imports an impl. Impls live in sibling
modules (one file each, owned by one author/agent):

  backend/localstore.py  FsDumpStore        — LocalDumpStore (the writable source)
  backend/github.py      GitHubSource       — RemoteDumpSource over GitHub releases
  backend/s3.py          S3Source           — RemoteDumpSource over the private
                         SeaweedFS (fast lane, strictly preferred; SigV4, stdlib)
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
    """The legacy flat state the HTTP/frontend contract speaks. It is a pure
    PROJECTION (machine.project) of the hierarchical per-dump machine
    (backend/machine): three component sub-machines — dump (hprof), data
    (overview bundle), indexes (MAT index set) — each NEW / in-progress
    (DOWNLOADING or local-PARSING) / DONE / ERROR / CANCELLED. The machine is
    persisted in meta.json and reconciled from disk truth; this flat enum is
    never the source of anything.

        dump error/cancelled  -> FAILED (retry resumes an errored download;
            a cancelled one was purged and restarts from scratch)
        dump in flight        -> DOWNLOADING / ASSEMBLING (parts tail in gzip)
        dump done, data open  -> INDEXING
        dump done, data done  -> READY (MAT indexes are OPTIONAL and lazy —
            an indexes ERROR never drags a usable dump below READY)

    Overview queries (trees/classes/compare) are served from any busy state
    once the data bundle is unpacked; analysis-level queries stay READY-only.
    """

    REMOTE = "remote"            # known to a source, not present locally
    DOWNLOADING = "downloading"  # parts landing in .dl/ (resumable)
    ASSEMBLING = "assembling"    # streaming parts through gzip/tar
    INDEXING = "indexing"        # hprof present, data missing (fill/bootstrap pending)
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
    CANCELLED = "cancelled"


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
    progress: Optional[dict] = None            # {"done","total"}, units kind-specific;
                                               # downloads add stage/speed/eta/parts/asm
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

    def cancel(self, job_id: int) -> bool:
        """QUEUED -> CANCELLED (True); anything else False. A RUNNING job is
        NOT killable at this level — its fn exits via the dump's cooperative
        abort flag (LocalDumpStore.cancel) and lands CANCELLED on its own."""
        ...


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
    meta.json, parts, indexes (single-writer rule). Queries only read.

    Lifecycle is driven by the per-dump state machine (backend/machine):
    reconcile() observes disk + remote availability and executes the next
    stage; every event source (user action, timer, stage completion,
    startup) just kicks reconcile()."""

    def get(self, dump_id: DumpId) -> DumpInfo:
        """Raises ApiError('not_found', ...) for unknown ids."""
        ...

    def start_download(self, dump_id: DumpId) -> Job:
        """The explicit user action: mark the dump wanted, reset ERROR /
        CANCELLED components, allow local MAT builds this pass, reconcile
        synchronously and return the first stage job. Idempotent — kept .dl/
        parts resume. Raises ApiError('bad_state') when everything is done,
        ApiError('not_found') when no remote source has anything for it."""
        ...

    def cancel(self, dump_id: DumpId) -> None:
        """User abort: every in-progress component -> CANCELLED, the running
        stage jobs abort cooperatively, and the partial download scratch
        (.dl/ parts, .untar/ staging, *.assembling) is purged once the stages
        have exited — an explicit retry restarts the download from scratch
        (only ERROR/crash re-entry resumes kept parts)."""
        ...

    def reconcile(self, dump_id: DumpId, allow_local: bool = False) -> list:
        """Run reconcile passes until quiescent (dirty-flag loop); returns
        the jobs submitted this call. allow_local permits local MAT builds
        (explicit user actions only — timers pass False)."""
        ...

    def reconcile_all(self) -> None:
        """Server startup (kernel only — never from init(), snapshot/CI
        stores are source-less and must not touch state): adopt every local
        dump dir into its machine and reconcile. Crashed in-progress stages
        re-enter and re-validate their artifacts; ERROR/CANCELLED stay put
        (user input)."""
        ...

    def request_indexes(self, dump_id: DumpId) -> None:
        """Engine hook: an analysis wants the MAT indexes. Sticky — the
        machine acquires them (remote download when published, else a local
        parse) and keeps driving across restarts."""
        ...

    def await_indexes(self, dump_id: DumpId, job: Job) -> None:
        """Block until the indexes component reaches DONE; raise with the
        component error on ERROR, core.Aborted on CANCELLED."""
        ...

    def note_indexes_corrupt(self, dump_id: DumpId) -> None:
        """Engine hook: a compacted index failed decompression and the set
        was dropped. The indexes component goes back to NEW and is
        re-acquired (remote when published, else local parse)."""
        ...

    def delete(self, dump_id: DumpId) -> None:
        """Cancel any in-flight stages, wait for them to exit, remove the
        dump dir."""
        ...

    def compact(self, dump_id: DumpId) -> Job:
        """(Re)compress MAT indexes per LAYOUT.md's mtime convention."""
        ...

    def hold_compact(self, dump_id: DumpId, seconds: float = None) -> float:
        """Suppress autocompact for this dump for `seconds` (bounded);
        returns the hold's expiry (wall-clock unix ts). Process-lifetime —
        a restart drops holds. Re-locking extends."""
        ...

    def release_compact(self, dump_id: DumpId) -> bool:
        """Drop the compact hold early (idempotent); True when a live hold
        existed."""
        ...

    def dir_of(self, dump_id: DumpId) -> str:
        """Absolute path of the dump dir, in ANY state of an existing dump
        (raises ApiError('not_found') for unknown ids). State gating is the
        caller's job: MatQueryEngine._data_dir enforces READY for
        analysis-level queries, _data_early allows busy states once the data
        bundle is unpacked, the bootstrap works during INDEXING."""
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

    def set_tags(self, dump_id: DumpId, tags: list) -> list:
        """Replace the user-assigned tags of a dump — any well-formed id,
        local or remote. Validated/normalized by the impl; returns the stored
        list ([] clears). Persisted OUTSIDE meta.json (a remote dump has no
        local dir, so update_meta cannot hold them)."""
        ...

    def user_tags(self) -> dict:
        """{dump_id: [tag, …]} for every tagged dump."""
        ...


# ---------------------------------------------------------------- queries


class QueryEngine(Protocol):
    """Read queries over local dumps, plus on-demand MAT analysis jobs.
    Overview queries (trees/classes/compare) work off the data bundle alone
    and are served in any busy state once it is unpacked; analysis-level
    queries (composition/anatomy/analyze) require READY. Owns all payload
    caches; invalidates them when a dump's data changes. Returned dicts are
    JSON-serializable payloads (the HTTP layer passes them through)."""

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


class Aborted(Exception):
    """Cooperative cancellation: a stage noticed its dump's abort flag and
    exited (user cancel, parse preemption, delete). Python threads cannot be
    killed — stages poll the flag at chunk/2s boundaries. The state
    transition already happened (CANCELLED, or preemption's DOWNLOADING);
    an Aborted stage must NOT touch the machine, just fail its job."""


# ---------------------------------------------------------------- wiring


@dataclass
class App:
    """What kernel.build_app(root) returns and http.serve(app, port) consumes."""
    store: LocalDumpStore
    engine: QueryEngine
    jobs: JobRegistry
    sources: list  # all registered DumpSources, store first
