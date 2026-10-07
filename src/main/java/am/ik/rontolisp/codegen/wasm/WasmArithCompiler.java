package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.ArithmeticIdentities;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.rontolisp.compiler.FloatFold;
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
		WasmOperandTypes.emitCall(ctx, ratioFunc);
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int f64Opcode, int ratioFunc) {
		List<LispVal> args = cons.toList();
		if (args.size() == 1) {
			// (+) is 0 and (*) is 1, the identities (CLHS 12.2); nothing else takes no
			// argument.
			WasmExprCompiler.compileExpr(ArithmeticIdentities.of(cons), ctx);
			return;
		}
		LispVal checked = args.size() == 2 ? ArithmeticIdentities.oneArgument(cons) : null;
		if (checked != null) {
			WasmExprCompiler.compileExpr(checked, ctx);
			return;
		}
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			List<LispVal> operands = args.subList(1, args.size());
			int prefix = exactPrefix(operands);
			if (prefix < operands.size()) {
				compileFloat(operands, prefix, ctx, f64Opcode, ratioFunc);
				return;
			}
			if (operands.size() > 1) {
				// No operand is proven a float: every step may be exact, so the generic
				// helpers fold the operation, a complex included.
				compileFold(operands, prefix, ctx, f64Opcode, ratioFunc);
				return;
			}
			// A lone operand not proven a float may be exact: the negation and the
			// reciprocal below answer it as the generic helpers do.
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
			WasmOperandTypes.emitCall(ctx, ratioFunc);
			return;
		}
		List<LispVal> operands = args.subList(1, args.size());
		compileFold(operands, operands.size(), ctx, f64Opcode, ratioFunc);
	}

	/**
	 * A float site with an operand proven a float ({@link #exactPrefix} below the operand
	 * count): the reciprocal and the negation as their {@code f64} operations, every
	 * other arity the fold, guarded where an operand may hold a complex.
	 * @param operands the operand forms, in source order
	 * @param prefix the exact prefix's length
	 * @param ctx the compile context
	 * @param f64Opcode the operator's {@code f64} instruction
	 * @param ratioFunc the operator's generic helper
	 */
	private static void compileFloat(List<LispVal> operands, int prefix, WasmLispCompiler.Ctx ctx, int f64Opcode,
			int ratioFunc) {
		if (WasmFloatOperands.guards(operands, ctx)) {
			compileGuarded(operands, prefix, ctx, f64Opcode, ratioFunc);
			return;
		}
		// Unary (/ x) is the reciprocal: 1.0 / x.
		if (operands.size() == 1 && ratioFunc == WasmLispCompiler.FUNC_RAT_DIV) {
			ctx.writer.write(Instruction.F64_CONST);
			ctx.writer.writeF64(1.0);
			WasmExprCompiler.compileExpr(operands.get(0), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(f64Opcode);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			return;
		}
		// Unary (- x) is IEEE negation: f64.neg. (Falling through to the loop
		// below would return x unchanged, and 0 - x would turn -0.0 into +0.0.)
		if (operands.size() == 1 && ratioFunc == WasmLispCompiler.FUNC_RAT_SUB) {
			WasmExprCompiler.compileExpr(operands.get(0), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(Instruction.F64_NEG);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			return;
		}
		compileFold(operands, prefix, ctx, f64Opcode, ratioFunc);
	}

	/**
	 * A generic n-ary fold whose every operand is a boxed value -- the complex helpers'
	 * included -- its steps held back as {@link #compileOperands} holds them.
	 * @param operands the operand forms, in source order
	 * @param dividing whether the operator divides
	 * @param ctx the compile context
	 * @param stepFunc the helper each step calls
	 */
	static void compileGenericFold(List<LispVal> operands, boolean dividing, WasmLispCompiler.Ctx ctx, int stepFunc) {
		compileOperands(operands, operands.size(), i -> FloatFold.stepMayFail(operands, i, dividing), ctx, i -> {
			if (i > 0) {
				WasmOperandTypes.emitCall(ctx, stepFunc);
			}
		});
	}

	/**
	 * The length of a float site's exact prefix ({@link FloatFold#exactPrefix}): the
	 * operands ahead of the first one proven a float, folded through the generic
	 * {@code _rat_*} helpers before the {@code f64} fold, or {@code 0} when the
	 * {@code f64} fold runs from the first operand.
	 * @param operands the operand forms
	 * @return the prefix length
	 */
	static int exactPrefix(List<LispVal> operands) {
		return FloatFold.exactPrefix(operands, WasmLispCompiler::isDefinitelyDouble);
	}

	/**
	 * A float site's fold, one pair at a time from the first operand: the first
	 * {@code prefix} operands boxed through the generic {@code _rat_*} helpers, the rest
	 * as {@code f64}s. The step that joins the prefix to the {@code f64} operands is the
	 * {@code _rat_*_f64} one, which converts the exact step's value or is the {@code f64}
	 * step itself when an operand is a float; a prefix that is the whole site leaves the
	 * generic helpers' value as it is. Otherwise the value is boxed.
	 * @param operands the operand forms, at least two
	 * @param prefix the exact prefix's length ({@link #exactPrefix})
	 * @param ctx the compile context
	 * @param f64Opcode the operator's {@code f64} instruction
	 * @param ratioFunc the operator's generic helper
	 */
	private static void compileFold(List<LispVal> operands, int prefix, WasmLispCompiler.Ctx ctx, int f64Opcode,
			int ratioFunc) {
		boolean f64Fold = prefix < operands.size();
		boolean dividing = ratioFunc == WasmLispCompiler.FUNC_RAT_DIV;
		compileOperands(operands, prefix, i -> FloatFold.stepMayFail(operands, i, dividing), ctx, i -> {
			if (i == 0) {
				return;
			}
			if (i >= prefix) {
				ctx.writer.write(f64Opcode);
			}
			else {
				WasmOperandTypes.emitCall(ctx, i == prefix - 1 && f64Fold ? stepF64(ratioFunc) : ratioFunc);
			}
		});
		if (f64Fold) {
			WasmEmitHelper.boxF64(ctx);
		}
	}

	/** The {@code _rat_*_f64} step of a generic helper. */
	private static int stepF64(int ratioFunc) {
		if (ratioFunc == WasmLispCompiler.FUNC_RAT_ADD) {
			return WasmLispCompiler.FUNC_RAT_ADD_F64;
		}
		if (ratioFunc == WasmLispCompiler.FUNC_RAT_SUB) {
			return WasmLispCompiler.FUNC_RAT_SUB_F64;
		}
		return ratioFunc == WasmLispCompiler.FUNC_RAT_MUL ? WasmLispCompiler.FUNC_RAT_MUL_F64
				: WasmLispCompiler.FUNC_RAT_DIV_F64;
	}

	/**
	 * Pushes the operands of a float-literal operation one after the other -- the first
	 * {@code boxed} as values for the generic helpers, the rest as {@code f64}s --
	 * running {@code step} with each operand's index once it is on the stack. Every
	 * operand is evaluated where the interpreter evaluates it -- an inner operation is a
	 * call like any other, applied where it stands -- and no action that can signal (a
	 * conversion, a generic step) runs before a later operand whose evaluation could be
	 * observed has run: an operation applies to its arguments only once they are all
	 * evaluated (`.kb/argument-evaluation-order.md`, "An operation applies after its
	 * operands"). Only an operand whose action can fail and that has such an operand
	 * after it waits, in a temporary, with the operands up to that later one
	 * ({@link FloatFold#waiting}); every other operand is acted on where it stands, so an
	 * operation without such a pair emits what it did before.
	 * @param operands the operand forms, in source order
	 * @param boxed how many leading operands are pushed boxed
	 * @param stepMayFail whether the step after boxed operand {@code i} may signal
	 * @param ctx the compile context
	 * @param step what follows each operand on the stack: the fold step, or nothing
	 */
	private static void compileOperands(List<LispVal> operands, int boxed, IntPredicate stepMayFail,
			WasmLispCompiler.Ctx ctx, IntConsumer step) {
		int count = operands.size();
		FloatFold.Waiting waiting = FloatFold.waiting(count,
				i -> !ArgumentOrder.isQuiet(operands.get(i), name -> isQuietVariable(name, ctx)),
				i -> i < boxed ? i > 0 && stepMayFail.test(i) : !ArgumentOrder.isRealValued(operands.get(i)));
		int[] slots = new int[count];
		for (int i = 0; i < count; i++) {
			if (!waiting.waits(i)) {
				WasmExprCompiler.compileExpr(operands.get(i), ctx);
				if (i >= boxed) {
					WasmEmitHelper.castFloatGetF64(ctx);
				}
				step.accept(i);
				continue;
			}
			// From the first operand that has to wait up to the last observable one: each
			// is evaluated in order into a temporary (a constant is left where it
			// stands), and acted on only once the last of them has run.
			slots[i] = -1;
			if (!ArgumentOrder.isOrderIndependent(operands.get(i))) {
				WasmExprCompiler.compileExpr(operands.get(i), ctx);
				slots[i] = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slots[i]);
			}
			if (i < waiting.last()) {
				continue;
			}
			for (int j = waiting.first(); j <= waiting.last(); j++) {
				if (slots[j] < 0) {
					WasmExprCompiler.compileExpr(operands.get(j), ctx);
				}
				else {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(slots[j]);
				}
				if (j >= boxed) {
					WasmEmitHelper.castFloatGetF64(ctx);
				}
				step.accept(j);
			}
		}
	}

	/**
	 * Whether a read of the variable can neither fail nor change anything where the site
	 * is compiled: a lexical variable, a special this function binds, or a global whose
	 * read tests for no UNBOUND marker ({@code WasmExprCompiler.emitCheckedRead}) and is
	 * not read through a task's dynamic bindings.
	 */
	static boolean isQuietVariable(String name, WasmLispCompiler.Ctx ctx) {
		if (ctx.boundSpecials.contains(name)) {
			return true;
		}
		if (ctx.locals.containsKey(name) || ctx.captures.containsKey(name) || ctx.rawLocals.containsKey(name)) {
			return true;
		}
		return ctx.globalIndices.containsKey(name) && !ctx.dynamic && !ctx.unboundGlobals.contains(name)
				&& !WasmDynVars.handles(ctx, name);
	}

	/**
	 * The float-literal path in a module whose program may observe a complex, over
	 * operands one of which may hold one ({@link WasmFloatOperands}): every operand is
	 * evaluated first, then the {@code f64} fold runs when none is a complex and the
	 * generic {@code _rat_*} fold, whose arms answer it, when one is.
	 */
	private static void compileGuarded(List<LispVal> operandForms, int prefix, WasmLispCompiler.Ctx ctx, int f64Opcode,
			int ratioFunc) {
		WasmFloatOperands.Operands operands = WasmFloatOperands.evaluate(operandForms, ctx);
		int count = operands.size();
		operands.emitHoldsComplex(ctx);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		if (count == 1 && ratioFunc == WasmLispCompiler.FUNC_RAT_DIV) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			operands.pushBoxed(0, ctx);
			WasmOperandTypes.emitCall(ctx, ratioFunc);
		}
		else if (count == 1 && ratioFunc == WasmLispCompiler.FUNC_RAT_SUB) {
			// The one operand IS the complex here: negated part by part, as
			// compileUnaryNegate negates one.
			operands.pushBoxed(0, ctx);
			WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_C_NEG);
		}
		else {
			operands.pushBoxed(0, ctx);
			for (int i = 1; i < count; i++) {
				operands.pushBoxed(i, ctx);
				WasmOperandTypes.emitCall(ctx, ratioFunc);
			}
		}
		ctx.writer.write(Instruction.ELSE);
		if (count == 1 && ratioFunc == WasmLispCompiler.FUNC_RAT_DIV) {
			ctx.writer.write(Instruction.F64_CONST);
			ctx.writer.writeF64(1.0);
			operands.pushRaw(0, ctx);
			ctx.writer.write(f64Opcode);
		}
		else if (count == 1 && ratioFunc == WasmLispCompiler.FUNC_RAT_SUB) {
			operands.pushRaw(0, ctx);
			ctx.writer.write(Instruction.F64_NEG);
		}
		else {
			// The exact prefix through the generic helpers, joined to the f64 operands by
			// the _rat_*_f64 step (compileFold).
			if (prefix > 0) {
				operands.pushBoxed(0, ctx);
				for (int i = 1; i < prefix; i++) {
					operands.pushBoxed(i, ctx);
					WasmOperandTypes.emitCall(ctx, i == prefix - 1 ? stepF64(ratioFunc) : ratioFunc);
				}
			}
			else {
				operands.pushRaw(0, ctx);
			}
			for (int i = Math.max(prefix, 1); i < count; i++) {
				operands.pushRaw(i, ctx);
				ctx.writer.write(f64Opcode);
			}
		}
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
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
		if (ctx.complexBlock != null) {
			// A program that may observe a complex: one arriving here negates through
			// _c_neg, part by part -- not through the _rat_sub below, whose 0 - x would
			// turn a -0.0 part into +0.0, as it would for a real one.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_C_NEG);
			ctx.writer.write(Instruction.ELSE);
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_RAT_SUB);
		if (ctx.complexBlock != null) {
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.END);
	}

}
