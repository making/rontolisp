package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

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
			ctx.emit(Opcode.ASTORE);
			ctx.emit(objSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(objSlot);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.lispToDisplayString.index());
			JvmExprCompiler.compileExpr(stream, ctx, className);
			JvmStringStreamCompiler.emitWriteStr(ctx, className);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(objSlot);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(objSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(objSlot);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.lispToDisplayString.index());
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
		ctx.emit(Opcode.ALOAD);
		ctx.emit(objSlot);
	}

}
