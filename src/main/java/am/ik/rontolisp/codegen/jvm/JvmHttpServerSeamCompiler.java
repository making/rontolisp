package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code rontolisp::%http-server-*} seam on the JVM backend: the
 * STOPPABLE counterpart of the {@code http-handler} directive, driven by the
 * {@code clack-handler-rontolisp} shim. {@code %http-server-start} takes the handler as a
 * FUNCTION VALUE (any expression), so it stores the compiled value into the
 * {@code _httpHandlerFn} static field the injected {@code handle(Request)} method
 * dispatches through (the same single-handler slot as the directive -- one clack server
 * per process, documented in the shim), and calls
 * {@code RontoHttpServer.startServer(port, address, new Prog())}, which returns the
 * opaque long handle {@code %http-server-join} / {@code %http-server-stop} /
 * {@code %http-server-port} take back. The address argument is passed as the runtime
 * value (a quote-wrapped string or null); {@code startServer} unwraps it.
 */
final class JvmHttpServerSeamCompiler {

	/** The internal name of the interpreter-shared HTTP server support class. */
	private static final String SUPPORT_CLASS = "am/ik/rontolisp/runtime/RontoHttpServer";

	private JvmHttpServerSeamCompiler() {
	}

	/**
	 * Returns whether the given {@code rontolisp} member is one of the seam functions.
	 * @param member the member name (without the package qualifier)
	 * @return {@code true} when {@link #compile} handles it
	 */
	static boolean handles(String member) {
		return LispNames.HTTP_SERVER_START.equals(member) || LispNames.HTTP_SERVER_JOIN.equals(member)
				|| LispNames.HTTP_SERVER_STOP.equals(member) || LispNames.HTTP_SERVER_PORT.equals(member)
				|| replies(member) >= 0;
	}

	/**
	 * The arity of a primitive of an exchange the application answers itself
	 * ({@code RontoHttpClack.reply*}), or -1 for another member.
	 */
	private static int replies(String member) {
		return switch (member) {
			case LispNames.HTTP_SERVER_TAKE -> 0;
			case LispNames.HTTP_SERVER_HEAD -> 3;
			case LispNames.HTTP_SERVER_WRITE, LispNames.HTTP_SERVER_FINISH -> 2;
			default -> -1;
		};
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		int arity = replies(member);
		if (arity >= 0) {
			// RontoHttpClack.reply*(Object..): the runtime values as they are, nil null
			if (parts.size() != arity + 1) {
				throw new UnsupportedOperationException(
						member + " expects " + arity + " arguments, got " + (parts.size() - 1));
			}
			for (int i = 1; i <= arity; i++) {
				JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
			}
			String method = switch (member) {
				case LispNames.HTTP_SERVER_TAKE -> "replyTake";
				case LispNames.HTTP_SERVER_HEAD -> "replyHead";
				case LispNames.HTTP_SERVER_WRITE -> "replyWrite";
				default -> "replyFinish";
			};
			ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry("am/ik/rontolisp/runtime/RontoHttpClack"), method,
					"(" + "Ljava/lang/Object;".repeat(arity) + ")Ljava/lang/Object;"));
			return;
		}
		ClassEntry supportClass = ctx.cp.classEntry(SUPPORT_CLASS);
		if (LispNames.HTTP_SERVER_START.equals(member)) {
			JvmHttpHandlerRuntimeBuilder.HttpHandlerRuntime runtime = ctx.httpHandlerRuntime;
			if (runtime == null) {
				throw new IllegalStateException(
						LispNames.HTTP_SERVER_START + " runtime was not prepared for this program");
			}
			// The optional trailing (:raw-body mode) keyword pair is compile-time only
			// (ClackEnv.usesBufferedBody read it off the program and the injected
			// handle(Request) was built for that mode), so it is accepted and dropped;
			// only (handler port address) reach the runtime call.
			if (parts.size() != 4 && parts.size() != 6) {
				throw new UnsupportedOperationException(LispNames.HTTP_SERVER_START
						+ " expects (handler port address [:raw-body mode]), got " + (parts.size() - 1));
			}
			// _httpHandlerFn = the handler function value
			JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
			ctx.body.putstatic(runtime.handlerField());
			if (ctx.servletMode) {
				// Servlet mode (-o app.war): the seam REGISTERS AND RETURNS rather than
				// refusing by name. Refusing would be the more honest answer to "a war
				// cannot own a port" -- but the register spelling is the whole point:
				// the clack-handler-rontolisp shim reaches this seam, and registering
				// here is what makes its servlet leg (.kb/clack.md) a feature-gated
				// spelling of the same call instead of a new transport. The port and
				// address are evaluated for their effects and discarded (the container
				// owns both), and the answered handle is the constant 0: join returns
				// at once, stop is a no-op, port answers 0 -- the container's port is
				// not this process's to know.
				JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
				ctx.body.pop();
				JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
				ctx.body.pop().lconst_0();
				JvmEmitHelper.boxLong(ctx);
				return;
			}
			// port (int)
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			ctx.body.l2i();
			// address (runtime value: a quote-wrapped string or null; startServer
			// unwraps)
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
			// RontoHttpServer.startServer(port, address, new Prog())
			ctx.body.new_(runtime.progClass()).dup();
			ctx.body.invokespecial(runtime.progInit());
			MethodRefEntry start = ctx.cp.methodRef(supportClass, "startServer",
					"(ILjava/lang/Object;L" + SUPPORT_CLASS + "$Handler;)J");
			ctx.body.invokestatic(start);
			JvmEmitHelper.boxLong(ctx);
			return;
		}
		// %http-server-stop takes an optional drain flag (RontoHttpServer.stopServer)
		boolean drainFlag = LispNames.HTTP_SERVER_STOP.equals(member) && parts.size() == 3;
		if (parts.size() != 2 && !drainFlag) {
			throw new UnsupportedOperationException(member + " expects a server handle, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		if (ctx.servletMode) {
			// The register-and-return contract's other half (see the start arm above):
			// there is no server of this process's to join, stop or ask.
			ctx.body.pop();
			if (drainFlag) {
				JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
				ctx.body.pop();
			}
			if (LispNames.HTTP_SERVER_PORT.equals(member)) {
				ctx.body.lconst_0();
				JvmEmitHelper.boxLong(ctx);
			}
			else {
				ctx.body.aconst_null();
			}
			return;
		}
		JvmEmitHelper.unboxLong(ctx);
		if (LispNames.HTTP_SERVER_PORT.equals(member)) {
			MethodRefEntry port = ctx.cp.methodRef(supportClass, "serverPort", "(J)J");
			ctx.body.invokestatic(port);
			JvmEmitHelper.boxLong(ctx);
			return;
		}
		if (drainFlag) {
			// nil is null in compiled code
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
			ctx.body.invokestatic(
					ctx.cp.methodRef(ctx.cp.classEntry("java/util/Objects"), "nonNull", "(Ljava/lang/Object;)Z"));
			ctx.body.invokestatic(ctx.cp.methodRef(supportClass, "stopServer", "(JZ)V")).aconst_null();
			return;
		}
		String method = LispNames.HTTP_SERVER_JOIN.equals(member) ? "joinServer" : "stopServer";
		MethodRefEntry ref = ctx.cp.methodRef(supportClass, method, "(J)V");
		ctx.body.invokestatic(ref).aconst_null();
	}

}
