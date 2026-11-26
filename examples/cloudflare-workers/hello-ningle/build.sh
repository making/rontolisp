#!/usr/bin/env bash
# Compile worker.lisp -- clack, ningle, the routes and the clackup call -- to
# the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported entry point directly and never runs
#   the module as a program, so it needs no WASI imports at all. It becomes a
#   reactor: nothing to shim on the JavaScript side, and `_initialize` instead
#   of `_start`.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is what
#   keeps the module down. ningle has no size opt-in to offer the way
#   tiny-routes does: myway compiles every rule to a cl-ppcre scanner, so the
#   regex engine is genuinely reachable and the shaker is right to keep it.
# The glue: src/worker.js, written beside the module -- the host half of this
#   boundary, from the program's own declarations. This module imports NOTHING
#   (the default `envelope` boundary keeps every body inside the head), so the
#   whole of that half is fixed by the transport and the glue writes all of it,
#   the Request -> envelope -> Response mapping included. src/worker.js is
#   CHECKED IN and pinned by HostGlueEmitterTest, so regenerate it here rather
#   than editing it.
# --emit-worker-entry: write src/worker.js as --emit-js-glue does (the flag
#   implies it), and src/index.js beside it -- the Worker's three lines,
#   importing the module and the glue and exporting worker(module). src/index.js
#   is written ONCE: a later build leaves one that is there alone, so it is
#   yours to edit (a remoteAddr, a host of your own).
#
# The first run downloads clack/lack/ningle into ~/.rontolisp/quicklisp; after
# that the build is offline.
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
  --no-wasi --optimize=size --emit-worker-entry

ls -l "$here/src/worker.wasm" "$here/src/worker.js" "$here/src/index.js"
echo "done. Run it with:  npx wrangler dev"
