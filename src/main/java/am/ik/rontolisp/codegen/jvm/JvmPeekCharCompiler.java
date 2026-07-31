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
 * Compiles the internal {@code %peek-char} primitive:
 * {@code (%peek-char [stream [eof-error-p [eof-value]]])}. Same argument shape as
 * {@code read-char} -- the {@code peek-type} skipping forms of the public
 * {@code peek-char} are lowered onto this one by
 * {@code LispMacroExpander.expandPeekChar}, so only the "next character, left in place"
 * primitive needs a runtime helper.
 */
final class JvmPeekCharCompiler {

	private JvmPeekCharCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 4) {
			throw new UnsupportedOperationException("peek-char expects 0 to 3 arguments, got " + (parts.size() - 1));
		}
		// The source designator: an omitted argument and an explicit nil both mean the
		// current *standard-input* (JvmStringStreamCompiler.inputStreamArg); the runtime
		// helper reads standard input for any non-handle value.
		LispVal stream = JvmStringStreamCompiler.inputStreamArg(ctx, parts.size() > 1 ? parts.get(1) : null);
		JvmExprCompiler.compileExpr(stream != null ? stream : LispNil.INSTANCE, ctx, className);
		JvmExprCompiler.compileExpr(parts.size() > 2 ? parts.get(2) : LispTrue.INSTANCE, ctx, className);
		if (parts.size() > 3) {
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.PEEK_CHAR_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.PEEK_CHAR_DESC);
		MethodrefConstant peekCharRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(peekCharRef.index());
	}

}
