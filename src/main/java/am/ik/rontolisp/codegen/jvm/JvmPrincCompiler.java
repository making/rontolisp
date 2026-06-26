package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code princ} built-in function. Prints without quotes and without
 * newline.
 */
final class JvmPrincCompiler {

	private JvmPrincCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.lispToDisplayString.index());
		int slot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(slot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printStr.index());
		JvmFreshLineCompiler.emitTrackLocal(ctx, className, slot);
		ctx.emit(Opcode.ACONST_NULL);
	}

}
