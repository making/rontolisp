package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code prin1} built-in function. Same as print but without newline.
 */
final class WasmPrin1Compiler {

	private WasmPrin1Compiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// prin1 returns its argument (CL semantics); stash the object in a temp so it can
		// be returned after printing, not nil.
		int objSlot = ctx.allocTemp();
		if (args.size() > 2) {
			// (prin1 value stream): render to a string, then route via
			// _write_stream_str.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(objSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);
		// Return the argument
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(objSlot);
	}

}
