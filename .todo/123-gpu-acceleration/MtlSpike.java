import java.lang.foreign.*;

/**
 * Spike: can rontolisp's linalg:matmul run on an Apple GPU through pure FFM (no Swift shim, no
 * bundled dylib, no external Java dependency)? Mirrors MatmulSpike.java shape for shape so the
 * two machines' tables can be read side by side -- with the one structural difference that MSL
 * has no double, so there is no f64 column to fill.
 */
public class MtlSpike {

	static final String SRC = """
			#include <metal_stdlib>
			using namespace metal;
			#define TILE 16

			kernel void gemm_f32(device const float* A [[buffer(0)]],
			                     device const float* B [[buffer(1)]],
			                     device float* C [[buffer(2)]],
			                     constant int* dims [[buffer(3)]],
			                     uint2 tid [[thread_position_in_threadgroup]],
			                     uint2 gp [[threadgroup_position_in_grid]]) {
			  threadgroup float As[TILE][TILE];
			  threadgroup float Bs[TILE][TILE];
			  int M = dims[0], N = dims[1], K = dims[2];
			  int tx = int(tid.x), ty = int(tid.y);
			  int row = int(gp.y) * TILE + ty;
			  int col = int(gp.x) * TILE + tx;
			  float acc = 0.0f;
			  int tiles = (K + TILE - 1) / TILE;
			  for (int t = 0; t < tiles; ++t) {
			    int ac = t * TILE + tx, br = t * TILE + ty;
			    As[ty][tx] = (row < M && ac < K) ? A[row * K + ac] : 0.0f;
			    Bs[ty][tx] = (br < K && col < N) ? B[br * N + col] : 0.0f;
			    threadgroup_barrier(mem_flags::mem_threadgroup);
			    for (int k = 0; k < TILE; ++k) acc += As[ty][k] * Bs[k][tx];
			    threadgroup_barrier(mem_flags::mem_threadgroup);
			  }
			  if (row < M && col < N) C[row * N + col] = acc;
			}

			kernel void gemm3_f32(device const float* A [[buffer(0)]],
			                      device const float* B [[buffer(1)]],
			                      device float* C [[buffer(2)]],
			                      constant int* dims [[buffer(3)]],
			                      uint3 tid [[thread_position_in_threadgroup]],
			                      uint3 gp [[threadgroup_position_in_grid]]) {
			  threadgroup float As[TILE][TILE];
			  threadgroup float Bs[TILE][TILE];
			  int M = dims[0], N = dims[1], K = dims[2];
			  int b = int(gp.z);
			  const device float* Ab = A + (long) b * M * K;
			  const device float* Bb = B + (long) b * K * N;
			  device float* Cb = C + (long) b * M * N;
			  int tx = int(tid.x), ty = int(tid.y);
			  int row = int(gp.y) * TILE + ty;
			  int col = int(gp.x) * TILE + tx;
			  float acc = 0.0f;
			  int tiles = (K + TILE - 1) / TILE;
			  for (int t = 0; t < tiles; ++t) {
			    int ac = t * TILE + tx, br = t * TILE + ty;
			    As[ty][tx] = (row < M && ac < K) ? Ab[row * K + ac] : 0.0f;
			    Bs[ty][tx] = (br < K && col < N) ? Bb[br * N + col] : 0.0f;
			    threadgroup_barrier(mem_flags::mem_threadgroup);
			    for (int k = 0; k < TILE; ++k) acc += As[ty][k] * Bs[k][tx];
			    threadgroup_barrier(mem_flags::mem_threadgroup);
			  }
			  if (row < M && col < N) Cb[row * N + col] = acc;
			}

			kernel void add_f32(device const float* A [[buffer(0)]],
			                    device const float* B [[buffer(1)]],
			                    device float* C [[buffer(2)]],
			                    constant int* n [[buffer(3)]],
			                    uint i [[thread_position_in_grid]]) {
			  if (int(i) < n[0]) C[i] = A[i] + B[i];
			}

			kernel void tanh_f32(device const float* A [[buffer(0)]],
			                     device float* C [[buffer(1)]],
			                     constant int* n [[buffer(2)]],
			                     uint i [[thread_position_in_grid]]) {
			  if (int(i) < n[0]) C[i] = tanh(A[i]);
			}
			""";

	static MemorySegment dev, queue;

	public static void main(String[] args) throws Throwable {
		MemorySegment pool = (MemorySegment) Mtl.poolPush.invokeExact();
		try (Arena ar = Arena.ofConfined()) {
			dev = Mtl.device();
			System.out.printf("device: %s  unified=%d  workingSet=%d MB  maxTG=%d%n",
					Mtl.fromNsString(Mtl.msg(dev, "name")), Mtl.msgLong(dev, "hasUnifiedMemory"),
					Mtl.msgLong(dev, "recommendedMaxWorkingSetSize") >> 20, Mtl.msgSize(ar, dev, "maxThreadsPerThreadgroup")[0]);

			long t0 = System.nanoTime();
			MemorySegment lib = Mtl.library(dev, SRC, MemorySegment.NULL);
			double compileMs = (System.nanoTime() - t0) / 1e6;
			t0 = System.nanoTime();
			MemorySegment gemm = Mtl.pipeline(dev, lib, "gemm_f32");
			double psoMs = (System.nanoTime() - t0) / 1e6;
			MemorySegment add = Mtl.pipeline(dev, lib, "add_f32");
			System.out.printf("MSL compile (newLibraryWithSource): %.1f ms | pipeline: %.1f ms%n", compileMs, psoMs);
			queue = Mtl.queue(dev);

			for (int n : new int[] { 64, 128, 256, 512, 1024, 2048 }) {
				bench(ar, gemm, n);
			}
			benchAdd(ar, add);
			benchLaunchOverhead(ar, gemm);
		}
		Mtl.poolPop.invokeExact(pool);
	}

	static void bench(Arena ar, MemorySegment gemm, int n) {
		double[] A = new double[n * n], B = new double[n * n];
		for (int i = 0; i < A.length; i++) {
			A[i] = ((i % 13) - 6) * 0.125;
			B[i] = ((i % 7) - 3) * 0.25;
		}
		double[] cpu = new double[n * n];
		int reps = n <= 256 ? 5 : 1;
		long t = System.nanoTime();
		for (int r = 0; r < reps; r++) {
			naive(A, B, cpu, n, n, n);
		}
		double cpuMs = (System.nanoTime() - t) / 1e6 / reps;

		float[] Af = new float[n * n], Bf = new float[n * n], Cf = new float[n * n];
		for (int i = 0; i < A.length; i++) {
			Af[i] = (float) A[i];
			Bf[i] = (float) B[i];
		}
		double full = gemmF32(ar, gemm, Af, Bf, Cf, n, n, n, 8, true);
		double hot = gemmF32(ar, gemm, Af, Bf, Cf, n, n, n, 8, false);

		double maxAbs = 0, maxRel = 0;
		for (int i = 0; i < cpu.length; i++) {
			double d = Math.abs(cpu[i] - Cf[i]);
			maxAbs = Math.max(maxAbs, d);
			double den = Math.max(1e-30, Math.abs(cpu[i]));
			if (Math.abs(cpu[i]) > 1e-6) maxRel = Math.max(maxRel, d / den);
		}
		// The inputs here are dyadic, so this column says "the kernel is not wrong", NOT "f32 is
		// exact" -- MtlPrecision.java is where the honest number lives.
		System.out.printf("n=%-5d cpu(java f64) %8.2f ms | gpu f32 %7.3f ms w/copy, %7.3f ms kernel"
				+ " | dyadic-input check: %s%n", n, cpuMs, full, hot,
				maxAbs == 0 ? "matches f64 oracle exactly" : String.format("maxabs %.3g maxrel %.3g", maxAbs, maxRel));
	}

	/** One intercepted matmul. includeCopy=true bills the heap->buffer and buffer->heap copies. */
	static double gemmF32(Arena ar, MemorySegment pso, float[] A, float[] B, float[] C, int M, int N, int K, int reps,
			boolean includeCopy) {
		long ab = (long) M * K * 4, bb = (long) K * N * 4, cb = (long) M * N * 4;
		MemorySegment da = Mtl.buffer(dev, ab, Mtl.SHARED), db = Mtl.buffer(dev, bb, Mtl.SHARED),
				dc = Mtl.buffer(dev, cb, Mtl.SHARED);
		MemorySegment ca = Mtl.contents(da, ab), cbuf = Mtl.contents(db, bb), cc = Mtl.contents(dc, cb);
		MemorySegment dims = ar.allocate(Mtl.I, 3);
		dims.setAtIndex(Mtl.I, 0, M);
		dims.setAtIndex(Mtl.I, 1, N);
		dims.setAtIndex(Mtl.I, 2, K);
		MemorySegment groups = Mtl.size(ar, (N + 15) / 16, (M + 15) / 16, 1);
		MemorySegment perGroup = Mtl.size(ar, 16, 16, 1);
		if (!includeCopy) {
			MemorySegment.copy(A, 0, ca, Mtl.F, 0, A.length);
			MemorySegment.copy(B, 0, cbuf, Mtl.F, 0, B.length);
		}
		double best = Double.MAX_VALUE;
		int warm = 3;
		for (int r = 0; r < reps + warm; r++) {
			long t = System.nanoTime();
			if (includeCopy) {
				MemorySegment.copy(A, 0, ca, Mtl.F, 0, A.length);
				MemorySegment.copy(B, 0, cbuf, Mtl.F, 0, B.length);
			}
			MemorySegment cmd = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cmd, pso);
			Mtl.setBuffer(enc, da, 0, 0);
			Mtl.setBuffer(enc, db, 0, 1);
			Mtl.setBuffer(enc, dc, 0, 2);
			Mtl.setBytes(enc, dims, 12, 3);
			Mtl.dispatch(enc, groups, perGroup);
			Mtl.endEncoding(enc);
			Mtl.commitAndWait(cmd);
			if (includeCopy) MemorySegment.copy(cc, Mtl.F, 0, C, 0, C.length);
			if (r >= warm) best = Math.min(best, (System.nanoTime() - t) / 1e6);
		}
		if (!includeCopy) MemorySegment.copy(cc, Mtl.F, 0, C, 0, C.length);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
		return best;
	}

	static void benchAdd(Arena ar, MemorySegment pso) {
		System.out.println("\n-- element-wise add (memory bound), f32 --");
		for (int n : new int[] { 4096, 65536, 1048576, 16777216 }) {
			float[] A = new float[n], B = new float[n], C = new float[n];
			for (int i = 0; i < n; i++) {
				A[i] = i * 0.5f;
				B[i] = i * 0.25f;
			}
			long t = System.nanoTime();
			for (int r = 0; r < 5; r++) {
				for (int i = 0; i < n; i++) C[i] = A[i] + B[i];
			}
			double cpuMs = (System.nanoTime() - t) / 1e6 / 5;

			long bytes = (long) n * 4;
			MemorySegment da = Mtl.buffer(dev, bytes, Mtl.SHARED), db = Mtl.buffer(dev, bytes, Mtl.SHARED),
					dc = Mtl.buffer(dev, bytes, Mtl.SHARED);
			MemorySegment ca = Mtl.contents(da, bytes), cbf = Mtl.contents(db, bytes), cc = Mtl.contents(dc, bytes);
			MemorySegment dims = ar.allocate(Mtl.I, 1);
			dims.setAtIndex(Mtl.I, 0, n);
			MemorySegment groups = Mtl.size(ar, (n + 255) / 256, 1, 1), per = Mtl.size(ar, 256, 1, 1);
			double withCopy = Double.MAX_VALUE, kernelOnly = Double.MAX_VALUE;
			for (int r = 0; r < 13; r++) {
				long t0 = System.nanoTime();
				MemorySegment.copy(A, 0, ca, Mtl.F, 0, n);
				MemorySegment.copy(B, 0, cbf, Mtl.F, 0, n);
				long t1 = System.nanoTime();
				MemorySegment cmd = Mtl.beginCommands(queue);
				MemorySegment enc = Mtl.beginEncoder(cmd, pso);
				Mtl.setBuffer(enc, da, 0, 0);
				Mtl.setBuffer(enc, db, 0, 1);
				Mtl.setBuffer(enc, dc, 0, 2);
				Mtl.setBytes(enc, dims, 4, 3);
				Mtl.dispatch(enc, groups, per);
				Mtl.endEncoding(enc);
				Mtl.commitAndWait(cmd);
				long t2 = System.nanoTime();
				MemorySegment.copy(cc, Mtl.F, 0, C, 0, n);
				long t3 = System.nanoTime();
				if (r > 2) {
					withCopy = Math.min(withCopy, (t3 - t0) / 1e6);
					kernelOnly = Math.min(kernelOnly, (t2 - t1) / 1e6);
				}
			}
			System.out.printf("n=%-10d cpu %7.3f ms | gpu %7.3f ms w/copy | %7.3f ms kernel%n", n, cpuMs, withCopy,
					kernelOnly);
			Mtl.release(da);
			Mtl.release(db);
			Mtl.release(dc);
		}
	}

	static void benchLaunchOverhead(Arena ar, MemorySegment pso) {
		int n = 16;
		long bytes = (long) n * n * 4;
		MemorySegment da = Mtl.buffer(dev, bytes, Mtl.SHARED), db = Mtl.buffer(dev, bytes, Mtl.SHARED),
				dc = Mtl.buffer(dev, bytes, Mtl.SHARED);
		MemorySegment dims = ar.allocate(Mtl.I, 3);
		dims.setAtIndex(Mtl.I, 0, n);
		dims.setAtIndex(Mtl.I, 1, n);
		dims.setAtIndex(Mtl.I, 2, n);
		MemorySegment groups = Mtl.size(ar, 1, 1, 1), per = Mtl.size(ar, 16, 16, 1);
		double sync = Double.MAX_VALUE, async = Double.MAX_VALUE;
		for (int r = 0; r < 500; r++) {
			long t = System.nanoTime();
			MemorySegment cmd = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cmd, pso);
			Mtl.setBuffer(enc, da, 0, 0);
			Mtl.setBuffer(enc, db, 0, 1);
			Mtl.setBuffer(enc, dc, 0, 2);
			Mtl.setBytes(enc, dims, 12, 3);
			Mtl.dispatch(enc, groups, per);
			Mtl.endEncoding(enc);
			Mtl.commitAndWait(cmd);
			if (r > 50) sync = Math.min(sync, (System.nanoTime() - t) / 1e3);
		}
		for (int r = 0; r < 500; r++) {
			long t = System.nanoTime();
			MemorySegment cmd = Mtl.beginCommands(queue);
			MemorySegment enc = Mtl.beginEncoder(cmd, pso);
			Mtl.setBuffer(enc, da, 0, 0);
			Mtl.setBuffer(enc, db, 0, 1);
			Mtl.setBuffer(enc, dc, 0, 2);
			Mtl.setBytes(enc, dims, 12, 3);
			Mtl.dispatch(enc, groups, per);
			Mtl.endEncoding(enc);
			Mtl.commit(cmd);
			if (r > 50) async = Math.min(async, (System.nanoTime() - t) / 1e3);
			Mtl.waitFor(cmd);
		}
		System.out.printf("%n-- pure launch overhead (16x16 gemm) --%nencode+commit+wait %.1f us | encode+commit only (async) %.1f us%n",
				sync, async);
		Mtl.release(da);
		Mtl.release(db);
		Mtl.release(dc);
	}

	static void naive(double[] A, double[] B, double[] C, int M, int N, int K) {
		for (int i = 0; i < M; i++) {
			for (int j = 0; j < N; j++) {
				double s = 0;
				for (int k = 0; k < K; k++) s += A[i * K + k] * B[k * N + j];
				C[i * N + j] = s;
			}
		}
	}
}
