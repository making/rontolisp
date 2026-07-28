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
		// print returns its argument (CL semantics); stash the object so the value can be
		// left on the stack after printing, not nil.
		int objSlot = ctx.allocTemp();
		// An explicit stream argument, or the current *standard-output* value when the
		// program redirects it (JvmStringStreamCompiler.defaultStreamArg).
		LispVal stream = args.size() > 2 ? args.get(2) : JvmStringStreamCompiler.defaultStreamArg(ctx);
		if (stream != null) {
			// (print value stream): render "value\n", then route through _writeStr.
			int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(objSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(objSlot);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.lispToString.index());
			JvmEmitHelper.compileStringLiteral("\n", ctx);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(concat);
			JvmExprCompiler.compileExpr(stream, ctx, className);
			JvmStringStreamCompiler.emitWriteStr(ctx, className);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(objSlot);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(objSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(objSlot);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.lispToString.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printlnStr.index());
		JvmFreshLineCompiler.emitSetLineStart(ctx, className);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(objSlot);
	}

}
