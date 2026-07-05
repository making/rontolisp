package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code functionp} predicate. A function reference is an {@code Object[]}
 * whose slot 0 is a boxed {@code Integer} funcId (integers are {@code Long} at runtime,
 * so the {@code Integer} marker distinguishes it from a cons cell -- the mirror image of
 * {@link JvmConspCompiler}).
 */
final class JvmFunctionpCompiler {

	private JvmFunctionpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifNotArrayPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		// A ratio (BigInteger[]) is also an Object[] but is not a function reference.
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.ratioArrayClass(ctx).index());
		int ifRatioPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.integerClass.index());
		int ifNotFuncRefPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		JvmEmitHelper.compileTrue(ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotArrayPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifRatioPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifNotFuncRefPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
