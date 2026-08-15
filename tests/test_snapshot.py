"""Snapshot bundler smoke test: the real web/ tree must bundle cleanly
(enforces the CONTRACTS.md module-style rules in CI, no browser needed)."""
import os
import unittest

from backend import snapshot

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class TestBundle(unittest.TestCase):
    def test_web_tree_bundles(self):
        payload = {"name": "smoke", "stats": {}, "trees": {},
                   "classes": [], "comps": {}, "anats": {}}
        html = snapshot.build_html(os.path.join(REPO, "web"), payload)
        self.assertIn("window.__INLINE__", html)
        for mod in ("_mod_data_http_js", "_mod_viz_common_js", "_mod_app_boot_js"):
            self.assertIn(mod, html)
        self.assertNotIn('<script type="module"', html)


if __name__ == "__main__":
    unittest.main()
