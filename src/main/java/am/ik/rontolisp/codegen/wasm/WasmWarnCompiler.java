package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the internal {@code %warn} primitive: it writes its single string argument
 * (the pre-built {@code WARNING: ...} message) plus a newline to the current
 * {@code *error-output*} by calling the always-present {@code _write_line} helper, then
 * pushes nil. A program that never binds the variable passes the constant i31 handle 2
 * (byte-identical to before the redirect existed) -- file descriptor 2, which is what the
 * seeded value of {@code *error-output*} means everywhere; one that DOES bind it (CL's
 * {@code (let ((*error-output* s)) (warn ...))} capture idiom) passes the variable's
 * current value, so a string stream captures the report. In {@code --component} mode the
 * message flows through the WASI 0.3 adapter's {@code fd_write}, whose fd&nbsp;2 branch
 * drives {@code wasi:cli/stderr} (mirroring the fd&nbsp;1 stdout path); the sockets /
 * http / serve component adapters wire fd&nbsp;2 the same way, so {@code warn} reaches
 * stderr on every backend.
 */
final class WasmWarnCompiler {

	private WasmWarnCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		Integer errorOutputGlobal = ctx.globalIndices.get(am.ik.rontolisp.LispNames.ERROR_OUTPUT_VAR);
		if (errorOutputGlobal != null) {
			// The redirect is active: the destination is the variable's current value.
			WasmExprCompiler.compileExpr(am.ik.rontolisp.compiler.StreamDesignators.errorOutput(), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_LINE);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			return;
		}
		// stream handle = (ref.i31 2) -> fd 2 = stderr
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128((int) am.ik.rontolisp.compiler.StreamDesignators.STANDARD_ERROR_HANDLE);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_LINE);
		// _write_line returns the string; %warn returns nil
		ctx.writer.write(Instruction.DROP);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
