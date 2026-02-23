package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code prin1} built-in function. Same as print but without newline.
 */
final class JvmPrin1Compiler {

	private JvmPrin1Compiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.lispToString.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printStr.index());
		ctx.emit(Opcode.ACONST_NULL);
	}

}
