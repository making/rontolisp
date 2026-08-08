#!/usr/bin/env bash
# Compile app.lisp to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported `handle-request` directly, it never
#   runs the module as a program, and the handler does no I/O -- so the module
#   needs no WASI imports at all. It becomes a reactor: nothing to shim on the
#   JavaScript side, and `_initialize` instead of `_start`.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is
#   what keeps the module small -- only the functions the program actually
#   reaches end up in the output. =size additionally declines the two
#   speed-over-size emissions: -11% raw / -14% gzip for a per-request cost of
#   a few microseconds, the right trade on a Worker.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling app.lisp -> src/app.wasm"
java -jar "$jar" "$here/app.lisp" -o "$here/src/app.wasm" --no-wasi --optimize=size

ls -l "$here/src/app.wasm"
echo "done. Run it with:  npx wrangler dev"
