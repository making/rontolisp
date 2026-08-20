import java.lang.foreign.*;
import java.util.Random;

/**
 * What does the precision contract have to say on Metal? Two questions CUDA did not have to
 * answer: how far f32 lands from the f64 scalar oracle on inputs that do NOT round-trip
 * exactly (the dyadic-input artifact the CUDA README warns about), and whether MSL's default
 * compile options are doing fast-math behind our back.
 */
public class MtlPrecision {

	static MemorySegment dev, queue;

	public static void main(String[] x) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			dev = Mtl.device();
			queue = Mtl.queue(dev);

			MemorySegment defaults = opts(ar, -1);
			System.out.println("MTLCompileOptions defaults: mathMode=" + Mtl.msgLong(defaults, "mathMode")
					+ "  (0=?, 1=safe, 2=relaxed, 3=fast)  fastMathEnabled=" + Mtl.msgLong(defaults, "fastMathEnabled"));

			for (int mode : new int[] { -1, 1, 3 }) {
				MemorySegment lib = Mtl.library(dev, MtlSpike.SRC, opts(ar, mode));
				MemorySegment pso = Mtl.pipeline(dev, lib, "gemm_f32");
				String label = mode < 0 ? "default options" : ("mathMode=" + mode);
				for (int n : new int[] { 128, 512 }) {
					compare(ar, pso, n, label);
				}
				transcendental(ar, Mtl.pipeline(dev, lib, "tanh_f32"), label);
			}
		}
		Mtl.poolPop.invokeExact(pool);
	}

	/**
	 * The reason mathMode matters at all: a GEMM is only multiply-add, but the ufunc tier
	 * (phase 4) is transcendentals, and relaxed math is where those move.
	 */
	static void transcendental(Arena ar, MemorySegment pso, String label) {
		int n = 4096;
		Random rnd = new Random(9);
		float[] in = new float[n], out = new float[n];
		for (int i = 0; i < n; i++) in[i] = (float) (rnd.nextGaussian() * 3);
		long bytes = (long) n * 4;
		MemorySegment da = Mtl.buffer(dev, bytes, Mtl.SHARED), dc = Mtl.buffer(dev, bytes, Mtl.SHARED);
		MemorySegment.copy(in, 0, Mtl.contents(da, bytes), Mtl.F, 0, n);
		MemorySegment cnt = ar.allocate(Mtl.I, 1);
		cnt.setAtIndex(Mtl.I, 0, n);
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, da, 0, 0);
		Mtl.setBuffer(enc, dc, 0, 1);
		Mtl.setBytes(enc, cnt, 4, 2);
		Mtl.dispatch(enc, Mtl.size(ar, (n + 255) / 256, 1, 1), Mtl.size(ar, 256, 1, 1));
		Mtl.endEncoding(enc);
		Mtl.commitAndWait(cmd);
		MemorySegment.copy(Mtl.contents(dc, bytes), Mtl.F, 0, out, 0, n);
		double maxAbs = 0, maxRel = 0;
		int differing = 0;
		for (int i = 0; i < n; i++) {
			float ref = (float) Math.tanh(in[i]);
			maxAbs = Math.max(maxAbs, Math.abs(ref - out[i]));
			if (Math.abs(ref) > 1e-4) maxRel = Math.max(maxRel, Math.abs(ref - out[i]) / Math.abs(ref));
			if (Float.floatToRawIntBits(ref) != Float.floatToRawIntBits(out[i])) differing++;
		}
		System.out.printf("  %-16s tanh vs Math.tanh over 4096 gaussians: max abs %.3g, max rel %.3g, %d/%d cells differ%n",
				label, maxAbs, maxRel, differing, n);
		Mtl.release(da);
		Mtl.release(dc);
	}

	/** mode < 0 means "default MTLCompileOptions"; otherwise set mathMode explicitly. */
	static MemorySegment opts(Arena ar, int mode) {
		MemorySegment o = Mtl.msg(Mtl.msg(Mtl.cls("MTLCompileOptions"), "alloc"), "init");
		if (mode >= 0) {
			try {
				Mtl.send(null, Mtl.L).invokeExact(o, Mtl.sel("setMathMode:"), (long) mode);
			}
			catch (Throwable t) {
				throw new RuntimeException(t);
			}
		}
		if (mode >= 0 && Mtl.msgLong(o, "mathMode") != mode) {
			throw new IllegalStateException("setMathMode: did not take: asked " + mode + ", got "
					+ Mtl.msgLong(o, "mathMode"));
		}
		return o;
	}

	static void compare(Arena ar, MemorySegment pso, int n, String label) {
		Random rnd = new Random(42);
		double[] A = new double[n * n], B = new double[n * n];
		float[] Af = new float[n * n], Bf = new float[n * n], Cf = new float[n * n];
		for (int i = 0; i < A.length; i++) {
			A[i] = rnd.nextGaussian();
			B[i] = rnd.nextGaussian();
			Af[i] = (float) A[i];
			Bf[i] = (float) B[i];
		}
		double[] oracle = new double[n * n];
		MtlSpike.naive(A, B, oracle, n, n, n);

		// the f32 CPU reference: same scalar order, f32 accumulator -- isolates "GPU reordered
		// the reduction" from "f32 is narrower than f64"
		float[] cpu32 = new float[n * n];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				float s = 0;
				for (int k = 0; k < n; k++) s += Af[i * n + k] * Bf[k * n + j];
				cpu32[i * n + j] = s;
			}
		}

		run(ar, pso, Af, Bf, Cf, n);
		System.out.printf("  %-16s n=%-5d gpu-f32 vs f64 oracle: maxrel %.3g | cpu-f32 vs f64 oracle: maxrel %.3g"
				+ " | gpu-f32 vs cpu-f32: %s%n", label, n, maxRel(oracle, Cf), maxRel(oracle, cpu32),
				identical(Cf, cpu32) ? "bit-identical" : String.format("maxrel %.3g", maxRelF(cpu32, Cf)));
	}

	static double maxRel(double[] oracle, float[] got) {
		double m = 0, scale = 0;
		for (double v : oracle) scale = Math.max(scale, Math.abs(v));
		for (int i = 0; i < oracle.length; i++) m = Math.max(m, Math.abs(oracle[i] - got[i]) / scale);
		return m;
	}

	static double maxRelF(float[] a, float[] b) {
		double m = 0, scale = 0;
		for (float v : a) scale = Math.max(scale, Math.abs(v));
		for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]) / scale);
		return m;
	}

	static boolean identical(float[] a, float[] b) {
		for (int i = 0; i < a.length; i++) {
			if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) return false;
		}
		return true;
	}

	static void run(Arena ar, MemorySegment pso, float[] A, float[] B, float[] C, int n) {
		long bytes = (long) n * n * 4;
		MemorySegment da = Mtl.buffer(dev, bytes, Mtl.SHARED), db = Mtl.buffer(dev, bytes, Mtl.SHARED),
				dc = Mtl.buffer(dev, bytes, Mtl.SHARED);
		MemorySegment.copy(A, 0, Mtl.contents(da, bytes), Mtl.F, 0, A.length);
		MemorySegment.copy(B, 0, Mtl.contents(db, bytes), Mtl.F, 0, B.length);
		MemorySegment dims = ar.allocate(Mtl.I, 3);
		for (int i = 0; i < 3; i++) dims.setAtIndex(Mtl.I, i, n);
		MemorySegment cmd = Mtl.beginCommands(queue);
		MemorySegment enc = Mtl.beginEncoder(cmd, pso);
		Mtl.setBuffer(enc, da, 0, 0);
		Mtl.setBuffer(enc, db, 0, 1);
		Mtl.setBuffer(enc, dc, 0, 2);
		Mtl.setBytes(enc, dims, 12, 3);
		Mtl.dispatch(enc, Mtl.size(ar, (n + 15) / 16, (n + 15) / 16, 1), Mtl.size(ar, 16, 16, 1));
		Mtl.endEncoding(enc);
		Mtl.commitAndWait(cmd);
		MemorySegment.copy(Mtl.contents(dc, bytes), Mtl.F, 0, C, 0, C.length);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
	}
}
