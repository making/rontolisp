package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code while} special form. Uses a {@code block}/{@code loop} pair: the
 * test is re-evaluated at the top of the loop and {@code br_if} exits the block when it
 * is nil; otherwise the body runs and {@code br} jumps back. The loop leaves nil on the
 * stack as its value.
 */
final class WasmWhileCompiler {

	private WasmWhileCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// The test and body are compiled inside the block/loop pair; track the two extra
		// levels so a return nested in the body computes the correct br depth.
		ctx.wasmCtrlDepth += 2;
		// Evaluate the test; exit the block (depth 1) when it is nil.
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.BR_IF, 1);
		// Body: every expression leaves a value, which is dropped.
		for (int i = 2; i < parts.size(); i++) {
			WasmExprCompiler.compileExpr(parts.get(i), ctx);
			ctx.writer.write(Instruction.DROP);
		}
		// Jump back to the top of the loop (depth 0).
		ctx.writer.write(Instruction.BR, 0);
		ctx.wasmCtrlDepth -= 2;
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		// Push nil as the result of the while form.
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
