package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code rontolisp:bfloat16-bits} / {@code rontolisp:bits-bfloat16} pair.
 * The arithmetic is {@code am.ik.rontolisp.BFloat16} instruction for instruction --
 * round-to-nearest-even on the way down, an exact widen on the way back, and NaN carried
 * across by its payload's top seven bits rather than through the f32, which quiets a
 * signalling one. Emitted INLINE rather than called: {@code BFloat16} lives in the root
 * package and does not travel with a compiled program.
 */
final class JvmBFloat16Compiler {

	/** The binary64 exponent field, all ones. */
	private static final long EXPONENT_MASK = 0x7ff0000000000000L;

	/** The binary64 mantissa field. */
	private static final long MANTISSA_MASK = 0x000fffffffffffffL;

	private JvmBFloat16Compiler() {
	}

	/** {@code (rontolisp:bfloat16-bits x)}: the bfloat16 pattern of a real, as a Long. */
	static void compileBits(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxDouble(ctx);
		int valueSlot = ctx.allocTemp();
		ctx.allocTemp();
		ctx.emit(Opcode.DSTORE);
		ctx.emit(valueSlot);
		// (bits & EXPONENT_MASK) == EXPONENT_MASK && (bits & MANTISSA_MASK) != 0
		rawBits(ctx, valueSlot);
		JvmEmitHelper.emitRawLong(EXPONENT_MASK, ctx);
		ctx.emit(Opcode.LAND);
		JvmEmitHelper.emitRawLong(EXPONENT_MASK, ctx);
		ctx.emit(Opcode.LCMP);
		int toOrdinaryOnExponent = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		rawBits(ctx, valueSlot);
		JvmEmitHelper.emitRawLong(MANTISSA_MASK, ctx);
		ctx.emit(Opcode.LAND);
		ctx.emit(Opcode.LCONST_0);
		ctx.emit(Opcode.LCMP);
		int toOrdinaryOnMantissa = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		// NaN: ((bits >>> 63) << 15) | 0x7f80 | (payload | ((payload - 1) >>> 31))
		rawBits(ctx, valueSlot);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(63);
		ctx.emit(Opcode.LUSHR);
		ctx.emit(Opcode.L2I);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(15);
		ctx.emit(Opcode.ISHL);
		JvmEmitHelper.emitIntConst(ctx, 0x7f80);
		ctx.emit(Opcode.IOR);
		rawBits(ctx, valueSlot);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(45);
		ctx.emit(Opcode.LUSHR);
		ctx.emit(Opcode.L2I);
		JvmEmitHelper.emitIntConst(ctx, 0x7f);
		ctx.emit(Opcode.IAND);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISUB);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(31);
		ctx.emit(Opcode.IUSHR);
		ctx.emit(Opcode.IOR);
		ctx.emit(Opcode.IOR);
		int toEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Ordinary: ((f + 0x7fff + ((f >>> 16) & 1)) >>> 16) & 0xffff over the f32
		JvmEmitHelper.patchBranch(ctx, toOrdinaryOnExponent, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, toOrdinaryOnMantissa, ctx.code.size());
		ctx.emit(Opcode.DLOAD);
		ctx.emit(valueSlot);
		ctx.emit(Opcode.D2F);
		invokeStatic(ctx, "java/lang/Float", "floatToRawIntBits", "(F)I");
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.emitIntConst(ctx, 0x7fff);
		ctx.emit(Opcode.IADD);
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(16);
		ctx.emit(Opcode.IUSHR);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.IAND);
		ctx.emit(Opcode.IADD);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(16);
		ctx.emit(Opcode.IUSHR);
		JvmEmitHelper.emitIntConst(ctx, 0xffff);
		ctx.emit(Opcode.IAND);
		JvmEmitHelper.patchBranch(ctx, toEnd, ctx.code.size());
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
	}

	/** {@code (rontolisp:bits-bfloat16 n)}: the double the pattern encodes, exactly. */
	static void compileFromBits(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		JvmEmitHelper.emitIntConst(ctx, 0xffff);
		ctx.emit(Opcode.IAND);
		int patternSlot = ctx.allocTemp();
		ctx.emit(Opcode.ISTORE);
		ctx.emit(patternSlot);
		// (n & 0x7f80) == 0x7f80 && (n & 0x7f) != 0
		ctx.emit(Opcode.ILOAD);
		ctx.emit(patternSlot);
		JvmEmitHelper.emitIntConst(ctx, 0x7f80);
		ctx.emit(Opcode.IAND);
		JvmEmitHelper.emitIntConst(ctx, 0x7f80);
		int toOrdinaryOnExponent = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(patternSlot);
		JvmEmitHelper.emitIntConst(ctx, 0x7f);
		ctx.emit(Opcode.IAND);
		int toOrdinaryOnMantissa = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		// NaN: ((n & 0x8000) << 48) | EXPONENT_MASK | ((n & 0x7f) << 45)
		ctx.emit(Opcode.ILOAD);
		ctx.emit(patternSlot);
		JvmEmitHelper.emitIntConst(ctx, 0x8000);
		ctx.emit(Opcode.IAND);
		ctx.emit(Opcode.I2L);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(48);
		ctx.emit(Opcode.LSHL);
		JvmEmitHelper.emitRawLong(EXPONENT_MASK, ctx);
		ctx.emit(Opcode.LOR);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(patternSlot);
		JvmEmitHelper.emitIntConst(ctx, 0x7f);
		ctx.emit(Opcode.IAND);
		ctx.emit(Opcode.I2L);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(45);
		ctx.emit(Opcode.LSHL);
		ctx.emit(Opcode.LOR);
		invokeStatic(ctx, "java/lang/Double", "longBitsToDouble", "(J)D");
		int toEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Ordinary: the pattern IS the top half of an f32, so the widen is a shift
		JvmEmitHelper.patchBranch(ctx, toOrdinaryOnExponent, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, toOrdinaryOnMantissa, ctx.code.size());
		ctx.emit(Opcode.ILOAD);
		ctx.emit(patternSlot);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(16);
		ctx.emit(Opcode.ISHL);
		invokeStatic(ctx, "java/lang/Float", "intBitsToFloat", "(I)F");
		ctx.emit(Opcode.F2D);
		JvmEmitHelper.patchBranch(ctx, toEnd, ctx.code.size());
		JvmEmitHelper.boxDouble(ctx);
	}

	/** The raw {@code long} bits of the double held in {@code slot}. */
	private static void rawBits(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.emit(Opcode.DLOAD);
		ctx.emit(slot);
		invokeStatic(ctx, "java/lang/Double", "doubleToRawLongBits", "(D)J");
	}

	private static void invokeStatic(JvmLispCompiler.Ctx ctx, String owner, String name, String desc) {
		ConstantPool.ClassConstant cls = ctx.cp.addClass(ctx.cp.addUtf8(owner));
		ConstantPool.MethodrefConstant ref = ctx.cp.addMethodref(cls,
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
