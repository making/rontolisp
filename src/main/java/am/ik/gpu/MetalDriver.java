package am.ik.gpu;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The whole binding to Metal: {@code libobjc}, {@code Metal.framework} and
 * {@code MetalPerformanceShaders.framework} through {@link SymbolLookup#libraryLookup},
 * and one downcall handle per distinct C signature. No JNI, no Swift shim, no bundled
 * native artifact, and -- the Apple counterpart of the CUDA half's "no toolkit" -- no
 * Xcode: the frameworks and the MSL compiler are all in the OS, so "this machine is a Mac
 * with a GPU" is the entire runtime requirement.
 *
 * <h2>Everything but one function is Objective-C</h2>
 *
 * {@code MTLCreateSystemDefaultDevice} is the only C entry point in Metal; every other
 * call below is {@code objc_msgSend}. Apple's own arm64 rule is that {@code objc_msgSend}
 * must be CALLED through a prototype matching the selector rather than as the variadic it
 * is declared as, which is exactly what a {@link FunctionDescriptor} without
 * {@code firstVariadicArg} produces -- so there is one handle per shape here and a
 * selector is never sent through the wrong one. A selector returning or taking an
 * {@code MTLSize} needs the struct layout: sending it through a {@code long} shape is an
 * immediate SIGBUS rather than a wrong answer.
 *
 * <h2>A binding that is absent is not a binding that failed</h2>
 *
 * Constructing one either binds every symbol or throws, and {@link #open()} turns the
 * throw into {@code null} -- on Linux, on a JVM that forbids native access, or on a Mac
 * too old to have one of these frameworks. The availability question is then asked
 * exactly once, by {@link Gpu}.
 *
 * @see MetalGemm
 * @see Gpu
 */
final class MetalDriver {

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

	static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;

	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;

	static final ValueLayout.OfBoolean B = ValueLayout.JAVA_BOOLEAN;

	static final AddressLayout P = ValueLayout.ADDRESS;

	/** {@code MTLSize} is three {@code NSUInteger}s, passed by value. */
	static final MemoryLayout MTL_SIZE = MemoryLayout.structLayout(L.withName("width"), L.withName("height"),
			L.withName("depth"));

	/**
	 * {@code MTLResourceStorageModeShared} -- the Apple-silicon default, and the one the
	 */
	/** unified-memory route needs: the buffer's contents pointer IS host memory. */
	static final long STORAGE_SHARED = 0L;

	/** {@code MPSDataTypeFloat32}: the float bit plus the width. */
	static final int MPS_FLOAT32 = 0x10000000 | 32;

	/** {@code MTLMathModeSafe}. See {@code gemm.metal}'s header for why it is set. */
	static final long MATH_MODE_SAFE = 0L;

	/** {@code MTLCommandBufferStatusCompleted}. Anything else is a decline. */
	static final long STATUS_COMPLETED = 4L;

	private static final Linker LINKER = Linker.nativeLinker();

	private static final String LIB_OBJC = "/usr/lib/libobjc.A.dylib";

	private static final String LIB_METAL = "/System/Library/Frameworks/Metal.framework/Metal";

	private static final String LIB_MPS = "/System/Library/Frameworks/MetalPerformanceShaders.framework/MetalPerformanceShaders";

	private final MethodHandle objcGetClass;

	private final MethodHandle selRegisterName;

	private final MethodHandle poolPush;

	private final MethodHandle poolPop;

	private final MethodHandle createDevice;

	/** {@code id (id, SEL)}. */
	private final MethodHandle idNoArgs;

	/** {@code id (id, SEL, id)}. */
	private final MethodHandle idId;

	/** {@code id (id, SEL, id, id)}. */
	private final MethodHandle idIdId;

	/** {@code id (id, SEL, id, id, id)}. */
	private final MethodHandle idIdIdId;

	/** {@code id (id, SEL, NSUInteger, NSUInteger)}. */
	private final MethodHandle idLongLong;

	/** {@code void (id, SEL)}. */
	private final MethodHandle voidNoArgs;

	/** {@code void (id, SEL, id)}. */
	private final MethodHandle voidId;

	/** {@code void (id, SEL, NSUInteger)}. */
	private final MethodHandle voidLong;

	/** {@code void (id, SEL, BOOL)}. */
	private final MethodHandle voidBool;

	/** {@code void (id, SEL, id, NSUInteger, NSUInteger)}. */
	private final MethodHandle voidIdLongLong;

	/** {@code void (id, SEL, MTLSize, MTLSize)}. */
	private final MethodHandle voidSizeSize;

	/** {@code void (id, SEL, id, id, id, id)}. */
	private final MethodHandle voidIdIdIdId;

	/** {@code NSUInteger (id, SEL)}. */
	private final MethodHandle longNoArgs;

	/** {@code BOOL (id, SEL)}. */
	private final MethodHandle boolNoArgs;

	/** {@code BOOL (id, SEL, SEL)}. */
	private final MethodHandle boolSel;

	/** {@code id (id, SEL, id, NSUInteger, id)}. */
	private final MethodHandle idIdLongId;

	/** {@code id (id, SEL, NSUInteger, NSUInteger, NSUInteger, MPSDataType)}. */
	private final MethodHandle idLongLongLongInt;

	/**
	 * {@code id (id, SEL, id, BOOL, BOOL, NSUInteger, NSUInteger, NSUInteger, double, double)}
	 * -- {@code MPSMatrixMultiplication}'s designated initializer, the widest signature
	 * here.
	 */
	private final MethodHandle idMpsInit;

	private final Map<String, MemorySegment> selectors = new ConcurrentHashMap<>();

	private final Map<String, MemorySegment> classes = new ConcurrentHashMap<>();

	private MetalDriver(SymbolLookup objc, SymbolLookup metal, SymbolLookup mps) {
		this.objcGetClass = handle(objc, "objc_getClass", FunctionDescriptor.of(P, P));
		this.selRegisterName = handle(objc, "sel_registerName", FunctionDescriptor.of(P, P));
		this.poolPush = handle(objc, "objc_autoreleasePoolPush", FunctionDescriptor.of(P));
		this.poolPop = handle(objc, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(P));
		this.createDevice = handle(metal, "MTLCreateSystemDefaultDevice", FunctionDescriptor.of(P));
		this.idNoArgs = send(objc, P);
		this.idId = send(objc, P, P);
		this.idIdId = send(objc, P, P, P);
		this.idIdIdId = send(objc, P, P, P, P);
		this.idLongLong = send(objc, P, L, L);
		this.voidNoArgs = send(objc, null);
		this.voidId = send(objc, null, P);
		this.voidLong = send(objc, null, L);
		this.voidBool = send(objc, null, B);
		this.voidIdLongLong = send(objc, null, P, L, L);
		this.voidSizeSize = send(objc, null, MTL_SIZE, MTL_SIZE);
		this.voidIdIdIdId = send(objc, null, P, P, P, P);
		this.longNoArgs = send(objc, L);
		this.boolNoArgs = send(objc, B);
		this.boolSel = send(objc, B, P);
		this.idIdLongId = send(objc, P, P, L, P);
		this.idLongLongLongInt = send(objc, P, L, L, L, I);
		this.idMpsInit = send(objc, P, P, B, B, L, L, L, D, D);
		// MetalPerformanceShaders is opened for its CLASSES, which objc_getClass finds
		// only once the framework's images are loaded; it exports no symbol this binding
		// calls, so the lookup itself is the whole use of it.
		mps.find("MPSSupportsMTLDevice");
	}

	private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		MemorySegment symbol = lookup.find(name).orElseThrow(() -> new IllegalStateException(name + " is missing"));
		return LINKER.downcallHandle(symbol, descriptor);
	}

	/**
	 * One {@code objc_msgSend} handle of the given shape. The receiver and the selector
	 * are prepended, so a caller names only the selector's own arguments.
	 * @param objc the {@code libobjc} lookup
	 * @param returnLayout the return layout, or {@code null} for a {@code void} selector
	 * @param argumentLayouts the selector's own arguments
	 * @return a handle that must be invoked with {@code (receiver, selector, ...)}
	 */
	private static MethodHandle send(SymbolLookup objc, @Nullable MemoryLayout returnLayout,
			MemoryLayout... argumentLayouts) {
		MemoryLayout[] all = new MemoryLayout[argumentLayouts.length + 2];
		all[0] = P;
		all[1] = P;
		System.arraycopy(argumentLayouts, 0, all, 2, argumentLayouts.length);
		FunctionDescriptor descriptor = returnLayout == null ? FunctionDescriptor.ofVoid(all)
				: FunctionDescriptor.of(returnLayout, all);
		return handle(objc, "objc_msgSend", descriptor);
	}

	/**
	 * Binds the three frameworks, or answers {@code null} when there is nothing to bind:
	 * any platform but macOS, a JVM that forbids native access, or a Mac without
	 * MetalPerformanceShaders. Never throws.
	 * @return the binding, or {@code null} when this machine has no Metal
	 */
	static @Nullable MetalDriver open() {
		try {
			Arena arena = Arena.global();
			return new MetalDriver(SymbolLookup.libraryLookup(LIB_OBJC, arena),
					SymbolLookup.libraryLookup(LIB_METAL, arena), SymbolLookup.libraryLookup(LIB_MPS, arena));
		}
		catch (Throwable ex) {
			return null;
		}
	}

	// --- interning --------------------------------------------------------------------
	// Both caches are load-bearing rather than an optimization: the C strings they are
	// built from are allocated in the GLOBAL arena and never come back, so registering a
	// selector per call would leak a few bytes per call forever.

	MemorySegment selector(String name) {
		return this.selectors.computeIfAbsent(name, key -> {
			try {
				return (MemorySegment) this.selRegisterName.invokeExact(Arena.global().allocateFrom(key));
			}
			catch (Throwable ex) {
				throw new IllegalStateException(ex);
			}
		});
	}

	MemorySegment objcClass(String name) {
		return this.classes.computeIfAbsent(name, key -> {
			try {
				return (MemorySegment) this.objcGetClass.invokeExact(Arena.global().allocateFrom(key));
			}
			catch (Throwable ex) {
				throw new IllegalStateException(ex);
			}
		});
	}

	// --- the entry points -------------------------------------------------------------
	// The Throwable on each signature is MethodHandle.invokeExact's, and reaching it
	// means
	// a defect in the descriptors above rather than anything the device did.

	/**
	 * Pushes an autorelease pool. Mandatory around every call that reaches Metal: a
	 * command buffer, an encoder and every {@code MPSMatrixDescriptor} are AUTORELEASED,
	 * so without a pool per call they accumulate for the life of the process.
	 * @return the pool token to hand back to {@link #autoreleasePoolPop}
	 * @throws Throwable never, in practice
	 */
	MemorySegment autoreleasePoolPush() throws Throwable {
		return (MemorySegment) this.poolPush.invokeExact();
	}

	void autoreleasePoolPop(MemorySegment pool) throws Throwable {
		this.poolPop.invokeExact(pool);
	}

	/** {@code MTLCreateSystemDefaultDevice()}, or a null pointer on a Mac without one. */
	MemorySegment systemDefaultDevice() throws Throwable {
		return (MemorySegment) this.createDevice.invokeExact();
	}

	MemorySegment message(MemorySegment receiver, String selector) throws Throwable {
		return (MemorySegment) this.idNoArgs.invokeExact(receiver, selector(selector));
	}

	MemorySegment message(MemorySegment receiver, String selector, MemorySegment a) throws Throwable {
		return (MemorySegment) this.idId.invokeExact(receiver, selector(selector), a);
	}

	MemorySegment message(MemorySegment receiver, String selector, MemorySegment a, MemorySegment b) throws Throwable {
		return (MemorySegment) this.idIdId.invokeExact(receiver, selector(selector), a, b);
	}

	MemorySegment message(MemorySegment receiver, String selector, MemorySegment a, MemorySegment b, MemorySegment c)
			throws Throwable {
		return (MemorySegment) this.idIdIdId.invokeExact(receiver, selector(selector), a, b, c);
	}

	MemorySegment message(MemorySegment receiver, String selector, long a, long b) throws Throwable {
		return (MemorySegment) this.idLongLong.invokeExact(receiver, selector(selector), a, b);
	}

	void messageVoid(MemorySegment receiver, String selector) throws Throwable {
		this.voidNoArgs.invokeExact(receiver, selector(selector));
	}

	void messageVoid(MemorySegment receiver, String selector, MemorySegment a) throws Throwable {
		this.voidId.invokeExact(receiver, selector(selector), a);
	}

	void messageVoid(MemorySegment receiver, String selector, long a) throws Throwable {
		this.voidLong.invokeExact(receiver, selector(selector), a);
	}

	void messageVoid(MemorySegment receiver, String selector, boolean a) throws Throwable {
		this.voidBool.invokeExact(receiver, selector(selector), a);
	}

	void messageVoid(MemorySegment receiver, String selector, MemorySegment a, long b, long c) throws Throwable {
		this.voidIdLongLong.invokeExact(receiver, selector(selector), a, b, c);
	}

	void messageVoid(MemorySegment receiver, String selector, MemorySegment a, MemorySegment b, MemorySegment c,
			MemorySegment d) throws Throwable {
		this.voidIdIdIdId.invokeExact(receiver, selector(selector), a, b, c, d);
	}

	long messageLong(MemorySegment receiver, String selector) throws Throwable {
		return (long) this.longNoArgs.invokeExact(receiver, selector(selector));
	}

	boolean messageBool(MemorySegment receiver, String selector) throws Throwable {
		return (boolean) this.boolNoArgs.invokeExact(receiver, selector(selector));
	}

	boolean respondsTo(MemorySegment receiver, String selector) throws Throwable {
		return (boolean) this.boolSel.invokeExact(receiver, selector("respondsToSelector:"), selector(selector));
	}

	/** {@code dispatchThreadgroups:threadsPerThreadgroup:} -- two {@code MTLSize}s. */
	void dispatch(MemorySegment encoder, MemorySegment groups, MemorySegment perGroup) throws Throwable {
		this.voidSizeSize.invokeExact(encoder, selector("dispatchThreadgroups:threadsPerThreadgroup:"), groups,
				perGroup);
	}

	/**
	 * {@code +[MPSMatrixDescriptor matrixDescriptorWithRows:columns:rowBytes:dataType:]}.
	 */
	MemorySegment matrixDescriptor(long rows, long columns, long rowBytes) throws Throwable {
		return (MemorySegment) this.idLongLongLongInt.invokeExact(objcClass("MPSMatrixDescriptor"),
				selector("matrixDescriptorWithRows:columns:rowBytes:dataType:"), rows, columns, rowBytes, MPS_FLOAT32);
	}

	/**
	 * {@code -[MPSMatrix initWithBuffer:offset:descriptor:]} on a freshly allocated one.
	 * The offset form is what lets one slab of a stacked product be addressed per batch,
	 * which a batched {@code MPSMatrixDescriptor} could not do with a zero stride.
	 * @param buffer the {@code MTLBuffer} holding the elements
	 * @param offset the matrix's first element, in BYTES
	 * @param descriptor the shape
	 * @return the matrix, to be released by the caller
	 * @throws Throwable never, in practice
	 */
	MemorySegment matrix(MemorySegment buffer, long offset, MemorySegment descriptor) throws Throwable {
		MemorySegment raw = message(objcClass("MPSMatrix"), "alloc");
		return (MemorySegment) this.idIdLongId.invokeExact(raw, selector("initWithBuffer:offset:descriptor:"), buffer,
				offset, descriptor);
	}

	/**
	 * {@code -[MPSMatrixMultiplication initWithDevice:...]} on a freshly allocated one.
	 */
	MemorySegment matrixMultiplication(MemorySegment device, long rows, long columns, long interior) throws Throwable {
		MemorySegment raw = message(objcClass("MPSMatrixMultiplication"), "alloc");
		return (MemorySegment) this.idMpsInit.invokeExact(raw, selector(
				"initWithDevice:transposeLeft:transposeRight:resultRows:resultColumns:interiorColumns:alpha:beta:"),
				device, false, false, rows, columns, interior, 1.0, 0.0);
	}

	/** An {@code MTLSize} in the given allocator, for {@link #dispatch}. */
	static MemorySegment size(SegmentAllocator allocator, long x, long y, long z) {
		MemorySegment out = allocator.allocate(MTL_SIZE);
		out.set(L, 0, x);
		out.set(L, 8, y);
		out.set(L, 16, z);
		return out;
	}

	/** An autoreleased {@code NSString} for a Java string. */
	MemorySegment nsString(String value) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			return message(objcClass("NSString"), "stringWithUTF8String:", arena.allocateFrom(value));
		}
	}

	/** The Java string behind an {@code NSString}, or {@code "?"} for a nil one. */
	String fromNsString(MemorySegment value) {
		try {
			if (value.address() == 0) {
				return "?";
			}
			MemorySegment chars = message(value, "UTF8String");
			return chars.address() == 0 ? "?" : chars.reinterpret(Long.MAX_VALUE).getString(0);
		}
		catch (Throwable ex) {
			return "?";
		}
	}

	/** The CPU-visible pointer of a shared-storage {@code MTLBuffer}. */
	MemorySegment contents(MemorySegment buffer, long bytes) throws Throwable {
		return message(buffer, "contents").reinterpret(bytes);
	}

}
