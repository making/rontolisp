import java.lang.foreign.*;
import java.nio.file.*;
import java.util.Random;

/**
 * Where the per-call microseconds go on Metal, which is the question phase 5's design
 * turns on: the CUDA half can allocate per call because the driver has a POOL, and Metal
 * has none. Measures the MPS object-creation cost against a cached one, and a pooled
 * MTLBuffer against a fresh one.
 *
 * java --enable-native-access=ALL-UNNAMED .todo/123-gpu-acceleration/MtlBreakdown.java
 */
public class MtlBreakdown {

	static final SymbolLookup MPS = SymbolLookup.libraryLookup(
			"/System/Library/Frameworks/MetalPerformanceShaders.framework/MetalPerformanceShaders", Arena.global());

	static final int MPS_FLOAT32 = 0x10000000 | 32;

	static MemorySegment dev, queue;

	public static void main(String[] a) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			dev = Mtl.device();
			queue = Mtl.queue(dev);
			String src = Files.readString(Path.of("src/main/resources/am/ik/gpu/gemm.metal"));
			MemorySegment lib = Mtl.library(dev, src, MemorySegment.NULL);
			MemorySegment gemmB = Mtl.pipeline(dev, lib, "gemm_batched_f32");

			// object creation, in isolation
			int n = 256;
			MemorySegment buf = Mtl.buffer(dev, n * n * 4L, Mtl.SHARED);
			long rb = rowBytesFor(n);
			System.out.printf("descriptor:      %.2f us%n", best(2000, () -> descriptor(n, n, rb)));
			MemorySegment desc = descriptor(n, n, rb);
			System.out.printf("matrix:          %.2f us%n", best(2000, () -> matrix(buf, desc)));
			System.out.printf("multiplication:  %.2f us%n", best(500, () -> multiplication(n, n, n)));
			System.out.printf("autorelease pool push+pop: %.2f us%n", best(20000, () -> {
				try {
					MemorySegment p = (MemorySegment) Mtl.poolPush.invokeExact();
					Mtl.poolPop.invokeExact(p);
				}
				catch (Throwable t) {
					throw new RuntimeException(t);
				}
				return null;
			}));

			rect(ar, gemmB);
			System.out.printf("%n%-6s %12s %12s %12s %12s%n", "n", "mps fresh", "mps cached", "ours fresh",
					"ours pooled");
			for (int sz : new int[] { 128, 256, 320, 384, 448, 512, 768, 1024 }) {
				row(ar, gemmB, sz);
			}
		}
		Mtl.poolPop.invokeExact(pool);
	}

	/** Does MPS agree with our kernel when the row is NOT a multiple of 16 floats? */
	static void rect(Arena ar, MemorySegment gemmB) {
		int M = 37, K = 23, N = 19;
		Random rnd = new Random(5);
		float[] A = rand(rnd, M * K), B = rand(rnd, K * N), C = new float[M * N], D = new float[M * N];
		System.out.printf("rowBytesFromColumns(%d) = %d, cols*4 = %d%n", N, rowBytesFor(N), N * 4);
		MemorySegment da = up(A), db = up(B), dc = Mtl.buffer(dev, M * N * 4L, Mtl.SHARED);
		MemorySegment ma = matrix(da, descriptor(M, K, K * 4L)), mb = matrix(db, descriptor(K, N, N * 4L)),
				mc = matrix(dc, descriptor(M, N, N * 4L));
		MemorySegment mm = multiplication(M, N, K);
		MemorySegment cmd = Mtl.beginCommands(queue);
		encode(mm, cmd, ma, mb, mc);
		Mtl.commitAndWait(cmd);
		down(dc, C);
		MemorySegment args = ar.allocate(Mtl.I, 5);
		int[] dims = { M, N, K, M * K, K * N };
		for (int i = 0; i < 5; i++) args.setAtIndex(Mtl.I, i, dims[i]);
		dispatch(gemmB, da, db, dc, args, Mtl.size(ar, (N + 15) / 16, (M + 15) / 16, 1), Mtl.size(ar, 16, 16, 1));
		down(dc, D);
		int bad = 0;
		double worst = 0;
		for (int i = 0; i < M * N; i++) {
			if (Float.floatToRawIntBits(C[i]) != Float.floatToRawIntBits(D[i])) bad++;
			worst = Math.max(worst, Math.abs(C[i] - D[i]));
		}
		System.out.printf("rectangular 37x23x19 with rowBytes = cols*4: MPS vs ours, %d of %d cells differ, worst %.3g%n",
				bad, M * N, worst);
	}

	static void row(Arena ar, MemorySegment gemmB, int n) {
		Random rnd = new Random(1);
		float[] A = rand(rnd, n * n), B = rand(rnd, n * n), C = new float[n * n];
		long bytes = n * n * 4L;

		double fresh = time(() -> {
			MemorySegment p = push();
			MemorySegment da = up(A), db = up(B), dc = Mtl.buffer(dev, bytes, Mtl.SHARED);
			long rb = rowBytesFor(n);
			MemorySegment ma = matrix(da, descriptor(n, n, rb)), mb = matrix(db, descriptor(n, n, rb)),
					mc = matrix(dc, descriptor(n, n, rb));
			MemorySegment mm = multiplication(n, n, n);
			MemorySegment cmd = Mtl.beginCommands(queue);
			encode(mm, cmd, ma, mb, mc);
			Mtl.commitAndWait(cmd);
			down(dc, C);
			Mtl.release(ma);
			Mtl.release(mb);
			Mtl.release(mc);
			Mtl.release(mm);
			Mtl.release(da);
			Mtl.release(db);
			Mtl.release(dc);
			pop(p);
			return null;
		});

		// everything but the copies hoisted, which is what a per-shape cache buys
		MemorySegment da = Mtl.buffer(dev, bytes, Mtl.SHARED), db = Mtl.buffer(dev, bytes, Mtl.SHARED),
				dc = Mtl.buffer(dev, bytes, Mtl.SHARED);
		long rb = rowBytesFor(n);
		MemorySegment ma = matrix(da, descriptor(n, n, rb)), mb = matrix(db, descriptor(n, n, rb)),
				mc = matrix(dc, descriptor(n, n, rb));
		MemorySegment mm = multiplication(n, n, n);
		MemorySegment ca = Mtl.contents(da, bytes), cbb = Mtl.contents(db, bytes), cc = Mtl.contents(dc, bytes);
		double cached = time(() -> {
			MemorySegment p = push();
			MemorySegment.copy(A, 0, ca, Mtl.F, 0, A.length);
			MemorySegment.copy(B, 0, cbb, Mtl.F, 0, B.length);
			MemorySegment cmd = Mtl.beginCommands(queue);
			encode(mm, cmd, ma, mb, mc);
			Mtl.commitAndWait(cmd);
			MemorySegment.copy(cc, Mtl.F, 0, C, 0, C.length);
			pop(p);
			return null;
		});

		MemorySegment args = Arena.global().allocate(Mtl.I, 5);
		int[] dims = { n, n, n, n * n, n * n };
		for (int i = 0; i < 5; i++) args.setAtIndex(Mtl.I, i, dims[i]);
		MemorySegment g = Mtl.size(Arena.global(), (n + 15) / 16, (n + 15) / 16, 1), pg = Mtl.size(Arena.global(), 16,
				16, 1);
		double oursFresh = time(() -> {
			MemorySegment p = push();
			MemorySegment xa = up(A), xb = up(B), xc = Mtl.buffer(dev, bytes, Mtl.SHARED);
			dispatch(gemmB, xa, xb, xc, args, g, pg);
			down(xc, C);
			Mtl.release(xa);
			Mtl.release(xb);
			Mtl.release(xc);
			pop(p);
			return null;
		});
		double oursPooled = time(() -> {
			MemorySegment p = push();
			MemorySegment.copy(A, 0, ca, Mtl.F, 0, A.length);
			MemorySegment.copy(B, 0, cbb, Mtl.F, 0, B.length);
			dispatch(gemmB, da, db, dc, args, g, pg);
			MemorySegment.copy(cc, Mtl.F, 0, C, 0, C.length);
			pop(p);
			return null;
		});
		System.out.printf("%-6d %9.1f us %9.1f us %9.1f us %9.1f us%n", n, fresh, cached, oursFresh, oursPooled);
		Mtl.release(ma);
		Mtl.release(mb);
		Mtl.release(mc);
		Mtl.release(mm);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
	}

	static void dispatch(MemorySegment pso, MemorySegment a, MemorySegment b, MemorySegment c, MemorySegment args,
			MemorySegment g, MemorySegment p) {
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, a, 0, 0);
		Mtl.setBuffer(enc, b, 0, 1);
		Mtl.setBuffer(enc, c, 0, 2);
		Mtl.setBytes(enc, args, 20, 3);
		Mtl.dispatch(enc, g, p);
		Mtl.endEncoding(enc);
		Mtl.commitAndWait(cmd);
	}

	static MemorySegment push() {
		try {
			return (MemorySegment) Mtl.poolPush.invokeExact();
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void pop(MemorySegment p) {
		try {
			Mtl.poolPop.invokeExact(p);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void encode(MemorySegment mm, MemorySegment cmd, MemorySegment a, MemorySegment b, MemorySegment c) {
		try {
			Mtl.send(null, Mtl.P, Mtl.P, Mtl.P, Mtl.P).invokeExact(mm,
					Mtl.sel("encodeToCommandBuffer:leftMatrix:rightMatrix:resultMatrix:"), cmd, a, b, c);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
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

	interface Thunk {

		Object run();

	}

	static double best(int reps, Thunk t) {
		double b = Double.MAX_VALUE;
		for (int i = 0; i < reps; i++) {
			long s = System.nanoTime();
			t.run();
			if (i > reps / 5) b = Math.min(b, (System.nanoTime() - s) / 1e3);
		}
		return b;
	}

	static double time(Thunk r) {
		for (int i = 0; i < 30; i++) r.run();
		double best = Double.MAX_VALUE;
		for (int round = 0; round < 3; round++) {
			long t = System.nanoTime();
			for (int i = 0; i < 40; i++) r.run();
			best = Math.min(best, (System.nanoTime() - t) / 1e3 / 40);
		}
		return best;
	}

}
