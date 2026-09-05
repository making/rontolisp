#!/usr/bin/env bash
# The Q8_0 integer-dot GEMV under BOTH JITs, beside the shipped f32 and bfloat16 kernels.
#
# Graal is what this box, CI and the native image run; C2 (-XX:-UseJVMCICompiler) is what
# a stock OpenJDK runs a compiled .class under. .todo/482 round 2 measured a Vector API
# kernel at 1.51x under Graal and 0.20x under C2 -- boxed, silently -- so a number is only
# a number when it carries the JIT that produced it.
#
#   ./mvnw -o test-compile          # first, from the repo root
#   .todo/672-a-q8-0-quantized-weight-matrix-and-its-integer-dot-gemv/bench.sh [eval|template|both]
#
# Detach it (this box's rule): setsid + a sentinel, then wait in the foreground. Record the
# load average before and after, the base commit and RONTOLISP_THREADS (unset = 20 here).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cp="$root/target/classes:$root/target/test-classes"
which="${1:-both}"

run() { # run <label> <class> <extra jvm args...>
	local label="$1" class="$2"
	shift 2
	echo "############ $label"
	java "$@" --add-modules jdk.incubator.vector -cp "$cp" "$class"
}

if [ "$which" = eval ] || [ "$which" = both ]; then
	run "VecSimdKernels / Graal" am.ik.rontolisp.eval.Q8GemvBench
	run "VecSimdKernels / C2" am.ik.rontolisp.eval.Q8GemvBench -XX:-UseJVMCICompiler
fi
if [ "$which" = template ] || [ "$which" = both ]; then
	run "JvmSimdVectorTemplate / Graal" am.ik.rontolisp.codegen.jvm.Q8TemplateGemvBench
	run "JvmSimdVectorTemplate / C2" am.ik.rontolisp.codegen.jvm.Q8TemplateGemvBench -XX:-UseJVMCICompiler
fi
