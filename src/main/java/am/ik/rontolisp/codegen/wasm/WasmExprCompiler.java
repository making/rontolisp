package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles Lisp expressions to WASM instructions. All values on the WASM stack are (ref
 * eq); integers use i31ref, nil uses ref.null eq.
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
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	private static void compileSymbolRef(LispSymbol sym, WasmLispCompiler.Ctx ctx) {
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
			emitLoadCapture(ctx, captureIdx);
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
				case "+" -> compileArith(cons, ctx, Instruction.I32_ADD, Instruction.F64_ADD);
				case "-" -> compileArith(cons, ctx, Instruction.I32_SUB, Instruction.F64_SUB);
				case "*" -> compileArith(cons, ctx, Instruction.I32_MUL, Instruction.F64_MUL);
				case "/" -> compileArith(cons, ctx, Instruction.I32_DIV_S, Instruction.F64_DIV);
				case "mod" -> compileArith(cons, ctx, Instruction.I32_REM_S, -1);
				case "=" -> compileComparison(cons, ctx, Instruction.I32_EQ, Instruction.F64_EQ);
				case "<" -> compileComparison(cons, ctx, Instruction.I32_LT_S, Instruction.F64_LT);
				case ">" -> compileComparison(cons, ctx, Instruction.I32_GT_S, Instruction.F64_GT);
				case "<=" -> compileComparison(cons, ctx, Instruction.I32_LE_S, Instruction.F64_LE);
				case ">=" -> compileComparison(cons, ctx, Instruction.I32_GE_S, Instruction.F64_GE);
				case "print" -> compilePrint(cons, ctx);
				case "quote" -> compileQuote(cons, ctx);
				case "if" -> compileIf(cons, ctx);
				case "let" -> compileLet(cons, ctx);
				case "progn" -> compileProgn(cons, ctx);
				case "setq" -> compileSetq(cons, ctx);
				case "lambda" -> compileLambdaValue(cons, ctx);
				case "defun" -> {
					ctx.writer.write(Instruction.REF_NULL);
					ctx.writer.writeHeapType(Type.EQ.code());
				}
				case "list" -> compileList(cons, ctx);
				case "car" -> compileCar(cons, ctx);
				case "cdr" -> compileCdr(cons, ctx);
				case "cons" -> compileConsBuiltin(cons, ctx);
				case "funcall" -> compileFuncall(cons, ctx);
				case "null" -> compileNullPredicate(cons, ctx);
				case "atom" -> compileAtom(cons, ctx);
				case "numberp" -> compileNumberp(cons, ctx);
				case "integerp" -> compileIntegerp(cons, ctx);
				case "floatp" -> compileFloatp(cons, ctx);
				case "symbolp" -> compileSymbolp(cons, ctx);
				case "stringp" -> compileStringp(cons, ctx);
				case "listp" -> compileListp(cons, ctx);
				case "consp" -> compileConsp(cons, ctx);
				default -> {
					// Check if the symbol is a local/capture (indirect call) or a
					// known function (direct call)
					String name = sym.name();
					if (ctx.locals.containsKey(name) || ctx.captures.containsKey(name)) {
						compileIndirectCall(cons, ctx);
					}
					else {
						compileFunctionCall(name, cons, ctx);
					}
				}
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			compileLambdaCall(headCons, cons, ctx);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + cons.print());
		}
	}

	private static void compileArith(LispCons cons, WasmLispCompiler.Ctx ctx, int i32Opcode, int f64Opcode) {
		List<LispVal> args = cons.toList();
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			compileExpr(args.get(1), ctx);
			castFloatGetF64(ctx);
			for (int i = 2; i < args.size(); i++) {
				compileExpr(args.get(i), ctx);
				castFloatGetF64(ctx);
				ctx.writer.write(f64Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}
		else {
			compileExpr(args.get(1), ctx);
			castI31GetS(ctx);
			for (int i = 2; i < args.size(); i++) {
				compileExpr(args.get(i), ctx);
				castI31GetS(ctx);
				ctx.writer.write(i32Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
	}

	private static void compileComparison(LispCons cons, WasmLispCompiler.Ctx ctx, int i32Opcode, int f64Opcode) {
		List<LispVal> args = cons.toList();
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			compileExpr(args.get(1), ctx);
			castFloatGetF64(ctx);
			compileExpr(args.get(2), ctx);
			castFloatGetF64(ctx);
			ctx.writer.write(f64Opcode);
		}
		else {
			compileExpr(args.get(1), ctx);
			castI31GetS(ctx);
			compileExpr(args.get(2), ctx);
			castI31GetS(ctx);
			ctx.writer.write(i32Opcode);
		}
		emitBoolFromI32(ctx);
	}

	/**
	 * Converts an i32 (0=false, non-0=true) on the WASM stack into a Lisp boolean
	 * (ref.null eq = nil, or i31ref(1) = t).
	 */
	private static void emitBoolFromI32(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	private static void compilePrint(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);
		// Write newline
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	private static void compileStringLiteral(String displayForm, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.StringTable.StringEntry entry = ctx.stringTable.addString(displayForm);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.length());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
	}

	private static void compileQuote(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal quoted = ((LispCons) cons.cdr()).car();
		compileQuotedVal(quoted, ctx);
	}

	private static void compileQuotedVal(LispVal val, WasmLispCompiler.Ctx ctx) {
		switch (val) {
			case LispInteger i -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128((int) i.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispDouble d -> {
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(d.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
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
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	private static void compileQuotedCons(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileQuotedVal(cons.car(), ctx);
		compileQuotedVal(cons.cdr(), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
	}

	private static void compileIf(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		if (parts.size() > 3) {
			compileExpr(parts.get(3), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.ELSE);
		compileExpr(parts.get(2), ctx);
		ctx.writer.write(Instruction.END);
	}

	private static void compileLet(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal bindings = parts.get(1);
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);

		// Pre-scan body for captured vars
		List<LispVal> bodyExprs = parts.subList(2, parts.size());
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				letVarNames.add(((LispSymbol) pair.toList().get(0)).name());
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(bodyExprs, letVarNames, ctx.functions.keySet());

		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				compileExpr(pairList.get(1), ctx);
				if (capturedInLet.contains(name)) {
					// Box in a cell
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
					ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
				}
				int slot = ctx.allocLocal(name);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(slot);
			}
		}

		// Save and extend boxedVars for the let body
		Set<String> savedBoxed = ctx.boxedVars;
		Set<String> newBoxed = new HashSet<>(savedBoxed);
		newBoxed.addAll(capturedInLet);
		ctx.boxedVars = newBoxed;

		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(parts.get(i), ctx);
		}

		ctx.boxedVars = savedBoxed;
		ctx.locals = savedLocals;
	}

	private static void compileProgn(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (i > 1) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(parts.get(i), ctx);
		}
	}

	private static void compileSetq(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();

		// Check if variable is a boxed local
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			// Write to cell: compile value, save to temp, then set cell
			compileExpr(parts.get(2), ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Load cell
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
			// Push value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Set cell field
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
			ctx.writer.writeSignedLeb128(0);
			// Return value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			return;
		}

		// Check if variable is a captured var
		Integer captureIdx = ctx.captures.get(name);
		if (captureIdx != null) {
			// Write to captured cell
			compileExpr(parts.get(2), ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Navigate to cell in env
			emitLoadCaptureCell(ctx, captureIdx);
			// Push value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Set cell
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
			ctx.writer.writeSignedLeb128(0);
			// Return value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			return;
		}

		// Plain local (not boxed)
		compileExpr(parts.get(2), ctx);
		if (slot == null) {
			slot = ctx.allocLocal(name);
		}
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	private static void compileFunctionCall(String name, LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			// Push null env (defun functions ignore it)
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			for (int i = 1; i < args.size(); i++) {
				compileExpr(args.get(i), ctx);
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(fi.funcIndex());
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private static void compileIndirectCall(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		LispSymbol headSym = (LispSymbol) cons.car();
		int arity = args.size() - 1;
		ctx.indirectCallArities.add(arity);
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + arity;

		// Push funcval as first arg to dispatch
		compileSymbolRef(headSym, ctx);

		// Push remaining args
		for (int i = 1; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
		}
		// Call dispatch function
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
	}

	private static void compileFuncall(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 2; // (funcall f arg0 ...) -> arity = num_args
		ctx.indirectCallArities.add(arity);
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + arity;

		// Push funcval
		compileExpr(parts.get(1), ctx);
		// Push args
		for (int i = 2; i < parts.size(); i++) {
			compileExpr(parts.get(i), ctx);
		}
		// Call dispatch
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
	}

	private static void compileLambdaValue(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal paramsVal = parts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		List<LispVal> bodyExprs = parts.subList(2, parts.size());

		// Free variable analysis
		Set<String> boundVars = new HashSet<>(paramNames);
		boundVars.addAll(ctx.locals.keySet());
		if (ctx.captures != null) {
			boundVars.addAll(ctx.captures.keySet());
		}
		LinkedHashSet<String> freeVars = FreeVarAnalyzer.findFreeVars(bodyExprs, new HashSet<>(paramNames),
				ctx.functions.keySet());

		int funcId = ctx.nextFuncId[0]++;
		String methodName = "_lambda_" + funcId;
		int funcIndex = WasmLispCompiler.FUNC_USER_BASE + ctx.functions.size() + ctx.lambdaDecls.size();
		ctx.lambdaDecls.add(new WasmLispCompiler.LambdaInfo(funcId, methodName, paramNames, bodyExprs,
				new ArrayList<>(freeVars), funcIndex));

		// Emit closure creation: {funcId, env}
		// funcId
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(funcId);

		// Build env as cons list of cells for captured variables
		if (freeVars.isEmpty()) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		else {
			// Build cons list right-to-left: (cons cell_0 (cons cell_1 ... null))
			// First push null (end of list)
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			// Iterate free vars in reverse
			List<String> freeVarList = new ArrayList<>(freeVars);
			for (int i = freeVarList.size() - 1; i >= 0; i--) {
				String varName = freeVarList.get(i);
				// Push the cell for this var
				emitLoadVarCell(varName, ctx);
				// Swap: we need (car=cell, cdr=rest) but stack is [rest, cell]
				// Use a temp local
				int tmpCdr = ctx.allocTemp();
				int tmpCar = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(tmpCar);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(tmpCdr);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(tmpCar);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(tmpCdr);
				// struct.new cons
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
			}
		}

		// struct.new closure
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
	}

	private static void compileList(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Build cons list right-to-left
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		for (int i = args.size() - 1; i >= 1; i--) {
			compileExpr(args.get(i), ctx);
			// Swap car and cdr on stack using temp
			int tmpCdr = ctx.allocTemp();
			int tmpCar = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCar);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCdr);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCar);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpCdr);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		}
	}

	private static void compileCar(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0);
	}

	private static void compileCdr(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
	}

	private static void compileConsBuiltin(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
	}

	private static void compileNullPredicate(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.REF_IS_NULL);
		emitBoolFromI32(ctx);
	}

	private static void compileIntegerp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		emitBoolFromI32(ctx);
	}

	private static void compileFloatp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		emitBoolFromI32(ctx);
	}

	private static void compileConsp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitBoolFromI32(ctx);
	}

	private static void compileAtom(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		emitBoolFromI32(ctx);
	}

	private static void compileNumberp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.I32_OR);
		emitBoolFromI32(ctx);
	}

	private static void compileListp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_OR);
		emitBoolFromI32(ctx);
	}

	private static void compileStringp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// It is a string struct; check if first byte is '"'
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeSignedLeb128(0); // field 0: offset
		ctx.writer.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(34); // '"'
		ctx.writer.write(Instruction.I32_EQ);
		emitBoolFromI32(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	private static void compileSymbolp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// It is a string struct; check if first byte is NOT '"'
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeSignedLeb128(0); // field 0: offset
		ctx.writer.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(34); // '"'
		ctx.writer.write(Instruction.I32_NE);
		emitBoolFromI32(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	private static void compileLambdaCall(LispCons lambda, LispCons call, WasmLispCompiler.Ctx ctx) {
		List<LispVal> lambdaParts = lambda.toList();
		LispVal paramsVal = lambdaParts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		List<LispVal> bodyExprs = lambdaParts.subList(2, lambdaParts.size());
		List<LispVal> callArgs = call.toList();

		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);

		for (int i = 0; i < paramNames.size(); i++) {
			compileExpr(callArgs.get(i + 1), ctx);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
		}

		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(bodyExprs.get(i), ctx);
		}

		ctx.locals = savedLocals;
	}

	private static void castI31GetS(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	/**
	 * Runtime type check: convert (ref eq) on stack to f64. If i31ref (integer), converts
	 * via f64.convert_i32_s. If float_struct, extracts f64 field.
	 */
	private static void castFloatGetF64(WasmLispCompiler.Ctx ctx) {
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// i31 path: cast to i31, get_s, convert to f64
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		ctx.writer.write(Instruction.F64_CONVERT_S_I32);
		ctx.writer.write(Instruction.ELSE);
		// float_struct path: cast, extract f64 field
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
	}

	// --- Helper: load captured variable value from env cons list ---
	private static void emitLoadCapture(WasmLispCompiler.Ctx ctx, int depth) {
		// Navigate env cons list to depth, get car (cell), then unbox
		emitLoadCaptureCell(ctx, depth);
		// Unbox from cell
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0);
	}

	private static void emitLoadCaptureCell(WasmLispCompiler.Ctx ctx, int depth) {
		// Load env from slot 0
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(ctx.closureEnvSlot);
		// Navigate through cons list: cdr depth times
		for (int i = 0; i < depth; i++) {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeSignedLeb128(1); // cdr
		}
		// Get car (the cell)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // car = cell
		// Cast to cell
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
	}

	/**
	 * Load the cell (boxed reference) for a variable, for building closure env.
	 */
	private static void emitLoadVarCell(String varName, WasmLispCompiler.Ctx ctx) {
		// First check if it's a boxed local
		Integer slot = ctx.locals.get(varName);
		if (slot != null && ctx.boxedVars.contains(varName)) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			// The local IS the cell
			return;
		}
		// Check if it's a capture (already a cell in the env)
		Integer captureIdx = ctx.captures.get(varName);
		if (captureIdx != null) {
			emitLoadCaptureCell(ctx, captureIdx);
			return;
		}
		// Unboxed local: create a new cell
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
			return;
		}
		throw new UnsupportedOperationException("Cannot find variable for closure: " + varName);
	}

	static void emitBoxLocal(WasmLispCompiler.Ctx ctx, int slot) {
		// Box: load value, create cell, store cell back
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

}
