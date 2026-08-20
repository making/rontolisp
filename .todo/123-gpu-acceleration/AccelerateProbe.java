import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Not a GPU probe. Apple ships a tuned BLAS in the OS (Accelerate.framework), it is plain C so
 * FFM reaches it in four lines, it costs no dependency and no toolchain -- and unlike Metal it
 * has a double. If it is fast, then on this platform the answer for linalg's DEFAULT width is
 * not the GPU at all, and --gpu's f32-only reach stops being the whole story.
 *
 * The binding is NOT Apple-specific and neither is the question. CBLAS is one ABI, so the same
 * two downcall handles reach OpenBLAS, NVPL, MKL or a distro's libblas -- what differs per
 * platform is only whether one is THERE. macOS always has one; Linux has whichever the machine
 * happens to carry. So this walks a candidate list and reports which it bound, which is how
 * `.todo/470`'s portability question gets answered by measurement rather than by assumption:
 * run it on the other machine and read the first line.
 *
 * And then read the LAST line, because the first one is not enough. Binding a CBLAS says
 * nothing about whether it is a tuned one: a DGX Spark bound `libblas.so.3` and got 7 GFLOP/s,
 * the netlib reference implementation, which is SLOWER than rontolisp's own `--simd` kernel.
 * A soname cannot tell the two apart either -- Debian's `libblas.so.3` is an alternatives
 * symlink that points at OpenBLAS when one is installed and at the reference when not. So this
 * also probes for the marker symbols tuned implementations export, and prints a verdict against
 * measured throughput. Any real feature needs that verdict, not just the binding.
 */
public class AccelerateProbe {

	/** In preference order. First one that loads AND exports cblas_dgemm wins. */
	static final String[] CANDIDATES = { "/System/Library/Frameworks/Accelerate.framework/Accelerate", // macOS
			"libnvpl_blas_lp64_gomp.so.0", "libnvpl_blas_lp64_seq.so.0", // NVIDIA Performance Libraries (Grace)
			"libopenblas.so.0", "libopenblas.so", // the usual Linux one
			"libmkl_rt.so.2", "libmkl_rt.so", // Intel
			"libblas.so.3", "libcblas.so.3", "libblas.so", // distro alternatives
			"libaccelerate.so" };

	static final Linker L = Linker.nativeLinker();

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;

	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;

	static final AddressLayout P = ValueLayout.ADDRESS;

	static final String found;

	static final MethodHandle sgemm, dgemm;

	static {
		String name = null;
		MethodHandle s = null, d = null;
		List<String> tried = new ArrayList<>();
		for (String cand : CANDIDATES) {
			try {
				SymbolLookup lk = SymbolLookup.libraryLookup(cand, Arena.global());
				d = L.downcallHandle(lk.find("cblas_dgemm").orElseThrow(),
						FunctionDescriptor.ofVoid(I, I, I, I, I, I, D, P, I, P, I, D, P, I));
				s = L.downcallHandle(lk.find("cblas_sgemm").orElseThrow(),
						FunctionDescriptor.ofVoid(I, I, I, I, I, I, F, P, I, P, I, F, P, I));
				name = cand;
				break;
			}
			catch (Throwable t) {
				tried.add(cand);
			}
		}
		if (name == null) {
			throw new IllegalStateException("no CBLAS found. Tried: " + tried
					+ "\nThat is itself the answer for this platform: nothing to intercept into.");
		}
		found = name;
		sgemm = s;
		dgemm = d;
	}

	static final int ROW_MAJOR = 101, NO_TRANS = 111;

	/** Marker symbols a tuned implementation exports and the reference implementation does not. */
	static final String[][] MARKERS = { { "openblas_get_config", "OpenBLAS" }, { "mkl_get_version", "Intel MKL" },
			{ "MKL_Get_Version", "Intel MKL" }, { "bli_info_get_version_str", "BLIS" },
			{ "ATL_buildinfo", "ATLAS" }, { "nvpl_blas_get_version", "NVIDIA NVPL" },
			{ "armpl_get_version", "Arm Performance Libraries" } };

	/** Below this at f64, it is the reference implementation and rontolisp's --simd beats it. */
	static final double TUNED_GFLOPS = 50;

	public static void main(String[] a) throws Throwable {
		System.out.println("bound CBLAS: " + found);
		System.out.println("identifies as: " + identify());
		System.out.println("cblas, ms per n x n gemm (single thread of control, library may thread):");
		System.out.printf("%-6s %12s %12s %12s%n", "n", "dgemm f64", "sgemm f32", "java f64 loop");
		for (int n : new int[] { 64, 128, 256, 512, 1024, 2048 }) {
			bench(n);
		}
		System.out.printf("%nverdict: %.0f GFLOP/s f64 at n=1024 -- %s%n", peakF64, peakF64 >= TUNED_GFLOPS ? "TUNED."
				: "REFERENCE-implementation territory. rontolisp's own --simd matmul is FASTER than"
						+ " this, so binding it would be a REGRESSION. Decline.");
	}

	static double peakF64;

	static String identify() {
		if (found.contains("Accelerate.framework")) return "Apple Accelerate (by path)";
		try {
			SymbolLookup lk = SymbolLookup.libraryLookup(found, Arena.global());
			for (String[] m : MARKERS) {
				if (lk.find(m[0]).isPresent()) return m[1] + " (exports " + m[0] + ")";
			}
		}
		catch (Throwable t) {
		}
		return "no tuned-implementation marker found -- possibly the netlib reference; read the verdict";
	}

	static void bench(int n) throws Throwable {
		Random rnd = new Random(7);
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment A = ar.allocate(D, (long) n * n), B = ar.allocate(D, (long) n * n),
					C = ar.allocate(D, (long) n * n);
			MemorySegment Af = ar.allocate(F, (long) n * n), Bf = ar.allocate(F, (long) n * n),
					Cf = ar.allocate(F, (long) n * n);
			double[] ha = new double[n * n], hb = new double[n * n];
			for (int i = 0; i < n * n; i++) {
				ha[i] = rnd.nextGaussian();
				hb[i] = rnd.nextGaussian();
				A.setAtIndex(D, i, ha[i]);
				B.setAtIndex(D, i, hb[i]);
				Af.setAtIndex(F, i, (float) ha[i]);
				Bf.setAtIndex(F, i, (float) hb[i]);
			}
			int reps = n <= 512 ? 20 : 5;
			double d = Double.MAX_VALUE, s = Double.MAX_VALUE;
			for (int r = 0; r < reps + 3; r++) {
				long t = System.nanoTime();
				dgemm.invokeExact(ROW_MAJOR, NO_TRANS, NO_TRANS, n, n, n, 1.0, A, n, B, n, 0.0, C, n);
				if (r >= 3) d = Math.min(d, (System.nanoTime() - t) / 1e6);
				t = System.nanoTime();
				sgemm.invokeExact(ROW_MAJOR, NO_TRANS, NO_TRANS, n, n, n, 1.0f, Af, n, Bf, n, 0.0f, Cf, n);
				if (r >= 3) s = Math.min(s, (System.nanoTime() - t) / 1e6);
			}
			double javaMs = Double.NaN;
			if (n <= 1024) {
				double[] hc = new double[n * n];
				long t = System.nanoTime();
				MtlSpike.naive(ha, hb, hc, n, n, n);
				javaMs = (System.nanoTime() - t) / 1e6;
			}
			double gf = 2.0 * n * n * n / (d / 1e3) / 1e9;
			if (n == 1024) peakF64 = gf;
			System.out.printf("%-6d %9.3f ms %9.3f ms %9.1f ms   (%.0f / %.0f GFLOP/s)%n", n, d, s, javaMs, gf,
					2.0 * n * n * n / (s / 1e3) / 1e9);
		}
	}
}
