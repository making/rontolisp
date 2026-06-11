package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code subseq} for strings: {@code (subseq string start [end])}. The runtime
 * string carries surrounding quotes, so the substring of the content at
 * {@code [start, end)} is {@code string.substring(1 + start, 1 + end)}, re-wrapped in
 * quotes. When {@code end} is omitted it defaults to the content length
 * ({@code string.length() - 1} in quoted-index terms).
 */
final class JvmSubseqCompiler {

	private JvmSubseqCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int length = JvmEmitHelper.stringMethod(ctx, "length", "()I").index();
		int substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;").index();
		int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		int sSlot = ctx.allocTemp();
		// s = (String) seq
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(sSlot);
		// "\"" + s.substring(1 + start, end') + "\""
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(sSlot);
		// a = 1 + (int) start
		ctx.emit(Opcode.ICONST_1);
		emitIntArg(args.get(2), ctx, className);
		ctx.emit(Opcode.IADD);
		// b = 1 + (int) end, or s.length() - 1 when end is omitted
		if (args.size() >= 4) {
			ctx.emit(Opcode.ICONST_1);
			emitIntArg(args.get(3), ctx, className);
			ctx.emit(Opcode.IADD);
		}
		else {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(sSlot);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(length);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ISUB);
		}
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(substring);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
	}

	// Compiles an index argument (a Lisp integer / boxed Long) to a raw int on the stack.
	private static void emitIntArg(LispVal arg, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(arg, ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
	}

}
