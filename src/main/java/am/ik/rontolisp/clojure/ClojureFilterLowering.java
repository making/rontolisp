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
 * Filter forms of the Clojure lowering: indexed, conditional and grouping sequence
 * functions.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureFilterLowering {

	private ClojureFilterLowering() {
	}

	/**
	 * {@code keep}: the non-nil results of the function over the collection, through the
	 * spliced {@code rontolisp::%clojure-keep}. {@code false} is kept (only nil drops),
	 * and a signalling function signals -- {@code (keep inc [1 nil 2])} throws, like the
	 * oracle, instead of skipping. Lazy-or-strict, like {@code map}. The function is a
	 * real one ({@link ClojureLowering#realFun}).
	 */
	static LispVal keepForm(ClojureLowering ctx, LispVal fn, LispVal coll) {
		return runtimeCall("KEEP", fn, coll);
	}

	/** {@code keep} as a value: a two-argument lambda over the same call. */
	static LispVal keepValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("keep-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("keep-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				keepForm(ctx, ClojureLowering.realFun(fun), coll));
	}

	/**
	 * {@code keep-indexed} ({@code keep} true) or {@code map-indexed}: the function of
	 * index and member over the collection, through the spliced
	 * {@code rontolisp::%clojure-indexed}; {@code keep-indexed} drops a nil answer.
	 * Lazy-or-strict, like {@code map}.
	 */
	static LispVal indexedForm(ClojureLowering ctx, LispVal fn, LispVal coll, boolean keep) {
		return runtimeCall("INDEXED", fn, coll, keep ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST);
	}

	/** {@code keep-indexed} or {@code map-indexed} as a value: a two-argument lambda. */
	static LispVal indexedValue(ClojureLowering ctx, boolean keep) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("indexed-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("indexed-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				indexedForm(ctx, ClojureLowering.realFun(fun), coll, keep));
	}

	/** The call {@code (rontolisp::%clojure-NAME args...)}. */
	private static LispVal runtimeCall(String name, LispVal... args) {
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol("RONTOLISP::%CLOJURE-" + name));
		call.addAll(List.of(args));
		return ClojureLowerUtil.list(call);
	}

	/** The function value {@code #'rontolisp::%clojure-NAME}. */
	private static LispVal runtimeFunction(String name) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), new LispSymbol("RONTOLISP::%CLOJURE-" + name));
	}

	/**
	 * {@code every?}: true when the predicate holds for every member, answering
	 * {@code T}-or-false directly (empty is true, like the oracle).
	 */
	static LispVal everyForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fn, LispVal seq) {
		String name = ClojureLowering.mangle("every-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
								ctx.callFun(fn.real(), fun,
										List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)))))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
								ctx.falseVariable, ClojureLowerUtil.list(self, ClojureSeqLowering.seqRestForm(rest)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn.fun()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code every?} as a value: a two-argument lambda over the same loop. */
	static LispVal everyValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("every-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("every-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				everyForm(ctx, ClojureBindingLowering.FnArg.of(pred), ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/**
	 * {@code some}: the first truthy predicate result, or nil. The predicate's own value
	 * answers (not the member), like the oracle.
	 */
	static LispVal someForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fn, LispVal seq) {
		String name = ClojureLowering.mangle("some-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.NIL_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
								ctx.callFun(fn.real(), fun,
										List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)))))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
								ClojureLowerUtil.list(self, ClojureSeqLowering.seqRestForm(rest)), got)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn.fun()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code some} as a value: a two-argument lambda over the same loop. */
	static LispVal someValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("some-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("some-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				someForm(ctx, ClojureBindingLowering.FnArg.of(pred), ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/**
	 * {@code remove}: the members the predicate rejects, through the spliced
	 * {@code rontolisp::%clojure-remove} ({@code filter} over the complement), so it is
	 * lazy-or-strict like {@code filter}. The predicate is a real function
	 * ({@link ClojureLowering#realFun}).
	 */
	static LispVal removeForm(ClojureLowering ctx, LispVal fn, LispVal coll) {
		return runtimeCall("REMOVE", fn, coll);
	}

	/** {@code remove} as a value: a two-argument lambda over the same call. */
	static LispVal removeValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("remove-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("remove-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				removeForm(ctx, ClojureLowering.realFun(pred), coll));
	}

	/**
	 * {@code distinct}: later duplicates dropped, first occurrences kept in order,
	 * through the spliced {@code rontolisp::%clojure-distinct}. Membership is {@code =},
	 * through the structural-key runtime like a set's. Lazy-or-strict.
	 */
	static LispVal distinctForm(LispVal coll) {
		return runtimeCall("DISTINCT", coll);
	}

	/** {@code distinct} as a value: the runtime worker itself. */
	static LispVal distinctValue() {
		return runtimeFunction("DISTINCT");
	}

	/**
	 * {@code partition}: size, optional step (defaulting to the size, evaluated once),
	 * collection, through the spliced {@code rontolisp::%clojure-partition}: full groups,
	 * an incomplete tail dropped, like the oracle; a non-positive size signals.
	 * Lazy-or-strict.
	 */
	static LispVal partitionOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "partition takes a size, an optional step and a collection");
		if (n == 3) {
			return runtimeCall("PARTITION", ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
		}
		LispSymbol size = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(size, ctx.lower(items.get(1))))),
				runtimeCall("PARTITION", size, size, ctx.lower(items.get(2))));
	}

	/** {@code partition} as a value: the runtime's arity-checking entry. */
	static LispVal partitionValue() {
		return runtimeFunction("PARTITION-V");
	}

	/**
	 * {@code take-while}: the strict prefix while the predicate stays truthy
	 * ({@code false} stops, like nil).
	 */
	static LispVal takeWhileForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fn, LispVal seq) {
		String name = ClojureLowering.mangle("take-while-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pred = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn.real(), pred,
				List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
								ClojureLowerUtil.list(self, ClojureSeqLowering.seqRestForm(rest),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
												ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest), acc)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pred, fn.fun()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq, ClojureLowering.NIL_CONST)));
	}

	/** {@code take-while} as a value: a two-argument lambda over the same loop. */
	static LispVal takeWhileValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("take-while-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("take-while-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				takeWhileForm(ctx, ClojureBindingLowering.FnArg.of(pred), ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/** {@code drop-while}: the seq view past the truthy prefix, sharing the tail. */
	static LispVal dropWhileForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fn, LispVal seq) {
		String name = ClojureLowering.mangle("drop-while-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pred = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn.real(), pred,
				List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.NIL_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
								rest, ClojureLowerUtil.list(self, ClojureSeqLowering.seqRestForm(rest)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pred, fn.fun()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code drop-while} as a value: a two-argument lambda over the same loop. */
	static LispVal dropWhileValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("drop-while-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("drop-while-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				dropWhileForm(ctx, ClojureBindingLowering.FnArg.of(pred), ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/**
	 * {@code interleave}: round-robin over the collections, stopping at the shortest,
	 * through the spliced {@code rontolisp::%clojure-interleave}. Lazy when any input is
	 * lazy, like {@code map}.
	 */
	static LispVal interleaveOf(ClojureLowering ctx, List<LispVal> items) {
		return runtimeCall("INTERLEAVE", ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1)));
	}

	/** {@code interleave} as a value: every argument interleaved. */
	static LispVal interleaveValue() {
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("interleave-colls"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, colls), runtimeCall("INTERLEAVE", colls));
	}

	/**
	 * {@code interpose}: the separator between every two members, through the spliced
	 * {@code rontolisp::%clojure-interpose}, so a one-member collection never shows it.
	 * Lazy-or-strict.
	 */
	static LispVal interposeForm(LispVal sep, LispVal coll) {
		return runtimeCall("INTERPOSE", sep, coll);
	}

	/** {@code interpose} as a value: the runtime worker itself. */
	static LispVal interposeValue() {
		return runtimeFunction("INTERPOSE");
	}

	/**
	 * {@code zipmap}: a fresh map pairing each key with its value, stopping at the
	 * shorter side, like the oracle.
	 */
	static LispVal zipmapForm(ClojureLowering ctx, LispVal keys, LispVal vals) {
		String name = ClojureLowering.mangle("zipmap-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol table = ctx.freshTemp();
		LispSymbol keyRest = ctx.freshTemp();
		LispSymbol valRest = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), keyRest),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), valRest)),
				table,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
						ClojureCollectionLowering.tablePut(table,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), keyRest),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), valRest)),
						ClojureLowerUtil.list(self, ClojureSeqLowering.seqRestForm(keyRest),
								ClojureSeqLowering.seqRestForm(valRest))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(keyRest, valRest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, keys, vals)));
	}

	/** {@code zipmap} as a value: a two-argument lambda over the same loop. */
	static LispVal zipmapValue(ClojureLowering ctx) {
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("zipmap-keys"));
		LispSymbol vals = new LispSymbol(ClojureLowering.mangle("zipmap-vals"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(keys, vals)),
				zipmapForm(ctx, ClojureSeqLowering.seqForm(ctx, keys), ClojureSeqLowering.seqForm(ctx, vals)));
	}

	/**
	 * {@code group-by}: a fresh map from each function value to the vector of members
	 * answering it, in encounter order. Members accumulate reversed, then convert.
	 */
	static LispVal groupByForm(ClojureLowering ctx, ClojureBindingLowering.FnArg fn, LispVal seq) {
		LispSymbol fun = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal keyed = ctx.callFun(fn.real(), fun, List.of(one));
		LispVal collect = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(one, seq)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(
								List.of(ClojureLowerUtil.list(key, ClojureCollectionLowering.storeKey(keyed, table)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), one, ClojureLowerUtil
									.list(ClojureLowerUtil.sym("gethash"), key, table, ClojureLowering.NIL_CONST)))));
		LispVal freeze = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("coerce"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("nreverse"), val),
										ClojureLowerUtil.quoted("vector")))),
				table);
		return ClojureLowerUtil
			.list(ClojureLowerUtil.sym("let*"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn.fun()),
							ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
					collect, freeze, table);
	}

	/** {@code group-by} as a value: a two-argument lambda over the same pass. */
	static LispVal groupByValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("group-by-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("group-by-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				groupByForm(ctx, ClojureBindingLowering.FnArg.of(fun), ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	/** {@code sort}: the seq view copied and sorted, with an optional comparator. */
	static LispVal sortOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "sort takes a collection and an optional comparator");
		LispVal seq = ClojureSeqLowering.seqAllForm(ctx, ctx.lower(items.get(n)));
		if (n == 1) {
			return sortForm(ctx, seq, null);
		}
		return sortForm(ctx, seq, ClojureBindingLowering.fnArg(ctx, items.get(1)));
	}

	/**
	 * The sort over an already-lowered seq view: a copy (the primitive sorts
	 * destructively) under the default or wrapped comparator.
	 */
	static LispVal sortForm(ClojureLowering ctx, LispVal seq, ClojureBindingLowering.@Nullable FnArg cmp) {
		LispSymbol coll = ctx.freshTemp();
		LispVal pred;
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(coll, ClojureLowerUtil.list(ClojureLowerUtil.sym("copy-list"), seq)));
		if (cmp == null) {
			pred = defaultCmpFn(ctx);
		}
		else {
			LispSymbol fun = ctx.freshTemp();
			LispSymbol left = ctx.freshTemp();
			LispSymbol right = ctx.freshTemp();
			LispSymbol got = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(fun, cmp.fun()));
			LispVal before = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(got, ctx.callFun(cmp.real(), fun, List.of(left, right))))),
					comparatorBefore(ctx, got));
			pred = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(left, right)),
					before);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("sort"), coll, pred));
	}

	/**
	 * Whether a comparator's answer, bound to {@code got}, puts its first argument before
	 * its second: the oracle's {@code AFunction.compare} read the way a sort reads it --
	 * a number when its integer part is negative ({@code compare}, {@code (- a b)}),
	 * anything else when it is truthy ({@code <}, {@code >}).
	 */
	static LispVal comparatorBefore(ClojureLowering ctx, LispSymbol got) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), got),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<="), got, new LispInteger(-1)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
						ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST));
	}

	/**
	 * The default comparator, {@code (fn [a b] (neg? (compare a b)))}: the oracle sorts
	 * by {@code compare}, so nil, booleans, symbols and vectors order too and two values
	 * of no common order signal.
	 */
	static LispVal defaultCmpFn(ClojureLowering ctx) {
		LispSymbol left = new LispSymbol(ClojureLowering.mangle("sort-a"));
		LispSymbol right = new LispSymbol(ClojureLowering.mangle("sort-b"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(left, right)),
				defaultCmpBody(ctx, left, right));
	}

	/** Whether the bound value is a keyword wrapper. */
	static LispVal keywordTest(LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), value),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), value),
						ClojureCollectionLowering.KEYWORD_TAG));
	}

	/** {@code sort} as a value: a one- or two-argument lambda over the same sort. */
	static LispVal sortValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("sort-args"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("sort takes a collection and an optional comparator"));
		LispVal one = sortForm(ctx,
				ClojureSeqLowering.seqAllForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)), null);
		LispVal two = sortForm(ctx,
				ClojureSeqLowering.seqAllForm(ctx,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
				ClojureBindingLowering.FnArg.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)));
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

	/** {@code sort-by}: the seq view sorted by key, with an optional comparator. */
	static LispVal sortByOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3,
				"sort-by takes a key function, a collection and an optional comparator");
		LispVal coll = ClojureSeqLowering.seqAllForm(ctx, ctx.lower(items.get(n)));
		if (n == 2) {
			return sortByForm(ctx, ClojureBindingLowering.fnArg(ctx, items.get(1)), coll, null);
		}
		return sortByForm(ctx, ClojureBindingLowering.fnArg(ctx, items.get(1)), coll,
				ClojureBindingLowering.fnArg(ctx, items.get(2)));
	}

	/**
	 * The key sort over already-lowered key function, seq view and optional comparator:
	 * the comparator (or the default) runs on the keyed values.
	 */
	static LispVal sortByForm(ClojureLowering ctx, ClojureBindingLowering.FnArg keyFn, LispVal seq,
			ClojureBindingLowering.@Nullable FnArg cmp) {
		LispSymbol key = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol left = ctx.freshTemp();
		LispSymbol right = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(key, keyFn.fun()));
		bindings.add(ClojureLowerUtil.list(coll, ClojureLowerUtil.list(ClojureLowerUtil.sym("copy-list"), seq)));
		LispVal keyedLeft = ctx.callFun(keyFn.real(), key, List.of(left));
		LispVal keyedRight = ctx.callFun(keyFn.real(), key, List.of(right));
		LispVal predBody;
		if (cmp == null) {
			predBody = defaultCmpBody(ctx, keyedLeft, keyedRight);
		}
		else {
			LispSymbol fun = ctx.freshTemp();
			LispSymbol got = ctx.freshTemp();
			LispVal invoked = ctx.callFun(cmp.real(), fun, List.of(keyedLeft, keyedRight));
			bindings.add(ClojureLowerUtil.list(fun, cmp.fun()));
			predBody = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))), comparatorBefore(ctx, got));
		}
		LispVal pred = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(left, right)), predBody);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("sort"), coll, pred));
	}

	/**
	 * The default comparison over two already-lowered key forms: {@code compare}'s order
	 * "is first less than second", with {@code <} answering two numbers (the same answer,
	 * without the call into the runtime: the kind nearly every sort holds).
	 */
	static LispVal defaultCmpBody(ClojureLowering ctx, LispVal left, LispVal right) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), left, right),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), ClojureSortedLowering.runtime("compare", left, right),
						new LispInteger(0)));
	}

	/** {@code sort-by} as a value: key, then one or two more arguments. */
	static LispVal sortByValue(ClojureLowering ctx) {
		LispSymbol key = new LispSymbol(ClojureLowering.mangle("sort-by-key"));
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("sort-by-args"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("sort-by takes a key function, a collection and an optional comparator"));
		LispVal one = sortByForm(ctx, ClojureBindingLowering.FnArg.of(key),
				ClojureSeqLowering.seqAllForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)), null);
		LispVal two = sortByForm(ctx, ClojureBindingLowering.FnArg.of(key),
				ClojureSeqLowering.seqAllForm(ctx,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
				ClojureBindingLowering.FnArg.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)));
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
				ClojureLowerUtil.list(List.of(key, ClojureLowering.AMPERSAND_REST, args)), body);
	}

	/** {@code last} as a value: the final member, or nil. */
	static LispVal lastValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("last-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("last"), ClojureSeqLowering.seqAllForm(ctx, coll))));
	}

	/** {@code butlast} as a value: everything but the final member. */
	static LispVal butlastValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("butlast-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("butlast"), ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	/** {@code second} as a value: the member past the head, or nil. */
	static LispVal secondValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("second-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ClojureSeqLowering.secondForm(coll));
	}

	// Core convenience fns: strict vectors, head pairs, names, randomness

	/**
	 * {@code mapv} over an already-lowered function and collections (one or more): one
	 * call to the spliced {@code rontolisp::%clojure-mapv}, which realizes every input
	 * fully (lazy inputs answer strictly too) and coerces to a vector, like the oracle.
	 */
	static LispVal mapvOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 2, "mapv takes a function and collections");
		return mapvForm(ctx, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lowers(items, 2));
	}

	/**
	 * {@code mapv} over an already-lowered real function
	 * ({@link ClojureLowering#realFun}) and collections.
	 */
	static LispVal mapvForm(ClojureLowering ctx, LispVal fun, List<LispVal> colls) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPV"), fun,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), colls));
	}

	/** {@code mapv} as a value: over a function and one rest list of collections. */
	static LispVal mapvValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("mapv-fn"));
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("mapv-colls"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("mapv takes a function and collections"));
		LispVal call = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPV"), ClojureLowering.realFun(fn),
				colls);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), colls), arity, call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, colls)), body);
	}

	/**
	 * {@code filterv} over an already-lowered real predicate and collection: one call to
	 * the spliced {@code rontolisp::%clojure-filterv}, the strict vector arm of
	 * {@code filter}.
	 */
	static LispVal filtervForm(ClojureLowering ctx, LispVal fun, LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-FILTERV"), fun, coll);
	}

	/** {@code filterv} as a value: the predicate over the collection. */
	static LispVal filtervValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("filterv-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("filterv-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				filtervForm(ctx, ClojureLowering.realFun(pred), coll));
	}

	/**
	 * {@code mapcat} over an already-lowered function and collections (one or more): one
	 * call to the spliced {@code rontolisp::%clojure-mapcat}, the strict concat-of-maps
	 * over the seq views (nil-safe, like {@code concat}). A lone function is the
	 * transducer ({@link ClojureTransducerLowering}, which intercepts it first).
	 */
	static LispVal mapcatOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 2, "mapcat takes a function and collections");
		return mapcatForm(ctx, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lowers(items, 2));
	}

	/**
	 * {@code mapcat} over an already-lowered real function
	 * ({@link ClojureLowering#realFun}) and collections.
	 */
	static LispVal mapcatForm(ClojureLowering ctx, LispVal fun, List<LispVal> colls) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPCAT"), fun,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), colls));
	}

	/**
	 * {@code mapcat} as a value: over a function and one rest list of collections; of the
	 * function alone, the transducer.
	 */
	static LispVal mapcatValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("mapcat-fn"));
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("mapcat-colls"));
		LispVal call = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPCAT"), ClojureLowering.realFun(fn),
				colls);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), colls),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-XF-MAPCAT"), ClojureLowering.realFun(fn)),
				call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, colls)), body);
	}

	/**
	 * {@code ffirst} over an already-lowered seq view: the head of the head, each level
	 * through the view (so a vector head seqs before its own head is read).
	 */
	static LispVal ffirstForm(ClojureLowering ctx, LispVal seq) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
				ClojureSeqLowering.seqForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), seq)));
	}

	/** {@code ffirst} as a value: a one-argument lambda over the same heads. */
	static LispVal ffirstValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("ffirst-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				ffirstForm(ctx, ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/**
	 * {@code nfirst} over an already-lowered seq view: the tail of the head, each level
	 * through the view (of empty, nil -- the {@code next} shape, not {@code rest}).
	 */
	static LispVal nfirstForm(ClojureLowering ctx, LispVal seq) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
				ClojureSeqLowering.seqForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), seq)));
	}

	/** {@code nfirst} as a value: a one-argument lambda over the same tail. */
	static LispVal nfirstValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("nfirst-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				nfirstForm(ctx, ClojureSeqLowering.seqForm(ctx, coll)));
	}

}
