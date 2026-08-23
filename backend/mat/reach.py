"""backend/mat/reach — the reachability pass over one anatomy extraction.

Runs at analyze time (inside the analyze job — minutes of MAT anyway) and
computes the DERIVED tables of analysis.db:

  reach    per class: rincl = inclusive retained (used bytes of everything
           reachable from the class's in-set instances, the instances
           themselves included — MAT's self-inclusive retained convention);
           rshared = the part of rincl reachable from >= 2 distinct SAMPLED
           ROOT instances.
  sgroups  split copies: each class's objects grouped by the frozenset of
           their direct in-set holder classes (the inspected class itself is
           never split — single root group). Per class the top-M groups by
           used bytes survive; the rest fold into one residual group
           (holders_json NULL). Bounded by MAX_GROUPS overall.
  slinks   per (source group, field, target group) ref count + bytes.

`shared` is ROOT-diversity, not class-diversity: ancestors always reach their
descendants, so "reached by >= 2 classes" would mark every deep object shared
and all wedges would read ~100%. The multi-source bitmask fixpoint from the
sampled roots is the "comes from different sources" signal. Known blind
spots (documented in the viz help): sharing with UNSAMPLED instances and
within-class sharing between two objects under the same root are invisible —
consistent with the viz's sampled nature.

Cones are ORIENTED: the viz answers "who retains how much", so a cone walk
never traverses an edge that climbs back toward the roots (target level below
source level, BFS depth from the sampled roots). Without the cut, any object
with a real up-reference (DependencySet.clientConfiguration, a this$0 outer
ref, …) would absorb its whole ancestor subtree and every such class would
read ~100% of the set — the parent shows its own weight when looked at
directly. Objects unreachable from the roots (unlevelled) are traversed
freely. Holder-set keying and slinks use the RAW adjacency (they describe who
references whom, incl. up-edges); only the cone sums are oriented.

Cost: one O(K*E) fixpoint + (C+G) BFS cone walks (avg-reach edge visits
each); progress is logged.
"""
from __future__ import annotations

import json
from collections import deque

from .parsing import _split_refs

MAX_GROUPS_PER_CLASS = 24    # top-M by used bytes survive; the rest fold into a residual
MAX_GROUPS = 4000            # global split-copy cap (residual folding continues)
MAX_SLINKS = 8000            # mirrors the 5000-link cap of the class-level graph


def _build_adj(src, pos, oids):
    """Int-indexed in-set adjacency, same rules as payloads._anatomy_build:
    named refs (incl. skipped synthetic ones — real holders) for instances,
    full outbounds for arrays / field-less objects."""
    nodes, named = src["nodes"], _split_refs(src)[0]
    edges, edges_full = src["edges"], src["edgesFull"]
    adj = [[] for _ in oids]
    for i, oid in enumerate(oids):
        cls = nodes[oid]["cls"]
        if cls.endswith("[]") or oid not in named:
            ch = [("[]", pos[t]) for t in (edges_full.get(oid) or edges.get(oid, []))
                  if t in pos]
        else:
            ch = [(nm, pos[t]) for nm, t, _sk in named[oid]]
        adj[i] = ch
    return adj


def _root_reach(adj, roots, n):
    """Multi-source bitmask fixpoint: mask[o] = the set of sampled roots that
    reach o (bit i = roots[i]). Worklist over edges; each object's mask
    changes <= K times, so this is O(K*E) worst case. Unoriented on purpose:
    this is true reachability, not a cone sum."""
    mask = [0] * n
    q = deque()
    for b, r in enumerate(roots):
        if not (mask[r] >> b) & 1:
            mask[r] |= 1 << b
            q.append(r)
    while q:
        u = q.popleft()
        mu = mask[u]
        for _, v in adj[u]:
            add = mu & ~mask[v]
            if add:
                mask[v] |= add
                q.append(v)
    return mask


def _levels(adj, roots, n):
    """BFS min-depth from the sampled roots; -1 for objects unreachable from
    them (depth-cut remnants, truncation leftovers, pure cycles). The cone
    orientation: an edge climbing to a shallower levelled object is a
    back-reference and is never traversed by a cone walk."""
    lvl = [-1] * n
    q = deque()
    for r in roots:
        lvl[r] = 0
        q.append(r)
    while q:
        u = q.popleft()
        for _, v in adj[u]:
            if lvl[v] == -1:
                lvl[v] = lvl[u] + 1
                q.append(v)
    return lvl


def _cone_sums(seeds, adj, used, shared, visited, lvl):
    """(rincl, rshared) of the union cone of seeds: BFS summing used bytes,
    each object counted once; rshared sums only shared-flagged objects.
    Oriented: edges to a shallower levelled object (back-references toward
    the roots) are not traversed; unlevelled objects are traversed freely."""
    rincl = rshared = 0
    stack = []
    for s in seeds:
        if not visited[s]:
            visited[s] = 1
            stack.append(s)
    while stack:
        u = stack.pop()
        uu = used[u]
        rincl += uu
        if shared[u]:
            rshared += uu
        lu = lvl[u]
        for _, v in adj[u]:
            if visited[v]:
                continue
            lv = lvl[v]
            if lu != -1 and lv != -1 and lv < lu:
                continue   # back-reference: the ancestor carries its own weight
            visited[v] = 1
            stack.append(v)
    return rincl, rshared


def compute(src, root_ids, log=lambda m: None):
    """-> (reach_rows, sgroup_rows, slink_rows) for db.write_reach.
    root_ids = the sampled root instance ids (src["ids"])."""
    nodes = src["nodes"]
    oids = sorted(nodes)
    pos = {o: i for i, o in enumerate(oids)}
    n = len(oids)
    cls_of = [nodes[o]["cls"] for o in oids]
    used = [nodes[o]["used"] for o in oids]
    ret = [nodes[o]["ret"] for o in oids]
    roots = [pos[o] for o in root_ids if o in pos]
    root_cls = cls_of[roots[0]] if roots else None
    adj = _build_adj(src, pos, oids)

    mask = _root_reach(adj, roots, n)
    shared = [bool(m & (m - 1)) for m in mask]   # popcount >= 2
    lvl = _levels(adj, roots, n)

    # ---- holder sets: inverse adjacency -> per object the frozenset of
    #      in-set DIRECT holder classes (intermediate same-class chains do
    #      not propagate — only direct holders key the copies) ----
    holders = [set() for _ in range(n)]
    for u in range(n):
        cu = cls_of[u]
        for _, v in adj[u]:
            holders[v].add(cu)

    # ---- group keying: (class, frozenset of direct holder classes); the
    #      inspected class itself is never split (single root group) ----
    gkey_of = [None] * n
    groups = {}   # gkey -> [n, s, r, members]
    for i in range(n):
        c = cls_of[i]
        gkey = (c, ()) if c == root_cls else (c, frozenset(holders[i]))
        gkey_of[i] = gkey
        g = groups.get(gkey)
        if g is None:
            g = groups[gkey] = [0, 0, 0, []]
        g[0] += 1
        g[1] += used[i]
        g[2] += ret[i]
        g[3].append(i)

    # ---- caps: per class top-M by used bytes, the rest fold into one
    #      residual group (holders_json NULL); then a global cap folds the
    #      smallest remaining groups the same way ----
    by_cls = {}
    for gkey in groups:
        by_cls.setdefault(gkey[0], []).append(gkey)

    def fold(cls, keep_keys):
        """Merge every non-kept group of cls into one residual group."""
        rest = [k for k in by_cls[cls] if k not in keep_keys]
        if not rest:
            return
        rkey = (cls, None)
        for k in rest:
            g = groups.pop(k)
            r = groups.get(rkey)
            if r is None:
                r = groups[rkey] = [0, 0, 0, []]
            r[0] += g[0]
            r[1] += g[1]
            r[2] += g[2]
            r[3].extend(g[3])
            for m in g[3]:
                gkey_of[m] = rkey
        by_cls[cls] = [k for k in by_cls[cls] if k in keep_keys]
        if rkey not in by_cls[cls]:
            by_cls[cls].append(rkey)

    for cls, keys in by_cls.items():
        if len(keys) > MAX_GROUPS_PER_CLASS:
            keep = set(sorted(keys, key=lambda k: -groups[k][1])
                       [:MAX_GROUPS_PER_CLASS])
            fold(cls, keep)
    while len(groups) > MAX_GROUPS:
        # fold the globally smallest non-residual, non-root group
        victim = min((k for k in groups if k[1] is not None and k[0] != root_cls),
                     key=lambda k: groups[k][1], default=None)
        if victim is None:
            break
        fold(victim[0], set(by_cls[victim[0]]) - {victim})

    ordered = sorted(groups, key=lambda k: (-groups[k][1], k[0]))
    gid_of = {k: gid for gid, k in enumerate(ordered)}
    gid_of_obj = [gid_of[gkey_of[i]] for i in range(n)]

    # ---- pass A: per-class inclusive retained + shared part ----
    cls_members = {}
    for i in range(n):
        cls_members.setdefault(cls_of[i], []).append(i)
    reach_rows = []
    visited = bytearray(n)
    total = len(cls_members)
    for ci, (cls, mem) in enumerate(cls_members.items()):
        if ci and ci % 200 == 0:
            log(f"  reach pass A: {ci}/{total} classes")
        visited = bytearray(n)
        rincl, rshared = _cone_sums(mem, adj, used, shared, visited, lvl)
        reach_rows.append((cls, rincl, rshared))

    # ---- pass B: per-group stats (n/s/r come from membership; rincl/rshared
    #      from the cone walk) ----
    sgroup_rows = []
    total = len(ordered)
    for gid, gkey in enumerate(ordered):
        if gid and gid % 200 == 0:
            log(f"  reach pass B: {gid}/{total} groups")
        cls, holderset = gkey
        g = groups[gkey]
        visited = bytearray(n)
        rincl, rshared = _cone_sums(g[3], adj, used, shared, visited, lvl)
        if cls == root_cls:
            # the single root group still reports its real holder union
            hj = json.dumps(sorted(set().union(*(holders[m] for m in g[3]))))
        elif holderset is None:
            hj = None   # residual "(other holders)" fold
        else:
            hj = json.dumps(sorted(holderset))
        sgroup_rows.append((gid, cls, hj, g[0], g[1], g[2], rincl, rshared))

    # ---- slinks: per (source group, field, target group) refs + bytes ----
    agg = {}
    for u in range(n):
        su = gid_of_obj[u]
        for f, v in adj[u]:
            k = (su, f, gid_of_obj[v])
            e = agg.get(k)
            if e is None:
                e = agg[k] = [0, 0]
            e[0] += 1
            e[1] += used[v]
    slink_rows = sorted(([s, t, f, en, b] for (s, f, t), (en, b) in agg.items()),
                        key=lambda x: -x[4])[:MAX_SLINKS]
    log(f"  reach: {len(reach_rows)} classes, {len(sgroup_rows)} split groups, "
        f"{len(slink_rows)} links")
    return reach_rows, sgroup_rows, slink_rows
