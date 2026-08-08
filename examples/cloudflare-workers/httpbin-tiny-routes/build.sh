#!/usr/bin/env bash
# Compile worker.lisp -- clack, tiny-routes/lite, the routes and the clackup
# call -- to the .wasm module the Worker imports.
#
# --no-wasi: the Worker calls the exported `handle-request` directly, it never
#   runs the module as a program, and the handler does no I/O -- so the module
#   needs no WASI imports at all. It becomes a reactor: nothing to shim on the
#   JavaScript side, and `_initialize` instead of `_start`.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is
#   what keeps the module small. It matters most in THIS example: the routes
#   go through tiny-routes/lite, whose ppcre-free path-template matcher is
#   what keeps the module ~0.45 MB -- the full "tiny-routes" spells the same
#   routes but ships the whole cl-ppcre engine, 1,118,916 B raw on this
#   same file (see the README's table).
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

echo "compiling worker.lisp -> src/app.wasm"
java -jar "$jar" "$here/worker.lisp" -o "$here/src/app.wasm" --no-wasi --optimize=size

ls -l "$here/src/app.wasm"
echo "done. Run it with:  npx wrangler dev"
