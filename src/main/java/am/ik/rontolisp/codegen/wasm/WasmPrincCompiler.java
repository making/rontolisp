package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code princ} built-in function. Prints without quotes and without
 * newline.
 */
final class WasmPrincCompiler {

	private WasmPrincCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// princ returns its argument (CL semantics); stash the object in a temp so it can
		// be returned after printing, not nil.
		int objSlot = ctx.allocTemp();
		// An explicit stream argument, or the current *standard-output* value when the
		// program redirects it (WasmEmitHelper.defaultStreamArg).
		LispVal stream = args.size() > 2 ? args.get(2) : WasmEmitHelper.defaultStreamArg(ctx);
		if (stream != null) {
			// (princ value stream): render to a string, then route via
			// _write_stream_str.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRINC_TO_STR);
			WasmExprCompiler.compileExpr(stream, ctx);
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
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRINC_VAL);
		// Return the argument
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(objSlot);
	}

}
