package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.StrictMathFunction;

/**
 * Compiles {@code (%strict-math :name x [y])} as one {@code invokestatic} of the
 * {@code java.lang.StrictMath} method the keyword names ({@link StrictMathFunction}) --
 * {@code java.lang.Math}'s for an exact operation, whose intrinsic answers the same bits
 * -- over the arguments unboxed to doubles, both evaluated before either is converted.
 * The interpreter calls the same method, and the wasm backends the fdlibm runtime that is
 * pinned to it bit for bit.
 */
final class JvmStrictMathCompiler {

	private JvmStrictMathCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		StrictMathFunction fn = function(args);
		List<LispVal> operands = args.subList(2, args.size());
		if (operands.size() == 1) {
			JvmArithCompiler.compileUnboxedOperand(operands.get(0), ctx, className);
		}
		else {
			JvmArithCompiler.compileUnboxedOperands(operands, ctx, className, i -> {
			});
		}
		if (fn.shape() == StrictMathFunction.Shape.SCALE) {
			// The exponent arrives as a double; clamped, it is an exact int.
			JvmEmitHelper.emitRawDouble(StrictMathFunction.SCALB_CLAMP, ctx);
			ctx.body.invokestatic(mathMethod(ctx, "min"));
			JvmEmitHelper.emitRawDouble(-StrictMathFunction.SCALB_CLAMP, ctx);
			ctx.body.invokestatic(mathMethod(ctx, "max"));
			ctx.body.d2i();
		}
		ClassEntry owner = ctx.cp.classEntry(fn.owner());
		ctx.body.invokestatic(ctx.cp.methodRef(owner, fn.method(), fn.shape().descriptor()));
		if (fn.shape() == StrictMathFunction.Shape.TO_INT) {
			ctx.body.i2l();
			JvmEmitHelper.boxLong(ctx);
		}
		else {
			JvmEmitHelper.boxDouble(ctx);
		}
	}

	/**
	 * The function a call names: its literal keyword, with the argument count it takes.
	 * @param args the call, head included
	 * @return the function
	 */
	static StrictMathFunction function(List<LispVal> args) {
		StrictMathFunction fn = args.size() < 2 || !(args.get(1) instanceof LispSymbol keyword) ? null
				: StrictMathFunction.ofKeyword(keyword.name());
		if (fn == null) {
			throw new UnsupportedOperationException(LispNames.STRICT_MATH_INTERNAL
					+ " needs a literal StrictMath keyword, got " + (args.size() < 2 ? "none" : args.get(1).print()));
		}
		if (args.size() - 2 != fn.shape().arity()) {
			throw new UnsupportedOperationException(LispNames.STRICT_MATH_INTERNAL + " " + fn.keyword() + " expects "
					+ fn.shape().arity() + " argument(s), got " + (args.size() - 2));
		}
		return fn;
	}

	private static MethodRefEntry mathMethod(JvmLispCompiler.Ctx ctx, String name) {
		return ctx.cp.methodRef(ctx.cp.classEntry("java/lang/Math"), name, "(DD)D");
	}

}
