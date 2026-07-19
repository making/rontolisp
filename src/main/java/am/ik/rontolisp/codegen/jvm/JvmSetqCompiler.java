package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code setq} special form.
 */
final class JvmSetqCompiler {

	private JvmSetqCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if ((parts.size() - 1) % 2 != 0) {
			throw new IllegalArgumentException("setq requires an even number of arguments");
		}
		if (parts.size() == 1) {
			// (setq) -> nil
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		int pairCount = (parts.size() - 1) / 2;
		for (int p = 0; p < pairCount; p++) {
			compilePair(((LispSymbol) parts.get(1 + 2 * p)).name(), parts.get(2 + 2 * p), ctx, className);
			if (p < pairCount - 1) {
				// Discard the intermediate value; only the last pair's value is the
				// result
				ctx.emit(Opcode.POP);
			}
		}
	}

	private static void compilePair(String name, LispVal valueExpr, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(valueExpr, ctx, className);
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
		}
		else if (ctx.captures.containsKey(name)) {
			int captureIdx = ctx.captures.get(name);
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
			JvmEmitHelper.emitIntConst(ctx, 1 + captureIdx);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
		}
		else if (slot == null && ctx.globals.contains(name)) {
			// A top-level global variable (not shadowed by a lexical here): store into
			// its
			// dedicated static field. Works from any method body, so a defun/lambda can
			// assign a global. The eval mirror still runs at top level (no-op elsewhere).
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.PUTSTATIC);
			ctx.emitU2(java.util.Objects.requireNonNull(ctx.globalFields.get(name)).index());
			mirrorTopLevelGlobal(name, ctx);
		}
		else {
			ctx.emit(Opcode.DUP);
			if (slot == null) {
				slot = ctx.allocLocal(name);
			}
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
			mirrorTopLevelGlobal(name, ctx);
		}
		// A special that is dual-bound here (a lexical slot/capture established by a
		// special-named let, see JvmLetCompiler): the assignment must reach the DYNAMIC
		// binding too, so a called function reading the special sees it.
		if (ctx.specialVars.contains(name) && (ctx.locals.containsKey(name) || ctx.captures.containsKey(name))
				&& ctx.globalFields.containsKey(name)) {
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.PUTSTATIC);
			ctx.emitU2(java.util.Objects.requireNonNull(ctx.globalFields.get(name)).index());
		}
	}

	/**
	 * Mirrors a top-level global variable binding into the embedded {@code eval}
	 * runtime's global environment, so an eval'd expression can resolve a variable that
	 * compiled code defined via {@code setq}/{@code defvar} (the compiled value otherwise
	 * lives only in a {@code main()} local the interpreter cannot see). No-op unless the
	 * program uses {@code eval} and this is the top-level context. Expects the assigned
	 * value on the stack and leaves it there (the {@code _store} call returns it).
	 */
	static void mirrorTopLevelGlobal(String name, JvmLispCompiler.Ctx ctx) {
		if (!ctx.topLevel || ctx.evalStoreRef == null) {
			return;
		}
		// stack: value -> _store(name, value, null) -> value
		JvmEmitHelper.compileStringLiteral(name, ctx);
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.evalStoreRef.index());
	}

}
