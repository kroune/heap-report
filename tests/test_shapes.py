"""SHP1 artifact parsing (backend/mat/shapes.py). No dumps, no MAT — the
fixture is built by hand with struct, mirroring experiments/shapes Emit.kt."""
import os
import struct
import tempfile
import unittest

from unittest import mock

from backend.mat.shapes import (artifact_path, parse_shp1,
                                _grph_stats, _analyze_xmx)


def build_shp1(labels, shapes, edges, roots):
    """Mirror of ShapesEmit.write: magic, label table, shapes, edges, roots
    histogram — all big-endian."""
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
