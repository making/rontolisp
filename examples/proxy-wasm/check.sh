#!/usr/bin/env bash
# Build gateway.wasm, run it in a real Envoy under Docker, and check what it does
# from the outside with curl. Exits 0 only if every check passed.
#
# Environment: ENVOY_IMAGE (default below), GATEWAY_PORT, ADMIN_PORT.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
image="${ENVOY_IMAGE:-envoyproxy/envoy:v1.39.3}"
port="${GATEWAY_PORT:-18080}"
admin="${ADMIN_PORT:-19901}"
container="rontolisp-proxy-wasm-$$"

if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar (build it from the repo root: ./mvnw clean package)" >&2
  exit 1
fi

echo "compiling gateway.lisp -> gateway.wasm"
java -jar "$jar" "$here/gateway.lisp" -o "$here/gateway.wasm" --no-wasi --optimize

trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$container" -p "$port:8080" -p "$admin:9901" \
  -v "$here/envoy.yaml:/etc/envoy/envoy.yaml:ro" \
  -v "$here/gateway.wasm:/etc/envoy/gateway.wasm:ro" \
  "$image" -c /etc/envoy/envoy.yaml --concurrency 2 >/dev/null

url="http://localhost:$port"
for _ in $(seq 1 50); do
  curl -s -m 1 -o /dev/null "http://localhost:$admin/ready" && break
  sleep 0.2
done

failures=0
pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; echo "       $2"; failures=$((failures + 1)); }

# check NAME GREP-PATTERN CURL-ARGUMENTS... : the whole response, headers included.
check() {
  local name="$1" pattern="$2"
  shift 2
  local response
  response="$(curl -s -i -m 5 "$@" 2>&1 || true)"
  if grep -qiE "$pattern" <<<"$response"; then pass "$name"; else fail "$name" "no /$pattern/ in: $response"; fi
}

check_not() {
  local name="$1" pattern="$2"
  shift 2
  local response
  response="$(curl -s -i -m 5 "$@" 2>&1 || true)"
  if grep -qiE "$pattern" <<<"$response"; then fail "$name" "/$pattern/ in: $response"; else pass "$name"; fi
}

check "a plain request reaches the upstream" '^HTTP/1.1 200' "$url/"
check "the upstream sees the request number the filter added" '^x-seen-request-number: [0-9]+' "$url/"
check "the response is stamped from the plugin configuration" '^x-powered-by: rontolisp' "$url/"
check "the filter reads the whole header map" '^x-header-count: [0-9]+' "$url/"
check_not "a header from the upstream is removed" 'x-internal-secret' "$url/"

check "a blocked prefix is refused locally" '^HTTP/1.1 403' "$url/admin/users"
check "the local response carries the filter's header" '^x-blocked-by: gateway' "$url/admin/users"
check "the local response carries the filter's body" '"refused":"blocked path"' "$url/internal"

check "an authorized user passes the outgoing call" '^HTTP/1.1 200' -H 'x-user: alice' "$url/guarded"
check "the call's reply body reaches the upstream" '^x-seen-authorized-by: authz-service' -H 'x-user: alice' "$url/guarded"
check "an unknown user is refused from the call's reply" '^HTTP/1.1 403' -H 'x-user: mallory' "$url/guarded"
check "no user is refused without a call" '^HTTP/1.1 401' "$url/guarded"

check "a clean body passes" '^HTTP/1.1 200' -d 'lisp is alive' "$url/"
check "a body with the forbidden word is refused" '^HTTP/1.1 422' -d 'they say lisp-is-dead again' "$url/"

check "the response body is rewritten" 'HELLO FROM UPSTREAM' "$url/shout"
check_not "the stale content-length is gone" '^content-length: 20' "$url/shout"

# A tick has to run for the gauge; the counter moved above.
sleep 1
stats="$(curl -s "http://localhost:$admin/stats?filter=gateway\\.")"
if grep -qE 'gateway\.blocked: [1-9]' <<<"$stats"; then pass "the counter metric moved"; else fail "the counter metric moved" "$stats"; fi
if grep -qE 'gateway\.requests_seen: [1-9]' <<<"$stats"; then pass "a tick published the gauge"; else fail "a tick published the gauge" "$stats"; fi

logs="$(docker logs "$container" 2>&1)"
if grep -q 'gateway: plugin lisp-gateway configured' <<<"$logs"; then pass "the configuration was read"; else fail "the configuration was read" "no configure line in the log"; fi
if grep -qE 'gateway: GET /admin/users -> 403 in [0-9]+ us' <<<"$logs"; then pass "the access line is logged"; else fail "the access line is logged" "no access line in the log"; fi
if grep -q 'proxy-wasm: ' <<<"$logs"; then fail "no callback raised an error" "$(grep 'proxy-wasm: ' <<<"$logs" | head -3)"; else pass "no callback raised an error"; fi

# A resident VM must not grow: every callback pops the bump heap it used. The ticks
# run between requests, so the gauge reads the heap with no callback holding any of it.
heap_top() {
  sleep 0.5
  curl -s "http://localhost:$admin/stats?filter=gateway\\.heap_top" | sed 's/.*: //'
}
before="$(heap_top)"
for _ in $(seq 1 300); do curl -s -o /dev/null -H 'x-user: alice' "$url/guarded"; done
after="$(heap_top)"
if [[ -n "$before" && "$before" == "$after" ]]; then
  pass "the bump heap is flat over 300 requests ($after)"
else
  fail "the bump heap is flat over 300 requests" "before: $before, after: $after"
fi

if ((failures > 0)); then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
