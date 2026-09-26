package am.ik.rontolisp.eval;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
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
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaField;
import am.ik.rontolisp.compiler.JavaImplementation;
import am.ik.rontolisp.compiler.JavaImplementationType;
import am.ik.rontolisp.compiler.JavaImplementations;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaType;
import am.ik.rontolisp.compiler.ReflectiveJavaClasses;

/**
 * Reflection bridge that exposes arbitrary Java APIs (Swing, AWT, ...) to the rontolisp
 * interpreter. It marshals between Lisp values and Java objects, resolves overloaded
 * constructors/methods by THE shared rule ({@link JavaOverloads}: a per-argument
 * conversion cost, ties broken by a stable signature key rather than by reflection
 * ordering), turns Lisp callables into Java interface instances via {@link Proxy},
 * bridges proper lists and rank-1 vectors to Java arrays / {@code java.util.List}
 * parameters (packing varargs tails), and returns Java array results as Lisp lists.
 * <p>
 * A member designator may carry a parameter tag ({@code "max(long,long)"},
 * {@code "java.lang.StringBuilder(int)"}) that narrows the candidates. A site the
 * interpreter resolved before running it ({@code compiler.JavaSiteResolver}) runs through
 * {@link #invokeResolved}: the member the resolution chose, called with the arguments
 * converted for exactly the kinds it counted on -- what a compiled program's direct call
 * does ({@code codegen.jvm.JvmJavaDirectSites}), check for check and message for message.
 * <p>
 * This is the interpreter side of the bridge: the wrapped objects are
 * {@link LispJavaObject}s, and the reflection relies on classes (and their members) being
 * registered for reflection at runtime -- a GraalVM native image carries none for
 * interop, so interpreting {@code java:} works only under {@code java -jar
 * rontolisp.jar}. The JVM compiler supports the same five functions through its own
 * rewrite of the run-time half against the compiled value representation
 * ({@code codegen.jvm.JavaBridgeTemplate}) -- keep the marshalling rules of the two in
 * sync. The WASM backend cannot lower host references and rejects {@code java:} forms.
 */
final class JavaInterop {

	/**
	 * Calls back into the interpreter to apply a Lisp function/lambda (used by proxies).
	 */
	@FunctionalInterface
	interface Caller {

		LispVal call(LispVal function, List<LispVal> args);

	}

	private static final ReflectiveJavaClasses CLASSES = ReflectiveJavaClasses.instance();

	private static final int NO_MATCH = JavaOverloads.NO_MATCH;

	// The overload chosen for (class, member designator, argument kinds), remembered: a
	// kind is the smallest token of which every marshal() cost is a pure function, so
	// the memoized choice is the one select() would make again. Lists and vectors are
	// element-dependent and have no kind; a call passing one is resolved every time.
	// Kinds are canonical (a JavaKind.Lisp constant or a canonical JavaType), so a
	// member's remembered choices are scanned by identity. Mirrored in
	// codegen.jvm.JavaBridgeTemplate.
	private static final int CACHE_LIMIT = 4096; // a full cache is cleared, never grown

	private static final int CHOICES_PER_MEMBER = 16; // a full member starts over

	private static final String CONSTRUCTOR = "<init>"; // member key of a constructor

	private static final String STATIC_PREFIX = "static "; // member key prefix of a
															// static call

	private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Memo[]>> CHOICES = new ConcurrentHashMap<>();

	// The overload a dispatched site chose for its argument kinds, remembered per site
	// (by identity): over kinds the choice is a pure function of the site's overloads.
	private static final ConcurrentHashMap<SiteKey, Memo[]> DISPATCHES = new ConcurrentHashMap<>();

	// Parsed member designators: a tag is parsed once, not per call.
	private static final ConcurrentHashMap<String, JavaOverloads.Member> MEMBERS = new ConcurrentHashMap<>();

	private JavaInterop() {
	}

	// A site compared by identity: two sites that resolved alike are still two sites.
	private record SiteKey(JavaSite site) {

		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof SiteKey key && key.site == this.site;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this.site);
		}

	}

	// The overload select() chose for these argument kinds.
	private record Memo(JavaKind[] kinds, JavaOverloads.Overload overload) {

		boolean matches(JavaKind[] argumentKinds) {
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

	static LispVal newInstance(String classDesignator, List<LispVal> args, Caller caller) {
		boolean tagged = isTagged(classDesignator);
		String name = tagged ? member(classDesignator).name() : classDesignator;
		ReflectiveJavaClasses.Type type = loadClass(name);
		// A tagged java:new is remembered under its designator, which no method name can
		// spell, so it never answers an untagged one's choice.
		JavaOverloads.Overload overload = resolve(type, tagged ? classDesignator : CONSTRUCTOR,
				() -> JavaOverloads.filterByTag(type.constructors(), tagged ? member(classDesignator).tag() : null),
				args, caller);
		if (overload == null) {
			throw new LispEvalException(
					"No matching constructor for " + classDesignator + " with " + args.size() + " argument(s)");
		}
		try {
			Constructor<?> constructor = (Constructor<?>) executable(overload);
			return unmarshal(constructor.newInstance(marshalArguments(overload, args, caller)));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("constructing " + name, ex);
		}
	}

	static LispVal callInstance(LispVal target, String methodName, List<LispVal> args, Caller caller) {
		if (!(target instanceof LispJavaObject obj)) {
			throw new LispEvalException("java:call expects a java object as the first argument, got " + target.print());
		}
		return invoke(ReflectiveJavaClasses.of(obj.ref().getClass()), obj.ref(), methodName, args, caller);
	}

	static LispVal callStatic(String className, String methodName, List<LispVal> args, Caller caller) {
		return invoke(loadClass(className), null, methodName, args, caller);
	}

	// A static call chooses among the static methods only (JavaOverloads.staticMethods),
	// so its choices are remembered apart from an instance call's of the same name.
	private static LispVal invoke(ReflectiveJavaClasses.Type type, @Nullable Object receiver, String methodName,
			List<LispVal> args, Caller caller) {
		boolean statics = receiver == null;
		// The designator is parsed only when the candidates are needed: a remembered
		// choice is found by the designator as written.
		JavaOverloads.Overload overload = resolve(type, statics ? STATIC_PREFIX + methodName : methodName, () -> {
			List<ReflectiveJavaClasses.Member> candidates;
			List<String> tag = null;
			if (!isTagged(methodName)) {
				candidates = type.methods(methodName);
			}
			else {
				JavaOverloads.Member member = member(methodName);
				candidates = type.methods(member.name());
				tag = member.tag();
			}
			return JavaOverloads.filterByTag(statics ? JavaOverloads.staticMethods(candidates) : candidates, tag);
		}, args, caller);
		if (overload == null) {
			throw new LispEvalException(
					"No matching method " + type.name() + "." + methodName + " with " + args.size() + " argument(s)");
		}
		try {
			Method method = (Method) executable(overload);
			return unmarshal(method.invoke(receiver, marshalArguments(overload, args, caller)));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("calling " + type.name() + "." + overload.executable().name(), ex);
		}
	}

	// Whether a designator carries a parameter tag (or a stray parenthesis parseMember
	// reports): the untagged common case is never looked up in MEMBERS.
	private static boolean isTagged(String designator) {
		return designator.indexOf('(') >= 0 || designator.indexOf(')') >= 0;
	}

	private static JavaOverloads.Member member(String designator) {
		JavaOverloads.Member cached = MEMBERS.get(designator);
		if (cached == null) {
			try {
				cached = JavaOverloads.parseMember(designator);
			}
			catch (IllegalArgumentException ex) {
				throw new LispEvalException(String.valueOf(ex.getMessage()));
			}
			remember(MEMBERS, designator, cached);
		}
		return cached;
	}

	private static java.lang.reflect.Executable executable(JavaOverloads.Overload overload) {
		return ((ReflectiveJavaClasses.Member) overload.executable()).executable();
	}

	// The overload of `member` on `type` for these arguments: the one remembered for
	// their kinds, otherwise select() over the candidates by argument kind (and
	// remembered). A list or vector has no kind: such a call is costed by marshalling its
	// arguments and never remembered.
	private static JavaOverloads.@Nullable Overload resolve(ReflectiveJavaClasses.Type type, String member,
			Supplier<? extends List<? extends JavaExecutable>> candidates, List<LispVal> args, Caller caller) {
		JavaKind[] kinds = kindsOf(args);
		if (kinds == null) {
			@Nullable Object[] slot = new @Nullable Object[1];
			return JavaOverloads.select(candidates.get(), args.size(),
					(i, target) -> marshal(args.get(i), target, caller, slot, 0));
		}
		JavaOverloads.Overload remembered = remembered(type.type(), member, kinds);
		if (remembered != null) {
			return remembered;
		}
		JavaOverloads.Overload chosen = JavaOverloads.select(candidates.get(), kinds.length,
				(i, target) -> JavaOverloads.kindCost(kinds[i], target, CLASSES));
		if (chosen != null) {
			rememberChoice(type.type(), member, new Memo(kinds, chosen));
		}
		return chosen;
	}

	// The kind of every argument, or null when one has none.
	private static JavaKind @Nullable [] kindsOf(List<LispVal> args) {
		JavaKind[] kinds = new JavaKind[args.size()];
		for (int i = 0; i < kinds.length; i++) {
			JavaKind kind = kindOf(args.get(i));
			if (kind == null) {
				return null;
			}
			kinds[i] = kind;
		}
		return kinds;
	}

	private static JavaOverloads.@Nullable Overload remembered(Class<?> cls, String member, JavaKind[] kinds) {
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
	private static @Nullable JavaKind kindOf(LispVal value) {
		return switch (value) {
			case LispNil ignored -> JavaKind.Lisp.NIL;
			case LispTrue ignored -> JavaKind.Lisp.T;
			case LispInteger ignored -> JavaKind.Lisp.INTEGER;
			case LispDouble ignored -> JavaKind.Lisp.FLOAT;
			case LispString s -> s.value().length() == 1 ? JavaKind.Lisp.STRING_1 : JavaKind.Lisp.STRING;
			case LispChar c ->
				Character.isBmpCodePoint(c.codePoint()) ? JavaKind.Lisp.CHAR : JavaKind.Lisp.SUPPLEMENTARY_CHAR;
			case LispJavaObject obj -> hostKind(obj.ref());
			case LispLambda ignored -> JavaKind.Lisp.FUNCTION;
			case LispFunction ignored -> JavaKind.Lisp.FUNCTION;
			default -> null;
		};
	}

	private static <K, V> void remember(ConcurrentHashMap<K, V> cache, K key, V value) {
		if (cache.size() >= CACHE_LIMIT) {
			cache.clear();
		}
		cache.put(key, value);
	}

	// The Java arguments for the chosen overload, packing a varargs tail.
	private static @Nullable Object[] marshalArguments(JavaOverloads.Overload overload, List<LispVal> args,
			Caller caller) {
		List<? extends JavaType> params = overload.executable().parameterTypes();
		@Nullable Object[] out = new @Nullable Object[params.size()];
		int fixed = overload.packed() ? params.size() - 1 : params.size();
		for (int i = 0; i < fixed; i++) {
			marshalSelected(args.get(i), params.get(i), caller, out, i);
		}
		if (overload.packed()) {
			JavaType component = java.util.Objects.requireNonNull(params.get(fixed).componentType());
			Object packed = Array.newInstance(classOf(component), args.size() - fixed);
			@Nullable Object[] slot = new @Nullable Object[1];
			for (int i = fixed; i < args.size(); i++) {
				marshalSelected(args.get(i), component, caller, slot, 0);
				Array.set(packed, i - fixed, slot[0]);
			}
			out[fixed] = packed;
		}
		return out;
	}

	// select() costed this argument against this type, so it converts -- unless a
	// statically resolved site met a value its declaration did not promise.
	private static void marshalSelected(LispVal value, JavaType target, Caller caller, @Nullable Object[] out,
			int index) {
		if (marshal(value, target, caller, out, index) == NO_MATCH) {
			throw new IllegalStateException("java interop: the selected overload rejects " + value.print());
		}
	}

	private static Class<?> classOf(JavaType type) {
		return ((ReflectiveJavaClasses.Type) type).type();
	}

	// (java:field "class.Name" "CONSTANT") -> static field; (java:field obj "name") ->
	// instance field.
	static LispVal field(LispVal classOrObject, String fieldName) {
		try {
			if (classOrObject instanceof LispString s) {
				ReflectiveJavaClasses.Type type = loadClass(s.value());
				ReflectiveJavaClasses.FieldMember member = type.field(fieldName);
				if (member != null && !member.isStatic()) {
					throw new LispEvalException("java:field: "
							+ am.ik.rontolisp.compiler.JavaSiteResolver.notStatic(type.name(), fieldName));
				}
				Field field = publicField(type, fieldName);
				return unmarshal(field.get(null));
			}
			if (classOrObject instanceof LispJavaObject obj) {
				Field field = publicField(ReflectiveJavaClasses.of(obj.ref().getClass()), fieldName);
				return unmarshal(field.get(obj.ref()));
			}
			throw new LispEvalException(
					"java:field expects a class-name string or a java object, got " + classOrObject.print());
		}
		catch (ReflectiveOperationException ex) {
			throw fail("reading field " + fieldName, ex);
		}
	}

	/**
	 * Runs a site resolved before it ran ({@code compiler.JavaSiteResolver}), exactly as
	 * a compiled program's direct call does ({@code codegen.jvm.JvmJavaDirectSites}): a
	 * {@code java:call} / {@code java:field} receiver must be a java object and an
	 * instance of the site's static class; each argument must be what the resolution
	 * counted on -- one of its kinds, or {@code nil} or an instance of its bound; then
	 * the member is called -- the resolved one, or at a dispatched site the cheapest of
	 * the site's overloads for the arguments' kinds, the first on a tie
	 * ({@link JavaOverloads#selectRanked}) -- with each argument converted to its
	 * parameter type (a varargs tail packed as the overload packs it). A value a
	 * declaration lied about is an error here, never converted for a member it was not
	 * chosen for.
	 * @param site the resolved site
	 * @param receiver the {@code java:call} / {@code java:field} receiver, or
	 * {@code null}
	 * @param args the argument values, the names written at the site left out
	 * @param caller applies a Lisp callable a proxy argument calls back
	 * @return the member's value
	 */
	static LispVal invokeResolved(JavaSite site, @Nullable LispVal receiver, List<LispVal> args, Caller caller) {
		String className = java.util.Objects.requireNonNull(site.staticClass());
		String operator = "java:" + site.operator().name().toLowerCase(java.util.Locale.ROOT);
		Object target = null;
		if (receiver != null) {
			boolean call = site.operator() == JavaSite.Operator.CALL;
			if (!(receiver instanceof LispJavaObject obj)) {
				throw new LispEvalException(
						call ? "java:call expects a java object as the first argument, got " + receiver.print()
								: "java:field expects a class-name string or a java object, got " + receiver.print());
			}
			if (!loadClass(className).type().isInstance(obj.ref())) {
				throw new LispEvalException(operator + ": the " + (call ? "receiver" : "object") + " is not a "
						+ className + ", got " + receiver.print());
			}
			target = obj.ref();
		}
		JavaField resolvedField = site.field();
		if (resolvedField != null) {
			try {
				return unmarshal(((ReflectiveJavaClasses.FieldMember) resolvedField).field().get(target));
			}
			catch (ReflectiveOperationException ex) {
				throw fail("reading field " + resolvedField.name(), ex);
			}
		}
		List<JavaSite.Argument> promised = site.arguments();
		for (int i = 0; i < args.size(); i++) {
			if (!keepsPromise(args.get(i), promised.get(i))) {
				throw new LispEvalException(operator + ": argument " + (i + 1) + " is not " + promised.get(i).expected()
						+ ", got " + args.get(i).print());
			}
		}
		JavaOverloads.Overload overload;
		if (site.dispatched()) {
			overload = dispatch(site, args, caller);
			if (overload == null) {
				String designator = java.util.Objects.requireNonNull(site.designator());
				throw new LispEvalException(site.operator() == JavaSite.Operator.NEW
						? "No matching constructor for " + designator + " with " + args.size() + " argument(s)"
						: "No matching method " + className + "." + designator + " with " + args.size()
								+ " argument(s)");
			}
		}
		else {
			overload = new JavaOverloads.Overload(java.util.Objects.requireNonNull(site.executable()), site.packed());
		}
		JavaExecutable executable = overload.executable();
		@Nullable Object[] javaArgs = marshalArguments(overload, args, caller);
		try {
			java.lang.reflect.Executable reflected = ((ReflectiveJavaClasses.Member) executable).executable();
			if (reflected instanceof Constructor<?> constructor) {
				return unmarshal(constructor.newInstance(javaArgs));
			}
			return unmarshal(((Method) reflected).invoke(target, javaArgs));
		}
		catch (ReflectiveOperationException ex) {
			throw fail(executable.isConstructor() ? "constructing " + className
					: "calling " + className + "." + executable.name(), ex);
		}
	}

	// The overload of a dispatched site for these arguments: the first cheapest of its
	// ranked overloads, remembered per site for the argument kinds. A list or vector has
	// no kind: such a call is costed by marshalling its arguments and never remembered.
	private static JavaOverloads.@Nullable Overload dispatch(JavaSite site, List<LispVal> args, Caller caller) {
		JavaKind[] kinds = kindsOf(args);
		if (kinds == null) {
			@Nullable Object[] slot = new @Nullable Object[1];
			return JavaOverloads.selectRanked(site.overloads(), args.size(), (i, type) -> {
				JavaKind kind = kindOf(args.get(i));
				return kind != null ? JavaOverloads.kindCost(kind, type, CLASSES)
						: marshal(args.get(i), type, caller, slot, 0);
			});
		}
		SiteKey key = new SiteKey(site);
		Memo[] memos = DISPATCHES.get(key);
		if (memos != null) {
			for (Memo memo : memos) {
				if (memo.matches(kinds)) {
					return memo.overload();
				}
			}
		}
		JavaOverloads.Overload chosen = JavaOverloads.selectRanked(site.overloads(), kinds.length,
				(i, type) -> JavaOverloads.kindCost(kinds[i], type, CLASSES));
		if (chosen != null) {
			Memo[] grown;
			if (memos == null || memos.length >= CHOICES_PER_MEMBER) {
				grown = new Memo[] { new Memo(kinds, chosen) };
			}
			else {
				grown = Arrays.copyOf(memos, memos.length + 1);
				grown[memos.length] = new Memo(kinds, chosen);
			}
			remember(DISPATCHES, key, grown);
		}
		return chosen;
	}

	// Whether a value is what a resolved site counted on for its argument: one of the
	// kinds, nil or an instance of the bound, or -- known only when it runs -- anything.
	private static boolean keepsPromise(LispVal value, JavaSite.Argument argument) {
		if (argument.known()) {
			JavaKind kind = kindOf(value);
			return kind != null && argument.kinds().contains(kind);
		}
		String bound = argument.bound();
		if (bound == null || value instanceof LispNil) {
			return true;
		}
		return value instanceof LispJavaObject obj && loadClass(bound).type().isInstance(obj.ref());
	}

	private static Field publicField(ReflectiveJavaClasses.Type type, String fieldName) throws NoSuchFieldException {
		ReflectiveJavaClasses.FieldMember field = type.field(fieldName);
		if (field == null) {
			throw new NoSuchFieldException(fieldName);
		}
		return field.field();
	}

	// (java:proxy "fully.qualified.Interface" callable): the callable is applied as
	// (callable method-name arg1 arg2 ...) for every interface method, default ones too;
	// Object's equals/hashCode/toString keep identity behavior
	// (compiler/JavaImplementations.proxy, which a compiled program's generated class
	// declares).
	static LispVal proxy(String interfaceName, LispVal callable, Caller caller) {
		ReflectiveJavaClasses.Type type = loadClass(interfaceName);
		if (!type.isInterface()) {
			throw new LispEvalException(JavaImplementations.notAnInterface(true, interfaceName));
		}
		List<Object> key = List.of(type.type(), PROXY_KEY);
		Dispatch dispatch = IMPLEMENTATIONS.get(key);
		if (dispatch == null) {
			dispatch = new Dispatch(JavaImplementations.proxy(type, CLASSES));
			remember(IMPLEMENTATIONS, key, dispatch);
		}
		return implement(dispatch, List.of(callable), caller);
	}

	// (java:reify "fully.qualified.Interface" "method" function ...): each function
	// implements the one method its designator names (compiler/JavaImplementations.reify
	// -- the rule a compiled program's generated class follows).
	static LispVal reify(List<LispVal> args, Caller caller) {
		if (args.isEmpty() || args.size() % 2 == 0 || !(args.get(0) instanceof LispString interfaceName)) {
			throw new LispEvalException(JavaImplementations.REIFY_USAGE);
		}
		List<String> designators = new ArrayList<>();
		List<LispVal> functions = new ArrayList<>();
		for (int i = 1; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispString designator)) {
				throw new LispEvalException(JavaImplementations.REIFY_USAGE);
			}
			designators.add(designator.value());
			functions.add(args.get(i + 1));
		}
		ReflectiveJavaClasses.Type type = loadClass(interfaceName.value());
		if (!type.isInterface()) {
			throw new LispEvalException(JavaImplementations.notAnInterface(false, interfaceName.value()));
		}
		List<Object> key = List.of(type.type(), designators);
		Dispatch dispatch = IMPLEMENTATIONS.get(key);
		if (dispatch == null) {
			try {
				dispatch = new Dispatch(JavaImplementations.reify(type, designators, CLASSES));
			}
			catch (IllegalArgumentException ex) {
				throw new LispEvalException(String.valueOf(ex.getMessage()));
			}
			remember(IMPLEMENTATIONS, key, dispatch);
		}
		return implement(dispatch, functions, caller);
	}

	// The designators-list stand-in of a java:proxy's key.
	private static final String PROXY_KEY = "proxy";

	// How a java:proxy of an interface class, or a java:reify of (interface class,
	// designators), implements it: resolved once.
	private static final ConcurrentHashMap<List<Object>, Dispatch> IMPLEMENTATIONS = new ConcurrentHashMap<>();

	// The object: a Proxy whose handler dispatches on the implementation's slots.
	private static LispVal implement(Dispatch dispatch, List<LispVal> functions, Caller caller) {
		Class<?> iface = ((ReflectiveJavaClasses.Type) java.util.Objects
			.requireNonNull(dispatch.implementation.iface())).type();
		return new LispJavaObject(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
				new ImplementationHandler(dispatch, functions, caller)));
	}

	/**
	 * Which slot of a {@code java:reify} / {@code java:proxy} implementation an invoked
	 * method is, remembered per method (a {@code Proxy} class passes the same
	 * {@code Method} objects on every call).
	 */
	private static final class Dispatch {

		// Codes beside a slot's implementation index (NONE: an abstract method no
		// function implements).
		static final int HASH_CODE = -2;

		static final int EQUALS = -3;

		static final int TO_STRING = -4;

		static final int DEFAULT = -5;

		final JavaImplementation implementation;

		final JavaImplementationType kind;

		final String ifaceName;

		private final java.util.Map<String, Integer> slots = new java.util.HashMap<>();

		private final ConcurrentHashMap<Method, Integer> byMethod = new ConcurrentHashMap<>();

		Dispatch(JavaImplementation implementation) {
			this.implementation = implementation;
			JavaType iface = java.util.Objects.requireNonNull(implementation.iface());
			this.kind = CLASSES.implementationOf(iface);
			this.ifaceName = iface.name();
			for (JavaImplementation.Slot slot : implementation.slots()) {
				this.slots.put(slot.dispatchKey(), slot.implementation());
			}
		}

		int indexOf(Method method) {
			Integer cached = this.byMethod.get(method);
			if (cached == null) {
				String key = keyOf(method);
				Integer slot = this.slots.get(key + method.getReturnType().getName());
				if (slot != null) {
					cached = slot;
				}
				else {
					cached = switch (key) {
						case "hashCode()" -> HASH_CODE;
						case "equals(java.lang.Object)" -> EQUALS;
						case "toString()" -> TO_STRING;
						default -> method.isDefault() ? DEFAULT : JavaImplementation.NONE;
					};
				}
				this.byMethod.put(method, cached);
			}
			return cached;
		}

		static String keyOf(Method method) {
			Class<?>[] params = method.getParameterTypes();
			List<ReflectiveJavaClasses.Type> types = new ArrayList<>(params.length);
			for (Class<?> param : params) {
				types.add(ReflectiveJavaClasses.of(param));
			}
			return JavaImplementation.key(method.getName(), types);
		}

	}

	/**
	 * The handler of a {@code java:reify} / {@code java:proxy} object: a method a slot
	 * declares calls its function -- a proxy's with the method's name first -- with the
	 * arguments unmarshalled, and its value is marshalled to the method's return type (a
	 * function is never made a proxy there); an abstract method no function implements
	 * throws; a default method no slot overrides runs its body; {@code Object}'s three
	 * keep their identity behavior. What a compiled program's generated class does,
	 * method for method.
	 */
	private static final class ImplementationHandler implements InvocationHandler {

		private final Dispatch dispatch;

		private final List<LispVal> functions;

		private final Caller caller;

		ImplementationHandler(Dispatch dispatch, List<LispVal> functions, Caller caller) {
			this.dispatch = dispatch;
			this.functions = List.copyOf(functions);
			this.caller = caller;
		}

		@Override
		public @Nullable Object invoke(Object p, Method method, @Nullable Object @Nullable [] methodArgs)
				throws Throwable {
			int index = this.dispatch.indexOf(method);
			switch (index) {
				case Dispatch.HASH_CODE -> {
					return System.identityHashCode(p);
				}
				case Dispatch.EQUALS -> {
					return p == (methodArgs == null ? null : methodArgs[0]);
				}
				case Dispatch.TO_STRING -> {
					return this.dispatch.implementation.defaultToString();
				}
				case Dispatch.DEFAULT -> {
					return InvocationHandler.invokeDefault(p, method, methodArgs);
				}
				case JavaImplementation.NONE -> throw new UnsupportedOperationException(
						JavaImplementation.noImplementation(this.dispatch.ifaceName, Dispatch.keyOf(method)));
				default -> {
				}
			}
			boolean proxy = this.dispatch.implementation.proxy();
			List<LispVal> callArgs = new ArrayList<>();
			if (proxy) {
				callArgs.add(new LispString(method.getName()));
			}
			if (methodArgs != null) {
				for (Object a : methodArgs) {
					callArgs.add(unmarshal(a));
				}
			}
			LispVal result = this.caller.call(this.functions.get(index), callArgs);
			Class<?> ret = method.getReturnType();
			if (ret == void.class) {
				return null;
			}
			@Nullable Object[] slot = new @Nullable Object[1];
			ReflectiveJavaClasses.Type returnType = ReflectiveJavaClasses.of(ret);
			if (marshal(result, returnType, this.caller, slot, 0, false) == NO_MATCH) {
				throw new LispEvalException(
						JavaImplementation.returnMismatchPrefix(proxy) + result.print() + JavaImplementation
							.returnMismatchSuffix(proxy, this.dispatch.ifaceName, method.getName(), returnType));
			}
			return slot[0];
		}

	}

	// The kind of a host object: a java:reify / java:proxy object's is its interface's
	// implementation type, whatever Proxy class made it (as a compiled program's
	// generated class is); any other object's its exact class.
	private static JavaKind hostKind(Object ref) {
		Class<?> type = ref.getClass();
		if (Proxy.isProxyClass(type) && Proxy.getInvocationHandler(ref) instanceof ImplementationHandler handler) {
			return handler.dispatch.kind;
		}
		return ReflectiveJavaClasses.of(type);
	}

	// Writes the Java value for `value` (assignable to `target`) into out[index] and
	// returns its conversion cost, or NO_MATCH (writing nothing) if it cannot convert. A
	// value with a kind is costed by kindCost -- the one cost table -- and converted by
	// convert(); a list or vector element-wise. A function becomes a proxy of an
	// interface: an argument's conversion.
	private static int marshal(LispVal value, JavaType target, Caller caller, @Nullable Object[] out, int index) {
		return marshal(value, target, caller, out, index, true);
	}

	// With proxies false, a function converts to nothing: the conversion of a value a
	// java:reify / java:proxy function answers to Java, which -- as in Clojure -- never
	// coerces a function (return a java:reify or java:proxy object instead). A compiled
	// program's generated class converts its functions' values the same way
	// (codegen.jvm.JvmJavaMarshal).
	private static int marshal(LispVal value, JavaType target, Caller caller, @Nullable Object[] out, int index,
			boolean proxies) {
		JavaKind kind = kindOf(value);
		if (kind != null) {
			if (!proxies && kind == JavaKind.Lisp.FUNCTION) {
				return NO_MATCH;
			}
			int cost = JavaOverloads.kindCost(kind, target, CLASSES);
			if (cost != NO_MATCH) {
				out[index] = convert(value, classOf(target), caller);
			}
			return cost;
		}
		switch (value) {
			case LispCons cons -> {
				List<LispVal> elements = properListElements(cons);
				if (elements == null) {
					return NO_MATCH; // a dotted (improper) list is not a sequence
				}
				return marshalSequence(elements, target, caller, out, index, proxies);
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
				return marshalSequence(elements, target, caller, out, index, proxies);
			}
			default -> {
				return NO_MATCH; // symbol, hash-table, ... are not bridged
			}
		}
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
	private static int marshalSequence(List<LispVal> elements, JavaType target, Caller caller, @Nullable Object[] out,
			int index, boolean proxies) {
		@Nullable Object[] slot = new @Nullable Object[1];
		JavaType component = target.componentType();
		if (component != null) {
			Object array = Array.newInstance(classOf(component), elements.size());
			int total = JavaOverloads.COST_CONVERT;
			for (int i = 0; i < elements.size(); i++) {
				int cost = marshal(elements.get(i), component, caller, slot, 0, proxies);
				if (cost == NO_MATCH) {
					return NO_MATCH;
				}
				total += cost;
				Array.set(array, i, slot[0]);
			}
			out[index] = array;
			return total;
		}
		if (classOf(target).isAssignableFrom(ArrayList.class)) {
			List<@Nullable Object> list = new ArrayList<>(elements.size());
			int total = JavaOverloads.COST_BOXED;
			JavaType object = ReflectiveJavaClasses.of(Object.class);
			for (LispVal element : elements) {
				int cost = marshal(element, object, caller, slot, 0, proxies);
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
			// A Java BigInteger is a Lisp integer (a fixnum when it fits), as compiled:
			// the
			// compiled representation cannot tell it from a bignum.
			case BigInteger b -> b.bitLength() < 64 ? new LispInteger(b.longValue()) : new LispBigInteger(b);
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

	private static ReflectiveJavaClasses.Type loadClass(String name) {
		ReflectiveJavaClasses.Type type = CLASSES.find(name);
		if (type == null || type.isPrimitive() || type.isArray()) {
			throw new LispEvalException("No such class: " + name);
		}
		return type;
	}

	private static LispEvalException fail(String what, ReflectiveOperationException ex) {
		Throwable cause = ex instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : ex;
		return new LispEvalException("error " + what + ": " + cause);
	}

}
