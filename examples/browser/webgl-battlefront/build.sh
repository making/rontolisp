#!/usr/bin/env bash
# Recompile battlefront.lisp to a browser-loadable WebAssembly reactor.
# --no-wasi drops all WASI imports, so the module's only imports are the host
# functions battlefront.lisp declares with rontolisp:wasm-import ("gl",
# "canvas" and "math") plus the shared WebGL2 package; --optimize tree-shakes
# the runtime so only the reachable functions ship.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling battlefront.lisp -> battlefront.wasm"
java -jar "$jar" "$here/battlefront.lisp" -o "$here/battlefront.wasm" --no-wasi --optimize

# The page imports the generated ../webgl-common/gl-imports.js, so the served
# root is examples/browser rather than this directory.
echo "done. Serve the examples/browser directory over http, e.g.:"
echo "  jwebserver -p 8000 --directory \"$(dirname "$here")\""
echo "then open http://localhost:8000/webgl-battlefront/"
