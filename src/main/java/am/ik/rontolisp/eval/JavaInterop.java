package am.ik.rontolisp.eval;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
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
 * ties broken by a stable signature key rather than by reflection ordering), turns Lisp
 * callables into Java interface instances via {@link Proxy}, bridges proper lists and
 * rank-1 vectors to Java arrays / {@code java.util.List} parameters (packing varargs
 * tails), and returns Java array results as Lisp lists.
 * <p>
 * This is the interpreter side of the bridge: the wrapped objects are
 * {@link LispJavaObject}s, and the reflection relies on classes (and their members) being
 * registered for reflection at runtime -- a GraalVM native image carries none for
 * interop, so interpreting {@code java:} works only under {@code java -jar
 * rontolisp.jar}. The JVM compiler supports the same five functions through its own
 * rewrite of this class against the compiled value representation
 * ({@code codegen.jvm.JavaBridgeTemplate}) -- keep the marshalling rules and overload
 * costs of the two in sync. The WASM backend cannot lower host references and rejects
 * {@code java:} forms.
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

	private static final int COST_VARARGS = 10; // flat penalty for packing a varargs
												// tail, so a fixed-arity overload wins

	private static final int NO_MATCH = -1; // this argument cannot become this type

	// Resolution caches. Reflection lookups (Class.forName, getMethods, getField) and the
	// cost-based overload selection dominate a java: call, so each is remembered. The
	// chosen overload is keyed by (class, member, argument kinds): a kind is the smallest
	// token of which every marshal() cost is a pure function, so the memoized choice is
	// the one select() would make again. Lists and vectors are element-dependent and
	// have no kind; a call passing one is resolved every time. Kinds are canonical (a
	// KIND_ constant or a Class), so a member's remembered choices are scanned by
	// identity. Mirrored in codegen.jvm.JavaBridgeTemplate.
	private static final int CACHE_LIMIT = 4096; // a full cache is cleared, never grown

	private static final int CHOICES_PER_MEMBER = 16; // a full member starts over

	private static final String CONSTRUCTOR = "<init>"; // member key of a constructor

	private static final ConcurrentHashMap<String, Class<?>> CLASSES = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<Class<?>, List<Constructor<?>>> CONSTRUCTORS = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<List<Object>, List<Method>> METHODS = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<List<Object>, Field> FIELDS = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Memo[]>> CHOICES = new ConcurrentHashMap<>();

	// Argument kinds that are not a host object's class (a host object's kind is its
	// exact class, which isInstance/== costs depend on).
	private static final String KIND_NIL = "nil";

	private static final String KIND_T = "t";

	private static final String KIND_INTEGER = "integer";

	private static final String KIND_FLOAT = "float";

	private static final String KIND_STRING_1 = "string/1"; // may narrow to char

	private static final String KIND_STRING = "string";

	private static final String KIND_CHAR = "char"; // BMP: fits a Java char

	private static final String KIND_SUPPLEMENTARY_CHAR = "char/supplementary";

	private static final String KIND_FUNCTION = "function";

	private JavaInterop() {
	}

	// An overload chosen by select(): the executable, its parameter types, and whether
	// the trailing arguments are packed into its varargs array.
	private record Overload(Executable executable, Class<?>[] params, boolean packed) {
	}

	// The overload select() chose for these argument kinds.
	private record Memo(Object[] kinds, Overload overload) {

		boolean matches(Object[] argumentKinds) {
			if (this.kinds.length != argumentKinds.length) {
				return false;
			}
			for (int i = 0; i < argumentKinds.length; i++) {
				if (this.kinds[i] != argumentKinds[i]) {
					return false;
				}
			}
			return true;
		}

	}

	// The cost of passing argument `index` where `target` is expected, or NO_MATCH.
	@FunctionalInterface
	private interface ArgumentCost {

		int cost(int index, Class<?> target);

	}

	static LispVal newInstance(String className, List<LispVal> args, Caller caller) {
		Class<?> cls = loadClass(className);
		Overload overload = resolve(cls, CONSTRUCTOR, () -> constructors(cls), args, caller);
		if (overload == null) {
			throw new LispEvalException(
					"No matching constructor for " + className + " with " + args.size() + " argument(s)");
		}
		try {
			Constructor<?> constructor = (Constructor<?>) overload.executable();
			return unmarshal(constructor.newInstance(marshalArguments(overload, args, caller)));
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
		Overload overload = resolve(cls, methodName, () -> methods(cls, methodName), args, caller);
		if (overload == null) {
			throw new LispEvalException(
					"No matching method " + cls.getName() + "." + methodName + " with " + args.size() + " argument(s)");
		}
		try {
			Method method = (Method) overload.executable();
			return unmarshal(method.invoke(receiver, marshalArguments(overload, args, caller)));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("calling " + cls.getName() + "." + methodName, ex);
		}
	}

	// The overload of `member` on `cls` for these arguments: the one remembered for their
	// kinds, otherwise select() over the candidates by argument kind (and remembered).
	// A list or vector has no kind: such a call is costed by marshalling its arguments
	// and never remembered.
	private static @Nullable Overload resolve(Class<?> cls, String member,
			Supplier<? extends List<? extends Executable>> candidates, List<LispVal> args, Caller caller) {
		Object[] kinds = kindsOf(args);
		if (kinds == null) {
			@Nullable Object[] slot = new @Nullable Object[1];
			return select(candidates.get(), args.size(), (i, target) -> marshal(args.get(i), target, caller, slot, 0));
		}
		Overload remembered = remembered(cls, member, kinds);
		if (remembered != null) {
			return remembered;
		}
		Overload chosen = select(candidates.get(), kinds.length, (i, target) -> kindCost(kinds[i], target));
		if (chosen != null) {
			rememberChoice(cls, member, new Memo(kinds, chosen));
		}
		return chosen;
	}

	// The kind of every argument, or null when one has none.
	private static Object @Nullable [] kindsOf(List<LispVal> args) {
		Object[] kinds = new Object[args.size()];
		for (int i = 0; i < kinds.length; i++) {
			Object kind = kindOf(args.get(i));
			if (kind == null) {
				return null;
			}
			kinds[i] = kind;
		}
		return kinds;
	}

	private static @Nullable Overload remembered(Class<?> cls, String member, Object[] kinds) {
		ConcurrentHashMap<String, Memo[]> members = CHOICES.get(cls);
		Memo[] memos = members == null ? null : members.get(member);
		if (memos != null) {
			for (Memo memo : memos) {
				if (memo.matches(kinds)) {
					return memo.overload();
				}
			}
		}
		return null;
	}

	// Copy-on-write: a racing update may drop a choice, which is only resolved again.
	private static void rememberChoice(Class<?> cls, String member, Memo memo) {
		ConcurrentHashMap<String, Memo[]> members = CHOICES.get(cls);
		if (members == null) {
			members = new ConcurrentHashMap<>();
			remember(CHOICES, cls, members);
		}
		Memo[] old = members.get(member);
		Memo[] memos;
		if (old == null || old.length >= CHOICES_PER_MEMBER) {
			memos = new Memo[] { memo };
		}
		else {
			memos = Arrays.copyOf(old, old.length + 1);
			memos[old.length] = memo;
		}
		members.put(member, memos);
	}

	// The token of which marshal(value, target) is a pure function for every target, or
	// null when there is none: a list or vector (the cost sums its elements), and the
	// values marshal() never bridges (they never match, so nothing is remembered).
	private static @Nullable Object kindOf(LispVal value) {
		return switch (value) {
			case LispNil ignored -> KIND_NIL;
			case LispTrue ignored -> KIND_T;
			case LispInteger ignored -> KIND_INTEGER;
			case LispDouble ignored -> KIND_FLOAT;
			case LispString s -> s.value().length() == 1 ? KIND_STRING_1 : KIND_STRING;
			case LispChar c -> Character.isBmpCodePoint(c.codePoint()) ? KIND_CHAR : KIND_SUPPLEMENTARY_CHAR;
			case LispJavaObject obj -> obj.ref().getClass();
			case LispLambda ignored -> KIND_FUNCTION;
			case LispFunction ignored -> KIND_FUNCTION;
			default -> null;
		};
	}

	private static List<Constructor<?>> constructors(Class<?> cls) {
		List<Constructor<?>> cached = CONSTRUCTORS.get(cls);
		if (cached == null) {
			cached = List.of(cls.getConstructors());
			remember(CONSTRUCTORS, cls, cached);
		}
		return cached;
	}

	private static List<Method> methods(Class<?> cls, String methodName) {
		List<Object> key = List.of(cls, methodName);
		List<Method> cached = METHODS.get(key);
		if (cached == null) {
			List<Method> candidates = new ArrayList<>();
			for (Method method : cls.getMethods()) {
				if (method.getName().equals(methodName)) {
					Method accessible = accessibleMethod(method);
					if (accessible != null) {
						candidates.add(accessible);
					}
				}
			}
			cached = List.copyOf(candidates);
			remember(METHODS, key, cached);
		}
		return cached;
	}

	private static Field publicField(Class<?> cls, String fieldName) throws NoSuchFieldException {
		List<Object> key = List.of(cls, fieldName);
		Field cached = FIELDS.get(key);
		if (cached == null) {
			cached = cls.getField(fieldName);
			remember(FIELDS, key, cached);
		}
		return cached;
	}

	private static <K, V> void remember(ConcurrentHashMap<K, V> cache, K key, V value) {
		if (cache.size() >= CACHE_LIMIT) {
			cache.clear();
		}
		cache.put(key, value);
	}

	// A public method declared in a non-exported/non-public class (e.g. the List.of
	// result type java.util.ImmutableCollections$ListN) cannot be invoked reflectively;
	// re-resolve it to the same method on an accessible superclass or interface
	// (List.size() instead of ImmutableCollections$ListN.size()).
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

	// Picks the overload whose arguments convert at the lowest total cost; ties are
	// broken by the parameter-type signature so the result never depends on the
	// (unspecified) order getMethods()/getConstructors() returns. A varargs executable
	// is tried both as-is (the last argument supplying the array itself) and with the
	// trailing arguments packed into the varargs array (at a flat extra cost, so a
	// fixed-arity interpretation is preferred). A pure function of the candidates and
	// the argument costs: over kindCost of argument kinds it needs no argument value.
	private static @Nullable Overload select(List<? extends Executable> candidates, int argc, ArgumentCost cost) {
		Overload best = null;
		int bestCost = 0;
		for (Executable e : candidates) {
			Class<?>[] params = e.getParameterTypes();
			int fixedCost = fixedArityCost(params, argc, cost);
			if (fixedCost != NO_MATCH) {
				Overload fixed = new Overload(e, params, false);
				if (best == null || beats(fixed, fixedCost, best, bestCost)) {
					best = fixed;
					bestCost = fixedCost;
				}
			}
			if (e.isVarArgs()) {
				int packedCost = varargsCost(params, argc, cost);
				if (packedCost != NO_MATCH) {
					Overload packed = new Overload(e, params, true);
					if (best == null || beats(packed, packedCost, best, bestCost)) {
						best = packed;
						bestCost = packedCost;
					}
				}
			}
		}
		return best;
	}

	private static boolean beats(Overload a, int costA, Overload b, int costB) {
		return costA < costB || (costA == costB && signatureOf(a).compareTo(signatureOf(b)) < 0);
	}

	private static int fixedArityCost(Class<?>[] params, int argc, ArgumentCost cost) {
		if (params.length != argc) {
			return NO_MATCH;
		}
		int total = 0;
		for (int i = 0; i < params.length; i++) {
			int c = cost.cost(i, params[i]);
			if (c == NO_MATCH) {
				return NO_MATCH;
			}
			total += c;
		}
		return total;
	}

	private static int varargsCost(Class<?>[] params, int argc, ArgumentCost cost) {
		int fixed = params.length - 1;
		if (argc < fixed) {
			return NO_MATCH;
		}
		int total = COST_VARARGS;
		for (int i = 0; i < fixed; i++) {
			int c = cost.cost(i, params[i]);
			if (c == NO_MATCH) {
				return NO_MATCH;
			}
			total += c;
		}
		Class<?> component = params[fixed].getComponentType();
		for (int i = fixed; i < argc; i++) {
			int c = cost.cost(i, component);
			if (c == NO_MATCH) {
				return NO_MATCH;
			}
			total += c;
		}
		return total;
	}

	// The tie-break key: the parameter-type names, with a "*" that keeps a packed varargs
	// interpretation distinct from the same method's fixed-arity one, so the tie-break
	// stays a total order. Built only on a cost tie.
	private static String signatureOf(Overload overload) {
		StringBuilder sb = new StringBuilder();
		for (Class<?> p : overload.params()) {
			sb.append(p.getName()).append(',');
		}
		return overload.packed() ? sb.append('*').toString() : sb.toString();
	}

	// The Java arguments for the chosen overload, packing a varargs tail.
	private static @Nullable Object[] marshalArguments(Overload overload, List<LispVal> args, Caller caller) {
		Class<?>[] params = overload.params();
		@Nullable Object[] out = new @Nullable Object[params.length];
		int fixed = overload.packed() ? params.length - 1 : params.length;
		for (int i = 0; i < fixed; i++) {
			marshalSelected(args.get(i), params[i], caller, out, i);
		}
		if (overload.packed()) {
			Class<?> component = params[fixed].getComponentType();
			Object packed = Array.newInstance(component, args.size() - fixed);
			@Nullable Object[] slot = new @Nullable Object[1];
			for (int i = fixed; i < args.size(); i++) {
				marshalSelected(args.get(i), component, caller, slot, 0);
				Array.set(packed, i - fixed, slot[0]);
			}
			out[fixed] = packed;
		}
		return out;
	}

	// select() costed this argument against this type, so it converts.
	private static void marshalSelected(LispVal value, Class<?> target, Caller caller, @Nullable Object[] out,
			int index) {
		if (marshal(value, target, caller, out, index) == NO_MATCH) {
			throw new IllegalStateException("java interop: the selected overload rejects " + value.print());
		}
	}

	// (java:field "class.Name" "CONSTANT") -> static field; (java:field obj "name") ->
	// instance field.
	static LispVal field(LispVal classOrObject, String fieldName) {
		try {
			if (classOrObject instanceof LispString s) {
				Field field = publicField(loadClass(s.value()), fieldName);
				return unmarshal(field.get(null));
			}
			if (classOrObject instanceof LispJavaObject obj) {
				Field field = publicField(obj.ref().getClass(), fieldName);
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
	// returns its conversion cost, or NO_MATCH (writing nothing) if it cannot convert. A
	// value with a kind is costed by kindCost -- the one cost table -- and converted by
	// convert(); a list or vector element-wise.
	private static int marshal(LispVal value, Class<?> target, Caller caller, @Nullable Object[] out, int index) {
		Object kind = kindOf(value);
		if (kind != null) {
			int cost = kindCost(kind, target);
			if (cost != NO_MATCH) {
				out[index] = convert(value, target, caller);
			}
			return cost;
		}
		switch (value) {
			case LispCons cons -> {
				List<LispVal> elements = properListElements(cons);
				if (elements == null) {
					return NO_MATCH; // a dotted (improper) list is not a sequence
				}
				return marshalSequence(elements, target, caller, out, index);
			}
			case LispArray array -> {
				if (array.dimensions().length != 1) {
					return NO_MATCH; // only rank-1 vectors are bridged
				}
				// The fill pointer, when present, bounds the marshaled sequence.
				int count = array.effectiveLength();
				List<LispVal> elements = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					LispVal element = array.readFlat(i);
					elements.add(element == null ? LispNil.INSTANCE : element);
				}
				return marshalSequence(elements, target, caller, out, index);
			}
			default -> {
				return NO_MATCH; // symbol, hash-table, ... are not bridged
			}
		}
	}

	// The conversion cost of a value of `kind` where `target` is expected, or NO_MATCH.
	// Pure: it reads no value, so overload selection over kinds needs none.
	private static int kindCost(Object kind, Class<?> target) {
		if (kind instanceof Class<?> host) { // a host object's exact class
			if (!target.isAssignableFrom(host)) {
				return NO_MATCH;
			}
			return target == host ? COST_EXACT : COST_WIDEN;
		}
		return switch ((String) kind) {
			case KIND_NIL -> {
				if (target == boolean.class) {
					yield COST_EXACT;
				}
				// nil carries no type, so any reference target ties
				yield target.isPrimitive() ? NO_MATCH : COST_BOXED;
			}
			case KIND_T -> {
				if (target == boolean.class) {
					yield COST_EXACT;
				}
				if (target == Boolean.class) {
					yield COST_WIDEN;
				}
				yield target.isAssignableFrom(Boolean.class) ? COST_BOXED : NO_MATCH;
			}
			case KIND_INTEGER -> integerCost(target);
			case KIND_FLOAT -> floatCost(target);
			case KIND_STRING, KIND_STRING_1 -> {
				if (target.isAssignableFrom(String.class)) {
					yield target == String.class ? COST_EXACT : COST_BOXED;
				}
				// Only a one-character string narrows to a char.
				yield KIND_STRING_1.equals(kind) && (target == char.class || target == Character.class) ? COST_NARROW
						: NO_MATCH;
			}
			case KIND_CHAR, KIND_SUPPLEMENTARY_CHAR -> {
				// A supplementary code point cannot fit a single Java char, so the
				// char/Character overload is refused for it rather than truncated.
				if (KIND_CHAR.equals(kind) && (target == char.class || target == Character.class
						|| target.isAssignableFrom(Character.class))) {
					yield target == char.class ? COST_EXACT : (target == Character.class ? COST_WIDEN : COST_BOXED);
				}
				// int / Integer accept the raw code point (including supplementary
				// values).
				if (target == int.class || target == Integer.class) {
					yield COST_WIDEN;
				}
				yield target.isAssignableFrom(Integer.class) ? COST_BOXED : NO_MATCH;
			}
			// A Lisp callable passed where an interface is expected is auto-wrapped in
			// a proxy.
			case KIND_FUNCTION -> target.isInterface() ? COST_PROXY : NO_MATCH;
			default -> throw new IllegalArgumentException("unknown argument kind " + kind);
		};
	}

	private static int integerCost(Class<?> target) {
		if (target == int.class) {
			return COST_EXACT;
		}
		if (target == Integer.class || target == long.class || target == Long.class) {
			return COST_WIDEN;
		}
		if (target == double.class || target == Double.class || target == float.class || target == Float.class) {
			return COST_CONVERT;
		}
		if (target == short.class || target == Short.class || target == byte.class || target == Byte.class) {
			return COST_NARROW;
		}
		if (target == Object.class || target == Number.class || target.isAssignableFrom(Long.class)
				|| target.isAssignableFrom(Integer.class)) {
			return COST_BOXED;
		}
		return NO_MATCH;
	}

	private static int floatCost(Class<?> target) {
		if (target == double.class) {
			return COST_EXACT;
		}
		if (target == Double.class) {
			return COST_WIDEN;
		}
		if (target == float.class || target == Float.class) {
			return COST_NARROW;
		}
		if (target == Object.class || target == Number.class || target.isAssignableFrom(Double.class)) {
			return COST_BOXED;
		}
		return NO_MATCH;
	}

	// The Java value of a value with a kind, for a target kindCost accepted.
	private static @Nullable Object convert(LispVal value, Class<?> target, Caller caller) {
		return switch (value) {
			case LispNil ignored -> target == boolean.class || target == Boolean.class ? Boolean.FALSE : null;
			case LispTrue ignored -> Boolean.TRUE;
			case LispInteger i -> convertLong(i.value(), target);
			case LispDouble d -> convertDouble(d.value(), target);
			case LispString s -> target.isAssignableFrom(String.class) ? s.value() : (Object) s.value().charAt(0);
			case LispChar c -> {
				int cp = c.codePoint();
				yield Character.isBmpCodePoint(cp) && (target == char.class || target == Character.class
						|| target.isAssignableFrom(Character.class)) ? (Object) (char) cp : (Object) cp;
			}
			case LispJavaObject obj -> obj.ref();
			case LispLambda lambda -> ((LispJavaObject) proxy(target.getName(), lambda, caller)).ref();
			case LispFunction function -> ((LispJavaObject) proxy(target.getName(), function, caller)).ref();
			default -> throw new IllegalArgumentException("no kind: " + value.print());
		};
	}

	private static Object convertLong(long v, Class<?> target) {
		if (target == int.class || target == Integer.class) {
			return (int) v;
		}
		if (target == long.class || target == Long.class) {
			return v;
		}
		if (target == double.class || target == Double.class) {
			return (double) v;
		}
		if (target == float.class || target == Float.class) {
			return (float) v;
		}
		if (target == short.class || target == Short.class) {
			return (short) v;
		}
		if (target == byte.class || target == Byte.class) {
			return (byte) v;
		}
		// Box to the narrowest type that holds the value, like Common Lisp fixnums.
		return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? (Object) (int) v : (Object) v;
	}

	private static Object convertDouble(double v, Class<?> target) {
		return target == float.class || target == Float.class ? (Object) (float) v : (Object) v;
	}

	// A proper list (or rank-1 vector) converts to a Java array (element-wise to the
	// component type, recursively) or, for any List-compatible reference target, to a
	// java.util.List of boxed elements. The per-element costs count toward the total so
	// string elements still prefer a String[] parameter over Object[].
	private static int marshalSequence(List<LispVal> elements, Class<?> target, Caller caller, @Nullable Object[] out,
			int index) {
		@Nullable Object[] slot = new @Nullable Object[1];
		if (target.isArray()) {
			Class<?> component = target.getComponentType();
			Object array = Array.newInstance(component, elements.size());
			int total = COST_CONVERT;
			for (int i = 0; i < elements.size(); i++) {
				int cost = marshal(elements.get(i), component, caller, slot, 0);
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
			for (LispVal element : elements) {
				int cost = marshal(element, Object.class, caller, slot, 0);
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

	private static @Nullable List<LispVal> properListElements(LispCons cons) {
		List<LispVal> result = new ArrayList<>();
		LispVal current = cons;
		while (current instanceof LispCons c) {
			result.add(c.car());
			current = c.cdr();
		}
		return current instanceof LispNil ? result : null;
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
			default -> o.getClass().isArray() ? arrayToList(o) : new LispJavaObject(o);
		};
	}

	// A Java array result (e.g. String.split) surfaces as a Lisp list, elements
	// unmarshalled recursively; it round-trips back through marshalSequence.
	private static LispVal arrayToList(Object array) {
		LispVal result = LispNil.INSTANCE;
		for (int i = Array.getLength(array) - 1; i >= 0; i--) {
			result = new LispCons(unmarshal(Array.get(array, i)), result);
		}
		return result;
	}

	private static Class<?> loadClass(String name) {
		Class<?> cached = CLASSES.get(name);
		if (cached != null) {
			return cached;
		}
		try {
			Class<?> cls = Class.forName(name);
			remember(CLASSES, name, cls);
			return cls;
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
