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
		emitStringpI32(ctx, tmpSlot);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/**
	 * Pushes an i32: 1 when the value in {@code slot} is a string -- a quote-framed
	 * {@code TYPE_STRING} (a symbol's name shares the struct without the frame) or a
	 * charvec {@code TYPE_CELL} (the mutable character vector) -- 0 otherwise. The same
	 * shape decision {@link #compile} renders as a bool, split out so the
	 * {@code array-element-type} lowering can branch on it and answer {@code character}
	 * for a string without re-rendering the value into a fresh immutable string.
	 * @param ctx the compilation context
	 * @param slot the local holding the value to test
	 */
	static void emitStringpI32(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		// A string struct is a string only when quote-framed (a symbol's name shares
		// TYPE_STRING without the frame).
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.emitStrBytesArray(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(34); // '"'
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF, Type.I32.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.emitCharvecPCall(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

}
