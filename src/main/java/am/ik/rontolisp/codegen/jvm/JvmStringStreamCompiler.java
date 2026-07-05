package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the string-stream built-ins behind with-output-to-string /
 * with-input-from-string: {@code %make-string-output-stream},
 * {@code %make-string-input-stream}, {@code %string-stream-contents}, and the public
 * {@code write-string}. Also provides the {@code _writeStr} call emitter used by the
 * print-family compilers for their optional stream argument.
 */
final class JvmStringStreamCompiler {

	private JvmStringStreamCompiler() {
	}

	private static MethodrefConstant methodRef(JvmLispCompiler.Ctx ctx, String className, String name, String desc) {
		return ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
	}

	/**
	 * Emits an {@code invokestatic _writeStr(String, Object) -> void} call; the caller
	 * must have pushed the rendered content string and the stream handle.
	 */
	static void emitWriteStr(JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(methodRef(ctx, className, JvmIoRuntimeBuilder.WRITE_STR_METHOD, JvmIoRuntimeBuilder.WRITE_STR_DESC)
			.index());
	}

	/**
	 * Compiles {@code (write-string str [stream])} via the {@code _writeString} helper.
	 */
	static void compileWriteString(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException("write-string expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		if (parts.size() == 3) {
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(methodRef(ctx, className, JvmIoRuntimeBuilder.WRITE_STRING_METHOD,
				JvmIoRuntimeBuilder.WRITE_STRING_DESC)
			.index());
	}

	/** Compiles {@code (%make-string-output-stream)}. */
	static void compileMakeOutputStream(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(methodRef(ctx, className, JvmIoRuntimeBuilder.MAKE_STRING_OUTPUT_STREAM_METHOD,
				JvmIoRuntimeBuilder.MAKE_STRING_OUTPUT_STREAM_DESC)
			.index());
	}

	/** Compiles {@code (%make-string-input-stream str)}. */
	static void compileMakeInputStream(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(methodRef(ctx, className, JvmIoRuntimeBuilder.MAKE_STRING_INPUT_STREAM_METHOD,
				JvmIoRuntimeBuilder.MAKE_STRING_INPUT_STREAM_DESC)
			.index());
	}

	/** Compiles {@code (%string-stream-contents stream)}. */
	static void compileContents(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(methodRef(ctx, className, JvmIoRuntimeBuilder.STRING_STREAM_CONTENTS_METHOD,
				JvmIoRuntimeBuilder.STRING_STREAM_CONTENTS_DESC)
			.index());
	}

}
