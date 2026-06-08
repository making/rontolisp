package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code eval} built-in. The argument expression is compiled normally to
 * produce a runtime Lisp value, then the {@code _eval} runtime interpreter is invoked to
 * evaluate it in the empty (global) lexical environment.
 */
final class JvmEvalCompiler {

	private JvmEvalCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException("eval expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// env = null (empty/global lexical environment)
		ctx.emit(Opcode.ACONST_NULL);
		Utf8Constant nameUtf8 = ctx.cp.addUtf8("_eval");
		Utf8Constant descUtf8 = ctx.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodrefConstant evalRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(evalRef.index());
	}

}
