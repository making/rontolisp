import java.lang.foreign.*;
import java.util.Random;

/**
 * The cuBLAS question, re-asked on Apple -- where it has a different answer, because
 * MetalPerformanceShaders is IN THE OS. There is no 660 MB toolkit to require and no new
 * dependency: MPSMatrixMultiplication is one more objc_msgSend away. So the only question left
 * is whether it is faster than the naive tiled kernel, and than Accelerate's CPU sgemm.
 */
public class MtlMps {

	static final SymbolLookup MPS = SymbolLookup.libraryLookup(
			"/System/Library/Frameworks/MetalPerformanceShaders.framework/MetalPerformanceShaders", Arena.global());

	static final int MPS_FLOAT32 = 0x10000000 | 32;

	static MemorySegment dev, queue;


	public static void main(String[] x) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			dev = Mtl.device();
			queue = Mtl.queue(dev);
			MemorySegment lib = Mtl.library(dev, MtlSpike.SRC, MemorySegment.NULL);
			MemorySegment ours = Mtl.pipeline(dev, lib, "gemm_f32");
			System.out.println("MPSMatrixMultiplication class = " + (Mtl.cls("MPSMatrixMultiplication").address() != 0));
			System.out.printf("%nf32 n x n gemm, ms per call%n%-6s %14s %14s %14s %14s%n", "n", "ours resident",
					"MPS resident", "ours + copy", "MPS + copy");
			for (int n : new int[] { 128, 256, 512, 1024, 2048 }) {
				bench(ar, ours, n);
			}
		}
		Mtl.poolPop.invokeExact(pool);
	}

	static void bench(Arena ar, MemorySegment ours, int n) {
		long bytes = (long) n * n * 4;
		MemorySegment da = Mtl.buffer(dev, bytes, Mtl.SHARED), db = Mtl.buffer(dev, bytes, Mtl.SHARED),
				dc = Mtl.buffer(dev, bytes, Mtl.SHARED);
		Random rnd = new Random(3);
		float[] A = new float[n * n], B = new float[n * n], C = new float[n * n], Cm = new float[n * n];
		for (int i = 0; i < n * n; i++) {
			A[i] = (float) rnd.nextGaussian();
			B[i] = (float) rnd.nextGaussian();
		}
		MemorySegment ca = Mtl.contents(da, bytes), cb = Mtl.contents(db, bytes), cc = Mtl.contents(dc, bytes);
		MemorySegment.copy(A, 0, ca, Mtl.F, 0, A.length);
		MemorySegment.copy(B, 0, cb, Mtl.F, 0, B.length);

		MemorySegment dims = ar.allocate(Mtl.I, 3);
		for (int i = 0; i < 3; i++) dims.setAtIndex(Mtl.I, i, n);
		MemorySegment g = Mtl.size(ar, (n + 15) / 16, (n + 15) / 16, 1), p = Mtl.size(ar, 16, 16, 1);

		long rowBytes = rowBytesFor(n);
		MemorySegment desc = descriptor(n, n, rowBytes);
		MemorySegment ma = matrix(da, desc), mb = matrix(db, desc), mc = matrix(dc, desc);
		MemorySegment mm = multiplication(n, n, n);

		double oursRes = time(20, () -> {
			MemorySegment cmd = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cmd, ours);
			Mtl.setBuffer(enc, da, 0, 0);
			Mtl.setBuffer(enc, db, 0, 1);
			Mtl.setBuffer(enc, dc, 0, 2);
			Mtl.setBytes(enc, dims, 12, 3);
			Mtl.dispatch(enc, g, p);
			Mtl.endEncoding(enc);
			Mtl.commitAndWait(cmd);
		});
		MemorySegment.copy(cc, Mtl.F, 0, C, 0, C.length);

		double mpsRes = time(20, () -> {
			MemorySegment cmd = Mtl.beginCommands(queue);
			encode(mm, cmd, ma, mb, mc);
			Mtl.commitAndWait(cmd);
		});
		MemorySegment.copy(cc, Mtl.F, 0, Cm, 0, Cm.length);

		double oursCopy = time(20, () -> {
			MemorySegment.copy(A, 0, ca, Mtl.F, 0, A.length);
			MemorySegment.copy(B, 0, cb, Mtl.F, 0, B.length);
			MemorySegment cmd = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cmd, ours);
			Mtl.setBuffer(enc, da, 0, 0);
			Mtl.setBuffer(enc, db, 0, 1);
			Mtl.setBuffer(enc, dc, 0, 2);
			Mtl.setBytes(enc, dims, 12, 3);
			Mtl.dispatch(enc, g, p);
			Mtl.endEncoding(enc);
			Mtl.commitAndWait(cmd);
			MemorySegment.copy(cc, Mtl.F, 0, C, 0, C.length);
		});
		double mpsCopy = time(20, () -> {
			MemorySegment.copy(A, 0, ca, Mtl.F, 0, A.length);
			MemorySegment.copy(B, 0, cb, Mtl.F, 0, B.length);
			MemorySegment cmd = Mtl.beginCommands(queue);
			encode(mm, cmd, ma, mb, mc);
			Mtl.commitAndWait(cmd);
			MemorySegment.copy(cc, Mtl.F, 0, Cm, 0, Cm.length);
		});

		double maxRel = 0, scale = 0;
		for (float v : C) scale = Math.max(scale, Math.abs(v));
		for (int i = 0; i < C.length; i++) maxRel = Math.max(maxRel, Math.abs(C[i] - Cm[i]) / scale);
		System.out.printf("%-6d %11.3f ms %11.3f ms %11.3f ms %11.3f ms   (MPS %.1fx ours; agree to %.2g)%n", n,
				oursRes, mpsRes, oursCopy, mpsCopy, oursRes / mpsRes, maxRel);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
	}

	static double time(int reps, Runnable r) {
		double best = Double.MAX_VALUE;
		for (int i = 0; i < reps + 5; i++) {
			long t = System.nanoTime();
			r.run();
			if (i >= 5) best = Math.min(best, (System.nanoTime() - t) / 1e6);
		}
		return best;
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

	static void encode(MemorySegment mm, MemorySegment cmd, MemorySegment a, MemorySegment b, MemorySegment c) {
		try {
			Mtl.send(null, Mtl.P, Mtl.P, Mtl.P, Mtl.P).invokeExact(mm,
					Mtl.sel("encodeToCommandBuffer:leftMatrix:rightMatrix:resultMatrix:"), cmd, a, b, c);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}
}
