import java.lang.foreign.*;

/**
 * Does an objc_msgSend downcall survive GraalVM native-image next to -H:+VectorAPISupport?
 * The interpreter's --gpu would ship inside the native binary, and todo-102 found
 * VectorAPISupport and SharedArenaSupport mutually exclusive, so this asks whether Metal
 * re-enters that fight. Mirrors NiProbe.java on the CUDA side.
 */
public class MtlNiProbe {

	public static void main(String[] args) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment dev = Mtl.device();
			String name = Mtl.fromNsString(Mtl.msg(dev, "name"));
			MemorySegment lib = Mtl.library(dev, MtlSpike.SRC, MemorySegment.NULL);
			MemorySegment pso = Mtl.pipeline(dev, lib, "gemm_f32");
			MemorySegment queue = Mtl.queue(dev);
			int n = 512;
			long bytes = (long) n * n * 4;
			MemorySegment da = Mtl.buffer(dev, bytes, Mtl.SHARED), db = Mtl.buffer(dev, bytes, Mtl.SHARED),
					dc = Mtl.buffer(dev, bytes, Mtl.SHARED);
			MemorySegment ca = Mtl.contents(da, bytes), cb = Mtl.contents(db, bytes), cc = Mtl.contents(dc, bytes);
			for (int i = 0; i < n * n; i++) {
				ca.setAtIndex(Mtl.F, i, ((i % 13) - 6) * 0.125f);
				cb.setAtIndex(Mtl.F, i, ((i % 7) - 3) * 0.25f);
			}
			MemorySegment dims = ar.allocate(Mtl.I, 3);
			for (int i = 0; i < 3; i++) dims.setAtIndex(Mtl.I, i, n);
			double best = Double.MAX_VALUE;
			for (int r = 0; r < 12; r++) {
				long t = System.nanoTime();
				MemorySegment cmd = Mtl.beginCommands(queue);
				MemorySegment enc = Mtl.beginEncoder(cmd, pso);
				Mtl.setBuffer(enc, da, 0, 0);
				Mtl.setBuffer(enc, db, 0, 1);
				Mtl.setBuffer(enc, dc, 0, 2);
				Mtl.setBytes(enc, dims, 12, 3);
				Mtl.dispatch(enc, Mtl.size(ar, (n + 15) / 16, (n + 15) / 16, 1), Mtl.size(ar, 16, 16, 1));
				Mtl.endEncoding(enc);
				Mtl.commitAndWait(cmd);
				if (r > 2) best = Math.min(best, (System.nanoTime() - t) / 1e6);
			}
			System.out.printf("MtlNiProbe OK on %s: n=%d f32 gemm %.3f ms, C[0]=%.3f%n", name, n, best,
					cc.getAtIndex(Mtl.F, 0));
		}
		Mtl.poolPop.invokeExact(pool);
	}
}
