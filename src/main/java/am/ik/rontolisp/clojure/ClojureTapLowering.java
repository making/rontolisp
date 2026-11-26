package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The taps of the lowering ({@code tap>}, {@code add-tap}, {@code remove-tap}) over
 * {@code clojure.lisp}'s {@code %clojure-tap-*} workers: on the interpreter and the JVM
 * {@code tap>} offers its value to the oracle's bounded queue, drained by one thread; on
 * wasm the stand-in delivers it at the call ({@code eval/ClojureLibrary}).
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureTapLowering {

	private static final LispSymbol SEND = new LispSymbol("RONTOLISP::%CLOJURE-TAP-SEND");

	private static final LispSymbol ADD = new LispSymbol("RONTOLISP::%CLOJURE-TAP-ADD");

	private static final LispSymbol REMOVE = new LispSymbol("RONTOLISP::%CLOJURE-TAP-REMOVE");

	private ClojureTapLowering() {
	}

	/**
	 * A call of a tap verb. {@code add-tap} keeps the value it is handed, which
	 * {@code remove-tap} finds by {@code identical?}, beside the real function the queue
	 * calls: the value itself when its form always yields one, else the IFn dispatcher's
	 * wrap of it.
	 * @param ctx the hub
	 * @param name {@code tap>}, {@code add-tap} or {@code remove-tap}
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		ClojureCoreLowering.arity(name, items.size() - 1, 1, 1);
		LispVal arg = ctx.lower(items.get(1));
		return switch (name) {
			case "tap>" -> ClojureLowerUtil.list(SEND, arg);
			case "remove-tap" -> ClojureLowerUtil.list(REMOVE, arg);
			default -> {
				LispSymbol tap = ctx.freshTemp();
				LispVal fn = ClojureBindingLowering.holdsRealFun(ctx, items.get(1), arg) ? tap
						: ClojureLowering.realFun(tap);
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(ClojureLowerUtil.list(tap, arg)), ClojureLowerUtil.list(ADD, tap, fn));
			}
		};
	}

	/**
	 * A tap verb as a value: its {@code -v} worker, which checks the arity.
	 * @param name {@code tap>}, {@code add-tap} or {@code remove-tap}
	 * @return the value form
	 */
	static LispVal valueOf(String name) {
		String worker = switch (name) {
			case "tap>" -> "RONTOLISP::%CLOJURE-TAP-SEND-V";
			case "add-tap" -> "RONTOLISP::%CLOJURE-TAP-ADD-V";
			default -> "RONTOLISP::%CLOJURE-TAP-REMOVE-V";
		};
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), new LispSymbol(worker));
	}

}
