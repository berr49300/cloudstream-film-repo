import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from site_audit import PageInventory, safe_url


class SiteAuditTests(unittest.TestCase):
    def test_safe_url_drops_query_and_fragment(self):
        self.assertEqual(
            "https://example.test/watch/abc",
            safe_url("https://Example.Test/watch/abc?token=secret#player"),
        )

    def test_safe_url_resolves_relative_url(self):
        self.assertEqual(
            "https://example.test/search",
            safe_url("/search?q=private", "https://example.test/home"),
        )

    def test_rejects_non_http_schemes(self):
        self.assertIsNone(safe_url("javascript:alert(1)"))
        self.assertIsNone(safe_url("file:///etc/passwd"))

    def test_inventory_collects_metadata_without_player_paths(self):
        html = """
        <html><head>
          <title>Sample catalogue</title>
          <meta name="description" content="A sample description">
          <link rel="canonical" href="/catalogue">
          <script type="application/ld+json">{"@type":"Movie"}</script>
          <script src="/assets/site.js?v=1"></script>
        </head><body>
          <a href="/film/example?id=private">Example</a>
          <a href="/video/sample.m3u8">manifest</a>
          <iframe src="https://player.example.test/embed/secret?token=private"></iframe>
          <form method="post" action="/search?key=private"></form>
        </body></html>
        """
        parser = PageInventory("https://example.test/")
        parser.feed(html)
        report = parser.report()

        self.assertEqual("Sample catalogue", report["title"])
        self.assertEqual("A sample description", report["description"])
        self.assertEqual("https://example.test/catalogue", report["canonical"])
        self.assertIn("Movie", report["jsonld_types"])
        self.assertIn("https://example.test/assets/site.js", report["scripts"])
        self.assertEqual(["https://player.example.test"], report["embedded_origins"])
        self.assertEqual({"/film/example": 1}, report["internal_route_counts"])
        self.assertEqual(
            [{"method": "POST", "action": "https://example.test/search"}],
            report["forms"],
        )
        serialized = str(report)
        self.assertNotIn("private", serialized)
        self.assertNotIn("secret", serialized)

    def test_external_links_are_not_counted_as_internal_routes(self):
        parser = PageInventory("https://example.test/")
        parser.feed('<a href="https://other.test/path">external</a><a href="/ok">ok</a>')
        self.assertEqual({"/ok": 1}, parser.report()["internal_route_counts"])


if __name__ == "__main__":
    unittest.main()
