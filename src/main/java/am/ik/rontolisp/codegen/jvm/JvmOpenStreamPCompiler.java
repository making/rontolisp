package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code open-stream-p} built-in: whether the handle still names an open
 * stream. The argument is compiled normally and passed to the {@code _openStreamP}
 * runtime helper, which reads the stream table (a {@code close} nulls the entry).
 */
final class JvmOpenStreamPCompiler {

	private JvmOpenStreamPCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException("open-stream-p expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(
				java.util.Objects.requireNonNull(JvmStringStreamCompiler.streamDesignator(ctx, parts.get(1))), ctx,
				className);
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.OPEN_STREAM_P_METHOD);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.OPEN_STREAM_P_DESC);
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(ref);
	}

}
