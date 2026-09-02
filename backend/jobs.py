"""backend.jobs — InMemoryJobRegistry: the JobRegistry impl + executors.

Concurrency policy (pinned by core.JobRegistry): DOWNLOAD jobs run on a small
pool of threads; INDEX/ANALYZE/COMPACT share one serial worker (MAT JVMs are
heavy, -Xmx10g each — one at a time is the whole point).

One lock guards the jobs dict, the order list, state transitions and log
tails. get()/list() return snapshot copies so HTTP threads never iterate
live structures.
"""
from __future__ import annotations

import dataclasses
import queue
import threading
import traceback

from . import core

LOG_CAP = 200        # job.log keeps only this tail
_ERROR_TAIL = 2000   # chars of traceback kept in job.error


class InMemoryJobRegistry:
    """In-memory core.JobRegistry. Jobs live only for the process lifetime —
    that's all the UI needs (it polls /api/jobs)."""

    def __init__(self, download_workers: int = 2):
        self._lock = threading.Lock()
        self._jobs = {}            # id -> live Job (the fn's mutable handle)
        self._order = []           # ids in creation order
        self._seq = 0
        self._mat_q = queue.Queue()   # INDEX/ANALYZE/COMPACT — strictly serial
        self._dl_q = queue.Queue()    # DOWNLOAD — pool
        threading.Thread(target=self._worker, args=(self._mat_q,),
                         daemon=True, name="job-mat").start()
        for i in range(max(1, download_workers)):
            threading.Thread(target=self._worker, args=(self._dl_q,),
                             daemon=True, name=f"job-dl-{i}").start()

    # ------------------------------------------------------------- registry

    def submit(self, kind: core.JobKind, dump_id, detail: str, fn) -> core.Job:
        """Queue fn(job). An identical active (QUEUED/RUNNING) job — same
        (kind, dump_id, detail) — is returned instead of duplicated."""
        with self._lock:
            for j in self._jobs.values():
                if (j.kind is kind and j.dump_id == dump_id
                        and j.detail == detail
                        and j.state in (core.JobState.QUEUED, core.JobState.RUNNING)):
                    return j
            self._seq += 1
            job = core.Job(id=self._seq, kind=kind, dump_id=dump_id, detail=detail)
            self._jobs[job.id] = job
            self._order.append(job.id)
        q = self._dl_q if kind is core.JobKind.DOWNLOAD else self._mat_q
        q.put((job, fn))
        return job

    def get(self, job_id: int):
        with self._lock:
            job = self._jobs.get(job_id)
            return self._snapshot(job) if job is not None else None

    def cancel(self, job_id: int) -> bool:
        """QUEUED -> CANCELLED. A RUNNING job can't be killed here — its fn
        exits via the dump's abort flag (store.cancel) and lands CANCELLED."""
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None or job.state is not core.JobState.QUEUED:
                return False
            job.state = core.JobState.CANCELLED
            return True

    def list(self, limit: int = 30) -> list:
        """Most-recent-first snapshots."""
        with self._lock:
            ids = self._order[-limit:]
            return [self._snapshot(self._jobs[i]) for i in reversed(ids)]

    @staticmethod
    def _snapshot(job: core.Job) -> core.Job:
        """Shallow copy with a copied log tail — callers may hold and iterate
        it while the fn thread keeps mutating the live job."""
        return dataclasses.replace(job, log=list(job.log))

    def log(self, job: core.Job, line) -> None:
        """The only way producers (fns) append to job.log. Capped tail,
        guarded by the lock — reads from HTTP threads race with appends."""
        with self._lock:
            job.log.append(str(line))
            job.log[:] = job.log[-LOG_CAP:]

    # --------------------------------------------------------------- worker

    def _worker(self, q: queue.Queue) -> None:
        while True:
            job, fn = q.get()
            try:
                with self._lock:
                    if job.state is core.JobState.CANCELLED:
                        continue   # cancelled while queued — never runs
                    job.state = core.JobState.RUNNING
                try:
                    fn(job)
                except core.Aborted as e:
                    with self._lock:
                        job.state = core.JobState.CANCELLED
                    self.log(job, f"cancelled: {e}")
                except Exception as e:  # noqa: BLE001 - surfaced in the job
                    with self._lock:
                        job.state = core.JobState.FAILED
                        job.error = traceback.format_exc()[-_ERROR_TAIL:]
                    self.log(job, f"error: {e}")
                else:
                    with self._lock:
                        job.state = core.JobState.DONE
            finally:
                q.task_done()
