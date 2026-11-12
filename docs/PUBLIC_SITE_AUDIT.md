# Public Site Audit Tool

`tools/site_audit.py` collects a bounded, unauthenticated snapshot of one public HTML page to make the first provider-research pass repeatable.

## Usage

```bash
python tools/site_audit.py https://example.org/
python tools/site_audit.py https://example.org/search --timeout 8
```

The JSON report includes response status/content type, title/description/canonical metadata, JSON-LD types, script paths, internal page-route counts, form actions, and **embedded origins only**. Query strings and fragments are removed so common token/signature parameters are not written to the report. The response body is capped at 1 MiB.

## Limitations

- It performs one ordinary public HTTP(S) GET; it does not execute JavaScript or emulate Chromium.
- It does not use cookies, logins, CAPTCHA solving, access-control bypasses, or DRM.
- It intentionally does not extract direct media URLs, manifests, subtitle tracks, or player endpoint paths.
- A JavaScript-only page or a network/legal restriction may produce an incomplete or blocked report. Mark such findings BLOCKED; do not infer the missing API or playback behavior.
- Never commit cookies, credentials, private tokens, or live signed URLs. Use an authorized test site and sanitized fixtures for playback-specific work.
