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
		else {
			ctx.emit(Opcode.DUP);
			if (slot == null) {
				slot = ctx.allocLocal(name);
			}
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
		}
	}

}
