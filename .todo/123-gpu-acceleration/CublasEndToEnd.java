import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Is cuBLAS worth the toolkit dependency? PtxSpike's (3) compares KERNELS, which is the
 * wrong question for phase 1: there the copies are on the clock too. This runs both
 * kernels through both phases -- with copies (phase 1-2) and already resident (phase 3) --
 * at both widths, so the 7x kernel gap can be read as what it is actually worth end to end.
 */
public class CublasEndToEnd {

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	static final AddressLayout P = ValueLayout.ADDRESS;

	public static void main(String[] x) throws Throwable {
		Cu.check((int) Cu.cuInit.invoke(0), "cuInit");
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment dev = ar.allocate(I);
			Cu.cuDeviceGet.invoke(dev, 0);
			MemorySegment pctx = ar.allocate(P);
			Cu.cuDevicePrimaryCtxRetain.invoke(pctx, dev.get(I, 0));
			Cu.cuCtxSetCurrent.invoke(pctx.get(P, 0));
			MemorySegment mod = Cu.compile(MatmulSpike.SRC, 12, 1);
			MemorySegment[] mine = { Cu.func(mod, "gemm_f32"), Cu.func(mod, "gemm_f64") };

			SymbolLookup lk = SymbolLookup.libraryLookup("libcublas.so.13", Arena.global());
			MethodHandle create = Cu.h(lk, "cublasCreate_v2", FunctionDescriptor.of(I, P));
			MethodHandle[] gemm = {
					Cu.h(lk, "cublasSgemm_v2", FunctionDescriptor.of(I, P, I, I, I, I, I, P, L, I, L, I, P, L, I)),
					Cu.h(lk, "cublasDgemm_v2", FunctionDescriptor.of(I, P, I, I, I, I, I, P, L, I, L, I, P, L, I)) };
			MemorySegment ph = ar.allocate(P);
			create.invoke(ph);
			MemorySegment h = ph.get(P, 0);

			for (int wi = 0; wi < 2; wi++) {
				boolean dbl = wi == 1;
				int w = dbl ? 8 : 4;
				System.out.println((dbl ? "f64" : "f32") + "  (ms/call)");
				System.out.println("    n     ours+copy  cuBLAS+copy  ratio |  ours res.  cuBLAS res.  ratio");
				for (int n : new int[] { 256, 512, 1024, 2048 }) {
					long bytes = (long) n * n * w;
					MemorySegment pa = ar.allocate(L), pb = ar.allocate(L), pc = ar.allocate(L);
					Cu.cuMemAlloc.invoke(pa, bytes);
					Cu.cuMemAlloc.invoke(pb, bytes);
					Cu.cuMemAlloc.invoke(pc, bytes);
					long da = pa.get(L, 0), db = pb.get(L, 0), dc = pc.get(L, 0);
					MemorySegment hs = ar.allocate(bytes);
					double[] hd = new double[dbl ? n * n : 0];
					float[] hf = new float[dbl ? 0 : n * n];
					MemorySegment alpha = ar.allocate(w), beta = ar.allocate(w);
					if (dbl) {
						alpha.set(ValueLayout.JAVA_DOUBLE, 0, 1.0);
						beta.set(ValueLayout.JAVA_DOUBLE, 0, 0.0);
					}
					else {
						alpha.set(ValueLayout.JAVA_FLOAT, 0, 1.0f);
						beta.set(ValueLayout.JAVA_FLOAT, 0, 0.0f);
					}
					double[] r = new double[4]; // ours+copy, cublas+copy, ours resident, cublas resident
					java.util.Arrays.fill(r, Double.MAX_VALUE);
					int reps = n >= 2048 ? 8 : 20;
					for (int rep = 0; rep < reps; rep++) {
						for (int route = 0; route < 4; route++) {
							boolean copy = route < 2, useCublas = (route & 1) == 1;
							long t = System.nanoTime();
							if (copy) {
								if (dbl) {
									MemorySegment.copy(hd, 0, hs, ValueLayout.JAVA_DOUBLE, 0, hd.length);
								}
								else {
									MemorySegment.copy(hf, 0, hs, ValueLayout.JAVA_FLOAT, 0, hf.length);
								}
								Cu.cuMemcpyHtoD.invoke(da, hs, bytes);
								Cu.cuMemcpyHtoD.invoke(db, hs, bytes);
							}
							if (useCublas) {
								gemm[wi].invoke(h, 0, 0, n, n, n, alpha, db, n, da, n, beta, dc, n);
							}
							else {
								MatmulSpike.launch(mine[wi], da, db, dc, n, n, n, ar);
							}
							if (copy) {
								Cu.cuMemcpyDtoH.invoke(hs, dc, bytes);
								if (dbl) {
									MemorySegment.copy(hs, ValueLayout.JAVA_DOUBLE, 0, hd, 0, hd.length);
								}
								else {
									MemorySegment.copy(hs, ValueLayout.JAVA_FLOAT, 0, hf, 0, hf.length);
								}
							}
							else {
								Cu.cuCtxSynchronize.invoke();
							}
							if (rep > 2) r[route] = Math.min(r[route], (System.nanoTime() - t) / 1e6);
						}
					}
					System.out.printf("    %-5d %9.3f %12.3f %6.1fx | %9.3f %12.3f %6.1fx%n", n, r[0], r[1],
							r[0] / r[1], r[2], r[3], r[2] / r[3]);
					Cu.cuMemFree.invoke(da);
					Cu.cuMemFree.invoke(db);
					Cu.cuMemFree.invoke(dc);
				}
			}
		}
	}
}
