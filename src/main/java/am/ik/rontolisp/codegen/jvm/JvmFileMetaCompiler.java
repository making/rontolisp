package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the file-metadata primitives -- {@code file-write-date},
 * {@code %make-directories}, {@code %delete-file}, {@code file-length},
 * {@code %read-link}, the two-argument {@code %rename-file}, {@code %make-link} and
 * {@code %file-stat} and the four-argument {@code %file-set-times} -- as a call of the
 * matching {@code JvmIoRuntimeBuilder} helper. Same shape as
 * {@link JvmProbeFileCompiler}: the arguments are compiled to runtime values and the
 * helper answers rather than signals for anything it cannot determine.
 */
final class JvmFileMetaCompiler {

	private JvmFileMetaCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String name) {
		List<LispVal> parts = cons.toList();
		int arity = switch (name) {
			case LispNames.RENAME_FILE_INTERNAL, LispNames.FILE_STAT_INTERNAL, LispNames.MAKE_LINK_INTERNAL -> 2;
			case LispNames.FILE_SET_TIMES_INTERNAL -> 4;
			default -> 1;
		};
		if (parts.size() != arity + 1) {
			throw new UnsupportedOperationException(
					name + " expects " + arity + " argument(s), got " + (parts.size() - 1));
		}
		for (int i = 1; i <= arity; i++) {
			// file-length's argument is a STREAM designator, not a path: it is resolved
			// to the raw handle the _fileLength helper looks the namestring up by.
			LispVal arg = LispNames.FILE_LENGTH.equals(name)
					? java.util.Objects.requireNonNull(JvmStringStreamCompiler.streamDesignator(ctx, parts.get(i)))
					: parts.get(i);
			JvmExprCompiler.compileExpr(arg, ctx, className);
		}
		String method = switch (name) {
			case LispNames.FILE_WRITE_DATE -> JvmIoRuntimeBuilder.FILE_WRITE_DATE_METHOD;
			case LispNames.MAKE_DIRECTORIES -> JvmIoRuntimeBuilder.MAKE_DIRECTORIES_METHOD;
			case LispNames.DELETE_FILE_INTERNAL -> JvmIoRuntimeBuilder.DELETE_FILE_METHOD;
			case LispNames.FILE_LENGTH -> JvmIoRuntimeBuilder.FILE_LENGTH_METHOD;
			case LispNames.RENAME_FILE_INTERNAL -> JvmIoRuntimeBuilder.RENAME_FILE_METHOD;
			case LispNames.READ_LINK_INTERNAL -> JvmIoRuntimeBuilder.READ_LINK_METHOD;
			case LispNames.MAKE_LINK_INTERNAL -> JvmIoRuntimeBuilder.MAKE_LINK_METHOD;
			case LispNames.FILE_STAT_INTERNAL -> JvmIoRuntimeBuilder.FILE_STAT_METHOD;
			case LispNames.FILE_SET_TIMES_INTERNAL -> JvmIoRuntimeBuilder.FILE_SET_TIMES_METHOD;
			default -> throw new UnsupportedOperationException("Not a file-metadata primitive: " + name);
		};
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(method);
		// The one-argument five share the (Object) -> Object shape of the probe-file
		// helper; the others are the same shape as many arguments wide.
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(switch (arity) {
			case 2 -> JvmIoRuntimeBuilder.RENAME_FILE_DESC;
			case 4 -> JvmIoRuntimeBuilder.FILE_SET_TIMES_DESC;
			default -> JvmIoRuntimeBuilder.PROBE_FILE_DESC;
		});
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(ref);
	}

}
