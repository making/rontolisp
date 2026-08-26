#!/usr/bin/env bash
# Compile the Worker's module. There is no worker.lisp in this directory and
# that is the point: the program is examples/net/hello-clack.lisp ITSELF -- the
# same file that serves on the interpreter, on the JVM, in a Servlet container
# and under `wasmtime serve` -- compiled for a host that CALLS an export instead
# of handing over a socket. :server :rontolisp picks that transport at compile
# time (--no-wasi reads the handler backend in reactor shape), and the compiler
# synthesizes the `handle-request` export src/index.js calls.
#
# --no-wasi: the Worker calls the exported entry point directly, it never runs
#   the module as a program, and the application does no I/O -- so the module
#   needs no WASI imports at all. It becomes a reactor: nothing to shim on the
#   JavaScript side, and `_initialize` instead of `_start`. The program's
#   (uiop:getenvp "PORT") answers nil here, which is why the :port default is
#   the one the Worker never looks at.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is what
#   keeps the module down -- only what the program reaches ships. The =size
#   level additionally declines the two speed-over-size wasm-GC emissions, the
#   right trade on a Worker.
# --emit-js-glue: write src/worker.js beside the module -- the host half of this
#   boundary, from the program's own declarations. This module imports NOTHING
#   (the default `envelope` boundary keeps every body inside the head), so the
#   glue writes all of that half, the Request -> envelope -> Response mapping
#   included, and src/index.js is three lines. It is CHECKED IN and pinned by
#   HostGlueEmitterTest, so regenerate it here rather than editing it.
#
# The first run downloads clack/lack into ~/.rontolisp/quicklisp; after that the
# build is offline.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling ../../net/hello-clack.lisp -> src/worker.wasm + src/worker.js"
java -jar "$jar" "$repo_root/examples/net/hello-clack.lisp" \
  -o "$here/src/worker.wasm" --no-wasi --optimize=size --emit-js-glue

ls -l "$here/src/worker.wasm" "$here/src/worker.js"
echo "done. Run it with:  npx wrangler dev"
