package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the internal {@code %block} return boundary that the loop macros
 * ({@code do}/{@code dolist}/{@code dotimes}) wrap their expansion in. It emits a WASM
 * {@code block} whose result type is the universal value type {@code (ref null eq)}: the
 * body runs as an implicit {@code progn} and leaves its value on the stack as the block
 * result on normal completion, while a {@code return} form (see
 * {@link WasmReturnCompiler}) branches out of this block carrying the returned value,
 * skipping the rest of the body and any result form.
 */
final class WasmBlockCompiler {

	private WasmBlockCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		// block (result (ref null eq))
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.wasmCtrlDepth++;
		ctx.blockMarkers.push(ctx.wasmCtrlDepth);
		// Body forms run as a progn, leaving the last value on the stack.
		if (parts.size() <= 1) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		else {
			for (int i = 1; i < parts.size(); i++) {
				if (i > 1) {
					ctx.writer.write(Instruction.DROP);
				}
				WasmExprCompiler.compileExpr(parts.get(i), ctx);
			}
		}
		ctx.blockMarkers.pop();
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

}
