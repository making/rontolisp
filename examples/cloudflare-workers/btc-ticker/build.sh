#!/usr/bin/env bash
# Compile worker.lisp to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported entry point directly, so the module
#   needs no WASI imports -- it becomes a reactor (`_initialize`, not `_start`).
# --host-fetch: rontolisp:fetch is lowered onto the host's own fetch, imported
#   as env.fetch(request-json) -> response-json.
# No --host-boundary: the DEFAULT is `envelope`, and that is what this Worker
#   wants. Every body rides the envelope's own "body" key -- the request's, the
#   response's, and the reply of that fetch -- so there is no env.readRequestBody,
#   no env.writeResponseBody and no env.readResponseBody, and no host-side cursor
#   behind any of them: this module imports exactly ONE function. It pays a copy
#   per body and cannot carry binary, which is nothing for a few hundred bytes of
#   JSON. ../dog-fetcher asks for --host-boundary=streaming because its bodies are
#   not documents; the two are meant to be read together.
# The glue: src/worker.js, written beside the module. On this boundary both
#   remaining halves are fixed by the transport rather than chosen by the
#   program, so the glue writes BOTH -- the env.fetch host half and a
#   worker(module) that maps a Request onto the envelope and a Response off it.
#   src/worker.js is CHECKED IN and pinned by HostGlueEmitterTest, so regenerate
#   it here rather than editing it.
# --optimize=size: a Worker bundle has a size limit.
# --emit-worker-entry: write src/worker.js as --emit-js-glue does (the flag
#   implies it), and src/index.js beside it -- the Worker's three lines,
#   importing the module and the glue and exporting worker(module). src/index.js
#   is written ONCE: a later build leaves one that is there alone, so it is
#   yours to edit (a remoteAddr, a host of your own).
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

echo "compiling worker.lisp -> src/worker.wasm + src/worker.js + src/index.js"
java -jar "$jar" "$here/worker.lisp" -o "$here/src/worker.wasm" \
  --no-wasi --host-fetch --optimize=size --emit-worker-entry

ls -l "$here/src/worker.wasm" "$here/src/worker.js" "$here/src/index.js"
echo "done. Run it with:  npx wrangler dev"
