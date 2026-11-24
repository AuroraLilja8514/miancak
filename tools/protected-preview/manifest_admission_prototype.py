#!/usr/bin/env python3
"""Offline prototype consuming fixture observations. Never registers a workflow or calls GitHub."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

import preview_ops as preview
import release_ops as ops

REQUEST_PATH = ".github/release-requests/" + preview.TAG + ".json"
BRANCH = "master"
REF = "refs/heads/" + BRANCH
HEAD_BRANCH = "release-request/" + preview.TAG
WORKFLOW_ID = 427797415
CI_WORKFLOW_ID = 352885306
REQUIRED = {"generated": "success", "unit": "success", "build-debug": "success"}
OWNER = {"id": preview.OWNER_ID, "login": ops.REPO.split("/")[0]}
MAX_FIXTURE_BYTES = 16 * 1024


class Rejected(ValueError):
    """A fixed local rejection code, safe to report without printing input."""


def require(condition, code):
    if not condition:
        raise Rejected(code)


def expect(record, fields, code):
    require(type(record) is dict and all(type(record.get(key)) is type(value) and record[key] == value
                                         for key, value in fields.items()), code)


def sha(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) and value != "0" * 40,
            "REQUEST_COMMIT")
    return value


def positive_id(value):
    require(type(value) is int and value > 0, "REQUEST_ID")
    return value


def canonical_request(parent):
    manifest = {
        "format_version": 1,
        "repository_id": ops.REPO_ID,
        "reviewed_base_sha": sha(parent),
        "kind": "protected-prerelease",
        "tag": preview.TAG,
        "draft_id": preview.DRAFT_ID,
        "make_latest": False,
        "candidate_sha": preview.SOURCE,
        "candidate_tree": preview.TREE,
        "application_input_sha256": preview.APP_INPUT,
        "application_id": ops.PACKAGE,
        "version_code": 7,
        "asset_name": preview.ATTACHMENT,
        "certificate_sha256": ops.CERTIFICATE,
        "baseline_release_id": preview.STABLE_ID,
        "baseline_version_code": 5,
        "baseline_apk_sha256": preview.STABLE_APK,
        "delivery_manifest_sha256": preview.DELIVERY,
    }
    return (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode()


def admit_fixture(raw, facts):
    """Exercise the gate against normalized mock host observations and exact manifest bytes."""
    parent = sha(facts["push"].get("before"))
    revision = sha(facts["push"].get("after"))
    require(parent != revision and raw == canonical_request(parent), "REQUEST_MANIFEST")
    manifest_digest = hashlib.sha256(raw).hexdigest()

    expect(facts["push"], {"ref": REF, "repository_id": ops.REPO_ID, "head_commit": revision,
                           "created": False, "deleted": False, "forced": False,
                           "commits": [revision]}, "REQUEST_PUSH_EVENT")
    expect(facts["push"].get("sender"), OWNER, "REQUEST_PUSH_ACTOR")
    run = facts["run"]
    run_id = positive_id(run.get("id"))
    expect(run, {"event": "push", "attempt": 1, "workflow_id": WORKFLOW_ID,
                 "workflow_ref": f"{ops.REPO}/{preview.WORKFLOW}@{REF}", "head_sha": revision,
                 "workflow_sha": revision, "repository_id": ops.REPO_ID, "head_repository_id": ops.REPO_ID,
                 "ref": REF, "ref_protected": True, "status": "in_progress", "conclusion": None},
           "REQUEST_RUN_SCOPE")
    for actor in ("actor", "triggering_actor"):
        expect(run.get(actor), OWNER, "REQUEST_RUN_ACTOR")
    require(facts["run_ids"] == [run_id] and all(type(i) is int for i in facts["run_ids"]), "REQUEST_REPLAY")

    expect(facts["git"], {"live_master": revision, "checkout_sha": revision, "parents": [parent],
                          "changes": [{"path": REQUEST_PATH, "status": "added", "mode": "100644"}],
                          "request_in_ancestry": False}, "REQUEST_COMMIT_SCOPE")

    pr = facts["pr"]
    number = positive_id(pr.get("number"))
    head = sha(pr.get("head_sha"))
    expect(pr, {"state": "closed", "merged": True, "base_repository_id": ops.REPO_ID,
                "head_repository_id": ops.REPO_ID, "base_ref": BRANCH, "head_ref": HEAD_BRANCH,
                "base_sha": parent, "merge_commit_sha": revision, "manifest_sha256": manifest_digest},
           "REQUEST_PR_SCOPE")
    expect(pr.get("merged_by"), OWNER, "REQUEST_MERGER")
    require(facts["associated_pr_numbers"] == [number]
            and all(type(i) is int for i in facts["associated_pr_numbers"]), "REQUEST_PR_ASSOCIATION")

    tree = sha(facts["git"].get("tree"))
    expect(facts["checks"], {"event": "pull_request", "repository_id": ops.REPO_ID,
                            "workflow_id": CI_WORKFLOW_ID, "attempt": 1, "conclusion": "success",
                            "pr_number": number, "head_sha": head, "base_sha": parent,
                            "tested_tree": tree, "required": REQUIRED, "artifacts": 0,
                            "caches_disabled": True}, "REQUEST_CHECKS")


def unique_object(pairs):
    record = dict(pairs)
    require(len(record) == len(pairs), "REQUEST_DUPLICATE_JSON_KEY")
    return record


def bounded_read(path):
    with path.open("rb") as stream:
        raw = stream.read(MAX_FIXTURE_BYTES + 1)
    require(len(raw) <= MAX_FIXTURE_BYTES, "REQUEST_FIXTURE_SIZE")
    return raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--evidence-fixture", type=Path, required=True)
    args = parser.parse_args()
    try:
        raw = bounded_read(args.manifest)
        facts = json.loads(bounded_read(args.evidence_fixture), object_pairs_hook=unique_object)
        admit_fixture(raw, facts)
    except Rejected as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RecursionError):
        print("FAIL: REQUEST_FIXTURE_INVALID", file=sys.stderr)
        return 1
    print("PASS: LOCAL_MANIFEST_FIXTURE")
    return 0


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    raise SystemExit(main())
