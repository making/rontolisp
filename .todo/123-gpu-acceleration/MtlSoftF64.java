import java.util.Random;

import am.ik.gpu.Gpu;

/**
 * todo-494's question on Metal: do the resident tier's members land on the CPU kernels' bits on
 * a backend with no double -- the scalar forms over a scalar that is NOT a float, the Adam
 * update and the sum fold through gemm.metal's software binary64, and the float routes (zip,
 * where, copy, sqrt/abs/negative/sign) through the flush guard? Checks every member over
 * 262144 bit patterns that include subnormals, the specials, the tiny and the huge, and 60-odd
 * scalars (exact floats, arbitrary doubles, 1e300, 1e-310, NaN, the signed zeros), against
 * Java's arithmetic, bit for bit. A NaN is a NaN whatever its payload (this GPU canonicalizes
 * them in float arithmetic, as CUDA does). Answer: 0 mismatches -- and it was this probe that
 * found (a) this GPU flushes subnormal floats to zero in every float operation even under
 * MTLMathModeSafe, and (b) plain `sqrt` is 1 ulp off in ~10% of operands where `precise::sqrt`
 * is correctly rounded. Run from the repository root with `-cp target/classes` and
 * `-Dam.ik.gpu.metal.residentMin=1` so the small calls are not declined for their size.
 */
public class MtlSoftF64 {

	static double apply(int op, double x, double y) {
		return switch (op) {
			case 0 -> x + y;
			case 1 -> x - y;
			case 2 -> x * y;
			case 3 -> x / y;
			case 4 -> x > y ? x : y;
			case 5 -> x < y ? x : y;
			case 6 -> x > y ? 1.0 : 0.0;
			case 7 -> x >= y ? 1.0 : 0.0;
			case 8 -> x < y ? 1.0 : 0.0;
			case 9 -> x <= y ? 1.0 : 0.0;
			default -> x == y ? 1.0 : 0.0;
		};
	}

	static int fails = 0;

	static java.util.Map<String, Integer> cats = new java.util.TreeMap<>();
	static void check(String what, float got, float want) {
		if (Float.floatToRawIntBits(got) != Float.floatToRawIntBits(want)) {
			if (Float.isNaN(got) && Float.isNaN(want)) return;
			fails++;
			String cat = what.replaceAll(" a=.*| i=.*", "");
			int c = cats.merge(cat, 1, Integer::sum);
			if (c <= 2) {
				System.out.println("MISMATCH " + what + ": got " + got + " (" + Integer.toHexString(Float.floatToRawIntBits(got)) + ") want " + want + " (" + Integer.toHexString(Float.floatToRawIntBits(want)) + ")");
			}
		}
	}

	public static void main(String[] args) {
		System.out.println(Gpu.description());
		Gpu.lazyResults(true);
		int n = 1 << 18;
		Random r = new Random(7);
		float[] a = new float[n];
		for (int i = 0; i < n; i++) {
			int kind = i % 8;
			a[i] = switch (kind) {
				case 0 -> (float) r.nextGaussian();
				case 1 -> (float) (r.nextGaussian() * 1e30);
				case 2 -> (float) (r.nextGaussian() * 1e-30);
				case 3 -> Float.intBitsToFloat(r.nextInt());            // any bit pattern incl NaN/inf/subnormal
				case 4 -> Float.intBitsToFloat(r.nextInt(0x00800000));   // subnormals
				case 5 -> (float) (r.nextInt(1000) - 500);
				case 6 -> (float) (r.nextGaussian() * 1e-40);
				default -> r.nextFloat() * 2 - 1;
			};
		}
		float[] out = new float[n];
		// make a resident
		if (!Gpu.map(Gpu.MAP_EXP, a, 0, new float[n], 0, n)) throw new IllegalStateException("exp declined");
		if (!Gpu.resident(a)) throw new IllegalStateException("not resident");
		double[] scalars = { 0.3, 7, 2.5, 1e300, 1e-300, 1e-40, -0.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, 3.0e-4, 0.1, 1.0 / 3, 1e38, 1e-45, 123456789.123456789, -1e-310, Double.MIN_VALUE, Double.MAX_VALUE, 0.9, 0.999, 1e-8 };
		for (int rep = 0; rep < 40; rep++) {
			scalars = java.util.Arrays.copyOf(scalars, scalars.length + 1);
			scalars[scalars.length - 1] = switch (rep % 4) {
				case 0 -> r.nextGaussian();
				case 1 -> Double.longBitsToDouble(r.nextLong());
				case 2 -> r.nextGaussian() * Math.pow(10, r.nextInt(80) - 40);
				default -> (double) (float) r.nextGaussian();
			};
		}
		long t0 = System.nanoTime();
		int calls = 0;
		for (double s : scalars) {
			for (int op = 0; op < 11; op++) {
				for (int swap = 0; swap < 2; swap++) {
					java.util.Arrays.fill(out, 0f);
					boolean ok = Gpu.scale(op, a, 0, s, swap == 1, out, 0, n);
					if (!ok) { System.out.println("scale declined op " + op + " s " + s); continue; }
					Gpu.materialize(out);
					calls++;
					for (int i = 0; i < n; i++) {
						float want = (float) (swap == 1 ? apply(op, s, a[i]) : apply(op, a[i], s));
						check("scale op " + op + " s=" + s + " swap=" + swap + " a=" + a[i], out[i], want);
					}
				}
			}
		}
		System.out.println("scale: " + calls + " calls, fails " + fails + ", " + (System.nanoTime() - t0) / 1e6 + " ms");
		// zip over a and b
		float[] b = new float[n];
		for (int i = 0; i < n; i++) b[i] = a[(i * 7919) % n];
		for (int op = 0; op < 11; op++) {
			if (!Gpu.zip(op, a, 0, b, 0, out, 0, n)) { System.out.println("zip declined " + op); continue; }
			Gpu.materialize(out);
			for (int i = 0; i < n; i++) check("zip op " + op, out[i], (float) apply(op, a[i], b[i]));
		}
		System.out.println("zip fails " + fails);
		// map 12..15
		for (int op = 12; op < 16; op++) {
			if (!Gpu.map(op, a, 0, out, 0, n)) { System.out.println("map declined " + op); continue; }
			Gpu.materialize(out);
			for (int i = 0; i < n; i++) {
				double d = a[i];
				float want = (float) switch (op) { case 12 -> Math.sqrt(d); case 13 -> Math.abs(d); case 14 -> -d; default -> Math.signum(d); };
				if (op == 12 && Float.isNaN(want) && Float.isNaN(out[i])) { if (Float.floatToRawIntBits(out[i]) != 0x7fc00000 && !Float.isNaN(a[i])) check("sqrt nan canon", out[i], want); continue; }
				check("map op " + op + " a=" + a[i], out[i], want);
			}
		}
		System.out.println("map fails " + fails);
		// adam
		float[] g = b, m = new float[n], v = new float[n], x = a.clone();
		for (int i = 0; i < n; i++) { m[i] = (float) r.nextGaussian() * 0.01f; v[i] = Math.abs((float) r.nextGaussian()) * 0.001f; if (Float.isNaN(x[i]) || Float.isInfinite(x[i])) x[i] = 0.5f; }
		float[] xe = x.clone(), me = m.clone(), ve = v.clone();
		for (int step = 1; step <= 6; step++) {
			double b1 = 0.9, b2 = 0.999, lr = 3e-4 * step, wd = 0.1;
			double[] rule = { lr, lr * wd, wd, b1, 1 - b1, b2, 1 - b2, 1e-8, 1 - Math.pow(b1, step), 1 - Math.pow(b2, step), step % 3 };
			if (!Gpu.adamStep(x, 0, g, 0, m, 0, v, 0, n, rule)) { System.out.println("adam declined"); break; }
			adamReference(xe, g, me, ve, rule);
			Gpu.materialize(x); Gpu.materialize(m); Gpu.materialize(v);
			for (int i = 0; i < n; i++) { check("adam x step " + step + " i=" + i, x[i], xe[i]); check("adam m", m[i], me[i]); check("adam v", v[i], ve[i]); }
		}
		System.out.println("adam fails " + fails);
		// fold sum/amax/amin over resident a: outer x len x inner
		int inner = 4, len = 256, outer = n / (inner * len);
		for (int op = 0; op < 3; op++) {
			float[] f = new float[outer * inner];
			if (!Gpu.fold(op, a, 0, f, 0, outer, len, inner)) { System.out.println("fold declined " + op); continue; }
			Gpu.materialize(f);
			for (int o = 0; o < outer; o++) for (int j = 0; j < inner; j++) {
				double acc = op == 0 ? 0.0 : a[o * len * inner + j]; 
				for (int k = (op == 0 ? 0 : 1); k < len; k++) { double vv = a[(o * len + k) * inner + j]; if (op == 0) acc += vv; else if (op == 1 ? vv > acc : vv < acc) acc = vv; }
				check("fold op " + op, f[o * inner + j], (float) acc);
			}
		}
		System.out.println("fold fails " + fails);
		// where: float mask resident (a), x scalar, y = b; and mask scalar
		{
			int rows = 64, cols = n / rows;
			float[] w = new float[n];
			if (!Gpu.where(a, 0, new int[] { cols, 1 }, 0.0, null, 0, new int[] { 0, 0 }, 2.5, b, 0, new int[] { cols, 1 }, 0.0, w, 0, new int[] { rows, cols })) System.out.println("where declined");
			else { Gpu.materialize(w); for (int i = 0; i < n; i++) check("where", w[i], (double) a[i] == 0.0 ? b[i] : 2.5f); }
			if (!Gpu.where(null, 0, new int[] { 0 }, 1.0, a, 0, new int[] { 1 }, 0.0, null, 0, new int[] { 0 }, -9.5, w, 0, new int[] { n })) System.out.println("where2 declined");
			else { Gpu.materialize(w); for (int i = 0; i < n; i++) check("where2", w[i], a[i]); }
		}
		System.out.println("where fails " + fails);
		// copy: transpose of a as rows x cols with negative-step slice
		{
			int rows = 256, cols = n / rows;
			float[] t = new float[n];
			if (!Gpu.copy(a, 0, new int[] { 1, cols }, new int[] { 0, n }, t, 0, new int[] { rows, 1 }, new int[] { 0, n }, new int[] { cols, rows })) System.out.println("copy declined");
			else { Gpu.materialize(t); for (int i = 0; i < rows; i++) for (int j = 0; j < cols; j++) check("copy", t[j * rows + i], a[i * cols + j]); }
		}
		System.out.println("copy fails " + fails);
		System.out.println("TOTAL FAILS " + fails); cats.forEach((k, cnt) -> System.out.println("  " + cnt + "  " + k));
	}

	static void adamReference(float[] xa, float[] ga, float[] ma, float[] va, double[] ps) {
		double lr = ps[0], lrwd = ps[1], wd = ps[2], beta1 = ps[3], omb1 = ps[4], beta2 = ps[5], omb2 = ps[6], eps = ps[7], corr1 = ps[8], corr2 = ps[9];
		int mode = (int) ps[10];
		for (int k = 0; k < xa.length; k++) {
			double x0 = xa[k];
			double xv = mode == 2 ? x0 - lrwd * x0 : x0;
			double gv = mode == 1 ? ga[k] + wd * x0 : ga[k];
			double mk = beta1 * ma[k] + omb1 * gv;
			double vk = beta2 * va[k] + omb2 * gv * gv;
			ma[k] = (float) mk;
			va[k] = (float) vk;
			xa[k] = (float) (xv - lr * (mk / corr1) / (Math.sqrt(vk / corr2) + eps));
		}
	}
}
