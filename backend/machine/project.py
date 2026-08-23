"""backend.machine.project — the legacy flat-state projection.

The HTTP/frontend contract speaks the flat DumpState strings; the machine
is the truth and this is the pure view. Every reachable machine state must
project somewhere sensible — that property is the frontend-compat check.

  dump ERROR/CANCELLED   -> FAILED (the retry button resumes it)
  dump not done          -> DOWNLOADING, or ASSEMBLING once every part is
                            complete in .dl/ (the gunzip tail)
  dump done, data done   -> READY (index failures never drag a usable dump
                            down: indexes are lazy — an indexes ERROR leaves
                            the dump READY, the analysis surface reports it)
  dump done, data error  -> FAILED
  dump done, data else   -> INDEXING (hprof present, data missing)
"""
from __future__ import annotations

from .. import core
from .types import CANCELLED, DONE, ERROR, Machine


def project(m: Machine, assembled: bool):
    """(DumpState, error|None). `assembled`: all dump parts are complete in
    .dl/ — the download tail is streaming through gzip (the old ASSEMBLING
    badge)."""
    c = m.dump
    if c.s == ERROR:
        return core.DumpState.FAILED, c.error
    if c.s == CANCELLED:
        return core.DumpState.FAILED, "cancelled by user"
    if c.s != DONE:
        return (core.DumpState.ASSEMBLING if assembled
                else core.DumpState.DOWNLOADING), None
    d = m.data
    if d.s == DONE:
        return core.DumpState.READY, None
    if d.s == ERROR:
        return core.DumpState.FAILED, d.error
    if d.s == CANCELLED:
        return core.DumpState.FAILED, "cancelled by user"
    return core.DumpState.INDEXING, None   # hprof present, data missing
