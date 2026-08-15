"""backend.localstore — FsDumpStore: the filesystem-backed LocalDumpStore.

The single writer for everything under dumps/<id>/: meta.json state, .dl/
download parts, assembly (gzip/tar), index compaction. On-disk contract:
LAYOUT.md; state machine: core.DumpState.
"""
import fcntl, glob, json, os, re, shutil, subprocess, tempfile, threading, time

from . import core

DUMP_RE = re.compile(r"^[\w.-]+$")

DL_CONN = int(os.environ.get("HEAP_REPORT_DL_CONN", "6"))       # connections per download
DL_RETRIES = int(os.environ.get("HEAP_REPORT_DL_RETRIES", "3"))  # attempts per part
ASSEMBLE_TIMEOUT = int(os.environ.get("HEAP_REPORT_ASSEMBLE_TIMEOUT", "7200"))

ZSTD = os.environ.get("ZSTD", "zstd")
LEVEL = int(os.environ.get("MATINDEX_LEVEL", "3"))     # zstd level for index compaction
THREADS = int(os.environ.get("MATINDEX_THREADS", "4"))
MARKER = "INDEXES-COMPACTED.txt"

MARKER_TEXT = """\
The MAT index files in this directory are stored compressed (*.index.zst).
MAT itself needs them raw; they are restored automatically when a query runs
and re-compressed when the analysis session goes idle.

Do NOT run ParseHeapDump.sh directly against the .hprof while compacted —
MAT would mistake the missing indexes for an unparsed dump and re-parse
everything.
"""

_STATES = {s.value: s for s in core.DumpState}
_BUSY = (core.DumpState.DOWNLOADING, core.DumpState.ASSEMBLING, core.DumpState.INDEXING)


def _mtime(path):
    return os.stat(path).st_mtime_ns


def raws_zsts(dump_dir):
    raws = sorted(glob.glob(os.path.join(dump_dir, "*.index")))
    zsts = sorted(glob.glob(os.path.join(dump_dir, "*.index.zst")))
    return raws, zsts


def parts_complete(tmp, parts):
    """All parts fully downloaded in .dl (matching sizes when known)."""
    return bool(parts) and all(
        os.path.exists(os.path.join(tmp, p.name))
        and (p.size is None or os.path.getsize(os.path.join(tmp, p.name)) == p.size)
        for p in parts)


def _flock(dump_dir, name):
    f = open(os.path.join(dump_dir, name), "a")
    fcntl.flock(f, fcntl.LOCK_EX)
    return f


def _zstd(args):
    cmd = ["nice", "-n", "10", ZSTD] + args
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed:\n{r.stderr[-500:]}")


def _compress_one(raw):
    zst = raw + ".zst"
    tmp = f"{zst}.tmp{os.getpid()}"
    try:
        _zstd([f"-{LEVEL}", f"-T{THREADS}", "-q", "-f", "-o", tmp, "--", raw])
        _zstd(["-t", "-q", "--", tmp])   # frame checksum verify before dropping the raw
        os.replace(tmp, zst)
        os.utime(zst, ns=(_mtime(raw), _mtime(raw)))
        os.remove(raw)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return os.path.getsize(zst)


def compact_dir(d, log=lambda m: None, progress=None):
    """(Re)compress the MAT indexes of one dump dir. Mtime convention: a raw
    *.index whose mtime matches its .zst is untouched since compaction and is
    just dropped; anything else is re-compressed (zstd, checksum-verified
    before the raw is removed). Standalone — CI uses this directly."""
    raws, _ = raws_zsts(d)
    archived = dropped = 0
    if raws:
        lf = _flock(d, ".matindex.lock")   # guards compact vs restore across processes
        try:
            for i, raw in enumerate(raws):
                if progress:
                    progress(i, len(raws))
                zst = raw + ".zst"
                if os.path.exists(zst) and _mtime(zst) == _mtime(raw):
                    dropped += os.path.getsize(raw)
                    os.remove(raw)   # unchanged since archived — the .zst is still valid
                    continue
                log(f"  zstd -{LEVEL} {os.path.basename(raw)} "
                    f"({os.path.getsize(raw) / 1e9:.2f} GB) ...")
                archived += _compress_one(raw)
            with open(os.path.join(d, MARKER), "w") as f:
                f.write(MARKER_TEXT)
        finally:
            lf.close()
    log(f"  compact: {archived / 1e9:.2f} GB archived, "
        f"{dropped / 1e9:.2f} GB unchanged raw dropped")
    return archived, dropped


def has_compacted(dump_dir):
    return bool(glob.glob(os.path.join(dump_dir, "*.index.zst"))) or \
        os.path.exists(os.path.join(dump_dir, MARKER))


class FsDumpStore:
    name = "local"

    def __init__(self, root, jobs, remote_sources):
        self.root = os.path.abspath(root)
        self.jobs = jobs
        self.remote_sources = list(remote_sources)
        self.indexer = None   # wired by the kernel to the MAT engine's bootstrap
        self._meta_locks = {}
        self._meta_guard = threading.Lock()

    def init(self):
        os.makedirs(self.root, exist_ok=True)

    # ------------------------------------------------------------ meta / state

    def _dir(self, dump_id):
        return os.path.join(self.root, dump_id)

    def _check_id(self, dump_id):
        if not dump_id or not DUMP_RE.match(dump_id) or ".." in dump_id:
            raise core.ApiError("bad_id", f"invalid dump id: {dump_id!r}")

    def _meta_lock(self, dump_id):
        with self._meta_guard:
            return self._meta_locks.setdefault(dump_id, threading.Lock())

    def _flock(self, dump_dir, name):
        f = open(os.path.join(dump_dir, name), "a")
        fcntl.flock(f, fcntl.LOCK_EX)
        return f

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
        with self._meta_lock(dump_id):     # threads
            lf = self._flock(d, ".meta.lock")   # processes
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

    def _set_state(self, dump_id, state, error=None, **extra):
        def m(meta):
            meta["state"] = state.value
            meta["error"] = error
            meta.update(extra)
        return self.update_meta(dump_id, m)

    def _info(self, dump_id):
        d = self._dir(dump_id)
        meta = self.read_meta(dump_id)
        st = meta.get("state")
        state = _STATES.get(st) if isinstance(st, str) else None
        error = meta.get("error")
        if state is None:
            dl = os.path.join(d, ".dl")
            if os.path.isdir(dl):
                state = core.DumpState.DOWNLOADING   # kept parts resume — normal operation
            else:
                state = core.DumpState.FAILED
                error = error or "no recorded state — dump dir without state tracking (or corruption); delete and re-download"
        progress = None
        if state is core.DumpState.DOWNLOADING and os.path.isdir(os.path.join(d, ".dl")):
            done = sum(os.path.getsize(os.path.join(d, ".dl", f))
                       for f in os.listdir(os.path.join(d, ".dl")))
            progress = (done, meta.get("dl_total") or 0)
        hprof = os.path.join(d, "daemon.hprof")
        size = os.path.getsize(hprof) if os.path.exists(hprof) else meta.get("dl_total")
        return core.DumpInfo(id=dump_id, state=state, size=size,
                             error=error if state is core.DumpState.FAILED else None,
                             progress=progress)

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
        """READY and INDEXING only: INDEXING is allowed so the store-sanctioned
        bootstrap job can work in the dir. READ QUERIES must enforce READY
        themselves (MatQueryEngine._data_dir does)."""
        info = self.get(dump_id)
        if info.state not in (core.DumpState.READY, core.DumpState.INDEXING):
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value}, need ready/indexing", 409)
        return self._dir(dump_id)

    # ------------------------------------------------------------ download

    def start_download(self, dump_id):
        self._check_id(dump_id)
        if os.path.isdir(self._dir(dump_id)):
            st = self._info(dump_id).state
            if st is core.DumpState.READY:
                raise core.ApiError("bad_state", f"dump {dump_id} is already ready", 409)
            if st is core.DumpState.INDEXING:
                # a previous bootstrap died or the server restarted mid-index;
                # re-trigger it (the registry dedups a live INDEX job) — the
                # remote source is not needed for this, it may even be offline
                if self.indexer is None:
                    raise core.ApiError("bad_state", f"dump {dump_id} is indexing", 409)
                return self.indexer(dump_id)
            # FAILED / DOWNLOADING / ASSEMBLING fall through to a remote
            # download: .dl parts resume, assembly is idempotent and re-runs
            # (the registry dedups a live DOWNLOAD job, so a truly-active
            # ASSEMBLING is safe)
        plan = source = None
        for s in self.remote_sources:
            p = s.download_plan(dump_id)
            if p is not None:
                plan, source = p, s
                break
        if plan is None:
            raise core.ApiError("not_found", f"no remote source has dump: {dump_id}", 404)
        os.makedirs(self._dir(dump_id), exist_ok=True)
        total = sum(p.size or 0 for p in
                    ([plan.data_bundle] if plan.data_bundle else [])
                    + list(plan.hprof_parts) + list(plan.index_parts))
        self._set_state(dump_id, core.DumpState.DOWNLOADING, dl_total=total or None)
        return self.jobs.submit(core.JobKind.DOWNLOAD, dump_id, "",
                                lambda job: self._run_download(job, dump_id, source, plan))

    def _run_download(self, job, dump_id, source, plan):
        log = lambda m: self.jobs.log(job, m)
        d = self._dir(dump_id)
        hprof = os.path.join(d, "daemon.hprof")
        data = os.path.join(d, "data")
        tmp = os.path.join(d, ".dl")
        os.makedirs(tmp, exist_ok=True)
        try:
            # phase 1: the tiny data bundle — the overview UI works right after this
            data_ok = os.path.exists(os.path.join(data, "histogram.csv")) \
                and os.path.exists(os.path.join(data, "dominator_by_class.csv"))
            if not data_ok and plan.data_bundle is not None:
                try:
                    self._fetch_all([plan.data_bundle], source, tmp, job, log)
                    bp = os.path.join(tmp, plan.data_bundle.name)
                    self._assemble([bp], ["tar", "-xz", "-C", d], None, job, "data")
                    os.remove(bp)
                    data_ok = True
                    log("  data: overview ready (classes/treemap/compare) — heavy download continues")
                except Exception as e:   # noqa: BLE001 - fall through to the local bootstrap
                    log(f"  data bundle failed ({e}); will bootstrap locally after the download")

            # phase 2: the heap dump + pre-built MAT indexes (all parts in parallel)
            raws, zsts = raws_zsts(d)
            dparts = list(plan.hprof_parts) if not os.path.exists(hprof) else []
            # Re-drive the index tar unless it verifiably completed: extracted
            # indexes alone prove nothing (a crash mid-untar leaves a partial
            # set); parts still in .dl prove the untar never finished (it is
            # rmtree'd only after success). tar -x overwrites, so re-running
            # is always safe.
            idx_redrive = parts_complete(tmp, plan.index_parts)
            tparts = [] if (raws or zsts) and not idx_redrive else list(plan.index_parts)
            if not os.path.exists(hprof) and not dparts:
                raise RuntimeError(f"download plan for {dump_id} has no daemon.hprof parts")
            if dparts or tparts:
                self._fetch_all(dparts + tparts, source, tmp, job, log)
                self._set_state(dump_id, core.DumpState.ASSEMBLING)
                if dparts:
                    out = hprof + f".part{os.getpid()}"
                    try:
                        self._assemble([os.path.join(tmp, p.name) for p in dparts],
                                       ["gzip", "-dc"], out, job, "gunzip")
                        os.replace(out, hprof)
                    finally:
                        if os.path.exists(out):
                            os.remove(out)
                    log(f"  dump: {os.path.getsize(hprof) / 1e9:.1f} GB -> {hprof}")
                if tparts:
                    self._assemble([os.path.join(tmp, p.name) for p in tparts],
                                   ["tar", "-x", "-C", d], None, job, "untar")
                    ok, why = self._indexes_complete(d, plan.manifest)
                    if not ok:
                        raise RuntimeError(f"index set incomplete vs manifest: {why}")
                    log(f"  indexes: unpacked and verified ({why})")
                shutil.rmtree(tmp, ignore_errors=True)   # success only — failures keep parts
            elif not (raws or zsts):
                log("  indexes: none published — bootstrap will run the full MAT parse "
                    "locally (slow, ~40 min)")
        except Exception as e:
            self._set_state(dump_id, core.DumpState.FAILED, error=str(e))
            raise

        raws, zsts = raws_zsts(d)
        data_ok = os.path.exists(os.path.join(data, "histogram.csv")) \
            and os.path.exists(os.path.join(data, "dominator_by_class.csv"))
        if os.path.exists(hprof) and data_ok and (raws or zsts):
            self._set_state(dump_id, core.DumpState.READY)
            log("  ready")
        else:
            why = []
            if not data_ok:
                why.append("no data bundle")
            if not (raws or zsts):
                why.append("no usable indexes")
            self._set_state(dump_id, core.DumpState.INDEXING)
            log(f"  indexing locally ({', '.join(why)})")
            if self.indexer:
                self.indexer(dump_id)

    def _fetch_all(self, parts, source, tmp, job, log):
        """All parts fetched concurrently (the CDN throttles per connection).
        Completed parts from a previous attempt are skipped, .tmp partials
        resume from their current size."""
        todo, skipped = [], 0
        for p in parts:
            dst = os.path.join(tmp, p.name)
            if p.size is not None and os.path.exists(dst) and os.path.getsize(dst) == p.size:
                skipped += p.size
            else:
                partial = dst + ".tmp"
                if os.path.exists(partial):
                    skipped += os.path.getsize(partial)
                todo.append(p)
        total = sum(p.size or 0 for p in parts)
        counter, lock = [skipped], threading.Lock()
        if skipped:
            log(f"  reusing {skipped / 1e9:.2f} GB of parts from the previous attempt")
        sem = threading.Semaphore(DL_CONN)
        errors = []

        def work(p):
            with sem:
                try:
                    self._fetch_part(source, p, os.path.join(tmp, p.name),
                                     job, counter, total, lock)
                    log(f"  downloaded {p.name}")
                except Exception as ex:   # noqa: BLE001 - re-raised on the main thread
                    errors.append((p.name, ex))

        ts = [threading.Thread(target=work, args=(p,)) for p in todo]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        job.progress = None
        if errors:
            raise RuntimeError(f"download failed: {errors[0][0]}: {errors[0][1]}")

    def _fetch_part(self, source, part, dst, job, counter, total, lock):
        """One part -> one file (atomic via .tmp rename). Retries re-call
        source.fetch with the resumed offset; the source itself never retries."""
        tmp = dst + ".tmp"
        for attempt in range(1, DL_RETRIES + 1):
            have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
            if part.size is not None and have >= part.size and os.path.exists(tmp):
                os.remove(tmp)   # stale/oversized partial — start over
                with lock:
                    counter[0] -= have
                have = 0
            try:
                n = have
                with open(tmp, "ab" if have else "wb") as f:
                    for chunk in source.fetch(part, offset=have):
                        if not chunk:
                            continue
                        f.write(chunk)
                        n += len(chunk)
                        with lock:
                            counter[0] += len(chunk)
                            job.progress = (counter[0], total)
                if part.size is not None and n != part.size:
                    raise RuntimeError(f"short file: {n}/{part.size} bytes")
                os.replace(tmp, dst)
                return
            except Exception as ex:   # noqa: BLE001 - retried / reported by caller
                if attempt == DL_RETRIES:
                    raise
                wait = 5 * attempt * attempt
                self.jobs.log(job, f"  {part.name}: {ex} — retry {attempt + 1}/{DL_RETRIES} in {wait}s")
                time.sleep(wait)

    def _assemble(self, files, argv, stdout_path, job, stage):
        """Concatenate local part files into a subprocess' stdin (gunzip for the
        dump, tar for the indexes/data bundle) and let it stream out the result.
        Bounded by ASSEMBLE_TIMEOUT; failures carry the process' stderr tail."""
        out = open(stdout_path, "wb") if stdout_path else subprocess.DEVNULL
        efd = tempfile.TemporaryFile()
        proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=out, stderr=efd)
        rc = None
        try:
            try:
                for fp in files:
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
            raise RuntimeError(f"{' '.join(argv)} exited {rc}: {tail}")
        efd.close()

    def _indexes_complete(self, dump_dir, manifest):
        """(ok, detail) — the extracted *.index.zst set must match the release
        manifest; the mere presence of some .zst files means nothing. Legacy
        releases without a manifest fall back to a presence check."""
        files = manifest.get("files") if isinstance(manifest, dict) else None
        if not files:
            n = len(glob.glob(os.path.join(dump_dir, "*.index.zst")))
            return (n > 0), f"{n} index archives (no manifest to check against)"
        items = files.items() if isinstance(files, dict) else ((n, None) for n in files)
        missing, bad = [], []
        for name, size in items:
            p = os.path.join(dump_dir, name)
            if not os.path.exists(p):
                missing.append(name)
            elif isinstance(size, int) and os.path.getsize(p) != size:
                bad.append(name)
        if missing or bad:
            return False, f"missing={missing or '-'} size-mismatch={bad or '-'}"
        return True, f"{len(files)} index files match the manifest"

    # ------------------------------------------------------------ delete

    def delete(self, dump_id):
        info = self.get(dump_id)
        if info.state in _BUSY:
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value} — cannot delete now", 409)
        shutil.rmtree(self._dir(dump_id))

    # ------------------------------------------------------------ compact

    def compact(self, dump_id):
        info = self.get(dump_id)
        if info.state is not core.DumpState.READY:
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value} — compact needs ready", 409)
        return self.jobs.submit(core.JobKind.COMPACT, dump_id, "",
                                lambda job: self._compact(job, dump_id))

    def list_compactable(self):
        """READY dumps with raw indexes lying over a compacted archive — the
        kernel's autocompact timer maintains exactly these."""
        out = []
        for info in self.list():
            if info.state is not core.DumpState.READY:
                continue
            raws, _ = raws_zsts(self._dir(info.id))
            if raws and has_compacted(self._dir(info.id)):
                out.append(info.id)
        return out

    def _compact(self, job, dump_id):
        log = lambda m: self.jobs.log(job, m)
        compact_dir(self._dir(dump_id), log=log,
                    progress=lambda i, n: setattr(job, "progress", (i, n)))
        job.progress = None
