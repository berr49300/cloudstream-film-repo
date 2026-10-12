# Haty DiziYou — evidence and limits

## Evidence collected (2026-10-12)

- THY TV APK contains `DiziyouRepository` and dedicated player parsing classes.
- A public CloudStream implementation in Kekik-cloudstream contains a DiziYou adapter. It documents an older host (`diziyou3.com`) and a source flow based on an episode page, `iframe#diziyouPlayer`, option IDs, and HLS/subtitle path patterns. This is prior art, not proof that the current host still uses that scheme.
- Current public-domain reconnaissance in this project found `https://diziyou.net/` and route examples such as `/dizi/from/` and `/bolum/from-4-sezon-10-bolum/`. Direct detail inspection was blocked with HTTP 403.
- Therefore the current host's selectors, episode markup, stream paths, subtitle paths, and playback availability remain **BLOCKED / NOT TESTED**. Do not copy the old stream URL construction into the current adapter until a live response or APK method-level evidence confirms it.

## Implementation gate

Before enabling a non-beta status:
1. Confirm the actual HTML response for a current series page and episode page.
2. Confirm how the current episode page maps to the player iframe/source.
3. Verify any source URL and subtitle declaration without committing signed URLs or session data.
4. Add sanitized fixtures for series, episode, and player page parsing.
5. Run repository CI, then test playback and subtitles in CloudStream on a device.

## Reference implementation

Prior-art source inspected:
- https://github.com/keyiflerolsun/Kekik-cloudstream/blob/master/DiziYou/src/main/kotlin/com/keyiflerolsun/DiziYou.kt

Do not copy its old domain or assume its endpoint scheme is still current.
