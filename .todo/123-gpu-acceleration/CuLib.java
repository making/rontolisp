import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;

/**
 * The DRIVER-ONLY binding the three am.ik.gpu probes share: no NVRTC, no toolkit, the
 * checked-in PTX loaded straight from the repository. Cu.java is the older sibling and
 * still compiles CUDA C at run time; this one is deliberately the shape the shipped
 * library has, so its numbers are the library's numbers.
 */
final class CuLib {

	static final Linker LINKER = Linker.nativeLinker();
	static final SymbolLookup DRIVER = SymbolLookup.libraryLookup("libcuda.so.1", Arena.global());
	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	static final AddressLayout P = ValueLayout.ADDRESS;
	static final Linker.Option CRITICAL = Linker.Option.critical(true);

	static MethodHandle h(String name, FunctionDescriptor d, Linker.Option... options) {
		return LINKER.downcallHandle(DRIVER.find(name).orElseThrow(), d, options);
	}

	static final MethodHandle cuInit = h("cuInit", FunctionDescriptor.of(I, I));
	static final MethodHandle cuDeviceGet = h("cuDeviceGet", FunctionDescriptor.of(I, P, I));
	static final MethodHandle cuDeviceGetName = h("cuDeviceGetName", FunctionDescriptor.of(I, P, I, I));
	static final MethodHandle cuDeviceGetAttribute = h("cuDeviceGetAttribute", FunctionDescriptor.of(I, P, I, I));
	static final MethodHandle cuDevicePrimaryCtxRetain = h("cuDevicePrimaryCtxRetain", FunctionDescriptor.of(I, P, I));
	static final MethodHandle cuCtxSetCurrent = h("cuCtxSetCurrent", FunctionDescriptor.of(I, P));
	static final MethodHandle cuCtxSynchronize = h("cuCtxSynchronize", FunctionDescriptor.of(I));
	static final MethodHandle cuMemAlloc = h("cuMemAlloc_v2", FunctionDescriptor.of(I, P, L));
	static final MethodHandle cuMemFree = h("cuMemFree_v2", FunctionDescriptor.of(I, L));
	static final MethodHandle cuMemAllocAsync = h("cuMemAllocAsync", FunctionDescriptor.of(I, P, L, P));
	static final MethodHandle cuMemFreeAsync = h("cuMemFreeAsync", FunctionDescriptor.of(I, L, P));
	static final MethodHandle cuDeviceGetDefaultMemPool = h("cuDeviceGetDefaultMemPool", FunctionDescriptor.of(I, P, I));
	static final MethodHandle cuMemPoolTrimTo = h("cuMemPoolTrimTo", FunctionDescriptor.of(I, P, L));
	static final MethodHandle cuMemGetInfo = h("cuMemGetInfo_v2", FunctionDescriptor.of(I, P, P));
	static final MethodHandle cuMemcpyHtoD = h("cuMemcpyHtoD_v2", FunctionDescriptor.of(I, L, P, L));
	static final MethodHandle cuMemcpyDtoH = h("cuMemcpyDtoH_v2", FunctionDescriptor.of(I, P, L, L));
	static final MethodHandle cuMemcpyHtoDc = h("cuMemcpyHtoD_v2", FunctionDescriptor.of(I, L, P, L), CRITICAL);
	static final MethodHandle cuMemcpyDtoHc = h("cuMemcpyDtoH_v2", FunctionDescriptor.of(I, P, L, L), CRITICAL);
	static final MethodHandle cuModuleLoadData = h("cuModuleLoadData", FunctionDescriptor.of(I, P, P));
	static final MethodHandle cuModuleGetFunction = h("cuModuleGetFunction", FunctionDescriptor.of(I, P, P, P));
	static final MethodHandle cuLaunchKernel = h("cuLaunchKernel",
			FunctionDescriptor.of(I, P, I, I, I, I, I, I, I, P, P, P));

	/** The checked-in kernels, relative to this directory. */
	static final String PTX = "../../src/main/resources/am/ik/gpu/gemm.ptx";

	static MemorySegment module;
	static MemorySegment gemmF64, gemmF32;
	static MemorySegment pool = MemorySegment.NULL;
	static int device;

	static void ck(int r, String what) {
		if (r != 0) throw new RuntimeException(what + " -> " + r);
	}

	/** cuInit, device 0, primary context, and the checked-in PTX. Prints what it found. */
	static void open() throws Throwable {
		ck((int) cuInit.invoke(0), "cuInit");
		Arena g = Arena.global();
		MemorySegment out = g.allocate(I);
		ck((int) cuDeviceGet.invoke(out, 0), "cuDeviceGet");
		device = out.get(I, 0);
		MemorySegment ctx = g.allocate(P);
		ck((int) cuDevicePrimaryCtxRetain.invoke(ctx, device), "cuDevicePrimaryCtxRetain");
		ck((int) cuCtxSetCurrent.invoke(ctx.get(P, 0)), "cuCtxSetCurrent");
		MemorySegment name = g.allocate(256);
		cuDeviceGetName.invoke(name, 256, device);
		cuDeviceGetAttribute.invoke(out, 16, device);
		int sms = out.get(I, 0);
		MemorySegment poolOut = g.allocate(P);
		if ((int) cuDeviceGetDefaultMemPool.invoke(poolOut, device) == 0) pool = poolOut.get(P, 0);
		long t = System.nanoTime();
		MemorySegment mod = g.allocate(P);
		ck((int) cuModuleLoadData.invoke(mod, g.allocateFrom(Files.readString(Path.of(PTX)))), "cuModuleLoadData");
		module = mod.get(P, 0);
		gemmF64 = func("gemm_f64");
		gemmF32 = func("gemm_f32");
		System.out.printf("%s, %d SMs, checked-in compute_75 PTX loaded in %.2f ms%n", name.getString(0), sms,
				(System.nanoTime() - t) / 1e6);
	}

	static MemorySegment func(String name) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment f = Arena.global().allocate(P);
			ck((int) cuModuleGetFunction.invoke(f, module, a.allocateFrom(name)), "cuModuleGetFunction " + name);
			return f.get(P, 0);
		}
	}

	static long freeBytes() throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment free = a.allocate(L), total = a.allocate(L);
			ck((int) cuMemGetInfo.invoke(free, total), "cuMemGetInfo");
			return free.get(L, 0);
		}
	}

	static long alloc(Arena a, long bytes, boolean pooled) throws Throwable {
		MemorySegment out = a.allocate(L);
		ck(pooled ? (int) cuMemAllocAsync.invoke(out, bytes, MemorySegment.NULL)
				: (int) cuMemAlloc.invoke(out, bytes), "alloc");
		return out.get(L, 0);
	}

	static void free(long p, boolean pooled) throws Throwable {
		ck(pooled ? (int) cuMemFreeAsync.invoke(p, MemorySegment.NULL) : (int) cuMemFree.invoke(p), "free");
	}

	/** One 16x16-tiled launch over an n x m by m x p product. */
	static void launch(MemorySegment f, long da, long db, long dc, int n, int m, int p, Arena a) throws Throwable {
		MemorySegment pa = a.allocate(L), pb = a.allocate(L), pc = a.allocate(L);
		pa.set(L, 0, da);
		pb.set(L, 0, db);
		pc.set(L, 0, dc);
		MemorySegment rows = a.allocate(I), cols = a.allocate(I), inner = a.allocate(I);
		rows.set(I, 0, n);
		cols.set(I, 0, p);
		inner.set(I, 0, m);
		MemorySegment params = a.allocate(P, 6);
		params.setAtIndex(P, 0, pa);
		params.setAtIndex(P, 1, pb);
		params.setAtIndex(P, 2, pc);
		params.setAtIndex(P, 3, rows);
		params.setAtIndex(P, 4, cols);
		params.setAtIndex(P, 5, inner);
		ck((int) cuLaunchKernel.invoke(f, (p + 15) / 16, (n + 15) / 16, 1, 16, 16, 1, 0, MemorySegment.NULL, params,
				MemorySegment.NULL), "cuLaunchKernel");
	}

	/** Min over reps, discarding the first fifth. */
	static double best(int reps, Run body) throws Throwable {
		double best = Double.MAX_VALUE;
		for (int r = 0; r < reps; r++) {
			long t = System.nanoTime();
			body.run();
			double us = (System.nanoTime() - t) / 1e3;
			if (r > reps / 5) best = Math.min(best, us);
		}
		return best;
	}

	interface Run {
		void run() throws Throwable;
	}
}
