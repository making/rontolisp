package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code parse-integer} built-in. The string and the {@code :radix} /
 * {@code :junk-allowed} keyword values are compiled as ordinary expressions and passed to
 * the {@code _parseInt} runtime helper. The keyword names must be literal; the
 * {@code :start} / {@code :end} keywords supported by the interpreter are not available
 * on the compiled backend.
 */
final class JvmParseIntegerCompiler {

	private JvmParseIntegerCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() < 2) {
			throw new UnsupportedOperationException("parse-integer expects at least 1 argument");
		}
		LispVal radixExpr = null;
		LispVal junkExpr = null;
		for (int i = 2; i + 1 < args.size(); i += 2) {
			String key = (args.get(i) instanceof LispSymbol s) ? s.name() : "";
			switch (key) {
				case LispNames.RADIX_KEYWORD -> radixExpr = args.get(i + 1);
				case LispNames.JUNK_ALLOWED_KEYWORD -> junkExpr = args.get(i + 1);
				default -> throw new UnsupportedOperationException(
						"parse-integer supports only literal :radix and :junk-allowed on the compiled backend, got: "
								+ key);
			}
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (radixExpr != null) {
			JvmExprCompiler.compileExpr(radixExpr, ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		if (junkExpr != null) {
			JvmExprCompiler.compileExpr(junkExpr, ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(JvmParseIntegerRuntimeBuilder.METHOD),
						ctx.cp.addUtf8(JvmParseIntegerRuntimeBuilder.DESC)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
