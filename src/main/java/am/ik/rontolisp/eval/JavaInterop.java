package am.ik.rontolisp.eval;

import java.lang.ref.WeakReference;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaField;
import am.ik.rontolisp.compiler.JavaImplementation;
import am.ik.rontolisp.compiler.JavaImplementationType;
import am.ik.rontolisp.compiler.JavaImplementations;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaMarkers;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaType;
import am.ik.rontolisp.compiler.ReflectiveJavaClasses;
import am.ik.rontolisp.runtime.RontoJavaBytesView;
import am.ik.rontolisp.runtime.RontoJavaCalls;
import am.ik.rontolisp.runtime.RontoJavaHandle;
import am.ik.rontolisp.runtime.RontoJavaListView;
import am.ik.rontolisp.runtime.RontoJavaMapView;
import am.ik.rontolisp.runtime.RontoJavaNumberHandle;
import am.ik.rontolisp.runtime.RontoJavaSetView;
import am.ik.rontolisp.runtime.RontoJavaValue;
import am.ik.rontolisp.runtime.RontoJavaVectorView;

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
	 * The interpreter as a {@code java:} call sees it: what applies a Lisp function a
	 * proxy calls back, and the classes a class name resolves to.
	 */
	interface Caller {

		LispVal call(LispVal function, List<LispVal> args);

		/**
		 * The classes a class name resolves to: the program's Java class path over the
		 * classes rontolisp runs with.
		 * @return the lookup
		 */
		ReflectiveJavaClasses classes();

		/**
		 * The markers the call ends in ({@link JavaMarkers}): with {@code :functional} a
		 * function argument converted to an interface implements it by its arguments
		 * ({@link JavaImplementations#functional}), not as a {@code java:proxy}; with
		 * {@code :java-false} Java's {@code false} comes back as {@code |false|}.
		 * @return the markers
		 */
		default JavaMarkers markers() {
			return JavaMarkers.NONE;
		}

	}

	// The caller of a call ending in these markers.
	static Caller withMarkers(Caller caller, JavaMarkers markers) {
		if (caller.markers().equals(markers)) {
			return caller;
		}
		return new Caller() {

			@Override
			public LispVal call(LispVal function, List<LispVal> args) {
				return caller.call(function, args);
			}

			@Override
			public ReflectiveJavaClasses classes() {
				return caller.classes();
			}

			@Override
			public JavaMarkers markers() {
				return markers;
			}

		};
	}

	// How many evaluated arguments ending the list are markers
	// (compiler/JavaMarkers): the arguments a member takes are the ones before.
	private static int markerCount(List<LispVal> args) {
		return JavaMarkers.count(args, 0);
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

	static LispVal newInstance(String classDesignator, List<LispVal> rawArgs, Caller caller) {
		int markers = markerCount(rawArgs);
		if (markers > 0) {
			return newInstance(classDesignator, rawArgs.subList(0, rawArgs.size() - markers),
					withMarkers(caller, JavaMarkers.of(rawArgs, 0)));
		}
		List<LispVal> args = hostArguments(rawArgs, caller);
		boolean tagged = isTagged(classDesignator);
		String name = tagged ? member(classDesignator).name() : classDesignator;
		ReflectiveJavaClasses.Type type = loadClass(name, caller);
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
			return unmarshal(constructor.newInstance(marshalArguments(overload, args, caller)), caller);
		}
		catch (ReflectiveOperationException ex) {
			throw fail("constructing " + name, ex);
		}
	}

	static LispVal callInstance(LispVal target, String methodName, List<LispVal> args, Caller caller) {
		// A condition standing for a host exception is called as that exception, as a
		// member takes it; the refusal shows the value handed in.
		Object receiver = receiverObject(hostException(target, caller));
		if (receiver == null) {
			throw new LispEvalException("java:call expects a java object as the first argument, got " + target.print());
		}
		return invoke(ReflectiveJavaClasses.of(receiver.getClass()), receiver, methodName, args, caller);
	}

	static LispVal callStatic(String className, String methodName, List<LispVal> args, Caller caller) {
		return invoke(loadClass(className, caller), null, methodName, args, caller);
	}

	// A static call chooses among the static methods only (JavaOverloads.staticMethods),
	// so its choices are remembered apart from an instance call's of the same name.
	private static LispVal invoke(ReflectiveJavaClasses.Type type, @Nullable Object receiver, String methodName,
			List<LispVal> rawArgs, Caller caller) {
		int markers = markerCount(rawArgs);
		if (markers > 0) {
			return invoke(type, receiver, methodName, rawArgs.subList(0, rawArgs.size() - markers),
					withMarkers(caller, JavaMarkers.of(rawArgs, 0)));
		}
		List<LispVal> args = hostArguments(rawArgs, caller);
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
			return unmarshal(method.invoke(receiver, marshalArguments(overload, args, caller)), caller);
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

	// The object a java:call is made on (LispJavaObject.receiverObject): a :bytes view's
	// byte[] -- what it is to Java (compiled: _jrecv, the bridge's receiverObject) -- or
	// the rule's object.
	private static @Nullable Object receiverObject(LispVal value) {
		return value instanceof LispJavaObject obj && obj.ref() instanceof RontoJavaBytesView view ? view.bytes()
				: LispJavaObject.receiverObject(value);
	}

	// The token of which marshal(value, target) is a pure function for every target, or
	// null when there is none: a list, vector or hash table (the cost sums its elements),
	// a java:view List (an array of its items costs theirs), and the values marshal()
	// never bridges (they never match, so nothing is remembered). A :bytes view has
	// none either: it is the byte[] it hands Java, of no class a host kind names.
	private static @Nullable JavaKind kindOf(LispVal value) {
		return switch (value) {
			case LispJavaObject obj when obj.ref() instanceof RontoJavaListView ignored -> null;
			case LispJavaObject obj when obj.ref() instanceof RontoJavaBytesView ignored -> null;
			case LispNil ignored -> JavaKind.Lisp.NIL;
			case LispTrue ignored -> JavaKind.Lisp.T;
			case LispSymbol symbol when LispNames.JAVA_FALSE.equals(symbol.name()) -> JavaKind.Lisp.FALSE;
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
	static LispVal field(LispVal classOrObject, String fieldName, Caller caller) {
		try {
			if (classOrObject instanceof LispString s) {
				ReflectiveJavaClasses.Type type = loadClass(s.value(), caller);
				ReflectiveJavaClasses.FieldMember member = type.field(fieldName);
				if (member != null && !member.isStatic()) {
					throw new LispEvalException("java:field: "
							+ am.ik.rontolisp.compiler.JavaSiteResolver.notStatic(type.name(), fieldName));
				}
				Field field = publicField(type, fieldName);
				return unmarshal(field.get(null), caller);
			}
			if (classOrObject instanceof LispJavaObject obj) {
				Field field = publicField(ReflectiveJavaClasses.of(obj.ref().getClass()), fieldName);
				return unmarshal(field.get(obj.ref()), caller);
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
			target = call ? receiverObject(receiver) : receiver instanceof LispJavaObject obj ? obj.ref() : null;
			if (target == null) {
				throw new LispEvalException(
						call ? "java:call expects a java object as the first argument, got " + receiver.print()
								: "java:field expects a class-name string or a java object, got " + receiver.print());
			}
			if (!loadClass(className, caller).type().isInstance(target)) {
				throw new LispEvalException(operator + ": the " + (call ? "receiver" : "object") + " is not a "
						+ className + ", got " + receiver.print());
			}
		}
		JavaField resolvedField = site.field();
		if (resolvedField != null) {
			try {
				return unmarshal(((ReflectiveJavaClasses.FieldMember) resolvedField).field().get(target), caller);
			}
			catch (ReflectiveOperationException ex) {
				throw fail("reading field " + resolvedField.name(), ex);
			}
		}
		List<JavaSite.Argument> promised = site.arguments();
		for (int i = 0; i < args.size(); i++) {
			if (!keepsPromise(args.get(i), promised.get(i), caller)) {
				throw new LispEvalException(operator + ": argument " + (i + 1) + " is not " + promised.get(i).expected()
						+ ", got " + args.get(i).print());
			}
		}
		// What the checks above show is the value the site was handed; what the member
		// takes, a condition standing for a host exception as that exception.
		List<LispVal> taken = hostArguments(args, caller);
		JavaOverloads.Overload overload;
		if (site.dispatched()) {
			overload = dispatch(site, taken, caller);
			if (overload == null) {
				String designator = java.util.Objects.requireNonNull(site.designator());
				throw new LispEvalException(site.operator() == JavaSite.Operator.NEW
						? "No matching constructor for " + designator + " with " + taken.size() + " argument(s)"
						: "No matching method " + className + "." + designator + " with " + taken.size()
								+ " argument(s)");
			}
		}
		else {
			overload = new JavaOverloads.Overload(java.util.Objects.requireNonNull(site.executable()), site.packed());
		}
		JavaExecutable executable = overload.executable();
		@Nullable Object[] javaArgs = marshalArguments(overload, taken, caller);
		try {
			java.lang.reflect.Executable reflected = ((ReflectiveJavaClasses.Member) executable).executable();
			if (reflected instanceof Constructor<?> constructor) {
				return unmarshal(constructor.newInstance(javaArgs), caller);
			}
			return unmarshal(((Method) reflected).invoke(target, javaArgs), caller);
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
	private static boolean keepsPromise(LispVal value, JavaSite.Argument argument, Caller caller) {
		if (argument.known()) {
			JavaKind kind = kindOf(value);
			return kind != null && argument.kinds().contains(kind);
		}
		String bound = argument.bound();
		if (bound == null || value instanceof LispNil) {
			return true;
		}
		return value instanceof LispJavaObject obj && loadClass(bound, caller).type().isInstance(obj.ref());
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
			ReflectiveJavaClasses.Type type = loadClass(interfaceName, caller);
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
		int markers = JavaMarkers.count(args, 4);
		if (markers > 0) {
			// A function constructor argument implements its interface by its arguments
			// (:functional); Java's false reaches the callable as |false| (:java-false).
			return subclass(args.subList(0, args.size() - markers), withMarkers(caller, JavaMarkers.of(args, 4)));
		}
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
		ReflectiveJavaClasses.Type superclass = loadClass(superName.value(), caller);
		if (superclass.isInterface()) {
			throw new LispEvalException(JavaImplementations.notAClass(superName.value()));
		}
		if (superclass.isFinal()) {
			throw new LispEvalException(JavaImplementations.finalSuperclass(superName.value()));
		}
		List<JavaType> interfaces = new ArrayList<>();
		List<Class<?>> ifaceClasses = new ArrayList<>();
		for (String interfaceName : interfaceNames) {
			ReflectiveJavaClasses.Type type = loadClass(interfaceName, caller);
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
				constructor, caller.classes().loader());
		SUBCLASS_KINDS.putIfAbsent(proxyClass, dispatch.kind);
		@Nullable Object[] javaArgs = marshalArguments(overload, ctorArgs, caller);
		try {
			Constructor<?> proxyConstructor = proxyConstructor(proxyClass, constructor);
			Object[] withHandler = new Object[javaArgs.length + 1];
			withHandler[0] = new SubclassHandler(dispatch, callable, caller);
			System.arraycopy(javaArgs, 0, withHandler, 1, javaArgs.length);
			return unmarshal(proxyConstructor.newInstance(withHandler), caller);
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
				JavaMarkers markers = this.caller.markers();
				for (Object argument : methodArgs) {
					// what Java hands a function: Java's false after
					// :java-false, a byte[] after :octets an octet vector over
					// Java's own array
					callArgs.add(unmarshal(argument, markers.javaFalse(), markers.octets()));
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

	// (java:reify interfaces [:value v] [:class "c"] "method" function ...): each
	// function implements the one method its designator names of the interfaces -- one
	// name, or a list of them (compiler/JavaImplementations.reify -- the rule a compiled
	// program's generated class follows) -- and with :value the object stands for v.
	static LispVal reify(List<LispVal> args, Caller caller) {
		JavaImplementations.ReifyParts shape = args.isEmpty() ? null : JavaImplementations.reifyParts(args, 0);
		if (shape == null) {
			throw new LispEvalException(JavaImplementations.REIFY_USAGE);
		}
		int markers = JavaMarkers.count(args, shape.firstDesignator());
		if (markers > 0) {
			// Java's false reaches the functions as |false| (:java-false).
			return reify(args.subList(0, args.size() - markers),
					withMarkers(caller, JavaMarkers.of(args, shape.firstDesignator())));
		}
		List<String> names = interfaceNames(args.get(0));
		if (names == null || (args.size() - shape.firstDesignator()) % 2 != 0) {
			throw new LispEvalException(JavaImplementations.REIFY_USAGE);
		}
		StandIn standIn = null;
		if (shape.value() >= 0) {
			LispVal className = shape.className() >= 0 ? args.get(shape.className()) : LispNil.INSTANCE;
			if (!(className instanceof LispString) && !(className instanceof LispNil)) {
				throw new LispEvalException(JavaImplementations.REIFY_USAGE);
			}
			standIn = new StandIn(args.get(shape.value()),
					className instanceof LispString given ? given.value() : null);
		}
		List<String> designators = new ArrayList<>();
		List<LispVal> functions = new ArrayList<>();
		for (int i = shape.firstDesignator(); i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispString designator)) {
				throw new LispEvalException(JavaImplementations.REIFY_USAGE);
			}
			designators.add(designator.value());
			functions.add(args.get(i + 1));
		}
		List<JavaType> types = new ArrayList<>();
		List<Class<?>> classes = new ArrayList<>();
		for (String name : names) {
			ReflectiveJavaClasses.Type type = loadClass(name, caller);
			if (!type.isInterface()) {
				throw new LispEvalException(JavaImplementations.notAnInterface(false, name));
			}
			if (types.contains(type)) {
				throw new LispEvalException(JavaImplementations.reifyRepeatedInterface(name));
			}
			types.add(type);
			classes.add(type.type());
		}
		List<Object> key = List.of(classes, designators);
		Dispatch dispatch = IMPLEMENTATIONS.get(key);
		if (dispatch == null) {
			try {
				dispatch = new Dispatch(JavaImplementations.reify(types, designators, CLASSES));
			}
			catch (IllegalArgumentException ex) {
				throw new LispEvalException(String.valueOf(ex.getMessage()));
			}
			remember(IMPLEMENTATIONS, key, dispatch);
		}
		if (standIn != null) {
			String conflict = JavaImplementations.standInConflict(dispatch.implementation.interfaces());
			if (conflict != null) {
				throw new LispEvalException(conflict);
			}
		}
		return implement(dispatch, functions, caller, standIn);
	}

	// A java:reify's interfaces: one name, or a non-empty proper list of names; null for
	// anything else.
	private static @Nullable List<String> interfaceNames(LispVal value) {
		if (value instanceof LispString name) {
			return List.of(name.value());
		}
		List<String> names = value instanceof LispCons ? stringList(value) : null;
		return names == null || names.isEmpty() ? null : names;
	}

	/**
	 * How a {@code java:reify} given {@code :value} stands for it: the value every
	 * unmarshal answers for the object ({@code runtime/RontoJavaValue}) and the class
	 * Java's messages name it by, null for the object's own.
	 */
	private record StandIn(LispVal value, @Nullable String className) {
	}

	// A function passed where an interface is expected: the interface's java:proxy, or,
	// at a call ending in :functional, the implementation calling the function with each
	// abstract method's arguments (compiler/JavaImplementations.functional).
	private static LispVal implementation(Class<?> iface, LispVal function, Caller caller) {
		if (!caller.markers().functional()) {
			return proxy(iface.getName(), function, caller);
		}
		List<Object> key = List.of(iface, FUNCTIONAL_KEY);
		Dispatch dispatch = IMPLEMENTATIONS.get(key);
		if (dispatch == null) {
			dispatch = new Dispatch(JavaImplementations.functional(ReflectiveJavaClasses.of(iface), CLASSES));
			remember(IMPLEMENTATIONS, key, dispatch);
		}
		return implement(dispatch, List.of(function), caller);
	}

	// The designators-list stand-in of a java:proxy's key.
	private static final String PROXY_KEY = "proxy";

	// The designators-list stand-in of a function's :functional implementation's key.
	private static final String FUNCTIONAL_KEY = "functional";

	// How a java:proxy of an interface class, or a java:reify of (interface class,
	// designators), implements it: resolved once.
	private static final ConcurrentHashMap<List<Object>, Dispatch> IMPLEMENTATIONS = new ConcurrentHashMap<>();

	// The object: a Proxy whose handler dispatches on the implementation's slots.
	private static LispVal implement(Dispatch dispatch, List<LispVal> functions, Caller caller) {
		return implement(dispatch, functions, caller, null);
	}

	// The object, standing for a value when STAND_IN is given: a Proxy implementing
	// runtime/RontoJavaValue too, which every unmarshal answers the value of.
	private static LispVal implement(Dispatch dispatch, List<LispVal> functions, Caller caller,
			@Nullable StandIn standIn) {
		List<JavaType> types = dispatch.implementation.interfaces();
		Class<?>[] interfaces = new Class<?>[types.size() + (standIn != null ? 1 : 0)];
		for (int i = 0; i < types.size(); i++) {
			interfaces[i] = ((ReflectiveJavaClasses.Type) types.get(i)).type();
		}
		if (standIn != null) {
			interfaces[types.size()] = RontoJavaValue.class;
		}
		return new LispJavaObject(Proxy.newProxyInstance(proxyLoader(interfaces), interfaces,
				new ImplementationHandler(dispatch, functions, caller, standIn)));
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

		// Whether the call that made the object ends in :java-false: Java's false
		// reaches the functions as |false|.
		private final boolean javaFalse;

		// Whether it ends in :octets: a byte[] reaches the functions as an octet vector
		// over Java's own array, so what they store Java reads.
		private final boolean octets;

		// Whether compare is a Comparator's whose function may answer a boolean
		// (compiler/JavaImplementation.readsComparison).
		private final boolean comparison;

		// The value the object stands for (a java:reify given :value), or null.
		private final @Nullable StandIn standIn;

		ImplementationHandler(Dispatch dispatch, List<LispVal> functions, Caller caller) {
			this(dispatch, functions, caller, null);
		}

		ImplementationHandler(Dispatch dispatch, List<LispVal> functions, Caller caller, @Nullable StandIn standIn) {
			this.dispatch = dispatch;
			this.functions = List.copyOf(functions);
			this.caller = caller;
			this.standIn = standIn;
			this.javaFalse = caller.markers().javaFalse();
			this.octets = caller.markers().octets();
			JavaImplementation marked = dispatch.implementation.withMarkers(caller.markers());
			boolean reads = false;
			for (JavaImplementation.Slot slot : marked.slots()) {
				reads |= marked.readsComparison(slot);
			}
			this.comparison = reads;
		}

		@Override
		public @Nullable Object invoke(Object p, Method method, @Nullable Object @Nullable [] methodArgs)
				throws Throwable {
			StandIn standing = this.standIn;
			if (standing != null && method.getDeclaringClass() == RontoJavaValue.class) {
				// runtime/RontoJavaValue: the value, and the class Java's messages name
				return "value".equals(method.getName()) ? standing.value()
						: standing.className() != null ? standing.className() : p.getClass().getName();
			}
			int index = this.dispatch.indexOf(method);
			switch (index) {
				case Dispatch.HASH_CODE -> {
					return standing != null ? RontoJavaValue.identityHash((RontoJavaValue) p)
							: System.identityHashCode(p);
				}
				case Dispatch.EQUALS -> {
					Object other = methodArgs == null ? null : methodArgs[0];
					return standing != null ? sameValue((RontoJavaValue) p, other) : p == other;
				}
				case Dispatch.TO_STRING -> {
					return standing != null ? RontoJavaValue.identityText((RontoJavaValue) p)
							: this.dispatch.implementation.defaultToString();
				}
				case Dispatch.DEFAULT -> {
					return InvocationHandler.invokeDefault(p, method, methodArgs);
				}
				case JavaImplementation.NONE -> throw new UnsupportedOperationException(JavaImplementation
					.noImplementation(this.dispatch.implementation.declaringName(Dispatch.keyOf(method)),
							Dispatch.keyOf(method)));
				default -> {
				}
			}
			Object answer;
			try {
				answer = call(index, method, methodArgs);
			}
			catch (Throwable signal) {
				throw raised(signal);
			}
			if (answer instanceof Refusal refusal) {
				// A comparison's own failure -- AFunction.compare's cast to Number --
				// like
				// an abstract method's: not recorded, so the site wraps it as a member's.
				throw refusal.failure();
			}
			return answer;
		}

		private @Nullable Object call(int index, Method method, @Nullable Object @Nullable [] methodArgs) {
			boolean proxy = this.dispatch.implementation.proxy();
			List<LispVal> callArgs = new ArrayList<>();
			if (proxy) {
				callArgs.add(new LispString(method.getName()));
			}
			if (methodArgs != null) {
				for (Object a : methodArgs) {
					callArgs.add(unmarshal(a, this.javaFalse, this.octets));
				}
			}
			LispVal function = this.functions.get(index);
			LispVal result = this.caller.call(function, callArgs);
			Class<?> ret = method.getReturnType();
			if (ret == void.class) {
				return null;
			}
			if (this.comparison && ret == int.class && "compare".equals(method.getName())
					&& method.getParameterCount() == 2) {
				return comparison(function, callArgs, result);
			}
			@Nullable Object[] slot = new @Nullable Object[1];
			ReflectiveJavaClasses.Type returnType = ReflectiveJavaClasses.of(ret);
			if (marshal(result, returnType, this.caller, slot, 0, false) == NO_MATCH) {
				// a java:reify names the method's interface, a java:proxy all of its own
				String iface = proxy ? this.dispatch.ifaceName
						: this.dispatch.implementation.declaringName(Dispatch.keyOf(method));
				throw new LispEvalException(JavaImplementation.returnMismatchPrefix(proxy) + result.print()
						+ JavaImplementation.returnMismatchSuffix(proxy, iface, method.getName(), returnType));
			}
			return slot[0];
		}

		// What a function implementing Comparator.compare answers, read as Clojure's
		// AFunction.compare reads it (compiler/JavaImplementation.readsComparison): t is
		// -1; |false| is 1 when the function answers true -- neither nil nor |false| --
		// for the arguments swapped, else 0; a real number its intValue (an integer's low
		// 32 bits, a float or ratio truncated). Nil is the Refusal of AFunction.compare's
		// NullPointerException, anything else of its ClassCastException (compiled: _jcmp,
		// the bridge's comparison).
		private Object comparison(LispVal function, List<LispVal> args, LispVal answer) {
			return switch (answer) {
				case LispTrue ignored -> -1;
				case LispSymbol symbol when LispNames.JAVA_FALSE.equals(symbol.name()) -> {
					LispVal reversed = this.caller.call(function, List.of(args.get(1), args.get(0)));
					boolean truthy = !(reversed instanceof LispNil)
							&& !(reversed instanceof LispSymbol s && LispNames.JAVA_FALSE.equals(s.name()));
					yield truthy ? 1 : 0;
				}
				case LispInteger i -> (int) i.value();
				case LispBigInteger b -> b.value().intValue();
				case LispDouble d -> (int) d.value();
				case am.ik.rontolisp.LispRatio r -> (int) r.doubleValue();
				case LispNil ignored -> new Refusal(new NullPointerException(JavaImplementation.COMPARISON_OF_NIL));
				default -> new Refusal(
						new ClassCastException(JavaImplementation.comparisonCastFailure(javaClassName(answer))));
			};
		}

	}

	// RontoJavaValue.sameValue of an object compared with null (equals(null)), which the
	// runtime interface cannot spell @Nullable.
	@SuppressWarnings("NullAway")
	private static boolean sameValue(RontoJavaValue self, @Nullable Object other) {
		return RontoJavaValue.sameValue(self, other);
	}

	// A comparison's refusal (ImplementationHandler.comparison): the failure its
	// comparator throws as its own, unrecorded.
	private record Refusal(RuntimeException failure) {
	}

	// The class a comparison's refusal names for an answer: a host object's own, or that
	// of the object a value of a receiver kind is in Java (a string's String), else its
	// printed spelling (compiled: _jcmp, the bridge's comparison).
	private static String javaClassName(LispVal answer) {
		Object object = LispJavaObject.receiverObject(answer);
		return object == null ? answer.print() : object.getClass().getName();
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
	// convert(); a list or vector element-wise, a hash table entry-wise. A function
	// becomes a proxy of an interface: an argument's conversion.
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
			case LispJavaObject obj when obj.ref() instanceof RontoJavaListView view -> {
				return marshalListView(view, target, caller, out, index, proxies);
			}
			case LispJavaObject obj when obj.ref() instanceof RontoJavaBytesView view -> {
				// the byte[] it is to Java, wherever one fits: the octets' own storage,
				// so what Java stores the program reads (compiled alike)
				int cost = JavaOverloads.bytesViewCost(target);
				if (cost != NO_MATCH) {
					out[index] = view.bytes();
				}
				return cost;
			}
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
			case LispHashTable table -> {
				List<LispVal> entries = new ArrayList<>(2 * table.count());
				for (LispHashTable.Entry entry : table.entries()) {
					entries.add(entry.key());
					entries.add(entry.value());
				}
				return marshalTable(entries, target, caller, out, index, proxies);
			}
			default -> {
				return NO_MATCH; // symbol, ratio, ... are not bridged
			}
		}
	}

	// The Java value of a value with a kind, for a target kindCost accepted.
	private static @Nullable Object convert(LispVal value, Class<?> target, Caller caller) {
		return switch (value) {
			case LispNil ignored -> target == boolean.class || target == Boolean.class ? Boolean.FALSE : null;
			case LispTrue ignored -> Boolean.TRUE;
			// |false|, the one symbol with a kind
			case LispSymbol ignored -> Boolean.FALSE;
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
			case LispLambda lambda -> ((LispJavaObject) implementation(target, lambda, caller)).ref();
			case LispFunction function -> ((LispJavaObject) implementation(target, function, caller)).ref();
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

	// A java:view List converts, for a target it is an instance of, to itself -- a host
	// object of its class -- and, where an array is expected, to an array of its items,
	// at
	// COST_VIEW_ARRAY plus their costs as the component: after every way to pass it
	// whole,
	// its varargs packing included (compiled: JvmJavaDirectSites' view arms, the bridge's
	// marshalListView).
	private static int marshalListView(RontoJavaListView view, JavaType target, Caller caller, @Nullable Object[] out,
			int index, boolean proxies) {
		if (!target.isPrimitive() && classOf(target).isInstance(view)) {
			out[index] = view;
			return classOf(target) == view.getClass() ? JavaOverloads.COST_EXACT : JavaOverloads.COST_WIDEN;
		}
		List<LispVal> items = target.componentType() == null ? null : sequenceElements((LispVal) view.items());
		if (items == null) {
			return NO_MATCH;
		}
		int cost = marshalSequence(items, target, caller, out, index, proxies);
		return cost == NO_MATCH ? NO_MATCH : cost - JavaOverloads.COST_CONVERT + JavaOverloads.COST_VIEW_ARRAY;
	}

	// A hash table converts, for any target a java.util.LinkedHashMap is assignable to,
	// to a fresh one of its entries in insertion order, each key and value marshalled as
	// an Object -- as a sequence converts to a java.util.List. ENTRIES alternates keys
	// and values (compiled: JvmJavaDirectSites' _jtab, the bridge's tableEntries).
	private static int marshalTable(List<LispVal> entries, JavaType target, Caller caller, @Nullable Object[] out,
			int index, boolean proxies) {
		if (target.isPrimitive() || !classOf(target).isAssignableFrom(LinkedHashMap.class)) {
			return NO_MATCH;
		}
		@Nullable Object[] slot = new @Nullable Object[2];
		JavaType object = ReflectiveJavaClasses.of(Object.class);
		Map<@Nullable Object, @Nullable Object> map = new LinkedHashMap<>();
		int total = JavaOverloads.COST_BOXED;
		for (int i = 0; i < entries.size(); i += 2) {
			int keyCost = marshal(entries.get(i), object, caller, slot, 0, proxies);
			if (keyCost == NO_MATCH) {
				return NO_MATCH;
			}
			int valueCost = marshal(entries.get(i + 1), object, caller, slot, 1, proxies);
			if (valueCost == NO_MATCH) {
				return NO_MATCH;
			}
			total += keyCost + valueCost;
			map.put(slot[0], slot[1]);
		}
		out[index] = map;
		return total;
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
		return unmarshal(o, false, false);
	}

	// What a call ending in the caller's markers answers for a Java value: Java's false
	// as |false| after :java-false, nil otherwise; a byte[] an (unsigned-byte 8) vector
	// after :octets, a list otherwise.
	private static LispVal unmarshal(@Nullable Object o, Caller caller) {
		JavaMarkers markers = caller.markers();
		return unmarshal(o, markers.javaFalse(), markers.octets());
	}

	/**
	 * {@code (java:handle value text hash order class)}: a Java object standing for the
	 * value ({@link RontoJavaHandle}). Java sees the text as its {@code toString} (nil:
	 * {@code Object}'s spelling, the class and the hex hash); handles of one class are
	 * equal by their texts, their {@code hashCode} the hash's low 32 bits (the text's own
	 * without one) -- or, with a nil hash, equal only to a handle of the very same value,
	 * whose identity hash is the hash. A handle orders one of its class by the order text
	 * (the text without one), or answers a function of its value and the object compared
	 * with -- its answer's sign, or the {@code ClassCastException} of a cast to the class
	 * when it answers no real -- and with a nil order no handle at all. A handle standing
	 * for a real number is a {@code Number} of it ({@link RontoJavaNumberHandle}).
	 * Wherever Java hands one back, {@code java:} answers the value.
	 * @param args the value, the text, and optionally the hash, the order and the class
	 * @param caller what calls an order function
	 * @return the handle, a host object
	 */
	static LispVal handle(List<LispVal> args, Caller caller) {
		if (args.size() < 2 || args.size() > 5) {
			throw new LispEvalException(HANDLE_USAGE);
		}
		LispVal value = args.get(0);
		String text = args.get(1) instanceof LispString given ? given.value() : null;
		if (text == null && !(args.get(1) instanceof LispNil)) {
			throw handleUsage(args.get(1));
		}
		boolean identity = args.size() > 2 && args.get(2) instanceof LispNil;
		int hash = switch (args.size() > 2 ? args.get(2) : LispNil.INSTANCE) {
			case LispInteger i -> (int) i.value();
			case LispBigInteger b -> b.value().intValue();
			case LispNil ignored when identity -> 0;
			case LispNil ignored when text != null -> text.hashCode();
			default -> throw handleUsage(args.size() > 2 ? args.get(2) : args.get(1));
		};
		if (text == null && !identity) {
			// a handle with no text is equal only to a handle of its value
			throw handleUsage(args.get(1));
		}
		int orderMode = text == null ? RontoJavaHandle.ORDER_NONE : RontoJavaHandle.ORDER_TEXT;
		Object order = text;
		if (args.size() > 3) {
			switch (args.get(3)) {
				case LispString given -> {
					orderMode = RontoJavaHandle.ORDER_TEXT;
					order = given.value();
				}
				case LispNil ignored -> {
					orderMode = RontoJavaHandle.ORDER_NONE;
					order = null;
				}
				case LispVal function when isFunction(function) -> {
					orderMode = RontoJavaHandle.ORDER_FUNCTION;
					order = function;
				}
				default -> throw handleUsage(args.get(3));
			}
		}
		String className = args.size() > 4 ? className(args.get(4), JavaInterop::handleUsage) : null;
		RontoJavaHandle handle = newHandle(value, text, identity, hash, orderMode, order, className,
				orderMode == RontoJavaHandle.ORDER_FUNCTION ? new Calls(caller) : null);
		return new LispJavaObject(switch (value) {
			case LispInteger i -> new RontoJavaNumberHandle(handle, i.value(), i.value(), (int) i.value());
			case LispBigInteger b ->
				new RontoJavaNumberHandle(handle, b.value().doubleValue(), b.value().longValue(), b.value().intValue());
			case LispDouble d -> new RontoJavaNumberHandle(handle, d.value(), (long) d.value(), (int) d.value());
			case am.ik.rontolisp.LispRatio r -> {
				// Clojure's Ratio: the DECIMAL64 quotient's double, and its int the
				// intValue; the truncated quotient's low 64 bits the longValue.
				double quotient = new java.math.BigDecimal(r.numerator())
					.divide(new java.math.BigDecimal(r.denominator()), java.math.MathContext.DECIMAL64)
					.doubleValue();
				yield new RontoJavaNumberHandle(handle, quotient, r.numerator().divide(r.denominator()).longValue(),
						(int) quotient);
			}
			default -> handle;
		});
	}

	// The runtime class cannot spell @Nullable (it imports nothing but the JDK): null is
	// its "none" for the text, the order, the class and the calls.
	@SuppressWarnings("NullAway")
	private static RontoJavaHandle newHandle(LispVal value, @Nullable String text, boolean identity, int hash,
			int orderMode, @Nullable Object order, @Nullable String className, @Nullable RontoJavaCalls calls) {
		return new RontoJavaHandle(value, text, identity, hash, orderMode, order, className, calls);
	}

	// The error of a malformed java:handle call (compiler/JavaImplementations; the
	// compiled program's _jhandle checks alike).
	static final String HANDLE_USAGE = JavaImplementations.HANDLE_USAGE;

	private static LispEvalException handleUsage(LispVal got) {
		return new LispEvalException(HANDLE_USAGE + ", got " + got.print());
	}

	// A class name argument: a string's text, or null for nil; anything else refused.
	private static @Nullable String className(LispVal value,
			java.util.function.Function<LispVal, LispEvalException> refusal) {
		if (value instanceof LispString name) {
			return name.value();
		}
		if (value instanceof LispNil) {
			return null;
		}
		throw refusal.apply(value);
	}

	private static boolean isFunction(LispVal value) {
		return value instanceof LispLambda || value instanceof LispFunction;
	}

	/**
	 * {@code (java:view value items shape printer order class)}: a read-only Java
	 * collection standing for the value, its elements the items converted as
	 * {@code Object} arguments, once, here. {@code :list} and {@code :vector} make a
	 * {@code java.util.List} of a sequence's elements (a vector's {@code RandomAccess}
	 * and {@code Comparable} by the order), {@code :set} a {@code java.util.Set} of them,
	 * {@code :map} a {@code java.util.Map} of a hash table's entries or a plist's pairs.
	 * Its {@code toString} is the printer's answer for the value (the Java spelling
	 * without one); every write is an {@code UnsupportedOperationException}; wherever
	 * Java hands it back, {@code java:} answers the value. As an argument it is a host
	 * object of its class, and a {@code List} also an array of its items where nothing
	 * takes it whole ({@link #marshalListView}). {@code :bytes} over an
	 * {@code (unsigned-byte 8)} vector makes no collection: Java is handed the vector's
	 * own {@code byte[]} wherever one fits ({@code runtime/RontoJavaBytesView}), so what
	 * Java stores into it the program reads.
	 * @param args the value, the items, the shape, and optionally the printer, the order
	 * and the class
	 * @param caller what calls the printer and the order
	 * @return the view, a host object
	 */
	static LispVal view(List<LispVal> args, Caller caller) {
		if (args.size() < 3 || args.size() > 6) {
			throw new LispEvalException(VIEW_USAGE);
		}
		LispVal value = args.get(0);
		LispVal items = args.get(1);
		String shape = args.get(2) instanceof LispSymbol keyword ? keyword.name() : "";
		boolean map = LispNames.JAVA_VIEW_MAP.equals(shape);
		boolean vector = LispNames.JAVA_VIEW_VECTOR.equals(shape);
		boolean set = LispNames.JAVA_VIEW_SET.equals(shape);
		boolean bytes = LispNames.JAVA_VIEW_BYTES.equals(shape);
		if (!map && !vector && !set && !bytes && !LispNames.JAVA_VIEW_LIST.equals(shape)) {
			throw viewUsage(args.get(2));
		}
		LispVal printer = args.size() > 3 ? args.get(3) : LispNil.INSTANCE;
		if (!(printer instanceof LispNil) && !isFunction(printer)) {
			throw viewUsage(printer);
		}
		LispVal order = args.size() > 4 ? args.get(4) : LispNil.INSTANCE;
		if (!(order instanceof LispNil) && !(vector && isFunction(order))) {
			throw viewUsage(order);
		}
		String className = args.size() > 5 ? className(args.get(5), JavaInterop::viewUsage) : null;
		if (bytes) {
			// the octets themselves: Java is handed their storage
			if (!(items instanceof LispIntVector octets) || octets.width() != 8) {
				throw viewUsage(items);
			}
			shareOctets(octets);
			return new LispJavaObject(new RontoJavaBytesView(value, octets.octets()));
		}
		List<LispVal> members = map ? viewEntries(items) : sequenceElements(items);
		if (members == null) {
			throw viewUsage(items);
		}
		@Nullable Object[] elements = new @Nullable Object[members.size()];
		@Nullable Object[] slot = new @Nullable Object[1];
		JavaType object = ReflectiveJavaClasses.of(Object.class);
		for (int i = 0; i < elements.length; i++) {
			if (marshal(members.get(i), object, caller, slot, 0) == NO_MATCH) {
				throw new LispEvalException(VIEW_NO_VALUE + members.get(i).print());
			}
			elements[i] = slot[0];
		}
		Object shown = printer instanceof LispNil ? null : printer;
		Object ordered = order instanceof LispNil ? null : order;
		RontoJavaCalls calls = shown != null || ordered != null ? new Calls(caller) : null;
		return new LispJavaObject(newView(shape, value, items, elements, shown, ordered, className, calls));
	}

	// The view of the shape (java:view validated it). The runtime classes cannot spell
	// @Nullable (they import nothing but the JDK): null is their "none" for the printer,
	// the order, the class and the calls, and an element may be Java's null.
	@SuppressWarnings("NullAway")
	private static Object newView(String shape, LispVal value, LispVal items, @Nullable Object[] elements,
			@Nullable Object printer, @Nullable Object order, @Nullable String className,
			@Nullable RontoJavaCalls calls) {
		return switch (shape) {
			case LispNames.JAVA_VIEW_MAP -> new RontoJavaMapView(value, elements, printer, className, calls);
			case LispNames.JAVA_VIEW_SET -> new RontoJavaSetView(value, elements, printer, className, calls);
			case LispNames.JAVA_VIEW_VECTOR ->
				new RontoJavaVectorView(value, items, elements, printer, order, className, calls);
			default -> new RontoJavaListView(value, items, elements, printer, className, calls);
		};
	}

	// The errors of a malformed java:view call and of an item that converts to no Object
	// (compiler/JavaImplementations; the compiled program's _jview checks alike).
	static final String VIEW_USAGE = JavaImplementations.VIEW_USAGE;

	static final String VIEW_NO_VALUE = JavaImplementations.VIEW_NO_VALUE;

	private static LispEvalException viewUsage(LispVal got) {
		return new LispEvalException(VIEW_USAGE + ", got " + got.print());
	}

	// The elements of a sequence as marshal reads one -- nil, a proper list, a rank-1
	// vector (its fill pointer bounding it), a specialized one -- or null for anything
	// else.
	private static @Nullable List<LispVal> sequenceElements(LispVal value) {
		switch (value) {
			case LispNil ignored -> {
				return List.of();
			}
			case LispCons cons -> {
				return properListElements(cons);
			}
			case LispArray array -> {
				if (array.dimensions().length != 1) {
					return null;
				}
				int count = array.effectiveLength();
				List<LispVal> elements = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					LispVal element = array.readFlat(i);
					elements.add(element == null ? LispNil.INSTANCE : element);
				}
				return elements;
			}
			case LispFloatArray array -> {
				if (array.dims().length != 1) {
					return null;
				}
				List<LispVal> elements = new ArrayList<>(array.dims()[0]);
				for (int i = 0; i < array.dims()[0]; i++) {
					elements.add(new LispDouble(array.elementAt(i)));
				}
				return elements;
			}
			case LispIntVector vector -> {
				List<LispVal> elements = new ArrayList<>(vector.length());
				for (int i = 0; i < vector.length(); i++) {
					elements.add(new LispInteger(vector.elementAt(i)));
				}
				return elements;
			}
			default -> {
				return null;
			}
		}
	}

	// A map view's keys and values, alternating: a hash table's live entries in order,
	// or a plist's pairs; null for anything else.
	private static @Nullable List<LispVal> viewEntries(LispVal value) {
		if (value instanceof LispHashTable table) {
			List<LispVal> entries = new ArrayList<>(2 * table.count());
			for (LispHashTable.Entry entry : table.entries()) {
				entries.add(entry.key());
				entries.add(entry.value());
			}
			return entries;
		}
		if (!(value instanceof LispNil) && !(value instanceof LispCons)) {
			return null;
		}
		List<LispVal> plist = sequenceElements(value);
		return plist != null && plist.size() % 2 == 0 ? plist : null;
	}

	/**
	 * How a handle or a view the interpreter made calls back into the program: through
	 * the evaluator. What the function raises leaves recorded ({@link #raised}), as a
	 * {@code java:reify} callback's does.
	 */
	private record Calls(Caller caller) implements RontoJavaCalls {

		@Override
		public String text(Object printer, Object value) {
			try {
				LispVal answer = this.caller.call((LispVal) printer, List.of((LispVal) value));
				return answer instanceof LispString text ? text.value() : answer.print();
			}
			catch (Throwable signal) {
				throw unchecked(raised(signal));
			}
		}

		// Null is the refusal RontoJavaCalls.order answers, which the runtime interface
		// cannot spell @Nullable.
		@Override
		@SuppressWarnings("NullAway")
		public @Nullable Integer order(Object order, Object value, Object other) {
			try {
				LispVal answer = this.caller.call((LispVal) order, List.of((LispVal) value, unmarshal(other)));
				return switch (answer) {
					case LispInteger i -> Long.signum(i.value());
					case LispBigInteger b -> b.value().signum();
					case LispDouble d -> (int) Math.signum(d.value());
					case am.ik.rontolisp.LispRatio r -> r.numerator().signum();
					default -> null;
				};
			}
			catch (Throwable signal) {
				throw unchecked(raised(signal));
			}
		}

	}

	// A throwable thrown on through a method that declares none.
	private static RuntimeException unchecked(Throwable throwable) {
		if (throwable instanceof RuntimeException exception) {
			return exception;
		}
		if (throwable instanceof Error error) {
			throw error;
		}
		return new RuntimeException(throwable);
	}

	// The Lisp value of a Java value: Java's false is nil, or |false| (javaFalse, a call
	// ending in :java-false) -- an array's elements alike; a handle or a view is the
	// value it stands for (compiled: _junm / _junf).
	static LispVal unmarshal(@Nullable Object o, boolean javaFalse) {
		return unmarshal(o, javaFalse, false);
	}

	// unmarshal with octets (a call ending in :octets): a byte[] -- the value or an
	// element of an array -- is an (unsigned-byte 8) vector over the very array, which
	// the program and Java then share (compiled: the array itself, _juno / _jufo).
	static LispVal unmarshal(@Nullable Object o, boolean javaFalse, boolean octets) {
		return switch (o) {
			case null -> LispNil.INSTANCE;
			case RontoJavaValue handle -> (LispVal) handle.value();
			case Boolean b -> b ? LispTrue.INSTANCE : javaFalse ? JAVA_FALSE : LispNil.INSTANCE;
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
			case byte[] bytes when octets -> octetVector(bytes);
			default -> o.getClass().isArray() ? arrayToList(o, javaFalse, octets) : new LispJavaObject(o);
		};
	}

	// The vector each byte[] the program and Java share is: compiled, an octet vector IS
	// its byte[], so two answers of one array are eq, and so are a vector a :bytes view
	// handed Java and the array coming back; here a vector is a wrapper, kept one per
	// array. Weak both ways: the wrapper holds the array, so the entry holds the wrapper
	// only weakly, and an array nothing holds takes its entry with it.
	private static final Map<byte[], WeakReference<LispIntVector>> OCTET_VECTORS = new WeakHashMap<>();

	// The vector over these octets: the one already shared, else a new one, kept.
	private static LispIntVector octetVector(byte[] octets) {
		synchronized (OCTET_VECTORS) {
			WeakReference<LispIntVector> kept = OCTET_VECTORS.get(octets);
			LispIntVector vector = kept != null ? kept.get() : null;
			if (vector == null) {
				vector = LispIntVector.wrapOctets(octets);
				OCTET_VECTORS.put(octets, new WeakReference<>(vector));
			}
			return vector;
		}
	}

	// A vector whose octets a :bytes view hands Java: the one their array comes back as.
	private static void shareOctets(LispIntVector vector) {
		synchronized (OCTET_VECTORS) {
			WeakReference<LispIntVector> kept = OCTET_VECTORS.get(vector.octets());
			if (kept == null || kept.get() == null) {
				OCTET_VECTORS.put(vector.octets(), new WeakReference<>(vector));
			}
		}
	}

	// The symbol a call ending in :java-false answers Java's false as.
	private static final LispSymbol JAVA_FALSE = new LispSymbol(LispNames.JAVA_FALSE);

	// A Java array result (e.g. String.split) surfaces as a Lisp list, elements
	// unmarshalled recursively; it round-trips back through marshalSequence.
	private static LispVal arrayToList(Object array, boolean javaFalse, boolean octets) {
		LispVal result = LispNil.INSTANCE;
		for (int i = Array.getLength(array) - 1; i >= 0; i--) {
			result = new LispCons(unmarshal(Array.get(array, i), javaFalse, octets), result);
		}
		return result;
	}

	private static ReflectiveJavaClasses.Type loadClass(String name, Caller caller) {
		ReflectiveJavaClasses.Type type = caller.classes().find(name);
		if (type == null || type.isPrimitive() || type.isArray()) {
			throw new LispEvalException("No such class: " + name);
		}
		return type;
	}

	// What a failed Java call throws: what a function called back from Java raised -- an
	// exit, a condition, uiop:quit -- on to the Lisp code that made the call, as it is;
	// anything else is the error calling the member, a java:java-exception carrying
	// what the member threw (the landing that catches it builds the condition from it).
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
		LispEvalException failure = LispEvalException.ofClass(ClosRegistry.JAVA_EXCEPTION_CLASS_NAME,
				"error " + what + ": " + cause);
		failure.initCause(cause);
		return failure;
	}

	// The arguments a member takes: each condition standing for a host exception -- a
	// java:java-exception, whose cause slot holds the exception or a function building
	// it -- as that exception; the list itself when none is one.
	private static List<LispVal> hostArguments(List<LispVal> args, Caller caller) {
		List<LispVal> out = null;
		for (int i = 0; i < args.size(); i++) {
			LispVal converted = hostException(args.get(i), caller);
			if (converted != args.get(i)) {
				if (out == null) {
					out = new ArrayList<>(args);
				}
				out.set(i, converted);
			}
		}
		return out == null ? args : out;
	}

	// The host exception a java:java-exception stands for, the value itself otherwise.
	// The class is the cause slot's name at its index: every subclass lays its slots
	// out after it, so no registry is needed to tell.
	private static LispVal hostException(LispVal value, Caller caller) {
		if (!(value instanceof LispInstance instance)) {
			return value;
		}
		List<String> slots = instance.layout().slotNames();
		if (slots.size() <= ClosRegistry.JAVA_EXCEPTION_CAUSE_INDEX
				|| !ClosRegistry.JAVA_EXCEPTION_CAUSE_SLOT.equals(slots.get(ClosRegistry.JAVA_EXCEPTION_CAUSE_INDEX))) {
			return value;
		}
		LispVal held = instance.slot(ClosRegistry.JAVA_EXCEPTION_CAUSE_INDEX);
		if (held instanceof LispLambda || held instanceof LispFunction) {
			held = caller.call(held, List.of(value));
		}
		return held instanceof LispJavaObject ? held : value;
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
