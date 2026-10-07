package am.ik.rontolisp.eval;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.nativeimage.c.type.CDoublePointer;
import org.graalvm.nativeimage.c.type.CFloatPointer;
import org.graalvm.word.WordFactory;

/**
 * The four CBLAS products of {@link LinalgBlasKernels} re-issued through SubstrateVM's
 * own AOT native-call route inside the native image. The FFM downcall handles the class
 * binds are interpreted by SubstrateVM -- a handle that did not exist at build time has
 * no compiled invoker, so every call walks its {@code LambdaForm} boxing each argument,
 * ~1.7 us plus ~0.4 us per argument, 6.4 us for a gemv and 7.2 for a gemm against 7-30 ns
 * on the JVM ({@code .kb/native-downcalls.md}). An {@code @InvokeCFunctionPointer}
 * interface method is compiled at build time against a function pointer supplied at run
 * time, and costs what the JVM's call costs: 11-16 ns for the CUDA shapes, ~90 ns here
 * with the three operand arrays pinned
 * ({@code .todo/artefacts/729-.../CfpShapeFloor.java}). So the binary's {@code --blas}
 * crossover is the JVM's again and {@code MIN_WORK} is one number.
 *
 * <p>
 * The operands are Java heap arrays. {@link Linker.Option#critical(boolean) critical} has
 * no counterpart on this route; a {@link PinnedObject} holds each array in place for the
 * call and hands out the address of its first used element. The call TRANSITIONS to
 * native, so a long product is safepoint-friendly and the staged-arena branch the JVM
 * takes above {@code CRITICAL_FLOP_CEILING} is not needed here. Word-typed values must
 * not cross a lambda or sit in a static field (both fail the image build, the second with
 * "missing StateSplitProxy"), which is why the pointer is made inside each method.
 *
 * <p>
 * Compiled only under the {@code native} Maven profile ({@code src/native/java}); the JVM
 * runs the FFM methods this class replaces. The aliased fields must keep their names in
 * {@link LinalgBlasKernels} -- {@code NativeSubstitutionsTest} pins every {@code @Alias}
 * and {@code @Substitute} member here against that class from the JVM lane, since this
 * source set is otherwise compiled by nothing {@code ./mvnw test} runs.
 * {@code .kb/native-downcalls.md}.
 */
@TargetClass(LinalgBlasKernels.class)
final class Target_LinalgBlasKernels {

	@Alias
	private static long DGEMM_ADDRESS, SGEMM_ADDRESS, DGEMV_ADDRESS, SGEMV_ADDRESS;

	@Alias
	private static int ROW_MAJOR, NO_TRANS, TRANS;

	@Alias
	static native void note(long flops);

	/** The four entry points, each invoked on the address the FFM lookup found. */
	interface Cblas extends CFunctionPointer {

		@InvokeCFunctionPointer
		void dgemm(int order, int transA, int transB, int m, int n, int k, double alpha, CDoublePointer a, int lda,
				CDoublePointer b, int ldb, double beta, CDoublePointer c, int ldc);

		@InvokeCFunctionPointer
		void sgemm(int order, int transA, int transB, int m, int n, int k, float alpha, CFloatPointer a, int lda,
				CFloatPointer b, int ldb, float beta, CFloatPointer c, int ldc);

		@InvokeCFunctionPointer
		void dgemv(int order, int trans, int rows, int cols, double alpha, CDoublePointer a, int lda, CDoublePointer x,
				int incx, double beta, CDoublePointer y, int incy);

		@InvokeCFunctionPointer
		void sgemv(int order, int trans, int rows, int cols, float alpha, CFloatPointer a, int lda, CFloatPointer x,
				int incx, float beta, CFloatPointer y, int incy);

	}

	@Substitute
	static void gemm(double[] a, int oa, double[] b, int ob, double[] c, int oc, int n, int m, int p) {
		note(2L * n * m * p);
		Cblas cblas = WordFactory.pointer(DGEMM_ADDRESS);
		try (PinnedObject pa = PinnedObject.create(a);
				PinnedObject pb = PinnedObject.create(b);
				PinnedObject pc = PinnedObject.create(c)) {
			cblas.dgemm(ROW_MAJOR, NO_TRANS, NO_TRANS, n, p, m, 1.0, pa.addressOfArrayElement(oa), m,
					pb.addressOfArrayElement(ob), p, 0.0, pc.addressOfArrayElement(oc), p);
		}
	}

	@Substitute
	static void gemmF(float[] a, int oa, float[] b, int ob, float[] c, int oc, int n, int m, int p) {
		note(2L * n * m * p);
		Cblas cblas = WordFactory.pointer(SGEMM_ADDRESS);
		try (PinnedObject pa = PinnedObject.create(a);
				PinnedObject pb = PinnedObject.create(b);
				PinnedObject pc = PinnedObject.create(c)) {
			cblas.sgemm(ROW_MAJOR, NO_TRANS, NO_TRANS, n, p, m, 1.0f, pa.addressOfArrayElement(oa), m,
					pb.addressOfArrayElement(ob), p, 0.0f, pc.addressOfArrayElement(oc), p);
		}
	}

	@Substitute
	static void gemv(double[] a, int oa, int rows, int cols, double[] x, int ox, double[] y, int oy,
			boolean transposed) {
		int trans = transposed ? TRANS : NO_TRANS;
		note(2L * rows * cols);
		Cblas cblas = WordFactory.pointer(DGEMV_ADDRESS);
		try (PinnedObject pa = PinnedObject.create(a);
				PinnedObject px = PinnedObject.create(x);
				PinnedObject py = PinnedObject.create(y)) {
			cblas.dgemv(ROW_MAJOR, trans, rows, cols, 1.0, pa.addressOfArrayElement(oa), cols,
					px.addressOfArrayElement(ox), 1, 0.0, py.addressOfArrayElement(oy), 1);
		}
	}

	@Substitute
	static void gemvF(float[] a, int oa, int rows, int cols, float[] x, int ox, float[] y, int oy, boolean transposed) {
		int trans = transposed ? TRANS : NO_TRANS;
		note(2L * rows * cols);
		Cblas cblas = WordFactory.pointer(SGEMV_ADDRESS);
		try (PinnedObject pa = PinnedObject.create(a);
				PinnedObject px = PinnedObject.create(x);
				PinnedObject py = PinnedObject.create(y)) {
			cblas.sgemv(ROW_MAJOR, trans, rows, cols, 1.0f, pa.addressOfArrayElement(oa), cols,
					px.addressOfArrayElement(ox), 1, 0.0f, py.addressOfArrayElement(oy), 1);
		}
	}

}
