#!/usr/bin/env bash
# Smoke test for github-checks. What is asserted is not "exit code 0": it is the payload the API received, the shape the
# API demands (50 annotations per request, appended), and the state the server holds afterwards -- recomputed by
# tools/check.py from the atom's input, not read back from the atom's own output.
set -uo pipefail
cd "$(dirname "$0")/.."
export BUILD_NUMBER=99 JOB_NAME=parcel BUILD_STATUS=UNSTABLE BUILD_URL=http://jenkins/job/parcel/99/
export BUILD_TAG=jenkins-parcel-99
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"
fixtures="$PWD/tools/fixtures"
host=127.0.0.1
pass=0
ok() { pass=$((pass + 1)); echo "  ok  $1"; }
bad() { echo "  FAIL $1" >&2; exit 1; }

start_server() {  # start_server <name> <port> [server flags...]
  local name="$1" port="$2"; shift 2
  python3 tools/fake-check-api.py "$work/$name.tsv" "$port" "$work/$name.state.json" "$@" \
    >"$work/$name.server.log" 2>&1 &
  echo $!
}
wait_server() {  # wait_server <base>
  for _ in $(seq 1 60); do
    curl -fsS -H "Authorization: Bearer good-token" -H "X-GitHub-Api-Version: 2022-11-28" \
      -o /dev/null "$1/repos/probe/repo" 2>/dev/null && return 0
    sleep 0.2
  done
  fail "the fake API at $1 did not come up"
}

port_main=$(( 22000 + RANDOM % 2000 ))
port_size=$(( port_main + 2000 ))
port_lie=$(( port_main + 4000 ))
port_drop=$(( port_main + 6000 ))
api_main="http://$host:$port_main"; log_main="$work/main.tsv"; state_main="$work/main.state.json"
api_size="http://$host:$port_size"; log_size="$work/size.tsv"; state_size="$work/size.state.json"
api_lie="http://$host:$port_lie";   log_lie="$work/lie.tsv";   state_lie="$work/lie.state.json"
api_drop="http://$host:$port_drop"; log_drop="$work/drop.tsv"; state_drop="$work/drop.state.json"

s1=$(start_server main "$port_main")
s2=$(start_server size "$port_size" --limit-size 400)
s3=$(start_server lie "$port_lie" --lie-conclusion)
s4=$(start_server drop "$port_drop" --drop-annotations)
trap 'kill $s1 $s2 $s3 $s4 2>/dev/null || true' EXIT
wait_server "$api_main"; wait_server "$api_size"; wait_server "$api_lie"; wait_server "$api_drop"

dir=""
newdir() { dir="$work/$1"; mkdir -p "$dir"; printf '{"token": "good-token"}' >"$dir/.secrets.json"; }
write_input() { printf '%s' "$1" >"$dir/input.json"; }
atom() { run "$dir" "$@" >"$dir/log.txt" 2>&1; }
code() { python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('errorCode',''))" "$dir/output.json"; }
mark() { wc -l <"$1" | tr -d ' '; }
since() { tail -n +"$(( $1 + 1 ))" "$2"; }

# ---------------------------------------------------------------- 1. the happy path
newdir basic
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
  \"name\": \"parcel/ci\", \"annotationsFile\": \"$fixtures/annotations.json\",
  \"text\": \"see \${BUILD_URL}console\"}"
atom || bad "the atom failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || bad "not a success: $(cat "$dir/output.json")"
python3 tools/check.py "$dir" "$state_main" "$log_main" >"$dir/check.txt" || bad "$(cat "$dir/check.txt")"
[ "$(output "$dir" id)" = "1" ] || bad "id: $(cat "$dir/output.json")"
[ "$(output "$dir" conclusion)" = "neutral" ] || bad "UNSTABLE must map to neutral: $(cat "$dir/output.json")"
[ "$(output "$dir" headSha)" = "abcdef1234567890abcdef1234567890abcdef12" ] || bad "the short sha must be resolved"
[ "$(output "$dir" annotationsVerified)" = "3" ] || bad "annotations were not read back: $(cat "$dir/output.json")"
grep -q "POST	/repos/acme/parcel/check-runs" "$log_main" || bad "the create did not go to /check-runs"
grep -q '"conclusion":"neutral"' "$log_main" || bad "the payload did not carry the mapped conclusion"
grep -q '"start_column":5' "$log_main" || bad "the columns were dropped from the payload"
grep -q '"head_sha":"abcdef1234567890abcdef1234567890abcdef12"' "$log_main" || bad "the resolved sha was not used"
grep -q "good-token" "$dir/log.txt" && bad "the token must not be in the atom's log"
awk -F'\t' '$2 ~ /^\/repos\/acme/ && $4 != "2022-11-28" { exit 1 }' "$log_main" || bad "the API version header is not pinned"
ok "create: neutral conclusion, resolved sha, 3 annotations with columns, read back"

# ---------------------------------------------------------------- 2. build status -> conclusion
for pair in "SUCCESS:success" "FAILURE:failure" "ABORTED:failure" "NOT_BUILT:skipped"; do
  build="${pair%%:*}"; want="${pair##*:}"
  newdir "state-$build"
  write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
    \"name\": \"parcel/$build\", \"summary\": \"from \${JOB_NAME}\"}"
  atom BUILD_STATUS="$build" || bad "the atom failed for $build: $(cat "$dir/log.txt")"
  [ "$(output "$dir" conclusion)" = "$want" ] || bad "$build must map to $want: $(cat "$dir/output.json")"
  python3 tools/check.py "$dir" "$state_main" "$log_main" >"$dir/check.txt" || bad "$(cat "$dir/check.txt")"
done
ok "SUCCESS/FAILURE/ABORTED/NOT_BUILT -> success/failure/failure/skipped, verified against the server"

# ---------------------------------------------------------------- 3. refused locally, no request
before=$(mark "$log_main")
newdir inprogress-conclusion
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
  \"status\": \"in_progress\", \"conclusion\": \"success\"}"
atom || true
[ "$(code)" = "40001" ] || bad "a conclusion on an unfinished check must be refused: $(cat "$dir/output.json")"
[ "$(mark "$log_main")" = "$before" ] || bad "the refused run still talked to the API"

for pair in "annotations-bad-line.json:endLine (2) is before startLine (5)" \
            "annotations-absolute.json:relative to the repository root" \
            "annotations-level.json:level must be notice, warning or failure" \
            "annotations-columns.json:columns are only accepted on a single line" \
            "annotations-broken.json:not JSON"; do
  file="${pair%%:*}"; want="${pair##*:}"
  newdir "bad-${file%.json}"
  write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
    \"annotationsFile\": \"$fixtures/$file\"}"
  atom || true
  [ "$(code)" = "40005" ] || bad "$file must be a 40005: $(cat "$dir/output.json")"
  grep -qF "$want" "$dir/log.txt" || bad "$file: the message does not say '$want': $(cat "$dir/log.txt")"
done
newdir bad-missing-file
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
  \"annotationsFile\": \"$fixtures/nope.json\"}"
atom || true
[ "$(code)" = "40005" ] || bad "a missing annotations file must be a 40005: $(cat "$dir/output.json")"
[ "$(mark "$log_main")" = "$before" ] || bad "a locally refused run still talked to the API"
ok "5 malformed annotation files and an unfinished conclusion are USER errors before any request"

# ---------------------------------------------------------------- 4. the preflight says no
before=$(mark "$log_main")
newdir bad-token
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\"}"
printf '{"token": "wrong-token"}' >"$dir/.secrets.json"
atom || true
[ "$(code)" = "40002" ] || bad "a bad token must be 40002: $(cat "$dir/output.json")"

newdir bad-repo
write_input "{\"account\": \"acme\", \"repo\": \"nope\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\"}"
atom || true
[ "$(code)" = "40003" ] || bad "a missing repository must be 40003: $(cat "$dir/output.json")"

newdir bad-commit
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"deadbeef\", \"apiUrl\": \"$api_main\"}"
atom || true
[ "$(code)" = "40004" ] || bad "a missing commit must be 40004: $(cat "$dir/output.json")"
since "$before" "$log_main" | grep -q "POST" && bad "a failed preflight still wrote a check run"
since "$before" "$log_main" | grep -q "PATCH" && bad "a failed preflight still patched a check run"
ok "credential/repository/commit are checked first; nothing is written when one is wrong"

# ---------------------------------------------------------------- 5. more annotations than one request may carry
before=$(mark "$log_main")
newdir many
python3 tools/gen-many-annotations.py "$dir/annotations.json" 120 >/dev/null
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
  \"name\": \"parcel/many\", \"annotationsFile\": \"annotations.json\"}"
atom || bad "120 annotations failed: $(cat "$dir/log.txt")"
python3 tools/check.py "$dir" "$state_main" "$log_main" --since "$before" >"$dir/check.txt" || bad "$(cat "$dir/check.txt")"
[ "$(output "$dir" annotationsVerified)" = "120" ] || bad "not 120 annotations on the server: $(cat "$dir/output.json")"
grep "^STATE" "$dir/check.txt" | grep -q '"batches": \[50, 50, 20\]' || bad "the batches are not 50/50/20: $(grep ^STATE "$dir/check.txt")"
ok "120 annotations go out as 50 + 50 + 20 and come back as 120 in order"

# ---------------------------------------------------------------- 6. reuse the same build's check, not another's
newdir reuse-first
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
  \"name\": \"parcel/reuse\", \"externalId\": \"parcel-99\"}"
atom || bad "first publish failed: $(cat "$dir/log.txt")"
first_id=$(output "$dir" id)
newdir reuse-second
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
  \"name\": \"parcel/reuse\", \"externalId\": \"parcel-99\", \"status\": \"completed\", \"conclusion\": \"success\"}"
atom || bad "second publish failed: $(cat "$dir/log.txt")"
[ "$(output "$dir" id)" = "$first_id" ] || bad "the same build's check was duplicated instead of updated"
python3 tools/check.py "$dir" "$state_main" "$log_main" >"$dir/check.txt" || bad "$(cat "$dir/check.txt")"
grep -q "PATCH	/repos/acme/parcel/check-runs/$first_id" "$log_main" || bad "re-running did not PATCH the existing check"

newdir other-build
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
  \"name\": \"parcel/reuse\", \"externalId\": \"parcel-100\"}"
atom || bad "a different build failed: $(cat "$dir/log.txt")"
[ "$(output "$dir" id)" != "$first_id" ] || bad "another build must not overwrite the first build's check"
ok "the same external_id updates the check, a different build gets its own"

# ---------------------------------------------------------------- 7. the server is the judge
newdir lying
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_lie\",
  \"name\": \"parcel/lie\", \"conclusion\": \"success\"}"
atom || true
[ "$(code)" = "40007" ] || bad "a server that stores another conclusion must be caught: $(cat "$dir/output.json")"
grep -q "conclusion" "$dir/log.txt" || bad "the mismatch message does not say which field: $(cat "$dir/log.txt")"

newdir dropping
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_drop\",
  \"name\": \"parcel/drop\", \"annotationsFile\": \"$fixtures/annotations.json\"}"
atom || true
[ "$(code)" = "40007" ] || bad "a server that drops annotations must be caught: $(cat "$dir/output.json")"
grep -q "annotations" "$dir/log.txt" || bad "the mismatch message does not mention annotations: $(cat "$dir/log.txt")"
ok "a lying conclusion and a dropped annotation are both caught by the read-back (40007)"

# ---------------------------------------------------------------- 8. GitHub's size limit, and the retry
newdir big
python3 -c "import sys;open(sys.argv[1],'w').write('line of a long report\n'*60)" "$dir/text.md"
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_size\",
  \"name\": \"parcel/big\", \"textFile\": \"text.md\"}"
atom || bad "the size retry failed: $(cat "$dir/log.txt")"
python3 tools/check.py "$dir" "$state_size" "$log_size" --limit 400 >"$dir/check.txt" || bad "$(cat "$dir/check.txt")"
[ "$(grep -c '^POST' "$log_size")" -ge 2 ] || bad "the atom did not retry after the 422"
grep -q "retrying with max message size" "$dir/log.txt" || bad "the retry was silent"
python3 tools/assert-truncated.py "$state_size" 400 || bad "the stored text was not truncated at the retried limit"
ok "an oversized summary/text is retried at the reduced limit, and the stored text is truncated"

# ---------------------------------------------------------------- 9. verify=false, GIT_URL, no token
before=$(mark "$log_main")
newdir no-verify
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\",
  \"name\": \"parcel/noverify\", \"verify\": \"false\"}"
atom || bad "verify=false failed: $(cat "$dir/log.txt")"
[ "$(output "$dir" verified)" = "not-checked" ] || bad "verify=false must say so: $(cat "$dir/output.json")"
since "$before" "$log_main" | grep -q "GET	/repos/acme/parcel/check-runs/" && bad "verify=false still read the run back"

newdir inferred
write_input "{\"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\", \"name\": \"parcel/inferred\",
  \"GIT_URL\": \"git@github.com:acme/inferred.git\"}"
atom || bad "GIT_URL inference failed: $(cat "$dir/log.txt")"
grep -q "POST	/repos/acme/inferred/check-runs" "$log_main" || bad "GIT_URL was not used to find the repository"

newdir no-token
write_input "{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\", \"apiUrl\": \"$api_main\"}"
printf '{}' >"$dir/.secrets.json"
atom || true
[ "$(code)" = "40002" ] || bad "a missing token must be 40002: $(cat "$dir/output.json")"
ok "verify=false skips the read-back, GIT_URL is understood, a missing token is a USER error"

echo "smoke passed: $pass groups"
