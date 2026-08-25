package am.ik.rontolisp.codegen.jvm;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The {@code java:} interop runtime injected into a compiled {@code .class} program. This
 * is a rewrite of the interpreter's {@code eval/JavaInterop} against the compiled runtime
 * value representation ({@code null} = nil, {@code Long} = integer, {@code Double} =
 * float, a {@code String} with surrounding quotes = string, any other {@code String} =
 * symbol ({@code "T"} = true), {@code Character} = character, an exact {@code Object[]} =
 * cons cell or (with an {@code Integer} head) a function value, an {@code ArrayList} with
 * a leading {@code Object[]} of dimension sizes = array); the overload selection costs
 * and tie-breaking are identical, so a program behaves the same interpreted and compiled.
 *
 * <p>
 * The class is never referenced by the rontolisp code base at runtime. Its compiled
 * bytecode is read from the classpath by {@link JvmJavaRuntimeBuilder}, renamed into the
 * default package (the compiled program's package, a {@code Lookup.defineClass}
 * requirement), base64-embedded into the generated class, and defined at first use by the
 * emitted {@code _javaInit} helper -- so the output stays a single self-contained
 * {@code .class} file. Because it is compiled with the project's Java release, running a
 * compiled program that uses {@code java:} requires a JRE at least as new as the one
 * rontolisp was built with (other programs keep running on any Java 6+ JVM).
 *
 * <p>
 * Design constraints: no nested classes or records (each would become a second class file
 * the injection cannot carry -- lambdas are fine, they stay in this class file) and no
 * references to other rontolisp classes (the bytes must stand alone). Lisp callables (for
 * {@code java:proxy} and auto-proxied arguments) are applied through the generated
 * program's {@code _apply(Object, Object)} eval-runtime method, handed over as a
 * {@link Method} by {@link #bind(Class)} from the emitted {@code _javaInit}.
 */
final class JavaBridgeTemplate {

	// Conversion cost of a single argument against a parameter type: lower is a better
	// (more specific, less lossy) match. Values mirror eval/JavaInterop exactly.
	private static final int COST_EXACT = 0;

	private static final int COST_WIDEN = 1;

	private static final int COST_CONVERT = 2;

	private static final int COST_NARROW = 4;

	private static final int COST_BOXED = 6;

	private static final int COST_PROXY = 8;

	private static final int COST_VARARGS = 10;

	private static final int NO_MATCH = -1;

	/**
	 * The generated program's {@code _apply(Object fn, Object argList)} eval-runtime
	 * method, used to apply Lisp callables from proxy invocation handlers.
	 */
	private static @Nullable Method applyMethod;

	private JavaBridgeTemplate() {
	}

	/**
	 * Binds the callback into the generated program. Called once by the emitted
	 * {@code _javaInit} right after this class is defined.
	 * @param mainClass the generated program class
	 */
	static void bind(Class<?> mainClass) {
		try {
			Method apply = mainClass.getDeclaredMethod("_apply", Object.class, Object.class);
			apply.setAccessible(true);
			applyMethod = apply;
		}
		catch (NoSuchMethodException ex) {
			throw new RuntimeException("java interop: no _apply method in " + mainClass.getName());
		}
	}

	/** Implements {@code (java:new "class" args...)}. */
	static @Nullable Object javaNew(@Nullable Object className, @Nullable Object[] args) {
		String name = lispString(className);
		if (name == null) {
			throw new RuntimeException("java:new expects a class-name string, got " + describe(className));
		}
		Class<?> cls = loadClass(name);
		Object[] sel = select(List.of(cls.getConstructors()), args);
		if (sel == null) {
			throw new RuntimeException("No matching constructor for " + name + " with " + args.length + " argument(s)");
		}
		try {
			return unmarshal(((Constructor<?>) sel[0]).newInstance((@Nullable Object[]) sel[1]));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("constructing " + name, ex);
		}
	}

	/** Implements {@code (java:call obj "method" args...)}. */
	static @Nullable Object javaCall(@Nullable Object target, @Nullable Object methodName, @Nullable Object[] args) {
		String method = lispString(methodName);
		if (method == null) {
			throw new RuntimeException("java:call expects (java:call object \"method\" args...)");
		}
		if (target == null || !isJavaObject(target)) {
			throw new RuntimeException(
					"java:call expects a java object as the first argument, got " + describe(target));
		}
		return invoke(target.getClass(), target, method, args);
	}

	/** Implements {@code (java:static "class" "method" args...)}. */
	static @Nullable Object javaStatic(@Nullable Object className, @Nullable Object methodName,
			@Nullable Object[] args) {
		String cls = lispString(className);
		String method = lispString(methodName);
		if (cls == null || method == null) {
			throw new RuntimeException("java:static expects (java:static \"class\" \"method\" args...)");
		}
		return invoke(loadClass(cls), null, method, args);
	}

	/**
	 * Implements {@code (java:field "class.Name" "CONSTANT")} (static) and
	 * {@code (java:field obj "name")} (instance).
	 */
	static @Nullable Object javaField(@Nullable Object classOrObject, @Nullable Object fieldName) {
		String name = lispString(fieldName);
		if (name == null) {
			throw new RuntimeException("java:field expects (java:field class-or-object \"field\")");
		}
		try {
			String staticClass = lispString(classOrObject);
			if (staticClass != null) {
				Field field = loadClass(staticClass).getField(name);
				return unmarshal(field.get(null));
			}
			if (classOrObject != null && isJavaObject(classOrObject)) {
				Field field = classOrObject.getClass().getField(name);
				return unmarshal(field.get(classOrObject));
			}
			throw new RuntimeException(
					"java:field expects a class-name string or a java object, got " + describe(classOrObject));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("reading field " + name, ex);
		}
	}

	/** Implements {@code (java:proxy "fully.qualified.Interface" callable)}. */
	static @Nullable Object javaProxy(@Nullable Object interfaceName, @Nullable Object callable) {
		String name = lispString(interfaceName);
		if (name == null) {
			throw new RuntimeException("java:proxy expects (java:proxy \"interface\" callable)");
		}
		Class<?> iface = loadClass(name);
		if (!iface.isInterface()) {
			throw new RuntimeException("java:proxy expects an interface, got " + name);
		}
		return proxy(iface, callable);
	}

	// The callable is applied as (callable method-name arg1 arg2 ...) for every
	// interface method; Object methods (equals/hashCode/toString) keep identity
	// behavior, like the interpreter's proxy.
	private static Object proxy(Class<?> iface, @Nullable Object callable) {
		return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface }, (p, method, methodArgs) -> {
			switch (method.getName()) {
				case "hashCode" -> {
					return System.identityHashCode(p);
				}
				case "equals" -> {
					return p == (methodArgs == null ? null : methodArgs[0]);
				}
				case "toString" -> {
					return "#<java-proxy " + iface.getName() + ">";
				}
				default -> {
				}
			}
			// Build the (method-name arg...) cons list, tail-first.
			Object argList = null;
			if (methodArgs != null) {
				for (int i = methodArgs.length - 1; i >= 0; i--) {
					argList = new Object[] { unmarshal(methodArgs[i]), argList };
				}
			}
			argList = new Object[] { quote(method.getName()), argList };
			Object result = applyCallable(callable, argList);
			Class<?> ret = method.getReturnType();
			if (ret == void.class) {
				return null;
			}
			@Nullable Object[] slot = new @Nullable Object[1];
			if (marshal(result, ret, slot, 0) == NO_MATCH) {
				throw new RuntimeException(
						"java:proxy: cannot return " + describe(result) + " as " + ret + " from " + iface.getName());
			}
			return slot[0];
		});
	}

	private static @Nullable Object applyCallable(@Nullable Object callable, @Nullable Object argList) {
		Method apply = applyMethod;
		if (apply == null) {
			throw new RuntimeException("java interop: callback is not bound");
		}
		try {
			return apply.invoke(null, callable, argList);
		}
		catch (InvocationTargetException ex) {
			// A Lisp error thrown by the callable propagates unchanged.
			if (ex.getCause() instanceof RuntimeException re) {
				throw re;
			}
			throw new RuntimeException("error applying callback: " + ex.getCause());
		}
		catch (ReflectiveOperationException ex) {
			throw new RuntimeException("error applying callback: " + ex);
		}
	}

	private static @Nullable Object invoke(Class<?> cls, @Nullable Object receiver, String methodName,
			@Nullable Object[] args) {
		List<Method> candidates = new ArrayList<>();
		for (Method method : cls.getMethods()) {
			if (method.getName().equals(methodName)) {
				Method accessible = accessibleMethod(method);
				if (accessible != null) {
					candidates.add(accessible);
				}
			}
		}
		Object[] sel = select(candidates, args);
		if (sel == null) {
			throw new RuntimeException(
					"No matching method " + cls.getName() + "." + methodName + " with " + args.length + " argument(s)");
		}
		try {
			return unmarshal(((Method) sel[0]).invoke(receiver, (@Nullable Object[]) sel[1]));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("calling " + cls.getName() + "." + methodName, ex);
		}
	}

	// A public method declared in a non-exported/non-public class (e.g. the List.of
	// result type) cannot be invoked reflectively; re-resolve it to the same method on
	// an accessible superclass or interface declaration (as in eval/JavaInterop).
	private static @Nullable Method accessibleMethod(Method method) {
		if (method.trySetAccessible()) {
			return method;
		}
		for (Class<?> c = method.getDeclaringClass(); c != null; c = c.getSuperclass()) {
			Method onInterface = accessibleOnInterfaces(c, method);
			if (onInterface != null) {
				return onInterface;
			}
			if (c != method.getDeclaringClass()) {
				Method declared = accessibleDeclaration(c, method);
				if (declared != null) {
					return declared;
				}
			}
		}
		return null;
	}

	private static @Nullable Method accessibleOnInterfaces(Class<?> cls, Method method) {
		for (Class<?> iface : cls.getInterfaces()) {
			Method declared = accessibleDeclaration(iface, method);
			if (declared != null) {
				return declared;
			}
			Method nested = accessibleOnInterfaces(iface, method);
			if (nested != null) {
				return nested;
			}
		}
		return null;
	}

	private static @Nullable Method accessibleDeclaration(Class<?> cls, Method method) {
		try {
			Method declared = cls.getMethod(method.getName(), method.getParameterTypes());
			return declared.trySetAccessible() ? declared : null;
		}
		catch (NoSuchMethodException ex) {
			return null;
		}
	}

	// Picks the overload whose arguments marshal at the lowest total cost; ties broken
	// by the parameter-type signature (never by getMethods() order). A varargs
	// executable is tried both as-is and with the trailing arguments packed (at a flat
	// extra cost). Returns {executable, marshalled args, Integer cost, signature} (a
	// plain Object[] because a nested record would become a second class file).
	private static Object @Nullable [] select(List<? extends Executable> candidates, @Nullable Object[] args) {
		Object[] best = null;
		for (Executable e : candidates) {
			best = better(best, tryFixedArity(e, args));
			if (e.isVarArgs()) {
				best = better(best, tryVarargs(e, args));
			}
		}
		return best;
	}

	private static Object @Nullable [] better(Object @Nullable [] a, Object @Nullable [] b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		int costA = (Integer) a[2];
		int costB = (Integer) b[2];
		return costB < costA || (costB == costA && ((String) b[3]).compareTo((String) a[3]) < 0) ? b : a;
	}

	private static Object @Nullable [] tryFixedArity(Executable e, @Nullable Object[] args) {
		if (e.getParameterCount() != args.length) {
			return null;
		}
		Class<?>[] params = e.getParameterTypes();
		@Nullable Object[] out = new @Nullable Object[params.length];
		int total = 0;
		for (int i = 0; i < params.length; i++) {
			int cost = marshal(args[i], params[i], out, i);
			if (cost == NO_MATCH) {
				return null;
			}
			total += cost;
		}
		return new Object[] { e, out, total, signatureOf(params) };
	}

	private static Object @Nullable [] tryVarargs(Executable e, @Nullable Object[] args) {
		Class<?>[] params = e.getParameterTypes();
		int fixed = params.length - 1;
		if (args.length < fixed) {
			return null;
		}
		@Nullable Object[] out = new @Nullable Object[params.length];
		int total = COST_VARARGS;
		for (int i = 0; i < fixed; i++) {
			int cost = marshal(args[i], params[i], out, i);
			if (cost == NO_MATCH) {
				return null;
			}
			total += cost;
		}
		Class<?> component = params[fixed].getComponentType();
		Object packed = Array.newInstance(component, args.length - fixed);
		@Nullable Object[] slot = new @Nullable Object[1];
		for (int i = fixed; i < args.length; i++) {
			int cost = marshal(args[i], component, slot, 0);
			if (cost == NO_MATCH) {
				return null;
			}
			total += cost;
			Array.set(packed, i - fixed, slot[0]);
		}
		out[fixed] = packed;
		// The "*" keeps the packed signature distinct from the fixed-arity
		// interpretation, so the tie-break stays a total order.
		return new Object[] { e, out, total, signatureOf(params) + "*" };
	}

	private static String signatureOf(Class<?>[] params) {
		StringBuilder sb = new StringBuilder();
		for (Class<?> p : params) {
			sb.append(p.getName()).append(',');
		}
		return sb.toString();
	}

	// Writes the Java value for `value` into out[index] and returns its conversion
	// cost, or NO_MATCH if it cannot convert. The single source of truth for both
	// overload selection and actual marshalling, mirroring eval/JavaInterop over the
	// compiled value representation.
	private static int marshal(@Nullable Object value, Class<?> target, @Nullable Object[] out, int index) {
		if (value == null) { // nil
			if (target == boolean.class || target == Boolean.class) {
				out[index] = Boolean.FALSE;
				return target == boolean.class ? COST_EXACT : COST_BOXED;
			}
			if (target.isPrimitive()) {
				return NO_MATCH;
			}
			out[index] = null;
			return COST_BOXED; // nil carries no type, so any reference target ties
		}
		if (value instanceof Long l) {
			return marshalLong(l, target, out, index);
		}
		if (value instanceof Double d) {
			return marshalDouble(d, target, out, index);
		}
		if (value instanceof int[] chBox && chBox.length == 1) {
			// A Lisp CHARACTER is a length-1 int[]{codePoint}. Narrow to a Java char when
			// the target parameter is char/Character AND the code point fits in the BMP;
			// a
			// supplementary code point cannot fit a single Java char, so bridging that
			// parameter shape is refused (the caller can accept a String / int instead).
			int cp = chBox[0];
			if ((target == char.class || target == Character.class || target.isAssignableFrom(Character.class))
					&& Character.isBmpCodePoint(cp)) {
				Character c = (char) cp;
				out[index] = c;
				return target == char.class ? COST_EXACT : (target == Character.class ? COST_WIDEN : COST_BOXED);
			}
			// int / Integer accept the raw code point (including supplementary values).
			if (target == int.class) {
				out[index] = cp;
				return COST_WIDEN;
			}
			if (target == Integer.class || target.isAssignableFrom(Integer.class)) {
				out[index] = cp;
				return target == Integer.class ? COST_WIDEN : COST_BOXED;
			}
			return NO_MATCH;
		}
		if (value instanceof String s) {
			if (isLispString(s)) {
				String str = stringValue(s);
				if (target.isAssignableFrom(String.class)) {
					out[index] = str;
					return target == String.class ? COST_EXACT : COST_BOXED;
				}
				if ((target == char.class || target == Character.class) && str.length() == 1) {
					out[index] = str.charAt(0);
					return COST_NARROW;
				}
				return NO_MATCH;
			}
			if ("T".equals(s)) { // the symbol t = Lisp true (the reader upcases it)
				if (target == boolean.class) {
					out[index] = Boolean.TRUE;
					return COST_EXACT;
				}
				if (target == Boolean.class || target.isAssignableFrom(Boolean.class)) {
					out[index] = Boolean.TRUE;
					return target == Boolean.class ? COST_WIDEN : COST_BOXED;
				}
				return NO_MATCH;
			}
			return NO_MATCH; // any other symbol is not bridged
		}
		if (value.getClass() == Object[].class) {
			Object[] arr = (Object[]) value;
			if (arr.length > 0 && arr[0] instanceof Integer) { // function value
				if (target.isInterface()) {
					out[index] = proxy(target, value);
					return COST_PROXY;
				}
				return NO_MATCH;
			}
			List<@Nullable Object> elements = properListElements(arr);
			if (elements == null) {
				return NO_MATCH; // a dotted (improper) list is not a sequence
			}
			return marshalSequence(elements, target, out, index);
		}
		if (value instanceof BigInteger || value instanceof BigInteger[]) {
			return NO_MATCH; // bignums and ratios are not bridged (as interpreted)
		}
		if (value instanceof ArrayList<?> list && !list.isEmpty() && list.get(0) instanceof Object[] header) {
			// The compiled Lisp array representation: slot 0 = the {dims, fillPointer,
			// adjustable} header. The fill pointer, when present, is the effective
			// length of the marshaled sequence.
			if (!(header[0] instanceof Object[] dims) || dims.length != 1) {
				return NO_MATCH; // only rank-1 vectors are bridged
			}
			int count = header[1] instanceof Long fp ? fp.intValue() : list.size() - 1;
			return marshalSequence(new ArrayList<>(list.subList(1, 1 + count)), target, out, index);
		}
		// Anything else is a wrapped host object.
		if (target.isInstance(value)) {
			out[index] = value;
			return target == value.getClass() ? COST_EXACT : COST_WIDEN;
		}
		return NO_MATCH;
	}

	// A proper list (or rank-1 vector) converts to a Java array (element-wise to the
	// component type, recursively) or, for any List-compatible reference target, to a
	// java.util.List of boxed elements.
	private static int marshalSequence(List<@Nullable Object> elements, Class<?> target, @Nullable Object[] out,
			int index) {
		@Nullable Object[] slot = new @Nullable Object[1];
		if (target.isArray()) {
			Class<?> component = target.getComponentType();
			Object array = Array.newInstance(component, elements.size());
			int total = COST_CONVERT;
			for (int i = 0; i < elements.size(); i++) {
				int cost = marshal(elements.get(i), component, slot, 0);
				if (cost == NO_MATCH) {
					return NO_MATCH;
				}
				total += cost;
				Array.set(array, i, slot[0]);
			}
			out[index] = array;
			return total;
		}
		if (target.isAssignableFrom(ArrayList.class)) {
			List<@Nullable Object> list = new ArrayList<>(elements.size());
			int total = COST_BOXED;
			for (Object element : elements) {
				int cost = marshal(element, Object.class, slot, 0);
				if (cost == NO_MATCH) {
					return NO_MATCH;
				}
				total += cost;
				list.add(slot[0]);
			}
			out[index] = list;
			return total;
		}
		return NO_MATCH;
	}

	private static int marshalLong(long v, Class<?> target, @Nullable Object[] out, int index) {
		if (target == int.class || target == Integer.class) {
			out[index] = (int) v;
			return target == int.class ? COST_EXACT : COST_WIDEN;
		}
		if (target == long.class || target == Long.class) {
			out[index] = v;
			return COST_WIDEN;
		}
		if (target == double.class || target == Double.class) {
			out[index] = (double) v;
			return COST_CONVERT;
		}
		if (target == float.class || target == Float.class) {
			out[index] = (float) v;
			return COST_CONVERT;
		}
		if (target == short.class || target == Short.class) {
			out[index] = (short) v;
			return COST_NARROW;
		}
		if (target == byte.class || target == Byte.class) {
			out[index] = (byte) v;
			return COST_NARROW;
		}
		if (target == Object.class || target == Number.class || target.isAssignableFrom(Long.class)
				|| target.isAssignableFrom(Integer.class)) {
			// Box to the narrowest type that holds the value, like Common Lisp fixnums.
			out[index] = v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? (Object) (int) v : (Object) v;
			return COST_BOXED;
		}
		return NO_MATCH;
	}

	private static int marshalDouble(double v, Class<?> target, @Nullable Object[] out, int index) {
		if (target == double.class || target == Double.class) {
			out[index] = v;
			return target == double.class ? COST_EXACT : COST_WIDEN;
		}
		if (target == float.class || target == Float.class) {
			out[index] = (float) v;
			return COST_NARROW;
		}
		if (target == Object.class || target == Number.class || target.isAssignableFrom(Double.class)) {
			out[index] = v;
			return COST_BOXED;
		}
		return NO_MATCH;
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

	static @Nullable Object unmarshal(@Nullable Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof Boolean b) {
			return b ? "T" : null;
		}
		if (o instanceof Integer i) {
			return (long) i;
		}
		if (o instanceof Long) {
			return o;
		}
		if (o instanceof Short s) {
			return (long) s;
		}
		if (o instanceof Byte b) {
			return (long) b;
		}
		if (o instanceof Double) {
			return o;
		}
		if (o instanceof Float f) {
			return (double) f;
		}
		if (o instanceof Character c) {
			// A Java char is a UTF-16 code unit; the Lisp CHARACTER is a length-1
			// int[]{codePoint}. Wrap directly (BMP code units are also code points).
			return new int[] { c.charValue() };
		}
		if (o instanceof String s) {
			return quote(s);
		}
		if (o.getClass().isArray()) {
			return arrayToList(o);
		}
		return o; // any other object stays a wrapped host object
	}

	// A Java array result surfaces as a Lisp list, elements unmarshalled recursively.
	private static @Nullable Object arrayToList(Object array) {
		Object result = null;
		for (int i = Array.getLength(array) - 1; i >= 0; i--) {
			result = new Object[] { unmarshal(Array.get(array, i)), result };
		}
		return result;
	}

	private static boolean isLispString(@Nullable Object v) {
		return v instanceof String s && !s.isEmpty() && s.charAt(0) == '"';
	}

	/** The unquoted value when {@code v} is a Lisp string, otherwise null. */
	private static @Nullable String lispString(@Nullable Object v) {
		return v instanceof String s && isLispString(s) ? stringValue(s) : null;
	}

	private static String stringValue(String s) {
		return s.substring(1, s.length() - 1);
	}

	private static String quote(String s) {
		return "\"" + s + "\"";
	}

	// A wrapped host object is any value outside the compiled Lisp representation
	// (Long/Double/BigInteger integers, BigInteger[] ratios, String symbols/strings,
	// int[]{codePoint} CHARACTERs, Object[] conses/function values). ArrayList/HashMap
	// receivers are accepted: a wrapped List/Map is indistinguishable from a Lisp
	// array/hash-table here, and calling methods on either is harmless.
	private static boolean isJavaObject(@Nullable Object v) {
		return v != null && !(v instanceof Long) && !(v instanceof Double) && !(v instanceof BigInteger)
				&& !(v instanceof BigInteger[]) && !(v instanceof String) && !(v instanceof int[])
				&& v.getClass() != Object[].class;
	}

	// A minimal prin1-ish description for error messages.
	private static String describe(@Nullable Object v) {
		if (v == null) {
			return "NIL";
		}
		if (v instanceof int[] cp && cp.length == 1) {
			return "#\\" + Character.toString(cp[0]);
		}
		if (v instanceof String || v instanceof Long || v instanceof Double) {
			return String.valueOf(v);
		}
		return "#<java " + v.getClass().getName() + ">";
	}

	private static Class<?> loadClass(String name) {
		try {
			return Class.forName(name);
		}
		catch (ClassNotFoundException ex) {
			throw new RuntimeException("No such class: " + name);
		}
	}

	private static RuntimeException fail(String what, ReflectiveOperationException ex) {
		Throwable cause = ex instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : ex;
		return new RuntimeException("error " + what + ": " + cause);
	}

}
