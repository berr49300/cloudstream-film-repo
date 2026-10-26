"""Regression tests for CI publishing and dependency-update policy."""

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class WorkflowPolicyTests(unittest.TestCase):
    def test_publishing_requires_manual_dispatch_and_explicit_opt_in(self):
        workflow = (ROOT / ".github" / "workflows" / "build.yml").read_text(encoding="utf-8")
        publish_job = workflow.split("\n  publish:\n", 1)[1]
        condition = next(
            line.strip()
            for line in publish_job.splitlines()
            if line.strip().startswith("if:")
        )
        self.assertIn("github.ref == 'refs/heads/main'", condition)
        self.assertIn("github.event_name == 'workflow_dispatch'", condition)
        self.assertIn("inputs.publish == true", condition)
        self.assertNotIn("github.event_name == 'push'", condition)

    def test_dependabot_covers_actions_and_gradle(self):
        config = (ROOT / ".github" / "dependabot.yml").read_text(encoding="utf-8")
        self.assertIn("package-ecosystem: github-actions", config)
        self.assertIn("package-ecosystem: gradle", config)
        self.assertGreaterEqual(config.count('interval: weekly'), 2)

    def test_release_docs_distinguish_ci_from_publish(self):
        docs = (ROOT / "docs" / "RELEASE_WORKFLOW.md").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch", docs)
        self.assertIn("publish=true", docs)
        self.assertIn("push", docs.lower())


if __name__ == "__main__":
    unittest.main()
