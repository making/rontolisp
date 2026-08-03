package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the one-argument file-metadata primitives -- {@code file-write-date},
 * {@code %make-directories}, {@code %delete-file} and {@code file-length} -- as a call of
 * the matching {@code JvmIoRuntimeBuilder} helper. Same shape as
 * {@link JvmProbeFileCompiler}: the argument is compiled to a runtime value and the
 * helper answers rather than signals for anything it cannot determine.
 */
final class JvmFileMetaCompiler {

	private JvmFileMetaCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String name) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(name + " expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		String method = switch (name) {
			case LispNames.FILE_WRITE_DATE -> JvmIoRuntimeBuilder.FILE_WRITE_DATE_METHOD;
			case LispNames.MAKE_DIRECTORIES -> JvmIoRuntimeBuilder.MAKE_DIRECTORIES_METHOD;
			case LispNames.DELETE_FILE_INTERNAL -> JvmIoRuntimeBuilder.DELETE_FILE_METHOD;
			case LispNames.FILE_LENGTH -> JvmIoRuntimeBuilder.FILE_LENGTH_METHOD;
			default -> throw new UnsupportedOperationException("Not a file-metadata primitive: " + name);
		};
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(method);
		// All four share the (Object) -> Object shape of the probe-file helper.
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.PROBE_FILE_DESC);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
