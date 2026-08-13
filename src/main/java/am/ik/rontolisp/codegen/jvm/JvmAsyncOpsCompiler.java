package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the async/await member functions of the {@code rontolisp} package that map 1:1
 * onto {@code JvmAsyncRuntimeBuilder} helpers: {@code %async-run} (the lowered
 * {@code async-defun}/{@code async-lambda} primitive), {@code futurep}, {@code streamp},
 * {@code make-stream}, {@code %stream-new} (the internal from-thunk PULL constructor),
 * {@code stream-read}, {@code stream-write} and {@code stream-close}. Each compiles its
 * evaluated arguments and one {@code invokestatic}.
 */
final class JvmAsyncOpsCompiler {

	private JvmAsyncOpsCompiler() {
	}

	static boolean handles(String member) {
		return switch (member) {
			case LispNames.ASYNC_RUN, LispNames.FUTUREP, LispNames.ASYNC_STREAMP, LispNames.MAKE_STREAM,
					LispNames.STREAM_NEW_INTERNAL, LispNames.STREAM_READ, LispNames.STREAM_WRITE,
					LispNames.STREAM_CLOSE, LispNames.WAIT_FOR ->
				true;
			default -> false;
		};
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int arity = switch (member) {
			case LispNames.MAKE_STREAM -> 0;
			case LispNames.STREAM_NEW_INTERNAL, LispNames.STREAM_WRITE -> 2;
			default -> 1;
		};
		if (args.size() != arity + 1) {
			throw new UnsupportedOperationException(member + " expects " + arity + " argument" + (arity == 1 ? "" : "s")
					+ ", got " + (args.size() - 1));
		}
		MethodrefConstant helper = switch (member) {
			case LispNames.ASYNC_RUN -> ctx.asyncRunHelper;
			case LispNames.FUTUREP -> ctx.futurepHelper;
			case LispNames.ASYNC_STREAMP -> ctx.streampHelper;
			case LispNames.MAKE_STREAM -> ctx.makeStreamHelper;
			case LispNames.STREAM_NEW_INTERNAL -> ctx.streamNewHelper;
			case LispNames.STREAM_READ -> ctx.streamReadHelper;
			case LispNames.STREAM_WRITE -> ctx.streamWriteHelper;
			case LispNames.STREAM_CLOSE -> ctx.streamCloseHelper;
			case LispNames.WAIT_FOR -> ctx.waitForHelper;
			default -> null;
		};
		if (helper == null) {
			throw new IllegalStateException(member + " helper method was not emitted");
		}
		for (int i = 1; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(helper.index());
	}

}
