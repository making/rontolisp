package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code princ} built-in function. Prints without quotes and without
 * newline.
 */
final class JvmPrincCompiler {

	private JvmPrincCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// princ returns its argument (CL semantics); stash the object so it can be left
		// on
		// the stack after printing, not nil.
		int objSlot = ctx.allocTemp();
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (JvmStringStreamCompiler.streamArg).
		LispVal stream = JvmStringStreamCompiler.streamArg(ctx, args.size() > 2 ? args.get(2) : null);
		if (stream != null) {
			// (princ value stream): render, then route through _writeStr.
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.astore(objSlot).aload(objSlot).invokestatic(ctx.lispToDisplayString.entry());
			JvmExprCompiler.compileExpr(stream, ctx, className);
			JvmStringStreamCompiler.emitWriteStr(ctx, className);
			ctx.body.aload(objSlot);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.astore(objSlot).aload(objSlot).invokestatic(ctx.lispToDisplayString.entry());
		int slot = ctx.allocTemp();
		ctx.body.astore(slot).getstatic(ctx.systemOut.entry()).aload(slot);
		ctx.body.invokevirtual(ctx.printStr.methodRefEntry());
		JvmFreshLineCompiler.emitTrackLocal(ctx, className, slot);
		ctx.body.aload(objSlot);
	}

}
