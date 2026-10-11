#!/usr/bin/env python3
"""Heuristic, read-only first-pass probe for a video-provider HTML page.

This is a developer diagnostic tool, not a player and not an anti-bot bypass.
It fetches ordinary HTML (or reads a saved HTML file), records concrete signals,
and suggests which resolver path to investigate next. All findings are heuristic.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen


MEDIA_EXTENSIONS = (".m3u8", ".mp4", ".mpd", ".m4v", ".webm", ".mp3", ".vtt", ".srt")
URL_RE = re.compile(r"""(?i)(?:https?:)?//[^\s\'"<>\\]+""")
PACKER_RE = re.compile(r"eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d")
PLAYER_CONFIG_RE = re.compile(r"""(?is)(sources\s*:\s*\[|['"]sources['"]\s*:|file_link\s*=|['"]file['"]\s*:\s*['"]https?://)""")
API_RE = re.compile(r"(?i)(fetch\s*\(|axios\.|XMLHttpRequest|/api/|\.json(?:\?|['\" ])|graphql)")
OBFUSCATION_RE = re.compile(r"(?i)(atob\s*\(|btoa\s*\(|fromCharCode|\bdecrypt\b|crypto-js|\bbase64\b|dc_hello|dc_\w+\s*\()")
DRM_RE = re.compile(r"(?i)(widevine|pssh|licenseUrl|license_url|drm\s*:|com\.widevine|application/dash\+xml)")
JS_RUNTIME_RE = re.compile(r"(?i)(document\.write|createElement\(['\"]script|eval\s*\(|new Function)")
HYDRATION_RE = re.compile(r"(?i)(__NEXT_DATA__|__NUXT__|__INITIAL_STATE__|__APOLLO_STATE__|__REACT_QUERY_STATE__|application/ld\+json|window\.__INITIAL_STATE__)")
CHALLENGE_RE = re.compile(r"(?i)(cf-chl-|challenge-platform|cloudflare ray id|checking your browser|verify you are human|captcha|access denied|just a moment\.\.\.)")
ACCESS_LIMIT_RE = re.compile(r"(?i)(too many requests|rate limit exceeded|sign in to continue|log in to continue|temporarily blocked)")


class PageSignals(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.links: list[dict[str, str]] = []
        self.script_count = 0
        self.inline_script_chunks: list[str] = []
        self._in_script = False
        self._script_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key.lower(): value or "" for key, value in attrs}
        if tag.lower() == "script":
            self.script_count += 1
            self._in_script = True
            self._script_parts = []
            src = values.get("src", "")
            if src:
                self.links.append({"kind": "script", "url": src})
        for attr, value in values.items():
            if attr in {"src", "data-src", "data-video_url", "data-video-url", "href", "content", "file"} and value:
                if attr == "href" and tag.lower() not in {"iframe", "a", "source", "video", "link"}:
                    continue
                kind = {
                    "data-video_url": "video-config",
                    "data-video-url": "video-config",
                    "data-src": "lazy-source",
                    "file": "file-config",
                    "src": "iframe" if tag.lower() == "iframe" else "media-source" if tag.lower() in {"source", "video"} else "asset",
                    "href": "link",
                    "content": "meta-content",
                }[attr]
                self.links.append({"kind": kind, "url": value})

    def handle_data(self, data: str) -> None:
        if self._in_script:
            self._script_parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "script" and self._in_script:
            body = "".join(self._script_parts)
            if body:
                self.inline_script_chunks.append(body)
            self._in_script = False
            self._script_parts = []


def fetch_html(url: str, timeout: int) -> tuple[str, int, str]:
    request = Request(url, headers={"User-Agent": "ProviderProbe/1.0 (read-only diagnostics)", "Accept": "text/html,application/xhtml+xml"})
    try:
        with urlopen(request, timeout=timeout) as response:
            charset = response.headers.get_content_charset() or "utf-8"
            return response.read(4_000_000).decode(charset, errors="replace"), response.status, response.geturl()
    except HTTPError as exc:
        # Keep the status/body as evidence so a challenge is not mistaken for a selector bug.
        charset = exc.headers.get_content_charset() or "utf-8"
        body = exc.read(4_000_000).decode(charset, errors="replace")
        return body, exc.code, exc.geturl()
    except URLError as exc:
        raise RuntimeError(f"Could not fetch {url}: {exc.reason}") from exc


def absolute(base: str, candidate: str) -> str | None:
    candidate = candidate.strip().replace("\\/", "/")
    if not candidate or candidate.startswith(("javascript:", "data:", "#")):
        return None
    resolved = urljoin(base, candidate)
    parsed = urlparse(resolved)
    if parsed.scheme in {"http", "https"} and parsed.netloc:
        return resolved
    return None


def analyze(html: str, base_url: str) -> dict:
    parser = PageSignals()
    parser.feed(html)
    script_text = "\n".join(parser.inline_script_chunks)
    combined = html + "\n" + script_text

    candidates: list[dict[str, str]] = []
    for item in parser.links:
        resolved = absolute(base_url, item["url"])
        if resolved:
            kind = item["kind"]
            candidates.append({"kind": kind, "url": resolved, "host": urlparse(resolved).netloc.lower()})

    for raw in URL_RE.findall(combined):
        resolved = absolute(base_url, raw)
        if resolved and any(urlparse(resolved).path.lower().endswith(ext) for ext in MEDIA_EXTENSIONS):
            candidates.append({"kind": "media-pattern", "url": resolved, "host": urlparse(resolved).netloc.lower()})

    # Stable de-duplication; one URL may legitimately have multiple kinds.
    seen: set[tuple[str, str]] = set()
    unique: list[dict[str, str]] = []
    for item in candidates:
        key = (item["kind"], item["url"])
        if key not in seen:
            seen.add(key)
            unique.append(item)

    media = [x for x in unique if x["kind"] in {"media-source", "video-config", "file-config", "media-pattern"}]
    challenge_detected = bool(CHALLENGE_RE.search(combined))
    access_limit_detected = bool(ACCESS_LIMIT_RE.search(combined))
    hydration_detected = bool(HYDRATION_RE.search(combined))

    if challenge_detected or access_limit_detected:
        route = "ACCESS_RESTRICTED_OR_CHALLENGE"
        advice = "Challenge/access-limit clues found. Record status and response evidence; do not treat this as an ordinary selector failure or attempt to bypass access controls."
    elif any(urlparse(x["url"]).path.lower().endswith(".m3u8") for x in media):
        route = "DIRECT_HLS"
        advice = "Manifest URL found. Check request headers/referer, response status and whether the manifest itself parses."
    elif any(urlparse(x["url"]).path.lower().endswith(".mp4") for x in media):
        route = "DIRECT_FILE"
        advice = "Direct video candidate found. Validate content type and range-request behavior."
    elif any(x["kind"] == "iframe" for x in unique):
        route = "IFRAME_OR_EXTRACTOR"
        advice = "Follow each iframe in order. Reuse a known CloudStream extractor for supported hosts; inspect unknown hosts separately."
    elif PLAYER_CONFIG_RE.search(combined):
        route = "PLAYER_CONFIG_OR_SCRIPT"
        advice = "Player configuration is present but no obvious direct media URL surfaced. Inspect the variable/data flow before adding a JS runtime."
    else:
        route = "NO_OBVIOUS_PLAYER_SIGNAL"
        advice = "No reliable player signal in this static response. Check whether the page is a challenge, needs a normal session, or loads the player after interaction."
 
    signals = {
        "packed_js": bool(PACKER_RE.search(combined)),
        "player_config": bool(PLAYER_CONFIG_RE.search(combined)),
        "api_or_xhr_clues": bool(API_RE.search(combined)),
        "obfuscation_clues": bool(OBFUSCATION_RE.search(combined)),
        "dynamic_js_clues": bool(JS_RUNTIME_RE.search(combined)),
        "drm_clues": bool(DRM_RE.search(combined)),
        "hydration_or_json_ld": hydration_detected,
        "access_challenge_clues": challenge_detected,
        "rate_limit_or_login_clues": access_limit_detected,
        "external_scripts": any(x["kind"] == "script" for x in unique),
    }
    if challenge_detected or access_limit_detected:
        architecture = "ACCESS_RESTRICTED_OR_CHALLENGE"
    elif hydration_detected:
        architecture = "EMBEDDED_STATE_OR_HYDRATED_APP"
    elif any(x["kind"] == "iframe" for x in unique):
        architecture = "IFRAME_PLAYER_CHAIN"
    elif signals["api_or_xhr_clues"] and parser.script_count:
        architecture = "HTML_PLUS_SCRIPTED_API_HINTS"
    elif media:
        architecture = "HTML_WITH_DIRECT_MEDIA_HINTS"
    elif parser.script_count:
        architecture = "SCRIPT_DRIVEN_OR_UNKNOWN"
    else:
        architecture = "SERVER_HTML_OR_UNKNOWN"

    next_steps = []
    if challenge_detected or access_limit_detected:
        next_steps.append("Classify the HTTP status and restriction type first. Continue only with public/permitted responses; this probe does not bypass CAPTCHA, login, rate limits or other access controls.")
    if hydration_detected:
        next_steps.append("Inspect the embedded-state/JSON-LD schema and map actual fields to models before adding selectors or inventing an API.")
    if signals["drm_clues"]:
        next_steps.append("DRM-related tokens detected: confirm the service is intended to be playable in CloudStream before writing an extractor.")
    if signals["packed_js"] or signals["obfuscation_clues"]:
        next_steps.append("Save a small representative player response as a fixture; add a bounded, deterministic decoder only for the confirmed payload format.")
    if signals["api_or_xhr_clues"]:
        next_steps.append("Identify the actual request and response schema from an authorized browser session; do not guess API endpoints from names alone.")
    if not media and not any(x["kind"] == "iframe" for x in unique):
        next_steps.append("Capture the page after the player is selected and compare the initial HTML with the player response.")
    next_steps.append("Test separately: candidate discovered, candidate HTTP-accessible with required headers, manifest valid, and playback verified on a real device.")

    return {
        "page": base_url,
        "route_guess": route,
        "architecture_hint": architecture,
        "confidence": "heuristic; verify each step",
        "script_count": parser.script_count,
        "signals": signals,
        "candidate_count": len(unique),
        # Strip query parameters/fragments from reports to avoid leaking signed URLs.
        "candidates": [{**item, "url": urlparse(item["url"])._replace(query="", fragment="").geturl()} for item in unique[:100]],
        "recommended_next_steps": next_steps,
        "summary": advice,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    source = ap.add_mutually_exclusive_group(required=True)
    source.add_argument("--url", help="URL whose ordinary HTML response should be inspected")
    source.add_argument("--html-file", type=Path, help="path to a saved HTML response")
    ap.add_argument("--base-url", help="base URL used to resolve relative URLs in --html-file input")
    ap.add_argument("--timeout", type=int, default=15)
    ap.add_argument("--json", action="store_true", help="print machine-readable JSON")
    args = ap.parse_args()

    try:
        if args.url:
            html, status, final_url = fetch_html(args.url, args.timeout)
            report = analyze(html, final_url)
            report["http_status"] = status
            if status in {401, 403, 429, 503}:
                report["http_access_status"] = "ACCESS_DENIED_RATE_LIMITED_OR_CHALLENGE"
                report["route_guess"] = "ACCESS_RESTRICTED_OR_CHALLENGE"
                report["summary"] = f"HTTP {status} returned. Treat this as an access/server response until proven otherwise, not as a parsing failure."
                report["recommended_next_steps"].insert(0, "Inspect the returned status/body and compare with a permitted browser response. Do not attempt to bypass login, CAPTCHA or access controls.")
        else:
            html = args.html_file.read_text(encoding="utf-8", errors="replace")
            base = args.base_url or "https://example.invalid/"
            report = analyze(html, base)
            report["input_file"] = str(args.html_file)
    except (OSError, RuntimeError, ValueError) as exc:
        print(f"Probe failed: {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return 0

    print(f"Page: {report['page']}")
    if "http_status" in report:
        print(f"HTTP status: {report['http_status']}")
    print(f"Likely route: {report['route_guess']} ({report['confidence']})")
    print(f"Architecture hint: {report['architecture_hint']}")
    print(f"Inline/external script count: {report['script_count']}")
    print(f"Candidates: {report['candidate_count']}")
    print("Signals:")
    for name, detected in report["signals"].items():
        print(f"  {'YES' if detected else 'no '}  {name}")
    for item in report["candidates"]:
        print(f"  [{item['kind']}] {item['url']}")
    print(f"Next: {report['summary']}")
    for step in report["recommended_next_steps"]:
        print(f"  - {step}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
