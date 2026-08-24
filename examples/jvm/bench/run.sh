#!/bin/bash
# Build the kernel library and run the packed float-array boundary benchmark.
#
#   ./run.sh                 # uses target/rontolisp-*-exec.jar from the repo root
#   RONTOLISP_JAR=... ./run.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
jar=${RONTOLISP_JAR:-$(ls "$root"/target/rontolisp-*-exec.jar | head -1)}
out=$here/target
rm -rf "$out"
mkdir -p "$out"
cd "$out"
java -jar "$jar" "$here/norm2-kernels.lisp" -o com/example/Norm2Kernels.class --no-main --simd
javac -cp . -d . "$here/HandleBench.java"
exec java --add-modules jdk.incubator.vector -cp . HandleBench
