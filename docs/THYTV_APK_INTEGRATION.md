# THY TV 5.0 APK — unified CloudStream integration

## Goal

Integrate the independently identifiable public media providers found in the supplied THY TV 5.0 APK into this repository, while keeping the existing FilmMakinesi, Dizilla, and FullHDFilmizlesene providers stable. “One roof” means one GitHub repository and one repo feed; CloudStream can still display each provider as a separate source. A single monolithic MainAPI is not the right architecture for unrelated websites and live-TV services.

This document records static evidence and the integration gates. It does **not** claim that the APK was run or that any source has been tested in CloudStream.

## Static APK findings

The APK contains code/classes named for these source families:

| Family | Evidence in APK | Initial disposition |
|---|---|---|
| FilmMakinesi | `FilmMakinesiRepository`, `FilmMakinesiConfig` | Already present in this repository; do not duplicate |
| FullHDFilmizlesene | `FullHdFilm` configuration/repository strings | Compare with existing provider before deciding whether a separate implementation is needed |
| Dizilla | `DizillaRepository`, `DizillaConfig` | Already present; preserve existing provider |
| Dizipal | `DizipalRepository`, `DizipalConfig` | Candidate for a new provider; domain configuration appears to be dynamic |
| Diziyou | `DiziyouRepository`, `DiziyouConfig` | Candidate for a new provider; domain configuration appears to be dynamic |
| Turkish123 | `Turkish123Repository`, `Turkish123Config` | Candidate for a new provider; domain configuration appears to be dynamic |
| Canlikolik | `CanlikolikRepository`, `CanlikolikConfig` | Live-TV candidate; source/host configuration must be verified |
| tabii | `TabiiTvRepository`; public site string `https://www.tabii.com/` | Public live-TV candidate; actual channel/stream response must be verified |
| CanlıTribün | `CanlitribunBaseUrl`, `RemoteDomainRepository` references | Sports source; base URL is remotely configured and not established by static strings alone |
| Papaz | `PapazRepository`, `PapazBaseUrlRepository` | Sports source; base URL is remotely configured and not established by static strings alone |

The APK also contains generic stream-detection logic for `.m3u8`, `.mpd`, `.mp4`, `.m3u`, `.ts`, and `.flv`. These patterns are evidence of parsing capability, not proof that every detected URL is valid or playable.

## Integration rules

1. Keep this repository as the single home; add each independent source as a Gradle subproject where technically appropriate.
2. Reuse the existing FilmMakinesi, Dizilla, and FullHDFilmizlesene implementations rather than introducing duplicate providers.
3. Do not invent hostnames, API paths, JSON schemas, stream URLs, request headers, or subtitle fields. For remotely configured domains, record the source of the configuration and verify a current public response before coding.
4. Implement public, ordinary access only. Do not bypass login, CAPTCHA, DRM, certificate pinning, or other access controls. Do not commit cookies, tokens, API secrets, or signed playback URLs.
5. Add sanitized fixtures and deterministic unit tests for parsers and mappings.
6. Validate each source separately: catalogue/search, detail, seasons/episodes or channel list, source resolution, playback, and subtitles where applicable.
7. A successful build is not playback verification. Report untested paths as **NOT TESTED**.
8. Do not publish to the `builds` branch as part of this integration work. Publication is a separate step.

## Work sequence

- [x] Inspect repository Gradle convention and existing provider protection rules.
- [x] Perform static string/class-name inventory of the supplied APK.
- [ ] Inspect existing providers against the APK inventory and avoid duplicates.
- [ ] Verify the current public domains/configuration for Dizipal, Diziyou, Turkish123, Canlikolik, tabii, CanlıTribün, and Papaz.
- [ ] Implement the smallest independent provider adapters using verified site behavior.
- [ ] Add sanitized tests and run the repository's documented test/build commands.
- [ ] Open a separate release/publishing request only after review and explicit approval.

## Evidence limitations

- The APK was inspected statically; it was not run.
- Class and string names show that code exists, but do not prove that a service is currently reachable or functional.
- The public base URL for tabii is visible in the APK, but the live stream API flow was not validated in this inspection.
- Several series and sports domains are supplied through a remote-domain mechanism. The current values and response schemas are **NOT CONFIRMED** from the APK strings alone.
- No new provider playback has been tested in CloudStream yet.