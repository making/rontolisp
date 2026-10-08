package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;

/**
 * {@code clojure.edn} of the Clojure lowering: {@code read-string} and {@code read} are
 * one call each to the spliced {@code rontolisp::%clojure-edn-} entries in
 * {@code clojure.lisp}, the run-time reader in EDN mode (the oracle's {@code EdnReader}:
 * data only, tagged literals through the {@code :readers} and {@code :default} options),
 * after an arity check worded like the oracle's. The oracle loads the namespace before
 * the program, so its qualified names resolve without a {@code require}, like
 * {@code clojure.string}'s.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureEdnLowering {

	/** The namespace this slice lowers. */
	static final String NAMESPACE = "clojure.edn";

	/** The {@code clojure.edn} vars: every public var of the oracle's namespace. */
	static final Set<String> VARS = Set.of("read", "read-string");

	private ClojureEdnLowering() {
	}

	/**
	 * A {@code clojure.edn} call: the var {@code ns} resolution already vetted, over the
	 * call's own items (whose head is ignored). {@code (read-string s)} reads with an
	 * {@code {:eof nil}} options map, like the oracle's; {@code (read)} reads
	 * {@code *in*} and {@code (read stream)} with no options.
	 * @param ctx the hub
	 * @param var the var name
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal ednCall(ClojureLowering ctx, String var, List<LispVal> items) {
		int n = items.size() - 1;
		switch (var) {
			case "read-string":
				arity(var, n, 1, 2);
				return n == 1 ? worker("READ-STRING-1", ctx.lower(items.get(1)))
						: worker("READ-STRING", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "read":
				arity(var, n, 0, 2);
				return switch (n) {
					case 0 -> worker("READ", ClojureLowering.NIL_CONST, ClojureLowerUtil.sym("*standard-input*"));
					case 1 -> worker("READ", ClojureLowering.NIL_CONST, ctx.lower(items.get(1)));
					default -> worker("READ", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
				};
			default:
				throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
	}

	/**
	 * A {@code clojure.edn} var as a function value: its entry's {@code -v} function.
	 * @param var the var name
	 * @return the value form
	 */
	static LispVal ednValue(String var) {
		if (!VARS.contains(var)) {
			throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"),
				runtime(var.equals("read") ? "READ-V" : "READ-STRING-V"));
	}

	private static LispVal worker(String name, LispVal... args) {
		return ClojureLowerUtil.cons(runtime(name), List.of(args));
	}

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-EDN-" + name);
	}

	/** The oracle's arity refusal when {@code n} falls outside {@code min..max}. */
	private static void arity(String var, int n, int min, int max) {
		if (n < min || n > max) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: " + NAMESPACE + "/" + var);
		}
	}

}
