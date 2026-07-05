package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code print} built-in function.
 */
final class WasmPrintCompiler {

	private WasmPrintCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() > 2) {
			// (print value stream): render to a string, route it and a newline via
			// _write_stream_str (the stream is evaluated once into a temp).
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
			int streamSlot = ctx.allocTemp();
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeSignedLeb128(streamSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
			ctx.writer.write(Instruction.DROP);
			WasmTerpriCompiler.emitNewlineStringStruct(ctx);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(streamSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);
		// Write newline
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
