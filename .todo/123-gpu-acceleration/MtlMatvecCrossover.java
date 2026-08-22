import java.lang.foreign.*;
import java.nio.file.*;

/**
 * MatvecCrossover.java's question, re-asked on Metal (.todo/477): where does a device GEMV
 * (`vec:matvec`) beat the `--simd` lane kernel on this machine, does the cold trip ever pay,
 * and -- the question MSL forces, having no `double` -- what accumulator can the kernel use
 * and how far does it land from the scalar defun's bits?
 *
 * <p>
 * Three device columns per shape, all through the shipped route (pooled shared-storage
 * buffers, a memcpy either way, one command buffer per call):
 *
 * <ul>
 * <li><b>cold</b>: W, x copied in; one command buffer; y copied out -- what a matrix the
 * program rewrites between calls would pay if it were taken;
 * <li><b>resident</b>: W copied in ONCE, then per call x in, one command buffer, y out --
 * what a never-written matrix pays from its second call on, which is what residency buys;
 * <li><b>kernel</b>: the command buffer alone over resident buffers -- the floor the other
 * two sit on, which on Metal is ~77 us per command buffer before the kernel runs at all.
 * </ul>
 *
 * <p>
 * Two accumulators, both float because that is all MSL has: the plain float sum (the
 * `--simd` lane kernel's width, a different order) and a COMPENSATED one -- the product's
 * rounding error recovered with an fma, the running sum kept as a float-float pair by
 * TwoSum -- which carries ~48 bits and so lands on the double-accumulated defun's bits
 * nearly as often as CUDA's double kernel does. Both are checked against the scalar defun's
 * oracle (a double sum, narrowed on the store) at 1024x768, and the compensated one is
 * compiled twice, with and without `#pragma METAL fp contract(off)`, to see whether the
 * compiler's default contraction breaks the error-free transforms.
 *
 * <p>
 * The CPU column is `matvec-baseline.lisp` under `--simd` on the JVM class output:
 *
 * <pre>
 * JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
 * java -jar $JAR matvec-baseline.lisp -o Mv.class --simd && java --add-modules jdk.incubator.vector Mv
 * java --enable-native-access=ALL-UNNAMED MtlMatvecCrossover.java
 * </pre>
 */
public class MtlMatvecCrossover {

	/** llama2 stories15M's shapes (dim 288, hidden 768, vocab 32000, seq 256, head 48). */
	static final int[][] SHAPES = { { 64, 64 }, { 128, 128 }, { 192, 192 }, { 256, 256 }, { 288, 288 }, { 384, 384 },
			{ 512, 512 }, { 768, 288 }, { 288, 768 }, { 768, 768 }, { 1024, 1024 }, { 1448, 1448 }, { 1536, 1536 }, { 2048, 2048 }, { 32000, 288 },
			{ 256, 48 }, { 48, 256 }, { 4096, 4096 } };

	static final String COMP_BODY = """
			  int rows = args[0], cols = args[1];
			  int row = int(tg * sgs + sg);
			  if (row >= rows) return;
			  device const float* w = W + (long) row * cols;
			  float hi = 0.0f, lo = 0.0f;
			  for (int j = int(lane); j < cols; j += int(width)) {
			    float a = w[j], b = x[j];
			    float p = a * b;
			    float pe = fma(a, b, -p);
			    float s = hi + p;
			    float bv = s - hi;
			    float err = (hi - (s - bv)) + (p - bv);
			    hi = s;
			    lo += err + pe;
			  }
			  for (uint off = width / 2; off > 0; off >>= 1) {
			    float ohi = simd_shuffle_down(hi, off);
			    float olo = simd_shuffle_down(lo, off);
			    float s = hi + ohi;
			    float bv = s - hi;
			    float err = (hi - (s - bv)) + (ohi - bv);
			    hi = s;
			    lo += olo + err;
			  }
			  if (lane == 0) y[row] = hi + lo;
			""";

	static final String SIG = "(device const float* W [[buffer(0)]], device const float* x [[buffer(1)]],"
			+ " device float* y [[buffer(2)]], constant int* args [[buffer(3)]],"
			+ " uint lane [[thread_index_in_simdgroup]], uint width [[threads_per_simdgroup]],"
			+ " uint sg [[simdgroup_index_in_threadgroup]], uint sgs [[simdgroups_per_threadgroup]],"
			+ " uint tg [[threadgroup_position_in_grid]])";

	static final String SRC = """
			#include <metal_stdlib>
			using namespace metal;
			kernel void gemv_f32_acc32 %s {
			  int rows = args[0], cols = args[1];
			  int row = int(tg * sgs + sg);
			  if (row >= rows) return;
			  device const float* w = W + (long) row * cols;
			  float acc = 0.0f;
			  for (int j = int(lane); j < cols; j += int(width)) acc += w[j] * x[j];
			  acc = simd_sum(acc);
			  if (lane == 0) y[row] = acc;
			}
			kernel void gemv_f32_comp_default %s {
			%s}
			#pragma METAL fp contract(off)
			kernel void gemv_f32_comp %s {
			%s}
			""".formatted(SIG, SIG, COMP_BODY, SIG, COMP_BODY);

	static final int GROUP = 256;

	static MemorySegment dev, queue, acc32, compDefault, comp;

	public static void main(String[] args) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		dev = Mtl.device();
		// MTLMathModeSafe, as the shipped library sets it.
		MemorySegment opts = Mtl.msg(Mtl.msg(Mtl.cls("MTLCompileOptions"), "alloc"), "init");
		Mtl.send(null, Mtl.L).invokeExact(opts, Mtl.sel("setMathMode:"), 0L);
		MemorySegment lib = Mtl.library(dev, SRC, opts);
		acc32 = Mtl.pipeline(dev, lib, "gemv_f32_acc32");
		compDefault = Mtl.pipeline(dev, lib, "gemv_f32_comp_default");
		comp = Mtl.pipeline(dev, lib, "gemv_f32_comp");
		queue = Mtl.queue(dev);
		System.out.println("device: " + Mtl.fromNsString(Mtl.msg(dev, "name")));
		// Warm the clocks and the path.
		for (int i = 0; i < 3; i++) {
			run(256, 256, comp, 200);
		}
		System.out.printf("%n%-12s %8s | %10s %10s %10s | %10s %10s%n", "rows x cols", "elems", "comp cold",
				"comp resid", "comp kern", "acc32 resid", "acc32 kern");
		for (int[] s : SHAPES) {
			int rows = s[0], cols = s[1];
			long n = (long) rows * cols;
			int reps = n <= 1 << 18 ? 400 : (n <= 1 << 22 ? 200 : 60);
			double[] c = run(rows, cols, comp, reps);
			double[] a = run(rows, cols, acc32, reps);
			System.out.printf("%-12s %8d | %10.1f %10.1f %10.1f | %10.1f %10.1f%n", rows + "x" + cols, n, c[0], c[1],
					c[2], a[1], a[2]);
		}
		check(1024, 768);
		Mtl.poolPop.invokeExact(pool);
	}

	/** {cold, resident, kernel} us/call, best of reps after a warm-up. */
	static double[] run(int rows, int cols, MemorySegment kernel, int reps) throws Throwable {
		long wBytes = (long) rows * cols * 4, xBytes = cols * 4L, yBytes = rows * 4L;
		float[] wf = new float[rows * cols], xf = new float[cols], yf = new float[rows];
		for (int i = 0; i < wf.length; i++) {
			wf[i] = (float) Math.sin(i * 0.37);
		}
		for (int i = 0; i < cols; i++) {
			xf[i] = (float) Math.cos(i * 0.11);
		}
		MemorySegment dw = Mtl.buffer(dev, wBytes, Mtl.SHARED), dx = Mtl.buffer(dev, xBytes, Mtl.SHARED),
				dy = Mtl.buffer(dev, yBytes, Mtl.SHARED);
		MemorySegment cw = Mtl.contents(dw, wBytes), cx = Mtl.contents(dx, xBytes), cy = Mtl.contents(dy, yBytes);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment argsSeg = arena.allocate(Mtl.I, 2);
			argsSeg.setAtIndex(Mtl.I, 0, rows);
			argsSeg.setAtIndex(Mtl.I, 1, cols);
			int perGroup = GROUP / 32;
			MemorySegment groups = Mtl.size(arena, (rows + perGroup - 1) / perGroup, 1, 1),
					per = Mtl.size(arena, GROUP, 1, 1);
			Runnable launch = () -> {
				MemorySegment cb = Mtl.beginCommands(queue);
				MemorySegment enc = Mtl.beginEncoder(cb, kernel);
				Mtl.setBuffer(enc, dw, 0, 0);
				Mtl.setBuffer(enc, dx, 0, 1);
				Mtl.setBuffer(enc, dy, 0, 2);
				Mtl.setBytes(enc, argsSeg, 8, 3);
				Mtl.dispatch(enc, groups, per);
				Mtl.endEncoding(enc);
				Mtl.commitAndWait(cb);
			};
			double cold = best(reps, () -> {
				MemorySegment.copy(wf, 0, cw, Mtl.F, 0, wf.length);
				MemorySegment.copy(xf, 0, cx, Mtl.F, 0, cols);
				launch.run();
				MemorySegment.copy(cy, Mtl.F, 0, yf, 0, rows);
			});
			MemorySegment.copy(wf, 0, cw, Mtl.F, 0, wf.length);
			double resident = best(reps, () -> {
				MemorySegment.copy(xf, 0, cx, Mtl.F, 0, cols);
				launch.run();
				MemorySegment.copy(cy, Mtl.F, 0, yf, 0, rows);
			});
			double kern = best(reps, launch);
			return new double[] { cold, resident, kern };
		}
		finally {
			Mtl.release(dw);
			Mtl.release(dx);
			Mtl.release(dy);
		}
	}

	static double best(int reps, Runnable body) {
		double best = Double.MAX_VALUE;
		int warm = Math.max(5, reps / 10);
		for (int r = 0; r < reps + warm; r++) {
			MemorySegment pool;
			try {
				pool = (MemorySegment) Mtl.poolPush.invokeExact();
			}
			catch (Throwable t) {
				throw new RuntimeException(t);
			}
			long t0 = System.nanoTime();
			body.run();
			long dt = System.nanoTime() - t0;
			try {
				Mtl.poolPop.invokeExact(pool);
			}
			catch (Throwable t) {
				throw new RuntimeException(t);
			}
			if (r >= warm) {
				best = Math.min(best, dt / 1e3);
			}
		}
		return best;
	}

	static void check(int rows, int cols) throws Throwable {
		float[] wf = new float[rows * cols], xf = new float[cols];
		for (int i = 0; i < wf.length; i++) {
			wf[i] = (float) Math.sin(i * 0.37);
		}
		for (int i = 0; i < cols; i++) {
			xf[i] = (float) Math.cos(i * 0.11);
		}
		float[] yComp = compute(rows, cols, wf, xf, comp), yDefault = compute(rows, cols, wf, xf, compDefault),
				y32 = compute(rows, cols, wf, xf, acc32);
		int sameComp = 0, sameDefault = 0, same32 = 0, sameLane = 0;
		double worstComp = 0, worstDefault = 0, worst32 = 0, worstLane = 0, scale = 0;
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
			if (yComp[r] == oracle) sameComp++;
			if (yDefault[r] == oracle) sameDefault++;
			if (y32[r] == oracle) same32++;
			if (lane == oracle) sameLane++;
			worstComp = Math.max(worstComp, Math.abs(yComp[r] - oracle));
			worstDefault = Math.max(worstDefault, Math.abs(yDefault[r] - oracle));
			worst32 = Math.max(worst32, Math.abs(y32[r] - oracle));
			worstLane = Math.max(worstLane, Math.abs(lane - oracle));
		}
		System.out.printf("%n%dx%d f32 against the scalar defun's oracle (double acc, narrowed):%n", rows, cols);
		System.out.printf("  compensated, contract off: %d/%d rows bit-identical, worst %.2e of the largest cell%n",
				sameComp, rows, worstComp / scale);
		System.out.printf("  compensated, default:      %d/%d rows bit-identical, worst %.2e%n", sameDefault, rows,
				worstDefault / scale);
		System.out.printf("  float accumulator:         %d/%d rows bit-identical, worst %.2e%n", same32, rows,
				worst32 / scale);
		System.out.printf("  --simd lanes:              %d/%d rows bit-identical, worst %.2e%n", sameLane, rows,
				worstLane / scale);
	}

	static float[] compute(int rows, int cols, float[] wf, float[] xf, MemorySegment kernel) throws Throwable {
		long wBytes = (long) rows * cols * 4, xBytes = cols * 4L, yBytes = rows * 4L;
		MemorySegment dw = Mtl.buffer(dev, wBytes, Mtl.SHARED), dx = Mtl.buffer(dev, xBytes, Mtl.SHARED),
				dy = Mtl.buffer(dev, yBytes, Mtl.SHARED);
		float[] y = new float[rows];
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment.copy(wf, 0, Mtl.contents(dw, wBytes), Mtl.F, 0, wf.length);
			MemorySegment.copy(xf, 0, Mtl.contents(dx, xBytes), Mtl.F, 0, cols);
			MemorySegment argsSeg = arena.allocate(Mtl.I, 2);
			argsSeg.setAtIndex(Mtl.I, 0, rows);
			argsSeg.setAtIndex(Mtl.I, 1, cols);
			int perGroup = GROUP / 32;
			MemorySegment cb = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cb, kernel);
			Mtl.setBuffer(enc, dw, 0, 0);
			Mtl.setBuffer(enc, dx, 0, 1);
			Mtl.setBuffer(enc, dy, 0, 2);
			Mtl.setBytes(enc, argsSeg, 8, 3);
			Mtl.dispatch(enc, Mtl.size(arena, (rows + perGroup - 1) / perGroup, 1, 1), Mtl.size(arena, GROUP, 1, 1));
			Mtl.endEncoding(enc);
			Mtl.commitAndWait(cb);
			MemorySegment.copy(Mtl.contents(dy, yBytes), Mtl.F, 0, y, 0, rows);
		}
		finally {
			Mtl.release(dw);
			Mtl.release(dx);
			Mtl.release(dy);
		}
		return y;
	}

}
