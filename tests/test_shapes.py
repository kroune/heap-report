"""SHP1 artifact parsing (backend/mat/shapes.py). No dumps, no MAT — the
fixture is built by hand with struct, mirroring experiments/shapes Emit.kt."""
import os
import struct
import tempfile
import unittest

from unittest import mock

from backend.mat.shapes import (artifact_path, parse_shp1, analysis_options,
                                analyze_class, _validate_projection,
                                _grph_stats, _analyze_xmx)


def build_shp1(labels, shapes, edges, roots, scc_details=None):
    """Mirror ShapesEmit.write, including optional SCC1 member graphs."""
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
        row = {"hi": 0, "lo": 1, "kind": 3, "labelIdx": 0, "count": 2,
               "shallowTotal": 10, "shallowMin": 5, "shallowMax": 5,
               "roots": 0, "sccSize": 2, "internalEdges": 2,
               "edgesPerMember": False}
        details = [(0, [{"labelIdx": 1, "internal": []},
                          {"labelIdx": 2, "internal": []}], False)]
        fixture = build_shp1(["scc![A,B]", "A", "B"], [row], [], [], details)
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

    def test_analysis_options(self):
        self.assertEqual(analysis_options(), {"scope": "retained", "normalizeMaps": True})
        from backend.core import ApiError
        for scope, normalized in [("bad", True), ("retained", "false")]:
            with self.assertRaises(ApiError):
                analysis_options(scope, normalized)

    def test_publish_projected_pipeline(self):
        import json
        from contextlib import nullcontext
        row = {"hi": 0, "lo": 1, "kind": 0, "labelIdx": 0, "count": 2,
               "shallowTotal": 32, "shallowMin": 16, "shallowMax": 16,
               "roots": 2, "sccSize": 1, "internalEdges": 0, "edgesPerMember": False}
        metadata = dict(scope="retained", mapProjection="unordered-entries-v1",
                        objects=2, roots=2, shallowTotal=32, sourceEdges=1,
                        scopeBoundaryEdges=4, projectedEdges=0)
        calls = []
        def run(job, jobs, abort, args, what, xmx=None):
            calls.append((args, what, xmx))
            target = args[args.index("--out") + 1]
            if what == "extract":
                self._write_grph(target, 2)
                with open(args[args.index("--stats") + 1], "w") as f:
                    json.dump(metadata, f)
            else:
                with open(target, "wb") as f:
                    f.write(build_shp1(["C"], [row], [], [(0, 2)], []))
                with open(args[args.index("--relax-stats") + 1], "w") as f:
                    json.dump({"classOnlyHiddenEdges": 0,
                               "setChildrenHiddenEdges": 0,
                               "setChildrenConcealedObjects": 0}, f)
        with tempfile.TemporaryDirectory() as d:
            dst = artifact_path(d, "C")
            runner = mock.Mock()
            runner.index_read_hold.side_effect = lambda *a: nullcontext()
            with mock.patch("backend.mat.shapes._run_java", side_effect=run), \
                 mock.patch("backend.mat.shapes._analyze_xmx", return_value="2048m"):
                analyze_class(None, mock.Mock(), runner, d, "heap.hprof", "C", dst)
            p = parse_shp1(dst)
            self.assertEqual(p["analysis"]["scope"], "retained")
            self.assertEqual(p["analysis"]["sourceEdges"], 1)
            self.assertEqual(p["analysis"]["pipelineVersion"], 5)
            self.assertIn("--normalize-maps", calls[0][0])
            self.assertIn("--whole-graph", calls[1][0])
            self.assertEqual(os.listdir(os.path.dirname(dst)), [os.path.basename(dst)])
            bad = dict(metadata, objects=3)
            with self.assertRaisesRegex(RuntimeError, "objects"):
                _validate_projection(dst, bad)

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
        from contextlib import nullcontext
        with tempfile.TemporaryDirectory() as d:
            dst = artifact_path(d, "C")
            os.makedirs(os.path.dirname(dst))
            with open(dst, "wb") as f:
                f.write(b"previous artifact")
            runner = mock.Mock()
            runner.index_read_hold.side_effect = lambda *args: nullcontext()
            with mock.patch("backend.mat.shapes._run_java",
                            side_effect=RuntimeError("extract failed")):
                with self.assertRaisesRegex(RuntimeError, "extract failed"):
                    analyze_class(None, mock.Mock(), runner, d, "heap.hprof", "C", dst)
            with open(dst, "rb") as f:
                self.assertEqual(f.read(), b"previous artifact")
            self.assertEqual(os.listdir(os.path.dirname(dst)), [os.path.basename(dst)])

    def test_analysis_options_are_part_of_job_identity(self):
        from backend.mat.engine import MatQueryEngine
        from backend import core
        engine = object.__new__(MatQueryEngine)
        engine._prepare_analysis = mock.Mock(return_value="heap.hprof")
        engine._jobs = mock.Mock()
        engine._jobs.submit.side_effect = lambda *args: args
        with mock.patch("backend.mat.shapes.shapes_jar", return_value="shapes.jar"):
            first = engine.analyze_shapes("dump", "com.acme.C", "retained", True)
            second = engine.analyze_shapes("dump", "com.acme.C", "reachable", False)
        self.assertEqual(core.JobKind.SHAPES, first[0])
        self.assertIn("retained", first[2])
        self.assertIn("normalized", first[2])
        self.assertIn("reachable", second[2])
        self.assertIn("raw", second[2])
        self.assertNotEqual(first[2], second[2])

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

    def _write_grph(self, path, n, refs_per_node=0):
        """Minimal GRPH: magic, sourceN, 2-class table, n, then n rows.
        For big n the body is faked with truncate (sizing only needs the
        header + the file size)."""
        buf = [struct.pack(">i", 0x47525048), struct.pack(">q", 260_000_000)]
        buf.append(struct.pack(">i", 2))
        for name in (b"java.lang.String", b"byte[]"):
            buf.append(struct.pack(">i", len(name)) + name)
        buf.append(struct.pack(">i", n))
        hdr = sum(len(b) for b in buf)
        row = struct.pack(">iqi", 1, 32, refs_per_node)
        with open(path, "wb") as f:
            f.write(b"".join(buf))
            if n > 1_000_000:
                f.truncate(hdr + n * (16 + 4 * refs_per_node))
                return
            for _ in range(n):
                f.write(row)
                if refs_per_node:
                    f.write(struct.pack(">i", 0) * refs_per_node)

    def test_grph_stats(self):
        with tempfile.NamedTemporaryFile(suffix=".grph", delete=False) as f:
            path = f.name
        try:
            self._write_grph(path, 100, refs_per_node=3)
            n, e = _grph_stats(path)
            self.assertEqual(n, 100)
            self.assertEqual(e, 300)
            self._write_grph(path, 15_800_000, refs_per_node=1)  # truncated body
            n, e = _grph_stats(path)
            self.assertEqual(n, 15_800_000)
            self.assertEqual(e, 15_800_000)
        finally:
            os.remove(path)

    def test_grph_stats_rejects_malformed_body(self):
        with tempfile.NamedTemporaryFile(suffix=".grph", delete=False) as f:
            path = f.name
        try:
            self._write_grph(path, 2)
            with open(path, "ab") as f:
                f.write(b"x")
            with self.assertRaisesRegex(RuntimeError, "body size"):
                _grph_stats(path)
        finally:
            os.remove(path)

    def test_analyze_xmx_sizing(self):
        with tempfile.NamedTemporaryFile(suffix=".grph", delete=False) as f:
            path = f.name
        try:
            self._write_grph(path, 3_000_000)
            # 3M objects -> the 2g floor (24n + 4E + 1g < 2g)
            with mock.patch("backend.mat.shapes._mem_available",
                            return_value=64 << 30):
                self.assertEqual(_analyze_xmx(path), "2048m")
                self._write_grph(path, 200_000_000)
                mb = int(_analyze_xmx(path)[:-1])
                self.assertGreaterEqual(mb * (1 << 20),
                                        200_000_000 * 20 + (1 << 30))
                self.assertEqual(mb % 256, 0)
                # needs ~5.8 GB but only 2 GB available -> fail fast, no thrash
            with mock.patch("backend.mat.shapes._mem_available",
                            return_value=2 << 30):
                with self.assertRaises(RuntimeError) as ctx:
                    _analyze_xmx(path, "com.acme.Hub")
                self.assertIn("com.acme.Hub", str(ctx.exception))
                self.assertIn("SHAPES_XMX_ANALYZE", str(ctx.exception))
        finally:
            os.remove(path)


if __name__ == "__main__":
    unittest.main()
