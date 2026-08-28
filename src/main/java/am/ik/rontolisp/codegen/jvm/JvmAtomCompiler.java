package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code atom} predicate. The decision itself lives in one per-class helper
 * ({@code _pAtom}, {@link JvmEmitHelper#emitSharedCall}): it depends on nothing but the
 * value, and inline it is ~90 bytecodes -- enough that a generated dispatch writing it
 * once per clause crosses HotSpot's {@code HugeMethodLimit}.
 */
final class JvmAtomCompiler {

	private JvmAtomCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.emitSharedCall(ctx, className, "_pAtom", 1, JvmAtomCompiler::emitCheck);
	}

	/** Emits the predicate over the value in local slot 0. */
	private static void emitCheck(JvmLispCompiler.Ctx ctx) {
		int tempSlot = 0;
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifNotArrayPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		// A ratio (BigInteger[]) is also an Object[] but is an atom, not a cons cell.
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
		int ifFuncRefPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		int ifInstancePos = JvmEmitHelper.emitInstanceExclusion(ctx, tempSlot);
		int[] asyncValuePos = JvmEmitHelper.emitAsyncValueExclusion(ctx, tempSlot);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotArrayPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifRatioPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifFuncRefPos, ctx.code.size());
		if (ifInstancePos >= 0) {
			JvmEmitHelper.patchBranch(ctx, ifInstancePos, ctx.code.size());
		}
		for (int pos : asyncValuePos) {
			JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		}
		JvmEmitHelper.compileTrue(ctx);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
