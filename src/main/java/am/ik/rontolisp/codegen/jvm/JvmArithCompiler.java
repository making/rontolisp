package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.Opcode;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.ArithmeticIdentities;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles arithmetic operations ({@code +}, {@code -}, {@code *}, {@code /},
 * {@code mod}). The integer path is dispatched to the numeric runtime helpers, which keep
 * values as {@code Long} and promote to {@code BigInteger} on overflow.
 */
final class JvmArithCompiler {

	private JvmArithCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String opKey, Opcode doubleOpcode, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() == 1) {
			// (+) is 0 and (*) is 1, the identities (CLHS 12.2); nothing else takes no
			// argument.
			JvmExprCompiler.compileExpr(ArithmeticIdentities.of(cons), ctx, className);
			return;
		}
		LispVal checked = args.size() == 2 ? ArithmeticIdentities.oneArgument(cons) : null;
		if (checked != null) {
			JvmExprCompiler.compileExpr(checked, ctx, className);
			return;
		}
		if (isComplexCapable(opKey) && JvmLispCompiler.hasComplexOperand(args)) {
			compileComplex(args, ctx, opKey, className);
			return;
		}
		boolean unaryDiv = JvmNumericRuntimeBuilder.DIV.equals(opKey) && args.size() == 2;
		if (JvmLispCompiler.hasDoubleLiteral(args, ctx)) {
			compileUnboxed(args, ctx, opKey, doubleOpcode, className);
			JvmEmitHelper.boxDouble(ctx);
			return;
		}
		// Unary (/ x) is the reciprocal: _div(1, x).
		if (unaryDiv) {
			JvmEmitHelper.compileLong(1, ctx);
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.DIV).entry());
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// Unary subtraction is negation; the other operators leave a single argument
		// as-is.
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && args.size() == 2) {
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.NEG).entry());
			return;
		}
		for (int i = 2; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			ctx.body.invokestatic(ctx.numOp(opKey).entry());
		}
	}

	/**
	 * Whether the operator has a complex-capable twin ({@code _cadd} and friends):
	 * {@code mod} and {@code rem} stay real-only, signalling through the existing funnels
	 * for a complex operand like the interpreter.
	 */
	private static boolean isComplexCapable(String opKey) {
		return JvmNumericRuntimeBuilder.ADD.equals(opKey) || JvmNumericRuntimeBuilder.SUB.equals(opKey)
				|| JvmNumericRuntimeBuilder.MUL.equals(opKey) || JvmNumericRuntimeBuilder.DIV.equals(opKey);
	}

	/**
	 * The complex fold: the same left fold as the object path, but through the gated
	 * {@code _c*} twins. Unary {@code -} negates from zero and unary {@code /} takes the
	 * reciprocal from one, mirroring the real shapes.
	 */
	private static void compileComplex(List<LispVal> args, JvmLispCompiler.Ctx ctx, String opKey, String className) {
		String complexOp = JvmNumericRuntimeBuilder.ADD.equals(opKey) ? JvmComplexRuntimeBuilder.ADD
				: JvmNumericRuntimeBuilder.SUB.equals(opKey) ? JvmComplexRuntimeBuilder.SUB
						: JvmNumericRuntimeBuilder.MUL.equals(opKey) ? JvmComplexRuntimeBuilder.MUL
								: JvmComplexRuntimeBuilder.DIV;
		if (JvmNumericRuntimeBuilder.DIV.equals(opKey) && args.size() == 2) {
			JvmEmitHelper.compileLong(1, ctx);
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, complexOp).entry());
			return;
		}
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && args.size() == 2) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.NEG).entry());
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		for (int i = 2; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, complexOp).entry());
		}
	}

	/**
	 * The double-literal path, leaving a RAW {@code double} on the stack: the operands
	 * unbox once, the left fold runs as IEEE machine arithmetic, and only the caller
	 * boxes. Split out so a nested operand that is itself on this path
	 * ({@link #compileUnboxedOperand}) folds straight into the same expression instead of
	 * boxing at every interior node.
	 */
	private static void compileUnboxed(List<LispVal> args, JvmLispCompiler.Ctx ctx, String opKey, Opcode doubleOpcode,
			String className) {
		boolean isMod = JvmNumericRuntimeBuilder.MOD.equals(opKey);
		boolean isRem = JvmNumericRuntimeBuilder.REM.equals(opKey);
		// Unary (/ x) is the reciprocal: 1.0 / x.
		if (JvmNumericRuntimeBuilder.DIV.equals(opKey) && args.size() == 2) {
			ctx.body.dconst_1();
			compileUnboxedOperand(args.get(1), ctx, className);
			emitDoubleOp(ctx, doubleOpcode);
			return;
		}
		// Unary (- x) is IEEE negation: DNEG. (Falling through to the loop below
		// would return x unchanged, and 0 - x would turn -0.0 into +0.0.)
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && args.size() == 2) {
			compileUnboxedOperand(args.get(1), ctx, className);
			ctx.body.dneg();
			return;
		}
		compileUnboxedOperand(args.get(1), ctx, className);
		for (int i = 2; i < args.size(); i++) {
			compileUnboxedOperand(args.get(i), ctx, className);
			if (isMod || isRem) {
				// Common Lisp float modulo (sign of the divisor) and remainder, neither
				// of which is a bare DREM: _fmod corrects the sign of a nonzero result
				// to the divisor's, and both take CLHS's sign for a ZERO result from
				// _frem rather than IEEE fmod's sign-of-the-dividend.
				ctx.body.invokestatic(
						ctx.numOp(isMod ? JvmNumericRuntimeBuilder.FMOD : JvmNumericRuntimeBuilder.FREM).entry());
			}
			else {
				emitDoubleOp(ctx, doubleOpcode);
			}
		}
	}

	/**
	 * The machine form of a double-literal operation's step.
	 * @param ctx the compile context
	 * @param doubleOpcode {@code DADD}, {@code DSUB}, {@code DMUL} or {@code DDIV}
	 */
	private static void emitDoubleOp(JvmLispCompiler.Ctx ctx, Opcode doubleOpcode) {
		switch (doubleOpcode) {
			case Opcode.DADD -> ctx.body.dadd();
			case Opcode.DSUB -> ctx.body.dsub();
			case Opcode.DMUL -> ctx.body.dmul();
			case Opcode.DDIV -> ctx.body.ddiv();
			default -> throw new IllegalArgumentException("no double operation: " + doubleOpcode);
		}
	}

	/**
	 * One operand of a double-literal operation, as a raw {@code double}. A numeric
	 * literal pushes its constant directly ({@code _dbl} of a {@code Long} is exactly the
	 * widening this does), and an arithmetic operand that would ITSELF take the
	 * double-literal path emits inline -- both are the same IEEE value the boxed emission
	 * produced, without the {@code Double.valueOf} / {@code _dbl} round trip that stood
	 * between every pair of interior nodes. Anything else compiles as an ordinary
	 * expression and unboxes, which is what keeps ratios, bignums and error shapes
	 * unchanged.
	 */
	static void compileUnboxedOperand(LispVal arg, JvmLispCompiler.Ctx ctx, String className) {
		if (arg instanceof LispDouble d) {
			JvmEmitHelper.emitRawDouble(d.value(), ctx);
			return;
		}
		if (arg instanceof LispInteger i) {
			JvmEmitHelper.emitRawDouble(i.value(), ctx);
			return;
		}
		// A declared-float variable (.kb/declarations-type-checks.md): a raw double
		// local pushes its slot directly; a declared name still held boxed (a parameter,
		// a captured or outer binding) reads through the strict cast -- a true
		// declaration makes both exactly the Double the generic path saw, and a false
		// one is a deterministic ClassCastException here, never a coerced value.
		if (arg instanceof LispSymbol sym) {
			Integer rawDoubleSlot = ctx.rawDoubleLocals.get(sym.name());
			if (rawDoubleSlot != null) {
				ctx.body.dload(rawDoubleSlot);
				return;
			}
			if (ctx.declaredDoubles.contains(sym.name())) {
				JvmExprCompiler.compileExpr(arg, ctx, className);
				JvmEmitHelper.unboxDeclaredDouble(ctx);
				return;
			}
		}
		if (arg instanceof LispCons nested && nested.isProperList() && nested.car() instanceof LispSymbol head) {
			// The heads JvmExprCompiler routes here, with the (helper, opcode) pair it
			// routes them with -- so an inlined operand compiles to exactly what the
			// boxed emission of the same node would have computed.
			String opKey = switch (head.name()) {
				case LispNames.ADD -> JvmNumericRuntimeBuilder.ADD;
				case LispNames.SUB -> JvmNumericRuntimeBuilder.SUB;
				case LispNames.MUL -> JvmNumericRuntimeBuilder.MUL;
				case LispNames.DIV -> JvmNumericRuntimeBuilder.DIV;
				case LispNames.MOD -> JvmNumericRuntimeBuilder.MOD;
				case LispNames.REM -> JvmNumericRuntimeBuilder.REM;
				default -> null;
			};
			List<LispVal> parts = nested.toList();
			if (opKey != null && parts.size() >= 2 && JvmLispCompiler.hasDoubleLiteral(parts, ctx)) {
				Opcode doubleOpcode = switch (head.name()) {
					case LispNames.ADD -> Opcode.DADD;
					case LispNames.SUB -> Opcode.DSUB;
					case LispNames.MUL -> Opcode.DMUL;
					case LispNames.DIV -> Opcode.DDIV;
					default -> Opcode.DREM;
				};
				compileUnboxed(parts, ctx, opKey, doubleOpcode, className);
				return;
			}
		}
		JvmExprCompiler.compileExpr(arg, ctx, className);
		JvmEmitHelper.unboxDouble(ctx);
	}

}
