package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code (rontolisp:http-handler 'name [port])} directive on the JVM
 * backend: stores the handler funcref (resolved like {@code #'name} against the Pass-1
 * function registry) into the {@code _httpHandlerFn} static field and calls
 * {@code HttpHandlerSupport.serve(port, new Prog())} -- the generated class itself
 * implements {@code HttpHandlerSupport.Handler} (see
 * {@link JvmHttpHandlerRuntimeBuilder}), so the fresh instance is the handler.
 * {@code serve} blocks forever; the nil result is emitted for stack discipline only.
 */
final class JvmHttpHandlerCompiler {

	private JvmHttpHandlerCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmHttpHandlerRuntimeBuilder.HttpHandlerRuntime runtime = ctx.httpHandlerRuntime;
		if (runtime == null) {
			throw new IllegalStateException(LispNames.HTTP_HANDLER + " runtime was not prepared for this program");
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException(
					LispNames.HTTP_HANDLER + " expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		if (!(parts.get(1) instanceof LispCons quoteForm && quoteForm.car() instanceof LispSymbol quoteSym
				&& LispNames.QUOTE.equals(quoteSym.name()) && quoteForm.cdr() instanceof LispCons nameCell
				&& nameCell.car() instanceof LispSymbol nameSym)) {
			throw new UnsupportedOperationException(
					LispNames.HTTP_HANDLER + " expects a quoted handler name, got: " + parts.get(1).print());
		}
		// _httpHandlerFn = #'name
		JvmFunctionFormCompiler.compileNamed(nameSym.name(), ctx, className);
		ctx.emit(Opcode.PUTSTATIC);
		ctx.emitU2(runtime.handlerField().index());
		// port (int); default 8080
		if (parts.size() == 3) {
			JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.longClass.index());
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(ctx.longValue.index());
			ctx.emit(Opcode.L2I);
		}
		else {
			JvmEmitHelper.emitIntConst(ctx, 8080);
		}
		// HttpHandlerSupport.serve(port, new Prog())
		ctx.emit(Opcode.NEW);
		ctx.emitU2(runtime.progClass().index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(runtime.progInit().index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(runtime.serve().index());
		ctx.emit(Opcode.ACONST_NULL);
	}

}
