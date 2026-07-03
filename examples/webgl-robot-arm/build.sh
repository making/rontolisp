#!/usr/bin/env bash
# Recompile robot-arm.lisp to a browser-loadable WebAssembly reactor.
# --no-wasi drops all WASI imports, so the module's only imports are the host
# functions robot-arm.lisp declares with rontolisp:wasm-import ("gl", "canvas",
# "math" and "ui"); --optimize tree-shakes the runtime so only the reachable
# functions ship.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling robot-arm.lisp -> robot-arm.wasm"
java -jar "$jar" "$here/robot-arm.lisp" -o "$here/robot-arm.wasm" --no-wasi --optimize

echo "done. Serve this directory over http, e.g.:"
echo "  jwebserver -p 8000 --directory \"$here\""
echo "then open http://localhost:8000/"
