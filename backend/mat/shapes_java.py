"""Managed Java subprocess execution for the shapes analysis pipeline."""
from __future__ import annotations

import os
import subprocess
import threading

from .. import core


def run_java(job, jobs, abort, args, what, *, jar, repo_root,
             default_xmx="2g", xmx=None):
    """Run one shapes-tool JVM, stream its log, and honor cooperative abort.

    The job log receives the complete (job-capped) output; a short local tail
    is retained for a useful exception.  No output is buffered wholesale.
    """
    command = ["java", f"-Xmx{xmx or default_xmx}", "-XX:ActiveProcessorCount=4",
               "-jar", jar] + list(args)
    jobs.log(job, f"  shapes {what}: {' '.join(args[:2])} ...")
    environment = os.environ.copy()
    process = subprocess.Popen(
        command, cwd=repo_root, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, encoding="utf-8",
        errors="replace", bufsize=1, env=environment)
    tail = []
    drain_error = []

    def drain():
        try:
            for line in process.stdout:
                line = line.rstrip("\n")
                tail.append(line)
                del tail[:-50]
                jobs.log(job, line)
        except Exception as exc:  # propagate logger/reader failures to the parent
            drain_error.append(exc)
            process.kill()

    reader = threading.Thread(target=drain, name=f"shapes-{what}-log", daemon=True)
    reader.start()
    try:
        while True:
            if abort is not None and abort.is_set():
                process.kill()
                process.wait()
                raise core.Aborted(f"shapes {what} aborted")
            try:
                process.wait(timeout=2)
                break
            except subprocess.TimeoutExpired:
                pass
    except BaseException:
        if process.poll() is None:
            process.kill()
            process.wait()
        raise
    finally:
        reader.join()
        process.stdout.close()
    if drain_error:
        raise RuntimeError(f"shapes {what} log reader failed") from drain_error[0]
    if process.returncode != 0:
        hint = ("\n  hint: the graph lives off-heap; a modest heap usually "
                "suffices — raise SHAPES_XMX_ANALYZE (e.g. 4g) only on "
                "OutOfMemoryError"
                if any("OutOfMemoryError" in line for line in tail) else "")
        raise RuntimeError(
            f"shapes {what} failed (rc={process.returncode}):\n"
            + "\n".join(tail[-20:]) + hint)
