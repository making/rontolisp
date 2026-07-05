package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the bitwise integer built-ins ({@code logand}, {@code logior}, {@code logxor},
 * {@code lognot}, {@code ash}, {@code integer-length}, {@code logbitp}) via the exact
 * {@code BigInteger} bit operations, so the result is never truncated. {@code ash} uses
 * {@code BigInteger.shiftLeft}, which performs a right shift for a negative count.
 */
final class JvmBitwiseCompiler {

	private JvmBitwiseCompiler() {
	}

	static void compileLogand(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileBinary(cons, ctx, className, "and");
	}

	static void compileLogior(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileBinary(cons, ctx, className, "or");
	}

	static void compileLogxor(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileBinary(cons, ctx, className, "xor");
	}

	private static void compileBinary(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String method) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(
				JvmEmitHelper.bigIntegerMethod(ctx, method, "(Ljava/math/BigInteger;)Ljava/math/BigInteger;").index());
		JvmEmitHelper.normalizeBigInteger(ctx);
	}

	static void compileLognot(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "not", "()Ljava/math/BigInteger;").index());
		JvmEmitHelper.normalizeBigInteger(ctx);
	}

	static void compileAsh(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "shiftLeft", "(I)Ljava/math/BigInteger;").index());
		JvmEmitHelper.normalizeBigInteger(ctx);
	}

	static void compileIntegerLength(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "bitLength", "()I").index());
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
	}

	static void compileLogbitp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// (logbitp index integer): the integer is the BigInteger receiver, the index the
		// arg.
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "testBit", "(I)Z").index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
