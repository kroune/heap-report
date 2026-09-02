#!/usr/bin/env python3
"""S3 source (private SeaweedFS, same LAN as the server): the fast lane.

Mirrors the benchmark repo's `run-*` release assets, one prefix per tag —
`s3://heap-reports/<tag>/daemon.stripped.hprof.gz` (the FULL dump is
GitHub-only), `idx-<tag>/data.tar.gz` + `indexes.tar.zst` + `manifest.json` —
as SINGLE objects (no 2 GiB part-splitting on S3). Strictly preferred over
GitHub: the store queries it first (plans merge per component, S3 winning
what it has), and transfer's SourceRouter switches an in-flight GitHub
download to S3 mid-stream when an object appears late — per-part HEAD probe
(`offer()`), negative answers cached PROBE_TTL seconds so a missing object
doesn't cost a HEAD per chunk but a late upload is picked up quickly.

Stdlib only: AWS SigV4 signing with hmac/hashlib (path-style urls
`{endpoint}/{bucket}/{key}`, service s3, region us-east-1, UNSIGNED-PAYLOAD;
every request is signed on its own — each Range GET is its own request).
Operations: ListObjectsV2 (paginated full listing for discovery, per-prefix
for plans), HEAD (existence/size probe), GET with Range (streaming).

Credentials: `~/.aws/credentials` [default] profile. Endpoint resolution:
HEAP_REPORT_S3_ENDPOINT > `endpoint_url` in `~/.aws/config` [default] >
`https://s3.kroune.tech` — RKN throttles the Cloudflare front to KB/s from
Russia, so the user's machine points the config at a direct LAN NodePort
(plain http — the signer signs host+path, scheme-independent; CI uploads
run outside Russia and are unaffected). Bucket: HEAP_REPORT_S3_BUCKET.
Missing or unreadable credentials DISABLE the source (logged once): list()
is empty, download_plan()/offer() say None — GitHub keeps working, the
server never crashes.

Error semantics mirror github.py: transport failures raise
core.ApiError('upstream', 502); only a confirmed 404 (or a successful
listing without the object) means "we don't have it" — errors are never
swallowed into empty results (the listing cache serves stale rows on a
failed refresh, exactly like GitHubSource._runs).
"""
import configparser
import hashlib
import hmac
import json
import logging
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from urllib.parse import quote

from . import core
from .github import IDX_RE, RUN_RE, _dump_parts, _index_parts

log = logging.getLogger("backend.s3")

DEFAULT_ENDPOINT = "https://s3.kroune.tech"
BUCKET = os.environ.get("HEAP_REPORT_S3_BUCKET", "heap-reports")
REGION = "us-east-1"   # SeaweedFS ignores it; SigV4 still needs one
TTL = int(os.environ.get("HEAP_REPORT_REMOTE_TTL", "60"))
PROBE_TTL = int(os.environ.get("HEAP_REPORT_S3_PROBE_TTL", "45"))
NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"


def _aws_files(creds_path=None, config_path=None):
    """One configparser pass over ~/.aws/credentials + ~/.aws/config
    ([default] profile; a config's `endpoint_url` is an awscli extension —
    same key the aws CLI reads). Returns (access, secret, endpoint_url),
    each piece None when absent. Unreadable files -> all None (the caller
    disables the source)."""
    cp = configparser.ConfigParser()
    try:
        cp.read([creds_path or os.path.expanduser("~/.aws/credentials"),
                 config_path or os.path.expanduser("~/.aws/config")])
        if not cp.has_section("default"):
            return None, None, None
        get = lambda k: cp["default"].get(k)
        return get("aws_access_key_id"), get("aws_secret_access_key"), \
            get("endpoint_url")
    except Exception:
        return None, None, None


# ------------------------------------------------------------ SigV4 signing


def _qs(query):
    """Canonical query string: sorted, uri-encoded (SigV4 rules)."""
    return "&".join(f"{quote(k, safe='-_.~')}={quote(str(v), safe='-_.~')}"
                    for k, v in sorted(query))


def _signing_key(secret, date, region, service):
    k = ("AWS4" + secret).encode()
    for part in (date, region, service, "aws4_request"):
        k = hmac.new(k, part.encode(), hashlib.sha256).digest()
    return k


def _signed_request(method, host, path, query, access, secret,
                    region=REGION, service="s3", extra=None, now=None,
                    unsigned_payload=True):
    """SigV4 headers for one request (`path` already encoded, `query` a list
    of (name, value) pairs, `extra` additional signed headers e.g. range).
    unsigned_payload signs UNSIGNED-PAYLOAD (streaming GETs); False signs the
    empty-body hash instead (the AWS documentation test vector)."""
    now = now or datetime.now(timezone.utc)
    amzdate = now.strftime("%Y%m%dT%H%M%SZ")
    scope = f"{now:%Y%m%d}/{region}/{service}/aws4_request"
    payload_hash = "UNSIGNED-PAYLOAD" if unsigned_payload \
        else hashlib.sha256(b"").hexdigest()
    headers = {"host": host, "x-amz-date": amzdate}
    if unsigned_payload:
        headers["x-amz-content-sha256"] = payload_hash
    headers.update(extra or {})
    signed = ";".join(sorted(headers))
    canonical = "\n".join([method, path, _qs(query),
                           "".join(f"{k}:{headers[k]}\n" for k in sorted(headers)),
                           signed, payload_hash])
    sts = "\n".join(["AWS4-HMAC-SHA256", amzdate, scope,
                     hashlib.sha256(canonical.encode()).hexdigest()])
    sig = hmac.new(_signing_key(secret, now.strftime("%Y%m%d"), region, service),
                   sts.encode(), hashlib.sha256).hexdigest()
    out = {"x-amz-date": amzdate,
           "Authorization": f"AWS4-HMAC-SHA256 Credential={access}/{scope}, "
                            f"SignedHeaders={signed}, Signature={sig}"}
    if unsigned_payload:
        out["x-amz-content-sha256"] = payload_hash
    out.update(extra or {})
    return out


class S3Source:
    name = "s3"

    def __init__(self, endpoint=None, bucket=BUCKET, creds_file=None,
                 config_file=None):
        access, secret, cfg_endpoint = _aws_files(creds_file, config_file)
        # resolution: explicit arg > env > ~/.aws/config endpoint_url > default
        self.endpoint = (endpoint
                         or os.environ.get("HEAP_REPORT_S3_ENDPOINT")
                         or cfg_endpoint or DEFAULT_ENDPOINT).rstrip("/")
        self.bucket = bucket
        self._base = f"{self.endpoint}/{self.bucket}"
        self._host = urllib.parse.urlparse(self.endpoint).netloc
        self.enabled = bool(access and secret)
        if self.enabled:
            self._access, self._secret = access, secret
        else:
            log.warning("S3 source disabled: no usable credentials in %s",
                        creds_file or "~/.aws/credentials")
        self._lock = threading.Lock()
        self._runs_cache = None      # joined remote runs
        self._runs_at = 0.0
        self._probes = {}            # key -> (monotonic ts, Part | None)

    def init(self):
        """Warm the listing cache; tolerate failure (offline start must not crash)."""
        if not self.enabled:
            return
        try:
            self._runs()
        except Exception:
            pass

    # ------------------------------------------------------------ transport

    def _req(self, method, key="", query=(), extra=None, allow_404=False):
        """One signed request; returns the open response (caller reads/closes).
        None on a confirmed 404 with allow_404; any other failure raises
        core.ApiError('upstream', ..., status=502) — never an empty result."""
        path = f"/{self.bucket}" + ("/" + quote(key, safe="-_.~/") if key else "")
        hdrs = _signed_request(method, self._host, path, query,
                               self._access, self._secret, extra=extra)
        q = _qs(query)
        req = urllib.request.Request(
            f"{self.endpoint}{path}" + (f"?{q}" if q else ""),
            headers=hdrs, method=method)
        try:
            return urllib.request.urlopen(req, timeout=60)
        except urllib.error.HTTPError as e:
            e.close()
            if allow_404 and e.code == 404:
                return None
            raise core.ApiError("upstream",
                                f"S3 {method} {key or '/'}: HTTP {e.code}",
                                status=502)
        except Exception as e:
            raise core.ApiError("upstream", f"S3 {method} {key or '/'}: {e}",
                                status=502)

    def _url(self, key):
        return f"{self._base}/{quote(key, safe='-_.~/')}"

    def _objects(self, prefix=""):
        """All (key, size, last_modified) under prefix, paginated ListObjectsV2."""
        out, token = [], None
        while True:
            q = [("list-type", "2"), ("prefix", prefix)]
            if token:
                q.append(("continuation-token", token))
            resp = self._req("GET", query=q)
            try:
                root = ET.fromstring(resp.read())
            finally:
                resp.close()
            for c in root.findall(f"{NS}Contents"):
                out.append((c.findtext(f"{NS}Key") or "",
                            int(c.findtext(f"{NS}Size") or 0),
                            c.findtext(f"{NS}LastModified") or ""))
            if (root.findtext(f"{NS}IsTruncated") or "").lower() != "true":
                return out
            token = root.findtext(f"{NS}NextContinuationToken")
            if not token:
                return out   # truncated listing without a token: serve what we got

    def _get_json(self, key):
        resp = self._req("GET", key, allow_404=True)
        if resp is None:
            return {}
        try:
            return json.load(resp)
        finally:
            resp.close()

    def _assets(self, prefix):
        """A github-style asset map {name: {size, browser_download_url}} for
        one prefix — lets the shared release-layout helpers (_dump_parts /
        _index_parts) work unchanged."""
        return {key[len(prefix):]: {"size": size,
                                    "browser_download_url": self._url(key)}
                for key, size, _ in self._objects(prefix)
                if key[len(prefix):]}

    # ------------------------------------------------------------ discovery

    def _runs(self):
        """Run prefixes joined with their idx- builds, cached for TTL seconds.

        Same shape and same offline rule as GitHubSource._runs: one paginated
        full listing grouped by prefix; a failed refresh serves the expired
        cache (stale-but-real rows, never an empty list); with no cache it
        raises upstream. Objects appear over time (CI uploads as the run
        progresses) — the TTL expiry notices late arrivals."""
        with self._lock:
            if self._runs_cache is not None and time.time() - self._runs_at < TTL:
                return self._runs_cache
            try:
                objects = self._objects()
            except core.ApiError:
                if self._runs_cache is not None:
                    return self._runs_cache   # offline: serve the stale listing
                raise
            runs, idx = {}, {}
            for key, size, lastmod in objects:
                tag, _, name = key.partition("/")
                if not name:
                    continue
                m = IDX_RE.match(tag)
                if m:
                    idx.setdefault(m.group(1), {})[name] = (size, lastmod)
                elif RUN_RE.match(tag):
                    runs.setdefault(tag, {})[name] = (size, lastmod)
            out = []
            for tag, objs in runs.items():
                dump = _dump_parts(self._assets_map(tag, objs))
                if not dump:
                    continue
                iobjs = idx.get(tag, {})
                tar = _index_parts(self._assets_map(f"idx-{tag}", iobjs))
                out.append({
                    "tag": tag,
                    "title": tag,   # S3 has no release titles — the id is honest
                    "created_at": objs.get(dump[0].name, (0, ""))[1],
                    "dump_bytes": sum(p.size or 0 for p in dump),
                    "indexed": bool(tar),
                    "idx_built_at": max((lm for _, lm in iobjs.values()),
                                        default=""),
                    "index_bytes": sum(p.size or 0 for p in tar) if tar else 0,
                })
            out.sort(key=lambda r: r["created_at"], reverse=True)
            self._runs_cache = out
            self._runs_at = time.time()
            return out

    def _assets_map(self, tag, objs):
        return {n: {"size": s, "browser_download_url": self._url(f"{tag}/{n}")}
                for n, (s, _) in objs.items()}

    def list(self):
        if not self.enabled:
            return []
        return [core.DumpInfo(
            id=r["tag"], state=core.DumpState.REMOTE, source=self.name,
            size=r["dump_bytes"] + r["index_bytes"] or None,
            meta={"title": r["title"], "created_at": r["created_at"],
                  "indexed": r["indexed"], "idx_built_at": r["idx_built_at"],
                  "dump_bytes": r["dump_bytes"], "index_bytes": r["index_bytes"]})
            for r in self._runs()]

    # ------------------------------------------------------------ downloads

    def download_plan(self, dump_id):
        """None only when the tag prefix or its dump object is confirmed
        absent (a successful listing IS the confirmation — the prefix exists
        while CI is still uploading). Upstream hiccups raise ApiError, never
        None."""
        if not self.enabled:
            return None
        dump = _dump_parts(self._assets(f"{dump_id}/"))
        if not dump:
            return None
        tar, data, manifest = [], None, {}
        assets = self._assets(f"idx-{dump_id}/")
        tar = _index_parts(assets) or []
        if "data.tar.gz" in assets:
            a = assets["data.tar.gz"]
            data = core.Part(name="data.tar.gz", index=0, size=a["size"],
                             url=a["browser_download_url"])
        if "manifest.json" in assets:
            try:
                manifest = self._get_json(f"idx-{dump_id}/manifest.json")
            except Exception:
                pass   # manifest only feeds completeness validation
        return core.DownloadPlan(dump_id=dump_id, data_bundle=data,
                                 hprof_parts=tuple(dump), index_parts=tuple(tar),
                                 manifest=manifest)

    def owns(self, part):
        return part.url.startswith(self._base + "/")

    def offer(self, prefix, part):
        """SourceRouter probe: a Part pointing at OUR copy of this object, or
        None when we confirmedly don't have it. Answers (hit or miss) are
        cached PROBE_TTL seconds: a missing object doesn't cost a HEAD per
        chunk, a late upload is still picked up within the TTL. A size
        mismatch means different bytes — never serve that. Probe failures
        degrade to None (GitHub keeps serving), logged, never raised."""
        if not self.enabled:
            return None
        if self.owns(part):
            return part   # already ours — no probe needed
        key = f"{prefix}/{part.name}"
        now = time.monotonic()
        with self._lock:
            ts, cached = self._probes.get(key, (0.0, None))
        if now - ts < PROBE_TTL:
            return cached
        result = None
        try:
            resp = self._req("HEAD", key, allow_404=True)
            if resp is not None:
                size = int(resp.headers.get("Content-Length") or 0)
                resp.close()
                if part.size is None or part.size == size:
                    result = core.Part(name=part.name, index=part.index,
                                       size=size, url=self._url(key))
        except core.ApiError as e:
            log.info("S3 probe %s failed (%s) — the fallback serves meanwhile",
                     key, e)
        with self._lock:
            self._probes[key] = (now, result)
        return result

    def fetch(self, part, offset=0):
        """Stream one object, resuming at offset via a signed Range GET.
        One attempt only — the caller owns retry/resume."""
        if not self.enabled:
            raise core.ApiError("upstream", "S3 source is disabled", status=502)
        key = part.url[len(self._base) + 1:]   # _url() output, same quoting
        resp = self._req("GET", key,
                         extra={"range": f"bytes={offset}-"} if offset else None)
        try:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                yield chunk
        finally:
            resp.close()
