#!/usr/bin/env python3
"""Local UI server for heap analysis.

  python3 serve.py [--port 8321] [--source-repo owner/name] [--index-repo owner/name]

Serves the interactive report (template.html) and runs MAT queries on demand:
click a class in the UI -> composition / anatomy extracted live from the dump.
Binds 127.0.0.1 only; the MAT job queue is serial (MAT JVMs are heavy). Everything
is cached on disk as CSVs under dumps/<name>/data/, so re-requesting an analysis is
instant and survives restarts.

Remote tab: discovers the benchmark repo's `run-*` releases (daemon heap dumps) and
this repo's `idx-*` releases (MAT indexes pre-built on CI — see
.github/workflows/build-indexes.yml), downloads both on demand and bootstraps the
cheap analysis locally (histogram + dominators). All parts of a run (dump + indexes)
are fetched in parallel (HEAP_REPORT_DL_CONN, default 6 — the CDN throttles per
connection) on a small pool of download workers (HEAP_REPORT_DL_WORKERS, default 2),
so downloads never block the MAT queue or each other.

MAT indexes next to a dump may be stored compressed (*.index.zst — see compact.py /
matindex.py): they are restored on demand when an Analyze job runs and re-compressed
automatically once the queue has been idle for a bit.
"""
import argparse, glob, json, os, queue, re, shutil, subprocess, sys, threading, time, traceback
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

HERE = os.path.dirname(os.path.abspath(__file__))   # repo root
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "tools"))
import reportdata as rd
import analyze_dump as ad
import matindex
import ghremote

PAGE = 200
CLASS_RE = re.compile(r"^[\w.$\[\]]+$")
DUMP_RE = re.compile(r"^[\w.-]+$")

SOURCE_REPO = os.environ.get("HEAP_REPORT_SOURCE_REPO", "kroune/feature-module-3000")
INDEX_REPO = os.environ.get("HEAP_REPORT_INDEX_REPO", "kroune/heap-report")

AUTOCOMPACT_DELAY = int(os.environ.get("HEAP_REPORT_AUTOCOMPACT_DELAY", "90"))
REMOTE_TTL = int(os.environ.get("HEAP_REPORT_REMOTE_TTL", "60"))
_autocompact_enabled = True   # --no-autocompact disables
_compact_timer = None

_jobs = {}
_jobs_order = []
_job_seq = [0]
_jq = queue.Queue()      # MAT analysis jobs (serial — MAT JVMs are heavy)
_dlq = queue.Queue()     # download+bootstrap jobs (serial — big I/O, polite to GitHub)

_remote_cache = {"ts": 0, "runs": None}


def _log(job, msg):
    job["log"].append(str(msg))
    job["log"][:] = job["log"][-200:]


def _do_autocompact(dump_dir):
    """Re-compress MAT indexes after an analysis job, once the queue has been
    idle for AUTOCOMPACT_DELAY seconds. Only maintains dumps the user already
    compacted (matindex.has_compacted) — never compacts a dump on its own."""
    if not _jq.empty() or any(j["status"] in ("queued", "running") for j in _jobs.values()):
        return
    if not matindex.has_compacted(dump_dir):
        return
    try:
        matindex.compact(dump_dir, log=lambda m: print(m, flush=True),
                         should_stop=lambda: not _jq.empty())
    except Exception:
        traceback.print_exc()


def _schedule_compact(dump_dir):
    global _compact_timer
    if not _autocompact_enabled or not matindex.has_compacted(dump_dir):
        return
    if _compact_timer:
        _compact_timer.cancel()
    _compact_timer = threading.Timer(AUTOCOMPACT_DELAY, _do_autocompact, args=(dump_dir,))
    _compact_timer.daemon = True
    _compact_timer.start()


def _alloc_key(data, full):
    """Stable short key for a class, collision-free against meta."""
    meta = ad.load_meta(data)
    classes = meta.setdefault("classes", {})
    for k, v in classes.items():
        if v == full:
            return k
    base = re.sub(r"[^A-Za-z0-9_]", "_", full)[-60:] or "cls"
    key, i = base, 2
    while key in classes:
        key = f"{base}_{i}"
        i += 1
    return key


def _worker():
    while True:
        job = _jq.get()
        job["status"] = "running"
        job["started"] = time.time()
        try:
            data = job["data"]
            key = _alloc_key(data, job["cls"])
            job["key"] = key
            _log(job, f"analyzing {job['cls']} (key={key}, samples={job['samples']}, anatomy={job['anatomy']})")
            res = ad.analyze_class(job["hprof"], data, key, job["cls"],
                                   samples=job["samples"], anatomy=job["anatomy"],
                                   log=lambda m: _log(job, m))
            job["result"] = res
            if "error" in res:
                job["status"] = "failed"
                _log(job, "error: " + res["error"])
            else:
                job["status"] = "done"
            rd.invalidate(data)
        except Exception:
            job["status"] = "failed"
            _log(job, traceback.format_exc()[-1500:])
        job["ended"] = time.time()
        _jq.task_done()
        _schedule_compact(os.path.dirname(job["data"].rstrip("/")))


# ---------------------------------------------------------------- downloads

DL_WORKERS = int(os.environ.get("HEAP_REPORT_DL_WORKERS", "2"))  # concurrent run downloads
DL_CONN = int(os.environ.get("HEAP_REPORT_DL_CONN", "6"))        # connections per download
DL_RETRIES = int(os.environ.get("HEAP_REPORT_DL_RETRIES", "3"))  # attempts per part


def _fetch(entry, job, counter, lock):
    """One asset -> one file (atomic via .tmp rename). Shared progress counter.
    Stalled sockets retry the part (HEAP_REPORT_DL_RETRIES); an existing .tmp is
    resumed with an HTTP Range request, so even mid-part progress survives
    restarts and network loss. A server that ignores Range (200) restarts the part."""
    name, url, size, dst = entry
    tmp = dst + ".tmp"
    for attempt in range(1, DL_RETRIES + 1):
        have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
        if have >= size and os.path.exists(tmp):
            os.remove(tmp)   # stale/oversized partial — start over
            have = 0
        try:
            headers = {"User-Agent": "heap-report"}
            if have:
                headers["Range"] = f"bytes={have}-"
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=120) as resp:
                if have and resp.status != 206:
                    with lock:   # Range ignored — full re-download; fix the counter
                        counter[0] -= have
                    have = 0
                with open(tmp, "ab" if have else "wb") as f:
                    while True:
                        chunk = resp.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
                        with lock:
                            counter[0] += len(chunk)
                            job["progress"] = {"stage": "download", "bytes": counter[0],
                                               "total": job["_total"]}
            if os.path.getsize(tmp) != size:
                raise RuntimeError(f"short file: {os.path.getsize(tmp)}/{size} bytes")
            os.replace(tmp, dst)
            return
        except Exception as ex:   # noqa: BLE001 - retried / reported by caller
            if attempt == DL_RETRIES:
                raise
            wait = 5 * attempt * attempt
            with lock:
                job["log"].append(f"  {name}: {ex} — retry {attempt + 1}/{DL_RETRIES} in {wait}s")
            time.sleep(wait)


def _download_files(entries, job, log):
    """All parts (dump + indexes) fetched concurrently — the CDN throttles per
    connection (~5 MB/s), so N connections multiply the throughput. Parts already
    fully on disk (from a previous failed attempt) are skipped, so a re-run only
    fetches what's missing."""
    todo = []
    skipped = 0
    for e in entries:
        if os.path.exists(e[3]) and os.path.getsize(e[3]) == e[2]:
            skipped += e[2]
        else:
            partial = e[3] + ".tmp"
            if os.path.exists(partial):   # mid-part progress resumes via Range
                skipped += os.path.getsize(partial)
            todo.append(e)
    counter, lock = [skipped], threading.Lock()
    if skipped:
        log(f"  reusing {skipped / 1e9:.2f} GB of parts from the previous attempt")
    sem = threading.Semaphore(DL_CONN)
    errors = []

    def work(e):
        with sem:
            try:
                _fetch(e, job, counter, lock)
                log(f"  downloaded {e[0]}")
            except Exception as ex:   # noqa: BLE001 - re-raised on the main thread
                errors.append((e[0], ex))

    ts = [threading.Thread(target=work, args=(e,)) for e in todo]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    job["progress"] = None
    if errors:
        raise RuntimeError(f"download failed: {errors[0][0]}: {errors[0][1]}")


def _assemble(files, argv, stdout_path, job, stage):
    """Concatenate local part files into a subprocess' stdin (gunzip for the dump,
    tar for the indexes/data bundle) and let it stream out the result."""
    out = open(stdout_path, "wb") if stdout_path else subprocess.DEVNULL
    proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=out)
    try:
        for fp in files:
            _log(job, f"  {stage}: {os.path.basename(fp)}")
            with open(fp, "rb") as src:
                shutil.copyfileobj(src, proc.stdin, 1 << 20)
        proc.stdin.close()
        rc = proc.wait()
    finally:
        if stdout_path:
            out.close()
        if proc.poll() is None:
            proc.kill()
    if rc != 0:
        raise RuntimeError(f"{' '.join(argv)} exited {rc}")


def _merge_legacy_dl(dump_dir, tmp):
    """Adopt parts left behind by an older server version (.dl-<pid> dirs), so a
    restarted download reuses them instead of starting over."""
    for d in glob.glob(os.path.join(dump_dir, ".dl-*")):
        if not os.path.isdir(d):
            continue
        os.makedirs(tmp, exist_ok=True)
        for f in os.listdir(d):
            if f.endswith(".tmp"):
                continue
            src = os.path.join(d, f)
            dst = os.path.join(tmp, f)
            if not os.path.exists(dst) and os.path.isfile(src):
                os.replace(src, dst)
        shutil.rmtree(d, ignore_errors=True)


def _run_download(job):
    tag = job["tag"]
    log = lambda m: _log(job, m)
    dump_dir = os.path.join(rd.REPORT_ROOT, tag)
    os.makedirs(dump_dir, exist_ok=True)
    hprof = os.path.join(dump_dir, "daemon.hprof")
    data = os.path.join(dump_dir, "data")
    tmp = os.path.join(dump_dir, ".dl")
    _merge_legacy_dl(dump_dir, tmp)
    os.makedirs(tmp, exist_ok=True)

    raws, zsts = matindex.raws_zsts(dump_dir)
    data_ok = os.path.exists(os.path.join(data, "dominator_by_class.csv")) \
        and os.path.exists(os.path.join(data, "histogram.csv"))
    tparts_all, dentry, _manifest = ghremote.index_urls(INDEX_REPO, tag)
    tparts = [] if (raws or zsts) else tparts_all

    # phase 1: the tiny data bundle (histogram + dominators + meta, a few MB) —
    # the full overview UI (classes/treemap/compare) works right after this,
    # while the heavy assets keep downloading in the background.
    if not data_ok and dentry:
        job["_total"] = dentry[2]
        try:
            _download_files([(dentry[0], dentry[1], dentry[2], os.path.join(tmp, dentry[0]))],
                            job, log)
            _assemble([os.path.join(tmp, dentry[0])], ["tar", "-xz", "-C", dump_dir],
                      None, job, "data")
            os.remove(os.path.join(tmp, dentry[0]))
            rd.invalidate(data)
            data_ok = True
            log("  data: overview ready (classes/treemap/compare) — heavy download continues")
        except Exception as e:   # noqa: BLE001 - fall through to the local bootstrap
            log(f"  data bundle failed ({e}); will bootstrap locally after the download")
            data_ok = False

    # phase 2: the heap dump + pre-built MAT indexes (all parts in parallel)
    dparts = None
    if not os.path.exists(hprof):
        dparts = ghremote.dump_urls(SOURCE_REPO, tag)
        if not dparts:
            raise RuntimeError(f"release {tag} in {SOURCE_REPO} has no daemon.hprof.gz")
    entries = [(n, u, s, os.path.join(tmp, n)) for n, u, s in (dparts or []) + tparts]
    if entries:
        os.makedirs(tmp, exist_ok=True)
        job["_total"] = sum(s for _, _, s, _ in entries)
        _download_files(entries, job, log)
        if dparts:
            out = hprof + f".part{os.getpid()}"
            try:
                _assemble([os.path.join(tmp, n) for n, _, _ in dparts],
                          ["gzip", "-dc"], out, job, "gunzip")
                os.replace(out, hprof)
            finally:
                if os.path.exists(out):
                    os.remove(out)
            log(f"  dump: {os.path.getsize(hprof) / 1e9:.1f} GB -> {hprof}")
        if tparts:
            _assemble([os.path.join(tmp, n) for n, _, _ in tparts],
                      ["tar", "-x", "-C", dump_dir], None, job, "untar")
            log(f"  indexes: unpacked {sum(s for _, _, s in tparts) / 1e9:.1f} GB "
                "(compacted *.index.zst)")
        shutil.rmtree(tmp, ignore_errors=True)   # success only — failures keep parts
    if not raws and not zsts and not tparts_all:
        log("  indexes: no idx release — bootstrap will run the full MAT parse "
            "locally (slow, ~40 min)")

    # phase 3: local bootstrap — only needed when the release had no data bundle
    if not data_ok:
        ad.bootstrap(hprof, tag, log=log)
    rd.invalidate(data)
    log("  ready")
    return {"downloaded": tag}



def _dl_worker():
    while True:
        job = _dlq.get()
        job["status"] = "running"
        job["started"] = time.time()
        try:
            job["result"] = _run_download(job)
            job["status"] = "done"
        except Exception as e:   # noqa: BLE001 - surfaced in the job log
            job["status"] = "failed"
            _log(job, f"error: {e}")
            _log(job, traceback.format_exc()[-1000:])
        job["ended"] = time.time()
        _dlq.task_done()
        _remote_cache["ts"] = 0


# ---------------------------------------------------------------- job registry

def _new_job(kind, **kw):
    _job_seq[0] += 1
    job = {"id": _job_seq[0], "kind": kind, "status": "queued", "log": [],
           "created": time.time(), "started": None, "ended": None,
           "key": None, "progress": None, "samples": None, "anatomy": False}
    job.update(kw)
    _jobs[job["id"]] = job
    _jobs_order.append(job["id"])
    return job


def _submit(dump, data, hprof, cls, samples, anatomy):
    for j in _jobs.values():
        if j["kind"] == "analyze" and \
                (j["dump"], j["cls"], j["samples"], j["anatomy"]) == (dump, cls, samples, anatomy) \
                and j["status"] in ("queued", "running"):
            return j, False
    job = _new_job("analyze", dump=dump, data=data, hprof=hprof, cls=cls,
                   samples=samples, anatomy=anatomy)
    _jq.put(job)
    return job, True


def _submit_download(tag):
    for j in _jobs.values():
        if j["kind"] == "download" and j["dump"] == tag and j["status"] in ("queued", "running"):
            return j, False
    job = _new_job("download", dump=tag, tag=tag, cls=f"download {tag}")
    _dlq.put(job)
    return job, True


def _job_json(j):
    return {"id": j["id"], "kind": j["kind"], "dump": j["dump"], "cls": j["cls"],
            "samples": j["samples"], "anatomy": j["anatomy"], "status": j["status"],
            "key": j["key"], "progress": j.get("progress"),
            "log": j["log"][-40:], "created": j["created"], "started": j["started"],
            "ended": j["ended"], "result": j.get("result")}


# ---------------------------------------------------------------- remote runs

def _local_status(tag):
    """ready = data + hprof + indexes on disk · data = overview only (heavy part
    missing, e.g. still downloading) · downloaded = hprof only · None = not here."""
    d = os.path.join(rd.REPORT_ROOT, tag)
    if not os.path.isdir(d):
        return None
    has_data = rd.data_dir_of(tag) is not None
    raws, zsts = matindex.raws_zsts(d)
    has_hprof = os.path.exists(os.path.join(d, "daemon.hprof"))
    if has_data and has_hprof and (raws or zsts):
        return "ready"
    if has_data:
        return "data"
    if has_hprof:
        return "downloaded"
    return None


def _remote_runs(fresh=False):
    now = time.time()
    if not fresh and _remote_cache["runs"] is not None and now - _remote_cache["ts"] < REMOTE_TTL:
        return _remote_cache["runs"]
    runs = ghremote.remote_runs(SOURCE_REPO, INDEX_REPO)
    for r in runs:
        r["local"] = _local_status(r["tag"])
        j = next((j for j in _jobs.values()
                  if j["kind"] == "download" and j["dump"] == r["tag"]
                  and j["status"] in ("queued", "running")), None)
        r["job"] = _job_json(j) if j else None
    _remote_cache.update(ts=now, runs=runs)
    return runs


class Handler(BaseHTTPRequestHandler):
    server_version = "heap-report/3"

    def log_message(self, fmt, *args):
        pass

    # ---------------- helpers ----------------
    def _send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, separators=(",", ":")).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _data(self, dump):
        if not DUMP_RE.match(dump) or ".." in dump:
            return None
        return rd.data_dir_of(dump)

    def _hprof(self, data):
        """The hprof lives inside the dump dir (dumps/<name>/), next to data/ and
        the MAT index files."""
        dump_dir = os.path.dirname(data.rstrip("/"))
        meta = rd.load_meta(data)
        p = os.path.join(dump_dir, meta.get("dump", ""))
        if meta.get("dump") and os.path.exists(p):
            return p
        hits = glob.glob(os.path.join(dump_dir, "*.hprof"))
        return hits[0] if hits else None

    # ---------------- routes ----------------
    def do_GET(self):
        u = urlparse(self.path)
        path = unquote(u.path)
        q = parse_qs(u.query)
        if path == "/":
            with open(os.path.join(HERE, "template.html")) as f:
                return self._send(200, f.read(), "text/html; charset=utf-8")
        m = re.match(r"^/js/([\w.-]+\.js)$", path)
        if m:
            p = os.path.join(HERE, "js", m.group(1))
            if os.path.isfile(p):
                with open(p, "rb") as f:
                    return self._send(200, f.read(), "text/javascript; charset=utf-8")
            return self._send(404, {"error": "not found"})
        if path == "/api/dumps":
            return self._send(200, rd.list_dumps())
        if path == "/api/remote":
            try:
                return self._send(200, _remote_runs(fresh=q.get("fresh") == ["1"]))
            except Exception as e:   # noqa: BLE001 - GitHub outages shouldn't 500 the UI
                return self._send(502, {"error": f"github: {e}"})
        if path == "/api/jobs":
            ids = _jobs_order[-30:]
            return self._send(200, [_job_json(_jobs[i]) for i in reversed(ids)])
        m = re.match(r"^/api/jobs/(\d+)$", path)
        if m:
            j = _jobs.get(int(m.group(1)))
            return self._send(200, _job_json(j)) if j else self._send(404, {"error": "no such job"})
        if path == "/api/compare":
            old, new = q.get("old", [""])[0], q.get("new", [""])[0]
            dold, dnew = self._data(old), self._data(new)
            if not dold or not dnew:
                return self._send(400, {"error": "unknown dump"})
            return self._send(200, rd.compare_payload(dold, dnew))
        m = re.match(r"^/api/([\w.-]+)/(\w+)(?:/(.*))?$", path)
        if m:
            dump, cmd, rest = m.group(1), m.group(2), m.group(3)
            data = self._data(dump)
            if not data:
                return self._send(404, {"error": "unknown dump"})
            if cmd == "trees":
                return self._send(200, {"stats": rd.stats(data), "trees": rd.trees(data)})
            if cmd == "classes":
                return self._classes(data, q)
            if cmd == "composition" and rest:
                full = unquote(rest)
                c = rd.composition(data, full)
                return self._send(200, c) if c else self._send(404, {"analyzed": False})
            if cmd == "anatomy" and rest:
                full = unquote(rest)
                samples = q.get("samples", [None])[0]
                a = rd.anatomy(data, full, samples=int(samples) if samples and samples.isdigit() else None)
                return self._send(200, a) if a else self._send(404, {"analyzed": False})
            if cmd == "anat2" and rest:
                full = unquote(rest)
                samples = q.get("samples", [None])[0]
                a = rd.anat2(data, full, samples=int(samples) if samples and samples.isdigit() else None)
                return self._send(200, a) if a else self._send(404, {"analyzed": False})
        return self._send(404, {"error": "not found"})

    def _classes(self, data, q):
        filt = q.get("filter", [""])[0].strip().lower()
        sort = q.get("sort", ["-s"])[0]
        page = max(0, int(q.get("page", ["0"])[0]) if q.get("page", ["0"])[0].isdigit() else 0)
        rows = rd.class_table(data)
        if filt:
            rows = [r for r in rows if filt in r["disp"].lower()]
        keymap = {
            "s": lambda r: r["s"], "c": lambda r: r["c"], "pi": lambda r: r["pi"],
            "r": lambda r: (r["r"] is not None, r["r"] or 0), "name": lambda r: r["disp"].lower(),
        }
        rev = sort.startswith("-")
        key = keymap.get(sort.lstrip("-"), keymap["s"])
        if sort.lstrip("-") == "r":   # unanalyzed (r=None) always last
            rows.sort(key=lambda r: (r["r"] is None, -(r["r"] or 0)))
        else:
            rows.sort(key=key, reverse=rev)
        total = len(rows)
        start = page * PAGE
        return self._send(200, {"rows": rows[start:start + PAGE], "total": total,
                                "page": page, "pages": max(1, (total + PAGE - 1) // PAGE)})

    def do_POST(self):
        u = urlparse(self.path)
        path = unquote(u.path)
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except json.JSONDecodeError:
            return self._send(400, {"error": "bad json"})
        if path == "/api/remote/download":
            tag = body.get("tag", "")
            if not ghremote.RUN_RE.match(tag):
                return self._send(400, {"error": "bad tag"})
            if _local_status(tag) == "ready":
                return self._send(200, {"already": True, "tag": tag})
            job, _created = _submit_download(tag)
            return self._send(200, _job_json(job))
        m = re.match(r"^/api/([\w.-]+)/analyze$", path)
        if not m:
            return self._send(404, {"error": "not found"})
        dump = m.group(1)
        data = self._data(dump)
        if not data:
            return self._send(404, {"error": "unknown dump"})
        cls = body.get("class", "")
        if not CLASS_RE.match(cls):
            return self._send(400, {"error": "bad class name"})
        known = {r[0] for r in rd.load_hist(data)}
        if cls not in known:
            return self._send(404, {"error": "class not in histogram"})
        hprof = self._hprof(data)
        if not hprof:
            return self._send(400, {"error": "hprof not found in the dump dir"})
        samples = body.get("samples", 32)
        samples = max(1, min(1024, int(samples))) if str(samples).isdigit() else 32
        anatomy = bool(body.get("anatomy", True))
        job, created = _submit(dump, data, hprof, cls, samples, anatomy)
        return self._send(200 if created else 200, _job_json(job))


def main():
    global _autocompact_enabled, SOURCE_REPO, INDEX_REPO
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8321)
    ap.add_argument("--source-repo", default=SOURCE_REPO,
                    help="repo that publishes run-* heap-dump releases")
    ap.add_argument("--index-repo", default=INDEX_REPO,
                    help="repo that publishes idx-* MAT index releases")
    ap.add_argument("--no-autocompact", action="store_true",
                    help="do not re-compress MAT indexes after analysis jobs go idle")
    a = ap.parse_args()
    _autocompact_enabled = not a.no_autocompact
    SOURCE_REPO, INDEX_REPO = a.source_repo, a.index_repo
    os.makedirs(rd.REPORT_ROOT, exist_ok=True)
    if not os.path.exists(ad.MAT):
        print(f"WARNING: MAT not found at {ad.MAT} — it will be downloaded on first use "
              "(or run: python3 tools/get_mat.py)", file=sys.stderr)
    threading.Thread(target=_worker, daemon=True).start()
    for _ in range(DL_WORKERS):
        threading.Thread(target=_dl_worker, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    print(f"heap-report UI: http://127.0.0.1:{a.port}/  (Ctrl-C to stop)")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
