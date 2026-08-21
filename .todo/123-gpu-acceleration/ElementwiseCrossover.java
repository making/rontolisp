import java.lang.foreign.*;
import java.nio.file.*;

/**
 * Phase 4b's question: does an ELEMENT-WISE member pay for a round trip, and which ones?
 *
 * <p>
 * The todo assumed the whole tier is memory-bound and therefore worthless without device
 * residency. That is right for `add`/`mul` and wrong for the transcendentals, and this
 * probe is where the two halves are told apart. Every column is the SHIPPED route --
 * pooled allocation, critical heap copies, the checked-in PTX -- so a number here is
 * comparable with the ones `am.ik.gpu` itself produces.
 *
 * <p>
 * The CPU half is `elementwise-baseline.lisp` under `--simd` on the JVM, which must be
 * JIT-warm to be honest; `.kb/linalg-simd.md`'s own erf table is the same measurement one
 * layer up.
 *
 * <p>
 * The third table is phase 3's (device residency) input and the reason this probe is
 * worth keeping: the SAME kernel launched over buffers that are already on the device,
 * against the whole round trip. The gap between the two columns is what residency has to
 * win, per op, and it is what a chain of members pays over and over today.
 *
 * <pre>
 * nvcc -arch=compute_75 -ptx elementwise-probe.cu -o /tmp/ew.ptx
 * java --enable-native-access=ALL-UNNAMED ElementwiseCrossover.java /tmp/ew.ptx
 * </pre>
 */
public class ElementwiseCrossover {

	static final String[] OPS = { "exp", "log", "tanh", "erf", "sqrt", "sin" };

	static final String[] ZIPS = { "add", "mul" };

	static final int[] SIZES = { 4096, 16384, 65536, 262144, 1048576, 1572864, 4194304 };

	static MemorySegment mapF64, mapF32, zipF64, zipF32;

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		String path = args.length > 0 ? args[0] : CuLib.PTX;
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = Arena.global().allocate(CuLib.P);
			CuLib.ck((int) CuLib.cuModuleLoadData.invoke(out, a.allocateFrom(Files.readString(Path.of(path)))),
					"cuModuleLoadData " + path);
			CuLib.module = out.get(CuLib.P, 0);
		}
		mapF64 = CuLib.func("map_f64");
		mapF32 = CuLib.func("map_f32");
		zipF64 = CuLib.func("zip_f64");
		zipF32 = CuLib.func("zip_f32");

		System.out.printf("%n%-10s %10s", "unary", "n");
		for (String op : OPS) {
			System.out.printf(" %9s", op);
		}
		System.out.println();
		for (boolean f64 : new boolean[] { true, false }) {
			for (int n : SIZES) {
				System.out.printf("%-10s %10d", f64 ? "f64" : "f32", n);
				for (int op = 0; op < OPS.length; op++) {
					System.out.printf(" %9.1f", map(n, op, f64));
				}
				System.out.println();
			}
		}
		System.out.printf("%n%-10s %10s", "binary", "n");
		for (String op : ZIPS) {
			System.out.printf(" %9s", op);
		}
		System.out.println();
		for (boolean f64 : new boolean[] { true, false }) {
			for (int n : SIZES) {
				System.out.printf("%-10s %10d", f64 ? "f64" : "f32", n);
				System.out.printf(" %9.1f %9.1f", zip(n, 0, f64), zip(n, 2, f64));
				System.out.println();
			}
		}
		System.out.printf("%n%-10s %10s %12s %12s %8s%n", "residency", "n", "round trip", "resident", "ratio");
		for (boolean f64 : new boolean[] { true, false }) {
			for (int n : new int[] { 65536, 262144, 1572864 }) {
				for (int op = 0; op < OPS.length; op++) {
					double trip = map(n, op, f64), res = resident(n, op, f64);
					System.out.printf("%-10s %10d %12.1f %12.1f %8.1fx%n", (f64 ? "f64 " : "f32 ") + OPS[op], n, trip,
							res, trip / res);
				}
			}
		}
		System.out.println("\nus per call, whole round trip: allocate, copy up, launch, copy back, free.");
		System.out.println("'resident' is the same launch with the buffers already on the device: no");
		System.out.println("allocation and no copy, which is the cost a phase-3 resident chain would pay.");
	}

	/**
	 * The same kernel with its buffers ALREADY on the device -- allocated once, filled
	 * once, and launched over and over. This is what one link of a resident chain costs,
	 * and the difference from {@link #map} is what residency would remove.
	 */
	static double resident(int n, int op, boolean f64) throws Throwable {
		int width = f64 ? 8 : 4;
		long bytes = (long) n * width;
		MemorySegment kernel = f64 ? mapF64 : mapF32;
		try (Arena arena = Arena.ofConfined()) {
			long da = CuLib.alloc(arena, bytes, true), dc = CuLib.alloc(arena, bytes, true);
			double best = CuLib.best(n <= 262144 ? 400 : 100, () -> {
				launchMap(kernel, da, dc, n, op, arena);
				CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
			});
			CuLib.free(da, true);
			CuLib.free(dc, true);
			return best;
		}
	}

	/** One unary map over n elements, allocation and both copies included. */
	static double map(int n, int op, boolean f64) throws Throwable {
		int width = f64 ? 8 : 4;
		long bytes = (long) n * width;
		double[] a = new double[n], c = new double[n];
		float[] af = new float[n], cf = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = 0.25 + (i % 97) * 0.01;
			af[i] = (float) a[i];
		}
		MemorySegment sa = f64 ? MemorySegment.ofArray(a) : MemorySegment.ofArray(af);
		MemorySegment sc = f64 ? MemorySegment.ofArray(c) : MemorySegment.ofArray(cf);
		MemorySegment kernel = f64 ? mapF64 : mapF32;
		int reps = n <= 262144 ? 400 : 100;
		return CuLib.best(reps, () -> {
			try (Arena arena = Arena.ofConfined()) {
				long da = CuLib.alloc(arena, bytes, true), dc = CuLib.alloc(arena, bytes, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, sa, bytes), "htod");
				launchMap(kernel, da, dc, n, op, arena);
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(sc, dc, bytes), "dtoh");
				CuLib.free(da, true);
				CuLib.free(dc, true);
			}
		});
	}

	/** One binary zip over n elements -- THREE buffers and three copies, not two. */
	static double zip(int n, int op, boolean f64) throws Throwable {
		int width = f64 ? 8 : 4;
		long bytes = (long) n * width;
		double[] a = new double[n], b = new double[n], c = new double[n];
		float[] af = new float[n], bf = new float[n], cf = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = 0.25 + (i % 97) * 0.01;
			b[i] = 0.5 + (i % 31) * 0.01;
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		MemorySegment sa = f64 ? MemorySegment.ofArray(a) : MemorySegment.ofArray(af);
		MemorySegment sb = f64 ? MemorySegment.ofArray(b) : MemorySegment.ofArray(bf);
		MemorySegment sc = f64 ? MemorySegment.ofArray(c) : MemorySegment.ofArray(cf);
		MemorySegment kernel = f64 ? zipF64 : zipF32;
		int reps = n <= 262144 ? 400 : 100;
		return CuLib.best(reps, () -> {
			try (Arena arena = Arena.ofConfined()) {
				long da = CuLib.alloc(arena, bytes, true), db = CuLib.alloc(arena, bytes, true),
						dc = CuLib.alloc(arena, bytes, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, sa, bytes), "htod");
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(db, sb, bytes), "htod");
				launchZip(kernel, da, db, dc, n, op, arena);
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(sc, dc, bytes), "dtoh");
				CuLib.free(da, true);
				CuLib.free(db, true);
				CuLib.free(dc, true);
			}
		});
	}

	static void launchMap(MemorySegment f, long da, long dc, int n, int op, Arena a) throws Throwable {
		MemorySegment pa = a.allocate(CuLib.L), pc = a.allocate(CuLib.L);
		pa.set(CuLib.L, 0, da);
		pc.set(CuLib.L, 0, dc);
		MemorySegment count = a.allocate(CuLib.I), which = a.allocate(CuLib.I);
		count.set(CuLib.I, 0, n);
		which.set(CuLib.I, 0, op);
		MemorySegment params = a.allocate(CuLib.P, 4);
		params.setAtIndex(CuLib.P, 0, pa);
		params.setAtIndex(CuLib.P, 1, pc);
		params.setAtIndex(CuLib.P, 2, count);
		params.setAtIndex(CuLib.P, 3, which);
		CuLib.ck((int) CuLib.cuLaunchKernel.invoke(f, (n + 255) / 256, 1, 1, 256, 1, 1, 0, MemorySegment.NULL, params,
				MemorySegment.NULL), "cuLaunchKernel map");
	}

	static void launchZip(MemorySegment f, long da, long db, long dc, int n, int op, Arena a) throws Throwable {
		MemorySegment pa = a.allocate(CuLib.L), pb = a.allocate(CuLib.L), pc = a.allocate(CuLib.L);
		pa.set(CuLib.L, 0, da);
		pb.set(CuLib.L, 0, db);
		pc.set(CuLib.L, 0, dc);
		MemorySegment count = a.allocate(CuLib.I), which = a.allocate(CuLib.I);
		count.set(CuLib.I, 0, n);
		which.set(CuLib.I, 0, op);
		MemorySegment params = a.allocate(CuLib.P, 5);
		params.setAtIndex(CuLib.P, 0, pa);
		params.setAtIndex(CuLib.P, 1, pb);
		params.setAtIndex(CuLib.P, 2, pc);
		params.setAtIndex(CuLib.P, 3, count);
		params.setAtIndex(CuLib.P, 4, which);
		CuLib.ck((int) CuLib.cuLaunchKernel.invoke(f, (n + 255) / 256, 1, 1, 256, 1, 1, 0, MemorySegment.NULL, params,
				MemorySegment.NULL), "cuLaunchKernel zip");
	}
}
