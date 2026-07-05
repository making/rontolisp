package am.ik.rontolisp.codegen.jvm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code let} special form.
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
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				letVarNames.add(((LispSymbol) pair.toList().get(0)).name());
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(parts.subList(2, parts.size()), letVarNames,
				ctx.functions.keySet());
		ctx.boxedVars = new HashSet<>(ctx.boxedVars);
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
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
				// the body (.todo/62). Updated only after the init compiled, so the
				// init (evaluated in the outer scope, e.g. a lambda capturing the
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
		ctx.locals = savedLocals;
		ctx.boxedVars = savedBoxedVars;
		ctx.nextLocal = savedNextLocal;
	}

}
