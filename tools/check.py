#!/usr/bin/env python3
"""Recompute, from the atom's input alone, what GitHub should now hold — then compare.

This is deliberately a second implementation, not a call into the atom: it resolves the repository the same way the
plugin does (account+repo, owner/name, GIT_URL), maps BUILD_STATUS to a conclusion, expands ${VAR}, applies the
plugin's truncation, validates nothing (the atom is supposed to have done that) and builds the expected annotation
list. If the atom and this script disagree, one of them is wrong, and the server's state file says which.

Usage: check.py <run-dir> <state-file> <request-log> [--expect-annotations N]
Prints one JSON line starting with STATE and exits non-zero on any mismatch.
"""
import json
import os
import re
import sys

RUN_DIR, STATE_FILE, LOG_FILE = sys.argv[1], sys.argv[2], sys.argv[3]
SINCE = 0
if "--since" in sys.argv:
    SINCE = int(sys.argv[sys.argv.index("--since") + 1])
SHAS = {"abcdef1": "abcdef1234567890abcdef1234567890abcdef12"}
VAR = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_.]*)")
CONCLUSION = {
    "SUCCESS": "success", "OK": "success", "PASSED": "success",
    "UNSTABLE": "neutral",
    "FAILURE": "failure", "FAILED": "failure", "ERROR": "failure",
    "NOT_BUILT": "skipped", "NOTBUILT": "skipped", "SKIPPED": "skipped",
    "ABORTED": "failure",
}
TITLE = {"SUCCESS": "Success", "OK": "Success", "PASSED": "Success", "UNSTABLE": "Unstable",
         "FAILURE": "Failure", "FAILED": "Failure", "ERROR": "Failure",
         "NOT_BUILT": "Skipped", "NOTBUILT": "Skipped", "SKIPPED": "Skipped",
         "ABORTED": "Aborted"}


def fail(*lines):
    for line in lines:
        print("MISMATCH: " + line)
    raise SystemExit(1)


given = json.load(open(os.path.join(RUN_DIR, "input.json"), encoding="utf-8"))


def param(name, default=""):
    value = given.get(name, default)
    return "" if value is None else str(value)


def expand(text):
    return VAR.sub(lambda m: param(m.group(1)), text or "")


def workspace_path(name):
    return name if os.path.isabs(name) else os.path.join(RUN_DIR, name)


def read_file_param(param_name):
    path = param(param_name)
    if not path:
        return None
    with open(workspace_path(path), encoding="utf-8") as f:
        return f.read()


def truncate(text, limit):
    if text is None or len(text) <= limit:
        return text
    note = "\n\n... [Output truncated; original size was %d characters] ..." % len(text)
    if limit <= len(note):
        return text[:max(0, limit)]
    cut = limit - len(note)
    while cut > 1 and 0xD800 <= ord(text[cut - 1]) <= 0xDBFF:
        cut -= 1
    return text[:cut] + note


# ---- repository
account, repo = param("account").strip(), param("repo").strip()
git_url = param("GIT_URL").strip()
if not repo and git_url:
    path = git_url.split("://", 1)[1].split("/", 1)[1] if "://" in git_url else git_url.split(":", 1)[1]
    path = path.lstrip("/")
    if path.endswith(".git"):
        path = path[:-4]
    parts = path.split("/")
    repo = "/".join(parts[-2:]) if len(parts) >= 2 else ""
if "/" in repo:
    head, tail = repo.split("/", 1)
    if not account:
        account, repo = head, tail
    elif account.lower() == head.lower():
        repo = tail

sha = param("sha") or param("GIT_COMMIT")
head_sha = SHAS.get(sha, sha)

name = expand(param("name")).strip() or param("JOB_NAME").strip() or "jenkins/atom"
status = param("status", "completed").strip().lower().replace("-", "_")
build_status = param("BUILD_STATUS").strip().upper()
conclusion = param("conclusion").strip().lower()
if not conclusion and status == "completed":
    conclusion = CONCLUSION.get(build_status, "neutral")
if conclusion == "canceled":
    conclusion = "cancelled"

title = expand(param("title")).strip()
if not title:
    title = TITLE.get(build_status, "Completed") if status == "completed" else "In progress"

summary = read_file_param("summaryFile")
summary = expand(summary) if summary is not None else expand(param("summary"))
if not summary:
    number = param("BUILD_NUMBER").strip()
    what = ("Build #" + number) if number else "This build"
    summary = {"queued": what + " is queued.", "in_progress": what + " is running."}.get(
        status, what + " finished as " + (build_status or "UNKNOWN") + ".")

text = read_file_param("textFile")
text = expand(text) if text is not None else expand(param("text"))

details_url = expand(param("detailsUrl") or param("BUILD_URL")).strip()

# ---- annotations, with the same defaults the atom applies
expected_annotations = []
annotations_json = read_file_param("annotationsFile") or param("annotations")
if annotations_json.strip():
    root = json.loads(annotations_json)
    items = root["annotations"] if isinstance(root, dict) else root
    for item in items:
        def pick(*keys):
            for key in keys:
                if item.get(key) not in (None, ""):
                    return item[key]
            return None
        start = int(pick("startLine", "start_line"))
        end = pick("endLine", "end_line")
        start_column = pick("startColumn", "start_column")
        expected_annotations.append({
            "path": pick("path", "fileName", "file"),
            "start_line": start,
            "end_line": int(end) if end is not None else start,
            "start_column": int(start_column) if start_column is not None else None,
            "end_column": int(pick("endColumn", "end_column")) if start_column is not None else None,
            "annotation_level": (pick("level", "annotationLevel", "annotation_level") or "warning").lower(),
            "message": pick("message"),
            "title": pick("title"),
        })

# ---- the size limit the write actually ended up with (a 422 with GitHub's wording means the atom retried lower)
limit = int(param("maxMessageSize", "65535") or 65535)
if "--limit" in sys.argv:
    # the API refused this many characters; GitHubChecksPublisher.calculateReducedLimit is what the atom must apply
    server_limit = int(sys.argv[sys.argv.index("--limit") + 1])
    oversized = max(len(text or ""), len(summary or ""))
    overshoot = max(0, oversized - server_limit)
    limit = max(100, server_limit - overshoot - 100)
log = ""
if os.path.exists(LOG_FILE):
    log = "".join(open(LOG_FILE, encoding="utf-8").readlines()[SINCE:])
for match in re.finditer(r"Only (\d+) characters are allowed; (\d+) were supplied", log, re.I):
    allowed, supplied = int(match.group(1)), int(match.group(2))
    overshoot = max(0, supplied - allowed)
    limit = max(100, allowed - overshoot - 100)

state = json.load(open(STATE_FILE, encoding="utf-8")) if os.path.exists(STATE_FILE) else {"runs": {}}
runs = list(state.get("runs", {}).values())
mine = [r for r in runs if r["name"] == name and r["head_sha"] == head_sha]
if not mine:
    fail("no check run named '%s' on %s; the server holds %s" % (name, head_sha, runs))
run = mine[-1]

expected = {
    "name": name, "head_sha": head_sha, "status": status,
    "conclusion": conclusion if status == "completed" else None,
    "output": {"title": title, "summary": truncate(summary, limit), "text": truncate(text, limit) or None},
}
for key in ("name", "head_sha", "status", "conclusion"):
    if run.get(key) != expected[key]:
        fail("%s is %r, expected %r" % (key, run.get(key), expected[key]))
for key in ("title", "summary", "text"):
    stored = run["output"].get(key)
    if (stored or None) != (expected["output"][key] or None):
        if key == "summary":
            fail("output.summary differs: %d chars stored, %d expected (limit %d)"
                 % (len(stored or ""), len(expected["output"][key] or ""), limit))
        fail("output.%s is %r, expected %r" % (key, (stored or "")[:120], (expected["output"][key] or "")[:120]))
if details_url and run.get("details_url") != details_url:
    fail("details_url is %r, expected %r" % (run.get("details_url"), details_url))

stored_annotations = run["output"]["annotations"]
if len(stored_annotations) != len(expected_annotations):
    fail("the server holds %d annotations, expected %d" % (len(stored_annotations), len(expected_annotations)))
for index, (want, got) in enumerate(zip(expected_annotations, stored_annotations), 1):
    for key in ("path", "start_line", "end_line", "annotation_level", "message"):
        if want[key] != got.get(key):
            fail("annotation %d: %s is %r, expected %r" % (index, key, got.get(key), want[key]))
    if want["start_column"] is not None:
        if want["start_column"] != got.get("start_column") or want["end_column"] != got.get("end_column"):
            fail("annotation %d: columns are %r-%r, expected %r-%r"
                 % (index, got.get("start_column"), got.get("end_column"), want["start_column"], want["end_column"]))

# ---- the request log has to show the shape the API demands: no request with more than 50 annotations
batches = [len(json.loads(line.split("\t")[4])["output"]["annotations"])
           for line in log.splitlines()
           if line.count("\t") >= 4 and "\"annotations\"" in line.split("\t")[4]]
if any(size > 50 for size in batches):
    fail("a request carried more than 50 annotations: %s" % batches)

print("STATE " + json.dumps({"id": run["id"], "name": name, "headSha": head_sha, "status": status,
                             "conclusion": conclusion, "batches": batches,
                             "annotations": len(stored_annotations)}, ensure_ascii=False))
