package am.ik.rontolisp.codegen.jvm;

import java.util.Random;

/**
 * {@code eval.Bf16GemvBench} for the embedded {@code --simd} bridge's mirror of the fused
 * {@code bfloat16} kernels ({@link JvmSimdVectorTemplate}). Read that class for why both
 * JITs are measured and why the accumulator count must match between the arms.
 *
 * <p>
 * It exists separately because this is the copy that ships: the template's bytecode is
 * embedded in every {@code --simd} {@code .class}, and a stock OpenJDK runs that class
 * under C2. The kernels are a literal mirror of the interpreter's, so the two harnesses
 * should agree; a divergence would mean the surrounding class -- 4000 lines of other
 * kernels -- is changing an inlining decision, which is exactly the failure mode
 * {@code .todo/482} round 2 found.
 *
 * <pre>{@code
 * CP=target/classes:target/test-classes
 * java --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.codegen.jvm.Bf16TemplateGemvBench
 * java -XX:-UseJVMCICompiler --add-modules jdk.incubator.vector -cp $CP \
 *     am.ik.rontolisp.codegen.jvm.Bf16TemplateGemvBench
 * }</pre>
 */
public final class Bf16TemplateGemvBench {

	private Bf16TemplateGemvBench() {
	}

	/**
	 * Which JIT is actually compiling: "graal" when {@code UseJVMCICompiler} is on (this
	 * box's default, CI's and the native image's), "c2" under
	 * {@code -XX:-UseJVMCICompiler} or a stock OpenJDK. Read from the VM flag rather than
	 * a system property, which is set on neither.
	 */
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

	public static void main(String[] args) {
		System.out.printf("jit=%s java=%s threads=%s%n", jit(), System.getProperty("java.version"),
				System.getenv().getOrDefault("RONTOLISP_THREADS", "default"));
		for (int[] shape : new int[][] { { 288, 288 }, { 1024, 1024 }, { 4096, 4096 } }) {
			int rows = shape[0];
			int cols = shape[1];
			int elements = rows * cols;
			Random random = new Random(11);
			// The f32 baseline runs through the real bridge entry, so it carries the
			// rank-2 packed header [2, rows, cols, ...]; the bf16 kernels are standalone
			// and take a bare short[] until the packed bf16 type exists.
			float[] wf = new float[3 + elements];
			wf[0] = 2.0f;
			wf[1] = rows;
			wf[2] = cols;
			short[] wb = new short[elements];
			for (int i = 0; i < elements; i++) {
				short bits = JvmSimdVectorTemplate.floatToBf16((float) (random.nextGaussian() * 0.02));
				wb[i] = bits;
				wf[3 + i] = JvmSimdVectorTemplate.bf16ToFloat(bits);
			}
			float[] xp = new float[2 + cols];
			xp[0] = 1.0f;
			xp[1] = cols;
			float[] x = new float[cols];
			for (int i = 0; i < cols; i++) {
				x[i] = (float) random.nextGaussian();
				xp[2 + i] = x[i];
			}
			float[] rp = new float[2 + rows];
			rp[0] = 1.0f;
			rp[1] = rows;
			float[] r = new float[rows];
			int iterations = elements > (1 << 22) ? 10 : 40;

			System.out.printf("%n=== %dx%d (%.1f MB f32 / %.1f MB bf16)%n%-26s %9s %9s %8s%n", rows, cols,
					elements * 4 / 1e6, elements * 2 / 1e6, "variant", "ms", "Gelem/s", "vs f32");
			String[] names = { "f32 lanes", "bf16 fused", "f32 lanes --parallel", "bf16 fused --parallel" };
			Variant[] variants = { () -> JvmSimdVectorTemplate.simdMatvecInto(rp, wf, xp),
					() -> JvmSimdVectorTemplate.matvecIntoBf16(r, wb, rows, cols, x, false),
					() -> JvmSimdVectorTemplate.simdMatvecIntoParallel(rp, wf, xp),
					() -> JvmSimdVectorTemplate.matvecIntoBf16(r, wb, rows, cols, x, true) };
			long baseline = 0;
			for (int i = 0; i < names.length; i++) {
				long ns = time(variants[i], iterations);
				if (i == 0) {
					baseline = ns;
				}
				System.out.printf("%-26s %9.3f %9.2f %7.2fx%n", names[i], ns / 1e6, elements / (double) ns,
						baseline / (double) ns);
			}
			JvmSimdVectorTemplate.matvecIntoBf16(r, wb, rows, cols, x, false);
			JvmSimdVectorTemplate.simdMatvecInto(rp, wf, xp);
			double fused = 0.0;
			double widened = 0.0;
			for (int i = 0; i < rows; i++) {
				fused += r[i];
				widened += rp[2 + i];
			}
			System.out.printf("checksum fused=%a widen-then-f32=%a identical=%b%n", fused, widened, fused == widened);
		}
	}

}
