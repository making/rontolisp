package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code open} built-in. The direction must be the literal {@code :input}
 * (default) or {@code :output} keyword so the file mode is known at compile time; the
 * path argument is compiled to a runtime string and passed to the {@code _open} stream
 * runtime helper, which returns the stream handle.
 */
final class JvmOpenCompiler {

	private JvmOpenCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException("open expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		ctx.emit(staticMode(parts) == 0 ? Opcode.ICONST_0 : Opcode.ICONST_1);
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.OPEN_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.OPEN_DESC);
		MethodrefConstant openRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(openRef.index());
	}

	/**
	 * Resolves the literal direction keyword to the file mode (0 = input, 1 = output).
	 * @param parts the open form parts
	 * @return the file mode
	 */
	static int staticMode(List<LispVal> parts) {
		if (parts.size() < 3) {
			return 0;
		}
		if (parts.get(2) instanceof LispSymbol dir) {
			if (LispNames.INPUT_KEYWORD.equals(dir.name())) {
				return 0;
			}
			if (LispNames.OUTPUT_KEYWORD.equals(dir.name())) {
				return 1;
			}
		}
		throw new UnsupportedOperationException("open requires a literal :input or :output direction");
	}

}
