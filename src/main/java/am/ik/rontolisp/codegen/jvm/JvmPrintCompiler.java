package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

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
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (JvmStringStreamCompiler.streamArg).
		LispVal stream = JvmStringStreamCompiler.streamArg(ctx, args.size() > 2 ? args.get(2) : null);
		if (stream != null) {
			// (print value stream): render "value\n", then route through _writeStr.
			MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.astore(objSlot).aload(objSlot).invokestatic(ctx.lispToString);
			JvmEmitHelper.compileStringLiteral("\n", ctx);
			ctx.body.invokevirtual(concat);
			JvmExprCompiler.compileExpr(stream, ctx, className);
			JvmStringStreamCompiler.emitWriteStr(ctx, className);
			ctx.body.aload(objSlot);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.astore(objSlot).getstatic(ctx.systemOut).aload(objSlot);
		ctx.body.invokestatic(ctx.lispToString).invokevirtual(ctx.printlnStr);
		JvmFreshLineCompiler.emitSetLineStart(ctx, className);
		ctx.body.aload(objSlot);
	}

}
