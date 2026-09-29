package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.Opcode;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code if} special form.
 */
final class JvmIfCompiler {

	private JvmIfCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		// A fusable binary comparison in condition position leaves a RAW int truth
		// value and branches on it directly, skipping the boxed t/nil round trip
		// (.kb/jvm-int-fusion.md); any other test compiles boxed as before.
		if (JvmExprCompiler.isSuppliedP(parts.get(1))) {
			// A physical optional's prologue, (if (%supplied-p p) p default): one
			// reference comparison against the UNSUPPLIED marker, no boxed t/nil.
			compileSuppliedPTest((LispCons) parts.get(1), parts, ctx, className);
			return;
		}
		Opcode falseBranchOpcode;
		if (JvmExprCompiler.tryCompileFusedCondition(parts.get(1), ctx, className)) {
			falseBranchOpcode = Opcode.IFEQ;
		}
		else {
			JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
			falseBranchOpcode = Opcode.IFNULL;
		}
		MethodCode.Label elseStart = ctx.body.newLabel();
		MethodCode.Label end = ctx.body.newLabel();
		ctx.body.branch(falseBranchOpcode, elseStart);
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		ctx.body.goto_(end);
		ctx.body.labelBinding(elseStart);
		if (parts.size() > 3) {
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		}
		else {
			ctx.body.aconst_null();
		}
		ctx.body.labelBinding(end);
	}

	// (if (%supplied-p p) then else): the comparison branches to THEN when the parameter
	// holds an argument and falls through to ELSE on the marker.
	private static void compileSuppliedPTest(LispCons test, List<LispVal> parts, JvmLispCompiler.Ctx ctx,
			String className) {
		MethodCode.Label supplied = ctx.body.newLabel();
		JvmExprCompiler.compileSuppliedPTest(test, ctx, className, supplied);
		if (parts.size() > 3) {
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		}
		else {
			ctx.body.aconst_null();
		}
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(supplied);
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		ctx.body.labelBinding(gotoEndPos);
	}

}
