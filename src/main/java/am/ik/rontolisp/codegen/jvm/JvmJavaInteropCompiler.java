package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the five {@code java:} interop functions ({@code java:new}, {@code java:call},
 * {@code java:static}, {@code java:field}, {@code java:proxy}). Each call site first
 * invokes the emitted {@code _javaInit} helper (which lazily defines the embedded
 * {@link JavaBridgeTemplate bridge class}, see {@link JvmJavaRuntimeBuilder}), then
 * evaluates the arguments -- the leading fixed arguments as-is and the variadic tail
 * packed into an {@code Object[]} -- and calls the matching bridge entry point.
 * Marshalling, overload resolution, and runtime validation all live in the bridge, so
 * compiled behavior matches the interpreter.
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
		Map<String, MethodrefConstant> ops = ctx.javaOps;
		if (ops == null) {
			throw new IllegalStateException("java interop runtime was not emitted");
		}
		List<LispVal> args = cons.toList();
		// Make sure the bridge class is defined before its method reference resolves.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(java.util.Objects.requireNonNull(ops.get("init")).index());
		switch (member) {
			case LispNames.JAVA_NEW -> {
				requireArity(args.size() >= 2, "java:new expects (java:new \"class\" args...)");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				compileRestArray(args, 2, ctx, className);
				emitBridgeCall(ctx, ops, "new");
			}
			case LispNames.JAVA_CALL -> {
				requireArity(args.size() >= 3, "java:call expects (java:call object \"method\" args...)");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				// The receiver may itself be a packed array ((java:call arr "clone")),
				// and
				// Java reads it raw: materialize it like every other argument.
				emitMaterialize(ctx);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				compileRestArray(args, 3, ctx, className);
				emitBridgeCall(ctx, ops, "call");
			}
			case LispNames.JAVA_STATIC -> {
				requireArity(args.size() >= 3, "java:static expects (java:static \"class\" \"method\" args...)");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				compileRestArray(args, 3, ctx, className);
				emitBridgeCall(ctx, ops, "static");
			}
			case LispNames.JAVA_FIELD -> {
				requireArity(args.size() == 3, "java:field expects (java:field class-or-object \"field\")");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				emitBridgeCall(ctx, ops, "field");
			}
			case LispNames.JAVA_PROXY -> {
				requireArity(args.size() == 3, "java:proxy expects (java:proxy \"interface\" callable)");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				emitBridgeCall(ctx, ops, "proxy");
			}
			default -> throw new UnsupportedOperationException("Cannot compile: java:" + member);
		}
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
			ctx.emitU2(java.util.Objects.requireNonNull(gpuOps.get(JvmGpuRuntimeBuilder.MATERIALIZE)).index());
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
		ctx.emitU2(java.util.Objects.requireNonNull(ops.get(key)).index());
	}

}
