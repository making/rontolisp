package am.ik.rontolisp.codegen.jvm;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;

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
		ctx.body.iconst_0().putstatic(colField(ctx, className).entry());
	}

	/**
	 * Emits code that updates {@code _col} from the string held in local {@code slot}: 0
	 * if it ends with a newline, 1 if it ends with any other character, unchanged if
	 * empty.
	 */
	static void emitTrackLocal(JvmLispCompiler.Ctx ctx, String className, int slot) {
		FieldrefConstant col = colField(ctx, className);
		MethodrefConstant length = stringLength(ctx);
		ctx.body.aload(slot).invokevirtual(length.methodRefEntry());
		MethodCode.Label ifEmpty = ctx.body.newLabel();
		ctx.body.ifeq(ifEmpty);
		ctx.body.aload(slot).aload(slot).invokevirtual(length.methodRefEntry()).iconst_1().isub();
		ctx.body.invokevirtual(ctx.stringCharAt.methodRefEntry()).loadConstant(10);
		MethodCode.Label ifNotNewline = ctx.body.newLabel();
		ctx.body.if_icmpne(ifNotNewline);
		ctx.body.iconst_0();
		MethodCode.Label gotoStore = ctx.body.newLabel();
		ctx.body.goto_(gotoStore);
		ctx.body.labelBinding(ifNotNewline);
		ctx.body.iconst_1();
		ctx.body.labelBinding(gotoStore);
		ctx.body.putstatic(col.entry());
		ctx.body.labelBinding(ifEmpty);
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		java.util.List<am.ik.rontolisp.LispVal> args = cons.toList();
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (JvmStringStreamCompiler.streamArg); either goes through the
		// handle-aware _freshLine runtime helper.
		am.ik.rontolisp.LispVal stream = JvmStringStreamCompiler.streamArg(ctx, args.size() > 1 ? args.get(1) : null);
		if (stream != null) {
			JvmExprCompiler.compileExpr(stream, ctx, className);
			MethodrefConstant freshLineRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
					ctx.cp.addNameAndType(ctx.cp.addUtf8(JvmIoRuntimeBuilder.FRESH_LINE_METHOD),
							ctx.cp.addUtf8(JvmIoRuntimeBuilder.FRESH_LINE_DESC)));
			ctx.body.invokestatic(freshLineRef.entry());
			return;
		}
		FieldrefConstant col = colField(ctx, className);
		ctx.body.getstatic(col.entry());
		MethodCode.Label ifAtStart = ctx.body.newLabel();
		ctx.body.ifeq(ifAtStart);
		ctx.body.getstatic(ctx.systemOut.entry()).invokevirtual(ctx.printlnVoid.methodRefEntry());
		emitSetLineStart(ctx, className);
		ctx.body.labelBinding(ifAtStart);
		ctx.body.aconst_null();
	}

}
