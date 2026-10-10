package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;

/**
 * {@code ring.adapter.rontolisp} of the Clojure lowering: {@code run-server} serves a
 * Ring handler (request map in, response map out) on the target's native inbound
 * transport. The call lowers to {@code rontolisp::%http-serve} -- the transport legs the
 * {@code clack-handler-rontolisp} shim's {@code run} calls too ({@code http-serve.lisp})
 * -- over the Clack application {@code rontolisp::%clojure-ring-app} makes of the
 * handler, which converts the Clack environment into a Ring request map and the Ring
 * response map into a Clack response ({@code clojure.lisp}). The call names
 * {@code %http-serve} itself, so the compile path splices the transport before the passes
 * that read its legs.
 *
 * <p>
 * Clojure has no documented way to call a Common Lisp function, so the adapter is built
 * in rather than a user library. One slice of {@link ClojureLowering}: every method takes
 * the hub as its first argument and re-enters it for subforms.
 */
final class ClojureRingLowering {

	/** The namespace this slice lowers. */
	static final String NAMESPACE = "ring.adapter.rontolisp";

	/** The {@code ring.adapter.rontolisp} vars. */
	static final Set<String> VARS = Set.of("run-server");

	private static final String SERVE = "RONTOLISP::%HTTP-SERVE";

	/**
	 * The Clack application of a Ring handler, whose request {@code :body} is a
	 * {@code clojure.java.io} byte stream: a producer of the io family.
	 */
	static final String APP = "RONTOLISP::%CLOJURE-RING-APP";

	private ClojureRingLowering() {
	}

	/**
	 * A {@code ring.adapter.rontolisp} call: the var {@code ns} resolution already
	 * vetted, over the call's own items (whose head is ignored).
	 * @param ctx the hub
	 * @param var the var name
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal ringCall(ClojureLowering ctx, String var, List<LispVal> items) {
		if (!var.equals("run-server")) {
			throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
		int n = items.size() - 1;
		if (n != 2) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: " + NAMESPACE + "/" + var);
		}
		LispVal handler = ClojureBindingLowering.fnValue(ctx, items.get(1));
		if (!ClojureBindingLowering.holdsRealFun(ctx, items.get(1), handler)) {
			handler = ClojureLowering.realFun(handler);
		}
		LispSymbol fun = ctx.freshTemp();
		LispSymbol options = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil
			.list(ClojureLowerUtil.list(fun, handler), ClojureLowerUtil.list(options, ctx.lower(items.get(2)))),
				serve(fun, options));
	}

	/**
	 * {@code run-server} as a function value: a two-argument lambda over the same serve,
	 * the handler through the IFn dispatcher when it is no function.
	 * @param var the var name
	 * @return the value form
	 */
	static LispVal ringValue(String var) {
		if (!var.equals("run-server")) {
			throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
		LispSymbol handler = new LispSymbol(ClojureLowering.mangle("run-server-handler"));
		LispSymbol options = new LispSymbol(ClojureLowering.mangle("run-server-options"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("run-server-fn"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(handler, options),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil.list(ClojureLowerUtil.list(fun, ClojureLowering.realFun(handler))),
						serve(fun, options)));
	}

	/**
	 * {@code (%http-serve (%clojure-ring-app f o) port host join)}: the application
	 * first, so a refused option signals before anything binds.
	 */
	private static LispVal serve(LispSymbol fun, LispSymbol options) {
		return ClojureLowerUtil.list(new LispSymbol(SERVE), ClojureLowerUtil.list(new LispSymbol(APP), fun, options),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RING-PORT"), options),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RING-HOST"), options),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RING-JOIN"), options));
	}

}
