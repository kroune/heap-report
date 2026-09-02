"""Job registry tests: cancellation semantics of InMemoryJobRegistry."""
import threading
import time
import unittest

from backend import core
from backend.jobs import InMemoryJobRegistry


def wait_state(jobs, job_id, states, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        j = jobs.get(job_id)
        if j.state in states:
            return j
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} never reached {states}: "
                         f"{jobs.get(job_id).state}")


class TestJobCancel(unittest.TestCase):
    def setUp(self):
        self.jobs = InMemoryJobRegistry(download_workers=1)

    def test_cancel_queued_job_never_runs(self):
        # block the single MAT worker so the second job stays QUEUED
        gate = threading.Event()
        first = self.jobs.submit(core.JobKind.INDEX, "run-a", "",
                                 lambda j: gate.wait(5))
        second = self.jobs.submit(core.JobKind.INDEX, "run-b", "",
                                  lambda j: j.log.append("ran"))
        wait_state(self.jobs, first.id, {core.JobState.RUNNING})
        self.assertTrue(self.jobs.cancel(second.id))
        self.assertIs(self.jobs.get(second.id).state, core.JobState.CANCELLED)
        gate.set()
        wait_state(self.jobs, first.id, {core.JobState.DONE})
        time.sleep(0.2)   # let the worker pick up the cancelled job
        self.assertIs(self.jobs.get(second.id).state, core.JobState.CANCELLED)
        self.assertEqual(self.jobs.get(second.id).log, [])   # fn never ran

    def test_cancel_rejects_non_queued(self):
        job = self.jobs.submit(core.JobKind.INDEX, "run-a", "", lambda j: None)
        wait_state(self.jobs, job.id, {core.JobState.DONE})
        self.assertFalse(self.jobs.cancel(job.id))          # terminal
        self.assertFalse(self.jobs.cancel(999))             # unknown

    def test_aborted_fn_lands_cancelled_not_failed(self):
        def fn(j):
            raise core.Aborted("cancelled")

        job = self.jobs.submit(core.JobKind.DOWNLOAD, "run-a", "", fn)
        j = wait_state(self.jobs, job.id, {core.JobState.CANCELLED})
        self.assertIsNone(j.error)


if __name__ == "__main__":
    unittest.main()
