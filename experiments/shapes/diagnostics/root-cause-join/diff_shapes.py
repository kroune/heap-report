#!/usr/bin/env python3
"""Diff two shapes in a SHP1: walk both DAGs in parallel, find first divergence points."""
import struct, sys, collections
KINDS = ["LEAF", "NODE", "SCC"]
def load(path):
    f = open(path, "rb").read(); p = 0
    def i32():
        nonlocal p; v = struct.unpack_from(">i", f, p)[0]; p += 4; return v
    def i64():
        nonlocal p; v = struct.unpack_from(">q", f, p)[0]; p += 8; return v
    def u8():
        nonlocal p; v = f[p]; p += 1; return v
    assert f[:4] == b"SHP1"; p = 4
    labels = []
    for _ in range(i32()):
        n = i32(); labels.append(f[p:p+n].decode()); p += n
    shapes = []
    for sid in range(i32()):
        hi, lo = i64(), i64()
        kind = u8(); lab = i32()
        cnt, sh, mn, mx, roots = i64(), i64(), i64(), i64(), i64()
        sccsz, intE = i32(), i32(); epm = u8()
        shapes.append(dict(id=sid, kind=KINDS[kind], label=labels[lab], count=cnt, shallow=sh,
                           roots=roots, sccsz=sccsz, intE=intE, epm=epm, kids=[]))
    for _ in range(i32()):
        s, c, m = i32(), i32(), i64()
        shapes[s]["kids"].append((c, m))
    return shapes

def short(l, n=100): return l if len(l) <= n else l[:n-3] + "..."

def main(path, a, b):
    shapes = load(path)
    a, b = int(a), int(b)
    def kidstr(sid):
        s = shapes[sid]
        return ", ".join(f"{short(shapes[c]['label'],60)}x{m}(#{c})" for c, m in sorted(s["kids"]))
    print(f"A #{a}: {shapes[a]['kind']} count={shapes[a]['count']} sccsz={shapes[a]['sccsz']} label={short(shapes[a]['label'],150)}")
    print(f"  kids: {kidstr(a)}")
    print(f"B #{b}: {shapes[b]['kind']} count={shapes[b]['count']} sccsz={shapes[b]['sccsz']} label={short(shapes[b]['label'],150)}")
    print(f"  kids: {kidstr(b)}")
    # parallel walk: stack of (sidA, sidB, path). At each step compare sorted kid (label,mult,shape) triples.
    # Find divergence where labels/mults match but child shape ids differ (propagation) vs label/mult diff (origin).
    seen = set()
    stack = [(a, b, "ROOT")]
    origins = []
    while stack:
        x, y, pathstr = stack.pop()
        if (x, y) in seen: continue
        seen.add((x, y))
        sx, sy = shapes[x], shapes[y]
        kx = sorted((shapes[c]["label"], m, c) for c, m in sx["kids"])
        ky = sorted((shapes[c]["label"], m, c) for c, m in sy["kids"])
        lx = [(l, m) for l, m, c in kx]; ly = [(l, m) for l, m, c in ky]
        if lx != ly:
            origins.append((pathstr, x, y))
            if len(origins) <= 12:
                print(f"\nORIGIN DIVERGENCE at {pathstr}")
                print(f"  A #{x} {short(sx['label'],80)}: {[(short(l,50),m) for l,m in lx]}")
                print(f"  B #{y} {short(sy['label'],80)}: {[(short(l,50),m) for l,m in ly]}")
            continue
        # same label/mult structure; recurse into differing child ids
        for (l, m, cx), (_, _, cy) in zip(kx, ky):
            if cx != cy:
                stack.append((cx, cy, f"{pathstr}/{short(l,40)}x{m}"))
    print(f"\ncompared {len(seen)} aligned pairs; origin divergences: {len(origins)}")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
