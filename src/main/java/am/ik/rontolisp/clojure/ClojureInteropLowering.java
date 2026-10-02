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
			List<LispVal> args = new ArrayList<>();
			args.add(LispString
				.literal(ClojureNamespaceLowering.resolveClass(ctx, name.substring(0, name.length() - 1))));
			args.addAll(ctx.lowers(items, 1));
			return ClojureLowerUtil.cons(JAVA_NEW, args);
		}
		int slash = name.indexOf('/');
		if (slash > 0) {
			String head = name.substring(0, slash);
			String tail = name.substring(slash + 1);
			if (!tail.isEmpty() && tail.indexOf('/') < 0 && ClojureNamespaceLowering.isClasslike(ctx, head)
					&& ctx.typeKeyOf(head) == null) {
				String cls = ClojureNamespaceLowering.resolveClass(ctx, head);
				if (items.size() == 1) {
					// no arguments: the zero-argument static method when the host
					// class has one, else the static field read (whose run-time
					// error names an unknown member or class, like before)
					return staticNoArg(ctx, cls, tail);
				}
				List<LispVal> args = new ArrayList<>();
				args.addAll(ctx.lowers(items, 1));
				return staticCall(ctx, cls, tail, args);
			}
		}
		return null;
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
		List<LispVal> call = new ArrayList<>();
		call.add(LispString.literal(cls));
		call.add(LispString.literal(member));
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
	 * error names what is missing. Null when the name is no classlike slash form, so the
	 * call keeps falling through to the unknown-name refusal.
	 */
	static @Nullable LispVal interopValue(ClojureLowering ctx, String name) {
		int slash = name.indexOf('/');
		if (slash <= 0) {
			return null;
		}
		String head = name.substring(0, slash);
		String tail = name.substring(slash + 1);
		if (tail.isEmpty() || tail.indexOf('/') >= 0 || !ClojureNamespaceLowering.isClasslike(ctx, head)
				|| ctx.typeKeyOf(head) != null) {
			return null;
		}
		String cls = ClojureNamespaceLowering.resolveClass(ctx, head);
		ClojureLowering.StaticMember seen = staticMember(cls, name.substring(slash + 1));
		if (seen.field()) {
			return ClojureLowerUtil.cons(JAVA_FIELD, List.of(LispString.literal(cls), LispString.literal(tail)));
		}
		if (!seen.arities().isEmpty()) {
			return memberLambda(ctx, cls, tail, name, seen);
		}
		if (seen.variadic()) {
			throw new LispReadException(name + " is variadic and has no value form");
		}
		return ClojureLowerUtil.cons(JAVA_FIELD, List.of(LispString.literal(cls), LispString.literal(tail)));
	}

	/**
	 * The member-as-value lambda: one {@code &rest} parameter dispatched per known fixed
	 * arity onto the static call (the run-time overload selection picks among same-arity
	 * overloads), any other count the wrong-argument-count error, like a multi-arity
	 * {@code defn} dispatch. A boolean answer is {@code T}-or-false, like every predicate
	 * value, so {@code (map Character/isWhitespace ...)} prints {@code (true false)}.
	 */
	static LispVal memberLambda(ClojureLowering ctx, String cls, String member, String spelling,
			ClojureLowering.StaticMember seen) {
		LispSymbol args = ctx.freshTemp();
		LispSymbol count = ctx.freshTemp();
		List<LispVal> arms = new ArrayList<>();
		for (int arity : seen.arities()) {
			List<LispVal> call = new ArrayList<>();
			call.add(LispString.literal(cls));
			call.add(LispString.literal(member));
			for (int p = 0; p < arity; p++) {
				call.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("NTH"), new LispInteger(p), args));
			}
			LispVal run = ClojureLowerUtil.cons(JAVA_STATIC, call);
			if (seen.booleanArities().contains(arity)) {
				run = ctx.booleanAnswer(run);
			}
			arms.add(ClojureLowerUtil
				.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("="), count, new LispInteger(arity)), run));
		}
		arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
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
		List<LispVal> args = new ArrayList<>();
		args.add(LispString.literal(ClojureNamespaceLowering.resolveClass(ctx, named.name())));
		args.addAll(ctx.lowers(items, 2));
		return ClojureLowerUtil.cons(JAVA_NEW, args);
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
	 * {@code (.-field target)}: a record or deftype answers its field table's entry
	 * (missing fields signal, like the oracle); anything else takes the host field path,
	 * like before.
	 */
	static LispVal fieldRead(ClojureLowering ctx, LispVal target, String field) {
		LispSymbol one = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal table = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isReifyForm(one),
				ClojureLowering.NIL_CONST, ClojureProtocolLowering.typedTableOf(one));
		LispVal read = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
									ClojureCollectionLowering.keywordForm(field), table, miss)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, miss), ClojureLowerUtil
								.list(ClojureLowerUtil.sym("error"), LispString.literal("No such field: " + field)),
							got));
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
			params.add(ClojureLowerUtil.idSym(pname));
			argForms.add(ClojureLowerUtil.idSym(pname));
		}
		return ctx.inScope(scope, () -> ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(params), instanceCallLowered(ctx, recv, method, argForms)));
	}

	/**
	 * {@code (proxy [interface] [] (method [params...] body...)...)}: a single interface
	 * implemented through {@code java:proxy} with a name-dispatching lambda. A
	 * superclass, constructor arguments and multi-arity methods are refused by name; the
	 * methods take the Java arguments only (no {@code this}, which has no binding to
	 * close over). Interpreter and JVM only, like all interop.
	 */
	static LispVal proxyOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "proxy takes a class vector, an argument vector and methods");
		List<LispVal> classes = ClojureLowerUtil.items(items.get(1));
		if (classes == null || classes.isEmpty() || classes.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("proxy takes a class vector, not " + items.get(1).print());
		}
		if (classes.size() != 2 || !(classes.get(1) instanceof LispSymbol)) {
			throw new LispReadException("proxy takes a single interface, not " + items.get(1).print());
		}
		LispSymbol className = (LispSymbol) classes.get(1);
		List<LispVal> argv = ClojureLowerUtil.items(items.get(2));
		if (argv == null || argv.size() != 1) {
			throw new LispReadException("proxy constructor arguments are not supported yet: " + items.get(2).print());
		}
		String iface = ClojureNamespaceLowering.resolveClass(ctx, className.name());
		LispSymbol all = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispVal miss = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"), ClojureLowerUtil.quoted("string"),
						LispString.literal("no proxy method: "), got));
		LispVal dispatch = miss;
		for (int i = items.size() - 1; i >= 3; i--) {
			List<LispVal> meth = ClojureLowerUtil.items(items.get(i));
			if (meth == null || meth.size() < 3 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			List<LispVal> params = ClojureLowerUtil.items(meth.get(1));
			if (params == null || params.isEmpty() || params.get(0) != ClojureReader.VECTOR) {
				throw new LispReadException("a proxy method takes a parameter vector, not " + meth.get(1).print());
			}
			Map<String, ClojureLowering.Kind> scope = new HashMap<>();
			Set<String> seen = new HashSet<>();
			List<LispVal> fnParams = new ArrayList<>();
			for (int j = 1; j < params.size(); j++) {
				String pname = ClojureLowerUtil.plainName(params.get(j), "proxy");
				ClojureLowerUtil.isTrue(seen.add(pname), "proxy parameter names must be distinct: " + pname);
				scope.put(pname, ClojureLowering.Kind.VARIABLE);
				fnParams.add(ClojureLowerUtil.idSym(pname));
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
		return ClojureLowerUtil.cons(JAVA_PROXY, List.of(LispString.literal(iface), callable));
	}

	/**
	 * An instance call: the receiver runs once, behind a temporary; a string receiver
	 * answers the mapped core operation (a Lisp string is not a host object, so the
	 * {@code java:} surface cannot take it), anything else goes to {@code java:call}
	 * directly.
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
		LispSymbol recv = ctx.freshTemp();
		List<LispVal> direct = new ArrayList<>();
		direct.add(recv);
		direct.add(LispString.literal(method));
		direct.addAll(args);
		LispVal call = ClojureLowerUtil.cons(JAVA_CALL, direct);
		String cls = knownClass;
		if (cls == null) {
			cls = constructedClass(receiver);
			if (cls == null && receiver instanceof LispSymbol ref) {
				cls = hostClassOf(ctx, ref);
			}
		}
		if (cls != null && instanceBooleanAtArity(cls, method, args.size())) {
			call = ctx.booleanAnswer(call);
		}
		LispVal stream = streamMethod(ctx, method, recv, args);
		if (stream != null) {
			call = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("streamp"), recv), stream, call);
		}
		LispVal mapped = stringMethod(ctx, method, recv, args);
		LispVal out = mapped == null ? call : ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), recv), mapped, call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(recv, receiver))), out);
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
			return cls.value();
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
	 * the oracle) and {@code close} closes the stream, so {@code with-open} over a
	 * {@code clojure.java.io/reader} (an {@code open} file stream) runs on every backend
	 * without reaching {@code java:call}. Null when the method maps to nothing, so the
	 * call goes to {@code java:call}.
	 */
	static @Nullable LispVal streamMethod(ClojureLowering ctx, String method, LispVal recv, List<LispVal> args) {
		if (method.equals("write") && args.size() == 1) {
			// nil signals, like the oracle's NullPointerException out of Writer.write
			LispSymbol value = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(ClojureLowerUtil.list(value, args.get(0))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), value),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
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
		return null;
	}

	/**
	 * A {@code String} instance method over an already-bound string receiver: the core
	 * operation answering what the oracle answers (a missing {@code indexOf} is
	 * {@code -1}, like the oracle, not the {@code nil} {@code clojure.string} favors).
	 * Null when the method maps to nothing, so the call goes to {@code java:call}.
	 */
	static @Nullable LispVal stringMethod(ClojureLowering ctx, String method, LispVal recv, List<LispVal> args) {
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
			case "substring" -> switch (args.size()) {
				case 1 -> ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), recv, args.get(0));
				case 2 -> ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), recv, args.get(0), args.get(1));
				default -> null;
			};
			case "charAt" ->
				args.size() == 1 ? ClojureLowerUtil.list(ClojureLowerUtil.sym("char"), recv, args.get(0)) : null;
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
