import java.lang.foreign.*;
import java.util.Random;

/** Are the tiled kernel and MPS really bit-identical, or is the comparison lying? */
public class MtlMpsDiff {
	public static void main(String[] x) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			MtlMps.dev = Mtl.device();
			MtlMps.queue = Mtl.queue(MtlMps.dev);
			MemorySegment lib = Mtl.library(MtlMps.dev, MtlSpike.SRC, MemorySegment.NULL);
			MemorySegment ours = Mtl.pipeline(MtlMps.dev, lib, "gemm_f32");
			for (int n : new int[] { 256, 1024 }) {
				long bytes = (long) n * n * 4;
				MemorySegment da = Mtl.buffer(MtlMps.dev, bytes, Mtl.SHARED), db = Mtl.buffer(MtlMps.dev, bytes, Mtl.SHARED),
						c1 = Mtl.buffer(MtlMps.dev, bytes, Mtl.SHARED), c2 = Mtl.buffer(MtlMps.dev, bytes, Mtl.SHARED);
				Random rnd = new Random(5);
				MemorySegment ca = Mtl.contents(da, bytes), cb = Mtl.contents(db, bytes);
				double[] A = new double[n * n], B = new double[n * n], oracle = new double[n * n];
				for (int i = 0; i < n * n; i++) {
					A[i] = rnd.nextGaussian();
					B[i] = rnd.nextGaussian();
					ca.setAtIndex(Mtl.F, i, (float) A[i]);
					cb.setAtIndex(Mtl.F, i, (float) B[i]);
				}
				MtlSpike.naive(A, B, oracle, n, n, n);
				MemorySegment dims = ar.allocate(Mtl.I, 3);
				for (int i = 0; i < 3; i++) dims.setAtIndex(Mtl.I, i, n);
				MemorySegment cmd = Mtl.beginCommands(MtlMps.queue);
				MemorySegment enc = Mtl.beginEncoder(cmd, ours);
				Mtl.setBuffer(enc, da, 0, 0);
				Mtl.setBuffer(enc, db, 0, 1);
				Mtl.setBuffer(enc, c1, 0, 2);
				Mtl.setBytes(enc, dims, 12, 3);
				Mtl.dispatch(enc, Mtl.size(ar, (n + 15) / 16, (n + 15) / 16, 1), Mtl.size(ar, 16, 16, 1));
				Mtl.endEncoding(enc);
				Mtl.commitAndWait(cmd);
				MemorySegment desc = MtlMps.descriptor(n, n, MtlMps.rowBytesFor(n));
				MemorySegment mm = MtlMps.multiplication(n, n, n);
				cmd = Mtl.beginCommands(MtlMps.queue);
				MtlMps.encode(mm, cmd, MtlMps.matrix(da, desc), MtlMps.matrix(db, desc), MtlMps.matrix(c2, desc));
				Mtl.commitAndWait(cmd);
				MemorySegment o1 = Mtl.contents(c1, bytes), o2 = Mtl.contents(c2, bytes);
				int differ = 0;
				double scale = 0, r1 = 0, r2 = 0, d12 = 0;
				for (double v : oracle) scale = Math.max(scale, Math.abs(v));
				for (int i = 0; i < n * n; i++) {
					float a = o1.getAtIndex(Mtl.F, i), b = o2.getAtIndex(Mtl.F, i);
					if (Float.floatToRawIntBits(a) != Float.floatToRawIntBits(b)) differ++;
					r1 = Math.max(r1, Math.abs(oracle[i] - a) / scale);
					r2 = Math.max(r2, Math.abs(oracle[i] - b) / scale);
					d12 = Math.max(d12, Math.abs((double) a - b) / scale);
				}
				System.out.printf("n=%-5d differing cells %d/%d | ours vs oracle %.3g | MPS vs oracle %.3g | ours vs MPS %.3g%n",
						n, differ, n * n, r1, r2, d12);
			}
		}
		Mtl.poolPop.invokeExact(pool);
	}
}
