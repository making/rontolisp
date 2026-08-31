package am.ik.rontolisp.codegen.jvm;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import am.ik.ffi.FfiException;
import am.ik.ffi.FfiRuntime;
import am.ik.ffi.FfiType;
import org.jspecify.annotations.Nullable;

/**
 * The {@code ffi:} bridge injected into a compiled {@code .class} program: the verbs'
 * bodies against the compiled value representation ({@code null} = nil, the symbol
 * {@code "T"} = true, a {@code String} with surrounding quotes = string, a bare
 * {@code String} starting with {@code :} = keyword, {@code Long} / {@code Double} /
 * {@code BigInteger} numbers, an exact {@code Object[]} pair = cons cell, a
 * {@link JvmFfiHandle} = a raw C pointer). It is the compiled sibling of
 * {@code eval/FfiBridge} and, like {@link JvmObjcTemplate} beside
 * {@code eval/ObjcBridge}, a hand-kept twin of it -- the marshalling, the type designator
 * parse and every error message are the interpreter's; KEEP THE TWO IN SYNC.
 *
 * <p>
 * Unlike the marshalling, the binding is NOT copied: {@code am.ik.ffi} itself travels in
 * the same blob as this class ({@link JvmFfiRuntimeBuilder}), renamed into the emitted
 * program's own package, so the compiled program runs the very bytes the interpreter runs
 * -- one carrier canonicalisation, one downcall handle cache, one errno capture, one
 * native-image shape refusal with the actionable message.
 *
 * <h2>Callbacks apply a compiled function</h2>
 *
 * {@code ffi:callback}'s Lisp function is applied from an upcall through the generated
 * program's {@code _apply(Object, Object)} eval-runtime method handed over by
 * {@link #bind(Class)} from the emitted {@code _ffiInit} -- the {@link JvmObjcTemplate}
 * arrangement, minus the thread hop: C has no thread-0 rule, so the callback runs on
 * whatever thread the native caller holds. An error the function does not handle is
 * printed ({@code ffi: error in a callback: ...}) and the callback answers zero, never
 * thrown -- unwinding into the native frame above an upcall ends the process.
 *
 * <p>
 * Design constraints (as for {@link JvmObjcTemplate}): no nested classes or records, no
 * switch over an enum (javac lowers one through a synthetic {@code $1} class file the
 * single-blob template cannot carry; lambdas are fine), and no reference to any class
 * that is not either the JDK's or in the blob.
 */
final class JvmFfiTemplate {

	private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

	/**
	 * The generated program's {@code _apply(Object fn, Object argList)} eval-runtime
	 * method, used to apply a callback's Lisp function.
	 */
	private static @Nullable Method applyMethod;

	/**
	 * The generated program's {@code _strv(Object)} character-vector renderer, or null
	 * when the program carries no array runtime (then no mutable character vector can
	 * exist). Bound reflectively beside {@code _apply}: a string a program builds with
	 * {@code concatenate} / {@code format nil} / the case family is a MUTABLE character
	 * vector on this backend ({@code .kb/string-write-runtime.md}), and every string this
	 * bridge accepts funnels through {@link #lispString(Object)}, which renders it once
	 * here -- the same one-chokepoint rule the IO/socket/fetch runtimes follow, without
	 * adding a class to the travelling blob or duplicating the representation walk
	 * {@code _strv} owns.
	 */
	private static @Nullable Method strvMethod;

	private JvmFfiTemplate() {
	}

	/**
	 * Binds the callback into the generated program. Called once by the emitted
	 * {@code _ffiInit} right after this class is defined.
	 * @param mainClass the generated program class
	 */
	static void bind(Class<?> mainClass) {
		try {
			Method apply = mainClass.getDeclaredMethod("_apply", Object.class, Object.class);
			apply.setAccessible(true);
			applyMethod = apply;
		}
		catch (NoSuchMethodException ex) {
			throw new RuntimeException("ffi: no _apply method in " + mainClass.getName());
		}
		try {
			Method strv = mainClass.getDeclaredMethod("_strv", Object.class);
			strv.setAccessible(true);
			strvMethod = strv;
		}
		catch (NoSuchMethodException ex) {
			// No array runtime in this program: no character vector can exist.
			strvMethod = null;
		}
		FfiRuntime.onError(ex -> System.err.println("ffi: error in a callback: " + message(ex)));
	}

	/** Implements {@code (ffi:open [path])}. */
	static Object ffiOpen(@Nullable Object path) {
		try {
			FfiRuntime runtime = FfiRuntime.get();
			if (path == null) {
				return FfiRuntime.DEFAULT_LIBRARY;
			}
			String name = lispString(path);
			if (name == null) {
				throw new RuntimeException("ffi:open expects a library name or path string, got " + describe(path));
			}
			return runtime.openLibrary(name);
		}
		catch (FfiException ex) {
			throw fail("open", ex);
		}
	}

	/** Implements {@code (ffi:symbol library "name")}. */
	static @Nullable Object ffiSymbol(@Nullable Object library, @Nullable Object name) {
		String symbol = lispString(name);
		if (!(library instanceof Long handle) || symbol == null) {
			throw new RuntimeException("ffi:symbol expects (ffi:symbol library \"name\"), got " + describe(library));
		}
		try {
			long address = FfiRuntime.get().symbol(handle, symbol);
			return address == 0 ? null : new JvmFfiHandle(address);
		}
		catch (FfiException ex) {
			throw fail("symbol", ex);
		}
	}

	/** Implements {@code (ffi:call function return-type argument-types args...)}. */
	static @Nullable Object ffiCall(@Nullable Object function, @Nullable Object returnType, @Nullable Object argTypes,
			@Nullable Object[] args) {
		try {
			long address = address("call", function);
			FfiType ret = parseType("call", returnType);
			List<FfiType> types = new ArrayList<>();
			int firstVariadic = -1;
			for (Object designator : elements("call", argTypes, "the argument-type list")) {
				if (designator instanceof String marker && marker.equalsIgnoreCase(":VARARGS")) {
					if (firstVariadic >= 0) {
						throw new RuntimeException("ffi:call: :varargs may appear once");
					}
					firstVariadic = types.size();
					continue;
				}
				types.add(parseType("call", designator));
			}
			if (args.length != types.size()) {
				throw new RuntimeException("ffi:call declares " + types.size() + " argument"
						+ (types.size() == 1 ? "" : "s") + " but got " + args.length);
			}
			@Nullable Object[] values = new @Nullable Object[types.size()];
			for (int i = 0; i < values.length; i++) {
				values[i] = toProtocol("call", types.get(i), args[i]);
			}
			Object raw = FfiRuntime.get().call(new FfiRuntime.CallRequest(address, ret, types, firstVariadic, values));
			return fromProtocol(ret, raw);
		}
		catch (FfiException ex) {
			throw fail("call", ex);
		}
	}

	/** Implements {@code (ffi:%apply-call function return-type argument-types args)}. */
	static @Nullable Object ffiApplyCall(@Nullable Object function, @Nullable Object returnType,
			@Nullable Object argTypes, @Nullable Object argList) {
		List<@Nullable Object> args = elements("%apply-call", argList, "the argument list");
		return ffiCall(function, returnType, argTypes, args.toArray());
	}

	/** Implements {@code (ffi:callback function return-type argument-types)}. */
	static Object ffiCallback(@Nullable Object function, @Nullable Object returnType, @Nullable Object argTypes) {
		try {
			FfiType ret = parseType("callback", returnType);
			List<FfiType> types = new ArrayList<>();
			for (Object designator : elements("callback", argTypes, "the argument-type list")) {
				types.add(parseType("callback", designator));
			}
			FfiRuntime.Callback target = rawArgs -> {
				Object lispArgs = null;
				for (int i = rawArgs.length - 1; i >= 0; i--) {
					lispArgs = new Object[] { fromProtocol(types.get(i), rawArgs[i]), lispArgs };
				}
				Object answer = applyCallable(function, lispArgs);
				return ret == FfiType.Scalar.VOID ? null : toProtocol("callback", ret, answer);
			};
			return new JvmFfiHandle(FfiRuntime.get().callback(target, ret, types));
		}
		catch (FfiException ex) {
			throw fail("callback", ex);
		}
	}

	/** Implements {@code (ffi:alloc size)}. */
	static Object ffiAlloc(@Nullable Object size) {
		if (!(size instanceof Long bytes)) {
			throw new RuntimeException("ffi:alloc expects a byte count, got " + describe(size));
		}
		try {
			return new JvmFfiHandle(FfiRuntime.get().alloc(bytes));
		}
		catch (FfiException ex) {
			throw fail("alloc", ex);
		}
	}

	/** Implements {@code (ffi:free pointer)}. */
	static @Nullable Object ffiFree(@Nullable Object pointer) {
		try {
			FfiRuntime.get().freeMemory(address("free", pointer));
			return null;
		}
		catch (FfiException ex) {
			throw fail("free", ex);
		}
	}

	/**
	 * Implements {@code (ffi:peek pointer type [offset])}; {@code offset} may be the
	 * compiled {@code null}.
	 */
	static @Nullable Object ffiPeek(@Nullable Object pointer, @Nullable Object type, @Nullable Object offset) {
		try {
			long base = address("peek", pointer);
			FfiType t = parseType("peek", type);
			long off = offset == null ? 0 : integerArgument("peek", offset, "the offset");
			return fromProtocol(t, FfiRuntime.get().peek(base, off, t));
		}
		catch (FfiException ex) {
			throw fail("peek", ex);
		}
	}

	/** Implements {@code (ffi:poke pointer type value [offset])}, answering the value. */
	static @Nullable Object ffiPoke(@Nullable Object pointer, @Nullable Object type, @Nullable Object value,
			@Nullable Object offset) {
		try {
			long base = address("poke", pointer);
			FfiType t = parseType("poke", type);
			long off = offset == null ? 0 : integerArgument("poke", offset, "the offset");
			FfiRuntime.get().poke(new FfiRuntime.PokeRequest(base, off, t, toProtocol("poke", t, value)));
			return value;
		}
		catch (FfiException ex) {
			throw fail("poke", ex);
		}
	}

	/** Implements {@code (ffi:size type)}. */
	static Object ffiSize(@Nullable Object type) {
		try {
			return parseType("size", type).size();
		}
		catch (FfiException ex) {
			throw fail("size", ex);
		}
	}

	/** Implements {@code (ffi:align type)}. */
	static Object ffiAlign(@Nullable Object type) {
		try {
			return parseType("align", type).align();
		}
		catch (FfiException ex) {
			throw fail("align", ex);
		}
	}

	/** Implements {@code (ffi:pointerp value)}. */
	static @Nullable Object ffiPointerp(@Nullable Object value) {
		return value instanceof JvmFfiHandle ? "T" : null;
	}

	/** Implements {@code (ffi:address value)} -- its own inverse. */
	static Object ffiAddress(@Nullable Object value) {
		if (value instanceof JvmFfiHandle handle) {
			// An address is UNSIGNED 64-bit, so the answer follows :uint64's rule --
			// otherwise (ffi:address (ffi:address #xFFFFFFFFFFFFFFFF)) would not be the
			// identity the sentinel addresses (SQLITE_TRANSIENT) rely on.
			return unsigned64(handle.address());
		}
		if (value instanceof Long address) {
			return new JvmFfiHandle(address);
		}
		if (value instanceof BigInteger big) {
			return new JvmFfiHandle(big.longValue());
		}
		throw new RuntimeException("ffi:address expects a pointer or an integer address, got " + describe(value));
	}

	/** A raw 64-bit word as the unsigned integer it denotes (an address, a :uint64). */
	private static Object unsigned64(long raw) {
		return raw < 0 ? BigInteger.valueOf(raw).add(TWO_64) : (Object) raw;
	}

	/** Implements {@code (ffi:errno)}. */
	static Object ffiErrno() {
		try {
			return (long) FfiRuntime.get().errno();
		}
		catch (FfiException ex) {
			throw fail("errno", ex);
		}
	}

	/**
	 * The print hook: {@code #<pointer #x...>} for a foreign pointer, {@code null} for
	 * any other value (the printer's own branches follow). Emitted ahead of the
	 * {@code java:} branch, which would otherwise print the handle as a host object.
	 */
	static @Nullable String ffiPrint(@Nullable Object value) {
		return value instanceof JvmFfiHandle handle ? handle.toString() : null;
	}

	// Every verb signals a plain error whose message names the verb: the runtime's own
	// exception is the reason (denied native access, a library that will not open, a
	// symbol that is not there, an operand that does not fit, an unregistered
	// native-image shape), and the Lisp condition is what handler-case catches.
	private static RuntimeException fail(String member, FfiException ex) {
		return new RuntimeException("ffi:" + member + ": " + ex.getMessage());
	}

	private static String message(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.toString() : message;
	}

	private static @Nullable Object applyCallable(@Nullable Object callable, @Nullable Object argList) {
		Method apply = applyMethod;
		if (apply == null) {
			throw new RuntimeException("ffi: callback is not bound");
		}
		try {
			return apply.invoke(null, callable, argList);
		}
		catch (InvocationTargetException ex) {
			if (ex.getCause() instanceof RuntimeException re) {
				throw re;
			}
			if (ex.getCause() instanceof Error error) {
				throw error;
			}
			throw new RuntimeException("error applying callback: " + ex.getCause());
		}
		catch (ReflectiveOperationException ex) {
			throw new RuntimeException("error applying callback: " + ex);
		}
	}

	// --- type designators -------------------------------------------------------------

	/**
	 * A type designator as an {@link FfiType}: a CFFI keyword (a bare {@code String}
	 * starting with {@code :} in the compiled representation), or a
	 * {@code (:struct member...)} list.
	 */
	private static FfiType parseType(String member, @Nullable Object designator) {
		if (designator instanceof String keyword && !keyword.isEmpty() && keyword.charAt(0) == ':'
				&& !isLispString(keyword)) {
			return FfiType.of(keyword.substring(1));
		}
		if (designator != null && designator.getClass() == Object[].class) {
			List<@Nullable Object> items = properListElements((Object[]) designator);
			if (items != null && !items.isEmpty() && items.get(0) instanceof String head
					&& head.equalsIgnoreCase(":STRUCT")) {
				List<FfiType> members = new ArrayList<>(items.size() - 1);
				for (Object item : items.subList(1, items.size())) {
					members.add(parseType(member, item));
				}
				return new FfiType.Struct(members);
			}
		}
		throw new RuntimeException(
				"ffi:" + member + ": a foreign type is a keyword or (:struct member...), got " + describe(designator));
	}

	/** An address operand: a pointer, or a plain integer a binding computed. */
	private static long address(String member, @Nullable Object value) {
		if (value instanceof JvmFfiHandle handle) {
			return handle.address();
		}
		if (value instanceof Long address) {
			return address;
		}
		// An address above 2^63 arrives as a bignum; the wrap to the raw 64 bits is what
		// an unsigned address means.
		if (value instanceof BigInteger big) {
			return big.longValue();
		}
		throw new RuntimeException(
				"ffi:" + member + " expects a pointer or an integer address, got " + describe(value));
	}

	private static long integerArgument(String member, @Nullable Object value, String what) {
		if (value instanceof Long integer) {
			return integer;
		}
		throw new RuntimeException("ffi:" + member + ": " + what + " must be an integer, got " + describe(value));
	}

	// --- marshalling ------------------------------------------------------------------

	/** A Lisp value in the binding's protocol: what {@code FfiRuntime} takes. */
	private static @Nullable Object toProtocol(String member, FfiType type, @Nullable Object value) {
		if (type instanceof FfiType.Struct) {
			return address(member, value);
		}
		FfiType.Scalar scalar = (FfiType.Scalar) type;
		if (scalar == FfiType.Scalar.VOID) {
			throw new RuntimeException("ffi:" + member + ": an operand cannot be :void");
		}
		if (scalar == FfiType.Scalar.FLOAT || scalar == FfiType.Scalar.DOUBLE) {
			if (value instanceof Double d) {
				return d;
			}
			if (value instanceof Long l) {
				return (double) l;
			}
			return operandMismatch(member, scalar, value);
		}
		if (scalar == FfiType.Scalar.POINTER) {
			if (value == null) {
				return null;
			}
			if (value instanceof JvmFfiHandle handle) {
				return handle.address();
			}
			if (value instanceof Long l) {
				return l;
			}
			if (value instanceof BigInteger big) {
				return big.longValue();
			}
			return operandMismatch(member, scalar, value);
		}
		if (scalar == FfiType.Scalar.STRING) {
			if (value == null) {
				return null;
			}
			String text = lispString(value);
			if (text != null) {
				return text;
			}
			if (value instanceof JvmFfiHandle handle) {
				return handle.address();
			}
			return operandMismatch(member, scalar, value);
		}
		// The integer types.
		if (value instanceof Long l) {
			return l;
		}
		if (value instanceof BigInteger big) {
			// The wrap to the raw 64 bits is exactly what an unsigned operand wants.
			return big.longValue();
		}
		return operandMismatch(member, scalar, value);
	}

	private static Object operandMismatch(String member, FfiType type, @Nullable Object value) {
		throw new RuntimeException("ffi:" + member + ": " + describe(value) + " does not fit " + type.spelling());
	}

	/** The binding's answer as a Lisp value, by the declared type. */
	private static @Nullable Object fromProtocol(FfiType type, @Nullable Object value) {
		if (value == null) {
			return null;
		}
		if (type instanceof FfiType.Struct) {
			return new JvmFfiHandle((Long) value);
		}
		FfiType.Scalar scalar = (FfiType.Scalar) type;
		if (scalar == FfiType.Scalar.POINTER) {
			return new JvmFfiHandle((Long) value);
		}
		if (scalar == FfiType.Scalar.UINT64) {
			return unsigned64((Long) value);
		}
		if (scalar == FfiType.Scalar.FLOAT || scalar == FfiType.Scalar.DOUBLE) {
			return value;
		}
		if (scalar == FfiType.Scalar.STRING) {
			return quote((String) value);
		}
		if (scalar == FfiType.Scalar.VOID) {
			return null;
		}
		return value;
	}

	// --- the compiled value representation --------------------------------------------

	private static boolean isLispString(@Nullable Object v) {
		return v instanceof String s && !s.isEmpty() && s.charAt(0) == '"';
	}

	/**
	 * The unquoted value when {@code v} is a Lisp string -- a mutable character vector
	 * rendered first -- otherwise null.
	 */
	private static @Nullable String lispString(@Nullable Object v) {
		return rendered(v) instanceof String s && isLispString(s) ? s.substring(1, s.length() - 1) : null;
	}

	/**
	 * The value with a mutable character vector rendered to its quote-framed string;
	 * anything else (including an {@code ArrayList} that is not a character vector, which
	 * {@code _strv} passes through) is returned as it is.
	 */
	private static @Nullable Object rendered(@Nullable Object v) {
		Method strv = strvMethod;
		if (strv != null && v instanceof ArrayList) {
			try {
				return strv.invoke(null, v);
			}
			catch (ReflectiveOperationException ex) {
				return v;
			}
		}
		return v;
	}

	private static String quote(String s) {
		return "\"" + s + "\"";
	}

	private static List<@Nullable Object> elements(String member, @Nullable Object list, String what) {
		if (list == null) {
			return List.of();
		}
		List<@Nullable Object> items = list.getClass() == Object[].class ? properListElements((Object[]) list) : null;
		if (items == null) {
			throw new RuntimeException("ffi:" + member + ": " + what + " must be a list, got " + describe(list));
		}
		return items;
	}

	private static @Nullable List<@Nullable Object> properListElements(Object[] cons) {
		List<@Nullable Object> result = new ArrayList<>();
		Object current = cons;
		while (current != null) {
			if (!(current instanceof Object[] cell) || current.getClass() != Object[].class || cell.length != 2
					|| cell[0] instanceof Integer) {
				return null; // improper tail (incl. a function value)
			}
			result.add(cell[0]);
			current = cell[1];
		}
		return result;
	}

	// A minimal prin1-ish description for error messages.
	private static String describe(@Nullable Object v) {
		if (v == null) {
			return "NIL";
		}
		if (v instanceof int[] cp && cp.length == 1) {
			return "#\\" + Character.toString(cp[0]);
		}
		if (v instanceof String || v instanceof Long || v instanceof Double || v instanceof BigInteger
				|| v instanceof JvmFfiHandle) {
			return String.valueOf(v);
		}
		if (v.getClass() == Object[].class) {
			Object[] arr = (Object[]) v;
			if (arr.length > 0 && arr[0] instanceof Integer) {
				return "#<FUNCTION>";
			}
			List<@Nullable Object> items = properListElements(arr);
			if (items != null) {
				StringBuilder sb = new StringBuilder("(");
				for (int i = 0; i < items.size(); i++) {
					sb.append(i == 0 ? "" : " ").append(describe(items.get(i)));
				}
				return sb.append(")").toString();
			}
		}
		return "#<" + v.getClass().getName() + ">";
	}

}
