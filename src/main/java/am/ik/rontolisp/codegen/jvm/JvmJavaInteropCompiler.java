package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.JavaImplementation;
import am.ik.rontolisp.compiler.JavaImplementations;
import am.ik.rontolisp.compiler.JavaSite;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the seven {@code java:} interop functions ({@code java:new},
 * {@code java:call}, {@code java:static}, {@code java:field}, {@code java:proxy},
 * {@code java:reify}, {@code java:subclass}). A site the shared resolver resolves at
 * compile time ({@link JvmJavaSites}) becomes a DIRECT call: the site evaluates its
 * receiver and arguments and calls its own method ({@link JvmJavaDirectSites}), which
 * checks them, converts them and invokes the member with plain bytecode, exactly as the
 * interpreter runs the same site. A {@code java:reify} or {@code java:proxy} whose
 * interface resolves evaluates its functions and calls the factory of the class generated
 * for it ({@link JvmJavaImplementations}). Any other site -- left to run time -- calls
 * the {@link JavaBridgeTemplate bridge class} shipped beside the program: it first
 * invokes the emitted {@code _javaInit} helper (which binds the program into the bridge,
 * see {@link JvmJavaRuntimeBuilder}), then evaluates the arguments -- the leading fixed
 * arguments as-is and the variadic tail packed into an {@code Object[]} -- and calls the
 * matching bridge entry point, which resolves by reflection from the receiver's run-time
 * class and the argument kinds. Under {@code --java-static} such a site is a compile
 * error instead.
 */
final class JvmJavaInteropCompiler {

	private JvmJavaInteropCompiler() {
	}

	/**
	 * Returns whether the given {@code java} package member is one of the seven interop
	 * functions this compiler handles.
	 */
	static boolean handles(String member) {
		return LispNames.JAVA_NEW.equals(member) || LispNames.JAVA_CALL.equals(member)
				|| LispNames.JAVA_STATIC.equals(member) || LispNames.JAVA_FIELD.equals(member)
				|| LispNames.JAVA_PROXY.equals(member) || LispNames.JAVA_REIFY.equals(member)
				|| LispNames.JAVA_SUBCLASS.equals(member);
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		switch (member) {
			case LispNames.JAVA_NEW -> requireArity(args.size() >= 2, "java:new expects (java:new \"class\" args...)");
			case LispNames.JAVA_CALL ->
				requireArity(args.size() >= 3, "java:call expects (java:call object \"method\" args...)");
			case LispNames.JAVA_STATIC ->
				requireArity(args.size() >= 3, "java:static expects (java:static \"class\" \"method\" args...)");
			case LispNames.JAVA_FIELD ->
				requireArity(args.size() == 3, "java:field expects (java:field class-or-object \"field\")");
			case LispNames.JAVA_PROXY -> requireArity(args.size() >= 3, JavaImplementations.PROXY_USAGE);
			case LispNames.JAVA_REIFY ->
				requireArity(args.size() >= 2 && args.size() % 2 == 0, JavaImplementations.REIFY_USAGE);
			case LispNames.JAVA_SUBCLASS -> requireArity(args.size() >= 5, JavaImplementations.SUBCLASS_USAGE);
			default -> throw new UnsupportedOperationException("Cannot compile: java:" + member);
		}
		JvmJavaSites sites = Objects.requireNonNull(ctx.javaSites, "the java: sites were not prepared");
		boolean implementsInterface = LispNames.JAVA_PROXY.equals(member) || LispNames.JAVA_REIFY.equals(member)
				|| LispNames.JAVA_SUBCLASS.equals(member);
		String bridgeReason = sites.bridgeReason(cons);
		if (bridgeReason != null && sites.javaStatic()) {
			// Refused: the attempt fails once every site has been seen; the value only
			// keeps the method being compiled well-formed until then.
			sites.refuse(cons, bridgeReason);
			ctx.body.aconst_null();
			return;
		}
		if (implementsInterface) {
			// How the form implements its interface (compiler/JavaImplementations, the
			// interpreter's rule): a resolved one makes an object of its generated class.
			JavaImplementation implementation = sites.implementation(cons);
			if (implementation.resolved()) {
				if (implementation.isSubclass()) {
					compileSubclass(implementation, args, ctx, className);
				}
				else {
					compileImplementation(implementation, args, ctx, className);
				}
				return;
			}
		}
		else {
			// How the site resolves (compiler/JavaSiteResolver, the interpreter's
			// resolver): a resolved site is compiled as the direct call of the member
			// chosen, which the interpreter runs it as too
			// (eval/JavaInterop.invokeResolved).
			JavaSite site = sites.resolve(cons);
			if (site.resolved()) {
				compileDirect(site, args, ctx, className);
				return;
			}
		}
		Map<String, MethodRefEntry> ops = ctx.javaOps;
		if (ops == null) {
			// This attempt carries no bridge (every site it predicted was direct): a call
			// to
			// the absent _javaInit makes the helper-gate check retry with the bridge.
			ctx.body
				.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(className), JvmJavaRuntimeBuilder.INIT_METHOD, "()V"));
			ctx.body.aconst_null();
			return;
		}
		// Make sure the bridge class is defined before its method reference resolves.
		ctx.body.invokestatic(Objects.requireNonNull(ops.get("init")));
		switch (member) {
			case LispNames.JAVA_NEW -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				compileArgumentArray(args, 2, ctx, className);
				emitBridgeCall(ctx, ops, "new");
			}
			case LispNames.JAVA_CALL -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				// The receiver may itself be a packed array ((java:call arr "clone")),
				// and Java reads it raw: materialize it like every other argument.
				emitMaterialize(ctx);
				// A condition standing for a host exception is called as that exception.
				emitHostException(ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				compileArgumentArray(args, 3, ctx, className);
				emitBridgeCall(ctx, ops, "call");
			}
			case LispNames.JAVA_STATIC -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				compileArgumentArray(args, 3, ctx, className);
				emitBridgeCall(ctx, ops, "static");
			}
			case LispNames.JAVA_FIELD -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				emitBridgeCall(ctx, ops, "field");
			}
			case LispNames.JAVA_REIFY -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				compileRestArray(args, 2, ctx, className);
				emitBridgeCall(ctx, ops, "reify");
			}
			default -> {
				if (LispNames.JAVA_SUBCLASS.equals(member)) {
					// The superclass, the two quoted lists, the evaluated constructor
					// arguments and the callable, last: the bridge refuses it by name.
					JvmExprCompiler.compileExpr(args.get(1), ctx, className);
					compileRestArray(args, 2, ctx, className);
					emitBridgeCall(ctx, ops, "subclass");
					return;
				}
				// The first interface, then the rest of them and the callable, last.
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				compileRestArray(args, 2, ctx, className);
				emitBridgeCall(ctx, ops, "proxy");
			}
		}
	}

	/**
	 * A resolved {@code java:reify} / {@code java:proxy}: its functions are evaluated
	 * left to right into an {@code Object[]} -- the names written beside them are
	 * literals and evaluate to nothing -- which the generated class's factory makes the
	 * object of.
	 */
	private static void compileImplementation(JavaImplementation implementation, List<LispVal> args,
			JvmLispCompiler.Ctx ctx, String className) {
		JvmJavaSites sites = Objects.requireNonNull(ctx.javaSites);
		List<LispVal> functions = new java.util.ArrayList<>();
		if (implementation.proxy()) {
			functions.add(args.get(args.size() - 1));
		}
		else {
			for (int i = 3; i < args.size(); i += 2) {
				functions.add(args.get(i));
			}
		}
		MethodRefEntry factory = sites.implementations().factory(implementation);
		JvmEmitHelper.emitIntConst(ctx, functions.size());
		ctx.body.anewarray(ctx.objectClass);
		for (int i = 0; i < functions.size(); i++) {
			ctx.body.dup();
			JvmEmitHelper.emitIntConst(ctx, i);
			JvmExprCompiler.compileExpr(functions.get(i), ctx, className);
			ctx.body.aastore();
		}
		ctx.body.invokestatic(factory);
	}

	/**
	 * A resolved {@code java:subclass}: its callable and its constructor arguments are
	 * evaluated left to right -- the superclass and the two quoted lists are literals and
	 * evaluate to nothing -- into the construction dispatcher of the class generated for
	 * it, which chooses the superclass constructor when it runs.
	 */
	private static void compileSubclass(JavaImplementation implementation, List<LispVal> args, JvmLispCompiler.Ctx ctx,
			String className) {
		JvmJavaSites sites = Objects.requireNonNull(ctx.javaSites);
		MethodRefEntry construct = sites.implementations().subclassFactory(implementation, args.size() - 5);
		JvmExprCompiler.compileExpr(args.get(args.size() - 1), ctx, className);
		emitMaterialize(ctx);
		JvmEmitHelper.emitIntConst(ctx, args.size() - 5);
		ctx.body.anewarray(ctx.objectClass);
		for (int i = 4; i < args.size() - 1; i++) {
			ctx.body.dup();
			JvmEmitHelper.emitIntConst(ctx, i - 4);
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			emitMaterialize(ctx);
			ctx.body.aastore();
		}
		ctx.body.invokestatic(construct);
	}

	/**
	 * A resolved site: the receiver (a {@code java:call}'s, a {@code java:field}'s
	 * object) and the arguments are evaluated left to right -- the names written at the
	 * site are literals and evaluate to nothing -- and handed to the site's method.
	 */
	private static void compileDirect(JavaSite site, List<LispVal> args, JvmLispCompiler.Ctx ctx, String className) {
		JvmJavaSites sites = Objects.requireNonNull(ctx.javaSites);
		boolean staticField = site.operator() == JavaSite.Operator.FIELD && args.get(1) instanceof LispString;
		// The values the method takes, and which of them is an argument that may be a
		// string (a mutable character vector is rendered to the string it spells before
		// the method sees it, as the bridge renders every argument).
		List<LispVal> values;
		int firstArgument;
		switch (site.operator()) {
			case CALL -> {
				values = new java.util.ArrayList<>();
				values.add(args.get(1));
				values.addAll(args.subList(3, args.size()));
				firstArgument = 1;
			}
			case STATIC -> {
				values = args.subList(3, args.size());
				firstArgument = 0;
			}
			case NEW -> {
				values = args.subList(2, args.size());
				firstArgument = 0;
			}
			default -> {
				values = staticField ? List.of() : List.of(args.get(1));
				firstArgument = 1;
			}
		}
		MethodRefEntry method = sites.direct().site(site, staticField, values.size());
		boolean packed = values.size() > JvmJavaDirectSites.MAX_SPREAD;
		if (packed) {
			JvmEmitHelper.emitIntConst(ctx, values.size());
			ctx.body.anewarray(ctx.objectClass);
		}
		for (int i = 0; i < values.size(); i++) {
			if (packed) {
				ctx.body.dup();
				JvmEmitHelper.emitIntConst(ctx, i);
			}
			JvmExprCompiler.compileExpr(values.get(i), ctx, className);
			emitMaterialize(ctx);
			if (i >= firstArgument && site.arguments().get(i - firstArgument).mayBeString()) {
				JvmArrayCompiler.emitStrvNormalize(ctx, className);
			}
			if (i >= firstArgument) {
				JavaSite.Argument argument = site.arguments().get(i - firstArgument);
				if (!argument.known() && argument.bound() == null) {
					emitHostException(ctx, className);
				}
			}
			if (packed) {
				ctx.body.aastore();
			}
		}
		ctx.body.invokestatic(method);
	}

	private static void requireArity(boolean ok, String message) {
		if (!ok) {
			throw new UnsupportedOperationException(message);
		}
	}

	/**
	 * Under {@code --gpu}, materializes the value on top of the stack and REPLACES it
	 * with what the guard answers: Java code reads a packed array raw, so a result the
	 * device still holds the only copy of has to come home before it is handed over, and
	 * what is handed over is the array holding the bytes -- a result stub's backing when
	 * the value is one ({@code .kb/gpu.md}). That backing is what Java sees, keeps and
	 * answers; the rule that a host rung's answer is mapped back onto the caller's object
	 * is NOT applied here, because Java may store the array as well as answer it -- the
	 * one seam where a program can come to hold a backing beside its stub, and the one
	 * the kb names.
	 */
	private static void emitMaterialize(JvmLispCompiler.Ctx ctx) {
		Map<String, MethodRefEntry> gpuOps = ctx.gpuOps;
		if (gpuOps != null) {
			ctx.body.invokestatic(Objects.requireNonNull(gpuOps.get(JvmGpuRuntimeBuilder.MATERIALIZE)));
		}
	}

	/**
	 * Replaces the value on top of the stack -- an argument of a kind the site does not
	 * know -- with the host exception it stands for when it is a
	 * {@code java:java-exception} ({@code _jexc}), as the interpreter's
	 * {@code JavaInterop} hands a member such an argument; nothing where the program can
	 * hold no such condition.
	 */
	private static void emitHostException(JvmLispCompiler.Ctx ctx, String className) {
		MethodRefEntry helper = hostExceptionArgument(ctx, className);
		if (helper != null) {
			ctx.body.invokestatic(helper);
		}
	}

	private static final String HOST_EXCEPTION_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code _jexc}, built on first use from the Lisp form below over its one parameter:
	 * a {@code java:java-exception}'s cause slot holds the host exception, or a function
	 * building it, which keeps what it built, and anything else is the value itself. Null
	 * where no such condition can reach program code: the class is registered only in a
	 * program that can make a host call, and only one that can hold a condition hands one
	 * to a member.
	 */
	private static @Nullable MethodRefEntry hostExceptionArgument(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		if (channel.javaExceptionArgument != null) {
			return channel.javaExceptionArgument;
		}
		if (!ctx.closRegistry.routesConditionReports() || ctx.closRegistry
			.findLayoutByTag(LispLayout.CLASS_TAG_PREFIX + ClosRegistry.JAVA_EXCEPTION_CLASS_NAME) == null) {
			return null;
		}
		String methodName = "_jexc";
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(methodName);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(HOST_EXCEPTION_DESC);
		MethodRefEntry ref = JvmEmitHelper.selfMethod(ctx, className, methodName, HOST_EXCEPTION_DESC);
		channel.javaExceptionArgument = ref;
		JvmLispCompiler.Ctx body = ctx.ctxBuilder.build();
		body.evalStoreRef = ctx.evalStoreRef;
		body.nextLocal = 1;
		body.maxLocals = 1;
		String value = "__jexc_value$0";
		body.locals.put(value, 0);
		JvmExprCompiler.compileExpr(hostExceptionForm(new LispSymbol(value)), body, className);
		body.body.areturn();
		ctx.outlinedBodies.add(new JvmBodyOutliner.OutlinedBody(methodName, nameUtf8, descUtf8, body));
		return ref;
	}

	/**
	 * The body of {@code _jexc} over its parameter:
	 * {@code (if (typep x 'java:java-exception) (let ((held (%obj-ref x 2))) (if (functionp held) (funcall held x) (if held held x))) x)}.
	 */
	private static LispVal hostExceptionForm(LispSymbol x) {
		LispSymbol held = new LispSymbol("%JEXC-HELD");
		LispVal isException = list(sym(LispNames.TYPEP), x,
				list(sym(LispNames.QUOTE), sym(LispNames.JAVA_EXCEPTION_QUALIFIED)));
		LispVal read = list(sym(LispNames.OBJ_REF), x, new LispInteger(ClosRegistry.JAVA_EXCEPTION_CAUSE_INDEX));
		LispVal answer = list(sym(LispNames.IF), list(sym(LispNames.FUNCTIONP), held),
				list(sym(LispNames.FUNCALL), held, x), list(sym(LispNames.IF), held, held, x));
		return list(sym(LispNames.IF), isException, list(sym(LispNames.LET), list(list(held, read)), answer), x);
	}

	private static LispSymbol sym(String name) {
		return new LispSymbol(name);
	}

	private static LispVal list(LispVal... items) {
		LispVal out = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			out = new LispCons(items[i], out);
		}
		return out;
	}

	/** Evaluates {@code args[from..]} into a fresh {@code Object[]} left on the stack. */
	private static void compileRestArray(List<LispVal> args, int from, JvmLispCompiler.Ctx ctx, String className) {
		compileRestArray(args, from, ctx, className, false);
	}

	/**
	 * {@link #compileRestArray} over a member's arguments, which the bridge resolves when
	 * the call runs: each may be a condition standing for a host exception
	 * ({@link #emitHostException}).
	 */
	private static void compileArgumentArray(List<LispVal> args, int from, JvmLispCompiler.Ctx ctx, String className) {
		compileRestArray(args, from, ctx, className, true);
	}

	private static void compileRestArray(List<LispVal> args, int from, JvmLispCompiler.Ctx ctx, String className,
			boolean memberArguments) {
		JvmEmitHelper.emitIntConst(ctx, args.size() - from);
		ctx.body.anewarray(ctx.objectClass);
		for (int i = from; i < args.size(); i++) {
			ctx.body.dup();
			JvmEmitHelper.emitIntConst(ctx, i - from);
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			emitMaterialize(ctx);
			if (memberArguments) {
				emitHostException(ctx, className);
			}
			ctx.body.aastore();
		}
	}

	private static void emitBridgeCall(JvmLispCompiler.Ctx ctx, Map<String, MethodRefEntry> ops, String key) {
		ctx.body.invokestatic(Objects.requireNonNull(ops.get(key)));
	}

}
