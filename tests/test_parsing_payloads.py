"""Pure-builder tests: parsing.py / payloads.py / extract.py query-shape helpers.
No dumps, no MAT, no network — tiny in-memory fixtures only."""
import os
import tempfile
import unittest

from backend.mat.parsing import (_merge_fams, _parse_dom, _parse_fields_dump,
                                 _parse_hist, cat_of, norm_lambda, split_pkg)
from backend.mat.payloads import (HIST_MIN_SHALLOW, _anatomy_build,
                                  _anatomy_diff, _class_table_build,
                                  _finish_agg, _flatten_anat, _new_agg,
                                  _trees_build, _waterfall)
from backend.mat.extract import sample_even, subselect, suffix


class TestHelpers(unittest.TestCase):
    def test_cat_of(self):
        self.assertEqual(cat_of("org.gradle.api.Foo"), "gradle")
        self.assertEqual(cat_of("com.android.build.Foo"), "agp")
        self.assertEqual(cat_of("org.jetbrains.kotlin.Foo"), "kotlin")
        self.assertEqual(cat_of("java.util.HashMap"), "jdk")
        self.assertEqual(cat_of("byte[]"), "jdk")          # no dot
        self.assertEqual(cat_of("javax.persistence.Foo"), "other")
        self.assertEqual(cat_of("com.squareup.Okio"), "other")

    def test_norm_lambda_and_merge(self):
        self.assertEqual(norm_lambda("a.B$$Lambda+0xdead"), "a.B$$Lambda*")
        fams = _merge_fams([("a.B$$Lambda+0x1", 2, 10),
                            ("a.B$$Lambda+0x2", 3, 20),
                            ("a.C", 1, 5)], 2)
        self.assertEqual(fams["a.B$$Lambda*"], [5, 30])
        self.assertEqual(fams["a.C"], [1, 5])

    def test_split_pkg(self):
        self.assertEqual(split_pkg("a.b.C"), ("a.b", "C"))
        self.assertEqual(split_pkg("C"), ("(no package)", "C"))

    def test_parse_fields_dump(self):
        out = _parse_fields_dump("[ref a:\tnull, boolean b:\tfalse, ref c:\t0x123]")
        self.assertEqual(out, [("ref", "a", "null"), ("boolean", "b", "false"),
                               ("ref", "c", "0x123")])
        self.assertEqual(_parse_fields_dump("not a dump"), [])


class TestCsvParsing(unittest.TestCase):
    def test_hist_and_dom(self):
        with tempfile.TemporaryDirectory() as d:
            hp = os.path.join(d, "histogram.csv")
            with open(hp, "w") as f:
                f.write("Class Name,Objects,Shallow Heap\n"
                        "com.a.A,10,1000\n"
                        "bad,row\n")
            dp = os.path.join(d, "dominator_by_class.csv")
            with open(dp, "w") as f:
                f.write("Class Name,Objects,Shallow Heap,Retained Heap,x\n"
                        "com.a.A,10,1000,5000,z\n")
            self.assertEqual(_parse_hist(hp), [("com.a.A", 10, 1000)])
            self.assertEqual(_parse_dom(dp), [("com.a.A", 10, 1000, 5000)])


class TestTreesAndTable(unittest.TestCase):
    HIST = [("org.gradle.Big", 5, HIST_MIN_SHALLOW + 1),
            ("org.gradle.Tiny", 3, 10)]         # folds into "· other ·"
    DOM = [("org.gradle.Big", 5, HIST_MIN_SHALLOW + 1, 9000)]

    def test_trees_fold_small_classes(self):
        trees = _trees_build(self.HIST, self.DOM)
        self.assertIn("dom", trees)
        hist_kids = trees["hist"]["children"][0]["children"][0]["children"]
        names = {lf["name"] for lf in hist_kids}
        self.assertIn("Big", names)
        self.assertTrue(any(n.startswith("· other") for n in names))

    def test_class_table(self):
        idx = {"org.gradle.Big": {"key": "Big", "comp": True, "anat": [32]}}
        tots = {"org.gradle.Big": (9000, 5, 3)}
        rows = _class_table_build(self.HIST + [("a.B$$Lambda+0x1", 2, 10)], idx, tots)
        by_disp = {r["disp"]: r for r in rows}
        big = by_disp["org.gradle.Big"]
        self.assertTrue(big["comp"])
        self.assertEqual(big["anat"], [32])
        self.assertEqual(big["r"], 9000)
        self.assertEqual(big["cat"], "gradle")
        lam = by_disp["a.B$$Lambda*"]
        self.assertFalse(lam["analyzable"])
        self.assertEqual(lam["lams"], [["a.B$$Lambda+0x1", 2, 10]])


class TestAnatomyAndCompare(unittest.TestCase):
    SRC = {
        "nodes": {1: {"addr": 0x100, "cls": "com.x.Holder", "used": 24, "ret": 100},
                  2: {"addr": 0x200, "cls": "java.lang.String", "used": 32, "ret": 32}},
        "addr2id": {0x100: 1, 0x200: 2},
        "refs": {1: [("name", 0x200, False)]},
        "prims": {1: [("count", "5", False)]},
        "edges": {}, "edgesFull": {}, "elen": {}, "hasFullEdges": False,
        "strings": {0x200: "hello"},
        "ids": [1],
    }

    def test_anatomy(self):
        out = _anatomy_build(self.SRC, "com.x.Holder", 1, [1], 32, 40)
        self.assertEqual(out["roots"], 1)
        tree = out["tree"]
        self.assertEqual(tree["n"], 1)
        kids = {k["name"]: k for k in tree["kids"]}
        self.assertIn('name: "hello"', kids)
        self.assertEqual(kids['name: "hello"']["pres"], 1)   # non-null in 1/1 samples
        self.assertIn("count: 5", kids)

    def test_finish_agg_fold_keeps_kids(self):
        root = _new_agg("root", "com.x.Root")
        for i in range(5):   # r descending: k0=50 … k4=10
            k = _new_agg(f"k{i}", "com.x.K")
            k["n"], k["s"], k["r"] = 1, 10 * (5 - i), 10 * (5 - i)
            root["kids"][f"k{i}"] = k
        out = _finish_agg(root, 3)
        self.assertEqual([k["name"] for k in out["kids"]],
                         ["k0", "k1", "k2", "· 2 more"])
        more = out["kids"][-1]
        self.assertEqual(more["r"], 30)   # fold sums the overflow…
        self.assertEqual([k["name"] for k in more["kids"]], ["k3", "k4"])   # …and keeps it

    def test_anatomy_diff(self):
        a = _anatomy_build(self.SRC, "com.x.Holder", 1, [1], 32, 40)
        b_src = dict(self.SRC, nodes={**self.SRC["nodes"],
                                      1: {"addr": 0x100, "cls": "com.x.Holder",
                                          "used": 24, "ret": 300}})
        b = _anatomy_build(b_src, "com.x.Holder", 1, [1], 32, 40)
        d = _anatomy_diff(a, b)
        self.assertIsNotNone(d)
        holder_rows = [r for r in d["rows"] if r[0].endswith("/Holder")]
        self.assertTrue(holder_rows)
        self.assertEqual(holder_rows[0][6], 200)   # Δ retained

    def test_anatomy_diff_fold_transparent(self):
        # max_kids=1 forces "· N more" folds; the diff must match the unfolded one
        a = _anatomy_build(self.SRC, "com.x.Holder", 1, [1], 32, 1)
        b = _anatomy_build(self.SRC, "com.x.Holder", 1, [1], 32, 40)
        self.assertEqual(_anatomy_diff(a, b)["rows"], [])   # identical trees → no rows…
        fa, fb = {}, {}
        _flatten_anat(a["tree"], "", fa)
        _flatten_anat(b["tree"], "", fb)
        self.assertEqual(fa, fb)                      # …and identical flattening

    def test_waterfall_tails(self):
        rows = [[f"c{i}", 0, 0, 0, 0, 0, (i - 12) * 100] for i in range(25)]
        w = _waterfall(rows, top=10)
        self.assertEqual(len(w["freed"]), 10)
        self.assertEqual(w["freedRestN"], 2)       # 12 shrinkers, 10 shown
        self.assertEqual(w["absorbedRestN"], 2)    # 12 growers, 10 shown
        self.assertEqual(w["freedSum"], sum(r[6] for r in rows if r[6] < 0))


class TestQueryShapes(unittest.TestCase):
    def test_suffix_cap(self):
        self.assertLessEqual(len(suffix("rs", "k" * 100)), 20)
        self.assertEqual(suffix("rs", "short"), "rs_short")
        # long keys hash — no collision between different long keys
        self.assertNotEqual(suffix("rs", "k" * 100), suffix("rs", "k" * 99 + "x"))

    def test_sample_even(self):
        ids = list(range(1000))
        picked = sample_even(ids, 32)
        self.assertEqual(len(picked), 32)
        self.assertEqual(picked[0], 0)
        self.assertEqual(picked[-1], 999)
        self.assertEqual(sample_even([1, 2], 32), [1, 2])   # fewer than k
        self.assertEqual(sample_even(ids, 1), [0])

    def test_subselect(self):
        self.assertEqual(subselect("com.a.A", [7, 9]),
                         "SELECT AS RETAINED SET * FROM INSTANCEOF com.a.A s "
                         "WHERE s.@objectId = 7 or s.@objectId = 9")


if __name__ == "__main__":
    unittest.main()
