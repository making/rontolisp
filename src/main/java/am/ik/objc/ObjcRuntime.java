package am.ik.objc;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.objc.TypeEncoding.Kind;
import am.ik.objc.TypeEncoding.Type;
import org.jspecify.annotations.Nullable;

/**
 * The binding to the Objective-C runtime and AppKit: {@code libobjc} and
 * {@code AppKit.framework} (which pulls Foundation in) through
 * {@link SymbolLookup#libraryLookup}, with no JNI, no bundled artifact and no dependency
 * -- the same shape as {@code am.ik.gpu.MetalDriver}, generalized from a hand-written
 * table of selector shapes to a {@linkplain #sendRaw raw send} whose shape the caller
 * hands over as the method's type encoding.
 *
 * <h2>One {@code objc_msgSend} handle per shape, derived from the encoding</h2>
 *
 * Apple's arm64 rule is that {@code objc_msgSend} must be called through a prototype
 * matching the selector, never as the variadic it is declared as. {@link #sendRaw} parses
 * the encoding it is given ({@link TypeEncoding}), turns it into a
 * {@link FunctionDescriptor}, and binds -- once per distinct shape -- a downcall handle
 * for it. A native image builds a downcall stub only for a shape registered at build time
 * and refuses any other, so the registered set is a CLOSED table
 * ({@code reachability-metadata.json}); a selector whose shape is outside it fails with
 * an {@link ObjcException} that spells the entry to add, never a silent decline and never
 * a SIGBUS.
 *
 * <p>
 * The encoding is complete for every method except a VARIADIC one, which it spells
 * exactly like its fixed-arity twin. The caller says where the variadic arguments start
 * ({@code objc.lisp}'s table of such selectors, or a list-form method's
 * {@code :variadic-num-of-fixed}), and the call is bound with
 * {@link Linker.Option#firstVariadicArg}.
 *
 * <h2>Threads</h2>
 *
 * This class is a plain binding and runs on whichever thread calls it. AppKit demands
 * thread 0, and {@link #sendRawOnMain} hops there ({@link MainThread}); a {@link #retain}
 * or a {@link #release} is safe anywhere.
 *
 * <h2>Absent is not broken</h2>
 *
 * {@link #open()} answers {@code null} only when the libraries are not there -- Linux, or
 * a JVM that forbids native access; a machine that has them and then fails to bind one
 * throws, because those are different answers and the caller printing them must be able
 * to tell.
 *
 * @see TypeEncoding
 * @see ObjcMethods
 * @see MainThread
 */
public final class ObjcRuntime {

	static final AddressLayout P = ValueLayout.ADDRESS;

	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

	static final ValueLayout.OfBoolean B = ValueLayout.JAVA_BOOLEAN;

	private static final Linker LINKER = Linker.nativeLinker();

	static final String LIB_OBJC = "/usr/lib/libobjc.A.dylib";

	static final String LIB_APPKIT = "/System/Library/Frameworks/AppKit.framework/AppKit";

	private static final Object OPEN_LOCK = new Object();

	private static @Nullable ObjcRuntime instance;

	private static @Nullable String unavailableReason;

	private final MethodHandle objcGetClass;

	private final MethodHandle selRegisterName;

	private final MethodHandle selGetName;

	private final MethodHandle objectGetClass;

	private final MethodHandle objectIsClass;

	private final MethodHandle classGetName;

	private final MethodHandle classGetSuperclass;

	private final MethodHandle classGetInstanceMethod;

	private final MethodHandle methodGetTypeEncoding;

	private final MethodHandle objcRetain;

	private final MethodHandle objcRelease;

	private final MethodHandle objcGetProtocol;

	private final MethodHandle objcAllocateClassPair;

	private final MethodHandle classAddProtocol;

	private final MethodHandle objcRegisterClassPair;

	private final MethodHandle classAddIvar;

	private final MethodHandle classReplaceMethod;

	private final MethodHandle classGetInstanceVariable;

	private final MethodHandle ivarGetOffset;

	private final MethodHandle ivarGetTypeEncoding;

	private final MethodHandle objcAutorelease;

	private final MethodHandle poolPush;

	private final MethodHandle poolPop;

	private final MethodHandle dlsym;

	private final MethodHandle malloc;

	private final MethodHandle free;

	private final MemorySegment stackBlockIsa;

	private final MemorySegment msgSend;

	private final MemorySegment msgSendSuper;

	private final @Nullable MemorySegment msgSendSuperStret;

	private final @Nullable MemorySegment msgSendStret;

	private final MainThread mainThread;

	/**
	 * The catching trampolines every send and C call goes through, or {@code null} where
	 * they cannot be built (x86_64, a macOS without the unwinder's finder API).
	 */
	private final @Nullable ObjcCatch catcher;

	/** The shapes bound, in binding order, for the native-image registration test. */
	private final Set<FunctionDescriptor> signatures = new LinkedHashSet<>();

	private final Map<Signature, MethodHandle> sends = new ConcurrentHashMap<>();

	private final Map<Signature, MethodHandle> superSends = new ConcurrentHashMap<>();

	/**
	 * Unbound downcall handles for {@link #callRaw}, by shape: the target is an argument.
	 */
	private final Map<Signature, MethodHandle> calls = new ConcurrentHashMap<>();

	// Both caches are load-bearing rather than an optimization: the C strings they are
	// built from live in the global arena forever.
	private final Map<String, MemorySegment> selectors = new ConcurrentHashMap<>();

	private final Map<String, MemorySegment> classes = new ConcurrentHashMap<>();

	/**
	 * Binds the runtime. Package-private so the registration test can construct one
	 * against a lookup that finds every name.
	 * @param objc the {@code libobjc} lookup
	 * @param appkit the AppKit lookup (opened for its classes; no symbol is called)
	 * @param mainThread the pump
	 */
	ObjcRuntime(SymbolLookup objc, SymbolLookup appkit, MainThread mainThread) {
		this.mainThread = mainThread;
		this.objcGetClass = handle(objc, "objc_getClass", FunctionDescriptor.of(P, P));
		this.selRegisterName = handle(objc, "sel_registerName", FunctionDescriptor.of(P, P));
		this.selGetName = handle(objc, "sel_getName", FunctionDescriptor.of(P, P));
		this.objectGetClass = handle(objc, "object_getClass", FunctionDescriptor.of(P, P));
		this.objectIsClass = handle(objc, "object_isClass", FunctionDescriptor.of(B, P));
		this.classGetName = handle(objc, "class_getName", FunctionDescriptor.of(P, P));
		this.classGetSuperclass = handle(objc, "class_getSuperclass", FunctionDescriptor.of(P, P));
		this.classGetInstanceMethod = handle(objc, "class_getInstanceMethod", FunctionDescriptor.of(P, P, P));
		this.methodGetTypeEncoding = handle(objc, "method_getTypeEncoding", FunctionDescriptor.of(P, P));
		this.objcRetain = handle(objc, "objc_retain", FunctionDescriptor.of(P, P));
		this.objcRelease = handle(objc, "objc_release", FunctionDescriptor.ofVoid(P));
		this.objcGetProtocol = handle(objc, "objc_getProtocol", FunctionDescriptor.of(P, P));
		this.objcAllocateClassPair = handle(objc, "objc_allocateClassPair", FunctionDescriptor.of(P, P, P, L));
		this.classAddProtocol = handle(objc, "class_addProtocol", FunctionDescriptor.of(B, P, P));
		this.objcRegisterClassPair = handle(objc, "objc_registerClassPair", FunctionDescriptor.ofVoid(P));
		this.classAddIvar = handle(objc, "class_addIvar", FunctionDescriptor.of(B, P, P, L, ValueLayout.JAVA_BYTE, P));
		this.classReplaceMethod = handle(objc, "class_replaceMethod", FunctionDescriptor.of(P, P, P, P, P));
		this.classGetInstanceVariable = handle(objc, "class_getInstanceVariable", FunctionDescriptor.of(P, P, P));
		this.ivarGetOffset = handle(objc, "ivar_getOffset", FunctionDescriptor.of(L, P));
		this.ivarGetTypeEncoding = handle(objc, "ivar_getTypeEncoding", FunctionDescriptor.of(P, P));
		this.objcAutorelease = handle(objc, "objc_autorelease", FunctionDescriptor.of(P, P));
		this.poolPush = handle(objc, "objc_autoreleasePoolPush", FunctionDescriptor.of(P));
		this.poolPop = handle(objc, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(P));
		// Found through libobjc's handle, which searches the images it depends on:
		// libSystem's dlsym, and the isa of a block made on the "stack" (ObjcBlocks).
		this.dlsym = handle(objc, "dlsym", FunctionDescriptor.of(P, P, P));
		// A block's literal lives in C memory: a native image serves no shared arena, and
		// the literal is freed from whichever thread frees the block.
		this.malloc = handle(objc, "malloc", FunctionDescriptor.of(P, L));
		this.free = handle(objc, "free", FunctionDescriptor.ofVoid(P));
		this.stackBlockIsa = objc.find("_NSConcreteStackBlock")
			.orElseThrow(() -> new ObjcException("_NSConcreteStackBlock is missing"));
		this.msgSend = objc.find("objc_msgSend").orElseThrow(() -> new ObjcException("objc_msgSend is missing"));
		// x86_64 returns a struct wider than two registers through a hidden pointer and a
		// different entry point; arm64 has one objc_msgSend for everything.
		this.msgSendStret = objc.find("objc_msgSend_stret").orElse(null);
		// A super send takes a struct objc_super { receiver, class to start the lookup
		// in } where a send takes the receiver; the _stret twin exists on x86_64 only.
		this.msgSendSuper = objc.find("objc_msgSendSuper")
			.orElseThrow(() -> new ObjcException("objc_msgSendSuper is missing"));
		this.msgSendSuperStret = objc.find("objc_msgSendSuper_stret").orElse(null);
		// AppKit is opened for its CLASSES, which objc_getClass finds only once the
		// framework's images are loaded; no symbol of it is called.
		appkit.find("NSApplicationMain");
		this.catcher = ObjcCatch.open(objc, (name, descriptor) -> handle(objc, name, descriptor));
	}

	private MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		MemorySegment symbol = lookup.find(name).orElseThrow(() -> new ObjcException(name + " is missing"));
		this.signatures.add(descriptor);
		return downcall(symbol, descriptor, name);
	}

	private static MethodHandle downcall(MemorySegment symbol, FunctionDescriptor descriptor, String what) {
		return downcall(symbol, new Signature(descriptor, -1), what);
	}

	private static MethodHandle downcall(MemorySegment symbol, Signature signature, String what) {
		try {
			return LINKER.downcallHandle(symbol, signature.descriptor(), signature.options());
		}
		catch (Throwable ex) {
			// A native image refuses a shape it did not register at build time
			// (MissingForeignRegistrationError, an Error): name the entry to add.
			throw new ObjcException(what + ": the shape " + signature
					+ " has no foreign-call stub in this binary; register it under foreign.downcalls in "
					+ "reachability-metadata.json and rebuild", ex);
		}
	}

	/**
	 * A bound downcall shape: the descriptor, plus where the variadic argument list
	 * starts. A variadic registration is a DIFFERENT stub than the same descriptor
	 * without one -- on the Apple arm64 ABI a variadic argument goes on the stack where a
	 * fixed one goes in a register -- so the two never share a handle or a metadata
	 * entry.
	 *
	 * @param descriptor the shape
	 * @param firstVariadicArg the index of the first variadic argument, or {@code -1}
	 * when the call is not variadic
	 */
	public record Signature(FunctionDescriptor descriptor, int firstVariadicArg) {

		/**
		 * @return whether this shape is called as a variadic
		 */
		public boolean isVariadic() {
			return this.firstVariadicArg >= 0;
		}

		Linker.Option[] options() {
			return isVariadic() ? new Linker.Option[] { Linker.Option.firstVariadicArg(this.firstVariadicArg) }
					: new Linker.Option[0];
		}

		@Override
		public String toString() {
			return TypeEncoding.spelling(this.descriptor)
					+ (isVariadic() ? " variadic from argument " + this.firstVariadicArg : "");
		}
	}

	/**
	 * Every non-variadic downcall shape this binding and its pump asked the linker for.
	 * @return the shapes
	 * @see #variadicSignatures()
	 */
	public Set<FunctionDescriptor> signatures() {
		Set<FunctionDescriptor> all = new LinkedHashSet<>(this.signatures);
		all.addAll(this.mainThread.signatures());
		for (Signature signature : this.sends.keySet()) {
			if (!signature.isVariadic()) {
				all.add(signature.descriptor());
			}
		}
		for (Signature signature : this.superSends.keySet()) {
			if (!signature.isVariadic()) {
				all.add(signature.descriptor());
			}
		}
		for (Signature signature : this.calls.keySet()) {
			if (!signature.isVariadic()) {
				all.add(signature.descriptor());
			}
		}
		return all;
	}

	/**
	 * Every variadic send shape this binding asked the linker for.
	 * @return the shapes
	 */
	public Set<Signature> variadicSignatures() {
		Set<Signature> all = new LinkedHashSet<>();
		for (Signature signature : this.sends.keySet()) {
			if (signature.isVariadic()) {
				all.add(signature);
			}
		}
		return all;
	}

	/**
	 * The upcall shapes the pump built.
	 * @return the shapes
	 */
	public Set<FunctionDescriptor> upcallSignatures() {
		return this.mainThread.upcallSignatures();
	}

	/**
	 * The pump this binding hops through.
	 * @return the main-thread pump
	 */
	public MainThread mainThread() {
		return this.mainThread;
	}

	// --- opening ------------------------------------------------------------------

	/**
	 * The process-wide binding, opened on first use.
	 * @return the binding
	 * @throws ObjcException when this machine has no Objective-C runtime
	 */
	public static ObjcRuntime get() {
		ObjcRuntime opened = open();
		if (opened == null) {
			throw new ObjcException(unavailableReason());
		}
		return opened;
	}

	/**
	 * Opens the binding, or answers {@code null} when there is nothing to bind: any
	 * platform but macOS, or a JVM that forbids native access. A runtime that opens and
	 * then fails to bind throws.
	 * @return the binding, or {@code null}
	 */
	public static @Nullable ObjcRuntime open() {
		synchronized (OPEN_LOCK) {
			if (instance != null || unavailableReason != null) {
				return instance;
			}
			MainThread pump = MainThread.open();
			if (pump == null) {
				unavailableReason = MainThread.unavailableReason();
				return null;
			}
			SymbolLookup objc, appkit;
			try {
				Arena arena = Arena.global();
				objc = SymbolLookup.libraryLookup(LIB_OBJC, arena);
				appkit = SymbolLookup.libraryLookup(LIB_APPKIT, arena);
			}
			catch (Throwable ex) {
				unavailableReason = "libobjc/AppKit cannot be opened: " + ex;
				return null;
			}
			instance = new ObjcRuntime(objc, appkit, pump);
			return instance;
		}
	}

	/**
	 * Whether this machine has the runtime.
	 * @return {@code true} when {@link #get()} will answer
	 */
	public static boolean available() {
		try {
			return open() != null;
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * Why the runtime is unavailable, in one line, or what was bound.
	 * @return a one-line description
	 */
	public static String description() {
		synchronized (OPEN_LOCK) {
			if (instance != null) {
				return "Objective-C runtime bound (" + LIB_OBJC + ", " + LIB_APPKIT + ")";
			}
			return unavailableReason == null ? "not opened yet" : unavailableReason;
		}
	}

	private static String unavailableReason() {
		String reason = description();
		return "Objective-C is not available: " + reason;
	}

	// --- interning ------------------------------------------------------------------

	/**
	 * The interned selector for a name.
	 * @param name the selector, e.g. {@code setTitle:}
	 * @return its {@code SEL}
	 */
	public MemorySegment selector(String name) {
		return this.selectors.computeIfAbsent(name, key -> {
			try {
				return (MemorySegment) this.selRegisterName.invokeExact(Arena.global().allocateFrom(key));
			}
			catch (Throwable ex) {
				throw new ObjcException("sel_registerName failed for " + key, ex);
			}
		});
	}

	/**
	 * A class by name.
	 * @param name the class name, e.g. {@code NSWindow}
	 * @return the class
	 * @throws ObjcException when no such class is loaded
	 */
	public MemorySegment objcClass(String name) {
		MemorySegment cls = this.classes.computeIfAbsent(name, key -> {
			try {
				return (MemorySegment) this.objcGetClass.invokeExact(Arena.global().allocateFrom(key));
			}
			catch (Throwable ex) {
				throw new ObjcException("objc_getClass failed for " + key, ex);
			}
		});
		if (cls.address() == 0) {
			this.classes.remove(name);
			throw new ObjcException("no Objective-C class named " + name);
		}
		return cls;
	}

	/**
	 * A class by name, or {@code null}.
	 * @param name the class name
	 * @return the class, or {@code null} when none is loaded
	 */
	public @Nullable MemorySegment classOrNull(String name) {
		try {
			return objcClass(name);
		}
		catch (ObjcException ex) {
			return null;
		}
	}

	/**
	 * The class of an object (its metaclass for a class object).
	 * @param object the receiver
	 * @return its class
	 */
	public MemorySegment classOf(MemorySegment object) {
		try {
			return (MemorySegment) this.objectGetClass.invokeExact(object);
		}
		catch (Throwable ex) {
			throw new ObjcException("object_getClass failed", ex);
		}
	}

	/**
	 * The superclass of a class, or {@code null} at the root.
	 * @param cls the class
	 * @return its superclass or {@code null}
	 */
	public @Nullable MemorySegment superclassOf(MemorySegment cls) {
		try {
			MemorySegment sup = (MemorySegment) this.classGetSuperclass.invokeExact(cls);
			return sup.address() == 0 ? null : sup;
		}
		catch (Throwable ex) {
			throw new ObjcException("class_getSuperclass failed", ex);
		}
	}

	private static String cString(MemorySegment chars) {
		return chars.address() == 0 ? "" : chars.reinterpret(Long.MAX_VALUE).getString(0);
	}

	/**
	 * A protocol by name.
	 * @param name the protocol name
	 * @return the protocol
	 * @throws ObjcException when no such protocol is loaded
	 */
	public MemorySegment protocol(String name) {
		try {
			MemorySegment proto = (MemorySegment) this.objcGetProtocol.invokeExact(Arena.global().allocateFrom(name));
			if (proto.address() == 0) {
				throw new ObjcException("no Objective-C protocol named " + name);
			}
			return proto;
		}
		catch (ObjcException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new ObjcException("objc_getProtocol failed for " + name, ex);
		}
	}

	// --- the send -------------------------------------------------------------------

	/**
	 * {@link #sendRaw} mode bit: retain an object result before the hop's pool drains.
	 */
	public static final int RETAIN_RESULT = 1;

	/** {@link #sendRaw} mode bit: answer a C-string result as its address. */
	public static final int RAW_CSTRING = 2;

	/**
	 * {@link #sendRaw} mode bit: the last argument is the address of a pointer-sized slot
	 * the callee may write an object into (an {@code NSError **}); what it holds after
	 * the call is retained before the hop's pool drains.
	 */
	public static final int RETAIN_OUT = 4;

	/**
	 * The address to call for a call of this shape to {@code target}: a catching
	 * trampoline ({@link ObjcCatch}), or the target itself where there is none.
	 */
	private MemorySegment catching(MemorySegment target, Signature signature) {
		ObjcCatch c = this.catcher;
		return c == null ? target : c.entry(target.address(), signature.descriptor());
	}

	/**
	 * Throws {@link ObjcRaised} when the call this thread just made raised an Objective-C
	 * exception (its answer is then garbage and never read).
	 */
	private static void checkRaised(String what) {
		long thrown = ObjcCatch.takeRaised();
		if (thrown != -1) {
			throw new ObjcRaised(what, thrown);
		}
	}

	/** Parsed encodings by spelling: a frame loop sends the same few shapes. */
	private final Map<String, TypeEncoding> encodings = new ConcurrentHashMap<>();

	/**
	 * The parsed form of an encoding, cached by spelling.
	 * @param types the encoding
	 * @return the parsed encoding
	 * @throws ObjcException when the encoding names a type this binding cannot call
	 */
	public TypeEncoding parsed(String types) {
		TypeEncoding cached = this.encodings.get(types);
		if (cached != null) {
			return cached;
		}
		TypeEncoding parsed = TypeEncoding.parse(types);
		this.encodings.put(types, parsed);
		return parsed;
	}

	/**
	 * The primitive layer's send: the whole call described by the caller, nothing looked
	 * up and nothing converted but what only the host can do. Each argument is RAW -- a
	 * {@link Number} for an integral, boolean or address kind (a {@code BOOL} is nonzero
	 * for YES), a {@link Number} for a floating kind, a {@code Number[]} of a struct's
	 * leaves in memory order -- except that a {@link String} is accepted where the
	 * encoding says object (an autoreleased {@code NSString}) or C string (bytes in the
	 * call's arena), and {@code null} anywhere an address goes. The answer is equally
	 * raw: a {@link Long} for every integral, boolean or address kind (an unsigned 32-bit
	 * value zero-extended; a 64-bit one as its bits), a {@link Double} for a floating
	 * kind, a {@code Number[]} for a struct, {@code null} for {@code void} -- and a
	 * {@link String} for a C string, read here because the buffer usually dies with the
	 * caller's pool (unless {@link #RAW_CSTRING}).
	 * <p>
	 * Runs on the calling thread and pushes no pool: {@link #sendRawOnMain} is the entry
	 * point that does both.
	 * @param receiver the receiver's address
	 * @param selector the {@code SEL}'s address
	 * @param types the encoding, covering every argument including variadic ones
	 * @param fixed the number of fixed method arguments of a variadic call, else -1
	 * @param args the method's arguments (not the receiver, not the selector)
	 * @param mode {@link #RETAIN_RESULT} and {@link #RAW_CSTRING}, or 0
	 * @return the raw answer
	 * @throws ObjcException when an argument does not fit, the arity is wrong, or the
	 * shape has no stub in this binary
	 */
	public @Nullable Object sendRaw(long receiver, long selector, String types, int fixed, @Nullable Object[] args,
			int mode) {
		return sendRaw(receiver, 0, selector, types, fixed, args, mode);
	}

	/**
	 * {@link #sendRaw}, or with a nonzero {@code superclass} the SUPER send
	 * ({@code objc_msgSendSuper}): the method is looked up from {@code superclass}, the
	 * class a method's {@code super} names, and runs on {@code receiver}.
	 * @param receiver the receiver's address
	 * @param superclass the class the lookup starts in, or 0 for an ordinary send
	 * @param selector the {@code SEL}'s address
	 * @param types the encoding
	 * @param fixed the fixed-argument count of a variadic call, else -1
	 * @param args the raw arguments
	 * @param mode the mode bits
	 * @return the raw answer
	 */
	public @Nullable Object sendRaw(long receiver, long superclass, long selector, String types, int fixed,
			@Nullable Object[] args, int mode) {
		TypeEncoding encoding = parsed(types);
		List<Type> params = encoding.argumentTypes();
		if (params.size() < 2) {
			throw new ObjcException("type encoding '" + types + "' has no receiver and selector");
		}
		int declared = params.size() - 2;
		String name = selectorName(MemorySegment.ofAddress(selector));
		if (args.length != declared) {
			throw new ObjcException(name + " takes " + declared + " argument(s), got " + args.length);
		}
		if (fixed > declared) {
			throw new ObjcException(name + ": " + fixed + " fixed argument(s) of " + declared);
		}
		try (Arena arena = Arena.ofConfined()) {
			List<Object> all = new ArrayList<>(args.length + 3);
			Type ret = encoding.returnType();
			if (ret.isStruct()) {
				all.add((SegmentAllocator) arena);
			}
			if (superclass != 0) {
				// struct objc_super { id receiver; Class super_class; }, alive for the
				// call.
				MemorySegment sup = arena.allocate(P, 2);
				sup.setAtIndex(P, 0, MemorySegment.ofAddress(receiver));
				sup.setAtIndex(P, 1, MemorySegment.ofAddress(superclass));
				all.add(sup);
			}
			else {
				all.add(MemorySegment.ofAddress(receiver));
			}
			all.add(MemorySegment.ofAddress(selector));
			for (int i = 0; i < declared; i++) {
				all.add(marshalRaw(params.get(i + 2), args[i], arena, name, i));
			}
			Signature signature = new Signature(encoding.descriptor(), fixed < 0 ? -1 : fixed + 2);
			// The same shape through objc_msgSendSuper is a different entry point, so a
			// different handle.
			MethodHandle handle = superclass != 0
					? this.superSends.computeIfAbsent(signature,
							s -> downcall(catching(superTarget(encoding), s), s, name))
					: this.sends.computeIfAbsent(signature, s -> downcall(catching(target(encoding), s), s, name));
			Object raw;
			try {
				raw = handle.invokeWithArguments(all);
			}
			catch (Throwable ex) {
				throw new ObjcException(name + " failed: " + ex, ex);
			}
			checkRaised(name);
			if ((mode & RETAIN_OUT) != 0 && declared > 0 && args[declared - 1] instanceof Number slot
					&& slot.longValue() != 0) {
				// The object the callee wrote through the last argument, alive past the
				// pool this send drains.
				MemorySegment written = MemorySegment.ofAddress(slot.longValue()).reinterpret(8).get(P, 0);
				if (written.address() != 0) {
					retain(written);
				}
			}
			return unmarshalRaw(ret, raw, mode);
		}
	}

	/**
	 * {@link #sendRaw} on thread 0 inside an autorelease pool of its own, so what the
	 * call autoreleased -- a string argument, an unretained result -- is gone when this
	 * returns, deterministically.
	 * @param receiver the receiver's address
	 * @param selector the {@code SEL}'s address
	 * @param types the encoding
	 * @param fixed the fixed-argument count of a variadic call, else -1
	 * @param args the raw arguments
	 * @param mode the mode bits
	 * @return the raw answer
	 */
	public @Nullable Object sendRawOnMain(long receiver, long selector, String types, int fixed,
			@Nullable Object[] args, int mode) {
		return sendRawOnMain(receiver, 0, selector, types, fixed, args, mode);
	}

	/**
	 * {@link #sendRaw(long, long, long, String, int, Object[], int)} on thread 0 inside
	 * an autorelease pool of its own.
	 * @param receiver the receiver's address
	 * @param superclass the class a super send's lookup starts in, or 0
	 * @param selector the {@code SEL}'s address
	 * @param types the encoding
	 * @param fixed the fixed-argument count of a variadic call, else -1
	 * @param args the raw arguments
	 * @param mode the mode bits
	 * @return the raw answer
	 */
	public @Nullable Object sendRawOnMain(long receiver, long superclass, long selector, String types, int fixed,
			@Nullable Object[] args, int mode) {
		return this.mainThread.sync(() -> {
			MemorySegment pool = autoreleasePoolPush();
			try {
				return sendRaw(receiver, superclass, selector, types, fixed, args, mode);
			}
			finally {
				autoreleasePoolPop(pool);
			}
		});
	}

	/**
	 * A call of a C function through its address -- a function {@code dlsym} found, or a
	 * block's invoke pointer -- described whole by the caller, with {@link #sendRaw}'s
	 * raw conventions in both directions. {@code types} covers every argument (there is
	 * no implicit receiver or selector). Runs on the CALLING thread, inside an
	 * autorelease pool of its own: a C function has no thread of its own to hop to, and
	 * one that waits (a semaphore, {@code dispatch_sync}) must not hold thread 0 while a
	 * block it waits for needs it.
	 * @param function the function's address
	 * @param types the encoding: the result, then every argument
	 * @param fixed the number of fixed arguments of a variadic call, else -1
	 * @param args the raw arguments
	 * @param mode {@link #RETAIN_RESULT} and {@link #RAW_CSTRING}, or 0
	 * @return the raw answer
	 * @throws ObjcException when an argument does not fit, the arity is wrong, or the
	 * shape has no stub in this binary
	 */
	public @Nullable Object callRaw(long function, String types, int fixed, @Nullable Object[] args, int mode) {
		if (function == 0) {
			throw new ObjcException("a call through a null function pointer");
		}
		TypeEncoding encoding = parsed(types);
		List<Type> params = encoding.argumentTypes();
		String what = "the function at #x" + Long.toHexString(function);
		if (args.length != params.size()) {
			throw new ObjcException(what + " takes " + params.size() + " argument(s), got " + args.length);
		}
		if (fixed > params.size()) {
			throw new ObjcException(what + ": " + fixed + " fixed argument(s) of " + params.size());
		}
		MemorySegment pool = autoreleasePoolPush();
		try (Arena arena = Arena.ofConfined()) {
			List<Object> all = new ArrayList<>(args.length + 2);
			Signature signature = new Signature(encoding.descriptor(), fixed < 0 ? -1 : fixed);
			all.add(catching(MemorySegment.ofAddress(function), signature));
			Type ret = encoding.returnType();
			if (ret.isStruct()) {
				all.add((SegmentAllocator) arena);
			}
			for (int i = 0; i < params.size(); i++) {
				all.add(marshalRaw(params.get(i), args[i], arena, what, i));
			}
			MethodHandle handle = this.calls.computeIfAbsent(signature, s -> unboundDowncall(s, what));
			Object raw;
			try {
				raw = handle.invokeWithArguments(all);
			}
			catch (Throwable ex) {
				throw new ObjcException(what + " failed: " + ex, ex);
			}
			checkRaised(what);
			return unmarshalRaw(ret, raw, mode);
		}
		finally {
			autoreleasePoolPop(pool);
		}
	}

	private static MethodHandle unboundDowncall(Signature signature, String what) {
		try {
			return LINKER.downcallHandle(signature.descriptor(), signature.options());
		}
		catch (Throwable ex) {
			throw new ObjcException(what + ": the shape " + signature
					+ " has no foreign-call stub in this binary; register it under foreign.downcalls in "
					+ "reachability-metadata.json and rebuild", ex);
		}
	}

	/**
	 * A symbol of any image loaded in the process ({@code dlsym(RTLD_DEFAULT, name)}):
	 * libSystem's own, a framework's, a module {@code ensure-objc-initialized} loaded.
	 * @param name the symbol's C name
	 * @return its address, or 0 when no loaded image defines it
	 */
	public long symbolAddress(String name) {
		try (Arena arena = Arena.ofConfined()) {
			// RTLD_DEFAULT on macOS is ((void *) -2).
			return ((MemorySegment) this.dlsym.invokeExact(MemorySegment.ofAddress(-2L), arena.allocateFrom(name)))
				.address();
		}
		catch (Throwable ex) {
			throw new ObjcException("dlsym failed for " + name, ex);
		}
	}

	/**
	 * {@code malloc}: memory the caller frees with {@link #freeMemory}.
	 * @param size the size in bytes
	 * @return the memory, sized
	 */
	MemorySegment allocateMemory(long size) {
		MemorySegment memory;
		try {
			memory = (MemorySegment) this.malloc.invokeExact(size);
		}
		catch (Throwable ex) {
			throw new ObjcException("malloc failed", ex);
		}
		if (memory.address() == 0) {
			throw new ObjcException("malloc answered no memory for " + size + " bytes");
		}
		return memory.reinterpret(size);
	}

	/**
	 * {@code free}.
	 * @param address memory {@link #allocateMemory} answered
	 */
	void freeMemory(long address) {
		try {
			this.free.invokeExact(MemorySegment.ofAddress(address));
		}
		catch (Throwable ex) {
			throw new ObjcException("free failed", ex);
		}
	}

	/**
	 * The isa of a block literal made outside libclosure: {@code &_NSConcreteStackBlock}
	 * ({@link ObjcBlocks}).
	 * @return its address
	 */
	long stackBlockIsa() {
		return this.stackBlockIsa.address();
	}

	private Object marshalRaw(Type type, @Nullable Object arg, Arena arena, String selector, int index) {
		Kind kind = type.kind();
		if (arg instanceof String s) {
			if (kind == Kind.OBJECT) {
				return nsString(s, arena);
			}
			if (kind == Kind.CSTRING) {
				return arena.allocateFrom(s);
			}
			throw mismatch(selector, index, "a " + kind.name().toLowerCase(Locale.ROOT), arg);
		}
		if (kind == Kind.STRUCT) {
			if (!(arg instanceof Number[] leaves) || leaves.length != type.leaves().size()) {
				throw mismatch(selector, index, "a struct of " + type.leaves().size() + " numbers", arg);
			}
			return struct(type, leaves, arena);
		}
		if (arg == null) {
			if (kind.isAddress()) {
				return MemorySegment.NULL;
			}
			throw mismatch(selector, index, "a " + kind.name().toLowerCase(Locale.ROOT), null);
		}
		if (!(arg instanceof Number n)) {
			throw mismatch(selector, index, "a number", arg);
		}
		return switch (kind) {
			case OBJECT, CLASS, SELECTOR, CSTRING, POINTER -> MemorySegment.ofAddress(n.longValue());
			case BOOL -> n.longValue() != 0;
			case INT8 -> n.byteValue();
			case INT16 -> n.shortValue();
			case INT32 -> n.intValue();
			case INT64 -> n.longValue();
			case FLOAT -> n.floatValue();
			case DOUBLE -> n.doubleValue();
			case VOID, STRUCT -> throw new ObjcException(selector + ": argument " + (index + 1) + " is " + kind);
		};
	}

	private @Nullable Object unmarshalRaw(Type type, @Nullable Object raw, int mode) {
		if (raw == null) {
			return null;
		}
		return switch (type.kind()) {
			case VOID -> null;
			case OBJECT -> {
				MemorySegment object = (MemorySegment) raw;
				if (object.address() != 0 && (mode & RETAIN_RESULT) != 0) {
					retain(object);
				}
				yield object.address();
			}
			case CLASS, SELECTOR, POINTER -> ((MemorySegment) raw).address();
			case CSTRING -> {
				MemorySegment chars = (MemorySegment) raw;
				if ((mode & RAW_CSTRING) != 0) {
					yield chars.address();
				}
				yield chars.address() == 0 ? null : cString(chars);
			}
			case BOOL -> (Boolean) raw ? 1L : 0L;
			case INT8 -> (long) (type.unsigned() ? Byte.toUnsignedInt((Byte) raw) : (Byte) raw);
			case INT16 -> (long) (type.unsigned() ? Short.toUnsignedInt((Short) raw) : (Short) raw);
			case INT32 -> type.unsigned() ? Integer.toUnsignedLong((Integer) raw) : (long) (Integer) raw;
			case INT64 -> raw;
			case FLOAT -> (double) (Float) raw;
			case DOUBLE -> raw;
			case STRUCT -> unmarshal(type, raw);
		};
	}

	/**
	 * The instance-method encoding of a class, by addresses: what {@code objc.lisp}'s
	 * signature lookup reads (a class's metaclass answers its class methods).
	 * @param cls the class's address
	 * @param selector the {@code SEL}'s address
	 * @return the encoding, or {@code null} when the class has no such method
	 */
	public @Nullable String methodTypes(long cls, long selector) {
		try {
			MemorySegment method = (MemorySegment) this.classGetInstanceMethod.invokeExact(MemorySegment.ofAddress(cls),
					MemorySegment.ofAddress(selector));
			if (method.address() == 0) {
				return null;
			}
			MemorySegment types = (MemorySegment) this.methodGetTypeEncoding.invokeExact(method);
			return types.address() == 0 ? null : cString(types);
		}
		catch (Throwable ex) {
			throw new ObjcException("method_getTypeEncoding failed", ex);
		}
	}

	/**
	 * A class by name, by address.
	 * @param name the class name
	 * @return the class's address, or 0 when no such class is loaded
	 */
	public long classOrNullAddress(String name) {
		MemorySegment cls = classOrNull(name);
		return cls == null ? 0 : cls.address();
	}

	/**
	 * The class of an object (its metaclass for a class), by address.
	 * @param object the object's address
	 * @return the class's address
	 */
	public long classOfAddress(long object) {
		return classOf(MemorySegment.ofAddress(object)).address();
	}

	/**
	 * Whether an object is a class (or a metaclass).
	 * @param object the object's address
	 * @return {@code true} for a class object
	 */
	public boolean isClass(long object) {
		try {
			return (boolean) this.objectIsClass.invokeExact(MemorySegment.ofAddress(object));
		}
		catch (Throwable ex) {
			throw new ObjcException("object_isClass failed", ex);
		}
	}

	/**
	 * The name of a class, by address.
	 * @param cls the class's address
	 * @return its name
	 */
	public String nameOfClass(long cls) {
		try {
			return cString((MemorySegment) this.classGetName.invokeExact(MemorySegment.ofAddress(cls)));
		}
		catch (Throwable ex) {
			throw new ObjcException("class_getName failed", ex);
		}
	}

	private MemorySegment target(TypeEncoding encoding) {
		if (this.msgSendStret != null && returnsThroughMemory(encoding)) {
			return this.msgSendStret;
		}
		return this.msgSend;
	}

	private MemorySegment superTarget(TypeEncoding encoding) {
		if (this.msgSendSuperStret != null && returnsThroughMemory(encoding)) {
			return this.msgSendSuperStret;
		}
		return this.msgSendSuper;
	}

	/**
	 * x86_64 returns a struct wider than two registers through a hidden pointer and a
	 * different entry point; arm64 has one entry point for everything.
	 */
	private static boolean returnsThroughMemory(TypeEncoding encoding) {
		Type ret = encoding.returnType();
		return ret.isStruct() && ret.argumentLayout().byteSize() > 16
				&& System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).matches("x86_64|amd64");
	}

	private static ObjcException mismatch(String selector, int index, String expected, @Nullable Object arg) {
		return new ObjcException(selector + ": argument " + (index + 1) + " must be " + expected + ", got "
				+ (arg == null ? "nil" : arg.getClass().getSimpleName()));
	}

	private static MemorySegment struct(Type type, Number[] leaves, Arena arena) {
		MemorySegment out = arena.allocate(type.argumentLayout());
		fill(type, leaves, out);
		return out;
	}

	/**
	 * Writes a struct's leaves, in memory order, into a segment of its layout.
	 * @param type the struct type
	 * @param leaves one number per leaf
	 * @param out the segment
	 */
	static void fill(Type type, Number[] leaves, MemorySegment out) {
		List<Kind> kinds = type.leaves();
		for (int i = 0; i < kinds.size(); i++) {
			Kind leaf = kinds.get(i);
			long offset = type.offsets().get(i);
			switch (leaf) {
				case DOUBLE -> out.set(ValueLayout.JAVA_DOUBLE, offset, leaves[i].doubleValue());
				case FLOAT -> out.set(ValueLayout.JAVA_FLOAT, offset, leaves[i].floatValue());
				case INT64 -> out.set(L, offset, leaves[i].longValue());
				case INT32 -> out.set(ValueLayout.JAVA_INT, offset, leaves[i].intValue());
				case INT16 -> out.set(ValueLayout.JAVA_SHORT, offset, leaves[i].shortValue());
				case INT8 -> out.set(ValueLayout.JAVA_BYTE, offset, leaves[i].byteValue());
				case BOOL -> out.set(B, offset, leaves[i].longValue() != 0);
				default -> out.set(P, offset, MemorySegment.ofAddress(leaves[i].longValue()));
			}
		}
	}

	private @Nullable Object unmarshal(Type type, @Nullable Object raw) {
		if (raw == null) {
			return null;
		}
		return switch (type.kind()) {
			case VOID -> null;
			case OBJECT, CLASS, POINTER -> raw instanceof MemorySegment seg && seg.address() != 0 ? seg : null;
			case SELECTOR -> raw instanceof MemorySegment seg && seg.address() != 0 ? selectorName(seg) : null;
			case CSTRING -> raw instanceof MemorySegment seg && seg.address() != 0 ? cString(seg) : null;
			case BOOL -> raw;
			case INT8 -> (long) (type.unsigned() ? Byte.toUnsignedInt((Byte) raw) : (Byte) raw);
			case INT16 -> (long) (type.unsigned() ? Short.toUnsignedInt((Short) raw) : (Short) raw);
			case INT32 -> type.unsigned() ? Integer.toUnsignedLong((Integer) raw) : (long) (Integer) raw;
			case INT64 -> raw;
			case FLOAT -> (double) (Float) raw;
			case DOUBLE -> raw;
			case STRUCT -> leaves(type, (MemorySegment) raw);
		};
	}

	/**
	 * Reads a struct's leaves, in memory order, out of a segment of its layout.
	 * @param type the struct type
	 * @param seg the segment
	 * @return one number per leaf
	 */
	static Number[] leaves(Type type, MemorySegment seg) {
		List<Kind> kinds = type.leaves();
		Number[] leaves = new Number[kinds.size()];
		for (int i = 0; i < kinds.size(); i++) {
			Kind leaf = kinds.get(i);
			long offset = type.offsets().get(i);
			leaves[i] = switch (leaf) {
				case DOUBLE -> seg.get(ValueLayout.JAVA_DOUBLE, offset);
				case FLOAT -> (double) seg.get(ValueLayout.JAVA_FLOAT, offset);
				case INT64 -> seg.get(L, offset);
				case INT32 -> (long) seg.get(ValueLayout.JAVA_INT, offset);
				case INT16 -> (long) seg.get(ValueLayout.JAVA_SHORT, offset);
				case INT8 -> (long) seg.get(ValueLayout.JAVA_BYTE, offset);
				case BOOL -> seg.get(B, offset) ? 1L : 0L;
				default -> seg.get(P, offset).address();
			};
		}
		return leaves;
	}

	/**
	 * The name of a selector.
	 * @param selector the {@code SEL}
	 * @return its name
	 */
	public String selectorName(MemorySegment selector) {
		try {
			return cString((MemorySegment) this.selGetName.invokeExact(selector));
		}
		catch (Throwable ex) {
			throw new ObjcException("sel_getName failed", ex);
		}
	}

	// --- strings and ownership ------------------------------------------------------

	/**
	 * An autoreleased {@code NSString} for a Java string, built with the UTF-8 bytes
	 * staged in the given arena.
	 * @param value the text
	 * @param arena where the bytes live for the duration of the call
	 * @return the string object
	 */
	public MemorySegment nsString(String value, Arena arena) {
		Object string = sendRaw(objcClass("NSString").address(), selector("stringWithUTF8String:").address(),
				"@24@0:8r*16", -1, new @Nullable Object[] { arena.allocateFrom(value).address() }, 0);
		if (!(string instanceof Long address) || address == 0) {
			throw new ObjcException("stringWithUTF8String: answered nil");
		}
		return MemorySegment.ofAddress(address);
	}

	/**
	 * Retains an object -- the caller now owns one reference and must {@link #release}
	 * it.
	 * @param object the object
	 * @return the same object
	 */
	public MemorySegment retain(MemorySegment object) {
		try {
			return (MemorySegment) this.objcRetain.invokeExact(object);
		}
		catch (Throwable ex) {
			throw new ObjcException("objc_retain failed", ex);
		}
	}

	/**
	 * Releases one reference. Safe on any thread for a Foundation object; an AppKit
	 * object must be released on thread 0, which {@link #releaseOnMain} does.
	 * @param object the object
	 */
	public void release(MemorySegment object) {
		try {
			this.objcRelease.invokeExact(object);
		}
		catch (Throwable ex) {
			throw new ObjcException("objc_release failed", ex);
		}
	}

	/**
	 * Queues a release for thread 0: AppKit deallocates a window or a view on the main
	 * thread only, and the caller (a cleaner) is never there.
	 * @param address the object's address
	 */
	public void releaseOnMain(long address) {
		this.mainThread.async(() -> release(MemorySegment.ofAddress(address)));
	}

	/**
	 * Pushes an autorelease pool.
	 * @return the pool token
	 */
	public MemorySegment autoreleasePoolPush() {
		try {
			return (MemorySegment) this.poolPush.invokeExact();
		}
		catch (Throwable ex) {
			throw new ObjcException("objc_autoreleasePoolPush failed", ex);
		}
	}

	/**
	 * Pops an autorelease pool.
	 * @param pool the token
	 */
	public void autoreleasePoolPop(MemorySegment pool) {
		try {
			this.poolPop.invokeExact(pool);
		}
		catch (Throwable ex) {
			throw new ObjcException("objc_autoreleasePoolPop failed", ex);
		}
	}

	// --- class building ---------------------------------------------------------------

	@Nullable MemorySegment allocateClassPair(MemorySegment superclass, String name) {
		try {
			MemorySegment cls = (MemorySegment) this.objcAllocateClassPair.invokeExact(superclass,
					Arena.global().allocateFrom(name), 0L);
			return cls.address() == 0 ? null : cls;
		}
		catch (Throwable ex) {
			throw new ObjcException("objc_allocateClassPair failed for " + name, ex);
		}
	}

	boolean addProtocol(MemorySegment cls, MemorySegment protocol) {
		try {
			return (boolean) this.classAddProtocol.invokeExact(cls, protocol);
		}
		catch (Throwable ex) {
			throw new ObjcException("class_addProtocol failed", ex);
		}
	}

	void registerClassPair(MemorySegment cls) {
		try {
			this.objcRegisterClassPair.invokeExact(cls);
		}
		catch (Throwable ex) {
			throw new ObjcException("objc_registerClassPair failed", ex);
		}
	}

	// --- class definition, by addresses
	// -------------------------------------------------

	/**
	 * The instance variable every class {@code objc-class.lisp} defines carries: how a
	 * later definition in the same process -- another interpreter, a compiled program
	 * with its own copy of this class, a re-evaluated form -- recognizes a class it may
	 * reuse, since the runtime cannot remove one. A subclass inherits it; a class Lisp
	 * did not define never has it.
	 */
	public static final String DEFINED_MARKER = "rontolispDefinedClass";

	/**
	 * Allocates a class pair, by addresses -- or answers the class of that name a
	 * definition in this process made before, which is then registered already.
	 * @param superclass the superclass's address
	 * @param name the new class's name
	 * @return the class's address, or 0 when the name belongs to a class no definition
	 * made (or the runtime refuses it)
	 */
	public long allocateClass(long superclass, String name) {
		MemorySegment existing = classOrNull(name);
		if (existing != null) {
			return ivarOffset(existing.address(), DEFINED_MARKER) >= 0 ? existing.address() : 0;
		}
		MemorySegment cls = allocateClassPair(MemorySegment.ofAddress(superclass), name);
		if (cls == null) {
			return 0;
		}
		addIvar(cls.address(), DEFINED_MARKER, 1, 1, "c");
		return cls.address();
	}

	/**
	 * Adds an instance variable to a class pair not yet registered.
	 * @param cls the class's address
	 * @param name the variable's name
	 * @param size its size in bytes
	 * @param alignment its alignment in bytes (a power of two)
	 * @param types its type encoding
	 * @return whether the runtime added it
	 */
	public boolean addIvar(long cls, String name, long size, long alignment, String types) {
		byte log2 = (byte) (63 - Long.numberOfLeadingZeros(Math.max(1, alignment)));
		try {
			return (boolean) this.classAddIvar.invokeExact(MemorySegment.ofAddress(cls),
					Arena.global().allocateFrom(name), size, log2, Arena.global().allocateFrom(types));
		}
		catch (Throwable ex) {
			throw new ObjcException("class_addIvar failed for " + name, ex);
		}
	}

	/**
	 * Registers a class pair, by address.
	 * @param cls the class's address
	 */
	public void registerClass(long cls) {
		registerClassPair(MemorySegment.ofAddress(cls));
	}

	/**
	 * Adds a method to a class, or replaces the one the class itself defines.
	 * @param cls the class's (or, for a class method, the metaclass's) address
	 * @param selector the {@code SEL}'s address
	 * @param imp the implementation
	 * @param types the method's encoding
	 */
	public void putMethod(long cls, long selector, MemorySegment imp, String types) {
		// The runtime keeps the encoding's bytes: they live in the global arena.
		MemorySegment encoding = Arena.global().allocateFrom(types);
		try {
			// class_replaceMethod adds a method the class lacks, and replaces the one it
			// has -- never a superclass's.
			MemorySegment previous = (MemorySegment) this.classReplaceMethod.invokeExact(MemorySegment.ofAddress(cls),
					MemorySegment.ofAddress(selector), imp, encoding);
			if (previous == null) {
				throw new ObjcException("class_replaceMethod answered nothing");
			}
		}
		catch (ObjcException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new ObjcException("class_replaceMethod failed", ex);
		}
	}

	/**
	 * Adopts a protocol, by name.
	 * @param cls the class's address
	 * @param name the protocol's name
	 * @return false when no protocol of that name is loaded
	 */
	public boolean addProtocol(long cls, String name) {
		MemorySegment proto;
		try {
			proto = protocol(name);
		}
		catch (ObjcException ex) {
			return false;
		}
		addProtocol(MemorySegment.ofAddress(cls), proto);
		return true;
	}

	/**
	 * The superclass of a class, by address.
	 * @param cls the class's address
	 * @return the superclass's address, 0 at a root
	 */
	public long superclassAddress(long cls) {
		MemorySegment sup = superclassOf(MemorySegment.ofAddress(cls));
		return sup == null ? 0 : sup.address();
	}

	/**
	 * An instance variable's offset, searched up the superclass chain.
	 * @param cls the class's address
	 * @param name the variable's name
	 * @return the offset, or -1 when the class has no such variable
	 */
	public long ivarOffset(long cls, String name) {
		try {
			MemorySegment ivar = (MemorySegment) this.classGetInstanceVariable.invokeExact(MemorySegment.ofAddress(cls),
					Arena.global().allocateFrom(name));
			return ivar.address() == 0 ? -1 : (long) this.ivarGetOffset.invokeExact(ivar);
		}
		catch (Throwable ex) {
			throw new ObjcException("class_getInstanceVariable failed for " + name, ex);
		}
	}

	/**
	 * An instance variable's type encoding.
	 * @param cls the class's address
	 * @param name the variable's name
	 * @return the encoding, or {@code null} when the class has no such variable
	 */
	public @Nullable String ivarTypes(long cls, String name) {
		try {
			MemorySegment ivar = (MemorySegment) this.classGetInstanceVariable.invokeExact(MemorySegment.ofAddress(cls),
					Arena.global().allocateFrom(name));
			if (ivar.address() == 0) {
				return null;
			}
			MemorySegment types = (MemorySegment) this.ivarGetTypeEncoding.invokeExact(ivar);
			return types.address() == 0 ? null : cString(types);
		}
		catch (Throwable ex) {
			throw new ObjcException("ivar_getTypeEncoding failed for " + name, ex);
		}
	}

	/**
	 * Autoreleases an object into the innermost pool of the calling thread.
	 * @param object the object
	 */
	public void autorelease(MemorySegment object) {
		try {
			MemorySegment ignored = (MemorySegment) this.objcAutorelease.invokeExact(object);
		}
		catch (Throwable ex) {
			throw new ObjcException("objc_autorelease failed", ex);
		}
	}

	/**
	 * Reads one value of a type from memory, raw as {@link #sendRaw} answers it; an
	 * object read is RETAINED for the value the caller makes of it.
	 * @param address where the value lives
	 * @param types the value's encoding (one type)
	 * @return the raw value
	 */
	public @Nullable Object peek(long address, String types) {
		Type type = parsed(types).returnType();
		MemorySegment at = MemorySegment.ofAddress(address).reinterpret(Math.max(1, type.argumentLayout().byteSize()));
		return switch (type.kind()) {
			case STRUCT -> unmarshal(type, at);
			case OBJECT -> {
				MemorySegment object = at.get(P, 0);
				if (object.address() != 0) {
					retain(object);
				}
				yield object.address();
			}
			default -> unmarshalRaw(type, read(type.kind(), at), 0);
		};
	}

	/**
	 * Writes one raw value of a type to memory ({@link #sendRaw}'s argument conventions;
	 * a string only where the type is an object, as an autoreleased {@code NSString}).
	 * @param address where the value goes
	 * @param types the value's encoding (one type)
	 * @param raw the raw value
	 */
	public void poke(long address, String types, @Nullable Object raw) {
		Type type = parsed(types).returnType();
		MemorySegment at = MemorySegment.ofAddress(address).reinterpret(Math.max(1, type.argumentLayout().byteSize()));
		try (Arena arena = Arena.ofConfined()) {
			Object value = marshalRaw(type, raw, arena, "a memory write", 0);
			if (value instanceof MemorySegment seg && type.kind() == Kind.STRUCT) {
				at.copyFrom(seg);
			}
			else {
				write(type.kind(), at, value);
			}
		}
	}

	/**
	 * Copies bytes to foreign memory.
	 * @param address where they go
	 * @param bytes the bytes
	 */
	public static void writeBytes(long address, byte[] bytes) {
		if (bytes.length == 0) {
			return;
		}
		if (address == 0) {
			throw new ObjcException("cannot write " + bytes.length + " byte(s) to NULL");
		}
		MemorySegment.ofAddress(address).reinterpret(bytes.length).copyFrom(MemorySegment.ofArray(bytes));
	}

	/**
	 * Copies a block of foreign memory out.
	 * @param address where it starts
	 * @param length how many bytes
	 * @return a fresh copy
	 */
	public static byte[] readBytes(long address, long length) {
		if (length <= 0) {
			return new byte[0];
		}
		if (address == 0) {
			throw new ObjcException("cannot read " + length + " byte(s) from NULL");
		}
		if (length > Integer.MAX_VALUE - 8) {
			throw new ObjcException("a block of " + length + " bytes does not fit in a vector");
		}
		return MemorySegment.ofAddress(address).reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
	}

	private static Object read(Kind kind, MemorySegment at) {
		return switch (kind) {
			case BOOL -> at.get(B, 0);
			case INT8 -> at.get(ValueLayout.JAVA_BYTE, 0);
			case INT16 -> at.get(ValueLayout.JAVA_SHORT, 0);
			case INT32 -> at.get(ValueLayout.JAVA_INT, 0);
			case INT64 -> at.get(L, 0);
			case FLOAT -> at.get(ValueLayout.JAVA_FLOAT, 0);
			case DOUBLE -> at.get(ValueLayout.JAVA_DOUBLE, 0);
			default -> at.get(P, 0);
		};
	}

	private static void write(Kind kind, MemorySegment at, Object value) {
		switch (kind) {
			case BOOL -> at.set(B, 0, (Boolean) value);
			case INT8 -> at.set(ValueLayout.JAVA_BYTE, 0, (Byte) value);
			case INT16 -> at.set(ValueLayout.JAVA_SHORT, 0, (Short) value);
			case INT32 -> at.set(ValueLayout.JAVA_INT, 0, (Integer) value);
			case INT64 -> at.set(L, 0, (Long) value);
			case FLOAT -> at.set(ValueLayout.JAVA_FLOAT, 0, (Float) value);
			case DOUBLE -> at.set(ValueLayout.JAVA_DOUBLE, 0, (Double) value);
			default -> at.set(P, 0, (MemorySegment) value);
		}
	}

	/**
	 * Binds an upcall stub, naming the shape when a native image refuses it.
	 * @param target the Java method
	 * @param descriptor the shape
	 * @param what what the stub is for, for the message
	 * @return the stub
	 */
	static MemorySegment upcall(MethodHandle target, FunctionDescriptor descriptor, String what) {
		try {
			return LINKER.upcallStub(target, descriptor, Arena.global());
		}
		catch (Throwable ex) {
			throw new ObjcException(what + ": the callback shape " + TypeEncoding.spelling(descriptor)
					+ " has no upcall stub in this binary; register it under foreign.upcalls in "
					+ "reachability-metadata.json and rebuild", ex);
		}
	}

}
