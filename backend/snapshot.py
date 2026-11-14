"""Export one READY dump as a self-contained, read-only HTML document.

The frontend is built separately with ``npm ci && npm run build``. Vite emits a
single ``web/dist/index.html`` containing the React application and its styles.
Snapshot generation only collects the dump payload and inserts it as
``window.__INLINE__`` before the application script; it never invokes Node or
accesses remote sources.

  python3 -m backend.snapshot --dump <id> --out <file.html> [--dumps <root>]

Payload shape:
  {name, stats, trees, classes, comps, anats, shapes}
    name     dump id
    stats    engine.trees(id)["stats"]
    trees    engine.trees(id)["trees"]
    classes  [[disp, c, s, r, comp, anat, lams], ...] — all class rows
    comps    {className: composition payload} — analyzed classes only
    anats    {className: anatomy payload} — analyzed classes only
    shapes   heap-wide shapes payload|null
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

from . import core, localstore, mat
from . import jobs as jobs_mod

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class SnapshotError(Exception):
    """Anything that must refuse the snapshot with a clear message."""


def build_local_engine(root):
    """Store + engine only — no server, no remote source, no network."""
    jobs = jobs_mod.InMemoryJobRegistry()
    store = localstore.FsDumpStore(root, jobs, [])
    store.init()
    return store, mat.MatQueryEngine(store, jobs)


def collect_payload(engine, dump_id):
    """Collect the window.__INLINE__ payload through the local query engine."""
    trees_payload = engine.trees(dump_id)
    rows = []
    page = 0
    while True:   # classes() is paged at 200 — walk every page
        res = engine.classes(dump_id, sort="-s", page=page)
        rows.extend(res["rows"])
        page += 1
        if page >= res["pages"]:
            break
    comps, anats = {}, {}
    for row in rows:
        disp = row["disp"]
        if row.get("comp"):
            composition = engine.composition(dump_id, disp)
            if composition is not None:
                comps[disp] = composition
        if row.get("anat"):
            anatomy = engine.anatomy(dump_id, disp)
            if anatomy is not None:
                anats[disp] = anatomy
    shapes = engine.shapes(dump_id)
    classes = [[row["disp"], row["c"], row["s"], row["r"],
                1 if row["comp"] else 0, row["anat"], row["lams"]] for row in rows]
    return {"name": dump_id, "stats": trees_payload["stats"],
            "trees": trees_payload["trees"], "classes": classes,
            "comps": comps, "anats": anats, "shapes": shapes}


def build_html(web_root, payload):
    """Insert the serialized payload into the built single-file React app."""
    index = os.path.join(web_root, "index.html")
    try:
        with open(index, encoding="utf-8") as file:
            html = file.read()
    except OSError:
        raise SnapshotError(
            f"built frontend not found: {index}; run 'npm ci && npm run build'")

    if re.search(r"<script\b[^>]*\bsrc\s*=", html, re.I):
        raise SnapshotError("built frontend still has an external script; rebuild the single-file app")
    if re.search(r"<link\b[^>]*\brel=[\"']stylesheet[\"']", html, re.I):
        raise SnapshotError("built frontend still has an external stylesheet; rebuild the single-file app")
    app_script = re.search(r"<script\b", html, re.I)
    if not app_script:
        raise SnapshotError("built frontend has no application script; run 'npm run build'")

    payload_json = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    payload_json = payload_json.replace("</", "<\\/")
    payload_json = payload_json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    payload_script = "<script>window.__INLINE__ = " + payload_json + ";</script>"
    return html[:app_script.start()] + payload_script + html[app_script.start():]


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="backend.snapshot", description="self-contained HTML snapshot of one READY dump")
    parser.add_argument("--dump", required=True, help="dump id (directory under the dumps root)")
    parser.add_argument("--out", required=True, help="output .html file")
    parser.add_argument("--dumps", default=os.environ.get(
        "HEAP_REPORT_DUMPS", os.path.join(REPO, "dumps")))
    parser.add_argument("--web", default=os.path.join(REPO, "web", "dist"),
                        help="built frontend root (for tests / staging)")
    args = parser.parse_args(argv)

    try:
        store, engine = build_local_engine(args.dumps)
        info = store.get(args.dump)
        if info.state is not core.DumpState.READY:
            raise SnapshotError(
                f"dump {args.dump} is {info.state.value} — snapshots need a ready dump")
        payload = collect_payload(engine, args.dump)
        html = build_html(args.web, payload)
    except (SnapshotError, core.ApiError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as file:
        file.write(html)
    print(f"wrote {args.out} ({os.path.getsize(args.out) // 1024} KB), "
          f"classes={len(payload['classes'])}, comps={len(payload['comps'])}, "
          f"anats={len(payload['anats'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
