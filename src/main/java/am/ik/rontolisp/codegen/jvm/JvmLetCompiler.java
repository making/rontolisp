package am.ik.rontolisp.codegen.jvm;

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
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code let} special form.
 *
 * <p>
 * A binding whose name is a special (dynamically bound) variable is not given a lexical
 * slot: instead the special's global static field is saved, set to the init value, and
 * restored to its previous value when the body exits normally -- a dynamic binding, whose
 * new value is visible (via {@code getstatic}) to any function called during the body.
 * Restore fires on normal completion and, for an error, is moot (the error aborts the
 * program). A {@code return}/{@code return-from} that unwinds across the {@code let}
 * boundary does not restore the field (a known compile-path limitation; the interpreter
 * restores on every exit).
 */
final class JvmLetCompiler {

	private JvmLetCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		// A bare symbol entry is an init-less binding to nil.
		LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		Set<String> savedBoxedVars = new HashSet<>(ctx.boxedVars);
		int savedNextLocal = ctx.nextLocal;
		// Every binding name takes part in capture analysis: a special-named binding is
		// DUAL-BOUND (dynamic set + a lexical slot, mirroring the interpreter), so a
		// closure built in the body captures the entry value and can read it after the
		// dynamic extent ended (cl-ppcre's end-string).
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				letVarNames.add(((LispSymbol) ((LispCons) binding).toList().get(0)).name());
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(parts.subList(2, parts.size()), letVarNames,
				ctx.functions.keySet());
		ctx.boxedVars = new HashSet<>(ctx.boxedVars);
		// Each dynamic (special) binding established here: {globalFieldIndex, saveSlot}.
		// Restored (reverse order) after the body, before the scope is popped.
		List<int[]> dynamicRestores = null;
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				if (ctx.specialVars.contains(name)) {
					// DUAL-BIND (interpreter parity): the global static field is saved
					// and overwritten with the init (the dynamic binding a called
					// function reads), AND the same value gets a lexical slot so a
					// closure built in the body captures it -- the closure may run
					// after this extent ended and restored the global (cl-ppcre's
					// end-string). Body reads resolve to the local, which equals the
					// global within the extent; a setq of the name writes BOTH
					// (JvmSetqCompiler).
					int fieldIndex = Objects.requireNonNull(ctx.globalFields.get(name)).index();
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.GETSTATIC);
					ctx.emitU2(fieldIndex);
					int saveSlot = ctx.allocTemp();
					ctx.emit(Opcode.ASTORE);
					ctx.emit(saveSlot);
					ctx.emit(Opcode.PUTSTATIC);
					ctx.emitU2(fieldIndex);
					if (dynamicRestores == null) {
						dynamicRestores = new ArrayList<>();
					}
					dynamicRestores.add(new int[] { fieldIndex, saveSlot });
					ctx.specialBindScopes.push(new int[] { fieldIndex, saveSlot, ctx.blockTargets.size() });
					if (capturedInLet.contains(name)) {
						int tmpSlot = ctx.allocTemp();
						ctx.emit(Opcode.ASTORE);
						ctx.emit(tmpSlot);
						ctx.emit(Opcode.ICONST_1);
						ctx.emit(Opcode.ANEWARRAY);
						ctx.emitU2(ctx.objectClass.index());
						ctx.emit(Opcode.DUP);
						ctx.emit(Opcode.ICONST_0);
						ctx.emit(Opcode.ALOAD);
						ctx.emit(tmpSlot);
						ctx.emit(Opcode.AASTORE);
					}
					int lexSlot = ctx.allocLocal(name);
					ctx.emit(Opcode.ASTORE);
					ctx.emit(lexSlot);
					if (capturedInLet.contains(name)) {
						ctx.boxedVars.add(name);
					}
					else {
						ctx.boxedVars.remove(name);
					}
					continue;
				}
				if (capturedInLet.contains(name)) {
					ctx.emit(Opcode.ICONST_1);
					ctx.emit(Opcode.ANEWARRAY);
					ctx.emitU2(ctx.objectClass.index());
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.ICONST_0);
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
					ctx.emit(Opcode.AASTORE);
				}
				else {
					JvmExprCompiler.compileExpr(pairList.get(1), ctx, className);
				}
				int slot = ctx.allocLocal(name);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(slot);
				// The boxed set tracks names, so this binding's boxedness must
				// REPLACE a shadowed outer binding's: a raw closure stored under a
				// name whose outer binding was boxed would otherwise be cell-read in
				// the body. Updated only after the init compiled, so the init
				// (evaluated in the outer scope, e.g. a lambda capturing the
				// same-named outer variable) still sees the outer boxedness.
				if (capturedInLet.contains(name)) {
					ctx.boxedVars.add(name);
				}
				else {
					ctx.boxedVars.remove(name);
				}
			}
		}
		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.emit(Opcode.POP);
			}
			JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
		}
		// Restore each dynamically bound special to its saved value. This runs with the
		// body's result on top of the stack; each restore is stack-neutral (aload;
		// putstatic)
		// so the result is preserved.
		if (dynamicRestores != null) {
			for (int i = dynamicRestores.size() - 1; i >= 0; i--) {
				int[] restore = dynamicRestores.get(i);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(restore[1]);
				ctx.emit(Opcode.PUTSTATIC);
				ctx.emitU2(restore[0]);
				ctx.specialBindScopes.pop();
			}
		}
		ctx.locals = savedLocals;
		ctx.boxedVars = savedBoxedVars;
		ctx.nextLocal = savedNextLocal;
	}

}
