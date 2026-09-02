package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles arithmetic operations ({@code +}, {@code -}, {@code *}, {@code /},
 * {@code mod}, {@code rem}). All of them fold through the rational runtime helpers, which
 * keep an i31 fast path and fall back to exact ratio (or, for a float operand, f64)
 * arithmetic; {@code mod} / {@code rem} go through {@link #compileModRem}.
 */
final class WasmArithCompiler {

	private WasmArithCompiler() {
	}

	/**
	 * Compiles binary {@code mod} / {@code rem}. Both operands are compiled normally
	 * (each boxed as i31 / ratio / {@code TYPE_FLOAT}) and the runtime helper
	 * {@code ratioFunc} ({@code FUNC_RAT_MOD} or {@code FUNC_RAT_REM}) dispatches on
	 * their type, so a float reaching {@code mod} / {@code rem} through a variable is
	 * handled. Unlike {@code + - * /} this does not special-case a literal float operand
	 * -- there is no single {@code f64} opcode for modulo, so the runtime helper computes
	 * {@code a - b*(floor|trunc)(a/b)} in every case.
	 */
	static void compileModRem(LispCons cons, WasmLispCompiler.Ctx ctx, int ratioFunc) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(ratioFunc);
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int f64Opcode, int ratioFunc) {
		List<LispVal> args = cons.toList();
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			// Unary (/ x) is the reciprocal: 1.0 / x.
			if (args.size() == 2 && ratioFunc == WasmLispCompiler.FUNC_RAT_DIV) {
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(1.0);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
				ctx.writer.write(f64Opcode);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
				return;
			}
			// Unary (- x) is IEEE negation: f64.neg. (Falling through to the loop
			// below would return x unchanged, and 0 - x would turn -0.0 into +0.0.)
			if (args.size() == 2 && ratioFunc == WasmLispCompiler.FUNC_RAT_SUB) {
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
				ctx.writer.write(Instruction.F64_NEG);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
				return;
			}
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			for (int i = 2; i < args.size(); i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
				ctx.writer.write(f64Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			return;
		}
		// Common Lisp unary forms: (- x) negates, (/ x) is the reciprocal.
		if (args.size() == 2
				&& (ratioFunc == WasmLispCompiler.FUNC_RAT_SUB || ratioFunc == WasmLispCompiler.FUNC_RAT_DIV)) {
			if (ratioFunc == WasmLispCompiler.FUNC_RAT_SUB) {
				// Negation of a float is f64.neg -- a sign-bit flip -- even when no
				// literal in the argument form says the operand is one. The
				// _rat_sub(0, x) fold below is 0.0 - 0.0 = +0.0 for a +0.0 operand,
				// where IEEE negation (and the interpreter, and the JVM) gives -0.0.
				compileUnaryNegate(args.get(1), ctx);
				return;
			}
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ratioFunc);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		for (int i = 2; i < args.size(); i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ratioFunc);
		}
	}

	/**
	 * Emits unary {@code (- x)} for an argument whose form carries no float literal:
	 * {@code f64.neg} on the branch where the operand has been established to be a
	 * {@code TYPE_FLOAT}, and {@code _rat_sub(0, x)} on every other.
	 * <p>
	 * The float branch is not an optimisation. {@code _rat_sub(0, x)} computes
	 * {@code 0.0 - x}, which is {@code +0.0} for both zeroes, while negation flips the
	 * sign bit -- so it is the only spelling that agrees with {@code -x} on the
	 * interpreter and the JVM for a signed zero arriving through a variable.
	 */
	private static void compileUnaryNegate(LispVal arg, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(arg, ctx);
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.F64_NEG);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_SUB);
		ctx.writer.write(Instruction.END);
	}

}
