package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;

/**
 * {@code clojure.set} of the Clojure lowering: every var is one call to its spliced
 * {@code rontolisp::%clojure-set-} worker in {@code clojure.lisp} (the oracle's own
 * algorithms over the set wrapper and the structural-key runtime), after an arity check
 * worded like the oracle's, and names that worker's {@code -v} entry as a value (which
 * checks the count at run time with the same wording). The variadic
 * {@code union}/{@code intersection}/{@code difference} pass their sets as one list.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureSetLowering {

	/** The namespace this slice lowers. */
	static final String NAMESPACE = "clojure.set";

	/** The {@code clojure.set} vars: every public var of the oracle's namespace. */
	static final Set<String> VARS = Set.of("union", "intersection", "difference", "select", "project", "rename-keys",
			"rename", "index", "map-invert", "join", "subset?", "superset?");

	private ClojureSetLowering() {
	}

	/**
	 * A {@code clojure.set} call: the var {@code ns} resolution already vetted, over the
	 * call's own items (whose head is ignored).
	 * @param ctx the hub
	 * @param var the var name
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal setCall(ClojureLowering ctx, String var, List<LispVal> items) {
		int n = items.size() - 1;
		switch (var) {
			case "union":
				return worker(var, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1)));
			case "intersection", "difference":
				arity(var, n, 1, -1);
				return worker(var, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1)));
			case "select":
				arity(var, n, 2, 2);
				return worker(var, ClojureBindingLowering.fnValue(ctx, items.get(1)), ctx.lower(items.get(2)));
			case "map-invert":
				arity(var, n, 1, 1);
				return worker(var, ctx.lower(items.get(1)));
			case "join":
				arity(var, n, 2, 3);
				return n == 2 ? worker(var, ctx.lower(items.get(1)), ctx.lower(items.get(2)))
						: worker("join-km", ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
			case "project", "rename-keys", "rename", "index", "subset?", "superset?":
				arity(var, n, 2, 2);
				return worker(var, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			default:
				throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
	}

	/**
	 * A {@code clojure.set} var as a function value: its worker's {@code -v} entry.
	 * @param var the var name
	 * @return the value form
	 */
	static LispVal setValue(String var) {
		if (!VARS.contains(var)) {
			throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime(var + "-v"));
	}

	private static LispVal worker(String var, LispVal... args) {
		List<LispVal> out = new ArrayList<>();
		out.add(runtime(var));
		out.addAll(List.of(args));
		return ClojureLowerUtil.list(out);
	}

	/**
	 * {@code subset?} is {@code %clojure-set-subset-p}: a {@code ?} spells {@code -p}.
	 */
	private static LispSymbol runtime(String var) {
		String spelled = var.replace("?", "-p");
		return new LispSymbol("RONTOLISP::%CLOJURE-SET-" + spelled.toUpperCase(Locale.ROOT));
	}

	/** The oracle's arity refusal when {@code n} falls outside {@code min..max}. */
	private static void arity(String var, int n, int min, int max) {
		if (n < min || (max >= 0 && n > max)) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: " + NAMESPACE + "/" + var);
		}
	}

}
