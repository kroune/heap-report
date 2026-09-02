"""backend.localstore.store — FsDumpStore: the filesystem-backed LocalDumpStore.

The single writer for everything under dumps/<id>/ and the owner of the
per-dump state machines (backend/machine). The control plane:

  reconcile(dump_id)  — one dirty-flag loop: observe disk truth, query the
                        remote sources when a component could act on the
                        answer, run the pure machine.decide(), persist the
                        machine, execute the returned actions as jobs.
  event sources       — user actions (start_download/cancel/delete), the
                        kernel timers, stage completion, server startup —
                        never do work themselves; they adjust the machine
                        (wanted, resets, want_indexes) and kick reconcile().
  stage jobs          — execution vehicles only (backend/localstore/stages.py
                        + the engine's parse/bootstrap). Their outcome is
                        CAS-applied to the machine (machine_transition): a
                        cancel/preempt that already moved the state wins.

Concurrency: one reconcile pass per dump at a time (per-dump lock, latest
event wins via the dirty flag); stages of different components run
concurrently, the download pool and .dl/ namespacing are transfer's
business, not the machine's. Python threads cannot be cancelled — the abort
flag is polled at chunk/attempt boundaries (core.Aborted).
"""
import json
import logging
import os
import re
import shutil
import threading
import time

from .. import core, machine
from ..machine import Comp
from . import files, stages
from .compact import compact_dir
from .transfer import SourceRouter, Transfer

log = logging.getLogger("backend.localstore")

DUMP_RE = re.compile(r"^[\w.-]+$")
TAG_RE = re.compile(r"^\w[\w .:-]{0,39}$")   # user tags: 1-40 chars
MAX_TAGS = 24                                # per dump


def _merge_plans(a, b):
    """Priority-ordered plan merge: each component comes from the FIRST
    source (remote_sources order — S3 before GitHub) that has it, e.g. S3's
    dump plus GitHub's late-published indexes. Parts carry source-specific
    urls; the SourceRouter fetches each from its owner (and switches a part
    to a faster lane mid-download when a probe hits)."""
    return core.DownloadPlan(
        dump_id=a.dump_id,
        data_bundle=a.data_bundle or b.data_bundle,
        hprof_parts=a.hprof_parts or b.hprof_parts,
        index_parts=a.index_parts or b.index_parts,
        manifest=a.manifest or b.manifest)

# active job (kind, detail) -> the machine components it drives
_LIVE = {
    (core.JobKind.DOWNLOAD, "dump"): {"dump"},
    (core.JobKind.DOWNLOAD, "data"): {"data"},
    (core.JobKind.DOWNLOAD, "indexes"): {"indexes"},
    (core.JobKind.INDEX, "parse"): {"indexes"},
    (core.JobKind.INDEX, "bootstrap"): {"data", "indexes"},
}
_ACTIVE = (core.JobState.QUEUED, core.JobState.RUNNING)
_MAT_KINDS = (core.JobKind.INDEX, core.JobKind.ANALYZE, core.JobKind.COMPACT)
COMPACT_HOLD_MAX = 3600   # compact-hold TTL cap, seconds (one hour)


class _Rt:
    """Per-dump runtime (process-lifetime, never persisted): reconcile
    serialization, the dirty flag (latest event wins), the cooperative
    abort flag, the condition await_indexes sleeps on, the one-shot
    allow_local of an explicit user action, the inline-parse liveness
    marker (a parse running inside an awaiting analyze job — the MAT
    worker itself — registers here so preemption still sees it as live),
    and the compact hold (a client pinning the restored indexes against
    autocompact for a bounded time)."""

    def __init__(self):
        self.lock = threading.Lock()
        self.dirty = threading.Event()
        self.abort = threading.Event()
        self.cond = threading.Condition()
        self.allow_local = False
        self.inline_indexes = threading.Event()
        self.compact_hold_until = 0.0   # wall-clock ts; 0 = no hold


class FsDumpStore:
    name = "local"

    def __init__(self, root, jobs, remote_sources):
        self.root = os.path.abspath(root)
        self.jobs = jobs
        self.remote_sources = list(remote_sources)
        self.transfer = Transfer(jobs)
        self.indexer = None        # wired by the kernel to the engine's bootstrap
        self.parser_inline = None  # wired by the kernel to the engine's local
                                   # parse, run INLINE by await_indexes (the
                                   # caller is the serial MAT worker — queueing
                                   # the parse behind it would deadlock)
        self.on_data_files = None  # wired by MatQueryEngine.__init__: ingest
                                   # landed data-bundle CSVs into analysis.db.
                                   # Keeps localstore free of any mat import.
        self._meta_locks = {}
        self._meta_guard = threading.Lock()
        self._tags_lock = threading.Lock()
        self._rts = {}
        self._rt_guard = threading.Lock()

    def init(self):
        os.makedirs(self.root, exist_ok=True)

    def reconcile_all(self):
        """Server startup (kernel only — snapshot/CI stores are source-less
        and never reconcile): adopt every local dump dir into its machine
        and reconcile. Crashed in-progress stages re-enter and re-validate
        their artifacts (kept .dl/ parts resume); ERROR/CANCELLED stay put —
        they wait for the user by design."""
        for info in self.list():
            try:
                self.reconcile(info.id)
            except Exception:
                log.exception("startup reconcile failed for %s", info.id)

    # ------------------------------------------------------------ meta / tags

    def _dir(self, dump_id):
        return os.path.join(self.root, dump_id)

    def _check_id(self, dump_id):
        if not dump_id or not DUMP_RE.match(dump_id) or ".." in dump_id:
            raise core.ApiError("bad_id", f"invalid dump id: {dump_id!r}")

    def _meta_lock(self, dump_id):
        with self._meta_guard:
            return self._meta_locks.setdefault(dump_id, threading.Lock())

    def read_meta(self, dump_id):
        p = os.path.join(self._dir(dump_id), "data", "meta.json")
        try:
            with open(p) as f:
                m = json.load(f)
            return m if isinstance(m, dict) else {}
        except Exception:
            return {}

    def update_meta(self, dump_id, mutate):
        self._check_id(dump_id)
        d = self._dir(dump_id)
        if not os.path.isdir(d):
            raise core.ApiError("not_found", f"unknown dump: {dump_id}", 404)
        with self._meta_lock(dump_id):          # threads
            lf = files.flock(d, ".meta.lock")   # processes
            try:
                meta = self.read_meta(dump_id)
                mutate(meta)
                data = os.path.join(d, "data")
                os.makedirs(data, exist_ok=True)
                tmp = os.path.join(data, f".meta.json.tmp{os.getpid()}")
                with open(tmp, "w") as f:
                    json.dump(meta, f, indent=1)
                os.replace(tmp, os.path.join(data, "meta.json"))
                return meta
            finally:
                lf.close()

    def _tags_path(self):
        return os.path.join(self.root, ".tags.json")

    def user_tags(self):
        """{dump_id: [tag, …]} — every user-assigned tag set ({} when the
        sidecar is absent/corrupt)."""
        try:
            with open(self._tags_path()) as f:
                t = json.load(f)
            if isinstance(t, dict):
                return {str(k): [str(x) for x in v] for k, v in t.items()
                        if isinstance(v, list)}
        except Exception:
            pass
        return {}

    def set_tags(self, dump_id, tags):
        """Replace a dump's user tags. Any well-formed id is taggable, local
        or remote — tags live in the root-level .tags.json sidecar, NOT
        meta.json (a remote dump has no local dir, so update_meta would 404).
        Normalized: stripped, validated against TAG_RE, deduped, [] clears."""
        self._check_id(dump_id)
        if not isinstance(tags, (list, tuple)):
            raise core.ApiError("bad_request", "tags must be a list", 400)
        out = []
        for t in tags:
            t = str(t).strip()
            if not t:
                continue
            if not TAG_RE.match(t):
                raise core.ApiError("bad_request", f"invalid tag: {t!r}", 400)
            if t not in out:
                out.append(t)
        if len(out) > MAX_TAGS:
            raise core.ApiError("bad_request",
                                f"too many tags (max {MAX_TAGS})", 400)
        with self._tags_lock:                         # threads
            lf = files.flock(self.root, ".tags.lock")   # processes
            try:
                all_tags = self.user_tags()
                if out:
                    all_tags[dump_id] = out
                else:
                    all_tags.pop(dump_id, None)
                tmp = self._tags_path() + f".tmp{os.getpid()}"
                with open(tmp, "w") as f:
                    json.dump(all_tags, f, indent=1)
                os.replace(tmp, self._tags_path())
            finally:
                lf.close()
        return out

    # ------------------------------------------------------------ machine persistence

    def _rt(self, dump_id):
        with self._rt_guard:
            return self._rts.setdefault(dump_id, _Rt())

    def _observe(self, dump_id):
        d = self._dir(dump_id)
        raws, zsts = files.raws_zsts(d)
        zsts_valid = None   # no persisted manifest -> presence is trusted
        if zsts:
            manifest = self.read_meta(dump_id).get("idx_manifest")
            if isinstance(manifest, dict) and manifest:
                zsts_valid = all(
                    os.path.exists(os.path.join(d, name))
                    and (not isinstance(size, int)
                         or os.path.getsize(os.path.join(d, name)) == size)
                    for name, size in manifest.items())
        return machine.Obs(
            hprof=os.path.exists(os.path.join(d, "daemon.hprof")),
            data=files.has_data(d),
            raws=bool(raws), zsts=bool(zsts), zsts_valid=zsts_valid,
            debris=bool(files.parse_debris(d)),
            compacted_marker=os.path.exists(os.path.join(d, files.MARKER)))

    def _machine(self, dump_id):
        return self._machine_of(dump_id, self.read_meta(dump_id))

    def _machine_of(self, dump_id, meta, obs=None):
        """The persisted machine, or — for a dir without meta["machine"]
        (crash before the first persist, or a pre-machine dump) — one
        bootstrapped from observation. Not legacy-adoption: in-progress
        states are untrusted by design, so inference only needs `wanted`
        and where to park a legacy failure."""
        if isinstance(meta.get("machine"), dict):
            return machine.machine_from(meta["machine"])
        if obs is None:
            obs = self._observe(dump_id)
        return self._infer_machine(dump_id, meta, obs)

    def _infer_machine(self, dump_id, meta, obs):
        m = machine.Machine()
        legacy = meta.get("state") if isinstance(meta.get("state"), str) else ""
        dl = os.path.isdir(os.path.join(self._dir(dump_id), ".dl"))
        m.wanted = bool(dl or obs.hprof or obs.data or obs.raws or obs.zsts
                        or legacy in ("downloading", "assembling", "indexing",
                                      "ready", "failed"))
        machine.validate(m, obs)   # promote DONE components from artifacts
        if legacy == "failed" and m.wanted:
            err = meta.get("error") or "failed"
            if m.dump.s != machine.DONE:
                m.dump = Comp(machine.ERROR, error=err)
            elif m.data.s != machine.DONE:
                m.data = Comp(machine.ERROR, error=err)
        return m

    def machine_transition(self, dump_id, comp, expect, to):
        """CAS a component state — how stage jobs (including the engine's
        parse/bootstrap) report their outcome. Applied only when the
        component is still in one of the `expect` states (None = always):
        a cancel/preempt that already moved the state wins."""
        def mut(meta):
            m = self._machine_of(dump_id, meta)
            if expect is not None and m.comp(comp).s not in expect:
                return
            setattr(m, comp, to)
            meta["machine"] = machine.machine_to(m)
        self.update_meta(dump_id, mut)
        rt = self._rt(dump_id)
        with rt.cond:
            rt.cond.notify_all()

    def set_resume_tokens(self, dump_id, comp, parts):
        """The dump download's expected part sizes — persisted so the
        projection can tell downloading from assembling after a restart."""
        def mut(meta):
            m = self._machine_of(dump_id, meta)
            m.comp(comp).parts = {p.name: p.size for p in parts}
            meta["machine"] = machine.machine_to(m)
        self.update_meta(dump_id, mut)

    # ------------------------------------------------------------ reconcile

    def reconcile(self, dump_id, allow_local=False):
        """Run reconcile passes until quiescent; returns the jobs submitted
        by this call. Passes are serialized per dump; an event arriving
        mid-pass just re-sets the dirty flag and gets its own pass — the
        latest event always gets its evaluation, never mid-stage."""
        self._check_id(dump_id)
        if not os.path.isdir(self._dir(dump_id)):
            raise core.ApiError("not_found", f"unknown dump: {dump_id}", 404)
        rt = self._rt(dump_id)
        if allow_local:
            rt.allow_local = True
        rt.dirty.set()
        if not rt.lock.acquire(blocking=False):
            return []   # a running pass sees the dirty flag and re-evaluates
        submitted = []
        try:
            while rt.dirty.is_set():
                rt.dirty.clear()
                submitted += self._pass(dump_id, rt)
        finally:
            rt.lock.release()
        return submitted

    def kick(self, dump_id):
        """Event hook for stage jobs: re-evaluate the machine. Tolerant —
        the dump may be gone (delete) by the time a stage exits."""
        try:
            self.reconcile(dump_id)
        except core.ApiError:
            pass
        except Exception:
            log.exception("reconcile failed for %s", dump_id)

    def _pass(self, dump_id, rt):
        obs = self._observe(dump_id)
        live = self._live(dump_id)
        if rt.abort.is_set() and not live:
            rt.abort.clear()   # the aborted stages have exited; reset for reuse
        allow_local, rt.allow_local = rt.allow_local, False
        remote = self._remote_view(dump_id, self._machine(dump_id), live)
        # decide + persist atomically w.r.t. stage transitions: both go
        # through update_meta's locked read-modify-write, so a transition
        # landing mid-pass is never clobbered by a stale whole-machine save
        # (which would re-drive an already-finished/-failed component).
        # obs/live/remote are gathered just before and may be marginally
        # stale — stages re-validate artifacts themselves, and job dedup +
        # the CAS transitions cover the rest.
        out = {}

        def mut(meta):
            m = self._machine_of(dump_id, meta)
            out["actions"] = machine.decide(m, obs, live, remote,
                                            allow_local=allow_local,
                                            mat_idle=self._mat_idle(),
                                            compact_hold=self._compact_held(dump_id))
            meta["machine"] = machine.machine_to(m)
            out["m"] = m

        self.update_meta(dump_id, mut)
        m, actions = out["m"], out["actions"]
        with rt.cond:
            rt.cond.notify_all()
        submitted = []
        for a in actions:
            job = self._exec(dump_id, a, rt, remote)
            if job is not None:
                submitted.append(job)
        if not live and not submitted \
                and all(m.comp(c).s == machine.DONE for c in machine.COMPONENTS):
            # everything done and nothing running: leftover .dl/ parts
            # (a kill between commit and cleanup) and stale .untar/ staging
            # are redundant. Only THIS quiescent sweep may remove the shared
            # scratch dirs — stages drop per-file, never the dirs (their
            # siblings may be mid-flight).
            shutil.rmtree(os.path.join(self._dir(dump_id), ".dl"),
                          ignore_errors=True)
            shutil.rmtree(os.path.join(self._dir(dump_id), files.UNTAR),
                          ignore_errors=True)
        return submitted

    def _exec(self, dump_id, action, rt, view):
        """Turn one decided action into a job (or a signal). Job dedup
        (kind, dump, detail = the stage identity) is the second line of
        defense against double-starts; `live` gating in decide() is first."""
        if action == machine.A_PREEMPT_PARSE:
            rt.abort.set()
            return None
        if action == machine.A_ACQUIRE_DUMP:
            return self.jobs.submit(
                core.JobKind.DOWNLOAD, dump_id, "dump",
                self._wrap(dump_id, "dump",
                           stages.dump_stage(self, dump_id, view, rt)))
        if action == machine.A_ACQUIRE_DATA:
            return self.jobs.submit(
                core.JobKind.DOWNLOAD, dump_id, "data",
                self._wrap(dump_id, "data",
                           stages.data_stage(self, dump_id, view, rt)))
        if action == machine.A_ACQUIRE_INDEXES:
            return self.jobs.submit(
                core.JobKind.DOWNLOAD, dump_id, "indexes",
                self._wrap(dump_id, "indexes",
                           stages.indexes_stage(self, dump_id, view, rt)))
        if action == machine.A_BOOTSTRAP:
            if self.indexer is None:
                self.machine_transition(
                    dump_id, "data", machine.IN_PROGRESS,
                    Comp(machine.ERROR,
                         error="nothing published and no local indexer wired"))
                return None
            return self.indexer(dump_id)   # engine fns transition + kick
        if action == machine.A_PARSE:
            # no job: the parse runs INLINE in the thread that waits for the
            # indexes (await_indexes — the serial MAT worker itself; queueing
            # the parse behind it on the same queue would deadlock). The
            # PARSING state decide() just set is the record of intent.
            if self.parser_inline is None:
                self.machine_transition(
                    dump_id, "indexes", machine.IN_PROGRESS,
                    Comp(machine.ERROR, error="no local parser wired"))
            return None
        if action == machine.A_COMPACT:
            return self.compact(dump_id)
        return None

    def _wrap(self, dump_id, comp, stagefn):
        """Stage job scaffold: the stage returns the DONE Comp or raises;
        ERROR/CANCELLED transitions and the chaining kick live here, once,
        instead of in every stage body."""

        def run(job):
            try:
                done = stagefn(job)
            except core.Aborted:
                raise   # cancel/preempt already owns the state
            except Exception as e:   # noqa: BLE001 - the component's ERROR
                self.machine_transition(dump_id, comp, machine.IN_PROGRESS,
                                        Comp(machine.ERROR, error=str(e)))
                raise
            else:
                self.machine_transition(dump_id, comp, machine.IN_PROGRESS, done)
            finally:
                self.kick(dump_id)   # chain the next component

        return run

    def _live(self, dump_id):
        """Component names with an active stage, from the job registry plus
        the inline-parse marker (an analyze job running the parse inside
        itself). Jobs are process-lifetime, so after a restart this is
        empty and crashed in-progress stages simply re-enter."""
        out = set()
        rt = self._rts.get(dump_id)
        if rt is not None and rt.inline_indexes.is_set():
            out.add("indexes")
        try:
            jobs = self.jobs.list(limit=1000)
        except Exception:   # noqa: BLE001 - a registry without list() = nothing live
            return out
        for j in jobs:
            if j.dump_id != dump_id or j.state not in _ACTIVE:
                continue
            out |= _LIVE.get((j.kind, j.detail), set())
        return out

    def _mat_idle(self):
        try:
            return not any(j.kind in _MAT_KINDS and j.state in _ACTIVE
                           for j in self.jobs.list(limit=1000))
        except Exception:   # noqa: BLE001 - unknown queue state: don't compact
            return False

    def _remote_view(self, dump_id, m, live):
        """Query the remote sources — but only when some component could act
        on the answer (the GitHub REST API is rate-limited). An upstream
        failure is a view with error set (decide idles), never an empty
        result masquerading as "nothing published". Plans merge in
        remote_sources priority order (S3 first, _merge_plans): a source may
        have the dump while another has the late-published indexes. The
        stage fetcher is a SourceRouter over all sources: per-part-attempt
        resolution (owner by url) plus probes that switch a part to S3
        mid-download when the object appears late."""
        need = False
        if m.wanted:
            if m.dump.s not in machine.TERMINAL and "dump" not in live:
                need = True
            if m.data.s not in machine.TERMINAL and "data" not in live:
                need = True
        if (m.wanted or m.want_indexes) \
                and m.indexes.s not in machine.TERMINAL \
                and ("indexes" not in live or m.indexes.s == machine.PARSING):
            need = True
        if not need:
            return machine.RemoteView()
        plan = None
        err = None
        for s in self.remote_sources:
            try:
                p = s.download_plan(dump_id)
            except core.ApiError as e:
                err = err or e
                continue
            if p is not None:
                plan = p if plan is None else _merge_plans(plan, p)
        if plan is None:
            return machine.RemoteView(queried=True,
                                      error=str(err) if err else None)
        return machine.RemoteView(
            queried=True,
            hprof=bool(plan.hprof_parts),
            data=plan.data_bundle is not None,
            indexes=bool(plan.index_parts),
            plan=plan,
            source=SourceRouter(plan, self.remote_sources))

    # ------------------------------------------------------------ user actions

    def start_download(self, dump_id):
        """The explicit user action (download / retry / resume / "fetch or
        build data"): mark the dump wanted, reset ERROR/CANCELLED components,
        allow local MAT builds for this pass, reconcile synchronously and
        return the first stage job. Idempotent — kept .dl/ parts resume."""
        self._check_id(dump_id)
        fresh = not os.path.isdir(self._dir(dump_id))
        if fresh:
            # a fresh download: only when some source actually has the dump —
            # otherwise don't even create the dir. One lane being DOWN must
            # not hide another that has it (S3 offline, GitHub fine).
            has, err = False, None
            for s in self.remote_sources:
                try:
                    p = s.download_plan(dump_id)
                except core.ApiError as e:
                    err = err or e
                    continue
                if p is not None and p.hprof_parts:
                    has = True
                    break
            if not has:
                if err is not None:
                    raise err   # upstream errors surface truthfully (502)
                raise core.ApiError("not_found",
                                    f"no remote source has dump: {dump_id}", 404)
        os.makedirs(self._dir(dump_id), exist_ok=True)
        obs = self._observe(dump_id)

        def mut(meta):
            m = self._machine_of(dump_id, meta, obs)
            m.wanted = True
            for name in machine.COMPONENTS:
                c = m.comp(name)
                if c.s in (machine.ERROR, machine.CANCELLED):
                    c.reset()
            meta["machine"] = machine.machine_to(m)

        self.update_meta(dump_id, mut)
        # local MAT builds only on an explicit RETRY of an existing dump —
        # a fresh download never bootstraps (CI ships the data bundle minutes
        # later; the poll fills it)
        jobs = self.reconcile(dump_id, allow_local=not fresh)
        if jobs:
            return jobs[0]
        m = self._machine(dump_id)
        state, _ = machine.project(m, self._assembled(dump_id, m))
        if state is core.DumpState.READY:
            raise core.ApiError("bad_state", f"dump {dump_id} is already ready", 409)
        raise core.ApiError("not_found",
                            f"no remote source has anything for {dump_id} yet", 404)

    def cancel(self, dump_id):
        """User abort: in-progress components -> CANCELLED, running stages
        notice the abort flag at their next chunk/attempt boundary and exit.
        Once nothing is live, the partial download scratch (.dl/ parts,
        .untar/ staging, *.assembling) is PURGED — an explicit retry
        restarts the download from scratch (only ERROR/crash re-entry
        resumes kept parts)."""
        self.get(dump_id)   # 404 for unknown ids
        rt = self._rt(dump_id)
        rt.abort.set()
        obs = self._observe(dump_id)

        def mut(meta):
            m = self._machine_of(dump_id, meta, obs)
            for name in machine.COMPONENTS:
                if m.comp(name).s in machine.IN_PROGRESS:
                    setattr(m, name, Comp(machine.CANCELLED))
            meta["machine"] = machine.machine_to(m)

        self.update_meta(dump_id, mut)
        with rt.cond:
            rt.cond.notify_all()
        # wait for the aborted stages to exit before touching their scratch —
        # .dl/ is shared by the concurrent component stages, so this purge
        # (like the quiescent sweep) may only run once nothing is live
        deadline = time.monotonic() + 60
        while self._live(dump_id) and time.monotonic() < deadline:
            time.sleep(0.1)
        d = self._dir(dump_id)
        if os.path.isdir(d):
            shutil.rmtree(os.path.join(d, ".dl"), ignore_errors=True)
            shutil.rmtree(os.path.join(d, files.UNTAR), ignore_errors=True)
            try:
                os.remove(os.path.join(d, "daemon.hprof.assembling"))
            except OSError:
                pass
        self.kick(dump_id)

    def delete(self, dump_id):
        """Cancel any in-flight stages, wait for them to exit, remove the
        dump dir. (A delete under a running stage would let the next
        reconcile resurrect a half-written dir.)"""
        self.get(dump_id)
        self.cancel(dump_id)
        deadline = time.monotonic() + 60
        while self._live(dump_id) and time.monotonic() < deadline:
            time.sleep(0.1)
        shutil.rmtree(self._dir(dump_id))
        with self._rt_guard:
            self._rts.pop(dump_id, None)
        self.set_tags(dump_id, [])   # drop the user tags of the deleted dump

    def request_indexes(self, dump_id):
        """Engine hook: an analysis wants the MAT indexes. Sticky and
        persisted — the machine acquires them (remote download when
        published, else a local parse) and keeps driving across restarts."""
        self.get(dump_id)

        def mut(meta):
            m = self._machine_of(dump_id, meta)
            m.want_indexes = True
            if m.indexes.s in (machine.ERROR, machine.CANCELLED):
                m.indexes.reset()
            meta["machine"] = machine.machine_to(m)

        self.update_meta(dump_id, mut)
        self.kick(dump_id)

    def note_indexes_corrupt(self, dump_id):
        """Engine hook: a compacted index failed decompression (the restore
        deleted it) and the partial set was dropped — MAT must never run
        against it. Back to NEW; reconcile re-acquires (remote when
        published, else a local parse — want_indexes is set by analyze)."""
        def mut(meta):
            m = self._machine_of(dump_id, meta)
            m.indexes = Comp()
            meta["machine"] = machine.machine_to(m)

        self.update_meta(dump_id, mut)
        self.kick(dump_id)

    def await_indexes(self, dump_id, job):
        """Block the calling (serial MAT) worker until the indexes component
        reaches DONE; raise with its error on ERROR, core.Aborted on
        CANCELLED. A needed LOCAL parse runs inline right here: the caller
        IS the MAT worker, so queueing the parse behind it would deadlock —
        inline execution also preserves the one-JVM-at-a-time rule. Remote
        downloads run on the independent DOWNLOAD pool, so waiting for those
        is no deadlock."""
        rt = self._rt(dump_id)
        while True:
            m = self._machine(dump_id)
            s = m.indexes.s
            if s == machine.DONE:
                return
            if s == machine.ERROR:
                raise RuntimeError("MAT index acquisition failed: "
                                   f"{m.indexes.error}")
            if s == machine.CANCELLED:
                raise core.Aborted("index acquisition cancelled")
            if s == machine.PARSING and "indexes" not in self._live(dump_id):
                if self.parser_inline is None:
                    raise RuntimeError("no local parser wired")
                rt.inline_indexes.set()
                try:
                    self.parser_inline(dump_id, job)
                except core.Aborted:
                    pass   # preempted by a remote publication (or cancelled
                           # — the loop re-reads the state and acts on it)
                finally:
                    rt.inline_indexes.clear()
                continue
            with rt.cond:
                rt.cond.wait(2)

    def abort_event(self, dump_id):
        """The cooperative-cancel flag stages/MAT runs poll (core.Aborted)."""
        return self._rt(dump_id).abort

    # ------------------------------------------------------------ DumpSource

    def list(self):
        if not os.path.isdir(self.root):
            return []
        out = []
        for name in sorted(os.listdir(self.root)):
            if DUMP_RE.match(name) and ".." not in name \
                    and os.path.isdir(os.path.join(self.root, name)):
                out.append(self._info(name))
        return out

    def get(self, dump_id):
        self._check_id(dump_id)
        if not os.path.isdir(self._dir(dump_id)):
            raise core.ApiError("not_found", f"unknown dump: {dump_id}", 404)
        return self._info(dump_id)

    def dir_of(self, dump_id):
        """Absolute path of the dump dir, for ANY state of an existing dump —
        state gating is the caller's job: read queries enforce it themselves
        (MatQueryEngine._data_dir READY-only / _data_early busy-ok), the
        store-sanctioned bootstrap works while data is still missing."""
        self.get(dump_id)   # raises not_found for unknown ids
        return self._dir(dump_id)

    def _assembled(self, dump_id, m):
        """All known dump parts complete in .dl/ — the projection's
        ASSEMBLING badge (the gunzip tail after the last byte landed)."""
        parts = m.dump.parts
        if not parts:
            return False
        tmp = os.path.join(self._dir(dump_id), ".dl")
        for name, size in parts.items():
            fp = os.path.join(tmp, name)
            try:
                have = os.path.getsize(fp)
            except OSError:
                return False   # vanished mid-check (stage cleanup) — not assembled
            if isinstance(size, int) and have != size:
                return False
        return True

    def _dl_progress(self, dump_id):
        """The live dump-stage progress dict (done/total/speed/eta/parts and
        `source` — which remote the bytes currently come from) while its
        download job runs; None otherwise (the caller falls back to the
        disk-counted tuple)."""
        try:
            for j in self.jobs.list(limit=1000):
                if j.dump_id == dump_id and j.kind is core.JobKind.DOWNLOAD \
                        and j.detail == "dump" and j.state in _ACTIVE \
                        and isinstance(j.progress, dict):
                    return j.progress
        except Exception:   # noqa: BLE001 - a registry without list(): no progress
            pass
        return None

    def _info(self, dump_id):
        d = self._dir(dump_id)
        meta = self.read_meta(dump_id)
        m = self._machine_of(dump_id, meta)
        if not isinstance(meta.get("machine"), dict) and not m.wanted:
            return core.DumpInfo(
                id=dump_id, state=core.DumpState.FAILED,
                error="no recorded state — dump dir without state tracking "
                      "(or corruption); delete and re-download")
        state, error = machine.project(m, self._assembled(dump_id, m))
        progress = None
        if state in (core.DumpState.DOWNLOADING, core.DumpState.ASSEMBLING):
            progress = self._dl_progress(dump_id)
            if progress is None:
                tmp = os.path.join(d, ".dl")
                done = 0
                if os.path.isdir(tmp):
                    for f in os.listdir(tmp):
                        try:
                            done += os.path.getsize(os.path.join(tmp, f))
                        except OSError:
                            pass   # part renamed/dropped by a stage mid-listing
                progress = (done, sum(v for v in m.dump.parts.values()
                                      if isinstance(v, int)))
        hprof = os.path.join(d, "daemon.hprof")
        size = os.path.getsize(hprof) if os.path.exists(hprof) else \
            sum(v for v in m.dump.parts.values() if isinstance(v, int)) or None
        return core.DumpInfo(id=dump_id, state=state, size=size,
                             error=error if state is core.DumpState.FAILED else None,
                             progress=progress)

    # ------------------------------------------------------------ compact hold

    def hold_compact(self, dump_id, seconds=None):
        """Pin this dump's restored indexes against autocompact for
        `seconds` (<= COMPACT_HOLD_MAX) — for agents running a query
        session: every restore of a compacted set is minutes of zstd, so a
        back-to-back query burst should not pay it per call. Process-
        lifetime (an _Rt field): a restart silently drops the hold and the
        client re-locks. Re-locking extends. Returns the hold's expiry
        (wall-clock unix ts)."""
        self.get(dump_id)   # 404 for unknown ids
        if seconds is None:
            seconds = COMPACT_HOLD_MAX
        try:
            seconds = float(seconds)
        except (TypeError, ValueError):
            raise core.ApiError("bad_request", "seconds must be a number", 400)
        if not 0 < seconds <= COMPACT_HOLD_MAX:
            raise core.ApiError(
                "bad_request",
                f"seconds must be in (0, {COMPACT_HOLD_MAX}]", 400)
        until = time.time() + seconds
        self._rt(dump_id).compact_hold_until = until
        return until

    def release_compact(self, dump_id):
        """Drop the compact hold early (idempotent). Returns True when a
        live hold existed."""
        self.get(dump_id)   # 404 for unknown ids
        rt = self._rt(dump_id)
        held = rt.compact_hold_until > time.time()
        rt.compact_hold_until = 0.0
        return held

    def _compact_held(self, dump_id):
        rt = self._rts.get(dump_id)
        return rt is not None and rt.compact_hold_until > time.time()

    # ------------------------------------------------------------ compact

    def compact(self, dump_id):
        """(Re)compress the MAT indexes per LAYOUT.md's mtime convention.
        Housekeeping over a READY dump — the machine's indexes component
        stays DONE throughout (the restore side is MatRunner's job)."""
        info = self.get(dump_id)
        if info.state is not core.DumpState.READY:
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value} — compact needs ready", 409)
        return self.jobs.submit(core.JobKind.COMPACT, dump_id, "",
                                lambda job: self._compact(job, dump_id))

    def _compact(self, job, dump_id):
        log_ = lambda m: self.jobs.log(job, m)
        compact_dir(self._dir(dump_id), log=log_,
                    progress=lambda i, n: setattr(job, "progress",
                                                  {"done": i, "total": n}))
        job.progress = None
