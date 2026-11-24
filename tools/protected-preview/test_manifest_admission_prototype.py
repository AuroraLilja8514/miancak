#!/usr/bin/env python3
"""Exercise the local CLI against synthetic GitHub observations."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import manifest_admission_prototype as gate
import preview_ops as preview
import release_ops as ops

PARENT = "a" * 40
REVISION = "b" * 40
PR_HEAD = "c" * 40
TREE = "d" * 40
RUN = 140
PR = 51


def fixture():
    raw = gate.canonical_request(PARENT)
    return raw, {
        "push": {"ref": gate.REF, "before": PARENT, "after": REVISION, "head_commit": REVISION,
                 "created": False, "deleted": False, "forced": False, "commits": [REVISION],
                 "repository_id": ops.REPO_ID, "sender": dict(gate.OWNER)},
        "run": {"id": RUN, "event": "push", "attempt": 1, "workflow_id": gate.WORKFLOW_ID,
                "workflow_ref": f"{ops.REPO}/{preview.WORKFLOW}@{gate.REF}",
                "repository_id": ops.REPO_ID, "head_repository_id": ops.REPO_ID,
                "head_sha": REVISION, "workflow_sha": REVISION, "ref": gate.REF, "ref_protected": True,
                "status": "in_progress", "conclusion": None,
                "actor": dict(gate.OWNER), "triggering_actor": dict(gate.OWNER)},
        "run_ids": [RUN],
        "git": {"live_master": REVISION, "checkout_sha": REVISION, "parents": [PARENT], "tree": TREE,
                "changes": [{"path": gate.REQUEST_PATH, "status": "added", "mode": "100644"}],
                "request_in_ancestry": False},
        "pr": {"number": PR, "state": "closed", "merged": True, "base_repository_id": ops.REPO_ID,
               "head_repository_id": ops.REPO_ID, "base_ref": gate.BRANCH, "head_ref": gate.HEAD_BRANCH,
               "base_sha": PARENT, "head_sha": PR_HEAD, "merge_commit_sha": REVISION,
               "merged_by": dict(gate.OWNER), "manifest_sha256": ops.digest(raw)},
        "associated_pr_numbers": [PR],
        "checks": {"event": "pull_request", "repository_id": ops.REPO_ID,
                   "workflow_id": gate.CI_WORKFLOW_ID, "attempt": 1, "conclusion": "success",
                   "pr_number": PR, "head_sha": PR_HEAD, "base_sha": PARENT, "tested_tree": TREE,
                   "required": dict(gate.REQUIRED), "artifacts": 0, "caches_disabled": True},
    }


class ManifestCLI(unittest.TestCase):
    def invoke(self, raw, facts):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            manifest = root / "manifest.json"
            evidence = root / "observations.json"
            manifest.write_bytes(raw)
            evidence.write_text(facts if isinstance(facts, str) else json.dumps(facts))
            env = {"PATH": os.defpath, "PYTHONDONTWRITEBYTECODE": "1"}
            return subprocess.run([sys.executable, gate.__file__, "--manifest", str(manifest),
                                   "--evidence-fixture", str(evidence), "--timeout-seconds", "10"],
                                  env=env, capture_output=True, text=True, timeout=15)

    def rejected(self, raw, facts, error):
        result = self.invoke(raw, facts)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, f"FAIL: {error}\n")

    def test_admits_only_the_matching_local_observations(self):
        result = self.invoke(*fixture())
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "PASS: LOCAL_MANIFEST_FIXTURE\n")
        self.assertEqual(result.stderr, "")

    def test_rejects_hidden_workflow_change_after_an_approved_manifest(self):
        raw, facts = fixture()
        facts["git"]["changes"].append({"path": preview.WORKFLOW, "status": "modified", "mode": "100644"})
        self.rejected(raw, facts, "REQUEST_COMMIT_SCOPE")

    def test_rejects_untrusted_request_and_old_checks(self):
        cases = (
            ("push", "forced", True, "REQUEST_PUSH_EVENT"),
            ("run", "triggering_actor", gate.OWNER | {"id": 99}, "REQUEST_RUN_ACTOR"),
            ("run", "ref_protected", False, "REQUEST_RUN_SCOPE"),
            ("run", "ref_protected", 1, "REQUEST_RUN_SCOPE"),
            ("run", "workflow_sha", "e" * 40, "REQUEST_RUN_SCOPE"),
            ("pr", "merged", False, "REQUEST_PR_SCOPE"),
            ("pr", "head_repository_id", 123, "REQUEST_PR_SCOPE"),
            ("pr", "manifest_sha256", "e" * 64, "REQUEST_PR_SCOPE"),
            ("pr", "merged_by", gate.OWNER | {"id": 99}, "REQUEST_MERGER"),
            ("checks", "head_sha", "e" * 40, "REQUEST_CHECKS"),
            ("checks", "tested_tree", "e" * 40, "REQUEST_CHECKS"),
            ("checks", "required", gate.REQUIRED | {"unit": "skipped"}, "REQUEST_CHECKS"),
            ("checks", "artifacts", 1, "REQUEST_CHECKS"),
        )
        for area, key, value, error in cases:
            with self.subTest(area=area, key=key):
                raw, facts = fixture()
                facts[area][key] = value
                self.rejected(raw, facts, error)

    def test_rejects_second_run_or_reintroduced_request(self):
        raw, facts = fixture()
        self.rejected(raw, facts | {"run_ids": [RUN, RUN + 1]}, "REQUEST_REPLAY")
        self.rejected(raw, facts | {"associated_pr_numbers": [PR, PR + 1]}, "REQUEST_PR_ASSOCIATION")
        facts["run"]["attempt"] = 2
        self.rejected(raw, facts, "REQUEST_RUN_SCOPE")
        raw, facts = fixture()
        facts["git"]["request_in_ancestry"] = True
        self.rejected(raw, facts, "REQUEST_COMMIT_SCOPE")

    def test_rejects_changed_release_pins_and_duplicate_keys(self):
        raw, facts = fixture()
        different = raw.replace(b'"make_latest": false', b'"make_latest": true')
        self.rejected(different, facts, "REQUEST_MANIFEST")
        self.rejected(raw.replace(preview.SOURCE.encode(), b"f" * 40), facts, "REQUEST_MANIFEST")
        self.rejected(b'{"version_code":999,' + raw[1:], facts, "REQUEST_MANIFEST")
        self.rejected(raw, '{"run":{},' + json.dumps(facts)[1:], "REQUEST_DUPLICATE_JSON_KEY")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
