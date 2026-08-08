#!/usr/bin/env bash
# Compile the Worker's module. There is no worker.lisp in this directory and
# that is the point: the program is examples/net/httpbin-clack.lisp ITSELF --
# the same file that serves on the interpreter, on the JVM and under
# `wasmtime serve` -- compiled for a host that calls an export instead of
# handing over a socket. :server :rontolisp picks this transport at compile
# time (--no-wasi reads the handler backend in reactor shape), and the
# compiler synthesizes the `handle-request` export src/index.js calls.
#
# --no-wasi: the Worker calls the exported `handle-request` directly, it never
#   runs the module as a program, and the handler does no I/O -- so the module
#   needs no WASI imports at all. It becomes a reactor: nothing to shim on the
#   JavaScript side, and `_initialize` instead of `_start`.
# --optimize=size: a Worker bundle has a size limit, and the tree-shaker is
#   what keeps the module small -- only the functions the program actually
#   reaches end up in the output. It matters more here than in ../httpbin,
#   because the program quickloads the whole of clack. =size additionally
#   declines the two speed-over-size emissions: -11% raw / -14% gzip for a
#   per-request cost of a few microseconds, the right trade on a Worker.
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

echo "compiling ../../net/httpbin-clack.lisp -> src/worker.wasm"
java -jar "$jar" "$repo_root/examples/net/httpbin-clack.lisp" \
  -o "$here/src/worker.wasm" --no-wasi --optimize=size

ls -l "$here/src/worker.wasm"
echo "done. Run it with:  npx wrangler dev"
