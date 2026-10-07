package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.rontolisp.compiler.ComplexCapability;

import org.jspecify.annotations.Nullable;

/**
 * The operands of an unboxed-double site in a program that may observe a complex
 * (`.kb/jvm-complex.md`, "A complex beside a float literal"). A float literal routes
 * {@code (* 2.0 z)}, {@code (exp (* 1.0 z))} and {@code (= (* 2.0 z) 1.0)} onto raw IEEE
 * arithmetic, whose {@code _dbl} rejects a holder, so a complex the variable holds never
 * reached the helpers that answer it. Here every operation evaluates its operands the way
 * the interpreter does -- left to right, an inner operation applied where it stands --
 * into temporaries, tests the ones a variable or a call produced for a holder, and
 * applies itself raw when none is one and through the generic helpers, whose arms answer
 * the complex, when one is. An inner operation leaves its raw double, and its boxed value
 * when it went generic: nothing is evaluated twice, and the raw path boxes nothing it did
 * not box before.
 */
final class JvmFloatOperands {

	private JvmFloatOperands() {
	}

	/**
	 * Whether a site that would unbox these operand forms takes this class's emission: a
	 * program that may observe a complex, and an operand that may evaluate to one
	 * ({@link ComplexCapability#mayYieldComplex}). Every other site keeps its raw
	 * emission byte for byte.
	 * @param operands the operand forms
	 * @param ctx the compile context
	 * @return whether the site must find a complex at run time
	 */
	static boolean guards(List<LispVal> operands, JvmLispCompiler.Ctx ctx) {
		if (!ctx.usesComplex) {
			return false;
		}
		for (LispVal operand : operands) {
			if (ComplexCapability.mayYieldComplex(operand, name -> isFloatDeclared(name, ctx))) {
				return true;
			}
		}
		return false;
	}

	// A variable the emitter unboxes on the strength of a float declaration: a false one
	// is undefined behavior (.kb/declarations-type-checks.md), so it holds no complex.
	private static boolean isFloatDeclared(String name, JvmLispCompiler.Ctx ctx) {
		return ctx.rawDoubleLocals.containsKey(name) || ctx.declaredDoubles.contains(name);
	}

	/**
	 * Evaluates the operand forms left to right, each where the interpreter would: an
	 * inner float-literal operation is applied in place, every other form is evaluated
	 * into a temporary that the site's raw and generic emissions both read.
	 * @param forms the operand forms
	 * @param ctx the compile context
	 * @param className the class being emitted
	 * @return the evaluated operands
	 */
	static Operands evaluate(List<LispVal> forms, JvmLispCompiler.Ctx ctx, String className) {
		return evaluate(forms, 0, ctx, className);
	}

	/**
	 * {@link #evaluate(List, JvmLispCompiler.Ctx, String)} for a site whose first
	 * {@code boxed} operands fold through the generic helpers (its exact prefix,
	 * {@link JvmArithCompiler#exactPrefix}): an inner operation among them is evaluated
	 * as a value, exact when its operands are, rather than as a raw double.
	 */
	private static Operands evaluate(List<LispVal> forms, int boxed, JvmLispCompiler.Ctx ctx, String className) {
		List<Operand> operands = new ArrayList<>(forms.size());
		for (int i = 0; i < forms.size(); i++) {
			operands.add(evaluate(forms.get(i), i < boxed, ctx, className));
		}
		return new Operands(operands);
	}

	private static Operand evaluate(LispVal form, boolean boxed, JvmLispCompiler.Ctx ctx, String className) {
		if (form instanceof LispDouble d) {
			return new Literal(form, d.value());
		}
		if (form instanceof LispInteger i) {
			return new Literal(form, i.value());
		}
		if (form instanceof LispSymbol sym && isFloatDeclared(sym.name(), ctx)) {
			JvmArithCompiler.compileUnboxedOperand(form, ctx, className);
			int raw = allocDouble(ctx);
			ctx.body.dstore(raw);
			return new Raw(raw);
		}
		String opKey = boxed ? null : JvmArithCompiler.inlinedOpKey(form, ctx);
		if (opKey != null) {
			return compileInner((LispCons) form, opKey, ctx, className);
		}
		if (ArgumentOrder.isOrderIndependent(form)) {
			return new Constant(form);
		}
		JvmExprCompiler.compileExpr(form, ctx, className);
		int slot = ctx.allocTemp();
		ctx.body.astore(slot);
		return new Leaf(slot);
	}

	/**
	 * An inner float-literal operation of a guarded site, applied where it stands under
	 * its own operator and source site, as the interpreter applies it: raw when no
	 * operand holds a complex (then its value is a double, and a wrong-typed operand
	 * signals here), generic otherwise.
	 */
	private static Operand compileInner(LispCons form, String opKey, JvmLispCompiler.Ctx ctx, String className) {
		@Nullable String outerOperator = ctx.operator;
		ctx.operator = ((LispSymbol) form.car()).name();
		int site = ctx.enterSite(form);
		try {
			List<LispVal> parts = form.toList();
			List<LispVal> forms = parts.subList(1, parts.size());
			int prefix = JvmArithCompiler.exactPrefix(forms, ctx);
			Operands operands = evaluate(forms, prefix, ctx, className);
			int raw = allocDouble(ctx);
			if (!operands.mayHoldComplex()) {
				foldRaw(operands, prefix, opKey, ctx, className);
				ctx.body.dstore(raw);
				return new Raw(raw);
			}
			int boxed = ctx.allocTemp();
			MethodCode.Label generic = ctx.body.newLabel();
			MethodCode.Label done = ctx.body.newLabel();
			operands.jumpIfComplex(ctx, className, generic);
			foldRaw(operands, prefix, opKey, ctx, className);
			ctx.body.dstore(raw).aconst_null().astore(boxed).goto_(done);
			ctx.body.labelBinding(generic);
			foldGeneric(operands, opKey, ctx, className);
			ctx.body.astore(boxed).dconst_0().dstore(raw);
			ctx.body.labelBinding(done);
			return new Inner(raw, boxed);
		}
		finally {
			ctx.operator = outerOperator;
			ctx.leaveSite(site);
		}
	}

	/**
	 * A float-literal {@code + - * / mod rem} whose operands may hold a complex, leaving
	 * the boxed answer: the double of the raw fold when none does, the generic helpers'
	 * answer when one does.
	 * @param operandForms the operand forms
	 * @param opKey the numeric helper key of the operator
	 * @param ctx the compile context
	 * @param className the class being emitted
	 */
	static void compileArithmetic(List<LispVal> operandForms, String opKey, JvmLispCompiler.Ctx ctx, String className) {
		int prefix = JvmArithCompiler.exactPrefix(operandForms, ctx);
		Operands operands = evaluate(operandForms, prefix, ctx, className);
		branch(operands, ctx, className, () -> {
			foldRaw(operands, prefix, opKey, ctx, className);
			JvmEmitHelper.boxDouble(ctx);
		}, () -> foldGeneric(operands, opKey, ctx, className));
	}

	/**
	 * A guarded call site whose raw form is one double helper over the raw operands and
	 * whose generic form is one helper over the boxed ones: {@code min}, {@code max},
	 * {@code abs}, {@code expt}, {@code signum}.
	 * @param operandForms the operand forms
	 * @param rawOp the {@code (D..D)D} helper of the raw path, its result boxed
	 * @param genericOp the boxed helper, whose arms answer (or refuse) a holder
	 * @param ctx the compile context
	 * @param className the class being emitted
	 */
	static void compileCall(List<LispVal> operandForms, MethodRefEntry rawOp, MethodRefEntry genericOp,
			JvmLispCompiler.Ctx ctx, String className) {
		Operands operands = evaluate(operandForms, ctx, className);
		branch(operands, ctx, className, () -> {
			for (int i = 0; i < operands.size(); i++) {
				operands.pushRaw(i, ctx, className);
			}
			ctx.body.invokestatic(rawOp);
			JvmEmitHelper.boxDouble(ctx);
		}, () -> {
			for (int i = 0; i < operands.size(); i++) {
				operands.pushBoxed(i, ctx, className);
			}
			ctx.body.invokestatic(genericOp);
		});
	}

	/**
	 * The shape every guarded site shares: the raw emission when no operand holds a
	 * complex, the generic one when one does, each leaving the site's one value.
	 * @param operands the evaluated operands
	 * @param ctx the compile context
	 * @param className the class being emitted
	 * @param raw the emission over {@link Operands#pushRaw}
	 * @param generic the emission over {@link Operands#pushBoxed}
	 */
	static void branch(Operands operands, JvmLispCompiler.Ctx ctx, String className, Runnable raw, Runnable generic) {
		MethodCode.Label genericLabel = ctx.body.newLabel();
		MethodCode.Label done = ctx.body.newLabel();
		operands.jumpIfComplex(ctx, className, genericLabel);
		raw.run();
		ctx.body.goto_(done);
		ctx.body.labelBinding(genericLabel);
		generic.run();
		ctx.body.labelBinding(done);
	}

	// JvmArithCompiler's raw fold, over the evaluated operands: the reciprocal and the
	// negation as their IEEE operations, every other arity a left fold -- its exact
	// prefix through the generic helpers, joined to the raw operands by the _addd
	// family's step (JvmArithCompiler.compileFold).
	private static void foldRaw(Operands operands, int prefix, String opKey, JvmLispCompiler.Ctx ctx,
			String className) {
		int count = operands.size();
		if (JvmNumericRuntimeBuilder.DIV.equals(opKey) && count == 1) {
			ctx.body.dconst_1();
			operands.pushRaw(0, ctx, className);
			ctx.body.ddiv();
			return;
		}
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && count == 1) {
			operands.pushRaw(0, ctx, className);
			ctx.body.dneg();
			return;
		}
		if (prefix > 0) {
			operands.pushBoxed(0, ctx, className);
			for (int i = 1; i < prefix; i++) {
				operands.pushBoxed(i, ctx, className);
				String toDouble = JvmArithCompiler.toDoubleKey(opKey);
				if (i < prefix - 1) {
					ctx.body.invokestatic(ctx.numOp(opKey));
				}
				else if (toDouble != null) {
					ctx.body.invokestatic(ctx.numOp(toDouble));
				}
				else {
					ctx.body.invokestatic(ctx.numOp(opKey));
					JvmEmitHelper.unboxDouble(ctx);
				}
			}
		}
		else {
			operands.pushRaw(0, ctx, className);
		}
		for (int i = Math.max(prefix, 1); i < count; i++) {
			operands.pushRaw(i, ctx, className);
			switch (opKey) {
				case JvmNumericRuntimeBuilder.ADD -> ctx.body.dadd();
				case JvmNumericRuntimeBuilder.SUB -> ctx.body.dsub();
				case JvmNumericRuntimeBuilder.MUL -> ctx.body.dmul();
				case JvmNumericRuntimeBuilder.DIV -> ctx.body.ddiv();
				case JvmNumericRuntimeBuilder.MOD -> ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.FMOD));
				case JvmNumericRuntimeBuilder.REM -> ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.FREM));
				default -> throw new IllegalArgumentException("no float fold for " + opKey);
			}
		}
	}

	// JvmArithCompiler's generic fold, over the evaluated operands: what the operation
	// compiles to without a float literal, whose helpers answer a holder.
	private static void foldGeneric(Operands operands, String opKey, JvmLispCompiler.Ctx ctx, String className) {
		int count = operands.size();
		if (JvmNumericRuntimeBuilder.DIV.equals(opKey) && count == 1) {
			JvmEmitHelper.compileLong(1, ctx);
			operands.pushBoxed(0, ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.DIV));
			return;
		}
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && count == 1) {
			operands.pushBoxed(0, ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.NEG));
			return;
		}
		operands.pushBoxed(0, ctx, className);
		for (int i = 1; i < count; i++) {
			operands.pushBoxed(i, ctx, className);
			ctx.body.invokestatic(ctx.numOp(opKey));
		}
	}

	private static int allocDouble(JvmLispCompiler.Ctx ctx) {
		int slot = ctx.allocTemp();
		ctx.allocTemp();
		return slot;
	}

	/** One evaluated operand. */
	private sealed interface Operand permits Literal, Constant, Raw, Leaf, Inner {

	}

	/** A number literal: its double raw, the literal itself boxed. */
	private record Literal(LispVal form, double value) implements Operand {
	}

	/** Any other constant (a ratio, a string, a quote): evaluated where it is read. */
	private record Constant(LispVal form) implements Operand {
	}

	/** A double that can be nothing else, evaluated in place into {@code raw}. */
	private record Raw(int raw) implements Operand {
	}

	/** A value a variable or a call produced, held boxed in {@code slot}. */
	private record Leaf(int slot) implements Operand {
	}

	/**
	 * An inner operation's value: the double in {@code raw} while {@code boxed} is null,
	 * the generic helpers' answer in {@code boxed} otherwise.
	 */
	private record Inner(int raw, int boxed) implements Operand {
	}

	/** A site's operands, evaluated. */
	static final class Operands {

		private final List<Operand> operands;

		private Operands(List<Operand> operands) {
			this.operands = operands;
		}

		int size() {
			return this.operands.size();
		}

		// Whether an operand can be a holder at run time.
		private boolean mayHoldComplex() {
			for (Operand operand : this.operands) {
				if (operand instanceof Leaf || operand instanceof Inner) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Jumps to {@code generic} when an operand holds a complex: a leaf that is a
		 * holder (tested behind the holder-presence probe, `.kb/jvm-complex.md`), or an
		 * inner operation that went generic.
		 * @param ctx the compile context
		 * @param className the class being emitted
		 * @param generic the generic emission's label
		 */
		void jumpIfComplex(JvmLispCompiler.Ctx ctx, String className, MethodCode.Label generic) {
			MethodCode.@Nullable Label tested = null;
			for (Operand operand : this.operands) {
				if (operand instanceof Leaf leaf) {
					if (tested == null) {
						tested = ctx.body.newLabel();
						JvmComplexCompiler.emitNoHolderJump(ctx, className, tested);
					}
					ctx.body.aload(leaf.slot()).instanceOf(JvmComplexCompiler.complexClass(ctx)).ifne(generic);
				}
			}
			if (tested != null) {
				ctx.body.labelBinding(tested);
			}
			for (Operand operand : this.operands) {
				if (operand instanceof Inner inner) {
					ctx.body.aload(inner.boxed()).ifnonnull(generic);
				}
			}
		}

		/**
		 * Pushes operand {@code i} as a raw double: what {@code compileUnboxedOperand}
		 * pushes for it, a leaf through {@code _dbl} under the site's operator.
		 * @param i the operand's index
		 * @param ctx the compile context
		 * @param className the class being emitted
		 */
		void pushRaw(int i, JvmLispCompiler.Ctx ctx, String className) {
			switch (this.operands.get(i)) {
				case Literal literal -> JvmEmitHelper.emitRawDouble(literal.value(), ctx);
				case Constant constant -> {
					JvmExprCompiler.compileExpr(constant.form(), ctx, className);
					JvmEmitHelper.unboxDouble(ctx);
				}
				case Raw raw -> ctx.body.dload(raw.raw());
				case Leaf leaf -> {
					ctx.body.aload(leaf.slot());
					JvmEmitHelper.unboxDouble(ctx);
				}
				case Inner inner -> ctx.body.dload(inner.raw());
			}
		}

		/**
		 * Pushes operand {@code i} as the generic path sees it: the value boxed, a raw
		 * double through {@code Double.valueOf}.
		 * @param i the operand's index
		 * @param ctx the compile context
		 * @param className the class being emitted
		 */
		void pushBoxed(int i, JvmLispCompiler.Ctx ctx, String className) {
			switch (this.operands.get(i)) {
				case Literal literal -> JvmExprCompiler.compileExpr(literal.form(), ctx, className);
				case Constant constant -> JvmExprCompiler.compileExpr(constant.form(), ctx, className);
				case Raw raw -> {
					ctx.body.dload(raw.raw());
					JvmEmitHelper.boxDouble(ctx);
				}
				case Leaf leaf -> ctx.body.aload(leaf.slot());
				case Inner inner -> {
					MethodCode.Label boxed = ctx.body.newLabel();
					ctx.body.aload(inner.boxed()).dup().ifnonnull(boxed).pop().dload(inner.raw());
					JvmEmitHelper.boxDouble(ctx);
					ctx.body.labelBinding(boxed);
				}
			}
		}

	}

}
