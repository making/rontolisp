package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code quote} special form.
 */
final class JvmQuoteCompiler {

	private JvmQuoteCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal quoted = ((LispCons) cons.cdr()).car();
		compileQuotedVal(quoted, ctx, className);
	}

	private static void compileQuotedVal(LispVal val, JvmLispCompiler.Ctx ctx, String className) {
		switch (val) {
			case LispInteger i -> JvmEmitHelper.compileLong(i.value(), ctx);
			case LispDouble d -> JvmEmitHelper.compileDouble(d.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> JvmEmitHelper.compileLong(1, ctx);
			case LispString s -> JvmEmitHelper.compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> JvmEmitHelper.compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx, className);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	private static void compileQuotedCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		compileQuotedVal(cons.car(), ctx, className);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		compileQuotedVal(cons.cdr(), ctx, className);
		ctx.emit(Opcode.AASTORE);
	}

}
