package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.Opcode;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.ArithmeticIdentities;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.rontolisp.compiler.FloatFold;
import org.jspecify.annotations.Nullable;

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
			List<LispVal> operands = args.subList(1, args.size());
			if (exactPrefix(operands, ctx) == operands.size()) {
				// No operand is proven a float: every step may be exact, so the
				// generic helpers fold the operation, a complex included.
				compileFold(operands, operands.size(), opKey, doubleOpcode, false, ctx, className);
				return;
			}
			if (JvmFloatOperands.guards(operands, ctx)) {
				// An operand may hold a complex the form does not spell: the raw fold
				// runs only when none does (`.kb/jvm-complex.md`).
				JvmFloatOperands.compileArithmetic(operands, opKey, ctx, className);
				return;
			}
			compileUnboxed(args, ctx, opKey, doubleOpcode, className);
			JvmEmitHelper.boxDouble(ctx);
			return;
		}
		// Unary (/ x) is the reciprocal: _div(1, x).
		if (unaryDiv) {
			JvmEmitHelper.compileLong(1, ctx);
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.DIV));
			return;
		}
		// Unary subtraction is negation; the other operators leave a single argument
		// as-is.
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && args.size() == 2) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.NEG));
			return;
		}
		List<LispVal> operands = args.subList(1, args.size());
		compileFold(operands, operands.size(), opKey, doubleOpcode, false, ctx, className);
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
	 * The complex fold: the same left fold as the object path, its steps held back the
	 * same way ({@link #compileOperands}), but through the gated {@code _c*} twins. Unary
	 * {@code -} negates from zero and unary {@code /} takes the reciprocal from one,
	 * mirroring the real shapes.
	 */
	private static void compileComplex(List<LispVal> args, JvmLispCompiler.Ctx ctx, String opKey, String className) {
		String complexOp = JvmNumericRuntimeBuilder.ADD.equals(opKey) ? JvmComplexRuntimeBuilder.ADD
				: JvmNumericRuntimeBuilder.SUB.equals(opKey) ? JvmComplexRuntimeBuilder.SUB
						: JvmNumericRuntimeBuilder.MUL.equals(opKey) ? JvmComplexRuntimeBuilder.MUL
								: JvmComplexRuntimeBuilder.DIV;
		if (JvmNumericRuntimeBuilder.DIV.equals(opKey) && args.size() == 2) {
			JvmEmitHelper.compileLong(1, ctx);
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, complexOp));
			return;
		}
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && args.size() == 2) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.NEG));
			return;
		}
		List<LispVal> operands = args.subList(1, args.size());
		boolean dividing = JvmNumericRuntimeBuilder.DIV.equals(opKey);
		compileOperands(operands, operands.size(), i -> FloatFold.stepMayFail(operands, i, dividing), ctx, className,
				i -> {
					if (i > 0) {
						ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, complexOp));
					}
				});
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
		List<LispVal> operands = args.subList(1, args.size());
		compileFold(operands, exactPrefix(operands, ctx), opKey, doubleOpcode, true, ctx, className);
	}

	/**
	 * The length of a float site's exact prefix ({@link FloatFold#exactPrefix}): the
	 * operands ahead of the first one proven a float, folded through the generic helpers
	 * before the raw fold, or {@code 0} when the raw fold runs from the first operand.
	 * Only {@code + - * /} fold more than two operands; {@code mod} and {@code rem} have
	 * a prefix exactly when neither operand is proven a float.
	 * @param operands the operand forms
	 * @param ctx the compile context
	 * @return the prefix length
	 */
	static int exactPrefix(List<LispVal> operands, JvmLispCompiler.Ctx ctx) {
		return FloatFold.exactPrefix(operands, operand -> JvmLispCompiler.isDefinitelyDouble(operand, ctx));
	}

	/**
	 * A float site's fold, one pair at a time from the first operand: the first
	 * {@code prefix} operands boxed through the generic helpers, the rest raw. The step
	 * that joins the prefix to the raw operands (or ends a prefix whose value is wanted
	 * raw) is the {@code _addd} family's, which converts the exact step's value or is the
	 * raw step itself when an operand is a float; a prefix that is the whole site and is
	 * wanted boxed leaves the generic helpers' value as it is.
	 * @param operands the operand forms, at least two
	 * @param prefix the exact prefix's length ({@link #exactPrefix})
	 * @param opKey the operator's helper key
	 * @param doubleOpcode the operator's double opcode
	 * @param raw whether the value is left as a raw double rather than boxed
	 * @param ctx the compile context
	 * @param className the class being emitted
	 */
	private static void compileFold(List<LispVal> operands, int prefix, String opKey, Opcode doubleOpcode, boolean raw,
			JvmLispCompiler.Ctx ctx, String className) {
		boolean isMod = JvmNumericRuntimeBuilder.MOD.equals(opKey);
		boolean isRem = JvmNumericRuntimeBuilder.REM.equals(opKey);
		boolean rawFold = raw || prefix < operands.size();
		@Nullable String toDouble = rawFold ? toDoubleKey(opKey) : null;
		boolean dividing = isMod || isRem || JvmNumericRuntimeBuilder.DIV.equals(opKey);
		compileOperands(operands, prefix, i -> FloatFold.stepMayFail(operands, i, dividing), ctx, className, i -> {
			if (i == 0) {
				return;
			}
			if (i < prefix) {
				if (i == prefix - 1 && toDouble != null) {
					ctx.body.invokestatic(ctx.numOp(toDouble));
					return;
				}
				ctx.body.invokestatic(ctx.numOp(opKey));
				if (i == prefix - 1 && rawFold) {
					// mod and rem: a binary site, so this is its value.
					JvmEmitHelper.unboxDouble(ctx);
				}
				return;
			}
			if (isMod || isRem) {
				// Common Lisp float modulo (sign of the divisor) and remainder, neither
				// of which is a bare DREM: _fmod corrects the sign of a nonzero result
				// to the divisor's, and both take CLHS's sign for a ZERO result from
				// _frem rather than IEEE fmod's sign-of-the-dividend.
				ctx.body.invokestatic(ctx.numOp(isMod ? JvmNumericRuntimeBuilder.FMOD : JvmNumericRuntimeBuilder.FREM));
			}
			else {
				emitDoubleOp(ctx, doubleOpcode);
			}
		});
		if (rawFold && !raw) {
			JvmEmitHelper.boxDouble(ctx);
		}
	}

	/**
	 * The {@code _addd}-family helper of an operator, or null for {@code mod} and
	 * {@code rem}, whose generic value converts through {@code _dbl}.
	 */
	static @Nullable String toDoubleKey(String opKey) {
		return switch (opKey) {
			case JvmNumericRuntimeBuilder.ADD -> JvmNumericRuntimeBuilder.ADD_TO_DOUBLE;
			case JvmNumericRuntimeBuilder.SUB -> JvmNumericRuntimeBuilder.SUB_TO_DOUBLE;
			case JvmNumericRuntimeBuilder.MUL -> JvmNumericRuntimeBuilder.MUL_TO_DOUBLE;
			case JvmNumericRuntimeBuilder.DIV -> JvmNumericRuntimeBuilder.DIV_TO_DOUBLE;
			default -> null;
		};
	}

	/**
	 * Pushes the operands of a float site as raw doubles, one after the other, running
	 * {@code step} with each operand's index once it is on the stack
	 * ({@link #compileOperands} with no boxed operand).
	 * @param operands the operand forms, in source order
	 * @param ctx the compile context
	 * @param className the class being emitted
	 * @param step what follows each operand on the stack: the fold step, or nothing
	 */
	static void compileUnboxedOperands(List<LispVal> operands, JvmLispCompiler.Ctx ctx, String className,
			IntConsumer step) {
		compileOperands(operands, 0, i -> false, ctx, className, step);
	}

	/**
	 * Pushes the operands of a float site one after the other -- the first {@code boxed}
	 * as values for the generic helpers, the rest as raw doubles -- running {@code step}
	 * with each operand's index once it is on the stack. Every operand is evaluated where
	 * the interpreter evaluates it -- an inner float-literal operation applied where it
	 * stands -- and no action that can signal (a conversion, a generic step) runs before
	 * a later operand whose evaluation could be observed has run: an operation applies to
	 * its arguments only once they are all evaluated (`.kb/argument-evaluation-order.md`,
	 * "An operation applies after its operands"). Only an operand whose action can fail
	 * and that has such an operand after it waits, in a temporary, with the operands up
	 * to that later one ({@link FloatFold#waiting}); every other operand is acted on
	 * where it stands, so a site without such a pair emits the instructions it did
	 * before; a temporary costs no machine work once compiled.
	 * @param operands the operand forms, in source order
	 * @param boxed how many leading operands are pushed boxed
	 * @param stepMayFail whether the step after boxed operand {@code i} may signal
	 * @param ctx the compile context
	 * @param className the class being emitted
	 * @param step what follows each operand on the stack: the fold step, or nothing
	 */
	private static void compileOperands(List<LispVal> operands, int boxed, IntPredicate stepMayFail,
			JvmLispCompiler.Ctx ctx, String className, IntConsumer step) {
		int count = operands.size();
		FloatFold.Waiting waiting = FloatFold.waiting(count, i -> observable(operands.get(i), ctx),
				i -> i < boxed ? i > 0 && stepMayFail.test(i) : conversionMayFail(operands.get(i), ctx));
		int[] slots = new int[count];
		boolean[] rawSlot = new boolean[count];
		for (int i = 0; i < count; i++) {
			if (!waiting.waits(i)) {
				pushOperand(operands.get(i), i < boxed, ctx, className);
				step.accept(i);
				continue;
			}
			// From the first operand that has to wait up to the last observable one: each
			// is evaluated in order into a temporary (a constant is left where it
			// stands), and acted on only once the last of them has run.
			LispVal operand = operands.get(i);
			if (ArgumentOrder.isOrderIndependent(operand)) {
				slots[i] = -1;
			}
			else if (i >= boxed && !conversionMayFail(operand, ctx)) {
				// Converted where it stands -- a declared float, an inner float-literal
				// operation, an arithmetic call -- and held raw.
				compileUnboxedOperand(operand, ctx, className);
				slots[i] = ctx.allocTemp();
				ctx.allocTemp();
				rawSlot[i] = true;
				ctx.body.dstore(slots[i]);
			}
			else {
				JvmExprCompiler.compileExpr(operand, ctx, className);
				slots[i] = ctx.allocTemp();
				ctx.body.astore(slots[i]);
			}
			if (i < waiting.last()) {
				continue;
			}
			for (int j = waiting.first(); j <= waiting.last(); j++) {
				if (slots[j] < 0) {
					pushOperand(operands.get(j), j < boxed, ctx, className);
				}
				else if (rawSlot[j]) {
					ctx.body.dload(slots[j]);
				}
				else {
					ctx.body.aload(slots[j]);
					if (j >= boxed) {
						JvmEmitHelper.unboxDouble(ctx);
					}
				}
				step.accept(j);
			}
		}
	}

	private static void pushOperand(LispVal operand, boolean boxed, JvmLispCompiler.Ctx ctx, String className) {
		if (boxed) {
			JvmExprCompiler.compileExpr(operand, ctx, className);
		}
		else {
			compileUnboxedOperand(operand, ctx, className);
		}
	}

	/**
	 * Whether evaluating an operand of a float site can be observed by a later operand's
	 * evaluation: anything but a constant or a quiet variable's read -- and an inner
	 * float-literal operation exactly when applying it can signal, through an operand of
	 * its own.
	 */
	private static boolean observable(LispVal operand, JvmLispCompiler.Ctx ctx) {
		if (ArgumentOrder.isQuiet(operand, name -> isQuietVariable(name, ctx))) {
			return false;
		}
		if (inlinedOpKey(operand, ctx) != null) {
			LispVal operands = ((LispCons) operand).cdr();
			while (operands instanceof LispCons cell) {
				if (observable(cell.car(), ctx) || conversionMayFail(cell.car(), ctx)) {
					return true;
				}
				operands = cell.cdr();
			}
			return false;
		}
		return true;
	}

	/**
	 * Whether pushing an operand as a raw double can signal: anything whose value is not
	 * certainly a number -- a declared float and an inner float-literal operation are raw
	 * already, and an arithmetic call answers a real when it returns.
	 */
	private static boolean conversionMayFail(LispVal operand, JvmLispCompiler.Ctx ctx) {
		if (operand instanceof LispSymbol sym
				&& (ctx.rawDoubleLocals.containsKey(sym.name()) || ctx.declaredDoubles.contains(sym.name()))) {
			return false;
		}
		return inlinedOpKey(operand, ctx) == null && !ArgumentOrder.isRealValued(operand);
	}

	/**
	 * Whether a read of the variable can neither fail nor change anything where the site
	 * is compiled: a lexical variable, or a global whose read tests for no UNBOUND marker
	 * ({@code JvmExprCompiler.compileSpecialRead}) and is not dynamically bound.
	 */
	static boolean isQuietVariable(String name, JvmLispCompiler.Ctx ctx) {
		if (ctx.locals.containsKey(name) || ctx.captures.containsKey(name) || ctx.rawLocals.containsKey(name)
				|| ctx.rawDoubleLocals.containsKey(name)) {
			return true;
		}
		JvmDynVarRuntimeBuilder.UnboundMarker marker = ctx.unboundMarker;
		JvmDynVarRuntimeBuilder.DynVarRuntime dyn = ctx.dynVars;
		return ctx.globals.contains(name) && !ctx.dynamic && (marker == null || !marker.globals().contains(name))
				&& (dyn == null || !dyn.fields().containsKey(name));
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
		String opKey = inlinedOpKey(arg, ctx);
		if (opKey != null) {
			LispCons nested = (LispCons) arg;
			String operator = ((LispSymbol) nested.car()).name();
			Opcode doubleOpcode = switch (operator) {
				case LispNames.ADD -> Opcode.DADD;
				case LispNames.SUB -> Opcode.DSUB;
				case LispNames.MUL -> Opcode.DMUL;
				case LispNames.DIV -> Opcode.DDIV;
				default -> Opcode.DREM;
			};
			// The inner operation is applied here, under its own operator and source
			// site, as the interpreter applies it: a wrong-type operand of an inner *
			// reports *, not the operator it is an operand of.
			@Nullable String outerOperator = ctx.operator;
			ctx.operator = operator;
			int site = ctx.enterSite(nested);
			try {
				compileUnboxed(nested.toList(), ctx, opKey, doubleOpcode, className);
			}
			finally {
				ctx.operator = outerOperator;
				ctx.leaveSite(site);
			}
			return;
		}
		JvmExprCompiler.compileExpr(arg, ctx, className);
		JvmEmitHelper.unboxDouble(ctx);
	}

	/**
	 * The helper key of an arithmetic operand that {@link #compileUnboxedOperand} inlines
	 * as a raw double expression -- one of the heads JvmExprCompiler routes here, over a
	 * double literal -- or null for any other operand. The heads come with the (helper,
	 * opcode) pair JvmExprCompiler routes them with, so an inlined operand compiles to
	 * exactly what the boxed emission of the same node would have computed.
	 */
	static @Nullable String inlinedOpKey(LispVal arg, JvmLispCompiler.Ctx ctx) {
		if (!(arg instanceof LispCons nested && nested.isProperList() && nested.car() instanceof LispSymbol head)) {
			return null;
		}
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
		return opKey != null && parts.size() >= 2 && JvmLispCompiler.hasDoubleLiteral(parts, ctx) ? opKey : null;
	}

}
