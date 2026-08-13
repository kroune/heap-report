#!/usr/bin/env python3
"""Compact (or restore) MAT index files of heap-report dumps.

  compact.py [DIR ...]              compress *.index -> *.index.zst (default: all dumps)
  compact.py --restore [DIR ...]    decompress back to raw *.index
  compact.py --level 19 DIR         higher ratio, ~10x slower (archival)

With no DIR, scans the repo (two levels — dumps/<name>/ lives at depth 2,
realpath-deduped) for directories containing MAT index files. MAT needs the raw
files only while a query runs; analyze_dump.py / serve.py restore automatically.
See matindex.py.
"""
import argparse, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import matindex


def find_dump_dirs():
    out, seen = [], set()
    for depth in (1, 2):
        for d in sorted(glob_depth(depth)):
            rp = os.path.realpath(d)
            if rp in seen or not os.path.isdir(d):
                continue
            raws, zsts = matindex.raws_zsts(rp)
            if raws or zsts:
                seen.add(rp)
                out.append(rp)
    return out


def glob_depth(depth):
    import glob as g
    return g.glob(os.path.join(HERE, *(["*"] * depth)))


def du(paths):
    return sum(os.path.getsize(p) for p in paths)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dirs", nargs="*")
    ap.add_argument("--restore", action="store_true")
    ap.add_argument("--level", type=int, default=matindex.LEVEL)
    a = ap.parse_args()
    dirs = [os.path.realpath(d) for d in a.dirs] if a.dirs else find_dump_dirs()
    if not dirs:
        print("no dump directories with MAT indexes found")
        return
    for d in dirs:
        raws, zsts = matindex.raws_zsts(d)
        if a.restore:
            n = matindex.restore(d)
            print(f"{d}: restored {n} files")
        else:
            print(f"{d}: {len(raws)} raw ({du(raws) / 1e9:.2f} GB), "
                  f"{len(zsts)} already archived ({du(zsts) / 1e9:.2f} GB)")
            archived, dropped = matindex.compact(d, level=a.level)
            raws, zsts = matindex.raws_zsts(d)
            print(f"{d}: now {len(raws)} raw ({du(raws) / 1e9:.2f} GB), "
                  f"{len(zsts)} archived ({du(zsts) / 1e9:.2f} GB)")


if __name__ == "__main__":
    main()
