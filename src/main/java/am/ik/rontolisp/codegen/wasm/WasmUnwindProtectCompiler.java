package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles {@code (unwind-protect protected cleanup...)} on the wasm-GC backend (EH mode
 * only): the cleanup forms run on every exit from the protected form -- normal
 * completion, a {@code $lisp-cond} unwind (or any other exception passing through, e.g. a
 * {@code %signal-cond} rethrow) and a {@code return} non-local exit.
 *
 * <p>
 * Layout, outermost first: {@code block $done (result (ref null eq))} holds the whole
 * form's value; an optional {@code block $tramp (result (ref null eq))} is the
 * return-exit trampoline (emitted only when an enclosing {@code %block} exists, i.e. a
 * {@code return} could escape); then every live local is pushed onto the operand stack
 * ({@link WasmLandingPad}); {@code block $u (result (ref null eq))} is the
 * {@code $lisp-cond} landing pad of a {@code try_table (catch $lisp-cond $u)} over the
 * protected form, joined -- in a program that lowers cross-lambda exits -- by a
 * {@code block $bx} landing for the block-exit tag, both funnelling into a
 * {@code block $rethrow (result i32)} whose value names the tag to rethrow on. On normal
 * completion the value is stashed in a local, the cleanups run and a {@code br $done}
 * skips the landing pads. On an exception the landing stashes the payload, pops the kept
 * locals back, runs the cleanups and rethrows the payload on its tag (a cleanup that
 * itself throws replaces the pending unwind -- the CL "newer exit wins"). On an escaped
 * {@code return} the trampoline landing receives the return value, runs the cleanups and
 * branches onward to the next escaped scope's trampoline (or the target {@code %block}),
 * innermost first -- the CL unwinding order. Because the trampoline is lexically outside
 * the try_table, a throw from an inlined cleanup cannot re-enter this scope's own
 * handler: the structural equivalent of the JVM {@code holes} mechanism.
 *
 * <p>
 * The region is also how a special {@code let} restores its dynamic bindings on every
 * exit ({@link #compileRegion}, called by {@link WasmLetCompiler} with
 * {@code %dyn-restore} cleanups): the restore rides the same landing pads and the same
 * trampoline cascade, so a binding and a cleanup nested either way around unwind in CL's
 * innermost-first order ({@code .kb/dynamic-special-variables.md}).
 */
final class WasmUnwindProtectCompiler {

	private WasmUnwindProtectCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.UNWIND_PROTECT + " expects a protected form");
		}
		LispVal protectedForm = parts.get(1);
		List<LispVal> cleanups = parts.subList(2, parts.size());
		if (ctx.asyncResume != null) {
			for (LispVal cleanup : cleanups) {
				if (WasmAwaitAnalysis.countAwaits(cleanup) > 0) {
					// the user-approved v1 restriction: a suspension skips cleanups (and
					// re-arms them on re-entry), so a cleanup itself cannot suspend
					throw new UnsupportedOperationException(
							"rontolisp:await inside an " + LispNames.UNWIND_PROTECT + " cleanup form is not supported");
				}
			}
		}
		// State-machine mode: the protected form is a spine child; a suspension inside
		// it returns straight out (skipping the cleanups) and the resume re-enters this
		// try_table from the top, re-arming them.
		compileRegion(() -> WasmAsyncEmit.spine(protectedForm, ctx), cleanups, ctx, true, null);
	}

	/**
	 * Compiles a protected region: {@code body} emits the protected code (leaving one
	 * {@code (ref null eq)} on the stack), and the cleanup forms run on every exit from
	 * it -- the layout described on the class. The same region serves a special
	 * {@code let} ({@link WasmLetCompiler}), whose cleanups are the {@code %dyn-restore}
	 * forms restoring its dynamic bindings, so one mechanism covers every exit channel
	 * for both.
	 * @param body emits the protected code
	 * @param cleanups the cleanup forms, compiled for effect on each exit path
	 * @param ctx the compilation context
	 * @param catching whether the region catches (EH mode: the {@code try_table} and its
	 * landing pads are emitted). Without it only the {@code return} trampoline remains --
	 * outside EH mode an error is a trap and no throw can cross the region, and with no
	 * enclosing plain-{@code return} boundary either, the body and its cleanups are
	 * emitted bare
	 * @param keptSlots the locals the landing pads refresh
	 * ({@code .kb/wasm-landing-pad-refresh.md}), or null for every local declared so far
	 * -- a region whose pads read nothing but their own save slots keeps only those
	 */
	static void compileRegion(Runnable body, List<LispVal> cleanups, WasmLispCompiler.Ctx ctx, boolean catching,
			int @Nullable [] keptSlots) {
		// block $tramp (result (ref null eq)) -- the return-exit trampoline, only when
		// an enclosing plain-return boundary exists (otherwise no return can escape
		// this scope; a named return-from inlines its escaped cleanups instead).
		boolean needTrampoline = WasmReturnCompiler.findPlainTarget(ctx) != null;
		if (!catching && !needTrampoline) {
			// Nothing can leave the body but a lexical return-from/go, and those inline
			// the escaped scopes' cleanups at the exit site.
			ctx.unwindScopes.push(new WasmLispCompiler.UnwindScope(cleanups, ctx.blockMarkers.size(), -1));
			body.run();
			ctx.unwindScopes.pop();
			compileCleanups(cleanups, ctx);
			return;
		}
		// A special let's restores are stack-neutral (local.get; global.set per binding),
		// so the body's value stays on the operand stack under them and the result slot
		// is spent only on user cleanups, whose forms may push anything.
		int resultSlot = dynRestoresOnly(cleanups) ? -1 : ctx.allocTemp();
		// block $done (result (ref null eq)) -- the region's value.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int doneDepth = ctx.wasmCtrlDepth;
		int trampolineDepth = -1;
		int continueDepth = -1;
		if (needTrampoline) {
			continueDepth = continueTargetDepth(ctx);
			ctx.writer.write(Instruction.BLOCK);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.wasmCtrlDepth++;
			trampolineDepth = ctx.wasmCtrlDepth;
		}
		// The landing-pad discipline (WasmLandingPad): every live local rides the
		// operand stack across the protected body, pushed inside the innermost block
		// enclosing each landing pad (a catch branch unwinds the stack to its target
		// block's entry height, so values pushed INSIDE the landing block are gone, and
		// values pushed outside an enclosing block are unreachable from within it), and
		// the pads pop them back before reading anything.
		//
		// One landing block per tag the program can throw: $lisp-cond always, and the
		// block-exit tag when a cross-lambda exit is lowered. Both pads refresh the
		// locals and funnel into one copy of the cleanups through block $rethrow, whose
		// i32 result says which tag to rethrow on -- the payload (an eqref) is what
		// crosses, so no exnref needs stashing; rethrowing the payload on its own tag
		// is what every catcher observes anyway.
		boolean twoTags = catching && ctx.blockExitTag;
		int rethrowDepth = -1;
		int blockExitDepth = -1;
		int[] kept = keptSlots != null ? keptSlots : WasmLandingPad.allSlots(ctx);
		// Each landing block's own push, popped by that block's pad.
		WasmLandingPad.@Nullable Kept blockExitKept = null;
		WasmLandingPad.@Nullable Kept condKept = null;
		int payloadSlot = -1;
		int landingDepth = -1;
		if (catching) {
			if (twoTags) {
				ctx.writer.write(Instruction.BLOCK);
				ctx.writer.write(Type.I32);
				ctx.wasmCtrlDepth++;
				rethrowDepth = ctx.wasmCtrlDepth;
				blockExitKept = WasmLandingPad.keepSlotsAlive(ctx, kept);
				ctx.writer.write(Instruction.BLOCK);
				ctx.writer.writeRefType(true, Type.EQ.code());
				ctx.wasmCtrlDepth++;
				blockExitDepth = ctx.wasmCtrlDepth;
			}
			condKept = WasmLandingPad.keepSlotsAlive(ctx, kept);
			// Allocated AFTER the pushes: a slot among the kept ones would be popped
			// back over the payload just stashed in it.
			payloadSlot = ctx.allocTemp();
			// block $u (result (ref null eq)) -- the $lisp-cond landing pad.
			ctx.writer.write(Instruction.BLOCK);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.wasmCtrlDepth++;
			landingDepth = ctx.wasmCtrlDepth;
			// try_table (result (ref null eq)) (catch $lisp-cond $u) [(catch $block-exit
			// $bx)]. Catch labels are resolved without the try_table's own label, so
			// label 0 is block $u here and $bx (one level out) is label 1.
			ctx.writer.write(Instruction.TRY_TABLE);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.writer.writeUnsignedLeb128(twoTags ? 2 : 1);
			ctx.writer.write(Instruction.CATCH);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
			ctx.writer.writeUnsignedLeb128(ctx.wasmCtrlDepth - landingDepth);
			if (twoTags) {
				ctx.writer.write(Instruction.CATCH);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_BLOCK_EXIT);
				ctx.writer.writeUnsignedLeb128(ctx.wasmCtrlDepth - blockExitDepth);
			}
			ctx.wasmCtrlDepth++;
		}
		ctx.unwindScopes.push(new WasmLispCompiler.UnwindScope(cleanups, ctx.blockMarkers.size(), trampolineDepth));
		body.run();
		ctx.unwindScopes.pop();
		if (catching) {
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END); // try_table
		}
		// Normal exit: stash the value, run the cleanups, skip the landing pads (the br
		// discards the kept locals).
		if (resultSlot >= 0) {
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultSlot);
		}
		compileCleanups(cleanups, ctx);
		if (resultSlot >= 0) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultSlot);
		}
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
		if (catching) {
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END); // block $u -- the payload is on the stack
			// $lisp-cond landing: stash the payload, refresh the locals, then the
			// cleanups and the rethrow. A throw from a cleanup propagates outward
			// instead (it cannot re-enter this scope's try_table, which is already
			// exited).
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(payloadSlot);
			WasmLandingPad.refresh(ctx, java.util.Objects.requireNonNull(condKept));
			WasmUncaughtLocations.notePad(ctx, payloadSlot);
			if (twoTags) {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - rethrowDepth);
				ctx.wasmCtrlDepth--;
				ctx.writer.write(Instruction.END); // block $bx -- the payload is on the
													// stack
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(payloadSlot);
				WasmLandingPad.refresh(ctx, java.util.Objects.requireNonNull(blockExitKept));
				WasmUncaughtLocations.resyncPad(ctx);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.wasmCtrlDepth--;
				ctx.writer.write(Instruction.END); // block $rethrow -- the tag kind is on
													// the stack
				compileCleanups(cleanups, ctx);
				ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
				ctx.wasmCtrlDepth++;
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(payloadSlot);
				ctx.writer.write(Instruction.THROW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_BLOCK_EXIT);
				ctx.writer.write(Instruction.ELSE);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(payloadSlot);
				ctx.writer.write(Instruction.THROW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
				ctx.wasmCtrlDepth--;
				ctx.writer.write(Instruction.END); // if
				ctx.writer.write(Instruction.UNREACHABLE);
			}
			else {
				compileCleanups(cleanups, ctx);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(payloadSlot);
				ctx.writer.write(Instruction.THROW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
			}
		}
		if (needTrampoline) {
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END); // block $tramp
			// Return landing: the escaped return's value is on the stack; run the
			// cleanups and continue the exit toward the target %block, through the
			// next escaped scope's trampoline when one encloses this one.
			compileCleanups(cleanups, ctx);
			ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - continueDepth);
		}
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $done
	}

	/**
	 * Computes the {@code wasmCtrlDepth} marker the exit trampoline continues to once its
	 * own cleanups have run: the enclosing unwind scope's trampoline when that scope is
	 * also escaped by the same {@code return} (it was entered inside the return's target
	 * block), otherwise the return's target block itself -- the nearest enclosing
	 * plain-{@code return} boundary, skipping named blocks the plain signal passes
	 * through. Must be called BEFORE this scope pushes itself, and only when a
	 * plain-return boundary encloses (the {@code needTrampoline} guard).
	 * @param ctx the compilation context
	 * @return the continuation label's depth marker
	 */
	static int continueTargetDepth(WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.BlockMarker plain = java.util.Objects.requireNonNull(WasmReturnCompiler.findPlainTarget(ctx));
		WasmLispCompiler.UnwindScope enclosing = ctx.unwindScopes.peek();
		if (enclosing != null && enclosing.blockDepth() >= WasmReturnCompiler.blockStackDepthOf(ctx, plain)) {
			return enclosing.trampolineDepth();
		}
		return plain.depth();
	}

	/**
	 * Compiles the cleanup forms as statements, dropping each value (a cleanup's value is
	 * discarded; the whole form yields the protected form's value). Safe to run with
	 * extra values (the exnref / the return value) beneath on the operand stack.
	 *
	 * <p>
	 * A cleanup's value count is discarded with it: the {@code %mv-spill} channel the
	 * protected form published its SECONDARY values on is saved into a local across the
	 * whole cleanup sequence and written back after it, so
	 * {@code (unwind-protect (values 1 2 3) (release))} answers all three values however
	 * many {@code release} returns ({@code .kb/multiple-values.md}). Emitted only when
	 * the program has the spill global at all -- a program using no multiple-value
	 * operator never writes the channel, and stays byte-identical to one compiled before
	 * this. Every exit path routes through here (normal completion, the exception landing
	 * and the return trampoline, plus the copies {@link WasmTagbodyCompiler} /
	 * {@link WasmReturnFromCompiler} inline at an escape), so the save covers the
	 * in-flight values of a {@code return-from} inside the protected form too. A local,
	 * not the operand stack: the landing pads run with an exnref / the return value
	 * beneath them.
	 */
	static void compileCleanups(List<LispVal> cleanups, WasmLispCompiler.Ctx ctx) {
		if (cleanups.isEmpty()) {
			return;
		}
		Integer spillGlobal = internalOnly(cleanups) ? null : ctx.globalIndices.get(LispNames.MV_SPILL);
		int spillSlot = -1;
		if (spillGlobal != null) {
			spillSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(spillGlobal);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(spillSlot);
		}
		for (LispVal form : cleanups) {
			// A special let's binding restore is always a statement: the restore alone,
			// no nil to drop.
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DYN_RESTORE_INTERNAL.equals(head.name())) {
				WasmLetCompiler.emitRestoreForEffect(cons, ctx);
				continue;
			}
			WasmExprCompiler.compileExpr(form, ctx);
			ctx.writer.write(Instruction.DROP);
		}
		if (spillGlobal != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(spillSlot);
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(spillGlobal);
		}
	}

	/**
	 * Whether every cleanup is a special {@code let}'s {@code %dyn-restore} -- the one
	 * cleanup kind emitted stack-neutrally, so the region's value can stay on the operand
	 * stack while they run.
	 * @param cleanups the cleanup forms
	 * @return whether all are binding restores
	 */
	private static boolean dynRestoresOnly(List<LispVal> cleanups) {
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
	 * Whether the cleanup sequence is the compiler's OWN bookkeeping rather than a user's
	 * cleanup forms -- the {@code (%hc-depth-dec)} a {@code handler-case} scope carries
	 * (an i32 counter adjustment) or the {@code (%dyn-restore ...)}s of a special
	 * {@code let}'s scope (a global or task-slot write), neither of which can reach the
	 * {@code %mv-spill} channel. Such a scope skips the save/restore above, so a
	 * program's handler-case escape paths stay byte-identical to a build compiled before
	 * it existed, and a special {@code let} pays nothing for the channel.
	 * @param cleanups the cleanup forms
	 * @return whether every form is compiler-internal
	 */
	private static boolean internalOnly(List<LispVal> cleanups) {
		for (LispVal form : cleanups) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !(LispNames.HC_DEPTH_DEC_INTERNAL.equals(head.name())
							|| LispNames.DYN_RESTORE_INTERNAL.equals(head.name()))) {
				return false;
			}
		}
		return true;
	}

}
