package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code keywordp} predicate.
 */
final class JvmKeywordpCompiler {

	private JvmKeywordpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.body.astore(tempSlot).aload(tempSlot).instanceOf(ctx.stringClass.entry());
		MethodCode.Label ifNotStringPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotStringPos);
		ctx.body.aload(tempSlot).checkcast(ctx.stringClass.entry()).iconst_0();
		ctx.body.invokevirtual(ctx.stringCharAt.methodRefEntry());
		JvmEmitHelper.emitIntConst(ctx, 58); // ':'
		MethodCode.Label ifNotColonPos = ctx.body.newLabel();
		ctx.body.if_icmpne(ifNotColonPos);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(ifNotStringPos);
		ctx.body.labelBinding(ifNotColonPos);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEndPos);
	}

}
