#!/usr/bin/env bash
# Recompile heat3d.lisp to a browser-loadable WebAssembly reactor.
# --no-wasi drops all WASI imports, so the module's only imports are the host
# functions heat3d.lisp declares with rontolisp:wasm-import ("gl", "canvas",
# "math" and "ui"); --optimize tree-shakes the runtime so only the reachable
# functions (including the spliced linalg definitions it uses) ship.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling heat3d.lisp -> heat3d.wasm"
java -jar "$jar" "$here/heat3d.lisp" -o "$here/heat3d.wasm" --no-wasi --optimize

# The page imports the generated ../webgl-common/gl-imports.js, so the served
# root is examples/browser rather than this directory.
echo "done. Serve the examples/browser directory over http, e.g.:"
echo "  jwebserver -p 8000 --directory \"$(dirname "$here")\""
echo "then open http://localhost:8000/webgl-heat3d/"
