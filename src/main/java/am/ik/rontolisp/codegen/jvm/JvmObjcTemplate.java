package am.ik.rontolisp.codegen.jvm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import am.ik.objc.MainThread;
import am.ik.objc.ObjcClasses;
import am.ik.objc.ObjcException;
import am.ik.objc.ObjcRuntime;
import am.ik.objc.TypeEncoding;
import org.jspecify.annotations.Nullable;

/**
 * The {@code objc:} bridge injected into a compiled {@code .class} program: the nine
 * verbs' bodies against the compiled value representation ({@code null} = nil, the symbol
 * {@code "T"} = true, a {@code String} with surrounding quotes = string, {@code Long} /
 * {@code Double} numbers, an exact {@code Object[]} pair = cons cell, a
 * {@link JvmObjcHandle} = an Objective-C object or class). It is the compiled sibling of
 * {@code eval/ObjcBridge} and, like {@link JavaBridgeTemplate} beside
 * {@code eval/JavaInterop}, a hand-kept twin of it -- the marshalling, the ownership rule
 * and every error message are the interpreter's; KEEP THE TWO IN SYNC.
 *
 * <p>
 * Unlike the marshalling, the binding is NOT copied: {@code am.ik.objc} itself travels in
 * the same blob as this class ({@link JvmObjcRuntimeBuilder}), renamed into the emitted
 * program's own package, so the compiled program runs the very bytes the interpreter runs
 * -- one type-encoding parser, one hop to thread 0, one closed set of callback shapes.
 * What this class holds is only what a call site adds: the value representation and the
 * callback into the program.
 *
 * <h2>Callbacks apply a compiled function</h2>
 *
 * A method of {@code objc:define-class} and the body of {@code objc:on-main} are Lisp
 * functions applied from an upcall on thread 0, through the generated program's
 * {@code _apply(Object, Object)} eval-runtime method handed over by {@link #bind(Class)}
 * from the emitted {@code _objcInit} -- the {@code java:proxy} precedent. A callback runs
 * with the program's GLOBAL dynamic bindings, and an error it does not handle is printed
 * ({@code objc: error in a callback: ...}) rather than thrown: unwinding into the native
 * frame above an upcall ends the process.
 *
 * <h2>Ownership: one retain per wrapper, released on thread 0</h2>
 *
 * A {@link JvmObjcHandle} owns exactly one reference -- taken from the {@code alloc} /
 * {@code new} / {@code copy} / {@code mutableCopy} / {@code retain} family, retained on
 * the way in for everything else, inside the main-thread hop that produced it -- and a
 * {@link Cleaner} releases it through {@link ObjcRuntime#releaseOnMain} when the handle
 * is collected. A class owns nothing.
 *
 * <p>
 * Design constraints (as for {@link JavaBridgeTemplate}): no nested classes or records
 * (lambdas are fine), and no reference to any class that is not either the JDK's or in
 * the blob.
 */
final class JvmObjcTemplate {

	private static final Cleaner CLEANER = Cleaner.create();

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

	private JvmObjcTemplate() {
	}

	/**
	 * Binds the callback into the generated program. Called once by the emitted
	 * {@code _objcInit} right after this class is defined.
	 * @param mainClass the generated program class
	 */
	static void bind(Class<?> mainClass) {
		try {
			Method apply = mainClass.getDeclaredMethod("_apply", Object.class, Object.class);
			apply.setAccessible(true);
			applyMethod = apply;
		}
		catch (NoSuchMethodException ex) {
			throw new RuntimeException("objc: no _apply method in " + mainClass.getName());
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
		ObjcClasses.onError(ex -> System.err.println("objc: error in a callback: " + message(ex)));
	}

	/** Implements {@code (objc:class "Name")}. */
	static Object objcClass(@Nullable Object name) {
		try {
			String className = string("class", name);
			ObjcRuntime runtime = ObjcRuntime.get();
			return wrapClass(runtime, runtime.objcClass(className));
		}
		catch (ObjcException ex) {
			throw fail("class", ex);
		}
	}

	/** Implements {@code (objc:send receiver "selector" args...)}. */
	static @Nullable Object objcSend(@Nullable Object target, @Nullable Object selector, @Nullable Object[] args) {
		String sel = lispString(selector);
		if (sel == null) {
			throw new RuntimeException("objc:send expects (objc:send receiver \"selector\" args...)");
		}
		if (target == null) {
			// Objective-C answers nil to a message sent to nil -- on every machine, so
			// the runtime is not opened for it.
			return null;
		}
		try {
			ObjcRuntime runtime = ObjcRuntime.get();
			@Nullable Object[] operands = new @Nullable Object[args.length];
			List<ObjcRuntime.Out> outs = new ArrayList<>(1);
			for (int i = 0; i < args.length; i++) {
				if (isErrorSlot(args[i])) {
					ObjcRuntime.Out out = new ObjcRuntime.Out();
					outs.add(out);
					operands[i] = out;
				}
				else {
					operands[i] = toJava(args[i]);
				}
			}
			return onMain(runtime, () -> {
				MemorySegment receiver = receiver(runtime, target);
				ObjcRuntime.Sent sent = runtime.send(receiver, sel, operands);
				for (ObjcRuntime.Out out : outs) {
					runtime.checkError(out, sent, sel);
				}
				return fromJava(runtime, sent, sel);
			});
		}
		catch (ObjcException ex) {
			throw fail("send", ex);
		}
	}

	/**
	 * Implements {@code (objc:define-class "Name" "Superclass" methods [protocols])};
	 * {@code protocols} is the compiled {@code null} when the form had three arguments.
	 */
	static Object objcDefineClass(@Nullable Object name, @Nullable Object superclass, @Nullable Object methods,
			@Nullable Object protocols) {
		String className = lispString(name);
		String superName = lispString(superclass);
		if (className == null || superName == null) {
			throw new RuntimeException(
					"objc:define-class expects (objc:define-class \"Name\" \"Superclass\" methods [protocols])");
		}
		List<ObjcClasses.Spec> specs = new ArrayList<>();
		for (Object spec : elements("define-class", methods, "the method list")) {
			List<@Nullable Object> pair = elements("define-class", spec, "a method");
			String sel = pair.size() == 2 ? lispString(pair.get(0)) : null;
			if (sel == null) {
				throw new RuntimeException(
						"objc:define-class: a method is (\"selector:\" function), got " + describe(spec));
			}
			Object function = pair.get(1);
			specs.add(new ObjcClasses.Spec(sel, (self, callArgs) -> callback(function, self, callArgs)));
		}
		List<String> protocolNames = new ArrayList<>();
		for (Object protocol : elements("define-class", protocols, "the protocol list")) {
			String p = lispString(protocol);
			if (p == null) {
				throw new RuntimeException("objc:define-class: a protocol is a string, got " + describe(protocol));
			}
			protocolNames.add(p);
		}
		try {
			ObjcRuntime runtime = ObjcRuntime.get();
			return onMainValue(runtime,
					() -> wrapClass(runtime, ObjcClasses.define(runtime, className, superName, protocolNames, specs)));
		}
		catch (ObjcException ex) {
			throw fail("define-class", ex);
		}
	}

	/** Implements {@code (objc:on-main function)}. */
	static @Nullable Object objcOnMain(@Nullable Object function) {
		try {
			ObjcRuntime runtime = ObjcRuntime.get();
			return onMain(runtime, () -> applyCallable(function, null));
		}
		catch (ObjcException ex) {
			throw fail("on-main", ex);
		}
	}

	/** Implements {@code (objc:string "text")}. */
	static Object objcString(@Nullable Object text) {
		try {
			String value = string("string", text);
			ObjcRuntime runtime = ObjcRuntime.get();
			return onMainValue(runtime, () -> {
				try (Arena arena = Arena.ofConfined()) {
					return wrapObject(runtime, runtime.nsString(value, arena), true);
				}
			});
		}
		catch (ObjcException ex) {
			throw fail("string", ex);
		}
	}

	/** Implements {@code (objc:data buffer)}. */
	static Object objcData(@Nullable Object buffer) {
		byte[] bytes = bufferBytes(buffer);
		try {
			ObjcRuntime runtime = ObjcRuntime.get();
			return onMainValue(runtime, () -> {
				try (Arena arena = Arena.ofConfined()) {
					return wrapObject(runtime, runtime.nsData(bytes, arena), true);
				}
			});
		}
		catch (ObjcException ex) {
			throw fail("data", ex);
		}
	}

	/** Implements {@code (objc:bytes data)}. */
	static Object objcBytes(@Nullable Object data) {
		if (!(data instanceof JvmObjcHandle handle)) {
			throw new RuntimeException("objc:bytes expects an Objective-C object, got " + describe(data));
		}
		try {
			ObjcRuntime runtime = ObjcRuntime.get();
			byte[] bytes = onMainValue(runtime, () -> runtime.dataBytes(MemorySegment.ofAddress(handle.address())));
			// The compiled packed (unsigned-byte 8) vector: long[]{width, e0, ...}.
			long[] vector = new long[bytes.length + 1];
			vector[0] = 8;
			for (int i = 0; i < bytes.length; i++) {
				vector[i + 1] = bytes[i] & 0xFFL;
			}
			return vector;
		}
		catch (ObjcException ex) {
			throw fail("bytes", ex);
		}
	}

	/**
	 * The bytes {@code objc:data} sends, little-endian and row-major: a packed float
	 * array is a {@code float[]} / {@code double[]} of any rank, a packed
	 * {@code (unsigned-byte 8|16|32)} vector a {@code long[]{width, e0, ...}}, and a
	 * string its UTF-8 bytes. The interpreter's {@code eval/PackedBuffer} decides the
	 * same thing against the interpreted representation.
	 */
	private static byte[] bufferBytes(@Nullable Object buffer) {
		String text = lispString(buffer);
		if (text != null) {
			return text.getBytes(StandardCharsets.UTF_8);
		}
		// A packed float array carries its dimension header IN the array --
		// [rank, dim_0..dim_{rank-1}, e_0...] -- so the elements start at 1 + rank
		// (JvmFloatArrayRuntimeBuilder). Only the elements go on the wire, which is what
		// the interpreter's LispSingleFloatArray.data() is.
		if (buffer instanceof float[] singles) {
			int from = 1 + (int) singles[0];
			ByteBuffer bytes = ByteBuffer.allocate((singles.length - from) * 4).order(ByteOrder.LITTLE_ENDIAN);
			bytes.asFloatBuffer().put(singles, from, singles.length - from);
			return bytes.array();
		}
		if (buffer instanceof double[] doubles) {
			int from = 1 + (int) doubles[0];
			ByteBuffer bytes = ByteBuffer.allocate((doubles.length - from) * 8).order(ByteOrder.LITTLE_ENDIAN);
			bytes.asDoubleBuffer().put(doubles, from, doubles.length - from);
			return bytes.array();
		}
		if (buffer instanceof long[] vector && vector.length >= 1) {
			int width = (int) vector[0] / 8;
			ByteBuffer bytes = ByteBuffer.allocate((vector.length - 1) * width).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 1; i < vector.length; i++) {
				switch (width) {
					case 1 -> bytes.put((byte) vector[i]);
					case 2 -> bytes.putShort((short) vector[i]);
					default -> bytes.putInt((int) vector[i]);
				}
			}
			return bytes.array();
		}
		throw new RuntimeException("objc:data expects a packed float array, a packed (unsigned-byte 8|16|32)"
				+ " vector or a string, got " + describe(buffer));
	}

	/** Implements {@code (objc:address object)}. */
	static Object objcAddress(@Nullable Object object) {
		if (!(object instanceof JvmObjcHandle handle)) {
			throw new RuntimeException("objc:address expects an Objective-C object, got " + describe(object));
		}
		return handle.address();
	}

	/** Implements {@code (objc:objectp value)}. */
	static @Nullable Object objcObjectp(@Nullable Object value) {
		return value instanceof JvmObjcHandle ? "T" : null;
	}

	/**
	 * The print hook: {@code #<objc Class>} for a wrapped object, {@code null} for any
	 * other value (the printer's own branches follow). Emitted ahead of the {@code java:}
	 * branch, which would otherwise print the wrapper as a host object.
	 */
	static @Nullable String objcPrint(@Nullable Object value) {
		return value instanceof JvmObjcHandle handle ? handle.toString() : null;
	}

	// Every verb signals a plain error whose message names the verb: the runtime's own
	// exception is the reason (absent runtime, unknown class, bad operand, unregistered
	// shape), and the Lisp condition is what handler-case catches.
	private static RuntimeException fail(String member, ObjcException ex) {
		return new RuntimeException("objc:" + member + ": " + ex.getMessage());
	}

	private static String message(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.toString() : message;
	}

	private static String string(String member, @Nullable Object value) {
		String s = lispString(value);
		if (s == null) {
			throw new RuntimeException("objc:" + member + " expects a string, got " + describe(value));
		}
		return s;
	}

	private static List<@Nullable Object> elements(String member, @Nullable Object list, String what) {
		if (list == null) {
			return List.of();
		}
		List<@Nullable Object> items = list.getClass() == Object[].class ? properListElements((Object[]) list) : null;
		if (items == null) {
			throw new RuntimeException("objc:" + member + ": " + what + " must be a list, got " + describe(list));
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

	private static <T> @Nullable T onMain(ObjcRuntime runtime, Callable<T> body) {
		return runtime.mainThread().sync(body);
	}

	/** {@link #onMain} for a body that answers a wrapper, never the Lisp {@code nil}. */
	private static <T> T onMainValue(ObjcRuntime runtime, Callable<T> body) {
		T value = onMain(runtime, body);
		if (value == null) {
			throw new IllegalStateException("a main-thread body answered null");
		}
		return value;
	}

	private static MemorySegment receiver(ObjcRuntime runtime, Object target) {
		if (target instanceof JvmObjcHandle handle) {
			return MemorySegment.ofAddress(handle.address());
		}
		String className = lispString(target);
		if (className != null) {
			return runtime.objcClass(className);
		}
		throw new RuntimeException(
				"objc:send: the receiver must be an object or a class name, got " + describe(target));
	}

	/**
	 * A callback's body: wrap the receiver and the arguments, apply, marshal the answer.
	 */
	private static @Nullable Object callback(@Nullable Object function, MemorySegment self, @Nullable Object[] args) {
		ObjcRuntime runtime = ObjcRuntime.get();
		// Build the (self arg...) cons list, tail-first.
		Object argList = null;
		for (int i = args.length - 1; i >= 0; i--) {
			Object arg = args[i] instanceof MemorySegment seg ? wrapObject(runtime, seg, true) : null;
			argList = new Object[] { arg, argList };
		}
		argList = new Object[] { wrapObject(runtime, self, true), argList };
		return toJava(applyCallable(function, argList));
	}

	private static @Nullable Object applyCallable(@Nullable Object callable, @Nullable Object argList) {
		Method apply = applyMethod;
		if (apply == null) {
			throw new RuntimeException("objc: callback is not bound");
		}
		try {
			return apply.invoke(null, callable, argList);
		}
		catch (InvocationTargetException ex) {
			// A Lisp error (or a non-local exit) thrown by the callable propagates
			// unchanged: MainThread.sync carries it back to the caller's thread as it
			// was.
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

	// --- marshalling ----------------------------------------------------------------

	/** A Lisp value in the binding's protocol: what {@link ObjcRuntime#send} takes. */
	private static @Nullable Object toJava(@Nullable Object value) {
		if (value == null) {
			return null;
		}
		if ("T".equals(value)) {
			return Boolean.TRUE;
		}
		if (value instanceof JvmObjcHandle handle) {
			return MemorySegment.ofAddress(handle.address());
		}
		String s = lispString(value);
		if (s != null) {
			return s;
		}
		if (value instanceof Long || value instanceof Double) {
			return value;
		}
		if (value.getClass() == Object[].class) {
			List<@Nullable Object> items = properListElements((Object[]) value);
			if (items != null) {
				Number[] leaves = new Number[items.size()];
				for (int i = 0; i < leaves.length; i++) {
					Object item = items.get(i);
					if (!(item instanceof Long) && !(item instanceof Double)) {
						throw new RuntimeException("objc:send: a struct is a list of numbers, got " + describe(value));
					}
					leaves[i] = (Number) item;
				}
				return leaves;
			}
		}
		throw new RuntimeException("objc:send: cannot pass " + describe(value) + " to Objective-C");
	}

	/** The binding's answer as a Lisp value, by the kind the selector declared. */
	private static @Nullable Object fromJava(ObjcRuntime runtime, ObjcRuntime.Sent sent, String selector) {
		Object value = sent.value();
		TypeEncoding.Type type = sent.type();
		if (value == null) {
			return null;
		}
		if (selector.startsWith("performSelector")) {
			// The answer is the TARGET method's, whose type this binding cannot see: a
			// void method leaves garbage in the result register, and retaining garbage
			// is a SIGSEGV. Objective-C code ignores it too.
			return null;
		}
		// An if-chain rather than a switch over the enum: javac lowers that switch
		// through
		// a synthetic JvmObjcTemplate$1 holding the ordinal map -- a second class file
		// the single-blob template cannot carry.
		TypeEncoding.Kind kind = type.kind();
		if (kind == TypeEncoding.Kind.OBJECT) {
			return wrapObject(runtime, (MemorySegment) value, !handsOwnership(selector));
		}
		if (kind == TypeEncoding.Kind.CLASS) {
			return wrapClass(runtime, (MemorySegment) value);
		}
		if (kind == TypeEncoding.Kind.POINTER) {
			return ((MemorySegment) value).address();
		}
		if (kind == TypeEncoding.Kind.SELECTOR || kind == TypeEncoding.Kind.CSTRING) {
			return quote((String) value);
		}
		if (kind == TypeEncoding.Kind.BOOL) {
			return (Boolean) value ? "T" : null;
		}
		if (kind == TypeEncoding.Kind.STRUCT) {
			Object list = null;
			Number[] leaves = (Number[]) value;
			for (int i = leaves.length - 1; i >= 0; i--) {
				Object leaf = leaves[i] instanceof Double d ? d : (Object) leaves[i].longValue();
				list = new Object[] { leaf, list };
			}
			return list;
		}
		if (kind == TypeEncoding.Kind.VOID) {
			return null;
		}
		// The integer kinds are a Long and the floating kinds a Double already.
		return value;
	}

	/**
	 * The Cocoa ownership convention: a method whose name begins with {@code alloc},
	 * {@code new}, {@code copy} or {@code mutableCopy}, and {@code retain}, answers an
	 * object the caller already owns.
	 */
	private static boolean handsOwnership(String selector) {
		return selector.startsWith("alloc") || selector.startsWith("new") || selector.startsWith("copy")
				|| selector.startsWith("mutableCopy") || selector.equals("retain");
	}

	private static JvmObjcHandle wrapObject(ObjcRuntime runtime, MemorySegment object, boolean retain) {
		if (retain) {
			runtime.retain(object);
		}
		JvmObjcHandle handle = new JvmObjcHandle(object.address(), runtime.className(object));
		long address = object.address();
		// The cleaning action must not reference the handle it is registered for.
		CLEANER.register(handle, () -> {
			try {
				runtime.releaseOnMain(address);
			}
			catch (RuntimeException ignored) {
				// the process is on its way out, or the pump is gone: leaking is the
				// safe direction
			}
		});
		return handle;
	}

	/** A class is immortal: no reference is owned and nothing is released. */
	private static JvmObjcHandle wrapClass(ObjcRuntime runtime, MemorySegment cls) {
		return new JvmObjcHandle(cls.address(), runtime.className(cls));
	}

	// --- the compiled value representation ------------------------------------------

	/**
	 * Whether an argument is the {@code :error} marker -- a keyword is a bare
	 * {@code String} in the compiled representation, a Lisp string a quoted one.
	 */
	private static boolean isErrorSlot(@Nullable Object value) {
		return value instanceof String symbol && symbol.equalsIgnoreCase(":ERROR");
	}

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

	// A minimal prin1-ish description for error messages.
	private static String describe(@Nullable Object v) {
		if (v == null) {
			return "NIL";
		}
		if (v instanceof int[] cp && cp.length == 1) {
			return "#\\" + Character.toString(cp[0]);
		}
		if (v instanceof String || v instanceof Long || v instanceof Double || v instanceof BigInteger
				|| v instanceof JvmObjcHandle) {
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
