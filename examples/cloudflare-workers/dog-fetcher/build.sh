#!/usr/bin/env bash
# Compile worker.lisp to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported entry point directly, so the module
#   needs no WASI imports -- it becomes a reactor (`_initialize`, not `_start`).
# --host-fetch: rontolisp:fetch is lowered onto the host's own fetch. That
#   costs two imports -- env.fetch(request-json) -> response-head-json and
#   env.readResponseBody(ptr, cap) -> i32, which carries the reply's body a
#   chunk at a time -- both provided by src/index.js behind
#   WebAssembly.Suspending (JSPI).
# --optimize=size: a Worker bundle has a size limit; tiny-routes/lite is what
#   keeps cl-ppcre out of what the tree-shaker has to keep.
#
# The first run downloads clack/lack/tiny-routes into ~/.rontolisp/quicklisp;
# after that the build is offline.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling worker.lisp -> src/worker.wasm"
java -jar "$jar" "$here/worker.lisp" -o "$here/src/worker.wasm" --no-wasi --host-fetch --optimize=size

ls -l "$here/src/worker.wasm"
echo "done. Run it with:  npx wrangler dev"
