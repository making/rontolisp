package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The sorted collections of the Clojure lowering ({@code sorted-map},
 * {@code sorted-map-by}, {@code sorted-set}, {@code sorted-set-by}, {@code subseq},
 * {@code rsubseq}), {@code compare}, their default order, and {@code vector-of}. Each
 * verb is one call to its spliced {@code rontolisp::%clojure-} worker in
 * {@code clojure.lisp} after an arity check worded like the oracle's, and names that
 * worker's value entry as a value.
 *
 * <p>
 * Also the arms the other verbs carry for a sorted collection: {@link #sortedTest} and
 * friends build the tests and views {@link ClojureArms} strips from a program that builds
 * none. An arm built here names only bound variables and allocates no temporary, so what
 * is left after the strip is the very form the verb lowered to before.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureSortedLowering {

	private ClojureSortedLowering() {
	}

	/** The core tests {@code subseq} and {@code rsubseq} read the comparison with. */
	private static final List<String> CORE_TESTS = List.of("<", "<=", ">", ">=");

	/**
	 * A sorted verb in call position, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "sorted-map", "sorted-set":
				return make(name.equals("sorted-set"), ClojureLowering.NIL_CONST, ctx.lowers(items, 1));
			case "sorted-map-by", "sorted-set-by":
				ClojureCoreLowering.arity(name, n, 1, -1);
				return make(name.equals("sorted-set-by"), ctx.lower(items.get(1)), ctx.lowers(items, 2));
			case "subseq", "rsubseq":
				return subseqOf(ctx, name, items);
			case "compare":
				ClojureCoreLowering.arity(name, n, 2, 2);
				return runtime("compare", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "vector-of":
				ClojureCoreLowering.arity(name, n, 1, -1);
				return runtime("vector-of", ctx.lower(items.get(1)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)));
			default:
				return null;
		}
	}

	/**
	 * A sorted verb as a function value, or null when the name is none of them.
	 * @param name the Clojure name
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(String name) {
		return switch (name) {
			case "sorted-map", "sorted-map-by", "sorted-set", "sorted-set-by", "subseq", "rsubseq", "compare",
					"vector-of" ->
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtimeSymbol(name + "-v"));
			default -> null;
		};
	}

	/** The constructor: {@code (%clojure-sorted-make setp cmp (list items...))}. */
	private static LispVal make(boolean set, LispVal cmp, List<LispVal> items) {
		return runtime("sorted-make", set ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST, cmp,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), items));
	}

	/**
	 * {@code (subseq sc test key)} / {@code (subseq sc start-test start-key end-test
	 * end-key)}, and {@code rsubseq} alike: one call to the walker over the collection,
	 * the tests and keys in order.
	 */
	private static LispVal subseqOf(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		if (n != 3 && n != 5) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: clojure.core/" + name);
		}
		LispVal ascending = name.equals("subseq") ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST;
		LispVal coll = ctx.lower(items.get(1));
		if (n == 3) {
			return runtime("subseq", coll, testArg(ctx, items.get(2)), ctx.lower(items.get(3)), ascending);
		}
		return runtime("subseq-5", coll, testArg(ctx, items.get(2)), ctx.lower(items.get(3)),
				testArg(ctx, items.get(4)), ctx.lower(items.get(5)), ascending);
	}

	/**
	 * A test of {@code subseq}/{@code rsubseq}: a literal {@code <}, {@code <=},
	 * {@code >} or {@code >=} naming the core function is that test's keyword ({@code :<}
	 * ...), which picks the oracle's path the way its identity check does; anything else
	 * is a real function, which the walker recognizes by how it answers.
	 */
	private static LispVal testArg(ClojureLowering ctx, LispVal datum) {
		if (datum instanceof LispSymbol symbol) {
			String name = symbol.name();
			String core = ClojureCoreNames.coreSpelling(name);
			if (core == null && !ctx.known(name) && ClojureNamespaceLowering.coreAllowed(ctx, name)) {
				core = name;
			}
			if (core != null && CORE_TESTS.contains(core)) {
				return new LispSymbol(":" + core);
			}
		}
		return ClojureBindingLowering.realFnValue(ctx, datum);
	}

	/**
	 * The arm test over a bound variable: whether it holds a sorted map or set.
	 * @param bound the variable
	 * @return {@code (rontolisp::%clojure-sorted-p bound)}
	 */
	static LispVal sortedTest(LispVal bound) {
		return runtime("sorted-p", bound);
	}

	/**
	 * The arm test over a bound variable: whether it holds a sorted map.
	 * @param bound the variable
	 * @return {@code (rontolisp::%clojure-sorted-map-p bound)}
	 */
	static LispVal sortedMapTest(LispVal bound) {
		return runtime("sorted-map-p", bound);
	}

	/**
	 * The arm test over a bound variable: whether it holds a sorted set.
	 * @param bound the variable
	 * @return {@code (rontolisp::%clojure-sorted-set-p bound)}
	 */
	static LispVal sortedSetTest(LispVal bound) {
		return runtime("sorted-set-p", bound);
	}

	/**
	 * A call to the spliced {@code rontolisp::%clojure-NAME} over the arguments.
	 * @param name the worker's name past the prefix, lower case
	 * @param args the argument forms
	 * @return the call
	 */
	static LispVal runtime(String name, LispVal... args) {
		List<LispVal> out = new ArrayList<>();
		out.add(runtimeSymbol(name));
		out.addAll(List.of(args));
		return ClojureLowerUtil.list(out);
	}

	private static LispSymbol runtimeSymbol(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name.toUpperCase(Locale.ROOT));
	}

}
