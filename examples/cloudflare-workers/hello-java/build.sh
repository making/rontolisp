#!/usr/bin/env bash
# Compile Worker.java to the .wasm module the Worker imports, and its glue.
#
# --no-wasi: a program with no main is a reactor -- the public static methods
#   of Worker are its exports, and it imports nothing. The module is wasm-GC
#   (a String is a garbage-collected value), which the Workers runtime runs.
# --emit-js-glue: write src/worker.js beside the module: instantiation, the
#   static initializers (_initialize), and the (ptr, len) staging a String
#   crosses as, both ways. src/index.js calls plain JavaScript functions.
# --optimize=size: the smallest module; it keeps the fused int arithmetic a
#   Java int's wraparound is made of wherever that is the smaller code.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling Worker.java -> src/worker.wasm + src/worker.js"
java -jar "$jar" "$here/Worker.java" -o "$here/src/worker.wasm" \
  --no-wasi --emit-js-glue --optimize=size

ls -l "$here/src/worker.wasm" "$here/src/worker.js"
echo "done. Run it with:  npx wrangler dev"
