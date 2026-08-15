package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code return} special form: a non-local exit from the nearest enclosing
 * block that catches plain {@code return} ({@code %block} or {@code (block nil ...)};
 * named blocks in between are skipped -- on the interpreter the plain signal passes
 * through them the same way). The optional value (default nil) is left on the stack and a
 * {@code br} branches out to the block, which has a matching {@code (ref null eq)} result
 * type. The branch depth is the number of control structures between this point and the
 * target block, derived from {@link WasmLispCompiler.Ctx#wasmCtrlDepth}.
 *
 * <p>
 * EH mode: when the branch would escape an {@code unwind-protect} / {@code handler-case}
 * protected region (the innermost unwind scope was entered inside the target block), it
 * branches to that scope's exit trampoline instead, which runs the scope's cleanups and
 * cascades outward toward the block (see {@code WasmUnwindProtectCompiler}). {@code
 * return} is only legal at empty operand stack, so the value-carrying {@code br} is
 * always safe.
 */
final class WasmReturnCompiler {

	private WasmReturnCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.BlockMarker marker = findPlainTarget(ctx);
		if (marker == null) {
			throw new IllegalStateException("Cannot compile return outside of a loop block");
		}
		int targetDepth = blockStackDepthOf(ctx, marker);
		List<LispVal> parts = cons.toList();
		if (parts.size() > 1) {
			// state-machine mode: the return value is a spine child (empty stack)
			WasmAsyncEmit.spine(parts.get(1), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		WasmLispCompiler.UnwindScope escaped = ctx.unwindScopes.peek();
		if (escaped != null && escaped.blockDepth() >= targetDepth) {
			// The exit crosses the innermost protected region: run its cleanups through
			// the trampoline; the trampolines cascade to the block, innermost first.
			// (Special-binding restores are skipped on this path -- the documented
			// unwind limitation.)
			ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - escaped.trampolineDepth());
			return;
		}
		// Restore every special-variable dynamic binding this exit escapes, innermost
		// first (see WasmReturnFromCompiler).
		for (int[] bind : ctx.specialBindScopes) {
			if (bind[2] < targetDepth) {
				break;
			}
			WasmDynVars.emitRestore(ctx, bind);
		}
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - marker.depth());
	}

	/**
	 * The nearest enclosing block marker that catches plain {@code return}, or null when
	 * none encloses.
	 */
	static WasmLispCompiler.@Nullable BlockMarker findPlainTarget(WasmLispCompiler.Ctx ctx) {
		for (WasmLispCompiler.BlockMarker marker : ctx.blockMarkers) {
			if (marker.catchesPlain()) {
				return marker;
			}
		}
		return null;
	}

	/**
	 * The 1-based depth (from the bottom of the block stack) of the given marker: an
	 * unwind scope was entered inside the marker's block exactly when its
	 * {@code blockDepth >=} this value.
	 */
	static int blockStackDepthOf(WasmLispCompiler.Ctx ctx, WasmLispCompiler.BlockMarker target) {
		int idxFromTop = 0;
		for (WasmLispCompiler.BlockMarker marker : ctx.blockMarkers) {
			if (marker == target) {
				return ctx.blockMarkers.size() - idxFromTop;
			}
			idxFromTop++;
		}
		throw new IllegalStateException("Block marker is not on the block stack");
	}

}
