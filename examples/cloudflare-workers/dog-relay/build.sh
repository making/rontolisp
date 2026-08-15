#!/usr/bin/env bash
# Compile worker.lisp to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported entry point directly, so the module
#   needs no WASI imports -- it becomes a reactor (`_initialize`, not `_start`).
# --host-fetch: rontolisp:fetch is lowered onto the host's own fetch, imported
#   as env.fetch(request-json) -> response-head-json.
# --host-boundary=streaming: the bodies leave the JSON envelope and cross as
#   octets through imports of their own -- env.readRequestBody,
#   env.writeResponseBody, and env.readResponseBody for the reply of that fetch.
#   ASKED FOR, because the default is `envelope`: this Worker relays an upstream
#   reply and forwards it a chunk at a time rather than holding it whole.
# --reentrant: let the Worker runtime OVERLAP calls into the one instance. A
#   relay is one upstream round trip of parked time per request; serialised,
#   N concurrent clients would each wait for the N-1 relays ahead of them. The
#   module then owns its per-call state, and every body import above carries a
#   leading call id (the envelope's "call-id", the fetch reply's "body-id"), so
#   each pull and push names the relay it belongs to. ../dog-fetcher is the same
#   boundary WITHOUT the flag: its calls go through the generated queue.
# --emit-js-glue: write src/worker.js beside the module -- the import object,
#   the linear-memory plumbing, the Suspending/promising wiring, the per-call
#   body state keyed by id, all derived from the same declarations the module was
#   built from. src/index.js is then three lines. It is CHECKED IN and pinned by
#   HostGlueEmitterTest, so regenerate it here rather than editing it.
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

echo "compiling worker.lisp -> src/worker.wasm + src/worker.js"
java -jar "$jar" "$here/worker.lisp" -o "$here/src/worker.wasm" \
  --no-wasi --host-fetch --host-boundary=streaming --reentrant --optimize=size --emit-js-glue

ls -l "$here/src/worker.wasm" "$here/src/worker.js"
echo "done. Run it with:  npx wrangler dev"
