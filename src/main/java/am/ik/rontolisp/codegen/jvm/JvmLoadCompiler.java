package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code load} built-in. The path argument is compiled to a runtime string
 * value, then the {@code _load} runtime helper reads the file, parses every top-level
 * datum, and evaluates each in the global environment via the {@code _eval} runtime.
 */
final class JvmLoadCompiler {

	private JvmLoadCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException("load expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		Utf8Constant nameUtf8 = ctx.cp.addUtf8("_load");
		Utf8Constant descUtf8 = ctx.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;");
		MethodrefConstant loadRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(loadRef.index());
	}

}
