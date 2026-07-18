package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code stringp} predicate. A value is a string when it is a quote-framed
 * {@code java.lang.String}, or -- when the array runtime helpers are emitted -- a mutable
 * character vector (an {@code ArrayList} whose slot-0 header {@code Object[]} has length
 * 4, see {@link JvmArrayRuntimeBuilder}).
 */
final class JvmStringpCompiler {

	private JvmStringpCompiler() {
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
		ctx.emitU2(ctx.stringClass.index());
		int ifNotStringPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.stringCharAt.index());
		JvmEmitHelper.emitIntConst(ctx, 34);
		int ifNotQuotePos = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPNE);
		ctx.emitU2(0);
		JvmEmitHelper.compileTrue(ctx);
		List<Integer> gotoEnds = new ArrayList<>();
		List<Integer> nilBranches = new ArrayList<>();
		gotoEnds.add(ctx.code.size());
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		nilBranches.add(ifNotQuotePos);
		JvmEmitHelper.patchBranch(ctx, ifNotStringPos, ctx.code.size());
		if (ctx.usesArrays) {
			// A mutable character vector (an ArrayList whose slot-0 header Object[] has
			// length 4) is a string too; the branch exists only when the array helpers
			// are emitted, so array-free programs stay byte-identical.
			ClassConstant arrayListClass = ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList"));
			MethodrefConstant alSize = ctx.cp.addMethodref(arrayListClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("size"), ctx.cp.addUtf8("()I")));
			MethodrefConstant alGet = ctx.cp.addMethodref(arrayListClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("get"), ctx.cp.addUtf8("(I)Ljava/lang/Object;")));
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(arrayListClass.index());
			nilBranches.add(ctx.code.size());
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(arrayListClass.index());
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(alSize.index());
			nilBranches.add(ctx.code.size());
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(arrayListClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(alGet.index());
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(ctx.objectArrayClass.index());
			nilBranches.add(ctx.code.size());
			ctx.emit(Opcode.IFEQ);
			ctx.emitU2(0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(arrayListClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(alGet.index());
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ARRAYLENGTH);
			JvmEmitHelper.emitIntConst(ctx, 4);
			nilBranches.add(ctx.code.size());
			ctx.emit(Opcode.IF_ICMPNE);
			ctx.emitU2(0);
			JvmEmitHelper.compileTrue(ctx);
			gotoEnds.add(ctx.code.size());
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
		}
		for (int pos : nilBranches) {
			JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		}
		ctx.emit(Opcode.ACONST_NULL);
		for (int pos : gotoEnds) {
			JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		}
	}

}
