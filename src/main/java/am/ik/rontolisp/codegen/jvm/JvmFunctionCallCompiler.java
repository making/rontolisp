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
			int supplied = args.size() - 1;
			int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
			if (supplied < required || (!fi.variadic() && supplied > required)) {
				throw new UnsupportedOperationException(name + " expects " + (fi.variadic() ? "at least " : "")
						+ required + " argument" + (required == 1 ? "" : "s") + ", got " + supplied);
			}
			for (int i = 1; i <= required; i++) {
				JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			}
			if (fi.variadic()) {
				// Evaluate the surplus arguments left to right into temps, then link
				// them into a cons list passed as the trailing rest parameter.
				List<Integer> extraSlots = new java.util.ArrayList<>();
				for (int i = required + 1; i < args.size(); i++) {
					JvmExprCompiler.compileExpr(args.get(i), ctx, className);
					int s = ctx.allocTemp();
					ctx.emit(Opcode.ASTORE);
					ctx.emit(s);
					extraSlots.add(s);
				}
				int restSlot = ctx.allocTemp();
				ctx.emit(Opcode.ACONST_NULL);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(restSlot);
				for (int k = extraSlots.size() - 1; k >= 0; k--) {
					ctx.emit(Opcode.ICONST_2);
					ctx.emit(Opcode.ANEWARRAY);
					ctx.emitU2(ctx.objectClass.index());
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.ICONST_0);
					ctx.emit(Opcode.ALOAD);
					ctx.emit(extraSlots.get(k));
					ctx.emit(Opcode.AASTORE);
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.ICONST_1);
					ctx.emit(Opcode.ALOAD);
					ctx.emit(restSlot);
					ctx.emit(Opcode.AASTORE);
					ctx.emit(Opcode.ASTORE);
					ctx.emit(restSlot);
				}
				ctx.emit(Opcode.ALOAD);
				ctx.emit(restSlot);
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
