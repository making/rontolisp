package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaSite;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the five {@code java:} interop functions ({@code java:new}, {@code java:call},
 * {@code java:static}, {@code java:field}, {@code java:proxy}). A site the shared
 * resolver resolves at compile time ({@link JvmJavaSites}) becomes a DIRECT call: the
 * site evaluates its receiver and arguments and calls its own method
 * ({@link JvmJavaDirectSites}), which checks them, converts them and invokes the member
 * with plain bytecode, exactly as the interpreter runs the same site. Any other site --
 * left to run time, or a {@code java:proxy} -- calls the embedded
 * {@link JavaBridgeTemplate bridge}: it first invokes the emitted {@code _javaInit}
 * helper (which lazily defines the bridge, see {@link JvmJavaRuntimeBuilder}), then
 * evaluates the arguments -- the leading fixed arguments as-is and the variadic tail
 * packed into an {@code Object[]} -- and calls the matching bridge entry point, which
 * resolves by reflection from the receiver's run-time class and the argument kinds. Under
 * {@code --java-static} such a site is a compile error instead.
 */
final class JvmJavaInteropCompiler {

	private JvmJavaInteropCompiler() {
	}

	/**
	 * Returns whether the given {@code java} package member is one of the five interop
	 * functions this compiler handles.
	 */
	static boolean handles(String member) {
		return LispNames.JAVA_NEW.equals(member) || LispNames.JAVA_CALL.equals(member)
				|| LispNames.JAVA_STATIC.equals(member) || LispNames.JAVA_FIELD.equals(member)
				|| LispNames.JAVA_PROXY.equals(member);
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
			case LispNames.JAVA_PROXY ->
				requireArity(args.size() == 3, "java:proxy expects (java:proxy \"interface\" callable)");
			default -> throw new UnsupportedOperationException("Cannot compile: java:" + member);
		}
		JvmJavaSites sites = Objects.requireNonNull(ctx.javaSites, "the java: sites were not prepared");
		// How the site resolves (compiler/JavaSiteResolver, the interpreter's resolver):
		// a
		// resolved site is compiled as the direct call of the member chosen, which the
		// interpreter runs it as too (eval/JavaInterop.invokeResolved).
		JavaSite site = LispNames.JAVA_PROXY.equals(member) ? null : sites.resolve(cons);
		String bridgeReason = sites.bridgeReason(cons);
		if (bridgeReason != null && sites.javaStatic()) {
			// Refused: the attempt fails once every site has been seen; the value only
			// keeps the method being compiled well-formed until then.
			sites.refuse(cons, bridgeReason);
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		if (site != null && site.resolved()) {
			compileDirect(site, args, ctx, className);
			return;
		}
		Map<String, MethodrefConstant> ops = ctx.javaOps;
		if (ops == null) {
			// This attempt carries no bridge (every site it predicted was direct): a call
			// to
			// the absent _javaInit makes the helper-gate check retry with the bridge.
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.cp
				.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
						ctx.cp.addNameAndType(ctx.cp.addUtf8(JvmJavaRuntimeBuilder.INIT_METHOD), ctx.cp.addUtf8("()V")))
				.index());
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		// Make sure the bridge class is defined before its method reference resolves.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
		switch (member) {
			case LispNames.JAVA_NEW -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				compileRestArray(args, 2, ctx, className);
				emitBridgeCall(ctx, ops, "new");
			}
			case LispNames.JAVA_CALL -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				// The receiver may itself be a packed array ((java:call arr "clone")),
				// and Java reads it raw: materialize it like every other argument.
				emitMaterialize(ctx);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				compileRestArray(args, 3, ctx, className);
				emitBridgeCall(ctx, ops, "call");
			}
			case LispNames.JAVA_STATIC -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				compileRestArray(args, 3, ctx, className);
				emitBridgeCall(ctx, ops, "static");
			}
			case LispNames.JAVA_FIELD -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				emitBridgeCall(ctx, ops, "field");
			}
			default -> {
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				emitBridgeCall(ctx, ops, "proxy");
			}
		}
	}

	/**
	 * A resolved site: the receiver (a {@code java:call}'s, a {@code java:field}'s
	 * object) and the arguments are evaluated left to right -- the names written at the
	 * site are literals and evaluate to nothing -- and handed to the site's method.
	 */
	private static void compileDirect(JavaSite site, List<LispVal> args, JvmLispCompiler.Ctx ctx, String className) {
		JvmJavaSites sites = Objects.requireNonNull(ctx.javaSites);
		boolean staticField = site.operator() == JavaSite.Operator.FIELD && args.get(1) instanceof LispString;
		// The values the method takes, and which of them is an argument the resolution
		// counted string kinds for (a mutable character vector is rendered to the
		// string it spells before the method sees it, as the bridge renders every
		// argument).
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
		MethodrefConstant method = sites.direct().site(site, staticField, values.size(), ctx.javaOps);
		boolean packed = values.size() > JvmJavaDirectSites.MAX_SPREAD;
		if (packed) {
			JvmEmitHelper.emitIntConst(ctx, values.size());
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
		}
		for (int i = 0; i < values.size(); i++) {
			if (packed) {
				ctx.emit(Opcode.DUP);
				JvmEmitHelper.emitIntConst(ctx, i);
			}
			JvmExprCompiler.compileExpr(values.get(i), ctx, className);
			emitMaterialize(ctx);
			if (i >= firstArgument && countsOnAString(site.arguments().get(i - firstArgument))) {
				JvmArrayCompiler.emitStrvNormalize(ctx, className);
			}
			if (packed) {
				ctx.emit(Opcode.AASTORE);
			}
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(method.index());
	}

	private static boolean countsOnAString(JavaSite.Argument argument) {
		return argument.kinds().contains(JavaKind.Lisp.STRING) || argument.kinds().contains(JavaKind.Lisp.STRING_1);
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
		Map<String, MethodrefConstant> gpuOps = ctx.gpuOps;
		if (gpuOps != null) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(Objects.requireNonNull(gpuOps.get(JvmGpuRuntimeBuilder.MATERIALIZE)).index());
		}
	}

	/** Evaluates {@code args[from..]} into a fresh {@code Object[]} left on the stack. */
	private static void compileRestArray(List<LispVal> args, int from, JvmLispCompiler.Ctx ctx, String className) {
		JvmEmitHelper.emitIntConst(ctx, args.size() - from);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		for (int i = from; i < args.size(); i++) {
			ctx.emit(Opcode.DUP);
			JvmEmitHelper.emitIntConst(ctx, i - from);
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			emitMaterialize(ctx);
			ctx.emit(Opcode.AASTORE);
		}
	}

	private static void emitBridgeCall(JvmLispCompiler.Ctx ctx, Map<String, MethodrefConstant> ops, String key) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(key)).index());
	}

}
