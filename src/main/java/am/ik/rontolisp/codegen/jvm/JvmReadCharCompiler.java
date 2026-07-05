package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code read-char} built-in: {@code (read-char [stream [eof-error-p
 * [eof-value]]])}. The stream (default nil = standard input), eof-error-p (default
 * {@code t}) and eof-value (default {@code nil}) are passed to the {@code _readChar}
 * runtime helper, which reads one character from the text stream.
 */
final class JvmReadCharCompiler {

	private JvmReadCharCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 4) {
			throw new UnsupportedOperationException("read-char expects 0 to 3 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.size() > 1 ? parts.get(1) : LispNil.INSTANCE, ctx, className);
		JvmExprCompiler.compileExpr(parts.size() > 2 ? parts.get(2) : LispTrue.INSTANCE, ctx, className);
		if (parts.size() > 3) {
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.READ_CHAR_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.READ_CHAR_DESC);
		MethodrefConstant readCharRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(readCharRef.index());
	}

}
