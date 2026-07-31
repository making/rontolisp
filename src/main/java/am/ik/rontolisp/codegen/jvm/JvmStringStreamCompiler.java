package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.StreamDesignators;
import org.jspecify.annotations.Nullable;

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
	 * The print family's default destination expression: the current value of
	 * {@code *standard-output*} when the program gives it a global cell (it binds or
	 * assigns it somewhere -- the redirect contract of
	 * {@code (with-output-to-string (*standard-output*) ...)}), else {@code null} so a
	 * redirect-free program keeps the hard-coded standard output and compiles
	 * byte-identically to before.
	 */
	static @Nullable LispVal defaultStreamArg(JvmLispCompiler.Ctx ctx) {
		return streamArg(ctx, null);
	}

	/**
	 * The destination expression of an output operation, applying CL's stream designator
	 * rule ({@link StreamDesignators}) when the {@code *standard-output*} redirect is
	 * active: an omitted argument and an explicit nil both denote the current
	 * {@code *standard-output*}.
	 * @param ctx the compile context
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile, or {@code null} for the hard-coded standard
	 * output
	 */
	static @Nullable LispVal streamArg(JvmLispCompiler.Ctx ctx, @Nullable LispVal explicit) {
		if (!ctx.globals.contains(LispNames.STANDARD_OUTPUT_VAR)) {
			return explicit;
		}
		return StreamDesignators.resolveOutput(explicit);
	}

	/**
	 * The source expression of an INPUT operation, the {@link #streamArg} mirror: an
	 * omitted argument and an explicit nil both denote the current
	 * {@code *standard-input*} when the program binds it somewhere.
	 * @param ctx the compile context
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile, or {@code null} for the hard-coded standard
	 * input
	 */
	static @Nullable LispVal inputStreamArg(JvmLispCompiler.Ctx ctx, @Nullable LispVal explicit) {
		if (!ctx.globals.contains(LispNames.STANDARD_INPUT_VAR)) {
			return explicit;
		}
		return StreamDesignators.resolveInput(explicit);
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
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		LispVal stream = streamArg(ctx, parts.size() == 3 ? parts.get(2) : null);
		if (stream != null) {
			JvmExprCompiler.compileExpr(stream, ctx, className);
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
