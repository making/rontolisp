package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code listp} predicate.
 */
final class JvmListpCompiler {

	private JvmListpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifNotArrayPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
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
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		JvmEmitHelper.compileLong(1, ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotArrayPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifFuncRefPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
