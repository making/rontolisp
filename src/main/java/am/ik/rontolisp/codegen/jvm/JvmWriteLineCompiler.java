package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code write-line} built-in. The string and the optional stream handle
 * (null = standard output) are passed to the {@code _writeLine} runtime helper, which
 * returns the string.
 */
final class JvmWriteLineCompiler {

	private JvmWriteLineCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException("write-line expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// An explicit stream argument, or the current *standard-output* value when the
		// program redirects it (JvmStringStreamCompiler.defaultStreamArg).
		LispVal stream = parts.size() == 3 ? parts.get(2) : JvmStringStreamCompiler.defaultStreamArg(ctx);
		if (stream != null) {
			JvmExprCompiler.compileExpr(stream, ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.WRITE_LINE_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.WRITE_LINE_DESC);
		MethodrefConstant writeLineRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(writeLineRef.index());
	}

}
