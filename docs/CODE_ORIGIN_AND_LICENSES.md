# Code origin and license notes

This file is a provenance record, not a legal opinion. It separates verified statements from questions that need repository-owner confirmation. Public similarity alone does not establish who wrote a piece of code first.

## Verified in this repository

- **FilmMakinesi provider:** The header in `FilmMakinesi/src/main/kotlin/com/kra/cloudstream/filmmakinesi/FilmMakinesi.kt` explicitly says the provider structure was adapted from NeoWatch's public implementation: https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/FilmMakinesi.kt
- **FilmMakinesi resolver:** NeoWatch also publishes a related resolver: https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/GizliJsOynatici.kt. The two resolvers share a problem domain and techniques (iframe requests, subtitle extraction, JavaScript/source-variable resolution). That is not enough, by itself, to conclude every line was copied or to determine who first implemented each technique. Record exact upstream revisions and compare functions before making stronger claims.
- **Other public FilmMakinesi providers exist:** For example, a public Keyiflerolsun/Kekik-cloudstream implementation is available at https://github.com/keyiflerolsun/Kekik-cloudstream/blob/master/FilmMakinesi/src/main/kotlin/com/keyiflerolsun/FilmMakinesi.kt. It has a FilmMakinesi provider but uses different site URLs and page paths. This establishes that related implementations exist in the ecosystem; it does **not** prove NeoWatch copied from that repository or identify the earliest author.
- **Module license:** `FilmMakinesi/LICENSE` contains GPL-3.0 text and the Kotlin sources use `SPDX-License-Identifier: GPL-3.0-or-later`. Preserve applicable copyright/license notices and comply with the license when redistributing this module or covered derivative works.
- **Test fixture:** Resolver tests should use reserved synthetic domains such as `example.test`, not live media URLs.

## What we cannot currently claim

- We have not established the first author or complete origin history of NeoWatch's FilmMakinesi provider/resolver. Do not state that NeoWatch is wholly original or that it copied a specific other repository without revision/history evidence.
- **Dizilla:** The current source does not contain a clear, explicit attribution header. Confirm whether implementation code was adapted and identify the exact source/revision before adding a copyright or license claim.
- **FullHDFilmizlesene:** The source describes the provider as independent. Keep that description only if the implementation history supports it. Matching selectors or behavior can result from parsing the same website and is not proof of copying.
- **Original contributions in this repository:** A snapshot alone cannot reliably separate human-written, AI-assisted, adapted, and independently developed lines. Use commit history and author notes to record specific changes; do not label an entire provider “original” without checking its provenance.
- **License scope:** The root `LICENSE` contains MIT text, while the FilmMakinesi module has a separate GPL-3.0 license. Do not assume the root MIT notice overrides module-level licensing. For any other provider that may contain adapted code, verify provenance and license compatibility before making a definitive claim.

## Provenance rules for future site additions

1. Start from the target site's observable structure and the CloudStream API, but do not copy a provider from another repository without checking its license and attribution requirements.
2. If code is adapted, record the source repository, file URL, exact commit/revision when known, and applicable license.
3. Separate site-derived facts (domains, selectors, page routes) and common techniques from copied/adapted implementation code.
4. Preserve upstream notices where required; never invent a source attribution or declare code independent without evidence.
5. Keep test fixtures synthetic and do not include live third-party media links when a reserved example URL is sufficient.
6. When adding a provider, inspect its own license headers and the repository/module license scope before release.
