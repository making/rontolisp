package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code read} built-in. Without an argument it invokes the {@code _read}
 * runtime helper, which parses one S-expression from a line of stdin; with a
 * stream-handle argument it parses one datum from that open input stream via
 * {@code _readStream}. The full CL tail
 * ({@code (read stream eof-error-p eof-value recursive-p)}) is rewritten first through
 * {@link LispMacroExpander#expandReadCompat}.
 */
final class JvmReadCompiler {

	private JvmReadCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal rewritten = LispMacroExpander.expandReadCompat(cons);
		if (rewritten != null) {
			JvmExprCompiler.compileExpr(rewritten, ctx, className);
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException("read expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		// The source designator: an omitted argument and an explicit nil both mean the
		// current *standard-input* (JvmStringStreamCompiler.inputStreamArg).
		LispVal stream = JvmStringStreamCompiler.inputStreamArg(ctx, parts.size() == 2 ? parts.get(1) : null);
		MethodrefConstant readRef;
		if (stream == null) {
			readRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
					ctx.cp.addNameAndType(ctx.cp.addUtf8("_read"), ctx.cp.addUtf8("()Ljava/lang/Object;")));
		}
		else {
			JvmExprCompiler.compileExpr(stream, ctx, className);
			readRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)), ctx.cp.addNameAndType(
					ctx.cp.addUtf8("_readStream"), ctx.cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(readRef.index());
	}

}
