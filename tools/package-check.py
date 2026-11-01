#!/usr/bin/env python3
"""Run the atom that was published to the center, not the one in dist/.

The released zip is unpacked into a throwaway directory, a fake API is started, and the atom is run the way AtomRunner
runs it. If the package on the center is broken (missing SDK classes, a stale task.json), this is where it shows.

Usage: python3 tools/package-check.py <path/to/github-checks-<version>.zip> [port]
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

ZIP = pathlib.Path(sys.argv[1])
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 29517
REPO = pathlib.Path(__file__).resolve().parent.parent
work = pathlib.Path(tempfile.mkdtemp(prefix="pkg-"))
shutil.unpack_archive(str(ZIP), str(work))
jar = work / "github-checks.jar"
assert jar.is_file(), "the package has no github-checks.jar: %s" % list(work.iterdir())

server = subprocess.Popen(["python3", str(REPO / "tools/fake-check-api.py"), str(work / "api.tsv"),
                           str(PORT), str(work / "state.json")])
try:
    for _ in range(100):
        try:
            request = urllib.request.Request("http://127.0.0.1:%d/repos/acme/parcel" % PORT,
                                             headers={"Authorization": "Bearer good-token"})
            urllib.request.urlopen(request, timeout=1)
            break
        except Exception:
            time.sleep(0.1)

    run_dir = work / "run"
    run_dir.mkdir()
    data = {"account": "acme", "repo": "parcel", "sha": "abcdef1", "apiUrl": "http://127.0.0.1:%d" % PORT,
            "name": "parcel/ci", "BUILD_STATUS": "UNSTABLE", "BUILD_NUMBER": "99",
            "BUILD_URL": "http://jenkins/job/parcel/99/", "BUILD_TAG": "jenkins-parcel-99",
            "annotationsFile": str(REPO / "tools/fixtures/annotations.json"),
            "text": "published package"}
    (run_dir / "input.json").write_text(json.dumps(data), encoding="utf-8")
    (run_dir / ".secrets.json").write_text(json.dumps({"token": "good-token"}), encoding="utf-8")

    env = dict(os.environ, atom_data_dir=str(run_dir), atom_data_input="input.json",
               atom_data_output="output.json", atom_workspace=str(run_dir))
    java = str(pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else "java"
    result = subprocess.run([java, "-cp", str(jar), "com.jenkinschina.atom.sdk.AtomRunner"],
                            cwd=run_dir, env=env, capture_output=True, text=True)
    print(result.stdout.strip())
    print(result.stderr.strip())
    output = json.loads((run_dir / "output.json").read_text(encoding="utf-8"))
    print("OUTPUT " + json.dumps({k: v.get("value") for k, v in (output.get("data") or {}).items()}))
    state = json.loads((work / "state.json").read_text(encoding="utf-8"))
    runs = list(state["runs"].values())
    print("SERVER " + json.dumps({"runs": len(runs), "id": runs[0]["id"], "conclusion": runs[0]["conclusion"],
                                  "annotations": len(runs[0]["output"]["annotations"])}))
    if output.get("status") != "success":
        raise SystemExit("the published package failed")
finally:
    server.terminate()
    shutil.rmtree(work, ignore_errors=True)
