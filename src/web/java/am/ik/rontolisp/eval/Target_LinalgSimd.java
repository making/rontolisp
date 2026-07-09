package am.ik.rontolisp.eval;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link LinalgSimd}, the exact counterpart of
 * {@link Target_VecSimd}. The browser playground compiles the interpreter to WebAssembly
 * with GraalVM Web Image, which cannot compile {@code jdk.incubator.vector}.
 * {@link LinalgSimd#available()} and {@link LinalgSimd#install(Environment,
 * LispEvaluator)} are the only two ENTRY POINTS into {@code LinalgSimdKernels} (the holder
 * of the Vector API, and the only caller of {@code VecSimdKernels} outside
 * {@code VecSimd}), so substituting both makes that class unreachable and keeps the
 * incubator module out of the image. Adding a new public method to {@code LinalgSimd} that
 * touches the kernels would break that, and only the Pages workflow's Web Image build
 * would catch it. The playground never sets the {@code --simd} flag anyway, so reporting
 * "unavailable" is also the truthful answer: the scalar {@code linalg.lisp} defuns run
 * instead.
 *
 * <p>
 * Compiled only under the {@code web} Maven profile (it lives in {@code src/web/java});
 * the JVM and regular native-image builds use the real {@link LinalgSimd}.
 */
@TargetClass(LinalgSimd.class)
final class Target_LinalgSimd {

	@Substitute
	static boolean available() {
		return false;
	}

	@Substitute
	static void install(Environment globalEnv, LispEvaluator evaluator) {
		throw new IllegalStateException("--simd is not available in the browser playground");
	}

}
