"""backend.machine — the hierarchical per-dump state machine.

  types.py    state types, observations, actions, (de)serialization
  decide.py   the pure transition function (one reconcile pass)
  project.py  the legacy flat DumpState projection for the HTTP contract

See types.py's docstring for the lifecycle and the rules that make it
robust. Nothing in this package does I/O.
"""
from .types import (A_ACQUIRE_DATA, A_ACQUIRE_DUMP, A_ACQUIRE_INDEXES,
                    A_BOOTSTRAP, A_COMPACT, A_PARSE, A_PREEMPT_PARSE,
                    CANCELLED, COMPONENTS, Comp, DONE, DOWNLOADING, ERROR,
                    IN_PROGRESS, Machine, NEW, Obs, PARSING, RemoteView,
                    TERMINAL, comp_from, comp_to, machine_from, machine_to)
from .decide import decide, validate
from .project import project

__all__ = [
    "A_ACQUIRE_DATA", "A_ACQUIRE_DUMP", "A_ACQUIRE_INDEXES", "A_BOOTSTRAP",
    "A_COMPACT", "A_PARSE", "A_PREEMPT_PARSE", "CANCELLED", "COMPONENTS",
    "Comp", "DONE", "DOWNLOADING", "ERROR", "IN_PROGRESS", "Machine", "NEW",
    "Obs", "PARSING", "RemoteView", "TERMINAL", "comp_from", "comp_to",
    "machine_from", "machine_to", "decide", "validate", "project",
]
