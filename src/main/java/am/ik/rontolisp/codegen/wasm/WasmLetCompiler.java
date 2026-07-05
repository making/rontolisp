package am.ik.rontolisp.codegen.wasm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code let} special form.
 */
final class WasmLetCompiler {

	private WasmLetCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
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
				WasmExprCompiler.compileExpr(pairList.get(1), ctx);
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

		// Save and adjust boxedVars for the let body. The set tracks names, so each
		// binding's boxedness must REPLACE a shadowed outer binding's: a raw value
		// stored under a name whose outer binding was boxed would otherwise be
		// cell-read in the body (.todo/62).
		Set<String> savedBoxed = ctx.boxedVars;
		Set<String> newBoxed = new HashSet<>(savedBoxed);
		newBoxed.removeAll(letVarNames);
		newBoxed.addAll(capturedInLet);
		ctx.boxedVars = newBoxed;

		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.writer.write(Instruction.DROP);
			}
			WasmExprCompiler.compileExpr(parts.get(i), ctx);
		}

		ctx.boxedVars = savedBoxed;
		ctx.locals = savedLocals;
	}

}
