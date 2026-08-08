#!/usr/bin/env bash
# Compile worker.lisp -- the whole program: clack, the handler backend, the
# application and the clackup call -- to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported entry point directly and never runs
#   the module as a program, so it needs no WASI imports at all. It becomes a
#   reactor: nothing to shim on the JavaScript side, and `_initialize` instead
#   of `_start`. clackup's own start-up banner is not a problem there -- under
#   --no-wasi standard output is a sink, so the bytes are discarded.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is
#   what keeps the module down -- only the functions the program actually
#   reaches end up in the output. It matters here because quickloading clack
#   pulls in the whole of clack and lack. The =size level additionally declines
#   the two speed-over-size emissions, which is the right trade on a Worker:
#   -11% raw / -14% gzip and a slightly FASTER isolate startup, for a
#   per-request cost measured in single-digit microseconds (0.011 -> 0.015 ms
#   on this module, node 24).
#
# The first run downloads clack/lack into ~/.rontolisp/quicklisp; after that the
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

echo "compiling worker.lisp -> src/app.wasm"
java -jar "$jar" "$here/worker.lisp" -o "$here/src/app.wasm" --no-wasi --optimize=size

ls -l "$here/src/app.wasm"
echo "done. Run it with:  npx wrangler dev"
