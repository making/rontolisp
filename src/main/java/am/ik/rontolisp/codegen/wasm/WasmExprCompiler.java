package am.ik.rontolisp.codegen.wasm;

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
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles Lisp expressions to WASM instructions. Serves as the entry point and
 * dispatcher, delegating to specialized compiler classes for each built-in function and
 * special form. All values on the WASM stack are (ref eq); integers use i31ref, nil uses
 * ref.null eq.
 */
final class WasmExprCompiler {

	private WasmExprCompiler() {
	}

	static void compileExpr(LispVal expr, WasmLispCompiler.Ctx ctx) {
		switch (expr) {
			case LispInteger i -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128((int) i.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispNil ignored -> {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispDouble d -> {
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(d.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			}
			case LispString s -> WasmEmitHelper.compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> {
				if (sym.isKeyword()) {
					WasmEmitHelper.compileStringLiteral(sym.name(), ctx);
				}
				else {
					compileSymbolRef(sym, ctx);
				}
			}
			case LispCons cons -> compileCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	static void compileSymbolRef(LispSymbol sym, WasmLispCompiler.Ctx ctx) {
		String name = sym.name();
		// Check local variables
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			if (ctx.boxedVars.contains(name)) {
				// Unbox from cell
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
				ctx.writer.writeSignedLeb128(0);
			}
			return;
		}
		// Check captured variables
		Integer captureIdx = ctx.captures.get(name);
		if (captureIdx != null) {
			WasmEmitHelper.emitLoadCapture(ctx, captureIdx);
			return;
		}
		// Check known functions (create function reference)
		WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			// Create closure struct {funcId, null env}
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fi.funcId());
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
			return;
		}
		throw new UnsupportedOperationException("Cannot compile symbol: " + name);
	}

	private static void compileCons(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.ADD -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_ADD, Instruction.F64_ADD);
				case LispNames.SUB -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_SUB, Instruction.F64_SUB);
				case LispNames.MUL -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_MUL, Instruction.F64_MUL);
				case LispNames.DIV -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_DIV_S, Instruction.F64_DIV);
				case LispNames.MOD -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_REM_S, -1);
				case LispNames.EQ -> WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_EQ, Instruction.F64_EQ);
				case LispNames.LT ->
					WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_LT_S, Instruction.F64_LT);
				case LispNames.GT ->
					WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_GT_S, Instruction.F64_GT);
				case LispNames.LE ->
					WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_LE_S, Instruction.F64_LE);
				case LispNames.GE ->
					WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_GE_S, Instruction.F64_GE);
				case LispNames.PRINT -> WasmPrintCompiler.compile(cons, ctx);
				case LispNames.PRIN1 -> WasmPrin1Compiler.compile(cons, ctx);
				case LispNames.PRINC -> WasmPrincCompiler.compile(cons, ctx);
				case LispNames.TERPRI -> WasmTerpriCompiler.compile(cons, ctx);
				case LispNames.READ_LINE -> WasmReadLineCompiler.compile(cons, ctx);
				case LispNames.EVAL -> WasmEvalCompiler.compile(cons, ctx);
				case LispNames.QUOTE -> WasmQuoteCompiler.compile(cons, ctx);
				case LispNames.IF -> WasmIfCompiler.compile(cons, ctx);
				case LispNames.LET -> WasmLetCompiler.compile(cons, ctx);
				case LispNames.PROGN -> WasmPrognCompiler.compile(cons, ctx);
				case LispNames.SETQ -> WasmSetqCompiler.compile(cons, ctx);
				case LispNames.LAMBDA -> WasmLambdaCompiler.compileValue(cons, ctx);
				case LispNames.DEFUN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandDefun(cons), ctx);
				case LispNames.LIST -> WasmListCompiler.compile(cons, ctx);
				case LispNames.CAR -> WasmCarCompiler.compile(cons, ctx);
				case LispNames.CDR -> WasmCdrCompiler.compile(cons, ctx);
				case LispNames.CONS -> WasmConsCompiler.compile(cons, ctx);
				case LispNames.NTHCDR -> WasmNthcdrCompiler.compile(cons, ctx);
				case LispNames.RPLACA -> WasmRplacaCompiler.compile(cons, ctx);
				case LispNames.RPLACD -> WasmRplacdCompiler.compile(cons, ctx);
				case LispNames.SETF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandSetf(cons), ctx);
				case LispNames.PUSH -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPush(cons), ctx);
				case LispNames.POP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPop(cons), ctx);
				case LispNames.REMF -> WasmExprCompiler.compileExpr(LispMacroExpander.expandRemf(cons), ctx);
				case LispNames.EQ_GENERAL -> WasmEqGeneralCompiler.compile(cons, ctx);
				case LispNames.REMF_TAIL -> WasmRemfTailCompiler.compile(cons, ctx);
				case LispNames.APPEND -> WasmAppendCompiler.compile(cons, ctx);
				case LispNames.FUNCALL -> WasmFunctionCallCompiler.compileFuncall(cons, ctx);
				case LispNames.MAP -> WasmMapCompiler.compile(cons, ctx);
				case LispNames.REDUCE -> WasmReduceCompiler.compile(cons, ctx);
				case LispNames.NULL -> WasmNullPredCompiler.compile(cons, ctx);
				case LispNames.ATOM -> WasmAtomCompiler.compile(cons, ctx);
				case LispNames.NUMBERP -> WasmNumberpCompiler.compile(cons, ctx);
				case LispNames.INTEGERP -> WasmIntegerpCompiler.compile(cons, ctx);
				case LispNames.FLOATP -> WasmFloatpCompiler.compile(cons, ctx);
				case LispNames.SYMBOLP -> WasmSymbolpCompiler.compile(cons, ctx);
				case LispNames.STRINGP -> WasmStringpCompiler.compile(cons, ctx);
				case LispNames.LISTP -> WasmListpCompiler.compile(cons, ctx);
				case LispNames.CONSP -> WasmConspCompiler.compile(cons, ctx);
				case LispNames.KEYWORDP -> WasmKeywordpCompiler.compile(cons, ctx);
				case LispNames.FLOAT -> WasmFloatConvCompiler.compile(cons, ctx);
				case LispNames.TRUNCATE -> WasmIntConvCompiler.compileTruncate(cons, ctx);
				case LispNames.FLOOR -> WasmIntConvCompiler.compileFloor(cons, ctx);
				case LispNames.CEILING -> WasmIntConvCompiler.compileCeiling(cons, ctx);
				case LispNames.ROUND -> WasmIntConvCompiler.compileRound(cons, ctx);
				case LispNames.COND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandCond(cons), ctx);
				case LispNames.AND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandAnd(cons), ctx);
				case LispNames.OR -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOr(cons), ctx);
				case LispNames.WHEN -> WasmExprCompiler.compileExpr(LispMacroExpander.expandWhen(cons), ctx);
				case LispNames.UNLESS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandUnless(cons), ctx);
				case LispNames.ONE_PLUS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOnePlus(cons), ctx);
				case LispNames.ONE_MINUS -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOneMinus(cons), ctx);
				case LispNames.ZEROP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandZerop(cons), ctx);
				case LispNames.PLUSP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandPlusp(cons), ctx);
				case LispNames.MINUSP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandMinusp(cons), ctx);
				case LispNames.EVENP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandEvenp(cons), ctx);
				case LispNames.ODDP -> WasmExprCompiler.compileExpr(LispMacroExpander.expandOddp(cons), ctx);
				case LispNames.ABS -> WasmAbsCompiler.compile(cons, ctx);
				case LispNames.MIN -> WasmMinCompiler.compile(cons, ctx);
				case LispNames.MAX -> WasmMaxCompiler.compile(cons, ctx);
				case LispNames.FIRST -> WasmExprCompiler.compileExpr(LispMacroExpander.expandFirst(cons), ctx);
				case LispNames.NTH -> WasmExprCompiler.compileExpr(LispMacroExpander.expandNth(cons), ctx);
				case LispNames.SECOND -> WasmExprCompiler.compileExpr(LispMacroExpander.expandSecond(cons), ctx);
				case LispNames.THIRD -> WasmExprCompiler.compileExpr(LispMacroExpander.expandThird(cons), ctx);
				case LispNames.FOURTH -> WasmExprCompiler.compileExpr(LispMacroExpander.expandFourth(cons), ctx);
				case LispNames.NOT -> WasmNullPredCompiler.compile(cons, ctx);
				default -> {
					if (LispMacroExpander.isCarCdrComposition(sym.name())) {
						WasmExprCompiler.compileExpr(LispMacroExpander.expandCarCdrComposition(cons), ctx);
					}
					else {
						WasmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx);
					}
				}
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& LispNames.LAMBDA.equals(headSym.name())) {
			WasmLambdaCompiler.compileCall(headCons, cons, ctx);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + cons.print());
		}
	}

}
