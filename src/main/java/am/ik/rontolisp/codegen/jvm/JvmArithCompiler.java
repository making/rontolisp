package am.ik.rontolisp.codegen.jvm;

import java.math.BigInteger;
import java.util.List;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

/**
 * Compiles arithmetic operations ({@code +}, {@code -}, {@code *}, {@code /},
 * {@code mod}). The integer path is dispatched to the numeric runtime helpers, which keep
 * values as {@code Long} and promote to {@code BigInteger} on overflow.
 */
final class JvmArithCompiler {

	private JvmArithCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String opKey, int doubleOpcode, String className) {
		List<LispVal> args = cons.toList();
		if (JvmNumericRuntimeBuilder.DIV.equals(opKey) && compileLiteralDivision(args, ctx)) {
			return;
		}
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.unboxDouble(ctx);
			for (int i = 2; i < args.size(); i++) {
				JvmExprCompiler.compileExpr(args.get(i), ctx, className);
				JvmEmitHelper.unboxDouble(ctx);
				ctx.emit(doubleOpcode);
			}
			JvmEmitHelper.boxDouble(ctx);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// Unary subtraction is negation; the other operators leave a single argument
		// as-is.
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && args.size() == 2) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.NEG).index());
			return;
		}
		for (int i = 2; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(opKey).index());
		}
	}

	private static boolean compileLiteralDivision(List<LispVal> args, JvmLispCompiler.Ctx ctx) {
		if (args.size() != 3) {
			return false;
		}
		BigInteger numerator = asBigInteger(args.get(1));
		BigInteger denominator = asBigInteger(args.get(2));
		if (numerator == null || denominator == null || denominator.signum() == 0) {
			return false;
		}
		if (denominator.signum() < 0) {
			numerator = numerator.negate();
			denominator = denominator.negate();
		}
		BigInteger[] qr = numerator.divideAndRemainder(denominator);
		if (qr[1].signum() == 0) {
			if (qr[0].bitLength() <= 63) {
				JvmEmitHelper.compileLong(qr[0].longValue(), ctx);
			}
			else {
				JvmEmitHelper.compileBigInteger(qr[0], ctx);
			}
			return true;
		}
		BigInteger gcd = numerator.gcd(denominator);
		BigInteger reducedNum = numerator.divide(gcd);
		BigInteger reducedDen = denominator.divide(gcd);
		JvmEmitHelper.compileStringLiteral(reducedNum + "/" + reducedDen, ctx);
		return true;
	}

	private static @Nullable BigInteger asBigInteger(LispVal val) {
		return switch (val) {
			case LispInteger i -> BigInteger.valueOf(i.value());
			case LispBigInteger b -> b.value();
			default -> null;
		};
	}

}
