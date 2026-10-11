# New Site Reconnaissance Playbook

This playbook is the default starting point for every new provider. Its purpose is to identify the site's actual architecture before requesting sample URLs or writing provider code.

## Non-negotiable rule

**Do not assume a site's homepage, detail page, or episode page is the source of truth.** The visible page may be a shell that loads catalog, detail, search, episode and player data from APIs or embedded state. Find and record the real data flow first.

Do not say a site is understood until each important claim is marked **CONFIRMED**, **HYPOTHESIS**, or **BLOCKED** with supporting evidence. A URL alone is not evidence of the API schema or player behavior.

## Investigation order

### 1. Repository and prior-art search

- Inspect this repo's shared utilities, provider acceptance checklist, engineering notes, test fixtures and current workflow before adding code.
- Search public, maintained CloudStream provider projects for the same site domain, API names, iframe/player family and distinctive response fields. Prefer several independent references over copying a single implementation.
- Record which parts are generic concepts and which are site-specific. Never create a runtime dependency on another provider's repository or server.
- Check project license and retain attribution where code is adapted; prefer independently implementing behavior from verified protocol facts.

### 2. Public site reconnaissance — before asking the user

Use the least costly, least intrusive route first:

1. Read public home, search, category, detail and episode URLs where access is available.
2. Inspect redirects, status codes, response content types and whether returned HTML has real content or only a JavaScript shell.
3. Search page source for hydration state markers such as __NEXT_DATA__, JSON-LD, script configuration, API route names, fetch/XHR calls, iframe URLs, data-* attributes, manifests and subtitle declarations.
4. Inspect publicly served JavaScript bundles for endpoint names and request/response field usage when the HTML alone is insufficient.
5. Compare likely API requests and browser behavior with provider code from independent references.
6. Create a request-flow diagram, for example: search UI -> JSON endpoint -> detail API -> episode API -> iframe -> player config -> manifest/subtitle track.
7. Only then ask for user-provided evidence, and only for a specific gap that cannot be reproduced from public material (for example device-only behavior, region-specific output, session-only flow or an actual failing CloudStream log).

Do not ask for a random episode URL as the first diagnostic step when the public site's own routes, scripts, API calls or existing code can answer the architecture question.

### First-pass probe — classify before choosing an implementation

Run the repository's read-only probe whenever a public site URL is available:

```bash
python tools/provider_probe.py --url "https://site.example/title" --json
```

If direct fetching is blocked but a permitted browser session can save the response, save a sanitized HTML snapshot and run:

```bash
python tools/provider_probe.py --html-file page.html --base-url "https://site.example/title" --json
```

Use `architecture_hint`, `route_guess`, `signals` and `recommended_next_steps` as leads, not as proof. The probe does not execute JavaScript, render a browser, crawl the whole site, prove a media URL is playable, or bypass access controls. It deliberately removes URL query strings from reported candidates so signed tokens are not copied into reports. When the result is `ACCESS_RESTRICTED_OR_CHALLENGE`, classify the returned status/body first; do not diagnose an empty catalog or bad selector from a challenge page.

Follow the evidence in this order:
1. **HTML/data source:** server-rendered content, hydration/JSON-LD, or a JS/API shell.
2. **Catalog flow:** search/category -> detail -> episodes; record real response shapes.
3. **Player flow:** direct media -> iframe chain -> player config/API -> manifest/track.
4. **Access and playback gates:** distinguish blocked access, no candidate discovered, candidate inaccessible with headers, invalid manifest, DRM, and device codec/player errors.

Do not add a JavaScript runtime simply because a site uses JavaScript. First determine whether data is already embedded, the browser calls a public JSON endpoint, an iframe has a supported extractor, or a small deterministic decoder handles the confirmed payload. Only introduce a runtime when observed control flow genuinely requires execution and the repository's CloudStream environment supports it.

Run the probe regression tests along with repository tests:

```bash
python -m unittest tools.tests.test_provider_probe -v
python -m unittest discover -s tools/tests -v
```

### 3. Classify each request path

For catalog, search, detail, episodes, playback and subtitles, record one of:

- Server-rendered HTML
- HTML shell plus JavaScript/XHR/Fetch
- Embedded state / serialized JSON
- Public JSON API
- Pagination or GraphQL API
- iframe chain / third-party player
- Direct media or HLS/DASH manifest
- Authenticated/session-dependent flow
- Access challenge / CAPTCHA / rate limit / region restriction
- Unknown / blocked (state why)

Record request method, path pattern, required non-secret headers, content type, response shape, pagination, and where each field is consumed. Never store passwords, session cookies, private tokens, or live signed media URLs in the repository.

### 4. Identify access restrictions accurately

Separate ordinary site architecture from access restrictions:
- A 403 is not an empty catalog and does not prove the selector is wrong.
- CAPTCHA, account login, signed tokens, rate limits, DRM and regional restrictions must be recorded as access constraints.
- Do not claim success by bypassing authentication, CAPTCHA, DRM or other access controls. Prefer documented/public interfaces and permitted access. If access cannot be verified, mark that gate **BLOCKED** and continue only with clearly labelled static parsing tests.
- Do not invent request parameters, secret keys, signatures or endpoint payloads. Verify them from public client code and observed requests.

### 5. Define the smallest adapter

Before coding, map evidence to responsibilities:

| Responsibility | Evidence needed | Implementation location |
| --- | --- | --- |
| Catalog/search | real response shape, paging, item identity | site MainAPI |
| Detail/episode list | season/episode IDs and canonical URL mapping | site MainAPI |
| Source discovery | actual source field and iframe/direct URL samples | site MainAPI + shared candidate parser where applicable |
| Player resolution | iframe family, request chain, referrer/headers, media format | dedicated ExtractorApi |
| Subtitles | exact track declaration and sample URL/format/headers | player extractor/subtitle helper |
| Tests | sanitized representative fixtures and expected outputs | unit tests |

Reuse only stable, repeated behavior. Put site-specific schemas and token/signature logic in the site adapter or an explicitly named player-family extractor. Do not turn the shared parser into an untestable pile of site-specific exceptions.

## Required evidence package before implementation

Fill out the SITE_PROFILE_TEMPLATE.md and attach or record:

- One sanitized sample response per distinct response shape (catalog/search/detail/episode/source/player).
- Exact origin and request chain for each sample.
- Field map from response fields to UI/model fields.
- At least one normal path and one failure/empty path.
- Any live-access restriction and which tests it blocks.
- A list of confirmed facts, hypotheses and unanswered questions.

The package can be incomplete only when the missing evidence is explicitly marked BLOCKED or UNKNOWN; the implementation must not quietly treat it as known.

## Stop/go rules

**GO to implementation** when request/data flow and response shapes for the first vertical slice (search or catalog -> detail -> episode -> playable source) are understood well enough to write fixtures.

**GO to release** only when build/unit tests pass and runtime tests meet the project's acceptance checklist. Build success is not proof that external hosts, video or subtitles work.

**Stop and investigate** if:
- multiple source URLs produce no emitted links;
- a subtitle label is visible but no text renders;
- an API response is empty while the website shows data;
- a protected or blocked page is being mistaken for an ordinary page;
- the implementation needs guessed IDs, signatures, keys or request fields.

## Learning loop

For each incident, record symptom -> stage -> evidence -> root cause -> change -> regression test -> verification status in PROVIDER_ENGINEERING_NOTES.md. Improve this playbook when a new class of failure is discovered so future providers start with the lesson learned.
