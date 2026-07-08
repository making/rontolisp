package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the internal {@code %arrayp} predicate used by the {@code vector}/
 * {@code array}/{@code sequence} type specifiers. An array is a
 * {@code java.util.ArrayList} at runtime (see {@link JvmArrayRuntimeBuilder}), and no
 * other value uses that class, so a plain {@code instanceof} suffices.
 */
final class JvmArraypCompiler {

	private JvmArraypCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// When the program uses packed float arrays, a packed double[]/float[] is also an
		// array: if (v instanceof double[] || v instanceof float[]) return t; else the
		// general ArrayList check below.
		if (ctx.usesFloatArray) {
			// if (v instanceof double[]) { pop; return t; }
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8("[D")).index());
			int ifNotDoublePos = ctx.code.size();
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			ctx.emit(Opcode.POP);
			JvmEmitHelper.compileTrue(ctx);
			int gotoEndDouble = ctx.code.size();
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, ifNotDoublePos, ctx.code.size());
			// if (v instanceof float[]) { pop; return t; }
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8("[F")).index());
			int ifNotFloatPos = ctx.code.size();
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			ctx.emit(Opcode.POP);
			JvmEmitHelper.compileTrue(ctx);
			int gotoEndFloat = ctx.code.size();
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, ifNotFloatPos, ctx.code.size());
			// fall through with the value still on the stack for the ArrayList check
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")).index());
			int ifNotListPos = ctx.code.size();
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			JvmEmitHelper.compileTrue(ctx);
			int gotoEnd2 = ctx.code.size();
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, ifNotListPos, ctx.code.size());
			ctx.emit(Opcode.ACONST_NULL);
			JvmEmitHelper.patchBranch(ctx, gotoEndDouble, ctx.code.size());
			JvmEmitHelper.patchBranch(ctx, gotoEndFloat, ctx.code.size());
			JvmEmitHelper.patchBranch(ctx, gotoEnd2, ctx.code.size());
			return;
		}
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")).index());
		int ifNotListPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotListPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
