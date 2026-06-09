package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code while} special form. Repeatedly evaluates the body while the test
 * expression is non-nil, then leaves nil on the stack as the loop's value.
 */
final class JvmWhileCompiler {

	private JvmWhileCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		int loopStart = ctx.code.size();
		// Evaluate the test; if nil, branch out of the loop.
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		// Body: every expression leaves a (boxed) reference, which is discarded.
		for (int i = 2; i < parts.size(); i++) {
			JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
			ctx.emit(Opcode.POP);
		}
		// Jump back to re-evaluate the test.
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, gotoPos, loopStart);
		// Loop exit: patch the IFNULL here and push nil as the result.
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
	}

}
