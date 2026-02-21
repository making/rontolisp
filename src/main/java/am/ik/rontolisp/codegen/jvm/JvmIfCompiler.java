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
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
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

}
