# Third-party notices

The FilmMakinesi provider structure is adapted from the public [NeoWatch FilmMakinesi implementation](https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/FilmMakinesi.kt). The local provider and resolver files identify their stated license as GPL-3.0-or-later; the full license text is in this directory's `LICENSE`.

The resolver in this repository handles media sources exposed in player HTML, including the current `dc_hello` / `dc_<name>([...])` fixture format and a bounded JavaScript evaluation path. This notice does not claim that every resolver technique is original or that it is a line-for-line copy of an upstream resolver. Review the upstream source and applicable license before redistributing changes.

Site selectors and URLs describe the target site's behavior and may need updates when the public website changes. Avoid putting live third-party media URLs in tests; use deterministic reserved example domains instead.
