#!/usr/bin/env bash
# Compile the Worker's module. There is no Java in this directory: the program is
# examples/java/hello-session's HelloSession.java ITSELF -- a servlet counting the
# visits of a session, which a JDK runs as it stands -- compiled for a host that
# CALLS an export instead of handing over a socket.
#
# The session is not in the instance's memory: servlet-httpserver-keyvalue on the
# source path is the store the server finds, over wasi:keyvalue/store@0.2.0-draft2,
# and the generated glue answers that interface with the Worker's KV namespace
# binding KEYVALUE (wrangler.jsonc). So a visit counts on in the next isolate, and
# in the next deployment.
#
# --no-wasi: the Worker calls the exported entry point directly. An interface the
#   flat set of a core import does not carry -- the store's results, options and
#   bucket handles -- is imported through the canonical ABI, as a component's core
#   module imports it, and the glue lifts and lowers it.
# --host-random: a session's id is 16 octets of SecureRandom, drawn from the host's
#   entropy (crypto.getRandomValues).
# --host-output: what the program prints is the Worker's log.
# --optimize=size: a Worker bundle has a size limit.
# --emit-worker-entry: src/worker.js, the glue (CHECKED IN and pinned by
#   HostGlueEmitterTest, so regenerate it here rather than editing it), and
#   src/index.js beside it, written once and yours to edit.
# --java-source-path / --java-source-dep: the libraries, read as source -- the
#   container, the store and @WitImport from this checkout, the Servlet API's
#   sources jar from the local Maven repository or Maven Central.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

sources="$repo_root/servlet-httpserver/src/main/java"
sources="$sources:$repo_root/servlet-httpserver-keyvalue/src/main/java"
sources="$sources:$repo_root/servlet-httpserver-keyvalue/src/main/resources"
sources="$sources:$repo_root/rontolisp-java-api/src/main/java"

echo "compiling ../../java/hello-session/src/main/java/HelloSession.java -> src/worker.wasm + src/worker.js + src/index.js"
java -jar "$jar" "$repo_root/examples/java/hello-session/src/main/java/HelloSession.java" \
  --java-source-path "$sources" \
  --java-source-dep jakarta.servlet:jakarta.servlet-api:6.1.0 \
  -o "$here/src/worker.wasm" --no-wasi --host-random --host-output --optimize=size --emit-worker-entry

ls -l "$here/src/worker.wasm" "$here/src/worker.js" "$here/src/index.js"
echo "done. Run it with:  npx wrangler dev"
