import java.lang.foreign.*;
import java.nio.file.*;
import java.util.Random;

/**
 * Phase 5's own probe: it compiles the CHECKED-IN gemm.metal (so a syntax error or a
 * missing MSL builtin fails here rather than at run time), checks every kernel against a
 * Java oracle, and measures the three numbers phase 5 needs and the CUDA half's file
 * cannot supply -- the cost of an MTLBuffer allocate/free pair, the per-call floor, and
 * the crossovers that set this backend's thresholds.
 *
 * java --enable-native-access=ALL-UNNAMED .todo/123-gpu-acceleration/MtlPhase5.java
 */
public class MtlPhase5 {

	static final SymbolLookup MPS = SymbolLookup.libraryLookup(
			"/System/Library/Frameworks/MetalPerformanceShaders.framework/MetalPerformanceShaders", Arena.global());

	static final int MPS_FLOAT32 = 0x10000000 | 32;

	static MemorySegment dev, queue;

	public static void main(String[] argv) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			dev = Mtl.device();
			queue = Mtl.queue(dev);
			String src = Files.readString(Path.of("src/main/resources/am/ik/gpu/gemm.metal"));
			long t0 = System.nanoTime();
			MemorySegment lib = Mtl.library(dev, src, options());
			System.out.printf("gemm.metal compiled in %.1f ms%n", (System.nanoTime() - t0) / 1e6);
			MemorySegment gemmB = Mtl.pipeline(dev, lib, "gemm_batched_f32");
			MemorySegment map = Mtl.pipeline(dev, lib, "map_f32");
			MemorySegment bcast = Mtl.pipeline(dev, lib, "bcast_f32");
			MemorySegment gather = Mtl.pipeline(dev, lib, "gather_f32");
			MemorySegment fold = Mtl.pipeline(dev, lib, "fold_f32");
			System.out.println("all five pipelines built");

			allocCost(ar);
			floor(ar, map);
			correctness(ar, gemmB, map, bcast, gather, fold);
			matmulSweep(ar, gemmB);
			mapSweep(ar, map);
			stridedSweep(ar, bcast, gather, fold);
			batchedSweep(ar, gemmB);
		}
		Mtl.poolPop.invokeExact(pool);
	}

	/** MTLMathModeSafe, or the older setFastMathEnabled:NO -- see gemm.metal's header. */
	static MemorySegment options() throws Throwable {
		MemorySegment o = Mtl.msg(Mtl.msg(Mtl.cls("MTLCompileOptions"), "alloc"), "init");
		MethodHandleShim.setLong(o, "setMathMode:", 0);
		return o;
	}

	static final class MethodHandleShim {

		static void setLong(MemorySegment self, String sel, long v) {
			try {
				Mtl.send(null, Mtl.L).invokeExact(self, Mtl.sel(sel), v);
			}
			catch (Throwable t) {
				throw new RuntimeException(t);
			}
		}

	}

	// --- what a buffer costs, which is what decides whether a pool is needed ---
	static void allocCost(Arena ar) {
		for (int bytes : new int[] { 4096, 1 << 20, 16 << 20 }) {
			double best = Double.MAX_VALUE;
			for (int i = 0; i < 2000; i++) {
				long t = System.nanoTime();
				MemorySegment b = Mtl.buffer(dev, bytes, Mtl.SHARED);
				Mtl.release(b);
				if (i > 200) best = Math.min(best, (System.nanoTime() - t) / 1e3);
			}
			System.out.printf("newBufferWithLength(%d) + release: %.2f us%n", bytes, best);
		}
	}

	static void floor(Arena ar, MemorySegment map) {
		int n = 16;
		MemorySegment a = Mtl.buffer(dev, n * 4L, Mtl.SHARED), c = Mtl.buffer(dev, n * 4L, Mtl.SHARED);
		MemorySegment args = ar.allocate(Mtl.I, 2);
		args.setAtIndex(Mtl.I, 0, n);
		args.setAtIndex(Mtl.I, 1, 0);
		MemorySegment g = Mtl.size(ar, 1, 1, 1), p = Mtl.size(ar, 256, 1, 1);
		double best = Double.MAX_VALUE;
		for (int i = 0; i < 3000; i++) {
			long t = System.nanoTime();
			MemorySegment cmd = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cmd, map);
			Mtl.setBuffer(enc, a, 0, 0);
			Mtl.setBuffer(enc, c, 0, 1);
			Mtl.setBytes(enc, args, 8, 2);
			Mtl.dispatch(enc, g, p);
			Mtl.endEncoding(enc);
			Mtl.commitAndWait(cmd);
			if (i > 300) best = Math.min(best, (System.nanoTime() - t) / 1e3);
		}
		System.out.printf("empty round trip (one dispatch, commit + wait): %.2f us%n", best);
		Mtl.release(a);
		Mtl.release(c);
	}

	// --- every kernel against a Java oracle ---
	static void correctness(Arena ar, MemorySegment gemmB, MemorySegment map, MemorySegment bcast,
			MemorySegment gather, MemorySegment fold) {
		Random rnd = new Random(7);
		// batched gemm, batch 3, 33x17 by 17x21, right operand broadcast
		int batch = 3, M = 33, K = 17, N = 21;
		float[] A = rand(rnd, batch * M * K), B = rand(rnd, K * N), C = new float[batch * M * N];
		run3(ar, gemmB, A, B, C, new int[] { M, N, K, M * K, 0 }, (N + 15) / 16, (M + 15) / 16, batch, 16, 16);
		double worst = 0;
		for (int z = 0; z < batch; z++)
			for (int i = 0; i < M; i++)
				for (int j = 0; j < N; j++) {
					float acc = 0;
					for (int k = 0; k < K; k++) acc += A[z * M * K + i * K + k] * B[k * N + j];
					worst = Math.max(worst, Math.abs(acc - C[z * M * N + i * N + j]));
				}
		System.out.printf("gemm_batched_f32 vs scalar f32 oracle: worst abs %.3g%n", worst);

		// map: erf over 4096
		int n = 4096;
		float[] X = rand(rnd, n), Y = new float[n];
		run2(ar, map, X, Y, new int[] { n, 11 }, (n + 255) / 256, 256);
		double mw = 0;
		for (int i = 0; i < n; i++) mw = Math.max(mw, Math.abs(erf(X[i]) - Y[i]) / Math.max(1e-30, Math.abs(erf(X[i]))));
		System.out.printf("map_f32 erf vs a double erf: worst relative %.3g%n", mw);

		// bcast: (4 5 6) - (4 5 1)
		int[] dims = { 4, 5, 6 };
		int[] sa = { 30, 6, 1 }, sb = { 5, 1, 0 };
		int outN = 120;
		float[] BA = rand(rnd, 120), BB = rand(rnd, 20), BC = new float[outN];
		int[] meta = new int[9];
		System.arraycopy(dims, 0, meta, 0, 3);
		System.arraycopy(sa, 0, meta, 3, 3);
		System.arraycopy(sb, 0, meta, 6, 3);
		runBcast(ar, bcast, BA, BB, BC, meta, 1, outN, 3);
		int bad = 0;
		for (int i = 0; i < outN; i++) {
			int ia = 0, ib = 0, rem = i;
			for (int k = 2; k >= 0; --k) {
				int c = rem % dims[k];
				rem /= dims[k];
				ia += c * sa[k];
				ib += c * sb[k];
			}
			if (Float.floatToRawIntBits(BC[i]) != Float.floatToRawIntBits(BA[ia] - BB[ib])) bad++;
		}
		System.out.println("bcast_f32 sub: cells differing from the float oracle = " + bad);

		// gather: transpose (4 5 6) -> (4 6 5)
		int[] gd = { 4, 6, 5 };
		int[] gs = { 30, 1, 6 };
		float[] GC = new float[120];
		int[] gmeta = new int[6];
		System.arraycopy(gd, 0, gmeta, 0, 3);
		System.arraycopy(gs, 0, gmeta, 3, 3);
		runGather(ar, gather, BA, GC, gmeta, 120, 3);
		bad = 0;
		for (int i = 0; i < 120; i++) {
			int ia = 0, rem = i;
			for (int k = 2; k >= 0; --k) {
				int c = rem % gd[k];
				rem /= gd[k];
				ia += c * gs[k];
			}
			if (Float.floatToRawIntBits(GC[i]) != Float.floatToRawIntBits(BA[ia])) bad++;
		}
		System.out.println("gather_f32 transpose: cells differing = " + bad);

		// fold: amax over the middle axis of (4 5 6)
		float[] FC = new float[24];
		runFold(ar, fold, BA, FC, 1, 4, 5, 6);
		bad = 0;
		for (int o = 0; o < 4; o++)
			for (int j = 0; j < 6; j++) {
				double acc = BA[o * 30 + j];
				for (int k = 1; k < 5; k++) {
					double x = BA[o * 30 + k * 6 + j];
					if (x > acc) acc = x;
				}
				if (Float.floatToRawIntBits(FC[o * 6 + j]) != Float.floatToRawIntBits((float) acc)) bad++;
			}
		System.out.println("fold_f32 amax: cells differing from the double-accumulating oracle = " + bad);
	}

	static double erf(double x) {
		// A&S 7.1.26 is not accurate enough to judge a kernel; use the series the project
		// itself uses only as a sanity bound, so this prints an order of magnitude.
		double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
		double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
				* Math.exp(-x * x);
		return x >= 0 ? y : -y;
	}

	// --- sweeps ---
	static void matmulSweep(Arena ar, MemorySegment gemmB) {
		System.out.printf("%nrank-2 f32 product, us per call, WITH both host copies and buffer churn%n");
		System.out.printf("%-8s %14s %14s%n", "n", "mps", "our kernel");
		for (int n : new int[] { 32, 48, 64, 96, 128, 192, 256, 512, 1024 }) {
			Random rnd = new Random(1);
			float[] A = rand(rnd, n * n), B = rand(rnd, n * n), C = new float[n * n];
			double ours = time(() -> run3(ar, gemmB, A, B, C, new int[] { n, n, n, n * n, n * n }, (n + 15) / 16,
					(n + 15) / 16, 1, 16, 16));
			double mps = time(() -> mps(ar, A, B, C, n, n, n));
			System.out.printf("%-8d %11.1f us %11.1f us%n", n, mps, ours);
		}
	}

	static void batchedSweep(Arena ar, MemorySegment gemmB) {
		System.out.printf("%nstacked f32 product, us per call, with copies%n");
		int[][] shapes = { { 256, 8 }, { 64, 16 }, { 16, 32 }, { 4, 64 }, { 16, 64 }, { 4, 128 }, { 16, 128 },
				{ 12, 256 } };
		for (int[] s : shapes) {
			int b = s[0], n = s[1];
			Random rnd = new Random(1);
			float[] A = rand(rnd, b * n * n), B = rand(rnd, b * n * n), C = new float[b * n * n];
			double t = time(() -> run3(ar, gemmB, A, B, C, new int[] { n, n, n, n * n, n * n }, (n + 15) / 16,
					(n + 15) / 16, b, 16, 16));
			System.out.printf("batch %-4d x n %-5d (work 2^%.1f): %8.1f us%n", b, n,
					Math.log((double) b * n * n * n) / Math.log(2), t);
		}
	}

	static void mapSweep(Arena ar, MemorySegment map) {
		System.out.printf("%nelement-wise f32 map, us per call, with copies%n");
		System.out.printf("%-10s %12s %12s%n", "n", "exp", "erf");
		for (int n : new int[] { 4096, 16384, 32768, 65536, 262144, 1048576, 1572864 }) {
			Random rnd = new Random(2);
			float[] X = rand(rnd, n), Y = new float[n];
			double e = time(() -> run2(ar, map, X, Y, new int[] { n, 0 }, (n + 255) / 256, 256));
			double f = time(() -> run2(ar, map, X, Y, new int[] { n, 11 }, (n + 255) / 256, 256));
			System.out.printf("%-10d %9.1f us %9.1f us%n", n, e, f);
		}
	}

	static void stridedSweep(Arena ar, MemorySegment bcast, MemorySegment gather, MemorySegment fold) {
		System.out.printf("%nstrided f32 tier, us per call, with copies%n");
		System.out.printf("%-10s %12s %12s %12s%n", "n", "bcast", "gather", "fold(amax)");
		for (int n : new int[] { 4096, 16384, 32768, 65536, 131072, 262144, 1048576 }) {
			Random rnd = new Random(4);
			int rows = n / 64;
			float[] A = rand(rnd, n), B = rand(rnd, rows), C = new float[n];
			int[] meta = { rows, 64, 1, 0, 64, 1, 0 };
			int[] m3 = new int[6];
			m3[0] = rows;
			m3[1] = 64;
			m3[2] = 1;
			m3[3] = 64;
			double bt = time(() -> runBcast(ar, bcast, A, B, C, new int[] { rows, 64, 1, 64, 1, 0 }, 1, n, 2));
			double gt = time(() -> runGather(ar, gather, A, C, new int[] { 64, rows, 1, rows }, n, 2));
			float[] F = new float[rows];
			double ft = time(() -> runFold(ar, fold, A, F, 1, rows, 64, 1));
			System.out.printf("%-10d %9.1f us %9.1f us %9.1f us%n", n, bt, gt, ft);
		}
	}

	// --- the harness: every run allocates its buffers and frees them, as the real path does ---
	static void run3(Arena ar, MemorySegment pso, float[] a, float[] b, float[] c, int[] args, int gx, int gy, int gz,
			int px, int py) {
		MemorySegment da = up(a), db = up(b), dc = Mtl.buffer(dev, c.length * 4L, Mtl.SHARED);
		MemorySegment ag = ar.allocate(Mtl.I, args.length);
		for (int i = 0; i < args.length; i++) ag.setAtIndex(Mtl.I, i, args[i]);
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, da, 0, 0);
		Mtl.setBuffer(enc, db, 0, 1);
		Mtl.setBuffer(enc, dc, 0, 2);
		Mtl.setBytes(enc, ag, args.length * 4L, 3);
		Mtl.dispatch(enc, Mtl.size(ar, gx, gy, gz), Mtl.size(ar, px, py, 1));
		Mtl.endEncoding(enc);
		Mtl.commitAndWait(cmd);
		down(dc, c);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
	}

	static void run2(Arena ar, MemorySegment pso, float[] a, float[] c, int[] args, int groups, int per) {
		MemorySegment da = up(a), dc = Mtl.buffer(dev, c.length * 4L, Mtl.SHARED);
		MemorySegment ag = ar.allocate(Mtl.I, args.length);
		for (int i = 0; i < args.length; i++) ag.setAtIndex(Mtl.I, i, args[i]);
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, da, 0, 0);
		Mtl.setBuffer(enc, dc, 0, 1);
		Mtl.setBytes(enc, ag, args.length * 4L, 2);
		Mtl.dispatch(enc, Mtl.size(ar, groups, 1, 1), Mtl.size(ar, per, 1, 1));
		Mtl.endEncoding(enc);
		Mtl.commitAndWait(cmd);
		down(dc, c);
		Mtl.release(da);
		Mtl.release(dc);
	}

	static void runBcast(Arena ar, MemorySegment pso, float[] a, float[] b, float[] c, int[] meta, int op, int n,
			int rank) {
		MemorySegment da = up(a), db = up(b), dc = Mtl.buffer(dev, c.length * 4L, Mtl.SHARED);
		MemorySegment dm = Mtl.buffer(dev, meta.length * 4L, Mtl.SHARED);
		MemorySegment cm = Mtl.contents(dm, meta.length * 4L);
		MemorySegment.copy(meta, 0, cm, Mtl.I, 0, meta.length);
		MemorySegment ag = ar.allocate(Mtl.I, 3);
		ag.setAtIndex(Mtl.I, 0, op);
		ag.setAtIndex(Mtl.I, 1, n);
		ag.setAtIndex(Mtl.I, 2, rank);
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, da, 0, 0);
		Mtl.setBuffer(enc, db, 0, 1);
		Mtl.setBuffer(enc, dc, 0, 2);
		Mtl.setBuffer(enc, dm, 0, 3);
		Mtl.setBytes(enc, ag, 12, 4);
		Mtl.dispatch(enc, Mtl.size(ar, (n + 255) / 256, 1, 1), Mtl.size(ar, 256, 1, 1));
		Mtl.endEncoding(enc);
		Mtl.commitAndWait(cmd);
		down(dc, c);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
		Mtl.release(dm);
	}

	static void runGather(Arena ar, MemorySegment pso, float[] a, float[] c, int[] meta, int n, int rank) {
		MemorySegment da = up(a), dc = Mtl.buffer(dev, c.length * 4L, Mtl.SHARED);
		MemorySegment dm = Mtl.buffer(dev, meta.length * 4L, Mtl.SHARED);
		MemorySegment.copy(meta, 0, Mtl.contents(dm, meta.length * 4L), Mtl.I, 0, meta.length);
		MemorySegment ag = ar.allocate(Mtl.I, 2);
		ag.setAtIndex(Mtl.I, 0, n);
		ag.setAtIndex(Mtl.I, 1, rank);
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, da, 0, 0);
		Mtl.setBuffer(enc, dc, 0, 1);
		Mtl.setBuffer(enc, dm, 0, 2);
		Mtl.setBytes(enc, ag, 8, 3);
		Mtl.dispatch(enc, Mtl.size(ar, (n + 255) / 256, 1, 1), Mtl.size(ar, 256, 1, 1));
		Mtl.endEncoding(enc);
		Mtl.commitAndWait(cmd);
		down(dc, c);
		Mtl.release(da);
		Mtl.release(dc);
		Mtl.release(dm);
	}

	static void runFold(Arena ar, MemorySegment pso, float[] a, float[] c, int op, int outer, int len, int inner) {
		MemorySegment da = up(a), dc = Mtl.buffer(dev, c.length * 4L, Mtl.SHARED);
		MemorySegment ag = ar.allocate(Mtl.I, 4);
		ag.setAtIndex(Mtl.I, 0, op);
		ag.setAtIndex(Mtl.I, 1, outer);
		ag.setAtIndex(Mtl.I, 2, len);
		ag.setAtIndex(Mtl.I, 3, inner);
		int total = outer * inner;
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, da, 0, 0);
		Mtl.setBuffer(enc, dc, 0, 1);
		Mtl.setBytes(enc, ag, 16, 2);
		Mtl.dispatch(enc, Mtl.size(ar, (total + 255) / 256, 1, 1), Mtl.size(ar, 256, 1, 1));
		Mtl.endEncoding(enc);
		Mtl.commitAndWait(cmd);
		down(dc, c);
		Mtl.release(da);
		Mtl.release(dc);
	}

	static void mps(Arena ar, float[] a, float[] b, float[] c, int M, int N, int K) {
		MemorySegment pool = null;
		try {
			pool = (MemorySegment) Mtl.poolPush.invokeExact();
			MemorySegment da = up(a), db = up(b), dc = Mtl.buffer(dev, c.length * 4L, Mtl.SHARED);
			MemorySegment ma = matrix(da, descriptor(M, K, rowBytesFor(K)));
			MemorySegment mb = matrix(db, descriptor(K, N, rowBytesFor(N)));
			MemorySegment mc = matrix(dc, descriptor(M, N, rowBytesFor(N)));
			MemorySegment mm = multiplication(M, N, K);
			MemorySegment cmd = Mtl.beginCommands(queue);
			Mtl.send(null, Mtl.P, Mtl.P, Mtl.P, Mtl.P).invokeExact(mm,
					Mtl.sel("encodeToCommandBuffer:leftMatrix:rightMatrix:resultMatrix:"), cmd, ma, mb, mc);
			Mtl.commitAndWait(cmd);
			down(dc, c);
			Mtl.release(ma);
			Mtl.release(mb);
			Mtl.release(mc);
			Mtl.release(mm);
			Mtl.release(da);
			Mtl.release(db);
			Mtl.release(dc);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
		finally {
			try {
				Mtl.poolPop.invokeExact(pool);
			}
			catch (Throwable ignored) {
			}
		}
	}

	static long rowBytesFor(int columns) {
		try {
			return (long) Mtl.send(Mtl.L, Mtl.L, Mtl.I).invokeExact(Mtl.cls("MPSMatrixDescriptor"),
					Mtl.sel("rowBytesFromColumns:dataType:"), (long) columns, MPS_FLOAT32);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static MemorySegment descriptor(int rows, int cols, long rowBytes) {
		try {
			return (MemorySegment) Mtl.send(Mtl.P, Mtl.L, Mtl.L, Mtl.L, Mtl.I).invokeExact(
					Mtl.cls("MPSMatrixDescriptor"), Mtl.sel("matrixDescriptorWithRows:columns:rowBytes:dataType:"),
					(long) rows, (long) cols, rowBytes, MPS_FLOAT32);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static MemorySegment matrix(MemorySegment buf, MemorySegment desc) {
		try {
			MemorySegment m = Mtl.msg(Mtl.cls("MPSMatrix"), "alloc");
			return (MemorySegment) Mtl.ID_PP.invokeExact(m, Mtl.sel("initWithBuffer:descriptor:"), buf, desc);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static MemorySegment multiplication(int rows, int cols, int interior) {
		try {
			MemorySegment m = Mtl.msg(Mtl.cls("MPSMatrixMultiplication"), "alloc");
			return (MemorySegment) Mtl
				.send(Mtl.P, Mtl.P, ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_BOOLEAN, Mtl.L, Mtl.L, Mtl.L, Mtl.D,
						Mtl.D)
				.invokeExact(m, Mtl.sel(
						"initWithDevice:transposeLeft:transposeRight:resultRows:resultColumns:interiorColumns:alpha:beta:"),
						dev, false, false, (long) rows, (long) cols, (long) interior, 1.0, 0.0);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static MemorySegment up(float[] a) {
		MemorySegment b = Mtl.buffer(dev, a.length * 4L, Mtl.SHARED);
		MemorySegment.copy(a, 0, Mtl.contents(b, a.length * 4L), Mtl.F, 0, a.length);
		return b;
	}

	static void down(MemorySegment b, float[] c) {
		MemorySegment.copy(Mtl.contents(b, c.length * 4L), Mtl.F, 0, c, 0, c.length);
	}

	static float[] rand(Random r, int n) {
		float[] a = new float[n];
		for (int i = 0; i < n; i++) a[i] = (float) r.nextGaussian();
		return a;
	}

	static double time(Runnable r) {
		for (int i = 0; i < 30; i++) r.run();
		double best = Double.MAX_VALUE;
		for (int round = 0; round < 3; round++) {
			int reps = 40;
			long t = System.nanoTime();
			for (int i = 0; i < reps; i++) r.run();
			best = Math.min(best, (System.nanoTime() - t) / 1e3 / reps);
		}
		return best;
	}

}
