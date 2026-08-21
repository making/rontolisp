package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;

/**
 * What {@code --gpu} accelerates on the JVM backend: the {@code linalg:} matrix product
 * -- {@code linalg:dot}'s MATRIX-BY-MATRIX case and the STACKED rank-&gt;=3 product
 * behind {@code linalg:matmul} -- and nothing else. The emission belongs to
 * {@link JvmLinalgKernelCompiler}, which chains this attempt ahead of the {@code --blas}
 * and {@code --simd} ones over shared temps; this class only says which member the
 * {@link JvmGpuTemplate device bridge} claims, so the compiler can decide whether to
 * embed the bridge at all.
 *
 * <p>
 * The set is the interpreter's ({@code eval/LinalgGpu}) exactly. It is NARROWER than
 * {@link JvmLinalgBlas}'s in one direction -- the two gemv shapes are memory-bound, so
 * their whole cost is one pass over an operand a device would have to be handed anyway,
 * and a round trip cannot win that race -- and WIDER in another: {@code --blas} stops at
 * {@code dot}, while a batch axis is free on a device ({@code blockIdx.z}), so the
 * stacked product is here. {@code linalg:matmul} at every rank and {@code linalg:solve}
 * are accelerated transitively, through the same spliced call sites.
 */
final class JvmLinalgGpu {

	private JvmLinalgGpu() {
	}

	/** The rank-2 member's qualified spelling (half of the emit gate's scan key). */
	static final String QUALIFIED_DOT = PackageRegistry.qualify(LispNames.LINALG_PKG, LispNames.LINALG_DOT);

	/**
	 * The stacked member's, which carries the DOUBLE colon: a {@code %}-prefixed member
	 * is an internal symbol ({@code .kb/linalg-simd.md}).
	 */
	static final String QUALIFIED_MATMUL_ND = PackageRegistry.qualifyInternal(LispNames.LINALG_PKG,
			LispNames.LINALG_MATMUL_ND);

	/** Whether the device bridge accelerates the given {@code linalg:} member. */
	static boolean handles(String member) {
		return LispNames.LINALG_DOT.equals(member) || LispNames.LINALG_MATMUL_ND.equals(member);
	}

	/** The {@code ops} key of the bridge kernel backing the given member. */
	static String kernelKey(String member) {
		return LispNames.LINALG_MATMUL_ND.equals(member) ? JvmGpuRuntimeBuilder.MATMUL_ND : JvmGpuRuntimeBuilder.DOT;
	}

}
