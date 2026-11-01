#!/usr/bin/env python3
"""A fake GitHub checks API, strict on purpose.

It is not a mock that says yes: it validates the payload the way the REST docs describe the endpoint, it keeps the
check runs and their annotations in a state file, and it appends annotations on every update (the API does), so the
smoke test can compare what the atom meant to publish with what the server actually holds.

Failure modes, for the paths that only a real server can reach:

  --lie-conclusion      store a conclusion other than the one sent (a proxy, a wrong field name, a lost body)
  --drop-annotations    store only the first annotation of every request
  --limit-size N        refuse summaries/texts longer than N characters, with GitHub's wording
                        ("Only N characters are allowed; M were supplied")

Usage: fake-check-api.py <request-log> <port> <state-file> [--limit-size N] [--lie-conclusion] [--drop-annotations]
"""
import http.server
import json
import sys
import urllib.parse

LOG = sys.argv[1]
PORT = int(sys.argv[2])
STATE = sys.argv[3]
ARGS = sys.argv[4:]


def flag(name):
    return name in ARGS


def option(name, default=None):
    if name in ARGS:
        return ARGS[ARGS.index(name) + 1]
    return default


LIE_CONCLUSION = flag("--lie-conclusion")
DROP_ANNOTATIONS = flag("--drop-annotations")
LIMIT_SIZE = int(option("--limit-size", 65535))
GOOD_TOKEN = "good-token"
CONCLUSIONS = {"success", "failure", "neutral", "cancelled", "skipped", "stale", "timed_out", "action_required"}
STATUSES = {"queued", "in_progress", "completed"}
LEVELS = {"notice", "warning", "failure"}
SHAS = {"abcdef1": "abcdef1234567890abcdef1234567890abcdef12"}

RUNS = {}
NEXT_ID = [1]


def load():
    try:
        with open(STATE, encoding="utf-8") as f:
            data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        data = {}
    RUNS.update({int(k): v for k, v in data.get("runs", {}).items()})
    NEXT_ID[0] = data.get("nextId", 1)


def save():
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump({"runs": {str(k): v for k, v in RUNS.items()}, "nextId": NEXT_ID[0]}, f, indent=1)


def public(run, with_annotations=True):
    body = {
        "id": run["id"],
        "name": run["name"],
        "head_sha": run["head_sha"],
        "status": run["status"],
        "conclusion": run["conclusion"],
        "started_at": run.get("started_at"),
        "completed_at": run.get("completed_at"),
        "details_url": run.get("details_url"),
        "external_id": run.get("external_id"),
        "output": {
            "title": run["output"]["title"],
            "summary": run["output"]["summary"],
            "text": run["output"].get("text"),
            "annotations_count": len(run["output"]["annotations"]),
        },
    }
    if with_annotations:
        body["output"]["annotations"] = run["output"]["annotations"]
    return body


def fail(field, message):
    return 422, {"message": "Validation Failed", "errors": [{"resource": "CheckRun", "field": field, "code": "invalid", "message": message}]}


def size_error(length, id_):
    allowed = LIMIT_SIZE
    return 422, {"message": "Validation Failed", "errors": [{
        "resource": "CheckRun", "field": "output.%s" % id_, "code": "invalid",
        "message": "Only %d characters are allowed; %d were supplied" % (allowed, length)}]}


def validate_annotations(items):
    if not isinstance(items, list):
        return ["annotations must be an array"]
    errors = []
    if len(items) > 50:
        errors.append("too many annotations: %d in one request, the API takes 50" % len(items))
    for index, item in enumerate(items, 1):
        if not isinstance(item, dict):
            errors.append("annotation %d is not an object" % index)
            continue
        path = item.get("path")
        if not path or not isinstance(path, str):
            errors.append("annotation %d has no path" % index)
        elif path.startswith("/"):
            errors.append("annotation %d: path must be relative to the repository root" % index)
        level = item.get("annotation_level")
        if level not in LEVELS:
            errors.append("annotation %d: annotation_level %r is not notice/warning/failure" % (index, level))
        if not item.get("message"):
            errors.append("annotation %d has no message" % index)
        start, end = item.get("start_line"), item.get("end_line")
        if not isinstance(start, int) or start < 1:
            errors.append("annotation %d: start_line must be >= 1" % index)
        if not isinstance(end, int) or (isinstance(start, int) and end < start):
            errors.append("annotation %d: end_line must be >= start_line" % index)
        start_column, end_column = item.get("start_column"), item.get("end_column")
        if (start_column is None) != (end_column is None):
            errors.append("annotation %d: start_column and end_column go together" % index)
        if start_column is not None:
            if start != end:
                errors.append("annotation %d: columns are only accepted on a single line" % index)
            if not isinstance(start_column, int) or not isinstance(end_column, int) or end_column < start_column:
                errors.append("annotation %d: end_column must be >= start_column" % index)
    return errors


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _log(self, body):
        auth = self.headers.get("Authorization") or "-"
        version = self.headers.get("X-GitHub-Api-Version") or "-"
        with open(LOG, "a", encoding="utf-8") as log:
            log.write("%s\t%s\t%s\t%s\t%s\n" % (self.command, self.path, auth, version, body))

    def _send(self, code, payload):
        raw = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _authorized(self):
        auth = self.headers.get("Authorization")
        if auth != "Bearer " + GOOD_TOKEN:
            self._send(401, {"message": "Bad credentials"})
            return False
        return True

    def _parts(self):
        path = urllib.parse.urlsplit(self.path).path
        return [p for p in path.split("/") if p]

    def _query(self):
        return urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)

    def do_GET(self):
        self._log("")
        parts = self._parts()
        if parts == ["user"]:
            if not self._authorized():
                return
            return self._send(200, {"login": "ci"})
        if not self._authorized():
            return
        if len(parts) == 3 and parts[0] == "repos":
            if parts[2] == "nope":
                return self._send(404, {"message": "Not Found"})
            return self._send(200, {"full_name": parts[1] + "/" + parts[2]})
        if len(parts) == 5 and parts[0] == "repos" and parts[3] == "commits":
            sha = parts[4]
            if sha.startswith("dead"):
                return self._send(404, {"message": "No commit found for SHA: " + sha})
            return self._send(200, {"sha": SHAS.get(sha, sha)})
        if len(parts) == 6 and parts[0] == "repos" and parts[3] == "commits" and parts[5] == "check-runs":
            wanted = (self._query().get("check_name") or [None])[0]
            runs = [public(r) for r in RUNS.values() if r["head_sha"] == SHAS.get(parts[4], parts[4])
                    and (wanted is None or r["name"] == wanted)]
            return self._send(200, {"total_count": len(runs), "check_runs": runs})
        if len(parts) == 5 and parts[0] == "repos" and parts[3] == "check-runs":
            run = RUNS.get(int(parts[4]))
            if not run:
                return self._send(404, {"message": "Not Found"})
            return self._send(200, public(run))
        if len(parts) == 6 and parts[0] == "repos" and parts[3] == "check-runs" and parts[5] == "annotations":
            run = RUNS.get(int(parts[4]))
            if not run:
                return self._send(404, {"message": "Not Found"})
            per_page = int((self._query().get("per_page") or ["30"])[0])
            page = int((self._query().get("page") or ["1"])[0])
            items = run["output"]["annotations"][(page - 1) * per_page: page * per_page]
            return self._send(200, [dict(item, blob_href="https://github.com/blob/" + item["path"]) for item in items])
        return self._send(404, {"message": "Not Found"})

    def _write(self, run, body):
        """Apply a create/update body the way the API does, appending annotations."""
        if "name" in body and body["name"]:
            run["name"] = body["name"]
        if "head_sha" in body:
            run["head_sha"] = SHAS.get(body["head_sha"], body["head_sha"])
        if "status" in body:
            if body["status"] not in STATUSES:
                return fail("status", "status %r is not queued/in_progress/completed" % body["status"])
            run["status"] = body["status"]
        if "conclusion" in body and body["conclusion"] is not None:
            if run["status"] != "completed":
                return fail("conclusion", "a conclusion is only accepted when the check run is completed")
            if body["conclusion"] not in CONCLUSIONS:
                return fail("conclusion", "conclusion %r is not a GitHub conclusion" % body["conclusion"])
            run["conclusion"] = "neutral" if LIE_CONCLUSION else body["conclusion"]
        if run["status"] == "completed" and not run.get("conclusion"):
            return fail("conclusion", "conclusion is required when status is completed")
        for key in ("details_url", "external_id", "started_at", "completed_at"):
            if body.get(key):
                run[key] = body[key]
        if "output" in body:
            output = body["output"] or {}
            for key in ("summary", "text"):
                if output.get(key) is not None and len(output[key]) > LIMIT_SIZE:
                    return size_error(len(output[key]), key)
            if output.get("title") is not None:
                run["output"]["title"] = output["title"]
            if output.get("summary") is not None:
                run["output"]["summary"] = output["summary"]
            if output.get("text") is not None:
                run["output"]["text"] = output["text"]
            if output.get("annotations") is not None:
                errors = validate_annotations(output["annotations"])
                if errors:
                    return fail("output.annotations", "; ".join(errors))
                items = output["annotations"]
                if DROP_ANNOTATIONS:
                    items = items[:1]
                run["output"]["annotations"].extend(items)
        if run["status"] == "completed" and not run["output"]["title"]:
            return fail("output.title", "output.title is required")
        return None

    def do_POST(self):
        length = int(self.headers.get("content-length", 0))
        raw = self.rfile.read(length).decode("utf-8")
        self._log(raw)
        parts = self._parts()
        if len(parts) != 4 or parts[0] != "repos" or parts[3] != "check-runs":
            return self._send(404, {"message": "Not Found"})
        if not self._authorized():
            return
        body = json.loads(raw)
        if not body.get("name"):
            return self._send(*fail("name", "name is required"))
        if not body.get("head_sha"):
            return self._send(*fail("head_sha", "head_sha is required"))
        if body.get("status") not in STATUSES:
            return self._send(*fail("status", "status must be queued, in_progress or completed"))
        if "output" in body and not (body["output"] or {}).get("summary"):
            return self._send(*fail("output.summary", "summary is required"))
        if (body.get("output") or {}).get("annotations") is not None:
            errors = validate_annotations(body["output"]["annotations"])
            if errors:
                return self._send(*fail("output.annotations", "; ".join(errors)))

        run = {"id": NEXT_ID[0], "name": body["name"], "head_sha": SHAS.get(body["head_sha"], body["head_sha"]),
               "status": "queued", "conclusion": None, "output": {"title": "", "summary": "", "annotations": []}}
        NEXT_ID[0] += 1
        error = self._write(run, body)
        if error:
            return self._send(*error)
        RUNS[run["id"]] = run
        save()
        return self._send(201, public(run))

    def do_PATCH(self):
        length = int(self.headers.get("content-length", 0))
        raw = self.rfile.read(length).decode("utf-8")
        self._log(raw)
        parts = self._parts()
        if len(parts) != 5 or parts[0] != "repos" or parts[3] != "check-runs":
            return self._send(404, {"message": "Not Found"})
        if not self._authorized():
            return
        run = RUNS.get(int(parts[4]))
        if not run:
            return self._send(404, {"message": "Not Found"})
        body = json.loads(raw)
        error = self._write(run, body)
        if error:
            return self._send(*error)
        save()
        return self._send(200, public(run))

    def log_message(self, *args):
        pass


load()
# threads: the atom keeps its connection alive between requests, and one blocked accept would hang the run
http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
