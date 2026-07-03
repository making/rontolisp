package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code write-byte} built-in: {@code (write-byte byte stream)}. The byte
 * and the stream handle are passed to the {@code _writeByte} runtime helper, which writes
 * one raw byte to the binary output stream and returns the byte.
 */
final class JvmWriteByteCompiler {

	private JvmWriteByteCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new UnsupportedOperationException("write-byte expects 2 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.WRITE_BYTE_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.WRITE_BYTE_DESC);
		MethodrefConstant writeByteRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(writeByteRef.index());
	}

}
