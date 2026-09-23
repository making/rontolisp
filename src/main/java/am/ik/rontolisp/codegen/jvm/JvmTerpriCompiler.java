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
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (JvmStringStreamCompiler.streamArg).
		am.ik.rontolisp.LispVal stream = JvmStringStreamCompiler.streamArg(ctx, args.size() > 1 ? args.get(1) : null);
		if (stream != null) {
			// (terpri stream): the newline routes through _writeString -- NOT _writeStr,
			// which the print family shares and which deliberately has no socket arm
			// (see .kb/tcp-sockets.md); _writeString's own socket arm sends it out the
			// socket. _writeString answers its string; terpri answers nil.
			JvmEmitHelper.compileStringLiteral("\"\n\"", ctx);
			JvmExprCompiler.compileExpr(stream, ctx, className);
			JvmStringStreamCompiler.emitWriteString(ctx, className);
			ctx.emit(Opcode.POP);
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
