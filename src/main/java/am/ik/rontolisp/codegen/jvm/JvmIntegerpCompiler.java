package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code integerp} predicate. An integer is represented at runtime as either
 * a {@code Long} or a {@code BigInteger}.
 */
final class JvmIntegerpCompiler {

	private JvmIntegerpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ClassConstant bigClass = ctx.cp.addClass(ctx.cp.addUtf8("java/math/BigInteger"));
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
		ctx.emitU2(bigClass.index());
		ctx.emit(Opcode.IOR);
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
