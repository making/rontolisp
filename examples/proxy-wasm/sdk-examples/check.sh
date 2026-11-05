#!/usr/bin/env bash
# Build each filter here, run it in a real Envoy under Docker, and check it from the
# outside with curl. Exits 0 only if every check passed.
#
# Environment: ENVOY_IMAGE (default below), GATEWAY_PORT, ADMIN_PORT. ../no-gc/check.sh
# runs the same checks on its filters through FILTERS_DIR and COMPILE_FLAGS, and each
# directory's check-errors.sh says what an error does on its SDK.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"
here="${FILTERS_DIR:-$here}"
jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
image="${ENVOY_IMAGE:-envoyproxy/envoy:v1.40.2}"
port="${GATEWAY_PORT:-18080}"
admin="${ADMIN_PORT:-19901}"
container="rontolisp-proxy-wasm-sdk-examples-$$"
url="http://localhost:$port"
failures=0

if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar (build it from the repo root: ./mvnw clean package)" >&2
  exit 1
fi

trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT

# start FILTER [COMPILE-FLAG...]: the filter in the place of the gateway, behind the
# same configuration.
start() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  # shellcheck disable=SC2086
  java -jar "$jar" "$here/$1.lisp" -o "$here/$1.wasm" --optimize=size ${COMPILE_FLAGS:-} "${@:2}"
  docker run -d --name "$container" -p "$port:8080" -p "$admin:9901" \
    -v "$here/../envoy.yaml:/etc/envoy/envoy.yaml:ro" \
    -v "$here/$1.wasm:/etc/envoy/gateway.wasm:ro" \
    "$image" -c /etc/envoy/envoy.yaml --concurrency 1 >/dev/null
  for _ in $(seq 1 50); do
    curl -s -m 1 -o /dev/null "http://localhost:$admin/ready" && break
    sleep 0.2
  done
  echo "--- $1 ($(wc -c <"$here/$1.wasm" | tr -d ' ') bytes)"
}

# check NAME GREP-PATTERN CURL-ARGUMENTS... : the whole response, headers included.
check() {
  local name="$1" pattern="$2"
  shift 2
  local response
  response="$(curl -s -i -m 5 "$@" 2>&1 || true)"
  if grep -qiE "$pattern" <<<"$response"; then
    echo "ok   - $name"
  else
    echo "FAIL - $name"
    echo "       no /$pattern/ in: $response"
    failures=$((failures + 1))
  fi
}

check_log() {
  local logs
  logs="$(docker logs "$container" 2>&1)"
  if grep -qE "$2" <<<"$logs"; then
    echo "ok   - $1"
  else
    echo "FAIL - $1"
    echo "       no /$2/ in the log"
    failures=$((failures + 1))
  fi
}

start http_body --no-wasi
check "a body without the word passes" 'hello from upstream' "$url/"
check "a body with the word is redacted" 'Original message body \(8 bytes\) redacted' "$url/secret"
if grep -q 'proxy-wasm: ' <<<"$(docker logs "$container" 2>&1)"; then
  echo "FAIL - a callback raised an error"
  failures=$((failures + 1))
else
  echo "ok   - no callback raised an error"
fi

start http_headers --no-wasi
check "a plain request reaches the upstream" 'hello from upstream' "$url/"
check "/hello is answered here" 'Hello, World!' "$url/hello"
check "the local response carries its header" '^powered-by: proxy-wasm' "$url/hello"
sleep 0.2
check_log "the request head is logged" '#[0-9]+ -> :path: /'
check_log "the response head is logged" '#[0-9]+ <- :status: 200'
check_log "the end of a stream is logged" '#[0-9]+ completed\.'

start test_plugin
check "a head is edited" '^x-plugin: edited' "$url/edit"
check "a local response" '^HTTP/1.1 403' "$url/local"
check "its body" 'denied' "$url/local"
check "a body is replaced, then appended to" 'HELLO FROM UPSTREAM' "$url/replace"
check "what was appended" '<end>' "$url/replace"
check "a plain request passes" '^HTTP/1.1 200' "$url/stdout"
sleep 0.2
check_log "what it writes to stdout reaches the log" 'to stdout'

# shellcheck disable=SC1091
source "$here/check-errors.sh"

rm -f "$here"/*.wasm
if ((failures > 0)); then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
