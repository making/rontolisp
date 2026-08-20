import java.lang.foreign.*;

/** Where is the crossover? Round-trip cost of ONE intercepted matmul, small shapes. */
public class TinySpike {
	public static void main(String[] x) throws Throwable {
		Cu.check((int) Cu.cuInit.invoke(0), "cuInit");
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment dev = ar.allocate(Cu.I);
			Cu.cuDeviceGet.invoke(dev, 0);
			MemorySegment pctx = ar.allocate(Cu.P);
			Cu.cuDevicePrimaryCtxRetain.invoke(pctx, dev.get(Cu.I, 0));
			Cu.cuCtxSetCurrent.invoke(pctx.get(Cu.P, 0));
			MemorySegment mod = Cu.compile(MatmulSpike.SRC, 12, 1);
			MemorySegment f = Cu.func(mod, "gemm_f64");
			System.out.println("one intercepted linalg:matmul, host->device->kernel->host, f64:");
			for (int[] s : new int[][] { { 8, 8, 8 }, { 32, 8, 8 }, { 32, 32, 32 }, { 64, 64, 64 }, { 128, 128, 128 } }) {
				int M = s[0], K = s[1], N = s[2];
				MemorySegment pa = ar.allocate(Cu.L), pb = ar.allocate(Cu.L), pc = ar.allocate(Cu.L);
				Cu.cuMemAlloc.invoke(pa, Math.max(4096L, (long) M * K * 8));
				Cu.cuMemAlloc.invoke(pb, Math.max(4096L, (long) K * N * 8));
				Cu.cuMemAlloc.invoke(pc, Math.max(4096L, (long) M * N * 8));
				long da = pa.get(Cu.L, 0), db = pb.get(Cu.L, 0), dc = pc.get(Cu.L, 0);
				double[] A = new double[M * K], B = new double[K * N], C = new double[M * N];
				MemorySegment ha = ar.allocate(ValueLayout.JAVA_DOUBLE, Math.max(A.length, C.length));
				double best = Double.MAX_VALUE;
				for (int r = 0; r < 300; r++) {
					long t = System.nanoTime();
					MemorySegment.copy(A, 0, ha, ValueLayout.JAVA_DOUBLE, 0, A.length);
					Cu.cuMemcpyHtoD.invoke(da, ha, (long) A.length * 8);
					MemorySegment.copy(B, 0, ha, ValueLayout.JAVA_DOUBLE, 0, B.length);
					Cu.cuMemcpyHtoD.invoke(db, ha, (long) B.length * 8);
					MatmulSpike.launch(f, da, db, dc, M, N, K, ar);
					Cu.cuMemcpyDtoH.invoke(ha, dc, (long) C.length * 8);
					MemorySegment.copy(ha, ValueLayout.JAVA_DOUBLE, 0, C, 0, C.length);
					if (r > 20) best = Math.min(best, (System.nanoTime() - t) / 1e3);
				}
				System.out.printf("    %3dx%-3d @ %3dx%-3d  %6.1f us%n", M, K, K, N, best);
				Cu.cuMemFree.invoke(da);
				Cu.cuMemFree.invoke(db);
				Cu.cuMemFree.invoke(dc);
			}
		}
	}
}
