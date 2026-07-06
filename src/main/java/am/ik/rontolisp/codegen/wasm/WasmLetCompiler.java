package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code let} special form.
 *
 * <p>
 * A binding whose name is a special (dynamically bound) variable is not given a lexical
 * local: instead the special's module-level wasm global is saved into a temp local, set
 * to the init value, and restored to its previous value when the body exits normally -- a
 * dynamic binding, whose new value is visible (via {@code global.get}) to any function
 * called during the body. Restore fires on normal completion; an error is a trap that
 * aborts the module (restore moot). A {@code return} that unwinds (a {@code br}) across
 * the {@code let} boundary does not restore the global (a known compile-path limitation;
 * the interpreter restores on every exit).
 */
final class WasmLetCompiler {

	private WasmLetCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		// A bare symbol entry is an init-less binding to nil.
		LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);

		// Pre-scan body for captured vars. Specials are globals reachable from any
		// function,
		// never captured lexicals, so they are excluded from the capture analysis.
		List<LispVal> bodyExprs = parts.subList(2, parts.size());
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				String name = ((LispSymbol) ((LispCons) binding).toList().get(0)).name();
				if (!ctx.specialVars.contains(name)) {
					letVarNames.add(name);
				}
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(bodyExprs, letVarNames, ctx.functions.keySet());

		// Each dynamic (special) binding established here: {globalIndex, saveSlot}.
		// Restored
		// (reverse order) after the body.
		List<int[]> dynamicRestores = null;
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				if (ctx.specialVars.contains(name)) {
					// Dynamic binding: [init] on stack; save the global into a temp
					// local,
					// then overwrite it with the init. Body reads via global.get.
					int globalIndex = Objects.requireNonNull(ctx.globalIndices.get(name));
					WasmExprCompiler.compileExpr(pairList.get(1), ctx);
					ctx.writer.write(Instruction.GET_GLOBAL);
					ctx.writer.writeUnsignedLeb128(globalIndex);
					int saveSlot = ctx.allocTemp();
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeSignedLeb128(saveSlot);
					ctx.writer.write(Instruction.SET_GLOBAL);
					ctx.writer.writeUnsignedLeb128(globalIndex);
					if (dynamicRestores == null) {
						dynamicRestores = new ArrayList<>();
					}
					dynamicRestores.add(new int[] { globalIndex, saveSlot });
					// Ensure a read in the body resolves to the global, not a stale outer
					// lexical of the same name (specials are never lexical).
					ctx.locals.remove(name);
					continue;
				}
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

		// Restore each dynamically bound special to its saved value. Runs with the body's
		// result on top of the stack; each restore is stack-neutral (local.get;
		// global.set).
		if (dynamicRestores != null) {
			for (int i = dynamicRestores.size() - 1; i >= 0; i--) {
				int[] restore = dynamicRestores.get(i);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(restore[1]);
				ctx.writer.write(Instruction.SET_GLOBAL);
				ctx.writer.writeUnsignedLeb128(restore[0]);
			}
		}

		ctx.boxedVars = savedBoxed;
		ctx.locals = savedLocals;
	}

}
