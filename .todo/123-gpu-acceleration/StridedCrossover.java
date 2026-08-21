// Phase 3's question: the members a training step still spends its time on are a
// BROADCAST binary op, an AXIS fold and an axes TRANSPOSE -- and their CPU twins are
// SCALAR ODOMETER walks (.kb/linalg-simd.md says so), not the lane loops phase 4b
// measured `add` and `sqrt` at. So 4b's refusal of the binary ops was a refusal at EQUAL
// shapes and says nothing about these. This measures the device side of all three,
// through the shipped route (checked-in PTX, pooled per-call allocation, critical copies,
// one launch), at the shapes `train-gpt-soseki.lisp` produces at the notebook's own
// (block 256, n-embd 384, n-head 2) and at a size sweep for the threshold.
//
// Pair it column by column with `shaped-baseline.lisp`, which is the CPU half.
//
//   java --enable-native-access=ALL-UNNAMED StridedCrossover.java
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class StridedCrossover {

	static final ValueLayout.OfInt I = CuLib.I;
	static final ValueLayout.OfLong L = CuLib.L;

	static MemorySegment bcastF32, bcastF64, gatherF32, gatherF64, foldF32, foldF64;

	/** The device buffer holding [dims..., strideA..., strideB...], all in elements. */
	static long meta(Arena a, int[] dims, int[] sa, int[] sb) throws Throwable {
		int rank = dims.length;
		int words = rank * (sb == null ? 2 : 3);
		long d = CuLib.alloc(a, (long) words * Integer.BYTES, true);
		MemorySegment host = a.allocate(I, words);
		for (int k = 0; k < rank; k++) {
			host.setAtIndex(I, k, dims[k]);
			host.setAtIndex(I, rank + k, sa[k]);
			if (sb != null) host.setAtIndex(I, 2 * rank + k, sb[k]);
		}
		CuLib.ck((int) CuLib.cuMemcpyHtoD.invoke(d, host, (long) words * Integer.BYTES), "meta HtoD");
		return d;
	}

	/** Row-major strides of a dims-d operand aligned to od, 0 on every stretched axis. */
	static int[] strides(int[] d, int[] od) {
		int[] s = new int[od.length];
		int acc = 1;
		for (int k = od.length - 1, i = d.length - 1; k >= 0; k--, i--) {
			int n = i >= 0 ? d[i] : 1;
			s[k] = n == 1 ? 0 : acc;
			acc *= n;
		}
		return s;
	}

	static int total(int[] d) {
		int n = 1;
		for (int x : d) n *= x;
		return n;
	}

	static void launch1d(MemorySegment f, int n, MemorySegment[] args, Arena a) throws Throwable {
		MemorySegment params = a.allocate(CuLib.P, args.length);
		for (int i = 0; i < args.length; i++) params.setAtIndex(CuLib.P, i, args[i]);
		CuLib.ck((int) CuLib.cuLaunchKernel.invoke(f, (n + 255) / 256, 1, 1, 256, 1, 1, 0, MemorySegment.NULL, params,
				MemorySegment.NULL), "cuLaunchKernel");
	}

	static MemorySegment pl(Arena a, long v) {
		MemorySegment s = a.allocate(L);
		s.set(L, 0, v);
		return s;
	}

	static MemorySegment pi(Arena a, int v) {
		MemorySegment s = a.allocate(I);
		s.set(I, 0, v);
		return s;
	}

	/** One whole broadcast binary round trip: two uploads, one launch, one download. */
	static double bcast(int op, int[] dx, int[] dy, boolean f32, int reps) throws Throwable {
		int[] od = dx.length >= dy.length ? dx : dy;
		int n = total(od), nx = total(dx), ny = total(dy);
		int w = f32 ? Float.BYTES : Double.BYTES;
		Object hx = f32 ? new float[nx] : new double[nx];
		Object hy = f32 ? new float[ny] : new double[ny];
		Object hc = f32 ? new float[n] : new double[n];
		MemorySegment sx = f32 ? MemorySegment.ofArray((float[]) hx) : MemorySegment.ofArray((double[]) hx);
		MemorySegment sy = f32 ? MemorySegment.ofArray((float[]) hy) : MemorySegment.ofArray((double[]) hy);
		MemorySegment sc = f32 ? MemorySegment.ofArray((float[]) hc) : MemorySegment.ofArray((double[]) hc);
		int[] sax = strides(dx, od), sby = strides(dy, od);
		return CuLib.best(reps, () -> {
			try (Arena a = Arena.ofConfined()) {
				long da = CuLib.alloc(a, (long) nx * w, true), db = CuLib.alloc(a, (long) ny * w, true),
						dc = CuLib.alloc(a, (long) n * w, true);
				long dm = meta(a, od, sax, sby);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, sx, (long) nx * w), "HtoD a");
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(db, sy, (long) ny * w), "HtoD b");
				launch1d(f32 ? bcastF32 : bcastF64, n, new MemorySegment[] { pi(a, op), pl(a, da), pl(a, db), pl(a, dc),
						pi(a, n), pi(a, od.length), pl(a, dm) }, a);
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(sc, dc, (long) n * w), "DtoH");
				CuLib.free(da, true);
				CuLib.free(db, true);
				CuLib.free(dc, true);
				CuLib.free(dm, true);
			}
		});
	}

	/** One whole axis-fold round trip. The result is outer x inner, so the way back is tiny. */
	static double fold(int op, int outer, int len, int inner, boolean f32, int reps) throws Throwable {
		int n = outer * len * inner, out = outer * inner;
		int w = f32 ? Float.BYTES : Double.BYTES;
		Object ha = f32 ? new float[n] : new double[n];
		Object hc = f32 ? new float[out] : new double[out];
		MemorySegment sa = f32 ? MemorySegment.ofArray((float[]) ha) : MemorySegment.ofArray((double[]) ha);
		MemorySegment sc = f32 ? MemorySegment.ofArray((float[]) hc) : MemorySegment.ofArray((double[]) hc);
		return CuLib.best(reps, () -> {
			try (Arena a = Arena.ofConfined()) {
				long da = CuLib.alloc(a, (long) n * w, true), dc = CuLib.alloc(a, (long) out * w, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, sa, (long) n * w), "HtoD a");
				launch1d(f32 ? foldF32 : foldF64, out,
						new MemorySegment[] { pi(a, op), pl(a, da), pl(a, dc), pi(a, outer), pi(a, len), pi(a, inner) },
						a);
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(sc, dc, (long) out * w), "DtoH");
				CuLib.free(da, true);
				CuLib.free(dc, true);
			}
		});
	}

	/** One whole axes-transpose round trip: the permuted copy, both ways over the link. */
	static double gather(int[] dims, int[] axes, boolean f32, int reps) throws Throwable {
		int rank = dims.length, n = total(dims);
		int[] src = new int[rank];
		int acc = 1;
		for (int i = rank - 1; i >= 0; i--) {
			src[i] = acc;
			acc *= dims[i];
		}
		int[] od = new int[rank], so = new int[rank];
		for (int k = 0; k < rank; k++) {
			od[k] = dims[axes[k]];
			so[k] = src[axes[k]];
		}
		int w = f32 ? Float.BYTES : Double.BYTES;
		Object ha = f32 ? new float[n] : new double[n];
		Object hc = f32 ? new float[n] : new double[n];
		MemorySegment sa = f32 ? MemorySegment.ofArray((float[]) ha) : MemorySegment.ofArray((double[]) ha);
		MemorySegment sc = f32 ? MemorySegment.ofArray((float[]) hc) : MemorySegment.ofArray((double[]) hc);
		return CuLib.best(reps, () -> {
			try (Arena a = Arena.ofConfined()) {
				long da = CuLib.alloc(a, (long) n * w, true), dc = CuLib.alloc(a, (long) n * w, true);
				long dm = meta(a, od, so, null);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, sa, (long) n * w), "HtoD a");
				launch1d(f32 ? gatherF32 : gatherF64, n,
						new MemorySegment[] { pl(a, da), pl(a, dc), pi(a, n), pi(a, rank), pl(a, dm) }, a);
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(sc, dc, (long) n * w), "DtoH");
				CuLib.free(da, true);
				CuLib.free(dc, true);
				CuLib.free(dm, true);
			}
		});
	}

	static void row(String label, double us) {
		System.out.printf("%-46s %8.1f us%n", label, us);
	}

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		bcastF32 = CuLib.func("bcast_f32");
		bcastF64 = CuLib.func("bcast_f64");
		gatherF32 = CuLib.func("gather_f32");
		gatherF64 = CuLib.func("gather_f64");
		foldF32 = CuLib.func("fold_f32");
		foldF64 = CuLib.func("fold_f64");
		// The first shape a process measures pays several hundred us/call for its first
		// few thousand device calls -- the call path being JIT-compiled, not the shape
		// (.kb/gpu.md). Throw one away at each width before anything that is quoted.
		bcast(0, new int[] { 4, 256, 384 }, new int[] { 4, 256, 1 }, false, 3000);
		bcast(0, new int[] { 4, 256, 384 }, new int[] { 4, 256, 1 }, true, 3000);

		for (boolean f32 : new boolean[] { false, true }) {
			String w = f32 ? "f32" : "f64";
			System.out.println("--- " + w + ", the training step's own shapes ---");
			row(w + " bcast sub (4 256 256)-(4 256 1)",
					bcast(1, new int[] { 4, 256, 256 }, new int[] { 4, 256, 1 }, f32, 2000));
			row(w + " bcast sub (4 256 384)-(4 256 1)",
					bcast(1, new int[] { 4, 256, 384 }, new int[] { 4, 256, 1 }, f32, 2000));
			row(w + " bcast mul (4 256 384)*(384)",
					bcast(2, new int[] { 4, 256, 384 }, new int[] { 384 }, f32, 2000));
			row(w + " bcast sub same shape (4 256 384)",
					bcast(1, new int[] { 4, 256, 384 }, new int[] { 4, 256, 384 }, f32, 2000));
			row(w + " fold sum axis 2 (4 256 256)", fold(0, 1024, 256, 1, f32, 2000));
			row(w + " fold sum axis 2 (4 256 384)", fold(0, 1024, 384, 1, f32, 2000));
			row(w + " fold amax axis 2 (4 256 256)", fold(1, 1024, 256, 1, f32, 2000));
			row(w + " fold sum axis 0 (4 256 384)", fold(0, 1, 4, 98304, f32, 2000));
			row(w + " transpose (0 2 1) (4 256 192)",
					gather(new int[] { 4, 256, 192 }, new int[] { 0, 2, 1 }, f32, 2000));
			System.out.println("--- " + w + ", the size sweep (the threshold) ---");
			for (int n : new int[] { 4096, 16384, 32768, 65536, 131072, 262144, 1048576 }) {
				row(w + " bcast sub (" + n / 64 + " 64)-(" + n / 64 + " 1) ",
						bcast(1, new int[] { n / 64, 64 }, new int[] { n / 64, 1 }, f32, 2000));
				row(w + " fold sum axis 1 (" + n / 64 + " 64)", fold(0, n / 64, 64, 1, f32, 2000));
				row(w + " transpose (1 0) (" + n / 64 + " 64)",
						gather(new int[] { n / 64, 64 }, new int[] { 1, 0 }, f32, 2000));
			}
		}
	}
}
