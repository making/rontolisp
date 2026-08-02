package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
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
 * {@code HttpHandlerSupport.startServer(port, address, new Prog())}, which returns the
 * opaque long handle {@code %http-server-join} / {@code %http-server-stop} /
 * {@code %http-server-port} take back. The address argument is passed as the runtime
 * value (a quote-wrapped string or null); {@code startServer} unwraps it.
 */
final class JvmHttpServerSeamCompiler {

	/** The internal name of the interpreter-shared HTTP server support class. */
	private static final String SUPPORT_CLASS = "am/ik/rontolisp/eval/HttpHandlerSupport";

	private JvmHttpServerSeamCompiler() {
	}

	/**
	 * Returns whether the given {@code rontolisp} member is one of the seam functions.
	 * @param member the member name (without the package qualifier)
	 * @return {@code true} when {@link #compile} handles it
	 */
	static boolean handles(String member) {
		return LispNames.HTTP_SERVER_START.equals(member) || LispNames.HTTP_SERVER_JOIN.equals(member)
				|| LispNames.HTTP_SERVER_STOP.equals(member) || LispNames.HTTP_SERVER_PORT.equals(member);
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		ConstantPool.ClassConstant supportClass = ctx.cp.addClass(ctx.cp.addUtf8(SUPPORT_CLASS));
		if (LispNames.HTTP_SERVER_START.equals(member)) {
			JvmHttpHandlerRuntimeBuilder.HttpHandlerRuntime runtime = ctx.httpHandlerRuntime;
			if (runtime == null) {
				throw new IllegalStateException(
						LispNames.HTTP_SERVER_START + " runtime was not prepared for this program");
			}
			if (parts.size() != 4) {
				throw new UnsupportedOperationException(
						LispNames.HTTP_SERVER_START + " expects (handler port address), got " + (parts.size() - 1));
			}
			// _httpHandlerFn = the handler function value
			JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
			ctx.emit(Opcode.PUTSTATIC);
			ctx.emitU2(runtime.handlerField().index());
			// port (int)
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.L2I);
			// address (runtime value: a quote-wrapped string or null; startServer
			// unwraps)
			JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
			// HttpHandlerSupport.startServer(port, address, new Prog())
			ctx.emit(Opcode.NEW);
			ctx.emitU2(runtime.progClass().index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.INVOKESPECIAL);
			ctx.emitU2(runtime.progInit().index());
			ConstantPool.MethodrefConstant start = ctx.cp.addMethodref(supportClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("startServer"),
							ctx.cp.addUtf8("(ILjava/lang/Object;L" + SUPPORT_CLASS + "$Handler;)J")));
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(start.index());
			JvmEmitHelper.boxLong(ctx);
			return;
		}
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(member + " expects a server handle, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		if (LispNames.HTTP_SERVER_PORT.equals(member)) {
			ConstantPool.MethodrefConstant port = ctx.cp.addMethodref(supportClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("serverPort"), ctx.cp.addUtf8("(J)J")));
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(port.index());
			JvmEmitHelper.boxLong(ctx);
			return;
		}
		String method = LispNames.HTTP_SERVER_JOIN.equals(member) ? "joinServer" : "stopServer";
		ConstantPool.MethodrefConstant ref = ctx.cp.addMethodref(supportClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8(method), ctx.cp.addUtf8("(J)V")));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
		ctx.emit(Opcode.ACONST_NULL);
	}

}
