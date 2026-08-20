import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal pure-FFM binding to Metal. No Swift shim, no bundled native artifact, no deps.
 *
 * Everything in Metal except MTLCreateSystemDefaultDevice() is Objective-C, so this is
 * objc_msgSend over libobjc with one downcall handle per distinct C signature. Apple's own
 * arm64 rule is that objc_msgSend must be CALLED through a prototype matching the selector --
 * never as the variadic it is declared as -- which is exactly what a FunctionDescriptor
 * without firstVariadicArg produces. A selector whose return is a 24-byte MTLSize needs the
 * struct descriptor: calling it as a long is an immediate SIGBUS.
 */
final class Mtl {

	static final Linker LINKER = Linker.nativeLinker();

	static final Arena G = Arena.global();

	static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", G);

	static final SymbolLookup METAL = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", G);

	static final AddressLayout P = ValueLayout.ADDRESS;

	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;

	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;

	/** MTLSize is three NSUIntegers, passed and returned by value. */
	static final MemoryLayout MTL_SIZE = MemoryLayout.structLayout(L.withName("width"), L.withName("height"),
			L.withName("depth"));

	static final long SHARED = 0L; // MTLResourceStorageModeShared, the Apple-silicon default

	static final long PRIVATE = 2L << 4; // MTLResourceStorageModePrivate

	static MethodHandle h(SymbolLookup lk, String name, FunctionDescriptor d) {
		return LINKER.downcallHandle(lk.find(name).orElseThrow(() -> new IllegalStateException("no symbol " + name)), d);
	}

	static MethodHandle send(MemoryLayout ret, MemoryLayout... args) {
		MemoryLayout[] all = new MemoryLayout[args.length + 2];
		all[0] = P; // self
		all[1] = P; // SEL
		System.arraycopy(args, 0, all, 2, args.length);
		return h(OBJC, "objc_msgSend", ret == null ? FunctionDescriptor.ofVoid(all) : FunctionDescriptor.of(ret, all));
	}

	static final MethodHandle objc_getClass = h(OBJC, "objc_getClass", FunctionDescriptor.of(P, P));

	static final MethodHandle sel_registerName = h(OBJC, "sel_registerName", FunctionDescriptor.of(P, P));

	static final MethodHandle poolPush = h(OBJC, "objc_autoreleasePoolPush", FunctionDescriptor.of(P));

	static final MethodHandle poolPop = h(OBJC, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(P));

	static final MethodHandle createDevice = h(METAL, "MTLCreateSystemDefaultDevice", FunctionDescriptor.of(P));

	// --- the eleven signatures this spike needs ---
	static final MethodHandle ID_0 = send(P);

	static final MethodHandle ID_P = send(P, P);

	static final MethodHandle ID_PP = send(P, P, P);

	static final MethodHandle ID_PPP = send(P, P, P, P);

	static final MethodHandle ID_LL = send(P, L, L);

	static final MethodHandle ID_PLLP = send(P, P, L, L, P);

	static final MethodHandle VOID_0 = send(null);

	static final MethodHandle VOID_P = send(null, P);

	static final MethodHandle VOID_PLL = send(null, P, L, L);

	static final MethodHandle VOID_SIZE2 = send(null, MTL_SIZE, MTL_SIZE);

	static final MethodHandle LONG_0 = send(L, new MemoryLayout[0]);

	static final MethodHandle SIZE_0 = send(MTL_SIZE, new MemoryLayout[0]);

	// --- interning; the caches matter, allocateFrom in a global arena never comes back ---
	private static final Map<String, MemorySegment> SELS = new ConcurrentHashMap<>();

	private static final Map<String, MemorySegment> CLSS = new ConcurrentHashMap<>();

	static MemorySegment sel(String name) {
		return SELS.computeIfAbsent(name, n -> {
			try {
				return (MemorySegment) sel_registerName.invokeExact(G.allocateFrom(n));
			}
			catch (Throwable t) {
				throw new RuntimeException(t);
			}
		});
	}

	static MemorySegment cls(String name) {
		return CLSS.computeIfAbsent(name, n -> {
			try {
				return (MemorySegment) objc_getClass.invokeExact(G.allocateFrom(n));
			}
			catch (Throwable t) {
				throw new RuntimeException(t);
			}
		});
	}

	// hot selectors, resolved once
	static final MemorySegment S_COMMAND_BUFFER = sel("commandBuffer");

	static final MemorySegment S_ENCODER = sel("computeCommandEncoder");

	static final MemorySegment S_SET_PSO = sel("setComputePipelineState:");

	static final MemorySegment S_SET_BUFFER = sel("setBuffer:offset:atIndex:");

	static final MemorySegment S_SET_BYTES = sel("setBytes:length:atIndex:");

	static final MemorySegment S_DISPATCH = sel("dispatchThreadgroups:threadsPerThreadgroup:");

	static final MemorySegment S_END = sel("endEncoding");

	static final MemorySegment S_COMMIT = sel("commit");

	static final MemorySegment S_WAIT = sel("waitUntilCompleted");

	static final MemorySegment S_CONTENTS = sel("contents");

	static MemorySegment msg(MemorySegment self, String selector) {
		try {
			return (MemorySegment) ID_0.invokeExact(self, sel(selector));
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void msgVoid(MemorySegment self, String selector) {
		try {
			VOID_0.invokeExact(self, sel(selector));
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static long msgLong(MemorySegment self, String selector) {
		try {
			return (long) LONG_0.invokeExact(self, sel(selector));
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/** A selector returning MTLSize by value: 24 bytes, so an indirect return on arm64. */
	static long[] msgSize(SegmentAllocator alloc, MemorySegment self, String selector) {
		try {
			MemorySegment r = (MemorySegment) SIZE_0.invokeExact(alloc, self, sel(selector));
			return new long[] { r.get(L, 0), r.get(L, 8), r.get(L, 16) };
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static MemorySegment nsString(String s) {
		try (Arena a = Arena.ofConfined()) {
			return (MemorySegment) ID_P.invokeExact(cls("NSString"), sel("stringWithUTF8String:"), a.allocateFrom(s));
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static String fromNsString(MemorySegment nss) {
		if (nss == null || nss.address() == 0) return "(null)";
		try {
			MemorySegment c = (MemorySegment) ID_0.invokeExact(nss, sel("UTF8String"));
			return c.address() == 0 ? "(null)" : c.reinterpret(Long.MAX_VALUE).getString(0);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	// --- Metal object lifecycle ---

	static MemorySegment device() {
		try {
			MemorySegment d = (MemorySegment) createDevice.invokeExact();
			if (d.address() == 0) throw new IllegalStateException("MTLCreateSystemDefaultDevice returned nil");
			return d;
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	/**
	 * Compiles MSL at run time. Returns the MTLLibrary, or throws with the compiler's own
	 * diagnostics -- which is what makes the "is double supported?" question answerable.
	 */
	static MemorySegment library(MemorySegment dev, String src, MemorySegment options) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment err = a.allocate(P);
			err.set(P, 0, MemorySegment.NULL);
			MemorySegment opts = options == null ? MemorySegment.NULL : options;
			MemorySegment lib = (MemorySegment) ID_PPP.invokeExact(dev, sel("newLibraryWithSource:options:error:"),
					nsString(src), opts, err);
			if (lib.address() == 0) {
				MemorySegment e = err.get(P, 0);
				throw new RuntimeException("MSL compile failed: " + fromNsString(msg(e, "localizedDescription")));
			}
			return lib;
		}
		catch (Throwable t) {
			if (t instanceof RuntimeException re) throw re;
			throw new RuntimeException(t);
		}
	}

	static MemorySegment pipeline(MemorySegment dev, MemorySegment lib, String fnName) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment fn = (MemorySegment) ID_P.invokeExact(lib, sel("newFunctionWithName:"), nsString(fnName));
			if (fn.address() == 0) throw new RuntimeException("no kernel named " + fnName);
			MemorySegment err = a.allocate(P);
			err.set(P, 0, MemorySegment.NULL);
			MemorySegment pso = (MemorySegment) ID_PP.invokeExact(dev,
					sel("newComputePipelineStateWithFunction:error:"), fn, err);
			if (pso.address() == 0) {
				throw new RuntimeException("pipeline for " + fnName + " failed: "
						+ fromNsString(msg(err.get(P, 0), "localizedDescription")));
			}
			return pso;
		}
		catch (Throwable t) {
			if (t instanceof RuntimeException re) throw re;
			throw new RuntimeException(t);
		}
	}

	static MemorySegment queue(MemorySegment dev) {
		return msg(dev, "newCommandQueue");
	}

	static MemorySegment buffer(MemorySegment dev, long bytes, long options) {
		try {
			MemorySegment b = (MemorySegment) ID_LL.invokeExact(dev, sel("newBufferWithLength:options:"),
					Math.max(16L, bytes), options);
			if (b.address() == 0) throw new RuntimeException("newBufferWithLength failed for " + bytes);
			return b;
		}
		catch (Throwable t) {
			if (t instanceof RuntimeException re) throw re;
			throw new RuntimeException(t);
		}
	}

	/** Wraps page-aligned host memory with no copy at all -- the UMA route. */
	static MemorySegment bufferNoCopy(MemorySegment dev, MemorySegment hostPageAligned, long bytes, long options) {
		try {
			MemorySegment b = (MemorySegment) ID_PLLP.invokeExact(dev,
					sel("newBufferWithBytesNoCopy:length:options:deallocator:"), hostPageAligned, bytes, options,
					MemorySegment.NULL);
			if (b.address() == 0) throw new RuntimeException("newBufferWithBytesNoCopy failed");
			return b;
		}
		catch (Throwable t) {
			if (t instanceof RuntimeException re) throw re;
			throw new RuntimeException(t);
		}
	}

	/** The CPU-visible pointer of a shared-storage buffer. */
	static MemorySegment contents(MemorySegment buf, long bytes) {
		try {
			MemorySegment c = (MemorySegment) ID_0.invokeExact(buf, S_CONTENTS);
			return c.reinterpret(bytes);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void release(MemorySegment obj) {
		msgVoid(obj, "release");
	}

	// --- one encoded dispatch ---

	static MemorySegment beginCommands(MemorySegment queue) {
		try {
			return (MemorySegment) ID_0.invokeExact(queue, S_COMMAND_BUFFER);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static MemorySegment beginEncoder(MemorySegment cb, MemorySegment pso) {
		try {
			MemorySegment enc = (MemorySegment) ID_0.invokeExact(cb, S_ENCODER);
			VOID_P.invokeExact(enc, S_SET_PSO, pso);
			return enc;
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void setBuffer(MemorySegment enc, MemorySegment buf, long offset, long index) {
		try {
			VOID_PLL.invokeExact(enc, S_SET_BUFFER, buf, offset, index);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void setBytes(MemorySegment enc, MemorySegment data, long length, long index) {
		try {
			VOID_PLL.invokeExact(enc, S_SET_BYTES, data, length, index);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static MemorySegment size(SegmentAllocator a, long x, long y, long z) {
		MemorySegment s = a.allocate(MTL_SIZE);
		s.set(L, 0, x);
		s.set(L, 8, y);
		s.set(L, 16, z);
		return s;
	}

	static void dispatch(MemorySegment enc, MemorySegment groups, MemorySegment perGroup) {
		try {
			VOID_SIZE2.invokeExact(enc, S_DISPATCH, groups, perGroup);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void endEncoding(MemorySegment enc) {
		try {
			VOID_0.invokeExact(enc, S_END);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void commitAndWait(MemorySegment cb) {
		try {
			VOID_0.invokeExact(cb, S_COMMIT);
			VOID_0.invokeExact(cb, S_WAIT);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void commit(MemorySegment cb) {
		try {
			VOID_0.invokeExact(cb, S_COMMIT);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	static void waitFor(MemorySegment cb) {
		try {
			VOID_0.invokeExact(cb, S_WAIT);
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}
}
