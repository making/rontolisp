#!/bin/bash
# Build the kernel libraries and run the packed float-array boundary benchmarks.
#
#   ./run.sh                 # both: the boundary cost, then --gpu residency
#   ./run.sh handle          # the boundary cost only (no --gpu build)
#   ./run.sh gpu             # the --gpu residency measurement only
#   RONTOLISP_JAR=... ./run.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
jar=${RONTOLISP_JAR:-$(ls "$root"/target/rontolisp-*-exec.jar | head -1)}
which=${1:-all}
out=$here/target
rm -rf "$out"
mkdir -p "$out"
cd "$out"

if [ "$which" = all ] || [ "$which" = handle ]; then
  java -jar "$jar" "$here/norm2-kernels.lisp" -o com/example/Norm2Kernels.class --no-main --simd
  javac -cp . -d . "$here/HandleBench.java"
  java --add-modules jdk.incubator.vector -cp . HandleBench
fi

if [ "$which" = all ] || [ "$which" = gpu ]; then
  # The same source twice: the --gpu build, and the oracle it is checked against.
  java -jar "$jar" "$here/gpu-kernels.lisp" -o com/example/GpuKernels.class --no-main --gpu
  java -jar "$jar" "$here/gpu-kernels.lisp" -o com/example/CpuKernels.class --no-main
  javac -cp . -d . "$here/GpuResidencyBench.java"
  java --enable-native-access=ALL-UNNAMED -cp . GpuResidencyBench
fi
