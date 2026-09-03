package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code rontolisp:bfloat16-bits} / {@code rontolisp:bits-bfloat16} pair,
 * the arithmetic of {@code am.ik.rontolisp.BFloat16} instruction for instruction. Unlike
 * the {@code %ieee754-*} quartet beside it, which needs a 64-bit unsigned integer this
 * numeric model has no room for, sixteen bits fit an i31 fixnum -- so this pair is real
 * here rather than a call-time signal.
 *
 * <p>
 * NaN is carried across by hand, through {@code i64.reinterpret_f64} and back, never
 * through {@code f32.demote_f64}: the specification lets a demotion invent any NaN
 * payload it likes, and the JVM's own demotion quiets a signalling NaN. Doing the bits
 * explicitly is what makes {@code (bfloat16-bits (bits-bfloat16 n))} answer {@code n} for
 * all 65536 patterns on this backend too.
 */
final class WasmBFloat16Compiler {

	/** The binary64 exponent field, all ones. */
	private static final long EXPONENT_MASK = 0x7ff0000000000000L;

	/** The binary64 mantissa field. */
	private static final long MANTISSA_MASK = 0x000fffffffffffffL;

	private WasmBFloat16Compiler() {
	}

	/**
	 * {@code (rontolisp:bfloat16-bits x)}: the bfloat16 pattern of a real, as a fixnum.
	 */
	static void compileBits(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.I64_REINTERPRET_F64);
		int savedI64Locals = ctx.nextI64Local;
		int bitsSlot = ctx.allocI64Temp();
		int payloadSlot = ctx.allocI64Temp();
		setI64(ctx, bitsSlot);
		// (bits & EXPONENT_MASK) == EXPONENT_MASK && (bits & MANTISSA_MASK) != 0
		getI64(ctx, bitsSlot);
		constI64(ctx, EXPONENT_MASK);
		ctx.writer.write(Instruction.I64_AND);
		constI64(ctx, EXPONENT_MASK);
		ctx.writer.write(Instruction.I64_EQ);
		getI64(ctx, bitsSlot);
		constI64(ctx, MANTISSA_MASK);
		ctx.writer.write(Instruction.I64_AND);
		constI64(ctx, 0);
		ctx.writer.write(Instruction.I64_NE);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		// NaN: ((bits >>> 63) << 15) | 0x7f80 | (payload | ((payload - 1) >>> 31))
		getI64(ctx, bitsSlot);
		constI64(ctx, 63);
		ctx.writer.write(Instruction.I64_SHR_U);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		constI32(ctx, 15);
		ctx.writer.write(Instruction.I32_SHL);
		constI32(ctx, 0x7f80);
		ctx.writer.write(Instruction.I32_OR);
		getI64(ctx, bitsSlot);
		constI64(ctx, 45);
		ctx.writer.write(Instruction.I64_SHR_U);
		constI64(ctx, 0x7f);
		ctx.writer.write(Instruction.I64_AND);
		setI64(ctx, payloadSlot);
		getI64(ctx, payloadSlot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		getI64(ctx, payloadSlot);
		constI64(ctx, 1);
		ctx.writer.write(Instruction.I64_SUB);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		constI32(ctx, 31);
		ctx.writer.write(Instruction.I32_SHR_U);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.ELSE);
		// Ordinary: ((f + 0x7fff + ((f >>> 16) & 1)) >>> 16) & 0xffff over the f32
		getI64(ctx, bitsSlot);
		ctx.writer.write(Instruction.F64_REINTERPRET_I64);
		ctx.writer.write(Instruction.F32_DEMOTE_F64);
		ctx.writer.write(Instruction.I32_REINTERPRET_F32);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		setI64(ctx, payloadSlot);
		getI64(ctx, payloadSlot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		constI32(ctx, 0x7fff);
		ctx.writer.write(Instruction.I32_ADD);
		getI64(ctx, payloadSlot);
		constI64(ctx, 16);
		ctx.writer.write(Instruction.I64_SHR_U);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		constI32(ctx, 1);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.I32_ADD);
		constI32(ctx, 16);
		ctx.writer.write(Instruction.I32_SHR_U);
		constI32(ctx, 0xffff);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.nextI64Local = savedI64Locals;
	}

	/** {@code (rontolisp:bits-bfloat16 n)}: the double the pattern encodes, exactly. */
	static void compileFromBits(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		constI32(ctx, 0xffff);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		int savedI64Locals = ctx.nextI64Local;
		int patternSlot = ctx.allocI64Temp();
		setI64(ctx, patternSlot);
		// (n & 0x7f80) == 0x7f80 && (n & 0x7f) != 0
		getI64(ctx, patternSlot);
		constI64(ctx, 0x7f80);
		ctx.writer.write(Instruction.I64_AND);
		constI64(ctx, 0x7f80);
		ctx.writer.write(Instruction.I64_EQ);
		getI64(ctx, patternSlot);
		constI64(ctx, 0x7f);
		ctx.writer.write(Instruction.I64_AND);
		constI64(ctx, 0);
		ctx.writer.write(Instruction.I64_NE);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF, 0x7C); // (result f64)
		// NaN: ((n & 0x8000) << 48) | EXPONENT_MASK | ((n & 0x7f) << 45)
		getI64(ctx, patternSlot);
		constI64(ctx, 0x8000);
		ctx.writer.write(Instruction.I64_AND);
		constI64(ctx, 48);
		ctx.writer.write(Instruction.I64_SHL);
		constI64(ctx, EXPONENT_MASK);
		ctx.writer.write(Instruction.I64_OR);
		getI64(ctx, patternSlot);
		constI64(ctx, 0x7f);
		ctx.writer.write(Instruction.I64_AND);
		constI64(ctx, 45);
		ctx.writer.write(Instruction.I64_SHL);
		ctx.writer.write(Instruction.I64_OR);
		ctx.writer.write(Instruction.F64_REINTERPRET_I64);
		ctx.writer.write(Instruction.ELSE);
		// Ordinary: the pattern IS the top half of an f32, so the widen is a shift
		getI64(ctx, patternSlot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		constI32(ctx, 16);
		ctx.writer.write(Instruction.I32_SHL);
		ctx.writer.write(Instruction.F32_REINTERPRET_I32);
		ctx.writer.write(Instruction.F64_PROMOTE_F32);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.nextI64Local = savedI64Locals;
	}

	/**
	 * An {@code i64} scratch slot is named by a PLACEHOLDER index the function's second
	 * locals run splices out, never by a raw one ({@code .kb/wasm-unboxed-locals.md}).
	 */
	private static void setI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void getI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void constI32(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	private static void constI64(WasmLispCompiler.Ctx ctx, long value) {
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

}
