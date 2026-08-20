import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;

/**
 * Spike 2, the three questions the design hangs on:
 * (1) can a PTX text shipped in the JAR be loaded with ONLY libcuda.so.1 (driver, no toolkit)?
 * (2) does this box's unified memory remove the host<->device copy?
 * (3) how far is a hand-written tiled kernel from cuBLAS?
 */
public class PtxSpike {

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	static final AddressLayout P = ValueLayout.ADDRESS;

	public static void main(String[] args) throws Throwable {
		Cu.check((int) Cu.cuInit.invoke(0), "cuInit");
		try (Arena a = Arena.ofConfined()) {
			MemorySegment dev = a.allocate(I);
			Cu.cuDeviceGet.invoke(dev, 0);
			int d = dev.get(I, 0);
			MemorySegment pctx = a.allocate(P);
			Cu.cuDevicePrimaryCtxRetain.invoke(pctx, d);
			Cu.cuCtxSetCurrent.invoke(pctx.get(P, 0));

			// (1) PTX from the JAR, driver only
			String ptx = Files.readString(Path.of("gemm_75.ptx"));
			long t = System.nanoTime();
			MemorySegment mod = a.allocate(P);
			MemorySegment ptxSeg = a.allocateFrom(ptx);
			Cu.check((int) Cu.cuModuleLoadData.invoke(mod, ptxSeg), "cuModuleLoadData(ptx compute_75 on sm_121)");
			System.out.printf("(1) driver JIT of compute_75 PTX onto sm_121: %.1f ms -- no nvrtc, no toolkit%n",
					(System.nanoTime() - t) / 1e6);
			MemorySegment f64 = Cu.func(mod.get(P, 0), "gemm_f64");
			MemorySegment f32 = Cu.func(mod.get(P, 0), "gemm_f32");

			// (2) managed (unified) memory: no explicit copy at all
			System.out.println("\n(2) memory routes, n=1024 f64 gemm");
			int n = 1024;
			long bytes = (long) n * n * 8;
			double[] A = new double[n * n], B = new double[n * n];
			for (int i = 0; i < A.length; i++) {
				A[i] = ((i % 13) - 6) * 0.125;
				B[i] = ((i % 7) - 3) * 0.25;
			}
			// classic: device alloc + explicit HtoD/DtoH
			classic(a, f64, A, B, n);
			// managed: one pointer, CPU stores straight into it
			managed(a, f64, A, B, n);
			// pinned host memory registered with the driver (zero-copy device pointer)
			pinned(a, f64, A, B, n);

			// (3) cuBLAS, if the toolkit is present
			System.out.println("\n(3) cuBLAS comparison");
			cublas(a, f32, f64, n);
			cublas(a, f32, f64, 2048);
		}
	}

	static void classic(Arena a, MemorySegment f, double[] A, double[] B, int n) throws Throwable {
		long bytes = (long) n * n * 8;
		MemorySegment pa = a.allocate(L), pb = a.allocate(L), pc = a.allocate(L);
		Cu.cuMemAlloc.invoke(pa, bytes);
		Cu.cuMemAlloc.invoke(pb, bytes);
		Cu.cuMemAlloc.invoke(pc, bytes);
		long da = pa.get(L, 0), db = pb.get(L, 0), dc = pc.get(L, 0);
		MemorySegment h = a.allocate(ValueLayout.JAVA_DOUBLE, A.length);
		double best = Double.MAX_VALUE;
		double[] C = new double[n * n];
		for (int r = 0; r < 10; r++) {
			long t = System.nanoTime();
			MemorySegment.copy(A, 0, h, ValueLayout.JAVA_DOUBLE, 0, A.length);
			Cu.cuMemcpyHtoD.invoke(da, h, bytes);
			MemorySegment.copy(B, 0, h, ValueLayout.JAVA_DOUBLE, 0, B.length);
			Cu.cuMemcpyHtoD.invoke(db, h, bytes);
			MatmulSpike.launch(f, da, db, dc, n, n, n, a);
			Cu.cuMemcpyDtoH.invoke(h, dc, bytes);
			MemorySegment.copy(h, ValueLayout.JAVA_DOUBLE, 0, C, 0, C.length);
			if (r > 1) best = Math.min(best, (System.nanoTime() - t) / 1e6);
		}
		System.out.printf("    device alloc + HtoD/DtoH (double copy: heap->native->device) %7.3f ms  [C[0]=%.3f]%n",
				best, C[0]);
		Cu.cuMemFree.invoke(da);
		Cu.cuMemFree.invoke(db);
		Cu.cuMemFree.invoke(dc);
	}

	static void managed(Arena a, MemorySegment f, double[] A, double[] B, int n) throws Throwable {
		long bytes = (long) n * n * 8;
		MemorySegment pa = a.allocate(L), pb = a.allocate(L), pc = a.allocate(L);
		Cu.check((int) Cu.cuMemAllocManaged.invoke(pa, bytes, 1), "managed"); // ATTACH_GLOBAL
		Cu.check((int) Cu.cuMemAllocManaged.invoke(pb, bytes, 1), "managed");
		Cu.check((int) Cu.cuMemAllocManaged.invoke(pc, bytes, 1), "managed");
		long da = pa.get(L, 0), db = pb.get(L, 0), dc = pc.get(L, 0);
		MemorySegment ma = MemorySegment.ofAddress(da).reinterpret(bytes);
		MemorySegment mb = MemorySegment.ofAddress(db).reinterpret(bytes);
		MemorySegment mc = MemorySegment.ofAddress(dc).reinterpret(bytes);
		double best = Double.MAX_VALUE;
		double[] C = new double[n * n];
		for (int r = 0; r < 10; r++) {
			long t = System.nanoTime();
			MemorySegment.copy(A, 0, ma, ValueLayout.JAVA_DOUBLE, 0, A.length);
			MemorySegment.copy(B, 0, mb, ValueLayout.JAVA_DOUBLE, 0, B.length);
			MatmulSpike.launch(f, da, db, dc, n, n, n, a);
			Cu.cuCtxSynchronize.invoke();
			MemorySegment.copy(mc, ValueLayout.JAVA_DOUBLE, 0, C, 0, C.length);
			if (r > 1) best = Math.min(best, (System.nanoTime() - t) / 1e6);
		}
		System.out.printf("    cuMemAllocManaged (one copy: heap->managed, GPU reads it)     %7.3f ms  [C[0]=%.3f]%n",
				best, C[0]);
		// and with the data ALREADY resident (the residency design): kernel only
		best = Double.MAX_VALUE;
		for (int r = 0; r < 10; r++) {
			long t = System.nanoTime();
			MatmulSpike.launch(f, da, db, dc, n, n, n, a);
			Cu.cuCtxSynchronize.invoke();
			if (r > 1) best = Math.min(best, (System.nanoTime() - t) / 1e6);
		}
		System.out.printf("    ... same buffers, already resident (kernel only)              %7.3f ms%n", best);
		Cu.cuMemFree.invoke(da);
		Cu.cuMemFree.invoke(db);
		Cu.cuMemFree.invoke(dc);
	}

	static void pinned(Arena a, MemorySegment f, double[] A, double[] B, int n) throws Throwable {
		MethodHandle reg, getDev;
		try {
			reg = Cu.h(Cu.DRIVER, "cuMemHostRegister_v2", FunctionDescriptor.of(I, P, L, I));
			getDev = Cu.h(Cu.DRIVER, "cuMemHostGetDevicePointer_v2", FunctionDescriptor.of(I, P, P, I));
		}
		catch (Throwable t) {
			System.out.println("    (no cuMemHostRegister)");
			return;
		}
		long bytes = (long) n * n * 8;
		MemorySegment ha = a.allocate(bytes, 4096), hb = a.allocate(bytes, 4096), hc = a.allocate(bytes, 4096);
		int rc = (int) reg.invoke(ha, bytes, 2); // CU_MEMHOSTREGISTER_DEVICEMAP
		if (rc != 0) {
			System.out.println("    cuMemHostRegister failed: " + rc);
			return;
		}
		reg.invoke(hb, bytes, 2);
		reg.invoke(hc, bytes, 2);
		MemorySegment pa = a.allocate(L), pb = a.allocate(L), pc = a.allocate(L);
		getDev.invoke(pa, ha, 0);
		getDev.invoke(pb, hb, 0);
		getDev.invoke(pc, hc, 0);
		long da = pa.get(L, 0), db = pb.get(L, 0), dc = pc.get(L, 0);
		double best = Double.MAX_VALUE;
		double[] C = new double[n * n];
		for (int r = 0; r < 10; r++) {
			long t = System.nanoTime();
			MemorySegment.copy(A, 0, ha, ValueLayout.JAVA_DOUBLE, 0, A.length);
			MemorySegment.copy(B, 0, hb, ValueLayout.JAVA_DOUBLE, 0, B.length);
			MatmulSpike.launch(f, da, db, dc, n, n, n, a);
			Cu.cuCtxSynchronize.invoke();
			MemorySegment.copy(hc, ValueLayout.JAVA_DOUBLE, 0, C, 0, C.length);
			if (r > 1) best = Math.min(best, (System.nanoTime() - t) / 1e6);
		}
		System.out.printf("    pinned host + DEVICEMAP (GPU reads host memory directly)      %7.3f ms  [C[0]=%.3f]%n",
				best, C[0]);
	}

	static void cublas(Arena a, MemorySegment kf32, MemorySegment kf64, int n) throws Throwable {
		SymbolLookup lk;
		try {
			lk = SymbolLookup.libraryLookup("libcublas.so.13", Arena.global());
		}
		catch (Throwable t) {
			System.out.println("    libcublas.so.13 not found -- toolkit absent");
			return;
		}
		MethodHandle create = Cu.h(lk, "cublasCreate_v2", FunctionDescriptor.of(I, P));
		MethodHandle sgemm = Cu.h(lk, "cublasSgemm_v2",
				FunctionDescriptor.of(I, P, I, I, I, I, I, P, L, I, L, I, P, L, I));
		MethodHandle dgemm = Cu.h(lk, "cublasDgemm_v2",
				FunctionDescriptor.of(I, P, I, I, I, I, I, P, L, I, L, I, P, L, I));
		MemorySegment ph = a.allocate(P);
		int rc = (int) create.invoke(ph);
		if (rc != 0) {
			System.out.println("    cublasCreate failed " + rc);
			return;
		}
		MemorySegment h = ph.get(P, 0);
		for (boolean dbl : new boolean[] { false, true }) {
			int w = dbl ? 8 : 4;
			long bytes = (long) n * n * w;
			MemorySegment pa = a.allocate(L), pb = a.allocate(L), pc = a.allocate(L);
			Cu.cuMemAlloc.invoke(pa, bytes);
			Cu.cuMemAlloc.invoke(pb, bytes);
			Cu.cuMemAlloc.invoke(pc, bytes);
			long da = pa.get(L, 0), db = pb.get(L, 0), dc = pc.get(L, 0);
			MemorySegment alpha = a.allocate(dbl ? 8 : 4), beta = a.allocate(dbl ? 8 : 4);
			if (dbl) {
				alpha.set(ValueLayout.JAVA_DOUBLE, 0, 1.0);
				beta.set(ValueLayout.JAVA_DOUBLE, 0, 0.0);
			}
			else {
				alpha.set(ValueLayout.JAVA_FLOAT, 0, 1.0f);
				beta.set(ValueLayout.JAVA_FLOAT, 0, 0.0f);
			}
			double best = Double.MAX_VALUE, mine = Double.MAX_VALUE;
			for (int r = 0; r < 12; r++) {
				long t = System.nanoTime();
				// row-major C = A*B  <=>  column-major C^T = B^T * A^T
				int res = dbl ? (int) dgemm.invoke(h, 0, 0, n, n, n, alpha, db, n, da, n, beta, dc, n)
						: (int) sgemm.invoke(h, 0, 0, n, n, n, alpha, db, n, da, n, beta, dc, n);
				if (res != 0) {
					System.out.println("    gemm status " + res);
					break;
				}
				Cu.cuCtxSynchronize.invoke();
				if (r > 2) best = Math.min(best, (System.nanoTime() - t) / 1e6);
				t = System.nanoTime();
				MatmulSpike.launch(dbl ? kf64 : kf32, da, db, dc, n, n, n, a);
				Cu.cuCtxSynchronize.invoke();
				if (r > 2) mine = Math.min(mine, (System.nanoTime() - t) / 1e6);
			}
			double gflops = 2.0 * n * n * n / (best * 1e6);
			System.out.printf("    n=%-5d %s: cuBLAS %7.3f ms (%6.1f GFLOP/s) | tiled PTX kernel %7.3f ms (%.1fx)%n",
					n, dbl ? "f64" : "f32", best, gflops, mine, mine / best);
			Cu.cuMemFree.invoke(da);
			Cu.cuMemFree.invoke(db);
			Cu.cuMemFree.invoke(dc);
		}
	}
}
