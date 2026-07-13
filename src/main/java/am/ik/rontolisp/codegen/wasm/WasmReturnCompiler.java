package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code return} special form: a non-local exit from the nearest enclosing
 * {@code %block} (established by the loop macros). The optional value (default nil) is
 * left on the stack and a {@code br} branches out to the block, which has a matching
 * {@code (ref null eq)} result type. The branch depth is the number of control structures
 * between this point and the target block, derived from
 * {@link WasmLispCompiler.Ctx#wasmCtrlDepth}.
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
		Integer marker = ctx.blockMarkers.peek();
		if (marker == null) {
			throw new IllegalStateException("Cannot compile return outside of a loop block");
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() > 1) {
			WasmExprCompiler.compileExpr(parts.get(1), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		WasmLispCompiler.UnwindScope escaped = ctx.unwindScopes.peek();
		if (escaped != null && escaped.blockDepth() >= ctx.blockMarkers.size()) {
			// The exit crosses the innermost protected region: run its cleanups through
			// the trampoline; the trampolines cascade to the block, innermost first.
			ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - escaped.trampolineDepth());
			return;
		}
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - marker);
	}

}
