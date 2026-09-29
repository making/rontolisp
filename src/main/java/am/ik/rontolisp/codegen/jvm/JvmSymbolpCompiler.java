package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code symbolp} predicate.
 */
final class JvmSymbolpCompiler {

	private JvmSymbolpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.body.astore(tempSlot);
		// nil (and t, a plain string) are symbols in CL.
		ctx.body.aload(tempSlot);
		MethodCode.Label ifNullPos = ctx.body.newLabel();
		ctx.body.ifnull(ifNullPos);
		ctx.body.aload(tempSlot).instanceOf(ctx.stringClass);
		MethodCode.Label ifNotStringPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotStringPos);
		ctx.body.aload(tempSlot).checkcast(ctx.stringClass).iconst_0();
		ctx.body.invokevirtual(ctx.stringCharAt);
		JvmEmitHelper.emitIntConst(ctx, 34);
		MethodCode.Label ifQuotePos = ctx.body.newLabel();
		ctx.body.if_icmpeq(ifQuotePos);
		ctx.body.labelBinding(ifNullPos);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(ifNotStringPos);
		ctx.body.labelBinding(ifQuotePos);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEndPos);
	}

}
