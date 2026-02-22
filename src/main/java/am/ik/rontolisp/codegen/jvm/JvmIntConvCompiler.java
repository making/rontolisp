package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

/**
 * Compiles integer conversion functions ({@code truncate}, {@code floor},
 * {@code ceiling}, {@code round}). All convert a number to an integer: truncate toward
 * zero, floor toward negative infinity, ceiling toward positive infinity, round to
 * nearest even.
 */
final class JvmIntConvCompiler {

	private JvmIntConvCompiler() {
	}

	static void compileTruncate(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, null);
	}

	static void compileFloor(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, "floor");
	}

	static void compileCeiling(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, "ceil");
	}

	static void compileRound(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, "rint");
	}

	private static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, @Nullable String mathMethod) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxDouble(ctx);
		if (mathMethod != null) {
			ConstantPool.ClassConstant mathClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Math"));
			ConstantPool.MethodrefConstant method = ctx.cp.addMethodref(mathClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8(mathMethod), ctx.cp.addUtf8("(D)D")));
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(method.index());
		}
		ctx.emit(Opcode.D2L);
		JvmEmitHelper.boxLong(ctx);
	}

}
