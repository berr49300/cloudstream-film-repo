# Site Profile — Canlikolik (Haty beta)

- Reviewed: 2026-10-12
- Module: HatyCanlikolik
- Status: INVESTIGATING / beta; playback NOT TESTED
- APK evidence: CanlikolikRepository, CanlikolikConfig, CanlikolikFeedApplier.
- Public domain-family evidence: search-indexed canlikolik5.my redirects to canlikolik11.live; direct page access can be denied (403).
- Candidate URLs: https://canlikolik5.my/, https://canlikolik11.live/, https://canlikolik3.my/, https://canlikolik2.my/.

| Stage | State | Remaining evidence |
|---|---|---|
| Host family | HYPOTHESIS with public mirror evidence | Recover APK remote config/feed endpoint and validate runtime host |
| Channel/match catalog | HTML adapter implemented | Fresh raw response and comparison to source page needed |
| Search | In-memory filtering of parsed event/channel links | Completeness unverified |
| Player/source | Parses literal iframes/media URLs/manifests from HTML | Needs actual player response, headers, and extraction test |
| Subtitles | NOT TESTED | No track evidence |
| Device playback | NOT TESTED | Install artifact on CloudStream device |

No media URL, channel ID or signed query is generated. The live APK remote configuration was not recovered from static string analysis.
