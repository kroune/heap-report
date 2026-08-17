"""backend.snapshot — self-contained read-only HTML snapshot of one READY dump.

  python3 -m backend.snapshot --dump <id> --out <file.html> [--dumps <root>]

Self-contained read-only HTML snapshot of one READY dump. Two parts:

a. Payload — pulled from the local dump through backend.mat.MatQueryEngine,
   constructed directly (job registry + FsDumpStore with NO remote sources,
   no HTTP, no GitHub source) so nothing warms up the network. Collects:
   trees(id), ALL classes rows (every page), and for every analyzed class its
   composition + anatomy payloads.

b. Bundling — web/index.html with its <script type="module"> boot tag replaced
   by ONE classic <script> (ES modules don't work from file://): the payload
   as window.__INLINE__ + every web/**/*.js module + the index.html boot code,
   in topological order of the relative-import graph; stylesheets are inlined
   as <style> tags.

Bundling is mechanical and RELIES on the hard module-style rules in
web/CONTRACTS.md: only single-line named imports from relative paths, no
aliases, no re-exports, no default exports, no dynamic imports, no top-level
side effects. Each module body is wrapped in an IIFE assigned to a namespace
const (`const _mod_data_http_js = (() => { … return {esc, fmtB}; })();`) and
each import line becomes a const destructure off that namespace
(`const {esc} = _mod_data_http_js;`). The IIFE scopes are REQUIRED, not
cosmetic: the contracts pin identical export names in sibling modules (every
viz module exports `const kind`, every tab module exports `mount`), so a naive
single-scope concatenation is a parse-time "already declared" error.
Topological order gives definition-before-use for the namespace consts and for
the only cross-module top-level references the rules allow (constants);
function declarations hoist inside each module scope, so call sites inside
function bodies resolve regardless of order. Any style violation refuses the
bundle and names the offender. One deliberate relaxation of rule 1's letter:
`import * as x from './m.js'` is SUPPORTED (rewritten to `const x = _mod_m_js;`)
because the pinned tab/viz contracts make it unwirable otherwise — see
_check_style's docstring.

Snapshot payload shape (window.__INLINE__) — pinned by the header comment of
web/data/inlinerepo.js (that module owns the shape):

  {name, stats, trees, classes, comps, anats}
    name     dump id
    stats    engine.trees(id)["stats"]
    trees    engine.trees(id)["trees"]
    classes  [[disp, c, s, r, comp, anat, lams], ...] — ALL class rows, unpaged;
             r = retained bytes|null, comp = 0/1, anat = [sampleCounts],
             lams = [[name,c,s],...]|null
    comps    {className: composition payload}        — analyzed classes only
    anats    {className: anatomy payload}            — analyzed classes only
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

# A legal import line per CONTRACTS.md: single line, named bindings, relative
# path. Anything else starting with `import` is a violation (listed, named).
IMPORT_RE = re.compile(r"""^\s*import\s*\{([^{}]*)\}\s*from\s*["']([^"']+)["']\s*;?\s*$""")
IMPORT_NS_FULL_RE = re.compile(
    r"""^\s*import\s*\*\s+as\s+([\w$]+)\s+from\s*["']([^"']+)["']\s*;?\s*$""")
IMPORT_ANY_RE = re.compile(r"^\s*import\b")
BOOT_TAG_RE = re.compile(r'<script\s+type="module"[^>]*>(.*?)</script>', re.S)
CSS_TAG_RE = re.compile(r'<link\b[^>]*\brel="stylesheet"[^>]*>')
HREF_RE = re.compile(r'href="([^"]+)"')
ALIAS_RE = re.compile(r"\bas\b")
EXPORT_PREFIX_RE = re.compile(
    r"^(\s*)export\s+(?=(?:async\s+)?(?:function|class|const|let|var)\b)")
EXPORT_LIST_RE = re.compile(r"^\s*export\s*\{([^{}]*)\}\s*;?\s*$")
EXPORT_NAME_RES = [
    re.compile(r"^\s*export\s+(?:async\s+)?function\s+([\w$]+)"),
    re.compile(r"^\s*export\s+class\s+([\w$]+)"),
    re.compile(r"^\s*export\s+(?:const|let|var)\s+([\w$]+)"),
]


class SnapshotError(Exception):
    """Anything that must refuse the snapshot with a clear message."""


# ---------------------------------------------------------------- payload


def build_local_engine(root):
    """Store + engine only — no server, no GitHub source, no network."""
    jobs = jobs_mod.InMemoryJobRegistry()
    store = localstore.FsDumpStore(root, jobs, [])
    store.init()
    return store, mat.MatQueryEngine(store, jobs)


def collect_payload(engine, dump_id):
    """The window.__INLINE__ payload for one READY dump (shape: module docstring,
    pinned by web/data/inlinerepo.js's header contract)."""
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
            c = engine.composition(dump_id, disp)
            if c is not None:
                comps[disp] = c
        if row.get("anat"):
            a = engine.anatomy(dump_id, disp)
            if a is not None:
                anats[disp] = a
    classes = [[r["disp"], r["c"], r["s"], r["r"], 1 if r["comp"] else 0,
                r["anat"], r["lams"]] for r in rows]
    return {"name": dump_id,
            "stats": trees_payload["stats"],
            "trees": trees_payload["trees"],
            "classes": classes,
            "comps": comps,
            "anats": anats}


# ---------------------------------------------------------------- module graph


def _load_modules(web_root):
    """Every web/**/*.js, keyed by its posix path relative to web_root."""
    mods = {}
    for dirpath, dirnames, filenames in os.walk(web_root):
        dirnames.sort()
        for fn in sorted(filenames):
            if fn.endswith(".js"):
                p = os.path.join(dirpath, fn)
                rel = os.path.relpath(p, web_root).replace(os.sep, "/")
                with open(p) as f:
                    mods[rel] = f.read()
    return mods


def _mod_id(rel):
    """Module path -> namespace const name: data/http.js -> _mod_data_http_js."""
    return "_mod_" + re.sub(r"[^\w$]", "_", rel)


def _check_style(src):
    """Grep-level enforcement of the CONTRACTS.md hard module rules. Returns a
    list of violations (empty = clean). Namespace imports (`import * as x`)
    deviate from rule 1's letter but are SUPPORTED (they rewrite to the module
    namespace const): the pinned tab/viz contracts (every tab exports `mount`,
    every viz exports kind/prepare/render) are unwirable without them."""
    bad = []
    if re.search(r"\bexport\s+default\b", src):
        bad.append("default export")
    if re.search(r"\bimport\s*\(", src):
        bad.append("dynamic import()")
    if re.search(r"^\s*export\s*(?:\*|\{[^{}]*\})\s*from\b", src, re.M):
        bad.append("re-export")
    for line in src.splitlines():
        m = IMPORT_RE.match(line)
        if m:
            if ALIAS_RE.search(m.group(1)):
                bad.append(f"import alias: {line.strip()}")
            elif not m.group(2).startswith(("./", "../")):
                bad.append(f"non-relative import: {line.strip()}")
        elif IMPORT_NS_FULL_RE.match(line):
            if not IMPORT_NS_FULL_RE.match(line).group(2).startswith(("./", "../")):
                bad.append(f"non-relative import: {line.strip()}")
        elif IMPORT_ANY_RE.match(line):
            bad.append(f"unsupported import form: {line.strip()}")
    return bad


def _exports_of(src):
    """(exported names, violations) — every `export …` line must yield a name,
    anything else is a violation the bundler cannot rewrite."""
    names, bad = [], []
    for line in src.splitlines():
        for r in EXPORT_NAME_RES:
            m = r.match(line)
            if m:
                names.append(m.group(1))
                break
        else:
            m = EXPORT_LIST_RE.match(line)
            if m:
                names.extend(x.strip() for x in m.group(1).split(",") if x.strip())
            elif re.match(r"^\s*export\b", line) \
                    and not re.search(r"\bexport\s*(?:\*|\{[^{}]*\})\s*from\b", line):
                # (re-exports were already reported by _check_style)
                bad.append(f"unsupported export form: {line.strip()}")
    return names, bad


def _resolve(base, spec, mods):
    """Relative import spec -> module key, or None when unresolvable."""
    tgt = os.path.normpath(os.path.join(os.path.dirname(base), spec))
    tgt = tgt.replace(os.sep, "/")
    return tgt if tgt in mods else None


def _imports_of(base, src, mods):
    """(deps, violations) of one module (or the index.html boot code, base=""
    so its './x.js' specs resolve from the web root)."""
    deps, bad = [], _check_style(src)
    if bad:
        return [], bad   # don't pile resolution noise on top of style errors
    for line in src.splitlines():
        m = IMPORT_RE.match(line) or IMPORT_NS_FULL_RE.match(line)
        if m:
            tgt = _resolve(base, m.group(2), mods)
            if tgt is None:
                bad.append(f"unresolvable import: {m.group(2)}")
            else:
                deps.append(tgt)
    return deps, bad


def _topo(deps):
    """Dependencies-first order (DFS post-order, deterministic). A cycle is a
    hard error — it would make definition-before-use impossible."""
    order, state = [], {}

    def visit(p, stack):
        st = state.get(p)
        if st == 2:
            return
        if st == 1:
            raise SnapshotError("import cycle: " + " -> ".join(stack + [p]))
        state[p] = 1
        for d in deps[p]:
            visit(d, stack + [p])
        state[p] = 2
        order.append(p)

    for p in sorted(deps):
        visit(p, [])
    return order


def _rewrite(base, src, mods):
    """Module source -> classic-script fragment: import lines become const
    destructures off the dependency's namespace const, `export` keywords and
    `export {…};` lists are dropped. The bindings stay, scoped by the IIFE the
    caller wraps the fragment in."""
    out = []
    for line in src.splitlines():
        m = IMPORT_RE.match(line)
        if m:
            tgt = _resolve(base, m.group(2), mods)   # validated by _imports_of
            out.append(f"const {{{m.group(1).strip()}}} = {_mod_id(tgt)};")
            continue
        m = IMPORT_NS_FULL_RE.match(line)
        if m:
            tgt = _resolve(base, m.group(2), mods)
            out.append(f"const {m.group(1)} = {_mod_id(tgt)};")
            continue
        if EXPORT_LIST_RE.match(line):
            continue
        out.append(EXPORT_PREFIX_RE.sub(r"\1", line))
    return "\n".join(out)


# ---------------------------------------------------------------- html assembly


def _inline_css(html, web_root):
    def repl(m):
        hm = HREF_RE.search(m.group(0))
        if not hm:
            raise SnapshotError(f"stylesheet link without href: {m.group(0)}")
        href = hm.group(1)
        rel = os.path.normpath(href).replace(os.sep, "/")
        if rel.startswith("../"):
            raise SnapshotError(f"stylesheet escapes web/: {href}")
        try:
            with open(os.path.join(web_root, rel)) as f:
                css = f.read()
        except OSError:
            raise SnapshotError(f"stylesheet not found: web/{rel}")
        if "</style" in css.lower():
            raise SnapshotError(f"stylesheet contains '</style': web/{rel}")
        return "<style>\n" + css + "\n</style>"

    return CSS_TAG_RE.sub(repl, html)


def build_html(web_root, payload):
    """web/index.html with stylesheets inlined and the module boot tag replaced
    by one classic script: payload + all modules (topological) + boot code."""
    index = os.path.join(web_root, "index.html")
    try:
        with open(index) as f:
            html = f.read()
    except OSError:
        raise SnapshotError(f"web frontend not found: {index}")

    m = BOOT_TAG_RE.search(html)
    if not m:
        raise SnapshotError('web/index.html has no <script type="module"> boot tag')
    boot_src = m.group(1)

    mods = _load_modules(web_root)
    graph, exports, errors = {}, {}, {}
    for rel, src in mods.items():
        deps, bad = _imports_of(rel, src, mods)
        names, xbad = _exports_of(src)
        graph[rel] = deps
        exports[rel] = names
        if bad or xbad:
            errors[f"web/{rel}"] = bad + xbad
    boot_deps, boot_bad = _imports_of("", boot_src, mods)
    if boot_bad:
        errors["web/index.html boot script"] = boot_bad
    if errors:
        lines = ["style-rule violations — refusing to bundle:"]
        for who in sorted(errors):
            for v in errors[who]:
                lines.append(f"  {who}: {v}")
        raise SnapshotError("\n".join(lines))

    payload_json = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    payload_json = payload_json.replace("</", "<\\/")   # a raw "</script" would end the script tag

    chunks = ["window.__INLINE__ = " + payload_json + ";"]
    for rel in _topo(graph):
        body = _rewrite(rel, mods[rel], mods)
        names = ", ".join(dict.fromkeys(exports[rel]))
        chunks.append(f"/* ---- web/{rel} ---- */\n"
                      f"const {_mod_id(rel)} = (() => {{\n{body}\nreturn {{{names}}};\n}})();")
    chunks.append("/* ---- web/index.html boot ---- */\n{\n"
                  + _rewrite("", boot_src, mods) + "\n}")
    script = "\n\n".join(chunks)
    if re.search(r"</script", script, re.I):
        raise SnapshotError("bundled script contains a raw '</script' sequence")

    html = _inline_css(html, web_root)
    # re-search: _inline_css rewrites the html, so the boot-tag match offsets
    # from the pre-inline html are stale (splicing there mangles the output)
    m = BOOT_TAG_RE.search(html)
    return html[:m.start()] + "<script>\n" + script + "\n</script>" + html[m.end():]


# ---------------------------------------------------------------- cli


def main(argv=None):
    p = argparse.ArgumentParser(prog="backend.snapshot",
                                description="self-contained HTML snapshot of one READY dump")
    p.add_argument("--dump", required=True, help="dump id (directory under the dumps root)")
    p.add_argument("--out", required=True, help="output .html file")
    p.add_argument("--dumps",
                   default=os.environ.get("HEAP_REPORT_DUMPS",
                                          os.path.join(REPO, "dumps")))
    p.add_argument("--web", default=os.path.join(REPO, "web"),
                   help="web frontend root (for tests / staging)")
    args = p.parse_args(argv)

    try:
        store, engine = build_local_engine(args.dumps)
        info = store.get(args.dump)
        if info.state is not core.DumpState.READY:
            raise SnapshotError(
                f"dump {args.dump} is {info.state.value} — snapshots need a ready dump")
        payload = collect_payload(engine, args.dump)
        html = build_html(args.web, payload)
    except (SnapshotError, core.ApiError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w") as f:
        f.write(html)
    print(f"wrote {args.out} ({os.path.getsize(args.out) // 1024} KB), "
          f"classes={len(payload['classes'])}, comps={len(payload['comps'])}, "
          f"anats={len(payload['anats'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
