#!/usr/bin/env python3
"""Verify the exact protected preview request using Git and GitHub metadata."""
import argparse
from functools import partial
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlencode

import manifest_rehearsal as checked
from manifest_admission_prototype import (
    CI_WORKFLOW_ID, HEAD_BRANCH, REQUEST_PATH, REQUIRED, WORKFLOW_ID, Rejected, canonical_request,
    expect, positive_id, require,
)
import preview_ops as preview
import release_ops as ops

# The workflow is still dispatch-only. Enabling a protected push is a separate
# protected review and does not use a workflow input or repository variable.
MERGE_ENABLED = False
REVIEW_STEP = "Verify preview request and reviewed tree"
RULESET_ID = 22517239


def binding(revision, *, environ=None, root=None):
    ops.require(MERGE_ENABLED, "PREVIEW_MERGE_DISARMED")
    environ = os.environ if environ is None else environ
    root = preview.ROOT if root is None else root
    try:
        actual, base, tree, raw = checked.checked_push(
            environ, root, workflow=preview.WORKFLOW, path=REQUEST_PATH, canonical=canonical_request)
        require(actual == revision, "PREVIEW_REQUEST_REVISION")
    except Rejected as failure:
        raise ops.ReleaseError(str(failure)) from None
    return {"path": REQUEST_PATH, "sha256": ops.digest(raw), "reviewed_base_sha": base, "tree": tree}


def public_read(path):
    return ops.api(path, authenticated=False, timeout=5, limit=1024 * 1024)


def metadata_read(path, *, actions_read):
    """Use the current job token only for fixed Contents and Actions reads."""
    endpoint = path.split("?", 1)[0]
    if endpoint == "branches/master" or re.fullmatch(r"git/commits/[0-9a-f]{40}", endpoint):
        authenticated = True
    elif (endpoint == "actions/caches"
          or re.fullmatch(r"actions/runs/[1-9][0-9]*/(?:attempts/1/jobs|artifacts)", endpoint)
          or re.fullmatch(rf"actions/workflows/(?:{WORKFLOW_ID}|{CI_WORKFLOW_ID})/runs", endpoint)):
        authenticated = actions_read
    elif (endpoint == "rules/branches/master"
          or re.fullmatch(r"(?:commits/[0-9a-f]{40}/pulls|pulls/[1-9][0-9]*)", endpoint)):
        authenticated = False
    else:
        raise ops.ReleaseError("PREVIEW_REQUEST_METADATA_ROUTE")
    if authenticated:
        return ops.api(path, authenticated=True, timeout=5, limit=1024 * 1024)
    return public_read(path)


def require_protection(fetch):
    # This endpoint returns the active rules that actually apply to master.
    # The runtime independently proves the exact checks and merger, without
    # requesting the ruleset writer's bypass list.
    effective = fetch("rules/branches/master")
    require(type(effective) is list, "PREVIEW_REQUEST_PROTECTION")
    matching = [item for item in effective if item.get("ruleset_id") == RULESET_ID]
    rules = {item["type"]: item.get("parameters", {}) for item in matching}
    require(len(rules) == len(matching) and "non_fast_forward" in rules, "PREVIEW_REQUEST_PROTECTION")
    expect(rules.get("pull_request"), {"required_review_thread_resolution": True},
           "PREVIEW_REQUEST_PROTECTION")
    status = rules.get("required_status_checks")
    expect(status, {"strict_required_status_checks_policy": True, "do_not_enforce_on_create": False},
           "PREVIEW_REQUEST_PROTECTION")
    require({item["context"] for item in status["required_status_checks"]} == REQUIRED.keys(),
            "PREVIEW_REQUEST_REQUIRED_CONTEXTS")


def verify(revision, *, actions_read, environ=None, root=None):
    """Read the authoritative current run, protected merge and its required checks."""
    environ = os.environ if environ is None else environ
    request = binding(revision, environ=environ, root=root)
    fetch = partial(metadata_read, actions_read=actions_read)
    try:
        branch = fetch("branches/master")
        expect(branch, {"name": "master", "protected": True}, "PREVIEW_REQUEST_PROTECTED_REF")
        expect(branch.get("commit"), {"sha": revision}, "PREVIEW_REQUEST_LIVE_HEAD")
        require_protection(fetch)
        run = checked.workflow_run(WORKFLOW_ID, "push", revision, fetch=fetch)
        checked.require_run(run, {
            "workflow_id": WORKFLOW_ID, "path": preview.WORKFLOW, "event": "push",
            "head_branch": "master", "head_sha": revision, "run_attempt": 1,
            "status": "in_progress", "display_title": f"{preview.TAG} publish",
        })
        require(str(positive_id(run.get("id"))) == environ.get("GITHUB_RUN_ID")
                and run.get("conclusion") is None, "PREVIEW_REQUEST_ACTIVE_RUN")
        number, head = checked.merged_pr_head(revision, request["reviewed_base_sha"], request["tree"],
                                             head_branch=HEAD_BRANCH, fetch=fetch)
        checked.require_review_checks(number, head, head_branch=HEAD_BRANCH, review_step=REVIEW_STEP, fetch=fetch)
        for ref in (f"refs/pull/{number}/merge", f"refs/heads/{HEAD_BRANCH}"):
            query = urlencode({"ref": ref, "per_page": 1})
            expect(fetch(f"actions/caches?{query}"), {"total_count": 0, "actions_caches": []},
                   "PREVIEW_REQUEST_CI_CACHE")
        current = fetch("branches/master")
        expect(current, {"protected": True}, "PREVIEW_REQUEST_PROTECTED_REF")
        expect(current.get("commit"), {"sha": revision}, "PREVIEW_REQUEST_STALE_BASE")
    except Rejected as failure:
        raise ops.ReleaseError(str(failure)) from None
    return request


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    try:
        checked.pr_check(os.environ, Path.cwd(), path=REQUEST_PATH, head_branch=HEAD_BRANCH,
                         canonical=canonical_request)
    except Rejected as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    except Exception:
        print("FAIL: PREVIEW_REQUEST_REVIEW_EVIDENCE", file=sys.stderr)
        return 1
    print("PASS: PREVIEW_REQUEST_REVIEW_TREE")
    return 0


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    raise SystemExit(main())
