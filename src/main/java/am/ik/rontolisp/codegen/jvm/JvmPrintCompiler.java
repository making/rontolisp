package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code print} built-in function.
 */
final class JvmPrintCompiler {

	private JvmPrintCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() > 2) {
			// (print value stream): render "value\n", then route through _writeStr.
			int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.lispToString.index());
			JvmEmitHelper.compileStringLiteral("\n", ctx);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(concat);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmStringStreamCompiler.emitWriteStr(ctx, className);
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.lispToString.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printlnStr.index());
		JvmFreshLineCompiler.emitSetLineStart(ctx, className);
		ctx.emit(Opcode.ACONST_NULL);
	}

}
