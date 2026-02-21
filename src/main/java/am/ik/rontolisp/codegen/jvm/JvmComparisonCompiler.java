package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles comparison operations ({@code =}, {@code <}, {@code >}, {@code <=},
 * {@code >=}).
 */
final class JvmComparisonCompiler {

	private JvmComparisonCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, int branchOpcode, String className) {
		List<LispVal> args = cons.toList();
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.unboxDouble(ctx);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmEmitHelper.unboxDouble(ctx);
			ctx.emit(Opcode.DCMPL);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.LCMP);
		}
		int ifPos = ctx.code.size();
		ctx.emit(branchOpcode);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		int trueLabel = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, ifPos, trueLabel);
		JvmEmitHelper.compileLong(1, ctx);
		int endLabel = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, endLabel);
	}

}
