package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %list-directory} internal primitive: {@code (t . names)} for a
 * readable directory, nil otherwise. The path argument is compiled to a runtime string
 * and passed to the {@code _listDirectory} runtime helper, which -- like
 * {@code _probeFile} -- answers rather than signals for a path that is not there.
 * Everything user-facing ({@code directory}, the {@code uiop:} spellings) is Lisp source
 * over this call, in {@code LispPreludeLibrary}.
 */
final class JvmListDirectoryCompiler {

	private JvmListDirectoryCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.LIST_DIRECTORY + " expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.LIST_DIRECTORY_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.LIST_DIRECTORY_DESC);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
