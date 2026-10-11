# Third-party notices

The FilmMakinesi provider structure is explicitly described in its source header as adapted from the public [NeoWatch FilmMakinesi implementation](https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/FilmMakinesi.kt). The local provider and resolver identify their license as GPL-3.0-or-later; the module's `LICENSE` contains the license text.

NeoWatch also publishes a related [FilmMakinesi resolver](https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/GizliJsOynatici.kt). The local resolver handles player-page source resolution, subtitle extraction, and bounded JavaScript evaluation, including the current `dc_hello` / `dc_<name>([...])` pattern. This notice does not claim that every technique is original or that the local resolver is a line-for-line copy. Publicly similar providers exist elsewhere in the CloudStream ecosystem, but similarity alone does not establish who authored code first or prove a direct copying relationship.

For additional provenance notes and unresolved questions, see [Code origin and license notes](../docs/CODE_ORIGIN_AND_LICENSES.md). Before redistributing adapted code, review the exact upstream revision and its applicable license. Keep site-specific URLs, selectors, categories, search routes, and runtime behavior unchanged when making documentation-only maintenance edits.

Use synthetic reserved domains such as `example.test` in tests; do not publish live third-party media URLs when a deterministic fixture is sufficient.
