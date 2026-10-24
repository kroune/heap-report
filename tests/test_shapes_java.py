import io
import threading
import unittest
from unittest import mock

from backend import core
from backend.mat.shapes_java import run_java


class FakeProcess:
    def __init__(self, output="", returncode=0):
        self.stdout = io.StringIO(output)
        self.returncode = returncode
        self.killed = False

    def wait(self, timeout=None):
        return self.returncode

    def kill(self):
        self.killed = True
        self.returncode = -9

    def poll(self):
        return self.returncode


class TestShapesJava(unittest.TestCase):
    def run_with(self, process, abort=None):
        jobs = mock.Mock()
        with mock.patch("backend.mat.shapes_java.subprocess.Popen",
                        return_value=process) as popen:
            result = run_java(None, jobs, abort, ["analyze-grph", "--graph", "x"],
                              "analyze", jar="tool.jar", repo_root="/repo",
                              scc_merge_limit=1000000)
        return result, jobs, popen

    def test_success_streams_log_and_sets_scc_limit(self):
        process = FakeProcess("first\nsecond\n")
        _, jobs, popen = self.run_with(process)
        command = popen.call_args.args[0]
        self.assertEqual(["java", "-Xmx2g", "-XX:ActiveProcessorCount=4",
                          "-jar", "tool.jar", "analyze-grph", "--graph", "x"], command)
        self.assertEqual("1000000", popen.call_args.kwargs["env"]["SHAPES_MAX_SCC_MERGE"])
        self.assertTrue(popen.call_args.kwargs["stdout"] is not None)
        logged = [call.args[1] for call in jobs.log.call_args_list]
        self.assertIn("first", logged)
        self.assertIn("second", logged)
        self.assertTrue(process.stdout.closed)

    def test_nonzero_exit_includes_tail_and_oom_hint(self):
        process = FakeProcess("java.lang.OutOfMemoryError: Java heap space\n", 1)
        with self.assertRaises(RuntimeError) as caught:
            self.run_with(process)
        self.assertIn("OutOfMemoryError", str(caught.exception))
        self.assertIn("SHAPES_XMX_ANALYZE", str(caught.exception))

    def test_abort_kills_jvm_and_raises_aborted(self):
        process = FakeProcess()
        abort = threading.Event()
        abort.set()
        with self.assertRaises(core.Aborted):
            self.run_with(process, abort)
        self.assertTrue(process.killed)
        self.assertTrue(process.stdout.closed)


if __name__ == "__main__":
    unittest.main()
