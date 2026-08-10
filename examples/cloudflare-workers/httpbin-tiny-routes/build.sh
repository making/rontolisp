#!/usr/bin/env bash
# Compile worker.lisp -- clack, tiny-routes/lite, the routes, the middleware and
# the clackup call -- to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported `handle-request` directly, it never
#   runs the module as a program, and the handler does no I/O -- so the module
#   needs no WASI imports at all. It becomes a reactor: nothing to shim on the
#   JavaScript side, and `_initialize` instead of `_start`.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is what
#   keeps the module small. It matters most in THIS example: the routes go
#   through tiny-routes/lite, whose ppcre-free path-template matcher is what
#   keeps the whole cl-ppcre engine out (the full "tiny-routes" spells the same
#   routes and ships it -- both rows are in the size report).
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
java -jar "$jar" "$here/worker.lisp" -o "$here/src/worker.wasm" --no-wasi --optimize=size

ls -l "$here/src/worker.wasm"
echo "done. Run it with:  npx wrangler dev"
