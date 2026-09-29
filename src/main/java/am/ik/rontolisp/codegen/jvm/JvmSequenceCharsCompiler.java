package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %read-sequence-chars} primitive the {@code read-sequence} expansion
 * calls after the packed one: a call to the {@code _readSeqChars} runtime helper when the
 * program emits it ({@code .kb/character-sequence-io.md}), else a plain {@code nil} --
 * the "declined" answer, which sends the expansion down its per-character loop exactly as
 * before the primitive existed.
 */
final class JvmSequenceCharsCompiler {

	private JvmSequenceCharsCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 5) {
			throw new UnsupportedOperationException(
					parts.get(0).print() + " expects (seq stream start end), got " + (parts.size() - 1) + " arguments");
		}
		if (!ctx.usesCharSequenceIo) {
			ctx.body.aconst_null();
			return;
		}
		// The stream designator, like read-char: an explicit nil means the current
		// *standard-input*, whose default the helper reads as the process stream.
		LispVal stream = JvmStringStreamCompiler.inputStreamArg(ctx, parts.get(2));
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		JvmExprCompiler.compileExpr(stream != null ? stream : parts.get(2), ctx, className);
		JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		JvmExprCompiler.compileExpr(parts.get(4), ctx, className);
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.READ_SEQ_CHARS_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.READ_SEQ_CHARS_DESC);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.body.invokestatic(ref.entry());
	}

}
