package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;

/**
 * Compiles the {@code gensym} built-in function. The compiled class holds a static
 * {@code _gensymCtr} counter field; each call increments it and returns the fresh symbol
 * {@code #:<prefix><n>} (a bare String at runtime, like every symbol). The optional
 * prefix must be a literal string so the {@code "#:" + prefix} part folds to a single
 * constant at compile time (same design as {@code open}'s literal {@code :direction}).
 */
final class JvmGensymCompiler {

	static final String CTR_FIELD = "_gensymCtr";

	static final String CTR_DESC = "I";

	private JvmGensymCompiler() {
	}

	static FieldrefConstant ctrField(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.addFieldref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(CTR_FIELD), ctx.cp.addUtf8(CTR_DESC)));
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() > 2) {
			throw new UnsupportedOperationException(LispNames.GENSYM + " expects at most 1 argument: " + cons.print());
		}
		FieldrefConstant ctr = ctrField(ctx, className);
		MethodrefConstant intToString = ctx.cp.addMethodref(ctx.integerClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("toString"), ctx.cp.addUtf8("(I)Ljava/lang/String;")));
		MethodrefConstant concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		if (args.size() == 2 && args.get(1) instanceof LispInteger n) {
			// CL's other gensym shape: a non-negative integer IS the suffix (under the
			// default "G" prefix), and it never touches the counter -- a compile-time
			// constant, like the literal-string branch below (todo a42).
			if (n.value() < 0) {
				throw new UnsupportedOperationException(
						LispNames.GENSYM + " suffix must be a non-negative integer: " + cons.print());
			}
			JvmEmitHelper.compileStringLiteral("#:G" + n.value(), ctx);
			return;
		}
		if (args.size() == 2 && !(args.get(1) instanceof LispString)) {
			// A computed prefix: the shared string-construction lowering (the interned
			// prefix text below is a compile-time constant, so it has no place here).
			JvmExprCompiler.compileExpr(am.ik.rontolisp.macro.LispMacroExpander.expandComputedGensym(args.get(1)), ctx,
					className);
			return;
		}
		String prefix = args.size() == 2 ? ((LispString) args.get(1)).value() : "G";
		// "#:prefix".concat(Integer.toString(++_gensymCtr))
		JvmEmitHelper.compileStringLiteral("#:" + prefix, ctx);
		ctx.body.getstatic(ctr.entry()).iconst_1().iadd().dup().putstatic(ctr.entry());
		ctx.body.invokestatic(intToString.entry()).invokevirtual(concat.methodRefEntry());
	}

}
