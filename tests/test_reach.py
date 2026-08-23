"""reach.py tests over synthetic in-memory graphs (no db, no MAT).

Pins the derived-table semantics: rootReach sharing (>= 2 distinct sampled
ROOTS — diamond sharing under one root does NOT count), per-class inclusive
retained (self-inclusive cone sums), split-copy keying by the frozenset of
direct holder classes, the per-class group cap + residual fold, and the
inspected class never splitting.
"""
import json
import unittest

from backend.mat import reach


def mk_src(spec, edges, ids):
    """spec: {oid: (cls, used, ret)}; edges: [(src, field, dst)] (named
    instance refs); ids: sampled root ids."""
    nodes, addr2id, refs = {}, {}, {}
    for oid, (cls, used, ret) in spec.items():
        nodes[oid] = {"addr": oid << 4, "cls": cls, "used": used, "ret": ret}
        addr2id[oid << 4] = oid
    for s, f, t in edges:
        refs.setdefault(s, []).append((f, t << 4, False))
    return {"nodes": nodes, "addr2id": addr2id, "refs": refs, "prims": {},
            "edges": {}, "edgesFull": {}, "elen": {}, "hasFullEdges": False,
            "strings": {}, "ids": ids}


def by_class(reach_rows):
    return {c: (ri, rs) for c, ri, rs in reach_rows}


def by_key(sgroup_rows):
    out = {}
    for gid, cls, hj, n, s, r, rincl, rshared in sgroup_rows:
        out[(cls, None if hj is None else tuple(json.loads(hj)))] = \
            (n, s, r, rincl, rshared)
    return out


class TestRootReachSharing(unittest.TestCase):
    """1→3→5 and 2→4→5: object 5 is reachable from BOTH sampled roots."""
    SRC = mk_src({1: ("com.Root", 10, 100), 2: ("com.Root", 10, 100),
                  3: ("com.A", 20, 50), 4: ("com.B", 30, 60),
                  5: ("com.C", 40, 40)},
                 [(1, "a", 3), (2, "b", 4), (3, "c", 5), (4, "c", 5)],
                 [1, 2])

    def test_class_reach(self):
        rr = by_class(reach.compute(self.SRC, [1, 2])[0])
        self.assertEqual(rr["com.Root"], (110, 40))   # whole graph; 5 is shared
        self.assertEqual(rr["com.A"], (60, 40))
        self.assertEqual(rr["com.B"], (70, 40))
        self.assertEqual(rr["com.C"], (40, 40))

    def test_groups_keyed_by_holder_set(self):
        gg = by_key(reach.compute(self.SRC, [1, 2])[1])
        self.assertEqual(gg[("com.C", ("com.A", "com.B"))], (1, 40, 40, 40, 40))
        self.assertEqual(gg[("com.A", ("com.Root",))], (1, 20, 50, 60, 40))
        self.assertEqual(gg[("com.B", ("com.Root",))], (1, 30, 60, 70, 40))
        # the inspected class is never split: one group even with 2 objects
        self.assertEqual(gg[("com.Root", ())], (2, 20, 200, 110, 40))

    def test_slinks_aggregate_per_group_and_field(self):
        _, sg, sl = reach.compute(self.SRC, [1, 2])
        cls_of_gid = {gid: cls for gid, cls, *_ in sg}
        got = {(cls_of_gid[s], f, cls_of_gid[t]): (n, b) for s, t, f, n, b in sl}
        self.assertEqual(got[("com.Root", "a", "com.A")], (1, 20))
        self.assertEqual(got[("com.A", "c", "com.C")], (1, 40))
        self.assertEqual(got[("com.B", "c", "com.C")], (1, 40))
        self.assertEqual(len(sl), 4)

    def test_diamond_under_one_root_is_not_shared(self):
        """z reachable twice from the SAME root: popcount 1 — not shared.
        (Class-diversity would get this wrong: A and B both reach z.)"""
        src = mk_src({1: ("com.Root", 10, 100), 2: ("com.A", 20, 20),
                      3: ("com.B", 30, 30), 4: ("com.C", 40, 40)},
                     [(1, "x", 2), (1, "y", 3), (2, "z", 4), (3, "z", 4)],
                     [1])
        rr = by_class(reach.compute(src, [1])[0])
        self.assertEqual(rr["com.C"], (40, 0))
        self.assertEqual(rr["com.Root"], (100, 0))

    def test_root_class_never_split(self):
        """Root-class objects with different holder sets stay one group."""
        src = mk_src({1: ("com.Root", 10, 100), 2: ("com.Root", 10, 100),
                      3: ("com.A", 20, 50)},
                     [(1, "peer", 2), (3, "r", 1), (2, "a", 3)],
                     [1, 2])
        gg = by_key(reach.compute(src, [1, 2])[1])
        roots = [k for k in gg if k[0] == "com.Root"]
        self.assertEqual(len(roots), 1)
        # the single root group still reports its real holder union for display
        self.assertEqual(roots[0][1], ("com.A", "com.Root"))


class TestBackrefExclusion(unittest.TestCase):
    """A child's reference UP to its parent must not make the child absorb the
    parent's weight (DependencySet.clientConfiguration in the real dumps):
    the viz answers "who retains how much" — the parent shows its own weight.
    Orientation = BFS depth from the sampled roots; edges to a shallower
    levelled object are not traversed by cone walks."""

    SRC = mk_src({1: ("com.Root", 10, 560), 2: ("com.Dep", 20, 50),
                  3: ("com.Sib", 500, 500), 4: ("com.Item", 30, 30)},
                 [(1, "dep", 2), (1, "sib", 3),          # root's children
                  (2, "clientConfiguration", 1),          # the back-reference
                  (2, "item", 4)],
                 [1])

    def test_child_excludes_parent(self):
        rr = by_class(reach.compute(self.SRC, [1])[0])
        self.assertEqual(rr["com.Dep"], (50, 0))     # dep + item only
        self.assertEqual(rr["com.Sib"], (500, 0))
        self.assertEqual(rr["com.Item"], (30, 0))

    def test_root_still_counts_everything_downward(self):
        rr = by_class(reach.compute(self.SRC, [1])[0])
        self.assertEqual(rr["com.Root"], (560, 0))   # the back-ref changes nothing up here

    def test_holders_and_links_keep_the_raw_adjacency(self):
        """Split keying still sees the parent as a direct holder of the child
        copy, and slinks still report the up-edge (they describe who
        references whom, not weight)."""
        _, sg, sl = reach.compute(self.SRC, [1])
        gg = by_key(sg)
        self.assertEqual(gg[("com.Root", ("com.Dep",))], (1, 10, 560, 560, 0))
        cls_of_gid = {gid: cls for gid, cls, *_ in sg}
        fields = {(cls_of_gid[s], cls_of_gid[t], f) for s, t, f, n, b in sl}
        self.assertIn(("com.Dep", "com.Root", "clientConfiguration"), fields)


class TestGroupCaps(unittest.TestCase):
    def test_per_class_cap_folds_into_residual(self):
        """>MAX_GROUPS_PER_CLASS holder sets for one class: top groups by used
        bytes survive, the rest fold into one residual group (holders NULL)."""
        M = reach.MAX_GROUPS_PER_CLASS
        spec = {1: ("com.Root", 10, 10)}
        edges = []
        for i in range(M + 6):   # 30 distinct holder classes for com.X
            h, x = 100 + i, 200 + i
            spec[h] = (f"com.H{i}", 10, 10)
            spec[x] = ("com.X", 10 + i, 10)   # used grows with i
            edges += [(1, f"h{i}", h), (h, "x", x)]
        src = mk_src(spec, edges, [1])
        _, sg, _ = reach.compute(src, [1])
        xgroups = [row for row in sg if row[1] == "com.X"]
        self.assertEqual(len(xgroups), M + 1)   # M survivors + 1 residual
        resid = [row for row in xgroups if row[2] is None]
        self.assertEqual(len(resid), 1)
        # the 6 smallest copies folded (i = 0..5: used 10..15)
        self.assertEqual(resid[0][3], 6)                       # n
        self.assertEqual(resid[0][4], sum(range(10, 16)))      # s
        kept = {row[2] for row in xgroups if row[2] is not None}
        self.assertNotIn(json.dumps(["com.H0"]), kept)         # smallest folded
        self.assertIn(json.dumps([f"com.H{M + 5}"]), kept)     # biggest kept

    def test_array_children_use_full_outbounds(self):
        """Array/field-less objects are traversed via their outbounds (no
        named fields) — the same rule as payloads._anatomy_build."""
        src = mk_src({1: ("com.Root", 10, 100), 2: ("java.lang.Object[]", 16, 60),
                      3: ("com.Item", 24, 24)},
                     [(1, "arr", 2)], [1])
        src["edges"][2] = [3]   # raw outbounds for the array element
        rr = by_class(reach.compute(src, [1])[0])
        self.assertEqual(rr["com.Item"], (24, 0))
        self.assertEqual(rr["com.Root"], (50, 0))
        gg = by_key(reach.compute(src, [1])[1])
        self.assertIn(("com.Item", ("java.lang.Object[]",)), gg)


if __name__ == "__main__":
    unittest.main()
