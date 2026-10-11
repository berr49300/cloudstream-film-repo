"""Small, offline repository checks for the CloudStream plugin repo."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SOURCE_SUFFIXES = {".kt", ".kts", ".java", ".json", ".yml", ".yaml", ".properties", ".py", ".xml", ".txt"}
EXCLUDED_DIRS = {".git", ".gradle", "build", "node_modules", "__pycache__"}
MODULE_EXCLUDED_DIRS = {".git", ".gradle", "build", "node_modules", "__pycache__", "tools", "docs", "scripts", "tests"}
SECRET_PATTERNS = (
    re.compile(r"AIza[0-9A-Za-z_-]{30,}"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{20,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)


def _read_json(path: Path, errors: list[str]):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        errors.append(f"required JSON file is missing: {path.as_posix()}")
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        errors.append(f"invalid JSON in {path.as_posix()}: {exc}")
    return None


def _provider_modules(root: Path) -> list[Path]:
    modules = []
    for child in root.iterdir():
        if not child.is_dir() or child.name in MODULE_EXCLUDED_DIRS or child.name.startswith("."):
            continue
        if (child / "build.gradle.kts").is_file():
            modules.append(child)
    return modules


def _scan_source_for_secrets(root: Path, modules: list[Path], errors: list[str]) -> None:
    candidates = []
    for module in modules:
        candidates.extend(module.rglob("*"))
    workflow_dirs = [root / ".github" / "workflows", root.parent / ".github" / "workflows"]
    for workflow_dir in workflow_dirs:
        if workflow_dir.exists():
            candidates.extend(workflow_dir.rglob("*"))

    seen = set()
    for path in candidates:
        if not path.is_file() or path.suffix.lower() not in SOURCE_SUFFIXES:
            continue
        if any(part in EXCLUDED_DIRS for part in path.parts) or path.stat().st_size > 1_000_000:
            continue
        if path in seen:
            continue
        seen.add(path)
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            continue
        for pattern in SECRET_PATTERNS:
            if pattern.search(content):
                try:
                    display_path = path.relative_to(root).as_posix()
                except ValueError:
                    display_path = "../" + path.relative_to(root.parent).as_posix()
                errors.append(f"possible secret pattern found in {display_path}")
                break


def validate_repo(root: Path | str, require_artifacts: bool = False) -> list[str]:
    """Return actionable validation errors; an empty list means the checks passed."""
    root = Path(root).resolve()
    errors: list[str] = []

    repo_manifest = _read_json(root / "repo.json", errors)
    if isinstance(repo_manifest, dict):
        if not isinstance(repo_manifest.get("name"), str) or not repo_manifest["name"].strip():
            errors.append("repo.json must contain a non-empty name")
        if not isinstance(repo_manifest.get("manifestVersion"), int):
            errors.append("repo.json must contain an integer manifestVersion")
        if not isinstance(repo_manifest.get("pluginLists"), list) or not repo_manifest["pluginLists"]:
            errors.append("repo.json must contain a non-empty pluginLists array")

    if not (root / "settings.gradle.kts").is_file():
        errors.append("required Gradle settings file is missing: settings.gradle.kts")

    for relative in (
        "docs/NEW_PROVIDER_ACCEPTANCE_CHECKLIST.md",
        "docs/SITE_PROFILE_TEMPLATE.md",
    ):
        if not (root / relative).is_file():
            errors.append(f"required provider guidance file is missing: {relative}")

    modules = _provider_modules(root)
    if not modules:
        errors.append("no provider module with a root build.gradle.kts was found")
    else:
        modules_without_kotlin = [
            module.name
            for module in modules
            if not any(
                path.is_file() and path.suffix == ".kt"
                for path in module.rglob("*.kt")
                if not any(part in EXCLUDED_DIRS for part in path.parts)
            )
        ]
        if modules_without_kotlin:
            errors.append("provider module(s) have no Kotlin source: " + ", ".join(sorted(modules_without_kotlin)))

    plugin_index_path = root / "build" / "plugins.json"
    if plugin_index_path.exists():
        plugin_index = _read_json(plugin_index_path, errors)
        if not isinstance(plugin_index, list) or not plugin_index:
            errors.append("build/plugins.json must contain a non-empty JSON array")
    elif require_artifacts:
        errors.append("required build index is missing: build/plugins.json")

    if require_artifacts:
        artifacts = [
            path for path in root.rglob("*.cs3")
            if path.is_file() and not any(part in {".git", ".gradle", "node_modules"} for part in path.parts)
        ]
        if not artifacts:
            errors.append("no .cs3 plugin artifact was produced")

    _scan_source_for_secrets(root, modules, errors)
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="repository root (default: current directory)")
    parser.add_argument(
        "--require-artifacts",
        action="store_true",
        help="require build/plugins.json and at least one generated .cs3 artifact",
    )
    args = parser.parse_args(argv)

    errors = validate_repo(args.root, require_artifacts=args.require_artifacts)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(f"Repository validation failed with {len(errors)} issue(s).", file=sys.stderr)
        return 1

    print("PASS: repository structure, JSON manifests, provider guidance and secret-pattern checks")
    if args.require_artifacts:
        print("PASS: generated plugin index and .cs3 artifact checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
