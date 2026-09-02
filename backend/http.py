"""backend.http — thin HTTP adapter over core.App.

No logic: parse -> call core -> map result/error. Every endpoint returns either
the payload or {"error", "code"}; no exception ever kills a handler silently.
Binds 127.0.0.1 only. Static GETs serve the web/ frontend (index.html at /).
"""
from __future__ import annotations

import json
import logging
import os
import re
import traceback
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

from . import core
from .mat.extract import SAMPLES

log = logging.getLogger("backend.http")

DUMP_RE = re.compile(r"^[\w.-]+$")
CLASS_RE = re.compile(r"^[\w.$]+$")
MAX_BODY = 1 << 20  # 1 MiB cap on POST bodies

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTENT_TYPES = {".js": "text/javascript; charset=utf-8",
                 ".css": "text/css; charset=utf-8",
                 ".html": "text/html; charset=utf-8"}


def _ctype(path):
    return CONTENT_TYPES.get(os.path.splitext(path)[1], "application/octet-stream")


def _progress(p):
    if p is None:
        return None
    if isinstance(p, dict):
        return p
    return {"done": p[0], "total": p[1]}   # legacy (done, total) tuple


def _job_json(j: core.Job) -> dict:
    return {
        "id": j.id,
        "kind": j.kind.value,
        "dump": j.dump_id,
        "detail": j.detail,
        "state": j.state.value,
        "progress": _progress(j.progress),
        "log": j.log,
        "error": j.error,
    }


def _dump_json(d: core.DumpInfo) -> dict:
    return {
        "id": d.id,
        "state": d.state.value,
        "source": d.source,
        "size": d.size,
        "error": d.error,
        "progress": _progress(d.progress),
        "meta": d.meta,
    }


def _check_dump_id(dump_id: str) -> str:
    if not DUMP_RE.match(dump_id) or ".." in dump_id:
        raise core.ApiError("bad_id", f"invalid dump id: {dump_id!r}", 400)
    return dump_id


def _int(qs: dict, name: str, default: int) -> int:
    try:
        return int(qs.get(name, [default])[0])
    except (TypeError, ValueError):
        return default


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def app(self) -> core.App:
        return self.server.app

    # ---------------------------------------------------------- plumbing

    def log_message(self, fmt, *args):
        log.debug("%s %s", self.address_string(), fmt % args)

    def _json(self, status: int, payload) -> None:
        body = json.dumps(payload).encode()
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _body(self) -> dict:
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n > MAX_BODY:
            raise core.ApiError("too_large", f"body exceeds {MAX_BODY} bytes", 413)
        raw = self.rfile.read(n) if n else b"{}"
        try:
            data = json.loads(raw)
        except ValueError:
            raise core.ApiError("bad_request", "invalid JSON body", 400)
        return data if isinstance(data, dict) else {}

    # ---------------------------------------------------------- dispatch

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_DELETE(self):
        self._handle("DELETE")

    def _handle(self, method: str) -> None:
        try:
            self._route(method)
        except core.ApiError as e:
            self._json(e.status, {"error": str(e), "code": e.code})
        except Exception as e:  # never drop the connection on a bug
            log.error("unhandled error on %s %s\n%s", method, self.path,
                      traceback.format_exc())
            self._json(500, {"error": str(e), "code": "internal"})

    def _route(self, method: str) -> None:
        u = urlparse(self.path)
        qs = parse_qs(u.query)
        parts = [unquote(p) for p in u.path.split("/") if p]

        if parts[:1] == ["api"]:
            return self._api(method, parts[1:], qs)
        if method == "GET":
            return self._static(parts)
        raise core.ApiError("not_found", f"no route: {method} {u.path}", 404)

    # ---------------------------------------------------------- static

    def _static(self, parts: list) -> None:
        """Everything static comes from web/ (the ES-module frontend)."""
        rel = [p for p in parts if p] or ["index.html"]
        if any(not DUMP_RE.match(p) or ".." in p for p in rel):
            raise core.ApiError("not_found", "no such file", 404)
        path = os.path.join(REPO, "web", *rel)
        ctype = _ctype(path)
        try:
            with open(path, "rb") as f:
                body = f.read()
        except OSError:
            raise core.ApiError("not_found", "no such file", 404)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---------------------------------------------------------- api

    def _api(self, method: str, parts: list, qs: dict) -> None:
        app = self.app

        if parts == ["dumps"] and method == "GET":
            merged = {}
            for src in app.sources:
                try:
                    entries = src.list()
                except core.ApiError as e:
                    # a down remote must not hide the local dumps (offline use)
                    log.warning("dump source %s unavailable: %s", src.name, e)
                    continue
                for d in entries:
                    merged.setdefault(d.id, d)  # store first: local state wins
            tags = app.store.user_tags()
            dumps = list(merged.values())
            if tags:
                # copy, never mutate: sources cache their DumpInfo objects
                dumps = [replace(d, meta={**d.meta, "tags": tags.get(d.id, [])})
                         for d in dumps]
            return self._json(200, [_dump_json(d) for d in dumps])

        if parts == ["compare"] and method == "GET":
            a = qs.get("a", [""])[0]
            b = qs.get("b", [""])[0]
            return self._json(200, app.engine.compare(_check_dump_id(a),
                                                      _check_dump_id(b)))

        if parts == ["jobs"] and method == "GET":
            return self._json(200, [_job_json(j) for j in app.jobs.list()])

        if len(parts) == 2 and parts[0] == "jobs" and method == "GET":
            try:
                job_id = int(parts[1])
            except ValueError:
                raise core.ApiError("not_found", "no such job", 404)
            job = app.jobs.get(job_id)
            if job is None:
                raise core.ApiError("not_found", f"no such job: {job_id}", 404)
            return self._json(200, _job_json(job))

        if len(parts) == 3 and parts[0] == "jobs" and parts[2] == "cancel" \
                and method == "POST":
            try:
                job_id = int(parts[1])
            except ValueError:
                raise core.ApiError("not_found", "no such job", 404)
            job = app.jobs.get(job_id)
            if job is None:
                raise core.ApiError("not_found", f"no such job: {job_id}", 404)
            if job.state is core.JobState.QUEUED:
                app.jobs.cancel(job_id)
                return self._json(200, {"id": job_id, "cancelled": True})
            if job.state is core.JobState.RUNNING and job.dump_id:
                # cooperative abort via the dump's machine — the fn raises
                # core.Aborted and the job lands CANCELLED on its own
                app.store.cancel(job.dump_id)
                return self._json(200, {"id": job_id, "cancelled": True})
            raise core.ApiError("bad_state",
                                f"job {job_id} is {job.state.value} — not cancellable",
                                409)

        if len(parts) >= 2 and parts[0] == "dumps":
            dump_id = _check_dump_id(parts[1])
            action = parts[2] if len(parts) == 3 else None

            if action in ("download", "retry") and method == "POST":
                return self._json(200, _job_json(app.store.start_download(dump_id)))
            if action == "cancel" and method == "POST":
                app.store.cancel(dump_id)
                return self._json(200, {"id": dump_id, "cancelled": True})
            if action == "compact-hold" and method == "POST":
                until = app.store.hold_compact(
                    dump_id, self._body().get("seconds"))
                return self._json(200, {"id": dump_id, "held_until": until})
            if action == "compact-hold" and method == "DELETE":
                released = app.store.release_compact(dump_id)
                return self._json(200, {"id": dump_id, "released": released})
            if action == "tags" and method == "POST":
                tags = app.store.set_tags(dump_id, self._body().get("tags", []))
                return self._json(200, {"id": dump_id, "tags": tags})
            if action is None and method == "DELETE":
                app.store.delete(dump_id)
                self.send_response(204)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if action == "trees" and method == "GET":
                return self._json(200, app.engine.trees(dump_id))
            if action == "classes" and method == "GET":
                return self._json(200, app.engine.classes(
                    dump_id,
                    filter=qs.get("filter", [""])[0],
                    sort=qs.get("sort", ["-s"])[0],
                    page=_int(qs, "page", 0)))
            if action == "composition" and method == "GET":
                cls = qs.get("class", [""])[0]
                if not cls:
                    raise core.ApiError("bad_request", "class required", 400)
                res = app.engine.composition(dump_id, cls)
                if res is None:
                    return self._json(404, {"analyzed": False})
                return self._json(200, res)
            if action == "anatomy" and method == "GET":
                cls = qs.get("class", [""])[0]
                if not cls:
                    raise core.ApiError("bad_request", "class required", 400)
                samples = _int(qs, "samples", 0) or None
                res = app.engine.anatomy(dump_id, cls, samples=samples)
                if res is None:
                    return self._json(404, {"analyzed": False})
                return self._json(200, res)
            if action == "analyze" and method == "POST":
                body = self._body()
                cls = str(body.get("class", ""))
                if not CLASS_RE.match(cls):
                    raise core.ApiError("bad_request",
                                        f"invalid class name: {cls!r}", 400)
                try:
                    samples = int(body.get("samples", SAMPLES))
                except (TypeError, ValueError):
                    samples = SAMPLES
                samples = max(1, min(1024, samples))
                job = app.engine.analyze(dump_id, cls, samples=samples,
                                         with_anatomy=bool(body.get("anatomy", True)))
                return self._json(200, _job_json(job))

        raise core.ApiError("not_found",
                            f"no route: {method} /{'/'.join(parts)}", 404)


def serve(app: core.App, port: int) -> None:
    """Serve the app on 127.0.0.1:<port> (blocks)."""
    srv = ThreadingHTTPServer(("127.0.0.1", port), _Handler)
    srv.app = app
    log.info("serving on http://127.0.0.1:%d/", port)
    try:
        srv.serve_forever()
    finally:
        srv.server_close()
