package am.ik.rontolisp.eval;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link LinalgGpu}, the exact counterpart of
 * {@link Target_LinalgBlas}. The browser playground compiles the interpreter to
 * WebAssembly with GraalVM Web Image, which has no foreign function API, no
 * {@code libcuda.so.1}, no Metal and no device to find. {@link LinalgGpu#available()},
 * {@link LinalgGpu#description()} and
 * {@link LinalgGpu#install(Environment, LispEvaluator)} are the only three ENTRY POINTS
 * into {@code LinalgGpuKernels} (the holder of the single {@code am.ik.gpu} reference), so
 * substituting all three makes that class -- and with it both device bindings, the CUDA
 * one and the Metal one -- unreachable. Adding a new public method to {@code LinalgGpu} that touches the kernels
 * would break that, and only the Pages workflow's Web Image build would catch it. The
 * playground never sets {@code --gpu} anyway, so reporting "unavailable" is also the
 * truthful answer.
 *
 * <p>
 * Compiled only under the {@code web} Maven profile (it lives in {@code src/web/java});
 * the JVM and regular native-image builds use the real {@link LinalgGpu}.
 */
@TargetClass(LinalgGpu.class)
final class Target_LinalgGpu {

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
		throw new IllegalStateException("--gpu is not available in the browser playground");
	}

}
