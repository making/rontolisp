import am.ik.gpu.Gpu;

/**
 * todo-494's threshold question: a member offered for its operand's RESIDENCY rather than its
 * size is a launch with no copy -- but this backend's launch is a command buffer, ~77 us of
 * wait plus the library's own bookkeeping -- so where does it beat the CPU's alternative, a
 * memcpy of the operand out of the slab and a lane loop? Per call, zip and scale over two
 * resident operands (lazy results on, nothing downloaded) against the memcpy + loop and the
 * loop alone, 2^10 .. 2^21 elements. Answer (.kb/gpu.md, "Lazy results and the resident tier
 * on Metal"): the device call is ~100-140 us at every size below 2^18 and the CPU crosses it
 * between 2^18 and 2^19 -- which is NOT where MetalGemm.MIN_RESIDENT_ELEMENTS was set: the
 * training step measured faster with the floor at 2^14 than at 2^17..2^20, because a declined
 * small member costs a materialize and a re-upload around it. The operands are made resident
 * by a broadcast add over them (rows x n output, above the strided threshold). Run from the
 * repository root with `-cp target/classes -Dam.ik.gpu.metal.residentMin=1`.
 */
public class MtlResidentFloor {
	public static void main(String[] args) {
		System.out.println(Gpu.description());
		Gpu.lazyResults(true);
		for (int p = 10; p <= 21; p++) {
			int n = 1 << p;
			float[] a = new float[n], b = new float[n], out = new float[n];
			for (int i = 0; i < n; i++) { a[i] = i * 0.001f; b[i] = 1.5f - i * 0.0001f; }
			int rows = Math.max(1, (1 << 18) / n);
			float[] big = new float[rows * n];
			if (!Gpu.bcast(Gpu.BIN_ADD, a, 0, new int[] { 0, 1 }, b, 0, new int[] { 0, 1 }, big, 0, new int[] { rows, n })) { System.out.println("bcast declined at n=" + n); continue; }
			if (!Gpu.resident(a) || !Gpu.resident(b)) { System.out.println("not resident at n=" + n); continue; }
			// device: zip over resident a, b -> out (lazy, no download); repeated, out replaced each time
			int reps = 200;
			for (int r = 0; r < 20; r++) if (!Gpu.zip(Gpu.BIN_MUL, a, 0, b, 0, out, 0, n)) { System.out.println("zip declined n=" + n); }
			long t0 = System.nanoTime();
			for (int r = 0; r < reps; r++) Gpu.zip(Gpu.BIN_MUL, a, 0, b, 0, out, 0, n);
			double dev = (System.nanoTime() - t0) / 1e3 / reps;
			// device scale
			t0 = System.nanoTime();
			for (int r = 0; r < reps; r++) Gpu.scale(Gpu.BIN_MUL, a, 0, 0.3, false, out, 0, n);
			double devScale = (System.nanoTime() - t0) / 1e3 / reps;
			// cpu: materialize a (dirty? a is clean here: it was uploaded) -> emulate the download cost as a memcpy of n floats + loop
			float[] src = a.clone();
			float[] dst = new float[n];
			for (int r = 0; r < 50; r++) { System.arraycopy(src, 0, dst, 0, n); for (int i = 0; i < n; i++) out[i] = (float) ((double) dst[i] * (double) b[i]); }
			t0 = System.nanoTime();
			for (int r = 0; r < reps; r++) { System.arraycopy(src, 0, dst, 0, n); for (int i = 0; i < n; i++) out[i] = (float) ((double) dst[i] * (double) b[i]); }
			double cpuCopyLoop = (System.nanoTime() - t0) / 1e3 / reps;
			t0 = System.nanoTime();
			for (int r = 0; r < reps; r++) { for (int i = 0; i < n; i++) out[i] = (float) ((double) dst[i] * (double) b[i]); }
			double cpuLoop = (System.nanoTime() - t0) / 1e3 / reps;
			System.out.printf("n=2^%2d %8d  device zip %7.1f us  scale %7.1f us | cpu memcpy+loop %7.1f us  loop alone %7.1f us%n", p, n, dev, devScale, cpuCopyLoop, cpuLoop);
		}
	}
}
