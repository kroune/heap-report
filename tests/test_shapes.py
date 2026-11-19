"""SHP1 artifact parsing (backend/mat/shapes.py). No dumps, no MAT — the
fixture is built by hand with struct, mirroring the SHP1 artifact writer."""
import os
import struct
import tempfile
import unittest

from unittest import mock

from backend.mat.shapes import (artifact_path, parse_shp1, analyze,
                                _validate_projection)


def build_shp1(labels, shapes, edges, roots, scc_details=None):
    """Mirror Shp1ArtifactWriter, including optional SCC1 member graphs."""
    out = [b"SHP1"]
    out.append(struct.pack(">i", len(labels)))
    for l in labels:
        b = l.encode()
        out.append(struct.pack(">i", len(b)) + b)
    out.append(struct.pack(">i", len(shapes)))
    for s in shapes:
        out.append(struct.pack(">qqbi", s["hi"], s["lo"], s["kind"],
                               s["labelIdx"]))
        out.append(struct.pack(">qqqqq", s["count"], s["shallowTotal"],
                               s["shallowMin"], s["shallowMax"], s["roots"]))
        out.append(struct.pack(">iib", s["sccSize"], s["internalEdges"],
                               1 if s["edgesPerMember"] else 0))
    out.append(struct.pack(">i", len(edges)))
    for e in edges:
        out.append(struct.pack(">iiq", *e))
    out.append(struct.pack(">i", len(roots)))
    for r in roots:
        out.append(struct.pack(">iq", *r))
    if scc_details is not None:
        out.append(b"SCC1")
        out.append(struct.pack(">i", len(scc_details)))
        for detail in scc_details:
            shape_id, members = detail[:2]
            complete = detail[2] if len(detail) > 2 else True
            out.append(struct.pack(">iiB", shape_id, len(members), 1 if complete else 0))
            for member in members:
                out.append(struct.pack(">ii", member["labelIdx"], len(member["internal"])))
                for target, mult in member["internal"]:
                    out.append(struct.pack(">ii", target, mult))
    return b"".join(out)


class TestShp1(unittest.TestCase):
    def test_parse_roundtrip(self):
        fixture = build_shp1(
            ["synth.Module", "synth.Deps"],
            [{"hi": -1, "lo": 2, "kind": 1, "labelIdx": 0, "count": 30,
              "shallowTotal": 3600, "shallowMin": 120, "shallowMax": 120,
              "roots": 30, "sccSize": 1, "internalEdges": 0,
              "edgesPerMember": True},
             {"hi": 0, "lo": 0, "kind": 2, "labelIdx": 1, "count": 9,
              "shallowTotal": 700, "shallowMin": 100, "shallowMax": 100,
              "roots": 0, "sccSize": 3, "internalEdges": 4,
              "edgesPerMember": True}],
            [(0, 1, 2)],
            [(0, 30)],
        )
        with tempfile.NamedTemporaryFile(suffix=".shp1", delete=False) as f:
            f.write(fixture)
            path = f.name
        try:
            p = parse_shp1(path)
        finally:
            os.remove(path)
        self.assertEqual(len(p["shapes"]), 2)
        s0, s1 = p["shapes"]
        self.assertEqual(s0["id"], 0)
        self.assertEqual(s0["kind"], "node")
        self.assertEqual(s0["label"], "synth.Module")
        self.assertEqual(s0["digest"], "ffffffffffffffff" + "0000000000000002")
        self.assertEqual(s0["count"], 30)
        self.assertTrue(s0["edgesPerMember"])
        self.assertEqual(s1["kind"], "scc")
        self.assertEqual(s1["sccSize"], 3)
        self.assertEqual(s1["internalEdges"], 4)
        self.assertEqual(p["edges"], [[0, 1, 2]])
        self.assertEqual(p["rootsHistogram"], [[0, 30]])
        # aggregate() counts OBJECTS per shape for every kind: 30 + 9
        self.assertEqual(p["reachableCount"], 39)

    def test_parse_scc_member_graph(self):
        row = {"hi": 0, "lo": 1, "kind": 2, "labelIdx": 0, "count": 4,
               "shallowTotal": 40, "shallowMin": 5, "shallowMax": 5,
               "roots": 2, "sccSize": 2, "internalEdges": 3,
               "edgesPerMember": False}
        details = [(0, [
            {"labelIdx": 1, "internal": [[1, 2]]},
            {"labelIdx": 2, "internal": [[0, 1]]},
        ])]
        fixture = build_shp1(["scc[A,B]", "A", "B"], [row], [], [(0, 2)], details)
        with tempfile.NamedTemporaryFile(suffix=".shp1", delete=False) as f:
            f.write(fixture)
            path = f.name
        try:
            shape = parse_shp1(path)["shapes"][0]
        finally:
            os.remove(path)
        self.assertEqual(shape["members"], [
            {"label": "A", "internal": [[1, 2]]},
            {"label": "B", "internal": [[0, 1]]},
        ])
        self.assertTrue(shape["membersComplete"])

    def test_rejects_invalid_scc_member_graph(self):
        row = {"hi": 0, "lo": 1, "kind": 2, "labelIdx": 0, "count": 2,
               "shallowTotal": 10, "shallowMin": 5, "shallowMax": 5,
               "roots": 0, "sccSize": 2, "internalEdges": 2,
               "edgesPerMember": False}
        bad_target = [(0, [{"labelIdx": 1, "internal": [[2, 1]]},
                           {"labelIdx": 2, "internal": [[0, 1]]}])]
        bad_total = [(0, [{"labelIdx": 1, "internal": []},
                          {"labelIdx": 2, "internal": [[0, 1]]}])]
        duplicate = [(0, [{"labelIdx": 1, "internal": [[1, 1], [1, 1]]},
                          {"labelIdx": 2, "internal": []}])]
        for details in (bad_target, bad_total, duplicate):
            fixture = build_shp1(["scc[A,B]", "A", "B"], [row], [], [], details)
            with tempfile.NamedTemporaryFile(suffix=".shp1", delete=False) as f:
                f.write(fixture)
                path = f.name
            try:
                with self.assertRaisesRegex(RuntimeError, "SCC"):
                    parse_shp1(path)
            finally:
                os.remove(path)

    def test_incomplete_scc_graph_is_explicit_not_count_inferred(self):
        row = {"hi": 0, "lo": 1, "kind": 2, "labelIdx": 0, "count": 2,
               "shallowTotal": 10, "shallowMin": 5, "shallowMax": 5,
               "roots": 0, "sccSize": 2, "internalEdges": 2,
               "edgesPerMember": False}
        details = [(0, [{"labelIdx": 1, "internal": []},
                          {"labelIdx": 2, "internal": []}], False)]
        fixture = build_shp1(["scc[A,B]", "A", "B"], [row], [], [], details)
        with tempfile.NamedTemporaryFile(suffix=".shp1", delete=False) as f:
            f.write(fixture)
            path = f.name
        try:
            shape = parse_shp1(path)["shapes"][0]
        finally:
            os.remove(path)
        self.assertEqual(len(shape["members"]), 2)
        self.assertFalse(shape["membersComplete"])

    def test_empty_scc_section_can_be_followed_by_metadata(self):
        import json
        row = {"hi": 0, "lo": 1, "kind": 0, "labelIdx": 0, "count": 1,
               "shallowTotal": 5, "shallowMin": 5, "shallowMax": 5,
               "roots": 1, "sccSize": 1, "internalEdges": 0,
               "edgesPerMember": False}
        encoded = json.dumps({"objects": 1}).encode()
        fixture = build_shp1(["C"], [row], [], [(0, 1)], []) + b"SMD1" + \
            struct.pack(">i", len(encoded)) + encoded
        with tempfile.NamedTemporaryFile(suffix=".shp1", delete=False) as f:
            f.write(fixture)
            path = f.name
        try:
            payload = parse_shp1(path)
        finally:
            os.remove(path)
        self.assertNotIn("members", payload["shapes"][0])
        self.assertEqual(payload["analysis"], {"objects": 1})

    def test_rejects_wrong_extraction_mode(self):
        from backend.core import ApiError
        with self.assertRaises(ApiError):
            analyze(None, mock.Mock(), "heap.hprof", None, "dst", extraction_mode="retained")
        with self.assertRaises(ApiError):
            analyze(None, mock.Mock(), "heap.hprof", "C", "dst", extraction_mode="heap")

    def test_publish_projected_pipeline(self):
        import json
        row = {"hi": 0, "lo": 1, "kind": 0, "labelIdx": 0, "count": 2,
               "shallowTotal": 32, "shallowMin": 16, "shallowMax": 16,
               "roots": 2, "sccSize": 1, "internalEdges": 0, "edgesPerMember": False}
        stats = dict(scope="C", objects=2, roots=2, shallowTotal=32, edges=0,
                     nodes=2, rawNonNullRefs=1)
        calls = []
        def run(job, jobs, abort, args, what, xmx=None):
            calls.append((args, what, xmx))
            target = args[args.index("--out") + 1]
            with open(args[args.index("--stats") + 1], "w") as f:
                json.dump(stats, f)
            with open(target, "wb") as f:
                f.write(build_shp1(["C"], [row], [], [(0, 2)], []))
            with open(args[args.index("--relax-stats") + 1], "w") as f:
                json.dump({"classOnlyHiddenEdges": 0,
                           "setChildrenHiddenEdges": 0,
                           "setChildrenConcealedObjects": 0}, f)
        with tempfile.TemporaryDirectory() as d:
            dst = artifact_path(d, "C")
            with mock.patch("backend.mat.shapes._run_java", side_effect=run):
                analyze(None, mock.Mock(), "heap.hprof", "C", dst)
            p = parse_shp1(dst)
            self.assertEqual(p["analysis"]["extractionMode"],
                             {"type": "reachable", "className": "C"})
            self.assertEqual(p["analysis"]["extraction"]["rawNonNullRefs"], 1)
            self.assertEqual(p["analysis"]["pipelineVersion"], 7)
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0][0][0], "hprof-analyze")
            self.assertIn("--class", calls[0][0])
            self.assertEqual(calls[0][1], "analyze")
            self.assertEqual(os.listdir(os.path.dirname(dst)), [os.path.basename(dst)])
            bad = dict(p["analysis"], objects=3)
            with self.assertRaisesRegex(RuntimeError, "objects"):
                _validate_projection(dst, bad)

    def test_publish_heap_wide_pipeline(self):
        import json
        row = {"hi": 0, "lo": 1, "kind": 0, "labelIdx": 0, "count": 2,
               "shallowTotal": 32, "shallowMin": 16, "shallowMax": 16,
               "roots": 0, "sccSize": 1, "internalEdges": 0, "edgesPerMember": False}
        stats = dict(scope="whole-heap", objects=2, roots=0, shallowTotal=32,
                     edges=0)
        calls = []

        def run(job, jobs, abort, args, what, xmx=None):
            calls.append(args)
            target = args[args.index("--out") + 1]
            with open(args[args.index("--stats") + 1], "w") as f:
                json.dump(stats, f)
            with open(target, "wb") as f:
                f.write(build_shp1(["C"], [row], [], [], []))
            with open(args[args.index("--relax-stats") + 1], "w") as f:
                json.dump({}, f)

        with tempfile.TemporaryDirectory() as d:
            dst = artifact_path(d)
            with mock.patch("backend.mat.shapes._run_java", side_effect=run):
                analyze(None, mock.Mock(), "heap.hprof", None, dst, extraction_mode="heap")
            self.assertEqual(parse_shp1(dst)["analysis"]["extractionMode"]["type"], "heap")
            for args in calls:
                self.assertIn("--whole-heap", args)
                self.assertNotIn("--class", args)
                self.assertEqual(args[0], "hprof-analyze")

    def test_reachable_count_includes_quotient_concealed_objects(self):
        import json
        row = {"hi": 0, "lo": 1, "kind": 2, "labelIdx": 0, "count": 3,
               "shallowTotal": 30, "shallowMin": 5, "shallowMax": 5,
               "roots": 0, "sccSize": 3, "internalEdges": 2,
               "edgesPerMember": False}
        metadata = {"objects": 8, "relaxation": {"setChildrenConcealedObjects": 5}}
        encoded = json.dumps(metadata).encode()
        fixture = build_shp1(["scc[C]"], [row], [], []) + b"SMD1" + \
            struct.pack(">i", len(encoded)) + encoded
        with tempfile.NamedTemporaryFile(suffix=".shp1", delete=False) as f:
            f.write(fixture)
            path = f.name
        try:
            self.assertEqual(parse_shp1(path)["reachableCount"], 8)
        finally:
            os.remove(path)

    def test_rejects_truncated_shp1_and_invalid_edge(self):
        row = {"hi": 0, "lo": 1, "kind": 0, "labelIdx": 0, "count": 1,
               "shallowTotal": 1, "shallowMin": 1, "shallowMax": 1,
               "roots": 0, "sccSize": 1, "internalEdges": 0,
               "edgesPerMember": False}
        valid = build_shp1(["C"], [row], [], [])
        invalid_edge = build_shp1(["C"], [row], [(0, 1, 1)], [])
        for fixture in (valid[:-1], invalid_edge, valid + b"junk"):
            with tempfile.NamedTemporaryFile(suffix=".shp1", delete=False) as f:
                f.write(fixture)
                path = f.name
            try:
                with self.assertRaises(RuntimeError):
                    parse_shp1(path)
            finally:
                os.remove(path)

    def test_failed_analysis_preserves_artifact_and_cleans_workspace(self):
        with tempfile.TemporaryDirectory() as d:
            dst = artifact_path(d, "C")
            os.makedirs(os.path.dirname(dst))
            with open(dst, "wb") as f:
                f.write(b"previous artifact")
            with mock.patch("backend.mat.shapes._run_java",
                            side_effect=RuntimeError("extract failed")):
                with self.assertRaisesRegex(RuntimeError, "extract failed"):
                    analyze(None, mock.Mock(), "heap.hprof", "C", dst)
            with open(dst, "rb") as f:
                self.assertEqual(f.read(), b"previous artifact")
            self.assertEqual(os.listdir(os.path.dirname(dst)), [os.path.basename(dst)])

    def test_heap_wide_analysis_job(self):
        from backend.mat.engine import MatQueryEngine
        from backend import core
        engine = object.__new__(MatQueryEngine)
        engine._prepare_analysis = mock.Mock(return_value="heap.hprof")
        engine._jobs = mock.Mock()
        engine._jobs.submit.side_effect = lambda *args: args
        with mock.patch("backend.mat.shapes.shapes_jar", return_value="shapes.jar"):
            submitted = engine.analyze_shapes("dump")
        self.assertEqual(core.JobKind.SHAPES, submitted[0])
        self.assertEqual("whole heap", submitted[2])
        engine._prepare_analysis.assert_called_with("dump")

    def test_rejects_non_shp1(self):
        with tempfile.NamedTemporaryFile(suffix=".shp1", delete=False) as f:
            f.write(b"NOPE" + b"\x00" * 32)
            path = f.name
        try:
            with self.assertRaises(RuntimeError):
                parse_shp1(path)
        finally:
            os.remove(path)

    def test_artifact_path_deterministic(self):
        a = artifact_path("/data", "org.gradle.util.Path")
        b = artifact_path("/data", "org.gradle.util.Path")
        c = artifact_path("/data", "java.lang.String")
        self.assertEqual(a, b)
        self.assertNotEqual(a, c)
        self.assertTrue(a.startswith(os.path.join("/data", "shapes")))
        self.assertTrue(a.endswith(".shp1"))
        self.assertEqual(artifact_path("/data"),
                         os.path.join("/data", "shapes", "heap.shp1"))


if __name__ == "__main__":
    unittest.main()
