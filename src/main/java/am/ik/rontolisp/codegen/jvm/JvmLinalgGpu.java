package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispNames;

/**
 * What {@code --gpu} accelerates on the JVM backend: the {@code linalg:} matrix product's
 * MATRIX-BY-MATRIX case, and nothing else. The emission belongs to
 * {@link JvmLinalgKernelCompiler}, which chains this attempt ahead of the {@code --blas}
 * and {@code --simd} ones over shared temps; this class only says which member the
 * {@link JvmGpuTemplate device bridge} claims, so the compiler can decide whether to
 * embed the bridge at all.
 *
 * <p>
 * The set is the interpreter's ({@code eval/LinalgGpu}) exactly, and it is NARROWER than
 * {@link JvmLinalgBlas}'s: the two gemv shapes are memory-bound, so their whole cost is
 * one pass over an operand a device would have to be handed anyway, and a round trip
 * cannot win that race. {@code linalg:matmul} at rank 2 and {@code linalg:solve} are
 * accelerated transitively, through the same spliced call site.
 */
final class JvmLinalgGpu {

	private JvmLinalgGpu() {
	}

	/** Whether the device bridge accelerates the given {@code linalg:} member. */
	static boolean handles(String member) {
		return LispNames.LINALG_DOT.equals(member);
	}

}
