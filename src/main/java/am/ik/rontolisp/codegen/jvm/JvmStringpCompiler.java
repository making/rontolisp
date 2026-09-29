package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;

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
		// The check depends on nothing but the value, so it lives in one per-class
		// helper (JvmEmitHelper.emitSharedCall) rather than ~90 bytecodes per site.
		JvmEmitHelper.emitSharedCall(ctx, className, "_pStringp", 1, helper -> emitStringpCheck(helper, 0));
	}

	/**
	 * Pushes the {@code stringp} result (the {@code t} symbol, or {@code null} for nil)
	 * for the value held in {@code tempSlot}: a quote-framed {@code java.lang.String}, or
	 * -- when the array runtime helpers are emitted -- a mutable character vector. Split
	 * out from {@link #compile} so the packed {@code array-element-type} lowering can
	 * branch on the same check and answer {@code character} for a string without re-doing
	 * it.
	 * @param ctx the compilation context
	 * @param tempSlot the local holding the value to test
	 */
	static void emitStringpCheck(JvmLispCompiler.Ctx ctx, int tempSlot) {
		ctx.body.aload(tempSlot).instanceOf(ctx.stringClass.entry());
		MethodCode.Label ifNotStringPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotStringPos);
		ctx.body.aload(tempSlot).checkcast(ctx.stringClass.entry()).iconst_0();
		ctx.body.invokevirtual(ctx.stringCharAt.methodRefEntry());
		JvmEmitHelper.emitIntConst(ctx, 34);
		MethodCode.Label nil = ctx.body.newLabel();
		ctx.body.if_icmpne(nil);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEnds = ctx.body.newLabel();
		ctx.body.goto_(gotoEnds);
		ctx.body.labelBinding(ifNotStringPos);
		if (ctx.usesArrays) {
			// A mutable character vector (an ArrayList whose slot-0 header Object[] has
			// length 4) is a string too; the branch exists only when the array helpers
			// are emitted, so array-free programs stay byte-identical.
			ClassConstant arrayListClass = ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList"));
			MethodrefConstant alSize = ctx.cp.addMethodref(arrayListClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("size"), ctx.cp.addUtf8("()I")));
			MethodrefConstant alGet = ctx.cp.addMethodref(arrayListClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("get"), ctx.cp.addUtf8("(I)Ljava/lang/Object;")));
			ctx.body.aload(tempSlot).instanceOf(arrayListClass.entry()).ifeq(nil);
			ctx.body.aload(tempSlot).checkcast(arrayListClass.entry());
			ctx.body.invokevirtual(alSize.methodRefEntry()).ifeq(nil);
			ctx.body.aload(tempSlot).checkcast(arrayListClass.entry()).iconst_0();
			ctx.body.invokevirtual(alGet.methodRefEntry()).instanceOf(ctx.objectArrayClass.entry());
			ctx.body.ifeq(nil);
			ctx.body.aload(tempSlot).checkcast(arrayListClass.entry()).iconst_0();
			ctx.body.invokevirtual(alGet.methodRefEntry()).checkcast(ctx.objectArrayClass.entry());
			ctx.body.arraylength();
			// Header length 4 is a character vector; 7 is a displaced STRING VIEW (a
			// view whose target is a string). Length 3 / 5 / 6 -- the ordinary, the
			// bare array view and the packed shapes -- are not strings.
			ctx.body.dup();
			JvmEmitHelper.emitIntConst(ctx, 7);
			MethodCode.Label ifNotSeven = ctx.body.newLabel();
			ctx.body.if_icmpne(ifNotSeven);
			ctx.body.pop();
			MethodCode.Label gotoIsString = ctx.body.newLabel();
			ctx.body.goto_(gotoIsString);
			ctx.body.labelBinding(ifNotSeven);
			JvmEmitHelper.emitIntConst(ctx, 4);
			ctx.body.if_icmpne(nil);
			ctx.body.labelBinding(gotoIsString);
			JvmEmitHelper.compileTrue(ctx);
			ctx.body.goto_(gotoEnds);
		}
		ctx.body.labelBinding(nil);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEnds);
	}

}
