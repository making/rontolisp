#!/usr/bin/env bash
# Compile worker.lisp -- clack, the handler backend, the application and the
# clackup call -- to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported entry point directly and never runs
#   the module as a program, so it needs no WASI imports at all. It becomes a
#   reactor: nothing to shim on the JavaScript side, and `_initialize` instead
#   of `_start`. clackup's start-up banner is not a problem there -- standard
#   output is a sink, so the bytes are discarded.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is what
#   keeps the module down -- only what the program reaches ships. The =size
#   level additionally declines the two speed-over-size wasm-GC emissions, the
#   right trade on a Worker: smaller and a slightly faster isolate startup, for
#   a per-request cost of single-digit microseconds.
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

echo "compiling worker.lisp -> src/worker.wasm"
java -jar "$jar" "$here/worker.lisp" -o "$here/src/worker.wasm" --no-wasi --optimize=size

ls -l "$here/src/worker.wasm"
echo "done. Run it with:  npx wrangler dev"
