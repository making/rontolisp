package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code force-output} / {@code finish-output} built-ins (the two coincide:
 * every rontolisp write is synchronous once flushed). The optional stream designator is
 * compiled normally (absent = null = standard output) and passed to the
 * {@code _forceOutput} runtime helper, which returns nil.
 */
final class JvmForceOutputCompiler {

	private JvmForceOutputCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException("force-output expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		if (parts.size() == 2) {
			JvmExprCompiler.compileExpr(
					java.util.Objects.requireNonNull(JvmStringStreamCompiler.streamDesignator(ctx, parts.get(1))), ctx,
					className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.FORCE_OUTPUT_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.FORCE_OUTPUT_DESC);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
