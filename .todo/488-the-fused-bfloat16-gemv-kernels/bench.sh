#!/usr/bin/env bash
# The fused bfloat16 GEMV kernels under BOTH JITs.
#
# Graal is what this box, CI and the native image run; C2 (-XX:-UseJVMCICompiler) is what
# a stock OpenJDK runs a compiled .class under. .todo/482 round 2 measured the same fused
# kernel at 1.51x of f32 under Graal and 0.20x under C2 -- the method had overrun C2's
# inlining budget for the Vector API chain and every vector was boxed, silently. So a
# number is only a number when it carries the JIT that produced it.
#
#   ./mvnw -o test-compile          # first, from the repo root
#   .todo/488-the-fused-bfloat16-gemv-kernels/bench.sh [eval|template|both]
#
# Detach it (this box's rule): setsid + a sentinel, then wait in the foreground.
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
	run "VecSimdKernels / Graal" am.ik.rontolisp.eval.Bf16GemvBench
	run "VecSimdKernels / C2" am.ik.rontolisp.eval.Bf16GemvBench -XX:-UseJVMCICompiler
fi
if [ "$which" = template ] || [ "$which" = both ]; then
	run "JvmSimdVectorTemplate / Graal" am.ik.rontolisp.codegen.jvm.Bf16TemplateGemvBench
	run "JvmSimdVectorTemplate / C2" am.ik.rontolisp.codegen.jvm.Bf16TemplateGemvBench -XX:-UseJVMCICompiler
fi
