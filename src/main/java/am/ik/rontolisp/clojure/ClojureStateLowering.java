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
import java.util.function.Supplier;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * State forms of the Clojure lowering: atoms, STM, binding, try and defonce/defstruct.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureStateLowering {

	private ClojureStateLowering() {
	}

	/**
	 * The {@code try} body lowered behind the barrier: the depth grows while it lowers,
	 * so a {@code recur} inside one trips the barrier no matter where the {@code try}
	 * itself sits. The body is never tail position either.
	 */
	static LispVal tryBodyOf(ClojureLowering ctx, List<LispVal> body) {
		boolean outerTail = ctx.tailPosition;
		ctx.tailPosition = false;
		ctx.tryDepth++;
		try {
			return ctx.bodyOf(body);
		}
		finally {
			ctx.tryDepth--;
			ctx.tailPosition = outerTail;
		}
	}

	/**
	 * {@code (try body... (catch Class var body...)... (finally ...))}: the body guarded
	 * by a {@code handler-case} inside an {@code unwind-protect}. Each catch clause is a
	 * {@code handler-case} clause of the type its class takes
	 * ({@link ClojureThrowables#clauseType}: an unknown class is the oracle's refusal),
	 * in order, so the first clause whose class takes the condition runs, with its var
	 * bound to the condition, and a condition no clause takes is never caught here -- the
	 * oracle's exception passing a catch that does not name its class. The body lowers
	 * behind the {@code try} barrier (a {@code recur} across it is the oracle's
	 * {@code Cannot recur across try} refusal); the catch and finally parts are never
	 * tail position (a {@code recur} there is the oracle's tail refusal), like the
	 * oracle.
	 */
	static LispVal tryOf(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> body = new ArrayList<>();
		List<List<LispVal>> catches = new ArrayList<>();
		List<LispVal> fin = new ArrayList<>();
		boolean closed = false;
		for (int i = 1; i < items.size(); i++) {
			List<LispVal> part = ClojureLowerUtil.items(items.get(i));
			if (part != null && !part.isEmpty() && ClojureLowerUtil.isSymbolNamed(part.get(0), "catch")) {
				ClojureLowerUtil.isTrue(part.size() >= 3 && part.get(1) instanceof LispSymbol,
						"catch takes a class, a name and a body");
				ClojureLowerUtil.plainName(part.get(2), "catch");
				catches.add(part);
				closed = true;
				continue;
			}
			if (part != null && !part.isEmpty() && ClojureLowerUtil.isSymbolNamed(part.get(0), "finally")) {
				fin.addAll(part.subList(1, part.size()));
				closed = true;
				continue;
			}
			ClojureLowerUtil.isTrue(!closed, "a try body comes before catch and finally");
			body.add(items.get(i));
		}
		LispVal guarded = body.isEmpty() ? ClojureLowering.NIL_CONST : tryBodyOf(ctx, body);
		if (!catches.isEmpty()) {
			ctx.usedCatch = true;
			List<LispVal> handler = new ArrayList<>();
			handler.add(ClojureLowerUtil.sym("HANDLER-CASE"));
			handler.add(guarded);
			for (List<LispVal> caught : catches) {
				LispVal type = ClojureThrowables.clauseType(ctx,
						ClojureThrowables.caughtChain(ctx, caught.get(1), "catch"));
				String var = ((LispSymbol) caught.get(2)).name();
				LispVal clauseBody = ctx.inScope(new HashMap<>(Map.of(var, ClojureLowering.Kind.VARIABLE)),
						() -> ctx.nonTailBodyOf(caught.subList(3, caught.size())));
				LispCons clause = (LispCons) ClojureLowerUtil.list(type, ClojureLowerUtil.list(ctx.localSym(var)),
						clauseBody);
				ctx.recordCatch(clause);
				handler.add(clause);
			}
			guarded = ClojureLowerUtil.list(handler);
		}
		if (!fin.isEmpty()) {
			List<LispVal> forms = new ArrayList<>();
			forms.add(ClojureLowerUtil.sym("UNWIND-PROTECT"));
			forms.add(guarded);
			for (LispVal f : fin) {
				forms.add(ctx.lower(f));
			}
			guarded = ClojureLowerUtil.list(forms);
		}
		return guarded;
	}

	// state: atoms and volatiles over a tagged one-vector cell

	/**
	 * The tag heading an atom: {@code (:C%ATOM #(value))}, the set wrapper's shape, so
	 * the verbs can tell an atom from a plain vector. The value lives in a vector cell,
	 * which every backend reads and writes; printing spells the wrapper, not the oracle's
	 * object syntax.
	 */
	static final LispSymbol ATOM_TAG = new LispSymbol(":C%ATOM");

	/**
	 * The cell of an atom or a ref: {@code #(value nil validator state watches)} in a
	 * program that may give a reference a validator or a watch, the one-vector
	 * {@code #(value)} in every other ({@link ClojureArms.Family#WATCH} folds it to
	 * {@code vector}).
	 */
	static final LispSymbol REF_CELL = new LispSymbol("RONTOLISP::%CLOJURE-REF-CELL");

	static LispVal wrapAtom(LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ATOM_TAG, ClojureLowerUtil.list(REF_CELL, value));
	}

	/**
	 * The second slot of a volatile's cell: a volatile is an atom whose cell carries it,
	 * so every atom verb reads and writes it unchanged while {@code volatile?} tells the
	 * two apart, like the oracle's two classes.
	 */
	static final LispSymbol VOLATILE_MARK = new LispSymbol(":C%VOLATILE");

	static LispVal wrapVolatile(LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ATOM_TAG,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), value, VOLATILE_MARK));
	}

	/** {@code volatile!} as a value: a one-argument lambda over the constructor. */
	static LispVal volatileValue() {
		LispSymbol init = new LispSymbol(ClojureLowering.mangle("volatile-init"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(init), wrapVolatile(init));
	}

	/** Whether the form holds a wrapped atom: the tag over a one-vector cell. */
	static LispVal isAtomForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), ATOM_TAG),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), form)));
	}

	/** The value inside an already-bound atom: the cell's only element. */
	static LispVal atomGet(LispVal bound) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), bound), new LispInteger(0));
	}

	/** The cell rewritten to the value: answers the value. */
	static LispVal atomPut(LispVal bound, LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"), ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), bound), new LispInteger(0)), value);
	}

	/**
	 * The lowered atom checked and handed to the body builder: anything else signals,
	 * like the oracle's cast failure. The atom runs once, behind a temporary.
	 */
	static LispVal withAtom(ClojureLowering ctx, LispVal lowered, String op,
			java.util.function.Function<LispVal, LispVal> body) {
		LispSymbol cell = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isAtomForm(cell), body.apply(cell), ClojureRefusals
					.refusal(ClojureRefusals.CLASS_CAST_OF, LispString.literal(op + " needs an atom"), cell)));
	}

	/**
	 * {@code (atom init & options)}: the wrapped cell; options go to the spliced
	 * {@link #ATOM_OPTIONS}, which reads them at run time like the oracle's
	 * {@code (apply hash-map options)} -- {@code :meta} first, then a {@code :validator}
	 * the initial value must pass, any other key ignored.
	 */
	static LispVal atomOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("atom", items.size() - 1, 1, -1);
		LispVal atom = wrapAtom(ctx.lower(items.get(1)));
		if (items.size() == 2) {
			return atom;
		}
		return ClojureLowerUtil.list(ATOM_OPTIONS, atom,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)));
	}

	/** {@code atom}'s options: the metadata and the validator of a fresh atom. */
	static final LispSymbol ATOM_OPTIONS = new LispSymbol("RONTOLISP::%CLOJURE-ATOM-OPTIONS");

	static LispVal derefOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2 || items.size() == 4, "deref takes one or three arguments");
		if (items.size() == 4) {
			return timedDerefForm(ctx, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
		}
		return derefForm(ctx, ctx.lower(items.get(1)));
	}

	/**
	 * The three-argument {@code deref}: a Clojure future or promise, a rontolisp future
	 * (the {@code rontolisp.http-client} answer under {@code :async true}) and a host
	 * {@code Future} are an {@code IBlockingDeref} here, so every other value, once the
	 * timeout and the timeout value have run like the oracle's arguments, is its
	 * {@code ClassCastException} (nil a {@code NullPointerException}). A program that can
	 * make neither kind folds every arm away ({@link ClojureArms.Family#FUTURE},
	 * {@link ClojureArms.Family#FETCH}, {@link ClojureArms.Family#HOST}), leaving the
	 * refusal.
	 */
	private static LispVal timedDerefForm(ClojureLowering ctx, LispVal future, LispVal timeout, LispVal timeoutValue) {
		LispSymbol cell = ctx.freshTemp();
		LispSymbol ms = ctx.freshTemp();
		LispSymbol dflt = ctx.freshTemp();
		LispVal host = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(ClojureDispatchLowering.HOST_OBJECT_P), cell,
						LispString.literal(HOST_FUTURE)),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-HOST-FUTURE-GET-WITHIN"), cell, ms, dflt),
				ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST_OF,
						LispString.literal("deref with a timeout needs a future"), cell));
		LispVal promised = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureFutureLowering.promiseTest(cell),
				ClojureLowerUtil.list(new LispSymbol(ClojureFutureLowering.PROMISE_GET_WITHIN), cell, ms, dflt), host);
		// a rontolisp future (rontolisp.http-client's :async answer) waits through the
		// await runtime: an arm a program that fetches nothing folds
		LispVal fetched = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(FUTURE_P), cell),
				ClojureLowerUtil.list(new LispSymbol(ClojureArms.FUTURE_GET_WITHIN), cell, ms, dflt), promised);
		// a Clojure future (future, future-call) waits on its host task, wrapping
		// the stored condition like the one-argument deref
		LispVal wrapped = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureFutureLowering.wrapperTest(cell),
				ClojureLowerUtil.list(new LispSymbol(ClojureFutureLowering.FUTURE_CLOJURE_GET_WITHIN), cell, ms, dflt),
				fetched);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, future), ClojureLowerUtil.list(ms, timeout),
						ClojureLowerUtil.list(dflt, timeoutValue))),
				wrapped);
	}

	/** The host class {@code deref} reads through its {@code get}. */
	static final String HOST_FUTURE = "java.util.concurrent.Future";

	/**
	 * The fetch family's test of a rontolisp future ({@code clojure.lisp}): what
	 * {@code rontolisp.http-client} answers under {@code :async true}.
	 */
	static final String FUTURE_P = "RONTOLISP::%CLOJURE-FUTURE-P";

	/**
	 * The fetch family's test of a rontolisp stream ({@code clojure.lisp}): the reply
	 * body {@code rontolisp.http-client} answers under {@code :as :stream}.
	 */
	static final String ASYNC_STREAM_P = "RONTOLISP::%CLOJURE-ASYNC-STREAM-P";

	/**
	 * The value inside the lowered atom; anything else goes to the spliced
	 * {@code rontolisp::%clojure-deref-other}, which answers a reduced value's content
	 * (the oracle's {@code Reduced} is an {@code IDeref}), a var's root or a host
	 * {@code Future}'s result, and signals otherwise. The atom's read goes through the
	 * tracking worker, which records the read set entry a conflicting commit retries on
	 * (outside a transaction it answers the cell directly).
	 */
	static LispVal derefForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol cell = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(cell, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isAtomForm(cell),
						ClojureLowerUtil.list(STM_DEREF, cell),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DEREF-OTHER"), cell)));
	}

	/**
	 * {@code swap!} and {@code vswap!}. {@code swap!} is the oracle's retry loop, the
	 * spliced {@link #ATOM_SWAP}: a Ring handler runs one thread per request on the
	 * interpreter and the JVM, and a function that swaps the same atom itself makes the
	 * outer store retry on every backend. A volatile, which the oracle does not make
	 * atomic either, is read, the function applied and the cell written.
	 */
	static LispVal swapOf(ClojureLowering ctx, List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		ClojureLowerUtil.isTrue(items.size() >= 3, op + " takes an atom, a function and arguments");
		LispVal loweredAtom = ctx.lower(items.get(1));
		LispVal fun = items.get(2);
		List<LispVal> rest = items.subList(3, items.size());
		return withAtom(ctx, loweredAtom, op, cell -> {
			List<LispVal> tail = new ArrayList<>();
			for (LispVal arg : rest) {
				tail.add(ctx.lower(arg));
			}
			if (atomic(op)) {
				return ClojureLowerUtil.list(ATOM_SWAP, cell, ClojureBindingLowering.fnValue(ctx, fun),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), tail));
			}
			LispVal spread = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), atomGet(cell),
					ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), tail));
			LispSymbol next = ctx.freshTemp();
			return ClojureLowerUtil
				.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
										ClojureBindingLowering.fnValue(ctx, fun), spread)))),
						atomPut(cell, next), next);
		});
	}

	static LispVal resetOf(ClojureLowering ctx, List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		ClojureLowerUtil.isTrue(items.size() == 3, op + " takes an atom and a value");
		LispVal value = ctx.lower(items.get(2));
		return withAtom(ctx, ctx.lower(items.get(1)), op, cell -> {
			if (atomic(op)) {
				return ClojureLowerUtil.list(ATOM_RESET, cell, value);
			}
			LispSymbol next = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next, value))), atomPut(cell, next), next);
		});
	}

	/**
	 * {@code compare-and-set!}: the cell rewritten only when its value is {@code eql} to
	 * the expected one -- value comparison for numbers, identity for everything else,
	 * which is how the oracle's reference comparison answers on coalesced literals --
	 * answering a Clojure boolean. The expected value runs before the new one, like the
	 * oracle's arguments.
	 */
	static LispVal compareAndSetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 4, "compare-and-set! takes an atom, an expected value and a new value");
		LispVal wanted = ctx.lower(items.get(2));
		LispVal value = ctx.lower(items.get(3));
		return withAtom(ctx, ctx.lower(items.get(1)), "compare-and-set!", cell -> {
			LispSymbol expected = ctx.freshTemp();
			LispSymbol next = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(expected, wanted), ClojureLowerUtil.list(next, value))),
					compareAndSetBuild(ctx, cell, expected, next));
		});
	}

	/**
	 * The compare and the store over an already-bound atom, expected value and new value,
	 * as one step: the spliced {@link #ATOM_CAS}, answering a Clojure boolean.
	 */
	private static LispVal compareAndSetBuild(ClojureLowering ctx, LispVal cell, LispVal wanted, LispVal value) {
		return ctx.booleanAnswer(ClojureLowerUtil.list(ATOM_CAS, cell, wanted, value));
	}

	/**
	 * The spliced helpers that make an atom's update one step where a Ring handler runs
	 * one thread per request ({@code clojure.lisp}, "Atoms across request threads" and
	 * "Validators and watches"): {@code swap!}'s retry loop, {@code compare-and-set!}'s
	 * compare and store, {@code reset!}'s store and the two {@code -vals} verbs, each
	 * over the atom, running its validator and calling its watches. A program that can
	 * give no reference either has each renamed to the plain {@code %clojure-atom-}
	 * helper ({@link ClojureArms.Family#WATCH}). On wasm their guard is a no-op.
	 */
	static final LispSymbol ATOM_SWAP = new LispSymbol("RONTOLISP::%CLOJURE-REF-SWAP");

	static final LispSymbol ATOM_CAS = new LispSymbol("RONTOLISP::%CLOJURE-REF-CAS");

	static final LispSymbol ATOM_RESET = new LispSymbol("RONTOLISP::%CLOJURE-REF-RESET");

	static final LispSymbol ATOM_SWAP_VALS = new LispSymbol("RONTOLISP::%CLOJURE-REF-SWAP-VALS");

	static final LispSymbol ATOM_RESET_VALS = new LispSymbol("RONTOLISP::%CLOJURE-REF-RESET-VALS");

	/** The aware helpers, each to the plain one a program without validators calls. */
	static final Map<String, String> WATCH_ALIASES = Map.of(REF_CELL.name(), "VECTOR", ATOM_SWAP.name(),
			"RONTOLISP::%CLOJURE-ATOM-SWAP", ATOM_CAS.name(), "RONTOLISP::%CLOJURE-ATOM-CAS", ATOM_RESET.name(),
			"RONTOLISP::%CLOJURE-ATOM-RESET", ATOM_SWAP_VALS.name(), "RONTOLISP::%CLOJURE-ATOM-SWAP-VALS",
			ATOM_RESET_VALS.name(), "RONTOLISP::%CLOJURE-ATOM-RESET-VALS");

	/**
	 * The views over a store the plain code already made, each answering its first
	 * argument: the call of a reference's watches after it, and a var's validator ahead
	 * of it, and the transaction's snapshot and call of its refs' watches.
	 */
	static final Set<String> WATCH_VIEWS = Set.of("RONTOLISP::%CLOJURE-REF-NOTIFIED",
			"RONTOLISP::%CLOJURE-REF-VALIDATED", "RONTOLISP::%CLOJURE-STM-NOTED", "RONTOLISP::%CLOJURE-STM-NOTIFIED",
			"RONTOLISP::%CLOJURE-VAR-VALIDATED");

	/**
	 * What may give a reference a validator or a watch: the verbs, called or as values,
	 * and an atom's options.
	 */
	static final Set<String> WATCH_PRODUCERS = Set.of("RONTOLISP::%CLOJURE-ADD-WATCH",
			"RONTOLISP::%CLOJURE-ADD-WATCH-V", "RONTOLISP::%CLOJURE-SET-VALIDATOR",
			"RONTOLISP::%CLOJURE-SET-VALIDATOR-V", "RONTOLISP::%CLOJURE-SET-VALIDATOR-MACRO", ATOM_OPTIONS.name());

	/**
	 * The WATCH-family test of a var named by its {@code "ns/name"}: whether the interned
	 * var has watches or a validator.
	 */
	static final LispSymbol VAR_GUARDED_P = new LispSymbol("RONTOLISP::%CLOJURE-VAR-GUARDED-P");

	/**
	 * The var-root store: {@code (store getter setter init "ns/name")}, over a
	 * zero-argument getter closure reading the value cell and a one-argument setter
	 * closure storing it, folding to the {@code setq} of the cell with the init
	 * ({@link ClojureArms.Family#stores}).
	 */
	static final LispSymbol VAR_STORE = new LispSymbol("RONTOLISP::%CLOJURE-VAR-STORE");

	/**
	 * A view over a dynamic definition's init: the init validated against the var's
	 * validator, its replaced root stashed for {@link #VAR_NOTIFY_STORED}.
	 */
	static final LispSymbol VAR_VALIDATED = new LispSymbol("RONTOLISP::%CLOJURE-VAR-VALIDATED");

	/**
	 * The notify statement behind a dynamic definition: the var's watches called with the
	 * stashed root and the new one.
	 */
	static final LispSymbol VAR_NOTIFY_STORED = new LispSymbol("RONTOLISP::%CLOJURE-VAR-NOTIFY-STORED");

	/**
	 * The validate-and-notify statement behind a {@code defn} with no value cell: the new
	 * function validated and the var's watches called with the old and the new one.
	 */
	static final LispSymbol VAR_DEFN_NOTIFY = new LispSymbol("RONTOLISP::%CLOJURE-VAR-DEFN-NOTIFY");

	/**
	 * The root a definition replaces, read off the value cell: the cell where bound, else
	 * the unbound root of the var -- a conditional definition above may never have run. A
	 * pure read for a bound cell, so a notify statement may name it.
	 * @param cell the value cell
	 * @param key the var key
	 * @return the guarded read
	 */
	static LispVal guardedOldRead(LispSymbol cell, String key) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("boundp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), cell)),
				cell, ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-UNBOUND"), LispString.literal(key)));
	}

	/**
	 * The store replacing a var's root, validating the init and notifying the var's
	 * watches like the oracle's {@code bindRoot}: {@code (store getter setter init
	 * "ns/name")} over closures over the value cell, folding to its {@code setq}. The
	 * getter answers the unbound root where the cell is still unbound -- the first
	 * binding, which no watch or validator can already guard -- so the store never reads
	 * an unbound variable.
	 * @param ctx the hub
	 * @param cell the value cell
	 * @param init the already-lowered init
	 * @param key the var key
	 * @return the store
	 */
	static LispVal varStore(ClojureLowering ctx, LispSymbol cell, LispVal init, String key) {
		// A fixed parameter name: no program spells it (like the fresh temporaries),
		// and it keeps the temporaries around the store numbered as before.
		LispSymbol value = new LispSymbol("__clojure_new_root");
		LispVal getter = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(),
				guardedOldRead(cell, key));
		LispVal setter = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(value),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), cell, value));
		return ClojureLowerUtil.list(VAR_STORE, getter, setter, init, LispString.literal(key));
	}

	/**
	 * A {@code with-redefs} set or restore of a var's root: the plain {@code setq} of the
	 * cell, or the validating and notifying store over it where the var is guarded -- an
	 * {@code if} arm the strip folds back to the {@code setq}.
	 * @param ctx the hub
	 * @param cell the value cell
	 * @param value the already-lowered value (a temporary)
	 * @param key the var key
	 * @return the guarded store
	 */
	static LispVal guardedVarStore(ClojureLowering ctx, LispSymbol cell, LispSymbol value, String key) {
		LispVal plain = ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), cell, value);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(VAR_GUARDED_P, LispString.literal(key)), varStore(ctx, cell, value, key), plain);
	}

	/**
	 * A dynamic definition's init validated against the var's validator, its replaced
	 * root stashed for {@link #varNotifyStored}: a view answering the init.
	 * @param init the already-lowered init
	 * @param key the var key
	 * @return the validated init
	 */
	static LispVal validatedInit(LispVal init, String key) {
		return ClojureLowerUtil.list(VAR_VALIDATED, init, LispString.literal(key));
	}

	/**
	 * The notify statement behind a dynamic definition: the var's watches called with the
	 * stashed root and the cell's new value. A statement arm the strip drops.
	 * @param key the var key
	 * @param cell the value cell, read once stored
	 * @return the statement
	 */
	static LispVal varNotifyStored(String key, LispSymbol cell) {
		return ClojureLowerUtil.list(VAR_NOTIFY_STORED, LispString.literal(key), cell);
	}

	/**
	 * The validate-and-notify statement behind a {@code defn} with no value cell: the new
	 * function validated and the var's watches called with the old and the new one. A
	 * statement arm the strip drops.
	 * @param key the var key
	 * @param next the new function
	 * @param prev the replaced function
	 * @return the statement
	 */
	static LispVal varDefnNotify(String key, LispVal next, LispVal prev) {
		return ClojureLowerUtil.list(VAR_DEFN_NOTIFY, LispString.literal(key), next, prev);
	}

	/**
	 * {@code (swap-vals! atom f args...)} and {@code (reset-vals! atom value)}: the store
	 * of {@code swap!} and {@code reset!}, answering {@code [old new]}.
	 */
	static LispVal swapValsOf(ClojureLowering ctx, List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		boolean swap = op.equals("swap-vals!");
		ClojureCoreLowering.arity(op, items.size() - 1, 2, swap ? -1 : 2);
		LispVal loweredAtom = ctx.lower(items.get(1));
		LispVal second = swap ? ClojureBindingLowering.fnValue(ctx, items.get(2)) : ctx.lower(items.get(2));
		LispVal rest = ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3));
		return withAtom(ctx, loweredAtom, op, cell -> swap ? ClojureLowerUtil.list(ATOM_SWAP_VALS, cell, second, rest)
				: ClojureLowerUtil.list(ATOM_RESET_VALS, cell, second));
	}

	/** {@code swap-vals!}/{@code reset-vals!} as a value. */
	static LispVal swapValsValue(ClojureLowering ctx, String op) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("vals-cell"));
		LispSymbol arg = new LispSymbol(ClojureLowering.mangle("vals-arg"));
		if (op.equals("reset-vals!")) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(cell, arg)),
					withAtom(ctx, cell, op, bound -> ClojureLowerUtil.list(ATOM_RESET_VALS, bound, arg)));
		}
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("vals-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cell, arg, ClojureLowering.AMPERSAND_REST, rest)),
				withAtom(ctx, cell, op, bound -> ClojureLowerUtil.list(ATOM_SWAP_VALS, bound, arg, rest)));
	}

	/**
	 * The validator and watch verbs: each one call to its {@code clojure.lisp} worker
	 * over a fixed argument count, and its {@code -v} function as a value.
	 */
	private static final Map<String, AgentVerb> WATCH_VERBS = Map.of("add-watch",
			new AgentVerb("RONTOLISP::%CLOJURE-ADD-WATCH", 3), "remove-watch",
			new AgentVerb("RONTOLISP::%CLOJURE-REMOVE-WATCH", 2), "set-validator!",
			new AgentVerb("RONTOLISP::%CLOJURE-SET-VALIDATOR", 2), "get-validator",
			new AgentVerb("RONTOLISP::%CLOJURE-GET-VALIDATOR", 1));

	/**
	 * A validator or watch verb's call, or null when the call names none of them.
	 */
	static @Nullable LispVal watchRow(ClojureLowering ctx, String name, List<LispVal> items) {
		if (name.equals("set-validator!") && items.size() == 3) {
			LispVal macroValidator = macroValidatorOf(ctx, items);
			if (macroValidator != null) {
				return macroValidator;
			}
		}
		AgentVerb verb = WATCH_VERBS.get(name);
		if (verb == null) {
			return null;
		}
		ClojureCoreLowering.arity(name, items.size() - 1, verb.arity(), verb.arity());
		return ClojureLowerUtil.cons(new LispSymbol(verb.worker()), ctx.lowers(items, 1));
	}

	/**
	 * {@code set-validator!} of a macro's var, as the lowering's own worker: the var's
	 * site reads no value (a macro has none -- deref of it signals), so the macro's
	 * present expander travels as the value the validator checks. Only a literal
	 * {@code #'name} below its definition takes this path; a computed one reads the
	 * expander the note behind its {@code defmacro} registered, through the plain worker.
	 */
	static final LispSymbol SET_VALIDATOR_MACRO = new LispSymbol("RONTOLISP::%CLOJURE-SET-VALIDATOR-MACRO");

	/**
	 * The note a {@code defmacro} site carries behind its table entry: the macro's
	 * present expander registered under its var key, so a computed {@code set-validator!}
	 * of its var checks the expander without touching the var's signaling site. A
	 * statement the strip drops where the program names no watch verb.
	 */
	static final LispSymbol NOTE_MACRO_EXPANDER = new LispSymbol("RONTOLISP::%CLOJURE-NOTE-MACRO-EXPANDER");

	/**
	 * {@code set-validator!} of a literal macro var below its definition, or null for any
	 * other target.
	 */
	private static @Nullable LispVal macroValidatorOf(ClojureLowering ctx, List<LispVal> items) {
		String target = ClojureVarLowering.literalVarName(items.get(1));
		String key = target == null ? null : ctx.resolveVar(target);
		LispVal expander = key == null ? null : ctx.macros.get(key);
		if (key == null || expander == null || ctx.globals.get(key) != ClojureLowering.Kind.MACRO) {
			return null;
		}
		List<LispVal> call = new ArrayList<>(ctx.lowers(items, 1));
		call.add(0, SET_VALIDATOR_MACRO);
		call.add(expander);
		return ClojureLowerUtil.list(call);
	}

	/** A validator or watch verb as a value: its library function, or null for none. */
	static @Nullable LispVal watchVerbValue(String name) {
		AgentVerb verb = WATCH_VERBS.get(name);
		return verb == null ? null
				: ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), new LispSymbol(verb.worker() + "-V"));
	}

	/**
	 * Whether the verb goes through the guarded helpers: an atom's, never a volatile's
	 * ({@code vswap!}/{@code vreset!} are a plain read and write in the oracle too).
	 */
	private static boolean atomic(String op) {
		return !op.startsWith("v");
	}

	/** {@code atom} as a value: a lambda of the initial value and options. */
	static LispVal atomValue(ClojureLowering ctx) {
		LispSymbol init = new LispSymbol(ClojureLowering.mangle("atom-init"));
		LispSymbol options = new LispSymbol(ClojureLowering.mangle("atom-options"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(init, ClojureLowering.AMPERSAND_REST, options)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), options,
						ClojureLowerUtil.list(ATOM_OPTIONS, wrapAtom(init), options), wrapAtom(init)));
	}

	/** {@code deref} as a value: a one-argument lambda over the reader. */
	static LispVal derefValue(ClojureLowering ctx) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("deref-cell"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(cell), derefForm(ctx, cell));
	}

	/**
	 * {@code swap!} (or {@code vswap!}, named by {@code op}) as a value: over an atom, a
	 * function and any more arguments.
	 */
	static LispVal swapValue(ClojureLowering ctx, String op) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("swap-cell"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("swap-fun"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("swap-rest"));
		LispSymbol next = new LispSymbol(ClojureLowering.mangle("swap-next"));
		LispVal spread = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), atomGet(cell), rest);
		LispVal update = atomic(op) ? ClojureLowerUtil.list(ATOM_SWAP, cell, fun, rest)
				: ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(next,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), fun, spread)))),
						atomPut(cell, next), next);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cell, fun, ClojureLowering.AMPERSAND_REST, rest)),
				withAtom(ctx, cell, "swap!", ignored -> update));
	}

	/**
	 * {@code reset!} (or {@code vreset!}, named by {@code op}) as a value: a two-argument
	 * lambda over the writer.
	 */
	static LispVal resetValue(ClojureLowering ctx, String op) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("reset-cell"));
		LispSymbol value = new LispSymbol(ClojureLowering.mangle("reset-value"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(cell, value)),
				withAtom(ctx, cell, "reset!",
						bound -> atomic(op) ? ClojureLowerUtil.list(ATOM_RESET, bound, value) : atomPut(bound, value)));
	}

	/**
	 * {@code ex-data}/{@code ex-message}/{@code ex-cause} as a value: a one-argument
	 * lambda over the library reader.
	 */
	static LispVal exHelperValue(ClojureLowering ctx, String helper) {
		ctx.usedExInfo = true;
		LispSymbol ex = new LispSymbol(ClojureLowering.mangle("ex-value"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(ex),
				ClojureLowerUtil.list(new LispSymbol(helper), ex));
	}

	/**
	 * {@code ex-info} as a value: a lambda of a message, data and an optional cause over
	 * the library constructor.
	 */
	static LispVal exInfoValue(ClojureLowering ctx) {
		ctx.usedExInfo = true;
		ctx.hostExceptionClasses.add(EXCEPTION_INFO);
		LispSymbol message = new LispSymbol(ClojureLowering.mangle("ex-message"));
		LispSymbol data = new LispSymbol(ClojureLowering.mangle("ex-data"));
		LispSymbol cause = new LispSymbol(ClojureLowering.mangle("ex-cause"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(message, data, ClojureLowerUtil.sym("&optional"), cause)),
				ClojureLowerUtil.list(new LispSymbol(EX_INFO), message, data, cause));
	}

	/** The class {@code ex-info} builds an exception of. */
	static final String EXCEPTION_INFO = "clojure.lang.ExceptionInfo";

	/** The library functions behind the exception verbs ({@code clojure.lisp}). */
	static final String EX_INFO = "RONTOLISP::%CLOJURE-EX-INFO";

	static final String EX_MESSAGE = "RONTOLISP::%CLOJURE-EX-MESSAGE";

	static final String EX_DATA = "RONTOLISP::%CLOJURE-EX-DATA";

	static final String EX_CAUSE = "RONTOLISP::%CLOJURE-EX-CAUSE";

	static final String THROWABLE_TO_MAP = "RONTOLISP::%CLOJURE-THROWABLE-TO-MAP";

	static final String THROW = "RONTOLISP::%CLOJURE-THROW";

	static final String EXCEPTION_NEW = "RONTOLISP::%CLOJURE-EXCEPTION-NEW";

	static final String EXCEPTION_NEW_1 = "RONTOLISP::%CLOJURE-EXCEPTION-NEW-1";

	static final String EXCEPTION_METHOD = "RONTOLISP::%CLOJURE-EXCEPTION-METHOD";

	/**
	 * {@code (ex-info message data cause?)}: an ExceptionInfo through the library
	 * constructor.
	 */
	static LispVal exInfoOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3 || items.size() == 4,
				"ex-info takes a message, a data map and an optional cause");
		ctx.usedExInfo = true;
		ctx.hostExceptionClasses.add(EXCEPTION_INFO);
		return ClojureLowerUtil.list(new LispSymbol(EX_INFO), ctx.lower(items.get(1)), ctx.lower(items.get(2)),
				items.size() == 4 ? ctx.lower(items.get(3)) : ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code (ex-message e)}, {@code (ex-data e)}, {@code (ex-cause e)},
	 * {@code (Throwable->map e)} and {@code (throw e)}: one call to the library function.
	 */
	static LispVal exReaderOf(ClojureLowering ctx, List<LispVal> items, String helper) {
		ClojureLowerUtil.isTrue(items.size() == 2, ((LispSymbol) items.get(0)).name() + " takes one exception");
		ctx.usedExInfo = true;
		return ClojureLowerUtil.list(new LispSymbol(helper), ctx.lower(items.get(1)));
	}

	/** {@code compare-and-set!} as a value: a three-argument lambda over the swap. */
	static LispVal compareAndSetValue(ClojureLowering ctx) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("cas-cell"));
		LispSymbol wanted = new LispSymbol(ClojureLowering.mangle("cas-wanted"));
		LispSymbol value = new LispSymbol(ClojureLowering.mangle("cas-value"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cell, wanted, value)),
				withAtom(ctx, cell, "compare-and-set!", bound -> compareAndSetBuild(ctx, bound, wanted, value)));
	}

	// state: refs and agents over the atom cell (the spliced helpers of clojure.lisp,
	// "Refs and agents across request threads"), binding over specials, and the small
	// imperative companions

	/** {@code dosync}: the body's thunk run as one transaction. */
	static final LispSymbol STM_RUN = new LispSymbol("RONTOLISP::%CLOJURE-STM-RUN");

	/** The refusal of a transaction verb outside {@code dosync}. */
	static final LispSymbol STM_REQUIRE = new LispSymbol("RONTOLISP::%CLOJURE-STM-REQUIRE");

	/** {@code alter}/{@code commute}/{@code ref-set}'s logged write. */
	static final LispSymbol STM_WRITE = new LispSymbol("RONTOLISP::%CLOJURE-STM-WRITE");

	/** {@code deref} of a reference read in a transaction. */
	static final LispSymbol STM_DEREF = new LispSymbol("RONTOLISP::%CLOJURE-STM-DEREF");

	/** {@code ensure}'s read: recorded and pinned to the transaction. */
	static final LispSymbol STM_ENSURE = new LispSymbol("RONTOLISP::%CLOJURE-STM-ENSURE");

	/** {@code commute}'s deferred application. */
	static final LispSymbol STM_COMMUTE = new LispSymbol("RONTOLISP::%CLOJURE-STM-COMMUTE");

	/** A ref with a validator. */
	static final LispSymbol REF_MAKE = new LispSymbol("RONTOLISP::%CLOJURE-REF-MAKE");

	/** An agent. */
	static final LispSymbol AGENT_MAKE = new LispSymbol("RONTOLISP::%CLOJURE-AGENT-MAKE");

	/** {@code send}/{@code send-off}: an action queued on the agent. */
	static final LispSymbol AGENT_SEND = new LispSymbol("RONTOLISP::%CLOJURE-AGENT-SEND");

	/** {@code await} on a host target, waiting for another thread's run of the queue. */
	static final LispSymbol AGENT_AWAIT = new LispSymbol("RONTOLISP::%CLOJURE-AGENT-AWAIT");

	/** {@code await} on wasm, where this thread runs every queue. */
	static final LispSymbol AGENT_AWAIT_HERE = new LispSymbol("RONTOLISP::%CLOJURE-AGENT-AWAIT-HERE");

	/** The acting-agent var, {@code *agent*}. */
	private static final LispSymbol AGENT_VAR = new LispSymbol("C%AGENT");

	/**
	 * The agent runtime, spliced once behind the false binding when the program sends to
	 * an agent, awaits one or reads {@code *agent*}: the acting-agent var, bound while an
	 * action runs, nil outside one. Transactions and agents are the spliced helpers.
	 */
	static List<LispVal> stmRuntime() {
		List<LispVal> runtime = new ArrayList<>();
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defvar"), AGENT_VAR, ClojureLowering.NIL_CONST));
		return runtime;
	}

	/** The STM transaction guard: the body runs only inside {@code dosync}. */
	static LispVal txnGuard(LispVal guarded) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ClojureLowerUtil.list(STM_REQUIRE), guarded);
	}

	/**
	 * The ref-history family's views: the registration beside a ref's construction,
	 * answering the ref, and the commit's count bump, answering its first argument.
	 */
	static final Set<String> REF_HISTORY_VIEWS = Set.of("RONTOLISP::%CLOJURE-REF-HISTORY-TRACK",
			"RONTOLISP::%CLOJURE-REF-HISTORY-NOTED");

	/**
	 * What tracks a ref for its history: the registration beside a ref's construction,
	 * the history readers (called or as values) and the min/max setters.
	 */
	static final Set<String> REF_HISTORY_PRODUCERS = Set.of("RONTOLISP::%CLOJURE-REF-HISTORY-TRACK",
			"RONTOLISP::%CLOJURE-REF-HISTORY-COUNT", "RONTOLISP::%CLOJURE-REF-HISTORY-COUNT-V",
			"RONTOLISP::%CLOJURE-REF-MIN-HISTORY", "RONTOLISP::%CLOJURE-REF-MIN-HISTORY-V",
			"RONTOLISP::%CLOJURE-REF-MIN-HISTORY-SET", "RONTOLISP::%CLOJURE-REF-MAX-HISTORY",
			"RONTOLISP::%CLOJURE-REF-MAX-HISTORY-V", "RONTOLISP::%CLOJURE-REF-MAX-HISTORY-SET");

	/** The ref-history family's registration: {@code (track ref min max)}. */
	static final LispSymbol REF_HISTORY_TRACK = new LispSymbol("RONTOLISP::%CLOJURE-REF-HISTORY-TRACK");

	/**
	 * A {@code ref}/{@code agent} option list over already-lowered inits: the
	 * {@code :validator} as a callable form, the {@code :min-history} and
	 * {@code :max-history} as lowered forms, each null when absent. {@code :meta} is
	 * dropped, like every other metadata; anything else is refused by name.
	 */
	private record RefOptions(@Nullable LispVal validator, @Nullable LispVal min, @Nullable LispVal max) {
	}

	/**
	 * A {@code ref}/{@code agent} option list over an already-lowered init: the
	 * {@code :validator} as a callable form, or null. {@code :meta} is dropped, like
	 * every other metadata; anything else is refused by name.
	 */
	static @Nullable LispVal validatorOpt(ClojureLowering ctx, List<LispVal> items, int from, String op) {
		return refOptions(ctx, items, from, op).validator();
	}

	/**
	 * A {@code ref}'s option list: the validator, min-history and max-history forms, each
	 * null when absent.
	 */
	private static RefOptions refOptions(ClojureLowering ctx, List<LispVal> items, int from, String op) {
		LispVal validator = null;
		LispVal min = null;
		LispVal max = null;
		for (int i = from; i < items.size(); i += 2) {
			if (!(items.get(i) instanceof LispSymbol opt) || i + 1 >= items.size()) {
				throw new LispReadException(op + " takes option/value pairs");
			}
			switch (opt.name()) {
				case ":validator" -> {
					ClojureLowerUtil.isTrue(validator == null, op + " takes one :validator");
					validator = ClojureBindingLowering.fnValue(ctx, items.get(i + 1));
				}
				case ":min-history" -> {
					ClojureLowerUtil.isTrue(min == null, op + " takes one :min-history");
					min = ctx.lower(items.get(i + 1));
				}
				case ":max-history" -> {
					ClojureLowerUtil.isTrue(max == null, op + " takes one :max-history");
					max = ctx.lower(items.get(i + 1));
				}
				case ":meta" -> {
				} // dropped, like every other metadata
				default -> throw new LispReadException(op + " option " + opt.name() + " is not supported yet");
			}
		}
		return new RefOptions(validator, min, max);
	}

	/**
	 * {@code (ref init & opts)}: an atom cell, like {@code atom}; with a
	 * {@code :validator}, the spliced {@link #REF_MAKE}'s cell carrying it, which the
	 * initial value must pass and every transaction writing the ref runs on its final
	 * value. With {@code :min-history}/{@code :max-history}, or where the program names a
	 * history verb, the cell registers for its history ({@link #trackRef}); anywhere else
	 * it stays the atom's cell.
	 */
	static LispVal refOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "ref takes an initial value");
		LispVal init = ctx.lower(items.get(1));
		RefOptions opts = refOptions(ctx, items, 2, "ref");
		LispVal cell = opts.validator() == null ? wrapAtom(init)
				: ClojureLowerUtil.list(REF_MAKE, init, opts.validator());
		return trackRef(ctx, cell, opts.min(), opts.max());
	}

	/** {@code ref} as a value: a one-argument lambda over the constructor. */
	static LispVal refValue(ClojureLowering ctx) {
		LispSymbol init = new LispSymbol(ClojureLowering.mangle("ref-init"));
		LispVal cell = wrapAtom(init);
		if (!tracksHistory(ctx, null, null)) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(init), cell);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(init),
				trackedCell(ctx, cell, new LispInteger(0), new LispInteger(10)));
	}

	/**
	 * Whether a ref made here registers for its history: the program names a history
	 * verb, this is a session (whose later buffers may), or the call spells a history
	 * bound itself.
	 */
	private static boolean tracksHistory(ClojureLowering ctx, @Nullable LispVal min, @Nullable LispVal max) {
		return ctx.usedRefHistory || ctx.session || min != null || max != null;
	}

	/**
	 * The ref cell with its history registration: {@code (track cell min max)} over the
	 * bounds (0 and 10 where the call spells none), each bound once behind a temporary. A
	 * view of the ref-history family, so a program the pre-scan found no history verb in
	 * keeps the bare cell.
	 * @param ctx the hub
	 * @param cell the already-lowered cell
	 * @param min the already-lowered min bound, or null
	 * @param max the already-lowered max bound, or null
	 * @return the bare or the registering cell
	 */
	static LispVal trackRef(ClojureLowering ctx, LispVal cell, @Nullable LispVal min, @Nullable LispVal max) {
		if (!tracksHistory(ctx, min, max)) {
			return cell;
		}
		return trackedCell(ctx, cell, min == null ? new LispInteger(0) : min, max == null ? new LispInteger(10) : max);
	}

	/** The registering cell over already-lowered bounds: both bound once. */
	private static LispVal trackedCell(ClojureLowering ctx, LispVal cell, LispVal min, LispVal max) {
		LispSymbol lo = ctx.freshTemp();
		LispSymbol hi = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(lo, min), ClojureLowerUtil.list(hi, max))),
				ClojureLowerUtil.list(REF_HISTORY_TRACK, cell, lo, hi));
	}

	/**
	 * {@code (dosync body...)}: the body's thunk, which the spliced {@link #STM_RUN} runs
	 * as one transaction (a nested {@code dosync} joins the running one). The body runs
	 * without holding any lock; the commit point validates the read set a conflicting
	 * commit retries, and the thunk is the body's own zero-arity {@code recur} target,
	 * like the oracle's {@code (fn [] body)}.
	 */
	static LispVal dosyncOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(ctx.freshRecurName(), true);
		target.setArity(0, false);
		ctx.pushRecurTarget(target);
		try {
			LispVal lambda = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of()),
					ctx.bodyTail(items, 1));
			LispVal thunk = target.used() ? ClojureLowering.labelsSelfCall(target.callName(), lambda) : lambda;
			return ClojureLowerUtil.list(STM_RUN, thunk);
		}
		finally {
			ctx.recurTargets.pop();
		}
	}

	/**
	 * {@code (sync flags body...)}: the flags dropped unevaluated (the oracle's
	 * {@code flags-ignored-for-now}, never spliced into its expansion) and the body run
	 * as one transaction under the same commit lock {@code dosync} takes -- a nested
	 * {@code sync} joins the running transaction, and a conflicting commit retries, so
	 * the body's side effects may run more than once. At least the flags are required,
	 * like the oracle's arity.
	 */
	static LispVal syncOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("sync", items.size() - 1, 1, -1);
		List<LispVal> dosyncItems = new ArrayList<>(items);
		dosyncItems.remove(1);
		return dosyncOf(ctx, dosyncItems);
	}

	/**
	 * {@code (io! body...)}: the body, refused inside a transaction with the oracle's
	 * {@code IllegalStateException} ({@code I/O in transaction}, read off
	 * {@code %clojure-stm-log} like {@code await}'s guard) and run as-is outside one,
	 * answering its last value.
	 */
	static LispVal ioOf(ClojureLowering ctx, List<LispVal> items) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), STM_LOG,
				ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_STATE, LispString.literal("I/O in transaction")),
				ctx.body(items, 1));
	}

	/**
	 * {@code (with-loading-context body...)}: the body as-is -- loading has no separate
	 * context to thread through here (the oracle rebinds its compiler loader around it,
	 * unobservable past the load).
	 */
	static LispVal withLoadingContextOf(ClojureLowering ctx, List<LispVal> items) {
		return ctx.body(items, 1);
	}

	/**
	 * {@code (StackTraceElement->vec e)}: the oracle's {@code [class method file line]}
	 * vector of the host stack-trace element E -- the class and the method as symbols,
	 * the file as written (nil when the element names none), the line as written. The
	 * argument evaluates once, into a temporary the nil test, the instance test and the
	 * four host-method reads share. Nil is the oracle's NullPointerException, anything
	 * else its ClassCastException; the arity is checked at lower time in the oracle's
	 * wording. Elements exist only where the host does ({@code .getStackTrace} answers []
	 * here), so on wasm only the two refusals can run.
	 */
	static LispVal stackTraceElementVecOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("StackTraceElement->vec", items.size() - 1, 1, 1);
		LispSymbol target = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(target, ctx.lower(items.get(1))))),
				stackTraceElementVecBody(ctx, target));
	}

	/**
	 * {@code StackTraceElement->vec} as a value: a one-argument lambda over the same
	 * read.
	 */
	static LispVal stackTraceElementVecValue(ClojureLowering ctx) {
		LispSymbol element = new LispSymbol(ClojureLowering.mangle("stack-trace-element"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(element),
				stackTraceElementVecBody(ctx, element));
	}

	/**
	 * The read {@link #stackTraceElementVecOf} and {@link #stackTraceElementVecValue}
	 * share over TARGET, which holds the already-evaluated argument: the nil refusal, the
	 * instance test with the oracle's cast refusal, else the four readings as a vector.
	 */
	private static LispVal stackTraceElementVecBody(ClojureLowering ctx, LispSymbol target) {
		LispVal className = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-HOST-METHOD"), target,
				LispString.literal("getClassName"));
		LispVal methodName = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-HOST-METHOD"), target,
				LispString.literal("getMethodName"));
		LispVal fileName = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-HOST-METHOD"), target,
				LispString.literal("getFileName"));
		LispVal lineNumber = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-HOST-METHOD"), target,
				LispString.literal("getLineNumber"));
		LispVal built = ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-1"), className),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-1"), methodName), fileName,
				lineNumber);
		LispVal cast = ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("string")),
						LispString.literal("class "),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CLASS-NAME-OF"), target),
						LispString.literal(" cannot be cast to class java.lang.StackTraceElement")));
		LispVal npe = ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER, LispString
			.literal("Cannot invoke \"java.lang.StackTraceElement.getClassName()\" because \"o\" is null"));
		LispVal typed = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureDispatchLowering.instanceTest(ctx, "StackTraceElement", target), built, cast);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), target), npe, typed);
	}

	/**
	 * {@code (alter ref fun args...)}: the function applied to the old value and the
	 * arguments, written through the transaction's log, the read recorded first so a
	 * conflicting commit retries. {@code (commute ref fun args...)}: the function logged
	 * for the commit's re-application onto the latest value (running twice uncontended,
	 * like the oracle's), answering the eager application; a later {@code alter} or
	 * {@code ref-set} of the ref refuses, like the oracle's. Both require a transaction.
	 */
	static LispVal alterOf(ClojureLowering ctx, List<LispVal> items, String op) {
		ClojureLowerUtil.isTrue(items.size() >= 3, op + " takes a ref, a function and arguments");
		return alterBuild(ctx, ctx.lower(items.get(1)), ClojureBindingLowering.fnValue(ctx, items.get(2)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)), op);
	}

	/**
	 * An {@code alter}/{@code commute} over already-lowered forms: the guard, the
	 * application and the logged write, answering the new value. The read goes through
	 * the tracking read, so a concurrent commit between it and the write retries instead
	 * of losing the update; {@code commute} defers to the commit instead.
	 */
	static LispVal alterBuild(ClojureLowering ctx, LispVal cell, LispVal fun, LispVal argList, String op) {
		if (op.equals("commute")) {
			return withAtom(ctx, cell, op, bound -> txnGuard(ClojureLowerUtil.list(STM_COMMUTE, bound, fun, argList)));
		}
		return withAtom(ctx, cell, op, bound -> {
			LispVal spread = ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
					ClojureLowerUtil.list(STM_DEREF, bound), argList);
			return txnGuard(ClojureLowerUtil.list(STM_WRITE, bound,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), fun, spread)));
		});
	}

	/** {@code alter}/{@code commute} as a value: over a ref, a function and arguments. */
	static LispVal alterValue(ClojureLowering ctx, String op) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("alter-cell"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("alter-fun"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("alter-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cell, fun, ClojureLowering.AMPERSAND_REST, rest)),
				alterBuild(ctx, cell, fun, rest, op));
	}

	/** {@code (ref-set ref value)}: the value written through the transaction's log. */
	static LispVal refSetOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "ref-set takes a ref and a value");
		LispVal value = ctx.lower(items.get(2));
		return withAtom(ctx, ctx.lower(items.get(1)), "ref-set", bound -> txnGuard(refSetBuild(bound, value)));
	}

	/** A {@code ref-set} over already-lowered forms: the logged write. */
	static LispVal refSetBuild(LispVal bound, LispVal value) {
		return ClojureLowerUtil.list(STM_WRITE, bound, value);
	}

	/** {@code ref-set} as a value: a two-argument lambda over the write. */
	static LispVal refSetValue(ClojureLowering ctx) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("ref-set-cell"));
		LispSymbol value = new LispSymbol(ClojureLowering.mangle("ref-set-value"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(cell, value)),
				withAtom(ctx, cell, "ref-set", bound -> txnGuard(refSetBuild(bound, value))));
	}

	/**
	 * {@code (ensure ref)}: the ref's value, requiring a transaction. The read is
	 * recorded and pinned to the transaction, so a writer of the ref waits until it
	 * commits, like the oracle's.
	 */
	static LispVal ensureOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "ensure takes one ref");
		return withAtom(ctx, ctx.lower(items.get(1)), "ensure", bound -> txnGuard(ensureRead(ctx, bound)));
	}

	/**
	 * An {@code ensure} read over an already-bound cell: the recorded and pinned read.
	 */
	static LispVal ensureRead(ClojureLowering ctx, LispVal bound) {
		return ClojureLowerUtil.list(STM_ENSURE, bound);
	}

	/**
	 * {@code (agent init & opts)}: the spliced {@link #AGENT_MAKE}'s cell, carrying the
	 * agent's validator (which the initial value must pass and every action's answer),
	 * its error handler and mode, and its queue. Options go to {@link #AGENT_V}, which
	 * reads them at run time like the oracle's {@code (apply hash-map options)}.
	 */
	static LispVal agentOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "agent takes an initial value");
		if (items.size() == 2) {
			return ClojureLowerUtil.list(AGENT_MAKE, ctx.lower(items.get(1)), ClojureLowering.NIL_CONST,
					ClojureLowering.NIL_CONST, ClojureLowering.NIL_CONST);
		}
		return ClojureLowerUtil.cons(AGENT_V, ctx.lowers(items, 1));
	}

	/** {@code agent} as a value: the library constructor reading options. */
	static LispVal agentValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), AGENT_V);
	}

	/** {@code agent} with options, called and as a value. */
	static final LispSymbol AGENT_V = new LispSymbol("RONTOLISP::%CLOJURE-AGENT-V");

	/**
	 * One agent verb reading or restarting a failed agent: one call to its
	 * {@code clojure.lisp} worker over a fixed argument count.
	 *
	 * @param worker the library function
	 * @param arity its fixed argument count
	 * @param options whether options follow them, handed to the worker as one list
	 */
	private record AgentVerb(String worker, int arity, boolean options) {

		AgentVerb(String worker, int arity) {
			this(worker, arity, false);
		}

	}

	private static final Map<String, AgentVerb> AGENT_VERBS = Map.of("agent-error",
			new AgentVerb("RONTOLISP::%CLOJURE-AGENT-ERROR", 1), "agent-errors",
			new AgentVerb("RONTOLISP::%CLOJURE-AGENT-ERRORS", 1), "clear-agent-errors",
			new AgentVerb("RONTOLISP::%CLOJURE-AGENT-CLEAR-ERRORS", 1), "restart-agent",
			new AgentVerb("RONTOLISP::%CLOJURE-AGENT-RESTART", 2, true), "set-error-handler!",
			new AgentVerb("RONTOLISP::%CLOJURE-AGENT-SET-ERROR-HANDLER", 2), "set-error-mode!",
			new AgentVerb("RONTOLISP::%CLOJURE-AGENT-SET-ERROR-MODE", 2), "error-handler",
			new AgentVerb("RONTOLISP::%CLOJURE-AGENT-ERROR-HANDLER", 1), "error-mode",
			new AgentVerb("RONTOLISP::%CLOJURE-AGENT-ERROR-MODE", 1));

	/**
	 * The agent rows of the lowering ({@code agent}, {@code send}, {@code send-off},
	 * {@code await}, {@code shutdown-agents} and the error verbs), or null when the call
	 * names none of them.
	 */
	static @Nullable LispVal agentRow(ClojureLowering ctx, String name, List<LispVal> items) {
		switch (name) {
			case "agent":
				return agentOf(ctx, items);
			case "send", "send-off":
				return sendOf(ctx, items);
			case "await":
				return awaitOf(ctx, items);
			case "await-for":
				return awaitForOf(ctx, items);
			case "shutdown-agents":
				ClojureLowerUtil.isTrue(items.size() == 1, "shutdown-agents takes no arguments");
				return ClojureLowering.NIL_CONST;
			default:
				AgentVerb verb = AGENT_VERBS.get(name);
				if (verb == null) {
					return null;
				}
				ClojureCoreLowering.arity(name, items.size() - 1, verb.arity(), verb.options() ? -1 : verb.arity());
				List<LispVal> call = new ArrayList<>();
				call.add(new LispSymbol(verb.worker()));
				call.addAll(ctx.lowers(items.subList(0, verb.arity() + 1), 1));
				if (verb.options()) {
					call.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, verb.arity() + 1)));
				}
				return ClojureLowerUtil.list(call);
		}
	}

	/** An agent error verb as a value: a lambda over its worker, or null for no verb. */
	static @Nullable LispVal agentVerbValue(String name) {
		AgentVerb verb = AGENT_VERBS.get(name);
		if (verb == null) {
			return null;
		}
		List<LispVal> params = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(verb.worker()));
		for (int i = 0; i < verb.arity(); i++) {
			LispSymbol param = new LispSymbol(ClojureLowering.mangle("agent-arg" + i));
			params.add(param);
			call.add(param);
		}
		if (verb.options()) {
			LispSymbol options = new LispSymbol(ClojureLowering.mangle("agent-options"));
			params.add(ClojureLowering.AMPERSAND_REST);
			params.add(options);
			call.add(options);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(params),
				ClojureLowerUtil.list(call));
	}

	/**
	 * {@code (send agent fun args...)} and {@code (send-off ...)}: the action queued on
	 * the agent, answering the agent. Where threads exist the queue runs on the shared
	 * pool ({@code clojure.lisp} "The shared agent pool"), so a send never waits for an
	 * action; on wasm, single-threaded by construction, an idle agent runs its queue on
	 * the sender's thread at once, like a running one on the thread already running it.
	 */
	static LispVal sendOf(ClojureLowering ctx, List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		ClojureLowerUtil.isTrue(items.size() >= 3, op + " takes an agent, a function and arguments");
		return sendBuild(ctx, ctx.lower(items.get(1)), ClojureBindingLowering.fnValue(ctx, items.get(2)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)), op);
	}

	/**
	 * A {@code send} over already-lowered forms: the function and the arguments run now,
	 * and the spliced {@link #AGENT_SEND} queues the action applying them to the agent's
	 * value with {@code *agent*} bound to the agent (its binding-depth counter one
	 * deeper).
	 */
	static LispVal sendBuild(ClojureLowering ctx, LispVal cell, LispVal fun, LispVal argList, String op) {
		ctx.usedStm = true;
		ctx.usedExInfo = true; // a failed agent's refusal carries its error as the cause
		return withAtom(ctx, cell, op, bound -> {
			LispSymbol function = ctx.freshTemp();
			LispSymbol args = ctx.freshTemp();
			LispSymbol old = ctx.freshTemp();
			LispVal action = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(old),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
							ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(AGENT_VAR, bound),
									ClojureVarLowering.specialDepthPair(ctx, "*agent*"))),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), function,
									ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), old, args))));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(function, fun), ClojureLowerUtil.list(args, argList))),
					ClojureLowerUtil.list(AGENT_SEND, bound, action));
		});
	}

	/**
	 * {@code send}/{@code send-off} as a value: over an agent, a function and arguments.
	 */
	static LispVal sendValue(ClojureLowering ctx, String op) {
		LispSymbol cell = new LispSymbol(ClojureLowering.mangle("send-cell"));
		LispSymbol fun = new LispSymbol(ClojureLowering.mangle("send-fun"));
		LispSymbol rest = new LispSymbol(ClojureLowering.mangle("send-rest"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cell, fun, ClojureLowering.AMPERSAND_REST, rest)),
				sendBuild(ctx, cell, fun, rest, op));
	}

	/**
	 * {@code (await agent...)}: the agents run, then the oracle's refusal inside an
	 * action, then the actions queued on each so far run, answering nil. A host target
	 * waits for a queue another thread is running ({@link #AGENT_AWAIT}); on wasm this
	 * thread runs every queue ({@link #AGENT_AWAIT_HERE}, no wait).
	 */
	static LispVal awaitOf(ClojureLowering ctx, List<LispVal> items) {
		ctx.usedStm = true;
		ctx.usedExInfo = true; // a failed agent's refusal carries its error as the cause
		LispSymbol agents = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(agents,
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1))))),
				awaitGuard("await"), ClojureLowerUtil.list(ctx.hostTarget ? AGENT_AWAIT : AGENT_AWAIT_HERE, agents),
				ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code (await-for timeout agent...)}: {@code await}'s refusals and markers, then a
	 * wait of at most {@code timeout} milliseconds for them, answering whether every one
	 * ran. A host target waits for a queue another thread is running
	 * ({@link #AGENT_AWAIT_FOR}); on wasm this thread runs every queue, so only a marker
	 * queued behind a failed action is left ({@link #AGENT_AWAIT_FOR_HERE}).
	 */
	static LispVal awaitForOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("await-for", items.size() - 1, 1, -1);
		ctx.usedStm = true;
		ctx.usedExInfo = true; // a failed agent's refusal carries its error as the cause
		LispSymbol timeout = ctx.freshTemp();
		LispSymbol agents = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(timeout, ctx.lower(items.get(1))),
						ClojureLowerUtil.list(agents,
								ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2))))),
				awaitGuard("await-for"), ctx.booleanAnswer(ClojureLowerUtil
					.list(ctx.hostTarget ? AGENT_AWAIT_FOR : AGENT_AWAIT_FOR_HERE, timeout, agents)));
	}

	/** {@code await-for} on a host target, waiting for another thread's run. */
	static final LispSymbol AGENT_AWAIT_FOR = new LispSymbol("RONTOLISP::%CLOJURE-AGENT-AWAIT-FOR");

	/** {@code await-for} on wasm, where this thread runs every queue. */
	static final LispSymbol AGENT_AWAIT_FOR_HERE = new LispSymbol("RONTOLISP::%CLOJURE-AGENT-AWAIT-FOR-HERE");

	/**
	 * {@code await} as a value: over any number of agents, like the call -- including
	 * none, which runs nothing and answers nil, like the call.
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal awaitValue(ClojureLowering ctx) {
		ctx.usedStm = true;
		ctx.usedExInfo = true; // a failed agent's refusal carries its error as the cause
		LispSymbol agents = new LispSymbol(ClojureLowering.mangle("await-agents"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, agents), awaitGuard("await"),
				ClojureLowerUtil.list(ctx.hostTarget ? AGENT_AWAIT : AGENT_AWAIT_HERE, agents),
				ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code await-for} as a value: over a timeout and any agents after it, like the
	 * call. Of none at all, the call's arity refusal -- the same words, through the
	 * run-time arity worker.
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal awaitForValue(ClojureLowering ctx) {
		ctx.usedStm = true;
		ctx.usedExInfo = true; // a failed agent's refusal carries its error as the cause
		LispSymbol all = new LispSymbol(ClojureLowering.mangle("await-for-args"));
		LispSymbol timeout = new LispSymbol(ClojureLowering.mangle("await-for-timeout"));
		LispSymbol agents = new LispSymbol(ClojureLowering.mangle("await-for-agents"));
		LispVal ran = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(
							ClojureLowerUtil.list(timeout, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), all)),
							ClojureLowerUtil.list(agents, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), all)))),
					awaitGuard("await-for"), ctx.booleanAnswer(ClojureLowerUtil
						.list(ctx.hostTarget ? AGENT_AWAIT_FOR : AGENT_AWAIT_FOR_HERE, timeout, agents)));
		LispVal arity = ClojureLowerUtil.list(new LispSymbol(ClojureBindingLowering.FN_ARITY_ERROR), new LispInteger(0),
				LispString.literal("clojure.core/await-for"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, all),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), all), arity, ran));
	}

	/**
	 * {@code ensure} as a value: over one ref, like the call.
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal ensureValue(ClojureLowering ctx) {
		LispSymbol ref = new LispSymbol(ClojureLowering.mangle("ensure-ref"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(ref),
				withAtom(ctx, ref, "ensure", bound -> txnGuard(ensureRead(ctx, bound))));
	}

	/**
	 * {@code shutdown-agents} as a value: no arguments, answering nil like the call.
	 * There is no thread pool on any backend, so there is nothing to shut down.
	 * @return the value form
	 */
	static LispVal shutdownAgentsValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(),
				ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code push-thread-bindings}/{@code pop-thread-bindings} as a value: the pairing
	 * the body takes never exists around a value, so any application refuses with the
	 * pairing's words.
	 * @param name {@code push-thread-bindings} or {@code pop-thread-bindings}
	 * @return the value form
	 */
	static LispVal threadBindingsValue(String name) {
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("thread-bindings-args"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args),
				ClojureRefusals.refusal(ClojureRefusals.UNSUPPORTED_OPERATION,
						LispString.literal(name + " is supported only as push-thread-bindings followed by a try"
								+ " whose finally calls pop-thread-bindings (what binding expands to)")));
	}

	/**
	 * {@code struct} as a value: over a struct and any values, like the call. Of none at
	 * all, the call's arity refusal.
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal structValue(ClojureLowering ctx) {
		LispSymbol all = new LispSymbol(ClojureLowering.mangle("struct-args"));
		LispVal build = structBuild(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), all),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), all), "struct");
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("struct takes a struct and values"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, all),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), all), arity, build));
	}

	/**
	 * {@code struct-map} as a value: over a struct and key/value pairs, like the call. Of
	 * none at all, the call's arity refusal; of an odd pair count, the call's key/value
	 * refusal.
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal structMapValue(ClojureLowering ctx) {
		LispSymbol all = new LispSymbol(ClojureLowering.mangle("struct-map-args"));
		LispSymbol slots = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol index = ctx.freshTemp();
		LispSymbol pairs = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispVal fill = ClojureLowerUtil.list(ClojureLowerUtil.sym("dotimes"),
				ClojureLowerUtil.list(List.of(index, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), slots))),
				ClojureCollectionLowering.tablePut(table,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"), slots, index), ClojureLowering.NIL_CONST));
		LispVal overlay = ClojureLowerUtil.list(ClojureLowerUtil.sym("do"),
				ClojureLowerUtil.list(List
					.of(ClojureLowerUtil.list(rest, pairs, ClojureLowerUtil.list(ClojureLowerUtil.sym("cddr"), rest)))),
				ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), rest)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
						ClojureCollectionLowering.tablePut(table,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), rest),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), rest))));
		LispVal build = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(slots, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), all)),
							ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
				fill, overlay, table);
		LispVal odd = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("struct-map takes key/value pairs"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("struct-map takes a struct and key/value pairs"));
		LispVal even = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(
						List.of(ClojureLowerUtil.list(pairs, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), all)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("oddp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), pairs)), odd, build));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, all),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), all), arity, even));
	}

	/**
	 * The oracle's refusals of an {@code await} or {@code await-for} named {@code op}:
	 * inside a transaction ({@code io!}'s {@code IllegalStateException}), then inside an
	 * action.
	 */
	private static LispVal awaitGuard(String op) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
				ClojureLowerUtil.list(STM_LOG,
						ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_STATE,
								LispString.literal(op + " in transaction"))),
				ClojureLowerUtil.list(AGENT_VAR, ClojureRefusals.refusal(ClojureRefusals.EXCEPTION,
						LispString.literal("Can't await in agent action"))));
	}

	/** The running transaction's log, nil outside one ({@code clojure.lisp}). */
	private static final LispSymbol STM_LOG = new LispSymbol("RONTOLISP::%CLOJURE-STM-LOG");

	/**
	 * One body lowered behind the {@code try} barrier with the tail position kept: a
	 * {@code recur} in it trips the oracle's {@code Cannot recur across try} refusal,
	 * while a target opened inside it still recurs. The {@code binding} and
	 * {@code with-open} bodies lower through here (the oracle wraps both in a
	 * {@code try}), the inits through plain {@code body}.
	 */
	static LispVal barrierBody(ClojureLowering ctx, List<LispVal> items, int from) {
		ctx.tryDepth++;
		try {
			return ctx.body(items, from);
		}
		finally {
			ctx.tryDepth--;
		}
	}

	/**
	 * {@code (binding [var init ...] body...)}: each var rebound around the body, like
	 * the oracle -- which is why only {@code ^:dynamic} vars and the {@code clojure.core}
	 * specials ({@link ClojureCoreSpecials}, each already a special variable) may be
	 * bound. Inits run sequentially, like {@code let}; the body reads each bound var
	 * through the var itself. Every bound var's binding-depth counter rebinds one deeper
	 * beside it, so {@code set!} tests at run time whether the var is thread-bound (a
	 * core special's counter is the one {@code thread-bound?} reads through its core var;
	 * a flag {@code clojure.main} binds has none). The body lowers behind the {@code try}
	 * barrier (the oracle wraps it in a {@code try/finally}), while the inits stay
	 * outside it.
	 */
	static LispVal bindingOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "binding needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "binding");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0, "a binding vector pairs a var with a value");
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.size(); i += 2) {
			String name = ClojureLowerUtil.plainName(bindings.get(i), "binding");
			// a project var (own, referred, or qualified) rebinds its own special,
			// a core special its special variable -- a syntax-quote qualifies one
			// (`*out* reads clojure.core/*out*), and the oracle binds the qualified
			// spelling like the bare one; the body reads either through the var, so
			// neither is a local
			String key = name.startsWith(ClojureCoreNames.PREFIX) ? null : ctx.resolveVar(name);
			String special = key == null ? ClojureCoreSpecials.targetName(name) : null;
			LispVal init = ctx.lower(bindings.get(i + 1));
			if (special != null) {
				pairs.add(ClojureLowerUtil.list(ctx.specialValue(ClojureCoreSpecials.required(special)), init));
				LispVal depth = ClojureVarLowering.specialDepthPair(ctx, special);
				if (depth != null) {
					pairs.add(depth);
				}
			}
			else if (key == null || !ctx.dynamicVars.contains(key)) {
				throw new LispReadException("binding " + name + " needs a ^:dynamic var: only dynamic vars rebind");
			}
			else {
				pairs.add(ClojureLowerUtil.list(ClojureLowering.varSym(key), init));
				LispSymbol depth = ClojureLowering.boundDepthSym(key);
				pairs.add(ClojureLowerUtil.list(depth,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), depth, new LispInteger(1))));
			}
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
				barrierBody(ctx, items, 2));
	}

	/** The run-time binding of a map of vars around a call ({@code clojure.lisp}). */
	private static final LispSymbol WITH_BINDINGS = new LispSymbol("RONTOLISP::%CLOJURE-WITH-BINDINGS");

	/**
	 * {@code with-bindings*} as the run-time library holds it: a map, a fn, arguments.
	 */
	private static final LispSymbol WITH_BINDINGS_V = new LispSymbol("RONTOLISP::%CLOJURE-WITH-BINDINGS-V");

	/**
	 * Whether a name spells a verb binding vars through var objects
	 * ({@code with-bindings}, {@code with-bindings*}, {@code push-thread-bindings}), bare
	 * or {@code clojure.core/}-qualified: what turns every dynamic var site's binder on
	 * ({@link ClojureLowering#threadBinders}).
	 */
	static boolean isThreadBindingName(String name) {
		String plain = name.startsWith(ClojureCoreNames.PREFIX) ? name.substring(ClojureCoreNames.PREFIX.length())
				: name;
		return plain.equals("with-bindings") || plain.equals("with-bindings*") || plain.equals("push-thread-bindings");
	}

	/**
	 * {@code (with-bindings binding-map body...)}: the body as a {@code fn} of no
	 * arguments, called with each var of the map bound to its value
	 * ({@code %clojure-with-bindings}, through the binder every dynamic var site carries
	 * in such a program), like the oracle's {@code with-bindings*} over
	 * {@code (fn [] body)}.
	 */
	static LispVal withBindingsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/with-bindings");
		ctx.needThreadBinders();
		List<LispVal> fn = new ArrayList<>();
		fn.add(new LispSymbol("fn*"));
		fn.add(ClojureLowerUtil.list(ClojureReader.VECTOR));
		fn.addAll(items.subList(2, items.size()));
		return ClojureLowerUtil.list(WITH_BINDINGS, ctx.lower(items.get(1)), ctx.lower(ClojureLowerUtil.list(fn)));
	}

	/**
	 * {@code (with-bindings* binding-map f args...)}: {@code f} applied to the arguments
	 * with each var of the map bound, every argument evaluated first, like the oracle's
	 * function.
	 */
	static LispVal withBindingsStarOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/with-bindings*");
		ctx.needThreadBinders();
		return ClojureLowerUtil.cons(WITH_BINDINGS_V, ctx.lowers(items, 1));
	}

	/** {@code with-bindings*} as a value. */
	static LispVal withBindingsStarValue(ClojureLowering ctx) {
		ctx.needThreadBinders();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), WITH_BINDINGS_V);
	}

	/**
	 * A {@code push-thread-bindings} or {@code pop-thread-bindings} the body pairing
	 * ({@link #pairThreadBindings}) did not take: refused by name, since a thread binding
	 * here is a dynamic extent, never a frame left pushed.
	 */
	static LispVal unpairedThreadBindings(String name) {
		throw new LispReadException(name + " is supported only as push-thread-bindings followed by a try"
				+ " whose finally calls pop-thread-bindings (what binding expands to)");
	}

	/**
	 * A body's forms with each {@code (push-thread-bindings m)} that the next form, a
	 * {@code try} whose {@code finally} calls {@code (pop-thread-bindings)}, closes --
	 * the shape the oracle's {@code binding} expands to, hiccup's {@code binding*} and
	 * honeysql's {@code with-inline} -- taken together as one
	 * {@code (clojure.core/with-bindings m (try body catches...))}: the catches still run
	 * under the bindings, the {@code finally} forms ahead of the pop inside them, the
	 * ones after it outside, in an outer {@code try}. A body without a push is answered
	 * itself.
	 */
	static List<LispVal> pairThreadBindings(ClojureLowering ctx, List<LispVal> forms) {
		boolean any = false;
		for (LispVal form : forms) {
			any |= isThreadBindingCall(ctx, form, "push-thread-bindings", 1);
		}
		if (!any) {
			return forms;
		}
		List<LispVal> out = new ArrayList<>();
		for (int i = 0; i < forms.size(); i++) {
			LispVal form = forms.get(i);
			List<LispVal> push = ClojureLowerUtil.items(form);
			if (push != null && i + 1 < forms.size() && isThreadBindingCall(ctx, form, "push-thread-bindings", 1)) {
				LispVal paired = pairedTry(ctx, push.get(1), forms.get(i + 1));
				if (paired != null) {
					out.add(paired);
					i++;
					continue;
				}
			}
			out.add(form);
		}
		return out;
	}

	/**
	 * Whether a datum is a call of the core verb {@code name} with {@code args}
	 * arguments, spelled bare (no local or program var claiming the name) or
	 * {@code clojure.core/}-qualified.
	 */
	private static boolean isThreadBindingCall(ClojureLowering ctx, LispVal form, String name, int args) {
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null || items.size() != args + 1 || !(items.get(0) instanceof LispSymbol head)) {
			return false;
		}
		String spelled = head.name();
		if (spelled.equals(name)) {
			return !ctx.known(name) && ClojureNamespaceLowering.coreAllowed(ctx, name);
		}
		try {
			return name.equals(ctx.coreSpelling(spelled));
		}
		catch (LispReadException ex) {
			return false;
		}
	}

	/**
	 * The pair of a push of {@code map} and the {@code try} after it, as one
	 * {@code with-bindings} form, or null when the {@code try} pops nowhere.
	 */
	private static @Nullable LispVal pairedTry(ClojureLowering ctx, LispVal map, LispVal next) {
		List<LispVal> tryItems = ClojureLowerUtil.items(next);
		if (tryItems == null || tryItems.size() < 2 || !ClojureLowerUtil.isSymbolNamed(tryItems.get(0), "try")) {
			return null;
		}
		List<LispVal> finallyItems = ClojureLowerUtil.items(tryItems.get(tryItems.size() - 1));
		if (finallyItems == null || finallyItems.isEmpty()
				|| !ClojureLowerUtil.isSymbolNamed(finallyItems.get(0), "finally")) {
			return null;
		}
		int pop = -1;
		for (int i = 1; i < finallyItems.size(); i++) {
			if (isThreadBindingCall(ctx, finallyItems.get(i), "pop-thread-bindings", 0)) {
				pop = i;
				break;
			}
		}
		if (pop < 0) {
			return null;
		}
		List<LispVal> inner = new ArrayList<>(tryItems.subList(0, tryItems.size() - 1));
		if (pop > 1) {
			List<LispVal> before = new ArrayList<>();
			before.add(finallyItems.get(0));
			before.addAll(finallyItems.subList(1, pop));
			inner.add(ClojureLowerUtil.list(before));
		}
		LispVal bound = ClojureLowerUtil.list(new LispSymbol(ClojureCoreNames.PREFIX + "with-bindings"), map,
				ClojureLowerUtil.list(inner));
		if (pop + 1 == finallyItems.size()) {
			return bound;
		}
		List<LispVal> after = new ArrayList<>();
		after.add(finallyItems.get(0));
		after.addAll(finallyItems.subList(pop + 1, finallyItems.size()));
		return ClojureLowerUtil.list(tryItems.get(0), bound, ClojureLowerUtil.list(after));
	}

	/**
	 * {@code (with-open [name init ...] body...)}: the body with each value bound, closed
	 * in reverse order on every exit through {@code unwind-protect}. Closing calls the
	 * {@code close} method, so a Java closeable works where host objects exist (the
	 * interpreter and the JVM -- wasm rejects {@code java:}). A non-empty body lowers
	 * behind the {@code try} barrier (the oracle closes in a {@code finally}); an empty
	 * vector is the plain body, like the oracle's bare {@code do}.
	 */
	static LispVal withOpenOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-open needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "with-open");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0, "a with-open binding vector pairs a name with a value");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		ctx.scopes.add(scope);
		ctx.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (int i = 0; i < bindings.size(); i += 2) {
				String name = ClojureLowerUtil.plainName(bindings.get(i), "with-open");
				LispVal init = ctx.lower(bindings.get(i + 1));
				pairs.add(ClojureLowerUtil.list(ctx.localSym(name), init));
				names.add(name);
				scope.put(name, ClojureLowering.Kind.VARIABLE);
				if (ClojureLowerUtil.yieldsFun(init)) {
					ctx.markDirect(name);
				}
			}
			LispVal run = names.isEmpty() ? ctx.body(items, 2) : barrierBody(ctx, items, 2);
			if (names.isEmpty()) {
				return run;
			}
			List<LispVal> guarded = new ArrayList<>();
			guarded.add(ClojureLowerUtil.sym("unwind-protect"));
			guarded.add(run);
			for (int i = names.size() - 1; i >= 0; i--) {
				guarded.add(ClojureInteropLowering.instanceCall(ctx, ctx.localSym(names.get(i)), "close", List.of()));
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
					ClojureLowerUtil.list(guarded));
		}
		finally {
			ctx.scopes.remove(ctx.scopes.size() - 1);
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
		}
	}

	/**
	 * {@code (with-out-str body...)}: the body with {@code *standard-output*} bound to a
	 * fresh string stream, answering what it printed. The stream is built with
	 * {@code make-string-output-stream} (never a literal {@code with-output-to-string},
	 * which would flip a WASM module into EH mode), like {@code str}. It binds
	 * {@code *out*}, so its binding-depth counter goes one deeper too.
	 */
	static LispVal withOutStrOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-out-str needs a body");
		LispSymbol stream = ctx.freshTemp();
		List<LispVal> run = new ArrayList<>(ctx.lowers(items, 1));
		run.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("get-output-stream-string"), stream));
		LispVal captured = run.size() == 1 ? run.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), run);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(stream,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("make-string-output-stream"))),
						ClojureLowerUtil.list(new LispSymbol("*STANDARD-OUTPUT*"), stream),
						ClojureVarLowering.specialDepthPair(ctx, "*out*"))),
				captured);
	}

	/**
	 * {@code (with-in-str s body...)}: the body with {@code *standard-input*} bound to a
	 * string input stream over {@code s}, so {@code read-line}, {@code read} and a
	 * {@code .read} of {@code *in*} read it. It binds {@code *in*}, so its binding-depth
	 * counter goes one deeper too. The body lowers behind the {@code try} barrier (the
	 * oracle closes the reader in a {@code with-open}).
	 */
	static LispVal withInStrOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-in-str needs a string and a body");
		List<LispVal> pairs = new ArrayList<>();
		pairs.add(ClojureLowerUtil.list(ctx.specialValue(ClojureCoreSpecials.required("*in*")),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("make-string-input-stream"), ctx.lower(items.get(1)))));
		pairs.add(ClojureVarLowering.specialDepthPair(ctx, "*in*"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
				barrierBody(ctx, items, 2));
	}

	/**
	 * {@code (time expr)}: the expression timed with {@code get-internal-real-time}
	 * (milliseconds here), reporting {@code Elapsed time: N.0 msecs} -- a double, like
	 * the oracle's {@code nanoTime} quotient, so a {@code \d+\.\d+} match over the report
	 * holds -- and answering the value. Only the value is deterministic -- the report's
	 * number never is, so the spec pins the shape, never the line.
	 */
	static LispVal timeOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "time takes one form");
		LispSymbol start = ctx.freshTemp();
		LispSymbol value = ctx.freshTemp();
		LispVal elapsed = ClojureLowerUtil.list(ClojureLowerUtil.sym("float"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("-"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("get-internal-real-time")), start),
				new LispDouble(1.0));
		// the report straight to the stream, like println of one string part
		List<LispVal> parts = new ArrayList<>();
		parts.add(ClojureStringLowering.strOf(ctx, LispString.literal("Elapsed time: "), LispString.literal(""),
				ClojureLowering.NIL_CONST));
		parts.add(ClojureStringLowering.strOf(ctx, elapsed, LispString.literal(""), ClojureLowering.NIL_CONST));
		parts.add(ClojureStringLowering.strOf(ctx, LispString.literal(" msecs"), LispString.literal(""),
				ClojureLowering.NIL_CONST));
		LispVal report = ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"),
				List.of(ClojureStringLowering.writeDatum(ctx, ClojureStringLowering.concat(ctx, parts),
						LispString.literal("nil"), ClojureLowering.NIL_CONST),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("terpri")), ClojureLowering.NIL_CONST));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(start,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("get-internal-real-time"))),
						ClojureLowerUtil.list(value, ctx.lower(items.get(1))))),
				report, value);
	}

	/**
	 * {@code (with-precision precision body...)}: the body with {@code *math-context*}
	 * bound to the evaluated precision, answering its last value (nil for an empty body).
	 * Division stays exact here -- a ratio, never the oracle's {@code BigDecimal} -- so
	 * the binding scopes the dynamic extent (nesting rebinds, leaving restores the root)
	 * without rounding anything. The body lowers behind the {@code try} barrier like a
	 * {@code binding} body, which the oracle wraps in a {@code try} too.
	 */
	static LispVal withPrecisionOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-precision takes a precision and a body");
		LispVal precision = ctx.lower(items.get(1));
		List<LispVal> pairs = new ArrayList<>();
		pairs.add(ClojureLowerUtil.list(ctx.specialValue(ClojureCoreSpecials.required("*math-context*")), precision));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
				barrierBody(ctx, items, 2));
	}

	/**
	 * {@code bound-fn*} as the run-time library calls it: the function and its bindings.
	 */
	private static final LispSymbol BOUND_FN_STAR = new LispSymbol("RONTOLISP::%CLOJURE-BOUND-FN*");

	/** A {@code with-local-vars} cell as the run-time library builds it. */
	private static final LispSymbol LOCAL_VAR = new LispSymbol("RONTOLISP::%CLOJURE-LOCAL-VAR");

	/** The current value of a {@code with-local-vars} cell. */
	private static final LispSymbol VAR_GET = new LispSymbol("RONTOLISP::%CLOJURE-VAR-GET");

	/** Whether the vars are bound right now. */
	private static final LispSymbol IS_THREAD_BOUND = new LispSymbol("RONTOLISP::%CLOJURE-IS-THREAD-BOUND");

	/** The unbound root a {@code with-local-vars} cell holds outside its extent. */
	private static final LispSymbol UNBOUND = new LispSymbol("RONTOLISP::%CLOJURE-UNBOUND");

	/** A map literal's map over its evaluated alternating keys and values. */
	private static final LispSymbol MAP_LITERAL = new LispSymbol("RONTOLISP::%CLOJURE-MAP-LITERAL");

	/**
	 * {@code (bound-fn & fdecl)}: {@code (bound-fn* (fn ...fdecl))}, like the oracle's
	 * expansion -- the fdecl travels untouched, so a docstring or an arity the {@code fn}
	 * lowering refuses fails there, where the oracle's macroexpansion fails. A program
	 * macro or a local of the name shadows this row, like any core meaning.
	 */
	static LispVal boundFnOf(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> fnItems = new ArrayList<>();
		fnItems.add(new LispSymbol("fn"));
		fnItems.addAll(items.subList(1, items.size()));
		return boundFnStarCall(ctx, ctx.lower(ClojureLowerUtil.list(fnItems)));
	}

	/**
	 * {@code (bound-fn* f)}: a function calling {@code f} with the creation-time bindings
	 * pushed -- every program dynamic var and core special thread-bound where the call
	 * stands, plus the lexically enclosing {@code with-local-vars} cells -- over the
	 * caller's own bindings, through the binder each var's site carries, so a call on
	 * another thread sees the captured values. A var bound nowhere at creation stays out
	 * of the map, like the oracle's frame. The capture enumerates the program's dynamic
	 * vars, so a pass that declares more of them after a capture site lowers again with
	 * the full set seeded.
	 */
	static LispVal boundFnStarOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureCoreLowering.arity("bound-fn*", items.size() - 1, 1, 1);
		return boundFnStarCall(ctx, ctx.lower(items.get(1)));
	}

	/**
	 * A function value wrapped to call it with its creation-time bindings pushed, over an
	 * already-lowered function: what {@code bound-fn*} calls and what a future body needs
	 * where the function value is already in hand.
	 * @param ctx the hub
	 * @param fun the lowered function
	 * @return the wrapper form
	 */
	static LispVal boundFnStarCall(ClojureLowering ctx, LispVal fun) {
		ctx.boundFnUsed = true;
		ctx.needThreadBinders();
		LispSymbol f = ctx.freshTemp();
		LispSymbol cap = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil
					.list(List.of(ClojureLowerUtil.list(f, fun), ClojureLowerUtil.list(cap, captureForm(ctx)))),
				ClojureLowerUtil.list(BOUND_FN_STAR, f, cap));
	}

	/**
	 * {@code bound-fn*} as a value: of the function, the same wrapper a call builds,
	 * capturing the bindings where the value is called.
	 */
	static LispVal boundFnStarValue(ClojureLowering ctx) {
		ctx.boundFnUsed = true;
		ctx.needThreadBinders();
		LispSymbol f = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(f),
				ClojureLowerUtil.list(BOUND_FN_STAR, f, captureForm(ctx)));
	}

	/**
	 * The map of the bindings in effect where a {@code bound-fn*} call stands: every
	 * program dynamic var and core special, each read only when thread-bound, plus the
	 * lexically enclosing {@code with-local-vars} cells. A program definition shadowing a
	 * special without being dynamic is skipped (it is not bindable); the unbound root a
	 * cell holds outside its extent is skipped the same way.
	 */
	private static LispVal captureForm(ClojureLowering ctx) {
		List<LispVal> unconditional = new ArrayList<>();
		List<LispVal> conditional = new ArrayList<>();
		List<String> keys = new ArrayList<>(ctx.dynamicVars);
		keys.sort(null);
		for (String key : keys) {
			// the site itself, not through `var`: the key is absolute, while a
			// `(var ns/name)` spelling resolves in the current namespace (an
			// `ns-map` elsewhere lowering this value would refuse it), and the
			// site carries no computed root setter
			LispVal var = ClojureVarLowering.internedVarOf(ctx, key);
			conditional.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), ClojureLowering.boundDepthSym(key),
							new LispInteger(0)),
					ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), List.of(var, ClojureLowering.varSym(key))),
					ClojureLowering.NIL_CONST));
		}
		for (String name : ClojureCoreSpecials.names()) {
			String key = ctx.lookupVar(name);
			ClojureCoreSpecials.Special special = ClojureCoreSpecials.required(name);
			if (key != null && !ctx.dynamicVars.contains(key)) {
				continue;
			}
			LispVal var = ClojureVarLowering.varOf(ctx,
					List.of(new LispSymbol("var"), new LispSymbol(key != null ? key : name)));
			LispVal value = key != null ? ClojureLowering.varSym(key) : ctx.specialValue(special);
			if (special.counter() == null) {
				unconditional.add(var);
				unconditional.add(value);
				continue;
			}
			conditional.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), special.counter(), new LispInteger(0)),
					ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), List.of(var, value)),
					ClojureLowering.NIL_CONST));
		}
		for (Set<String> scope : ctx.localVarScopes) {
			for (String name : scope) {
				LispSymbol local = ctx.symOf(name);
				LispVal var = local;
				LispVal value = ClojureLowerUtil.list(VAR_GET, local);
				conditional.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(IS_THREAD_BOUND,
								ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), List.of(var))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), List.of(var, value)),
						ClojureLowering.NIL_CONST));
			}
		}
		List<LispVal> segments = new ArrayList<>(conditional);
		if (!unconditional.isEmpty()) {
			segments.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), unconditional));
		}
		LispVal entries = segments.isEmpty() ? ClojureLowering.NIL_CONST
				: ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), segments);
		return ClojureLowerUtil.list(MAP_LITERAL, entries);
	}

	/**
	 * {@code (with-local-vars [name init ...] body...)}: each name a fresh var cell
	 * holding the init, thread-locally bound around the body with dynamic extent -- an
	 * {@code unwind-protect} restores every cell, so a throw leaves the unbound root
	 * behind, like the oracle's {@code try} popping its frame. The cell starts unbound
	 * (the init binds only the extent); after the extent deref reads the unbound root,
	 * and {@code var-set} stores only while bound. Inits run in order after every cell
	 * exists, so an init reads the earlier cells unbound, like the oracle's map build.
	 * The body lowers behind the {@code try} barrier like a {@code binding} body, so a
	 * {@code recur} across it is refused where the oracle's is.
	 */
	static LispVal withLocalVarsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-local-vars needs a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "with-local-vars");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0,
				"with-local-vars requires an even number of forms in binding vector");
		List<String> names = new ArrayList<>();
		for (int i = 0; i < bindings.size(); i += 2) {
			names.add(ClojureLowerUtil.plainName(ClojureLowerUtil.stripMeta(bindings.get(i)), "with-local-vars"));
		}
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		for (String name : names) {
			scope.put(name, ClojureLowering.Kind.VARIABLE);
		}
		ctx.scopes.add(scope);
		ctx.directScopes.add(new HashSet<>());
		ctx.localVarScopes.addLast(new LinkedHashSet<>(names));
		try {
			List<LispVal> pairs = new ArrayList<>();
			List<LispVal> sets = new ArrayList<>();
			List<LispVal> restores = new ArrayList<>();
			for (int i = 0; i < names.size(); i++) {
				String name = names.get(i);
				LispSymbol box = ctx.freshTemp();
				LispSymbol cell = ctx.localSym(name);
				LispSymbol temp = ctx.freshTemp();
				LispSymbol oldValue = ctx.freshTemp();
				LispSymbol oldDepth = ctx.freshTemp();
				String key = ClojureLowering.varKey(ctx.currentNs, name);
				pairs.add(ClojureLowerUtil.list(box, ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
						ClojureLowerUtil.list(UNBOUND, LispString.literal(key)), new LispInteger(0))));
				pairs.add(ClojureLowerUtil.list(cell, ClojureLowerUtil.list(LOCAL_VAR, LispString.literal(key), box)));
				pairs.add(ClojureLowerUtil.list(temp, ctx.lower(bindings.get(i * 2 + 1))));
				pairs.add(ClojureLowerUtil.list(oldValue, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), box)));
				pairs.add(ClojureLowerUtil.list(oldDepth, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), box)));
				sets.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("rplaca"), box, temp));
				sets.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("rplacd"), box,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), oldDepth, new LispInteger(1))));
				restores.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("rplaca"), box, oldValue));
				restores.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("rplacd"), box, oldDepth));
			}
			List<LispVal> guarded = new ArrayList<>(sets);
			List<LispVal> cleanup = new ArrayList<>();
			cleanup.add(ClojureLowerUtil.sym("unwind-protect"));
			cleanup.add(barrierBody(ctx, items, 2));
			cleanup.addAll(restores);
			guarded.add(ClojureLowerUtil.list(cleanup));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
					ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), guarded));
		}
		finally {
			ctx.localVarScopes.removeLast();
			ctx.directScopes.remove(ctx.directScopes.size() - 1);
			ctx.scopes.remove(ctx.scopes.size() - 1);
		}
	}

	/**
	 * {@code (pvalues expr...)}: one future per expression on the interpreter and the
	 * JVM, dereferenced in order, so the values answer in order like the oracle's; a
	 * failure surfaces at the deref after every branch was started. On wasm,
	 * single-threaded by construction, each expression runs in order on the calling
	 * thread, the documented sequential shape with the same printed seq. An empty
	 * `pvalues` answers nil, where the oracle's empty lazy seq prints ().
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered call
	 */
	static LispVal pvaluesOf(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> lowered = ctx.lowers(items, 1);
		if (!ctx.hostTarget || lowered.isEmpty()) {
			List<LispVal> out = new ArrayList<>();
			out.add(ClojureLowerUtil.sym("list"));
			out.addAll(lowered);
			return ClojureLowerUtil.list(out);
		}
		// one conveyed applier for the whole call: each expression runs on its
		// own thread with the same creation-time bindings, dereferenced in order
		LispSymbol thunkParam = ctx.freshTemp();
		LispVal applier = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(thunkParam),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), thunkParam));
		LispSymbol conveyedVar = ctx.freshTemp();
		LispVal conveyedForm = ClojureStateLowering.boundFnStarCall(ctx, applier);
		LispSymbol runnerVar = ctx.freshTemp();
		LispSymbol runnerArg = ctx.freshTemp();
		LispVal runner = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(runnerArg),
				ClojureFutureLowering.futureSpawnForm(ctx,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), conveyedVar, runnerArg))));
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(conveyedVar, conveyedForm));
		bindings.add(ClojureLowerUtil.list(runnerVar, runner));
		List<LispVal> reads = new ArrayList<>();
		for (LispVal expr : lowered) {
			LispSymbol futureVar = ctx.freshTemp();
			LispVal thunk = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), expr);
			bindings.add(ClojureLowerUtil.list(futureVar,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), runnerVar, thunk)));
			reads.add(ClojureStateLowering.derefForm(ctx, futureVar));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), reads));
	}

	/**
	 * {@code (defonce name init?)}: {@code def} unless the name is already bound -- a
	 * reload keeps the root, like the oracle.
	 */
	static LispVal defonceForm(ClojureLowering ctx, List<LispVal> items) {
		return defonceForm(ctx, items, null);
	}

	/**
	 * A {@code defonce} lowered for a namespace init: the bound-or-set statement, plus --
	 * for a {@code ^:dynamic} var, into {@code hoisted} -- the declaim and the
	 * binding-depth counter, which stay top-level at the head where the collectors read
	 * them. A reload keeps the root through the same {@code boundp} test either way.
	 * @param ctx the lowering
	 * @param items the defonce datum's items
	 * @param hoisted where a dynamic var's top-level forms go, or null for the ordinary
	 * top-level shape
	 * @return the form
	 */
	static LispVal defonceForm(ClojureLowering ctx, List<LispVal> items, @Nullable List<LispVal> hoisted) {
		return defonceForm(ctx, items, hoisted, null);
	}

	/**
	 * A {@code defonce} that answers like the oracle's REPL: the var when it defined one,
	 * {@code nil} over a var that was already bound.
	 * @param ctx the lowering
	 * @param items the defonce datum's items
	 * @param echo the lowered var the definition answers, built after the definition
	 * lowered
	 * @return the form
	 */
	static LispVal defonceEchoing(ClojureLowering ctx, List<LispVal> items, Supplier<LispVal> echo) {
		return defonceForm(ctx, items, null, echo);
	}

	private static LispVal defonceForm(ClojureLowering ctx, List<LispVal> items, @Nullable List<LispVal> hoisted,
			@Nullable Supplier<LispVal> echo) {
		ClojureLowerUtil.isTrue(items.size() == 2 || items.size() == 3, "defonce takes a name and an optional value");
		LispVal nameDatum = items.get(1);
		String name = ClojureLowerUtil.plainName(nameDatum, "defonce");
		boolean dynamic = ClojureLowerUtil.nameIsDynamic(nameDatum);
		// Like def: the value lowers against the OLD binding first.
		LispVal value = items.size() == 3 ? ctx.lower(items.get(2)) : ClojureLowering.NIL_CONST;
		String key = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(nameDatum));
		ctx.globals.put(key, ClojureLowering.Kind.VARIABLE);
		ctx.macros.remove(key); // a definition wins over the macro it shadows
		ctx.valuedAbove.add(key);
		if (dynamic) {
			ctx.dynamicVars.add(key);
		}
		boolean redef = ctx.redefinable(key, nameDatum);
		ClojureDispatchLowering.recordClassDispatchFn(ctx, key, dynamic || redef,
				items.size() == 3 ? items.get(2) : null);
		if (ClojureLowerUtil.yieldsFun(value) && !redef) {
			ctx.globalDirectFuns.add(key);
		}
		else {
			ctx.globalDirectFuns.remove(key);
		}
		LispSymbol var = ClojureLowering.varSym(key);
		LispVal set;
		if (dynamic && hoisted != null) {
			// in a namespace init the setq runs at load time, and the declaim
			// plus the counter ride top-level, where both collectors read them
			hoisted.add(ClojureLowering.declaimSpecial(var, ClojureLowering.boundDepthSym(key)));
			hoisted.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), ClojureLowering.boundDepthSym(key),
					new LispInteger(0)));
		}
		if (hoisted != null) {
			// a reload keeps the root through a runtime boundp probe of the var
			// itself: every store to a global feeds the eval mirror the compiled
			// boundp reads, so the init's own assignment is visible to the
			// probe and no set flag beside the var is needed. The probe survives
			// the compile-time boundp fold -- the init's assignment poisons the
			// name -- like the loaded flag's plain-variable test.
			LispVal store = varStore(ctx, var, value, key);
			LispVal guarded = ClojureLowerUtil.list(ClojureLowerUtil.sym("unless"), boundTest(ctx, key, var), store);
			ctx.defonceStores.put(guarded, store);
			return guarded;
		}
		else {
			set = dynamic
					? ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"), var, validatedInit(value, key)),
							varNotifyStored(key, var),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"),
									ClojureLowering.boundDepthSym(key), new LispInteger(0)))
					: varStore(ctx, var, value, key);
		}
		LispVal store = set;
		if (echo != null) {
			set = ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), set, echo.get());
		}
		LispVal guarded = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), boundTest(ctx, key, var),
				echo != null ? ClojureLowering.NIL_CONST : var, set);
		ctx.defonceStores.put(guarded, store);
		return guarded;
	}

	/**
	 * Whether a {@code defonce}'s var has a root: its global is bound and -- when a
	 * {@code declare} or a value-less {@code def} may have left it the unbound root --
	 * holds no unbound root. The root test is an {@code if}'s, so a program that makes no
	 * unbound root folds it to {@code t} ({@link ClojureArms}).
	 */
	private static LispVal boundTest(ClojureLowering ctx, String key, LispSymbol var) {
		LispVal boundp = ClojureLowerUtil.list(ClojureLowerUtil.sym("boundp"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), var));
		if (!ctx.unboundCapable.contains(key)) {
			return boundp;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), boundp,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-UNBOUND-P"), var),
						ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST));
	}

	/**
	 * {@code (defstruct name key...)}: the key vector behind the name, so {@code struct}
	 * builds maps with exactly those keys. Keys are keywords, like the oracle.
	 */
	static LispVal defstructForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "defstruct takes a name and keys");
		String name = ClojureLowerUtil.plainName(items.get(1), "defstruct");
		String struct = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(items.get(1)));
		ctx.globals.put(struct, ClojureLowering.Kind.VARIABLE);
		ctx.macros.remove(struct); // a definition wins over the macro it shadows
		List<LispVal> keys = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			LispVal key = items.get(i);
			if (!(key instanceof LispSymbol s) || !s.name().startsWith(":")) {
				throw new LispReadException("defstruct takes keyword keys, not " + key.print());
			}
			keys.add(ClojureCollectionLowering
				.keywordForm(ClojureCollectionLowering.resolveKeywordSpelling(ctx, s.name())));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureLowering.varSym(struct),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("vector"), keys));
	}

	/**
	 * {@code (struct struct-map value...)}: a fresh map pairing the struct's keys with
	 * the values, missing values nil. Too many values signal, like the oracle.
	 */
	static LispVal structOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "struct takes a struct and values");
		return structBuild(ctx, ctx.lower(items.get(1)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)), "struct");
	}

	/** A {@code struct} over an already-lowered key vector and value list. */
	static LispVal structBuild(ClojureLowering ctx, LispVal keys, LispVal values, String op) {
		LispSymbol slots = ctx.freshTemp();
		LispSymbol vals = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol have = ctx.freshTemp();
		LispSymbol want = ctx.freshTemp();
		LispSymbol index = ctx.freshTemp();
		LispVal fill = ClojureLowerUtil.list(ClojureLowerUtil.sym("dotimes"),
				ClojureLowerUtil.list(List.of(index, want)),
				ClojureCollectionLowering.tablePut(table,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"), slots, index),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("<"), index, have),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("nth"), index, vals),
								ClojureLowering.NIL_CONST)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(slots, keys), ClojureLowerUtil.list(vals, values),
						ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(have, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), vals)),
						ClojureLowerUtil.list(want, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), slots)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), have, want),
						ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
								LispString.literal("Too many arguments to " + op + " constructor")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), fill, table)));
	}

	/**
	 * {@code (struct-map struct-map key value...)}: a fresh map with the struct's keys
	 * (nil unless overridden here), like the oracle.
	 */
	static LispVal structMapOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "struct-map takes a struct and key/value pairs");
		List<LispVal> pairs = ctx.lowers(items, 2);
		ClojureLowerUtil.isTrue(pairs.size() % 2 == 0, "struct-map takes key/value pairs");
		LispSymbol slots = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol index = ctx.freshTemp();
		List<LispVal> run = new ArrayList<>();
		run.add(ClojureLowerUtil.sym("progn"));
		run.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("dotimes"),
				ClojureLowerUtil.list(List.of(index, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), slots))),
				ClojureCollectionLowering.tablePut(table,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"), slots, index), ClojureLowering.NIL_CONST)));
		for (int i = 0; i < pairs.size(); i += 2) {
			run.add(ClojureCollectionLowering.tablePut(table, pairs.get(i), pairs.get(i + 1)));
		}
		run.add(table);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(slots, ctx.lower(items.get(1))),
						ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
				ClojureLowerUtil.list(run));
	}

	// dispatch: multimethods over a method table and a dispatcher defun

	/**
	 * The exception runtime, spliced once behind the false binding when the program
	 * throws, builds or reads an exception: the one condition class every exception of
	 * the program is (ex-info, a throwable construction, a thrown host
	 * {@code Throwable}), carrying a class chain ({@link ClojureThrowables}: the class's
	 * name, then its superclasses' -- what a catch tests), a message, data and a cause,
	 * whose report is the oracle's {@code toString}; {@code C%E-NEW} builds one and
	 * {@code C%E-PARTS} answers the four slots of one (NIL for any other value). The
	 * readers and builders over them are library functions ({@code clojure.lisp},
	 * "Exceptions"), which only a program carrying this runtime reaches. Pure lowering
	 * over the shared condition runtime, so every backend runs it unchanged.
	 * <p>
	 * A program that names a {@code java:} operator and builds an exception, for a target
	 * where the host is ({@link #hostExceptions}), passes its exceptions to Java members,
	 * which take a host {@code Throwable}: there the class is a
	 * {@code java:java-exception} whose cause slot holds {@code C%E-HOST}, the function
	 * building the host exception it stands for once ({@link #hostExceptionOf}, kept in a
	 * fifth slot) -- what the {@code java:} boundary hands the member in its place.
	 */
	static List<LispVal> exInfoRuntime(ClojureLowering ctx) {
		boolean host = hostExceptions(ctx);
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol cls = new LispSymbol("C%E-EXCEPTION");
		LispSymbol cond = new LispSymbol("c");
		LispSymbol stream = new LispSymbol("s");
		LispSymbol ex = new LispSymbol("e");
		String[] slots = { "CLASS", "MESSAGE", "DATA", "CAUSE" };
		List<LispVal> slotSpecs = new ArrayList<>();
		List<LispVal> readers = new ArrayList<>();
		List<LispVal> params = new ArrayList<>();
		List<LispVal> initargs = new ArrayList<>();
		initargs.add(ClojureLowerUtil.sym("make-condition"));
		initargs.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), cls));
		for (String slot : slots) {
			LispSymbol reader = new LispSymbol("C%E-EXCEPTION-" + slot);
			LispSymbol param = new LispSymbol(slot);
			LispSymbol initarg = ClojureLowerUtil.sym(":" + slot);
			slotSpecs.add(ClojureLowerUtil.list(new LispSymbol("C%E-" + slot), ClojureLowerUtil.sym(":initarg"),
					initarg, ClojureLowerUtil.sym(":reader"), reader));
			readers.add(reader);
			params.add(param);
			initargs.add(initarg);
			initargs.add(param);
		}
		LispSymbol hostAccessor = new LispSymbol("C%E-EXCEPTION-HOST");
		LispSymbol hostBuilder = new LispSymbol("C%E-HOST");
		if (host) {
			slotSpecs.add(ClojureLowerUtil.list(new LispSymbol("C%E-HOST"), ClojureLowerUtil.sym(":initform"),
					ClojureLowering.NIL_CONST, ClojureLowerUtil.sym(":accessor"), hostAccessor));
			initargs.add(ClojureLowerUtil.sym(":" + LispNames.JAVA_EXCEPTION_CAUSE_SLOT));
			initargs.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), hostBuilder));
		}
		LispVal report = ClojureLowerUtil.list(ClojureLowerUtil.sym(":report"), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(cond, stream)), ClojureLowerUtil.list(
					ClojureLowerUtil.sym("write-string"),
					ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-EXCEPTION-STRING"),
							ClojureLowerUtil.list(readers.get(0), cond), ClojureLowerUtil.list(readers.get(1), cond),
							ClojureLowerUtil.list(readers.get(2), cond)),
					stream)));
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("define-condition"), cls,
				ClojureLowerUtil.list(List
					.of(host ? new LispSymbol(LispNames.JAVA_EXCEPTION_QUALIFIED) : ClojureLowerUtil.sym("error"))),
				ClojureLowerUtil.list(slotSpecs), report));
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol("C%E-NEW"),
				ClojureLowerUtil.list(params), ClojureLowerUtil.list(initargs)));
		List<LispVal> parts = new ArrayList<>();
		parts.add(ClojureLowerUtil.sym("list"));
		for (LispVal reader : readers) {
			parts.add(ClojureLowerUtil.list(reader, ex));
		}
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol("C%E-PARTS"),
				ClojureLowerUtil.list(List.of(ex)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("typep"), ex,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), cls)),
						ClojureLowerUtil.list(parts), ClojureLowering.NIL_CONST)));
		if (host) {
			// (defun C%E-HOST (e) (or (C%E-EXCEPTION-HOST e) (setf (C%E-EXCEPTION-HOST e)
			// (C%E-HOST-OF chain message (%clojure-host-cause cause))))), the message a
			// string: ex-info's lowering takes any value, the oracle's a String alone
			LispSymbol text = new LispSymbol("text");
			LispVal message = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil
						.list(List.of(ClojureLowerUtil.list(text, ClojureLowerUtil.list(readers.get(1), ex)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), text), text));
			LispVal built = ClojureLowerUtil.list(new LispSymbol(HOST_OF), ClojureLowerUtil.list(readers.get(0), ex),
					message,
					ClojureLowerUtil.list(new LispSymbol(HOST_CAUSE), ClojureLowerUtil.list(readers.get(3), ex)));
			runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), hostBuilder,
					ClojureLowerUtil.list(List.of(ex)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), ClojureLowerUtil.list(hostAccessor, ex),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"), ClojureLowerUtil.list(hostAccessor, ex),
									built))));
			runtime.add(hostExceptionOf(ctx));
		}
		return runtime;
	}

	/**
	 * Whether the program's exceptions are host-backed ({@link #exInfoRuntime}): it names
	 * a {@code java:} operator, for a target where the host is, and builds an exception
	 * ({@link ClojureLowering#hostExceptionClasses}) -- or it is a session, which runs on
	 * the interpreter.
	 */
	static boolean hostExceptions(ClojureLowering ctx) {
		return ctx.session || (ctx.hostTarget && ctx.namedHost && !ctx.hostExceptionClasses.isEmpty());
	}

	/** The function building the host exception a chain, message and cause stand for. */
	static final String HOST_OF = "C%E-HOST-OF";

	/** The library function a cause is a host exception through. */
	static final String HOST_CAUSE = "RONTOLISP::%CLOJURE-HOST-CAUSE";

	/**
	 * {@code (defun C%E-HOST-OF (chain message cause) ...)}: the host exception of the
	 * first class of the chain the program builds exceptions of
	 * ({@link ClojureLowering#hostExceptionClasses}) that the host builds with a message
	 * and a cause -- a construction of the literal class, so every site resolves before
	 * it runs and nothing reflects -- else a {@code RuntimeException}: {@code (C. message
	 * cause)} where the class has a {@code (String, Throwable)} constructor, else
	 * {@code (C. message)} and {@code initCause}. The classes it covers are recorded, so
	 * a session defines it again once a later buffer builds another.
	 * @param ctx the lowering
	 * @return the definition
	 */
	static LispVal hostExceptionOf(ClojureLowering ctx) {
		LispSymbol chain = new LispSymbol("chain");
		LispSymbol message = new LispSymbol("message");
		LispSymbol cause = new LispSymbol("cause");
		LispSymbol built = new LispSymbol("built");
		LispSymbol name = new LispSymbol("name");
		List<LispVal> clauses = new ArrayList<>();
		clauses.add(ClojureLowerUtil.sym("cond"));
		for (String className : ctx.hostExceptionClasses) {
			LispVal construction = hostConstruction(className, message, cause);
			if (construction != null) {
				clauses.add(ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"), name, LispString.literal(className)),
						construction));
			}
		}
		ctx.hostExceptionClassesEmitted.addAll(ctx.hostExceptionClasses);
		// RuntimeException's constructors are known: no reflection, which a native image
		// may not answer
		LispVal fallback = construction("java.lang.RuntimeException", message, cause, true);
		LispVal body;
		if (clauses.size() == 1) {
			body = fallback;
		}
		else {
			// (let ((built nil)) (dolist (name chain) (if (null built) (setq built (cond
			// ...)))) (or built fallback))
			body = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(built, ClojureLowering.NIL_CONST))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(name, chain),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), built),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), built,
											ClojureLowerUtil.list(clauses)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), built, fallback));
		}
		List<LispVal> params = new ArrayList<>(List.of(chain, message, cause));
		List<LispVal> defun = new ArrayList<>();
		defun.add(ClojureLowerUtil.sym("defun"));
		defun.add(new LispSymbol(HOST_OF));
		defun.add(ClojureLowerUtil.list(params));
		if (clauses.size() == 1) {
			defun.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), chain)));
		}
		defun.add(body);
		return ClojureLowerUtil.list(defun);
	}

	/**
	 * The construction of the host exception of a class with a message and a cause, or
	 * null when the host cannot build one from them: no such public concrete
	 * {@code Throwable}, or no public constructor taking a message.
	 */
	private static @Nullable LispVal hostConstruction(String className, LispSymbol message, LispSymbol cause) {
		ClojureJavaClasses.JavaClass type = ClojureHostClasses.find(className);
		if (type == null || !ClojureHostClasses.isAssignable(ClojureThrowables.THROWABLE, className) || !type.isPublic()
				|| type.isAbstract()) {
			return null;
		}
		if (hasPublicConstructor(type, "java.lang.String", ClojureThrowables.THROWABLE)) {
			return construction(className, message, cause, true);
		}
		return hasPublicConstructor(type, "java.lang.String") ? construction(className, message, cause, false) : null;
	}

	/**
	 * {@code (java:new "C" message cause)} for a class with a {@code (String, Throwable)}
	 * constructor, else
	 * {@code (let ((made (java:new "C" message))) (if cause (java:call made "initCause" cause)) made)}.
	 */
	private static LispVal construction(String className, LispSymbol message, LispSymbol cause, boolean takesCause) {
		LispVal javaNew = new LispSymbol(LispNames.JAVA_NEW_QUALIFIED);
		LispVal text = typed("java.lang.String", message);
		if (takesCause) {
			return ClojureLowerUtil.list(javaNew, LispString.literal(className), text,
					typed("java.lang.Throwable", cause));
		}
		LispSymbol made = new LispSymbol("made");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(made,
						ClojureLowerUtil.list(javaNew, LispString.literal(className), text)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), cause, ClojureLowerUtil
					.list(new LispSymbol(LispNames.JAVA_CALL_QUALIFIED), made, LispString.literal("initCause"), cause)),
				made);
	}

	/**
	 * {@code (the (java:object "C") value)}: the value as the class, so a construction
	 * resolves to the constructor taking it before it runs.
	 */
	private static LispVal typed(String className, LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("the"),
				ClojureLowerUtil.list(new LispSymbol(LispNames.JAVA_OBJECT_QUALIFIED), LispString.literal(className)),
				value);
	}

	private static boolean hasPublicConstructor(ClojureJavaClasses.JavaClass type, String... parameters) {
		try {
			for (ClojureJavaClasses.Member constructor : type.constructors()) {
				if (constructor.parameterTypes().equals(List.of(parameters))) {
					return true;
				}
			}
			return false;
		}
		catch (LinkageError _) {
			return false;
		}
	}

}
