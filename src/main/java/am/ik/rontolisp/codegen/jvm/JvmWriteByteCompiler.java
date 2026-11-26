package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code write-byte} built-in: {@code (write-byte byte stream)}. The byte
 * and the stream handle are passed to the {@code _writeByte} runtime helper, which writes
 * one raw byte to the binary output stream -- or to the process standard output for a
 * non-handle designator -- and returns the byte.
 */
final class JvmWriteByteCompiler {

	private JvmWriteByteCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new UnsupportedOperationException("write-byte expects 2 arguments, got " + (parts.size() - 1));
		}
		// The destination designator, like the print family: an explicit nil means the
		// current *standard-output*, whose default t the runtime helper writes stdout
		// for.
		LispVal stream = JvmStringStreamCompiler.streamArg(ctx, parts.get(2));
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		JvmExprCompiler.compileExpr(stream != null ? stream : parts.get(2), ctx, className);
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.WRITE_BYTE_METHOD);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.WRITE_BYTE_DESC);
		MethodRefEntry writeByteRef = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(writeByteRef);
	}

}
