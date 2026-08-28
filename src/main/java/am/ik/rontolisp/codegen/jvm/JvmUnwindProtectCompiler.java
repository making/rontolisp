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
		List<LispVal> cleanups = parts.subList(2, parts.size());
		int savedNextLocal = ctx.nextLocal;
		int resultSlot = ctx.allocTemp();
		int excSlot = ctx.allocTemp();
		JvmLispCompiler.UnwindScope scope = new JvmLispCompiler.UnwindScope(cleanups, ctx.blockTargets.size());
		ctx.unwindScopes.push(scope);
		int start = ctx.code.size();
		JvmExprCompiler.compileExpr(protectedForm, ctx, className);
		int end = ctx.code.size();
		ctx.unwindScopes.pop();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);
		// Normal exit: run the cleanups, jump over the handler.
		compileCleanups(cleanups, ctx, className);
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Error unwind: store the throwable (the handler's operand stack holds only it),
		// run the cleanups, rethrow. A cleanup that itself throws replaces the pending
		// unwind (CL semantics: the newer exit wins). This path never merges back into
		// the normal one -- it ends in a throw -- so operands live across the protected
		// region survive on the normal path and need no spill.
		int handler = ctx.code.size();
		ctx.stack.enterHandler();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(excSlot);
		compileCleanups(cleanups, ctx, className);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.ATHROW);
		JvmEmitHelper.patchBranch(ctx, gotoPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(resultSlot);
		addExceptionEntries(ctx, scope, start, end, handler);
		ctx.nextLocal = savedNextLocal;
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
		am.ik.jvm.ConstantPool.FieldrefConstant spillField = internalOnly(cleanups) ? null
				: ctx.globalFields.get(LispNames.MV_SPILL);
		int savedNextLocal = ctx.nextLocal;
		int spillSlot = -1;
		if (spillField != null) {
			spillSlot = ctx.allocTemp();
			ctx.emit(Opcode.GETSTATIC);
			ctx.emitU2(spillField.index());
			ctx.emit(Opcode.ASTORE);
			ctx.emit(spillSlot);
		}
		for (LispVal form : cleanups) {
			JvmExprCompiler.compileForEffect(form, ctx, className);
		}
		if (spillField != null) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(spillSlot);
			ctx.emit(Opcode.PUTSTATIC);
			ctx.emitU2(spillField.index());
			ctx.nextLocal = savedNextLocal;
		}
	}

	/**
	 * Whether the cleanup sequence is the compiler's OWN bookkeeping rather than a user's
	 * cleanup forms -- the {@code (%hc-depth-dec)} a {@code handler-case} scope carries,
	 * which is an i32 counter adjustment and can never reach the {@code %mv-spill}
	 * channel. Such a scope skips the save/restore above, so a program's handler-case
	 * escape paths stay byte-identical to a build compiled before it existed.
	 * @param cleanups the cleanup forms
	 * @return whether every form is compiler-internal
	 */
	private static boolean internalOnly(List<LispVal> cleanups) {
		for (LispVal form : cleanups) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !LispNames.HC_DEPTH_DEC_INTERNAL.equals(head.name())) {
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
