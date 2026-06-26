package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code defvar} special form. A top-level variable is stored in a plain
 * local, mirroring a top-level {@code setq}; the assignment is idempotent (Common Lisp
 * semantics): the initial value is bound only when the variable has not already been
 * bound, the compile-time analog of "if not already bound". The form returns the variable
 * name symbol. {@code defparameter}/{@code defconstant} pass {@code force=true} and
 * always (re)bind the initial value.
 */
final class WasmDefvarCompiler {

	private WasmDefvarCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, boolean force) {
		List<LispVal> parts = cons.toList();
		LispSymbol name = (LispSymbol) parts.get(1);
		if (parts.size() > 2 && (force || !ctx.locals.containsKey(name.name()))) {
			WasmExprCompiler.compileExpr(parts.get(2), ctx);
			int slot = ctx.allocLocal(name.name());
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			// Mirror the binding into the eval runtime's global env (no-op unless eval is
			// used at top level); the stack is left clean (the SET_LOCAL consumed the
			// value and mirrorTopLevelGlobal drops the _store return).
			WasmSetqCompiler.mirrorTopLevelGlobal(name.name(), slot, ctx);
		}
		// defvar returns the variable name symbol.
		WasmExprCompiler
			.compileExpr(new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(name, LispNil.INSTANCE)), ctx);
	}

}
