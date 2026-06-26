package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code fresh-line} built-in function and provides the column-tracking
 * helpers used by the output primitives. The compiled class holds a static {@code _col}
 * field (0 = at the start of a line, 1 = mid-line); {@code princ}/{@code prin1} update it
 * from the last character printed, {@code terpri}/{@code print} reset it to 0, and
 * {@code fresh-line} emits a newline only when {@code _col} is non-zero. Always returns
 * nil.
 */
final class JvmFreshLineCompiler {

	static final String COL_FIELD = "_col";

	static final String COL_DESC = "I";

	private JvmFreshLineCompiler() {
	}

	static FieldrefConstant colField(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.addFieldref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(COL_FIELD), ctx.cp.addUtf8(COL_DESC)));
	}

	private static MethodrefConstant stringLength(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addMethodref(ctx.stringClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("length"), ctx.cp.addUtf8("()I")));
	}

	/** Emits {@code _col = 0} (the output ended at the start of a line). */
	static void emitSetLineStart(JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.PUTSTATIC);
		ctx.emitU2(colField(ctx, className).index());
	}

	/**
	 * Emits code that updates {@code _col} from the string held in local {@code slot}: 0
	 * if it ends with a newline, 1 if it ends with any other character, unchanged if
	 * empty.
	 */
	static void emitTrackLocal(JvmLispCompiler.Ctx ctx, String className, int slot) {
		FieldrefConstant col = colField(ctx, className);
		MethodrefConstant length = stringLength(ctx);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(length.index());
		int ifEmpty = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(length.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISUB);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.stringCharAt.index());
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(10);
		int ifNotNewline = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ICONST_0);
		int gotoStore = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotNewline, ctx.code.size());
		ctx.emit(Opcode.ICONST_1);
		JvmEmitHelper.patchBranch(ctx, gotoStore, ctx.code.size());
		ctx.emit(Opcode.PUTSTATIC);
		ctx.emitU2(col.index());
		JvmEmitHelper.patchBranch(ctx, ifEmpty, ctx.code.size());
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		FieldrefConstant col = colField(ctx, className);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(col.index());
		int ifAtStart = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printlnVoid.index());
		emitSetLineStart(ctx, className);
		JvmEmitHelper.patchBranch(ctx, ifAtStart, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
	}

}
