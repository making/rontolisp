package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

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
		int falseBranchOpcode;
		if (JvmExprCompiler.tryCompileFusedCondition(parts.get(1), ctx, className)) {
			falseBranchOpcode = Opcode.IFEQ;
		}
		else {
			JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
			falseBranchOpcode = Opcode.IFNULL;
		}
		int ifNullPos = ctx.code.size();
		ctx.emit(falseBranchOpcode);
		ctx.emitU2(0);
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		int elseStart = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, ifNullPos, elseStart);
		if (parts.size() > 3) {
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		int endPos = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, endPos);
	}

	// (if (%supplied-p p) then else): the comparison branches to THEN when the parameter
	// holds an argument and falls through to ELSE on the marker.
	private static void compileSuppliedPTest(LispCons test, List<LispVal> parts, JvmLispCompiler.Ctx ctx,
			String className) {
		int suppliedPos = JvmExprCompiler.compileSuppliedPTest(test, ctx, className);
		if (parts.size() > 3) {
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, suppliedPos, ctx.code.size());
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
