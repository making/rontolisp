package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OpenModes;

/**
 * Compiles the {@code open} built-in. The direction must be the literal {@code :input}
 * (default) or {@code :output} keyword and the optional element type the literal
 * {@code 'character} (default) or {@code '(unsigned-byte 8)} (binary) so the file mode is
 * known at compile time; the path argument is compiled to a runtime string and passed to
 * the {@code _open} stream runtime helper, which returns the stream handle.
 */
final class JvmOpenCompiler {

	private JvmOpenCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		cons = OpenModes.normalizeKeywordForm(cons);
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 4) {
			throw new UnsupportedOperationException("open expects 1 to 3 arguments, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// 5 and 7 are the OpenModes.APPEND_BIT arms, so the constant no longer fits
		// ICONST_0..3.
		JvmEmitHelper.emitIntConst(ctx, OpenModes.staticMode(parts));
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.OPEN_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.OPEN_DESC);
		MethodrefConstant openRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(openRef.index());
	}

}
