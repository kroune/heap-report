"""Smoke tests for the built React single-file snapshot assembly.

CI runs ``npm run build`` before the Python suite, so these tests exercise the
real Vite artifact rather than a synthetic module fixture.
"""
import os
import tempfile
import unittest

from backend import snapshot

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "web", "dist")


def _payload(name="smoke"):
    return {"name": name, "stats": {}, "trees": {}, "classes": [],
            "comps": {}, "anats": {}, "shapes": None}


class TestBundle(unittest.TestCase):
    def test_built_react_app_gets_inline_payload(self):
        html = snapshot.build_html(DIST, _payload())
        self.assertIn('id="root"', html)
        self.assertIn("window.__INLINE__", html)
        self.assertIn('"name":"smoke"', html)
        self.assertIn('<script type="module"', html)
        self.assertLess(html.index("window.__INLINE__"),
                        html.index('<script type="module"'))
        self.assertNotRegex(html, r'<script\b[^>]*\bsrc\s*=')
        self.assertNotRegex(html, r'<link\b[^>]*\brel=["\']stylesheet["\']')
        self.assertNotIn("/src/main.tsx", html)
        self.assertNotIn("sourceMappingURL", html)

    def test_payload_cannot_terminate_its_script(self):
        hostile = '</script><script id="injected">alert(1)</script>'
        html = snapshot.build_html(DIST, _payload(hostile))
        self.assertNotIn(hostile, html)
        self.assertIn('<\\/script><script id=\\"injected\\">', html)
        self.assertEqual(html.count('id="injected"'), 0)

    def test_missing_build_has_actionable_error(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(snapshot.SnapshotError, "npm ci && npm run build"):
                snapshot.build_html(root, _payload())


if __name__ == "__main__":
    unittest.main()
