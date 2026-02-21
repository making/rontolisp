package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code cons} built-in function.
 */
final class JvmConsCompiler {

	private JvmConsCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.emit(Opcode.AASTORE);
	}

}
