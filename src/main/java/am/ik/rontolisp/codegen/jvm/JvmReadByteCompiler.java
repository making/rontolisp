package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code read-byte} built-in: {@code (read-byte stream &optional
 * eof-error-p eof-value)}. The stream, eof-error-p (default {@code t}) and eof-value
 * (default {@code nil}) are passed to the {@code _readByte} runtime helper, which reads
 * one byte from the binary input stream.
 */
final class JvmReadByteCompiler {

	private JvmReadByteCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 4) {
			throw new UnsupportedOperationException("read-byte expects 1 to 3 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		if (parts.size() > 2) {
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		}
		else {
			JvmExprCompiler.compileExpr(LispTrue.INSTANCE, ctx, className);
		}
		if (parts.size() > 3) {
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.READ_BYTE_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.READ_BYTE_DESC);
		MethodrefConstant readByteRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(readByteRef.index());
	}

}
