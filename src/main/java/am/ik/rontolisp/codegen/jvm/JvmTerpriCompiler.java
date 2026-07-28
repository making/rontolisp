package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code terpri} built-in function. Prints a newline only.
 */
final class JvmTerpriCompiler {

	private JvmTerpriCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		java.util.List<am.ik.rontolisp.LispVal> args = cons.toList();
		// An explicit stream argument, or the current *standard-output* value when the
		// program redirects it (JvmStringStreamCompiler.defaultStreamArg).
		am.ik.rontolisp.LispVal stream = args.size() > 1 ? args.get(1) : JvmStringStreamCompiler.defaultStreamArg(ctx);
		if (stream != null) {
			// (terpri stream): route a newline through _writeStr.
			JvmEmitHelper.compileStringLiteral("\n", ctx);
			JvmExprCompiler.compileExpr(stream, ctx, className);
			JvmStringStreamCompiler.emitWriteStr(ctx, className);
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printlnVoid.index());
		JvmFreshLineCompiler.emitSetLineStart(ctx, className);
		ctx.emit(Opcode.ACONST_NULL);
	}

}
