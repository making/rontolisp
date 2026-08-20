import java.lang.foreign.*;

/**
 * Where is the crossover, and what does one intercepted call actually cost? Metal's
 * commit/wait round trip is the whole story here -- CUDA's was 8.1 us -- so this also asks
 * whether a busy poll on the command buffer's status beats waitUntilCompleted, and whether
 * batching several dispatches into ONE command buffer amortizes it (which is what phase 3
 * residency would get for free).
 */
public class MtlTiny {

	static MemorySegment dev, queue;

	public static void main(String[] x) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			dev = Mtl.device();
			MemorySegment lib = Mtl.library(dev, MtlSpike.SRC, MemorySegment.NULL);
			MemorySegment pso = Mtl.pipeline(dev, lib, "gemm_f32");
			queue = Mtl.queue(dev);

			System.out.println("one intercepted linalg:matmul, heap->buffer->kernel->heap, f32:");
			for (int[] s : new int[][] { { 8, 8, 8 }, { 32, 8, 8 }, { 32, 32, 32 }, { 64, 64, 64 }, { 128, 128, 128 },
					{ 256, 256, 256 } }) {
				roundTrip(ar, pso, s[0], s[1], s[2]);
			}

			System.out.println("\nwait strategy, empty-ish 16x16 dispatch:");
			waitStrategies(ar, pso);

			System.out.println("\nN dispatches inside ONE command buffer (16x16 each), us per dispatch:");
			for (int k : new int[] { 1, 2, 5, 10, 50 }) {
				double total = batched(ar, pso, k);
				System.out.printf("    %3d dispatches: %7.1f us total, %6.2f us each%n", k, total, total / k);
			}
		}
		Mtl.poolPop.invokeExact(pool);
	}

	static void roundTrip(Arena ar, MemorySegment pso, int M, int K, int N) {
		long ab = (long) M * K * 4, bb = (long) K * N * 4, cb = (long) M * N * 4;
		MemorySegment da = Mtl.buffer(dev, ab, Mtl.SHARED), db = Mtl.buffer(dev, bb, Mtl.SHARED),
				dc = Mtl.buffer(dev, cb, Mtl.SHARED);
		MemorySegment ca = Mtl.contents(da, ab), cbf = Mtl.contents(db, bb), cc = Mtl.contents(dc, cb);
		float[] A = new float[M * K], B = new float[K * N], C = new float[M * N];
		MemorySegment dims = ar.allocate(Mtl.I, 3);
		dims.setAtIndex(Mtl.I, 0, M);
		dims.setAtIndex(Mtl.I, 1, N);
		dims.setAtIndex(Mtl.I, 2, K);
		MemorySegment groups = Mtl.size(ar, (N + 15) / 16, (M + 15) / 16, 1), per = Mtl.size(ar, 16, 16, 1);
		double best = Double.MAX_VALUE;
		for (int r = 0; r < 300; r++) {
			long t = System.nanoTime();
			MemorySegment.copy(A, 0, ca, Mtl.F, 0, A.length);
			MemorySegment.copy(B, 0, cbf, Mtl.F, 0, B.length);
			MemorySegment cmd = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cmd, pso);
			Mtl.setBuffer(enc, da, 0, 0);
			Mtl.setBuffer(enc, db, 0, 1);
			Mtl.setBuffer(enc, dc, 0, 2);
			Mtl.setBytes(enc, dims, 12, 3);
			Mtl.dispatch(enc, groups, per);
			Mtl.endEncoding(enc);
			Mtl.commitAndWait(cmd);
			MemorySegment.copy(cc, Mtl.F, 0, C, 0, C.length);
			if (r > 30) best = Math.min(best, (System.nanoTime() - t) / 1e3);
		}
		System.out.printf("    %3dx%-3d @ %3dx%-3d  %6.1f us%n", M, K, K, N, best);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
	}

	static void waitStrategies(Arena ar, MemorySegment pso) {
		MemorySegment da = Mtl.buffer(dev, 1024, Mtl.SHARED), db = Mtl.buffer(dev, 1024, Mtl.SHARED),
				dc = Mtl.buffer(dev, 1024, Mtl.SHARED);
		MemorySegment dims = ar.allocate(Mtl.I, 3);
		for (int i = 0; i < 3; i++) dims.setAtIndex(Mtl.I, i, 16);
		MemorySegment groups = Mtl.size(ar, 1, 1, 1), per = Mtl.size(ar, 16, 16, 1);
		double blockWait = Double.MAX_VALUE, spin = Double.MAX_VALUE, encodeOnly = Double.MAX_VALUE;
		for (int r = 0; r < 400; r++) {
			long t = System.nanoTime();
			MemorySegment cmd = encode(ar, pso, da, db, dc, dims, groups, per);
			long t1 = System.nanoTime();
			Mtl.commitAndWait(cmd);
			if (r > 50) {
				blockWait = Math.min(blockWait, (System.nanoTime() - t) / 1e3);
				encodeOnly = Math.min(encodeOnly, (t1 - t) / 1e3);
			}
		}
		for (int r = 0; r < 400; r++) {
			long t = System.nanoTime();
			MemorySegment cmd = encode(ar, pso, da, db, dc, dims, groups, per);
			Mtl.commit(cmd);
			while (Mtl.msgLong(cmd, "status") < 4) { // MTLCommandBufferStatusCompleted == 4
				Thread.onSpinWait();
			}
			if (r > 50) spin = Math.min(spin, (System.nanoTime() - t) / 1e3);
		}
		System.out.printf("    encode only                %6.1f us%n", encodeOnly);
		System.out.printf("    encode + waitUntilCompleted %6.1f us%n", blockWait);
		System.out.printf("    encode + spin on status     %6.1f us%n", spin);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
	}

	static double batched(Arena ar, MemorySegment pso, int k) {
		MemorySegment da = Mtl.buffer(dev, 1024, Mtl.SHARED), db = Mtl.buffer(dev, 1024, Mtl.SHARED),
				dc = Mtl.buffer(dev, 1024, Mtl.SHARED);
		MemorySegment dims = ar.allocate(Mtl.I, 3);
		for (int i = 0; i < 3; i++) dims.setAtIndex(Mtl.I, i, 16);
		MemorySegment groups = Mtl.size(ar, 1, 1, 1), per = Mtl.size(ar, 16, 16, 1);
		double best = Double.MAX_VALUE;
		for (int r = 0; r < 200; r++) {
			long t = System.nanoTime();
			MemorySegment cmd = Mtl.beginCommands(queue);
			for (int i = 0; i < k; i++) {
				MemorySegment enc = Mtl.beginEncoder(cmd, pso);
				Mtl.setBuffer(enc, da, 0, 0);
				Mtl.setBuffer(enc, db, 0, 1);
				Mtl.setBuffer(enc, dc, 0, 2);
				Mtl.setBytes(enc, dims, 12, 3);
				Mtl.dispatch(enc, groups, per);
				Mtl.endEncoding(enc);
			}
			Mtl.commitAndWait(cmd);
			if (r > 30) best = Math.min(best, (System.nanoTime() - t) / 1e3);
		}
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
		return best;
	}

	static MemorySegment encode(Arena ar, MemorySegment pso, MemorySegment da, MemorySegment db, MemorySegment dc,
			MemorySegment dims, MemorySegment groups, MemorySegment per) {
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, da, 0, 0);
		Mtl.setBuffer(enc, db, 0, 1);
		Mtl.setBuffer(enc, dc, 0, 2);
		Mtl.setBytes(enc, dims, 12, 3);
		Mtl.dispatch(enc, groups, per);
		Mtl.endEncoding(enc);
		return cmd;
	}
}
