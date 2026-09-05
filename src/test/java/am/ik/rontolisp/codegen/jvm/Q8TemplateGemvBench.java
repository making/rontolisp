package am.ik.rontolisp.codegen.jvm;

import java.util.Random;

/**
 * {@code eval.Q8GemvBench}'s twin over {@link JvmSimdVectorTemplate} -- the copy of the
 * kernels that ships in every {@code --simd} {@code .class}, reached through the real
 * bridge entries over headered arrays, so the header arithmetic is timed too and the
 * surrounding class is shown not to change an inlining decision. Same variants, same
 * shapes, same values; run by {@code .todo/672-.../bench.sh} under both JITs.
 */
public final class Q8TemplateGemvBench {

	private Q8TemplateGemvBench() {
	}

	private static String jit() {
		try {
			return "true".equals(java.lang.management.ManagementFactory
				.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
				.getVMOption("UseJVMCICompiler")
				.getValue()) ? "graal" : "c2";
		}
		catch (RuntimeException ex) {
			return "unknown";
		}
	}

	private interface Variant {

		void run();

	}

	private static long time(Variant v, int iterations) {
		for (int i = 0; i < 8; i++) {
			v.run();
		}
		long best = Long.MAX_VALUE;
		for (int round = 0; round < 5; round++) {
			long start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				v.run();
			}
			best = Math.min(best, (System.nanoTime() - start) / iterations);
		}
		return best;
	}

	private static void quantizeBlock(float[] src, int srcOff, byte[] dst, int dstOff) {
		float amax = 0.0f;
		for (int k = 0; k < 32; k++) {
			amax = Math.max(amax, Math.abs(src[srcOff + k]));
		}
		float d = amax / 127.0f;
		float id = d != 0.0f ? 1.0f / d : 0.0f;
		short dh = Float.floatToFloat16(d);
		dst[dstOff] = (byte) dh;
		dst[dstOff + 1] = (byte) (dh >>> 8);
		for (int k = 0; k < 32; k++) {
			float x0 = src[srcOff + k] * id;
			dst[dstOff + 2 + k] = (byte) (x0 < 0 ? -Math.round(-x0) : Math.round(x0));
		}
	}

	private static void putInt(byte[] a, int off, int v) {
		for (int k = 0; k < 4; k++) {
			a[off + k] = (byte) (v >>> (8 * k));
		}
	}

	public static void main(String[] args) {
		System.out.printf("jit=%s java=%s threads=%s%n", jit(), System.getProperty("java.version"),
				System.getenv().getOrDefault("RONTOLISP_THREADS", "default"));
		for (int[] shape : new int[][] { { 288, 288 }, { 1024, 1024 }, { 4096, 4096 }, { 5632, 2048 } }) {
			int rows = shape[0];
			int cols = shape[1];
			int elements = rows * cols;
			Random random = new Random(11);
			float[] wf = new float[3 + elements];
			wf[0] = 2.0f;
			wf[1] = rows;
			wf[2] = cols;
			for (int i = 0; i < elements; i++) {
				wf[3 + i] = (float) (random.nextGaussian() * 0.02);
			}
			short[] wb = new short[5 + elements];
			wb[0] = 2;
			wb[1] = (short) (rows >>> 16);
			wb[2] = (short) rows;
			wb[3] = (short) (cols >>> 16);
			wb[4] = (short) cols;
			for (int i = 0; i < elements; i++) {
				wb[5 + i] = JvmSimdVectorTemplate.floatToBf16(wf[3 + i]);
			}
			byte[] q8 = new byte[16 + elements / 32 * 34];
			putInt(q8, 0, 1);
			putInt(q8, 4, 2);
			putInt(q8, 8, rows);
			putInt(q8, 12, cols);
			for (int b = 0; b < elements / 32; b++) {
				quantizeBlock(wf, 3 + b * 32, q8, 16 + b * 34);
			}
			float[] x = new float[2 + cols];
			x[0] = 1.0f;
			x[1] = cols;
			for (int i = 0; i < cols; i++) {
				x[2 + i] = (float) random.nextGaussian();
			}
			float[] r = new float[2 + rows];
			r[0] = 1.0f;
			r[1] = rows;
			int iterations = elements > (1 << 22) ? 10 : 40;
			System.out.printf("%n=== %dx%d%n%-24s %9s %9s %8s%n", rows, cols, "variant", "ms", "Gelem/s", "vs f32");
			String[] names = { "f32 lanes", "bf16 fused", "q8 int-dot", "f32 lanes --parallel", "bf16 fused --parallel",
					"q8 int-dot --parallel" };
			Variant[] variants = { () -> JvmSimdVectorTemplate.simdMatvecInto(r, wf, x),
					() -> JvmSimdVectorTemplate.simdMatvecInto(r, wb, x),
					() -> JvmSimdVectorTemplate.simdMatvecInto(r, q8, x),
					() -> JvmSimdVectorTemplate.simdMatvecIntoParallel(r, wf, x),
					() -> JvmSimdVectorTemplate.simdMatvecIntoParallel(r, wb, x),
					() -> JvmSimdVectorTemplate.simdMatvecIntoParallel(r, q8, x) };
			long baseline = 0;
			long parallelBaseline = 0;
			for (int i = 0; i < names.length; i++) {
				long ns = time(variants[i], iterations);
				if (i == 0) {
					baseline = ns;
				}
				if (i == 3) {
					parallelBaseline = ns;
				}
				long against = i >= 3 ? parallelBaseline : baseline;
				System.out.printf("%-24s %9.3f %9.2f %7.2fx%n", names[i], ns / 1e6, elements / (double) ns,
						against / (double) ns);
			}
			JvmSimdVectorTemplate.simdMatvecInto(r, q8, x);
			double checksum = 0.0;
			for (int i = 0; i < rows; i++) {
				checksum += r[2 + i];
			}
			System.out.printf("checksum q8=%a%n", checksum);
		}
	}

}
