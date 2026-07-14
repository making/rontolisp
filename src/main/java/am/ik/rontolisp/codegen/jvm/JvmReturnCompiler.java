package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code return} special form: a non-local exit from the nearest enclosing
 * {@code %block} (established by the loop macros). The optional value (default nil) is
 * stored into the block's value slot and an unconditional {@code goto} jumps to the
 * block's exit; the jump's position is recorded so {@link JvmBlockCompiler} can patch it.
 *
 * <p>
 * When the jump escapes one or more {@code unwind-protect} protected regions (the scope
 * was entered inside the target block), their cleanup forms are compiled inline before
 * the {@code goto}, innermost first -- the CL unwinding order. The inlined sequence is
 * recorded as a hole in each escaped scope from that scope's own cleanup onward: once a
 * scope's cleanup has started, a throw from the remaining sequence must no longer
 * re-enter that scope's handler (its cleanup already ran), while a throw from an inner
 * scope's cleanup still lands in the outer scopes' handlers.
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
		compileEscapedCleanups(ctx, className);
		emitStackUnwind(ctx, target);
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		target.exitPatches().add(gotoPos);
	}

	/**
	 * Brings the operand stack to the shape the target block's exit is reached with on
	 * every other path -- the stack the block was entered with. The operands the body
	 * pushed on top of it belong to expressions this exit abandons half-evaluated, so
	 * they are simply discarded.
	 *
	 * <p>
	 * A {@code handler-case} this {@code return} escapes complicates that: it spilled the
	 * operand stack into locals and compiled its body on an empty one (see
	 * {@link JvmHandlerCaseCompiler}), so the block's own operands are no longer on the
	 * stack to keep -- they are reloaded from the outermost escaped spill, whose saved
	 * stack has the block's as its bottom (a block enclosing the catching form was
	 * entered with fewer operands).
	 */
	private static void emitStackUnwind(JvmLispCompiler.Ctx ctx, JvmLispCompiler.BlockTarget target) {
		int exitDepth = target.entryStack().size();
		JvmLispCompiler.SpillScope escaped = null;
		for (JvmLispCompiler.SpillScope scope : ctx.spillScopes) {
			if (scope.blockDepth() < ctx.blockTargets.size()) {
				break;
			}
			escaped = scope;
		}
		if (escaped == null) {
			ctx.discardOperandsDownTo(exitDepth);
			return;
		}
		ctx.discardOperandsDownTo(0);
		escaped.spill().restore(ctx, exitDepth);
	}

	/**
	 * Compiles the cleanup forms of every {@code unwind-protect} scope this
	 * {@code return} escapes -- the scopes entered while the target block was already the
	 * innermost ({@code blockDepth >=} the current block-stack depth), innermost first.
	 * The scope stack iterates top-down and block/unwind scopes nest properly, so the
	 * scan stops at the first scope that encloses the target block.
	 */
	private static void compileEscapedCleanups(JvmLispCompiler.Ctx ctx, String className) {
		List<JvmLispCompiler.UnwindScope> escaped = new ArrayList<>();
		for (JvmLispCompiler.UnwindScope scope : ctx.unwindScopes) {
			if (scope.blockDepth < ctx.blockTargets.size()) {
				break;
			}
			escaped.add(scope);
		}
		if (escaped.isEmpty()) {
			return;
		}
		int[] holeStarts = new int[escaped.size()];
		for (int i = 0; i < escaped.size(); i++) {
			holeStarts[i] = ctx.code.size();
			JvmUnwindProtectCompiler.compileCleanups(escaped.get(i).cleanupForms, ctx, className);
		}
		int sequenceEnd = ctx.code.size();
		for (int i = 0; i < escaped.size(); i++) {
			escaped.get(i).holes.add(new int[] { holeStarts[i], sequenceEnd });
		}
	}

}
