package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code while} special form. Repeatedly evaluates the body while the test
 * expression is non-nil, then leaves nil on the stack as the loop's value.
 *
 * <p>
 * The enclosing expression's pending operands are spilled to locals first, so the loop
 * head -- the backedge target -- sits at operand stack depth 0, the only shape HotSpot
 * will enter an on-stack-replacement compilation at ({@link JvmTagbodyCompiler} has the
 * full reasoning). This is the lowering
 * {@code do}/{@code do*}/{@code dotimes}/{@code dolist} and most of {@code loop} reach,
 * so an unspilled loop head here is what a {@code getf} inside an argument list, or
 * {@code (setq s (+ s (loop ...)))}, would leave running in the bytecode interpreter
 * forever. The operands are reloaded under the loop's nil result on the way out.
 */
final class JvmWhileCompiler {

	private JvmWhileCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		JvmLispCompiler.Ctx.Spill spill = JvmEmitHelper.enterLoopScope(ctx);
		int loopStart = ctx.code.size();
		// Evaluate the test; if false, branch out of the loop. A fusable binary
		// comparison leaves a RAW int truth value (no boxed t/nil per iteration,
		// .kb/jvm-int-fusion.md); any other test compiles boxed as before.
		int exitBranchOpcode;
		if (JvmIntFusionCompiler.tryCompileCondition(parts.get(1), ctx, className)) {
			exitBranchOpcode = Opcode.IFEQ;
		}
		else {
			JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
			exitBranchOpcode = Opcode.IFNULL;
		}
		int ifNullPos = ctx.code.size();
		ctx.emit(exitBranchOpcode);
		ctx.emitU2(0);
		// Body: every expression leaves a (boxed) reference, which is discarded.
		for (int i = 2; i < parts.size(); i++) {
			JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
			ctx.emit(Opcode.POP);
		}
		// Jump back to re-evaluate the test.
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, gotoPos, loopStart);
		// Loop exit: patch the IFNULL here and push nil as the result.
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		JvmEmitHelper.leaveLoopScope(ctx, spill);
		ctx.emit(Opcode.ACONST_NULL);
	}

}
