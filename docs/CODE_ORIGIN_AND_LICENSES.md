# Code origin and license notes

This document records what is known from the current repository files. It is not a legal opinion and does not replace checking the exact upstream revision and its license.

## Confirmed attribution

- **FilmMakinesi provider structure:** `FilmMakinesi/src/main/kotlin/com/kra/cloudstream/filmmakinesi/FilmMakinesi.kt` explicitly states that its provider structure was adapted from the public NeoWatch FilmMakinesi implementation: https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/FilmMakinesi.kt
- **FilmMakinesi license:** The module contains its own GPL-3.0 license text and the relevant Kotlin files declare `SPDX-License-Identifier: GPL-3.0-or-later`. Preserve these notices when modifying or distributing that module.
- **Resolver comparison:** NeoWatch also has a public FilmMakinesi resolver at https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/GizliJsOynatici.kt. Similar techniques do not by themselves prove that the current resolver is a line-for-line copy. A precise provenance claim requires a revision-specific, function-by-function comparison.

## Items that still need owner verification

- **Dizilla:** The provider has been compared with public examples during development, but the current source file does not contain a clear, explicit attribution header. Confirm whether any code was adapted, and from which exact repository revision, before adding a copyright or license claim.
- **FullHDFilmizlesene:** The source currently describes the provider as independent. Keep that claim only if its implementation history supports it. Similar selectors or behavior may result from parsing the same website, so do not infer copying from functional similarity alone.
- **License scope:** The root `LICENSE` contains MIT text, while `FilmMakinesi/LICENSE` declares GPL-3.0. Treat the module-level license and source-file SPDX notices as important; do not assume the root MIT notice overrides them. If any other provider includes adapted GPL-covered code, confirm its provenance and license obligations before release.

## Maintenance rules

1. Preserve upstream copyright and license notices when required.
2. Record the upstream URL and exact revision/commit for adapted code when known.
3. Distinguish copied/adapted implementation from general ideas and site-derived facts.
4. Do not assign a license to a file or module based only on an unverified similarity.
5. Keep test fixtures synthetic; do not publish live media URLs when a reserved example URL is sufficient.
