package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code read} built-in. Invokes the {@code _read} runtime helper, which
 * parses one S-expression from a line of stdin into the runtime value representation.
 */
final class JvmReadCompiler {

	private JvmReadCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Utf8Constant nameUtf8 = ctx.cp.addUtf8("_read");
		Utf8Constant descUtf8 = ctx.cp.addUtf8("()Ljava/lang/Object;");
		MethodrefConstant readRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(readRef.index());
	}

}
