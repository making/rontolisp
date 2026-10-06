package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.Opcode;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles comparison operations ({@code =}, {@code <}, {@code >}, {@code <=},
 * {@code >=}).
 */
final class JvmComparisonCompiler {

	private JvmComparisonCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, Opcode branchOpcode, String className) {
		List<LispVal> args = cons.toList();
		Opcode branch;
		if (JvmLispCompiler.hasComplexOperand(args)) {
			// A complex operand steers off the double path: = compares
			// part-wise through _cmpb, every other operator signals through
			// the gated _ccmpb (`.kb/jvm-complex.md`).
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.invokestatic((branchOpcode == Opcode.IFEQ ? ctx.numOp(JvmNumericRuntimeBuilder.CMPB)
					: JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.CCPMB)));
			JvmEmitHelper.emitIntConst(ctx, maskFor(branchOpcode));
			ctx.body.iand();
			branch = Opcode.IFNE;
		}
		else if (JvmLispCompiler.isDefinitelyDouble(args.get(1), ctx)
				&& JvmLispCompiler.isDefinitelyDouble(args.get(2), ctx)
				&& JvmFloatOperands.guards(args.subList(1, 3), ctx)) {
			// Proven doubles -- unless an operand holds a complex the form does not spell
			// ((* 2.0 z)): then = compares part-wise and an ordering signals, through
			// the generic comparison the operands take when one is a holder.
			JvmFloatOperands.Operands operands = JvmFloatOperands.evaluate(args.subList(1, 3), ctx, className);
			MethodCode.Label trueLabel = ctx.body.newLabel();
			MethodCode.Label endLabel = ctx.body.newLabel();
			JvmFloatOperands.branch(operands, ctx, className, () -> {
				operands.pushRaw(0, ctx, className);
				operands.pushRaw(1, ctx, className);
				emitDoubleCompare(ctx, branchOpcode);
				ctx.body.branch(branchOpcode, trueLabel);
			}, () -> {
				operands.pushBoxed(0, ctx, className);
				operands.pushBoxed(1, ctx, className);
				ctx.body.invokestatic(orderingOrEquality(branchOpcode, ctx, className));
				JvmEmitHelper.emitIntConst(ctx, maskFor(branchOpcode));
				ctx.body.iand().ifne(trueLabel);
			});
			ctx.body.aconst_null().goto_(endLabel);
			ctx.body.labelBinding(trueLabel);
			JvmEmitHelper.compileTrue(ctx);
			ctx.body.labelBinding(endLabel);
			return;
		}
		else if (JvmLispCompiler.isDefinitelyDouble(args.get(1), ctx)
				&& JvmLispCompiler.isDefinitelyDouble(args.get(2), ctx)) {
			// Both sides are PROVEN doubles (a literal, a declared/raw double local,
			// or a true-contagion tree): their f64 comparison is exact, so the
			// unboxed DCMPL is sound. Anything else -- a double literal beside a
			// computed exact operand, e.g. (= 1.0 (+ 1 tiny-ratio)) -- goes through
			// _cmpb, whose mixed arm compares exact values (the min/max gate's
			// hasDoubleLiteral-vs-isDefinitelyDouble distinction, JvmMinCompiler).
			JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
			JvmArithCompiler.compileUnboxedOperand(args.get(2), ctx, className);
			emitDoubleCompare(ctx, branchOpcode);
			branch = branchOpcode;
		}
		else {
			// _cmpb returns the comparison as a bitmask (1 = lt, 2 = eq, 4 = gt,
			// 0 = unordered), so a NaN operand fails every operator -- a -1/0/1
			// signum compared against zero cannot express "unordered".
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.invokestatic(orderingOrEquality(branchOpcode, ctx, className));
			JvmEmitHelper.emitIntConst(ctx, maskFor(branchOpcode));
			ctx.body.iand();
			branch = Opcode.IFNE;
		}
		MethodCode.Label trueLabel = ctx.body.newLabel();
		MethodCode.Label endLabel = ctx.body.newLabel();
		ctx.body.branch(branch, trueLabel);
		ctx.body.aconst_null().goto_(endLabel);
		ctx.body.labelBinding(trueLabel);
		JvmEmitHelper.compileTrue(ctx);
		ctx.body.labelBinding(endLabel);
	}

	/**
	 * The comparison helper for operands no source text shows to be complex:
	 * {@code _cmpb}, whose holder arm compares a complex part-wise -- right for
	 * {@code =}, which is all a syntactic complex ever reached it under -- except for an
	 * ordering in a program that may observe a complex, which a complex arriving through
	 * a variable must make SIGNAL, as the interpreter does: the gated {@code _ccmpb}
	 * (`.kb/jvm-complex.md`).
	 * @param branchOpcode the operator's branch
	 * @param ctx the compile context
	 * @param className the class being emitted
	 * @return the reference to invoke
	 */
	static java.lang.classfile.constantpool.MethodRefEntry orderingOrEquality(Opcode branchOpcode,
			JvmLispCompiler.Ctx ctx, String className) {
		return ctx.usesComplex && branchOpcode != Opcode.IFEQ
				? JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.CCPMB)
				: ctx.numOp(JvmNumericRuntimeBuilder.CMPB);
	}

	// IEEE: a comparison against NaN is false. javac's rule: DCMPG for < and <= (NaN
	// falls out as +1, failing IFLT/IFLE), DCMPL for the others (NaN falls out as -1,
	// failing IFGT/IFGE/IFEQ).
	private static void emitDoubleCompare(JvmLispCompiler.Ctx ctx, Opcode branchOpcode) {
		if (branchOpcode == Opcode.IFLT || branchOpcode == Opcode.IFLE) {
			ctx.body.dcmpg();
		}
		else {
			ctx.body.dcmpl();
		}
	}

	private static int maskFor(Opcode branchOpcode) {
		return switch (branchOpcode) {
			case Opcode.IFEQ -> 0b010;
			case Opcode.IFLT -> 0b001;
			case Opcode.IFGT -> 0b100;
			case Opcode.IFLE -> 0b011;
			case Opcode.IFGE -> 0b110;
			default -> throw new IllegalArgumentException("unexpected comparison branch: " + branchOpcode);
		};
	}

}
