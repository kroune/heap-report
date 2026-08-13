#!/usr/bin/env python3
"""Static snapshot export: inline reportdata payloads into a self-contained HTML.

  generate.py --data dumps/<name>/data --out dumps/<name>/index.html

The snapshot is read-only: on-demand Analyze and the Compare tab are disabled
(they need the server — see serve.py). For the interactive UI prefer serve.py.
"""
import argparse, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import reportdata as rd


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    data_dir = os.path.abspath(a.data)
    name = os.path.basename(os.path.dirname(data_dir.rstrip("/"))) or "heap-report"

    idx = rd.analysis_index(data_dir)
    comps, anats = {}, {}
    for full, st in idx.items():
        if st["comp"]:
            c = rd.composition(data_dir, full)
            if c:
                comps[full] = c
        if st["anat"]:
            an = rd.anatomy(data_dir, full)
            if an:
                anats[full] = an
    classes = [[r["disp"], r["c"], r["s"], r["r"], 1 if r["comp"] else 0, r["anat"], r["lams"]]
               for r in rd.class_table(data_dir)]
    payload = {
        "name": name,
        "stats": rd.stats(data_dir),
        "trees": rd.trees(data_dir),
        "classes": classes,
        "comps": comps,
        "anats": anats,
    }
    with open(os.path.join(HERE, "template.html")) as f:
        html = f.read()
    # static snapshot must be self-contained: inline the js/ modules in load order
    def sub_js(m):
        with open(os.path.join(HERE, "js", m.group(1))) as fj:
            return "<script>\n" + fj.read() + "\n</script>"
    html = re.sub(r'<script src="js/([\w.-]+\.js)"></script>', sub_js, html)
    html = html.replace("/*__INLINE__*/null",
                        json.dumps(payload, separators=(",", ":"), ensure_ascii=False))
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w") as f:
        f.write(html)
    print(f"wrote {a.out} ({os.path.getsize(a.out)//1024} KB), "
          f"classes={len(classes)}, comps={len(comps)}, anats={len(anats)}")


if __name__ == "__main__":
    main()
