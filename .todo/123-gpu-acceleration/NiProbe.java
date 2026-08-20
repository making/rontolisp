import java.lang.foreign.*;
import java.nio.file.*;

/** Does a pure-FFM CUDA call survive GraalVM native-image, alongside -H:+VectorAPISupport? */
public class NiProbe {
	public static void main(String[] a) throws Throwable {
		Cu.check((int) Cu.cuInit.invoke(0), "cuInit");
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment dev = ar.allocate(Cu.I);
			Cu.cuDeviceGet.invoke(dev, 0);
			int d = dev.get(Cu.I, 0);
			MemorySegment name = ar.allocate(256);
			Cu.cuDeviceGetName.invoke(name, 256, d);
			MemorySegment pctx = ar.allocate(Cu.P);
			Cu.cuDevicePrimaryCtxRetain.invoke(pctx, d);
			Cu.cuCtxSetCurrent.invoke(pctx.get(Cu.P, 0));
			MemorySegment mod = ar.allocate(Cu.P);
			Cu.check((int) Cu.cuModuleLoadData.invoke(mod, ar.allocateFrom(Files.readString(Path.of("gemm_75.ptx")))),
					"cuModuleLoadData");
			MemorySegment f = Cu.func(mod.get(Cu.P, 0), "gemm_f64");
			int n = 512;
			long bytes = (long) n * n * 8;
			MemorySegment pa = ar.allocate(Cu.L), pb = ar.allocate(Cu.L), pc = ar.allocate(Cu.L);
			Cu.cuMemAlloc.invoke(pa, bytes);
			Cu.cuMemAlloc.invoke(pb, bytes);
			Cu.cuMemAlloc.invoke(pc, bytes);
			long da = pa.get(Cu.L, 0), db = pb.get(Cu.L, 0), dc = pc.get(Cu.L, 0);
			double[] A = new double[n * n];
			for (int i = 0; i < A.length; i++) {
				A[i] = (i % 5) * 0.5;
			}
			MemorySegment h = ar.allocate(ValueLayout.JAVA_DOUBLE, A.length);
			MemorySegment.copy(A, 0, h, ValueLayout.JAVA_DOUBLE, 0, A.length);
			Cu.cuMemcpyHtoD.invoke(da, h, bytes);
			Cu.cuMemcpyHtoD.invoke(db, h, bytes);
			double best = Double.MAX_VALUE;
			for (int r = 0; r < 8; r++) {
				long t = System.nanoTime();
				MatmulSpike.launch(f, da, db, dc, n, n, n, ar);
				Cu.cuCtxSynchronize.invoke();
				if (r > 1) best = Math.min(best, (System.nanoTime() - t) / 1e6);
			}
			double[] C = new double[n * n];
			Cu.cuMemcpyDtoH.invoke(h, dc, bytes);
			MemorySegment.copy(h, ValueLayout.JAVA_DOUBLE, 0, C, 0, C.length);
			System.out.printf("NiProbe OK on %s: n=512 f64 gemm %.3f ms, C[0]=%.3f%n", name.getString(0), best, C[0]);
		}
	}
}
