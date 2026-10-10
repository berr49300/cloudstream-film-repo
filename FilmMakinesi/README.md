# FilmMakinesi

Bağımsız CloudStream sağlayıcısı for https://filmmakinesi.to/.

The module is isolated under FilmMakinesi/; it does not edit or depend on the existing Dizilla or FullHDFilmizlesene provider source.

## Included

- Film and series cards, title search and metadata parsing.
- Season/episode link parsing with explicit numbering.
- Source collection from iframes, data-* player attributes, media tags and inline player URLs.
- Direct HLS/DASH/MP4/MKV/WebM-style media classification plus CloudStream's generic extractor fallback.
- Dedicated CloseLoad/Rapid player inspection, plus subtitles from the content and player page.
- Separate subtitle labels and ordering for Turkish, English and Turkish Forced.

## Verification status

The parser/unit-test fixtures are not live-site recordings. Automated page retrieval returned HTTP 451 in this environment, so this release must not be called runtime-verified until installed in CloudStream and tested on the user's device. Build status is recorded in the GitHub Actions workflow.

See docs/SITE_PROFILE_FILMMAKINESI.md for evidence and known gaps. This module is distributed under GPL-3.0-or-later; see LICENSE and THIRD_PARTY_NOTICES.md.
