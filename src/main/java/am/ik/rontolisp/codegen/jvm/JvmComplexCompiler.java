package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispComplex;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the complex-number built-ins: the {@code complex} constructor, the
 * {@code complexp}/{@code realp} predicates, the
 * {@code realpart}/{@code imagpart}/{@code conjugate}/{@code phase} accessors, and the
 * always-complex {@code sqrt}. Construction routes through the gated {@code _c*} helpers;
 * the predicates and the real/imaginary projections are inline {@code instanceof} shapes
 * that need no helper and no travelling class (`.kb/jvm-complex.md`).
 */
final class JvmComplexCompiler {

	private JvmComplexCompiler() {
	}

	/** The travelling holder's class constant. */
	static ClassConstant complexClass(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addClass(ctx.cp.addUtf8("am/ik/rontolisp/runtime/RontoComplex"));
	}

	/**
	 * The holder-presence probe's field reference (.todo/757, minted in
	 * {@code JvmLispCompiler}): every holder test below consults it before resolving the
	 * travelling class. Created on demand like {@link #complexOp} -- only a site that
	 * emits the probe names the field.
	 */
	static FieldrefConstant hasComplexField(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.addFieldref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_hasComplex"), ctx.cp.addUtf8("Z")));
	}

	/**
	 * Emits the holder-presence probe: falls through when a holder instance can exist,
	 * and jumps to {@code noHolder}, which the caller binds at the arm's end, otherwise
	 * -- then the holder-less shape that follows is exact, because no holder instance can
	 * exist without its class (.todo/757). Net zero on the operand stack (the flag is
	 * pushed and popped above whatever is live).
	 */
	static void emitNoHolderJump(JvmLispCompiler.Ctx ctx, String className, MethodCode.Label noHolder) {
		ctx.body.getstatic(hasComplexField(ctx, className).entry()).ifeq(noHolder);
	}

	/**
	 * A reference to a gated {@code _c*} helper, created on demand: the helper may be
	 * emitted later (or on a retry with the group forced on), so call sites must not look
	 * it up in the always-present map -- the same reason every other gated runtime builds
	 * its references at the call site.
	 */
	static MethodrefConstant complexOp(JvmLispCompiler.Ctx ctx, String className, String op) {
		String desc = JvmComplexRuntimeBuilder.descFor(op);
		MethodrefConstant ref = JvmEmitHelper.selfMethod(ctx, className, op, desc);
		// Every complex helper but the constructor can meet a wrong-type operand.
		return JvmComplexRuntimeBuilder.COMPLEX.equals(op) ? ref : ctx.wrapForOperator(op, desc, ref);
	}

	/**
	 * Emits a complex literal: its canonical parts, then the canonicalizing constructor
	 * (idempotent over reader-canonical values).
	 */
	static void compileLiteral(LispComplex complex, JvmLispCompiler.Ctx ctx, String className) {
		compileRealPart(complex.real(), ctx);
		compileRealPart(complex.imag(), ctx);
		ctx.body.invokestatic(complexOp(ctx, className, JvmComplexRuntimeBuilder.COMPLEX).entry());
	}

	private static void compileRealPart(LispVal part, JvmLispCompiler.Ctx ctx) {
		switch (part) {
			case am.ik.rontolisp.LispInteger i -> JvmEmitHelper.compileLong(i.value(), ctx);
			case am.ik.rontolisp.LispBigInteger b -> JvmEmitHelper.compileBigInteger(b.value(), ctx);
			case am.ik.rontolisp.LispRatio r -> JvmEmitHelper.compileRatio(r, ctx);
			case am.ik.rontolisp.LispDouble d -> JvmEmitHelper.compileDouble(d.value(), ctx);
			default -> throw new UnsupportedOperationException("Cannot compile complex part: " + part.print());
		}
	}

	/** Compiles {@code (complex real &optional imag)}. */
	static void compileComplex(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() < 2 || args.size() > 3) {
			throw new UnsupportedOperationException(
					"complex expects a real part and an optional imaginary part, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (args.size() == 3) {
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		}
		else {
			JvmEmitHelper.compileLong(0, ctx);
		}
		// The constructor rejects a part that is no real: under the complex form it is
		// named COMPLEX (a literal's parts are canonical and cannot fail).
		String desc = JvmComplexRuntimeBuilder.descFor(JvmComplexRuntimeBuilder.COMPLEX);
		ctx.body.invokestatic(ctx.wrapForOperator(JvmComplexRuntimeBuilder.COMPLEX, desc,
				complexOp(ctx, className, JvmComplexRuntimeBuilder.COMPLEX))
			.entry());
	}

	/** Compiles {@code (complexp x)}. */
	static void compileComplexp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		requireArity(cons, 1);
		if (!ctx.usesComplex) {
			// No holder can exist in this program (construction needs the
			// gated helpers), so the answer is constantly nil -- and, crucially,
			// the travelling class stays out of the constant pool
			// (`.kb/jvm-complex.md`).
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.pop().aconst_null();
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// The presence probe first: a lone class run without the travelling file
		// answers nil without resolving the holder class it then never touches
		// (.todo/757) -- exact, since no holder can exist then.
		MethodCode.Label notHolder = ctx.body.newLabel();
		emitNoHolderJump(ctx, className, notHolder);
		ctx.body.instanceOf(complexClass(ctx).entry());
		JvmEmitHelper.emitBoolFromInt(ctx);
		MethodCode.Label donePos = ctx.body.newLabel();
		ctx.body.goto_(donePos);
		ctx.body.labelBinding(notHolder);
		ctx.body.pop().aconst_null();
		ctx.body.labelBinding(donePos);
	}

	/** Compiles {@code (realp x)}: true for integers, ratios and floats. */
	static void compileRealp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		requireArity(cons, 1);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// A holder is never real; without one in the program there is nothing
		// to exclude (and the travelling class stays out of the constant pool).
		compileIsReal(ctx);
	}

	/** The four real representations as a boolean value. */
	private static void compileIsReal(JvmLispCompiler.Ctx ctx) {
		int temp = ctx.allocTemp();
		ctx.body.astore(temp).aload(temp).instanceOf(ctx.longClass.entry()).aload(temp);
		ctx.body.instanceOf(JvmEmitHelper.bigIntegerClass(ctx).entry()).ior().aload(temp);
		ctx.body.instanceOf(JvmEmitHelper.ratioArrayClass(ctx).entry()).ior().aload(temp);
		ctx.body.instanceOf(ctx.doubleClass.entry()).ior();
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/**
	 * Compiles {@code (realpart x)}: the holder's field, or the value itself after the
	 * real funnel validates it.
	 */
	static void compileRealpart(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		requireArity(cons, 1);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int temp = ctx.allocTemp();
		ctx.body.astore(temp);
		if (ctx.usesComplex) {
			// The presence probe first: without the travelling file no holder
			// can exist, so the real funnel below is the whole answer
			// (.todo/757).
			MethodCode.Label notHolder = ctx.body.newLabel();
			emitNoHolderJump(ctx, className, notHolder);
			ctx.body.aload(temp).instanceOf(complexClass(ctx).entry()).ifeq(notHolder);
			ctx.body.aload(temp).checkcast(complexClass(ctx).entry());
			ctx.body.getfield(realField(ctx).entry());
			MethodCode.Label donePos = ctx.body.newLabel();
			ctx.body.goto_(donePos);
			ctx.body.labelBinding(notHolder);
			emitRealCheck(ctx, temp);
			ctx.body.labelBinding(donePos);
		}
		else {
			// No holder can exist here: the funnel validates and the value
			// itself is the answer, with no reference to the travelling class.
			emitRealCheck(ctx, temp);
		}
	}

	/**
	 * Validates the real in {@code temp} through the {@code _dbl} funnel (signalling
	 * NUMBER operand-type report for a non-real, like the interpreter's requireReal) and
	 * leaves the value itself on the stack.
	 */
	private static void emitRealCheck(JvmLispCompiler.Ctx ctx, int temp) {
		// Not a holder: the _dbl funnel validates (signalling NUMBER operand-type report
		// for a non-real, like the interpreter's requireReal) and the value
		// itself is the answer.
		ctx.body.aload(temp).dup().invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.DBL).entry());
		ctx.body.pop();
	}

	/**
	 * Compiles {@code (imagpart x)}: the holder's field, (* 0 x) for a float, an integer
	 * zero for any other real.
	 */
	static void compileImagpart(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		requireArity(cons, 1);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int temp = ctx.allocTemp();
		ctx.body.astore(temp);
		if (ctx.usesComplex) {
			// The presence probe first: without the travelling file no holder
			// can exist, so the real zero below is the whole answer
			// (.todo/757).
			MethodCode.Label notHolder = ctx.body.newLabel();
			emitNoHolderJump(ctx, className, notHolder);
			ctx.body.aload(temp).instanceOf(complexClass(ctx).entry()).ifeq(notHolder);
			ctx.body.aload(temp).checkcast(complexClass(ctx).entry());
			ctx.body.getfield(imagField(ctx).entry());
			MethodCode.Label donePos = ctx.body.newLabel();
			ctx.body.goto_(donePos);
			ctx.body.labelBinding(notHolder);
			emitZeroForReal(ctx, temp);
			ctx.body.labelBinding(donePos);
		}
		else {
			// No holder can exist here: a float zero for a float, an integer
			// zero for any other real, with no reference to the class.
			emitZeroForReal(ctx, temp);
		}
	}

	/** The imagpart answer for a real: (* 0 x) for a float, else int zero. */
	private static void emitZeroForReal(JvmLispCompiler.Ctx ctx, int temp) {
		ctx.body.aload(temp).instanceOf(ctx.doubleClass.entry());
		MethodCode.Label ifNotDoublePos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotDoublePos);
		// CLHS: (imagpart x) of a real IS (* 0 x) -- multiply the unboxed value
		// by 0.0 so a negative float answers -0.0. The value IS a Double here.
		ctx.body.aload(temp);
		JvmEmitHelper.unboxDeclaredDouble(ctx);
		JvmEmitHelper.emitRawDouble(0.0, ctx);
		ctx.body.dmul();
		JvmEmitHelper.boxDouble(ctx);
		MethodCode.Label done2Pos = ctx.body.newLabel();
		ctx.body.goto_(done2Pos);
		ctx.body.labelBinding(ifNotDoublePos);
		ctx.body.aload(temp).dup().invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.DBL).entry());
		ctx.body.pop().pop();
		JvmEmitHelper.compileLong(0, ctx);
		ctx.body.labelBinding(done2Pos);
	}

	/** Compiles {@code (conjugate x)}. */
	static void compileConjugate(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		requireArity(cons, 1);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.invokestatic(complexOp(ctx, className, JvmComplexRuntimeBuilder.CONJUGATE).entry());
	}

	/** Compiles {@code (phase x)}. */
	static void compilePhase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		requireArity(cons, 1);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.invokestatic(complexOp(ctx, className, JvmComplexRuntimeBuilder.CPHASE).entry());
	}

	/**
	 * Compiles {@code (sqrt x)}: always through the complex-aware helper, since a
	 * negative real roots into the plane -- a runtime property no syntactic gate can see.
	 */
	static void compileSqrt(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		requireArity(cons, 1);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.invokestatic(complexOp(ctx, className, JvmComplexRuntimeBuilder.SQRT).entry());
	}

	private static void requireArity(LispCons cons, int arity) {
		int got = cons.toList().size() - 1;
		if (got != arity) {
			String name = cons.car() instanceof LispSymbol sym ? sym.name() : "?";
			throw new UnsupportedOperationException(name + " expects " + arity + " argument(s), got " + got);
		}
	}

	private static FieldrefConstant realField(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addFieldref(complexClass(ctx),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("real"), ctx.cp.addUtf8("Ljava/lang/Object;")));
	}

	private static FieldrefConstant imagField(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addFieldref(complexClass(ctx),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("imag"), ctx.cp.addUtf8("Ljava/lang/Object;")));
	}

}
