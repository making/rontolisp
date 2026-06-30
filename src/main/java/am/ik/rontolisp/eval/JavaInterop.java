package am.ik.rontolisp.eval;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * Reflection bridge that exposes arbitrary Java APIs (Swing, AWT, ...) to the rontolisp
 * interpreter. It marshals between Lisp values and Java objects, resolves overloaded
 * constructors/methods <em>deterministically</em> (by a per-argument conversion cost, so
 * an integer prefers an {@code int} overload over a {@code long}/{@code double} one, with
 * ties broken by a stable signature key rather than by reflection ordering), and turns
 * Lisp callables into Java interface instances via {@link Proxy}.
 * <p>
 * JVM-interpreter-only: the wrapped objects are {@link LispJavaObject}s, which the
 * compiler backends cannot lower, and the reflection relies on classes (and their
 * members) being registered for reflection at runtime -- a GraalVM native image carries
 * none for interop, so it does not work in the native binary, only under {@code java -jar
 * rontolisp.jar}.
 */
final class JavaInterop {

	/**
	 * Calls back into the interpreter to apply a Lisp function/lambda (used by proxies).
	 */
	@FunctionalInterface
	interface Caller {

		LispVal call(LispVal function, List<LispVal> args);

	}

	// Conversion cost of a single argument against a parameter type: lower is a better
	// (more specific, less lossy) match. The selected overload minimizes the total.
	private static final int COST_EXACT = 0; // ideal target (int<-integer,
												// String<-string)

	private static final int COST_WIDEN = 1; // lossless widening (int->long,
												// float->double)

	private static final int COST_CONVERT = 2; // representable but less ideal
												// (integer->double)

	private static final int COST_NARROW = 4; // narrowing/lossy (integer->short/byte,
												// string->char)

	private static final int COST_BOXED = 6; // boxed/Object/Number/super-type target

	private static final int COST_PROXY = 8; // a Lisp callable adapted to an interface

	private static final int NO_MATCH = -1; // this argument cannot become this type

	private JavaInterop() {
	}

	// The chosen overload together with its already-marshalled arguments.
	private record Selected<E extends Executable>(E executable, @Nullable Object[] args, int cost, String signature) {
	}

	static LispVal newInstance(String className, List<LispVal> args, Caller caller) {
		Class<?> cls = loadClass(className);
		Selected<Constructor<?>> sel = select(List.of(cls.getConstructors()), args, caller);
		if (sel == null) {
			throw new LispEvalException(
					"No matching constructor for " + className + " with " + args.size() + " argument(s)");
		}
		try {
			return unmarshal(sel.executable().newInstance(sel.args()));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("constructing " + className, ex);
		}
	}

	static LispVal callInstance(LispVal target, String methodName, List<LispVal> args, Caller caller) {
		if (!(target instanceof LispJavaObject obj)) {
			throw new LispEvalException("java:call expects a java object as the first argument, got " + target.print());
		}
		return invoke(obj.ref().getClass(), obj.ref(), methodName, args, caller);
	}

	static LispVal callStatic(String className, String methodName, List<LispVal> args, Caller caller) {
		return invoke(loadClass(className), null, methodName, args, caller);
	}

	private static LispVal invoke(Class<?> cls, @Nullable Object receiver, String methodName, List<LispVal> args,
			Caller caller) {
		List<Method> candidates = new ArrayList<>();
		for (Method method : cls.getMethods()) {
			if (method.getName().equals(methodName)) {
				candidates.add(method);
			}
		}
		Selected<Method> sel = select(candidates, args, caller);
		if (sel == null) {
			throw new LispEvalException(
					"No matching method " + cls.getName() + "." + methodName + " with " + args.size() + " argument(s)");
		}
		try {
			sel.executable().setAccessible(true);
			return unmarshal(sel.executable().invoke(receiver, sel.args()));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("calling " + cls.getName() + "." + methodName, ex);
		}
	}

	// Picks the overload whose arguments marshal at the lowest total cost; ties are
	// broken by the parameter-type signature so the result never depends on the
	// (unspecified) order getMethods()/getConstructors() returns.
	private static <E extends Executable> @Nullable Selected<E> select(List<E> candidates, List<LispVal> args,
			Caller caller) {
		Selected<E> best = null;
		for (E e : candidates) {
			if (e.getParameterCount() != args.size()) {
				continue;
			}
			Class<?>[] params = e.getParameterTypes();
			@Nullable Object[] out = new @Nullable Object[params.length];
			int total = 0;
			boolean ok = true;
			for (int i = 0; i < params.length; i++) {
				int cost = marshal(args.get(i), params[i], caller, out, i);
				if (cost == NO_MATCH) {
					ok = false;
					break;
				}
				total += cost;
			}
			if (!ok) {
				continue;
			}
			String signature = signatureOf(params);
			if (best == null || total < best.cost()
					|| (total == best.cost() && signature.compareTo(best.signature()) < 0)) {
				best = new Selected<>(e, out, total, signature);
			}
		}
		return best;
	}

	private static String signatureOf(Class<?>[] params) {
		StringBuilder sb = new StringBuilder();
		for (Class<?> p : params) {
			sb.append(p.getName()).append(',');
		}
		return sb.toString();
	}

	// (java:field "class.Name" "CONSTANT") -> static field; (java:field obj "name") ->
	// instance field.
	static LispVal field(LispVal classOrObject, String fieldName) {
		try {
			if (classOrObject instanceof LispString s) {
				Field field = loadClass(s.value()).getField(fieldName);
				return unmarshal(field.get(null));
			}
			if (classOrObject instanceof LispJavaObject obj) {
				Field field = obj.ref().getClass().getField(fieldName);
				return unmarshal(field.get(obj.ref()));
			}
			throw new LispEvalException(
					"java:field expects a class-name string or a java object, got " + classOrObject.print());
		}
		catch (ReflectiveOperationException ex) {
			throw fail("reading field " + fieldName, ex);
		}
	}

	// (java:proxy "fully.qualified.Interface" callable): the callable is applied as
	// (callable method-name arg1 arg2 ...) for every interface method; Object methods
	// (equals/hashCode/toString) keep identity behavior.
	static LispVal proxy(String interfaceName, LispVal callable, Caller caller) {
		Class<?> iface = loadClass(interfaceName);
		if (!iface.isInterface()) {
			throw new LispEvalException("java:proxy expects an interface, got " + interfaceName);
		}
		Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
				(p, method, methodArgs) -> {
					switch (method.getName()) {
						case "hashCode" -> {
							return System.identityHashCode(p);
						}
						case "equals" -> {
							return p == (methodArgs == null ? null : methodArgs[0]);
						}
						case "toString" -> {
							return "#<java-proxy " + interfaceName + ">";
						}
						default -> {
						}
					}
					List<LispVal> callArgs = new ArrayList<>();
					callArgs.add(new LispString(method.getName()));
					if (methodArgs != null) {
						for (Object a : methodArgs) {
							callArgs.add(unmarshal(a));
						}
					}
					LispVal result = caller.call(callable, callArgs);
					Class<?> ret = method.getReturnType();
					if (ret == void.class) {
						return null;
					}
					@Nullable Object[] slot = new @Nullable Object[1];
					if (marshal(result, ret, caller, slot, 0) == NO_MATCH) {
						throw new LispEvalException("java:proxy: cannot return " + result.print() + " as " + ret
								+ " from " + interfaceName);
					}
					return slot[0];
				});
		return new LispJavaObject(proxy);
	}

	// Writes the Java value for `value` (assignable to `target`) into out[index] and
	// returns its conversion cost, or NO_MATCH (writing nothing) if it cannot convert.
	// The single source of truth for both overload selection and actual marshalling.
	private static int marshal(LispVal value, Class<?> target, Caller caller, @Nullable Object[] out, int index) {
		switch (value) {
			case LispNil ignored -> {
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
			case LispTrue ignored -> {
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
			case LispInteger i -> {
				return marshalLong(i.value(), target, out, index);
			}
			case LispDouble d -> {
				return marshalDouble(d.value(), target, out, index);
			}
			case LispString s -> {
				if (target.isAssignableFrom(String.class)) {
					out[index] = s.value();
					return target == String.class ? COST_EXACT : COST_BOXED;
				}
				if ((target == char.class || target == Character.class) && s.value().length() == 1) {
					out[index] = s.value().charAt(0);
					return COST_NARROW;
				}
				return NO_MATCH;
			}
			case LispChar c -> {
				if (target == char.class) {
					out[index] = (char) c.codePoint();
					return COST_EXACT;
				}
				if (target == Character.class || target.isAssignableFrom(Character.class)) {
					out[index] = (char) c.codePoint();
					return target == Character.class ? COST_WIDEN : COST_BOXED;
				}
				return NO_MATCH;
			}
			case LispJavaObject obj -> {
				if (target.isInstance(obj.ref())) {
					out[index] = obj.ref();
					return target == obj.ref().getClass() ? COST_EXACT : COST_WIDEN;
				}
				return NO_MATCH;
			}
			case LispLambda lambda -> {
				return marshalCallable(lambda, target, caller, out, index);
			}
			case LispFunction function -> {
				return marshalCallable(function, target, caller, out, index);
			}
			default -> {
				return NO_MATCH; // cons, symbol, array, hash-table, ... are not bridged
			}
		}
	}

	// A Lisp callable passed where an interface is expected is auto-wrapped in a proxy.
	private static int marshalCallable(LispVal callable, Class<?> target, Caller caller, @Nullable Object[] out,
			int index) {
		if (target.isInterface()) {
			out[index] = ((LispJavaObject) proxy(target.getName(), callable, caller)).ref();
			return COST_PROXY;
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

	static LispVal unmarshal(@Nullable Object o) {
		return switch (o) {
			case null -> LispNil.INSTANCE;
			case Boolean b -> b ? LispTrue.INSTANCE : LispNil.INSTANCE;
			case Integer i -> new LispInteger(i);
			case Long l -> new LispInteger(l);
			case Short s -> new LispInteger(s);
			case Byte b -> new LispInteger(b);
			case Double d -> new LispDouble(d);
			case Float f -> new LispDouble(f);
			case Character c -> new LispChar(c);
			case String s -> new LispString(s);
			default -> new LispJavaObject(o);
		};
	}

	private static Class<?> loadClass(String name) {
		try {
			return Class.forName(name);
		}
		catch (ClassNotFoundException ex) {
			throw new LispEvalException("No such class: " + name);
		}
	}

	private static LispEvalException fail(String what, ReflectiveOperationException ex) {
		Throwable cause = ex instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : ex;
		return new LispEvalException("error " + what + ": " + cause);
	}

}
