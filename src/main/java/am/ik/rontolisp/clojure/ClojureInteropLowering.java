package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Interop forms of the Clojure lowering: the java: surface over host classes.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureInteropLowering {

	private ClojureInteropLowering() {
	}

	static final LispSymbol JAVA_CALL = new LispSymbol("JAVA:CALL");

	static final LispSymbol JAVA_STATIC = new LispSymbol("JAVA:STATIC");

	static final LispSymbol JAVA_NEW = new LispSymbol("JAVA:NEW");

	static final LispSymbol JAVA_FIELD = new LispSymbol("JAVA:FIELD");

	static final LispSymbol JAVA_PROXY = new LispSymbol("JAVA:PROXY");

	static final LispSymbol JAVA_SUBCLASS = new LispSymbol("JAVA:SUBCLASS");

	/**
	 * The one host class the lowering builds itself: a zero-argument
	 * {@code java.io.StringWriter} is a Common Lisp string output stream on every backend
	 * -- never a host {@code Writer}, which no backend writes to and wasm refuses
	 * outright.
	 */
	static final String STRING_WRITER_CLASS = "java.io.StringWriter";

	/** A zero-argument {@code java.io.StringWriter}: a string output stream. */
	static final String STRING_WRITER = "RONTOLISP::%CLOJURE-STRING-WRITER";

	/** A reader over a {@code java.io.StringReader}: a string input stream. */
	static final String STRING_READER = "RONTOLISP::%CLOJURE-STRING-READER";

	/** The stream test, an arm of {@code ClojureArms.Family.STREAM}. */
	static final String STREAM_P = "RONTOLISP::%CLOJURE-STREAM-P";

	/** A stream's toString. */
	static final String STREAM_STRING = "RONTOLISP::%CLOJURE-STREAM-STRING";

	/** A read of {@code *in*} as a value. */
	static final String STANDARD_INPUT_READ = "RONTOLISP::%CLOJURE-IN";

	/**
	 * The zero-argument {@code Throwable} methods an exception condition answers
	 * ({@link #instanceCallLoweredWithClass}); {@code toString} is
	 * {@link #valueToString}'s.
	 */
	static final Set<String> EXCEPTION_METHODS = Set.of("getMessage", "getLocalizedMessage", "getCause");

	/**
	 * The stack-trace methods of an exception, to the library function answering them
	 * ({@code clojure.lisp}): a condition has no frames, so they need no exception
	 * reader.
	 */
	static final Map<String, String> STACK_TRACE_METHODS = Map.of("printStackTrace",
			"RONTOLISP::%CLOJURE-PRINT-STACK-TRACE", "getStackTrace", "RONTOLISP::%CLOJURE-STACK-TRACE");

	/**
	 * A possible interop head: {@code (.} target method ...), {@code (.. ...)} chains,
	 * {@code (.method target ...)} and {@code (.-field target)} instance forms,
	 * {@code (Class. ...)} construction and {@code (Class/member ...)} statics. Null when
	 * the name is no interop shape, so the call keeps falling through.
	 */
	static @Nullable LispVal interopCall(ClojureLowering ctx, String name, List<LispVal> items) {
		if (name.equals(".")) {
			return dotForm(ctx, items);
		}
		if (name.equals("..")) {
			return dotDotOf(ctx, items);
		}
		if (name.startsWith(".")) {
			if (name.startsWith(".-")) {
				ClojureLowerUtil.isTrue(name.length() > 2, "a field read needs a field name: " + name);
				ClojureLowerUtil.isTrue(items.size() == 2, name + " takes a target");
				return fieldRead(ctx, ctx.lower(items.get(1)), name.substring(2));
			}
			ClojureLowerUtil.isTrue(name.length() > 1, "a method call needs a method name");
			ClojureLowerUtil.isTrue(items.size() >= 2, name + " takes a target and arguments");
			return instanceCall(ctx, ctx.lower(items.get(1)), name.substring(1), items.subList(2, items.size()));
		}
		if (name.endsWith(".") && name.length() > 1 && isClassSpelling(name.substring(0, name.length() - 1))) {
			String base = name.substring(0, name.length() - 1);
			String type = ctx.typeKeyOf(base);
			if (type != null) {
				// (T. args...) constructs the record or deftype, like ->T
				return ctx.lower(ClojureLowerUtil.cons(new LispSymbol(constructorSpelling(ctx, type)),
						items.subList(1, items.size())));
			}
			String constructed = ClojureNamespaceLowering.resolveClass(ctx, name.substring(0, name.length() - 1));
			return hostConstruction(ctx, constructed, items.subList(1, items.size()), null);
		}
		return memberCall(ctx, name, items, null);
	}

	/**
	 * A qualified member in call position, {@code (Class/member args...)}: a static call,
	 * {@code Class/.method} an instance call on the first argument, {@code Class/new} the
	 * construction ({@code R/new} of a record or deftype its positional constructor).
	 * {@code tags} are the {@code ^[types]} param tags, naming the overload through the
	 * {@code java:} parameter-tag designator; null when untagged. Null when the name is
	 * no classlike slash form, so the call keeps falling through.
	 */
	static @Nullable LispVal memberCall(ClojureLowering ctx, String name, List<LispVal> items, @Nullable LispVal tags) {
		QualifiedMember qualified = qualifiedMember(ctx, name);
		if (qualified == null) {
			return null;
		}
		List<LispVal> argDatums = items.subList(1, items.size());
		if (qualified.typeKey() != null) {
			// (R/new args...) constructs the record or deftype, like ->R
			return ctx
				.lower(ClojureLowerUtil.cons(new LispSymbol(constructorSpelling(ctx, qualified.typeKey())), argDatums));
		}
		String cls = qualified.cls();
		String member = qualified.member();
		@Nullable List<String> types = tags == null ? null : tagTypes(ctx, tags);
		if (member.equals("new")) {
			checkTagCount(types, argDatums.size(), "constructor", cls, name);
			return hostConstruction(ctx, cls, argDatums, types);
		}
		if (member.startsWith(".")) {
			ClojureLowerUtil.isTrue(!argDatums.isEmpty(), name + " takes a target and arguments");
			String method = member.substring(1);
			checkTagCount(types, argDatums.size() - 1, "method " + method, cls, name);
			return instanceCallLoweredWithClass(ctx, ctx.lower(argDatums.get(0)), cls, method,
					designator(method, types), ctx.lowers(items, 2));
		}
		checkTagCount(types, argDatums.size(), "method " + member, cls, name);
		if (argDatums.isEmpty() && types == null) {
			// no arguments: the zero-argument static method when the host
			// class has one, else the static field read (whose run-time
			// error names an unknown member or class, like before)
			return staticNoArg(ctx, cls, member);
		}
		return staticCall(ctx, cls, member, designator(member, types), ctx.lowers(items, 1));
	}

	/**
	 * A {@code Class/member} spelling split and resolved: the class (null for a record or
	 * deftype, whose key {@code typeKey} is), and the member as written ({@code .method},
	 * {@code new} or a static name). Null when the head is no class, the member is empty
	 * or slashed, or a record or deftype names anything but {@code new}.
	 */
	static @Nullable QualifiedMember qualifiedMember(ClojureLowering ctx, String name) {
		int slash = name.indexOf('/');
		if (slash <= 0) {
			return null;
		}
		String head = name.substring(0, slash);
		String member = name.substring(slash + 1);
		if (member.isEmpty() || member.indexOf('/') >= 0 || member.equals(".")
				|| !ClojureNamespaceLowering.isClasslike(ctx, head)) {
			return null;
		}
		String type = ctx.typeKeyOf(head);
		if (type != null) {
			return member.equals("new") ? new QualifiedMember(head, member, type) : null;
		}
		return new QualifiedMember(ClojureNamespaceLowering.resolveClass(ctx, head), member, null);
	}

	/** A resolved {@code Class/member} spelling ({@link #qualifiedMember}). */
	record QualifiedMember(String cls, String member, @Nullable String typeKey) {
	}

	/**
	 * The {@code java:} designator of a member under param tags: the bare name untagged,
	 * else {@code name(T1,T2)} -- the parameter-tag spelling {@code java:call},
	 * {@code java:static} and {@code java:new} select an overload by.
	 */
	static String designator(String member, @Nullable List<String> types) {
		return types == null ? member : member + "(" + String.join(",", types) + ")";
	}

	/**
	 * The {@code ^[types]} param tags of a datum: the first vector among its reader
	 * metadata layers, or null when it carries none.
	 */
	static @Nullable LispVal paramTags(LispVal datum) {
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		while (parts != null && parts.size() == 3
				&& ClojureLowerUtil.isSymbolNamed(parts.get(0), ClojureLowerUtil.READER_META)) {
			List<LispVal> meta = ClojureLowerUtil.items(parts.get(2));
			if (meta != null && !meta.isEmpty() && meta.get(0) == ClojureReader.VECTOR) {
				return parts.get(2);
			}
			parts = ClojureLowerUtil.items(parts.get(1));
		}
		return null;
	}

	/**
	 * The param tags as {@code java:} designator types: {@code _} any type, a primitive
	 * as itself, {@code ints}/{@code longs}/... and {@code objects} the primitive and
	 * {@code Object} arrays, {@code T/N} an {@code N}-dimensional array of {@code T}, any
	 * other name its class (dotted, imported or {@code java.lang}).
	 */
	static List<String> tagTypes(ClojureLowering ctx, LispVal tags) {
		List<LispVal> parts = ClojureLowerUtil.items(tags);
		if (parts == null || parts.isEmpty() || parts.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("param tags take a vector of class names, not " + tags.print());
		}
		List<String> types = new ArrayList<>();
		for (LispVal tag : parts.subList(1, parts.size())) {
			if (!(tag instanceof LispSymbol named) || named.name().startsWith(":")) {
				throw new LispReadException("param tags take class names, not " + tag.print());
			}
			String spelled = named.name();
			int dims = 0;
			int slash = spelled.lastIndexOf('/');
			if (slash > 0 && slash < spelled.length() - 1
					&& spelled.substring(slash + 1).chars().allMatch(Character::isDigit)) {
				dims = Integer.parseInt(spelled.substring(slash + 1));
				spelled = spelled.substring(0, slash);
			}
			String type = switch (spelled) {
				case "_", "boolean", "byte", "char", "short", "int", "long", "float", "double" -> spelled;
				case "booleans", "bytes", "chars", "shorts", "ints", "longs", "floats", "doubles" ->
					spelled.substring(0, spelled.length() - 1) + "[]";
				case "objects" -> "java.lang.Object[]";
				default -> ClojureNamespaceLowering.resolveClass(ctx, spelled);
			};
			types.add(type + "[]".repeat(dims));
		}
		return types;
	}

	/**
	 * Refuses a tagged call whose argument count is not the tag count, like the oracle's
	 * compile-time {@code expected N arguments, but received M}.
	 */
	private static void checkTagCount(@Nullable List<String> types, int argCount, String what, String cls,
			String spelling) {
		if (types != null && types.size() != argCount) {
			throw new LispReadException(spelling + ": invocation of " + what + " in class " + cls + " expected "
					+ types.size() + " arguments, but received " + argCount);
		}
	}

	/**
	 * A host construction over argument datums, shared by {@code (Class. ...)},
	 * {@code (new Class ...)} and {@code (Class/new ...)}: the reader wrappers and the
	 * zero-argument {@code java.io.StringWriter} as streams, an untagged plain throwable
	 * as an exception ({@link #throwableConstruction}), anything else {@code java:new},
	 * under the param-tag designator when tagged.
	 */
	static LispVal hostConstruction(ClojureLowering ctx, String cls, List<LispVal> argDatums,
			@Nullable List<String> types) {
		if (types == null) {
			LispVal wrapped = readerWrapperConstruction(ctx, cls, argDatums);
			if (wrapped != null) {
				return wrapped;
			}
		}
		LispVal stringWriter = stringWriterConstruction(cls, argDatums.size());
		if (stringWriter != null) {
			return stringWriter;
		}
		List<LispVal> lowered = ctx.lowers(argDatums, 0);
		if (types == null) {
			LispVal exception = throwableConstruction(ctx, cls, lowered);
			if (exception != null) {
				return exception;
			}
		}
		List<LispVal> args = new ArrayList<>();
		args.add(LispString.literal(designator(cls, types)));
		args.addAll(lowered);
		return ClojureLowerUtil.cons(JAVA_NEW, args);
	}

	/**
	 * A construction of a plain throwable class ({@link #plainThrowable}) as an exception
	 * condition of the program, so it runs on every backend: no argument, a message, a
	 * message or a cause (decided at run time when the class takes either), or a message
	 * and a cause, each only where the class has that public constructor -- a call the
	 * class has no constructor for keeps {@code java:new} and its host refusal. Null when
	 * the class is no plain throwable or the arity has no such constructor.
	 */
	static @Nullable LispVal throwableConstruction(ClojureLowering ctx, String cls, List<LispVal> args) {
		Class<?> type = plainThrowable(cls);
		if (type == null) {
			// a throwable host object becomes an exception of its class once thrown
			List<String> hostChain = ClojureThrowables.chainOf(cls);
			if (hostChain != null) {
				ctx.recordChain(hostChain);
			}
			return null;
		}
		ctx.recordChain(ClojureThrowables.chainOf(type));
		LispVal chain = ClojureThrowables.quoted(ClojureThrowables.chainOf(type));
		LispVal call = switch (args.size()) {
			case 0 -> hasConstructor(type) ? ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.EXCEPTION_NEW),
					chain, ClojureLowering.NIL_CONST, ClojureLowering.NIL_CONST) : null;
			case 1 -> messageOrCauseConstruction(type, chain, args.get(0));
			case 2 -> onlyConstructorsAtArity(type, 2, List.of(String.class, Throwable.class)) ? ClojureLowerUtil
				.list(new LispSymbol(ClojureStateLowering.EXCEPTION_NEW), chain, args.get(0), args.get(1)) : null;
			default -> null;
		};
		if (call != null) {
			ctx.usedExInfo = true;
			ctx.hostExceptionClasses.add(type.getName());
		}
		return call;
	}

	/**
	 * {@code (Class. x)}: a run-time choice between the message and the cause when the
	 * class's one-argument constructors are exactly {@code (String)} and
	 * {@code (Throwable)}, the message alone when {@code (String)} is the only one, and a
	 * literal string the message wherever a one-argument constructor takes a string (the
	 * oracle resolves a literal at compile time: {@code (AssertionError. "m")} is its
	 * {@code (Object)} constructor). Anything else is null.
	 */
	private static @Nullable LispVal messageOrCauseConstruction(Class<?> type, LispVal chain, LispVal arg) {
		if (onlyConstructorsAtArity(type, 1, List.of(String.class, Throwable.class))) {
			return ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.EXCEPTION_NEW_1), chain, arg);
		}
		boolean literal = arg instanceof LispString;
		if (onlyConstructorsAtArity(type, 1, List.of(String.class)) || (literal && takesAString(type))) {
			return ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.EXCEPTION_NEW), chain, arg,
					ClojureLowering.NIL_CONST);
		}
		return null;
	}

	/**
	 * The class a throwable construction may build as an exception condition: a public,
	 * concrete {@code Throwable} that carries nothing beyond a message and a cause -- no
	 * public field, and no public method but {@code Throwable}'s and {@code Object}'s (an
	 * override counts as {@code Throwable}'s) -- so the condition answers every member a
	 * program can call on it. Null for any other class, or none.
	 */
	static @Nullable Class<?> plainThrowable(String cls) {
		Class<?> type;
		try {
			type = Class.forName(cls, false, ClojureLowering.class.getClassLoader());
		}
		catch (ClassNotFoundException | LinkageError _) {
			return null;
		}
		if (!Throwable.class.isAssignableFrom(type) || !java.lang.reflect.Modifier.isPublic(type.getModifiers())
				|| java.lang.reflect.Modifier.isAbstract(type.getModifiers()) || type.getFields().length > 0) {
			return null;
		}
		for (java.lang.reflect.Method method : type.getMethods()) {
			Class<?> owner = method.getDeclaringClass();
			if (owner != Throwable.class && owner != Object.class && !overridesThrowable(method)) {
				return null;
			}
		}
		return type;
	}

	private static boolean overridesThrowable(java.lang.reflect.Method method) {
		if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
			return false;
		}
		try {
			Throwable.class.getMethod(method.getName(), method.getParameterTypes());
			return true;
		}
		catch (NoSuchMethodException _) {
			return false;
		}
	}

	private static boolean hasConstructor(Class<?> type, Class<?>... parameters) {
		try {
			type.getConstructor(parameters);
			return true;
		}
		catch (NoSuchMethodException _) {
			return false;
		}
	}

	/**
	 * Whether the public constructors of {@code arity} parameters are exactly the given
	 * ones: each single parameter type of {@code shapes} at arity one, or the one
	 * parameter list {@code shapes} at arity two.
	 */
	private static boolean onlyConstructorsAtArity(Class<?> type, int arity, List<Class<?>> shapes) {
		Set<List<Class<?>>> wanted = new HashSet<>();
		if (arity == 1) {
			for (Class<?> shape : shapes) {
				wanted.add(List.of(shape));
			}
		}
		else {
			wanted.add(shapes);
		}
		Set<List<Class<?>>> found = new HashSet<>();
		for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
			if (constructor.getParameterCount() == arity) {
				found.add(List.of(constructor.getParameterTypes()));
			}
		}
		return found.equals(wanted);
	}

	private static boolean takesAString(Class<?> type) {
		for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
			if (constructor.getParameterCount() == 1
					&& constructor.getParameterTypes()[0].isAssignableFrom(String.class)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code (. target method args...)}: a static call when the target symbol names a
	 * class (dotted, imported, {@code java.lang} or capitalized), an instance call
	 * otherwise. A {@code (method args...)} list spells the call, with no further
	 * arguments beside it.
	 */
	static LispVal dotForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, ". takes a target, a method and arguments");
		LispVal target = items.get(1);
		LispVal member = items.get(2);
		String method;
		List<LispVal> argDatums;
		List<LispVal> nested = ClojureLowerUtil.items(member);
		if (nested != null && !nested.isEmpty()) {
			ClojureLowerUtil.isTrue(items.size() == 3, ". with a call list takes no further arguments");
			if (!(nested.get(0) instanceof LispSymbol called)) {
				throw new LispReadException(". takes a method name, not " + nested.get(0).print());
			}
			method = called.name();
			argDatums = nested.subList(1, nested.size());
		}
		else {
			if (!(member instanceof LispSymbol called)) {
				throw new LispReadException(". takes a method name, not " + member.print());
			}
			method = called.name();
			argDatums = items.subList(3, items.size());
		}
		if (target instanceof LispSymbol named && ClojureNamespaceLowering.isClasslike(ctx, named.name())) {
			if (argDatums.isEmpty()) {
				// no arguments: the zero-argument static method when the host
				// class has one, else the static field read -- the same rule the
				// (Class/member) spelling follows, so (. Math PI) reads the field
				return staticNoArg(ctx, ClojureNamespaceLowering.resolveClass(ctx, named.name()), method);
			}
			List<LispVal> lowered = new ArrayList<>();
			for (LispVal arg : argDatums) {
				lowered.add(ctx.lower(arg));
			}
			return staticCall(ctx, ClojureNamespaceLowering.resolveClass(ctx, named.name()), method, lowered);
		}
		return instanceCall(ctx, ctx.lower(target), method, argDatums);
	}

	/**
	 * {@code (.. target (step args...) name...)}: the steps threaded left to right, each
	 * lowered as it goes so a step's declared return type carries the known class to the
	 * next one. The first step over a classlike target stays the static path, like
	 * {@code .}; the rest are instance calls.
	 */
	static LispVal dotDotOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, ".. takes a target and steps");
		LispVal target = items.get(1);
		if (target instanceof LispSymbol named && ClojureNamespaceLowering.isClasslike(ctx, named.name())) {
			if (items.size() == 2) {
				return ctx.lower(target);
			}
			String cls = ClojureNamespaceLowering.resolveClass(ctx, named.name());
			ClojureLowering.DotStep first = dotStep(items.get(2));
			List<LispVal> loweredFirst = new ArrayList<>();
			for (LispVal arg : first.args()) {
				loweredFirst.add(ctx.lower(arg));
			}
			LispVal cur = loweredFirst.isEmpty() ? staticNoArg(ctx, cls, first.method())
					: staticCall(ctx, cls, first.method(), loweredFirst);
			@Nullable String curClass = declaredStaticReturn(cls, first.method(), loweredFirst.size());
			for (int i = 3; i < items.size(); i++) {
				ClojureLowering.DotStep step = dotStep(items.get(i));
				List<LispVal> loweredArgs = new ArrayList<>();
				for (LispVal arg : step.args()) {
					loweredArgs.add(ctx.lower(arg));
				}
				cur = instanceCallLoweredWithClass(ctx, cur, curClass, step.method(), loweredArgs);
				curClass = declaredInstanceReturn(curClass, step.method(), loweredArgs.size());
			}
			return cur;
		}
		LispVal cur = ctx.lower(target);
		@Nullable String curClass = classOfLowered(ctx, cur);
		for (int i = 2; i < items.size(); i++) {
			ClojureLowering.DotStep step = dotStep(items.get(i));
			List<LispVal> loweredArgs = new ArrayList<>();
			for (LispVal arg : step.args()) {
				loweredArgs.add(ctx.lower(arg));
			}
			cur = instanceCallLoweredWithClass(ctx, cur, curClass, step.method(), loweredArgs);
			curClass = declaredInstanceReturn(curClass, step.method(), loweredArgs.size());
		}
		return cur;
	}

	/** Parses a {@code ..} step datum into its method name and argument datums. */
	static ClojureLowering.DotStep dotStep(LispVal step) {
		List<LispVal> parts = ClojureLowerUtil.items(step);
		if (parts != null && !parts.isEmpty()) {
			if (!(parts.get(0) instanceof LispSymbol called)) {
				throw new LispReadException(".. takes method names and call lists, not " + step.print());
			}
			return new ClojureLowering.DotStep(called.name(), parts.subList(1, parts.size()));
		}
		if (step instanceof LispSymbol bare) {
			return new ClojureLowering.DotStep(bare.name(), List.of());
		}
		throw new LispReadException(".. takes method names and call lists, not " + step.print());
	}

	/**
	 * Reads the host class once: whether {@code member} is a public static field, the
	 * sorted distinct fixed arities of its public static non-variadic methods, the subset
	 * whose overloads all answer a boolean, and whether a variadic one exists. An
	 * unloadable class answers all absent, so the call sites keep their old shape and the
	 * run-time error names the class.
	 */
	static ClojureLowering.StaticMember staticMember(String className, String member) {
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			boolean field = false;
			try {
				field = java.lang.reflect.Modifier.isStatic(found.getField(member).getModifiers());
			}
			catch (NoSuchFieldException _) {
				// no field of that name: the methods decide below
			}
			Set<Integer> arities = new HashSet<>();
			Map<Integer, Boolean> booleanByArity = new HashMap<>();
			boolean variadic = false;
			for (java.lang.reflect.Method method : found.getMethods()) {
				if (!method.getName().equals(member) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
						|| method.isSynthetic()) {
					continue;
				}
				if (method.isVarArgs()) {
					variadic = true;
				}
				else {
					int fixed = method.getParameterCount();
					arities.add(fixed);
					booleanByArity.merge(fixed, method.getReturnType() == Boolean.TYPE, (a, b) -> a && b);
				}
			}
			List<Integer> sorted = new ArrayList<>(arities);
			sorted.sort(Integer::compareTo);
			Set<Integer> booleanArities = new HashSet<>();
			for (Map.Entry<Integer, Boolean> entry : booleanByArity.entrySet()) {
				if (entry.getValue()) {
					booleanArities.add(entry.getKey());
				}
			}
			return new ClojureLowering.StaticMember(field, sorted, booleanArities, variadic);
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return new ClojureLowering.StaticMember(false, List.of(), Set.of(), false);
		}
	}

	/**
	 * A static call through {@code java:static}: a boolean answer is {@code T}-or-false
	 * when every overload at that arity answers a boolean, like every predicate value.
	 */
	static LispVal staticCall(ClojureLowering ctx, String cls, String member, List<LispVal> args) {
		return staticCall(ctx, cls, member, member, args);
	}

	/**
	 * {@link #staticCall(ClojureLowering, String, String, List)} under a {@code java:}
	 * designator ({@link #designator}); the boolean rule still reads the bare member.
	 */
	static LispVal staticCall(ClojureLowering ctx, String cls, String member, String designator, List<LispVal> args) {
		List<LispVal> call = new ArrayList<>();
		call.add(LispString.literal(cls));
		call.add(LispString.literal(designator));
		call.addAll(args);
		LispVal run = ClojureLowerUtil.cons(JAVA_STATIC, call);
		if (staticMember(cls, member).booleanArities().contains(args.size())) {
			return ctx.booleanAnswer(run);
		}
		return run;
	}

	/**
	 * {@code (Class/member)} or {@code (. Class member)} with no arguments: the
	 * zero-argument static method when the host class has one (a static call through
	 * {@code java:static}), else the static field read through {@code java:field} (whose
	 * run-time error names an unknown member or class, like before). The method wins a
	 * field of the same name, like the oracle's unified resolution; a boolean answer is
	 * {@code T}-or-false, like every predicate value.
	 */
	static LispVal staticNoArg(ClojureLowering ctx, String cls, String member) {
		List<LispVal> args = new ArrayList<>();
		args.add(LispString.literal(cls));
		args.add(LispString.literal(member));
		ClojureLowering.StaticMember seen = staticMember(cls, member);
		if (seen.arities().contains(0)) {
			LispVal call = ClojureLowerUtil.cons(JAVA_STATIC, args);
			return seen.booleanArities().contains(0) ? ctx.booleanAnswer(call) : call;
		}
		return ClojureLowerUtil.cons(JAVA_FIELD, args);
	}

	/**
	 * A {@code Class/member} name in value position: the static field read when the host
	 * class has that field, else a member-as-value lambda dispatching by argument count
	 * over the static call (so {@code (every? Character/isWhitespace s)} runs). A member
	 * with only variadic overloads is refused by name (no rest-spread reaches
	 * {@code java:static}); an unknown class or member reads the field, whose run-time
	 * error names what is missing. {@code Class/.method} is a lambda taking the target
	 * first, {@code Class/new} one constructing ({@link #memberValue}). Null when the
	 * name is no classlike slash form, so the call keeps falling through to the
	 * unknown-name refusal.
	 */
	static @Nullable LispVal interopValue(ClojureLowering ctx, String name) {
		return memberValue(ctx, name, null);
	}

	/**
	 * {@link #interopValue} under optional {@code ^[types]} param tags: tagged, the
	 * lambda takes exactly the tagged count (plus the target of an instance method) and
	 * calls the one overload the designator names, like the oracle's tagged value.
	 * Untagged {@code Class/.method} and {@code Class/new} dispatch per fixed arity of
	 * the public instance methods or constructors, and a name with none is refused, like
	 * the oracle's {@code no matches found}.
	 */
	static @Nullable LispVal memberValue(ClojureLowering ctx, String name, @Nullable LispVal tags) {
		QualifiedMember qualified = qualifiedMember(ctx, name);
		if (qualified == null) {
			return null;
		}
		if (qualified.typeKey() != null) {
			// R/new is the record or deftype's positional constructor, like ->R
			return ctx.lower(new LispSymbol(constructorSpelling(ctx, qualified.typeKey())));
		}
		String cls = qualified.cls();
		String member = qualified.member();
		@Nullable List<String> types = tags == null ? null : tagTypes(ctx, tags);
		if (member.equals("new")) {
			List<Integer> arities = types != null ? List.of(types.size()) : constructorArities(cls, name);
			return arityLambda(ctx, arities, name, args -> hostConstructionLowered(ctx, cls, args, types));
		}
		if (member.startsWith(".")) {
			String method = member.substring(1);
			List<Integer> arities = types != null ? List.of(types.size() + 1) : instanceArities(cls, method, name);
			return arityLambda(ctx, arities, name, args -> instanceCallLoweredWithClass(ctx, args.get(0), cls, method,
					designator(method, types), args.subList(1, args.size())));
		}
		if (types != null) {
			return arityLambda(ctx, List.of(types.size()), name,
					args -> staticCall(ctx, cls, member, designator(member, types), args));
		}
		ClojureLowering.StaticMember seen = staticMember(cls, member);
		if (seen.field()) {
			return ClojureLowerUtil.cons(JAVA_FIELD, List.of(LispString.literal(cls), LispString.literal(member)));
		}
		if (!seen.arities().isEmpty()) {
			return arityLambda(ctx, seen.arities(), name, args -> staticCall(ctx, cls, member, args));
		}
		if (seen.variadic()) {
			throw new LispReadException(name + " is variadic and has no value form");
		}
		return ClojureLowerUtil.cons(JAVA_FIELD, List.of(LispString.literal(cls), LispString.literal(member)));
	}

	/**
	 * The sorted distinct fixed arities of a class's public instance methods of that
	 * name, each counting the target. Refuses a name with only variadic ones (no
	 * rest-spread reaches {@code java:call}) or with none, or an unloadable class.
	 */
	static List<Integer> instanceArities(String className, String method, String spelling) {
		Set<Integer> arities = new HashSet<>();
		boolean variadic = false;
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			for (java.lang.reflect.Method candidate : found.getMethods()) {
				if (!candidate.getName().equals(method) || java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
						|| candidate.isSynthetic()) {
					continue;
				}
				if (candidate.isVarArgs()) {
					variadic = true;
				}
				else {
					arities.add(candidate.getParameterCount() + 1);
				}
			}
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			throw new LispReadException(spelling + ": no class " + className);
		}
		return sortedArities(arities, variadic, spelling,
				"no matches found for instance method " + method + " in class " + className);
	}

	/**
	 * The sorted distinct fixed arities of a class's public constructors, refused like
	 * {@link #instanceArities}.
	 */
	static List<Integer> constructorArities(String className, String spelling) {
		Set<Integer> arities = new HashSet<>();
		boolean variadic = false;
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			for (java.lang.reflect.Constructor<?> candidate : found.getConstructors()) {
				if (candidate.isVarArgs()) {
					variadic = true;
				}
				else {
					arities.add(candidate.getParameterCount());
				}
			}
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			throw new LispReadException(spelling + ": no class " + className);
		}
		return sortedArities(arities, variadic, spelling, "no matches found for constructor in class " + className);
	}

	private static List<Integer> sortedArities(Set<Integer> arities, boolean variadic, String spelling, String none) {
		if (arities.isEmpty()) {
			throw new LispReadException(spelling + (variadic ? " is variadic and has no value form" : ": " + none));
		}
		List<Integer> sorted = new ArrayList<>(arities);
		sorted.sort(Integer::compareTo);
		return sorted;
	}

	/**
	 * {@link #hostConstruction} over already-lowered arguments: the zero-argument
	 * {@code java.io.StringWriter} a string output stream, an untagged plain throwable an
	 * exception, anything else {@code java:new}.
	 */
	static LispVal hostConstructionLowered(ClojureLowering ctx, String cls, List<LispVal> args,
			@Nullable List<String> types) {
		LispVal stringWriter = stringWriterConstruction(cls, args.size());
		if (stringWriter != null) {
			return stringWriter;
		}
		if (types == null) {
			LispVal exception = throwableConstruction(ctx, cls, args);
			if (exception != null) {
				return exception;
			}
		}
		List<LispVal> call = new ArrayList<>();
		call.add(LispString.literal(designator(cls, types)));
		call.addAll(args);
		return ClojureLowerUtil.cons(JAVA_NEW, call);
	}

	/**
	 * A bare class name in value position ({@code String}, an imported class, a dotted
	 * name): the class object, the same {@code Class.forName} answer the oracle's class
	 * literal prints and compares by. Null when the name is no loadable class (a typo
	 * keeps the unknown-name refusal at compile time) or names a record or deftype.
	 */
	static @Nullable LispVal classValue(ClojureLowering ctx, String name) {
		if (name.indexOf('/') >= 0 || !ClojureNamespaceLowering.isClasslike(ctx, name) || ctx.typeKeyOf(name) != null) {
			return null;
		}
		String cls = ClojureNamespaceLowering.resolveClass(ctx, name);
		try {
			Class.forName(cls, false, ClojureLowering.class.getClassLoader());
		}
		catch (ClassNotFoundException | LinkageError _) {
			return null;
		}
		return ClojureLowerUtil.cons(JAVA_STATIC,
				List.of(LispString.literal("java.lang.Class"), LispString.literal("forName"), LispString.literal(cls)));
	}

	/**
	 * The member-as-value lambda: one {@code &rest} parameter dispatched per known fixed
	 * arity onto the arm {@code call} builds over that many argument forms (for a static
	 * member the run-time overload selection picks among same-arity overloads), any other
	 * count the wrong-argument-count error, like a multi-arity {@code defn} dispatch. The
	 * arms answer booleans {@code T}-or-false, like every predicate value, so
	 * {@code (map Character/isWhitespace ...)} prints {@code (true false)}.
	 */
	static LispVal arityLambda(ClojureLowering ctx, List<Integer> arities, String spelling,
			java.util.function.Function<List<LispVal>, LispVal> call) {
		LispSymbol args = ctx.freshTemp();
		LispSymbol count = ctx.freshTemp();
		List<LispVal> arms = new ArrayList<>();
		for (int arity : arities) {
			List<LispVal> argForms = new ArrayList<>();
			for (int p = 0; p < arity; p++) {
				argForms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("NTH"), new LispInteger(p), args));
			}
			arms.add(ClojureLowerUtil.list(
					ClojureLowerUtil.list(ClojureLowerUtil.sym("="), count, new LispInteger(arity)),
					call.apply(argForms)));
		}
		arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("wrong number of arguments passed to: " + spelling))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(count,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), args)))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms)));
	}

	/** {@code (new Class args...)}: construction through {@code java:new}. */
	static LispVal newOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "new takes a class and arguments");
		if (!(items.get(1) instanceof LispSymbol named)) {
			throw new LispReadException("new takes a class name, not " + items.get(1).print());
		}
		String type = ctx.typeKeyOf(named.name());
		if (type != null) {
			// (new T args...) constructs the record or deftype, like ->T
			return ctx.lower(ClojureLowerUtil.cons(new LispSymbol(constructorSpelling(ctx, type)),
					items.subList(2, items.size())));
		}
		String cls = ClojureNamespaceLowering.resolveClass(ctx, named.name());
		return hostConstruction(ctx, cls, items.subList(2, items.size()), null);
	}

	/**
	 * A zero-argument {@code java.io.StringWriter} construction (dotted or imported,
	 * resolved): a fresh string output stream ({@code %clojure-string-writer}, a stream
	 * producer), so every backend runs it -- wasm included, which never sees the refused
	 * {@code java:new}. Anything else (an initial-capacity argument, another class) is
	 * null, so the call keeps its {@code java:new} shape.
	 */
	static @Nullable LispVal stringWriterConstruction(String cls, int argCount) {
		if (cls.equals(STRING_WRITER_CLASS) && argCount == 0) {
			return ClojureLowerUtil.list(new LispSymbol(STRING_WRITER));
		}
		return null;
	}

	/**
	 * The host reader classes a construction of which over a Common Lisp character input
	 * stream answers the stream itself: such a stream already peeks one character, which
	 * is all {@code read} needs, so {@code (java.io.PushbackReader. (reader path))} reads
	 * on every backend.
	 */
	static final Set<String> READER_WRAPPERS = Set.of("java.io.PushbackReader", "java.io.BufferedReader");

	/**
	 * A {@code java.io.PushbackReader}/{@code java.io.BufferedReader} construction (an
	 * optional buffer size behind the reader): over a {@code java.io.StringReader}
	 * construction a string input stream of its text, on every backend; over a Common
	 * Lisp stream (a {@code clojure.java.io/reader}, {@code *in*}, a nested wrapper) that
	 * stream -- decided at run time unless the argument lowers to one; over anything else
	 * the host construction, like before. Null for any other class or count, so the call
	 * keeps its {@code java:new} shape.
	 * @param ctx the hub
	 * @param cls the resolved class name
	 * @param args the argument datums
	 * @return the lowered construction, or null
	 */
	static @Nullable LispVal readerWrapperConstruction(ClojureLowering ctx, String cls, List<LispVal> args) {
		if (!READER_WRAPPERS.contains(cls) || args.isEmpty() || args.size() > 2) {
			return null;
		}
		LispVal text = stringReaderText(ctx, args.get(0));
		LispVal reader = text != null ? ClojureLowerUtil.list(new LispSymbol(STRING_READER), ctx.lower(text))
				: ctx.lower(args.get(0));
		if (args.size() == 1 && isStreamForm(reader)) {
			return reader;
		}
		LispSymbol source = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(source, reader));
		List<LispVal> host = new ArrayList<>();
		host.add(LispString.literal(cls));
		host.add(source);
		if (args.size() == 2) {
			LispSymbol size = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(size, ctx.lower(args.get(1))));
			host.add(size);
		}
		LispVal body = isStreamForm(reader) ? source
				: ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("streamp"), source), source,
						ClojureLowerUtil.cons(JAVA_NEW, host));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), body);
	}

	/**
	 * Whether a lowered form surely answers a Common Lisp character input stream: a
	 * {@code clojure.java.io/reader}, a string input stream over a
	 * {@code java.io.StringReader}, a read of {@code *in*}, or a binding form ending in
	 * one (a nested reader wrapper).
	 */
	static boolean isStreamForm(LispVal lowered) {
		List<LispVal> items = ClojureLowerUtil.items(lowered);
		if (items == null || items.isEmpty()) {
			return false;
		}
		if (ClojureLowerUtil.isSymbolNamed(items.get(0), ClojureNamespaceLowering.READER)
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), STRING_READER)
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), STANDARD_INPUT_READ)) {
			return true;
		}
		return items.size() == 3 && ClojureLowerUtil.isSymbolNamed(items.get(0), "LET*") && isStreamForm(items.get(2));
	}

	/**
	 * The text datum of a one-argument {@code java.io.StringReader} construction
	 * ({@code (StringReader. text)} dotted or imported, {@code (StringReader/new text)}
	 * or {@code (new StringReader text)}), or null for anything else.
	 */
	static @Nullable LispVal stringReaderText(ClojureLowering ctx, LispVal datum) {
		List<LispVal> items = ClojureLowerUtil.items(ClojureLowerUtil.stripMeta(datum));
		if (items == null || items.size() < 2 || !(items.get(0) instanceof LispSymbol head)) {
			return null;
		}
		String spelled;
		if (head.name().equals("new") && items.size() == 3 && items.get(1) instanceof LispSymbol named) {
			spelled = named.name();
		}
		else if (head.name().endsWith(".") && head.name().length() > 1 && items.size() == 2) {
			spelled = head.name().substring(0, head.name().length() - 1);
		}
		else if (head.name().endsWith("/new") && head.name().length() > 4 && items.size() == 2) {
			spelled = head.name().substring(0, head.name().length() - 4);
		}
		else {
			return null;
		}
		if (!isClassSpelling(spelled) || ctx.typeKeyOf(spelled) != null
				|| !ClojureNamespaceLowering.resolveClass(ctx, spelled).equals("java.io.StringReader")) {
			return null;
		}
		return items.get(items.size() - 1);
	}

	/**
	 * How the current namespace names a record or deftype's positional constructor:
	 * {@code ->T} for its own, {@code ns/->T} for another namespace's.
	 */
	static String constructorSpelling(ClojureLowering ctx, String typeKey) {
		int slash = typeKey.indexOf('/');
		String ns = typeKey.substring(0, slash);
		String ctor = "->" + typeKey.substring(slash + 1);
		return ns.equals(ctx.currentNs) ? ctor : ns + "/" + ctor;
	}

	/**
	 * {@code (make-array Class dim...)}: a general array over the dimensions -- the class
	 * spells the element type and is ignored, every array here is general (the book's
	 * {@code interop.clj} {@code painstakingly-create-array} shape). One dimension is the
	 * scalar, several the dimension list, like the oracle's separate-argument shape; only
	 * the Clojure spellings are new, the array itself compiles on all four backends.
	 */
	static LispVal makeArrayOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "make-array takes a class and dimensions");
		if (!(items.get(1) instanceof LispSymbol)) {
			throw new LispReadException("make-array takes a class name, not " + items.get(1).print());
		}
		List<LispVal> dims = ctx.lowers(items, 2);
		LispVal shape = dims.size() == 1 ? dims.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), dims);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("make-array"), shape);
	}

	/** {@code (aget array index...)}: the element, through {@code aref}. */
	static LispVal agetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "aget takes an array and subscripts");
		List<LispVal> ref = new ArrayList<>();
		ref.add(ClojureLowerUtil.sym("aref"));
		ref.addAll(ctx.lowers(items, 1));
		return ClojureLowerUtil.list(ref);
	}

	/** {@code (aset array index... value)}: the write, through {@code (setf aref)}. */
	static LispVal asetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 4, "aset takes an array, subscripts and a value");
		List<LispVal> ref = new ArrayList<>();
		ref.add(ClojureLowerUtil.sym("aref"));
		for (int i = 1; i < items.size() - 1; i++) {
			ref.add(ctx.lower(items.get(i)));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"), ClojureLowerUtil.list(ref),
				ctx.lower(items.get(items.size() - 1)));
	}

	/** {@code (alength array)}: the zeroth dimension, through {@code array-dimension}. */
	static LispVal alengthOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "alength takes an array");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("array-dimension"), ctx.lower(items.get(1)),
				new LispInteger(0));
	}

	/**
	 * {@code (.-field target)}: a record or deftype answers its field table's entry (a
	 * missing or mutable field signals in the oracle's words); anything else takes the
	 * host field path, like before.
	 */
	static LispVal fieldRead(ClojureLowering ctx, LispVal target, String field) {
		LispSymbol one = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal table = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isReifyForm(one),
				ClojureLowering.NIL_CONST, ClojureProtocolLowering.typedTableOf(one));
		LispVal read = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
								ClojureCollectionLowering.keywordForm(field), table, miss)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, miss),
						ClojureValueMethodLowering.refusal(one, field, true, List.of()), got));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, target),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isTypedForm(one), read,
						ClojureLowerUtil.cons(JAVA_FIELD, List.of(one, LispString.literal(field)))));
	}

	/**
	 * {@code (memfn name arg...)}: a function of a target and the named arguments calling
	 * the method on it -- the oracle's expansion, so {@code ((memfn toUpperCase) "hi")}
	 * answers {@code "HI"}. String receivers take the mapped core operation, like any
	 * other instance call.
	 */
	static LispVal memfnOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "memfn takes a method name and argument names");
		String method = ClojureLowerUtil.plainName(items.get(1), "memfn");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		Set<String> seen = new HashSet<>();
		List<LispVal> params = new ArrayList<>();
		LispSymbol recv = ctx.freshTemp();
		params.add(recv);
		List<LispVal> argForms = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			String pname = ClojureLowerUtil.plainName(items.get(i), "memfn");
			ClojureLowerUtil.isTrue(seen.add(pname), "memfn argument names must be distinct: " + pname);
			scope.put(pname, ClojureLowering.Kind.VARIABLE);
			params.add(ctx.localSym(pname));
			argForms.add(ctx.localSym(pname));
		}
		return ctx.inScope(scope, () -> ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(params), instanceCallLowered(ctx, recv, method, argForms)));
	}

	/**
	 * {@code (proxy [interface...] [] (method [params...] body...)...)}: one object
	 * implementing every interface through {@code java:proxy} with a name-dispatching
	 * lambda, so a method name several interfaces declare runs the one body. A
	 * superclass, constructor arguments, an {@code Object} method
	 * ({@code toString}/{@code equals}/{@code hashCode}) and multi-arity methods are
	 * refused by name; the methods take the Java arguments only (no {@code this}, which
	 * has no binding to close over). Interpreter and JVM only, like all interop.
	 */
	static LispVal proxyOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "proxy takes a class vector, an argument vector and methods");
		List<LispVal> classes = ClojureLowerUtil.items(items.get(1));
		if (classes == null || classes.isEmpty() || classes.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("proxy takes a class vector, not " + items.get(1).print());
		}
		if (classes.size() < 2) {
			throw new LispReadException("proxy takes at least one interface");
		}
		List<LispVal> javaProxy = new ArrayList<>();
		for (LispVal className : classes.subList(1, classes.size())) {
			if (!(className instanceof LispSymbol symbol)) {
				throw new LispReadException("proxy takes interface names, not " + className.print());
			}
			String iface = ClojureNamespaceLowering.resolveClass(ctx, symbol.name());
			if (isHostClass(iface)) {
				if (className == classes.get(1)) {
					return proxyClassOf(ctx, items, classes, iface);
				}
				throw new LispReadException(
						"proxy takes a single superclass and interfaces: " + iface + " is a class, not an interface");
			}
			javaProxy.add(LispString.literal(iface));
		}
		List<LispVal> argv = ClojureLowerUtil.items(items.get(2));
		if (argv == null || argv.size() != 1) {
			throw new LispReadException("proxy constructor arguments are not supported yet: " + items.get(2).print());
		}
		LispSymbol all = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispVal miss = ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"), ClojureLowerUtil.quoted("string"),
						LispString.literal("no proxy method: "), got));
		LispVal dispatch = miss;
		for (int i = items.size() - 1; i >= 3; i--) {
			List<LispVal> meth = ClojureLowerUtil.items(items.get(i));
			if (meth == null || meth.size() < 2 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			List<LispVal> params = ClojureLowerUtil.items(meth.get(1));
			if (params == null || params.isEmpty() || params.get(0) != ClojureReader.VECTOR) {
				throw new LispReadException("a proxy method takes a parameter vector, not " + meth.get(1).print());
			}
			if (isObjectMethod(methodName, params.size() - 1)) {
				// java:proxy keeps Object's three: the body would never run.
				throw new LispReadException("proxy cannot override " + methodName
						+ " yet: a proxy keeps Object's equals, hashCode and toString");
			}
			Map<String, ClojureLowering.Kind> scope = new HashMap<>();
			Set<String> seen = new HashSet<>();
			List<LispVal> fnParams = new ArrayList<>();
			for (int j = 1; j < params.size(); j++) {
				String pname = ClojureLowerUtil.plainName(params.get(j), "proxy");
				ClojureLowerUtil.isTrue(seen.add(pname), "proxy parameter names must be distinct: " + pname);
				scope.put(pname, ClojureLowering.Kind.VARIABLE);
				fnParams.add(ctx.localSym(pname));
			}
			Map<String, ClojureLowering.Kind> use = new HashMap<>(scope);
			LispVal run = ctx
				.inScope(use,
						() -> ClojureLowerUtil.list(
								ClojureLowerUtil.sym("apply"), ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
										ClojureLowerUtil.list(fnParams), ctx.bodyOf(meth.subList(2, meth.size()))),
								rest));
			LispVal test = ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"), got, LispString.literal(methodName));
			dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), test, run, dispatch);
		}
		LispVal callable = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, all)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(List.of(
							ClojureLowerUtil.list(got, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), all)),
							ClojureLowerUtil.list(rest, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), all)))),
							dispatch));
		javaProxy.add(callable);
		return ClojureLowerUtil.cons(JAVA_PROXY, javaProxy);
	}

	// Object's equals(Object), hashCode() and toString(), which java:proxy never routes
	// to
	// the callable.
	private static boolean isObjectMethod(String name, int arity) {
		return switch (name) {
			case "toString", "hashCode" -> arity == 0;
			case "equals" -> arity == 1;
			default -> false;
		};
	}

	// Whether the name loads, here, as a final class: a proxy superclass must be
	// extensible. A name that does not load is left to the run-time error.
	private static boolean isFinalClass(String className) {
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			return !found.isInterface() && java.lang.reflect.Modifier.isFinal(found.getModifiers());
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return false;
		}
	}

	// Whether the name loads, here, as a class that is not an interface: a proxy
	// superclass. A name that does not load is left to the run-time error.
	private static boolean isHostClass(String className) {
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			return !found.isInterface();
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return false;
		}
	}

	/**
	 * {@code (proxy [superclass interface...] [args...] (method [params...] body...)...)}:
	 * one object extending the superclass through {@code java:subclass} with a
	 * name-dispatching lambda taking the object first, so a name several interfaces
	 * declare runs the one body, as the oracle's proxy does. Each body sees {@code this}
	 * (the proxy object) and calls the superclass implementation through
	 * {@code (proxy-super method args...)}. A method left out is inherited when the class
	 * chain implements it, else refused with the method's name when it is called;
	 * {@code toString}/{@code equals}/{@code hashCode} run their bodies, like the oracle.
	 * Interpreter and JVM only, like all interop.
	 */
	static LispVal proxyClassOf(ClojureLowering ctx, List<LispVal> items, List<LispVal> classes, String superclass) {
		if (isFinalClass(superclass)) {
			throw new LispReadException("proxy cannot extend final class " + superclass);
		}
		List<LispVal> javaSubclass = new ArrayList<>();
		javaSubclass.add(LispString.literal(superclass));
		List<LispVal> ifaceNames = new ArrayList<>();
		for (LispVal className : classes.subList(2, classes.size())) {
			if (!(className instanceof LispSymbol symbol)) {
				throw new LispReadException("proxy takes interface names, not " + className.print());
			}
			String iface = ClojureNamespaceLowering.resolveClass(ctx, symbol.name());
			if (isHostClass(iface)) {
				throw new LispReadException(
						"proxy takes a single superclass and interfaces: " + iface + " is a class, not an interface");
			}
			ifaceNames.add(LispString.literal(iface));
		}
		javaSubclass.add(quotedList(ifaceNames));
		List<LispVal> argv = ClojureLowerUtil.items(items.get(2));
		if (argv == null || argv.isEmpty() || argv.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("proxy takes an argument vector, not " + items.get(2).print());
		}
		List<LispVal> ctorArgs = new ArrayList<>();
		for (LispVal arg : argv.subList(1, argv.size())) {
			ctorArgs.add(ctx.lower(arg));
		}
		List<String> methodNames = new ArrayList<>();
		for (int i = 3; i < items.size(); i++) {
			List<LispVal> meth = ClojureLowerUtil.items(items.get(i));
			if (meth == null || meth.size() < 2 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			if (methodNames.contains(methodName)) {
				throw new LispReadException("proxy defines method " + methodName + " twice");
			}
			methodNames.add(methodName);
		}
		LispSymbol thisSym = ctx.localSym("this");
		LispSymbol got = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispVal miss = ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"), ClojureLowerUtil.quoted("string"),
						LispString.literal("no proxy method: "), got));
		LispVal dispatch = miss;
		for (int i = items.size() - 1; i >= 3; i--) {
			List<LispVal> meth = ClojureLowerUtil.items(items.get(i));
			if (meth == null || meth.size() < 2 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			List<LispVal> params = ClojureLowerUtil.items(meth.get(1));
			if (params == null || params.isEmpty() || params.get(0) != ClojureReader.VECTOR) {
				throw new LispReadException("a proxy method takes a parameter vector, not " + meth.get(1).print());
			}
			Map<String, ClojureLowering.Kind> scope = new HashMap<>();
			Set<String> seen = new HashSet<>();
			scope.put("this", ClojureLowering.Kind.VARIABLE);
			List<LispVal> fnParams = new ArrayList<>();
			fnParams.add(thisSym);
			for (int j = 1; j < params.size(); j++) {
				String pname = ClojureLowerUtil.plainName(params.get(j), "proxy");
				ClojureLowerUtil.isTrue(seen.add(pname), "proxy parameter names must be distinct: " + pname);
				scope.put(pname, ClojureLowering.Kind.VARIABLE);
				fnParams.add(ctx.localSym(pname));
			}
			Map<String, ClojureLowering.Kind> use = new HashMap<>(scope);
			ctx.proxyMethods.push(new ClojureLowering.ProxyMethod(thisSym));
			LispVal run;
			try {
				run = ctx.inScope(use,
						() -> ClojureLowerUtil.list(
								ClojureLowerUtil.sym("apply"), ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
										ClojureLowerUtil.list(fnParams), ctx.bodyOf(meth.subList(2, meth.size()))),
								thisSym, rest));
			}
			finally {
				ctx.proxyMethods.pop();
			}
			LispVal test = ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"), got, LispString.literal(methodName));
			dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), test, run, dispatch);
		}
		List<LispVal> methodLiterals = new ArrayList<>();
		for (String name : methodNames) {
			methodLiterals.add(LispString.literal(name));
		}
		javaSubclass.add(quotedList(methodLiterals));
		javaSubclass.addAll(ctorArgs);
		LispVal callable = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(thisSym, got, ClojureLowering.AMPERSAND_REST, rest)), dispatch);
		javaSubclass.add(callable);
		return ClojureLowerUtil.cons(JAVA_SUBCLASS, javaSubclass);
	}

	// A quoted list of literals, as java:subclass takes its interface and method names.
	private static LispVal quotedList(List<LispVal> names) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(names));
	}

	/**
	 * {@code (proxy-super method args...)} inside a proxy method body: the superclass
	 * implementation on {@code this}, through the generated {@code super} accessor of the
	 * method's arity. Outside a proxy method body it is refused by name.
	 */
	static LispVal proxySuperOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowering.ProxyMethod method = ctx.proxyMethods.peekLast();
		if (method == null) {
			throw new LispReadException("proxy-super outside a proxy method");
		}
		ClojureLowerUtil.isTrue(items.size() >= 2, "proxy-super takes a method and arguments");
		if (!(items.get(1) instanceof LispSymbol target)) {
			throw new LispReadException("proxy-super takes a method name, not " + items.get(1).print());
		}
		List<LispVal> args = new ArrayList<>();
		args.add(method.self);
		// Mirrors compiler/JavaImplementations.superAccessor: one accessor per (name,
		// arity), so the lowering needs no types to spell it.
		args.add(LispString.literal("super$" + target.name() + "$" + (items.size() - 2)));
		for (LispVal arg : items.subList(2, items.size())) {
			args.add(ctx.lower(arg));
		}
		return ClojureLowerUtil.cons(JAVA_CALL, args);
	}

	/**
	 * An instance call: the receiver runs once, behind a temporary; a string receiver
	 * answers the mapped core operation, which runs on every backend, and so does a
	 * collection, keyword, symbol, ratio or atom ({@link ClojureValueMethodLowering});
	 * anything else goes to {@code java:call} directly, which calls a string, number or
	 * character as its {@code String}, box or {@code Character}.
	 */
	static LispVal instanceCall(ClojureLowering ctx, LispVal receiver, String method, List<LispVal> argDatums) {
		List<LispVal> args = new ArrayList<>();
		for (LispVal arg : argDatums) {
			args.add(ctx.lower(arg));
		}
		return instanceCallLowered(ctx, receiver, method, args);
	}

	/**
	 * An instance call over already-lowered forms: the receiver runs once, behind a
	 * temporary; a string receiver answers the mapped core operation, anything else goes
	 * to {@code java:call} directly. When the receiver's class is known -- a construction
	 * literal, a {@code let}/{@code if-let}/{@code when-let} local bound to one, or a
	 * {@code ..} step's declared return -- and every overload at that arity answers a
	 * primitive boolean, the call answers {@code T}-or-false, like every predicate value
	 * (the shared {@code java:} unmarshal still maps a host false to nil underneath); any
	 * other receiver keeps the unmarshal.
	 */
	static LispVal instanceCallLowered(ClojureLowering ctx, LispVal receiver, String method, List<LispVal> args) {
		return instanceCallLoweredWithClass(ctx, receiver, null, method, args);
	}

	/**
	 * An instance call with an explicit known receiver class (a {@code ..} step's
	 * declared return): null to read it off the receiver instead, like
	 * {@link #instanceCallLowered}.
	 */
	static LispVal instanceCallLoweredWithClass(ClojureLowering ctx, LispVal receiver, @Nullable String knownClass,
			String method, List<LispVal> args) {
		return instanceCallLoweredWithClass(ctx, receiver, knownClass, method, method, args);
	}

	/**
	 * {@link #instanceCallLoweredWithClass(ClojureLowering, LispVal, String, String, List)}
	 * with the {@code java:call} under a designator ({@link #designator}); the string,
	 * stream and boolean rules still read the bare method.
	 */
	static LispVal instanceCallLoweredWithClass(ClojureLowering ctx, LispVal receiver, @Nullable String knownClass,
			String method, String designator, List<LispVal> args) {
		LispSymbol recv = ctx.freshTemp();
		List<LispVal> direct = new ArrayList<>();
		direct.add(recv);
		direct.add(LispString.literal(designator));
		direct.addAll(args);
		LispVal hostCall = ClojureLowerUtil.cons(JAVA_CALL, direct);
		LispVal call = hostCall;
		String cls = knownClass;
		if (cls == null) {
			cls = constructedClass(receiver);
			if (cls == null && receiver instanceof LispSymbol ref) {
				cls = hostClassOf(ctx, ref);
			}
		}
		if (args.isEmpty() && EXCEPTION_METHODS.contains(method) && (cls == null || plainThrowable(cls) != null)) {
			// a caught runtime error, an ex-info and a throwable construction are
			// conditions: the library answers from the exception, and calls the host
			// method on anything else
			ctx.usedExInfo = true;
			return ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.EXCEPTION_METHOD), receiver,
					LispString.literal(method));
		}
		if (args.isEmpty() && STACK_TRACE_METHODS.containsKey(method) && (cls == null || plainThrowable(cls) != null)) {
			// a condition answers its toString line and no frames; anything else calls
			// the host method
			return ClojureLowerUtil.list(new LispSymbol(STACK_TRACE_METHODS.get(method)), receiver);
		}
		if (cls != null && instanceBooleanAtArity(cls, method, args.size())) {
			call = ctx.booleanAnswer(call);
		}
		if (method.equals("toString") && args.isEmpty()) {
			call = valueToString(recv);
		}
		LispVal stream = streamMethod(ctx, method, recv, args);
		if (stream != null) {
			call = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("streamp"), recv), stream, call);
		}
		if (cls == null) {
			call = valuePredicate(ctx, recv, method, args.size(), hostCall, call);
			if (!(method.equals("toString") && args.isEmpty())) {
				// a collection, keyword, symbol or ratio has no host object: its common
				// methods answer through the core verbs, any other is refused by name
				call = ClojureValueMethodLowering.valueArm(ctx, method, recv, args, call);
			}
		}
		if (method.equals("getClass") && args.isEmpty()) {
			call = ClojureDispatchLowering.getClassForm(ctx, recv, cls, call);
		}
		LispVal mapped = stringMethod(ctx, method, recv, args, cls != null || receiver instanceof LispString);
		if (mapped == null && cls == null && instanceBooleanAtArity("java.lang.String", method, args.size())) {
			// An unmapped String predicate (matches, regionMatches, ...) on a string
			// answers T-or-false, as on a receiver known to be a String.
			mapped = ctx.booleanAnswer(hostCall);
		}
		LispVal out = mapped == null ? call : ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), recv), mapped, call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(recv, receiver))), out);
	}

	/**
	 * A receiver of no known class that is a number or a character is called as its box
	 * or {@code Character} (an integer as an {@code Integer} or a {@code Long}, by size):
	 * where every overload of the method at this arity on each of those classes answers a
	 * primitive boolean, such a receiver answers {@code T}-or-false, like a known
	 * receiver ({@code (.isNaN 1.5)}, {@code (.equals 1 2)}); anything else keeps the
	 * call. A string takes its own arm ({@link #stringMethod}).
	 */
	static LispVal valuePredicate(ClojureLowering ctx, LispSymbol recv, String method, int arity, LispVal hostCall,
			LispVal call) {
		List<LispVal> tests = new ArrayList<>();
		if (instanceBooleanAtArity("java.lang.Integer", method, arity)
				&& instanceBooleanAtArity("java.lang.Long", method, arity)) {
			tests.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("integerp"), recv));
		}
		if (instanceBooleanAtArity("java.lang.Double", method, arity)) {
			tests.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("floatp"), recv));
		}
		if (instanceBooleanAtArity("java.lang.Character", method, arity)) {
			tests.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), recv));
		}
		if (tests.isEmpty()) {
			return call;
		}
		LispVal test = tests.size() == 1 ? tests.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), tests);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), test, ctx.booleanAnswer(hostCall), call);
	}

	/**
	 * {@code toString} over an already-bound receiver that is no string: a stream answers
	 * its toString ({@code %clojure-stream-string}: a string output stream's text so far
	 * without clearing it, any other stream its host class name; the test is an arm of
	 * {@code ClojureArms.Family.STREAM}, and an exact tag test, since {@code streamp}
	 * answers true for {@code t} and, on the JVM, for a host object whose {@code equals}
	 * answers true); a value of a Lisp kind -- number, character, symbol (keywords and
	 * booleans included), cons (lists, keywords, records, lazy seqs), array, table,
	 * function, condition (an exception's report is its {@code toString}) -- answers its
	 * {@code str} spelling, the oracle's {@code toString}, on every backend; nil signals,
	 * like the oracle's {@code NullPointerException}; anything else is a host object and
	 * keeps the {@code java:call}. No predicate here answers true for a host object (a
	 * host collection is no Lisp array or table on the JVM either), so the host path is
	 * exactly what it was.
	 */
	static LispVal valueToString(LispSymbol recv) {
		List<LispVal> direct = new ArrayList<>();
		direct.add(recv);
		direct.add(LispString.literal("toString"));
		LispVal call = ClojureLowerUtil.cons(JAVA_CALL, direct);
		LispVal lispValue = ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("symbolp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("arrayp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("typep"), recv,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("condition"))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), recv),
						ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER,
								LispString.literal("NullPointerException: toString of nil"))),
				ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(STREAM_P), recv),
						ClojureLowerUtil.list(new LispSymbol(STREAM_STRING), recv)),
				ClojureLowerUtil
					.list(lispValue,
							ClojureLowerUtil.list(ClojureLowering.CLOJURE_STR_OF, recv, LispString.literal("nil"),
									ClojureLowering.NIL_CONST)),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, call));
	}

	/**
	 * The class a lowered receiver constructs, when it is a construction literal: a
	 * {@code java:new} over a literal class name. No user form lowers to that head
	 * (anything else spelling it is an unknown name), so the class is read off the call
	 * site with no scope analysis.
	 */
	static @Nullable String constructedClass(LispVal receiver) {
		if (receiver instanceof LispCons cell && ClojureLowerUtil.isSymbolNamed(cell.car(), "JAVA:NEW")
				&& cell.cdr() instanceof LispCons rest && rest.car() instanceof LispString cls) {
			// a param-tagged construction names the class before its parameter types
			int tagged = cls.value().indexOf('(');
			return tagged < 0 ? cls.value() : cls.value().substring(0, tagged);
		}
		return null;
	}

	/**
	 * A {@code let}-bound name's host class, or null when no visible binding holds one: a
	 * binding above the recording depth shadows it, like any other scope rule, and
	 * anything else was never recorded.
	 */
	static @Nullable String hostClassOf(ClojureLowering ctx, LispSymbol ref) {
		ClojureLowering.HostClass held = ctx.hostClasses.get(ref.name());
		if (held == null) {
			return null;
		}
		for (int i = held.depth(); i < ctx.scopes.size(); i++) {
			if (ctx.scopes.get(i).containsKey(held.name())) {
				return null;
			}
		}
		return held.fqn();
	}

	/**
	 * Whether every fixed-arity overload of {@code member} at {@code arity} on the host
	 * class answers a primitive boolean: the instance-call half of the static
	 * {@code T}-or-false rule ({@link #staticMember}). A {@code java:call} may reach a
	 * static through an instance, so statics count too; a boxed answer never qualifies
	 * (it may be null, which the oracle reads as nil, not false). An unloadable class
	 * answers false, so the call keeps its old shape and the run-time error names the
	 * class.
	 */
	static boolean instanceBooleanAtArity(String className, String member, int arity) {
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			boolean seen = false;
			for (java.lang.reflect.Method method : found.getMethods()) {
				if (!method.getName().equals(member) || method.isSynthetic() || method.isVarArgs()
						|| method.getParameterCount() != arity) {
					continue;
				}
				if (method.getReturnType() != Boolean.TYPE) {
					return false;
				}
				seen = true;
			}
			return seen;
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return false;
		}
	}

	/**
	 * A lowered receiver's known host class, when it is one: a construction literal or a
	 * recorded local. A {@code ..} step's {@code let} wrapper is never one -- the chain
	 * threads the declared return instead.
	 */
	static @Nullable String classOfLowered(ClojureLowering ctx, LispVal receiver) {
		String cls = constructedClass(receiver);
		if (cls == null && receiver instanceof LispSymbol ref) {
			cls = hostClassOf(ctx, ref);
		}
		return cls;
	}

	/**
	 * A {@code ..} step's receiver class for the next step: the single declared return
	 * type shared by every non-variadic overload at that arity, or null when there is
	 * none, several disagree, or it is no host class (a primitive, void, an array, or an
	 * unloadable class). Primitives stay unknown: a boolean answer is already a Lisp
	 * value, so no further host call wraps it.
	 */
	static @Nullable String declaredInstanceReturn(@Nullable String className, String member, int arity) {
		if (className == null) {
			return null;
		}
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			Set<String> returns = new HashSet<>();
			boolean seen = false;
			for (java.lang.reflect.Method method : found.getMethods()) {
				if (!method.getName().equals(member) || method.isSynthetic() || method.isVarArgs()
						|| method.getParameterCount() != arity) {
					continue;
				}
				seen = true;
				Class<?> ret = method.getReturnType();
				if (ret.isPrimitive() || ret == Void.TYPE || ret.isArray()) {
					return null;
				}
				returns.add(ret.getName());
			}
			return seen && returns.size() == 1 ? returns.iterator().next() : null;
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return null;
		}
	}

	/**
	 * The same single-return rule for a {@code ..} chain's static first step: the
	 * zero-argument static method's return when one exists, else the static field's type,
	 * else null. Anything else keeps the chain unknown, like an instance step with no
	 * single return.
	 */
	static @Nullable String declaredStaticReturn(String className, String member, int arity) {
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			if (arity == 0) {
				Set<String> returns = new HashSet<>();
				boolean seen = false;
				for (java.lang.reflect.Method method : found.getMethods()) {
					if (!method.getName().equals(member) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
							|| method.isSynthetic() || method.isVarArgs() || method.getParameterCount() != 0) {
						continue;
					}
					seen = true;
					Class<?> ret = method.getReturnType();
					if (ret.isPrimitive() || ret == Void.TYPE || ret.isArray()) {
						return null;
					}
					returns.add(ret.getName());
				}
				if (seen) {
					return returns.size() == 1 ? returns.iterator().next() : null;
				}
				try {
					java.lang.reflect.Field field = found.getField(member);
					if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
						Class<?> type = field.getType();
						return type.isPrimitive() || type.isArray() ? null : type.getName();
					}
				}
				catch (NoSuchFieldException _) {
					// no field either: unknown, like an unknown member
				}
				return null;
			}
			Set<String> returns = new HashSet<>();
			boolean seen = false;
			for (java.lang.reflect.Method method : found.getMethods()) {
				if (!method.getName().equals(member) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
						|| method.isSynthetic() || method.isVarArgs() || method.getParameterCount() != arity) {
					continue;
				}
				seen = true;
				Class<?> ret = method.getReturnType();
				if (ret.isPrimitive() || ret == Void.TYPE || ret.isArray()) {
					return null;
				}
				returns.add(ret.getName());
			}
			return seen && returns.size() == 1 ? returns.iterator().next() : null;
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return null;
		}
	}

	/**
	 * A stream method over an already-bound receiver: {@code write} prints through
	 * {@code princ} (strings bare, characters as glyphs), {@code flush} finishes the
	 * output, {@code readLine} reads through {@code read-line} (nil past the end, like
	 * the oracle), {@code read} answers one character's code ({@code -1} past the end)
	 * and {@code close} closes the stream, so {@code with-open} over a
	 * {@code clojure.java.io/reader} (an {@code open} file stream) runs on every backend
	 * without reaching {@code java:call}. {@code toString} is {@link #valueToString}'s.
	 * Null when the method maps to nothing, so the call goes to {@code java:call}.
	 */
	static @Nullable LispVal streamMethod(ClojureLowering ctx, String method, LispSymbol recv, List<LispVal> args) {
		if (method.equals("write") && args.size() == 1) {
			// nil signals, like the oracle's NullPointerException out of Writer.write
			LispSymbol value = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(ClojureLowerUtil.list(value, args.get(0))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), value),
							ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER,
									LispString.literal("NullPointerException: write takes a value, not nil")),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("princ"), value, recv)));
		}
		if (method.equals("flush") && args.isEmpty()) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("finish-output"), recv);
		}
		if (method.equals("readLine") && args.isEmpty()) {
			// nil past the end, like the oracle (a Java reader takes the java:call path)
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("read-line"), recv, ClojureLowering.NIL_CONST,
					ClojureLowering.NIL_CONST);
		}
		if (method.equals("close") && args.isEmpty()) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("close"), recv);
		}
		if (method.equals("read") && args.isEmpty()) {
			// one character's code, -1 past the end, like Reader.read
			LispSymbol c = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(ClojureLowerUtil.list(c,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("read-char"), recv, ClojureLowering.NIL_CONST,
									ClojureLowering.NIL_CONST))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), c,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("char-code"), c), new LispInteger(-1)));
		}
		return null;
	}

	/**
	 * A {@code String} instance method over an already-bound string receiver: the core
	 * operation answering what the oracle answers (a missing {@code indexOf} is
	 * {@code -1}, like the oracle, not the {@code nil} {@code clojure.string} favors).
	 * Null when the method maps to nothing, so the call goes to {@code java:call}.
	 */
	static @Nullable LispVal stringMethod(ClojureLowering ctx, String method, LispVal recv, List<LispVal> args,
			boolean typed) {
		return switch (method) {
			case "toUpperCase" ->
				args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-upcase"), recv) : null;
			case "toLowerCase" ->
				args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-downcase"), recv) : null;
			case "trim", "strip" -> args.isEmpty()
					? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-trim"), ClojureStringLowering.trimBag(), recv)
					: null;
			case "stripLeading" -> args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-left-trim"),
					ClojureStringLowering.trimBag(), recv) : null;
			case "stripTrailing" -> args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-right-trim"),
					ClojureStringLowering.trimBag(), recv) : null;
			case "length" -> args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), recv) : null;
			case "isEmpty" -> args.isEmpty() ? ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), recv))) : null;
			case "isBlank" -> args.isEmpty() ? ctx.booleanAnswer(ClojureStringLowering.blankForm(recv)) : null;
			case "toString" -> args.isEmpty() ? recv : null;
			case "getClass" ->
				args.isEmpty() ? ClojureLowerUtil.cons(JAVA_STATIC, List.of(LispString.literal("java.lang.Class"),
						LispString.literal("forName"), LispString.literal("java.lang.String"))) : null;
			case "substring" -> switch (args.size()) {
				case 1 -> ClojureLowerUtil.list(
						new LispSymbol(typed ? ClojureRefusals.SUBS : ClojureRefusals.SUBS_BY_REFLECTION), recv,
						ClojureStringLowering.bound(args.get(0)));
				case 2 -> ClojureLowerUtil.list(
						new LispSymbol(typed ? ClojureRefusals.SUBS : ClojureRefusals.SUBS_BY_REFLECTION), recv,
						ClojureStringLowering.bound(args.get(0)), ClojureStringLowering.bound(args.get(1)));
				default -> null;
			};
			case "charAt" -> args.size() == 1 ? ClojureLowerUtil.list(
					new LispSymbol(typed ? ClojureRefusals.CHAR_AT : ClojureRefusals.CHAR_AT_BY_REFLECTION), recv,
					ClojureStringLowering.bound(args.get(0))) : null;
			case "equals" -> args.size() == 1
					? ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("string="), recv, args.get(0)))
					: null;
			case "equalsIgnoreCase" -> args.size() == 1
					? ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("string-equal"), recv, args.get(0)))
					: null;
			case "contains" -> args.size() == 1
					? ctx.booleanAnswer(ClojureStringLowering.affixForm("includes?", recv, args.get(0))) : null;
			case "startsWith" -> args.size() == 1
					? ctx.booleanAnswer(ClojureStringLowering.affixForm("starts-with?", recv, args.get(0))) : null;
			case "endsWith" -> args.size() == 1
					? ctx.booleanAnswer(ClojureStringLowering.affixForm("ends-with?", recv, args.get(0))) : null;
			case "indexOf" -> stringIndexForm(ctx, recv, args, false);
			case "lastIndexOf" -> stringIndexForm(ctx, recv, args, true);
			case "replace" ->
				args.size() == 2 ? ClojureStringLowering.replaceForm(ctx, recv, args.get(0), args.get(1), false) : null;
			case "replaceFirst" ->
				args.size() == 2 ? ClojureStringLowering.replaceForm(ctx, recv, args.get(0), args.get(1), true) : null;
			case "split" -> switch (args.size()) {
				case 1 -> ClojureStringLowering.splitForm(ctx, recv, args.get(0), ClojureLowering.NIL_CONST, true);
				case 2 -> ClojureStringLowering.splitForm(ctx, recv, args.get(0), args.get(1), true);
				default -> null;
			};
			case "concat" -> args.size() == 1 ? ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"),
					ClojureLowerUtil.quoted("string"), recv, args.get(0)) : null;
			case "repeat" -> args.size() == 1 ? ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("concatenate")),
					ClojureLowerUtil.quoted("string"), ClojureLowerUtil.list(ClojureLowerUtil.sym("make-list"),
							args.get(0), ClojureLowerUtil.sym(":initial-element"), recv))
					: null;
			default -> null;
		};
	}

	/**
	 * {@code indexOf} / {@code lastIndexOf} over an already-bound string: the index, or
	 * {@code -1} when missing, like the oracle.
	 */
	static @Nullable LispVal stringIndexForm(ClojureLowering ctx, LispVal recv, List<LispVal> args, boolean last) {
		LispVal search = switch (args.size()) {
			case 1 -> last ? ClojureStringLowering.lastIndexForm(recv, args.get(0), null)
					: ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), args.get(0), recv);
			case 2 -> last ? ClojureStringLowering.lastIndexForm(recv, args.get(0), args.get(1)) : ClojureLowerUtil
				.list(ClojureLowerUtil.sym("search"), args.get(0), recv, ClojureLowerUtil.sym(":start2"), args.get(1));
			default -> null;
		};
		if (search == null) {
			return null;
		}
		LispSymbol at = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(at, search))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), at), new LispInteger(-1), at));
	}

	/** Whether the spelling can name a class: a dotted name over identifier parts. */
	static boolean isClassSpelling(String spelling) {
		if (spelling.isEmpty() || !Character.isJavaIdentifierStart(spelling.charAt(0))) {
			return false;
		}
		for (int i = 1; i < spelling.length(); i++) {
			char c = spelling.charAt(i);
			if (!Character.isJavaIdentifierPart(c) && c != '.') {
				return false;
			}
		}
		return true;
	}

}
