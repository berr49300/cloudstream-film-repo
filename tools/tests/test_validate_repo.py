import json
import tempfile
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validate_repo import validate_repo


class ValidateRepoTests(unittest.TestCase):
    def make_repo(self, root: Path) -> Path:
        (root / "repo.json").write_text(
            json.dumps({
                "name": "Fixture repo",
                "manifestVersion": 1,
                "pluginLists": ["https://example.invalid/plugins.json"],
            }),
            encoding="utf-8",
        )
        (root / "settings.gradle.kts").write_text('rootProject.name = "fixture"\n', encoding="utf-8")
        (root / "provider" / "src" / "main" / "kotlin").mkdir(parents=True)
        (root / "provider" / "build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
        (root / "provider" / "src" / "main" / "kotlin" / "Provider.kt").write_text(
            "class Provider\n", encoding="utf-8"
        )
        (root / "docs").mkdir()
        (root / "docs" / "NEW_PROVIDER_ACCEPTANCE_CHECKLIST.md").write_text("checklist\n", encoding="utf-8")
        (root / "docs" / "SITE_PROFILE_TEMPLATE.md").write_text("template\n", encoding="utf-8")
        (root / "build").mkdir()
        (root / "build" / "plugins.json").write_text('[{"name":"Fixture"}]\n', encoding="utf-8")
        (root / "provider" / "build").mkdir()
        (root / "provider" / "build" / "Fixture.cs3").write_bytes(b"fixture artifact")
        return root

    def test_valid_repository_passes_all_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_repo(Path(tmp))
            self.assertEqual([], validate_repo(root, require_artifacts=True))

    def test_invalid_repo_json_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_repo(Path(tmp))
            (root / "repo.json").write_text("{invalid", encoding="utf-8")
            errors = validate_repo(root, require_artifacts=True)
            self.assertTrue(any("repo.json" in error for error in errors), errors)

    def test_missing_cs3_artifact_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_repo(Path(tmp))
            (root / "provider" / "build" / "Fixture.cs3").unlink()
            errors = validate_repo(root, require_artifacts=True)
            self.assertTrue(any(".cs3" in error for error in errors), errors)

    def test_obvious_api_key_in_provider_source_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_repo(Path(tmp))
            (root / "provider" / "src" / "main" / "kotlin" / "Provider.kt").write_text(
                'val key = "AIzaSy' + "A" * 35 + '"\n', encoding="utf-8"
            )
            errors = validate_repo(root, require_artifacts=True)
            self.assertTrue(any("possible secret" in error.lower() for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
