#!/usr/bin/env bash
# Compile fractal.lisp to a WebAssembly COMPONENT and transpile it to a browser
# ES module.
#
#   1. rontolisp: --no-gc --component --optimize --emit-wit
#        --no-gc      a plain MVP core module (no wasm-GC, no WASI, no flags),
#                     wrapped in a component
#        --component  the canonical ABI carries the strings across the boundary,
#                     so no page code ever touches memory or a pointer
#        --emit-wit   write the component's OWN world next to the .wasm; it is a
#                     regeneration of wit/fractal.wit (which the program declares
#                     it implements) with the component's own package/world name
#   2. jco transpile: read the types out of the .wasm and generate the JS
#      bindings the page imports. --base64-cutoff inlines the core module into
#      the .js, so dist/fractal.js is one self-contained file with no imports.
#
# Needs Node (for npx). Everything else is downloaded on demand by npx.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling fractal.lisp -> fractal.wasm (component) + fractal.wit"
java -jar "$jar" "$here/fractal.lisp" -o "$here/fractal.wasm" \
  --no-gc --component --optimize --emit-wit

echo "transpiling fractal.wasm -> dist/fractal.js"
(cd "$here" && npx -y @bytecodealliance/jco transpile fractal.wasm \
  -o dist --base64-cutoff 1000000)

echo "done. Serve this directory over http, e.g.:"
echo "  python3 -m http.server 8000 --directory \"$here\""
echo "then open http://localhost:8000/"
