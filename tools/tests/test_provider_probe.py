import unittest
from urllib.parse import urlparse

from provider_probe import analyze


class ProviderProbeTests(unittest.TestCase):
    def test_resolves_relative_iframe_and_selects_extractor_route(self):
        report = analyze(
            '<html><iframe src="/embed/123"></iframe></html>',
            "https://video.example/watch/1",
        )

        self.assertEqual("IFRAME_OR_EXTRACTOR", report["route_guess"])
        self.assertIn(
            {
                "kind": "iframe",
                "url": "https://video.example/embed/123",
                "host": "video.example",
            },
            report["candidates"],
        )

    def test_detects_direct_hls_candidate(self):
        report = analyze(
            '<video><source src="/media/master.m3u8"></video>',
            "https://video.example/movie",
        )

        self.assertEqual("DIRECT_HLS", report["route_guess"])
        self.assertTrue(
            any(item["url"] == "https://video.example/media/master.m3u8"
                for item in report["candidates"])
        )

    def test_player_and_obfuscation_signals_are_reported(self):
        report = analyze(
            "<script>var player = {sources: []}; atob('eA==');</script>",
            "https://video.example/movie",
        )

        self.assertTrue(report["signals"]["player_config"])
        self.assertTrue(report["signals"]["obfuscation_clues"])
        self.assertIn(
            "Save a small representative player response as a fixture; add a bounded, deterministic decoder only for the confirmed payload format.",
            report["recommended_next_steps"],
        )

    def test_drm_clue_adds_explicit_verification_step(self):
        report = analyze(
            "<script>const licenseUrl = '/license'; const pssh = 'sample';</script>",
            "https://video.example/movie",
        )

        self.assertTrue(report["signals"]["drm_clues"])
        self.assertTrue(
            any("DRM-related tokens detected" in step
                for step in report["recommended_next_steps"])
        )

    def test_ignores_non_http_candidates_and_deduplicates_same_kind(self):
        report = analyze(
            '<iframe src="javascript:alert(1)"></iframe>'
            '<video src="data:video/mp4;base64,AAAA"></video>'
            '<video><source src="/media/clip.mp4"></video>'
            '<video><source src="/media/clip.mp4"></video>',
            "https://video.example/movie",
        )

        urls = [item["url"] for item in report["candidates"]]
        self.assertTrue(all(urlparse(url).scheme in {"http", "https"} for url in urls))
        self.assertEqual(
            1,
            sum(item["url"] == "https://video.example/media/clip.mp4"
                and item["kind"] == "media-source"
                for item in report["candidates"]),
        )


if __name__ == "__main__":
    unittest.main()
