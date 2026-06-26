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
		List<LispVal> parts = cons.toList();
		LispSymbol name = (LispSymbol) parts.get(1);
		if (parts.size() > 2 && (force || !ctx.locals.containsKey(name.name()))) {
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
			// Mirror the binding into the eval runtime's global env (no-op unless eval is
			// used at top level); _store returns the value, which we discard here because
			// the local slot keeps the compiled copy.
			if (ctx.topLevel && ctx.evalStoreRef != null) {
				ctx.emit(Opcode.DUP);
				JvmSetqCompiler.mirrorTopLevelGlobal(name.name(), ctx);
				ctx.emit(Opcode.POP);
			}
			int slot = ctx.allocLocal(name.name());
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
		}
		// defvar returns the variable name symbol.
		JvmExprCompiler.compileExpr(new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(name, LispNil.INSTANCE)),
				ctx, className);
	}

}
