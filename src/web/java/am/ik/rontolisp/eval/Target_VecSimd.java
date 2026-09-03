package am.ik.rontolisp.eval;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link VecSimd}. The browser playground compiles the
 * interpreter to WebAssembly with GraalVM Web Image, which cannot compile
 * {@code jdk.incubator.vector}. {@link VecSimd#available()} and
 * {@link VecSimd#install(Environment, LispEvaluator, boolean)} are the only two ENTRY POINTS into
 * {@code VecSimdKernels} (the sole holder of the Vector API) -- every other reference to it
 * lives in private helpers reachable only from {@code install} -- so substituting both
 * makes that class unreachable and keeps the incubator module out of the image. Adding a
 * new public method to {@code VecSimd} that touches the kernels would break that, and only
 * the Pages workflow's Web Image build would catch it. The playground never sets the
 * {@code --simd} flag anyway, so reporting "unavailable" is also the truthful answer: the
 * scalar {@code vec.lisp} kernels run instead.
 *
 * <p>
 * Compiled only under the {@code web} Maven profile (it lives in {@code src/web/java});
 * the JVM and regular native-image builds use the real {@link VecSimd}.
 */
@TargetClass(VecSimd.class)
final class Target_VecSimd {

	@Substitute
	static boolean available() {
		return false;
	}

	@Substitute
	static void install(Environment globalEnv, LispEvaluator evaluator, boolean parallel) {
		throw new IllegalStateException("--simd is not available in the browser playground");
	}

}
