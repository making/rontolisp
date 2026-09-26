package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code rational} built-in function. Integers (any tier) and ratios answer
 * themselves; a finite float decomposes from its raw IEEE 754 bits exactly like
 * {@code WasmFloatFdivRuntimeBuilder}'s exact route (hidden bit, subnormal shape, sign on
 * the mantissa, trailing-zero strip so integral values divide by one) and normalizes
 * through the existing {@code _rat_div} -- so the answer is exactly what
 * {@code (/ num den)} of the same integers would be, including the backend's
 * ratio-component range. A complex or any other non-real takes the {@code _type_err_real}
 * landing (the interpreter's REAL operand-type report text); a NaN or an infinity has no
 * exact rational and signals the interpreter's non-finite error instead.
 */
final class WasmRationalCompiler {

	/** The binary64 mantissa field. */
	private static final long MANTISSA_MASK = 0x000fffffffffffffL;

	/** The binary64 hidden bit of a normal value. */
	private static final long HIDDEN_BIT = 0x0010000000000000L;

	private WasmRationalCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		// An exact integer (any tier) is already exact.
		isExactInt(ctx, tmpSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.ELSE);
		// A ratio is already exact.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.ELSE);
		// A float decomposes below.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		compileFloat(ctx, tmpSlot);
		ctx.writer.write(Instruction.ELSE);
		// Anything else -- a complex, a non-number -- has no exact rational.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/** Pushes {@code tmp is (i31 | TYPE_BIGNUM | TYPE_BIGINT)} as an i32. */
	private static void isExactInt(WasmLispCompiler.Ctx ctx, int tmpSlot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.I32_OR);
	}

	/**
	 * Emits the float decomposition for the float in {@code tmpSlot}, leaving the exact
	 * rational. All intermediate state lives in i64 scratch temps plus two eqref temps
	 * for the numerator/denominator pair {@code _rat_div} normalizes.
	 */
	private static void compileFloat(WasmLispCompiler.Ctx ctx, int tmpSlot) {
		int savedI64Locals = ctx.nextI64Local;
		int bitsSlot = ctx.allocI64Temp();
		int bexpSlot = ctx.allocI64Temp();
		int mantSlot = ctx.allocI64Temp();
		int expSlot = ctx.allocI64Temp();
		int numSlot = ctx.allocTemp();
		int denSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.I64_REINTERPRET_F64);
		setI64(ctx, bitsSlot);
		// biased exponent = (bits >>u 52) & 0x7ff, kept in i64 throughout.
		getI64(ctx, bitsSlot);
		constI64(ctx, 52);
		ctx.writer.write(Instruction.I64_SHR_U);
		constI64(ctx, 0x7ff);
		ctx.writer.write(Instruction.I64_AND);
		setI64(ctx, bexpSlot);
		// A NaN or an infinity has no exact rational: the interpreter's
		// non-finite error (a catchable throw in EH mode, a bare trap outside
		// it -- stack-polymorphic either way, like a plain %error).
		getI64(ctx, bexpSlot);
		constI64(ctx, 0x7ff);
		ctx.writer.write(Instruction.I64_EQ);
		ctx.writer.write(Instruction.IF, 0x40);
		WasmErrorCompiler.compile(
				new LispCons(new LispSymbol(LispNames.ERROR_INTERNAL),
						new LispCons(new LispString("rational of a non-finite float is undefined"), LispNil.INSTANCE)),
				ctx);
		ctx.writer.write(Instruction.END);
		getI64(ctx, bitsSlot);
		constI64(ctx, MANTISSA_MASK);
		ctx.writer.write(Instruction.I64_AND);
		setI64(ctx, mantSlot);
		// Subnormal (and zero): the mantissa is the fraction, scaled by 2^-1074.
		getI64(ctx, bexpSlot);
		constI64(ctx, 0);
		ctx.writer.write(Instruction.I64_EQ);
		ctx.writer.write(Instruction.IF, 0x40);
		constI64(ctx, -1074);
		setI64(ctx, expSlot);
		ctx.writer.write(Instruction.ELSE);
		getI64(ctx, mantSlot);
		constI64(ctx, HIDDEN_BIT);
		ctx.writer.write(Instruction.I64_OR);
		setI64(ctx, mantSlot);
		getI64(ctx, bexpSlot);
		constI64(ctx, 1075);
		ctx.writer.write(Instruction.I64_SUB);
		setI64(ctx, expSlot);
		ctx.writer.write(Instruction.END);
		// The sign bit, applied to the integer mantissa.
		getI64(ctx, bitsSlot);
		constI64(ctx, 0);
		ctx.writer.write(Instruction.I64_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		constI64(ctx, 0);
		getI64(ctx, mantSlot);
		ctx.writer.write(Instruction.I64_SUB);
		setI64(ctx, mantSlot);
		ctx.writer.write(Instruction.END);
		// A zero mantissa (either zero) is plain zero over one.
		getI64(ctx, mantSlot);
		ctx.writer.write(Instruction.I64_EQZ);
		ctx.writer.write(Instruction.IF, 0x40);
		constI32(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(ctx, numSlot);
		constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(ctx, denSlot);
		ctx.writer.write(Instruction.ELSE);
		// Strip the mantissa's trailing zeros into the exponent: 7.0 becomes
		// 7*2^0 rather than 2^52-scaled, so an integral value divides by one.
		getI64(ctx, mantSlot);
		ctx.writer.write(Instruction.I64_CTZ);
		setI64(ctx, bexpSlot);
		getI64(ctx, mantSlot);
		getI64(ctx, bexpSlot);
		ctx.writer.write(Instruction.I64_SHR_S);
		setI64(ctx, mantSlot);
		getI64(ctx, expSlot);
		getI64(ctx, bexpSlot);
		ctx.writer.write(Instruction.I64_ADD);
		setI64(ctx, expSlot);
		getI64(ctx, expSlot);
		constI64(ctx, 0);
		ctx.writer.write(Instruction.I64_GE_S);
		ctx.writer.write(Instruction.IF, 0x40);
		getI64(ctx, mantSlot);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_INT_NEW);
		getI64(ctx, expSlot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_BIG_ASH);
		setLocal(ctx, numSlot);
		constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(ctx, denSlot);
		ctx.writer.write(Instruction.ELSE);
		getI64(ctx, mantSlot);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_INT_NEW);
		setLocal(ctx, numSlot);
		constI64(ctx, 1);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_INT_NEW);
		constI64(ctx, 0);
		getI64(ctx, expSlot);
		ctx.writer.write(Instruction.I64_SUB);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_BIG_ASH);
		setLocal(ctx, denSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, numSlot);
		getLocal(ctx, denSlot);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_RAT_DIV);
		ctx.nextI64Local = savedI64Locals;
	}

	private static void getI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void setI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void getLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
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
