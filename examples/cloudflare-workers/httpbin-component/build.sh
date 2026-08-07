#!/usr/bin/env bash
# Build ../httpbin/app.lisp -- the SAME Lisp source -- as a WebAssembly
# component, and transpile it for Cloudflare Workers with jco.
#
# Workers has no native component-model support, so `jco transpile` lowers the
# component to a core module plus JavaScript glue. Three flags make that glue
# runnable inside a Worker:
#
#   --instantiation sync       the glue does not compile wasm itself; it asks the
#                              host for each already-compiled core module. Workers
#                              forbids runtime WebAssembly compilation, so this is
#                              the only mode that works -- the default glue calls
#                              WebAssembly.compile() and hangs at startup.
#   -b 0                       never inline a core module as base64; emit it as a
#                              .wasm file the Worker can `import`.
#   --bindgen-enable-wasm-exnref   accept the exception-handling instructions
#                              `handler-case` compiles into.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling ../httpbin/app.lisp -> app.wasm (component)"
java -jar "$jar" "$here/../httpbin/app.lisp" -o "$here/app.wasm" --component --optimize

echo "transpiling app.wasm -> src/dist/"
rm -rf "$here/src/dist"
npx -y @bytecodealliance/jco transpile "$here/app.wasm" -o "$here/src/dist" \
  --instantiation sync -b 0 --bindgen-enable-wasm-exnref

echo
echo "core modules src/index.js must import:"
ls -1 "$here/src/dist"/*.wasm | xargs -n1 basename
echo "done. Run it with:  npx wrangler dev"
