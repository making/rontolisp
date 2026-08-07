package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.StringValuedForms;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the string-stream built-ins: the public {@code write-string} (write-line minus
 * the newline) and the internal {@code %make-string-output-stream},
 * {@code %make-string-input-stream} and {@code %string-stream-contents} helpers behind
 * with-output-to-string / with-input-from-string. See
 * {@link WasmStringStreamRuntimeBuilder} for the runtime.
 */
final class WasmWriteStringCompiler {

	private WasmWriteStringCompiler() {
	}

	static void compileWriteString(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException("write-string expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (WasmEmitHelper.streamArg).
		LispVal stream = WasmEmitHelper.streamArg(ctx, parts.size() == 3 ? parts.get(2) : null);
		if (stream == null && parts.get(1) instanceof am.ik.rontolisp.LispString literal) {
			// A string literal to standard output: static bytes, so the stream runtime
			// stays shakeable (WasmLiteralPrint).
			int objSlot = ctx.allocTemp();
			WasmExprCompiler.compileExpr(literal, ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			WasmLiteralPrint.emitStaticWrite(literal.value(), literal, false, ctx);
			// write-string returns its string argument.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			return;
		}
		// A mutable character vector normalizes to a string first -- unless the argument
		// form cannot BE one, in which case the normalizer is 653 bytes of runtime the
		// program never needed (StringValuedForms).
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		if (!StringValuedForms.certainlyString(parts.get(1))) {
			WasmEmitHelper.emitCharvecToStrCall(ctx);
		}
		if (stream != null) {
			WasmExprCompiler.compileExpr(stream, ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
	}

	static void compileMakeOutputStream(LispCons cons, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_MAKE_STR_OSTREAM);
	}

	static void compileMakeInputStream(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		// A mutable character vector normalizes to a string first.
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_MAKE_STR_ISTREAM);
	}

	static void compileContents(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_STREAM_CONTENTS);
	}

}
