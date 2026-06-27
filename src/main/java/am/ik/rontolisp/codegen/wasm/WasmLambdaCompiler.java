package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles {@code lambda} expressions (both value creation and inline calls).
 */
final class WasmLambdaCompiler {

	private WasmLambdaCompiler() {
	}

	/**
	 * Compiles a lambda expression as a closure value.
	 */
	static void compileValue(LispCons cons, WasmLispCompiler.Ctx ctx) {
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
				ctx.functions.keySet(), ctx.globals);

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
				WasmEmitHelper.emitLoadVarCell(varName, ctx);
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

	/**
	 * Compiles an inline lambda call: {@code ((lambda (params) body) args)}.
	 */
	static void compileCall(LispCons lambda, LispCons call, WasmLispCompiler.Ctx ctx) {
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
			WasmExprCompiler.compileExpr(callArgs.get(i + 1), ctx);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
		}

		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.writer.write(Instruction.DROP);
			}
			WasmExprCompiler.compileExpr(bodyExprs.get(i), ctx);
		}

		ctx.locals = savedLocals;
	}

}
