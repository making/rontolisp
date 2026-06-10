package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Compiles function calls: direct calls, indirect calls, general indirect calls, and
 * {@code funcall}.
 */
final class JvmFunctionCallCompiler {

	private JvmFunctionCallCompiler() {
	}

	/**
	 * Compiles the default case in dispatch. Under the Lisp-2 model a symbol in call
	 * position resolves in the function namespace only, so variable bindings never shadow
	 * it: this is always a direct call against the function registry.
	 */
	static void compileDefault(String name, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileDirectCall(name, cons, ctx, className);
	}

	/**
	 * Compiles the {@code funcall} built-in.
	 */
	static void compileFuncall(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 2;
		ctx.indirectCallArities.add(arity);
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		for (int i = 2; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		emitDispatchCall(arity, ctx, className);
	}

	/**
	 * Compiles a general indirect call where the head is an expression (not a symbol).
	 */
	static void compileGeneralIndirect(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 1;
		ctx.indirectCallArities.add(arity);
		JvmExprCompiler.compileExpr(args.get(0), ctx, className);
		for (int i = 1; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		emitDispatchCall(arity, ctx, className);
	}

	private static void compileDirectCall(String name, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			for (int i = 1; i < args.size(); i++) {
				JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			}
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(fi.methodref().index());
		}
		else if (ctx.dynamic) {
			JvmDynamicCallCompiler.compileCall(name, cons, ctx, className);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	static void emitDispatchCall(int arity, JvmLispCompiler.Ctx ctx, String className) {
		String dispatchName = "_invoke_" + arity;
		String dispatchDesc = "(" + "Ljava/lang/Object;".repeat(arity + 1) + ")Ljava/lang/Object;";
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(dispatchName);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(dispatchDesc);
		MethodrefConstant methodref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(methodref.index());
	}

}
