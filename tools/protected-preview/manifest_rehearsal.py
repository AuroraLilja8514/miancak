#!/usr/bin/env python3
"""Verify the single dummy request on a PR test merge or protected master."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from manifest_admission_prototype import (
    BRANCH, CI_WORKFLOW_ID, OWNER, REF, REQUIRED, Rejected, expect,
    positive_id, require, sha, unique_object,
)
import release_ops as ops

ROOT = Path(__file__).resolve().parents[2]
HEAD_BRANCH = "release-request/dummy-protected-merge"
DUMMY_PATH = ".github/release-requests/_protected-bootstrap-probe.json"
WORKFLOW = ".github/workflows/protected-release-request-rehearsal.yml"
CI_WORKFLOW = ".github/workflows/ci.yml"
REVIEW_STEP = "Verify dummy request and reviewed tree"
MAX_EVENT_BYTES = 256 * 1024


def canonical_dummy(base):
    record = {"format_version": 1, "kind": "dummy-protected-merge", "reviewed_base_sha": sha(base)}
    return (json.dumps(record, indent=2, sort_keys=True) + "\n").encode()


def git(root, *args):
    return ops.command(["git", "-C", root, *args], timeout=10).stdout


def parents(root, commit):
    header = git(root, "cat-file", "commit", sha(commit)).split(b"\n\n", 1)[0]
    return [sha(line[7:].decode()) for line in header.splitlines() if line.startswith(b"parent ")]


def request_tree(root, base, revision, path, expected):
    changes = git(root, "diff", "--name-status", "--no-renames", "-z", base, revision)
    require(changes == f"A\0{path}\0".encode(), "REHEARSAL_CHANGED_PATHS")
    entry = git(root, "ls-tree", "-z", revision, "--", path)
    require(entry.startswith(b"100644 blob "), "REHEARSAL_REQUEST_MODE")
    spec = f"{revision}:{path}"
    require(int(git(root, "cat-file", "-s", spec)) == len(expected)
            and git(root, "cat-file", "blob", spec) == expected, "REHEARSAL_REQUEST_BYTES")
    return sha(git(root, "rev-parse", f"{revision}^{{tree}}").decode().strip())


def runner_event(environ, root, event_name):
    require(environ.get("GITHUB_REPOSITORY") == ops.REPO
            and environ.get("GITHUB_REPOSITORY_ID") == str(ops.REPO_ID)
            and environ.get("GITHUB_EVENT_NAME") == event_name
            and environ.get("GITHUB_RUN_ATTEMPT") == "1", "REHEARSAL_RUNNER_SCOPE")
    require(environ.get("GITHUB_ACTOR_ID") == str(OWNER["id"])
            and environ.get("GITHUB_ACTOR") == environ.get("GITHUB_TRIGGERING_ACTOR") == OWNER["login"],
            "REHEARSAL_RUNNER_ACTOR")
    revision = sha(environ.get("GITHUB_SHA"))
    require(git(root, "rev-parse", "HEAD").decode().strip() == revision, "REHEARSAL_CHECKOUT")
    with Path(environ["GITHUB_EVENT_PATH"]).open("rb") as stream:
        raw = stream.read(MAX_EVENT_BYTES + 1)
    require(len(raw) <= MAX_EVENT_BYTES, "REHEARSAL_EVENT_SIZE")
    event = json.loads(raw, object_pairs_hook=unique_object)
    expect(event.get("repository"), {"id": ops.REPO_ID, "full_name": ops.REPO}, "REHEARSAL_EVENT_REPO")
    expect(event.get("sender"), OWNER, "REHEARSAL_EVENT_ACTOR")
    return revision, event


def pr_check(environ, root, *, path=DUMMY_PATH, head_branch=HEAD_BRANCH, canonical=canonical_dummy):
    revision, event = runner_event(environ, root, "pull_request")
    number = positive_id(event.get("number"))
    pr = event["pull_request"]
    require(environ.get("GITHUB_REF") == f"refs/pull/{number}/merge"
            and pr.get("draft") is True, "REHEARSAL_DRAFT_PR")
    require_pr_ref(pr["base"], BRANCH)
    require_pr_ref(pr["head"], head_branch)
    base, head = sha(pr["base"].get("sha")), sha(pr["head"].get("sha"))
    require(environ.get("GITHUB_WORKFLOW_SHA") in (revision, head)
            and parents(root, revision) == [base, head]
            and parents(root, head) == [base], "REHEARSAL_PR_PARENTS")
    require(request_tree(root, base, revision, path, canonical(base))
            == git(root, "rev-parse", f"{head}^{{tree}}").decode().strip(), "REHEARSAL_PR_TREE")


def require_pr_ref(value, ref):
    expect(value, {"ref": ref}, "REHEARSAL_PR_REF")
    expect(value.get("repo"), {"id": ops.REPO_ID}, "REHEARSAL_PR_REPO")


def read(path):
    return ops.api(path, timeout=8)


def workflow_run(workflow, event, revision, *, fetch=read):
    result = fetch(f"actions/workflows/{workflow}/runs?event={event}&head_sha={revision}&per_page=2")
    require(type(result.get("total_count")) is int and result["total_count"] == 1
            and len(result["workflow_runs"]) == 1, "REHEARSAL_RUN_UNIQUE")
    return result["workflow_runs"][0]


def require_run(run, fields):
    expect(run, fields, "REHEARSAL_RUN_IDENTITY")
    for name in ("repository", "head_repository"):
        expect(run.get(name), {"id": ops.REPO_ID}, "REHEARSAL_RUN_REPO")
    for name in ("actor", "triggering_actor"):
        expect(run.get(name), OWNER, "REHEARSAL_RUN_ACTOR")


def merged_pr_head(revision, base, tree, *, head_branch=HEAD_BRANCH, fetch=read):
    related = fetch(f"commits/{revision}/pulls?per_page=2")
    require(type(related) is list and len(related) == 1, "REHEARSAL_PR_UNIQUE")
    number = positive_id(related[0].get("number"))
    pr = fetch(f"pulls/{number}")
    expect(pr, {"number": number, "state": "closed", "merged": True, "merge_commit_sha": revision},
           "REHEARSAL_SQUASH_PR")
    expect(pr.get("merged_by"), OWNER, "REHEARSAL_MERGER")
    require_pr_ref(pr["base"], BRANCH)
    require_pr_ref(pr["head"], head_branch)
    expect(pr["base"], {"sha": base}, "REHEARSAL_REVIEW_BASE")
    head = sha(pr["head"].get("sha"))
    commit = fetch(f"git/commits/{head}")
    expect(commit, {"sha": head}, "REHEARSAL_REVIEW_HEAD")
    expect(commit.get("tree"), {"sha": tree}, "REHEARSAL_REVIEW_TREE")
    require([p.get("sha") for p in commit["parents"]] == [base], "REHEARSAL_REVIEW_PARENT")
    return number, head


def require_review_checks(number, head, *, head_branch=HEAD_BRANCH, review_step=REVIEW_STEP, fetch=read):
    run = workflow_run(CI_WORKFLOW_ID, "pull_request", head, fetch=fetch)
    require_run(run, {"workflow_id": CI_WORKFLOW_ID, "path": CI_WORKFLOW, "event": "pull_request",
                      "head_branch": head_branch, "head_sha": head, "run_attempt": 1,
                      "status": "completed", "conclusion": "success"})
    # GitHub can omit PR associations for PR runs. The protected exact-branch
    # guard in each required job checks the actual test merge and its parents.
    related = run["pull_requests"]
    require(type(related) is list and (not related or [item.get("number") for item in related] == [number]),
            "REHEARSAL_CHECK_PR")
    run_id = positive_id(run.get("id"))
    result = fetch(f"actions/runs/{run_id}/attempts/1/jobs?per_page=30")
    jobs = result["jobs"]
    require(len(jobs) == result["total_count"] <= 30, "REHEARSAL_CHECK_JOB_PAGE")
    # Check the full job page so an extra synthetic or ordinary job is caught.
    ran = [job.get("name") for job in jobs if job.get("conclusion") != "skipped"]
    require(sorted(ran) == sorted(REQUIRED), "REHEARSAL_REQUIRED_JOBS")
    for job in jobs:
        if job.get("name") in REQUIRED:
            expect(job, {"head_sha": head, "run_id": run_id, "run_attempt": 1,
                         "status": "completed", "conclusion": "success"}, "REHEARSAL_CHECK_JOB")
            reviewed = [step.get("conclusion") for step in job["steps"] if step.get("name") == review_step]
            require(reviewed == ["success"], "REHEARSAL_REQUIRED_TREE_CHECK")
    expect(fetch(f"actions/runs/{run_id}/artifacts?per_page=1"), {"total_count": 0, "artifacts": []},
           "REHEARSAL_CHECK_ARTIFACTS")


def checked_push(environ, root, *, workflow=WORKFLOW, path=DUMMY_PATH, canonical=canonical_dummy):
    revision, event = runner_event(environ, root, "push")
    require(environ.get("GITHUB_REF") == REF and environ.get("GITHUB_REF_PROTECTED") == "true"
            and environ.get("GITHUB_WORKFLOW_SHA") == revision
            and environ.get("GITHUB_WORKFLOW_REF") == f"{ops.REPO}/{workflow}@{REF}",
            "REHEARSAL_PROTECTED_WORKFLOW")
    expect(event, {"ref": REF, "after": revision, "created": False, "deleted": False, "forced": False},
           "REHEARSAL_PUSH_SCOPE")
    expect(event.get("head_commit"), {"id": revision}, "REHEARSAL_PUSH_HEAD")
    require([commit.get("id") for commit in event["commits"]] == [revision], "REHEARSAL_PUSH_COMMITS")
    base = sha(event.get("before"))
    require(git(root, "rev-parse", "--is-shallow-repository") == b"false\n"
            and parents(root, revision) == [base]
            and not git(root, "log", "-1", "--full-history", "--format=%H", base, "--", path),
            "REHEARSAL_PUSH_HISTORY")
    raw = canonical(base)
    tree = request_tree(root, base, revision, path, raw)
    return revision, base, tree, raw


def push_check(environ, root):
    revision, base, tree, _ = checked_push(environ, root)

    branch = read(f"branches/{BRANCH}")
    expect(branch, {"name": BRANCH, "protected": True}, "REHEARSAL_LIVE_BRANCH")
    expect(branch.get("commit"), {"sha": revision}, "REHEARSAL_LIVE_HEAD")
    workflow = read(f"actions/workflows/{Path(WORKFLOW).name}")
    expect(workflow, {"path": WORKFLOW, "state": "active"}, "REHEARSAL_LIVE_WORKFLOW")
    workflow_id = positive_id(workflow.get("id"))
    run = workflow_run(workflow_id, "push", revision)
    require_run(run, {"workflow_id": workflow_id, "path": WORKFLOW, "event": "push",
                      "head_branch": BRANCH, "head_sha": revision, "run_attempt": 1,
                      "status": "in_progress"})
    require(str(positive_id(run.get("id"))) == environ.get("GITHUB_RUN_ID")
            and run.get("conclusion") is None, "REHEARSAL_ACTIVE_RUN")
    require_review_checks(*merged_pr_head(revision, base, tree))


def main(argv=None, *, environ=None, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("pr-check", "push-check"))
    operation = parser.parse_args(argv).operation
    environ = os.environ if environ is None else environ
    try:
        check = pr_check if operation == "pr-check" else push_check
        check(environ, root)
    except Rejected as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    except ops.ReleaseError as failure:
        code = str(failure)
        if not re.fullmatch(r"API_HTTP_[1-5][0-9]{2}", code):
            code = "API_OR_GIT"
        print(f"FAIL: REHEARSAL_{code}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RecursionError,
            subprocess.TimeoutExpired):
        print("FAIL: REHEARSAL_INVALID_EVIDENCE", file=sys.stderr)
        return 1
    print("PASS: DUMMY_REVIEW_TREE" if operation == "pr-check" else "PASS: DUMMY_PROTECTED_MERGE")
    return 0


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    raise SystemExit(main(root=Path.cwd()))
