package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmFdlibmRuntimeBuilder.Fn;
import am.ik.rontolisp.compiler.StrictMathFunction;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code (%strict-math :name x [y])} ({@link StrictMathFunction}): each argument
 * evaluated, then each converted to {@code f64}, then the one instruction an exact
 * operation is ({@code sqrt}, {@code ceil}, {@code floor}, {@code rint}) or a call into
 * the fdlibm runtime ({@link WasmFdlibmRuntimeBuilder}), whose functions answer
 * {@code java.lang.StrictMath}'s bits -- what the interpreter and the JVM call.
 */
final class WasmStrictMathCompiler {

	private WasmStrictMathCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		StrictMathFunction fn = function(args);
		if (fn.shape().arity() == 1) {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
		}
		else {
			// Both are evaluated before either is converted: the conversion is the
			// operation's, which applies once its arguments are all evaluated.
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			int first = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(first);
			WasmExprCompiler.compileExpr(args.get(3), ctx);
			int second = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(second);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(first);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(second);
			WasmEmitHelper.castFloatGetF64(ctx);
		}
		switch (fn) {
			case SQRT -> ctx.writer.write(Instruction.F64_SQRT);
			case CEIL -> ctx.writer.write(Instruction.F64_CEIL);
			case FLOOR -> ctx.writer.write(Instruction.F64_FLOOR);
			// f64.nearest rounds half to even, as rint does.
			case RINT -> ctx.writer.write(Instruction.F64_NEAREST);
			default -> WasmTranscendentalCompiler.call(ctx, runtimeFunction(fn));
		}
		if (fn.shape() == StrictMathFunction.Shape.TO_INT) {
			// getExponent answers -1023..1024, an i31 fixnum.
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		else {
			WasmEmitHelper.boxF64(ctx);
		}
	}

	/**
	 * The runtime function computing a function the backend does not emit inline.
	 * @param fn the function
	 * @return its fdlibm runtime function
	 */
	static Fn runtimeFunction(StrictMathFunction fn) {
		return switch (fn) {
			case SIN -> Fn.SIN;
			case COS -> Fn.COS;
			case TAN -> Fn.TAN;
			case ASIN -> Fn.ASIN;
			case ACOS -> Fn.ACOS;
			case ATAN -> Fn.ATAN;
			case EXP -> Fn.EXP;
			case LOG -> Fn.LOG;
			case LOG10 -> Fn.LOG10;
			case CBRT -> Fn.CBRT;
			case SINH -> Fn.SINH;
			case COSH -> Fn.COSH;
			case TANH -> Fn.TANH;
			case EXPM1 -> Fn.EXPM1;
			case LOG1P -> Fn.LOG1P;
			case ULP -> Fn.ULP;
			case SIGNUM -> Fn.SIGNUM;
			case NEXT_UP -> Fn.NEXT_UP;
			case NEXT_DOWN -> Fn.NEXT_DOWN;
			case ATAN2 -> Fn.ATAN2;
			case POW -> Fn.POW;
			case HYPOT -> Fn.HYPOT;
			case IEEE_REMAINDER -> Fn.IEEE_REMAINDER;
			case COPY_SIGN -> Fn.COPY_SIGN;
			case NEXT_AFTER -> Fn.NEXT_AFTER;
			case GET_EXPONENT -> Fn.GET_EXPONENT;
			case SCALB -> Fn.SCALB;
			case SQRT, CEIL, FLOOR, RINT -> throw new IllegalArgumentException(fn + " is one instruction");
		};
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

}
