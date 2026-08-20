import java.lang.foreign.*;

/**
 * The design crux from .todo/123, re-asked on Metal: does a per-call intercept pay, or must the
 * array LIVE on the device? Same 5-op chain (matmul, add, tanh, matmul, add) as ResidencySpike.
 *
 * Metal splits the question in two where CUDA did not. There are two separate costs to remove --
 * the host copies, and the ~77 us per-COMMAND-BUFFER submission -- so this measures three ways,
 * not two, to say which of them residency is actually buying back.
 */
public class MtlResidency {

	static MemorySegment dev, queue, gemm, gemm3, add, tanh;

	public static void main(String[] x) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			dev = Mtl.device();
			MemorySegment lib = Mtl.library(dev, MtlSpike.SRC, MemorySegment.NULL);
			gemm = Mtl.pipeline(dev, lib, "gemm_f32");
			gemm3 = Mtl.pipeline(dev, lib, "gemm3_f32");
			add = Mtl.pipeline(dev, lib, "add_f32");
			tanh = Mtl.pipeline(dev, lib, "tanh_f32");
			queue = Mtl.queue(dev);

			System.out.println("-- batched rank-3 matmul, the shape --simd never intercepts --");
			batched(ar, 24, 64, 32);
			batched(ar, 48, 256, 64);
			batched(ar, 192, 512, 64);

			System.out.println("\n-- the residency question: (x@w1 + b) -> tanh -> (@w2 + b2), f32 --");
			for (int n : new int[] { 128, 512, 1024 }) {
				chain(ar, n);
			}
		}
		Mtl.poolPop.invokeExact(pool);
	}

	static void batched(Arena ar, int batch, int n, int d) {
		long ab = (long) batch * n * d * 4, bb = (long) batch * d * n * 4, cb = (long) batch * n * n * 4;
		MemorySegment da = Mtl.buffer(dev, ab, Mtl.SHARED), db = Mtl.buffer(dev, bb, Mtl.SHARED),
				dc = Mtl.buffer(dev, cb, Mtl.SHARED);
		MemorySegment ca = Mtl.contents(da, ab), cbf = Mtl.contents(db, bb);
		for (int i = 0; i < ab / 4; i++) ca.setAtIndex(Mtl.F, i, ((i % 13) - 6) * 0.125f);
		for (int i = 0; i < bb / 4; i++) cbf.setAtIndex(Mtl.F, i, ((i % 7) - 3) * 0.25f);
		MemorySegment dims = ar.allocate(Mtl.I, 3);
		dims.setAtIndex(Mtl.I, 0, n);
		dims.setAtIndex(Mtl.I, 1, n);
		dims.setAtIndex(Mtl.I, 2, d);
		MemorySegment groups = Mtl.size(ar, (n + 15) / 16, (n + 15) / 16, batch), per = Mtl.size(ar, 16, 16, 1);
		double best = Double.MAX_VALUE;
		for (int r = 0; r < 40; r++) { // Apple GPUs ramp their clocks: a short warm-up under-reports
			long t = System.nanoTime();
			MemorySegment cmd = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cmd, gemm3);
			Mtl.setBuffer(enc, da, 0, 0);
			Mtl.setBuffer(enc, db, 0, 1);
			Mtl.setBuffer(enc, dc, 0, 2);
			Mtl.setBytes(enc, dims, 12, 3);
			Mtl.dispatch(enc, groups, per);
			Mtl.endEncoding(enc);
			Mtl.commitAndWait(cmd);
			if (r > 14) best = Math.min(best, (System.nanoTime() - t) / 1e6);
		}
		double gflops = 2.0 * batch * n * n * d / (best / 1e3) / 1e9;
		System.out.printf("    b*h=%-4d n=%-4d d=%-3d  gpu %8.3f ms (%.0f GFLOP/s)%n", batch, n, d, best, gflops);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
	}

	static void chain(Arena ar, int n) {
		long sq = (long) n * n * 4;
		MemorySegment X = buf(sq), W1 = buf(sq), B1 = buf(sq), H = buf(sq), T = buf(sq), W2 = buf(sq), B2 = buf(sq),
				O = buf(sq), Y = buf(sq);
		fill(X, n * n, 0.01f);
		fill(W1, n * n, 0.02f);
		fill(B1, n * n, 0.03f);
		fill(W2, n * n, 0.04f);
		fill(B2, n * n, 0.05f);
		MemorySegment mm = ar.allocate(Mtl.I, 3);
		mm.setAtIndex(Mtl.I, 0, n);
		mm.setAtIndex(Mtl.I, 1, n);
		mm.setAtIndex(Mtl.I, 2, n);
		MemorySegment cnt = ar.allocate(Mtl.I, 1);
		cnt.setAtIndex(Mtl.I, 0, n * n);
		MemorySegment gg = Mtl.size(ar, (n + 15) / 16, (n + 15) / 16, 1), pg = Mtl.size(ar, 16, 16, 1);
		MemorySegment ge = Mtl.size(ar, (n * n + 255) / 256, 1, 1), pe = Mtl.size(ar, 256, 1, 1);
		float[] host = new float[n * n];

		// (a) fully resident, ONE command buffer: 5 dispatches, no host traffic at all
		double resident = Double.MAX_VALUE;
		for (int r = 0; r < 20; r++) {
			long t = System.nanoTime();
			MemorySegment cmd = Mtl.beginCommands(queue);
			mmEnc(ar, cmd, X, W1, H, mm, gg, pg);
			addEnc(ar, cmd, H, B1, H, cnt, ge, pe);
			tanhEnc(ar, cmd, H, T, cnt, ge, pe);
			mmEnc(ar, cmd, T, W2, O, mm, gg, pg);
			addEnc(ar, cmd, O, B2, Y, cnt, ge, pe);
			Mtl.commitAndWait(cmd);
			if (r > 4) resident = Math.min(resident, (System.nanoTime() - t) / 1e6);
		}

		// (b) resident buffers, but one command buffer PER op -- isolates the submission cost
		double perSubmit = Double.MAX_VALUE;
		for (int r = 0; r < 20; r++) {
			long t = System.nanoTime();
			step(ar, cb -> mmEnc(ar, cb, X, W1, H, mm, gg, pg));
			step(ar, cb -> addEnc(ar, cb, H, B1, H, cnt, ge, pe));
			step(ar, cb -> tanhEnc(ar, cb, H, T, cnt, ge, pe));
			step(ar, cb -> mmEnc(ar, cb, T, W2, O, mm, gg, pg));
			step(ar, cb -> addEnc(ar, cb, O, B2, Y, cnt, ge, pe));
			if (r > 4) perSubmit = Math.min(perSubmit, (System.nanoTime() - t) / 1e6);
		}

		// (c) a real per-op intercept: every op uploads its operands and downloads its result
		double perOp = Double.MAX_VALUE;
		for (int r = 0; r < 20; r++) {
			long t = System.nanoTime();
			up(X, host);
			up(W1, host);
			step(ar, cb -> mmEnc(ar, cb, X, W1, H, mm, gg, pg));
			down(H, host);
			up(H, host);
			up(B1, host);
			step(ar, cb -> addEnc(ar, cb, H, B1, H, cnt, ge, pe));
			down(H, host);
			up(H, host);
			step(ar, cb -> tanhEnc(ar, cb, H, T, cnt, ge, pe));
			down(T, host);
			up(T, host);
			up(W2, host);
			step(ar, cb -> mmEnc(ar, cb, T, W2, O, mm, gg, pg));
			down(O, host);
			up(O, host);
			up(B2, host);
			step(ar, cb -> addEnc(ar, cb, O, B2, Y, cnt, ge, pe));
			down(Y, host);
			if (r > 4) perOp = Math.min(perOp, (System.nanoTime() - t) / 1e6);
		}
		System.out.printf("    n=%-5d resident 1 cmdbuf %7.3f ms | resident, 5 cmdbufs %7.3f ms (%.1fx)"
				+ " | per-op round trip %7.3f ms (%.1fx)%n", n, resident, perSubmit, perSubmit / resident, perOp,
				perOp / resident);
		for (MemorySegment b : new MemorySegment[] { X, W1, B1, H, T, W2, B2, O, Y }) Mtl.release(b);
	}

	interface Enc {

		void run(MemorySegment cb);

	}

	static void step(Arena ar, Enc e) {
		MemorySegment cb = Mtl.beginCommands(queue);
		e.run(cb);
		Mtl.commitAndWait(cb);
	}

	static void mmEnc(Arena ar, MemorySegment cb, MemorySegment a, MemorySegment b, MemorySegment c, MemorySegment dims,
			MemorySegment g, MemorySegment p) {
		MemorySegment enc = Mtl.beginEncoder(cb, gemm);
		Mtl.setBuffer(enc, a, 0, 0);
		Mtl.setBuffer(enc, b, 0, 1);
		Mtl.setBuffer(enc, c, 0, 2);
		Mtl.setBytes(enc, dims, 12, 3);
		Mtl.dispatch(enc, g, p);
		Mtl.endEncoding(enc);
	}

	static void addEnc(Arena ar, MemorySegment cb, MemorySegment a, MemorySegment b, MemorySegment c, MemorySegment cnt,
			MemorySegment g, MemorySegment p) {
		MemorySegment enc = Mtl.beginEncoder(cb, add);
		Mtl.setBuffer(enc, a, 0, 0);
		Mtl.setBuffer(enc, b, 0, 1);
		Mtl.setBuffer(enc, c, 0, 2);
		Mtl.setBytes(enc, cnt, 4, 3);
		Mtl.dispatch(enc, g, p);
		Mtl.endEncoding(enc);
	}

	static void tanhEnc(Arena ar, MemorySegment cb, MemorySegment a, MemorySegment c, MemorySegment cnt,
			MemorySegment g, MemorySegment p) {
		MemorySegment enc = Mtl.beginEncoder(cb, tanh);
		Mtl.setBuffer(enc, a, 0, 0);
		Mtl.setBuffer(enc, c, 0, 1);
		Mtl.setBytes(enc, cnt, 4, 2);
		Mtl.dispatch(enc, g, p);
		Mtl.endEncoding(enc);
	}

	static MemorySegment buf(long bytes) {
		return Mtl.buffer(dev, bytes, Mtl.SHARED);
	}

	static void fill(MemorySegment b, int n, float v) {
		MemorySegment c = Mtl.contents(b, (long) n * 4);
		for (int i = 0; i < n; i++) c.setAtIndex(Mtl.F, i, v + (i % 7) * 0.001f);
	}

	static void up(MemorySegment b, float[] host) {
		MemorySegment.copy(host, 0, Mtl.contents(b, (long) host.length * 4), Mtl.F, 0, host.length);
	}

	static void down(MemorySegment b, float[] host) {
		MemorySegment.copy(Mtl.contents(b, (long) host.length * 4), Mtl.F, 0, host, 0, host.length);
	}
}
