package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code numberp} predicate.
 */
final class JvmNumberpCompiler {

	private JvmNumberpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int temp = ctx.allocTemp();
		ctx.body.astore(temp).aload(temp).instanceOf(ctx.numberClass).aload(temp);
		ctx.body.instanceOf(JvmEmitHelper.ratioArrayClass(ctx)).ior();
		if (ctx.usesComplex) {
			// A complex value is a number too -- but the holder test names the
			// travelling class, so it is emitted only for a complex-capable
			// program (`.kb/jvm-complex.md`). The presence probe first: a lone
			// class run without the file beside it must not resolve the holder
			// class it then never touches (.todo/757) -- exact, since no holder
			// can exist then.
			MethodCode.Label notHolder = ctx.body.newLabel();
			JvmComplexCompiler.emitNoHolderJump(ctx, className, notHolder);
			ctx.body.aload(temp).instanceOf(JvmComplexCompiler.complexClass(ctx)).ior();
			ctx.body.labelBinding(notHolder);
		}
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
