package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code read-line} built-in function. Without an argument it reads from
 * standard input via the {@code _readLine} helper; with a stream-handle argument it reads
 * from that stream via the {@code _readLineStream} stream runtime helper. The CL 3-arg
 * {@code (read-line stream eof-error-p eof-value)} shape is rewritten first through
 * {@link LispMacroExpander#expandReadLineCompat}: with a literal-nil {@code eof-error-p}
 * it drops back to the 1-arg form (both runtime helpers already return nil at EOF), so a
 * per-line loop over a file works as-is on the compile path.
 */
final class JvmReadLineCompiler {

	private JvmReadLineCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal rewritten = LispMacroExpander.expandReadLineCompat(cons);
		if (rewritten != null) {
			JvmExprCompiler.compileExpr(rewritten, ctx, className);
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException("read-line expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		// The source, under CL's stream designator rule: an explicit stream, or -- for an
		// omitted argument AND for an explicit nil -- the current *standard-input*
		// (JvmStringStreamCompiler.inputStreamArg). A program that never binds it keeps
		// the hard-coded standard input.
		LispVal stream = JvmStringStreamCompiler.inputStreamArg(ctx, parts.size() == 2 ? parts.get(1) : null);
		if (stream == null) {
			ctx.body.invokestatic(ctx.readLineHelper);
			return;
		}
		JvmExprCompiler.compileExpr(stream, ctx, className);
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.READ_LINE_STREAM_METHOD);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.READ_LINE_STREAM_DESC);
		MethodRefEntry readLineStreamRef = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(readLineStreamRef);
	}

}
