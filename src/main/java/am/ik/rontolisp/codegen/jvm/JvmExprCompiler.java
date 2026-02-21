package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

import am.ik.jvm.Opcode;

/**
 * Compiles Lisp expressions to JVM bytecode. Serves as the entry point and dispatcher,
 * delegating to specialized compiler classes for each built-in function and special form.
 */
final class JvmExprCompiler {

	private JvmExprCompiler() {
	}

	static void compileExpr(LispVal expr, JvmLispCompiler.Ctx ctx, String className) {
		switch (expr) {
			case LispInteger i -> JvmEmitHelper.compileLong(i.value(), ctx);
			case LispDouble d -> JvmEmitHelper.compileDouble(d.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> JvmEmitHelper.compileLong(1, ctx);
			case LispString s -> JvmEmitHelper.compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx, className);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	static void compileSymbolRef(LispSymbol sym, JvmLispCompiler.Ctx ctx) {
		String name = sym.name();
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			if (ctx.boxedVars.contains(name)) {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
				ctx.emit(Opcode.CHECKCAST);
				ctx.emitU2(ctx.objectArrayClass.index());
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.AALOAD);
			}
			else {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
			}
		}
		else if (ctx.captures.containsKey(name)) {
			int captureIdx = ctx.captures.get(name);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
			JvmEmitHelper.emitIntConst(ctx, 1 + captureIdx);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
		}
		else if (ctx.functions.containsKey(name)) {
			JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			JvmEmitHelper.emitIntConst(ctx, fi.funcId());
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.integerValueOf.index());
			ctx.emit(Opcode.AASTORE);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile symbol reference: " + name);
		}
	}

	private static void compileCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.ADD -> JvmArithCompiler.compile(cons, ctx, Opcode.LADD, Opcode.DADD, className);
				case LispNames.SUB -> JvmArithCompiler.compile(cons, ctx, Opcode.LSUB, Opcode.DSUB, className);
				case LispNames.MUL -> JvmArithCompiler.compile(cons, ctx, Opcode.LMUL, Opcode.DMUL, className);
				case LispNames.DIV -> JvmArithCompiler.compile(cons, ctx, Opcode.LDIV, Opcode.DDIV, className);
				case LispNames.MOD -> JvmArithCompiler.compile(cons, ctx, Opcode.LREM, Opcode.DREM, className);
				case LispNames.EQ -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFEQ, className);
				case LispNames.LT -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFLT, className);
				case LispNames.GT -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFGT, className);
				case LispNames.LE -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFLE, className);
				case LispNames.GE -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFGE, className);
				case LispNames.PRINT -> JvmPrintCompiler.compile(cons, ctx, className);
				case LispNames.QUOTE -> JvmQuoteCompiler.compile(cons, ctx, className);
				case LispNames.IF -> JvmIfCompiler.compile(cons, ctx, className);
				case LispNames.LET -> JvmLetCompiler.compile(cons, ctx, className);
				case LispNames.PROGN -> JvmPrognCompiler.compile(cons, ctx, className);
				case LispNames.SETQ -> JvmSetqCompiler.compile(cons, ctx, className);
				case LispNames.LAMBDA -> JvmLambdaCompiler.compileValue(cons, ctx, className);
				case LispNames.DEFUN ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDefun(cons), ctx, className);
				case LispNames.LIST -> JvmListCompiler.compile(cons, ctx, className);
				case LispNames.CAR -> JvmCarCompiler.compile(cons, ctx, className);
				case LispNames.CDR -> JvmCdrCompiler.compile(cons, ctx, className);
				case LispNames.CONS -> JvmConsCompiler.compile(cons, ctx, className);
				case LispNames.FUNCALL -> JvmFunctionCallCompiler.compileFuncall(cons, ctx, className);
				case LispNames.NULL -> JvmNullPredCompiler.compile(cons, ctx, className);
				case LispNames.ATOM -> JvmAtomCompiler.compile(cons, ctx, className);
				case LispNames.NUMBERP -> JvmNumberpCompiler.compile(cons, ctx, className);
				case LispNames.INTEGERP -> JvmIntegerpCompiler.compile(cons, ctx, className);
				case LispNames.FLOATP -> JvmFloatpCompiler.compile(cons, ctx, className);
				case LispNames.SYMBOLP -> JvmSymbolpCompiler.compile(cons, ctx, className);
				case LispNames.STRINGP -> JvmStringpCompiler.compile(cons, ctx, className);
				case LispNames.LISTP -> JvmListpCompiler.compile(cons, ctx, className);
				case LispNames.CONSP -> JvmConspCompiler.compile(cons, ctx, className);
				case LispNames.COND -> JvmExprCompiler.compileExpr(LispMacroExpander.expandCond(cons), ctx, className);
				case LispNames.AND -> JvmExprCompiler.compileExpr(LispMacroExpander.expandAnd(cons), ctx, className);
				case LispNames.OR -> JvmExprCompiler.compileExpr(LispMacroExpander.expandOr(cons), ctx, className);
				case LispNames.NOT -> JvmNullPredCompiler.compile(cons, ctx, className);
				default -> JvmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx, className);
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& LispNames.LAMBDA.equals(headSym.name())) {
			JvmLambdaCompiler.compileCall(headCons, cons, ctx, className);
		}
		else {
			JvmFunctionCallCompiler.compileGeneralIndirect(cons, ctx, className);
		}
	}

}
