package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code atom} predicate. The decision itself lives in one per-class helper
 * ({@code _pAtom}, {@link JvmEmitHelper#emitSharedCall}): it depends on nothing but the
 * value, and inline it is ~90 bytecodes -- enough that a generated dispatch writing it
 * once per clause crosses HotSpot's {@code HugeMethodLimit}.
 */
final class JvmAtomCompiler {

	private JvmAtomCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.emitSharedCall(ctx, className, "_pAtom", 1, JvmAtomCompiler::emitCheck);
	}

	/** Emits the predicate over the value in local slot 0. */
	private static void emitCheck(JvmLispCompiler.Ctx ctx) {
		int tempSlot = 0;
		ctx.body.aload(tempSlot).instanceOf(ctx.objectArrayClass);
		MethodCode.Label ifNotArrayPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotArrayPos);
		// A ratio (BigInteger[]) is also an Object[] but is an atom, not a cons cell.
		ctx.body.aload(tempSlot).instanceOf(JvmEmitHelper.ratioArrayClass(ctx));
		MethodCode.Label ifRatioPos = ctx.body.newLabel();
		ctx.body.ifne(ifRatioPos);
		ctx.body.aload(tempSlot).checkcast(ctx.objectArrayClass).iconst_0().aaload();
		ctx.body.instanceOf(ctx.integerClass);
		MethodCode.Label ifFuncRefPos = ctx.body.newLabel();
		ctx.body.ifne(ifFuncRefPos);
		MethodCode.Label notCons = ctx.body.newLabel();
		JvmEmitHelper.emitInstanceExclusion(ctx, tempSlot, notCons);
		JvmEmitHelper.emitAsyncValueExclusion(ctx, tempSlot, notCons);
		ctx.body.aconst_null();
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(ifNotArrayPos);
		ctx.body.labelBinding(ifRatioPos);
		ctx.body.labelBinding(ifFuncRefPos);
		ctx.body.labelBinding(notCons);
		JvmEmitHelper.compileTrue(ctx);
		ctx.body.labelBinding(gotoEndPos);
	}

}
