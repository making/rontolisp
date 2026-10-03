package am.ik.rontolisp.eval;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayDeque;
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
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispIntVector;
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

	// What functions called back from Java raised on this thread and no site has passed
	// on yet, newest first (at most JavaImplementations.PENDING_SIGNALS): a Java call
	// that throws one of them throws it on unchanged (fail). A compiled program keeps
	// the same record (codegen.jvm.JvmJavaDirectSites' _jsig / _jfail).
	private static final ThreadLocal<ArrayDeque<Throwable>> RAISED = new ThreadLocal<>();

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
		Object receiver = receiverObject(target, caller);
		if (receiver == null) {
			throw new LispEvalException("java:call expects a java object as the first argument, got " + target.print());
		}
		return invoke(ReflectiveJavaClasses.of(receiver.getClass()), receiver, methodName, args, caller);
	}

	/**
	 * The object a {@code java:call} is made on: a host object's own, or the one a Lisp
	 * value of a receiver kind ({@link JavaOverloads#isReceiverKind}) converts to for an
	 * {@code Object} parameter -- a string's {@code String}, an integer's narrowest box.
	 * @return the object, or {@code null} when the value is neither
	 */
	private static @Nullable Object receiverObject(LispVal target, Caller caller) {
		if (target instanceof LispJavaObject obj) {
			return obj.ref();
		}
		if (kindOf(target) instanceof JavaKind.Lisp kind && JavaOverloads.isReceiverKind(kind)) {
			return convert(target, Object.class, caller);
		}
		return null;
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
			case LispBigInteger ignored -> JavaKind.Lisp.BIGNUM;
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
			target = call ? receiverObject(receiver, caller)
					: receiver instanceof LispJavaObject obj ? obj.ref() : null;
			if (target == null) {
				throw new LispEvalException(
						call ? "java:call expects a java object as the first argument, got " + receiver.print()
								: "java:field expects a class-name string or a java object, got " + receiver.print());
			}
			if (!loadClass(className).type().isInstance(target)) {
				throw new LispEvalException(operator + ": the " + (call ? "receiver" : "object") + " is not a "
						+ className + ", got " + receiver.print());
			}
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
		return proxy(List.of(interfaceName), callable, caller);
	}

	// (java:proxy "I" "J" ... callable): one object implementing every interface, each
	// method of each calling the callable by its name.
	static LispVal proxy(List<String> interfaceNames, LispVal callable, Caller caller) {
		List<JavaType> types = new ArrayList<>();
		List<Class<?>> classes = new ArrayList<>();
		for (String interfaceName : interfaceNames) {
			ReflectiveJavaClasses.Type type = loadClass(interfaceName);
			if (!type.isInterface()) {
				throw new LispEvalException(JavaImplementations.notAnInterface(true, interfaceName));
			}
			if (types.contains(type)) {
				throw new LispEvalException(JavaImplementations.repeatedInterface(interfaceName));
			}
			types.add(type);
			classes.add(type.type());
		}
		List<Object> key = List.of(classes, PROXY_KEY);
		Dispatch dispatch = IMPLEMENTATIONS.get(key);
		if (dispatch == null) {
			dispatch = new Dispatch(JavaImplementations.proxy(types, CLASSES));
			remember(IMPLEMENTATIONS, key, dispatch);
		}
		return implement(dispatch, List.of(callable), caller);
	}

	// (java:subclass "super.Class" (iface...) (methods...) ctor-args... callable): one
	// object extending the superclass (and the extra interfaces), each named method
	// calling the callable with the object and the method's name before its arguments
	// (compiler/JavaImplementations.subclass, which a compiled program's generated
	// class declares).
	static LispVal subclass(List<LispVal> args, Caller caller) {
		if (args.size() < 4 || !(args.get(0) instanceof LispString superName)) {
			throw new LispEvalException(JavaImplementations.SUBCLASS_USAGE);
		}
		List<String> interfaceNames = stringList(args.get(1));
		List<String> methodNames = stringList(args.get(2));
		if (interfaceNames == null || methodNames == null) {
			throw new LispEvalException(JavaImplementations.SUBCLASS_USAGE);
		}
		List<LispVal> ctorArgs = args.subList(3, args.size() - 1);
		LispVal callable = args.get(args.size() - 1);
		ReflectiveJavaClasses.Type superclass = loadClass(superName.value());
		if (superclass.isInterface()) {
			throw new LispEvalException(JavaImplementations.notAClass(superName.value()));
		}
		if (superclass.isFinal()) {
			throw new LispEvalException(JavaImplementations.finalSuperclass(superName.value()));
		}
		List<JavaType> interfaces = new ArrayList<>();
		List<Class<?>> ifaceClasses = new ArrayList<>();
		for (String interfaceName : interfaceNames) {
			ReflectiveJavaClasses.Type type = loadClass(interfaceName);
			if (!type.isInterface()) {
				throw new LispEvalException(JavaImplementations.subclassNotAnInterface(interfaceName));
			}
			if (interfaces.contains(type)) {
				throw new LispEvalException(JavaImplementations.subclassRepeatedInterface(interfaceName));
			}
			interfaces.add(type);
			ifaceClasses.add(type.type());
		}
		List<Object> key = List.of(superclass.type(), ifaceClasses, methodNames);
		SubclassDispatch dispatch = SUBCLASSES.get(key);
		if (dispatch == null) {
			JavaImplementation implementation;
			try {
				implementation = JavaImplementations.subclass(superclass, interfaces, methodNames);
			}
			catch (IllegalArgumentException ex) {
				throw new LispEvalException(String.valueOf(ex.getMessage()));
			}
			dispatch = new SubclassDispatch(implementation, CLASSES.subclassOf(superclass, interfaces));
			remember(SUBCLASSES, key, dispatch);
		}
		JavaOverloads.Overload overload = selectConstructor(superclass, ctorArgs, caller);
		if (overload == null) {
			throw new LispEvalException(
					"No matching constructor for " + superName.value() + " with " + ctorArgs.size() + " argument(s)");
		}
		Constructor<?> constructor = (Constructor<?>) ((ReflectiveJavaClasses.Member) overload.executable())
			.executable();
		Class<?> proxyClass = ClassProxyMaker.proxyClass(superclass.type(), ifaceClasses, dispatch.implementation,
				constructor);
		SUBCLASS_KINDS.putIfAbsent(proxyClass, dispatch.kind);
		@Nullable Object[] javaArgs = marshalArguments(overload, ctorArgs, caller);
		try {
			Constructor<?> proxyConstructor = proxyConstructor(proxyClass, constructor);
			Object[] withHandler = new Object[javaArgs.length + 1];
			withHandler[0] = new SubclassHandler(dispatch, callable, caller);
			System.arraycopy(javaArgs, 0, withHandler, 1, javaArgs.length);
			return unmarshal(proxyConstructor.newInstance(withHandler));
		}
		catch (ReflectiveOperationException ex) {
			throw fail("constructing " + superName.value(), ex);
		}
	}

	// A proper list of strings as its values, or null when the value is not one.
	private static @Nullable List<String> stringList(LispVal value) {
		List<String> names = new ArrayList<>();
		LispVal current = value;
		while (current instanceof LispCons cons) {
			if (!(cons.car() instanceof LispString name)) {
				return null;
			}
			names.add(name.value());
			current = cons.cdr();
		}
		return current instanceof LispNil ? names : null;
	}

	// The superclass constructor for these constructor arguments, over the public and
	// the protected ones: never remembered (the candidates differ from java:new's
	// public-only ones, and construction is rare).
	private static JavaOverloads.@Nullable Overload selectConstructor(ReflectiveJavaClasses.Type superclass,
			List<LispVal> args, Caller caller) {
		List<ReflectiveJavaClasses.Member> candidates = new ArrayList<>();
		for (JavaExecutable executable : superclass.subclassConstructors()) {
			candidates.add((ReflectiveJavaClasses.Member) executable);
		}
		JavaKind[] kinds = kindsOf(args);
		if (kinds == null) {
			@Nullable Object[] slot = new @Nullable Object[1];
			return JavaOverloads.select(candidates, args.size(),
					(i, target) -> marshal(args.get(i), target, caller, slot, 0));
		}
		return JavaOverloads.select(candidates, kinds.length,
				(i, target) -> JavaOverloads.kindCost(kinds[i], target, CLASSES));
	}

	// The generated constructor matching this superclass constructor: the handler
	// first, then the same parameters.
	private static Constructor<?> proxyConstructor(Class<?> proxyClass, Constructor<?> constructor)
			throws NoSuchMethodException {
		Class<?>[] params = constructor.getParameterTypes();
		Class<?>[] proxyParams = new Class<?>[params.length + 1];
		proxyParams[0] = ClassProxyHandler.class;
		System.arraycopy(params, 0, proxyParams, 1, params.length);
		return proxyClass.getConstructor(proxyParams);
	}

	// How a java:subclass of (superclass, interfaces, methods) extends it: computed
	// once, the kind canonical per (superclass, interfaces).
	private static final class SubclassDispatch {

		final JavaImplementation implementation;

		final JavaImplementationType kind;

		SubclassDispatch(JavaImplementation implementation, JavaImplementationType kind) {
			this.implementation = implementation;
			this.kind = kind;
		}

	}

	private static final ConcurrentHashMap<List<Object>, SubclassDispatch> SUBCLASSES = new ConcurrentHashMap<>();

	// The kind of a generated subclass, by its class.
	private static final ConcurrentHashMap<Class<?>, JavaImplementationType> SUBCLASS_KINDS = new ConcurrentHashMap<>();

	/**
	 * The handler of a {@code java:subclass} object: a named method calls the callable
	 * with the object and the method's name before its unmarshalled arguments, and its
	 * value is marshalled to the method's return type (a function is never made a proxy
	 * there). What the function raises -- or the refusal of its value -- is recorded on
	 * its way out to the Java caller ({@link #raised}), so the site whose Java call it
	 * reaches throws it on unchanged.
	 */
	private static final class SubclassHandler implements ClassProxyHandler {

		private final SubclassDispatch dispatch;

		private final LispVal callable;

		private final Caller caller;

		SubclassHandler(SubclassDispatch dispatch, LispVal callable, Caller caller) {
			this.dispatch = dispatch;
			this.callable = callable;
			this.caller = caller;
		}

		@Override
		public @Nullable Object invoke(int slot, Object self, Object[] methodArgs) throws Throwable {
			JavaImplementation.Slot implemented = this.dispatch.implementation.slots().get(slot);
			try {
				List<LispVal> callArgs = new ArrayList<>(methodArgs.length + 2);
				callArgs.add(new LispJavaObject(self));
				callArgs.add(new LispString(implemented.name()));
				for (Object argument : methodArgs) {
					callArgs.add(unmarshal(argument));
				}
				LispVal result = this.caller.call(this.callable, callArgs);
				Class<?> ret = returnClass(implemented);
				if (ret == void.class) {
					return null;
				}
				@Nullable Object[] boxed = new @Nullable Object[1];
				ReflectiveJavaClasses.Type returnType = ReflectiveJavaClasses.of(ret);
				if (marshal(result, returnType, this.caller, boxed, 0, false) == NO_MATCH) {
					JavaImplementation implementation = this.dispatch.implementation;
					throw new LispEvalException(JavaImplementation.subclassReturnMismatchPrefix() + result.print()
							+ JavaImplementation.subclassReturnMismatchSuffix(
									java.util.Objects.requireNonNull(implementation.superclass()),
									implementation.interfaces(), returnType));
				}
				return boxed[0];
			}
			catch (Throwable signal) {
				throw raised(signal);
			}
		}

		// The method's return class: the slot's return type is canonical in this
		// lookup, so its class is read off it.
		private static Class<?> returnClass(JavaImplementation.Slot slot) {
			JavaType type = slot.returnType();
			if (type instanceof ReflectiveJavaClasses.Type reflective) {
				return reflective.type();
			}
			try {
				return Class.forName(type.name(), false, JavaInterop.class.getClassLoader());
			}
			catch (ClassNotFoundException ex) {
				throw new LispEvalException("No such class: " + type.name());
			}
		}

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
		List<JavaType> types = dispatch.implementation.interfaces();
		Class<?>[] interfaces = new Class<?>[types.size()];
		for (int i = 0; i < interfaces.length; i++) {
			interfaces[i] = ((ReflectiveJavaClasses.Type) types.get(i)).type();
		}
		return new LispJavaObject(Proxy.newProxyInstance(proxyLoader(interfaces), interfaces,
				new ImplementationHandler(dispatch, functions, caller)));
	}

	// The loader a Proxy class over the interfaces is defined in: the first of theirs
	// that
	// sees every one of them.
	private static @Nullable ClassLoader proxyLoader(Class<?>[] interfaces) {
		for (Class<?> candidate : interfaces) {
			ClassLoader loader = candidate.getClassLoader();
			if (seesAll(loader, interfaces)) {
				return loader;
			}
		}
		return interfaces[0].getClassLoader();
	}

	private static boolean seesAll(@Nullable ClassLoader loader, Class<?>[] interfaces) {
		for (Class<?> iface : interfaces) {
			try {
				if (Class.forName(iface.getName(), false, loader) != iface) {
					return false;
				}
			}
			catch (ClassNotFoundException ex) {
				return false;
			}
		}
		return true;
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
			this.kind = CLASSES.implementationOf(implementation.interfaces());
			this.ifaceName = implementation.interfaceNames();
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
	 * method for method. What the function raises -- or the refusal of its value -- is
	 * recorded on its way out to the Java caller ({@link #raised}), so the site whose
	 * Java call it reaches throws it on unchanged.
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
			try {
				return call(index, method, methodArgs);
			}
			catch (Throwable signal) {
				throw raised(signal);
			}
		}

		private @Nullable Object call(int index, Method method, @Nullable Object @Nullable [] methodArgs) {
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
	// generated class is); a java:subclass object's its superclass and interfaces'
	// subclass type, whatever generated subclass made it; any other object's its exact
	// class.
	private static JavaKind hostKind(Object ref) {
		Class<?> type = ref.getClass();
		if (Proxy.isProxyClass(type) && Proxy.getInvocationHandler(ref) instanceof ImplementationHandler handler) {
			return handler.dispatch.kind;
		}
		JavaImplementationType subclass = SUBCLASS_KINDS.get(type);
		if (subclass != null) {
			return subclass;
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
	// (codegen.jvm.JvmJavaDirectSites#returnedConvert).
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
			case LispFloatArray array -> {
				if (array.dims().length != 1) {
					return NO_MATCH;
				}
				// A packed float vector's elements are the floats aref reads (every width
				// widened to a double), as a compiled program's _jseq reads them.
				int count = array.dims()[0];
				List<LispVal> elements = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					elements.add(new LispDouble(array.elementAt(i)));
				}
				return marshalSequence(elements, target, caller, out, index, proxies);
			}
			case LispIntVector vector -> {
				// A packed (unsigned-byte 8|16|32) vector's elements, widened unsigned.
				List<LispVal> elements = new ArrayList<>(vector.length());
				for (int i = 0; i < vector.length(); i++) {
					elements.add(new LispInteger(vector.elementAt(i)));
				}
				return marshalSequence(elements, target, caller, out, index, proxies);
			}
			default -> {
				return NO_MATCH; // symbol, ratio, hash-table, ... are not bridged
			}
		}
	}

	// The Java value of a value with a kind, for a target kindCost accepted.
	private static @Nullable Object convert(LispVal value, Class<?> target, Caller caller) {
		return switch (value) {
			case LispNil ignored -> target == boolean.class || target == Boolean.class ? Boolean.FALSE : null;
			case LispTrue ignored -> Boolean.TRUE;
			case LispInteger i -> convertLong(i.value(), target);
			case LispBigInteger b -> b.value(); // a BigInteger or a supertype of it
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
		if (target == BigInteger.class) {
			return BigInteger.valueOf(v);
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

	// What a failed Java call throws: what a function called back from Java raised -- an
	// exit, a condition, uiop:quit -- on to the Lisp code that made the call, as it is;
	// anything else is the error calling the member.
	private static RuntimeException fail(String what, ReflectiveOperationException ex) {
		Throwable cause = ex instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : ex;
		if (passedOn(cause)) {
			if (cause instanceof RuntimeException signal) {
				return signal;
			}
			if (cause instanceof Error error) {
				throw error;
			}
		}
		return new LispEvalException("error " + what + ": " + cause);
	}

	// Records a throwable leaving a function called back from Java; answers it.
	private static Throwable raised(Throwable throwable) {
		ArrayDeque<Throwable> pending = RAISED.get();
		if (pending == null) {
			pending = new ArrayDeque<>();
			RAISED.set(pending);
		}
		pending.addFirst(throwable);
		if (pending.size() > JavaImplementations.PENDING_SIGNALS) {
			pending.removeLast();
		}
		return throwable;
	}

	// Whether a callback raised this very throwable on this thread; if so it is taken off
	// the record with every newer one -- those left their callbacks after it and never
	// reached a site: Java code swallowed them while this one was on its way out.
	private static boolean passedOn(Throwable throwable) {
		ArrayDeque<Throwable> pending = RAISED.get();
		if (pending == null) {
			return false;
		}
		for (Throwable raised : pending) {
			if (raised == throwable) {
				while (pending.pollFirst() != throwable) {
					// newer, swallowed
				}
				return true;
			}
		}
		return false;
	}

}
