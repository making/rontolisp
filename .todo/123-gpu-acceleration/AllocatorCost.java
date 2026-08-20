import java.lang.foreign.*;

/**
 * What does per-call device memory cost, and what does that do to the floor of an
 * intercepted product?
 *
 * <p>
 * The question the ORIGINAL spike probes never asked: every one of them allocated its
 * buffers once and then looped, so the 16-18 us floor they report excludes allocation
 * entirely. A per-call intercept cannot do that. This measures the pair in isolation, the
 * pair inside a steady product loop, and the resulting floor both ways -- which is what
 * `.kb/gpu.md` sets the unpooled size threshold from.
 *
 * <p>
 * It also measures what a FAILED pooled allocation costs the device, which is the reason
 * the shipped library pre-flights against free memory and trims the pool afterwards.
 */
public class AllocatorCost {

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		try (Arena a = Arena.ofConfined()) {
			System.out.println("\n-- one allocate + free pair, in isolation --");
			for (long bytes : new long[] { 4096, 1 << 20, 8L << 20 }) {
				double pooled = CuLib.best(500, () -> {
					long p = CuLib.alloc(a, bytes, true);
					CuLib.free(p, true);
				});
				double plain = CuLib.best(200, () -> {
					long p = CuLib.alloc(a, bytes, false);
					CuLib.free(p, false);
				});
				System.out.printf("    %9d B   cuMemAllocAsync %6.2f us | cuMemAlloc %8.2f us  (%.0fx)%n", bytes,
						pooled, plain, plain / pooled);
			}

			System.out.println("\n-- a whole f64 product, both allocators, three buffers a call --");
			System.out.printf("    %-6s %14s %14s %10s %14s%n", "n", "pooled us", "unpooled us", "ratio",
					"per pair us");
			for (int n : new int[] { 64, 128, 256, 512 }) {
				double pooled = product(a, n, true);
				double plain = product(a, n, false);
				System.out.printf("    %-6d %14.1f %14.1f %10.1f %14.1f%n", n, pooled, plain, plain / pooled,
						(plain - pooled) / 3);
			}

			System.out.println("\n-- what a FAILED pooled allocation costs the device --");
			long free = CuLib.freeBytes();
			System.out.printf("    free before                              %6d MB%n", free >> 20);
			long huge = free / 2 + (8L << 30);
			long first = CuLib.alloc(a, huge, true);
			int second = (int) CuLib.cuMemAllocAsync.invoke(a.allocate(CuLib.L), huge, MemorySegment.NULL);
			System.out.printf("    two %d MB requests: second -> CUresult %d%n", huge >> 20, second);
			System.out.printf("    free with the first still held           %6d MB%n", CuLib.freeBytes() >> 20);
			CuLib.free(first, true);
			System.out.printf("    free after cuMemFreeAsync, no trim       %6d MB%n", CuLib.freeBytes() >> 20);
			CuLib.ck((int) CuLib.cuMemPoolTrimTo.invoke(CuLib.pool, 0L), "trim");
			System.out.printf("    free after cuMemPoolTrimTo, NO sync      %6d MB%n", CuLib.freeBytes() >> 20);
			CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
			CuLib.ck((int) CuLib.cuMemPoolTrimTo.invoke(CuLib.pool, 0L), "trim");
			System.out.printf("    free after cuCtxSynchronize + trim       %6d MB%n", CuLib.freeBytes() >> 20);
		}
	}

	/** One intercepted product, allocation included, exactly as the library does it. */
	static double product(Arena outer, int n, boolean pooled) throws Throwable {
		double[] A = new double[n * n], B = new double[n * n], C = new double[n * n];
		for (int i = 0; i < A.length; i++) {
			A[i] = (i % 13) * 0.125;
			B[i] = (i % 7) * 0.25;
		}
		long bytes = (long) n * n * 8;
		int reps = n <= 256 ? 200 : 60;
		return CuLib.best(reps, () -> {
			try (Arena a = Arena.ofConfined()) {
				long da = CuLib.alloc(a, bytes, pooled), db = CuLib.alloc(a, bytes, pooled),
						dc = CuLib.alloc(a, bytes, pooled);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, MemorySegment.ofArray(A), bytes), "htod");
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(db, MemorySegment.ofArray(B), bytes), "htod");
				CuLib.launch(CuLib.gemmF64, da, db, dc, n, n, n, a);
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(C), dc, bytes), "dtoh");
				CuLib.free(da, pooled);
				CuLib.free(db, pooled);
				CuLib.free(dc, pooled);
			}
		});
	}
}
