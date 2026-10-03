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
	 * {@code keep}: the non-nil results of the function over the seq view. {@code false}
	 * is kept (only nil drops), and a signalling function signals --
	 * {@code (keep inc [1 nil 2])} throws, like the oracle, instead of skipping.
	 */
	static LispVal keepForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		LispSymbol fun = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal mapped = ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), ctx.callFun(fn, fun, List.of(one))), coll);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remove-if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("null")), mapped));
	}

	/** {@code keep} as a value: a two-argument lambda over the same removal. */
	static LispVal keepValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("keep-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("keep-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				keepForm(ctx, fun, ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	/**
	 * {@code keep-indexed}: like {@code keep}, but the function takes the index and the
	 * item. A labels self call accumulating in reverse, so it stays tail-recursive.
	 */
	static LispVal keepIndexedForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("keep-indexed-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn, fun, List.of(at, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
								ClojureLowerUtil.list(self,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, new LispInteger(1)),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest), acc),
								ClojureLowerUtil.list(self,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, new LispInteger(1)),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), got, acc)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(at, rest, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, new LispInteger(0), coll, ClojureLowering.NIL_CONST)));
	}

	/** {@code keep-indexed} as a value: a two-argument lambda over the same loop. */
	static LispVal keepIndexedValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("keep-indexed-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("keep-indexed-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				keepIndexedForm(ctx, fun, ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	/**
	 * {@code map-indexed}: the function of index and item over the seq view, strictly.
	 * Same loop as {@link #keepIndexedForm}, keeping every result.
	 */
	static LispVal mapIndexedForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("map-indexed-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn, fun, List.of(at, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, new LispInteger(1)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), invoked, acc)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(at, rest, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, new LispInteger(0), coll, ClojureLowering.NIL_CONST)));
	}

	/** {@code map-indexed} as a value: a two-argument lambda over the same loop. */
	static LispVal mapIndexedValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("map-indexed-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("map-indexed-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				mapIndexedForm(ctx, fun, ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	/**
	 * {@code every?}: true when the predicate holds for every member, answering
	 * {@code T}-or-false directly (empty is true, like the oracle).
	 */
	static LispVal everyForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("every-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil
			.sym("null"), rest), ClojureLowering.TRUE_CONST, ClojureLowerUtil.list(
					ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
							ctx.callFun(fn, fun, List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)))))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
							ctx.falseVariable, ClojureLowerUtil.list(self, ClojureSeqLowering.seqRestForm(rest)))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code every?} as a value: a two-argument lambda over the same loop. */
	static LispVal everyValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("every-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("every-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				everyForm(ctx, pred, ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/**
	 * {@code some}: the first truthy predicate result, or nil. The predicate's own value
	 * answers (not the member), like the oracle.
	 */
	static LispVal someForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("some-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil
			.sym("null"), rest), ClojureLowering.NIL_CONST, ClojureLowerUtil.list(
					ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
							ctx.callFun(fn, fun, List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)))))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
							ClojureLowerUtil.list(self, ClojureSeqLowering.seqRestForm(rest)), got)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code some} as a value: a two-argument lambda over the same loop. */
	static LispVal someValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("some-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("some-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				someForm(ctx, pred, ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/** {@code remove}: the members the predicate rejects, over the seq view. */
	static LispVal removeForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		LispSymbol pred = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoke = ClojureLowerUtil.isDirectFun(fn)
				? ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), pred, one)
				: ctx.callableApply(pred, ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), one));
		LispVal test = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoke))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pred, fn), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remove-if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), test), coll));
	}

	/** {@code remove} as a value: a two-argument lambda over the same removal. */
	static LispVal removeValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("remove-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("remove-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				removeForm(ctx, pred, ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	/**
	 * {@code distinct}: the seq view with later duplicates dropped, first occurrences
	 * kept in order. Membership is {@code equal} (vectors key by identity, like the table
	 * runtime).
	 */
	static LispVal distinctForm(ClojureLowering ctx, LispVal seq) {
		String name = ClojureLowering.mangle("distinct-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol table = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal keep = ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table), one),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), one, acc)));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List
							.of(ClojureLowerUtil.list(one, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table, miss), miss),
								keep, ClojureLowerUtil.list(self,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), rest), acc))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest, acc)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq, ClojureLowering.NIL_CONST)));
	}

	/** {@code distinct} as a value: a one-argument lambda over the same loop. */
	static LispVal distinctValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("distinct-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				distinctForm(ctx, ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	/** {@code partition}: size, optional step (defaulting to the size), collection. */
	static LispVal partitionOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "partition takes a size, an optional step and a collection");
		LispSymbol size = ctx.freshTemp();
		LispSymbol step = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(size, ctx.lower(items.get(1))));
		bindings.add(ClojureLowerUtil.list(step, n == 3 ? ctx.lower(items.get(2)) : size));
		bindings.add(ClojureLowerUtil.list(coll, ClojureSeqLowering.seqAllForm(ctx, ctx.lower(items.get(n)))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				partitionForm(ctx, size, step, coll));
	}

	/**
	 * The partition loop over already-bound size, step and seq: full groups consed, an
	 * incomplete tail dropped, like the oracle. A non-positive size signals.
	 */
	static LispVal partitionForm(ClojureLowering ctx, LispVal size, LispVal step, LispVal seq) {
		String name = ClojureLowering.mangle("partition-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol rest = ctx.freshTemp();
		LispSymbol part = ctx.freshTemp();
		LispVal stepBody = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest), ClojureLowering.NIL_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil
							.list(List.of(ClojureLowerUtil.list(part, ClojureSeqLowering.takeForm(ctx, size, rest)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("<"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), part), size),
								ClojureLowering.NIL_CONST,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), part, ClojureLowerUtil.list(self,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("nthcdr"), step, rest))))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(stepBody, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<="), size, new LispInteger(0)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
						LispString.literal("partition takes a positive size")),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code partition} as a value: a one- or two-rest lambda over the same loop. */
	static LispVal partitionValue(ClojureLowering ctx) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("partition-args"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("partition takes a size, an optional step and a collection"));
		LispVal one = partitionForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
				ClojureSeqLowering.seqAllForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))));
		LispVal two = partitionForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
				ClojureSeqLowering.seqAllForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("cdr"), ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)))));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args), arity),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
												ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
								one, arity)),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args)))),
						two),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, arity));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), body);
	}

	/**
	 * {@code take-while}: the strict prefix while the predicate stays truthy
	 * ({@code false} stops, like nil).
	 */
	static LispVal takeWhileForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("take-while-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pred = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn, pred, List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
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
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pred, fn))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq, ClojureLowering.NIL_CONST)));
	}

	/** {@code take-while} as a value: a two-argument lambda over the same loop. */
	static LispVal takeWhileValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("take-while-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("take-while-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				takeWhileForm(ctx, pred, ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/** {@code drop-while}: the seq view past the truthy prefix, sharing the tail. */
	static LispVal dropWhileForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		String name = ClojureLowering.mangle("drop-while-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pred = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal invoked = ctx.callFun(fn, pred, List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest)));
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
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(pred, fn))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, seq)));
	}

	/** {@code drop-while} as a value: a two-argument lambda over the same loop. */
	static LispVal dropWhileValue(ClojureLowering ctx) {
		LispSymbol pred = new LispSymbol(ClojureLowering.mangle("drop-while-pred"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("drop-while-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
				dropWhileForm(ctx, pred, ClojureSeqLowering.seqForm(ctx, coll)));
	}

	/** {@code interleave}: round-robin over the seq views, stopping at the shortest. */
	static LispVal interleaveOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return ClojureLowering.NIL_CONST;
		}
		List<LispVal> seqs = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			seqs.add(ClojureSeqLowering.seqForm(ctx, ctx.lower(items.get(i))));
		}
		return interleaveGo(ctx, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), seqs));
	}

	/**
	 * The interleave loop over an already-lowered list of seq views: heads appended while
	 * every view is non-empty.
	 */
	static LispVal interleaveGo(ClojureLowering ctx, LispVal lists) {
		String name = ClojureLowering.mangle("interleave-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol rest = ctx.freshTemp();
		LispVal stop = ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureLowerUtil.list(ClojureLowerUtil.sym("every"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("identity")),
						rest)));
		LispVal step = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("if"), stop, ClojureLowering.NIL_CONST, ClojureLowerUtil.list(
					ClojureLowerUtil.sym("append"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("car")), rest),
					ClojureLowerUtil.list(self,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcar"), ClojureLowerUtil
								.list(ClojureLowerUtil.sym("function"), new LispSymbol("RONTOLISP::%CLOJURE-SEQ-REST")),
									rest))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(rest)), ClojureLowerUtil.cons(step, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
				ClojureLowerUtil.list(self, lists));
	}

	/** {@code interleave} as a value: every argument's seq view interleaved. */
	static LispVal interleaveValue(ClojureLowering ctx) {
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("interleave-colls"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, colls), interleaveGo(ctx, ClojureLowerUtil
					.list(ClojureLowerUtil.sym("mapcar"), ClojureSeqLowering.seqValue(ctx), colls)));
	}

	/**
	 * {@code interpose}: the separator between every two members, strictly. The head
	 * answers bare, so a one-member collection never shows the separator.
	 */
	static LispVal interposeForm(ClojureLowering ctx, LispVal sep, LispVal seq) {
		LispSymbol gap = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispVal looped = ClojureLowerUtil.list(ClojureLowerUtil.sym("mapcan"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), gap, one)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), coll));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(gap, sep), ClojureLowerUtil.list(coll, seq))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), coll), ClojureLowering.NIL_CONST,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), coll), looped)));
	}

	/** {@code interpose} as a value: a two-argument lambda over the same shape. */
	static LispVal interposeValue(ClojureLowering ctx) {
		LispSymbol gap = new LispSymbol(ClojureLowering.mangle("interpose-sep"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("interpose-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(gap, coll)),
				interposeForm(ctx, gap, ClojureSeqLowering.seqAllForm(ctx, coll)));
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
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), keyRest), table),
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
	static LispVal groupByForm(ClojureLowering ctx, LispVal fn, LispVal seq) {
		LispSymbol fun = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispVal keyed = ctx.callFun(fn, fun, List.of(one));
		LispVal collect = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(one, seq)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(key, keyed))),
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
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(fun, fn),
							ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
					collect, freeze, table);
	}

	/** {@code group-by} as a value: a two-argument lambda over the same pass. */
	static LispVal groupByValue(ClojureLowering ctx) {
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("group-by-fn"));
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("group-by-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(fun, coll)),
				groupByForm(ctx, fun, ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	/** {@code sort}: the seq view copied and sorted, with an optional comparator. */
	static LispVal sortOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 1 || n == 2, "sort takes a collection and an optional comparator");
		LispVal seq = ClojureSeqLowering.seqAllForm(ctx, ctx.lower(items.get(n)));
		if (n == 1) {
			return sortForm(ctx, seq, null);
		}
		return sortForm(ctx, seq, ClojureBindingLowering.fnValue(ctx, items.get(1)));
	}

	/**
	 * The sort over an already-lowered seq view: a copy (the primitive sorts
	 * destructively) under the default or wrapped comparator.
	 */
	static LispVal sortForm(ClojureLowering ctx, LispVal seq, @Nullable LispVal cmp) {
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
			bindings.add(ClojureLowerUtil.list(fun, cmp));
			LispVal truthy = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(got, ctx.callFun(cmp, fun, List.of(left, right))))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
							ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST));
			pred = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(left, right)),
					truthy);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("sort"), coll, pred));
	}

	/**
	 * The default comparator: numbers with {@code <}, strings with {@code string<},
	 * characters with {@code char<}, keywords by spelling; anything else signals instead
	 * of answering wrongly.
	 */
	static LispVal defaultCmpFn(ClojureLowering ctx) {
		LispSymbol left = new LispSymbol(ClojureLowering.mangle("sort-a"));
		LispSymbol right = new LispSymbol(ClojureLowering.mangle("sort-b"));
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("char<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), keywordTest(left), keywordTest(right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string<"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), right))));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil
			.list(ClojureLowerUtil.sym("error"), LispString.literal("sort needs mutually comparable elements"))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(left, right)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
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
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("sort takes a collection and an optional comparator"));
		LispVal one = sortForm(ctx,
				ClojureSeqLowering.seqAllForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)), null);
		LispVal two = sortForm(ctx,
				ClojureSeqLowering.seqAllForm(ctx,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
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
			return sortByForm(ctx, ClojureBindingLowering.fnValue(ctx, items.get(1)), coll, null);
		}
		return sortByForm(ctx, ClojureBindingLowering.fnValue(ctx, items.get(1)), coll,
				ClojureBindingLowering.fnValue(ctx, items.get(2)));
	}

	/**
	 * The key sort over already-lowered key function, seq view and optional comparator:
	 * the comparator (or the default) runs on the keyed values.
	 */
	static LispVal sortByForm(ClojureLowering ctx, LispVal keyFn, LispVal seq, @Nullable LispVal cmp) {
		LispSymbol key = ctx.freshTemp();
		LispSymbol coll = ctx.freshTemp();
		LispSymbol left = ctx.freshTemp();
		LispSymbol right = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(key, keyFn));
		bindings.add(ClojureLowerUtil.list(coll, ClojureLowerUtil.list(ClojureLowerUtil.sym("copy-list"), seq)));
		LispVal keyedLeft = ctx.callFun(keyFn, key, List.of(left));
		LispVal keyedRight = ctx.callFun(keyFn, key, List.of(right));
		LispVal predBody;
		if (cmp == null) {
			predBody = defaultCmpBody(ctx, keyedLeft, keyedRight);
		}
		else {
			LispSymbol fun = ctx.freshTemp();
			LispSymbol got = ctx.freshTemp();
			LispVal invoked = ctx.callFun(cmp, fun, List.of(keyedLeft, keyedRight));
			bindings.add(ClojureLowerUtil.list(fun, cmp));
			predBody = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, invoked))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), got),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, ctx.falseVariable)),
							ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST));
		}
		LispVal pred = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(left, right)), predBody);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("sort"), coll, pred));
	}

	/** The default comparison over two already-lowered key forms. */
	static LispVal defaultCmpBody(ClojureLowering ctx, LispVal left, LispVal right) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("char<"), left, right)));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), keywordTest(left), keywordTest(right)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("string<"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), left),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), right))));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil
			.list(ClojureLowerUtil.sym("error"), LispString.literal("sort needs mutually comparable elements"))));
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches);
	}

	/** {@code sort-by} as a value: key, then one or two more arguments. */
	static LispVal sortByValue(ClojureLowering ctx) {
		LispSymbol key = new LispSymbol(ClojureLowering.mangle("sort-by-key"));
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("sort-by-args"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("sort-by takes a key function, a collection and an optional comparator"));
		LispVal one = sortByForm(ctx, key,
				ClojureSeqLowering.seqAllForm(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args)), null);
		LispVal two = sortByForm(ctx, key,
				ClojureSeqLowering.seqAllForm(ctx,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), args))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args));
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
				ClojureSeqLowering.nthForm(ctx, coll, new LispInteger(1), ClojureLowering.NIL_CONST));
	}

	// b18 core convenience fns: strict vectors, head pairs, names, randomness

	/**
	 * {@code mapv} over an already-lowered function and collections (one or more): one
	 * call to the spliced {@code rontolisp::%clojure-mapv}, which realizes every input
	 * fully (lazy inputs answer strictly too) and coerces to a vector, like the oracle.
	 */
	static LispVal mapvOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 2, "mapv takes a function and collections");
		return mapvForm(ctx, ClojureBindingLowering.fnValue(ctx, items.get(1)), ctx.lowers(items, 2));
	}

	/** {@code mapv} over an already-lowered function and collections. */
	static LispVal mapvForm(ClojureLowering ctx, LispVal fun, List<LispVal> colls) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPV"), fun,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), colls));
	}

	/** {@code mapv} as a value: over a function and one rest list of collections. */
	static LispVal mapvValue(ClojureLowering ctx) {
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("mapv-fn"));
		LispSymbol colls = new LispSymbol(ClojureLowering.mangle("mapv-colls"));
		LispVal arity = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("mapv takes a function and collections"));
		LispVal call = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPV"), fn, colls);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), colls), arity, call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, colls)), body);
	}

	/**
	 * {@code filterv} over an already-lowered predicate and collection: one call to the
	 * spliced {@code rontolisp::%clojure-filterv}, the strict vector arm of
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
				filtervForm(ctx, pred, coll));
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
		return mapcatForm(ctx, ClojureBindingLowering.fnValue(ctx, items.get(1)), ctx.lowers(items, 2));
	}

	/** {@code mapcat} over an already-lowered function and collections. */
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
		LispVal call = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-MAPCAT"), fn, colls);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), colls),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-XF-MAPCAT"), fn), call);
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
