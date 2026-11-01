#!/usr/bin/env python3
"""Plant one bug at a time and require the smoke test to go red.

A smoke test that passes proves nothing unless it can fail. Each mutation is a single textual change to the atom that a
plausible mistake would produce; the smoke test has to catch every one of them, and the script prints which group did.

Usage: python3 tools/mutations.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
ATOM = "src/main/java/com/jenkinschina/atom/githubchecks/GitHubChecksAtom.java"
def find_sdk():
    """The SDK is a sibling on a workstation and a subdirectory of the checkout in CI; build.sh knows both."""
    for base in (REPO / "jenkins-atom-sdk", REPO.parent / "jenkins-atom-sdk"):
        jars = sorted((base / "target").glob("jenkins-atom-sdk-*.jar"))
        if jars:
            return jars[-1]
    return None


SDK = find_sdk()

MUTATIONS = [
    ("unstable-is-not-neutral", ATOM,
     'case "UNSTABLE" -> "neutral";', 'case "UNSTABLE" -> "failure";'),
    ("no-chunking-of-annotations", ATOM,
     "private static final int ANNOTATIONS_PER_REQUEST = 50;",
     "private static final int ANNOTATIONS_PER_REQUEST = 1000;"),
    ("no-read-back", ATOM,
     'if (!context.getBooleanParam("verify", true)) {', 'if (true) {'),
    ("columns-dropped", ATOM,
     '                map.put("start_column", startColumn);',
     '                map.put("start_column", 0);'),
    ("conclusion-on-unfinished-run", ATOM,
     'if (!"completed".equals(status) && !conclusion.isEmpty()) {', 'if (false) {'),
    ("input-sha-instead-of-resolved", ATOM,
     "                headSha = canonical;", "                headSha = sha;"),
    ("no-size-retry", ATOM,
     "            Integer reduced = reducedLimit(response.body);",
     "            Integer reduced = null;"),
]


def run(name, relative, old, new):
    work = pathlib.Path(tempfile.mkdtemp(prefix="mutate-" + name + "-"))
    target = work / "repo"
    shutil.copytree(REPO, target, ignore=shutil.ignore_patterns(".git", "dist", "*.class"))
    path = target / relative
    text = path.read_text(encoding="utf-8")
    if old not in text:
        print("  SKIP %-32s (the line is gone: %s)" % (name, old.strip()[:50]))
        return None
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    env = dict(os.environ)
    if SDK is not None:
        env["SDK_JAR"] = str(SDK)
    result = subprocess.run(["bash", "tools/smoke.sh"], cwd=target, env=env,
                            capture_output=True, text=True)
    output = result.stdout + result.stderr
    if result.returncode == 0:
        print("  MISSED %-30s smoke stayed green with the bug in place" % name)
        return False
    line = [l.strip() for l in output.splitlines() if l.strip().startswith("FAIL")]
    print("  caught %-30s %s" % (name, line[-1] if line else output.strip().splitlines()[-1]))
    shutil.rmtree(work, ignore_errors=True)
    return True


def main():
    print("mutations against the smoke test (%d)" % len(MUTATIONS))
    results = [run(*m) for m in MUTATIONS]
    missed = [r for r in results if r is False]
    if missed:
        print("FAILED: %d mutation(s) were not caught" % len(missed))
        raise SystemExit(1)
    print("all %d mutations were caught" % len([r for r in results if r]))


if __name__ == "__main__":
    main()
