#!/usr/bin/env bash
# Recompile battlefront.lisp to a browser-loadable WebAssembly reactor.
# --no-wasi drops all WASI imports, so the module's only imports are the host
# functions battlefront.lisp declares with rontolisp:wasm-import ("gl",
# "canvas" and "math") plus the shared WebGL2 package; --optimize tree-shakes
# the runtime so only the reachable functions ship.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

# Prefer a rontolisp binary on PATH (a native image or the launcher); fall back
# to the built exec JAR at the repo root.
if command -v rontolisp >/dev/null 2>&1; then
  compile() { rontolisp "$@"; }
else
  repo_root="$(cd "$here/../../.." && pwd)"
  jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
  if [[ ! -f "$jar" ]]; then
    echo "No 'rontolisp' on PATH and JAR not found: $jar" >&2
    echo "Install the binary, or build the JAR from the repo root: ./mvnw clean package" >&2
    exit 1
  fi
  compile() { java -jar "$jar" "$@"; }
fi

echo "compiling battlefront.lisp -> battlefront.wasm"
compile "$here/battlefront.lisp" -o "$here/battlefront.wasm" --no-wasi --optimize

# The page imports the generated ../webgl-common/gl-imports.js, so the served
# root is examples/browser rather than this directory.
echo "done. Serve the examples/browser directory over http, e.g.:"
echo "  jwebserver -p 8000 --directory \"$(dirname "$here")\""
echo "then open http://localhost:8000/webgl-battlefront/"
