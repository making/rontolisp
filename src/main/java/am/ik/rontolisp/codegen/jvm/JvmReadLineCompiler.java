package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.MethodCode;

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

	/**
	 * Compiles {@code (%read-line-pair [stream])}, the read under a {@code read-line}
	 * producer's multiple-value lowering: the {@code _readLinePair} helper's cons
	 * {@code (line . missing-newline-p)}, or nil at end of file, under the same
	 * designator rule as {@code read-line}. The line carries a writable identity, as
	 * {@code read-line}'s does ({@code _toMutStr}).
	 */
	static void compilePair(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException(
					"%read-line-pair expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		LispVal stream = JvmStringStreamCompiler.inputStreamArg(ctx, parts.size() == 2 ? parts.get(1) : null);
		if (stream == null) {
			ctx.body.aconst_null();
		}
		else {
			JvmExprCompiler.compileExpr(stream, ctx, className);
		}
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.READ_LINE_PAIR_METHOD);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.READ_LINE_PAIR_DESC);
		ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8));
		if (!ctx.mutableStringProducers) {
			return;
		}
		// if (pair != null) pair[0] = _toMutStr(pair[0]);
		ctx.body.dup();
		MethodCode.Label atEnd = ctx.body.newLabel();
		ctx.body.ifnull(atEnd);
		ctx.body.checkcast(ctx.cp.classEntry("[Ljava/lang/Object;"));
		ctx.body.dup();
		ctx.body.iconst_0();
		ctx.body.dup2();
		ctx.body.aaload();
		JvmArrayCompiler.emitToMutStr(ctx, className);
		ctx.body.aastore();
		ctx.body.labelBinding(atEnd);
	}

}
