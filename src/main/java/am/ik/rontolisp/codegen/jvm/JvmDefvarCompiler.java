package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code defvar} special form. A top-level variable is stored in a main()
 * local, mirroring a top-level {@code setq}; the assignment is idempotent (Common Lisp
 * semantics): the initial value is bound only when the variable has not already been
 * bound, the compile-time analog of "if not already bound". The form returns the variable
 * name symbol. {@code defparameter}/{@code defconstant} pass {@code force=true} and
 * always (re)bind the initial value.
 */
final class JvmDefvarCompiler {

	private JvmDefvarCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, boolean force) {
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
		if (ctx.globals.contains(name.name())) {
			// A top-level global: store into its dedicated static field. defvar binds
			// only
			// if not already bound (idempotent); defparameter/defconstant (force) always
			// rebind. definedGlobals tracks compile-time "already bound".
			if (parts.size() > 2 && (force || !ctx.definedGlobals.contains(name.name()))) {
				JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.PUTSTATIC);
				ctx.emitU2(java.util.Objects.requireNonNull(ctx.globalFields.get(name.name())).index());
				// Mirror into the eval runtime's global env (no-op unless eval is used at
				// top level); leaves the stack as it was (the DUP'd copy is consumed by
				// the
				// mirror's _store, which returns it, then we pop it).
				JvmSetqCompiler.mirrorTopLevelGlobal(name.name(), ctx);
				ctx.emit(Opcode.POP);
				ctx.definedGlobals.add(name.name());
			}
		}
		else if (parts.size() > 2
				&& (force || !(ctx.locals.containsKey(name.name()) || ctx.rawLocals.containsKey(name.name())))) {
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
			// Mirror the binding into the eval runtime's global env; _store returns the
			// value, which we discard here because the local slot keeps the compiled
			// copy. A name without a global backing store never reaches the mirror
			// (JvmSetqCompiler.mirrorsTopLevelGlobal), so the DUP/POP is emitted only
			// when the mirror is.
			if (JvmSetqCompiler.mirrorsTopLevelGlobal(name.name(), ctx)) {
				ctx.emit(Opcode.DUP);
				JvmSetqCompiler.mirrorTopLevelGlobal(name.name(), ctx);
				ctx.emit(Opcode.POP);
			}
			int slot = ctx.allocLocal(name.name());
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
		}
		// defvar returns the variable name symbol -- unless the caller is dropping it.
		if (emitName) {
			JvmExprCompiler.compileExpr(
					new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(name, LispNil.INSTANCE)), ctx,
					className);
		}
	}

}
