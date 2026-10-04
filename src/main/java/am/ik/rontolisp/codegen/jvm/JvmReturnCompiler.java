package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.MethodCode;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code return} special form: a non-local exit from the nearest enclosing
 * block that catches plain {@code return} ({@code %block} or {@code (block nil ...)};
 * named blocks in between are skipped -- on the interpreter the plain signal passes
 * through them the same way). The optional value (default nil) is stored into the block's
 * value slot and an unconditional {@code goto} jumps to the block's exit label, which
 * {@link JvmBlockCompiler} binds. The same emit sequence, generalized to a target
 * anywhere in the block stack, is shared with {@link JvmReturnFromCompiler}.
 *
 * <p>
 * When the jump escapes one or more protected regions (the scope was entered inside the
 * target block) -- an {@code unwind-protect}, or a special {@code let} whose cleanups
 * restore its dynamic bindings ({@link JvmLetCompiler}) -- their cleanup forms are
 * compiled inline before the {@code goto}, innermost first -- the CL unwinding order,
 * bindings and cleanups interleaved as nested. The inlined sequence is recorded as a hole
 * in each escaped scope from that scope's own cleanup onward: once a scope's cleanup has
 * started, a throw from the remaining sequence must no longer re-enter that scope's
 * handler (its cleanup already ran), while a throw from an inner scope's cleanup still
 * lands in the outer scopes' handlers.
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
		emitExit(parts.size() > 1 ? parts.get(1) : null, ctx, className, targetDepth,
				isTailExit(cons, ctx, targetDepth));
	}

	/**
	 * {@return whether the exit {@code cons} to the block at {@code targetDepth} hands
	 * its value form on as the method's result} It carries the tail mark itself, or it
	 * sits on the exit chain ({@code Ctx.exitMark}: nothing between it and its blocks
	 * opens a dynamic extent) and its target block is in tail position -- the value of a
	 * {@code return} out of a loop body whose block ends the method.
	 * @param cons the exit form
	 * @param ctx the method being emitted
	 * @param targetDepth the target block's 1-based depth
	 */
	static boolean isTailExit(LispCons cons, JvmLispCompiler.Ctx ctx, int targetDepth) {
		return ctx.tailMark == cons || ctx.exitMark == cons && targetAt(ctx, targetDepth).tail()
				&& ctx.unwindScopes.isEmpty() && ctx.spillScopes.isEmpty();
	}

	/**
	 * {@return whether {@code cons}, the form being compiled, is on the exit chain} It
	 * carries the tail mark or the exit mark, so an emitter that opens no dynamic extent
	 * lays the exit mark on each sub-form it compiles in place ({@code Ctx.exitMark}).
	 * @param cons the form being compiled
	 * @param ctx the method being emitted
	 */
	static boolean onExitChain(LispCons cons, JvmLispCompiler.Ctx ctx) {
		return ctx.tailMark == cons || ctx.exitMark == cons;
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
	 * {@code goto} jumps to the target's exit label (bound by {@link JvmBlockCompiler}).
	 * @param tail whether the exit form carries the tail mark: every form between it and
	 * the method's result -- its target block among them -- hands its value on unchanged
	 * and opens no dynamic extent, so the value form is the method's tail too
	 * ({@link JvmSelfTailCall}, {@link JvmTailBounce})
	 */
	static void emitExit(@Nullable LispVal valueForm, JvmLispCompiler.Ctx ctx, String className, int targetDepth,
			boolean tail) {
		JvmLispCompiler.BlockTarget target = targetAt(ctx, targetDepth);
		if (valueForm != null) {
			LispVal savedMark = ctx.tailMark;
			ctx.tailMark = tail ? valueForm : null;
			JvmExprCompiler.compileExpr(valueForm, ctx, className);
			ctx.tailMark = savedMark;
		}
		else {
			ctx.body.aconst_null();
		}
		ctx.body.astore(target.rvSlot());
		compileEscapedCleanups(ctx, className, targetDepth);
		emitStackUnwind(ctx, target, targetDepth);
		ctx.body.goto_(target.exit());
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
		MethodCode.Label[] holeStarts = new MethodCode.Label[escaped.size()];
		for (int i = 0; i < escaped.size(); i++) {
			holeStarts[i] = ctx.body.newBoundLabel();
			JvmUnwindProtectCompiler.compileCleanups(escaped.get(i).cleanupForms, ctx, className);
		}
		MethodCode.Label sequenceEnd = ctx.body.newBoundLabel();
		for (int i = 0; i < escaped.size(); i++) {
			escaped.get(i).holes.add(new JvmLispCompiler.Hole(holeStarts[i], sequenceEnd));
		}
	}

}
