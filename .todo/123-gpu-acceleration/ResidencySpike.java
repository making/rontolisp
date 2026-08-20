import java.lang.foreign.*;

/**
 * Spike 3, the design crux from .todo/123: does a per-call intercept pay, or must the array
 * LIVE on the device? Runs the same 5-op chain (matmul, add, tanh, matmul, add) two ways.
 * Plus the batched rank-3 product that --simd does not intercept at all.
 */
public class ResidencySpike {

	static final String SRC = """
			#define TILE 16
			extern "C" __global__ void bgemm_f32(const float* A, const float* B, float* C,
			                                     int M, int N, int K, int batch) {
			  __shared__ float As[TILE][TILE];
			  __shared__ float Bs[TILE][TILE];
			  int tx = threadIdx.x, ty = threadIdx.y;
			  int row = blockIdx.y * TILE + ty, col = blockIdx.x * TILE + tx, b = blockIdx.z;
			  const float* a = A + (long) b * M * K;
			  const float* bb = B + (long) b * K * N;
			  float* c = C + (long) b * M * N;
			  float acc = 0;
			  for (int t = 0; t < (K + TILE - 1) / TILE; ++t) {
			    int ac = t * TILE + tx, br = t * TILE + ty;
			    As[ty][tx] = (row < M && ac < K) ? a[row * (long) K + ac] : 0.f;
			    Bs[ty][tx] = (br < K && col < N) ? bb[br * (long) N + col] : 0.f;
			    __syncthreads();
			    for (int k = 0; k < TILE; ++k) acc += As[ty][k] * Bs[k][tx];
			    __syncthreads();
			  }
			  if (row < M && col < N) c[row * (long) N + col] = acc;
			}
			extern "C" __global__ void addv(const float* A, const float* B, float* C, long n) {
			  long i = blockIdx.x * (long) blockDim.x + threadIdx.x;
			  if (i < n) C[i] = A[i] + B[i];
			}
			extern "C" __global__ void tanhv(const float* A, float* C, long n) {
			  long i = blockIdx.x * (long) blockDim.x + threadIdx.x;
			  if (i < n) C[i] = tanhf(A[i]);
			}
			""";

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	static final AddressLayout P = ValueLayout.ADDRESS;

	public static void main(String[] a) throws Throwable {
		Cu.check((int) Cu.cuInit.invoke(0), "cuInit");
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment dev = ar.allocate(I);
			Cu.cuDeviceGet.invoke(dev, 0);
			int d = dev.get(I, 0);
			MemorySegment pctx = ar.allocate(P);
			Cu.cuDevicePrimaryCtxRetain.invoke(pctx, d);
			Cu.cuCtxSetCurrent.invoke(pctx.get(P, 0));
			MemorySegment mod = Cu.compile(SRC, 12, 1);
			MemorySegment bgemm = Cu.func(mod, "bgemm_f32");
			MemorySegment addv = Cu.func(mod, "addv");
			MemorySegment tanhv = Cu.func(mod, "tanhv");

			// --- batched rank-3 product: (B*H, N, D) x (B*H, D, N), a real attention shape
			System.out.println("-- batched rank-3 matmul, the shape --simd never intercepts --");
			for (int[] cfg : new int[][] { { 4, 6, 64, 32 }, { 8, 6, 256, 64 }, { 16, 12, 512, 64 } }) {
				int batch = cfg[0] * cfg[1], N = cfg[2], D = cfg[3];
				long flops = 2L * batch * N * N * D;
				MemorySegment pa = ar.allocate(L), pb = ar.allocate(L), pc = ar.allocate(L);
				Cu.cuMemAlloc.invoke(pa, (long) batch * N * D * 4);
				Cu.cuMemAlloc.invoke(pb, (long) batch * D * N * 4);
				Cu.cuMemAlloc.invoke(pc, (long) batch * N * N * 4);
				long da = pa.get(L, 0), db = pb.get(L, 0), dc = pc.get(L, 0);
				double best = Double.MAX_VALUE;
				for (int r = 0; r < 10; r++) {
					long t = System.nanoTime();
					launchB(bgemm, da, db, dc, N, N, D, batch, ar);
					Cu.cuCtxSynchronize.invoke();
					if (r > 2) best = Math.min(best, (System.nanoTime() - t) / 1e6);
				}
				// what the scalar defun would cost: rontolisp's %la-matmul-nd is one flop per
				// inner step; measure the equivalent Java triple loop at one batch and scale
				double cpuMs = javaBatched(batch, N, D);
				System.out.printf("    b*h=%-4d n=%-4d d=%-3d  java f64 %9.1f ms | gpu %7.3f ms (%.0fx, %.0f GFLOP/s)%n",
						batch, N, D, cpuMs, best, cpuMs / best, flops / (best * 1e6));
				Cu.cuMemFree.invoke(da);
				Cu.cuMemFree.invoke(db);
				Cu.cuMemFree.invoke(dc);
			}

			// --- residency: 5-op chain, resident vs one round trip per op
			System.out.println("\n-- the residency question: (x@w1 + b) -> tanh -> (@w2 + b2) --");
			for (int n : new int[] { 128, 512, 1024 }) {
				chain(ar, bgemm, addv, tanhv, n);
			}
		}
	}

	static double javaBatched(int batch, int N, int D) {
		double[] A = new double[N * D], B = new double[D * N], C = new double[N * N];
		long t = System.nanoTime();
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < N; j++) {
				double acc = 0;
				for (int k = 0; k < D; k++) {
					acc += A[i * D + k] * B[k * N + j];
				}
				C[i * N + j] = acc;
			}
		}
		return (System.nanoTime() - t) / 1e6 * batch;
	}

	static void chain(Arena ar, MemorySegment bgemm, MemorySegment addv, MemorySegment tanhv, int n) throws Throwable {
		long els = (long) n * n, bytes = els * 4;
		long[] p = new long[6];
		for (int i = 0; i < 6; i++) {
			MemorySegment q = ar.allocate(L);
			Cu.cuMemAlloc.invoke(q, bytes);
			p[i] = q.get(L, 0);
		}
		float[] host = new float[(int) els];
		MemorySegment h = ar.allocate(ValueLayout.JAVA_FLOAT, els);

		double resident = Double.MAX_VALUE, perOp = Double.MAX_VALUE;
		for (int r = 0; r < 10; r++) {
			// resident: one upload, five kernels, one download
			long t = System.nanoTime();
			MemorySegment.copy(host, 0, h, ValueLayout.JAVA_FLOAT, 0, host.length);
			Cu.cuMemcpyHtoD.invoke(p[0], h, bytes);
			launchB(bgemm, p[0], p[1], p[2], n, n, n, 1, ar);
			launch1(addv, p[2], p[1], p[3], els, ar);
			launch1u(tanhv, p[3], p[4], els, ar);
			launchB(bgemm, p[4], p[1], p[5], n, n, n, 1, ar);
			launch1(addv, p[5], p[1], p[0], els, ar);
			Cu.cuMemcpyDtoH.invoke(h, p[0], bytes);
			MemorySegment.copy(h, ValueLayout.JAVA_FLOAT, 0, host, 0, host.length);
			if (r > 2) resident = Math.min(resident, (System.nanoTime() - t) / 1e6);

			// per-op intercept: every op uploads its operands and downloads its result
			t = System.nanoTime();
			for (int op = 0; op < 5; op++) {
				MemorySegment.copy(host, 0, h, ValueLayout.JAVA_FLOAT, 0, host.length);
				Cu.cuMemcpyHtoD.invoke(p[0], h, bytes);
				Cu.cuMemcpyHtoD.invoke(p[1], h, bytes);
				if (op == 0 || op == 3) {
					launchB(bgemm, p[0], p[1], p[2], n, n, n, 1, ar);
				}
				else if (op == 2) {
					launch1u(tanhv, p[0], p[2], els, ar);
				}
				else {
					launch1(addv, p[0], p[1], p[2], els, ar);
				}
				Cu.cuMemcpyDtoH.invoke(h, p[2], bytes);
				MemorySegment.copy(h, ValueLayout.JAVA_FLOAT, 0, host, 0, host.length);
			}
			if (r > 2) perOp = Math.min(perOp, (System.nanoTime() - t) / 1e6);
		}
		System.out.printf("    n=%-5d resident (1 up, 5 kernels, 1 down) %7.3f ms | per-op round trip %7.3f ms (%.1fx worse)%n",
				n, resident, perOp, perOp / resident);
		for (long q : p) {
			Cu.cuMemFree.invoke(q);
		}
	}

	static void launchB(MemorySegment f, long da, long db, long dc, int M, int N, int K, int batch, Arena a)
			throws Throwable {
		MemorySegment aa = a.allocate(L), bb = a.allocate(L), cc = a.allocate(L);
		aa.set(L, 0, da);
		bb.set(L, 0, db);
		cc.set(L, 0, dc);
		MemorySegment mm = a.allocate(I), nn = a.allocate(I), kk = a.allocate(I), bt = a.allocate(I);
		mm.set(I, 0, M);
		nn.set(I, 0, N);
		kk.set(I, 0, K);
		bt.set(I, 0, batch);
		MemorySegment params = a.allocate(P, 7);
		MemorySegment[] ps = { aa, bb, cc, mm, nn, kk, bt };
		for (int i = 0; i < 7; i++) {
			params.setAtIndex(P, i, ps[i]);
		}
		Cu.check((int) Cu.cuLaunchKernel.invoke(f, (N + 15) / 16, (M + 15) / 16, batch, 16, 16, 1, 0,
				MemorySegment.NULL, params, MemorySegment.NULL), "bgemm");
	}

	static void launch1(MemorySegment f, long da, long db, long dc, long n, Arena a) throws Throwable {
		MemorySegment aa = a.allocate(L), bb = a.allocate(L), cc = a.allocate(L), nn = a.allocate(L);
		aa.set(L, 0, da);
		bb.set(L, 0, db);
		cc.set(L, 0, dc);
		nn.set(L, 0, n);
		MemorySegment params = a.allocate(P, 4);
		MemorySegment[] ps = { aa, bb, cc, nn };
		for (int i = 0; i < 4; i++) {
			params.setAtIndex(P, i, ps[i]);
		}
		Cu.check((int) Cu.cuLaunchKernel.invoke(f, (int) ((n + 255) / 256), 1, 1, 256, 1, 1, 0, MemorySegment.NULL,
				params, MemorySegment.NULL), "addv");
	}

	static void launch1u(MemorySegment f, long da, long dc, long n, Arena a) throws Throwable {
		MemorySegment aa = a.allocate(L), cc = a.allocate(L), nn = a.allocate(L);
		aa.set(L, 0, da);
		cc.set(L, 0, dc);
		nn.set(L, 0, n);
		MemorySegment params = a.allocate(P, 3);
		MemorySegment[] ps = { aa, cc, nn };
		for (int i = 0; i < 3; i++) {
			params.setAtIndex(P, i, ps[i]);
		}
		Cu.check((int) Cu.cuLaunchKernel.invoke(f, (int) ((n + 255) / 256), 1, 1, 256, 1, 1, 0, MemorySegment.NULL,
				params, MemorySegment.NULL), "tanhv");
	}
}
