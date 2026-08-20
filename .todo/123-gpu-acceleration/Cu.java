import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** Minimal pure-FFM binding to the CUDA driver API + NVRTC. No native shim, no deps. */
final class Cu {
	static final Linker LINKER = Linker.nativeLinker();
	static final SymbolLookup DRIVER = SymbolLookup.libraryLookup("libcuda.so.1", Arena.global());
	static final SymbolLookup NVRTC = SymbolLookup.libraryLookup("libnvrtc.so.13", Arena.global());

	static MethodHandle h(SymbolLookup lk, String name, FunctionDescriptor d) {
		return LINKER.downcallHandle(lk.find(name).orElseThrow(() -> new IllegalStateException("no symbol " + name)), d);
	}

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	static final AddressLayout P = ValueLayout.ADDRESS;

	// --- driver ---
	static final MethodHandle cuInit = h(DRIVER, "cuInit", FunctionDescriptor.of(I, I));
	static final MethodHandle cuDeviceGet = h(DRIVER, "cuDeviceGet", FunctionDescriptor.of(I, P, I));
	static final MethodHandle cuDeviceGetName = h(DRIVER, "cuDeviceGetName", FunctionDescriptor.of(I, P, I, I));
	static final MethodHandle cuDeviceGetAttribute = h(DRIVER, "cuDeviceGetAttribute", FunctionDescriptor.of(I, P, I, I));
	static final MethodHandle cuDevicePrimaryCtxRetain = h(DRIVER, "cuDevicePrimaryCtxRetain", FunctionDescriptor.of(I, P, I));
	static final MethodHandle cuCtxSetCurrent = h(DRIVER, "cuCtxSetCurrent", FunctionDescriptor.of(I, P));
	static final MethodHandle cuCtxSynchronize = h(DRIVER, "cuCtxSynchronize", FunctionDescriptor.of(I));
	static final MethodHandle cuMemAlloc = h(DRIVER, "cuMemAlloc_v2", FunctionDescriptor.of(I, P, L));
	static final MethodHandle cuMemAllocManaged = h(DRIVER, "cuMemAllocManaged", FunctionDescriptor.of(I, P, L, I));
	static final MethodHandle cuMemFree = h(DRIVER, "cuMemFree_v2", FunctionDescriptor.of(I, L));
	static final MethodHandle cuMemcpyHtoD = h(DRIVER, "cuMemcpyHtoD_v2", FunctionDescriptor.of(I, L, P, L));
	static final MethodHandle cuMemcpyDtoH = h(DRIVER, "cuMemcpyDtoH_v2", FunctionDescriptor.of(I, P, L, L));
	static final MethodHandle cuModuleLoadData = h(DRIVER, "cuModuleLoadData", FunctionDescriptor.of(I, P, P));
	static final MethodHandle cuModuleGetFunction = h(DRIVER, "cuModuleGetFunction", FunctionDescriptor.of(I, P, P, P));
	static final MethodHandle cuLaunchKernel = h(DRIVER, "cuLaunchKernel",
			FunctionDescriptor.of(I, P, I, I, I, I, I, I, I, P, P, P));
	static final MethodHandle cuGetErrorString = h(DRIVER, "cuGetErrorString", FunctionDescriptor.of(I, I, P));

	// --- nvrtc ---
	static final MethodHandle nvrtcCreateProgram = h(NVRTC, "nvrtcCreateProgram",
			FunctionDescriptor.of(I, P, P, P, I, P, P));
	static final MethodHandle nvrtcCompileProgram = h(NVRTC, "nvrtcCompileProgram", FunctionDescriptor.of(I, P, I, P));
	static final MethodHandle nvrtcGetPTXSize = h(NVRTC, "nvrtcGetPTXSize", FunctionDescriptor.of(I, P, P));
	static final MethodHandle nvrtcGetPTX = h(NVRTC, "nvrtcGetPTX", FunctionDescriptor.of(I, P, P));
	static final MethodHandle nvrtcGetProgramLogSize = h(NVRTC, "nvrtcGetProgramLogSize", FunctionDescriptor.of(I, P, P));
	static final MethodHandle nvrtcGetProgramLog = h(NVRTC, "nvrtcGetProgramLog", FunctionDescriptor.of(I, P, P));
	static final MethodHandle nvrtcVersion = h(NVRTC, "nvrtcVersion", FunctionDescriptor.of(I, P, P));

	static void check(int r, String what) {
		if (r != 0) {
			String msg = "?";
			try (Arena a = Arena.ofConfined()) {
				MemorySegment pp = a.allocate(P);
				if ((int) cuGetErrorString.invoke(r, pp) == 0) {
					msg = pp.get(P, 0).reinterpret(Long.MAX_VALUE).getString(0);
				}
			}
			catch (Throwable t) {
			}
			throw new RuntimeException(what + " failed: " + r + " (" + msg + ")");
		}
	}

	/** Compiles CUDA C to PTX with NVRTC and loads it, returning the CUmodule handle. */
	static MemorySegment compile(String src, int major, int minor) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment prog = a.allocate(P);
			int r = (int) nvrtcCreateProgram.invoke(prog, a.allocateFrom(src), a.allocateFrom("k.cu"), 0,
					MemorySegment.NULL, MemorySegment.NULL);
			if (r != 0) throw new RuntimeException("nvrtcCreateProgram: " + r);
			MemorySegment p = prog.get(P, 0);
			MemorySegment opts = a.allocate(P, 2);
			opts.setAtIndex(P, 0, a.allocateFrom("--gpu-architecture=compute_" + major + minor));
			opts.setAtIndex(P, 1, a.allocateFrom("-default-device"));
			r = (int) nvrtcCompileProgram.invoke(p, 2, opts);
			MemorySegment ls = a.allocate(L);
			nvrtcGetProgramLogSize.invoke(p, ls);
			long n = ls.get(L, 0);
			if (n > 1) {
				MemorySegment log = a.allocate(n);
				nvrtcGetProgramLog.invoke(p, log);
				System.out.println("[nvrtc log] " + log.getString(0));
			}
			if (r != 0) throw new RuntimeException("nvrtcCompileProgram: " + r);
			nvrtcGetPTXSize.invoke(p, ls);
			long sz = ls.get(L, 0);
			MemorySegment ptx = Arena.global().allocate(sz);
			nvrtcGetPTX.invoke(p, ptx);
			MemorySegment mod = Arena.global().allocate(P);
			check((int) cuModuleLoadData.invoke(mod, ptx), "cuModuleLoadData");
			return mod.get(P, 0);
		}
	}

	static MemorySegment func(MemorySegment mod, String name) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment f = Arena.global().allocate(P);
			check((int) cuModuleGetFunction.invoke(f, mod, a.allocateFrom(name)), "cuModuleGetFunction " + name);
			return f.get(P, 0);
		}
	}
}
