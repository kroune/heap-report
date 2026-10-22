#!/usr/bin/env python3
"""Parse the feature-module-3000 synthetic project into per-module logical
signatures: tier template, direct project-dependency count, transitive
project-dependency closure size, and full WL dependency-pattern id.

The WL-pattern computation reproduces the source-graph diagnostic in
FLOW-COMPARISON.md exactly (foundation 1, common 2, core 14, api 19,
impl 958, ui 275, app 1 -> 1,254 patterns).

Output: module-signatures.tsv with columns module, tier, ndirect, closure,
libs, pattern.
"""
import re, os, sys, collections
from functools import lru_cache

ROOT = sys.argv[1] if len(sys.argv) > 1 else \
    "/home/olowo/IdeaProjects/GradleExperiments-v2/published/feature-module-3000"
OUT = sys.argv[2] if len(sys.argv) > 2 else "module-signatures.tsv"

mods = []
for line in open(os.path.join(ROOT, "settings.gradle.kts")):
    m = re.search(r'include\s*\(\s*"([^"]+)"\s*\)', line)
    if m:
        mods.append(m.group(1))

PROJ = re.compile(r'project\(\s*"([^"]+)"\s*\)')
LIBS = re.compile(r'libs\.[a-zA-Z0-9_.]+')
deps, libs = {}, {}
for mod in mods:
    p = os.path.join(ROOT, *mod.strip(":").split(":"), "build.gradle.kts")
    txt = re.sub(r'//.*', '', open(p).read()) if os.path.exists(p) else ""
    deps[mod] = tuple(sorted(set(PROJ.findall(txt))))
    libs[mod] = len(set(LIBS.findall(txt)))


def tier(m):
    return m.strip(":").split(":")[0] if m != ":app:app" else "app"


color = {m: () for m in mods}
for _ in range(300):
    newc = {m: tuple(sorted(color[d] for d in deps[m])) for m in mods}
    if newc == color:
        break
    color = newc


@lru_cache(maxsize=None)
def canon(c):
    return tuple(sorted(canon(x) for x in c))


pat = {m: canon(color[m]) for m in mods}
patids = {}
for m in mods:
    if pat[m] not in patids:
        patids[pat[m]] = len(patids)


@lru_cache(maxsize=None)
def closure(m):
    s = set()
    for d in deps[m]:
        s.add(d)
        s |= closure(d)
    return frozenset(s)


with open(OUT, "w") as w:
    w.write("module\ttier\tndirect\tclosure\tlibs\tpattern\n")
    for m in mods:
        w.write(f"{m}\t{tier(m)}\t{len(deps[m])}\t{len(closure(m))}\t{libs[m]}\t{patids[pat[m]]}\n")

bytier = collections.defaultdict(set)
for m in mods:
    bytier[tier(m)].add(pat[m])
print("patterns per tier:", {k: len(v) for k, v in bytier.items()},
      "total:", len(set(pat.values())))
print("ndirect histogram:", collections.Counter(len(v) for v in deps.values()).most_common())
