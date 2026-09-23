package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code listen} built-in. A string input stream (a negative i31 handle to a
 * {@code [kind][cursor][end]} record) answers whether a character remains, without
 * consuming anything; anything else keeps the call-time unsupported stub (no non-blocking
 * input probe exists on this WASM target). The parked unread-char cell rides ahead of
 * this helper (the {@code %unread-listen} rewrite), so only the record is consulted here.
 */
final class WasmListenCompiler {

	private WasmListenCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		// The source designator, as read-char compiles it: an omitted argument and an
		// explicit nil both mean the current *standard-input*.
		LispVal stream = WasmEmitHelper.inputStreamArg(ctx, parts.size() == 2 ? parts.get(1) : null);
		int tmp = ctx.allocTemp();
		WasmExprCompiler.compileExpr(stream != null ? stream : LispNil.INSTANCE, ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		// is-i31(tmp) && i31.get_s(tmp) < 0 ? 1 : 0 -- a negative handle is a
		// string-stream record.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF, 0x7F);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// String path: kind == 0 (input) and cursor < end.
		emitRecField(ctx, tmp, 0);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		emitRecField(ctx, tmp, 4);
		emitRecField(ctx, tmp, 8);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.ELSE);
		emitNil(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		emitNil(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// Anything else keeps the call-time unsupported stub. The stream was
		// already evaluated once into the temp, so the stub errors without
		// re-running it: drop the temp, then signal.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		ctx.writer.write(Instruction.DROP);
		WasmExprCompiler
			.compileExpr(
					LispMacroExpander.callTimeUnsupportedStub("listen requires the interpreter, the JVM backend or a "
							+ "--component socket stream (no non-blocking input probe exists on this WASM target)"),
					ctx);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Pushes {@code i32.load((0 - i31.get_s(tmp)) + offset)} -- one field of the
	 * string-stream record behind the temp's negative handle.
	 */
	private static void emitRecField(WasmLispCompiler.Ctx ctx, int tmp, int offset) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(offset);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	private static void emitNil(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
