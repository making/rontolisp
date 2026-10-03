package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The type and collection predicates of the Clojure lowering ({@code seq?}, {@code map?},
 * {@code keyword?}, {@code int?}, {@code identical?}, ...). A one-argument predicate is
 * one test answering a Common Lisp boolean -- a CL type predicate or a spliced
 * {@code rontolisp::%clojure-is-} helper in {@code clojure.lisp} -- wrapped in
 * {@code (if test T false)}; as a value it is a one-argument lambda over the same test. A
 * predicate whose kind no value here can have ({@code delay?}, {@code future?},
 * {@code decimal?}, ...) answers false after evaluating its argument. {@code set?} and
 * {@code reversible?} name the sorted-aware helpers, which a program building no sorted
 * collection calls as the plain ones ({@link ClojureSortedArms#ALIASES}).
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojurePredicateLowering {

	private ClojurePredicateLowering() {
	}

	/**
	 * A one-argument test: the function called on the value, then the constant extra
	 * arguments.
	 */
	private record Test(String function, List<LispVal> extra) {

		LispVal over(LispVal value) {
			List<LispVal> call = new ArrayList<>();
			call.add(new LispSymbol(this.function));
			call.add(value);
			call.addAll(this.extra);
			return ClojureLowerUtil.list(call);
		}

	}

	private static final LispVal T = ClojureLowering.TRUE_CONST;

	private static final LispVal NIL = ClojureLowering.NIL_CONST;

	private static final Map<String, Test> TESTS = Map.ofEntries(Map.entry("seq?", helper("IS-SEQ")),
			Map.entry("sequential?", helper("IS-SEQUENTIAL")), Map.entry("map?", helper("IS-MAP")),
			Map.entry("set?", helper("IS-SET")), Map.entry("list?", helper("IS-LIST")),
			Map.entry("record?", helper("RECORD-P")), Map.entry("coll?", helper("IS-COLL")),
			Map.entry("seqable?", helper("IS-SEQABLE")), Map.entry("associative?", helper("IS-ASSOCIATIVE")),
			Map.entry("counted?", helper("IS-COUNTED")), Map.entry("indexed?", helper("IS-VECTOR")),
			Map.entry("reversible?", helper("IS-REVERSIBLE")), Map.entry("ifn?", helper("IS-IFN")),
			Map.entry("sorted?", helper("IS-SORTED")), Map.entry("number?", cl("NUMBERP")),
			Map.entry("integer?", cl("INTEGERP")), Map.entry("int?", helper("IS-INT")),
			Map.entry("double?", cl("FLOATP")), Map.entry("float?", cl("FLOATP")),
			Map.entry("ratio?", helper("IS-RATIO")), Map.entry("rational?", cl("RATIONALP")),
			Map.entry("nat-int?", helper("IS-NAT-INT")), Map.entry("pos-int?", helper("IS-POS-INT")),
			Map.entry("neg-int?", helper("IS-NEG-INT")), Map.entry("infinite?", helper("IS-INFINITE")),
			Map.entry("NaN?", helper("IS-NAN")), Map.entry("keyword?", helper("KEYWORD-P")),
			Map.entry("ident?", helper("IS-IDENT")), Map.entry("simple-ident?", qualified(T, T, NIL)),
			Map.entry("qualified-ident?", qualified(T, T, T)), Map.entry("simple-keyword?", qualified(T, NIL, NIL)),
			Map.entry("qualified-keyword?", qualified(T, NIL, T)), Map.entry("simple-symbol?", qualified(NIL, T, NIL)),
			Map.entry("qualified-symbol?", qualified(NIL, T, T)), Map.entry("char?", cl("CHARACTERP")),
			Map.entry("var?", helper("VAR-P")), Map.entry("volatile?", helper("IS-VOLATILE")),
			Map.entry("realized?", helper("IS-REALIZED")), Map.entry("special-symbol?", helper("IS-SPECIAL-SYMBOL")),
			Map.entry("inst?", helper("IS-INST")), Map.entry("uuid?", host("java.util.UUID")),
			Map.entry("uri?", host("java.net.URI")), Map.entry("class?", host("java.lang.Class")));

	/**
	 * The predicates of a kind no value here can have: no chunked seq, decimal, byte
	 * array, delay, future, reader conditional or tagged literal exists on any backend,
	 * so each answers false for every value, which is the oracle's answer for every value
	 * a program here can build.
	 */
	private static final List<String> NEVER = List.of("chunked-seq?", "decimal?", "bytes?", "delay?", "future?",
			"reader-conditional?", "tagged-literal?");

	/**
	 * A predicate in call position, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		Test test = TESTS.get(name);
		if (test != null) {
			ClojureCoreLowering.arity(name, n, 1, 1);
			return ctx.booleanAnswer(test.over(ctx.lower(items.get(1))));
		}
		if (NEVER.contains(name) || name.equals("any?")) {
			ClojureCoreLowering.arity(name, n, 1, 1);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.lower(items.get(1)),
					name.equals("any?") ? T : ctx.falseVariable);
		}
		switch (name) {
			case "not-any?", "not-every?": {
				ClojureCoreLowering.arity(name, n, 2, 2);
				ClojureBindingLowering.FnArg fn = ClojureBindingLowering.fnArg(ctx, items.get(1));
				LispVal seq = ClojureSeqLowering.seqForm(ctx, ctx.lower(items.get(2)));
				return negated(ctx, name.equals("not-any?") ? ClojureFilterLowering.someForm(ctx, fn, seq)
						: ClojureFilterLowering.everyForm(ctx, fn, seq));
			}
			case "identical?":
				ClojureCoreLowering.arity(name, n, 2, 2);
				return ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-IDENTICAL"), ctx.lower(items.get(1)),
						ctx.lower(items.get(2))));
			case "distinct?":
				ClojureCoreLowering.arity(name, n, 1, -1);
				return ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-DISTINCT"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1))));
			case "bound?":
				return ClojureLowerUtil.list(helperSymbol("IS-BOUND"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1)));
			case "thread-bound?":
				return ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-THREAD-BOUND"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1))));
			case "extends?":
				ClojureCoreLowering.arity(name, n, 2, 2);
				return extendsOf(ctx, items);
			case "future-done?", "future-cancelled?":
				throw new LispReadException(name + " is not supported yet: there is no thread pool on any backend");
			default:
				return null;
		}
	}

	/**
	 * A predicate as a function value, or null when the name is none of them.
	 * {@code extends?} has none: its protocol and type are names read at lower time.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(ClojureLowering ctx, String name) {
		Test test = TESTS.get(name);
		if (test != null) {
			return ClojureFnLowering.predValue(ctx, test::over);
		}
		if (NEVER.contains(name) || name.equals("any?")) {
			LispSymbol one = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), one,
					name.equals("any?") ? T : ctx.falseVariable);
		}
		switch (name) {
			case "not-any?", "not-every?": {
				LispSymbol pred = new LispSymbol(ClojureLowering.mangle(name + "-pred"));
				LispSymbol coll = new LispSymbol(ClojureLowering.mangle(name + "-coll"));
				ClojureBindingLowering.FnArg fn = ClojureBindingLowering.FnArg.of(pred);
				LispVal seq = ClojureSeqLowering.seqForm(ctx, coll);
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(pred, coll)),
						negated(ctx, name.equals("not-any?") ? ClojureFilterLowering.someForm(ctx, fn, seq)
								: ClojureFilterLowering.everyForm(ctx, fn, seq)));
			}
			case "identical?": {
				LispSymbol a = ctx.freshTemp();
				LispSymbol b = ctx.freshTemp();
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(a, b)),
						ctx.booleanAnswer(ClojureLowerUtil.list(helperSymbol("IS-IDENTICAL"), a, b)));
			}
			case "distinct?":
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), helperSymbol("IS-DISTINCT-V"));
			case "bound?":
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), helperSymbol("IS-BOUND-V"));
			case "thread-bound?":
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), helperSymbol("IS-THREAD-BOUND-V"));
			default:
				return null;
		}
	}

	/**
	 * {@code (extends? Protocol Type)}: whether the type has a row in the protocol's
	 * table -- an inline implementation or an extension -- or, for {@code Object},
	 * whether the protocol was extended to {@code Object}, like the oracle (which counts
	 * an {@code Object} extension for no other type). Both are literal names, like
	 * {@code satisfies?}'s protocol.
	 */
	private static LispVal extendsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "extends? takes a protocol name");
		ClojureLowerUtil.isTrue(items.get(2) instanceof LispSymbol, "extends? takes a type name");
		String protocol = ((LispSymbol) items.get(1)).name();
		ClojureLowering.ProtocolDef def = ClojureProtocolLowering.protocolOf(ctx, protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		LispVal key = ClojureProtocolLowering.extendKeyForm(ctx, ((LispSymbol) items.get(2)).name(), "extends?");
		ctx.usedProtocols = true;
		if (key == null) {
			return ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), def.defaultVar())));
		}
		LispSymbol miss = ctx.freshTemp();
		List<LispVal> hits = new ArrayList<>();
		hits.add(ClojureLowerUtil.sym("or"));
		hits.add(rowHit(key, def.methodsVar(), miss));
		if (def.inlineVar() != null) {
			hits.add(rowHit(key, def.inlineVar(), miss));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(miss,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ctx.booleanAnswer(ClojureLowerUtil.list(hits)));
	}

	/** Whether the table holds a row under the key: its lookup is not the miss marker. */
	private static LispVal rowHit(LispVal key, LispSymbol table, LispSymbol miss) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("not"), ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table, miss), miss));
	}

	/** {@code T} when the {@code some}/{@code every?} answer is falsey, else false. */
	private static LispVal negated(ClojureLowering ctx, LispVal answer) {
		LispSymbol got = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got, answer))),
				ctx.booleanAnswer(ctx.isFalsey(got)));
	}

	private static Test helper(String name) {
		return new Test(helperSymbol(name).name(), List.of());
	}

	private static Test cl(String name) {
		return new Test(name, List.of());
	}

	private static Test qualified(LispVal keywords, LispVal symbols, LispVal qualified) {
		return new Test(helperSymbol("IS-QUALIFIED").name(), List.of(keywords, symbols, qualified));
	}

	private static Test host(String className) {
		return new Test(helperSymbol("HOST-INSTANCE-P").name(), List.of(LispString.literal(className)));
	}

	private static LispSymbol helperSymbol(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name);
	}

}
