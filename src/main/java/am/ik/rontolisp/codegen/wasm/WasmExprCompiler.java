package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
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
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
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
				case "+" -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_ADD, Instruction.F64_ADD);
				case "-" -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_SUB, Instruction.F64_SUB);
				case "*" -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_MUL, Instruction.F64_MUL);
				case "/" -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_DIV_S, Instruction.F64_DIV);
				case "mod" -> WasmArithCompiler.compile(cons, ctx, Instruction.I32_REM_S, -1);
				case "=" -> WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_EQ, Instruction.F64_EQ);
				case "<" -> WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_LT_S, Instruction.F64_LT);
				case ">" -> WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_GT_S, Instruction.F64_GT);
				case "<=" -> WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_LE_S, Instruction.F64_LE);
				case ">=" -> WasmComparisonCompiler.compile(cons, ctx, Instruction.I32_GE_S, Instruction.F64_GE);
				case "print" -> WasmPrintCompiler.compile(cons, ctx);
				case "quote" -> WasmQuoteCompiler.compile(cons, ctx);
				case "if" -> WasmIfCompiler.compile(cons, ctx);
				case "let" -> WasmLetCompiler.compile(cons, ctx);
				case "progn" -> WasmPrognCompiler.compile(cons, ctx);
				case "setq" -> WasmSetqCompiler.compile(cons, ctx);
				case "lambda" -> WasmLambdaCompiler.compileValue(cons, ctx);
				case "defun" -> {
					ctx.writer.write(Instruction.REF_NULL);
					ctx.writer.writeHeapType(Type.EQ.code());
				}
				case "list" -> WasmListCompiler.compile(cons, ctx);
				case "car" -> WasmCarCompiler.compile(cons, ctx);
				case "cdr" -> WasmCdrCompiler.compile(cons, ctx);
				case "cons" -> WasmConsCompiler.compile(cons, ctx);
				case "funcall" -> WasmFunctionCallCompiler.compileFuncall(cons, ctx);
				case "null" -> WasmNullPredCompiler.compile(cons, ctx);
				case "atom" -> WasmAtomCompiler.compile(cons, ctx);
				case "numberp" -> WasmNumberpCompiler.compile(cons, ctx);
				case "integerp" -> WasmIntegerpCompiler.compile(cons, ctx);
				case "floatp" -> WasmFloatpCompiler.compile(cons, ctx);
				case "symbolp" -> WasmSymbolpCompiler.compile(cons, ctx);
				case "stringp" -> WasmStringpCompiler.compile(cons, ctx);
				case "listp" -> WasmListpCompiler.compile(cons, ctx);
				case "consp" -> WasmConspCompiler.compile(cons, ctx);
				default -> WasmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx);
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			WasmLambdaCompiler.compileCall(headCons, cons, ctx);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + cons.print());
		}
	}

}
