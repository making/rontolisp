package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
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
		LambdaLists.NativeForm nf = LambdaLists.toNative(parts.get(1), parts.subList(2, parts.size()));
		List<String> paramNames = nf.paramNames();
		List<LispVal> bodyExprs = nf.body();

		// Free variable analysis. A global, function or built-in name shadowed by a
		// lexical binding visible at this lambda's creation site (an enclosing let
		// variable, defun parameter, or an outer closure's capture) must be captured
		// like any other free variable: Lisp-2 means a bare symbol is always a
		// variable reference.
		Set<String> enclosingLexicals = new HashSet<>(ctx.locals.keySet());
		enclosingLexicals.addAll(ctx.captures.keySet());
		LinkedHashSet<String> freeVars = FreeVarAnalyzer.findFreeVars(bodyExprs, new HashSet<>(paramNames),
				ctx.functions.keySet(), ctx.globals, enclosingLexicals);

		int funcId = ctx.nextFuncId[0]++;
		if (ctx.injectedRuntimeBody) {
			// Built by an injected wrapper body, so Pass 2c compiles it as one too
			// (Ctx.injectedRuntimeLambdas).
			ctx.injectedRuntimeLambdas.add(funcId);
		}
		String methodName = "_lambda_" + funcId;
		int funcIndex = ctx.userFuncBase + ctx.numDefuns + ctx.lambdaDecls.size();
		ctx.lambdaDecls.add(new WasmLispCompiler.LambdaInfo(funcId, methodName, paramNames, nf.variadic(), bodyExprs,
				new ArrayList<>(freeVars), funcIndex));

		emitClosureValue(funcId, new ArrayList<>(freeVars), ctx);
	}

	/**
	 * Emits a closure value {@code {funcId, env}} whose environment is the cons list of
	 * the given captured variables' cells (shared with the async-lambda entry pair, see
	 * {@code WasmAsyncEmit}).
	 * @param funcId the callee's funcId
	 * @param freeVarList the captured variable names, in capture order
	 * @param ctx the compilation context
	 */
	static void emitClosureValue(int funcId, List<String> freeVarList, WasmLispCompiler.Ctx ctx) {
		// The other place a funcId becomes a callable VALUE (see Ctx.valueFuncIds): a
		// lambda is only ever reached through a dispatcher, so its case must stay --
		// but only when some emitted body actually builds the closure.
		ctx.valueFuncIds.add(funcId);
		// funcId
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(funcId);

		// Build env as cons list of cells for captured variables
		if (freeVarList.isEmpty()) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		else {
			// Build cons list right-to-left: (cons cell_0 (cons cell_1 ... null))
			// First push null (end of list)
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			// Iterate free vars in reverse
			for (int i = freeVarList.size() - 1; i >= 0; i--) {
				String varName = freeVarList.get(i);
				// Push the cell for this var
				WasmEmitHelper.emitLoadVarCell(varName, ctx);
				// Swap: we need (car=cell, cdr=rest) but stack is [rest, cell]
				// Use a temp local
				int tmpCdr = ctx.allocTemp();
				int tmpCar = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(tmpCar);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(tmpCdr);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(tmpCar);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(tmpCdr);
				// struct.new cons
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			}
		}

		// struct.new closure
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
	}

	/**
	 * Compiles an inline lambda call: {@code ((lambda (params) body) args)}.
	 */
	static void compileCall(LispCons lambda, LispCons call, WasmLispCompiler.Ctx ctx) {
		List<LispVal> lambdaParts = lambda.toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(lambdaParts.get(1),
				lambdaParts.subList(2, lambdaParts.size()));
		List<String> paramNames = nf.paramNames();
		List<LispVal> bodyExprs = nf.body();
		List<LispVal> callArgs = call.toList();
		int required = paramNames.size() - (nf.variadic() ? 1 : 0);
		int supplied = callArgs.size() - 1;
		if (supplied < required || (!nf.variadic() && supplied > required)) {
			throw new UnsupportedOperationException("lambda expects " + (nf.variadic() ? "at least " : "") + required
					+ " argument" + (required == 1 ? "" : "s") + ", got " + supplied);
		}

		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		Set<String> savedBoxed = ctx.boxedVars;
		// The parameters are bound HERE, in the caller's frame, so this is a binder and
		// it owes the same question every other binder asks: does a closure in the body
		// read this name? One owner answers it -- FreeVarAnalyzer.findCapturedVars, the
		// same call WasmLetCompiler and the lambda prologue make -- because the closure
		// emitter decides capture from findFreeVars, and a binder that does not agree
		// with it hands the closure a private snapshot cell instead of the binding
		// (.kb/core-representation.md).
		Set<String> capturedParams = FreeVarAnalyzer.findCapturedVars(bodyExprs, new HashSet<>(paramNames),
				ctx.functions.keySet());
		ctx.boxedVars = new HashSet<>(savedBoxed);
		// The maps are keyed on names, so an outer unboxed dual-representation local or
		// local integer lambda the parameters SHADOW must stop answering for the body.
		Map<String, WasmIntFusionCompiler.RawLocal> savedRawLocals = ctx.rawLocals;
		Map<String, WasmIntFusionCompiler.LocalIntLambda> savedLocalLambdas = ctx.localIntLambdas;
		if (paramNames.stream().anyMatch(savedRawLocals::containsKey)) {
			Map<String, WasmIntFusionCompiler.RawLocal> shadowed = new HashMap<>(savedRawLocals);
			paramNames.forEach(shadowed::remove);
			ctx.rawLocals = shadowed;
		}
		if (paramNames.stream().anyMatch(savedLocalLambdas::containsKey)) {
			Map<String, WasmIntFusionCompiler.LocalIntLambda> shadowed = new HashMap<>(savedLocalLambdas);
			paramNames.forEach(shadowed::remove);
			ctx.localIntLambdas = shadowed;
		}

		for (int i = 0; i < required; i++) {
			String name = paramNames.get(i);
			WasmExprCompiler.compileExpr(callArgs.get(i + 1), ctx);
			if (capturedParams.contains(name)) {
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
			}
			int slot = ctx.allocLocal(name);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			bindName(name, capturedParams.contains(name), ctx);
		}
		if (nf.variadic()) {
			// Evaluate the surplus arguments left to right into temps, then link them
			// into a cons list bound to the rest parameter.
			List<Integer> extraSlots = new ArrayList<>();
			for (int i = required; i < supplied; i++) {
				WasmExprCompiler.compileExpr(callArgs.get(i + 1), ctx);
				int s = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(s);
				extraSlots.add(s);
			}
			String restName = paramNames.get(required);
			int restSlot = ctx.allocLocal(restName);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(restSlot);
			for (int k = extraSlots.size() - 1; k >= 0; k--) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(extraSlots.get(k));
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
			}
			if (capturedParams.contains(restName)) {
				// The list is built in place, so the cell wraps the finished value
				// rather than the build.
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
			}
			bindName(restName, capturedParams.contains(restName), ctx);
		}

		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.writer.write(Instruction.DROP);
			}
			WasmExprCompiler.compileExpr(bodyExprs.get(i), ctx);
		}

		ctx.locals = savedLocals;
		ctx.boxedVars = savedBoxed;
		ctx.rawLocals = savedRawLocals;
		ctx.localIntLambdas = savedLocalLambdas;
	}

	/**
	 * Records what representation a freshly bound name has, REPLACING whatever a shadowed
	 * outer binding of the same name had: the set is keyed on names, so an outer boxed
	 * binding would otherwise still answer for this one.
	 */
	private static void bindName(String name, boolean boxed, WasmLispCompiler.Ctx ctx) {
		if (boxed) {
			ctx.boxedVars.add(name);
		}
		else {
			ctx.boxedVars.remove(name);
		}
	}

}
