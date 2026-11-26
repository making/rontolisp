#!/usr/bin/env bash
# Build ../httpbin/worker.lisp -- the SAME Lisp source -- as a WebAssembly
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

echo "compiling ../httpbin/worker.lisp -> worker.wasm (reactor component)"
# --optimize=size: same trade as the sibling builds -- smaller core modules for
# a per-request cost of a few microseconds.
# --no-wasi: worker.lisp does no I/O, so ask for the REACTOR component -- it
# imports NOTHING (no WASI stubs to hand-write on the JavaScript side) and its
# top-level forms run at instantiation, exactly like ../httpbin's _initialize.
java -jar "$jar" "$here/../httpbin/worker.lisp" -o "$here/worker.wasm" --component --no-wasi --optimize=size

echo "transpiling worker.wasm -> src/dist/"
rm -rf "$here/src/dist"
npx -y @bytecodealliance/jco transpile "$here/worker.wasm" -o "$here/src/dist" \
  --instantiation sync -b 0 --bindgen-enable-wasm-exnref

echo
echo "core modules src/index.js must import (a reactor component has ONE):"
ls -1 "$here/src/dist"/*.wasm | xargs -n1 basename
echo "done. Run it with:  npx wrangler dev"
