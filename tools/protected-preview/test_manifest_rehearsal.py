"""Use real Git trees and the rehearsal CLI with synthetic GitHub metadata."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import manifest_rehearsal as rehearsal

PR_NUMBER = 89
RUN_ID = 345
WORKFLOW_ID = 678
CI_RUN_ID = 901
REPO = {"id": rehearsal.ops.REPO_ID, "full_name": rehearsal.ops.REPO}


class Rehearsal(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "repo"
        self.root.mkdir()
        self.event = self.root.parent / "event.json"
        self.env = {k: v for k, v in os.environ.items() if k not in ("GH_TOKEN", "GITHUB_TOKEN")}
        self.env.update(GIT_AUTHOR_NAME="Dummy", GIT_AUTHOR_EMAIL="dummy@example.invalid",
                        GIT_COMMITTER_NAME="Dummy", GIT_COMMITTER_EMAIL="dummy@example.invalid",
                        GITHUB_REPOSITORY=rehearsal.ops.REPO,
                        GITHUB_REPOSITORY_ID=str(REPO["id"]), GITHUB_RUN_ATTEMPT="1",
                        GITHUB_ACTOR=rehearsal.OWNER["login"], GITHUB_ACTOR_ID=str(rehearsal.OWNER["id"]),
                        GITHUB_TRIGGERING_ACTOR=rehearsal.OWNER["login"], GITHUB_EVENT_PATH=str(self.event))
        self.git("init", "-q", "--initial-branch=master")
        self.git("commit", "-q", "--allow-empty", "-m", "base")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, env=self.env, timeout=6,
                                       text=True).strip()

    def request(self, *, extra=False, symlink=False):
        path = self.root / rehearsal.DUMMY_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        if symlink:
            path.symlink_to("dummy")
        else:
            path.write_bytes(rehearsal.canonical_dummy(self.base))
        if extra:
            other = self.root / ".github/workflows/hidden.yml"
            other.parent.mkdir()
            other.write_text("name: synthetic extra workflow\n")
        self.git("add", "--all")
        self.tree = self.git("write-tree")
        self.head = self.git("commit-tree", self.tree, "-p", self.base, "-m", "review")
        self.squash = self.git("commit-tree", self.tree, "-p", self.base, "-m", "squash")
        self.test_merge = self.git("commit-tree", self.tree, "-p", self.base, "-p", self.head, "-m", "merge")
        self.pr_base = {"ref": rehearsal.BRANCH, "sha": self.base, "repo": REPO}
        self.pr_head = {"ref": rehearsal.HEAD_BRANCH, "sha": self.head, "repo": REPO}

    def run_pr(self, *, shallow=False):
        self.git("update-ref", "HEAD", self.test_merge)
        self.event.write_text(json.dumps({
            "repository": REPO, "sender": rehearsal.OWNER, "number": PR_NUMBER,
            "pull_request": {"draft": True, "base": self.pr_base, "head": self.pr_head},
        }))
        checkout = self.root
        if shallow:
            checkout = self.root.parent / "checkout"
            self.git("clone", "-q", "--depth", "2", self.root.as_uri(), str(checkout))
        return subprocess.run([sys.executable, rehearsal.__file__, "pr-check", "--timeout-seconds", "15"],
                              cwd=checkout, timeout=20, capture_output=True, text=True, env=self.env | {
                                  "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REF": f"refs/pull/{PR_NUMBER}/merge",
                                  "GITHUB_SHA": self.test_merge, "GITHUB_WORKFLOW_SHA": self.test_merge,
                              })

    def push_metadata(self):
        self.git("update-ref", "HEAD", self.squash)
        self.event.write_text(json.dumps({
            "repository": REPO, "sender": rehearsal.OWNER, "ref": rehearsal.REF,
            "before": self.base, "after": self.squash, "head_commit": {"id": self.squash},
            "commits": [{"id": self.squash}], "created": False, "deleted": False, "forced": False,
        }))
        actors = {"actor": rehearsal.OWNER, "triggering_actor": rehearsal.OWNER,
                  "repository": REPO, "head_repository": REPO, "run_attempt": 1}
        checks = [
            {"name": name, "head_sha": self.head, "run_id": CI_RUN_ID, "run_attempt": 1,
             "status": "completed", "conclusion": "success",
             "steps": [{"name": rehearsal.REVIEW_STEP, "conclusion": "success"}]}
            for name in rehearsal.REQUIRED
        ] + [{"name": "build-release", "conclusion": "skipped"}]
        self.push_run_path = (f"actions/workflows/{WORKFLOW_ID}/runs?event=push"
                              f"&head_sha={self.squash}&per_page=2")
        self.ci_run_path = (f"actions/workflows/{rehearsal.CI_WORKFLOW_ID}/runs?event=pull_request"
                            f"&head_sha={self.head}&per_page=2")
        self.jobs_path = f"actions/runs/{CI_RUN_ID}/attempts/1/jobs?per_page=30"
        return {
            "branches/master": {"name": "master", "protected": True, "commit": {"sha": self.squash}},
            f"actions/workflows/{Path(rehearsal.WORKFLOW).name}": {
                "id": WORKFLOW_ID, "path": rehearsal.WORKFLOW, "state": "active",
            },
            self.push_run_path: {"total_count": 1, "workflow_runs": [
                actors | {"id": RUN_ID, "workflow_id": WORKFLOW_ID, "path": rehearsal.WORKFLOW,
                          "head_sha": self.squash, "head_branch": rehearsal.BRANCH, "status": "in_progress",
                          "event": "push", "conclusion": None},
            ]},
            f"commits/{self.squash}/pulls?per_page=2": [{"number": PR_NUMBER}],
            f"pulls/{PR_NUMBER}": {"number": PR_NUMBER, "state": "closed", "merged": True,
                                  "merge_commit_sha": self.squash, "merged_by": rehearsal.OWNER,
                                  "head": self.pr_head, "base": self.pr_base},
            f"git/commits/{self.head}": {"sha": self.head, "tree": {"sha": self.tree},
                                         "parents": [{"sha": self.base}]},
            self.ci_run_path: {"total_count": 1, "workflow_runs": [
                actors | {"id": CI_RUN_ID, "workflow_id": rehearsal.CI_WORKFLOW_ID, "path": rehearsal.CI_WORKFLOW,
                          "head_sha": self.head, "head_branch": rehearsal.HEAD_BRANCH, "status": "completed",
                          "event": "pull_request", "conclusion": "success", "pull_requests": []},
            ]},
            self.jobs_path: {"jobs": checks, "total_count": len(checks)},
            f"actions/runs/{CI_RUN_ID}/artifacts?per_page=1": {"total_count": 0, "artifacts": []},
        }

    def run_push(self, metadata, **changes):
        output = io.StringIO()
        env = self.env | {
            "GITHUB_EVENT_NAME": "push", "GITHUB_REF": rehearsal.REF, "GITHUB_REF_PROTECTED": "true",
            "GITHUB_SHA": self.squash, "GITHUB_WORKFLOW_SHA": self.squash, "GITHUB_RUN_ID": str(RUN_ID),
            "GITHUB_WORKFLOW_REF": f"{rehearsal.ops.REPO}/{rehearsal.WORKFLOW}@{rehearsal.REF}",
        } | changes
        with mock.patch.object(rehearsal.ops, "api", side_effect=lambda path, **kw: metadata[path]) as api:
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                code = rehearsal.main(["push-check"], root=self.root, environ=env)
        self.assertTrue(all(call.kwargs == {"timeout": 8} for call in api.call_args_list))
        return code, output.getvalue(), api.call_count

    def test_draft_pr_cli_checks_actual_test_merge_and_single_path(self):
        self.request()
        completed = self.run_pr(shallow=True)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout, "PASS: DUMMY_REVIEW_TREE\n")

    def test_extra_workflow_blocks_both_real_git_entrypoints(self):
        self.request(extra=True)
        completed = self.run_pr()
        self.assertEqual((completed.returncode, completed.stderr), (1, "FAIL: REHEARSAL_CHANGED_PATHS\n"))
        self.push_metadata()
        self.assertEqual(self.run_push({}), (1, "FAIL: REHEARSAL_CHANGED_PATHS\n", 0))

    def test_symlink_request_fails_before_reading_object(self):
        self.request(symlink=True)
        completed = self.run_pr()
        self.assertEqual((completed.returncode, completed.stderr), (1, "FAIL: REHEARSAL_REQUEST_MODE\n"))

    def test_exact_protected_squash_compares_genuine_check_contract(self):
        self.request()
        self.assertEqual(self.run_push(self.push_metadata()), (0, "PASS: DUMMY_PROTECTED_MERGE\n", 9))

    def test_untrusted_runner_and_rerun_fail_before_network(self):
        self.request()
        self.push_metadata()
        for variable, value, code in (
            ("GITHUB_TRIGGERING_ACTOR", "unknown-app[bot]", "RUNNER_ACTOR"),
            ("GITHUB_RUN_ATTEMPT", "2", "RUNNER_SCOPE"),
            ("GITHUB_REF_PROTECTED", "false", "PROTECTED_WORKFLOW"),
            ("GITHUB_WORKFLOW_SHA", self.head, "PROTECTED_WORKFLOW"),
        ):
            with self.subTest(variable=variable):
                self.assertEqual(self.run_push({}, **{variable: value}), (1, f"FAIL: REHEARSAL_{code}\n", 0))

    def test_hosted_mismatches_fail_closed(self):
        self.request()
        self.push_metadata()
        for key, path, value, code in (
            ("branches/master", ("commit", "sha"), self.head, "LIVE_HEAD"),
            (self.push_run_path, ("total_count",), 2, "RUN_UNIQUE"),
            (self.push_run_path, ("workflow_runs", 0, "status"), "completed", "RUN_IDENTITY"),
            (self.push_run_path, ("workflow_runs", 0, "actor"), {"id": 1}, "RUN_ACTOR"),
            (f"pulls/{PR_NUMBER}", ("merged_by",), {"id": 1}, "MERGER"),
            (f"pulls/{PR_NUMBER}", ("head", "repo"), {"id": 1}, "PR_REPO"),
            (f"git/commits/{self.head}", ("tree", "sha"), "c" * 40, "REVIEW_TREE"),
            (self.ci_run_path, ("workflow_runs", 0, "head_sha"), self.squash, "RUN_IDENTITY"),
            (self.jobs_path, ("jobs", 0, "steps"), [], "REQUIRED_TREE_CHECK"),
            (self.jobs_path, ("jobs", 3, "conclusion"), "success", "REQUIRED_JOBS"),
            (f"actions/runs/{CI_RUN_ID}/artifacts?per_page=1", ("total_count",), 1, "CHECK_ARTIFACTS"),
        ):
            with self.subTest(key=key, path=path):
                metadata = json.loads(json.dumps(self.push_metadata()))
                item = metadata[key]
                for field in path[:-1]:
                    item = item[field]
                item[path[-1]] = value
                result, log, _ = self.run_push(metadata)
                self.assertEqual(result, 1, log)
                self.assertEqual(log, f"FAIL: REHEARSAL_{code}\n")

    def test_removed_manifest_in_ancestry_cannot_be_reintroduced(self):
        self.request()
        self.git("update-ref", "HEAD", self.head)
        self.git("rm", rehearsal.DUMMY_PATH)
        self.git("commit", "-q", "-m", "remove dummy")
        self.base = self.git("rev-parse", "HEAD")
        self.request()
        self.push_metadata()
        self.assertEqual(self.run_push({}), (1, "FAIL: REHEARSAL_PUSH_HISTORY\n", 0))


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
