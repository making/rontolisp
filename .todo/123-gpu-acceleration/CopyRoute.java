import java.lang.foreign.*;

/**
 * Does {@code Linker.Option.critical} remove the host copy on the GPU path too, and what
 * bounds the window in which the thread cannot reach a safepoint?
 *
 * <p>
 * todo-123 was written before `--blas` found that a critical downcall takes a HEAP
 * segment, and still calls the heap -> native copy "unavoidable in every row". This is the
 * measurement that says otherwise, in the shape the shipped library uses: pooled
 * allocation, three buffers a call, both operands and the result moved either straight off
 * the Java heap or through a confined arena.
 *
 * <p>
 * The second block is the part that has no CPU analogue: a device-to-host copy on the null
 * stream also WAITS for the kernel, so a critical one issued straight after a launch holds
 * the thread off a safepoint for the kernel's whole runtime. That is what `.kb/gpu.md`'s
 * SYNC_FLOPS_PER_MULTIPROCESSOR is for.
 */
public class CopyRoute {

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		System.out.println("\n-- one f64 product end to end, allocation included --");
		System.out.printf("    %-6s %16s %16s %8s%n", "n", "staged us", "critical us", "ratio");
		for (int n : new int[] { 8, 32, 64, 128, 256, 512, 1024, 2048 }) {
			double staged = product(n, false), critical = product(n, true);
			System.out.printf("    %-6d %16.1f %16.1f %8.2f%n", n, staged, critical, staged / critical);
		}

		System.out.println("\n-- how long one CRITICAL call holds the thread off a safepoint --");
		System.out.printf("    %-6s %10s %16s %20s %18s%n", "n", "MB/op", "HtoD crit us", "DtoH after launch",
				"DtoH after sync");
		for (int n : new int[] { 128, 256, 512, 1024, 2048, 4096 }) {
			window(n);
		}
	}

	/** The whole intercepted product, one route or the other. */
	static double product(int n, boolean critical) throws Throwable {
		double[] A = new double[n * n], B = new double[n * n], C = new double[n * n];
		for (int i = 0; i < A.length; i++) {
			A[i] = (i % 13) * 0.125;
			B[i] = (i % 7) * 0.25;
		}
		long bytes = (long) n * n * 8;
		int reps = n <= 256 ? 200 : (n <= 512 ? 60 : 20);
		double best = CuLib.best(reps, () -> {
			try (Arena a = Arena.ofConfined()) {
				long da = CuLib.alloc(a, bytes, true), db = CuLib.alloc(a, bytes, true),
						dc = CuLib.alloc(a, bytes, true);
				if (critical) {
					CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, MemorySegment.ofArray(A), bytes), "htod");
					CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(db, MemorySegment.ofArray(B), bytes), "htod");
					CuLib.launch(CuLib.gemmF64, da, db, dc, n, n, n, a);
					CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(C), dc, bytes), "dtoh");
				}
				else {
					MemorySegment ha = a.allocate(bytes), hb = a.allocate(bytes), hc = a.allocate(bytes);
					MemorySegment.copy(MemorySegment.ofArray(A), 0, ha, 0, bytes);
					MemorySegment.copy(MemorySegment.ofArray(B), 0, hb, 0, bytes);
					CuLib.ck((int) CuLib.cuMemcpyHtoD.invoke(da, ha, bytes), "htod");
					CuLib.ck((int) CuLib.cuMemcpyHtoD.invoke(db, hb, bytes), "htod");
					CuLib.launch(CuLib.gemmF64, da, db, dc, n, n, n, a);
					CuLib.ck((int) CuLib.cuMemcpyDtoH.invoke(hc, dc, bytes), "dtoh");
					MemorySegment.copy(hc, 0, MemorySegment.ofArray(C), 0, bytes);
				}
				CuLib.free(da, true);
				CuLib.free(db, true);
				CuLib.free(dc, true);
			}
		});
		double acc = 0;
		for (int k = 0; k < n; k++) acc += A[k] * B[k * n];
		if (Math.abs(acc - C[0]) > 1e-9) throw new AssertionError("wrong C[0] at n=" + n);
		return best;
	}

	/** The duration of each individual critical call, which is the safepoint window. */
	static void window(int n) throws Throwable {
		double[] A = new double[n * n], C = new double[n * n];
		long bytes = (long) n * n * 8;
		try (Arena a = Arena.ofConfined()) {
			long da = CuLib.alloc(a, bytes, true), db = CuLib.alloc(a, bytes, true), dc = CuLib.alloc(a, bytes, true);
			int reps = n <= 512 ? 60 : 20;
			double htod = 1e18, afterLaunch = 1e18, afterSync = 1e18;
			for (int r = 0; r < reps; r++) {
				long t1 = System.nanoTime();
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, MemorySegment.ofArray(A), bytes), "htod");
				long t2 = System.nanoTime();
				CuLib.launch(CuLib.gemmF64, da, db, dc, n, n, n, a);
				long t3 = System.nanoTime();
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(C), dc, bytes), "dtoh");
				long t4 = System.nanoTime();
				CuLib.launch(CuLib.gemmF64, da, db, dc, n, n, n, a);
				CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
				long t5 = System.nanoTime();
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(C), dc, bytes), "dtoh");
				long t6 = System.nanoTime();
				if (r > reps / 5) {
					htod = Math.min(htod, (t2 - t1) / 1e3);
					afterLaunch = Math.min(afterLaunch, (t4 - t3) / 1e3);
					afterSync = Math.min(afterSync, (t6 - t5) / 1e3);
				}
			}
			System.out.printf("    %-6d %10.1f %16.1f %20.1f %18.1f%n", n, bytes / 1e6, htod, afterLaunch, afterSync);
			CuLib.free(da, true);
			CuLib.free(db, true);
			CuLib.free(dc, true);
		}
	}
}
