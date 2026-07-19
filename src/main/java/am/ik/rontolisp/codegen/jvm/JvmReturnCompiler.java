package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code return} special form: a non-local exit from the nearest enclosing
 * block that catches plain {@code return} ({@code %block} or {@code (block nil ...)};
 * named blocks in between are skipped -- on the interpreter the plain signal passes
 * through them the same way). The optional value (default nil) is stored into the block's
 * value slot and an unconditional {@code goto} jumps to the block's exit; the jump's
 * position is recorded so {@link JvmBlockCompiler} can patch it. The same emit sequence,
 * generalized to a target anywhere in the block stack, is shared with
 * {@link JvmReturnFromCompiler}.
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
		int targetDepth = findPlainTargetDepth(ctx);
		if (targetDepth == 0) {
			throw new IllegalStateException("Cannot compile return outside of a loop block");
		}
		List<LispVal> parts = cons.toList();
		emitExit(parts.size() > 1 ? parts.get(1) : null, ctx, className, targetDepth);
	}

	/**
	 * The 1-based depth (from the bottom of the block stack) of the nearest enclosing
	 * block that catches plain {@code return}, or 0 when none encloses.
	 */
	static int findPlainTargetDepth(JvmLispCompiler.Ctx ctx) {
		int idxFromTop = 0;
		for (JvmLispCompiler.BlockTarget target : ctx.blockTargets) {
			if (target.catchesPlain()) {
				return ctx.blockTargets.size() - idxFromTop;
			}
			idxFromTop++;
		}
		return 0;
	}

	/**
	 * Emits the exit sequence to the block at {@code targetDepth} (1-based from the
	 * bottom of the block stack): the value (nil when {@code valueForm} is null) is
	 * stored into the target's slot, the cleanups of every escaped {@code unwind-protect}
	 * scope run inline, the operand stack is unwound to the target's entry shape and a
	 * {@code goto} (patched by {@link JvmBlockCompiler}) jumps to the target's exit.
	 */
	static void emitExit(@Nullable LispVal valueForm, JvmLispCompiler.Ctx ctx, String className, int targetDepth) {
		JvmLispCompiler.BlockTarget target = targetAt(ctx, targetDepth);
		if (valueForm != null) {
			JvmExprCompiler.compileExpr(valueForm, ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		ctx.emit(Opcode.ASTORE);
		ctx.emit(target.rvSlot());
		compileEscapedCleanups(ctx, className, targetDepth);
		// Restore every special-variable dynamic binding this exit escapes (the
		// bindings established inside the target block), innermost first -- so a named
		// exit from a scan closure does not leak the bound value into the global.
		for (int[] scope : ctx.specialBindScopes) {
			if (scope[2] < targetDepth) {
				break;
			}
			ctx.emit(Opcode.ALOAD);
			ctx.emit(scope[1]);
			ctx.emit(Opcode.PUTSTATIC);
			ctx.emitU2(scope[0]);
		}
		emitStackUnwind(ctx, target, targetDepth);
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		target.exitPatches().add(gotoPos);
	}

	private static JvmLispCompiler.BlockTarget targetAt(JvmLispCompiler.Ctx ctx, int targetDepth) {
		int idxFromTop = 0;
		for (JvmLispCompiler.BlockTarget target : ctx.blockTargets) {
			if (ctx.blockTargets.size() - idxFromTop == targetDepth) {
				return target;
			}
			idxFromTop++;
		}
		throw new IllegalStateException("No block target at depth " + targetDepth);
	}

	/**
	 * Brings the operand stack to the shape the target block's exit is reached with on
	 * every other path -- the stack the block was entered with. The operands the body
	 * pushed on top of it belong to expressions this exit abandons half-evaluated, so
	 * they are simply discarded.
	 *
	 * <p>
	 * A {@code handler-case} this exit escapes complicates that: it spilled the operand
	 * stack into locals and compiled its body on an empty one (see
	 * {@link JvmHandlerCaseCompiler}), so the block's own operands are no longer on the
	 * stack to keep -- they are reloaded from the outermost escaped spill, whose saved
	 * stack has the block's as its bottom (a block enclosing the catching form was
	 * entered with fewer operands).
	 */
	private static void emitStackUnwind(JvmLispCompiler.Ctx ctx, JvmLispCompiler.BlockTarget target, int targetDepth) {
		int exitDepth = target.entryStack().size();
		JvmLispCompiler.SpillScope escaped = null;
		for (JvmLispCompiler.SpillScope scope : ctx.spillScopes) {
			if (scope.blockDepth() < targetDepth) {
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
	 * Compiles the cleanup forms of every {@code unwind-protect} scope this exit escapes
	 * -- the scopes entered while the target block was already on the block stack
	 * ({@code blockDepth >= targetDepth}), innermost first. The scope stack iterates
	 * top-down and block/unwind scopes nest properly, so the scan stops at the first
	 * scope that encloses the target block.
	 */
	private static void compileEscapedCleanups(JvmLispCompiler.Ctx ctx, String className, int targetDepth) {
		List<JvmLispCompiler.UnwindScope> escaped = new ArrayList<>();
		for (JvmLispCompiler.UnwindScope scope : ctx.unwindScopes) {
			if (scope.blockDepth < targetDepth) {
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
