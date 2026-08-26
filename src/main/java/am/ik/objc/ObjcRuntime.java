package am.ik.objc;

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
 * table of selector shapes to a {@linkplain #send generic send} whose shape is read back
 * from the runtime.
 *
 * <h2>One {@code objc_msgSend} handle per shape, derived from the encoding</h2>
 *
 * Apple's arm64 rule is that {@code objc_msgSend} must be called through a prototype
 * matching the selector, never as the variadic it is declared as. {@link #send} asks the
 * runtime for the selector's type encoding ({@link TypeEncoding}), turns it into a
 * {@link FunctionDescriptor}, and binds -- once per distinct shape -- a downcall handle
 * for it. A native image builds a downcall stub only for a shape registered at build time
 * and refuses any other, so the registered set is a CLOSED table
 * ({@code reachability-metadata.json}); a selector whose shape is outside it fails with
 * an {@link ObjcException} that spells the entry to add, never a silent decline and never
 * a SIGBUS.
 *
 * <h2>Threads</h2>
 *
 * This class is a plain binding and runs on whichever thread calls it. AppKit demands
 * thread 0, and the policy that every send hops there lives in the caller
 * ({@link MainThread}); a {@link #retain} or a {@link #release} is safe anywhere.
 *
 * <h2>Absent is not broken</h2>
 *
 * {@link #open()} answers {@code null} only when the libraries are not there -- Linux, or
 * a JVM that forbids native access; a machine that has them and then fails to bind one
 * throws, because those are different answers and the caller printing them must be able
 * to tell.
 *
 * @see TypeEncoding
 * @see ObjcClasses
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

	private final MethodHandle classGetName;

	private final MethodHandle classGetSuperclass;

	private final MethodHandle classGetInstanceMethod;

	private final MethodHandle methodGetTypeEncoding;

	private final MethodHandle objcRetain;

	private final MethodHandle objcRelease;

	private final MethodHandle objcGetProtocol;

	private final MethodHandle protocolGetMethodDescription;

	private final MethodHandle objcAllocateClassPair;

	private final MethodHandle classAddMethod;

	private final MethodHandle classAddProtocol;

	private final MethodHandle objcRegisterClassPair;

	private final MethodHandle poolPush;

	private final MethodHandle poolPop;

	private final MemorySegment msgSend;

	private final @Nullable MemorySegment msgSendStret;

	private final MainThread mainThread;

	/** The shapes bound, in binding order, for the native-image registration test. */
	private final Set<FunctionDescriptor> signatures = new LinkedHashSet<>();

	private final Map<FunctionDescriptor, MethodHandle> sends = new ConcurrentHashMap<>();

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
		this.classGetName = handle(objc, "class_getName", FunctionDescriptor.of(P, P));
		this.classGetSuperclass = handle(objc, "class_getSuperclass", FunctionDescriptor.of(P, P));
		this.classGetInstanceMethod = handle(objc, "class_getInstanceMethod", FunctionDescriptor.of(P, P, P));
		this.methodGetTypeEncoding = handle(objc, "method_getTypeEncoding", FunctionDescriptor.of(P, P));
		this.objcRetain = handle(objc, "objc_retain", FunctionDescriptor.of(P, P));
		this.objcRelease = handle(objc, "objc_release", FunctionDescriptor.ofVoid(P));
		this.objcGetProtocol = handle(objc, "objc_getProtocol", FunctionDescriptor.of(P, P));
		// struct objc_method_description { SEL name; const char *types; }, by value.
		this.protocolGetMethodDescription = handle(objc, "protocol_getMethodDescription",
				FunctionDescriptor.of(MemoryLayout.structLayout(P, P), P, P, B, B));
		this.objcAllocateClassPair = handle(objc, "objc_allocateClassPair", FunctionDescriptor.of(P, P, P, L));
		this.classAddMethod = handle(objc, "class_addMethod", FunctionDescriptor.of(B, P, P, P, P));
		this.classAddProtocol = handle(objc, "class_addProtocol", FunctionDescriptor.of(B, P, P));
		this.objcRegisterClassPair = handle(objc, "objc_registerClassPair", FunctionDescriptor.ofVoid(P));
		this.poolPush = handle(objc, "objc_autoreleasePoolPush", FunctionDescriptor.of(P));
		this.poolPop = handle(objc, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(P));
		this.msgSend = objc.find("objc_msgSend").orElseThrow(() -> new ObjcException("objc_msgSend is missing"));
		// x86_64 returns a struct wider than two registers through a hidden pointer and a
		// different entry point; arm64 has one objc_msgSend for everything.
		this.msgSendStret = objc.find("objc_msgSend_stret").orElse(null);
		// AppKit is opened for its CLASSES, which objc_getClass finds only once the
		// framework's images are loaded; no symbol of it is called.
		appkit.find("NSApplicationMain");
	}

	private MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		MemorySegment symbol = lookup.find(name).orElseThrow(() -> new ObjcException(name + " is missing"));
		this.signatures.add(descriptor);
		return downcall(symbol, descriptor, name);
	}

	private static MethodHandle downcall(MemorySegment symbol, FunctionDescriptor descriptor, String what) {
		try {
			return LINKER.downcallHandle(symbol, descriptor);
		}
		catch (Throwable ex) {
			// A native image refuses a shape it did not register at build time
			// (MissingForeignRegistrationError, an Error): name the entry to add.
			throw new ObjcException(what + ": the shape " + TypeEncoding.spelling(descriptor)
					+ " has no foreign-call stub in this binary; register it under foreign.downcalls in "
					+ "reachability-metadata.json and rebuild", ex);
		}
	}

	/**
	 * Every downcall shape this binding and its pump asked the linker for.
	 * @return the shapes
	 */
	public Set<FunctionDescriptor> signatures() {
		Set<FunctionDescriptor> all = new LinkedHashSet<>(this.signatures);
		all.addAll(this.mainThread.signatures());
		all.addAll(this.sends.keySet());
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

	/**
	 * The name of an object's class -- what a printer shows.
	 * @param object the receiver
	 * @return the class name
	 */
	public String className(MemorySegment object) {
		try {
			MemorySegment name = (MemorySegment) this.classGetName.invokeExact(classOf(object));
			return cString(name);
		}
		catch (Throwable ex) {
			throw new ObjcException("class_getName failed", ex);
		}
	}

	private static String cString(MemorySegment chars) {
		return chars.address() == 0 ? "" : chars.reinterpret(Long.MAX_VALUE).getString(0);
	}

	// --- encodings ------------------------------------------------------------------

	/**
	 * The type encoding of the method a receiver runs for a selector -- an instance
	 * method of its class, which for a class receiver means a class method.
	 * @param receiver the object or class
	 * @param selector the selector name
	 * @return the encoding
	 * @throws ObjcException when the receiver does not respond to the selector
	 */
	public TypeEncoding encoding(MemorySegment receiver, String selector) {
		String raw = rawEncoding(classOf(receiver), selector);
		if (raw == null) {
			throw new ObjcException(className(receiver) + " does not respond to " + selector);
		}
		return TypeEncoding.parse(raw);
	}

	/**
	 * The raw type encoding of a class's instance method, or {@code null}.
	 * @param cls the class
	 * @param selector the selector name
	 * @return the encoding string or {@code null} when the class has no such method
	 */
	public @Nullable String rawEncoding(MemorySegment cls, String selector) {
		try {
			MemorySegment method = (MemorySegment) this.classGetInstanceMethod.invokeExact(cls, selector(selector));
			if (method.address() == 0) {
				return null;
			}
			MemorySegment types = (MemorySegment) this.methodGetTypeEncoding.invokeExact(method);
			return types.address() == 0 ? null : cString(types);
		}
		catch (Throwable ex) {
			throw new ObjcException("method_getTypeEncoding failed for " + selector, ex);
		}
	}

	/**
	 * The raw type encoding a protocol declares for an instance method, required or
	 * optional, or {@code null}.
	 * @param protocol the protocol name, e.g. {@code NSWindowDelegate}
	 * @param selector the selector name
	 * @return the encoding string, or {@code null} when the protocol does not declare it
	 * @throws ObjcException when there is no such protocol
	 */
	public @Nullable String protocolEncoding(String protocol, String selector) {
		MemorySegment proto = protocol(protocol);
		try (Arena arena = Arena.ofConfined()) {
			for (boolean required : new boolean[] { true, false }) {
				MemorySegment description = (MemorySegment) this.protocolGetMethodDescription
					.invokeExact((SegmentAllocator) arena, proto, selector(selector), required, true);
				MemorySegment types = description.get(P, 8);
				if (types.address() != 0) {
					return cString(types);
				}
			}
			return null;
		}
		catch (Throwable ex) {
			throw new ObjcException("protocol_getMethodDescription failed for " + selector, ex);
		}
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

	// --- send -----------------------------------------------------------------------

	/**
	 * What a {@link #send} answered, with the kind the receiver declared for it -- the
	 * caller needs the kind to tell an object it should retain from a raw pointer it must
	 * not.
	 *
	 * @param value the marshalled value ({@code null} for {@code void}, nil and NULL)
	 * @param type the declared return type
	 */
	public record Sent(@Nullable Object value, Type type) {

		/**
		 * Whether the answer is the Cocoa failure value -- nil, {@code NO}, or zero. It
		 * is the RESULT, never the error slot, that says a call failed (Foundation's own
		 * rule), so this is what {@link #checkError} gates on.
		 * @return {@code true} when the call reported failure
		 */
		public boolean failed() {
			return this.value == null || Boolean.FALSE.equals(this.value) || (this.value instanceof Long n && n == 0L);
		}
	}

	/**
	 * A pointer-sized out-parameter slot. Pass one to {@link #send} in place of a
	 * {@code ^@} argument and the binding allocates the slot, passes its address, and
	 * fills {@link #value()} with whatever the callee wrote -- the {@code NSError **} of
	 * every {@code ...error:} selector in Cocoa, which the caller could otherwise neither
	 * supply nor read.
	 *
	 * <p>
	 * What the callee writes is AUTORELEASED, so a caller that keeps it must retain it
	 * before the hop's pool drains, exactly as for any other object a send answers.
	 */
	public static final class Out {

		private @Nullable MemorySegment value;

		/**
		 * What the callee wrote into the slot.
		 * @return the object, or {@code null} when the slot was left NULL
		 */
		public @Nullable MemorySegment value() {
			return this.value;
		}

	}

	/**
	 * Sends a message, marshalling each argument by the selector's declared type:
	 * <ul>
	 * <li>an object, class or pointer parameter takes a {@link MemorySegment}
	 * ({@code null} for nil) -- an object parameter also takes a {@link String}, sent as
	 * an autoreleased {@code NSString}, and a class parameter a class name;</li>
	 * <li>a selector parameter takes the selector name;</li>
	 * <li>a C-string parameter takes a {@link String};</li>
	 * <li>{@code BOOL} takes a {@link Boolean}; an integer or floating kind a
	 * {@link Number};</li>
	 * <li>a struct takes a {@code Number[]} of its scalar leaves in memory order.</li>
	 * </ul>
	 * and answers the result the same way: a {@link MemorySegment} for an object, class
	 * or pointer ({@code null} for nil), a {@link String} for a selector or C string, a
	 * {@link Boolean}, a {@link Long} for any integer, a {@link Double} for any float, a
	 * {@code Number[]} for a struct, and {@code null} for void.
	 * @param receiver the object or class
	 * @param selector the selector name
	 * @param args the selector's own arguments (not the receiver, not the selector)
	 * @return the marshalled result and its declared type
	 * @throws ObjcException when the receiver does not respond, the arity is wrong, an
	 * argument does not fit its parameter, or the shape has no stub in this binary
	 */
	public Sent send(MemorySegment receiver, String selector, @Nullable Object... args) {
		TypeEncoding encoding = encoding(receiver, selector);
		List<Type> params = encoding.argumentTypes();
		if (params.size() != args.length + 2) {
			throw new ObjcException(selector + " takes " + (params.size() - 2) + " argument(s), got " + args.length);
		}
		FunctionDescriptor descriptor = encoding.descriptor();
		MethodHandle handle = this.sends.computeIfAbsent(descriptor, d -> downcall(target(encoding), d, selector));
		try (Arena arena = Arena.ofConfined()) {
			List<Object> all = new ArrayList<>(args.length + 3);
			Type ret = encoding.returnType();
			if (ret.isStruct()) {
				all.add((SegmentAllocator) arena);
			}
			all.add(receiver);
			all.add(selector(selector));
			List<Out> outs = List.of();
			List<MemorySegment> slots = List.of();
			for (int i = 0; i < args.length; i++) {
				if (args[i] instanceof Out out) {
					Type param = params.get(i + 2);
					if (param.kind() != Kind.POINTER) {
						throw new ObjcException(selector + ": argument " + (i + 1) + " is declared "
								+ param.kind().name().toLowerCase(Locale.ROOT) + ", not a pointer, so it takes no"
								+ " out slot");
					}
					MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
					slot.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
					all.add(slot);
					if (outs.isEmpty()) {
						outs = new ArrayList<>(1);
						slots = new ArrayList<>(1);
					}
					outs.add(out);
					slots.add(slot);
				}
				else {
					all.add(marshal(params.get(i + 2), args[i], arena, selector, i));
				}
			}
			Object raw;
			try {
				raw = handle.invokeWithArguments(all);
			}
			catch (Throwable ex) {
				throw new ObjcException(selector + " failed: " + ex, ex);
			}
			for (int i = 0; i < outs.size(); i++) {
				MemorySegment written = slots.get(i).get(ValueLayout.ADDRESS, 0);
				outs.get(i).value = written.address() == 0 ? null : written;
			}
			return new Sent(unmarshal(ret, raw), ret);
		}
	}

	private MemorySegment target(TypeEncoding encoding) {
		Type ret = encoding.returnType();
		if (this.msgSendStret != null && ret.isStruct() && ret.argumentLayout().byteSize() > 16
				&& System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).matches("x86_64|amd64")) {
			return this.msgSendStret;
		}
		return this.msgSend;
	}

	private Object marshal(Type type, @Nullable Object arg, Arena arena, String selector, int index) {
		Kind kind = type.kind();
		try {
			return switch (kind) {
				case OBJECT -> switch (arg) {
					case null -> MemorySegment.NULL;
					case MemorySegment seg -> seg;
					case String s -> nsString(s, arena);
					default -> throw mismatch(selector, index, "an object", arg);
				};
				case CLASS -> switch (arg) {
					case null -> MemorySegment.NULL;
					case MemorySegment seg -> seg;
					case String s -> objcClass(s);
					default -> throw mismatch(selector, index, "a class", arg);
				};
				case POINTER -> switch (arg) {
					case null -> MemorySegment.NULL;
					case MemorySegment seg -> seg;
					case Number n -> MemorySegment.ofAddress(n.longValue());
					default -> throw mismatch(selector, index, "a pointer", arg);
				};
				case SELECTOR -> switch (arg) {
					case null -> MemorySegment.NULL;
					case MemorySegment seg -> seg;
					case String s -> selector(s);
					default -> throw mismatch(selector, index, "a selector name", arg);
				};
				case CSTRING -> switch (arg) {
					case null -> MemorySegment.NULL;
					case MemorySegment seg -> seg;
					case String s -> arena.allocateFrom(s);
					default -> throw mismatch(selector, index, "a string", arg);
				};
				case BOOL -> switch (arg) {
					case null -> Boolean.FALSE;
					case Boolean b -> b;
					default -> throw mismatch(selector, index, "a boolean", arg);
				};
				case INT8 -> scalar(arg, selector, index, "an integer").byteValue();
				case INT16 -> scalar(arg, selector, index, "an integer").shortValue();
				case INT32 -> scalar(arg, selector, index, "an integer").intValue();
				case INT64 -> scalar(arg, selector, index, "an integer").longValue();
				case FLOAT -> scalar(arg, selector, index, "a number").floatValue();
				case DOUBLE -> scalar(arg, selector, index, "a number").doubleValue();
				case STRUCT -> {
					if (!(arg instanceof Number[] leaves) || leaves.length != type.leaves().size()) {
						throw mismatch(selector, index, "a struct of " + type.leaves().size() + " numbers", arg);
					}
					yield struct(type, leaves, arena);
				}
				case VOID -> throw new ObjcException(selector + ": a void parameter");
			};
		}
		catch (ObjcException ex) {
			throw ex;
		}
	}

	private static Number scalar(@Nullable Object arg, String selector, int index, String expected) {
		if (arg instanceof Number n) {
			return n;
		}
		throw mismatch(selector, index, expected, arg);
	}

	private static ObjcException mismatch(String selector, int index, String expected, @Nullable Object arg) {
		return new ObjcException(selector + ": argument " + (index + 1) + " must be " + expected + ", got "
				+ (arg == null ? "nil" : arg.getClass().getSimpleName()));
	}

	private static MemorySegment struct(Type type, Number[] leaves, Arena arena) {
		MemorySegment out = arena.allocate(type.layout());
		long offset = 0;
		List<Kind> kinds = type.leaves();
		for (int i = 0; i < kinds.size(); i++) {
			Kind leaf = kinds.get(i);
			MemoryLayout layout = leaf.scalarLayout();
			long align = layout.byteAlignment();
			offset += (align - offset % align) % align;
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
			offset += layout.byteSize();
		}
		return out;
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
			case STRUCT -> {
				MemorySegment seg = (MemorySegment) raw;
				List<Kind> kinds = type.leaves();
				Number[] leaves = new Number[kinds.size()];
				long offset = 0;
				for (int i = 0; i < kinds.size(); i++) {
					Kind leaf = kinds.get(i);
					MemoryLayout layout = leaf.scalarLayout();
					long align = layout.byteAlignment();
					offset += (align - offset % align) % align;
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
					offset += layout.byteSize();
				}
				yield leaves;
			}
		};
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
		Sent sent = send(objcClass("NSString"), "stringWithUTF8String:", (Object) arena.allocateFrom(value));
		if (!(sent.value() instanceof MemorySegment string)) {
			throw new ObjcException("stringWithUTF8String: answered nil");
		}
		return string;
	}

	/**
	 * The text of an {@code NSString}.
	 * @param string the string object
	 * @return its UTF-8 contents
	 */
	public String string(MemorySegment string) {
		Object chars = send(string, "UTF8String").value();
		return chars == null ? "" : (String) chars;
	}

	/**
	 * An autoreleased {@code NSMutableData} holding a COPY of the given bytes, staged in
	 * the given arena. Mutable rather than an {@code NSData} so one object serves both
	 * directions: {@code bytes} hands the block to a {@code ^v} parameter, and
	 * {@code mutableBytes} is writable scratch a callee can fill.
	 * @param bytes the contents
	 * @param arena where the staging copy lives for the duration of the call
	 * @return the data object
	 */
	public MemorySegment nsData(byte[] bytes, Arena arena) {
		MemorySegment source = bytes.length == 0 ? MemorySegment.NULL
				: arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
		Sent sent = send(objcClass("NSMutableData"), "dataWithBytes:length:", source, (long) bytes.length);
		if (!(sent.value() instanceof MemorySegment data)) {
			throw new ObjcException("dataWithBytes:length: answered nil");
		}
		return data;
	}

	/**
	 * The bytes of an {@code NSData}, copied out.
	 * @param data the data object
	 * @return its contents
	 */
	public byte[] dataBytes(MemorySegment data) {
		Object length = send(data, "length").value();
		if (!(length instanceof Long size)) {
			throw new ObjcException("length answered no integer; the receiver is not an NSData");
		}
		if (size == 0) {
			return new byte[0];
		}
		Object pointer = send(data, "bytes").value();
		if (!(pointer instanceof MemorySegment block) || block.address() == 0) {
			throw new ObjcException("bytes answered NULL for a data of " + size + " byte(s)");
		}
		return block.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
	}

	/**
	 * The {@code ...error:} convention: when the call reported failure AND filled the out
	 * slot, raise what the {@code NSError} says. Nothing else in this binding answers a
	 * bare nil for a failure, and neither does a selector asked for its error.
	 * @param out the slot that was passed
	 * @param sent what the call answered
	 * @param selector the selector, for the message
	 * @throws ObjcException when the call failed and named a reason
	 */
	public void checkError(Out out, Sent sent, String selector) {
		MemorySegment error = out.value();
		if (error == null || !sent.failed()) {
			return;
		}
		Object description = send(error, "localizedDescription").value();
		Object domain = send(error, "domain").value();
		Object code = send(error, "code").value();
		String reason = description instanceof MemorySegment text ? string(text) : "no reason given";
		String where = domain instanceof MemorySegment name ? " [" + string(name) + " " + code + "]" : "";
		throw new ObjcException(selector + ": " + reason + where);
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

	// --- class building (for ObjcClasses) -------------------------------------------

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

	boolean addMethod(MemorySegment cls, String selector, MemorySegment imp, String types) {
		try {
			return (boolean) this.classAddMethod.invokeExact(cls, selector(selector), imp,
					Arena.global().allocateFrom(types));
		}
		catch (Throwable ex) {
			throw new ObjcException("class_addMethod failed for " + selector, ex);
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
