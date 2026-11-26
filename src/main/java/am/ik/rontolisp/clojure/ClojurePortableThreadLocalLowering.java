package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The single-threaded {@code ThreadLocal} cell test.check's seedless {@code next-rng}
 * builds ({@code .kb/clojure-frontend.md}, "Portable ThreadLocal"): a
 * {@code (proxy [ThreadLocal] [] (initialValue [] ...))} where the host is not (wasm) is
 * the portable cell -- the {@code (:C%THREAD-LOCAL value
 * has init)} list its {@code .get} lazily fills through the {@code initialValue} body,
 * its {@code .set} stores into and its {@code .remove} clears -- never a
 * {@code java:subclass}, which wasm refuses at call time and which would kill the
 * module's start function while the namespace loads. Wasm runs one thread, so no thread
 * key is kept; the body sees {@code this}, the cell, like the oracle's proxy body sees
 * its proxy. Where the host is the proxy keeps the host's subclass and calls.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableThreadLocalLowering {

	/** Whether a value is a portable {@code ThreadLocal} cell. */
	static final String THREAD_LOCAL_P = "RONTOLISP::%CLOJURE-THREAD-LOCAL-P";

	/** A portable {@code ThreadLocal} cell over an {@code initialValue} thunk. */
	static final String THREAD_LOCAL = "RONTOLISP::%CLOJURE-THREAD-LOCAL";

	/** {@code (.get cell)}: the stored value, or the computed and stored initial. */
	static final String THREAD_LOCAL_GET = "RONTOLISP::%CLOJURE-THREAD-LOCAL-GET";

	/** {@code (.set cell v)}: store {@code v}, answering nil. */
	static final String THREAD_LOCAL_SET = "RONTOLISP::%CLOJURE-THREAD-LOCAL-SET";

	/** {@code (.remove cell)}: clear the cell, answering nil. */
	static final String THREAD_LOCAL_REMOVE = "RONTOLISP::%CLOJURE-THREAD-LOCAL-REMOVE";

	/** The cell's members by the argument count each takes. */
	static final Map<String, List<Integer>> THREAD_LOCAL_METHODS = Map.of("get", List.of(0), "set", List.of(1),
			"remove", List.of(0));

	private ClojurePortableThreadLocalLowering() {
	}

	/**
	 * A {@code (proxy [ThreadLocal] [] ...)} as its portable cell, where the host is not:
	 * the caller checks the target. Null when the proxy takes constructor arguments,
	 * which stay the host's {@code java:subclass} (a call-time refusal where no host is,
	 * as before). Any other method but a parameterless {@code initialValue} is refused by
	 * name, before it runs.
	 * @param ctx the hub
	 * @param items the {@code proxy} form's items
	 * @return the cell construction, or null
	 */
	static @Nullable LispVal cellOf(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> argv = ClojureLowerUtil.items(items.get(2));
		if (argv == null || argv.size() != 1) {
			return null;
		}
		LispVal init = null;
		for (int i = 3; i < items.size(); i++) {
			List<LispVal> meth = ClojureLowerUtil.items(items.get(i));
			if (meth == null || meth.size() < 2 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			if (!methodName.equals("initialValue")) {
				throw new LispReadException("proxy of ThreadLocal supports initialValue, not " + methodName);
			}
			if (init != null) {
				throw new LispReadException("proxy defines method initialValue twice");
			}
			List<LispVal> params = ClojureLowerUtil.items(meth.get(1));
			if (params == null || params.size() != 1 || params.get(0) != ClojureReader.VECTOR) {
				throw new LispReadException("proxy of ThreadLocal takes a parameterless initialValue");
			}
			LispSymbol thisSym = ctx.localSym("this");
			Map<String, ClojureLowering.Kind> scope = new HashMap<>();
			scope.put("this", ClojureLowering.Kind.VARIABLE);
			Map<String, ClojureLowering.Kind> use = new HashMap<>(scope);
			LispVal run = ctx.inScope(use, () -> ctx.bodyOf(meth.subList(2, meth.size())));
			init = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(thisSym)), run);
		}
		if (init == null) {
			LispSymbol thisSym = ctx.localSym("this");
			init = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(thisSym)),
					ClojureLowering.NIL_CONST);
		}
		return ClojureLowerUtil.list(new LispSymbol(THREAD_LOCAL), init);
	}

	/**
	 * An instance call of a portable cell's member where the host is not (wasm): a
	 * portable cell answers its worker, anything else what the call lowered to. The
	 * caller checks the method and the count.
	 * @param method the method name
	 * @param recv the bound receiver
	 * @param args the lowered arguments
	 * @param otherwise the form for any other receiver
	 * @return the gated call
	 */
	static LispVal arm(String method, LispSymbol recv, List<LispVal> args, LispVal otherwise) {
		String worker = switch (method) {
			case "get" -> THREAD_LOCAL_GET;
			case "set" -> THREAD_LOCAL_SET;
			case "remove" -> THREAD_LOCAL_REMOVE;
			default -> throw new IllegalStateException("no portable cell member: " + method);
		};
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(worker));
		call.add(recv);
		call.addAll(args);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(THREAD_LOCAL_P), recv), ClojureLowerUtil.list(call), otherwise);
	}

}
