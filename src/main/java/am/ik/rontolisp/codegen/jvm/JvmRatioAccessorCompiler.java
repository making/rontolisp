package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code numerator} and {@code denominator} accessors. For a ratio
 * ({@code BigInteger[]}) the requested component is returned (normalized back to a
 * {@code Long} when it fits); an integer is its own numerator and has denominator one.
 * Anything else -- a float, a complex, a non-number -- is rejected first by
 * {@code _ckRat}, a {@code RATIONAL} type-error named after the operator
 * ({@code JvmOperandTypeRuntime}), so it can never fall through to the identity tail.
 */
final class JvmRatioAccessorCompiler {

	private JvmRatioAccessorCompiler() {
	}

	static void compileNumerator(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, 0);
	}

	static void compileDenominator(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, 1);
	}

	private static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int index) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// _ckRat answers a rational and throws the operator's type-error for anything
		// else -- a float, a complex, a non-number.
		ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.CK_RAT).entry());
		int temp = ctx.allocTemp();
		ctx.body.astore(temp).aload(temp).instanceOf(JvmEmitHelper.ratioArrayClass(ctx).entry());
		MethodCode.Label ifNotRatioPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotRatioPos);
		ctx.body.aload(temp).checkcast(JvmEmitHelper.ratioArrayClass(ctx).entry());
		ctx.body.loadConstant(index);
		ctx.body.aaload();
		JvmEmitHelper.normalizeBigInteger(ctx);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(ifNotRatioPos);
		if (index == 0) {
			ctx.body.aload(temp);
		}
		else {
			JvmEmitHelper.compileLong(1, ctx);
		}
		ctx.body.labelBinding(gotoEndPos);
	}

}
