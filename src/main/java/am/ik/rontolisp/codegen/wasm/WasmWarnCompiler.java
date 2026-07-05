package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the internal {@code %warn} primitive: it writes its single string argument
 * (the pre-built {@code WARNING: ...} message) plus a newline to file descriptor 2
 * (stderr) by calling the always-present {@code _write_line} helper with an i31 stream
 * handle of 2, then pushes nil. In {@code --component} mode the message is dropped
 * instead (evaluated for effect only): the WASI 0.3 adapter's {@code fd_write} wires fd 1
 * to {@code wasi:cli} stdout and treats every other fd as a file-table slot, so fd 2
 * would trap -- the silent drop mirrors {@code %error}, whose message a WASM trap cannot
 * carry either.
 */
final class WasmWarnCompiler {

	private WasmWarnCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		if (ctx.component) {
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			return;
		}
		// stream handle = (ref.i31 2) -> fd 2 = stderr
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(2);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_LINE);
		// _write_line returns the string; %warn returns nil
		ctx.writer.write(Instruction.DROP);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
