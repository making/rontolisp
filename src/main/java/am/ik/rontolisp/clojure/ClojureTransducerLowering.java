package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Transducers in the Clojure lowering: the one-argument arities of the seq verbs
 * ({@code (map f)}, {@code (take n)}, {@code (dedupe)}, ...) and their consumers
 * ({@code transduce}, {@code eduction}, {@code sequence}, {@code completing},
 * {@code reduced} and its companions, {@code cat}, {@code take-nth}). A transducer is
 * what the oracle's is -- a function from a reducing function to a reducing function --
 * built by a spliced {@code rontolisp::%clojure-xf-} worker in {@code clojure.lisp}, so
 * {@code comp} composes transducers left to right with no help and a program may write
 * its own. {@code into}'s transducer arity lowers in {@link ClojureUpdateLowering} and
 * {@code reduce} (which stops at a {@code reduced} answer) in {@link ClojureSeqLowering}.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureTransducerLowering {

	private ClojureTransducerLowering() {
	}

	/** How a transducer arity's one argument lowers, if it takes one. */
	private enum Arg {

		/** A function argument, through {@code fnValue}. */
		FN,

		/** A plain value. */
		VALUE,

		/** No argument at all. */
		NONE

	}

	/**
	 * One transducer arity: the worker's name and its fixed trailing argument (the
	 * filter/remove and keep-indexed/map-indexed pairs share a worker), or null.
	 */
	private record Xform(Arg arg, String worker, @Nullable LispVal flag) {
	}

	/** The seq verbs whose transducer arity is the one listed, by name. */
	private static final Map<String, Xform> XFORMS = Map.ofEntries(Map.entry("map", new Xform(Arg.FN, "xf-map", null)),
			Map.entry("filter", new Xform(Arg.FN, "xf-filter", ClojureLowering.TRUE_CONST)),
			Map.entry("remove", new Xform(Arg.FN, "xf-filter", ClojureLowering.NIL_CONST)),
			Map.entry("keep", new Xform(Arg.FN, "xf-keep", null)),
			Map.entry("keep-indexed", new Xform(Arg.FN, "xf-indexed", ClojureLowering.TRUE_CONST)),
			Map.entry("map-indexed", new Xform(Arg.FN, "xf-indexed", ClojureLowering.NIL_CONST)),
			Map.entry("take", new Xform(Arg.VALUE, "xf-take", null)),
			Map.entry("drop", new Xform(Arg.VALUE, "xf-drop", null)),
			Map.entry("take-while", new Xform(Arg.FN, "xf-take-while", null)),
			Map.entry("drop-while", new Xform(Arg.FN, "xf-drop-while", null)),
			Map.entry("take-nth", new Xform(Arg.VALUE, "xf-take-nth", null)),
			Map.entry("mapcat", new Xform(Arg.FN, "xf-mapcat", null)),
			Map.entry("partition-all", new Xform(Arg.VALUE, "xf-partition-all", null)),
			Map.entry("partition-by", new Xform(Arg.FN, "xf-partition-by", null)),
			Map.entry("interpose", new Xform(Arg.VALUE, "xf-interpose", null)),
			Map.entry("dedupe", new Xform(Arg.NONE, "xf-dedupe", null)),
			Map.entry("distinct", new Xform(Arg.NONE, "xf-distinct", null)));

	/**
	 * The transducer arity of a seq verb in call position, or null when the call has
	 * another arity (or the name has no transducer).
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the transducer construction, or null
	 */
	static @Nullable LispVal xformCall(ClojureLowering ctx, String name, List<LispVal> items) {
		Xform xform = XFORMS.get(name);
		if (xform == null || items.size() - 1 != (xform.arg() == Arg.NONE ? 0 : 1)) {
			return null;
		}
		List<LispVal> args = new ArrayList<>();
		if (xform.arg() == Arg.FN) {
			args.add(ClojureBindingLowering.realFnValue(ctx, items.get(1)));
		}
		else if (xform.arg() == Arg.VALUE) {
			args.add(ctx.lower(items.get(1)));
		}
		if (xform.flag() != null) {
			args.add(xform.flag());
		}
		return call(xform.worker(), args);
	}

	/**
	 * A seq verb's function value widened by its transducer arity, so
	 * {@code (apply filter [odd?])} answers the transducer like the call: the verb's own
	 * value is bound once and called for the collection arity. {@code map} and
	 * {@code mapcat} (rest lambdas) and the {@code -v} entries answer the transducer
	 * themselves, so they pass through.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param value the verb's value form
	 * @return the widened value form
	 */
	static LispVal xformValue(ClojureLowering ctx, String name, LispVal value) {
		Xform xform = XFORMS.get(name);
		if (xform == null || name.equals("map") || name.equals("mapcat") || name.equals("dedupe")
				|| name.equals("partition-all") || name.equals("partition-by") || name.equals("take-nth")) {
			return value;
		}
		LispSymbol seqFn = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol supplied = ctx.freshTemp();
		LispVal optional = ClojureLowerUtil.list(coll, ClojureLowering.NIL_CONST, supplied);
		List<LispVal> params = new ArrayList<>();
		List<LispVal> seqCall = new ArrayList<>(List.of(ClojureLowerUtil.sym("funcall"), seqFn));
		List<LispVal> xfArgs = new ArrayList<>();
		if (xform.arg() != Arg.NONE) {
			LispSymbol first = ctx.freshTemp();
			params.add(first);
			seqCall.add(first);
			xfArgs.add(xform.arg() == Arg.FN ? ClojureLowering.realFun(first) : first);
		}
		params.add(ClojureLowerUtil.sym("&optional"));
		params.add(optional);
		seqCall.add(coll);
		if (xform.flag() != null) {
			xfArgs.add(xform.flag());
		}
		LispVal lambda = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(params),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), supplied, ClojureLowerUtil.list(seqCall),
						call(xform.worker(), xfArgs)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(seqFn, value))), lambda);
	}

	/**
	 * A transducer consumer or companion in call position, or null when the name is none
	 * of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "transduce":
				ClojureCoreLowering.arity(name, n, 3, 4);
				if (n == 3) {
					return call("transduce-3", fnArgs(ctx, items, 1, 2), ctx.lower(items.get(3)));
				}
				return call("transduce", fnArgs(ctx, items, 1, 2), ctx.lower(items.get(3)), ctx.lower(items.get(4)));
			case "eduction": {
				ClojureCoreLowering.arity(name, n, 1, -1);
				List<LispVal> xfs = fnArgs(ctx, items, 1, n - 1);
				LispVal xf = xfs.size() == 1 ? xfs.get(0)
						: call("xf-comp", List.of(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), xfs)));
				// a collection reducing through its own CollReduce row steps what that
				// reduction steps (a view a program storing no such row sheds)
				return call("sequence-xf", List.of(xf), ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
						ClojureSeqLowering.reducibleItemsForm(ctx.lower(items.get(n)))));
			}
			case "sequence":
				ClojureCoreLowering.arity(name, n, 1, -1);
				if (n == 1) {
					return call("sequence", List.of(ctx.lower(items.get(1))));
				}
				return call("sequence-xf", fnArgs(ctx, items, 1, 1),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)));
			case "completing":
				ClojureCoreLowering.arity(name, n, 1, 2);
				if (n == 1) {
					return call("completing", fnArgs(ctx, items, 1, 1),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("identity")));
				}
				return call("completing", fnArgs(ctx, items, 1, 2));
			case "reduced", "unreduced", "ensure-reduced":
				ClojureCoreLowering.arity(name, n, 1, 1);
				return call(name, List.of(ctx.lower(items.get(1))));
			case "reduced?":
				ClojureCoreLowering.arity(name, n, 1, 1);
				return call("reduced-pred", List.of(ctx.lower(items.get(1))));
			case "cat":
				ClojureCoreLowering.arity(name, n, 1, 1);
				return call("xf-cat", fnArgs(ctx, items, 1, 1));
			case "take-nth":
				ClojureCoreLowering.arity(name, n, 2, 2);
				return call("take-nth", List.of(ctx.lower(items.get(1)), ctx.lower(items.get(2))));
			default:
				return null;
		}
	}

	/**
	 * A transducer consumer or companion as a function value, or null when the name is
	 * none of them.
	 * @param name the Clojure name
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(String name) {
		String worker = switch (name) {
			case "transduce", "eduction", "sequence", "completing", "take-nth" -> name + "-v";
			case "reduced", "unreduced", "ensure-reduced" -> name;
			case "reduced?" -> "reduced-pred";
			case "cat" -> "xf-cat";
			default -> null;
		};
		return worker == null ? null : ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime(worker));
	}

	/**
	 * The function arguments {@code from..to}, each as the real function the runtime
	 * funcalls ({@link ClojureLowering#realFun}): transducers and reducing functions
	 * alike, so the transducer runtime never names the IFn dispatcher.
	 */
	private static List<LispVal> fnArgs(ClojureLowering ctx, List<LispVal> items, int from, int to) {
		List<LispVal> out = new ArrayList<>();
		for (int i = from; i <= to; i++) {
			out.add(ClojureBindingLowering.realFnValue(ctx, items.get(i)));
		}
		return out;
	}

	private static LispVal call(String worker, List<LispVal> args, LispVal... more) {
		List<LispVal> out = new ArrayList<>();
		out.add(runtime(worker));
		out.addAll(args);
		out.addAll(List.of(more));
		return ClojureLowerUtil.list(out);
	}

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name.toUpperCase(java.util.Locale.ROOT));
	}

}
