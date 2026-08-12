package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code stringp} predicate. A quote-framed {@code TYPE_STRING} tests
 * inline; only a {@code TYPE_CELL} (the general-array representation, which is what a
 * mutable character vector is) pays a call, and that call is {@code _charvec_p} -- the
 * marker-shape test, constant time. Anything else (a packed vector, a number, nil)
 * answers nil with two inline type tests and NO call: {@code (setf (aref v i) x)} on a
 * variable place runs this dispatch per store, and the unconditional call was ~2% of the
 * PBKDF2 profile.
 *
 * <p>
 * The {@code TYPE_CELL} arm used to call {@code _charvec_to_str} and re-test the result
 * for {@code TYPE_STRING}, which is an O(1) question answered by rendering the whole
 * vector into a fresh string and throwing it away: {@code (stringp (make-string 8192))}
 * cost ~10 us per call, exactly linear in the length, and a {@code make-string} value
 * keeps that representation for its whole life. Splitting the shape decision out of the
 * normalization (`.kb/adjustable-arrays.md`) made it flat -- the interpreter and the JVM
 * always were.
 */
final class WasmStringpCompiler {

	private WasmStringpCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// A string struct is a string only when quote-framed (a symbol's name shares
		// TYPE_STRING without the frame).
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		WasmEmitHelper.emitStrBytesArray(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(34); // '"'
		ctx.writer.write(Instruction.I32_EQ);
		WasmEmitHelper.emitBoolFromI32(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		WasmEmitHelper.emitCharvecPCall(ctx);
		WasmEmitHelper.emitBoolFromI32(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

}
