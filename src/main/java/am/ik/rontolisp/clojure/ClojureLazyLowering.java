package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Lazy forms of the Clojure lowering: lazy wrappers, repetition, ranges and realization.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureLazyLowering {

	private ClojureLazyLowering() {
	}

	/**
	 * {@code repeat} as a value: over a value, or a count and a value -- the two call
	 * shapes, dispatched on the rest count. Any other count signals, like a call's arity
	 * refusal.
	 */
	static LispVal repeatValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("repeat-args"));
		LispVal one = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
		LispVal two = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT-N"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("repeat takes a value, or a count and a value"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), one),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						two),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/** {@code cycle} as a value: a one-argument lambda over the cycled seq. */
	static LispVal cycleValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("cycle-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CYCLE"), coll));
	}

	/** {@code iterate} as a value: a two-argument lambda over the iterated seq. */
	static LispVal iterateValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("iterate-fn"));
		LispSymbol start = new LispSymbol(ClojureLowering.mangle("iterate-start"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, start)),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ITERATE"), ClojureLowering.realFun(fun),
						start));
	}

	/**
	 * {@code repeatedly} as a value: over a function, or a count and a function -- the
	 * two call shapes, dispatched on the rest count. Any other count signals, like a
	 * call's arity refusal.
	 */
	static LispVal repeatedlyValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("repeatedly-args"));
		LispVal one = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY"),
				ClojureLowering.realFun(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)));
		LispVal two = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY-N"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), ClojureLowering.realFun(ClojureLowerUtil
					.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("repeatedly takes a function, or a count and a function"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), one),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						two),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code range} as a value: the one-, two- and three-argument call shapes over the
	 * rest list. Any other count signals, like a call's arity refusal.
	 */
	static LispVal rangeValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("range-args"));
		LispVal second = ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args));
		LispVal third = ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("cdr"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)));
		LispVal one = rangeForm(ctx, new LispInteger(0), ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
				new LispInteger(1));
		LispVal two = rangeForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), second,
				new LispInteger(1));
		LispVal three = rangeForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), second, third);
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("range takes an end, or a start, an end and an optional step"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)), one),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						two),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)))),
						three),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code (lazy-seq body...)}: the body behind a memoized thunk. The body runs at most
	 * once per seq object -- when first realized -- and answers the seq's contents (nil,
	 * a cons, or another collection to seq). The body is its own zero-arity recur target
	 * (the oracle's thunk is a zero-argument function): a {@code recur} in the body's
	 * tail position calls the thunk itself, checked against arity 0, while a
	 * {@code recur} anywhere else is refused by the tail walk first, and a {@code try}
	 * between the {@code recur} and the body trips the barrier the same way. The thunk
	 * lambda wraps itself in a {@code labels} self-binding only when a {@code recur}
	 * reaches it (the anonymous-{@code fn} shape).
	 */
	static LispVal lazySeqOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(ctx.freshRecurName(), true);
		target.setArity(0, false);
		ctx.pushRecurTarget(target);
		try {
			LispVal lambda = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of()),
					ctx.bodyTail(items, 1));
			LispVal thunk = target.used() ? ClojureLowering.labelsSelfCall(target.callName(), lambda) : lambda;
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAKE-LAZY"), thunk);
		}
		finally {
			ctx.recurTargets.pop();
		}
	}

	/**
	 * {@code (lazy-cat e...)}: each expression behind its own {@code lazy-seq},
	 * concatenated lazily -- a datum rewrite onto {@code concat}, so chunk-free laziness
	 * holds per member. Of none, nil.
	 */
	static LispVal lazyCatOf(ClojureLowering ctx, List<LispVal> items) {
		if (items.size() == 1) {
			return ClojureLowering.NIL_CONST;
		}
		List<LispVal> form = new ArrayList<>();
		form.add(new LispSymbol(ClojureCoreNames.PREFIX + "concat"));
		for (int i = 1; i < items.size(); i++) {
			form.add(ClojureLowerUtil.list(new LispSymbol(ClojureCoreNames.PREFIX + "lazy-seq"), items.get(i)));
		}
		return ctx.lower(ClojureLowerUtil.list(form));
	}

	/**
	 * {@code (repeat x)} (infinite, lazy) or {@code (repeat n x)} (finite, strict like
	 * the oracle's print).
	 */
	static LispVal repeatOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "repeat takes a value, or a count and a value");
		if (n == 1) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT"), ctx.lower(items.get(1)));
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT-N"), ctx.lower(items.get(1)),
				ctx.lower(items.get(2)));
	}

	/**
	 * {@code (repeatedly f)} (infinite, lazy) or {@code (repeatedly n f)} (finite, strict
	 * like the oracle's print).
	 */
	static LispVal repeatedlyOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "repeatedly takes a function, or a count and a function");
		if (n == 1) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY"),
					ClojureBindingLowering.realFnValue(ctx, items.get(1)));
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY-N"), ctx.lower(items.get(1)),
				ClojureBindingLowering.realFnValue(ctx, items.get(2)));
	}

	/**
	 * {@code dorun}: the spliced {@code %clojure-dorun} (or {@code -n} with a count),
	 * walking the seq view to its end -- a lazy seq realizes member by member, a strict
	 * one is already realized -- and answering nil.
	 */
	static LispVal dorunOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "dorun takes a collection and an optional count");
		if (n == 1) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DORUN"), ctx.lower(items.get(1)));
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DORUN-N"), ctx.lower(items.get(1)),
				ctx.lower(items.get(2)));
	}

	/**
	 * {@code doall}: like {@code dorun}, but answers the collection itself (never
	 * coerced: a vector stays a vector, like the oracle's).
	 */
	static LispVal doallOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "doall takes a collection and an optional count");
		if (n == 1) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DOALL"), ctx.lower(items.get(1)));
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DOALL-N"), ctx.lower(items.get(1)),
				ctx.lower(items.get(2)));
	}

	/**
	 * {@code dorun} as a value: over one collection (or a count and a collection),
	 * realizing it like the call and answering nil; any other count signals, like a
	 * call's arity refusal.
	 */
	static LispVal dorunValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("dorun-args"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("dorun takes a collection and an optional count"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DORUN"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args))),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DORUN-N"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), args))),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code doall} as a value: over one collection (or a count and a collection),
	 * realizing it like the call and answering the collection; any other count signals.
	 */
	static LispVal doallValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("doall-args"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("doall takes a collection and an optional count"));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DOALL"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args))),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DOALL-N"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), args))),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code range} with an end: the strict list, built by a labels self call. Without an
	 * end there is no strict lowering -- an infinite seq cannot be spelled -- so it is
	 * refused by name, like {@code lazy-seq}. A zero step signals at run time.
	 */
	static LispVal rangeOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			throw new LispReadException("infinite range is not supported: range needs an end");
		}
		ClojureLowerUtil.isTrue(n >= 1 && n <= 3, "range takes an end, or a start, an end and an optional step");
		LispVal start = n >= 2 ? ctx.lower(items.get(1)) : new LispInteger(0);
		LispVal end = n >= 2 ? ctx.lower(items.get(2)) : ctx.lower(items.get(1));
		LispVal step = n == 3 ? ctx.lower(items.get(3)) : new LispInteger(1);
		return rangeForm(ctx, start, end, step);
	}

	/** The strict list from {@code start} below {@code end} stepping by {@code step}. */
	static LispVal rangeForm(ClojureLowering ctx, LispVal start, LispVal end, LispVal step) {
		String name = ClojureLowering.mangle("range-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol from = ctx.freshTemp();
		LispSymbol to = ctx.freshTemp();
		LispSymbol by = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol grown = ctx.freshTemp();
		LispVal pastEnd = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("plusp"), by),
				ClojureLowerUtil.list(ClojureLowerUtil.sym(">="), at, to),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<="), at, to));
		LispVal advance = ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, by),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), at, grown));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), pastEnd,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), grown), advance);
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(at, grown)), ClojureLowerUtil.cons(body, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(from, start), ClojureLowerUtil.list(to, end),
						ClojureLowerUtil.list(by, step))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"), by),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
								LispString.literal("range step cannot be zero")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
								ClojureLowerUtil.list(self, from, ClojureLowering.NIL_CONST))));
	}

}
