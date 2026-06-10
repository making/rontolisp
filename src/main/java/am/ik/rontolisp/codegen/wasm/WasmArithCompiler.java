package am.ik.rontolisp.codegen.wasm;

import java.math.BigInteger;
import java.util.List;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import org.jspecify.annotations.Nullable;

/**
 * Compiles arithmetic operations ({@code +}, {@code -}, {@code *}, {@code /},
 * {@code mod}).
 */
final class WasmArithCompiler {

	private WasmArithCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int i32Opcode, int f64Opcode) {
		List<LispVal> args = cons.toList();
		if (i32Opcode == Instruction.I32_DIV_S && compileLiteralDivision(args, ctx)) {
			return;
		}
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			for (int i = 2; i < args.size(); i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
				ctx.writer.write(f64Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}
		else {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			for (int i = 2; i < args.size(); i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				WasmEmitHelper.castI31GetS(ctx);
				ctx.writer.write(i32Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
	}

	private static boolean compileLiteralDivision(List<LispVal> args, WasmLispCompiler.Ctx ctx) {
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
		if (qr[1].signum() == 0 && qr[0].bitLength() <= 31) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(qr[0].intValue());
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			return true;
		}
		BigInteger gcd = numerator.gcd(denominator);
		BigInteger reducedNum = numerator.divide(gcd);
		BigInteger reducedDen = denominator.divide(gcd);
		WasmEmitHelper.compileStringLiteral(reducedNum + "/" + reducedDen, ctx);
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
