package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;

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

	static FieldRefEntry colField(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.fieldRef(ctx.cp.classEntry(className), COL_FIELD, COL_DESC);
	}

	private static MethodRefEntry stringLength(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.methodRef(ctx.stringClass, "length", "()I");
	}

	/** Emits {@code _col = 0} (the output ended at the start of a line). */
	static void emitSetLineStart(JvmLispCompiler.Ctx ctx, String className) {
		ctx.body.iconst_0().putstatic(colField(ctx, className));
	}

	/**
	 * Emits code that updates {@code _col} from the string held in local {@code slot}: 0
	 * if it ends with a newline, 1 if it ends with any other character, unchanged if
	 * empty.
	 */
	static void emitTrackLocal(JvmLispCompiler.Ctx ctx, String className, int slot) {
		FieldRefEntry col = colField(ctx, className);
		MethodRefEntry length = stringLength(ctx);
		ctx.body.aload(slot).invokevirtual(length);
		MethodCode.Label ifEmpty = ctx.body.newLabel();
		ctx.body.ifeq(ifEmpty);
		ctx.body.aload(slot).aload(slot).invokevirtual(length).iconst_1().isub();
		ctx.body.invokevirtual(ctx.stringCharAt).loadConstant(10);
		MethodCode.Label ifNotNewline = ctx.body.newLabel();
		ctx.body.if_icmpne(ifNotNewline);
		ctx.body.iconst_0();
		MethodCode.Label gotoStore = ctx.body.newLabel();
		ctx.body.goto_(gotoStore);
		ctx.body.labelBinding(ifNotNewline);
		ctx.body.iconst_1();
		ctx.body.labelBinding(gotoStore);
		ctx.body.putstatic(col);
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
			MethodRefEntry freshLineRef = ctx.cp.methodRef(ctx.cp.classEntry(className),
					JvmIoRuntimeBuilder.FRESH_LINE_METHOD, JvmIoRuntimeBuilder.FRESH_LINE_DESC);
			ctx.body.invokestatic(freshLineRef);
			return;
		}
		FieldRefEntry col = colField(ctx, className);
		ctx.body.getstatic(col);
		MethodCode.Label ifAtStart = ctx.body.newLabel();
		ctx.body.ifeq(ifAtStart);
		ctx.body.getstatic(ctx.systemOut).invokevirtual(ctx.printlnVoid);
		emitSetLineStart(ctx, className);
		ctx.body.labelBinding(ifAtStart);
		ctx.body.aconst_null();
	}

}
