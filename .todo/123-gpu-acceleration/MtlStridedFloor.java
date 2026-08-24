import am.ik.gpu.Gpu;

/**
 * todo-509's question: what does the strided tier's LAYOUT cost on Metal? The layout was a
 * pooled slab -- 4 KB, MIN_SLAB_BYTES, for 96-256 bytes of ints -- taken and given back per
 * `bcast`/`gather`/`where`/`copy` call, plus its `setBuffer` binding; the copy into it was
 * never the problem, since a shared slab's `contents` is host memory on unified memory. This
 * measures the whole shipped call at the sizes the tier actually runs at (MIN_STRIDED_ELEMENTS
 * is 2^18 here, so nothing below it is offered), eager results, operands NOT resident: what a
 * step's ~380 `bcast` and ~340 `gather` calls each pay.
 *
 * Pair a run before the change with one after; the difference is one pool acquisition, its
 * release and one binding per call. Run from the repository root with `-cp target/classes`.
 */
public class MtlStridedFloor {
	public static void main(String[] args) {
		System.out.println(Gpu.description());
		for (int p = 18; p <= 23; p++) {
			int n = 1 << p;
			int cols = 384, rows = Math.max(1, (n + cols - 1) / cols);
			int total = rows * cols;
			float[] a = new float[total], b = new float[cols], out = new float[total];
			for (int i = 0; i < total; i++) { a[i] = i * 0.001f; }
			for (int i = 0; i < cols; i++) { b[i] = 1.5f - i * 0.0001f; }
			int[] dims = { rows, cols };
			int[] sa = { cols, 1 }, sb = { 0, 1 };
			int reps = total > (1 << 21) ? 100 : 400;
			for (int r = 0; r < 30; r++) {
				if (!Gpu.bcast(Gpu.BIN_ADD, a, 0, sa, b, 0, sb, out, 0, dims)) { System.out.println("bcast declined at " + total); break; }
			}
			long t0 = System.nanoTime();
			for (int r = 0; r < reps; r++) { Gpu.bcast(Gpu.BIN_ADD, a, 0, sa, b, 0, sb, out, 0, dims); }
			double bcast = (System.nanoTime() - t0) / 1e3 / reps;
			int[] tdims = { cols, rows };
			int[] tsa = { 1, cols };
			for (int r = 0; r < 30; r++) {
				if (!Gpu.gather(a, 0, tsa, out, 0, tdims)) { System.out.println("gather declined at " + total); break; }
			}
			t0 = System.nanoTime();
			for (int r = 0; r < reps; r++) { Gpu.gather(a, 0, tsa, out, 0, tdims); }
			double gather = (System.nanoTime() - t0) / 1e3 / reps;
			System.out.printf("%8d elements (%d x %d)  bcast %8.1f us  gather %8.1f us%n", total, rows, cols, bcast, gather);
		}
		// The small end, where the layout slab is a share of the call rather than of a
		// memory pass: `copy` is a RESIDENT-operand member (MIN_RESIDENT_ELEMENTS is 2^14),
		// so it runs at sizes the size-thresholded members never see.
		Gpu.lazyResults(true);
		for (int p = 14; p <= 20; p++) {
			int n = 1 << p;
			float[] a = new float[n], b = new float[n], out = new float[n];
			for (int i = 0; i < n; i++) { a[i] = i * 0.001f; b[i] = 1.5f - i * 0.0001f; }
			// Made resident the way MtlResidentFloor does it: a broadcast over the pair,
			// above the size threshold, keeps both operands as CLEAN copies.
			int stack = Math.max(1, (1 << 18) / n);
			if (!Gpu.bcast(Gpu.BIN_ADD, a, 0, new int[] { 0, 1 }, b, 0, new int[] { 0, 1 }, new float[stack * n], 0,
					new int[] { stack, n }) || !Gpu.resident(a)) {
				System.out.println("not resident at n=" + n);
				continue;
			}
			int[] span = { 0, n };
			int[] one = { 1 };
			int[] dims = { n };
			int reps = 300;
			for (int r = 0; r < 30; r++) {
				if (!Gpu.copy(a, 0, one, span, out, 0, one, span, dims)) { System.out.println("copy declined at " + n); break; }
			}
			long t0 = System.nanoTime();
			for (int r = 0; r < reps; r++) { Gpu.copy(a, 0, one, span, out, 0, one, span, dims); }
			double copy = (System.nanoTime() - t0) / 1e3 / reps;
			System.out.printf("resident copy n=2^%2d %8d  %8.1f us%n", p, n, copy);
		}
		Gpu.lazyResults(false);
	}
}
