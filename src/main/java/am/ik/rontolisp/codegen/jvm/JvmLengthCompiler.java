package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code length} built-in. A string returns its character count; any other
 * argument is treated as a list and its cons cells are counted (Common Lisp sequences).
 * The string runtime representation carries the surrounding double quotes, so the
 * character count is the Java string length minus two.
 */
final class JvmLengthCompiler {

	private JvmLengthCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		MethodrefConstant stringLength = ctx.cp.addMethodref(ctx.stringClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("length"), ctx.cp.addUtf8("()I")));
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int valSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(valSlot);
		// if (val instanceof String) return (val.length() - 2)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.stringClass.index());
		int ifNotString = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(stringLength.index());
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ISUB);
		ctx.emit(Opcode.I2L);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// List case: count cons cells until the value is no longer a cons (Object[]).
		JvmEmitHelper.patchBranch(ctx, ifNotString, ctx.code.size());
		int countSlot = ctx.allocTemp();
		ctx.allocTemp(); // reserve the second slot of the long accumulator
		ctx.emit(Opcode.LCONST_0);
		ctx.emit(Opcode.LSTORE);
		ctx.emit(countSlot);
		int loopPos = ctx.code.size();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifDone = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.LLOAD);
		ctx.emit(countSlot);
		ctx.emit(Opcode.LCONST_1);
		ctx.emit(Opcode.LADD);
		ctx.emit(Opcode.LSTORE);
		ctx.emit(countSlot);
		// val = ((Object[]) val)[1]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(valSlot);
		int gotoLoop = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2((loopPos - gotoLoop) & 0xFFFF);
		JvmEmitHelper.patchBranch(ctx, ifDone, ctx.code.size());
		ctx.emit(Opcode.LLOAD);
		ctx.emit(countSlot);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
	}

}
