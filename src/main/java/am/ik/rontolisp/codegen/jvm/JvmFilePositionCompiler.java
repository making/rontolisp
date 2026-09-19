package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles {@code file-position} -- the one-argument query and the two-argument set -- as
 * a call of the {@code JvmIoRuntimeBuilder} {@code _filePosition(Object, Object)} helper:
 * the stream designator is resolved to a raw handle, the optional position argument
 * compiled as written (or null for the query), and the helper answers rather than signals
 * for anything it cannot determine.
 */
final class JvmFilePositionCompiler {

	private JvmFilePositionCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException(
					"file-position expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		LispVal handle = JvmStringStreamCompiler.streamDesignator(ctx, parts.get(1));
		if (handle != null) {
			JvmExprCompiler.compileExpr(handle, ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		if (parts.size() == 3) {
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.FILE_POSITION_METHOD);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(JvmIoRuntimeBuilder.FILE_POSITION_DESC);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
