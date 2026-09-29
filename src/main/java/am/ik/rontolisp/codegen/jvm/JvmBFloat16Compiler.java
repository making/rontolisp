package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
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
		ctx.body.dstore(valueSlot);
		// (bits & EXPONENT_MASK) == EXPONENT_MASK && (bits & MANTISSA_MASK) != 0
		rawBits(ctx, valueSlot);
		JvmEmitHelper.emitRawLong(EXPONENT_MASK, ctx);
		ctx.body.land();
		JvmEmitHelper.emitRawLong(EXPONENT_MASK, ctx);
		ctx.body.lcmp();
		MethodCode.Label toOrdinaryOnExponent = ctx.body.newLabel();
		ctx.body.ifne(toOrdinaryOnExponent);
		rawBits(ctx, valueSlot);
		JvmEmitHelper.emitRawLong(MANTISSA_MASK, ctx);
		ctx.body.land().lconst_0().lcmp();
		MethodCode.Label toOrdinaryOnMantissa = ctx.body.newLabel();
		ctx.body.ifeq(toOrdinaryOnMantissa);
		// NaN: ((bits >>> 63) << 15) | 0x7f80 | (payload | ((payload - 1) >>> 31))
		rawBits(ctx, valueSlot);
		ctx.body.loadConstant(63).lushr().l2i().loadConstant(15).ishl();
		JvmEmitHelper.emitIntConst(ctx, 0x7f80);
		ctx.body.ior();
		rawBits(ctx, valueSlot);
		ctx.body.loadConstant(45).lushr().l2i();
		JvmEmitHelper.emitIntConst(ctx, 0x7f);
		ctx.body.iand().dup().iconst_1().isub().loadConstant(31).iushr().ior().ior();
		MethodCode.Label toEnd = ctx.body.newLabel();
		ctx.body.goto_(toEnd);
		// Ordinary: ((f + 0x7fff + ((f >>> 16) & 1)) >>> 16) & 0xffff over the f32
		ctx.body.labelBinding(toOrdinaryOnExponent);
		ctx.body.labelBinding(toOrdinaryOnMantissa);
		ctx.body.dload(valueSlot).d2f();
		invokeStatic(ctx, "java/lang/Float", "floatToRawIntBits", "(F)I");
		ctx.body.dup();
		JvmEmitHelper.emitIntConst(ctx, 0x7fff);
		ctx.body.iadd().swap().loadConstant(16).iushr().iconst_1().iand().iadd().loadConstant(16);
		ctx.body.iushr();
		JvmEmitHelper.emitIntConst(ctx, 0xffff);
		ctx.body.iand();
		ctx.body.labelBinding(toEnd);
		ctx.body.i2l();
		JvmEmitHelper.boxLong(ctx);
	}

	/** {@code (rontolisp:bits-bfloat16 n)}: the double the pattern encodes, exactly. */
	static void compileFromBits(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.body.l2i();
		JvmEmitHelper.emitIntConst(ctx, 0xffff);
		ctx.body.iand();
		int patternSlot = ctx.allocTemp();
		ctx.body.istore(patternSlot);
		// (n & 0x7f80) == 0x7f80 && (n & 0x7f) != 0
		ctx.body.iload(patternSlot);
		JvmEmitHelper.emitIntConst(ctx, 0x7f80);
		ctx.body.iand();
		JvmEmitHelper.emitIntConst(ctx, 0x7f80);
		MethodCode.Label toOrdinaryOnExponent = ctx.body.newLabel();
		ctx.body.if_icmpne(toOrdinaryOnExponent);
		ctx.body.iload(patternSlot);
		JvmEmitHelper.emitIntConst(ctx, 0x7f);
		ctx.body.iand();
		MethodCode.Label toOrdinaryOnMantissa = ctx.body.newLabel();
		ctx.body.ifeq(toOrdinaryOnMantissa);
		// NaN: ((n & 0x8000) << 48) | EXPONENT_MASK | ((n & 0x7f) << 45)
		ctx.body.iload(patternSlot);
		JvmEmitHelper.emitIntConst(ctx, 0x8000);
		ctx.body.iand().i2l().loadConstant(48).lshl();
		JvmEmitHelper.emitRawLong(EXPONENT_MASK, ctx);
		ctx.body.lor().iload(patternSlot);
		JvmEmitHelper.emitIntConst(ctx, 0x7f);
		ctx.body.iand().i2l().loadConstant(45).lshl().lor();
		invokeStatic(ctx, "java/lang/Double", "longBitsToDouble", "(J)D");
		MethodCode.Label toEnd = ctx.body.newLabel();
		ctx.body.goto_(toEnd);
		// Ordinary: the pattern IS the top half of an f32, so the widen is a shift
		ctx.body.labelBinding(toOrdinaryOnExponent);
		ctx.body.labelBinding(toOrdinaryOnMantissa);
		ctx.body.iload(patternSlot).loadConstant(16).ishl();
		invokeStatic(ctx, "java/lang/Float", "intBitsToFloat", "(I)F");
		ctx.body.f2d();
		ctx.body.labelBinding(toEnd);
		JvmEmitHelper.boxDouble(ctx);
	}

	/** The raw {@code long} bits of the double held in {@code slot}. */
	private static void rawBits(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.body.dload(slot);
		invokeStatic(ctx, "java/lang/Double", "doubleToRawLongBits", "(D)J");
	}

	private static void invokeStatic(JvmLispCompiler.Ctx ctx, String owner, String name, String desc) {
		ConstantPool.ClassConstant cls = ctx.cp.addClass(ctx.cp.addUtf8(owner));
		ConstantPool.MethodrefConstant ref = ctx.cp.addMethodref(cls,
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
		ctx.body.invokestatic(ref.entry());
	}

}
