package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the file-metadata primitives -- {@code file-write-date},
 * {@code %make-directories}, {@code %delete-file}, {@code file-length} and the
 * two-argument {@code %rename-file} -- as a call of the matching
 * {@code JvmIoRuntimeBuilder} helper. Same shape as {@link JvmProbeFileCompiler}: the
 * arguments are compiled to runtime values and the helper answers rather than signals for
 * anything it cannot determine.
 */
final class JvmFileMetaCompiler {

	private JvmFileMetaCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String name) {
		List<LispVal> parts = cons.toList();
		int arity = LispNames.RENAME_FILE_INTERNAL.equals(name) ? 2 : 1;
		if (parts.size() != arity + 1) {
			throw new UnsupportedOperationException(
					name + " expects " + arity + " argument(s), got " + (parts.size() - 1));
		}
		for (int i = 1; i <= arity; i++) {
			JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
		}
		String method = switch (name) {
			case LispNames.FILE_WRITE_DATE -> JvmIoRuntimeBuilder.FILE_WRITE_DATE_METHOD;
			case LispNames.MAKE_DIRECTORIES -> JvmIoRuntimeBuilder.MAKE_DIRECTORIES_METHOD;
			case LispNames.DELETE_FILE_INTERNAL -> JvmIoRuntimeBuilder.DELETE_FILE_METHOD;
			case LispNames.FILE_LENGTH -> JvmIoRuntimeBuilder.FILE_LENGTH_METHOD;
			case LispNames.RENAME_FILE_INTERNAL -> JvmIoRuntimeBuilder.RENAME_FILE_METHOD;
			default -> throw new UnsupportedOperationException("Not a file-metadata primitive: " + name);
		};
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(method);
		// The one-argument four share the (Object) -> Object shape of the probe-file
		// helper; %rename-file is the same shape one argument wider.
		Utf8Constant descUtf8 = ctx.cp.addUtf8(LispNames.RENAME_FILE_INTERNAL.equals(name)
				? JvmIoRuntimeBuilder.RENAME_FILE_DESC : JvmIoRuntimeBuilder.PROBE_FILE_DESC);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
