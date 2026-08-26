package am.ik.ffi;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import am.ik.ffi.FfiType.Scalar;
import am.ik.ffi.FfiType.Struct;
import org.jspecify.annotations.Nullable;

/**
 * The binding to plain C through {@code java.lang.foreign}: dlopen by name, symbol
 * addresses, downcalls whose whole calling convention -- return type, argument types,
 * where the variadic tail starts -- is decided at RUN time, upcall stubs over a caller's
 * function, {@code malloc}/{@code free} reached as ordinary downcalls, and typed
 * loads/stores at raw addresses. The C-flavoured sibling of {@link am.ik.objc.ObjcRuntime
 * ObjcRuntime}, and like it language-independent: the protocol is Java-typed
 * ({@code Long} for every integer and every address, {@code Double} for the two floating
 * types, {@code String} for {@code :string}), and the caller marshals its own values.
 *
 * <h2>One downcall handle per shape</h2>
 *
 * Building a downcall handle costs ~24 us; calling a cached one ~0.5 us. {@link #call}
 * therefore binds -- once per distinct (descriptor, variadic index) shape -- an UNBOUND
 * handle that takes the target address as its leading argument, so one cached handle
 * serves every symbol of that shape and the cache key needs no library or symbol in it.
 * The cache lives in a {@code static final} field; the JIT still cannot treat a
 * map-fetched handle as a constant (the {@code Invokers.checkCustomized} trap of
 * {@code am.ik.gpu}), and the compiled twin that wants {@code invokeExact} on constants
 * is the JVM backend's job, not this class's.
 *
 * <h2>{@code errno} is captured, not fetched</h2>
 *
 * Every downcall handle is bound with {@code Linker.Option.captureCallState("errno")}
 * into a PER-THREAD capture segment, so {@link #errno()} answers the value the calling
 * thread's LAST call left -- the only version of this that is correct under threads.
 *
 * <h2>Absent is not broken</h2>
 *
 * {@link #open()} answers {@code null} only when this JVM denies native access
 * ({@code --illegal-native-access=deny}); a machine that has it and then fails a call
 * throws, because those are different answers and the caller printing them must be able
 * to tell. In a native image a shape that was not registered at build time is refused by
 * the linker; that failure is wrapped in an {@link FfiException} naming the shape, so the
 * caller can signal rather than crash.
 *
 * @see FfiType
 */
public final class FfiRuntime {

	private static final Linker LINKER = Linker.nativeLinker();

	private static final AddressLayout UNALIGNED_ADDRESS = ValueLayout.ADDRESS.withByteAlignment(1);

	private static final StructLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();

	private static final VarHandle ERRNO = CAPTURE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

	/** How {@link #callback upcalls} into a caller's code report an escaped error. */
	private static volatile @Nullable Consumer<Throwable> onError;

	private static final Object OPEN_LOCK = new Object();

	private static @Nullable FfiRuntime instance;

	private static @Nullable String unavailableReason;

	/** The library handle of the process's own symbols. */
	public static final long DEFAULT_LIBRARY = 0;

	/**
	 * One cached downcall handle per distinct shape, keyed by the descriptor spelling
	 * plus the variadic index. The handles are unbound (the address is an argument), so
	 * the key needs no library and no symbol.
	 */
	private static final Map<String, MethodHandle> DOWNCALLS = new ConcurrentHashMap<>();

	/**
	 * The constant lookup for the one upcall dispatcher (a name in a variable needs
	 * reflection metadata in a native image; a constant does not).
	 */
	private static final MethodHandle DISPATCH;

	static {
		try {
			DISPATCH = MethodHandles.lookup()
				.findStatic(FfiRuntime.class, "dispatch",
						MethodType.methodType(Object.class, CallbackShape.class, Object[].class));
		}
		catch (ReflectiveOperationException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	/** Index = the library handle a caller holds; 0 is the process's own symbols. */
	private final List<SymbolLookup> libraries = new CopyOnWriteArrayList<>();

	private final ThreadLocal<MemorySegment> captureState = ThreadLocal
		.withInitial(() -> Arena.ofAuto().allocate(CAPTURE_LAYOUT));

	private final MethodHandle malloc;

	private final MethodHandle free;

	private FfiRuntime() {
		// Binding a downcall is a restricted operation, so this doubles as the
		// native-access probe: a JVM run with --illegal-native-access=deny throws here
		// and open() records the reason.
		SymbolLookup process = LINKER.defaultLookup();
		this.malloc = LINKER.downcallHandle(find(process, "malloc"),
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
		this.free = LINKER.downcallHandle(find(process, "free"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		this.libraries.add(process);
	}

	private static MemorySegment find(SymbolLookup lookup, String name) {
		return lookup.find(name).orElseThrow(() -> new FfiException("the C library does not export " + name));
	}

	/**
	 * The process-wide runtime.
	 * @return the runtime
	 * @throws FfiException when native access is denied
	 */
	public static FfiRuntime get() {
		FfiRuntime opened = open();
		if (opened == null) {
			throw new FfiException(unavailableReason());
		}
		return opened;
	}

	/**
	 * Opens the binding, or answers {@code null} when this JVM denies native access.
	 * @return the binding, or {@code null}
	 */
	public static @Nullable FfiRuntime open() {
		synchronized (OPEN_LOCK) {
			if (instance != null || unavailableReason != null) {
				return instance;
			}
			try {
				instance = new FfiRuntime();
			}
			catch (Throwable ex) {
				unavailableReason = "the foreign function API is not usable here: " + ex;
			}
			return instance;
		}
	}

	/**
	 * Whether foreign calls will work on this JVM.
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
	 * What was bound, or why nothing was, in one line.
	 * @return a one-line description
	 */
	public static String description() {
		synchronized (OPEN_LOCK) {
			if (instance != null) {
				return "foreign functions bound (" + instance.libraries.size() + " librar"
						+ (instance.libraries.size() == 1 ? "y" : "ies") + " open)";
			}
			return unavailableReason == null ? "not opened yet" : unavailableReason;
		}
	}

	private static String unavailableReason() {
		return "foreign functions are not available: " + description();
	}

	/**
	 * Installs the handler for an error a callback's target let escape. The error cannot
	 * be thrown -- unwinding into the native frame above an upcall ends the process -- so
	 * it is handed here and the callback answers zero.
	 * @param handler the handler
	 */
	public static void onError(Consumer<Throwable> handler) {
		onError = handler;
	}

	// --- libraries and symbols --------------------------------------------------------

	/**
	 * Opens a library by name or path ({@code dlopen} semantics) and answers its handle.
	 * @param path the library name or path, e.g. {@code "libm.so.6"}
	 * @return the handle
	 * @throws FfiException when the library will not open
	 */
	public long openLibrary(String path) {
		SymbolLookup lookup;
		try {
			lookup = SymbolLookup.libraryLookup(path, Arena.global());
		}
		catch (Throwable ex) {
			throw new FfiException("the library " + path + " will not open: " + message(ex), ex);
		}
		this.libraries.add(lookup);
		return this.libraries.size() - 1;
	}

	/**
	 * The address of a symbol in an opened library, or 0 when it is not there.
	 * @param library the library handle ({@link #DEFAULT_LIBRARY} for the process)
	 * @param name the symbol name
	 * @return the address, or 0
	 */
	public long symbol(long library, String name) {
		return lookup(library).find(name).map(MemorySegment::address).orElse(0L);
	}

	private SymbolLookup lookup(long library) {
		if (library < 0 || library >= this.libraries.size()) {
			throw new FfiException("no open library has the handle " + library);
		}
		return this.libraries.get((int) library);
	}

	// --- calls ------------------------------------------------------------------------

	/**
	 * Calls the function at an address with a calling convention decided now.
	 * @param request the address, the shape and the arguments
	 * @return the return value in the protocol ({@code Long}, {@code Double},
	 * {@code String}, an address as {@code Long}, or {@code null} for {@code :void})
	 */
	public @Nullable Object call(CallRequest request) {
		if (request.function() == 0) {
			throw new FfiException("a null function pointer cannot be called");
		}
		List<FfiType> argTypes = request.argTypes();
		@Nullable Object[] values = request.values();
		if (values.length != argTypes.size()) {
			throw new FfiException("the call declares " + argTypes.size() + " argument"
					+ (argTypes.size() == 1 ? "" : "s") + " but got " + values.length);
		}
		MethodHandle handle = downcall(request.returnType(), argTypes, request.firstVariadic());
		try (Arena arena = Arena.ofConfined()) {
			List<Object> invocation = new ArrayList<>(values.length + 3);
			invocation.add(MemorySegment.ofAddress(request.function()));
			if (request.returnType() instanceof Struct) {
				invocation.add((SegmentAllocator) arena);
			}
			invocation.add(this.captureState.get());
			for (int i = 0; i < values.length; i++) {
				invocation.add(toNativeArgument(argTypes.get(i), values[i], i, arena));
			}
			Object raw;
			try {
				raw = handle.invokeWithArguments(invocation);
			}
			catch (FfiException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new FfiException("the call failed: " + message(ex), ex);
			}
			return fromNativeReturn(request.returnType(), raw);
		}
	}

	/**
	 * A {@link #call} in one value: the function address, the shape and the arguments.
	 *
	 * @param function the function address
	 * @param returnType the return type
	 * @param argTypes the declared argument types
	 * @param firstVariadic the index of the first variadic argument, or -1 for a fixed
	 * call
	 * @param values the arguments in the protocol
	 */
	public record CallRequest(long function, FfiType returnType, List<FfiType> argTypes, int firstVariadic,
			@Nullable Object[] values) {
	}

	private static MethodHandle downcall(FfiType ret, List<FfiType> args, int firstVariadic) {
		StringBuilder key = new StringBuilder(ret.spelling());
		for (FfiType arg : args) {
			key.append('|').append(arg.spelling());
		}
		key.append('#').append(firstVariadic);
		return DOWNCALLS.computeIfAbsent(key.toString(), ignored -> {
			MemoryLayout[] layouts = new MemoryLayout[args.size()];
			for (int i = 0; i < args.size(); i++) {
				FfiType arg = args.get(i);
				if (arg == Scalar.VOID) {
					throw new FfiException("an argument cannot be :void");
				}
				layouts[i] = arg.layout();
			}
			FunctionDescriptor descriptor = ret == Scalar.VOID ? FunctionDescriptor.ofVoid(layouts)
					: FunctionDescriptor.of(ret.layout(), layouts);
			List<Linker.Option> options = new ArrayList<>(2);
			options.add(Linker.Option.captureCallState("errno"));
			if (firstVariadic >= 0) {
				options.add(Linker.Option.firstVariadicArg(firstVariadic));
			}
			try {
				return LINKER.downcallHandle(descriptor, options.toArray(Linker.Option[]::new));
			}
			catch (Throwable ex) {
				// A native image refuses a shape it did not register at build time
				// (MissingForeignRegistrationError, an Error): name the shape to add.
				throw new FfiException("no foreign-call stub for the shape " + key
						+ "; in a native image every downcall shape must be registered at build time", ex);
			}
		});
	}

	/**
	 * The {@code errno} value the calling thread's last {@link #call} left.
	 * @return the errno value
	 */
	public int errno() {
		return (int) ERRNO.get(this.captureState.get(), 0L);
	}

	// --- memory -----------------------------------------------------------------------

	/**
	 * {@code malloc}: foreign memory that outlives every scope until {@link #free}.
	 * @param size the byte count
	 * @return the address
	 */
	public long alloc(long size) {
		if (size < 0) {
			throw new FfiException("cannot allocate " + size + " bytes");
		}
		try {
			long address = ((MemorySegment) this.malloc.invokeExact(size)).address();
			if (address == 0) {
				throw new FfiException("malloc(" + size + ") answered NULL");
			}
			return address;
		}
		catch (FfiException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new FfiException("malloc failed: " + message(ex), ex);
		}
	}

	/**
	 * {@code free}. Freeing address 0 is a no-op, as in C.
	 * @param address the address {@link #alloc} answered
	 */
	public void freeMemory(long address) {
		if (address == 0) {
			return;
		}
		try {
			this.free.invokeExact(MemorySegment.ofAddress(address));
		}
		catch (Throwable ex) {
			throw new FfiException("free failed: " + message(ex), ex);
		}
	}

	/**
	 * A typed load at address + offset. The load is unaligned-safe; a {@code :string}
	 * load reads the NUL-terminated UTF-8 at the location itself.
	 * @param address the base address
	 * @param offset the byte offset
	 * @param type the scalar type to load
	 * @return the value in the protocol, or {@code null} for a NULL {@code :string}
	 */
	public @Nullable Object peek(long address, long offset, FfiType type) {
		if (address == 0) {
			throw new FfiException("a null pointer cannot be read");
		}
		if (!(type instanceof Scalar scalar) || scalar == Scalar.VOID) {
			throw new FfiException("cannot read a " + type.spelling() + " (read a struct through its members)");
		}
		long base = address + offset;
		if (scalar == Scalar.STRING) {
			return MemorySegment.ofAddress(base).reinterpret(Long.MAX_VALUE).getString(0);
		}
		MemorySegment segment = MemorySegment.ofAddress(base).reinterpret(scalar.size());
		return switch (scalar) {
			case INT8 -> (long) segment.get(ValueLayout.JAVA_BYTE, 0);
			case UINT8 -> segment.get(ValueLayout.JAVA_BYTE, 0) & 0xFFL;
			case INT16 -> (long) segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, 0);
			case UINT16 -> segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, 0) & 0xFFFFL;
			case INT32 -> (long) segment.get(ValueLayout.JAVA_INT_UNALIGNED, 0);
			case UINT32 -> segment.get(ValueLayout.JAVA_INT_UNALIGNED, 0) & 0xFFFF_FFFFL;
			case INT64, UINT64 -> segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
			case FLOAT -> (double) segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 0);
			case DOUBLE -> segment.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, 0);
			case POINTER -> segment.get(UNALIGNED_ADDRESS, 0).address();
			default -> throw new FfiException("cannot read a " + scalar.spelling());
		};
	}

	/**
	 * A typed store at address + offset, the inverse of {@link #peek}. A {@code :string}
	 * cannot be stored (it would need an allocation of its own; store a {@code :pointer}
	 * to memory of the caller's).
	 * @param request the address, the type and the value
	 */
	public void poke(PokeRequest request) {
		if (request.address() == 0) {
			throw new FfiException("a null pointer cannot be written");
		}
		if (!(request.type() instanceof Scalar scalar) || scalar == Scalar.VOID || scalar == Scalar.STRING) {
			throw new FfiException("cannot write a " + request.type().spelling());
		}
		MemorySegment segment = MemorySegment.ofAddress(request.address() + request.offset())
			.reinterpret(scalar.size());
		Object value = request.value();
		switch (scalar) {
			case INT8, UINT8 -> segment.set(ValueLayout.JAVA_BYTE, 0, (byte) integer(scalar, value));
			case INT16, UINT16 -> segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, 0, (short) integer(scalar, value));
			case INT32, UINT32 -> segment.set(ValueLayout.JAVA_INT_UNALIGNED, 0, (int) integer(scalar, value));
			case INT64, UINT64 -> segment.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, integer(scalar, value));
			case FLOAT -> segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 0, (float) floating(scalar, value));
			case DOUBLE -> segment.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 0, floating(scalar, value));
			case POINTER -> segment.set(UNALIGNED_ADDRESS, 0, MemorySegment.ofAddress(integer(scalar, value)));
			default -> throw new FfiException("cannot write a " + scalar.spelling());
		}
	}

	/**
	 * A {@link #poke} in one value.
	 *
	 * @param address the base address
	 * @param offset the byte offset
	 * @param type the scalar type to store
	 * @param value the value in the protocol
	 */
	public record PokeRequest(long address, long offset, FfiType type, @Nullable Object value) {
	}

	// --- callbacks --------------------------------------------------------------------

	/**
	 * The target an upcall stub adapts: arguments in, answer out, all in the protocol.
	 */
	public interface Callback {

		/**
		 * @param args the native arguments in the protocol
		 * @return the answer in the protocol ({@code null} for {@code :void})
		 */
		@Nullable Object invoke(@Nullable Object[] args);

	}

	/**
	 * A code address C can call, adapting the target. The stub lives for the program. An
	 * error the target lets escape is handed to {@link #onError} and the callback answers
	 * zero -- unwinding into the native frame above an upcall ends the process.
	 * @param target the target
	 * @param returnType the return type ({@code :string} is refused; answer a pointer)
	 * @param argTypes the argument types
	 * @return the code address
	 */
	public long callback(Callback target, FfiType returnType, List<FfiType> argTypes) {
		if (returnType == Scalar.STRING || returnType instanceof Struct) {
			throw new FfiException("a callback cannot return " + returnType.spelling());
		}
		MemoryLayout[] layouts = new MemoryLayout[argTypes.size()];
		for (int i = 0; i < argTypes.size(); i++) {
			FfiType arg = argTypes.get(i);
			if (!(arg instanceof Scalar scalar) || scalar == Scalar.VOID || scalar == Scalar.STRING) {
				throw new FfiException("a callback argument cannot be " + arg.spelling() + " (take a :pointer)");
			}
			layouts[i] = scalar.layout();
		}
		FunctionDescriptor descriptor = returnType == Scalar.VOID ? FunctionDescriptor.ofVoid(layouts)
				: FunctionDescriptor.of(returnType.layout(), layouts);
		CallbackShape shape = new CallbackShape(target, returnType, List.copyOf(argTypes));
		MethodHandle handle = DISPATCH.bindTo(shape)
			.asCollector(Object[].class, argTypes.size())
			.asType(descriptor.toMethodType());
		try {
			return LINKER.upcallStub(handle, descriptor, Arena.global()).address();
		}
		catch (Throwable ex) {
			throw new FfiException("no upcall stub for the shape " + spellShape(returnType, argTypes)
					+ "; in a native image every callback shape must be registered at build time", ex);
		}
	}

	private static String spellShape(FfiType returnType, List<FfiType> argTypes) {
		StringBuilder spelling = new StringBuilder(returnType.spelling());
		for (FfiType arg : argTypes) {
			spelling.append(' ').append(arg.spelling());
		}
		return spelling.toString();
	}

	private record CallbackShape(Callback target, FfiType returnType, List<FfiType> argTypes) {
	}

	/** The one upcall body: marshal in, apply the target, marshal out; never throw. */
	private static @Nullable Object dispatch(CallbackShape shape, Object[] raw) {
		FfiType returnType = shape.returnType();
		try {
			@Nullable Object[] values = new @Nullable Object[raw.length];
			for (int i = 0; i < raw.length; i++) {
				values[i] = fromNativeReturn(shape.argTypes().get(i), raw[i]);
			}
			return toCallbackReturn(returnType, shape.target().invoke(values));
		}
		catch (Throwable ex) {
			Consumer<Throwable> handler = onError;
			if (handler != null) {
				handler.accept(ex);
			}
			return toCallbackReturn(returnType, null);
		}
	}

	/**
	 * The exactly-boxed value {@code asType} unboxes for the stub's return. A
	 * {@code null} answer -- the target answered nothing, or let an error escape -- is
	 * zero of the declared type, because this runs below a native frame and must not
	 * throw.
	 */
	private static @Nullable Object toCallbackReturn(FfiType type, @Nullable Object value) {
		if (type == Scalar.VOID) {
			return null;
		}
		Scalar scalar = (Scalar) type;
		return switch (scalar) {
			case INT8, UINT8 -> (byte) (value == null ? 0 : integer(scalar, value));
			case INT16, UINT16 -> (short) (value == null ? 0 : integer(scalar, value));
			case INT32, UINT32 -> (int) (value == null ? 0 : integer(scalar, value));
			case INT64, UINT64 -> value == null ? 0L : integer(scalar, value);
			case FLOAT -> (float) (value == null ? 0 : floating(scalar, value));
			case DOUBLE -> value == null ? 0.0d : floating(scalar, value);
			case POINTER -> MemorySegment.ofAddress(value == null ? 0 : integer(scalar, value));
			default -> throw new FfiException("a callback cannot return " + scalar.spelling());
		};
	}

	// --- marshalling ------------------------------------------------------------------

	/** A protocol value as the exactly-typed argument the downcall handle wants. */
	private static Object toNativeArgument(FfiType type, @Nullable Object value, int index, Arena arena) {
		if (type instanceof Struct struct) {
			if (value == null) {
				throw new FfiException("argument " + index + " is a " + struct.spelling() + " and cannot be NULL");
			}
			return MemorySegment.ofAddress(integer(struct, value)).reinterpret(struct.size());
		}
		Scalar scalar = (Scalar) type;
		return switch (scalar) {
			case INT8, UINT8 -> (byte) integer(scalar, value);
			case INT16, UINT16 -> (short) integer(scalar, value);
			case INT32, UINT32 -> (int) integer(scalar, value);
			case INT64, UINT64 -> integer(scalar, value);
			case FLOAT -> (float) floating(scalar, value);
			case DOUBLE -> floating(scalar, value);
			case POINTER -> value == null ? MemorySegment.NULL : MemorySegment.ofAddress(integer(scalar, value));
			case STRING -> switch (value) {
				case null -> MemorySegment.NULL;
				case String text -> arena.allocateFrom(text);
				case Long address -> MemorySegment.ofAddress(address);
				default -> throw new FfiException(
						"argument " + index + " does not fit :string: " + value.getClass().getSimpleName());
			};
			case VOID -> throw new FfiException("an argument cannot be :void");
		};
	}

	private static long integer(FfiType type, @Nullable Object value) {
		if (value instanceof Long l) {
			return l;
		}
		throw new FfiException("the operand does not fit " + type.spelling() + ": "
				+ (value == null ? "NULL" : value.getClass().getSimpleName()));
	}

	private static double floating(FfiType type, @Nullable Object value) {
		if (value instanceof Double d) {
			return d;
		}
		if (value instanceof Long l) {
			return l;
		}
		throw new FfiException("the operand does not fit " + type.spelling() + ": "
				+ (value == null ? "NULL" : value.getClass().getSimpleName()));
	}

	/** A raw handle answer as the protocol: widened, masked when unsigned. */
	private static @Nullable Object fromNativeReturn(FfiType type, @Nullable Object raw) {
		if (type == Scalar.VOID || raw == null) {
			return null;
		}
		if (type instanceof Struct struct) {
			// The struct was returned into the call's own arena; copy it to malloc'd
			// memory so the value outlives every Lisp scope, exactly as an explicit
			// foreign-alloc would (the caller frees it).
			MemorySegment result = (MemorySegment) raw;
			long size = struct.size();
			FfiRuntime runtime = get();
			long copy = runtime.alloc(size);
			MemorySegment.copy(result, 0, MemorySegment.ofAddress(copy).reinterpret(size), 0, size);
			return copy;
		}
		Scalar scalar = (Scalar) type;
		return switch (scalar) {
			case INT8 -> (long) (Byte) raw;
			case UINT8 -> (Byte) raw & 0xFFL;
			case INT16 -> (long) (Short) raw;
			case UINT16 -> (Short) raw & 0xFFFFL;
			case INT32 -> (long) (Integer) raw;
			case UINT32 -> (Integer) raw & 0xFFFF_FFFFL;
			case INT64, UINT64 -> (Long) raw;
			case FLOAT -> (double) (Float) raw;
			case DOUBLE -> (Double) raw;
			case POINTER -> ((MemorySegment) raw).address();
			case STRING -> {
				MemorySegment segment = (MemorySegment) raw;
				yield segment.address() == 0 ? null : segment.reinterpret(Long.MAX_VALUE).getString(0);
			}
			case VOID -> null;
		};
	}

	private static String message(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.toString() : message;
	}

}
