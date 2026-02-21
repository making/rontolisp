package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code list} built-in function.
 */
final class JvmListCompiler {

	private JvmListCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.ACONST_NULL);
		for (int i = args.size() - 1; i >= 1; i--) {
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ICONST_2);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
		}
	}

}
