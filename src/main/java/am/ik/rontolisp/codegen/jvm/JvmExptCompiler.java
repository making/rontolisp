package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code expt} built-in. When a floating-point literal is involved it
 * delegates to {@code Math.pow} (returning a double); otherwise it raises the base
 * {@code BigInteger} to the integer power, keeping an exact integer result. The integer
 * path requires a non-negative exponent (a negative one throws at runtime); use a float
 * base or exponent for fractional or negative powers.
 */
final class JvmExptCompiler {

	private JvmExptCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.unboxDouble(ctx);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmEmitHelper.unboxDouble(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.POW).index());
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.toBigInteger(ctx);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.L2I);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "pow", "(I)Ljava/math/BigInteger;").index());
			JvmEmitHelper.normalizeBigInteger(ctx);
		}
	}

}
