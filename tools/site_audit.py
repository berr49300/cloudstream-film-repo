"""Non-invasive public HTML reconnaissance for provider development.

This tool inventories page metadata and route shapes only. It deliberately does not
extract media URLs, enumerate player endpoints, inspect authenticated sessions, or
attempt to bypass challenges/access controls.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from html.parser import HTMLParser
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.request import Request, urlopen

MAX_BODY_BYTES = 1_048_576
USER_AGENT = "CloudStreamProviderResearch/1.0 (+public HTML metadata audit)"
MEDIA_SUFFIXES = (
    ".m3u8", ".mpd", ".mp4", ".mkv", ".webm", ".ts", ".m4s",
    ".vtt", ".srt", ".ass", ".ssa", ".mp3", ".m4a", ".aac",
)


def safe_url(value: str, base: str | None = None, *, keep_query: bool = False) -> str | None:
    """Normalize an HTTP(S) URL and remove query/fragment secrets by default."""
    if not value or value.strip().lower().startswith(("javascript:", "data:", "blob:")):
        return None
    absolute = urljoin(base, value.strip()) if base else value.strip()
    try:
        parts = urlsplit(absolute)
        if parts.scheme.lower() not in {"http", "https"} or not parts.hostname:
            return None
        host = parts.hostname.lower()
        port = parts.port
        netloc = host if port is None else f"{host}:{port}"
        path = parts.path or "/"
        query = parts.query if keep_query else ""
        return urlunsplit((parts.scheme.lower(), netloc, path, query, ""))
    except ValueError:
        return None


def origin(url: str) -> str:
    parts = urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}"


class PageInventory(HTMLParser):
    def __init__(self, page_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.page_url = page_url
        self.title = ""
        self._inside_title = False
        self.description = None
        self.canonical = None
        self.robots = None
        self.jsonld_types: set[str] = set()
        self.script_urls: set[str] = set()
        self.iframe_origins: set[str] = set()
        self.internal_routes: Counter[str] = Counter()
        self.forms: set[tuple[str, str]] = set()

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        a = {key.lower(): value for key, value in attrs}
        tag = tag.lower()
        if tag == "title":
            self._inside_title = True
        elif tag == "meta":
            name = (a.get("name") or a.get("property") or "").lower()
            content = (a.get("content") or "").strip()
            if name in {"description", "og:description"} and content:
                self.description = content[:500]
            elif name == "robots" and content:
                self.robots = content[:200]
        elif tag == "link" and "canonical" in (a.get("rel") or "").lower().split():
            self.canonical = safe_url(a.get("href") or "", self.page_url)
        elif tag == "script":
            src = safe_url(a.get("src") or "", self.page_url)
            if src:
                self.script_urls.add(src)
            if (a.get("type") or "").lower().split(";")[0].strip() == "application/ld+json":
                self._inside_jsonld = True
            else:
                self._inside_jsonld = False
        elif tag == "iframe":
            # Keep only the embedded origin, not player paths or playback parameters.
            iframe_url = safe_url(a.get("src") or "", self.page_url)
            if iframe_url:
                self.iframe_origins.add(origin(iframe_url))
        elif tag == "a":
            target = safe_url(a.get("href") or "", self.page_url)
            if target and origin(target) == origin(self.page_url):
                path = urlsplit(target).path or "/"
                if not path.lower().endswith(MEDIA_SUFFIXES):
                    self.internal_routes[path] += 1
        elif tag == "form":
            action = safe_url(a.get("action") or self.page_url, self.page_url)
            if action:
                self.forms.add(((a.get("method") or "GET").upper(), action))

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "title":
            self._inside_title = False
        elif tag.lower() == "script":
            self._inside_jsonld = False

    def handle_data(self, data: str) -> None:
        if self._inside_title and data.strip():
            self.title = (self.title + " " + data.strip()).strip()[:300]
        if getattr(self, "_inside_jsonld", False) and data.strip():
            try:
                obj = json.loads(data)
            except json.JSONDecodeError:
                return
            self._collect_jsonld_type(obj)

    def _collect_jsonld_type(self, obj) -> None:
        if isinstance(obj, dict):
            types = obj.get("@type")
            if isinstance(types, str):
                self.jsonld_types.add(types[:120])
            elif isinstance(types, list):
                self.jsonld_types.update(str(t)[:120] for t in types if isinstance(t, str))
            for value in obj.values():
                if isinstance(value, (dict, list)):
                    self._collect_jsonld_type(value)
        elif isinstance(obj, list):
            for value in obj:
                self._collect_jsonld_type(value)

    def report(self) -> dict:
        return {
            "title": self.title or None,
            "description": self.description,
            "canonical": self.canonical,
            "robots": self.robots,
            "jsonld_types": sorted(self.jsonld_types),
            "scripts": sorted(self.script_urls),
            "embedded_origins": sorted(self.iframe_origins),
            "internal_route_counts": dict(sorted(self.internal_routes.items())),
            "forms": [
                {"method": method, "action": action}
                for method, action in sorted(self.forms)
            ],
        }


def inspect_public_page(url: str, timeout: float = 12.0) -> dict:
    normalized = safe_url(url)
    if not normalized:
        raise ValueError("URL must be an absolute HTTP(S) URL without embedded credentials.")
    request = Request(normalized, headers={"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1"})
    try:
        response = urlopen(request, timeout=timeout)
    except HTTPError as exc:
        response = exc
    except (URLError, TimeoutError, OSError) as exc:
        return {"requested_url": normalized, "status": None, "error": type(exc).__name__}

    with response:
        content_type = response.headers.get("Content-Type", "")
        body = response.read(MAX_BODY_BYTES + 1)
        truncated = len(body) > MAX_BODY_BYTES
        body = body[:MAX_BODY_BYTES]
        report = {
            "requested_url": normalized,
            "final_url": safe_url(response.geturl()),
            "status": getattr(response, "status", getattr(response, "code", None)),
            "content_type": content_type.split(";", 1)[0].strip().lower(),
            "body_truncated": truncated,
            "body_bytes_read": len(body),
        }
        if "html" not in content_type.lower():
            report["html_inventory"] = None
            return report
        text = body.decode("utf-8", errors="replace")
        parser = PageInventory(report["final_url"] or normalized)
        parser.feed(text)
        report["html_inventory"] = parser.report()
        return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", help="public HTTP(S) page to inspect")
    parser.add_argument("--timeout", type=float, default=12.0, help="request timeout in seconds (default: 12)")
    args = parser.parse_args(argv)
    try:
        report = inspect_public_page(args.url, timeout=args.timeout)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report.get("status") is not None else 1


if __name__ == "__main__":
    raise SystemExit(main())
