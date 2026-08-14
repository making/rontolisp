#!/usr/bin/env bash
# Compile worker.lisp to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported entry point directly, so the module
#   needs no WASI imports -- it becomes a reactor (`_initialize`, not `_start`).
# --host-fetch: rontolisp:fetch is lowered onto the host's own fetch, imported
#   as env.fetch(request-json) -> response-json.
# --host-boundary=envelope: every body rides the envelope's own "body" key --
#   the request's, the response's, and the reply of that fetch. So there is no
#   env.readRequestBody, no env.writeResponseBody and no env.readResponseBody,
#   and no host-side cursor behind any of them: this module imports exactly ONE
#   function. It pays a copy per body and cannot carry binary, which is nothing
#   for a few hundred bytes of JSON. ../dog-fetcher is the same program shape on
#   the other boundary; the two are meant to be read together.
# --emit-js-glue: write src/worker.js beside the module. On this boundary both
#   remaining halves are fixed by the transport rather than chosen by the
#   program, so the glue writes BOTH -- the env.fetch host half and a
#   worker(module) that maps a Request onto the envelope and a Response off it.
#   src/index.js is then three lines. It is CHECKED IN and pinned by
#   HostGlueEmitterTest, so regenerate it here rather than editing it.
# --optimize=size: a Worker bundle has a size limit.
#
# The first run downloads clack into ~/.rontolisp/quicklisp; after that the
# build is offline.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling worker.lisp -> src/worker.wasm + src/worker.js"
java -jar "$jar" "$here/worker.lisp" -o "$here/src/worker.wasm" \
  --no-wasi --host-fetch --host-boundary=envelope --optimize=size --emit-js-glue

ls -l "$here/src/worker.wasm" "$here/src/worker.js"
echo "done. Run it with:  npx wrangler dev"
