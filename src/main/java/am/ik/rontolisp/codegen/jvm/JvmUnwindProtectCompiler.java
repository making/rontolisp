package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles {@code (unwind-protect protected cleanup...)}: the cleanup forms run on every
 * exit from the protected form -- normal return, an error unwind and a {@code return}/
 * {@code return-from} non-local exit.
 *
 * <p>
 * Layout: the protected form's value is stored into a result local, the cleanup forms run
 * and a {@code goto} skips the handler; a catch-any exception-table entry covering the
 * protected region routes an error unwind to a handler that stores the throwable, runs
 * the cleanup forms and rethrows. Class version 50 verifies exception handlers without a
 * StackMapTable (the type-inference verifier computes handler frames itself), so no
 * stack-map bookkeeping is needed.
 *
 * <p>
 * The {@code return} exit path is a plain {@code goto} to the enclosing {@code %block}'s
 * exit (see {@link JvmReturnCompiler}), which would skip both copies above --
 * {@code JvmReturnCompiler} therefore compiles the cleanup forms of every escaped scope
 * inline before its jump, and records those ranges as holes that this scope's
 * exception-table entries exclude (a throw from an inlined cleanup must not re-enter the
 * scope's own handler and run the cleanup twice; it still lands in the handlers of outer
 * scopes, which is the CL unwinding order).
 *
 * <p>
 * The region is also how a special {@code let} restores its dynamic bindings on every
 * exit ({@link #compileRegion}, called by {@link JvmLetCompiler} with
 * {@code %dyn-restore} cleanups): the restore rides the same handler, the same
 * {@code return}/{@code go} inlining and the same holes, so a binding and a cleanup
 * nested either way around unwind in CL's innermost-first order.
 */
final class JvmUnwindProtectCompiler {

	private JvmUnwindProtectCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.UNWIND_PROTECT + " expects a protected form");
		}
		LispVal protectedForm = parts.get(1);
		compileRegion(() -> JvmExprCompiler.compileExpr(protectedForm, ctx, className), parts.subList(2, parts.size()),
				ctx, className, true);
	}

	/**
	 * Compiles a protected region: {@code body} emits the protected code, and the cleanup
	 * forms run on every exit from it -- the layout described on the class. The same
	 * region serves a special {@code let} ({@link JvmLetCompiler}), whose cleanups are
	 * the {@code %dyn-restore} forms restoring its dynamic bindings, so one mechanism
	 * covers every exit channel for both.
	 * @param body emits the protected code, leaving one value on the operand stack when
	 * {@code hasValue}, nothing otherwise
	 * @param cleanups the cleanup forms, compiled for effect on each exit path
	 * @param ctx the compilation context
	 * @param className the class being generated
	 * @param hasValue whether the protected code produces the region's value
	 */
	static void compileRegion(Runnable body, List<LispVal> cleanups, JvmLispCompiler.Ctx ctx, String className,
			boolean hasValue) {
		Region region = Region.open(cleanups, ctx, className, hasValue);
		body.run();
		region.close();
	}

	/**
	 * A protected region under construction: {@link #open} allocates the slots, pushes
	 * the scope and marks the start; the caller emits the protected code (possibly as
	 * items on the tail spine, {@link JvmBodyOutliner}); {@link #close} emits the normal
	 * exit, the handler and the exception-table entries. The two halves exist so a
	 * special {@code let}'s body can keep joining the spine: the closing half runs in the
	 * method that opened the region, after the last body item.
	 */
	static final class Region {

		private final List<LispVal> cleanups;

		private final JvmLispCompiler.Ctx ctx;

		private final String className;

		private final boolean hasValue;

		private final int savedNextLocal;

		private final int resultSlot;

		private final int excSlot;

		private final JvmLispCompiler.UnwindScope scope;

		private final int start;

		private Region(List<LispVal> cleanups, JvmLispCompiler.Ctx ctx, String className, boolean hasValue) {
			this.cleanups = cleanups;
			this.ctx = ctx;
			this.className = className;
			this.hasValue = hasValue;
			this.savedNextLocal = ctx.nextLocal;
			// A special let's restores are stack-neutral (one getstatic / aload /
			// ThreadLocal.set triple per binding), so the body's value stays on the
			// operand stack under them and the result slot is spent only on user
			// cleanups, whose forms may push anything.
			this.resultSlot = hasValue && !dynRestoresOnly(cleanups) ? ctx.allocTemp() : -1;
			this.excSlot = ctx.allocTemp();
			this.scope = new JvmLispCompiler.UnwindScope(cleanups, ctx.blockTargets.size());
			ctx.unwindScopes.push(this.scope);
			this.start = ctx.code.size();
		}

		static Region open(List<LispVal> cleanups, JvmLispCompiler.Ctx ctx, String className, boolean hasValue) {
			return new Region(cleanups, ctx, className, hasValue);
		}

		void close() {
			JvmLispCompiler.Ctx ctx = this.ctx;
			int end = ctx.code.size();
			ctx.unwindScopes.pop();
			if (this.resultSlot >= 0) {
				ctx.emit(Opcode.ASTORE);
				ctx.emit(this.resultSlot);
			}
			// Normal exit: run the cleanups, jump over the handler.
			compileCleanups(this.cleanups, ctx, this.className);
			int gotoPos = ctx.code.size();
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			// Error unwind: store the throwable (the handler's operand stack holds only
			// it), run the cleanups, rethrow. A cleanup that itself throws replaces the
			// pending unwind (CL semantics: the newer exit wins). This path never merges
			// back into the normal one -- it ends in a throw -- so operands live across
			// the protected region survive on the normal path and need no spill.
			int handler = ctx.code.size();
			ctx.stack.enterHandler();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(this.excSlot);
			compileCleanups(this.cleanups, ctx, this.className);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(this.excSlot);
			ctx.emit(Opcode.ATHROW);
			JvmEmitHelper.patchBranch(ctx, gotoPos, ctx.code.size());
			if (this.resultSlot >= 0) {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(this.resultSlot);
			}
			addExceptionEntries(ctx, this.scope, this.start, end, handler);
			ctx.nextLocal = this.savedNextLocal;
		}

	}

	/**
	 * Whether every cleanup is a special {@code let}'s {@code %dyn-restore} -- the one
	 * cleanup kind emitted stack-neutrally, so the region's value can stay on the operand
	 * stack while they run.
	 * @param cleanups the cleanup forms
	 * @return whether all are binding restores
	 */
	static boolean dynRestoresOnly(List<LispVal> cleanups) {
		if (cleanups.isEmpty()) {
			return false;
		}
		for (LispVal form : cleanups) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !LispNames.DYN_RESTORE_INTERNAL.equals(head.name())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Compiles the cleanup forms as statements, popping each value (a cleanup's value is
	 * discarded; the whole form yields the protected form's value).
	 *
	 * <p>
	 * A cleanup's value count is discarded with it: the {@code %mv-spill} channel the
	 * protected form published its SECONDARY values on is saved into a local across the
	 * whole cleanup sequence and written back after it, so
	 * {@code (unwind-protect (values 1 2 3) (release))} answers all three values however
	 * many {@code release} returns ({@code .kb/multiple-values.md}). Emitted only when
	 * the program has the spill global at all -- a program using no multiple-value
	 * operator never writes the channel, and stays byte-identical to one compiled before
	 * this. Every exit path routes through here (normal completion, the error-unwind
	 * handler and the copies {@link JvmReturnCompiler}/{@link JvmGoCompiler} inline at an
	 * escape), so the save covers the in-flight values of a {@code return-from} inside
	 * the protected form too.
	 */
	static void compileCleanups(List<LispVal> cleanups, JvmLispCompiler.Ctx ctx, String className) {
		if (cleanups.isEmpty()) {
			return;
		}
		JvmMvChannel spill = internalOnly(cleanups) ? null : ctx.mvChannel;
		int savedNextLocal = ctx.nextLocal;
		int spillSlot = -1;
		if (spill != null) {
			spillSlot = ctx.allocTemp();
			spill.emitLoad(ctx);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(spillSlot);
		}
		for (LispVal form : cleanups) {
			JvmExprCompiler.compileForEffect(form, ctx, className);
		}
		if (spill != null) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(spillSlot);
			spill.emitStore(ctx);
			ctx.nextLocal = savedNextLocal;
		}
	}

	/**
	 * Whether the cleanup sequence is the compiler's OWN bookkeeping rather than a user's
	 * cleanup forms -- the {@code (%hc-depth-dec)} a {@code handler-case} scope carries
	 * (an i32 counter adjustment) or the {@code (%dyn-restore ...)}s of a special
	 * {@code let}'s scope (a ThreadLocal write), neither of which can reach the
	 * {@code %mv-spill} channel. Such a scope skips the save/restore above, so a
	 * program's handler-case escape paths stay byte-identical to a build compiled before
	 * it existed, and a special {@code let} pays nothing for the channel.
	 * @param cleanups the cleanup forms
	 * @return whether every form is compiler-internal
	 */
	static boolean internalOnly(List<LispVal> cleanups) {
		for (LispVal form : cleanups) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !(LispNames.HC_DEPTH_DEC_INTERNAL.equals(head.name())
							|| LispNames.DYN_RESTORE_INTERNAL.equals(head.name()))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Appends the scope's catch-any exception-table entries: {@code [start, end)} minus
	 * the recorded holes (the cleanup sequences inlined at {@code return} escape sites,
	 * which lie inside the protected region but must not be covered by this scope's own
	 * handler). The holes are recorded in code order, so a single left-to-right sweep
	 * suffices.
	 */
	private static void addExceptionEntries(JvmLispCompiler.Ctx ctx, JvmLispCompiler.UnwindScope scope, int start,
			int end, int handler) {
		int cur = start;
		for (int[] hole : scope.holes) {
			if (hole[0] > cur) {
				ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(cur, hole[0], handler, 0));
			}
			cur = Math.max(cur, hole[1]);
		}
		if (cur < end) {
			ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(cur, end, handler, 0));
		}
	}

}
