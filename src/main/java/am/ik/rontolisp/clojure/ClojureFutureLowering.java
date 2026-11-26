package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The Clojure futures and promises of the lowering ({@code future}, {@code future-call},
 * {@code promise}, {@code deliver}, and the future and promise arms of the predicates and
 * {@code deref}): a future is {@code (:C%FUTURE task failure-cell)}, the host
 * {@code FutureTask} running its body on its own thread, and a promise
 * {@code (:C%PROMISE cell)}, delivered once and read by waiting -- on the interpreter and
 * the JVM, where threads exist. On wasm, single-threaded by construction, the call
 * refuses, in the same call-time shape as every other refusal of a construct the oracle
 * accepts.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureFutureLowering {

	private ClojureFutureLowering() {
	}

	/** The runner spawning the host task and wrapping it (the FUTURE producer). */
	static final String FUTURE_START = "RONTOLISP::%CLOJURE-FUTURE-START";

	/** The guard storing a failing body's condition ahead of the host call. */
	static final String FUTURE_GUARD = "RONTOLISP::%CLOJURE-FUTURE-GUARD";

	/** The deref of a Clojure future, wrapping the stored condition. */
	static final String FUTURE_CLOJURE_GET = "RONTOLISP::%CLOJURE-FUTURE-CLOJURE-GET";

	/** The timed deref of a Clojure future, wrapping the stored condition. */
	static final String FUTURE_CLOJURE_GET_WITHIN = "RONTOLISP::%CLOJURE-FUTURE-CLOJURE-GET-WITHIN";

	/** The windowed in-order deref behind parallel `pmap`. */
	static final String PMAP_WINDOW = "RONTOLISP::%CLOJURE-PMAP-WINDOW";

	/** The promise constructor (the FUTURE producer beside the runner). */
	static final String PROMISE_MAKE = "RONTOLISP::%CLOJURE-PROMISE";

	/** The promise store behind the checked {@code deliver}. */
	static final String PROMISE_DELIVER = "RONTOLISP::%CLOJURE-DELIVER";

	/** The blocking promise read behind {@code deref}. */
	static final String PROMISE_GET = "RONTOLISP::%CLOJURE-PROMISE-GET";

	/** The timed promise read behind the three-argument {@code deref}. */
	static final String PROMISE_GET_WITHIN = "RONTOLISP::%CLOJURE-PROMISE-GET-WITHIN";

	/** The test of a Clojure future. */
	static final String WRAPPER_P = "RONTOLISP::%CLOJURE-FUTURE-WRAPPER-P";

	/** The host task inside a Clojure future. */
	static final String FUTURE_TASK = "RONTOLISP::%CLOJURE-FUTURE-TASK";

	/** The test of a Clojure promise. */
	static final String PROMISE_P = "RONTOLISP::%CLOJURE-PROMISE-P";

	/**
	 * {@code (future body...)}: the body behind {@code (future-call (fn [] body...))}, so
	 * the body's own {@code recur} still targets the function, like the oracle's macro.
	 * Where no thread exists the call refuses.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal futureOf(ClojureLowering ctx, List<LispVal> items) {
		if (!ctx.hostTarget) {
			return ClojureCoreLowering.refusedCall(ctx, items, refusalMessage("future"));
		}
		List<LispVal> fn = new ArrayList<>();
		fn.add(new LispSymbol("fn"));
		fn.add(ClojureLowerUtil.list(ClojureReader.VECTOR));
		fn.addAll(items.subList(1, items.size()));
		return futureCallForm(ctx, ClojureLowerUtil.list(fn));
	}

	/**
	 * {@code (future-call f)}: the host {@code FutureTask} over {@code f} conveyed
	 * through {@code bound-fn*} (the creation-time bindings reach the new thread),
	 * started on its own thread and wrapped. Anything answers the call -- a body that is
	 * no function fails in its own thread, like the oracle's, whose {@code deref} then
	 * throws. Where no thread exists the call refuses.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal futureCallOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("future-call", items.size() - 1, 1, 1);
		if (!ctx.hostTarget) {
			return ClojureCoreLowering.refusedCall(ctx, items, refusalMessage("future-call"));
		}
		return futureCallForm(ctx, items.get(1));
	}

	/**
	 * The lowered {@code (future-call ...)} over the function datum: the function
	 * conveyed through {@code bound-fn*} (the creation-time bindings reach the new
	 * thread) guarded by the failure cell (the body's condition is stored before it
	 * crosses the host call, so {@code deref} wraps the original) and handed to the host
	 * {@code FutureTask}, which the runner starts on its own thread. A Clojure fn passed
	 * where an interface is expected implements every abstract method by the method's
	 * arguments, so no {@code proxy} form is built.
	 * @param ctx the hub
	 * @param fun the function datum, lowered here
	 * @return the lowered call
	 */
	private static LispVal futureCallForm(ClojureLowering ctx, LispVal fun) {
		LispVal conveyed = ClojureStateLowering.boundFnStarOf(ctx, List.of(new LispSymbol("bound-fn*"), fun));
		return futureSpawnForm(ctx, conveyed);
	}

	/**
	 * The spawned task over an already-lowered zero-argument thunk: the failure cell, the
	 * guard running it on the new thread, the host {@code FutureTask} and the runner
	 * answering the Clojure future. The thunk is a value where the form runs -- evaluated
	 * on the calling thread for a call, run on a fresh one for a spawned body.
	 * @param ctx the hub
	 * @param thunk the lowered zero-argument thunk
	 * @return the lowered spawn
	 */
	static LispVal futureSpawnForm(ClojureLowering ctx, LispVal thunk) {
		LispSymbol cell = ctx.freshTemp();
		LispSymbol worker = ctx.freshTemp();
		LispVal guarded = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(),
				ClojureLowerUtil.list(new LispSymbol(FUTURE_GUARD), cell, worker));
		LispVal task = ClojureInteropLowering.hostCall(ctx, ClojureInteropLowering.JAVA_NEW,
				List.of(LispString.literal("java.util.concurrent.FutureTask"), guarded), 1);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(cell,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(worker, thunk))),
				ClojureLowerUtil.list(new LispSymbol(FUTURE_START), task, cell));
	}

	/**
	 * {@code (promise)}: the undelivered cell. Where no thread can wait on one the call
	 * refuses.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal promiseOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("promise", items.size() - 1, 0, 0);
		if (!ctx.hostTarget) {
			return ClojureCoreLowering.refusedCall(ctx, items, refusalMessage("promise"));
		}
		return ClojureLowerUtil.list(new LispSymbol(PROMISE_MAKE));
	}

	/**
	 * {@code (deliver p v)}: the store over a checked promise, answering it -- a second
	 * delivery keeping the first value, like the oracle's. Anything but a promise is the
	 * oracle's cast failure. Where no thread can wait on one the call refuses.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal deliverOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("deliver", items.size() - 1, 2, 2);
		if (!ctx.hostTarget) {
			return ClojureCoreLowering.refusedCall(ctx, items, refusalMessage("deliver"));
		}
		return deliverForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
	}

	/**
	 * The checked store over already-lowered forms: the delivery, else the oracle's cast
	 * failure over the value.
	 * @param ctx the hub
	 * @param promise the lowered promise
	 * @param value the lowered value
	 * @return the lowered call
	 */
	private static LispVal deliverForm(ClojureLowering ctx, LispVal promise, LispVal value) {
		LispSymbol cell = ctx.freshTemp();
		LispVal stored = ClojureLowerUtil.list(new LispSymbol(PROMISE_DELIVER), cell, value);
		LispVal checked = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), promiseTest(cell), stored, ClojureRefusals
			.refusal(ClojureRefusals.CLASS_CAST_OF, LispString.literal("deliver needs a promise"), cell));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, promise))), checked);
	}

	/**
	 * {@code future-call} as a value: over its function, the same spawned task a call
	 * answers, capturing the bindings where the value is called.
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal futureCallValue(ClojureLowering ctx) {
		LispSymbol fun = ctx.freshTemp();
		LispVal form = ctx.inScope(Map.of(fun.name(), ClojureLowering.Kind.VARIABLE), () -> futureCallForm(ctx, fun));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(ctx.localSym(fun.name())),
				form);
	}

	/**
	 * {@code pmap} as a value: over a function and any number of collections, the same
	 * parallel map a call answers (sequential where no thread exists).
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal pmapValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("pmap-fn"));
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("pmap-colls"));
		LispVal body = ctx.hostTarget ? pmapParallel(ctx, ClojureLowering.realFun(fun), colls)
				: ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), ClojureLowering.realFun(fun), colls);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fun, ClojureLowering.AMPERSAND_REST, colls)), body);
	}

	/**
	 * {@code (pmap f colls...)} on the interpreter and the JVM: one future per element,
	 * started eagerly and dereferenced in order with the oracle's lookahead, so the
	 * printed seq is the sequential one's; a lazy input stays lazy and sequential, like
	 * `%CLOJURE-MAP` (nothing hangs that did not hang). A failure surfaces at the deref
	 * in order, after every branch was started.
	 * @param ctx the hub
	 * @param fun the already-lowered real function
	 * @param colls the already-lowered collections, as a list value
	 * @return the lowered parallel map
	 */
	static LispVal pmapParallel(ClojureLowering ctx, LispVal fun, LispVal colls) {
		LispSymbol funVar = ctx.freshTemp();
		LispSymbol conveyedVar = ctx.freshTemp();
		LispSymbol collsVar = ctx.freshTemp();
		LispSymbol argsVar = ctx.freshTemp();
		LispVal conveyed = ClojureStateLowering.boundFnStarCall(ctx, funVar);
		LispVal thunk = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), conveyedVar, argsVar));
		LispVal spawner = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, argsVar)), futureSpawnForm(ctx, thunk));
		LispVal futures = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), spawner, collsVar);
		LispVal staged = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DOALL"), futures);
		LispVal windowed = ClojureLowerUtil.list(new LispSymbol(PMAP_WINDOW), staged, lookaheadForm(ctx));
		LispVal sequential = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), funVar, collsVar);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(funVar, fun),
						ClojureLowerUtil.list(conveyedVar, conveyed), ClojureLowerUtil.list(collsVar, colls))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ANY-LAZY-P"), collsVar), sequential,
						windowed));
	}

	/**
	 * The oracle's pmap lookahead: two futures plus one per processor, so at most that
	 * many branches run ahead of the consumption.
	 * @param ctx the hub
	 * @return the lowered lookahead count
	 */
	private static LispVal lookaheadForm(ClojureLowering ctx) {
		LispVal procs = ClojureLowerUtil.list(new LispSymbol(".availableProcessors"),
				ClojureLowerUtil.list(new LispSymbol("Runtime/getRuntime")));
		return ctx.lower(ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), new LispInteger(2), procs));
	}

	/**
	 * {@code (pmap f colls...)}: the parallel map where threads exist, the sequential map
	 * where they do not.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal pmapOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("pmap", items.size() - 1, 2, -1);
		LispVal fun = ClojureBindingLowering.realFnValue(ctx, items.get(1));
		LispVal colls = ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2));
		if (!ctx.hostTarget) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), fun, colls);
		}
		return pmapParallel(ctx, fun, colls);
	}

	/**
	 * {@code promise} as a value: the same undelivered cell a call answers.
	 * @return the value form
	 */
	static LispVal promiseValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(),
				ClojureLowerUtil.list(new LispSymbol(PROMISE_MAKE)));
	}

	/**
	 * {@code deliver} as a value: over a promise and a value, the same checked store a
	 * call runs.
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal deliverValue(ClojureLowering ctx) {
		LispSymbol promise = new LispSymbol(ClojureLowering.mangle("deliver-promise"));
		LispSymbol value = new LispSymbol(ClojureLowering.mangle("deliver-value"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(promise, value)),
				deliverForm(ctx, promise, value));
	}

	/**
	 * Whether the lowered value is a Clojure future.
	 * @param value the lowered value
	 * @return the test form
	 */
	static LispVal wrapperTest(LispVal value) {
		return ClojureLowerUtil.list(new LispSymbol(WRAPPER_P), value);
	}

	/**
	 * The host task inside the lowered Clojure future.
	 * @param value the lowered future
	 * @return the task form
	 */
	static LispVal taskOf(LispVal value) {
		return ClojureLowerUtil.list(new LispSymbol(FUTURE_TASK), value);
	}

	/**
	 * Whether the lowered value is a Clojure promise.
	 * @param value the lowered value
	 * @return the test form
	 */
	static LispVal promiseTest(LispVal value) {
		return ClojureLowerUtil.list(new LispSymbol(PROMISE_P), value);
	}

	/**
	 * A refused future or promise verb as a function value: a {@code &rest} lambda over
	 * the same call-time signal the call lowers to.
	 * @param name the Clojure name
	 * @return the value form
	 */
	static LispVal refusedFutureValue(String name) {
		return ClojureCoreLowering.refusedValue(refusalMessage(name));
	}

	/**
	 * The call-time refusal's words of a future or promise verb on a target with no
	 * threads.
	 * @param name the Clojure name
	 * @return the refusal's words
	 */
	static String refusalMessage(String name) {
		return switch (name) {
			case "future", "future-call" ->
				name + " is not supported yet on this backend: the WebAssembly backends are single-threaded";
			case "promise", "deliver" -> name
					+ " is not supported yet on this backend: blocking rendezvous needs threads, and the WebAssembly backends are single-threaded";
			default -> throw new IllegalArgumentException("no future refusal: " + name);
		};
	}

}
