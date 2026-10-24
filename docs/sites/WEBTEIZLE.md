# WebteIzle Site Profile

- Base URL: https://webteizle.info/
- Date checked: 2026-10-24
- Overall status: INVESTIGATING
- Public site reachable: PARTIAL (public pages readable through web research)
- Content type: Movies
- Provider branch: feature/webteizle-provider

## Evidence ledger

| Claim | Status | Evidence | Remaining question |
|---|---|---|---|
| Home lists films and pagination | CONFIRMED | https://webteizle.info/ shows cards and page links 1–958 | Verify raw DOM and exact pagination route |
| Detail route | CONFIRMED | https://webteizle.info/hakkinda/love-language | Confirm exact poster/meta selectors |
| Language/player route | CONFIRMED | https://webteizle.info/izle/altyazi/love-language | Actual source data is not visible in rendered page text |
| Search and AJAX endpoints | HYPOTHESIS | Public reference provider uses /filtre?a=, /ajax/dataAlternatif3.asp, /ajax/dataEmbed.asp | Verify against live site |
| Player families | HYPOTHESIS | Public reference provider mentions Vidmoly, OK.ru, Filemoon, Pixeldrain, RubyVid and Dzen | Enumerate hosts from current responses; do not assume all films use them |
| Subtitles | NOT TESTED | Reference provider parses player tracks in some player families | Validate payload and CloudStream rendering |
| Real playback | NOT TESTED | No real-device test performed | User tests emitted source labels |

## Implementation notes
- Isolated module; no existing provider/shared code changed.
- No challenge, authentication, DRM bypass, or signed URL fixture.
- Catalog selectors and AJAX endpoint behavior require live response verification.
- External reference: https://github.com/nikyokki/nik-cloudstream/tree/master/WebteIzle
- The reference repository states GPL-3.0; this implementation is independently written from observed behavior and public protocol clues, not copied source.

## Resolver inventory
01 Html5SourceResolver — NOT TESTED
02 LazyDataAttributeResolver — HYPOTHESIS, live DOM pending
03 SelectedPlayerElementResolver — NOT TESTED
04 JsonLdMediaResolver — NOT TESTED
05 EmbeddedJsonResolver — NOT TESTED
06 HydrationStateResolver — NOT TESTED
07 PlayerConfigObjectResolver — NOT TESTED
08 StaticScriptUrlResolver — NOT TESTED
09 EscapedScriptUrlResolver — NOT TESTED
10 JavaScriptVariableResolver — NOT TESTED
11 JavaScriptRuntimeResolver — NOT TESTED
12 PackedScriptResolver — NOT TESTED
13 KnownEncodingResolver — NOT TESTED
14 SiteSpecificObfuscationResolver — NOT TESTED
15 IframeDiscoveryResolver — HYPOTHESIS
16 NestedIframeResolver — NOT TESTED
17 ExtractorRegistryResolver — HYPOTHESIS, loadExtractor path
18 ObservedRestApiResolver — NOT TESTED
19 ObservedAjaxResolver — HYPOTHESIS, endpoints not live-verified
20 GraphQlResolver — NOT TESTED
21 PlayerIdManifestResolver — NOT TESTED
22 RedirectChainResolver — NOT TESTED
23 HlsMasterPlaylistResolver — NOT TESTED
24 HlsVariantPlaylistResolver — NOT TESTED
25 DashManifestResolver — NOT TESTED
26 DirectMediaFileResolver — NOT TESTED
27 ContainerFormatCandidateResolver — NOT TESTED
28 SegmentPlaylistResolver — NOT TESTED
29 BrowserNetworkDiscoveryResolver — NOT TESTED
30 BlobMediaSourceDiagnostic — NOT TESTED
31 ServiceWorkerPlaybackDiagnostic — NOT TESTED
32 SignedUrlResolver — NOT TESTED; no signed URLs stored
33 HeaderContextResolver — HYPOTHESIS, headers from reference provider
34 SessionDependentSourceDiagnostic — NOT TESTED
35 AlternateAudioAndSubtitleResolver — NOT TESTED
36 GenericExtractorFallback — HYPOTHESIS, extractor registry path
37 ManualNetworkEvidenceResolver — NOT TESTED

## Test status
- Gradle build: NOT TESTED
- Unit tests: NOT TESTED
- Catalog/search parity: NOT TESTED against raw live DOM
- Source response validation: NOT TESTED
- CloudStream source list: NOT TESTED
- Manual playback: NOT TESTED
- Publication: NOT PUBLISHED
