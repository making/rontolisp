import java.lang.foreign.*;
import java.util.Arrays;

/**
 * The 2026-08-22 residency question, second half, and the one that decided the route: what
 * does a device copy cost when the HOST array is fresh? {@code ResidencyCost.java} showed
 * that live device blocks do not slow a copy; this shows what does. Every probe before it
 * (CopyRoute, ZeroCopyRoute, ElementwiseCrossover) copied to and from the SAME host array
 * on every iteration, and a training step never does -- every result is a fresh Java
 * array.
 *
 * Part 1: one 1 MB f32 buffer, HtoD then DtoH, over a reused host array against a fresh
 * one per iteration, with and without eden churn (garbage between iterations) and with the
 * fresh array first written by the CPU. Part 2: which FIRST access warms a fresh array for
 * the device -- a DtoH, an HtoD, a CPU store per page, or a full CPU copy into it -- by
 * timing the first access and a second DtoH after it. Median / p90 / max in us.
 *
 * On the GB10 (integrated, pageable memory access through the CPU page tables) a fresh
 * array costs the copy engine ~9 us per 4 KB page on the GPU's first touch -- 2.3 ms for
 * 1 MB against 25 us warm -- whichever direction touches it first, and a CPU copy INTO the
 * array beforehand (not the JVM's own zeroing of it) makes the first device access warm.
 * That is why am.ik.gpu stages every download through a pinned bounce buffer and a Java
 * copy (see {@code CudaGemm.BOUNCE_BYTES}), and why the baseline's uploads had been hiding
 * the cost: they warmed the eden pages the downloads later landed on.
 *
 * cd .todo/123-gpu-acceleration && java -Xmx6g --enable-native-access=ALL-UNNAMED
 * FreshPageCost.java
 */
public class FreshPageCost {

	static float[] sink;

	static float[] src;

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		int bytes = 1 << 20;
		try (Arena a = Arena.ofConfined()) {
			long d = CuLib.alloc(a, bytes, true);
			System.out.println("-- part 1: a reused host array against a fresh one (1 MB f32, us) --");
			part1("reused host array", a, d, bytes, false, 0);
			part1("fresh host array each iteration", a, d, bytes, true, 0);
			part1("fresh host array + 64 MB garbage/iter", a, d, bytes, true, 64 << 20);
			part1("fresh host array + 256 MB garbage/iter", a, d, bytes, true, 256 << 20);
			part1("reused host array + 256 MB garbage/iter", a, d, bytes, false, 256 << 20);
			part1("fresh, CPU-written first + 256 MB garbage/iter", a, d, bytes, true, -(256 << 20));
			System.out.println("-- part 2: which first access warms a fresh array (then a second DtoH) --");
			String[] names = { "DtoH first", "HtoD first", "one CPU store per 4 KB page first",
					"a full CPU arraycopy into it first" };
			for (int mode = 0; mode < 4; mode++) {
				part2(names[mode], mode, a, d, bytes);
			}
		}
	}

	static void part1(String label, Arena a, long d, int bytes, boolean fresh, int garbage) throws Throwable {
		int reps = 300;
		double[] htod = new double[reps], dtoh = new double[reps];
		float[] reused = new float[bytes / 4];
		for (int i = 0; i < reps; i++) {
			float[] h = fresh ? new float[bytes / 4] : reused;
			if (garbage < 0) {
				for (int k = 0; k < h.length; k += 1024) {
					h[k] = 1f;
				}
			}
			MemorySegment seg = MemorySegment.ofArray(h);
			long t0 = System.nanoTime();
			CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(d, seg, (long) bytes), "HtoD");
			long t1 = System.nanoTime();
			CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(seg, d, (long) bytes), "DtoH");
			long t2 = System.nanoTime();
			htod[i] = (t1 - t0) / 1e3;
			dtoh[i] = (t2 - t1) / 1e3;
			churn(Math.abs(garbage));
		}
		Arrays.sort(htod);
		Arrays.sort(dtoh);
		System.out.printf("%-50s HtoD med %7.1f p90 %7.1f max %8.1f | DtoH med %7.1f p90 %7.1f max %8.1f%n", label,
				htod[reps / 2], htod[reps * 9 / 10], htod[reps - 1], dtoh[reps / 2], dtoh[reps * 9 / 10],
				dtoh[reps - 1]);
	}

	static void part2(String label, int mode, Arena a, long d, int bytes) throws Throwable {
		int reps = 200;
		double[] first = new double[reps], second = new double[reps];
		for (int i = 0; i < reps; i++) {
			float[] h = new float[bytes / 4];
			if (mode == 2) {
				for (int k = 0; k < h.length; k += 1024) {
					h[k] = 1f;
				}
			}
			if (mode == 3) {
				if (src == null) {
					src = new float[bytes / 4];
				}
				System.arraycopy(src, 0, h, 0, h.length);
			}
			MemorySegment seg = MemorySegment.ofArray(h);
			long t0 = System.nanoTime();
			if (mode == 1) {
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(d, seg, (long) bytes), "HtoD");
			}
			else {
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(seg, d, (long) bytes), "DtoH");
			}
			long t1 = System.nanoTime();
			CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(seg, d, (long) bytes), "DtoH");
			long t2 = System.nanoTime();
			first[i] = (t1 - t0) / 1e3;
			second[i] = (t2 - t1) / 1e3;
			churn(256 << 20);
		}
		Arrays.sort(first);
		Arrays.sort(second);
		System.out.printf("%-50s first med %7.1f p90 %7.1f max %8.1f | second DtoH med %7.1f p90 %7.1f max %8.1f%n",
				label, first[reps / 2], first[reps * 9 / 10], first[reps - 1], second[reps / 2],
				second[reps * 9 / 10], second[reps - 1]);
	}

	/** Allocates and drops {@code bytes} of garbage, so the eden cycles as a program's does. */
	static void churn(int bytes) {
		for (int k = 0; k < bytes / (4 << 20); k++) {
			sink = new float[1 << 20];
		}
	}

}
