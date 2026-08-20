package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;

/**
 * What {@code --blas} accelerates on the JVM backend: the {@code linalg:} matrix product,
 * and nothing else. The emission itself belongs to {@link JvmLinalgKernelCompiler}, which
 * chains this attempt ahead of the {@code --simd} one over shared temps; this class only
 * says which member the {@link JvmBlasTemplate CBLAS bridge} claims, so the compiler can
 * decide whether to emit the bridge at all.
 *
 * <p>
 * One member is the whole of it by measurement, not by staging: a tuned {@code gemm} is
 * 35-121x the lane kernel at {@code linalg}'s default width, while the memory-bound
 * members would gain nothing from a library call ({@code .kb/linalg-blas.md}).
 * {@code linalg:matmul} at rank {@code <= 2} and {@code linalg:solve} are accelerated
 * transitively -- their spliced bodies call {@code linalg:dot}, whose call site this is.
 */
final class JvmLinalgBlas {

	private JvmLinalgBlas() {
	}

	/** The one accelerated member's qualified spelling (the emit gate's scan key). */
	static final String QUALIFIED_DOT = PackageRegistry.qualify(LispNames.LINALG_PKG, LispNames.LINALG_DOT);

	/** Whether the CBLAS bridge accelerates the given {@code linalg:} member. */
	static boolean handles(String member) {
		return LispNames.LINALG_DOT.equals(member);
	}

}
