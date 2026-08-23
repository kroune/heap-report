"""MatRunner.run contract tests with a stub ParseHeapDump.sh — no real MAT.

Pins the failure semantics that the real MAT only reveals at runtime:
an empty OQL result (MAT text page "did not yield any result", no CSV page)
returns None; a real query error raises with the report text; a missing
report zip raises with the process tail. MAT exits rc=0 in ALL of these
cases, so the report content — not the exit code — is the contract.

Plus restore_indexes' corruption contract: a .zst that fails decompression
is deleted and reported as CorruptIndexError (needs the real zstd binary)."""
import os
import shutil
import subprocess
import tempfile
import unittest

from backend.mat.extract import CorruptIndexError, MatRunner

STUB = r"""#!/usr/bin/env python3
import os, sys, zipfile
hprof = next(a for a in sys.argv[1:] if a.endswith(".hprof"))
sfx = next(a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("-filename_suffix="))
base = os.path.splitext(os.path.basename(hprof))[0]
z = os.path.join(os.path.dirname(hprof), f"{base}_{sfx}.zip")
mode = os.environ.get("STUB_MAT_MODE", "csv")
if mode == "nozip":
    print("some MAT output line")
    sys.exit(0)
with zipfile.ZipFile(z, "w") as zf:
    if mode == "csv":
        zf.writestr("pages/Query_Command2.csv", "a,b\n1,2\n")
    elif mode == "empty":
        zf.writestr("index.html",
                    "<html><body>Your Query did not yield any result.</body></html>")
    else:
        zf.writestr("index.html",
                    "<html><body>Problem reported: Lexical error at line 1</body></html>")
"""


class _Jobs:
    def __init__(self):
        self.lines = []

    def log(self, job, msg):
        self.lines.append(msg)


class TestMatRunnerRun(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        d = self.tmp.name
        self.hprof = os.path.join(d, "daemon.hprof")
        open(self.hprof, "w").close()
        self.outdir = os.path.join(d, "out")
        os.makedirs(self.outdir)
        stub = os.path.join(d, "ParseHeapDump.sh")
        with open(stub, "w") as f:
            f.write(STUB)
        os.chmod(stub, 0o755)
        os.environ["MAT_PARSE"] = stub
        self.addCleanup(lambda: os.environ.pop("MAT_PARSE", None))
        self.runner = MatRunner(_Jobs())

    def _run(self, mode, keep="out.csv"):
        os.environ["STUB_MAT_MODE"] = mode
        self.addCleanup(lambda: os.environ.pop("STUB_MAT_MODE", None))
        return self.runner.run(None, self.hprof, self.outdir, "t1",
                               'oql "SELECT s.@objectId FROM INSTANCEOF X s"', keep)

    def test_csv_result_is_moved_to_outdir(self):
        dst = self._run("csv")
        self.assertEqual(dst, os.path.join(self.outdir, "out.csv"))
        with open(dst) as f:
            self.assertEqual(f.read(), "a,b\n1,2\n")

    def test_empty_result_returns_none_and_writes_nothing(self):
        self.assertIsNone(self._run("empty"))
        self.assertFalse(os.path.exists(os.path.join(self.outdir, "out.csv")))

    def test_query_error_raises_with_report_text(self):
        with self.assertRaises(RuntimeError) as ctx:
            self._run("error")
        self.assertIn("Lexical error", str(ctx.exception))

    def test_missing_zip_raises_with_mat_output(self):
        with self.assertRaises(RuntimeError) as ctx:
            self._run("nozip")
        self.assertIn("some MAT output line", str(ctx.exception))

    def test_existing_dst_short_circuits(self):
        dst = os.path.join(self.outdir, "out.csv")
        with open(dst, "w") as f:
            f.write("cached\n")
        self.assertEqual(self._run("nozip"), dst)   # MAT never invoked
        with open(dst) as f:
            self.assertEqual(f.read(), "cached\n")


@unittest.skipUnless(shutil.which("zstd"), "needs the zstd binary")
class TestRestoreCorruptZst(unittest.TestCase):
    """restore_indexes against a corrupt compacted index (truncated download,
    disk rot): the file is deleted — it can never become valid — and reported
    as CorruptIndexError so the caller re-acquires the set instead of letting
    MAT run against a partial one."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.runner = MatRunner(_Jobs())

    def test_corrupt_zst_deleted_and_reported(self):
        d = self.tmp.name
        with open(os.path.join(d, "payload"), "wb") as f:
            f.write(b"hello-index")
        subprocess.run(["zstd", "-q", "-f", "-o",
                        os.path.join(d, "daemon.a2s.index.zst"),
                        os.path.join(d, "payload")], check=True)
        os.remove(os.path.join(d, "payload"))
        with open(os.path.join(d, "daemon.domOut.index.zst"), "wb") as f:
            f.write(b"not-a-zstd-frame")   # e.g. a truncated download tail
        with open(os.path.join(d, "daemon.a2s.index.tmp999"), "wb") as f:
            f.write(b"debris of a killed restore")
        with self.assertRaises(CorruptIndexError) as ctx:
            self.runner.restore_indexes(d, lambda m: None)
        self.assertIn("domOut", str(ctx.exception))
        self.assertFalse(os.path.exists(os.path.join(d, "daemon.domOut.index.zst")))
        with open(os.path.join(d, "daemon.a2s.index"), "rb") as f:
            self.assertEqual(f.read(), b"hello-index")   # valid sibling restored
        self.assertFalse(os.path.exists(os.path.join(d, "daemon.a2s.index.tmp999")))


if __name__ == "__main__":
    unittest.main()
