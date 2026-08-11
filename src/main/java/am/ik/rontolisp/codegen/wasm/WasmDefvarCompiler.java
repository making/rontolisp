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
		// Take the top-level emitter's offer to drop the name this form returns, if the
		// offer is for THIS form (compiler/ToplevelStatements,
		// .kb/toplevel-statement-values.md). Cleared here, before the init expression is
		// compiled, so a nested definer cannot take it and so the emitter can see that it
		// was taken.
		boolean emitName = true;
		if (ctx.definerNameDropped == cons) {
			ctx.definerNameDropped = null;
			emitName = false;
		}
		List<LispVal> parts = cons.toList();
		LispSymbol name = (LispSymbol) parts.get(1);
		Integer globalIndex = ctx.globalIndices.get(name.name());
		if (globalIndex != null) {
			// A top-level global: store into its module-level wasm global. defvar binds
			// only if not already bound (idempotent); defparameter/defconstant (force)
			// always rebind. definedGlobals tracks compile-time "already bound".
			if (parts.size() > 2 && (force || !ctx.definedGlobals.contains(name.name()))) {
				// state-machine mode: the init is a spine child (empty stack)
				WasmAsyncEmit.spine(parts.get(2), ctx);
				// The tee stages the value for the eval mirror to read back, so it is
				// emitted only when the mirror is: a program that never evals would
				// otherwise pay a local.tee AND a local per top-level binding for a
				// reader that is not there.
				int tmpSlot = -1;
				if (WasmSetqCompiler.mirrorsTopLevelGlobal(ctx)) {
					tmpSlot = ctx.allocTemp();
					ctx.writer.write(Instruction.TEE_LOCAL);
					ctx.writer.writeUnsignedLeb128(tmpSlot);
				}
				ctx.writer.write(Instruction.SET_GLOBAL);
				ctx.writer.writeUnsignedLeb128(globalIndex);
				// Stack stays clean (SET_GLOBAL consumed the value, the mirror drops the
				// _store return).
				if (tmpSlot >= 0) {
					WasmSetqCompiler.mirrorTopLevelGlobal(name.name(), tmpSlot, ctx);
				}
				ctx.definedGlobals.add(name.name());
			}
		}
		else if (parts.size() > 2
				&& (force || !(ctx.locals.containsKey(name.name()) || ctx.rawLocals.containsKey(name.name())))) {
			WasmAsyncEmit.spine(parts.get(2), ctx);
			int slot = ctx.allocLocal(name.name());
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			// Mirror the binding into the eval runtime's global env (no-op unless eval is
			// used at top level); the stack is left clean (the SET_LOCAL consumed the
			// value and mirrorTopLevelGlobal drops the _store return).
			WasmSetqCompiler.mirrorTopLevelGlobal(name.name(), slot, ctx);
		}
		// defvar returns the variable name symbol -- unless the caller is dropping it.
		if (emitName) {
			WasmExprCompiler
				.compileExpr(new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(name, LispNil.INSTANCE)), ctx);
		}
	}

}
