#!/usr/bin/env bash
# Compile worker.lisp -- clack, the five echo endpoints, the middleware and the
# clackup call -- to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported `handle-request` directly, it never
#   runs the module as a program, and the handler does no I/O -- so the module
#   needs no WASI imports at all. It becomes a reactor: nothing to shim on the
#   JavaScript side, and `_initialize` instead of `_start`.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is what
#   keeps the module small. It matters more here than in ../httpbin, because the
#   program quickloads the whole of clack.
#
# The first run downloads clack/lack into ~/.rontolisp/quicklisp; after that the
# build is offline.
# --host-boundary=streaming: this Worker ECHOES request bodies, so they must
#   cross as octets rather than as JSON text in the envelope -- which is what
#   src/index.js feeds through env.readRequestBody / env.writeResponseBody, and
#   what lets a BINARY body come back exactly. Asked for, because the default is
#   `envelope` (see ../btc-ticker), where a body rides the head instead.
#
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
java -jar "$jar" "$here/worker.lisp" -o "$here/src/worker.wasm" --no-wasi --host-boundary=streaming --optimize=size

ls -l "$here/src/worker.wasm"
echo "done. Run it with:  npx wrangler dev"
