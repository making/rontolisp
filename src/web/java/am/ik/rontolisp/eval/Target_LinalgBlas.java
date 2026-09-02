package am.ik.rontolisp.eval;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link LinalgBlas}, the exact counterpart of
 * {@link Target_LinalgSimd}. The browser playground compiles the interpreter to
 * WebAssembly with GraalVM Web Image, which has no foreign function API and no operating
 * system to find a CBLAS in. {@link LinalgBlas#available()},
 * {@link LinalgBlas#description()}, {@link LinalgBlas#install(Environment,
 * LispEvaluator)} and {@link LinalgBlas#installVec(Environment, LispEvaluator)} are the
 * only four ENTRY POINTS into {@code LinalgBlasKernels} (the holder of every
 * {@code java.lang.foreign} reference), so substituting all four makes
 * that class unreachable and keeps the API out of the image. Adding a new public method to
 * {@code LinalgBlas} that touches the kernels would break that, and only the Pages
 * workflow's Web Image build would catch it. The playground never sets {@code --blas}
 * anyway, so reporting "unavailable" is also the truthful answer.
 *
 * <p>
 * Compiled only under the {@code web} Maven profile (it lives in {@code src/web/java});
 * the JVM and regular native-image builds use the real {@link LinalgBlas}.
 */
@TargetClass(LinalgBlas.class)
final class Target_LinalgBlas {

	@Substitute
	static boolean available() {
		return false;
	}

	@Substitute
	static String description() {
		return "no foreign function API in the browser playground";
	}

	@Substitute
	static void install(Environment globalEnv, LispEvaluator evaluator) {
		throw new IllegalStateException("--blas is not available in the browser playground");
	}

	@Substitute
	static void installVec(Environment globalEnv, LispEvaluator evaluator) {
		throw new IllegalStateException("--blas is not available in the browser playground");
	}

}
