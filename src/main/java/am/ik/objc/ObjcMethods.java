package am.ik.objc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import am.ik.objc.TypeEncoding.Kind;
import am.ik.objc.TypeEncoding.Type;
import org.jspecify.annotations.Nullable;

/**
 * Methods defined at run time whose bodies are host-language functions, of ANY shape the
 * encoding describes -- the new base's class definition ({@code objc-class.lisp},
 * .kb/objc.md, "The new base: class definition").
 *
 * <p>
 * {@link #imp} makes one FFM upcall stub PER METHOD: the stub is the generic
 * {@link #dispatch} with the method's own {@link Target} bound in, collected and adapted
 * to the method's {@link FunctionDescriptor}. A stub per method (not per shape) is what a
 * super send needs: the superclass's IMP runs for a receiver whose class also defines the
 * selector, so an IMP that looked its body up by (receiver class, selector) would find
 * the subclass's and recurse.
 *
 * <p>
 * The raw protocol is the new base's, in both directions: an argument arrives as a
 * {@link Long} for every integral, boolean or address kind (an object RETAINED for the
 * value the body makes of it), a {@link Double} for a floating kind, a {@code Number[]}
 * of a struct's leaves, a {@link String} for a C string; the body answers the same way,
 * and an object answer is retained -- and autoreleased, unless the method is of an owning
 * family -- per the {@code flags} the definition passed.
 *
 * <p>
 * Under {@code java} any shape is served; a native image serves the shapes registered
 * under {@code foreign.upcalls} and refuses any other at definition, naming the entry to
 * add ({@link ObjcRuntime#upcall}). A body that throws never unwinds into the native
 * frame above it: the failure goes to the {@linkplain #onError error sink} and the method
 * answers its zero value.
 */
public final class ObjcMethods {

	/** Flag: retain an object answer (an owning method's +1). */
	public static final int RETAIN_RESULT = 1;

	/** Flag: autorelease the retained object answer (every other method). */
	public static final int AUTORELEASE_RESULT = 2;

	/**
	 * A method body: the receiver's address and the method's own raw arguments, answering
	 * the raw result ({@code null} for void).
	 */
	@FunctionalInterface
	public interface Body {

		/**
		 * @param self the receiver's address
		 * @param args the raw arguments after {@code self} and {@code _cmd}
		 * @return the raw result
		 */
		@Nullable Object invoke(long self, @Nullable Object[] args);

	}

	/** What one method's stub is bound to. */
	private record Target(ObjcRuntime runtime, TypeEncoding encoding, Body body, int flags) {
	}

	private static final Set<FunctionDescriptor> UPCALL_SIGNATURES = new LinkedHashSet<>();

	private static volatile Consumer<Throwable> errorSink = ObjcMethods::report;

	private static volatile @Nullable MethodHandle dispatchHandle;

	private ObjcMethods() {
	}

	private static void report(Throwable ex) {
		String message = ex.getMessage();
		System.err.println("objc: error in a callback: " + (message == null || message.isEmpty() ? ex : message));
	}

	/**
	 * Where a body's uncaught failure goes. Default: one line on standard error.
	 * @param sink the reporter
	 */
	public static void onError(Consumer<Throwable> sink) {
		errorSink = sink;
	}

	/**
	 * The upcall shapes bound so far, for the native-image registration test.
	 * @return the shapes
	 */
	public static Set<FunctionDescriptor> upcallSignatures() {
		synchronized (UPCALL_SIGNATURES) {
			return Set.copyOf(UPCALL_SIGNATURES);
		}
	}

	/**
	 * The upcall shape a method of this encoding needs.
	 * @param types the method's encoding
	 * @return the shape
	 */
	public static FunctionDescriptor shape(String types) {
		return TypeEncoding.parse(types).descriptor();
	}

	/**
	 * Installs (or replaces) a method.
	 * @param runtime the binding
	 * @param cls the class's address (the metaclass's for a class method)
	 * @param selector the {@code SEL}'s address
	 * @param types the method's encoding, which is also what the runtime records
	 * @param body the body
	 * @param flags {@link #RETAIN_RESULT} and {@link #AUTORELEASE_RESULT}, or 0
	 * @throws ObjcException when the encoding names a type no method can take, or a
	 * native image has no stub for the shape
	 */
	public static void add(ObjcRuntime runtime, long cls, long selector, String types, Body body, int flags) {
		runtime.putMethod(cls, selector, imp(runtime, types, body, flags), types);
	}

	/**
	 * A method implementation calling {@code body}.
	 * @param runtime the binding
	 * @param types the method's encoding
	 * @param body the body
	 * @param flags the result flags
	 * @return the IMP, alive for the life of the process
	 */
	public static MemorySegment imp(ObjcRuntime runtime, String types, Body body, int flags) {
		TypeEncoding encoding = runtime.parsed(types);
		FunctionDescriptor descriptor = encoding.descriptor();
		MethodHandle target = MethodHandles.insertArguments(dispatch(), 0, new Target(runtime, encoding, body, flags))
			.asCollector(Object[].class, descriptor.argumentLayouts().size())
			.asType(descriptor.toMethodType());
		synchronized (UPCALL_SIGNATURES) {
			UPCALL_SIGNATURES.add(descriptor);
		}
		return ObjcRuntime.upcall(target, descriptor, "the method " + types);
	}

	// One CONSTANT lookup, so a native image folds it into a direct handle.
	private static MethodHandle dispatch() {
		MethodHandle handle = dispatchHandle;
		if (handle == null) {
			try {
				handle = MethodHandles.lookup()
					.findStatic(ObjcMethods.class, "dispatch",
							MethodType.methodType(Object.class, Object.class, Object[].class));
			}
			catch (ReflectiveOperationException ex) {
				throw new ObjcException("the method dispatcher cannot be bound", ex);
			}
			dispatchHandle = handle;
		}
		return handle;
	}

	/**
	 * Every method's IMP lands here. Public because an upcall needs a lookup-visible
	 * method; never call it.
	 * @param bound the method's {@link Target}
	 * @param args the native arguments, {@code self} and {@code _cmd} first
	 * @return the native result, boxed as its carrier
	 */
	public static @Nullable Object dispatch(Object bound, Object[] args) {
		Target target = (Target) bound;
		Type ret = target.encoding().returnType();
		try {
			List<Type> params = target.encoding().argumentTypes();
			@Nullable Object[] raw = new @Nullable Object[args.length - 2];
			for (int i = 2; i < args.length; i++) {
				raw[i - 2] = toRaw(target.runtime(), params.get(i), args[i]);
			}
			Object answer = target.body().invoke(((MemorySegment) args[0]).address(), raw);
			return toNative(target.runtime(), target.flags(), ret, answer);
		}
		catch (Throwable ex) {
			fail(ex);
			return zero(ret);
		}
	}

	/**
	 * Hands a callback's failure to the error sink; nothing may escape into the native
	 * frame above an upcall, not even the sink's own failure.
	 * @param ex the failure
	 */
	static void fail(Throwable ex) {
		try {
			errorSink.accept(ex);
		}
		catch (Throwable ignored) {
			// the sink itself failed; nothing may escape into the native frame
		}
	}

	/**
	 * A native argument as the raw protocol hands it to a body (an object RETAINED for
	 * the value the body makes of it).
	 * @param runtime the binding
	 * @param type the argument's type
	 * @param arg the native argument, boxed as its carrier
	 * @return the raw value
	 */
	static @Nullable Object toRaw(ObjcRuntime runtime, Type type, Object arg) {
		Kind kind = type.kind();
		if (kind == Kind.STRUCT) {
			return ObjcRuntime.leaves(type, (MemorySegment) arg);
		}
		if (kind == Kind.OBJECT) {
			MemorySegment object = (MemorySegment) arg;
			if (object.address() != 0) {
				runtime.retain(object);
			}
			return object.address();
		}
		if (kind == Kind.CSTRING) {
			MemorySegment chars = (MemorySegment) arg;
			return chars.address() == 0 ? null : chars.reinterpret(Long.MAX_VALUE).getString(0);
		}
		if (kind.isAddress()) {
			return ((MemorySegment) arg).address();
		}
		if (kind == Kind.BOOL) {
			return (Boolean) arg ? 1L : 0L;
		}
		if (kind == Kind.FLOAT) {
			return (double) (Float) arg;
		}
		if (kind == Kind.DOUBLE) {
			return arg;
		}
		long value = ((Number) arg).longValue();
		if (type.unsigned()) {
			if (kind == Kind.INT8) {
				value &= 0xFFL;
			}
			else if (kind == Kind.INT16) {
				value &= 0xFFFFL;
			}
			else if (kind == Kind.INT32) {
				value &= 0xFFFFFFFFL;
			}
		}
		return value;
	}

	/**
	 * A body's raw answer as the native result, boxed as its carrier.
	 * @param runtime the binding
	 * @param flags {@link #RETAIN_RESULT} and {@link #AUTORELEASE_RESULT} for an object
	 * @param ret the result type
	 * @param answer the raw answer
	 * @return the native result
	 */
	static @Nullable Object toNative(ObjcRuntime runtime, int flags, Type ret, @Nullable Object answer) {
		Kind kind = ret.kind();
		if (kind == Kind.VOID) {
			return null;
		}
		if (kind == Kind.STRUCT) {
			if (!(answer instanceof Number[] leaves) || leaves.length != ret.leaves().size()) {
				throw new ObjcException("a method answering a struct of " + ret.leaves().size() + " numbers answered "
						+ describe(answer));
			}
			MemorySegment out = Arena.ofAuto().allocate(ret.argumentLayout());
			ObjcRuntime.fill(ret, leaves, out);
			return out;
		}
		if (kind == Kind.FLOAT || kind == Kind.DOUBLE) {
			double d = answer instanceof Number n ? n.doubleValue() : 0.0;
			return kind == Kind.FLOAT ? (Object) (float) d : (Object) d;
		}
		long value = answer instanceof Number n ? n.longValue() : 0L;
		if (kind == Kind.OBJECT) {
			MemorySegment object = MemorySegment.ofAddress(value);
			if (value != 0 && (flags & RETAIN_RESULT) != 0) {
				runtime.retain(object);
				if ((flags & AUTORELEASE_RESULT) != 0) {
					runtime.autorelease(object);
				}
			}
			return object;
		}
		if (kind.isAddress()) {
			return MemorySegment.ofAddress(value);
		}
		return carrier(kind, value);
	}

	private static Object carrier(Kind kind, long value) {
		if (kind == Kind.BOOL) {
			return value != 0;
		}
		if (kind == Kind.INT8) {
			return (byte) value;
		}
		if (kind == Kind.INT16) {
			return (short) value;
		}
		if (kind == Kind.INT32) {
			return (int) value;
		}
		return value;
	}

	/**
	 * The zero value of a result type, as its carrier: what a failed callback answers.
	 * @param ret the result type
	 * @return the zero
	 */
	static @Nullable Object zero(Type ret) {
		Kind kind = ret.kind();
		if (kind == Kind.VOID) {
			return null;
		}
		if (kind == Kind.STRUCT) {
			return Arena.ofAuto().allocate(ret.argumentLayout());
		}
		if (kind == Kind.FLOAT) {
			return 0.0f;
		}
		if (kind == Kind.DOUBLE) {
			return 0.0;
		}
		if (kind.isAddress()) {
			return MemorySegment.NULL;
		}
		return carrier(kind, 0L);
	}

	private static String describe(@Nullable Object value) {
		return value == null ? "nil" : value.getClass().getSimpleName();
	}

}
