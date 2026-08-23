"""backend.machine.decide — the transition function.

decide() is one reconcile pass over the three component sub-machines. It is
PURE: no I/O — the store feeds observations in, persists the mutated
machine, and executes the returned actions as jobs. Each component is its
own small when-block; cross-component coupling is explicit in the inputs
(`live`, `obs`) rather than hidden in call sites.

Per-component summary:

  dump     wanted + hprof not done -> ACQUIRE_DUMP (remote only — the hprof
           has no local build; a confirmed-absent run release is a real
           ERROR, everything else just idles until a later tick)
  data     not done -> ACQUIRE_DATA when published (unprompted — CI ships
           the bundle minutes after the hprof), else BOOTSTRAP, but only on
           an explicit user action (allow_local) — nothing local starts
           unprompted
  indexes  not done -> ACQUIRE_INDEXES when published (unprompted), else
           PARSE on an explicit analysis request (want_indexes) once hprof
           AND data are in place; a running PARSE is preempted by a late
           remote publication (minutes vs tens of minutes); DONE + raws over
           a compacted set + idle MAT queue + no client hold -> COMPACT
           (housekeeping)
"""
from __future__ import annotations

from .types import (A_ACQUIRE_DATA, A_ACQUIRE_DUMP, A_ACQUIRE_INDEXES,
                    A_BOOTSTRAP, A_COMPACT, A_PARSE, A_PREEMPT_PARSE,
                    CANCELLED, Comp, DONE, DOWNLOADING, ERROR, IN_PROGRESS,
                    Machine, NEW, Obs, PARSING, RemoteView)


def decide(m: Machine, obs: Obs, live: set, remote: RemoteView,
           allow_local: bool = False, mat_idle: bool = False,
           compact_hold: bool = False) -> list:
    """Mutate `m` to the next state (the store persists it) and return the
    actions to execute. `live` = component names with an active stage job
    ("data" also covers a running bootstrap, which produces indexes too).
    `allow_local` = the pass is driven by an explicit user action.
    `mat_idle` = the serial MAT queue has no active work (compact guard).
    `compact_hold` = a client pinned this dump's restored indexes via the
    compact-hold API — autocompact stays off until it lapses."""
    actions = []
    validate(m, obs)
    _dump(m, live, remote, actions)
    _data(m, obs, live, remote, allow_local, actions)
    _indexes(m, obs, live, remote, mat_idle, compact_hold, actions)
    return actions


def validate(m: Machine, obs: Obs):
    """DONE is earned by artifacts, not by the record: demote DONE states
    whose artifacts vanished (or whose compacted set contradicts the
    manifest persisted at commit), promote components whose final artifacts
    are already on disk. A raw index set with parse debris and no .zst
    backing is an interrupted local parse — partial, untrusted, NOT promoted
    (the acquiring stage drops it)."""
    if m.dump.s != DONE and obs.hprof:
        m.dump = Comp(DONE)
    elif m.dump.s == DONE and not obs.hprof:
        m.dump = Comp()
    if m.data.s != DONE and obs.data:
        m.data = Comp(DONE)
    elif m.data.s == DONE and not obs.data:
        m.data = Comp()
    zsts_ok = obs.zsts and obs.zsts_valid is not False
    idx_ok = (obs.raws or zsts_ok) and not (obs.debris and not obs.zsts)
    if m.indexes.s != DONE and idx_ok:
        m.indexes = Comp(DONE, compacted=not obs.raws)
    elif m.indexes.s == DONE and not (obs.raws or zsts_ok):
        m.indexes = Comp()
    # a crashed local parse nobody wants anymore does not deserve an ERROR
    # (nobody is waiting) — reset silently; a late remote publication or an
    # explicit analyze re-drives it
    if m.indexes.s == PARSING and not m.want_indexes:
        m.indexes = Comp()


def _dump(m: Machine, live: set, remote: RemoteView, actions: list):
    c = m.dump
    if c.s in (DONE, ERROR, CANCELLED) or not m.wanted:
        return
    if c.s == DOWNLOADING and "dump" in live:
        return
    if not remote.usable:
        return                     # unknown — idle, a later tick re-queries
    if not remote.hprof:
        # the source confirmed it does NOT have the dump: unlike "not
        # published yet" for data/indexes, the hprof only ever appears with
        # the run release itself, so this is a real dead end
        c.s = ERROR
        c.error = "no remote source has this dump (anymore)"
        return
    c.s = DOWNLOADING
    c.error = None
    actions.append(A_ACQUIRE_DUMP)


def _data(m: Machine, obs: Obs, live: set, remote: RemoteView,
          allow_local: bool, actions: list):
    c = m.data
    if c.s in (DONE, ERROR, CANCELLED) or not m.wanted:
        return
    if c.s in IN_PROGRESS and "data" in live:
        return
    if remote.usable and remote.data:
        c.s = DOWNLOADING
        c.error = None
        actions.append(A_ACQUIRE_DATA)
        return
    # nothing published: the local MAT bootstrap is explicit-user-action only
    if allow_local and obs.hprof:
        c.s = PARSING
        c.error = None
        actions.append(A_BOOTSTRAP)


def _indexes(m: Machine, obs: Obs, live: set, remote: RemoteView,
             mat_idle: bool, compact_hold: bool, actions: list):
    c = m.indexes
    if c.s == DONE:
        # housekeeping: raw indexes lying over a compacted set, MAT idle,
        # and no client holding the restored set against autocompact
        if obs.raws and obs.compacted_marker and mat_idle \
                and not compact_hold and "indexes" not in live:
            actions.append(A_COMPACT)
        return
    if c.s in (ERROR, CANCELLED) or not (m.wanted or m.want_indexes):
        return
    if c.s == PARSING and "indexes" in live:
        # a late remote publication preempts the running local parse — the
        # download is minutes, the parse is tens of minutes
        if remote.usable and remote.indexes:
            c.s = DOWNLOADING
            c.error = None
            actions.append(A_PREEMPT_PARSE)
            actions.append(A_ACQUIRE_INDEXES)
        return
    if c.s in IN_PROGRESS and "indexes" in live:
        return
    if "data" in live:
        return                       # the bootstrap produces indexes itself
    if remote.usable and remote.indexes:
        c.s = DOWNLOADING
        c.error = None
        actions.append(A_ACQUIRE_INDEXES)
        return
    # the local parse needs the hprof and doubles as the analysis trigger;
    # it only runs on an explicit request (want_indexes), never unprompted
    if m.want_indexes and obs.hprof and obs.data:
        c.s = PARSING
        c.error = None
        actions.append(A_PARSE)
