package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code return} special form: a non-local exit from the nearest enclosing
 * {@code %block} (established by the loop macros). The optional value (default nil) is
 * stored into the block's value slot and an unconditional {@code goto} jumps to the
 * block's exit; the jump's position is recorded so {@link JvmBlockCompiler} can patch it.
 */
final class JvmReturnCompiler {

	private JvmReturnCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.BlockTarget target = ctx.blockTargets.peek();
		if (target == null) {
			throw new IllegalStateException("Cannot compile return outside of a loop block");
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() > 1) {
			JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		ctx.emit(Opcode.ASTORE);
		ctx.emit(target.rvSlot());
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		target.exitPatches().add(gotoPos);
	}

}
