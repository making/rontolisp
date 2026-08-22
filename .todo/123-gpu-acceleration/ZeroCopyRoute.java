import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Three routes for the same element-wise op (map_f32 exp) end to end, us/call, best of reps:
 *  R0 current: pooled device buffers, critical HtoD from heap, launch, critical DtoH to heap.
 *  R1 pinned staging: Java copy heap->pinned, cuMemcpyHtoD(pinned->device), launch, DtoH->pinned, copy out.
 *  R2 zero-copy: Java copy heap->pinned, launch over the pinned buffers' device pointers, sync, copy out.
 *  R3 zero-copy, no Java copies (the kernel over host memory alone).
 *  R4 device buffers, kernel only (no copies).
 */
public class ZeroCopyRoute {
	static final MethodHandle cuMemHostAlloc = CuLib.h("cuMemHostAlloc", FunctionDescriptor.of(CuLib.I, CuLib.P, CuLib.L, CuLib.I));
	static final MethodHandle cuMemHostGetDevicePointer = CuLib.h("cuMemHostGetDevicePointer_v2", FunctionDescriptor.of(CuLib.I, CuLib.P, CuLib.P, CuLib.I));
	static final MethodHandle cuMemFreeHost = CuLib.h("cuMemFreeHost", FunctionDescriptor.of(CuLib.I, CuLib.P));
	static final MethodHandle cuMemcpyHtoDAsync = CuLib.h("cuMemcpyHtoDAsync_v2", FunctionDescriptor.of(CuLib.I, CuLib.L, CuLib.P, CuLib.L, CuLib.P));
	static final MethodHandle cuMemcpyDtoHAsync = CuLib.h("cuMemcpyDtoHAsync_v2", FunctionDescriptor.of(CuLib.I, CuLib.P, CuLib.L, CuLib.L, CuLib.P));
	static MemorySegment mapF32;

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		mapF32 = CuLib.func("map_f32");
		int[] attrs = { 18, 19, 41, 83, 88, 89, 100, 101 };
		String[] names = { "INTEGRATED", "CAN_MAP_HOST_MEMORY", "UNIFIED_ADDRESSING", "MANAGED_MEMORY", "PAGEABLE_MEMORY_ACCESS", "CONCURRENT_MANAGED_ACCESS", "PAGEABLE_USES_HOST_PAGE_TABLES", "DIRECT_MANAGED_MEM_ACCESS_FROM_HOST" };
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = a.allocate(CuLib.I);
			for (int i = 0; i < attrs.length; i++) {
				CuLib.cuDeviceGetAttribute.invoke(out, attrs[i], CuLib.device);
				System.out.printf("  %-38s %d%n", names[i], out.get(CuLib.I, 0));
			}
		}
		System.out.printf("%n%-10s %10s %10s %10s %10s %10s %12s%n", "n (f32)", "R0 cur", "R1 pinned", "R2 zcopy", "R3 zc-nocp", "R4 kern", "javacpy MB/s");
		for (int n : new int[] { 65536, 262144, 1 << 20, 1 << 22 }) {
			run(n);
		}
	}

	static void run(int n) throws Throwable {
		float[] A = new float[n], C = new float[n];
		for (int i = 0; i < n; i++) A[i] = (i % 100) * 0.01f;
		long bytes = (long) n * 4;
		int reps = n <= 262144 ? 300 : 60;
		try (Arena a = Arena.ofConfined()) {
			MemorySegment pp = a.allocate(CuLib.P);
			CuLib.ck((int) cuMemHostAlloc.invoke(pp, bytes, 2), "hostalloc");
			MemorySegment ha = pp.get(CuLib.P, 0).reinterpret(bytes);
			CuLib.ck((int) cuMemHostAlloc.invoke(pp, bytes, 2), "hostalloc");
			MemorySegment hc = pp.get(CuLib.P, 0).reinterpret(bytes);
			MemorySegment dp = a.allocate(CuLib.L);
			CuLib.ck((int) cuMemHostGetDevicePointer.invoke(dp, ha, 0), "getdevptr");
			long dha = dp.get(CuLib.L, 0);
			CuLib.ck((int) cuMemHostGetDevicePointer.invoke(dp, hc, 0), "getdevptr");
			long dhc = dp.get(CuLib.L, 0);
			long da = CuLib.alloc(a, bytes, true), dc = CuLib.alloc(a, bytes, true);
			double r0 = CuLib.best(reps, () -> {
				try (Arena b = Arena.ofConfined()) {
					long xa = CuLib.alloc(b, bytes, true), xc = CuLib.alloc(b, bytes, true);
					CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(xa, MemorySegment.ofArray(A), bytes), "htod");
					launch(xa, xc, n, b);
					CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(C), xc, bytes), "dtoh");
					CuLib.free(xa, true);
					CuLib.free(xc, true);
				}
			});
			check(A, C, n);
			double r1 = CuLib.best(reps, () -> {
				try (Arena b = Arena.ofConfined()) {
					long xa = CuLib.alloc(b, bytes, true), xc = CuLib.alloc(b, bytes, true);
					MemorySegment.copy(MemorySegment.ofArray(A), 0, ha, 0, bytes);
					CuLib.ck((int) cuMemcpyHtoDAsync.invoke(xa, ha, bytes, MemorySegment.NULL), "htod");
					launch(xa, xc, n, b);
					CuLib.ck((int) cuMemcpyDtoHAsync.invoke(hc, xc, bytes, MemorySegment.NULL), "dtoh");
					CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
					MemorySegment.copy(hc, 0, MemorySegment.ofArray(C), 0, bytes);
					CuLib.free(xa, true);
					CuLib.free(xc, true);
				}
			});
			check(A, C, n);
			double r2 = CuLib.best(reps, () -> {
				try (Arena b = Arena.ofConfined()) {
					MemorySegment.copy(MemorySegment.ofArray(A), 0, ha, 0, bytes);
					launch(dha, dhc, n, b);
					CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
					MemorySegment.copy(hc, 0, MemorySegment.ofArray(C), 0, bytes);
				}
			});
			check(A, C, n);
			double r3 = CuLib.best(reps, () -> {
				try (Arena b = Arena.ofConfined()) {
					launch(dha, dhc, n, b);
					CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
				}
			});
			double r4 = CuLib.best(reps, () -> {
				try (Arena b = Arena.ofConfined()) {
					launch(da, dc, n, b);
					CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
				}
			});
			double cp = CuLib.best(reps, () -> {
				MemorySegment.copy(MemorySegment.ofArray(A), 0, ha, 0, bytes);
			});
			System.out.printf("%-10d %10.1f %10.1f %10.1f %10.1f %10.1f %12.0f%n", n, r0, r1, r2, r3, r4, bytes / cp);
			CuLib.free(da, true);
			CuLib.free(dc, true);
			cuMemFreeHost.invoke(ha);
			cuMemFreeHost.invoke(hc);
		}
	}

	static void check(float[] A, float[] C, int n) {
		for (int i = 0; i < n; i += 997) {
			if (Math.abs(C[i] - (float) Math.exp(A[i])) > 1e-4) throw new AssertionError("wrong at " + i + ": " + C[i] + " vs " + Math.exp(A[i]));
		}
	}

	static void launch(long da, long dc, int n, Arena a) throws Throwable {
		MemorySegment pa = a.allocate(CuLib.L), pc = a.allocate(CuLib.L);
		pa.set(CuLib.L, 0, da);
		pc.set(CuLib.L, 0, dc);
		MemorySegment cnt = a.allocate(CuLib.I), op = a.allocate(CuLib.I);
		cnt.set(CuLib.I, 0, n);
		op.set(CuLib.I, 0, 0);
		MemorySegment params = a.allocate(CuLib.P, 4);
		params.setAtIndex(CuLib.P, 0, pa);
		params.setAtIndex(CuLib.P, 1, pc);
		params.setAtIndex(CuLib.P, 2, cnt);
		params.setAtIndex(CuLib.P, 3, op);
		CuLib.ck((int) CuLib.cuLaunchKernel.invoke(mapF32, (n + 255) / 256, 1, 1, 256, 1, 1, 0, MemorySegment.NULL, params, MemorySegment.NULL), "launch");
	}
}
