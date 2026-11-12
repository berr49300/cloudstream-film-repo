import io
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from email.message import Message

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provider_probe


class ProviderProbeTests(unittest.TestCase):
    def test_direct_hls_is_classified_and_signed_query_is_redacted(self):
        html = '<video><source src="/stream/master.m3u8?token=secret123"></video>'
        report = provider_probe.analyze(html, "https://video.example/watch/1")

        self.assertEqual("DIRECT_HLS", report["route_guess"])
        self.assertEqual("HTML_WITH_DIRECT_MEDIA_HINTS", report["architecture_hint"])
        self.assertEqual(1, report["candidate_count"])
        self.assertNotIn("secret123", str(report))
        self.assertIn("/stream/master.m3u8", report["candidates"][0]["url"])

    def test_iframe_is_classified_as_player_chain(self):
        html = '<html><body><iframe src="//player.example/embed/abc"></iframe></body></html>'
        report = provider_probe.analyze(html, "https://site.example/movie/1")

        self.assertEqual("IFRAME_OR_EXTRACTOR", report["route_guess"])
        self.assertEqual("IFRAME_PLAYER_CHAIN", report["architecture_hint"])
        self.assertEqual("https://player.example/embed/abc", report["candidates"][0]["url"])

    def test_hydration_json_state_is_reported(self):
        html = '<div id="__next"></div><script id="__NEXT_DATA__" type="application/json">{"props":{}}</script>'
        report = provider_probe.analyze(html, "https://site.example/movie/1")

        self.assertTrue(report["signals"]["hydration_or_json_ld"])
        self.assertEqual("EMBEDDED_STATE_OR_HYDRATED_APP", report["architecture_hint"])
        self.assertTrue(any("embedded-state/JSON-LD" in item for item in report["recommended_next_steps"]))

    def test_challenge_is_not_misclassified_as_selector_failure(self):
        html = '<html><title>Just a moment...</title><body>challenge-platform cf-chl- Cloudflare Ray ID</body></html>'
        report = provider_probe.analyze(html, "https://site.example/movie/1")

        self.assertEqual("ACCESS_RESTRICTED_OR_CHALLENGE", report["route_guess"])
        self.assertTrue(report["signals"]["access_challenge_clues"])

    def test_http_403_body_is_retained_as_evidence_without_bypassing_it(self):
        headers = Message()
        headers["Content-Type"] = "text/html; charset=utf-8"
        error = HTTPError("https://site.example/movie", 403, "Forbidden", headers, io.BytesIO(b"Access denied"))
        with patch.object(provider_probe, "urlopen", side_effect=error):
            body, status, final_url = provider_probe.fetch_html("https://site.example/movie", 2)

        self.assertEqual(403, status)
        self.assertIn("Access denied", body)
        self.assertEqual("https://site.example/movie", final_url)


if __name__ == "__main__":
    unittest.main()
