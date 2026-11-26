package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * What {@code --blas} accelerates on the JVM backend: the matrix product, in both of the
 * packages that have one -- {@code linalg:dot} and the {@code vec:} GEMV pair. The
 * emission belongs to {@link JvmLinalgKernelCompiler} for the first and
 * {@link JvmSimdCompiler} for the other two, each chaining this attempt ahead of the
 * {@code --simd} one over shared temps; this class only says which members the
 * {@link JvmBlasTemplate CBLAS bridge} claims, so the compiler can decide whether to emit
 * the bridge at all.
 *
 * <p>
 * The set is a product set by measurement, not by staging: a tuned {@code gemm} is
 * 35-121x the lane kernel at {@code linalg}'s default width and a tuned {@code gemv}
 * 1.2-9x it depending on the machine, while the memory-bound members would gain nothing
 * from a library call ({@code .kb/linalg-blas.md}). {@code linalg:matmul} at rank
 * {@code <= 2}, {@code linalg:solve} and {@code vec:mean} / {@code vec:norm} are
 * accelerated transitively -- their spliced bodies call an intercepted member.
 */
final class JvmLinalgBlas {

	private JvmLinalgBlas() {
	}

	/** The accelerated {@code linalg:} member's qualified spelling (an emit-gate key). */
	static final String QUALIFIED_DOT = PackageRegistry.qualify(LispNames.LINALG_PKG, LispNames.LINALG_DOT);

	/** {@code vec:matvec}, the {@code vec:} GEMV (an emit-gate key). */
	static final String QUALIFIED_MATVEC = PackageRegistry.qualify(LispNames.VEC_PKG, LispNames.VEC_MATVEC);

	/** {@code vec:matvec-into}, the destination-passing GEMV (an emit-gate key). */
	static final String QUALIFIED_MATVEC_INTO = PackageRegistry.qualify(LispNames.VEC_PKG, LispNames.VEC_MATVEC_INTO);

	/**
	 * Every member the CBLAS bridge claims, in the order the emit gate scans for them.
	 */
	static List<String> qualifiedMembers() {
		return List.of(QUALIFIED_DOT, QUALIFIED_MATVEC, QUALIFIED_MATVEC_INTO);
	}

	/** Whether the CBLAS bridge accelerates the given {@code linalg:} member. */
	static boolean handles(String member) {
		return LispNames.LINALG_DOT.equals(member);
	}

	/**
	 * Whether the CBLAS bridge accelerates the given {@code vec:} member, and with which
	 * {@link JvmBlasRuntimeBuilder} {@code ops} key -- {@code null} for every other
	 * member. The two GEMV forms are the only ones: the element-wise and reduction
	 * members of {@code vec:} are memory-bound, and a library call cannot beat a lane
	 * loop over the same bytes ({@code .kb/linalg-blas.md}).
	 */
	static @Nullable String vecKernelKey(String member) {
		if (LispNames.VEC_MATVEC.equals(member)) {
			return JvmBlasRuntimeBuilder.MATVEC;
		}
		return LispNames.VEC_MATVEC_INTO.equals(member) ? JvmBlasRuntimeBuilder.MATVEC_INTO : null;
	}

}
