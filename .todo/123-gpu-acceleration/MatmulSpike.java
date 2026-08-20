import java.lang.foreign.*;

/**
 * Spike: can rontolisp's linalg:matmul run on the GPU through pure FFM (no native shim, no
 * external Java dependency)? Measures the naive Java triple loop (what linalg.lisp's defun
 * compiles to), against an NVRTC-compiled tiled GEMM, at both widths, with and without the
 * host<->device transfer in the timing.
 */
public class MatmulSpike {

	static final String SRC = """
			#define TILE 16
			template <typename T>
			__device__ void gemm(const T* A, const T* B, T* C, int M, int N, int K) {
			  __shared__ T As[TILE][TILE];
			  __shared__ T Bs[TILE][TILE];
			  int tx = threadIdx.x, ty = threadIdx.y;
			  int row = blockIdx.y * TILE + ty;
			  int col = blockIdx.x * TILE + tx;
			  T acc = 0;
			  for (int t = 0; t < (K + TILE - 1) / TILE; ++t) {
			    int ac = t * TILE + tx, br = t * TILE + ty;
			    As[ty][tx] = (row < M && ac < K) ? A[row * (long) K + ac] : (T) 0;
			    Bs[ty][tx] = (br < K && col < N) ? B[br * (long) N + col] : (T) 0;
			    __syncthreads();
			    for (int k = 0; k < TILE; ++k) acc += As[ty][k] * Bs[k][tx];
			    __syncthreads();
			  }
			  if (row < M && col < N) C[row * (long) N + col] = acc;
			}
			extern "C" __global__ void gemm_f32(const float* A, const float* B, float* C, int M, int N, int K) {
			  gemm<float>(A, B, C, M, N, K);
			}
			extern "C" __global__ void gemm_f64(const double* A, const double* B, double* C, int M, int N, int K) {
			  gemm<double>(A, B, C, M, N, K);
			}
			extern "C" __global__ void add_f64(const double* A, const double* B, double* C, long n) {
			  long i = blockIdx.x * (long) blockDim.x + threadIdx.x;
			  if (i < n) C[i] = A[i] + B[i];
			}
			""";

	static MemorySegment ctx;

	public static void main(String[] args) throws Throwable {
		Cu.check((int) Cu.cuInit.invoke(0), "cuInit");
		try (Arena a = Arena.ofConfined()) {
			MemorySegment dev = a.allocate(Cu.I);
			Cu.check((int) Cu.cuDeviceGet.invoke(dev, 0), "cuDeviceGet");
			int d = dev.get(Cu.I, 0);
			MemorySegment name = a.allocate(256);
			Cu.cuDeviceGetName.invoke(name, 256, d);
			MemorySegment attr = a.allocate(Cu.I);
			Cu.cuDeviceGetAttribute.invoke(attr, 75, d); // COMPUTE_CAPABILITY_MAJOR
			int major = attr.get(Cu.I, 0);
			Cu.cuDeviceGetAttribute.invoke(attr, 76, d);
			int minor = attr.get(Cu.I, 0);
			Cu.cuDeviceGetAttribute.invoke(attr, 16, d); // MULTIPROCESSOR_COUNT
			int sms = attr.get(Cu.I, 0);
			Cu.cuDeviceGetAttribute.invoke(attr, 41, d); // UNIFIED_ADDRESSING
			int unified = attr.get(Cu.I, 0);
			Cu.cuDeviceGetAttribute.invoke(attr, 83, d); // MANAGED_MEMORY
			int managed = attr.get(Cu.I, 0);
			System.out.printf("device: %s  sm_%d%d  SMs=%d unified-addr=%d managed=%d%n", name.getString(0), major,
					minor, sms, unified, managed);
			MemorySegment pctx = a.allocate(Cu.P);
			Cu.check((int) Cu.cuDevicePrimaryCtxRetain.invoke(pctx, d), "cuDevicePrimaryCtxRetain");
			ctx = pctx.get(Cu.P, 0);
			Cu.check((int) Cu.cuCtxSetCurrent.invoke(ctx), "cuCtxSetCurrent");

			long t0 = System.nanoTime();
			MemorySegment mod = Cu.compile(SRC, major, minor);
			System.out.printf("nvrtc compile+load: %.1f ms%n", (System.nanoTime() - t0) / 1e6);
			MemorySegment f32 = Cu.func(mod, "gemm_f32");
			MemorySegment f64 = Cu.func(mod, "gemm_f64");
			MemorySegment add64 = Cu.func(mod, "add_f64");

			for (int n : new int[] { 64, 128, 256, 512, 1024, 2048 }) {
				bench(n, f32, f64);
			}
			benchAdd(add64);
			benchLaunchOverhead(f64);
		}
	}

	static void bench(int n, MemorySegment f32, MemorySegment f64) throws Throwable {
		double[] A = new double[n * n], B = new double[n * n];
		for (int i = 0; i < A.length; i++) {
			A[i] = ((i % 13) - 6) * 0.125;
			B[i] = ((i % 7) - 3) * 0.25;
		}
		double[] cpu = new double[n * n];
		long t = System.nanoTime();
		int reps = n <= 256 ? 5 : 1;
		for (int r = 0; r < reps; r++) {
			naive(A, B, cpu, n, n, n);
		}
		double cpuMs = (System.nanoTime() - t) / 1e6 / reps;

		double[] gpu = new double[n * n];
		double[] full = gemm(f64, A, B, gpu, n, n, n, 8, true);
		double[] hot = gemm(f64, A, B, gpu, n, n, n, 8, false);

		float[] Af = new float[n * n], Bf = new float[n * n], Cf = new float[n * n];
		for (int i = 0; i < A.length; i++) {
			Af[i] = (float) A[i];
			Bf[i] = (float) B[i];
		}
		double[] full32 = gemmF(f32, Af, Bf, Cf, n, n, n, 8, true);
		double[] hot32 = gemmF(f32, Af, Bf, Cf, n, n, n, 8, false);

		double maxdiff = 0;
		for (int i = 0; i < cpu.length; i++) {
			maxdiff = Math.max(maxdiff, Math.abs(cpu[i] - gpu[i]));
		}
		System.out.printf(
				"n=%-5d cpu(java f64) %8.2f ms | gpu f64 %7.3f ms w/copy, %7.3f ms kernel | gpu f32 %7.3f / %7.3f | f64 exact-vs-cpu %s%n",
				n, cpuMs, full[0], hot[0], full32[0], hot32[0], maxdiff == 0 ? "bit-identical" : ("maxdiff " + maxdiff));
	}

	static void naive(double[] A, double[] B, double[] C, int M, int N, int K) {
		for (int i = 0; i < M; i++) {
			for (int j = 0; j < N; j++) {
				double acc = 0;
				for (int k = 0; k < K; k++) {
					acc += A[i * K + k] * B[k * N + j];
				}
				C[i * N + j] = acc;
			}
		}
	}

	/** Returns {medianMillis}. includeCopy=false times only the kernel (data already resident). */
	static double[] gemm(MemorySegment f, double[] A, double[] B, double[] C, int M, int N, int K, int reps,
			boolean includeCopy) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment pa = a.allocate(Cu.L), pb = a.allocate(Cu.L), pc = a.allocate(Cu.L);
			Cu.check((int) Cu.cuMemAlloc.invoke(pa, (long) M * K * 8), "alloc");
			Cu.check((int) Cu.cuMemAlloc.invoke(pb, (long) K * N * 8), "alloc");
			Cu.check((int) Cu.cuMemAlloc.invoke(pc, (long) M * N * 8), "alloc");
			long da = pa.get(Cu.L, 0), db = pb.get(Cu.L, 0), dc = pc.get(Cu.L, 0);
			MemorySegment ha = a.allocate(ValueLayout.JAVA_DOUBLE, A.length);
			MemorySegment hb = a.allocate(ValueLayout.JAVA_DOUBLE, B.length);
			MemorySegment hc = a.allocate(ValueLayout.JAVA_DOUBLE, C.length);
			double best = Double.MAX_VALUE;
			for (int r = 0; r < reps; r++) {
				long t = System.nanoTime();
				if (includeCopy) {
					MemorySegment.copy(A, 0, ha, ValueLayout.JAVA_DOUBLE, 0, A.length);
					MemorySegment.copy(B, 0, hb, ValueLayout.JAVA_DOUBLE, 0, B.length);
					Cu.check((int) Cu.cuMemcpyHtoD.invoke(da, ha, (long) A.length * 8), "htod");
					Cu.check((int) Cu.cuMemcpyHtoD.invoke(db, hb, (long) B.length * 8), "htod");
				}
				launch(f, da, db, dc, M, N, K, a);
				if (includeCopy) {
					Cu.check((int) Cu.cuMemcpyDtoH.invoke(hc, dc, (long) C.length * 8), "dtoh");
					MemorySegment.copy(hc, ValueLayout.JAVA_DOUBLE, 0, C, 0, C.length);
				}
				else {
					Cu.check((int) Cu.cuCtxSynchronize.invoke(), "sync");
				}
				double ms = (System.nanoTime() - t) / 1e6;
				if (r > 0) best = Math.min(best, ms);
			}
			if (!includeCopy) {
				Cu.check((int) Cu.cuMemcpyDtoH.invoke(hc, dc, (long) C.length * 8), "dtoh");
				MemorySegment.copy(hc, ValueLayout.JAVA_DOUBLE, 0, C, 0, C.length);
			}
			Cu.cuMemFree.invoke(da);
			Cu.cuMemFree.invoke(db);
			Cu.cuMemFree.invoke(dc);
			return new double[] { best };
		}
	}

	static double[] gemmF(MemorySegment f, float[] A, float[] B, float[] C, int M, int N, int K, int reps,
			boolean includeCopy) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment pa = a.allocate(Cu.L), pb = a.allocate(Cu.L), pc = a.allocate(Cu.L);
			Cu.check((int) Cu.cuMemAlloc.invoke(pa, (long) M * K * 4), "alloc");
			Cu.check((int) Cu.cuMemAlloc.invoke(pb, (long) K * N * 4), "alloc");
			Cu.check((int) Cu.cuMemAlloc.invoke(pc, (long) M * N * 4), "alloc");
			long da = pa.get(Cu.L, 0), db = pb.get(Cu.L, 0), dc = pc.get(Cu.L, 0);
			MemorySegment ha = a.allocate(ValueLayout.JAVA_FLOAT, A.length);
			MemorySegment hb = a.allocate(ValueLayout.JAVA_FLOAT, B.length);
			MemorySegment hc = a.allocate(ValueLayout.JAVA_FLOAT, C.length);
			double best = Double.MAX_VALUE;
			for (int r = 0; r < reps; r++) {
				long t = System.nanoTime();
				if (includeCopy) {
					MemorySegment.copy(A, 0, ha, ValueLayout.JAVA_FLOAT, 0, A.length);
					MemorySegment.copy(B, 0, hb, ValueLayout.JAVA_FLOAT, 0, B.length);
					Cu.check((int) Cu.cuMemcpyHtoD.invoke(da, ha, (long) A.length * 4), "htod");
					Cu.check((int) Cu.cuMemcpyHtoD.invoke(db, hb, (long) B.length * 4), "htod");
				}
				launch(f, da, db, dc, M, N, K, a);
				if (includeCopy) {
					Cu.check((int) Cu.cuMemcpyDtoH.invoke(hc, dc, (long) C.length * 4), "dtoh");
					MemorySegment.copy(hc, ValueLayout.JAVA_FLOAT, 0, C, 0, C.length);
				}
				else {
					Cu.check((int) Cu.cuCtxSynchronize.invoke(), "sync");
				}
				double ms = (System.nanoTime() - t) / 1e6;
				if (r > 0) best = Math.min(best, ms);
			}
			Cu.cuMemFree.invoke(da);
			Cu.cuMemFree.invoke(db);
			Cu.cuMemFree.invoke(dc);
			return new double[] { best };
		}
	}

	static void launch(MemorySegment f, long da, long db, long dc, int M, int N, int K, Arena a) throws Throwable {
		MemorySegment aa = a.allocate(Cu.L), bb = a.allocate(Cu.L), cc = a.allocate(Cu.L);
		aa.set(Cu.L, 0, da);
		bb.set(Cu.L, 0, db);
		cc.set(Cu.L, 0, dc);
		MemorySegment mm = a.allocate(Cu.I), nn = a.allocate(Cu.I), kk = a.allocate(Cu.I);
		mm.set(Cu.I, 0, M);
		nn.set(Cu.I, 0, N);
		kk.set(Cu.I, 0, K);
		MemorySegment params = a.allocate(Cu.P, 6);
		params.setAtIndex(Cu.P, 0, aa);
		params.setAtIndex(Cu.P, 1, bb);
		params.setAtIndex(Cu.P, 2, cc);
		params.setAtIndex(Cu.P, 3, mm);
		params.setAtIndex(Cu.P, 4, nn);
		params.setAtIndex(Cu.P, 5, kk);
		int gx = (N + 15) / 16, gy = (M + 15) / 16;
		Cu.check((int) Cu.cuLaunchKernel.invoke(f, gx, gy, 1, 16, 16, 1, 0, MemorySegment.NULL, params,
				MemorySegment.NULL), "cuLaunchKernel");
	}

	static void benchAdd(MemorySegment f) throws Throwable {
		System.out.println("\n-- element-wise add (memory bound), f64 --");
		for (int n : new int[] { 4096, 65536, 1 << 20, 1 << 24 }) {
			double[] A = new double[n], B = new double[n], C = new double[n];
			long t = System.nanoTime();
			for (int i = 0; i < n; i++) {
				C[i] = A[i] + B[i];
			}
			double cpuMs = (System.nanoTime() - t) / 1e6;
			try (Arena a = Arena.ofConfined()) {
				MemorySegment pa = a.allocate(Cu.L), pb = a.allocate(Cu.L), pc = a.allocate(Cu.L);
				Cu.cuMemAlloc.invoke(pa, (long) n * 8);
				Cu.cuMemAlloc.invoke(pb, (long) n * 8);
				Cu.cuMemAlloc.invoke(pc, (long) n * 8);
				long da = pa.get(Cu.L, 0), db = pb.get(Cu.L, 0), dc = pc.get(Cu.L, 0);
				MemorySegment ha = a.allocate(ValueLayout.JAVA_DOUBLE, n);
				double bestFull = Double.MAX_VALUE, bestHot = Double.MAX_VALUE;
				for (int r = 0; r < 8; r++) {
					long t1 = System.nanoTime();
					MemorySegment.copy(A, 0, ha, ValueLayout.JAVA_DOUBLE, 0, n);
					Cu.cuMemcpyHtoD.invoke(da, ha, (long) n * 8);
					MemorySegment.copy(B, 0, ha, ValueLayout.JAVA_DOUBLE, 0, n);
					Cu.cuMemcpyHtoD.invoke(db, ha, (long) n * 8);
					launch1d(f, da, db, dc, n, a);
					Cu.cuMemcpyDtoH.invoke(ha, dc, (long) n * 8);
					MemorySegment.copy(ha, ValueLayout.JAVA_DOUBLE, 0, C, 0, n);
					if (r > 0) bestFull = Math.min(bestFull, (System.nanoTime() - t1) / 1e6);
					t1 = System.nanoTime();
					launch1d(f, da, db, dc, n, a);
					Cu.cuCtxSynchronize.invoke();
					if (r > 0) bestHot = Math.min(bestHot, (System.nanoTime() - t1) / 1e6);
				}
				System.out.printf("n=%-9d cpu %7.3f ms | gpu %7.3f ms w/copy | %7.3f ms kernel%n", n, cpuMs, bestFull,
						bestHot);
				Cu.cuMemFree.invoke(da);
				Cu.cuMemFree.invoke(db);
				Cu.cuMemFree.invoke(dc);
			}
		}
	}

	static void launch1d(MemorySegment f, long da, long db, long dc, long n, Arena a) throws Throwable {
		MemorySegment aa = a.allocate(Cu.L), bb = a.allocate(Cu.L), cc = a.allocate(Cu.L), nn = a.allocate(Cu.L);
		aa.set(Cu.L, 0, da);
		bb.set(Cu.L, 0, db);
		cc.set(Cu.L, 0, dc);
		nn.set(Cu.L, 0, n);
		MemorySegment params = a.allocate(Cu.P, 4);
		params.setAtIndex(Cu.P, 0, aa);
		params.setAtIndex(Cu.P, 1, bb);
		params.setAtIndex(Cu.P, 2, cc);
		params.setAtIndex(Cu.P, 3, nn);
		int threads = 256;
		int blocks = (int) ((n + threads - 1) / threads);
		Cu.check((int) Cu.cuLaunchKernel.invoke(f, blocks, 1, 1, threads, 1, 1, 0, MemorySegment.NULL, params,
				MemorySegment.NULL), "cuLaunchKernel");
	}

	static void benchLaunchOverhead(MemorySegment f) throws Throwable {
		System.out.println("\n-- pure launch overhead (16x16 gemm, sync each) --");
		try (Arena a = Arena.ofConfined()) {
			MemorySegment pa = a.allocate(Cu.L);
			Cu.cuMemAlloc.invoke(pa, 16L * 16 * 8);
			long da = pa.get(Cu.L, 0);
			double best = Double.MAX_VALUE, bestAsync = Double.MAX_VALUE;
			for (int r = 0; r < 200; r++) {
				long t = System.nanoTime();
				launch(f, da, da, da, 16, 16, 16, a);
				Cu.cuCtxSynchronize.invoke();
				if (r > 5) best = Math.min(best, (System.nanoTime() - t) / 1e3);
			}
			for (int r = 0; r < 200; r++) {
				long t = System.nanoTime();
				launch(f, da, da, da, 16, 16, 16, a);
				if (r > 5) bestAsync = Math.min(bestAsync, (System.nanoTime() - t) / 1e3);
			}
			Cu.cuCtxSynchronize.invoke();
			System.out.printf("launch+sync %.1f us | launch only (async) %.1f us%n", best, bestAsync);
			Cu.cuMemFree.invoke(da);
		}
	}
}
