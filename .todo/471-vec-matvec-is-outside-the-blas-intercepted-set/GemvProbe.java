import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Random;
import jdk.incubator.vector.*;

/**
 * Is cblas_?gemv worth binding for vec:matvec? The comparison that matters is against the
 * --simd LANE kernel (JvmSimdVectorTemplate.simdMatvec), not against a scalar loop: f32
 * accumulates in FloatVector.SPECIES_128 (four lanes, single precision, the pinned
 * reduction species), f64 in DoubleVector.SPECIES_PREFERRED, and both fold with
 * mul().add() rather than fma() -- the same shape the emitted bridge runs.
 */
public class GemvProbe {
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_128;
	static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;
	static final Linker L = Linker.nativeLinker();
	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;
	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
	static final AddressLayout P = ValueLayout.ADDRESS;
	static final int ROW = 101, NT = 111;
	static MethodHandle sgemv, dgemv;
	/** The library to time, overridable so the probe runs off macOS: see README.md. */
	static String library() {
		for (String name : new String[] { "PROBE_BLAS", "RONTOLISP_BLAS" }) {
			String value = System.getenv(name);
			if (value != null && !value.isEmpty()) {
				return value;
			}
		}
		return System.getProperty("os.name", "").contains("Mac")
				? "/System/Library/Frameworks/Accelerate.framework/Accelerate" : "libopenblas.so.0";
	}

	static {
		var lk = SymbolLookup.libraryLookup(library(), Arena.global());
		sgemv = L.downcallHandle(lk.find("cblas_sgemv").orElseThrow(),
				FunctionDescriptor.ofVoid(I, I, I, I, F, P, I, P, I, F, P, I), Linker.Option.critical(true));
		dgemv = L.downcallHandle(lk.find("cblas_dgemv").orElseThrow(),
				FunctionDescriptor.ofVoid(I, I, I, I, D, P, I, P, I, D, P, I), Linker.Option.critical(true));
	}

	public static void main(String[] a) throws Throwable {
		System.out.println("library: " + library() + "   (set PROBE_BLAS to time another one)");
		System.out.printf("%-14s %14s %14s %10s   %s%n", "rows x cols", "lane kernel", "cblas gemv", "ratio", "max rel diff");
		for (int[] s : new int[][] { { 256, 256 }, { 288, 288 }, { 288, 768 }, { 768, 288 }, { 4096, 288 }, { 2048, 2048 } }) {
			bench32(s[0], s[1]);
		}
		System.out.println();
		System.out.printf("%-14s %14s %14s %10s%n", "rows x cols (f64)", "lane kernel", "cblas gemv", "ratio");
		for (int[] s : new int[][] { { 256, 256 }, { 512, 512 }, { 2048, 2048 } }) {
			bench64(s[0], s[1]);
		}
	}

	static void bench32(int rows, int cols) throws Throwable {
		Random r = new Random(3);
		float[] w = new float[rows * cols], x = new float[cols], y = new float[rows], y2 = new float[rows];
		for (int i = 0; i < w.length; i++) w[i] = (float) r.nextGaussian();
		for (int i = 0; i < cols; i++) x[i] = (float) r.nextGaussian();
		var sw = MemorySegment.ofArray(w); var sx = MemorySegment.ofArray(x); var sy = MemorySegment.ofArray(y2);
		int reps = Math.max(50, 20_000_000 / (rows * cols));
		double lane = Double.MAX_VALUE, blas = Double.MAX_VALUE;
		for (int t = 0; t < 6; t++) {
			long s0 = System.nanoTime();
			for (int i = 0; i < reps; i++) laneMatvecF(w, x, y, rows, cols);
			lane = Math.min(lane, (System.nanoTime() - s0) / 1e6 / reps);
			s0 = System.nanoTime();
			for (int i = 0; i < reps; i++) sgemv.invokeExact(ROW, NT, rows, cols, 1.0f, sw, cols, sx, 1, 0.0f, sy, 1);
			blas = Math.min(blas, (System.nanoTime() - s0) / 1e6 / reps);
		}
		double worst = 0;
		for (int i = 0; i < rows; i++) worst = Math.max(worst, Math.abs(y[i] - y2[i]) / Math.max(1e-9, Math.abs(y[i])));
		System.out.printf("%-14s %11.4f ms %11.4f ms %9.2fx   %.2e%n", rows + "x" + cols, lane, blas, lane / blas, worst);
	}

	static void bench64(int rows, int cols) throws Throwable {
		Random r = new Random(3);
		double[] w = new double[rows * cols], x = new double[cols], y = new double[rows], y2 = new double[rows];
		for (int i = 0; i < w.length; i++) w[i] = r.nextGaussian();
		for (int i = 0; i < cols; i++) x[i] = r.nextGaussian();
		var sw = MemorySegment.ofArray(w); var sx = MemorySegment.ofArray(x); var sy = MemorySegment.ofArray(y2);
		int reps = Math.max(50, 20_000_000 / (rows * cols));
		double lane = Double.MAX_VALUE, blas = Double.MAX_VALUE;
		for (int t = 0; t < 6; t++) {
			long s0 = System.nanoTime();
			for (int i = 0; i < reps; i++) laneMatvecD(w, x, y, rows, cols);
			lane = Math.min(lane, (System.nanoTime() - s0) / 1e6 / reps);
			s0 = System.nanoTime();
			for (int i = 0; i < reps; i++) dgemv.invokeExact(ROW, NT, rows, cols, 1.0, sw, cols, sx, 1, 0.0, sy, 1);
			blas = Math.min(blas, (System.nanoTime() - s0) / 1e6 / reps);
		}
		System.out.printf("%-14s %11.4f ms %11.4f ms %9.2fx%n", rows + "x" + cols, lane, blas, lane / blas);
	}

	/** The shape of JvmSimdVectorTemplate.simdMatvec's f32 path: one lane dot per row, f32 accumulator. */
	static void laneMatvecF(float[] w, float[] x, float[] y, int rows, int cols) {
		int bound = FS.loopBound(cols);
		for (int i = 0; i < rows; i++) {
			int off = i * cols;
			FloatVector acc = FloatVector.zero(FS);
			int j = 0;
			for (; j < bound; j += FS.length()) {
				acc = acc.add(FloatVector.fromArray(FS, w, off + j).mul(FloatVector.fromArray(FS, x, j)));
			}
			float s = acc.reduceLanes(VectorOperators.ADD);
			for (; j < cols; j++) s += w[off + j] * x[j];
			y[i] = s;
		}
	}

	static void laneMatvecD(double[] w, double[] x, double[] y, int rows, int cols) {
		int bound = DS.loopBound(cols);
		for (int i = 0; i < rows; i++) {
			int off = i * cols;
			DoubleVector acc = DoubleVector.zero(DS);
			int j = 0;
			for (; j < bound; j += DS.length()) {
				acc = acc.add(DoubleVector.fromArray(DS, w, off + j).mul(DoubleVector.fromArray(DS, x, j)));
			}
			double s = acc.reduceLanes(VectorOperators.ADD);
			for (; j < cols; j++) s += w[off + j] * x[j];
			y[i] = s;
		}
	}
}
