package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code print} built-in function.
 */
final class WasmPrintCompiler {

	private WasmPrintCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// print returns its argument (CL semantics); stash the object in a temp so it can
		// be returned after printing, not nil.
		int objSlot = ctx.allocTemp();
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (WasmEmitHelper.streamArg).
		LispVal stream = WasmEmitHelper.streamArg(ctx, args.size() > 2 ? args.get(2) : null);
		if (stream != null) {
			// (print value stream): render to a string, route it and a newline via
			// _write_stream_str (the stream is evaluated once into a temp).
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
			int streamSlot = ctx.allocTemp();
			WasmExprCompiler.compileExpr(stream, ctx);
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
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			return;
		}
		if (!compileLiteralPrint(args.get(1), ctx, objSlot)) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);
		}
		// Write newline
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		// Return the argument
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(objSlot);
	}

	/**
	 * A literal argument's readable form is a compile-time constant ({@code print} always
	 * escapes, and every printer-control variable that could change the text is inert --
	 * see {@code .kb/pretty-printer.md}), so it is written as pre-rendered static bytes
	 * through {@code FUNC_WRITE_STR} (which keeps the {@code *standard-output*} redirect
	 * semantics). The generic {@code FUNC_PRINT_VAL} is never referenced, so the whole
	 * print-dispatch family (float/bignum/ratio/array renderers) stays shakeable for a
	 * program that only prints literals. The {@code print-object} hook cannot be in play
	 * here: this path runs inside the print-object-free gate
	 * ({@code WasmExprCompiler.compilePrintOperator}).
	 * @return true when the argument was a handled literal and the write was emitted
	 */
	private static boolean compileLiteralPrint(LispVal obj, WasmLispCompiler.Ctx ctx, int objSlot) {
		String rendered = switch (obj) {
			case am.ik.rontolisp.LispString s -> s.print();
			case am.ik.rontolisp.LispInteger i -> i.print();
			case am.ik.rontolisp.LispBigInteger bi -> bi.print();
			default -> null;
		};
		if (rendered == null) {
			return false;
		}
		WasmExprCompiler.compileExpr(obj, ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(objSlot);
		WasmLispCompiler.StringTable.StringEntry out = ctx.stringTable.addString(rendered);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(out.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(out.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		return true;
	}

}
