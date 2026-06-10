package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code rationalp} predicate. A rational is an integer ({@code Long} or
 * {@code BigInteger}) or a ratio ({@code BigInteger[]}).
 */
final class JvmRationalpCompiler {

	private JvmRationalpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int temp = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(temp);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.longClass.index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.bigIntegerClass(ctx).index());
		ctx.emit(Opcode.IOR);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.ratioArrayClass(ctx).index());
		ctx.emit(Opcode.IOR);
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
