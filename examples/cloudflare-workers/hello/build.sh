#!/usr/bin/env bash
# Compile hello.lisp to the .wasm module the Worker imports.
#
# --no-gc: this program is inside the non-GC subset (integers and a string
#   literal, no cons cells or hash tables), so it compiles to a plain MVP module
#   -- no wasm-GC, no WASI, no imports at all, and about half a kilobyte.
# --optimize: the dead-code tree-shaker; only what the exports reach ships.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling hello.lisp -> src/hello.wasm"
java -jar "$jar" "$here/hello.lisp" -o "$here/src/hello.wasm" --no-gc --optimize

ls -l "$here/src/hello.wasm"
echo "done. Run it with:  npx wrangler dev"
