package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

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
 * {@code return} could escape); {@code block $u (result exnref)} is the exception landing
 * pad of a {@code try_table (catch_all_ref $u)} over the protected form. On normal
 * completion the value is stashed in a local, the cleanups run and a {@code br $done}
 * skips both landing pads. On an exception the landing runs the cleanups with the caught
 * {@code exnref} beneath the stack and rethrows it with {@code throw_ref} (a cleanup that
 * itself throws replaces the pending unwind -- the CL "newer exit wins"). On an escaped
 * {@code return} the trampoline landing receives the return value, runs the cleanups and
 * branches onward to the next escaped scope's trampoline (or the target {@code %block}),
 * innermost first -- the CL unwinding order. Because the trampoline is lexically outside
 * the try_table, a throw from an inlined cleanup cannot re-enter this scope's own
 * handler: the structural equivalent of the JVM {@code holes} mechanism.
 *
 * <p>
 * Documented lite limit kept for JVM parity: a special-variable {@code let} binding is
 * NOT restored when an unwind crosses it ({@code .kb/dynamic-special-variables.md}).
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
		int resultSlot = ctx.allocTemp();
		// block $done (result (ref null eq)) -- the unwind-protect value.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int doneDepth = ctx.wasmCtrlDepth;
		// block $tramp (result (ref null eq)) -- the return-exit trampoline, only when
		// an enclosing %block exists (otherwise no return can escape this scope).
		boolean needTrampoline = !ctx.blockMarkers.isEmpty();
		int trampolineDepth = -1;
		int continueDepth = -1;
		if (needTrampoline) {
			continueDepth = continueTargetDepth(ctx);
			ctx.writer.write(Instruction.BLOCK);
			ctx.writer.write(Type.REFNULL.code());
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.wasmCtrlDepth++;
			trampolineDepth = ctx.wasmCtrlDepth;
		}
		// block $u (result exnref) -- the exception landing pad.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.EXNREF.code());
		ctx.wasmCtrlDepth++;
		int landingDepth = ctx.wasmCtrlDepth;
		// try_table (result (ref null eq)) (catch_all_ref $u). Catch labels are
		// resolved without the try_table's own label, so label 0 is block $u here.
		ctx.writer.write(Instruction.TRY_TABLE);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.CATCH_ALL_REF);
		ctx.writer.writeUnsignedLeb128(ctx.wasmCtrlDepth - landingDepth);
		ctx.wasmCtrlDepth++;
		ctx.unwindScopes.push(new WasmLispCompiler.UnwindScope(cleanups, ctx.blockMarkers.size(), trampolineDepth));
		WasmExprCompiler.compileExpr(protectedForm, ctx);
		ctx.unwindScopes.pop();
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // try_table
		// Normal exit: stash the value, run the cleanups, skip the landing pads.
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(resultSlot);
		compileCleanups(cleanups, ctx);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(resultSlot);
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $u
		// Exception landing: the caught exnref is on the stack; run the cleanups over
		// it and rethrow. A throw from a cleanup propagates outward instead (it cannot
		// re-enter this scope's try_table, which is already exited).
		compileCleanups(cleanups, ctx);
		ctx.writer.write(Instruction.THROW_REF);
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
	 * block), otherwise the target {@code %block} itself. Must be called BEFORE this
	 * scope pushes itself.
	 * @param ctx the compilation context
	 * @return the continuation label's depth marker
	 */
	static int continueTargetDepth(WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.UnwindScope enclosing = ctx.unwindScopes.peek();
		if (enclosing != null && enclosing.blockDepth() >= ctx.blockMarkers.size()) {
			return enclosing.trampolineDepth();
		}
		return java.util.Objects.requireNonNull(ctx.blockMarkers.peek());
	}

	/**
	 * Compiles the cleanup forms as statements, dropping each value (a cleanup's value is
	 * discarded; the whole form yields the protected form's value). Safe to run with
	 * extra values (the exnref / the return value) beneath on the operand stack.
	 */
	static void compileCleanups(List<LispVal> cleanups, WasmLispCompiler.Ctx ctx) {
		for (LispVal form : cleanups) {
			WasmExprCompiler.compileExpr(form, ctx);
			ctx.writer.write(Instruction.DROP);
		}
	}

}
