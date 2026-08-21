import java.lang.foreign.*;

/**
 * Where does an intercepted product start to beat the fastest thing rontolisp already has,
 * and therefore where does `am.ik.gpu`'s worth() threshold belong?
 *
 * <p>
 * This is the GPU half only. The CPU half is `matmul-baseline.lisp` under `--simd` on the
 * JVM, and it has to be JIT-WARM to be honest -- the table in the spike's own write-up
 * (the deleted `.todo/123-gpu-acceleration.md`; see this directory's README) was taken with
 * 3 warm-up iterations and 20 reps, which over-reports the small end by about 10x and is
 * why that table's crossover is in the wrong place. `../../.kb/gpu.md` carries the number
 * that replaced it. Run the CPU side with many more reps:
 *
 * <pre>
 * JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
 * java -jar $JAR matmul-baseline.lisp -o Mm2.class --simd
 * java --add-modules jdk.incubator.vector Mm2
 * </pre>
 *
 * <p>
 * Every column here is the SHIPPED route: pooled allocation, critical heap copies, the
 * checked-in PTX. What it must beat at the small end is not CPU arithmetic but rontolisp's
 * own per-call cost, which is why the crossover is far below where a FLOP count would put
 * it.
 */
public class WorthCrossover {

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		System.out.printf("%n%-8s %14s %14s     %s%n", "n", "f64 us/call", "f32 us/call", "n*m*p");
		for (int n : new int[] { 16, 24, 32, 40, 48, 56, 64, 96, 128, 256, 512, 1024, 2048 }) {
			System.out.printf("%-8d %14.1f %14.1f     %d%n", n, square(n, true), square(n, false), (long) n * n * n);
		}
		System.out.println("\nam.ik.gpu declines below n*m*p = 131072 (a 50.8-cubed product), which is");
		System.out.println("where the later of the two crossovers against the warm --simd column falls.");
	}

	static double square(int n, boolean f64) throws Throwable {
		int width = f64 ? 8 : 4;
		long bytes = (long) n * n * width;
		double[] a = new double[n * n], b = new double[n * n], c = new double[n * n];
		float[] af = new float[n * n], bf = new float[n * n], cf = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 13) * 0.125;
			b[i] = (i % 7) * 0.25;
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		MemorySegment sa = f64 ? MemorySegment.ofArray(a) : MemorySegment.ofArray(af);
		MemorySegment sb = f64 ? MemorySegment.ofArray(b) : MemorySegment.ofArray(bf);
		MemorySegment sc = f64 ? MemorySegment.ofArray(c) : MemorySegment.ofArray(cf);
		MemorySegment kernel = f64 ? CuLib.gemmF64 : CuLib.gemmF32;
		int reps = n <= 256 ? 300 : (n <= 512 ? 60 : 20);
		return CuLib.best(reps, () -> {
			try (Arena arena = Arena.ofConfined()) {
				long da = CuLib.alloc(arena, bytes, true), db = CuLib.alloc(arena, bytes, true),
						dc = CuLib.alloc(arena, bytes, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(da, sa, bytes), "htod");
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(db, sb, bytes), "htod");
				CuLib.launch(kernel, da, db, dc, n, n, n, arena);
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(sc, dc, bytes), "dtoh");
				CuLib.free(da, true);
				CuLib.free(db, true);
				CuLib.free(dc, true);
			}
		});
	}
}
