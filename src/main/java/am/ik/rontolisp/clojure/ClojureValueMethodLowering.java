package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Instance calls on a Clojure value that has no host object: a collection (vector, list,
 * lazy seq, map, record, set, sorted collection), a keyword, a symbol, a ratio, an atom
 * cell or nil (the empty list here). The oracle calls the {@code clojure.lang} /
 * {@code java.util} interface method of the value's class ({@code Counted}, {@code List},
 * {@code Map}, {@code Set}, {@code Named}, {@code Ratio}, ...); here the common ones
 * answer through the core verb that does the same, so they run on every backend, and
 * every other method on such a value is refused by name instead of reaching
 * {@code java:call}, which takes no Lisp value but a string, number, character or
 * {@code t}.
 *
 * <p>
 * A record, deftype or reify is such a value too, and its class also has the protocol
 * methods its body implements and, for a record or deftype, its declared fields: a site
 * naming one of those ({@link TypedMembers}) calls the method's dispatcher or reads the
 * field, and refuses any other name on such a value in the oracle's words, since its
 * class is fully known. A site naming none pays nothing for them.
 *
 * <p>
 * A row maps a method at one arity to arms: the kinds that answer it (a one-argument
 * predicate's bare test) and the core verb, a datum lowered with the receiver and the
 * arguments bound to locals of this class's own names. A kind the row names no arm for is
 * refused in the oracle's words ({@code No matching method contains found taking 1 args
 * for class clojure.lang.PersistentArrayMap}), since its class lacks the method; a method
 * no row names is refused as unsupported, since its class may have it.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureValueMethodLowering {

	private ClojureValueMethodLowering() {
	}

	/**
	 * The receiver's local name inside an arm: the {@code %} suffix keeps it generated.
	 */
	private static final String RECV = "recv%";

	private static final LispSymbol R = new LispSymbol(RECV);

	private static final LispSymbol A = new LispSymbol(argName(0));

	private static final LispSymbol B = new LispSymbol(argName(1));

	/**
	 * One arm: the predicates whose kinds answer (any one of them; none for every value
	 * kind) and the body over the bound locals.
	 */
	private record Arm(List<String> kinds, Function<ClojureLowering, LispVal> body) {

	}

	/**
	 * The atom cell's kind (atoms, volatiles, refs and agents share it), which no
	 * one-argument predicate names.
	 */
	private static final String ATOM = "atom";

	/** The predicates whose kinds include the list, which nil stands for when empty. */
	private static final List<String> LIST_KINDS = List.of("coll?", "seq?", "list?", "sequential?");

	/** The rows, by {@code method/arity}. */
	private static final Map<String, List<Arm>> ROWS = new HashMap<>();

	static {
		collectionRows();
		lookupRows();
		updateRows();
		nameAndNumberRows();
	}

	private static void collectionRows() {
		for (String counted : List.of("count", "size")) {
			row(counted, 0, arm(List.of("coll?"), core("count", R)));
		}
		row("isEmpty", 0, arm(List.of("coll?"), core("empty?", R)));
		row("length", 0, arm(List.of("indexed?"), core("count", R)));
		row("seq", 0, arm(List.of("coll?"), core("seq", R)));
		row("first", 0, arm(List.of("seq?"), core("first", R)));
		row("next", 0, arm(List.of("seq?"), core("next", R)));
		row("more", 0, arm(List.of("seq?"), core("rest", R)));
		row("peek", 0, arm(List.of("indexed?", "list?"), core("peek", R)));
		row("pop", 0, arm(List.of("indexed?", "list?"), core("pop", R)));
		row("empty", 0, arm(List.of("coll?"), core("empty", R)));
		row("rseq", 0, arm(List.of("reversible?"), core("rseq", R)));
		row("keySet", 0, arm(List.of("map?"), core("set", core("keys", R))));
		row("subList", 2, arm(List.of("indexed?"), core("subvec", R, A, B)),
				arm(List.of("seq?"), core("subvec", core("vec", R), A, B)));
	}

	private static void lookupRows() {
		row("get", 1, arm(List.of("map?", "set?"), core("get", R, A)),
				new Arm(List.of("sequential?"), ClojureValueMethodLowering::listGet));
		row("nth", 1, new Arm(List.of("indexed?"), ClojureValueMethodLowering::listGet));
		row("nth", 2, arm(List.of("indexed?"), core("nth", R, A, B)));
		row("valAt", 1, arm(List.of("map?", "indexed?"), core("get", R, A)));
		row("valAt", 2, arm(List.of("map?", "indexed?"), core("get", R, A, B)));
		row("contains", 1, arm(List.of("set?"), core("contains?", R, A)),
				arm(List.of("sequential?"), core("contains?", core("set", R), A)));
		row("containsKey", 1, arm(List.of("map?", "indexed?"), core("contains?", R, A)));
		row("containsValue", 1, arm(List.of("map?"), core("contains?", core("set", core("vals", R)), A)));
		row("indexOf", 1, new Arm(List.of("sequential?"), ctx -> indexOf(ctx, false)));
		row("lastIndexOf", 1, new Arm(List.of("sequential?"), ctx -> indexOf(ctx, true)));
		row("entryAt", 1, arm(List.of("map?", "indexed?"), core("find", R, A)));
		row("invoke", 1, arm(List.of("ifn?"), ClojureLowerUtil.list(R, A)));
		row("invoke", 2, arm(List.of("ifn?"), ClojureLowerUtil.list(R, A, B)));
		row("applyTo", 1, arm(List.of("ifn?"), core("apply", R, A)));
	}

	private static void updateRows() {
		row("cons", 1, arm(List.of("coll?"), core("conj", R, A)));
		row("assoc", 2, arm(List.of("map?", "indexed?"), core("assoc", R, A, B)));
		row("without", 1, arm(List.of("map?"), core("dissoc", R, A)));
		row("disjoin", 1, arm(List.of("set?"), core("disj", R, A)));
		for (String equal : List.of("equiv", "equals")) {
			row(equal, 1, arm(List.of(), core("=", R, A)));
		}
		row("compareTo", 1, arm(List.of("indexed?", "ident?", "ratio?"), core("compare", R, A)));
	}

	private static void nameAndNumberRows() {
		row("getName", 0, arm(List.of("ident?"), core("name", R)));
		row("getNamespace", 0, arm(List.of("ident?"), core("namespace", R)));
		row("sym", 0, arm(List.of("keyword?"), core("symbol", R)));
		row("numerator", 0, arm(List.of("ratio?"), core("numerator", R)));
		row("denominator", 0, arm(List.of("ratio?"), core("denominator", R)));
		for (String widened : List.of("doubleValue", "floatValue")) {
			row(widened, 0, arm(List.of("ratio?"), core("double", R)));
		}
		for (String truncated : List.of("intValue", "longValue")) {
			row(truncated, 0, arm(List.of("ratio?"), core("long", R)));
		}
		row("getClass", 0, arm(List.of(), core("class", R)));
		row("deref", 0, arm(List.of(ATOM), core("deref", R)));
		row("reset", 1, arm(List.of(ATOM), core("reset!", R, A)));
		row("swap", 1, arm(List.of(ATOM), core("swap!", R, A)));
	}

	private static void row(String method, int arity, Arm... arms) {
		ROWS.put(method + "/" + arity, List.of(arms));
	}

	private static Arm arm(List<String> kinds, LispVal datum) {
		return new Arm(kinds, ctx -> ctx.lower(datum));
	}

	private static LispVal core(String verb, LispVal... operands) {
		List<LispVal> items = new ArrayList<>();
		items.add(new LispSymbol(ClojureCoreNames.PREFIX + verb));
		items.addAll(List.of(operands));
		return ClojureLowerUtil.list(items);
	}

	private static String argName(int i) {
		return "arg" + i + "%";
	}

	/**
	 * {@code List.get} / {@code Indexed.nth} of one index: the member, signalling past
	 * either end like the oracle's {@code IndexOutOfBoundsException} ({@code nth} answers
	 * nil there).
	 */
	private static LispVal listGet(ClojureLowering ctx) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-LIST-GET"), ctx.localSym(RECV),
				ctx.localSym(argName(0)));
	}

	/**
	 * {@code indexOf} / {@code lastIndexOf}: the first (last) index of a member {@code =}
	 * to the argument, or {@code -1}, like {@code java.util.List}.
	 */
	private static LispVal indexOf(ClojureLowering ctx, boolean last) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-INDEX-OF"), ctx.localSym(RECV),
				ctx.localSym(argName(0)), last ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST);
	}

	/**
	 * The value arm of an instance call over an already-bound receiver: when the receiver
	 * is a Clojure value with no host object, or nil, the mapped core verb of the first
	 * kind that answers the method, else the refusal; anything else keeps {@code call}.
	 * Nil is the empty list here, so it answers where a list does ({@code .isEmpty} of an
	 * empty {@code filter} is true, like the oracle's empty seq) and is the oracle's
	 * {@code NullPointerException} elsewhere. The arguments run once, before the
	 * dispatch, like the oracle's.
	 * @param ctx the hub
	 * @param method the method name
	 * @param recv the bound receiver
	 * @param args the lowered arguments
	 * @param call the call for any other receiver
	 * @return the form
	 */
	static LispVal valueArm(ClojureLowering ctx, String method, LispSymbol recv, List<LispVal> args, LispVal call) {
		List<Arm> arms = ROWS.get(method + "/" + args.size());
		// a row at another arity: the method exists, so the refusal is the oracle's own
		boolean known = arms != null || ROWS.keySet().stream().anyMatch(key -> key.startsWith(method + "/"));
		TypedMembers typed = typedMembers(ctx, method, args.size());
		String implemented = ClojureInterfaces.instanceTest(method, args.size() + 1);
		LispVal arm;
		if (arms == null && typed == null) {
			arm = refusal(recv, method, known, args);
			if (implemented != null) {
				// a type implementing the interface answers its own method (an arm a
				// program storing no such row sheds, leaving the refusal)
				arm = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(new LispSymbol(implemented), recv), interfaceCall(method, recv, args),
						arm);
			}
		}
		else {
			arm = boundArm(ctx, method, recv, args, arms == null ? List.of() : arms, known, typed, implemented);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-VALUE-RECEIVER-P"), recv), arm, call);
	}

	/**
	 * The call of the method a record's, deftype's or reify's type implements for an
	 * interface (its row's entry), over the receiver and the arguments.
	 */
	private static LispVal interfaceCall(String method, LispVal recv, List<LispVal> args) {
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowerUtil.sym("funcall"));
		call.add(ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.ENTRY), recv, LispString.literal(method)));
		call.add(recv);
		call.addAll(args);
		return ClojureLowerUtil.list(call);
	}

	/**
	 * What a site's method name is on a record, deftype or reify: the protocols declaring
	 * it at the site's arity, each with the record and deftype classes whose body
	 * implements it ({@code classes}; a reify is read at run time), and whether it is an
	 * immutable declared field a zero-argument call reads.
	 */
	private record TypedMembers(List<InlineCall> calls, boolean field) {

	}

	/** One protocol a site's method may call: its var key and definition. */
	private record InlineCall(String protocolKey, ClojureLowering.ProtocolDef def, List<String> classes) {

	}

	/**
	 * The typed members a site's method name may reach, or null when it names no protocol
	 * method (at any arity) and no declared field of a known record or deftype: such a
	 * site is lowered as before. The protocols and types are the lowering's, so a type
	 * defined later in another buffer of a session is not seen.
	 */
	private static @Nullable TypedMembers typedMembers(ClojureLowering ctx, String method, int n) {
		boolean named = false;
		List<InlineCall> calls = new ArrayList<>();
		for (Map.Entry<String, ClojureLowering.ProtocolDef> protocol : new TreeMap<>(ctx.protocols).entrySet()) {
			Set<Integer> arities = protocol.getValue().arities().get(method);
			if (arities == null) {
				continue;
			}
			named = true;
			if (arities.contains(n + 1)) {
				String inline = ClojureLowering.inlineMethodKey(protocol.getKey(), method);
				List<String> classes = ctx.types.values()
					.stream()
					.filter(type -> type.inlineMethods().contains(inline))
					.map(ClojureLowering.TypeDef::className)
					.sorted()
					.toList();
				calls.add(new InlineCall(protocol.getKey(), protocol.getValue(), classes));
			}
		}
		boolean field = false;
		for (ClojureLowering.TypeDef type : ctx.types.values()) {
			if (type.fields().contains(method)) {
				named = true;
				field |= n == 0 && !type.mutableFields().contains(method);
			}
		}
		return named ? new TypedMembers(calls, field) : null;
	}

	/**
	 * The arm over the receiver and arguments bound to this class's locals: the typed
	 * members first (a body's own method, then the mapped rows, then a declared field),
	 * then the refusal -- in the oracle's words on a record, deftype or reify when the
	 * site names a typed member, since its class is fully known.
	 */
	private static LispVal boundArm(ClojureLowering ctx, String method, LispSymbol recv, List<LispVal> args,
			List<Arm> arms, boolean known, @Nullable TypedMembers typed, @Nullable String implemented) {
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		scope.put(RECV, ClojureLowering.Kind.VARIABLE);
		for (int i = 0; i < args.size(); i++) {
			scope.put(argName(i), ClojureLowering.Kind.VARIABLE);
		}
		return ctx.inScope(scope, () -> {
			LispSymbol self = ctx.localSym(RECV);
			List<LispVal> bindings = new ArrayList<>();
			bindings.add(ClojureLowerUtil.list(self, recv));
			List<LispVal> locals = new ArrayList<>();
			for (int i = 0; i < args.size(); i++) {
				LispSymbol local = ctx.localSym(argName(i));
				bindings.add(ClojureLowerUtil.list(local, args.get(i)));
				locals.add(local);
			}
			List<LispVal> clauses = new ArrayList<>();
			clauses.add(ClojureLowerUtil.sym("cond"));
			if (typed != null) {
				for (InlineCall call : typed.calls()) {
					clauses.add(inlineClause(call, method, self, locals));
				}
			}
			if (implemented != null) {
				// a type implementing the interface answers its own method (an arm a
				// program storing no such row sheds, ClojureArms)
				clauses.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(implemented), self),
						interfaceCall(method, self, locals)));
			}
			for (Arm arm : arms) {
				clauses.add(ClojureLowerUtil.list(armTest(arm, self), arm.body().apply(ctx)));
			}
			if (typed != null && typed.field()) {
				LispVal key = ClojureCollectionLowering.keywordForm(method);
				clauses.add(ClojureLowerUtil.list(
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DECLARED-FIELD-P"), self, key),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key,
								ClojureProtocolLowering.typedTableOf(self))));
			}
			LispVal fallback = refusal(self, method, known, locals);
			if (typed != null && !known) {
				fallback = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RECORD-P"), self),
								ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TYPED-OPAQUE-P"), self)),
						refusal(self, method, true, locals), fallback);
			}
			clauses.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, fallback));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
					ClojureLowerUtil.list(clauses));
		});
	}

	/**
	 * A clause calling a protocol method's dispatcher on a typed receiver whose own body
	 * implements it -- an {@code extend-type} row is no method of the class, so it is not
	 * reached here, like the oracle.
	 */
	private static LispVal inlineClause(InlineCall call, String method, LispSymbol self, List<LispVal> locals) {
		List<LispVal> classes = new ArrayList<>();
		for (String cls : call.classes()) {
			classes.add(LispString.literal(cls));
		}
		LispVal test = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-INLINE-METHOD-P"), self,
				call.def().inlineTable(), ClojureCollectionLowering.keywordForm(method),
				classes.isEmpty() ? ClojureLowering.NIL_CONST
						: ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(classes)));
		String ns = call.protocolKey().substring(0, call.protocolKey().lastIndexOf('/'));
		List<LispVal> invocation = new ArrayList<>();
		invocation.add(ClojureLowering.varSym(ClojureLowering.varKey(ns, method)));
		invocation.add(self);
		invocation.addAll(locals);
		return ClojureLowerUtil.list(test, ClojureLowerUtil.list(invocation));
	}

	/**
	 * Whether an arm takes the bound receiver: one of its kinds, where nil -- the empty
	 * list here -- counts as a list; an arm naming no kind takes every value but nil.
	 */
	private static LispVal armTest(Arm arm, LispSymbol self) {
		if (arm.kinds().isEmpty()) {
			return self;
		}
		List<LispVal> tests = new ArrayList<>();
		for (String kind : arm.kinds()) {
			if (kind.equals(ATOM)) {
				tests.add(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ATOM-P"), self));
			}
			else if (kind.equals("indexed?")) {
				// a vector's methods: a type implementing Indexed answers its own
				// through the typed clauses ahead of the rows
				tests.add(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-IS-VECTOR"), self));
			}
			else {
				tests.add(ClojurePredicateLowering.rawTest(kind, self));
			}
		}
		if (arm.kinds().stream().anyMatch(LIST_KINDS::contains)) {
			tests.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), self));
		}
		return tests.size() == 1 ? tests.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), tests);
	}

	/**
	 * The refusal after the arguments run: in the oracle's words when {@code known} (a
	 * row maps the method for another kind or arity, so this class lacks it), by name
	 * otherwise. The words are fixed here but for the receiver's class, which
	 * {@code %clojure-no-method} appends (or, for nil, signals the oracle's
	 * {@code NullPointerException}).
	 */
	static LispVal refusal(LispVal recv, String method, boolean known, List<LispVal> args) {
		int n = args.size();
		String words;
		if (!known) {
			words = "Method " + method + " taking " + n + " args is not supported for class ";
		}
		else if (n == 0) {
			words = "No matching field found: " + method + " for class ";
		}
		else {
			words = "No matching method " + method + " found taking " + n + " args for class ";
		}
		List<LispVal> body = new ArrayList<>();
		body.add(ClojureLowerUtil.sym("progn"));
		for (LispVal arg : args) {
			if (!(arg instanceof LispSymbol)) {
				body.add(arg);
			}
		}
		body.add(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NO-METHOD"), recv, LispString.literal(words),
				LispString.literal(method)));
		return body.size() == 2 ? body.get(1) : ClojureLowerUtil.list(body);
	}

}
