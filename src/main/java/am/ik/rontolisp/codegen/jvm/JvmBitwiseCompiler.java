package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the bitwise integer built-ins ({@code logand}, {@code logior}, {@code logxor},
 * {@code lognot}, {@code ash}, {@code integer-length}, {@code logbitp}) into calls to the
 * matching {@link JvmNumericRuntimeBuilder} helper. Each helper answers with the
 * {@code long} opcode when every operand is a {@code Long} and falls back to the exact
 * {@code BigInteger} operation otherwise, so the result is never truncated. Going through
 * a helper rather than inlining {@code BigInteger} calls at the call site is what keeps a
 * {@code (unsigned-byte 32)} loop -- SHA-256's {@code rol32}/{@code mod32+}, every
 * {@code ldb} mask -- off the boxing path it used to pay unconditionally.
 */
final class JvmBitwiseCompiler {

	private JvmBitwiseCompiler() {
	}

	static void compileLogand(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileBinary(cons, ctx, className, JvmNumericRuntimeBuilder.LOGAND);
	}

	static void compileLogior(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileBinary(cons, ctx, className, JvmNumericRuntimeBuilder.LOGIOR);
	}

	static void compileLogxor(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileBinary(cons, ctx, className, JvmNumericRuntimeBuilder.LOGXOR);
	}

	private static void compileBinary(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String op) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(op).index());
	}

	static void compileLognot(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.LOGNOT).index());
	}

	static void compileAsh(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.ASH).index());
	}

	static void compileIntegerLength(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.INTEGER_LENGTH).index());
	}

	static void compileLogbitp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// (logbitp index integer): the helper takes the integer first, the index second.
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.LOGBITP).index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
