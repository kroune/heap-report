"""The production HTTP adapter serves only the built React artifact."""
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from tempfile import TemporaryDirectory
from pathlib import Path

from backend import http


class TestStaticFrontend(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "index.html").write_text(
            '<div id="root">built-react</div><script type="module">console.log("built")</script>')
        self.old_root = http.WEB_ROOT
        http.WEB_ROOT = str(self.root)
        self.addCleanup(self._restore_root)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), http._Handler)
        self.server.app = None
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def _restore_root(self):
        http.WEB_ROOT = self.old_root

    def test_root_and_index_serve_the_same_build(self):
        for path in ("/", "/index.html"):
            with self.subTest(path=path), urllib.request.urlopen(self.base + path, timeout=5) as response:
                self.assertEqual(response.status, 200)
                self.assertTrue(response.headers["Content-Type"].startswith("text/html"))
                self.assertIn(b"built-react", response.read())

    def test_unknown_and_development_source_are_not_served(self):
        for path in ("/missing.js", "/src/main.tsx"):
            with self.subTest(path=path):
                with self.assertRaises(urllib.error.HTTPError) as raised:
                    urllib.request.urlopen(self.base + path, timeout=5)
                self.assertEqual(raised.exception.code, 404)
                raised.exception.close()


if __name__ == "__main__":
    unittest.main()
