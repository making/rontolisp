package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %ieee754-*} bit-reinterpretation primitives (the quartet under the
 * float-features shim library). Bits travel as unsigned integers like the interpreter:
 * the double variants mask the raw long through {@code BigInteger} so a set sign bit
 * becomes a bignum, the single variants always fit a {@code Long}. JVM only -- the WASM
 * numeric model cannot carry 64-bit unsigned bit patterns.
 */
final class JvmIeee754Compiler {

	private static final java.math.BigInteger MASK64 = new java.math.BigInteger("FFFFFFFFFFFFFFFF", 16);

	private JvmIeee754Compiler() {
	}

	/** {@code (%ieee754-double-bits f)}: the raw IEEE 754 bits as an unsigned integer. */
	static void compileDoubleBits(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxDouble(ctx);
		invokeStatic(ctx, "java/lang/Double", "doubleToRawLongBits", "(D)J");
		ctx.body.invokestatic(bigIntegerValueOf(ctx));
		JvmEmitHelper.compileBigInteger(MASK64, ctx);
		ctx.body.invokevirtual(
				JvmEmitHelper.bigIntegerMethod(ctx, "and", "(Ljava/math/BigInteger;)Ljava/math/BigInteger;"));
		JvmEmitHelper.normalizeBigInteger(ctx);
	}

	/** {@code (%ieee754-double-from-bits bits)}: the double the bit pattern encodes. */
	static void compileDoubleFromBits(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.body.invokevirtual(JvmEmitHelper.bigIntegerMethod(ctx, "longValue", "()J"));
		invokeStatic(ctx, "java/lang/Double", "longBitsToDouble", "(J)D");
		JvmEmitHelper.boxDouble(ctx);
	}

	/** {@code (%ieee754-single-bits f)}: the float's raw bits as an unsigned integer. */
	static void compileSingleBits(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxDouble(ctx);
		ctx.body.d2f();
		invokeStatic(ctx, "java/lang/Float", "floatToRawIntBits", "(F)I");
		invokeStatic(ctx, "java/lang/Integer", "toUnsignedLong", "(I)J");
		JvmEmitHelper.boxLong(ctx);
	}

	/** {@code (%ieee754-single-from-bits bits)}: the float the bit pattern encodes. */
	static void compileSingleFromBits(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.body.invokevirtual(JvmEmitHelper.bigIntegerMethod(ctx, "longValue", "()J"));
		ctx.body.l2i();
		invokeStatic(ctx, "java/lang/Float", "intBitsToFloat", "(I)F");
		ctx.body.f2d();
		JvmEmitHelper.boxDouble(ctx);
	}

	private static MethodRefEntry bigIntegerValueOf(JvmLispCompiler.Ctx ctx) {
		return JvmEmitHelper.bigIntegerMethod(ctx, "valueOf", "(J)Ljava/math/BigInteger;");
	}

	private static void invokeStatic(JvmLispCompiler.Ctx ctx, String owner, String name, String desc) {
		ClassEntry cls = ctx.cp.classEntry(owner);
		MethodRefEntry ref = ctx.cp.methodRef(cls, name, desc);
		ctx.body.invokestatic(ref);
	}

}
