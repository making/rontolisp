import java.lang.foreign.*;
import java.nio.file.*;

/**
 * Where does a device GEMV (`vec:matvec`, .todo/475) beat the `--simd` lane kernel, and
 * does it beat it at all when the matrix is NOT resident? The question the todo left open,
 * and the one the threshold of `Gpu.matvec` is derived from.
 *
 * <p>
 * Three device columns per shape, all through the shipped route (pooled allocation,
 * critical heap copies), over the kernels in `matvec-probe.cu`:
 *
 * <ul>
 * <li><b>cold</b>: W, x up; launch; y down -- what a matrix the program writes between
 * calls (llama2's KV cache) pays on every call;
 * <li><b>resident</b>: W uploaded ONCE, then per call x up; launch; y down -- what a
 * matrix the program never writes (every weight) pays from its second call on, which is
 * what `CudaResidency` buys;
 * <li><b>kernel</b>: the launch plus a synchronize over resident buffers, no copies --
 * the floor the other two sit on.
 * </ul>
 *
 * <p>
 * And the accumulator choice, at f32: summing in float (the lane kernel's width) against
 * summing in double (the scalar defun's rule), and the plain kernel against a float4-load
 * one at the 36.8 MB classifier head, which is the one shape here that is at the device's
 * own bandwidth rather than at the launch floor.
 *
 * <p>
 * The CPU column is `matvec-baseline.lisp` under `--simd` on the JVM class output:
 *
 * <pre>
 * JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
 * java -jar $JAR matvec-baseline.lisp -o Mv.class --simd && java --add-modules jdk.incubator.vector Mv
 * nvcc -arch=compute_75 -ptx matvec-probe.cu -o matvec-probe.ptx
 * java --enable-native-access=ALL-UNNAMED MatvecCrossover.java
 * </pre>
 */
public class MatvecCrossover {

	/** llama2 stories15M's shapes (dim 288, hidden 768, vocab 32000, seq 256, head 48). */
	static final int[][] SHAPES = { { 64, 64 }, { 128, 128 }, { 192, 192 }, { 256, 256 }, { 288, 288 }, { 384, 384 },
			{ 512, 512 }, { 768, 288 }, { 288, 768 }, { 768, 768 }, { 1024, 1024 }, { 2048, 2048 }, { 32000, 288 },
			{ 256, 48 }, { 48, 256 }, { 4096, 4096 } };

	static MemorySegment f32acc32, f32acc64, f64acc64, f32v4;

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		// The probe's own module, beside the shipped one CuLib loaded.
		try (Arena a = Arena.ofConfined()) {
			MemorySegment mod = Arena.global().allocate(CuLib.P);
			CuLib.ck((int) CuLib.cuModuleLoadData.invoke(mod,
					Arena.global().allocateFrom(Files.readString(Path.of("matvec-probe.ptx")))), "cuModuleLoadData");
			CuLib.module = mod.get(CuLib.P, 0);
			f32acc32 = CuLib.func("gemv_f32_acc32");
			f32acc64 = CuLib.func("gemv_f32_acc64");
			f64acc64 = CuLib.func("gemv_f64_acc64");
			f32v4 = CuLib.func("gemv_f32_acc64_v4");
		}
		// Warm the device call path first (the first shape in a process pays ~500 us/call
		// for its first few thousand calls, .kb/gpu.md).
		for (int i = 0; i < 3; i++) {
			run(256, 256, f32acc64, 4, 2000);
		}
		System.out.printf("%n%-12s %8s | %10s %10s %10s | %10s %10s %10s | %10s %10s%n", "rows x cols", "elems",
				"f32 cold", "f32 resid", "f32 kern", "f64 cold", "f64 resid", "f64 kern", "f32acc32 r", "f32 v4 r");
		for (int[] s : SHAPES) {
			int rows = s[0], cols = s[1];
			long n = (long) rows * cols;
			int reps = n <= 1 << 18 ? 2000 : (n <= 1 << 22 ? 400 : 60);
			double[] f32 = run(rows, cols, f32acc64, 4, reps);
			double[] f64 = run(rows, cols, f64acc64, 8, reps);
			double[] a32 = run(rows, cols, f32acc32, 4, reps);
			double[] v4 = cols % 4 == 0 ? run(rows, cols, f32v4, 4, reps) : new double[] { 0, 0, 0 };
			System.out.printf("%-12s %8d | %10.1f %10.1f %10.1f | %10.1f %10.1f %10.1f | %10.1f %10.1f%n",
					rows + "x" + cols, n, f32[0], f32[1], f32[2], f64[0], f64[1], f64[2], a32[1], v4[1]);
		}
		// Correctness of the double-accumulating f32 kernel against a double oracle, and
		// how far the float-accumulating one lands, at the classifier head's shape.
		check(1024, 768);
	}

	/** {cold, resident, kernel} us/call, best of reps. */
	static double[] run(int rows, int cols, MemorySegment kernel, int width, int reps) throws Throwable {
		long wBytes = (long) rows * cols * width, xBytes = (long) cols * width, yBytes = (long) rows * width;
		double[] wd = new double[rows * cols], xd = new double[cols], yd = new double[rows];
		float[] wf = new float[rows * cols], xf = new float[cols], yf = new float[rows];
		for (int i = 0; i < wd.length; i++) {
			wd[i] = Math.sin(i * 0.37);
			wf[i] = (float) wd[i];
		}
		for (int i = 0; i < cols; i++) {
			xd[i] = Math.cos(i * 0.11);
			xf[i] = (float) xd[i];
		}
		MemorySegment sw = width == 8 ? MemorySegment.ofArray(wd) : MemorySegment.ofArray(wf);
		MemorySegment sx = width == 8 ? MemorySegment.ofArray(xd) : MemorySegment.ofArray(xf);
		MemorySegment sy = width == 8 ? MemorySegment.ofArray(yd) : MemorySegment.ofArray(yf);
		double cold = CuLib.best(reps, () -> {
			try (Arena arena = Arena.ofConfined()) {
				long dw = CuLib.alloc(arena, wBytes, true), dx = CuLib.alloc(arena, xBytes, true),
						dy = CuLib.alloc(arena, yBytes, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dw, sw, wBytes), "htod");
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dx, sx, xBytes), "htod");
				launch(kernel, dw, dx, dy, rows, cols, arena);
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(sy, dy, yBytes), "dtoh");
				CuLib.free(dw, true);
				CuLib.free(dx, true);
				CuLib.free(dy, true);
			}
		});
		double[] out = new double[3];
		try (Arena arena = Arena.ofConfined()) {
			long dw = CuLib.alloc(arena, wBytes, true);
			CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dw, sw, wBytes), "htod");
			double resident = CuLib.best(reps, () -> {
				try (Arena inner = Arena.ofConfined()) {
					long dx = CuLib.alloc(inner, xBytes, true), dy = CuLib.alloc(inner, yBytes, true);
					CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dx, sx, xBytes), "htod");
					launch(kernel, dw, dx, dy, rows, cols, inner);
					CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(sy, dy, yBytes), "dtoh");
					CuLib.free(dx, true);
					CuLib.free(dy, true);
				}
			});
			long dx = CuLib.alloc(arena, xBytes, true), dy = CuLib.alloc(arena, yBytes, true);
			CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dx, sx, xBytes), "htod");
			double kern = CuLib.best(reps, () -> {
				try (Arena inner = Arena.ofConfined()) {
					launch(kernel, dw, dx, dy, rows, cols, inner);
					CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
				}
			});
			CuLib.free(dx, true);
			CuLib.free(dy, true);
			CuLib.free(dw, true);
			out[0] = cold;
			out[1] = resident;
			out[2] = kern;
		}
		return out;
	}

	static void launch(MemorySegment f, long dw, long dx, long dy, int rows, int cols, Arena a) throws Throwable {
		MemorySegment pw = a.allocate(CuLib.L), px = a.allocate(CuLib.L), py = a.allocate(CuLib.L);
		pw.set(CuLib.L, 0, dw);
		px.set(CuLib.L, 0, dx);
		py.set(CuLib.L, 0, dy);
		MemorySegment r = a.allocate(CuLib.I), c = a.allocate(CuLib.I);
		r.set(CuLib.I, 0, rows);
		c.set(CuLib.I, 0, cols);
		MemorySegment params = a.allocate(CuLib.P, 5);
		params.setAtIndex(CuLib.P, 0, pw);
		params.setAtIndex(CuLib.P, 1, px);
		params.setAtIndex(CuLib.P, 2, py);
		params.setAtIndex(CuLib.P, 3, r);
		params.setAtIndex(CuLib.P, 4, c);
		int block = 256, warps = block / 32;
		CuLib.ck((int) CuLib.cuLaunchKernel.invoke(f, (rows + warps - 1) / warps, 1, 1, block, 1, 1, 0,
				MemorySegment.NULL, params, MemorySegment.NULL), "cuLaunchKernel");
	}

	static void check(int rows, int cols) throws Throwable {
		float[] wf = new float[rows * cols], xf = new float[cols], y64 = new float[rows], y32 = new float[rows];
		for (int i = 0; i < wf.length; i++) {
			wf[i] = (float) Math.sin(i * 0.37);
		}
		for (int i = 0; i < cols; i++) {
			xf[i] = (float) Math.cos(i * 0.11);
		}
		try (Arena arena = Arena.ofConfined()) {
			long dw = CuLib.alloc(arena, (long) wf.length * 4, true), dx = CuLib.alloc(arena, cols * 4L, true),
					dy = CuLib.alloc(arena, rows * 4L, true);
			CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dw, MemorySegment.ofArray(wf), (long) wf.length * 4), "htod");
			CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dx, MemorySegment.ofArray(xf), cols * 4L), "htod");
			launch(f32acc64, dw, dx, dy, rows, cols, arena);
			CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(y64), dy, rows * 4L), "dtoh");
			launch(f32acc32, dw, dx, dy, rows, cols, arena);
			CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(y32), dy, rows * 4L), "dtoh");
			CuLib.free(dw, true);
			CuLib.free(dx, true);
			CuLib.free(dy, true);
		}
		// The scalar defun's oracle: double accumulation in order, narrowed on the store;
		// and the --simd lane kernel's: four float lanes, stride 4, then a lane fold.
		int same64 = 0, same32 = 0, sameLane = 0;
		double worst64 = 0, worst32 = 0, worstLane = 0, scale = 0;
		for (int r = 0; r < rows; r++) {
			double acc = 0;
			float l0 = 0, l1 = 0, l2 = 0, l3 = 0;
			for (int j = 0; j < cols; j++) {
				acc += (double) wf[r * cols + j] * xf[j];
			}
			for (int j = 0; j + 3 < cols; j += 4) {
				l0 += wf[r * cols + j] * xf[j];
				l1 += wf[r * cols + j + 1] * xf[j + 1];
				l2 += wf[r * cols + j + 2] * xf[j + 2];
				l3 += wf[r * cols + j + 3] * xf[j + 3];
			}
			float oracle = (float) acc, lane = (l0 + l1) + (l2 + l3);
			scale = Math.max(scale, Math.abs(oracle));
			if (y64[r] == oracle) same64++;
			if (y32[r] == oracle) same32++;
			if (lane == oracle) sameLane++;
			worst64 = Math.max(worst64, Math.abs(y64[r] - oracle));
			worst32 = Math.max(worst32, Math.abs(y32[r] - oracle));
			worstLane = Math.max(worstLane, Math.abs(lane - oracle));
		}
		System.out.printf("%n%dx%d f32 against the scalar defun's oracle (double acc, narrowed):%n", rows, cols);
		System.out.printf("  device acc64: %d/%d rows bit-identical, worst %.2e of the largest cell%n", same64, rows,
				worst64 / scale);
		System.out.printf("  device acc32: %d/%d rows bit-identical, worst %.2e%n", same32, rows, worst32 / scale);
		System.out.printf("  --simd lanes: %d/%d rows bit-identical, worst %.2e%n", sameLane, rows, worstLane / scale);
	}

}
