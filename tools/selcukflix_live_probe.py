#!/usr/bin/env python3
"""Read-only live smoke test for SelcukFlix page/source availability.

Uses ordinary HTTP requests only. It never solves CAPTCHA/Cloudflare challenges,
replays browser cookies, or attempts to evade access controls. It intentionally
logs source hosts and paths without query tokens.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import html
import ipaddress
import json
import re
import subprocess
from html.parser import HTMLParser
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urljoin, urlparse
from urllib.request import Request, urlopen

DEFAULT_URL = "https://selcukflix.app/film/animals-2026-21/izle"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
CHALLENGE = re.compile(
    r"(?is)(cf-chl-|challenge-platform|/cdn-cgi/challenge-platform|"
    r"verify you are human|just a moment\.\.\.|checking your browser|"
    r"attention required!\s*\|\s*cloudflare|captcha|access denied|error 1020|error 1015)"
)
MEDIA_EXT = re.compile(r"(?i)\.(m3u8|mpd|mp4|m4v|webm|mkv)(?:$|[?#])")


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.scripts: dict[str, str] = {}
        self.iframes: list[str] = []
        self._script_id: str | None = None
        self._script_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag.lower() == "script":
            self._script_id = a.get("id", "")
            self._script_parts = []
        if tag.lower() == "iframe":
            value = a.get("src") or a.get("data-src") or a.get("data-lazy-src")
            if value:
                self.iframes.append(html.unescape(value.strip()))

    def handle_data(self, data: str) -> None:
        if self._script_id is not None:
            self._script_parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "script" and self._script_id is not None:
            self.scripts[self._script_id] = "".join(self._script_parts)
            self._script_id = None
            self._script_parts = []


def safe_url(url: str) -> str:
    p = urlparse(url)
    return f"{p.scheme}://{p.hostname or ''}{p.path}"


def request_bytes(url: str, referer: str | None = None, ranged: bool = False) -> dict:
    headers = {"User-Agent": UA, "Accept": "text/html,application/json,*/*"}
    if referer:
        headers["Referer"] = referer
    if ranged:
        headers["Range"] = "bytes=0-2047"
    req = Request(url, headers=headers)
    try:
        with urlopen(req, timeout=15) as response:
            body = response.read(2048 if ranged else 2_000_000)
            text = body.decode("utf-8", errors="replace")
            return {
                "status": response.status,
                "content_type": response.headers.get("Content-Type", ""),
                "final_url": response.geturl(),
                "body": body,
                "text": text,
                "challenge": bool(CHALLENGE.search(text)),
                "error": None,
            }
    except HTTPError as exc:
        body = exc.read(2048)
        text = body.decode("utf-8", errors="replace")
        return {
            "status": exc.code,
            "content_type": exc.headers.get("Content-Type", ""),
            "final_url": exc.geturl(),
            "body": body,
            "text": text,
            "challenge": bool(CHALLENGE.search(text)) or exc.code in (403, 429, 503),
            "error": f"HTTP {exc.code}",
        }
    except (URLError, TimeoutError, OSError) as exc:
        return {
            "status": None, "content_type": "", "final_url": url,
            "body": b"", "text": "", "challenge": False,
            "error": f"{type(exc).__name__}: {exc}",
        }


def decode_payloads(html_text: str, scripts: dict[str, str]) -> list[object]:
    """Decode Next.js state and the site's observed AES-CBC secureData envelope."""
    roots: list[object] = []
    next_text = scripts.get("__NEXT_DATA__", "")
    if next_text:
        try:
            root = json.loads(next_text)
            roots.append(root)
            secure = root.get("props", {}).get("pageProps", {}).get("secureData")
            if isinstance(secure, str):
                # Some deployments use plain JSON or base64-encoded JSON; try those first.
                for candidate in (secure,):
                    try:
                        roots.append(json.loads(candidate))
                    except Exception:
                        pass
                try:
                    raw = base64.b64decode(secure + "=" * (-len(secure) % 4), altchars=b"-_", validate=False)
                except Exception:
                    raw = b""
                try:
                    roots.append(json.loads(raw.decode("utf-8")))
                except Exception:
                    pass

                # Current public provider implementations parse secureData as AES-256-CBC,
                # fixed zero IV. This is payload decoding, not challenge solving or access-control
                # bypass. If OpenSSL is unavailable, the regular JSON/base64 paths still run.
                keys = [b"9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"]
                derived = base64.b64encode(hashlib.sha256(b"!!22xx!!90!!").digest())[:32]
                if derived not in keys:
                    keys.append(derived)
                if raw:
                    for key in keys:
                        try:
                            proc = subprocess.run(
                                [
                                    "openssl", "enc", "-d", "-aes-256-cbc",
                                    "-K", key.hex(), "-iv", "00" * 16,
                                ],
                                input=raw,
                                stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL,
                                check=True,
                                timeout=5,
                            )
                            decrypted = proc.stdout.decode("utf-8").strip()
                            if not (
                                decrypted.startswith("{") or decrypted.startswith("[") or
                                "source_content" in decrypted.lower() or "<iframe" in decrypted.lower()
                            ):
                                continue
                            try:
                                roots.append(json.loads(decrypted))
                            except Exception:
                                # Keep only structured JSON for recursive source walking.
                                continue
                            break
                        except (OSError, subprocess.SubprocessError, UnicodeDecodeError):
                            continue
        except Exception:
            pass
    # Some providers expose source metadata directly in inline JSON rather than __NEXT_DATA__.
    for match in re.finditer(r"(?is)<script[^>]*>(.*?)</script>", html_text):
        text = match.group(1).strip()
        if text.startswith(("{", "[")):
            try:
                roots.append(json.loads(html.unescape(text)))
            except Exception:
                pass
    return roots


def walk_sources(node: object, iframe_urls: set[str], media_urls: set[str], source_fields: list[str]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            lower = str(key).lower()
            if lower in {"source_content", "sourcecontent", "embed_url", "embedurl"} and isinstance(value, str):
                source_fields.append(value)
                parser = PageParser()
                parser.feed(value)
                iframe_urls.update(parser.iframes)
                for raw in re.findall(r"""(?i)(?:https?:)?//[^\s\"'<>\\]+""", value):
                    if MEDIA_EXT.search(raw):
                        media_urls.add(raw.replace("\\/", "/"))
            elif isinstance(value, str) and lower in {"file", "url", "src", "video_url", "videourl"}:
                candidate = value.replace("\\/", "/").strip()
                if candidate.startswith(("http://", "https://")) and MEDIA_EXT.search(candidate):
                    media_urls.add(candidate)
            walk_sources(value, iframe_urls, media_urls, source_fields)
    elif isinstance(node, list):
        for value in node:
            walk_sources(value, iframe_urls, media_urls, source_fields)


def public_http_url(url: str) -> bool:
    p = urlparse(url)
    if p.scheme not in {"http", "https"} or not p.hostname or p.username or p.password:
        return False
    try:
        ip = ipaddress.ip_address(p.hostname)
        return ip.is_global
    except ValueError:
        return "." in p.hostname and not p.hostname.endswith((".local", ".internal"))


def inspect_media(url: str, referer: str) -> dict:
    if not public_http_url(url):
        return {"url": safe_url(url), "result": "SKIPPED_NON_PUBLIC_OR_INVALID_URL"}
    result = request_bytes(url, referer, ranged=True)
    body = result["body"].lstrip()
    if result["challenge"]:
        verdict = "BLOCKED_OR_CHALLENGE"
    elif body.startswith(b"#EXTM3U"):
        verdict = "HLS_MANIFEST"
    elif b"<MPD" in body[:512] or body.startswith(b"<?xml") and b"MPD" in body[:512]:
        verdict = "DASH_MANIFEST"
    elif body[4:8] == b"ftyp":
        verdict = "MP4_BYTES"
    elif result["status"] in (200, 206, 301, 302, 307, 308):
        verdict = "HTTP_OK_BUT_MEDIA_TYPE_UNCONFIRMED"
    else:
        verdict = "REQUEST_FAILED"
    return {
        "url": safe_url(result["final_url"]),
        "status": result["status"],
        "content_type": result["content_type"],
        "verdict": verdict,
        "error": result["error"],
    }


def extract_php_or_media_files(text: str, base_url: str) -> list[str]:
    """Read file URLs explicitly present in a player/source API response."""
    values: set[str] = set()
    try:
        root = json.loads(text)
    except Exception:
        root = None

    def walk(node: object) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if str(key).lower() in {"file", "url", "src", "video_url", "videourl"} and isinstance(value, str):
                    candidate = urljoin(base_url, value.replace("\\/", "/").strip())
                    p = urlparse(candidate)
                    if p.scheme in {"http", "https"} and p.hostname and (
                        MEDIA_EXT.search(candidate) or p.path.lower().endswith(".php")
                    ):
                        values.add(candidate)
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    if root is not None:
        walk(root)
    for match in re.finditer(r"""(?is)['"]?file['"]?\s*[:=]\s*['"]((?:\\.|[^'"])+)['"]""", text):
        candidate = urljoin(base_url, match.group(1).replace("\\/", "/"))
        p = urlparse(candidate)
        if p.scheme in {"http", "https"} and p.hostname and (
            MEDIA_EXT.search(candidate) or p.path.lower().endswith(".php")
        ):
            values.add(candidate)
    return sorted(values)


def inspect_observed_pichive(iframe_url: str, page_url: str) -> dict:
    """Follow only the observed public iframe -> source2.php flow, without challenge evasion."""
    p = urlparse(iframe_url)
    if not p.hostname or not any(token in p.hostname.lower() for token in ("pichive", "playru", "contentx")):
        return {"iframe": safe_url(iframe_url), "result": "NOT_PROBED_UNSUPPORTED_HOST"}
    frame = request_bytes(iframe_url, page_url)
    result: dict = {
        "iframe": safe_url(iframe_url),
        "iframe_status": frame["status"],
        "iframe_challenge_or_denial": frame["challenge"],
        "source_api_status": None,
        "file_urls_found": 0,
        "media_checks": [],
    }
    if frame["status"] is None or frame["challenge"] or frame["status"] >= 400:
        result["result"] = "IFRAME_BLOCKED_OR_UNREACHABLE"
        return result
    token_match = re.search(r"""(?is)(?:window\s*\.\s*)?openPlayer\s*\(\s*['"]([^'"]+)['"]""", frame["text"])
    token = token_match.group(1).strip() if token_match else ""
    if not token:
        result["result"] = "NO_OPENPLAYER_TOKEN_IN_NORMAL_RESPONSE"
        return result
    api = f"{p.scheme}://{p.netloc}/source2.php?v=" + quote(token, safe="")
    api_result = request_bytes(api, iframe_url)
    result["source_api_status"] = api_result["status"]
    result["source_api_challenge_or_denial"] = api_result["challenge"]
    if api_result["status"] is None or api_result["challenge"] or api_result["status"] >= 400:
        result["result"] = "SOURCE_API_BLOCKED_OR_UNREACHABLE"
        return result
    files = extract_php_or_media_files(api_result["text"], api)
    result["file_urls_found"] = len(files)
    result["media_checks"] = [inspect_media(item, iframe_url) for item in files[:2]]
    result["result"] = "SOURCE_API_RESPONSE_INSPECTED" if files else "SOURCE_API_NO_FILE_FIELD"
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", default=DEFAULT_URL, help="SelcukFlix film/episode page")
    args = ap.parse_args()
    parsed = urlparse(args.url)
    if parsed.scheme != "https" or parsed.hostname not in {"selcukflix.app", "www.selcukflix.app"}:
        print(json.dumps({"overall": "INVALID_URL", "message": "Only the known HTTPS SelcukFlix .app host is allowed."}))
        return 2

    page = request_bytes(args.url, "https://selcukflix.app/")
    report: dict = {
        "target": safe_url(args.url),
        "page": {
            "status": page["status"],
            "content_type": page["content_type"],
            "challenge_or_denial": page["challenge"],
            "error": page["error"],
        },
        "next_data_found": False,
        "secure_data_found": False,
        "payload_json_decoded": False,
        "source_content_fields": 0,
        "iframe_hosts": [],
        "direct_media_candidates": [],
        "media_checks": [],
        "limitations": [
            "Ordinary HTTP only; no CAPTCHA/Cloudflare bypass, cookie replay, or access-control evasion.",
            "HTTP success and URL extraction do not prove playback inside CloudStream.",
        ],
    }
    if page["status"] is None or page["challenge"] or page["status"] >= 400:
        report["overall"] = "BLOCKED_OR_UNREACHABLE" if page["challenge"] or page["status"] in (403, 429, 503) else "PAGE_REQUEST_FAILED"
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    parser = PageParser()
    parser.feed(page["text"])
    report["next_data_found"] = bool(parser.scripts.get("__NEXT_DATA__"))
    try:
        next_root = json.loads(parser.scripts.get("__NEXT_DATA__", "{}"))
        report["secure_data_found"] = isinstance(next_root.get("props", {}).get("pageProps", {}).get("secureData"), str)
    except Exception:
        next_root = {}
    roots = decode_payloads(page["text"], parser.scripts)
    report["payload_json_decoded"] = bool(roots)
    if not report["next_data_found"]:
        report["overall"] = "PAGE_OK_BUT_NO_NEXT_DATA"
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    iframes: set[str] = set(parser.iframes)
    media_urls: set[str] = set()
    source_fields: list[str] = []
    for root in roots:
        walk_sources(root, iframes, media_urls, source_fields)
    # Include literal iframe attributes embedded in the page's HTML.
    for raw in re.findall(r"""(?is)<iframe\b[^>]*?(?:src|data-src)\s*=\s*['"]([^'"]+)""", page["text"]):
        iframes.add(urljoin(args.url, html.unescape(raw.replace("\\/", "/"))))
    report["source_content_fields"] = len(source_fields)
    report["iframe_hosts"] = sorted({(urlparse(urljoin(args.url, u)).hostname or "").lower() for u in iframes if urlparse(urljoin(args.url, u)).scheme in ("http", "https")})
    report["direct_media_candidates"] = sorted({safe_url(u) for u in media_urls})[:20]

    # Validate only links explicitly exposed by the page. Probe at most two observed
    # Pichive-family frames, using their ordinary player/source API flow.
    for media in sorted(media_urls)[:3]:
        absolute = urljoin(args.url, media)
        report["media_checks"].append(inspect_media(absolute, args.url))
    for frame in sorted({urljoin(args.url, value) for value in iframes})[:2]:
        report.setdefault("player_checks", []).append(inspect_observed_pichive(frame, args.url))

    if report["source_content_fields"] and (iframes or media_urls):
        report["overall"] = "PAGE_AND_SOURCE_CANDIDATES_FOUND"
    elif report["payload_json_decoded"]:
        report["overall"] = "PAGE_OK_BUT_NO_SOURCE_CANDIDATES"
    else:
        report["overall"] = "PAGE_OK_BUT_PAYLOAD_NOT_DECODABLE"
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
