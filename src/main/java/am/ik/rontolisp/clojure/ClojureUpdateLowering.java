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
 * Update forms of the Clojure lowering: nested map verbs and frequency tables.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureUpdateLowering {

	private ClojureUpdateLowering() {
	}

	/**
	 * One association over already-lowered map, key and value: a fresh table over the old
	 * pairs plus the pair, so {@code assoc} onto nil builds from empty.
	 */
	static LispVal assocPairForm(ClojureLowering ctx, LispVal map, LispVal key, LispVal val) {
		LispSymbol one = ctx.freshTemp();
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(one),
				ClojureProtocolLowering.typedTableOf(one), one);
		LispVal grown = ClojureCollectionLowering.grownTable(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, src, ClojureLowering.NIL_CONST),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), key, val));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, map))),
				ClojureCollectionLowering.rewrapAnswer(ctx, one, grown));
	}

	/** {@code update}: the key rewritten through the function and extra arguments. */
	static LispVal updateOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 3, "update takes a map, a key, a function and arguments");
		LispSymbol map = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(map, ctx.lower(items.get(1))));
		bindings.add(ClojureLowerUtil.list(key, ctx.lower(items.get(2))));
		// the function form is bound by updateForm right behind the map and key, before
		// the extra arguments: the oracle's order
		LispVal fun = ClojureBindingLowering.fnValue(ctx, items.get(3));
		boolean real = ClojureBindingLowering.holdsRealFun(ctx, items.get(3), fun);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), updateForm(ctx, map,
				key, fun, real, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 4))));
	}

	/**
	 * The update over already-lowered map, key, function and the extra-arguments tail
	 * list: {@code (apply f (cons current tail))} associated back, copy-on-write. A real
	 * function form applies directly, anything else through the IFn dispatcher.
	 */
	static LispVal updateForm(ClojureLowering ctx, LispVal map, LispVal key, LispVal fun, LispVal tail) {
		return updateForm(ctx, map, key, fun, ClojureLowerUtil.yieldsFun(fun), tail);
	}

	/**
	 * {@link #updateForm(ClojureLowering, LispVal, LispVal, LispVal, LispVal)} over a
	 * function form already known to hold a real function ({@code real}) or not.
	 */
	private static LispVal updateForm(ClojureLowering ctx, LispVal map, LispVal key, LispVal fun, boolean real,
			LispVal tail) {
		LispSymbol one = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol fn = ctx.freshTemp();
		LispVal args = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
				ClojureCollectionLowering.getForm(ctx, one, at, ClojureLowering.NIL_CONST), tail);
		LispVal next = real ? ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), fn, args)
				: ctx.callableApply(fn, args);
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(one),
				ClojureProtocolLowering.typedTableOf(one), one);
		LispVal grown = ClojureCollectionLowering.grownTable(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, src, ClojureLowering.NIL_CONST),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), at, next));
		return ClojureLowerUtil.list(
				ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, map),
						ClojureLowerUtil.list(at, key), ClojureLowerUtil.list(fn, fun))),
				ClojureCollectionLowering.rewrapAnswer(ctx, one, grown));
	}

	/** {@code update} as a value: map, key, function and any extra arguments. */
	static LispVal updateValue(ClojureLowering ctx) {
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("update-map"));
		LispSymbol key = new LispSymbol(ClojureLowering.mangle("update-key"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("update-fn"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("update-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, key, fun, ClojureLowering.AMPERSAND_REST, rest)),
				updateForm(ctx, map, key, fun, rest));
	}

	/** The key vector of {@code update-in}/{@code assoc-in}/{@code get-in}. */
	static List<LispVal> keysVector(LispVal datum, String what) {
		List<LispVal> keyItems = ClojureLowerUtil.items(datum);
		if (keyItems == null || keyItems.isEmpty() || keyItems.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(what + " takes a vector of keys, not " + datum.print());
		}
		return keyItems.subList(1, keyItems.size());
	}

	/** {@code update-in}: the nested update, recursing down the key vector. */
	static LispVal updateInOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 3, "update-in takes a map, keys, a function and arguments");
		List<LispVal> keyData = keysVector(items.get(2), "update-in");
		ClojureLowerUtil.isTrue(!keyData.isEmpty(), "update-in takes a non-empty vector of keys");
		LispSymbol map = ctx.freshTemp();
		LispSymbol fun = ctx.freshTemp();
		LispVal fnForm = ClojureBindingLowering.fnValue(ctx, items.get(3));
		boolean real = ClojureBindingLowering.holdsRealFun(ctx, items.get(3), fnForm);
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(map, ctx.lower(items.get(1))));
		bindings.add(ClojureLowerUtil.list(fun, fnForm));
		List<LispVal> keys = new ArrayList<>();
		for (LispVal keyDatum : keyData) {
			LispSymbol key = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(key, ctx.lower(keyDatum)));
			keys.add(key);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), updateInForm(ctx,
				map, keys, fun, real, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 4))));
	}

	/**
	 * The nested update over already-lowered map, keys, function and extra-arguments
	 * tail: the leaf updates, outer levels re-associate through a one-argument lambda.
	 */
	static LispVal updateInForm(ClojureLowering ctx, LispVal map, List<LispVal> keys, LispVal fun, boolean real,
			LispVal tail) {
		if (keys.size() == 1) {
			return updateForm(ctx, map, keys.get(0), fun, real, tail);
		}
		LispSymbol inner = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(inner),
				updateInForm(ctx, inner, keys.subList(1, keys.size()), fun, real, tail));
		return updateForm(ctx, map, keys.get(0), step, ClojureLowering.NIL_CONST);
	}

	/** {@code update-in} as a value: the key sequence walked at run time. */
	static LispVal updateInValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("update-in-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("update-in-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("update-in-keys"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("update-in-fn"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("update-in-rest"));
		LispSymbol left = ctx.freshTemp();
		LispSymbol whole = ctx.freshTemp();
		LispSymbol inner = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(inner),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left), inner));
		LispVal go = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), left),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
						LispString.literal("update-in takes a non-empty vector of keys")),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left)),
						updateForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), fun, rest),
						updateForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), step,
								ClojureLowering.NIL_CONST)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(left, whole)), ClojureLowerUtil.cons(go, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, keys, fun, ClojureLowering.AMPERSAND_REST, rest)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, ClojureSeqLowering.seqAllForm(ctx, keys), map)));
	}

	/** {@code assoc-in}: the nested association, building missing levels. */
	static LispVal assocInOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 4, "assoc-in takes a map, keys and a value");
		List<LispVal> keyData = keysVector(items.get(2), "assoc-in");
		LispSymbol map = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(map, ctx.lower(items.get(1))));
		bindings.add(ClojureLowerUtil.list(val, ctx.lower(items.get(3))));
		if (keyData.isEmpty()) {
			// like the oracle: no keys associates under nil
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
					assocPairForm(ctx, map, ClojureLowering.NIL_CONST, val));
		}
		List<LispVal> keys = new ArrayList<>();
		for (LispVal keyDatum : keyData) {
			LispSymbol key = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(key, ctx.lower(keyDatum)));
			keys.add(key);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				assocInForm(ctx, map, keys, val));
	}

	/**
	 * The nested association over already-lowered map, keys and value: the leaf
	 * associates, outer levels re-associate through a one-argument lambda.
	 */
	static LispVal assocInForm(ClojureLowering ctx, LispVal map, List<LispVal> keys, LispVal val) {
		if (keys.size() == 1) {
			return assocPairForm(ctx, map, keys.get(0), val);
		}
		LispSymbol inner = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(inner),
				assocInForm(ctx, inner, keys.subList(1, keys.size()), val));
		return updateForm(ctx, map, keys.get(0), step, ClojureLowering.NIL_CONST);
	}

	/** {@code assoc-in} as a value: the key sequence walked at run time. */
	static LispVal assocInValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("assoc-in-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("assoc-in-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("assoc-in-keys"));
		LispSymbol val = new LispSymbol(ClojureLowering.mangle("assoc-in-val"));
		LispSymbol left = ctx.freshTemp();
		LispSymbol whole = ctx.freshTemp();
		LispSymbol inner = ctx.freshTemp();
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(inner),
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left), inner));
		LispVal go = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left)),
				assocPairForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), val),
				updateForm(ctx, whole, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), step,
						ClojureLowering.NIL_CONST));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(left, whole)), ClojureLowerUtil.cons(go, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(map, keys, val)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
						ClojureLowerUtil.list(self, ClojureSeqLowering.seqAllForm(ctx, keys), map)));
	}

	/**
	 * {@code get-in}: the read folded down the key vector, the default threaded through
	 * every level, like the oracle.
	 */
	static LispVal getInOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "get-in takes a map, keys and an optional default");
		List<LispVal> keyData = keysVector(items.get(2), "get-in");
		LispVal dflt = n == 3 ? ctx.lower(items.get(3)) : ClojureLowering.NIL_CONST;
		LispVal acc = ctx.lower(items.get(1));
		for (LispVal keyDatum : keyData) {
			acc = ClojureCollectionLowering.getForm(ctx, acc, ctx.lower(keyDatum), dflt);
		}
		return acc;
	}

	/** {@code get-in} as a value: the key sequence walked at run time. */
	static LispVal getInValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("get-in-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("get-in-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("get-in-keys"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("get-in-rest"));
		LispSymbol left = ctx.freshTemp();
		LispSymbol whole = ctx.freshTemp();
		LispSymbol dflt = ctx.freshTemp();
		LispVal go = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), left), whole,
				ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left),
						ClojureCollectionLowering.getForm(ctx, whole,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), dflt)));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(left, whole)), ClojureLowerUtil.cons(go, List.of())));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(map, keys, ClojureLowering.AMPERSAND_REST, rest)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(dflt, ClojureLowerUtil.list(
								ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest),
								ClojureLowering.NIL_CONST, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest))))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"), ClojureLowerUtil.list(List.of(binding)),
								ClojureLowerUtil.list(self, ClojureSeqLowering.seqAllForm(ctx, keys), map))));
	}

	/**
	 * {@code select-keys}: a fresh map holding the present keys only. Of nil, the empty
	 * map; anything else that is no map signals.
	 */
	static LispVal selectKeysForm(ClojureLowering ctx, LispVal map, LispVal keys) {
		LispSymbol whole = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol out = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispSymbol held = ctx.freshTemp();
		LispVal keep = ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), held, out), got);
		LispSymbol src = ctx.freshTemp();
		// the entry keeps the map's own key, like the oracle's find
		LispVal gather = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(one, keys)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
							ClojureLowerUtil.list(List.of(
									ClojureLowerUtil.list(held, ClojureCollectionLowering.lookupKey(one, src)),
									ClojureLowerUtil.list(got,
											ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), held, src, miss)))),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, miss),
									ClojureLowering.NIL_CONST, keep)));
		// a record reads through its entry table and answers a plain map, like the
		// oracle; anything opaque signals, like the oracle
		LispVal norm = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(whole),
				ClojureProtocolLowering.typedTableOf(whole), whole);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(whole, map),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(out, ClojureCollectionLowering.makeTable()))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), whole), out,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
								ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(src, norm))),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), src),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), gather, out),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
												LispString.literal("select-keys needs a map"))))));
	}

	/** {@code select-keys} as a value: a two-argument lambda over the same read. */
	static LispVal selectKeysValue(ClojureLowering ctx) {
		LispSymbol map = new LispSymbol(ClojureLowering.mangle("select-keys-map"));
		LispSymbol keys = new LispSymbol(ClojureLowering.mangle("select-keys-keys"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(map, keys)),
				selectKeysForm(ctx, map, ClojureSeqLowering.seqAllForm(ctx, keys)));
	}

	/** {@code merge-with}: every map merged, conflicts resolved through the function. */
	static LispVal mergeWithOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n >= 1, "merge-with takes a function and maps");
		if (n == 1) {
			return ClojureLowering.NIL_CONST;
		}
		if (n == 2) {
			return ctx.lower(items.get(2));
		}
		LispVal fun = ClojureBindingLowering.fnValue(ctx, items.get(1));
		List<LispVal> maps = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			maps.add(ctx.lower(items.get(i)));
		}
		return mergeWithForm(ctx, fun, maps);
	}

	/**
	 * The merge over an already-lowered function and maps: a fresh table grown map by
	 * map, so inputs are never mutated and nil maps contribute nothing.
	 */
	static LispVal mergeWithForm(ClojureLowering ctx, LispVal fun, List<LispVal> maps) {
		LispSymbol fn = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(fn, fun));
		bindings.add(ClojureLowerUtil.list(acc, ClojureCollectionLowering.makeTable()));
		bindings.add(ClojureLowerUtil.list(miss,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)));
		List<LispVal> syms = new ArrayList<>();
		for (LispVal map : maps) {
			LispSymbol one = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(one, map));
			syms.add(one);
		}
		List<LispVal> merges = new ArrayList<>();
		for (LispVal one : syms) {
			LispSymbol key = ctx.freshTemp();
			LispSymbol val = ctx.freshTemp();
			LispSymbol old = ctx.freshTemp();
			LispSymbol stored = ctx.freshTemp();
			LispVal invoked = ctx.callFun(fun, fn, List.of(old, val));
			// a record contributes its entries, like a map; anything opaque signals
			// in the maphash, like the oracle
			LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(one),
					ClojureProtocolLowering.typedTableOf(one), one);
			LispVal join = ClojureLowerUtil.list(ClojureLowerUtil.sym("maphash"), ClojureLowerUtil.list(
					ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(stored, ClojureCollectionLowering.storeKey(key, acc)),
								ClojureLowerUtil.list(old,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), stored, acc, miss)))),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), stored, acc),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), old, miss), val,
											invoked)))),
					src);
			merges.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), one, join, ClojureLowering.NIL_CONST));
		}
		merges.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), syms),
				ClojureCollectionLowering.rewrapAnswer(ctx, syms.get(0), acc), ClojureLowering.NIL_CONST));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), merges));
	}

	/**
	 * {@code merge-with} as a value: the function, then any number of maps, grown map by
	 * map through {@code f} like a call and rewrapped in the first rest map's record when
	 * there is one. Of no maps, {@code nil}.
	 */
	static LispVal mergeWithValue(ClojureLowering ctx) {
		String name = ClojureLowering.mangle("merge-with-") + (ctx.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fn = new LispSymbol(ClojureLowering.mangle("merge-with-fn"));
		LispSymbol maps = new LispSymbol(ClojureLowering.mangle("merge-with-maps"));
		LispSymbol left = ctx.freshTemp();
		LispSymbol acc = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol key = ctx.freshTemp();
		LispSymbol val = ctx.freshTemp();
		LispSymbol old = ctx.freshTemp();
		LispSymbol stored = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispSymbol probe = ctx.freshTemp();
		// a record contributes its entries, like a map; anything opaque signals
		// in the maphash, like the oracle (the mergeWithForm precedent)
		LispVal head = ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left);
		LispVal src = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isRecordForm(head),
				ClojureProtocolLowering.typedTableOf(head), head);
		LispVal join = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("maphash"), ClojureLowerUtil
				.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(key, val)), ClojureLowerUtil.list(
						ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(List.of(
								ClojureLowerUtil.list(stored, ClojureCollectionLowering.storeKey(key, acc)),
								ClojureLowerUtil.list(old,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), stored, acc, miss)))),
						ClojureLowerUtil
							.list(ClojureLowerUtil.sym("setf"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym(
											"gethash"), stored, acc),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), old, miss), val,
											ctx.callableApply(fn, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"),
													List.of(old, val))))))),
					src);
		LispVal answer = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), found, ClojureCollectionLowering
			.rewrapAnswer(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), maps), acc),
				ClojureLowering.NIL_CONST);
		LispVal go = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), left), answer,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), left), join, ClojureLowering.NIL_CONST),
						ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), left))));
		LispVal binding = new LispCons(self,
				new LispCons(ClojureLowerUtil.list(List.of(left)), ClojureLowerUtil.cons(go, List.of())));
		// the answer is nil unless some rest map is present, but the rewrap follows
		// the first rest map (a nil first map answers a plain map), like the oracle
		LispVal find = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
				ClojureLowerUtil.list(List.of(probe, maps)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), found), probe),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), found, probe)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(fn, ClojureLowering.AMPERSAND_REST, maps)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(acc, ClojureCollectionLowering.makeTable()),
								ClojureLowerUtil.list(miss,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
								ClojureLowerUtil.list(found, ClojureLowering.NIL_CONST))),
						find, ClojureLowerUtil.list(ClojureLowerUtil.sym("labels"),
								ClojureLowerUtil.list(List.of(binding)), ClojureLowerUtil.list(self, maps))));
	}

	/**
	 * {@code into}: the source conjoined onto the target, one member at a time; with a
	 * transducer between them, the source stepped through it first (the spliced
	 * {@code rontolisp::%clojure-into-xf}, the oracle's {@code (transduce xform conj to
	 * from)}).
	 */
	static LispVal intoOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n == 2 || n == 3, "into takes a target, an optional transducer and a source");
		if (n == 3) {
			LispVal to = ctx.lower(items.get(1));
			LispVal xf = ClojureBindingLowering.realFnValue(ctx, items.get(2));
			return intoXformForm(ctx, to, xf, ctx.lower(items.get(3)));
		}
		return intoForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
	}

	/** The conj step over the accumulated collection and one member. */
	private static LispVal conjStep(ClojureLowering ctx) {
		LispSymbol acc = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(acc, one)),
				ClojureCollectionLowering.conjTwoForm(ctx, acc, one));
	}

	/**
	 * The conj fold over an already-lowered target and source, through the spliced reduce
	 * (the lazy-aware walk, so a lazy source pours in whole).
	 */
	static LispVal intoForm(ClojureLowering ctx, LispVal to, LispVal from) {
		return ClojureSeqLowering.reduceForm(ctx, conjStep(ctx), from, to);
	}

	/** The conj fold through an already-lowered real transducer function. */
	static LispVal intoXformForm(ClojureLowering ctx, LispVal to, LispVal xf, LispVal from) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-INTO-XF"), to, xf, from, conjStep(ctx));
	}

	/**
	 * {@code into} as a value: a target and a source, or a target, a transducer and a
	 * source.
	 */
	static LispVal intoValue(ClojureLowering ctx) {
		LispSymbol to = new LispSymbol(ClojureLowering.mangle("into-to"));
		LispSymbol from = new LispSymbol(ClojureLowering.mangle("into-from"));
		LispSymbol source = new LispSymbol(ClojureLowering.mangle("into-source"));
		LispSymbol supplied = new LispSymbol(ClojureLowering.mangle("into-source-p"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(to, from, ClojureLowerUtil.sym("&optional"),
						ClojureLowerUtil.list(source, ClojureLowering.NIL_CONST, supplied))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), supplied,
						intoXformForm(ctx, to, ClojureLowering.realFun(from), source), intoForm(ctx, to, from)));
	}

	/**
	 * {@code frequencies}: the member counts in one pass over the seq view, as a fresh
	 * map.
	 */
	static LispVal frequenciesForm(ClojureLowering ctx, LispVal seq) {
		LispSymbol table = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol one = ctx.freshTemp();
		LispSymbol old = ctx.freshTemp();
		LispSymbol stored = ctx.freshTemp();
		LispVal bump = ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), stored, table),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), old, miss), new LispInteger(1),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), old, new LispInteger(1))));
		LispVal step = ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(one, seq)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil.list(List.of(
								ClojureLowerUtil.list(stored, ClojureCollectionLowering.storeKey(one, table)),
								ClojureLowerUtil.list(old,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), stored, table, miss)))),
						bump));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				step, table);
	}

	/** {@code frequencies} as a value: a one-argument lambda over the same pass. */
	static LispVal frequenciesValue(ClojureLowering ctx) {
		LispSymbol coll = new LispSymbol(ClojureLowering.mangle("frequencies-coll"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
				frequenciesForm(ctx, ClojureSeqLowering.seqAllForm(ctx, coll)));
	}

	// Higher-order functions: closures, no new runtime

}
