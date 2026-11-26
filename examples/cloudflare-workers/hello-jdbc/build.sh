#!/usr/bin/env bash
# Compile the Worker's module: Visits.java, a JDBC program, with the driver of
# jdbc-postgresql/ read as source.
#
# --no-wasi: the Worker calls the exported entry point directly, so the module
#   needs no WASI imports. Its `main` runs once, when the instance starts, and
#   each request is one call of the `handle-request` export.
# --host-sockets: java.net.Socket, which the driver connects with, is the host's:
#   the module imports the socket functions (tcp.*), and the glue answers them
#   over connect() of cloudflare:sockets. Outbound connections only, and a socket
#   ends with the request that opened it.
# --host-random: SecureRandom, which a SCRAM-SHA-256 login draws its nonce from,
#   is the host's entropy.
# --host-output: what the program prints is the Worker's log.
# --optimize=size: a Worker bundle has a size limit.
# --emit-js-glue: src/worker.js, the host half, written beside the module.
#   src/index.js is this directory's own: it hands the program the address of its
#   database.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling Visits.java -> src/worker.wasm + src/worker.js"
java -jar "$jar" "$here/Visits.java" \
  --java-source-path "$repo_root/jdbc-postgresql/src/main/java:$repo_root/jdbc-postgresql/src/main/resources" \
  -o "$here/src/worker.wasm" --no-wasi --host-sockets --host-random --host-output --optimize=size --emit-js-glue

ls -l "$here/src/worker.wasm" "$here/src/worker.js"
echo "done. Run it with:  npx wrangler dev"
