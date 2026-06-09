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
			case LispSymbol sym -> {
				if (sym.isKeyword()) {
					JvmEmitHelper.compileStringLiteral(sym.name(), ctx);
				}
				else {
					compileSymbolRef(sym, ctx);
				}
			}
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
				case LispNames.PRIN1 -> JvmPrin1Compiler.compile(cons, ctx, className);
				case LispNames.PRINC -> JvmPrincCompiler.compile(cons, ctx, className);
				case LispNames.TERPRI -> JvmTerpriCompiler.compile(cons, ctx, className);
				case LispNames.READ_LINE -> JvmReadLineCompiler.compile(cons, ctx, className);
				case LispNames.QUOTE -> JvmQuoteCompiler.compile(cons, ctx, className);
				case LispNames.IF -> JvmIfCompiler.compile(cons, ctx, className);
				case LispNames.WHILE -> JvmWhileCompiler.compile(cons, ctx, className);
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
				case LispNames.NTHCDR -> JvmNthcdrCompiler.compile(cons, ctx, className);
				case LispNames.RPLACA -> JvmRplacaCompiler.compile(cons, ctx, className);
				case LispNames.RPLACD -> JvmRplacdCompiler.compile(cons, ctx, className);
				case LispNames.SETF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandSetf(cons), ctx, className);
				case LispNames.PUSH -> JvmExprCompiler.compileExpr(LispMacroExpander.expandPush(cons), ctx, className);
				case LispNames.POP -> JvmExprCompiler.compileExpr(LispMacroExpander.expandPop(cons), ctx, className);
				case LispNames.REMF -> JvmExprCompiler.compileExpr(LispMacroExpander.expandRemf(cons), ctx, className);
				case LispNames.EQ_GENERAL -> JvmEqGeneralCompiler.compile(cons, ctx, className);
				case LispNames.REMF_TAIL -> JvmRemfTailCompiler.compile(cons, ctx, className);
				case LispNames.APPEND -> JvmAppendCompiler.compile(cons, ctx, className);
				case LispNames.EVAL -> JvmEvalCompiler.compile(cons, ctx, className);
				case LispNames.FUNCALL -> JvmFunctionCallCompiler.compileFuncall(cons, ctx, className);
				case LispNames.MAP -> JvmMapCompiler.compile(cons, ctx, className);
				case LispNames.REDUCE -> JvmReduceCompiler.compile(cons, ctx, className);
				case LispNames.NULL -> JvmNullPredCompiler.compile(cons, ctx, className);
				case LispNames.ATOM -> JvmAtomCompiler.compile(cons, ctx, className);
				case LispNames.NUMBERP -> JvmNumberpCompiler.compile(cons, ctx, className);
				case LispNames.INTEGERP -> JvmIntegerpCompiler.compile(cons, ctx, className);
				case LispNames.FLOATP -> JvmFloatpCompiler.compile(cons, ctx, className);
				case LispNames.SYMBOLP -> JvmSymbolpCompiler.compile(cons, ctx, className);
				case LispNames.STRINGP -> JvmStringpCompiler.compile(cons, ctx, className);
				case LispNames.LISTP -> JvmListpCompiler.compile(cons, ctx, className);
				case LispNames.CONSP -> JvmConspCompiler.compile(cons, ctx, className);
				case LispNames.KEYWORDP -> JvmKeywordpCompiler.compile(cons, ctx, className);
				case LispNames.FLOAT -> JvmFloatConvCompiler.compile(cons, ctx, className);
				case LispNames.TRUNCATE -> JvmIntConvCompiler.compileTruncate(cons, ctx, className);
				case LispNames.FLOOR -> JvmIntConvCompiler.compileFloor(cons, ctx, className);
				case LispNames.CEILING -> JvmIntConvCompiler.compileCeiling(cons, ctx, className);
				case LispNames.ROUND -> JvmIntConvCompiler.compileRound(cons, ctx, className);
				case LispNames.COND -> JvmExprCompiler.compileExpr(LispMacroExpander.expandCond(cons), ctx, className);
				case LispNames.AND -> JvmExprCompiler.compileExpr(LispMacroExpander.expandAnd(cons), ctx, className);
				case LispNames.OR -> JvmExprCompiler.compileExpr(LispMacroExpander.expandOr(cons), ctx, className);
				case LispNames.WHEN -> JvmExprCompiler.compileExpr(LispMacroExpander.expandWhen(cons), ctx, className);
				case LispNames.DOTIMES ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandDotimes(cons), ctx, className);
				case LispNames.UNLESS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandUnless(cons), ctx, className);
				case LispNames.ONE_PLUS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandOnePlus(cons), ctx, className);
				case LispNames.ONE_MINUS ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandOneMinus(cons), ctx, className);
				case LispNames.ZEROP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandZerop(cons), ctx, className);
				case LispNames.PLUSP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandPlusp(cons), ctx, className);
				case LispNames.MINUSP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandMinusp(cons), ctx, className);
				case LispNames.EVENP ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandEvenp(cons), ctx, className);
				case LispNames.ODDP -> JvmExprCompiler.compileExpr(LispMacroExpander.expandOddp(cons), ctx, className);
				case LispNames.ABS -> JvmAbsCompiler.compile(cons, ctx, className);
				case LispNames.MIN -> JvmMinCompiler.compile(cons, ctx, className);
				case LispNames.MAX -> JvmMaxCompiler.compile(cons, ctx, className);
				case LispNames.FIRST ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFirst(cons), ctx, className);
				case LispNames.NTH -> JvmExprCompiler.compileExpr(LispMacroExpander.expandNth(cons), ctx, className);
				case LispNames.SECOND ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandSecond(cons), ctx, className);
				case LispNames.THIRD ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandThird(cons), ctx, className);
				case LispNames.FOURTH ->
					JvmExprCompiler.compileExpr(LispMacroExpander.expandFourth(cons), ctx, className);
				case LispNames.NOT -> JvmNullPredCompiler.compile(cons, ctx, className);
				default -> {
					if (LispMacroExpander.isCarCdrComposition(sym.name())) {
						JvmExprCompiler.compileExpr(LispMacroExpander.expandCarCdrComposition(cons), ctx, className);
					}
					else {
						JvmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx, className);
					}
				}
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
