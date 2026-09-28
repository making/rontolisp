package am.ik.rontolisp.codegen.jvm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import am.ik.objc.ObjcBlocks;
import am.ik.objc.ObjcException;
import am.ik.objc.ObjcMethods;
import am.ik.objc.ObjcRaised;
import am.ik.objc.ObjcReference;
import am.ik.objc.ObjcRuntime;
import org.jspecify.annotations.Nullable;

/**
 * The new {@code objc} base's primitive layer shipped beside a compiled program: the
 * {@code objc::%} functions {@code objc.lisp} is written over, plus {@code objc:on-main},
 * against the compiled value representation ({@code null} = nil, {@code "T"} = true, a
 * quote-framed {@code String} = string, {@code Long} / {@code BigInteger} /
 * {@code Double} numbers, an {@code Object[2]} = cons). The interpreter's twin is
 * {@code eval/ObjcPrimitives}; neither decides a rule -- every conversion and every
 * ownership decision is {@code objc.lisp}'s, compiled into the program -- so what they
 * share is only the protocol, and the moving parts live in {@code am.ik.objc}
 * ({@code ObjcRuntime.sendRawOnMain}, {@code ObjcReference}), which travels with this
 * class ({@link JvmObjcRuntimeBuilder}).
 *
 * <p>
 * {@code objc:on-main} applies a compiled function on thread 0 through the program's
 * {@code _apply(Object, Object)}, handed over by {@link #bind(Class)} from the emitted
 * {@code _objcInit}, like the {@code java:} bridge.
 *
 * <p>
 * Design constraints (as for {@link JavaBridgeTemplate}): no nested classes or records
 * (lambdas are fine), no enum switch, and no reference to any class that is not either
 * the JDK's or shipped with it.
 */
final class JvmObjcPrimitivesTemplate {

	/** The program's {@code _apply(Object fn, Object argList)}. */
	private static @Nullable Method applyMethod;

	/**
	 * The program's {@code _strv(Object)} character-vector renderer, or null when the
	 * program carries no array runtime (then no mutable character vector can exist).
	 */
	private static @Nullable Method strvMethod;

	private JvmObjcPrimitivesTemplate() {
	}

	/**
	 * Binds the program. Called once by the emitted {@code _objcInit}.
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
			strvMethod = null;
		}
	}

	/** {@code (objc::%get-class name)}. */
	static Object getClass(@Nullable Object name) {
		try {
			return ObjcRuntime.get().classOrNullAddress(string(name));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%class-name class)}. */
	static Object className(@Nullable Object cls) {
		try {
			return quote(ObjcRuntime.get().nameOfClass(address(cls)));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%object-class object)}. */
	static Object objectClass(@Nullable Object object) {
		try {
			return ObjcRuntime.get().classOfAddress(address(object));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%class-p object)}. */
	static @Nullable Object classP(@Nullable Object object) {
		try {
			return ObjcRuntime.get().isClass(address(object)) ? "T" : null;
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%register-selector name)}. */
	static Object registerSelector(@Nullable Object name) {
		try {
			return ObjcRuntime.get().selector(string(name)).address();
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%selector-name sel)}. */
	static Object selectorName(@Nullable Object sel) {
		try {
			return quote(ObjcRuntime.get().selectorName(MemorySegment.ofAddress(address(sel))));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%method-types class sel)}. */
	static @Nullable Object methodTypes(@Nullable Object cls, @Nullable Object sel) {
		try {
			String types = ObjcRuntime.get().methodTypes(address(cls), address(sel));
			return types == null ? null : quote(types);
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%send receiver sel types fixed args mode)}. */
	static @Nullable Object send(@Nullable Object receiver, @Nullable Object sel, @Nullable Object types,
			@Nullable Object fixed, @Nullable Object args, @Nullable Object mode) {
		List<@Nullable Object> raw = elements(args);
		@Nullable Object[] operands = new @Nullable Object[raw.size()];
		for (int i = 0; i < operands.length; i++) {
			operands[i] = toRaw(raw.get(i));
		}
		RAISED.get()[0] = 0;
		try {
			Object answer = ObjcRuntime.get()
				.sendRawOnMain(address(receiver), address(sel), string(types), (int) address(fixed), operands,
						(int) address(mode));
			return fromRaw(answer);
		}
		catch (ObjcRaised ex) {
			return raised(ex);
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/**
	 * {@code (objc::%raised)}: the retained address of what the last call of this thread
	 * raised, or nil; reading clears it.
	 */
	static @Nullable Object raised() {
		long[] raised = RAISED.get();
		if (raised[0] == 0) {
			return null;
		}
		raised[0] = 0;
		return raised[1];
	}

	/** The last call's raise on this thread: flag and retained address. */
	private static final ThreadLocal<long[]> RAISED = ThreadLocal.withInitial(() -> new long[2]);

	// A call that raised answers nil; objc.lisp asks %raised on a nil answer.
	private static @Nullable Object raised(ObjcRaised ex) {
		long[] raised = RAISED.get();
		raised[0] = 1;
		raised[1] = ex.exception();
		return null;
	}

	/** {@code (objc::%new-handle address gc)}. */
	static Object newHandle(@Nullable Object object, @Nullable Object gc) {
		try {
			return ObjcReference.own(ObjcRuntime.get(), address(object), address(gc));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%refs handle which delta)}. */
	static Object refs(@Nullable Object handle, @Nullable Object which, @Nullable Object delta) {
		if (!(handle instanceof ObjcReference reference)) {
			throw new RuntimeException("objc: not a reference handle: " + handle);
		}
		return reference.adjust((int) address(which), address(delta));
	}

	/** {@code (objc::%interned address)}. */
	static @Nullable Object interned(@Nullable Object object) {
		return ObjcReference.interned(address(object));
	}

	/** {@code (objc::%intern address pointer)}. */
	static Object intern(@Nullable Object object, @Nullable Object pointer) {
		if (pointer == null) {
			throw new RuntimeException("objc: cannot intern nil");
		}
		return ObjcReference.intern(address(object), pointer);
	}

	/** {@code (objc::%load-module path)}. */
	static Object loadModule(@Nullable Object path) {
		String module = string(path);
		try {
			SymbolLookup.libraryLookup(module, Arena.global());
		}
		catch (RuntimeException ex) {
			throw new RuntimeException("objc: the module " + module + " cannot be loaded: " + ex.getMessage());
		}
		return "T";
	}

	/** {@code (objc::%initialize)}. */
	static Object initialize() {
		try {
			ObjcRuntime.get();
			return "T";
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	// --- the class-definition half -------------------------------------------------

	/** {@code (objc::%allocate-class superclass name)}. */
	static Object allocateClass(@Nullable Object superclass, @Nullable Object name) {
		try {
			return ObjcRuntime.get().allocateClass(address(superclass), string(name));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%add-ivar class name size alignment types)}. */
	static @Nullable Object addIvar(@Nullable Object cls, @Nullable Object name, @Nullable Object size,
			@Nullable Object alignment, @Nullable Object types) {
		try {
			return ObjcRuntime.get()
				.addIvar(address(cls), string(name), address(size), address(alignment), string(types)) ? "T" : null;
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%register-class class)}. */
	static Object registerClass(@Nullable Object cls) {
		try {
			ObjcRuntime.get().registerClass(address(cls));
			return "T";
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%add-method class sel types function flags)}. */
	static Object addMethod(@Nullable Object cls, @Nullable Object sel, @Nullable Object types,
			@Nullable Object function, @Nullable Object flags) {
		try {
			ObjcMethods.add(ObjcRuntime.get(), address(cls), address(sel), string(types), (self, raw) -> {
				Object args = null;
				for (int i = raw.length - 1; i >= 0; i--) {
					args = new Object[] { fromRaw(raw[i]), args };
				}
				Object list = new Object[] { self, new Object[] { args, null } };
				return toRaw(applyCallable(function, list));
			}, (int) address(flags));
			return "T";
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%add-protocol class name)}. */
	static @Nullable Object addProtocol(@Nullable Object cls, @Nullable Object name) {
		try {
			return ObjcRuntime.get().addProtocol(address(cls), string(name)) ? "T" : null;
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%superclass class)}. */
	static Object superclass(@Nullable Object cls) {
		try {
			return ObjcRuntime.get().superclassAddress(address(cls));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%send-super receiver class sel types fixed args mode)}. */
	static @Nullable Object sendSuper(@Nullable Object receiver, @Nullable Object cls, @Nullable Object sel,
			@Nullable Object types, @Nullable Object fixed, @Nullable Object args, @Nullable Object mode) {
		List<@Nullable Object> raw = elements(args);
		@Nullable Object[] operands = new @Nullable Object[raw.size()];
		for (int i = 0; i < operands.length; i++) {
			operands[i] = toRaw(raw.get(i));
		}
		RAISED.get()[0] = 0;
		try {
			return fromRaw(ObjcRuntime.get()
				.sendRawOnMain(address(receiver), address(cls), address(sel), string(types), (int) address(fixed),
						operands, (int) address(mode)));
		}
		catch (ObjcRaised ex) {
			return raised(ex);
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%ivar-offset class name)}. */
	static Object ivarOffset(@Nullable Object cls, @Nullable Object name) {
		try {
			return ObjcRuntime.get().ivarOffset(address(cls), string(name));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%ivar-types class name)}. */
	static @Nullable Object ivarTypes(@Nullable Object cls, @Nullable Object name) {
		try {
			String types = ObjcRuntime.get().ivarTypes(address(cls), string(name));
			return types == null ? null : quote(types);
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%peek address types)}. */
	static @Nullable Object peek(@Nullable Object at, @Nullable Object types) {
		try {
			return fromRaw(ObjcRuntime.get().peek(address(at), string(types)));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%poke address types raw)}. */
	static @Nullable Object poke(@Nullable Object at, @Nullable Object types, @Nullable Object raw) {
		try {
			ObjcRuntime.get().poke(address(at), string(types), toRaw(raw));
			return null;
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	// --- the blocks and C functions half ---------------------------------------------

	/** {@code (objc::%make-block types signature function)}. */
	static Object makeBlock(@Nullable Object types, @Nullable Object signature, @Nullable Object function) {
		try {
			return ObjcBlocks.make(ObjcRuntime.get(), string(types), string(signature), raw -> {
				Object args = null;
				for (int i = raw.length - 1; i >= 0; i--) {
					args = new Object[] { fromRaw(raw[i]), args };
				}
				return toRaw(applyCallable(function, new Object[] { args, null }));
			});
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%free-block address)}. */
	static @Nullable Object freeBlock(@Nullable Object address) {
		ObjcBlocks.free(address(address));
		return null;
	}

	/** {@code (objc::%call-function address types fixed args mode)}. */
	static @Nullable Object callFunction(@Nullable Object function, @Nullable Object types, @Nullable Object fixed,
			@Nullable Object args, @Nullable Object mode) {
		List<@Nullable Object> raw = elements(args);
		@Nullable Object[] operands = new @Nullable Object[raw.size()];
		for (int i = 0; i < operands.length; i++) {
			operands[i] = toRaw(raw.get(i));
		}
		RAISED.get()[0] = 0;
		try {
			return fromRaw(ObjcRuntime.get()
				.callRaw(address(function), string(types), (int) address(fixed), operands, (int) address(mode)));
		}
		catch (ObjcRaised ex) {
			return raised(ex);
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%symbol-address name)}. */
	static Object symbolAddress(@Nullable Object name) {
		try {
			return ObjcRuntime.get().symbolAddress(string(name));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	// --- the byte copies objc:data and objc:bytes are written over --------------------

	/**
	 * {@code (objc::%octets value)}: the bytes of a packed buffer, little-endian and
	 * row-major -- a packed float array is a {@code float[]} / {@code double[]} of any
	 * rank, a packed {@code (unsigned-byte 8)} vector a {@code byte[]{8, e0, ...}}, a
	 * packed {@code (unsigned-byte 16|32)} vector a {@code long[]{width, e0, ...}} -- or
	 * a string's UTF-8, as a compiled octet vector; nil for any other value. The
	 * interpreter's {@code eval/PackedBuffer} decides the same against its own
	 * representation.
	 */
	static @Nullable Object octets(@Nullable Object value) {
		byte[] bytes = bufferBytes(value);
		return bytes == null ? null : octetVector(bytes);
	}

	/** {@code (objc::%write-octets address octets)}. */
	static @Nullable Object writeOctets(@Nullable Object at, @Nullable Object octets) {
		if (!(octets instanceof byte[] vector) || vector.length < 1 || vector[0] != 8) {
			throw new RuntimeException("objc: expected an (unsigned-byte 8) vector, got " + octets);
		}
		try {
			ObjcRuntime.writeBytes(address(at), Arrays.copyOfRange(vector, 1, vector.length));
			return null;
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/** {@code (objc::%read-octets address length)}. */
	static Object readOctets(@Nullable Object at, @Nullable Object length) {
		try {
			return octetVector(ObjcRuntime.readBytes(address(at), address(length)));
		}
		catch (ObjcException ex) {
			throw fail(ex);
		}
	}

	/**
	 * The compiled packed {@code (unsigned-byte 8)} vector: {@code byte[]{8, e0, ...}},
	 * the width in slot 0 ({@code JvmIntArrayRuntimeBuilder.OCTET_TAG}; this class
	 * travels alone, so the number is spelled here).
	 */
	private static byte[] octetVector(byte[] bytes) {
		byte[] vector = new byte[bytes.length + 1];
		vector[0] = 8;
		System.arraycopy(bytes, 0, vector, 1, bytes.length);
		return vector;
	}

	private static byte @Nullable [] bufferBytes(@Nullable Object buffer) {
		Object v = rendered(buffer);
		if (v instanceof String s && !s.isEmpty() && s.charAt(0) == '"') {
			return s.substring(1, s.length() - 1).getBytes(StandardCharsets.UTF_8);
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
		if (buffer instanceof byte[] octets && octets.length >= 1 && octets[0] == 8) {
			return Arrays.copyOfRange(octets, 1, octets.length);
		}
		if (buffer instanceof long[] vector && vector.length >= 1) {
			int width = (int) vector[0] / 8;
			ByteBuffer bytes = ByteBuffer.allocate((vector.length - 1) * width).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 1; i < vector.length; i++) {
				if (width == 1) {
					bytes.put((byte) vector[i]);
				}
				else if (width == 2) {
					bytes.putShort((short) vector[i]);
				}
				else {
					bytes.putInt((int) vector[i]);
				}
			}
			return bytes.array();
		}
		return null;
	}

	/** {@code (objc:on-main function)}. */
	static @Nullable Object onMain(@Nullable Object function) {
		try {
			return ObjcRuntime.get().mainThread().sync(() -> applyCallable(function));
		}
		catch (ObjcException ex) {
			throw new RuntimeException("objc:on-main: " + ex.getMessage());
		}
	}

	private static @Nullable Object applyCallable(@Nullable Object callable) {
		return applyCallable(callable, null);
	}

	private static @Nullable Object applyCallable(@Nullable Object callable, @Nullable Object argList) {
		Method apply = applyMethod;
		if (apply == null) {
			throw new RuntimeException("objc: the program is not bound");
		}
		try {
			return apply.invoke(null, callable, argList);
		}
		catch (InvocationTargetException ex) {
			// A Lisp error (or a non-local exit) propagates unchanged: MainThread.sync
			// carries it back to the caller's thread as it was.
			if (ex.getCause() instanceof RuntimeException re) {
				throw re;
			}
			if (ex.getCause() instanceof Error error) {
				throw error;
			}
			throw new RuntimeException("objc: error applying a function: " + ex.getCause());
		}
		catch (ReflectiveOperationException ex) {
			throw new RuntimeException("objc: error applying a function: " + ex);
		}
	}

	// --- the compiled value representation ------------------------------------------

	private static RuntimeException fail(ObjcException ex) {
		return new RuntimeException("objc: " + ex.getMessage());
	}

	private static long address(@Nullable Object value) {
		if (value == null) {
			return 0L;
		}
		if (value instanceof Long n) {
			return n;
		}
		if (value instanceof BigInteger b) {
			return b.longValue();
		}
		throw new RuntimeException("objc: expected an integer, got " + value);
	}

	private static String string(@Nullable Object value) {
		Object v = rendered(value);
		if (v instanceof String s && !s.isEmpty() && s.charAt(0) == '"') {
			return s.substring(1, s.length() - 1);
		}
		throw new RuntimeException("objc: expected a string, got " + value);
	}

	private static String quote(String s) {
		return "\"" + s + "\"";
	}

	/**
	 * The value with a mutable character vector rendered to its quote-framed string;
	 * anything else is returned as it is.
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

	private static List<@Nullable Object> elements(@Nullable Object list) {
		List<@Nullable Object> result = new ArrayList<>();
		Object current = list;
		while (current != null) {
			if (!(current instanceof Object[] cell) || current.getClass() != Object[].class || cell.length != 2
					|| cell[0] instanceof Integer) {
				throw new RuntimeException("objc: expected a list, got " + list);
			}
			result.add(cell[0]);
			current = cell[1];
		}
		return result;
	}

	/** A raw argument: a number, a string, nil, or a list of a struct's leaves. */
	private static @Nullable Object toRaw(@Nullable Object value) {
		if (value == null || value instanceof Long || value instanceof Double || value instanceof BigInteger) {
			return value;
		}
		Object v = rendered(value);
		if (v instanceof String s && !s.isEmpty() && s.charAt(0) == '"') {
			return s.substring(1, s.length() - 1);
		}
		if (v != null && v.getClass() == Object[].class) {
			List<@Nullable Object> items = elements(v);
			Number[] leaves = new Number[items.size()];
			for (int i = 0; i < leaves.length; i++) {
				if (!(toRaw(items.get(i)) instanceof Number n)) {
					throw new RuntimeException("objc: a struct leaf is a number, got " + items.get(i));
				}
				leaves[i] = n;
			}
			return leaves;
		}
		throw new RuntimeException("objc: cannot pass " + value + " to Objective-C");
	}

	private static @Nullable Object fromRaw(@Nullable Object value) {
		if (value instanceof String s) {
			return quote(s);
		}
		if (value instanceof Number[] leaves) {
			Object list = null;
			for (int i = leaves.length - 1; i >= 0; i--) {
				Object leaf = leaves[i] instanceof Double d ? d : (Object) leaves[i].longValue();
				list = new Object[] { leaf, list };
			}
			return list;
		}
		return value;
	}

}
