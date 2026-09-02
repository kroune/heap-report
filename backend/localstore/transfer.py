"""backend.localstore.transfer — the download + assembly machinery.

Everything that moves bytes from a RemoteDumpSource into a dump dir:
parallel part fetch with Range resume and per-part retries, streaming
assembly (PartPipe feeds parts to gzip/tar in order as they land —
decompression overlaps the transfer), staged untar (.untar/ + manifest
validation before anything is trusted).

Trust rules (learned the hard way, do not relax):
  - Nothing lands at its final path directly: the hprof assembles into
    `daemon.hprof.assembling` (renamed on success; gzip verifies the stream
    CRC), tars untar into .untar/ and move into place (atomic per file,
    under .matindex.lock EXCLUSIVE) only after manifest validation.
  - An extracted index set is trusted ONLY via manifest validation, never
    via presence; size-mismatched members are deleted and re-fetched.
  - Assembly rejecting size-complete parts (bad gzip/tar stream, or a
    valid-but-wrong archive — all-zeros is an EMPTY tar to GNU tar) raises
    AssemblyError carrying the parts: the caller drops them so the next
    attempt re-downloads instead of re-failing forever.

Cancellation: fetch loops take an `abort` threading.Event and raise
core.Aborted — cooperative, at chunk/attempt boundaries.
"""
import glob
import os
import shutil
import subprocess
import tempfile
import threading
import time
from collections import deque

from .. import core
from . import files

DL_CONN = int(os.environ.get("HEAP_REPORT_DL_CONN", "6"))        # connections per download
DL_RETRIES = int(os.environ.get("HEAP_REPORT_DL_RETRIES", "3"))  # attempts per part
ASSEMBLE_TIMEOUT = int(os.environ.get("HEAP_REPORT_ASSEMBLE_TIMEOUT", "7200"))


class AssemblyError(RuntimeError):
    """The assembly subprocess (gzip/tar) rejected input whose parts were all
    size-verified complete — the downloaded bytes are corrupt (or the release
    is). Carries .parts so the caller can drop them: keeping them would make
    every later attempt fail identically. Distinct from feed errors/timeouts
    (local hiccups — parts are kept and resumed)."""

    def __init__(self, msg, parts=()):
        super().__init__(msg)
        self.parts = list(parts)


class SourceRouter:
    """Per-part-ATTEMPT fetch-source resolution (mid-download lane switching).

    The plan's parts carry source-specific urls; the first source (priority
    order — the store's remote_sources) owning a part's url fetches it
    (`owns()`, default True for generic HTTP fetchers like GitHub). Before
    that, probe-capable sources (`offer(prefix, part)`, e.g. S3) are asked
    for the same object: a hit switches this and all later range-requests of
    the part to the faster lane mid-download. Parts already complete are
    never re-fetched (the resume logic in fetch_all), and probes cache their
    answers (hit or miss), so a missing object costs at most one HEAD per
    probe TTL while a late upload is still picked up quickly. Switching is
    only by exact object identity (same name + size) — a differently-segmented
    copy (GitHub parts vs one S3 object) never matches and is left alone.
    """

    def __init__(self, plan, sources):
        self.plan = plan
        self.sources = list(sources)
        self.probes = [s for s in self.sources if hasattr(s, "offer")]
        self._prefix = {p: plan.dump_id for p in plan.hprof_parts}
        idx = f"idx-{plan.dump_id}"
        for p in plan.index_parts:
            self._prefix[p] = idx
        if plan.data_bundle is not None:
            self._prefix[plan.data_bundle] = idx

    def resolve(self, part):
        """(source, part) to fetch from — the probe winner, else the part's
        owner. Called per fetch attempt, so a late S3 upload is picked up by
        the retry loop."""
        prefix = self._prefix.get(part)
        if prefix:
            for s in self.probes:
                owns = getattr(s, "owns", None)
                if owns is not None and owns(part):
                    return s, part   # already this probe's own url
                try:
                    alt = s.offer(prefix, part)
                except Exception:   # noqa: BLE001 - a broken probe must never
                    alt = None      # break the download: the fallback owns it
                if alt is not None:
                    return s, alt
        for s in self.sources:
            owns = getattr(s, "owns", None)
            if owns is None or owns(part):
                return s, part
        return self.sources[-1], part

    def fetch(self, part, offset=0):
        src, p = self.resolve(part)
        return src.fetch(p, offset)


class DlProgress:
    """Throttled job.progress reporter for downloads: a per-part byte counter
    with a speed/ETA window, plus the assembly tail (bytes fed into
    gzip/tar). Publishes dict payloads (see core.Job.progress): download stage
    carries done/total bytes, speed, eta, assembly overlap and per-part
    states; the assemble stage reports fed bytes."""

    def __init__(self, job):
        self.job = job
        self.lock = threading.Lock()
        self.stage = "download"
        self.parts = {}            # name -> {"have", "size", "done"}
        self.total = 0             # expected download bytes (known part sizes)
        self.asm_total = 0         # bytes to feed through assembly
        self.fed = 0               # assembly bytes so far
        self.source = None         # name of the remote source currently feeding
                                   # bytes ("s3"/"github"/…), None until the
                                   # first fetch — omitted from the payload then
        self._new = 0              # bytes downloaded in this attempt (speed base)
        self._samples = deque()    # (monotonic, _new) — ~10s speed window
        self._last_push = 0.0

    def planned(self, parts):
        with self.lock:
            for p in parts:
                self.parts.setdefault(p.name,
                                      {"have": 0, "size": p.size, "done": False})
                self.total += p.size or 0
                self.asm_total += p.size or 0

    def seed(self, name, have, done=False):
        """Bytes reused from a previous attempt: count towards done, not speed."""
        with self.lock:
            e = self.parts.setdefault(name, {"have": 0, "size": None, "done": False})
            e["have"] = have
            e["done"] = done
            self._push()

    def reset(self, name):   # stale partial discarded — starts over
        with self.lock:
            e = self.parts[name]
            e["have"] = 0
            e["done"] = False

    def add(self, name, n):
        with self.lock:
            e = self.parts.setdefault(name, {"have": 0, "size": None, "done": False})
            e["have"] += n
            self._new += n
            self._push()

    def part_done(self, name):
        with self.lock:
            self.parts[name]["done"] = True
            self._push(force=True)

    def fed_bytes(self, n):
        with self.lock:
            self.fed += n
            self._push()

    def set_stage(self, stage):
        with self.lock:
            self.stage = stage
            self._push(force=True)

    def set_source(self, name):
        """Which remote source is serving the current fetch (the SourceRouter
        may switch lanes mid-download)."""
        with self.lock:
            if name != self.source:
                self.source = name
                self._push(force=True)

    def flush(self):
        """Force a payload refresh — used after each fully-fed part so a
        download stall doesn't leave the assembly overlap stale."""
        with self.lock:
            self._push(force=True)

    def _push(self, force=False):
        now = time.monotonic()
        if not force and now - self._last_push < 0.4:
            return
        self._last_push = now
        src = {"source": self.source} if self.source else {}
        if self.stage == "download":
            done = sum(e["have"] for e in self.parts.values())
            self._samples.append((now, self._new))
            while self._samples and now - self._samples[0][0] > 10:
                self._samples.popleft()
            speed = eta = None
            if len(self._samples) >= 2:
                dt = self._samples[-1][0] - self._samples[0][0]
                if dt > 0.5:
                    speed = (self._samples[-1][1] - self._samples[0][1]) / dt
                    if speed and self.total > done:
                        eta = (self.total - done) / speed
            self.job.progress = {
                "stage": "download", "done": done, "total": self.total,
                "speed": speed, "eta": eta, **src,
                "asm": {"done": self.fed, "total": self.asm_total},
                "parts": [{"n": n, "have": e["have"], "size": e["size"],
                           "done": e["done"]} for n, e in self.parts.items()],
            }
        else:
            self.job.progress = {"stage": "assemble", "done": self.fed,
                                 "total": self.asm_total, **src}


class PartPipe:
    """Stream .dl part files into a subprocess' stdin in Part.index order AS
    THEY COMPLETE (downloads land concurrently) — decompression/untar overlaps
    the transfer instead of starting after it. finish() validates the exit
    status exactly like Transfer.assemble; a non-zero rc after size-complete
    input raises AssemblyError (the parts are bad — the caller drops them);
    abort() kills everything (other failure paths — completed parts stay in
    .dl for resume). The untar target is always a staging dir, never the
    dump dir."""

    def __init__(self, jobs, job, parts, tmp, argv, stdout_path, stage,
                 on_fed=None, on_part=None):
        self._jobs, self._job, self._argv = jobs, job, argv
        self._parts = parts
        self._on_fed = on_fed
        self._on_part = on_part
        self._out = open(stdout_path, "wb") if stdout_path else None
        self._efd = tempfile.TemporaryFile()
        self._proc = subprocess.Popen(
            argv, stdin=subprocess.PIPE,
            stdout=self._out if self._out else subprocess.DEVNULL,
            stderr=self._efd)
        self._deadline = time.monotonic() + ASSEMBLE_TIMEOUT
        self._aborted = threading.Event()
        self._error = None
        self._feeder = threading.Thread(target=self._feed, args=(parts, tmp),
                                        daemon=True, name=f"assemble-{stage}")
        self._feeder.start()

    def _feed(self, parts, tmp):
        try:
            for p in parts:
                fp = os.path.join(tmp, p.name)
                # a visible final file is complete: downloads rename atomically
                while not os.path.exists(fp) or \
                        (p.size is not None and os.path.getsize(fp) != p.size):
                    if self._aborted.is_set():
                        return
                    if time.monotonic() > self._deadline:
                        raise RuntimeError(f"timed out waiting for part {p.name}")
                    time.sleep(0.2)
                self._jobs.log(self._job, f"  assemble: {p.name}")
                with open(fp, "rb") as src:
                    while True:
                        chunk = src.read(1 << 20)
                        if not chunk:
                            break
                        self._proc.stdin.write(chunk)
                        if self._on_fed is not None:
                            self._on_fed(len(chunk))
                if self._on_part is not None:
                    self._on_part()
            self._proc.stdin.close()
        except (BrokenPipeError, ConnectionResetError):
            pass   # the reader died — rc + stderr in finish() carry the cause
        except Exception as e:   # noqa: BLE001 - re-raised by finish()
            self._error = e
            try:
                self._proc.stdin.close()   # EOF lets the proc exit; finish() reports
            except Exception:
                pass

    def finish(self):
        """Call only once ALL parts are complete (fetch returned)."""
        try:
            rc = self._proc.wait(timeout=max(1.0, self._deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            self.abort()
            raise RuntimeError(f"{' '.join(self._argv)} timed out after {ASSEMBLE_TIMEOUT}s")
        self._feeder.join(timeout=5)
        if self._error is not None:
            self.abort()
            raise RuntimeError(f"{' '.join(self._argv)} feed failed: {self._error}")
        if rc != 0:
            self._efd.seek(0)
            tail = self._efd.read()[-500:].decode(errors="replace").strip()
            self._close()
            raise AssemblyError(f"{' '.join(self._argv)} exited {rc}: {tail}",
                                self._parts)
        self._close()

    def abort(self):
        self._aborted.set()
        if self._proc.poll() is None:
            self._proc.kill()
        self._proc.wait()
        self._feeder.join(timeout=5)
        self._close()

    def _close(self):
        try:
            self._proc.stdin.close()
        except Exception:
            pass   # already closed by the feeder, or the proc is dead
        if self._out is not None:
            self._out.close()
        self._efd.close()


class Transfer:
    """The part-fetch / assembly / untar-validation primitives, shared by all
    stage executors. Owns no state beyond the job registry (for job logs)."""

    def __init__(self, jobs):
        self.jobs = jobs

    # ------------------------------------------------------------ fetch

    def fetch_all(self, parts, source, tmp, job, log, prog, abort=None):
        """All parts fetched concurrently (the CDN throttles per connection).
        Completed parts from a previous attempt are skipped, .tmp partials
        resume from their current size. `prog` (DlProgress) gets per-part
        seeds and per-chunk byte updates; the caller owns job.progress."""
        todo, skipped = [], 0
        for p in parts:
            prog.planned([p])
            dst = os.path.join(tmp, p.name)
            if p.size is not None and os.path.exists(dst) and os.path.getsize(dst) == p.size:
                skipped += p.size
                prog.seed(p.name, p.size, done=True)
            else:
                partial = dst + ".tmp"
                have = os.path.getsize(partial) if os.path.exists(partial) else 0
                if have:
                    skipped += have
                prog.seed(p.name, have)
                todo.append(p)
        if skipped:
            log(f"  reusing {skipped / 1e9:.2f} GB of parts from the previous attempt")
        sem = threading.Semaphore(DL_CONN)
        errors = []

        def work(p):
            try:
                self._fetch_part(source, p, os.path.join(tmp, p.name),
                                 job, sem, prog, abort)
                prog.part_done(p.name)
                log(f"  downloaded {p.name}")
            except Exception as ex:   # noqa: BLE001 - re-raised on the main thread
                errors.append((p.name, ex))

        ts = [threading.Thread(target=work, args=(p,)) for p in todo]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        if errors:
            if isinstance(errors[0][1], core.Aborted):
                raise errors[0][1]
            raise RuntimeError(f"download failed: {errors[0][0]}: {errors[0][1]}")

    def _fetch_part(self, source, part, dst, job, sem, prog, abort):
        """One part -> one file (atomic via .tmp rename). Retries re-resolve
        the source per attempt (a SourceRouter picks up a late S3 upload
        mid-download) and re-call fetch with the resumed offset; the source
        itself never retries. The connection slot (sem) is held only while
        bytes flow — backoff sleeps happen outside it so a retrying part
        doesn't idle a slot."""
        router = source if isinstance(source, SourceRouter) else None
        tmp = dst + ".tmp"
        for attempt in range(1, DL_RETRIES + 1):
            if abort is not None and abort.is_set():
                raise core.Aborted(f"{part.name}: cancelled")
            src, p = router.resolve(part) if router else (source, part)
            name = getattr(src, "name", "")
            if name:
                prog.set_source(name)
            have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
            if part.size is not None and have >= part.size and os.path.exists(tmp):
                os.remove(tmp)   # stale/oversized partial — start over
                prog.reset(part.name)
                have = 0
            try:
                with sem:
                    n = have
                    with open(tmp, "ab" if have else "wb") as f:
                        for chunk in src.fetch(p, offset=have):
                            if abort is not None and abort.is_set():
                                raise core.Aborted(f"{part.name}: cancelled")
                            if not chunk:
                                continue
                            f.write(chunk)
                            n += len(chunk)
                            prog.add(part.name, len(chunk))
                if part.size is not None and n != part.size:
                    raise RuntimeError(f"short file: {n}/{part.size} bytes")
                os.replace(tmp, dst)
                return
            except core.Aborted:
                raise
            except Exception as ex:   # noqa: BLE001 - retried / reported by caller
                if attempt == DL_RETRIES:
                    raise
                wait = 5 * attempt * attempt
                self.jobs.log(job, f"  {part.name}: {ex} — retry {attempt + 1}/{DL_RETRIES} in {wait}s")
                if abort is not None and abort.wait(wait):
                    raise core.Aborted(f"{part.name}: cancelled")
                elif abort is None:
                    time.sleep(wait)

    # ------------------------------------------------------------ assembly

    def assemble(self, files_, argv, stdout_path, job, stage):
        """Concatenate local part files into a subprocess' stdin (gunzip for
        the dump, tar for the data bundle) and let it stream out the result.
        Bounded by ASSEMBLE_TIMEOUT; a non-zero rc raises AssemblyError (the
        input parts are bad), other failures carry the process' stderr tail."""
        out = open(stdout_path, "wb") if stdout_path else subprocess.DEVNULL
        efd = tempfile.TemporaryFile()
        proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=out, stderr=efd)
        rc = None
        try:
            try:
                for fp in files_:
                    self.jobs.log(job, f"  {stage}: {os.path.basename(fp)}")
                    with open(fp, "rb") as src:
                        shutil.copyfileobj(src, proc.stdin, 1 << 20)
                proc.stdin.close()
            except (BrokenPipeError, ConnectionResetError):
                pass   # the reader died — rc + stderr below carry the cause
            rc = proc.wait(timeout=ASSEMBLE_TIMEOUT)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            raise RuntimeError(f"{' '.join(argv)} timed out after {ASSEMBLE_TIMEOUT}s")
        finally:
            if stdout_path:
                out.close()
            if proc.poll() is None:
                proc.kill()
        if rc != 0:
            efd.seek(0)
            tail = efd.read()[-500:].decode(errors="replace").strip()
            efd.close()
            raise AssemblyError(f"{' '.join(argv)} exited {rc}: {tail}")
        efd.close()

    # ------------------------------------------------------------ untar staging

    @staticmethod
    def stage_prepare(d, name):
        """Fresh per-component .untar/<name> staging dir: whatever a previous
        run left in one comes from an interrupted untar and is untrusted
        (partial) by definition. Components stage in SEPARATE subdirs — the
        data and indexes stages untar concurrently, a shared staging dir
        would let one wipe the other's half-extracted set."""
        staging = os.path.join(d, files.UNTAR, name)
        shutil.rmtree(staging, ignore_errors=True)
        os.makedirs(staging)
        return staging

    def commit_untar(self, d, staging, manifest, parts=()):
        """Validate the STAGED index set against the manifest, then move it
        into the dump dir (os.replace per member — atomic, same fs) holding
        .matindex.lock EXCLUSIVE: a concurrent restore/compact/MAT run must
        never see a half-moved set. A validation failure means the downloaded
        bytes were bad even though tar exited 0 (an all-zeros part is a valid
        EMPTY archive to GNU tar) — raised as AssemblyError carrying the parts
        so the caller drops them; keeping them would re-fail every later
        attempt identically. Returns the validation detail."""
        ok, why = self.indexes_complete(staging, manifest)
        if not ok:
            raise AssemblyError(f"index set incomplete vs manifest: {why}", parts)
        lf = files.flock(d, ".matindex.lock")
        try:
            for name in os.listdir(staging):
                os.replace(os.path.join(staging, name), os.path.join(d, name))
        finally:
            lf.close()
        shutil.rmtree(staging, ignore_errors=True)
        try:
            os.rmdir(os.path.dirname(staging))   # .untar itself, when empty
        except OSError:
            pass   # another component is staging concurrently
        return why

    @staticmethod
    def indexes_complete(dump_dir, manifest):
        """(ok, detail) — the extracted *.index.zst set must match the release
        manifest; the mere presence of some .zst files means nothing. Legacy
        releases without a manifest fall back to a presence check."""
        files_ = manifest.get("files") if isinstance(manifest, dict) else None
        if not files_:
            n = len(glob.glob(os.path.join(dump_dir, "*.index.zst")))
            return (n > 0), f"{n} index archives (no manifest to check against)"
        items = files_.items() if isinstance(files_, dict) else ((n, None) for n in files_)
        missing, bad = [], []
        for name, size in items:
            p = os.path.join(dump_dir, name)
            if not os.path.exists(p):
                missing.append(name)
            elif isinstance(size, int) and os.path.getsize(p) != size:
                bad.append(name)
        if missing or bad:
            return False, f"missing={missing or '-'} size-mismatch={bad or '-'}"
        return True, f"{len(files_)} index files match the manifest"

    def drop_invalid(self, d, manifest, log):
        """Delete extracted index files whose size contradicts the manifest —
        a truncated/corrupt artifact can never become valid and must not
        masquerade as part of a complete set. Returns the count dropped."""
        files_ = manifest.get("files") if isinstance(manifest, dict) else None
        if not isinstance(files_, dict):
            return 0
        n = 0
        for name, size in files_.items():
            p = os.path.join(d, name)
            if isinstance(size, int) and os.path.exists(p) \
                    and os.path.getsize(p) != size:
                log(f"  dropped corrupt {name} "
                    f"({os.path.getsize(p)} bytes, manifest says {size})")
                os.remove(p)
                n += 1
        return n

    @staticmethod
    def drop_parts(tmp, parts):
        """Delete .dl parts whose assembly REJECTED size-complete input
        (AssemblyError), or parts made redundant by an already-valid extracted
        set — keeping either would just re-fail or waste disk."""
        for p in parts or ():
            for fp in (os.path.join(tmp, p.name), os.path.join(tmp, p.name) + ".tmp"):
                if os.path.exists(fp):
                    os.remove(fp)
