package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code read-byte} built-in: {@code (read-byte stream &optional
 * eof-error-p eof-value)}. The stream, eof-error-p (default {@code t}) and eof-value
 * (default {@code nil}) are passed to the {@code _readByte} runtime helper, which reads
 * one byte from the binary input stream -- or from the process standard input for a
 * non-handle designator.
 */
final class JvmReadByteCompiler {

	private JvmReadByteCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 4) {
			throw new UnsupportedOperationException("read-byte expects 1 to 3 arguments, got " + (parts.size() - 1));
		}
		// The source designator, like the character reads: an explicit nil means the
		// current *standard-input*, whose default t the runtime helper reads stdin for.
		LispVal stream = JvmStringStreamCompiler.inputStreamArg(ctx, parts.get(1));
		JvmExprCompiler.compileExpr(stream != null ? stream : parts.get(1), ctx, className);
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
			ctx.body.aconst_null();
		}
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.READ_BYTE_METHOD);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.READ_BYTE_DESC);
		MethodRefEntry readByteRef = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(readByteRef);
	}

}
