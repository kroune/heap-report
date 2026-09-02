"""backend.machine.types — the state types of the per-dump machine.

One machine per dump, three component sub-machines — `dump` (the hprof),
`data` (the overview bundle), `indexes` (the MAT index set) — each with the
same lifecycle:

    NEW -> DOWNLOADING|PARSING -> DONE
    in-progress -> ERROR       work-site retries exhausted; only an explicit
                               retry (store.start_download) leaves ERROR
    in-progress -> CANCELLED   user abort; same re-entry rules as ERROR

`indexes` has two acquisition modes (DOWNLOADING = remote prebuilt set,
PARSING = local MAT parse) and a DONE flavor (`compacted`). `data` uses
PARSING for the local MAT bootstrap (which produces data AND indexes).

Rules that make the machine robust:

  - In-progress states are UNTRUSTED: re-entering one (resume after crash,
    retry after ERROR/CANCELLED) re-validates the on-disk artifacts and
    resumes or restarts the stage. Stage executors own that validation;
    the machine only records position.
  - DONE is gated on artifacts: decide() demotes DONE states whose artifacts
    vanished and promotes components whose final artifacts are on disk (also
    how a meta-less dump dir adopts a machine — observation first).
  - Remote availability is an INPUT (RemoteView), never a state.
  - Jobs are execution vehicles, never state: `live` comes from the job
    registry at each pass.

Pure data + (de)serialization only; the logic lives in decide.py.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

# component state tags
NEW = "new"
DOWNLOADING = "downloading"
PARSING = "parsing"          # local MAT work (data bootstrap / index parse)
DONE = "done"
ERROR = "error"
CANCELLED = "cancelled"
IN_PROGRESS = (DOWNLOADING, PARSING)
TERMINAL = (DONE, ERROR, CANCELLED)

COMPONENTS = ("dump", "data", "indexes")

# actions decide() returns (the store turns them into jobs / signals)
A_ACQUIRE_DUMP = "acquire_dump"          # DOWNLOAD job, detail="dump"
A_ACQUIRE_DATA = "acquire_data"          # DOWNLOAD job, detail="data"
A_BOOTSTRAP = "bootstrap"                # local MAT build of data+indexes
A_ACQUIRE_INDEXES = "acquire_indexes"    # DOWNLOAD job, detail="indexes"
A_PARSE = "parse"                        # local MAT index parse
A_PREEMPT_PARSE = "preempt_parse"        # abort the running local parse
A_COMPACT = "compact"                    # housekeeping: recompress raw indexes


@dataclass
class Comp:
    """One component's state. `parts` is the dump download's resume token
    ({part name: expected size}); `error` is the ERROR payload; `compacted`
    is the indexes DONE flavor."""
    s: str = NEW
    error: Optional[str] = None
    compacted: bool = False
    parts: dict = field(default_factory=dict)

    def reset(self):
        """ERROR/CANCELLED -> NEW on an explicit retry. Resume tokens live
        on disk and are re-observed, so nothing else survives the reset."""
        self.s = NEW
        self.error = None


@dataclass
class Machine:
    wanted: bool = False        # the user asked for this dump (sticky)
    want_indexes: bool = False  # an analysis asked for the MAT indexes (sticky)
    dump: Comp = field(default_factory=Comp)
    data: Comp = field(default_factory=Comp)
    indexes: Comp = field(default_factory=Comp)

    def comp(self, name: str) -> Comp:
        return getattr(self, name)


# ------------------------------------------------------------------ observations


@dataclass
class Obs:
    """Disk truth for one dump dir, gathered by the store."""
    hprof: bool = False             # daemon.hprof at its final path
    data: bool = False              # data bundle CSVs present
    raws: bool = False              # raw *.index set present
    zsts: bool = False              # compacted *.index.zst set present
    zsts_valid: object = None       # None = no persisted manifest (trust
                                    # presence), True/False = matches/contra-
                                    # dicts the manifest persisted at commit
    debris: bool = False            # local-parse debris (interrupted parse)
    compacted_marker: bool = False  # INDEXES-COMPACTED.txt present


@dataclass
class RemoteView:
    """What the remote sources offer, as of this pass. `error` = the lookup
    itself failed (upstream hiccup): decide() treats everything as unknown
    and idles; the next tick re-queries. `queried=False` when no component
    could need the remote (same idling, cheaper). plan is the priority-merged
    DownloadPlan and source the transfer.SourceRouter over the ordered remote
    sources — both carried for the store's stage executors; decide() only
    reads the booleans."""
    queried: bool = False
    error: Optional[str] = None
    hprof: bool = False
    data: bool = False
    indexes: bool = False
    plan: object = None    # core.DownloadPlan
    source: object = None  # core.RemoteDumpSource

    @property
    def usable(self):
        return self.queried and self.error is None


# ------------------------------------------------------------------ (de)serialization

_STATES = (NEW, DOWNLOADING, PARSING, DONE, ERROR, CANCELLED)


def comp_from(d) -> Comp:
    if not isinstance(d, dict):
        return Comp()
    parts = d.get("parts")
    return Comp(s=d.get("s") if d.get("s") in _STATES else NEW,
                error=d.get("error"),
                compacted=bool(d.get("compacted")),
                parts={str(k): v for k, v in parts.items()}
                if isinstance(parts, dict) else {})


def comp_to(c: Comp) -> dict:
    out = {"s": c.s}
    if c.error:
        out["error"] = c.error
    if c.compacted:
        out["compacted"] = True
    if c.parts:
        out["parts"] = c.parts
    return out


def machine_from(d) -> Machine:
    if not isinstance(d, dict):
        return Machine()
    return Machine(wanted=bool(d.get("wanted")),
                   want_indexes=bool(d.get("want_indexes")),
                   dump=comp_from(d.get("dump")),
                   data=comp_from(d.get("data")),
                   indexes=comp_from(d.get("indexes")))


def machine_to(m: Machine) -> dict:
    return {"wanted": m.wanted, "want_indexes": m.want_indexes,
            "dump": comp_to(m.dump), "data": comp_to(m.data),
            "indexes": comp_to(m.indexes)}
