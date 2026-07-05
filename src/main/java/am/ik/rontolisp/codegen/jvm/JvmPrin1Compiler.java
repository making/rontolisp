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
		if (args.size() > 2) {
			// (prin1 value stream): render, then route through _writeStr.
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.lispToString.index());
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmStringStreamCompiler.emitWriteStr(ctx, className);
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.lispToString.index());
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
