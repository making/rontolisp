package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
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
 * The EXPERIMENTAL Clojure front end's lowering: datums to the Common Lisp core forms the
 * pipeline already consumes. A deliberately small subset, enough to state what a Clojure
 * front end over this pipeline looks like; anything else is refused by name.
 *
 * <p>
 * Binding model: every identifier mangles behind {@link #PREFIX} ({@code c%}), so no user
 * name collides with a core form, a built-in or the reader's markers. {@code defn} is a
 * {@code defun} (the direct call and the tree shaker keep working); a head-position call
 * to a {@code VARIABLE}-kind name (a parameter, a {@code let}/{@code loop} binding, a
 * {@code def}'d variable) is a {@code funcall} of the value cell, while a {@code defn}
 * name stays a direct call; a multi-arity {@code defn} is one {@code defun} per arity
 * plus a dispatch {@code defun} picking by argument count (like {@code case-lambda});
 * {@code declare} registers forward names in the pre-scan and lowers to {@code nil};
 * {@code def} a top-level {@code setq} (inside a body it still sets the global when the
 * body runs); {@code fn} and {@code #(...)} a {@code lambda} (the {@code #(...)}
 * arguments travel as one rest list), a multi-arity {@code fn} a single {@code lambda}
 * over {@code &rest} dispatching per arity, a named {@code fn} a {@code labels}
 * self-binding; {@code let} a {@code let*} (Clojure's let is sequential);
 * {@code loop}/{@code recur} a {@code labels} self call (the interpreter's tail calls
 * make it constant-stack). Binding patterns destructure: a vector pattern binds
 * positionally through the seq view ({@code nth}, {@code &} the rest as a seq,
 * {@code :as} the whole), a map pattern through the table-aware read
 * ({@code :keys}/{@code :syms}/{@code :strs}, explicit locals, {@code :as}, {@code :or}
 * defaults) -- in {@code let}, {@code loop} and {@code fn}/ {@code defn} parameters
 * alike. Threading ({@code ->}/{@code ->>}/{@code as->}) is a pure datum rewrite;
 * {@code doto}/{@code cond->}/{@code cond->>}/ {@code some->}/{@code some->>} thread
 * around one temporary; {@code list*} is a right fold of {@code cons} over the seq view.
 * A multimethod ({@code defmulti}) is a method table plus a dispatcher {@code defun}
 * applying each call's dispatch value to it ({@code defmethod} stores,
 * {@code remove-method} drops, {@code get-method} reads); hierarchies
 * ({@code derive}/{@code underive}/{@code isa?}/{@code parents}/{@code ancestors}/
 * {@code descendants}/{@code make-hierarchy}/{@code prefer-method}, {@code defmulti}
 * {@code :hierarchy}) widen the dispatch through the global hierarchy value or a custom
 * one, while protocols stay refused by name; the imperative loops and comprehensions
 * lower like the rest ({@code doseq} over the seq view answering nil, {@code dotimes}
 * over the integers below a count, {@code for} as a strict list comprehension with
 * {@code :when}/{@code :while}/{@code :let}, plus the strict {@code dorun}/ {@code doall}
 * companions). Collections: a vector literal is a {@code vector} call; a map literal is
 * an {@code equal} hash table built by {@code rontolisp:plist-hash-table} (lists and
 * nested maps key structurally; vectors and tables key by identity, like the runtime),
 * never mutated in place -- every verb that "changes" a map builds a fresh table, which
 * is what keeps the persistent semantics observable; a set literal is the same table with
 * each member stored under itself, wrapped as {@code (:C%SET table)} so a verb can tell a
 * set from a map (the wrapper prints as written, like vectors in CL notation); a seq is a
 * STRICT list view -- lists pass through untouched, vectors and strings coerce, maps
 * contribute one two-vector per entry and sets one member per element (both in the
 * table's walk order, unspecified), nil and the false object are empty, anything else
 * signals -- so {@code first}/{@code rest}/{@code next}/{@code seq}/{@code cons}/
 * {@code concat}/{@code map}/{@code filter}/{@code reduce}/{@code apply}/
 * {@code nth}/{@code take}/{@code drop} all run over every collection while the list path
 * stays a no-copy identity. There is no laziness, chunking or memoisation:
 * {@code lazy-seq}/{@code cycle}/{@code repeat}/{@code repeatedly}/{@code iterate} and an
 * end-less {@code range} are refused by name, and {@code range} with an end builds the
 * strict list. {@code nth} and {@code quot} as VALUES are correctly-ordered lambdas
 * wrapping the primitive (a bare {@code #'NTH} would have the operands backwards). A
 * keyword {@code :foo} is the list {@code (:C%KEYWORD "foo")} holding its spelling
 * verbatim (case-preserved, so {@code :a} and {@code :A} stay apart and compare unequal);
 * {@code println}/{@code print}/{@code str} spell it with its colon, and a keyword in
 * call position {@code (:k m)} (or with a default {@code (:k m dflt)}) is the same
 * table-aware read {@code get} lowers to. {@code false} is a DISTINCT non-{@code NIL}
 * object -- the value of {@code rontolisp::%clojure-false}, bound before anything else
 * runs, a symbol spelled {@code false} -- so {@code (= false nil)} is false and
 * {@code (nil?
 * false)} is false. It is falsey in every conditional: {@code if}/{@code when}/
 * {@code cond}/{@code and}/{@code or}/{@code not} lower their tests to an explicit
 * null-or-false check, and every boolean-answering builtin ({@code =}, the comparisons,
 * {@code not}, the {@code ?} predicates) answers {@code T} or the false object. Printing
 * spells the three values out: {@code println}/{@code print} show
 * {@code true}/{@code false}/{@code nil}, {@code str} shows {@code true}/{@code false}
 * and {@code ""} for {@code nil}; {@code println}/{@code print} join their parts with a
 * single space (like the oracle) while {@code str} concatenates bare, and
 * {@code pr}/{@code prn}/{@code pr-str} are the readable arms (strings print quoted).
 * Every conversion runs through the spliced {@code clojure.lisp} library
 * ({@code rontolisp::%clojure-str-of} for strings,
 * {@code rontolisp::%clojure-write-datum} straight to the stream for the print family),
 * so collections print in Clojure notation ({@code [1 :a s]}, {@code {:a 1}},
 * {@code #{1}}, {@code (true false nil :k)}) and the print family answers nil, like the
 * oracle. A lowering error names the innermost form's position ({@code file:line:column}
 * when the file is known) through the reader's offsets. Transients ({@code transient},
 * {@code persistent!}, {@code assoc!}/{@code dissoc!}/{@code conj!}/{@code disj!}) are
 * refused by name: there is no transient runtime behind the tables. State is a tagged
 * one-vector cell ({@code atom} builds it, {@code deref} reads it,
 * {@code swap!}/{@code reset!}/ {@code compare-and-set!} rewrite it); errors are
 * {@code handler-case} inside {@code unwind-protect} ({@code try}, every catch class
 * catch-all) with {@code throw} over {@code error} -- an {@code ex-info} value signals as
 * its own condition (message plus data, read by {@code ex-data}/{@code ex-message}),
 * anything else through its printed rendering; dispatch is a method table plus a
 * dispatcher {@code defun} ({@code defmulti}/{@code defmethod}); namespaces wire aliases
 * (only {@code clojure.string} resolves, over the core string operations); interop lowers
 * to the {@code java:} surface. A {@code defmacro} is a compile-time expander (one lambda
 * over the call's argument list, the same function the runtime table entry holds for
 * {@code macroexpand-1}/{@code macroexpand}) plus datum-to-datum expansion at lower time,
 * so every backend runs expanded code; syntax-quote lowers to {@code quote} with unquote
 * splicing over the mangled namespace ({@code x#} one gensym per expansion);
 * {@code gensym} is the ordinary uninterned symbol. {@code var}/{@code #'} stays refused.
 * The reader spells characters, radix integers and exact {@code M} decimals, and refuses
 * regex literals by name.
 */
public final class ClojureLowering {

	/**
	 * What every mangled identifier starts with. Contains a lowercase letter on purpose.
	 */
	static final String PREFIX = "c%";

	/**
	 * The variable holding the Clojure false value, as spelled in the emitted program --
	 * the distinct-object treatment {@code scheme.lisp}'s {@code #f} uses: a dedicated
	 * object, distinct from {@code NIL}, bound before anything else runs, so no backend
	 * learns a Clojure name.
	 */
	static final String FALSE_VARIABLE = "RONTOLISP::%CLOJURE-FALSE";

	/** The false value's own spelling: a symbol, so it prints as {@code false}. */
	static final String FALSE_VALUE_NAME = "false";

	private static final LispVal NIL_CONST = LispNil.INSTANCE;

	private static final LispVal TRUE_CONST = LispTrue.INSTANCE;

	private static final LispSymbol AMPERSAND_REST = new LispSymbol("&REST");

	/**
	 * The library string builder behind {@code str}/{@code pr-str} parts, the
	 * {@code clojure.string/join} elements, {@code throw} of a non-{@code ex-info} value,
	 * {@code ex-message} of one, and the multimethod miss messages: the value's
	 * Clojure-notation string, so no backend prints the wrappers.
	 */
	private static final LispSymbol CLOJURE_STR_OF = new LispSymbol("RONTOLISP::%CLOJURE-STR-OF");

	/**
	 * The library direct writer behind {@code println}/{@code print}/{@code pr}/
	 * {@code prn} parts: the value in Clojure notation straight to
	 * {@code *standard-output*}, answering nil.
	 */
	private static final LispSymbol CLOJURE_WRITE_DATUM = new LispSymbol("RONTOLISP::%CLOJURE-WRITE-DATUM");

	private static final LispSymbol ELSE = new LispSymbol(":else");

	private final List<LispVal> forms = new ArrayList<>();

	private final Map<String, Kind> globals = new HashMap<>();

	/**
	 * The namespace aliases in scope: an {@code :as} alias (or a namespace's own name) to
	 * its namespace. Wired by {@code ns} clauses and top-level {@code require} /
	 * {@code use}, in order, so an alias serves only the forms below it.
	 */
	private final Map<String, String> aliases = new HashMap<>();

	/**
	 * Unqualified names a {@code :refer} / {@code :use} brought in: the name to its
	 * namespace and var.
	 */
	private final Map<String, VarRef> refers = new HashMap<>();

	/**
	 * Simple class names an {@code :import} (or a top-level {@code import}) registered:
	 * the name to its FQN.
	 */
	private final Map<String, String> classNames = new HashMap<>();

	/**
	 * What {@code (:refer-clojure :only [...])} restricts the core to, or null without
	 * one; {@code (:refer-clojure :exclude [...])} removes instead. A name outside the
	 * set is not a builtin, so a user definition of it wins.
	 */
	private @Nullable Set<String> referClojureOnly;

	private final Set<String> referClojureExclude = new HashSet<>();

	/** The scopes, innermost last; globals live in {@link #globals}. */
	private final List<Map<String, Kind>> scopes = new ArrayList<>();

	/**
	 * The macros in scope: a user {@code defmacro} name to its expander, a lowered lambda
	 * over the call's argument list (one value) applying each arity's parameters to the
	 * lowered body. Expansion is datum to datum at lower time: the call site's argument
	 * datums travel quoted into one application, the answer decodes back to a datum and
	 * lowers like any other form, so every backend sees only expanded code.
	 */
	private final Map<String, LispVal> macros = new HashMap<>();

	/**
	 * Who evaluates one macro application in the macro-time environment, or null when the
	 * driver supplied none. Every production path supplies one (a file read, a session
	 * buffer); without one a macro definition still registers and still emits its runtime
	 * table entry, but a call site cannot expand.
	 */
	private @Nullable ClojureMacroEvaluator macroEvaluator;

	/**
	 * How deep macro expansion currently nests; over {@link #MAX_MACRO_DEPTH} it ends.
	 */
	private int macroDepth;

	/**
	 * The {@code #}-suffixed names the enclosing syntax-quotes bound, innermost last: one
	 * map per syntax-quote node, so {@code ~x#} in an unquote answers the same gensym the
	 * template's {@code x#} does.
	 */
	private final List<Map<String, LispSymbol>> syntaxGens = new ArrayList<>();

	private int counter;

	/** The loop whose body is being lowered, for {@code recur}; null outside any. */
	private @Nullable String loop;

	/** The rest parameter of the {@code #(...)} being lowered, or null outside one. */
	private @Nullable String anonArgs;

	/** The false value, referenced (never rebuilt) wherever {@code false} lowers. */
	private final LispSymbol falseVariable = new LispSymbol(FALSE_VARIABLE);

	/** Whether the session already emitted the false binding (files always emit it). */
	private boolean falseBound;

	/**
	 * Whether the program throws or carries exception data: the ex-info runtime (a
	 * condition with message and data slots) is spliced in once, behind the false
	 * binding.
	 */
	private boolean usedExInfo;

	/** Whether the ex-info runtime was already spliced in (files splice it inline). */
	private boolean exInfoEmitted;

	/**
	 * Whether the program uses hierarchies (any of {@code derive}/{@code underive}/
	 * {@code isa?}/{@code parents}/{@code ancestors}/{@code descendants}/
	 * {@code make-hierarchy}/{@code prefer-method}, or any {@code defmulti} whose
	 * dispatcher consults them): the hierarchy runtime is spliced in once, behind the
	 * false binding.
	 */
	private boolean usedHierarchy;

	/** Whether the hierarchy runtime was already spliced in (files splice it inline). */
	private boolean hierarchyEmitted;

	/**
	 * Whether the program defines or expands macros: the macro runtime (the table lookup,
	 * the demangler and {@code C%MACROEXPAND-1}/{@code C%MACROEXPAND}) is spliced in
	 * once, behind the false binding.
	 */
	private boolean usedMacros;

	/** Whether the macro runtime was already spliced in (files splice it inline). */
	private boolean macrosEmitted;

	/**
	 * How deep lower-time macro expansion may nest before it ends with an error. Each
	 * level is a recursive expansion (a macro whose expansion calls a macro); a wide
	 * recursion like {@code chain} over many forms nests one level per form, so the bound
	 * sits far above any written macro and only catches the self-recursive one.
	 */
	private static final int MAX_MACRO_DEPTH = 128;

	/** The reader the datums came out of, for error positions; null when unknown. */
	private @Nullable ClojureReader reader;

	static List<LispVal> lower(List<LispVal> datums) {
		return lower(datums, null, null);
	}

	static List<LispVal> lower(List<LispVal> datums, @Nullable ClojureReader reader) {
		return lower(datums, reader, null);
	}

	static List<LispVal> lower(List<LispVal> datums, @Nullable ClojureReader reader,
			@Nullable ClojureMacroEvaluator macroEvaluator) {
		ClojureLowering lowering = new ClojureLowering();
		lowering.reader = reader;
		lowering.macroEvaluator = macroEvaluator;
		lowering.declare(datums);
		lowering.forms.add(lowering.falseBinding());
		// pass two: lower
		for (LispVal datum : datums) {
			lowering.forms.addAll(lowering.topLevels(datum));
		}
		if (lowering.usedMacros) {
			// the macro runtime travels with the program, like the false value
			lowering.forms.addAll(1, lowering.macroRuntime());
		}
		if (lowering.usedHierarchy) {
			// the hierarchy runtime runs before anything else, like the false value
			lowering.forms.addAll(1, lowering.hierarchyRuntime());
		}
		if (lowering.usedExInfo) {
			// the ex-info runtime runs before anything else, like the false value
			lowering.forms.addAll(1, lowering.exInfoRuntime());
		}
		return lowering.forms;
	}

	/**
	 * Lowers one buffer of a session, one entry per top-level datum. No pre-scan can see
	 * the forms still to be typed, so each buffer first declares its own top-level names
	 * into the session's globals -- a later buffer may call what an earlier one defined
	 * -- and then lowers. A buffer that defines and uses a name in the same buffer sees
	 * it too, like a file's own pre-scan.
	 * @param buffer the reader over the typed text
	 * @return the lowered datums, in order
	 */
	List<ClojureTopLevel> interact(ClojureReader buffer) {
		this.reader = buffer;
		List<LispVal> datums = buffer.readAll();
		declare(datums);
		List<ClojureTopLevel> out = new ArrayList<>();
		for (LispVal datum : datums) {
			List<LispVal> forms = topLevels(datum);
			if (!forms.isEmpty() || !isNsForm(datum)) {
				out.add(new ClojureTopLevel(forms, true));
			}
		}
		if (!this.falseBound && !out.isEmpty()) {
			// The session's first datum carries the false binding ahead of itself,
			// like a file's first form; a buffer that failed to lower binds nothing.
			ClojureTopLevel first = out.get(0);
			List<LispVal> forms = new ArrayList<>();
			forms.add(falseBinding());
			forms.addAll(first.forms());
			out.set(0, new ClojureTopLevel(List.copyOf(forms), first.echoes()));
			this.falseBound = true;
		}
		if (this.usedHierarchy && !this.hierarchyEmitted) {
			// The hierarchy runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(hierarchyRuntime(), false));
			this.hierarchyEmitted = true;
		}
		if (this.usedMacros && !this.macrosEmitted) {
			// The macro runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(macroRuntime(), false));
			this.macrosEmitted = true;
		}
		if (this.usedExInfo && !this.exInfoEmitted) {
			// The ex-info runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(exInfoRuntime(), false));
			this.exInfoEmitted = true;
		}
		return out;
	}

	private void declare(List<LispVal> datums) {
		// pass one: every top-level name, so a definition may use one below it
		for (LispVal datum : datums) {
			try {
				declareOne(datum);
			}
			catch (LispReadException ex) {
				throw positioned(ex, datum);
			}
		}
	}

	private void declareOne(LispVal datum) {
		List<LispVal> items = items(datum);
		if (items == null || items.size() < 2) {
			return;
		}
		if (isSymbolNamed(items.get(0), "def")) {
			this.globals.put(plainName(items.get(1), "def"), Kind.VARIABLE);
		}
		else if (isSymbolNamed(items.get(0), "defn")) {
			this.globals.put(plainName(items.get(1), "defn"), Kind.FUNCTION);
		}
		else if (isSymbolNamed(items.get(0), "defmulti")) {
			this.globals.put(plainName(items.get(1), "defmulti"), Kind.FUNCTION);
		}
		else if (isSymbolNamed(items.get(0), "defmacro")) {
			// a macro name, so a call above its definition names the missing
			// expander instead of an unknown name; the definition still runs in
			// order, like the oracle's compile
			this.globals.put(plainName(items.get(1), "defmacro"), Kind.MACRO);
		}
		else if (isSymbolNamed(items.get(0), "declare")) {
			// a forward declaration: later buffers (and later forms) may call
			// what is only defined below; a real definition still wins
			for (int i = 1; i < items.size(); i++) {
				this.globals.putIfAbsent(plainName(items.get(i), "declare"), Kind.FUNCTION);
			}
		}
	}

	ClojureLowering() {
		this.scopes.add(new HashMap<>()); // locals; globals live in globals
	}

	private List<LispVal> topLevels(LispVal form) {
		try {
			if (isNsForm(form)) {
				processNs(form); // wires the aliases; defines nothing
				return List.of();
			}
			List<LispVal> items = items(form);
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defn")) {
				return defuns(items);
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defmulti")) {
				return defmultiForms(items);
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defmacro")) {
				return defmacroForms(items);
			}
			return List.of(lower(form));
		}
		catch (LispReadException ex) {
			throw positioned(ex, form);
		}
	}

	/**
	 * One datum lowered, positioned: a lowering error names the innermost form's
	 * {@code file:line:column} (through the reader's offsets), so {@code unknown name}
	 * and arity errors point at the call. An error that already carries a position -- a
	 * reader error, or one an inner form attached -- passes through untouched.
	 */
	private LispVal lower(LispVal form) {
		try {
			return lowerInner(form);
		}
		catch (LispReadException ex) {
			throw positioned(ex, form);
		}
	}

	/**
	 * The datum's position, or the error untouched when neither the datum nor the reader
	 * knows one.
	 */
	private LispReadException positioned(LispReadException ex, LispVal datum) {
		if (ex.location() != null || this.reader == null) {
			return ex;
		}
		SourceLocation at = this.reader.locate(datum);
		if (at == null) {
			return ex;
		}
		String message = ex.getMessage();
		return new LispReadException(message != null ? message : ex.toString(), at);
	}

	private LispVal lowerInner(LispVal form) {
		if (!(form instanceof LispCons)) {
			return atom(form);
		}
		List<LispVal> items = items(form);
		if (items == null) {
			throw new LispReadException("a dotted list is not a Clojure form");
		}
		if (items.isEmpty()) {
			return NIL_CONST;
		}
		LispVal head = items.get(0);
		if (isSymbolNamed(head, "quote")) {
			isTrue(items.size() == 2, "quote takes one form");
			return quote(items.get(1));
		}
		if (isSymbolNamed(head, "def")) {
			return def(items);
		}
		if (isSymbolNamed(head, "defn")) {
			// in a body: a single defun, like ever; several arities cannot splice
			// into expression position
			List<LispVal> forms = defuns(items);
			isTrue(forms.size() == 1, "a multi-arity defn is only allowed at the top level");
			return forms.get(0);
		}
		if (isSymbolNamed(head, "defmacro")) {
			// in a body: a single progn, like ever; the expander registers when
			// reached, the table entry runs with the body
			List<LispVal> forms = defmacroForms(items);
			isTrue(forms.size() == 1, "a defmacro lowers to one form");
			return forms.get(0);
		}
		if (head == ClojureReader.FN_ANON || isSymbolNamed(head, "fn")) {
			return fn(items);
		}
		if (isSymbolNamed(head, "let")) {
			return let(items);
		}
		if (isSymbolNamed(head, "loop")) {
			return loop(items);
		}
		if (isSymbolNamed(head, "declare")) {
			return declareForm(items);
		}
		if (isSymbolNamed(head, "->")) {
			return threadFirst(items);
		}
		if (isSymbolNamed(head, "->>")) {
			return threadLast(items);
		}
		if (isSymbolNamed(head, "as->")) {
			return threadAs(items);
		}
		if (isSymbolNamed(head, "doto")) {
			return dotoOf(items);
		}
		if (isSymbolNamed(head, "cond->")) {
			return condThread(items, false);
		}
		if (isSymbolNamed(head, "cond->>")) {
			return condThread(items, true);
		}
		if (isSymbolNamed(head, "some->")) {
			return someThread(items, false);
		}
		if (isSymbolNamed(head, "some->>")) {
			return someThread(items, true);
		}
		if (isSymbolNamed(head, "list*")) {
			return listStar(items);
		}
		if (isSymbolNamed(head, "doseq")) {
			return doseqOf(items);
		}
		if (isSymbolNamed(head, "dotimes")) {
			return dotimesOf(items);
		}
		if (isSymbolNamed(head, "for")) {
			return forOf(items);
		}
		if (isSymbolNamed(head, "defmulti")) {
			List<LispVal> forms = defmultiForms(items);
			return forms.size() == 1 ? forms.get(0) : cons(sym("progn"), forms);
		}
		if (isSymbolNamed(head, "defmethod")) {
			return defmethodForm(items);
		}
		if (isSymbolNamed(head, "remove-method")) {
			return removeMethodOf(items);
		}
		if (isSymbolNamed(head, "get-method")) {
			return getMethodOf(items);
		}
		if (isSymbolNamed(head, "prefer-method")) {
			return preferMethodOf(items);
		}
		if (isSymbolNamed(head, "derive") || isSymbolNamed(head, "underive") || isSymbolNamed(head, "isa?")
				|| isSymbolNamed(head, "parents") || isSymbolNamed(head, "ancestors")
				|| isSymbolNamed(head, "descendants") || isSymbolNamed(head, "make-hierarchy")) {
			return hierarchyOp(items);
		}
		if (isSymbolNamed(head, "defprotocol") || isSymbolNamed(head, "defrecord") || isSymbolNamed(head, "deftype")
				|| isSymbolNamed(head, "definterface") || isSymbolNamed(head, "reify")
				|| isSymbolNamed(head, "extend-protocol") || isSymbolNamed(head, "extend-type")
				|| isSymbolNamed(head, "extend") || isSymbolNamed(head, "satisfies?")
				|| isSymbolNamed(head, "gen-class") || isSymbolNamed(head, "gen-interface")) {
			throw new LispReadException("protocols are not supported yet: " + ((LispSymbol) head).name());
		}
		if (isSymbolNamed(head, "try")) {
			return tryOf(items);
		}
		if (isSymbolNamed(head, "throw")) {
			isTrue(items.size() == 2, "throw takes one form");
			// an ex-info value signals as its own condition (carrying the data);
			// anything else signals through its printed rendering, like before
			this.usedExInfo = true;
			LispSymbol thrown = freshTemp();
			return list(sym("let"), list(List.of(list(thrown, lower(items.get(1))))),
					list(new LispSymbol("C%E-THROW"), thrown));
		}
		if (isSymbolNamed(head, "ex-info")) {
			isTrue(items.size() == 3, "ex-info takes a message and a data map");
			this.usedExInfo = true;
			return list(sym("make-condition"), list(sym("quote"), new LispSymbol("C%E-EX-INFO")), sym(":message"),
					lower(items.get(1)), sym(":data"), lower(items.get(2)));
		}
		if (isSymbolNamed(head, "ex-data")) {
			isTrue(items.size() == 2, "ex-data takes one exception");
			this.usedExInfo = true;
			return list(new LispSymbol("C%E-DATA"), lower(items.get(1)));
		}
		if (isSymbolNamed(head, "ex-message")) {
			isTrue(items.size() == 2, "ex-message takes one exception");
			this.usedExInfo = true;
			return list(new LispSymbol("C%E-MESSAGE"), lower(items.get(1)));
		}
		if (isSymbolNamed(head, "atom")) {
			return atomOf(items);
		}
		if (isSymbolNamed(head, "deref")) {
			return derefOf(items);
		}
		if (isSymbolNamed(head, "swap!")) {
			return swapOf(items);
		}
		if (isSymbolNamed(head, "reset!")) {
			return resetOf(items);
		}
		if (isSymbolNamed(head, "compare-and-set!")) {
			return compareAndSetOf(items);
		}
		if (isSymbolNamed(head, "volatile!")) {
			isTrue(items.size() == 2, "volatile! takes an initial value");
			return wrapAtom(lower(items.get(1)));
		}
		if (isSymbolNamed(head, "vreset!")) {
			return resetOf(items);
		}
		if (isSymbolNamed(head, "vswap!")) {
			return swapOf(items);
		}
		if (isSymbolNamed(head, "add-watch") || isSymbolNamed(head, "remove-watch")) {
			throw new LispReadException(
					((LispSymbol) head).name() + " is not supported yet: atom watches need a design");
		}
		if (isSymbolNamed(head, "comment")) {
			return NIL_CONST;
		}
		if (isSymbolNamed(head, "require")) {
			requireSpecs(specsOf(items, "require"), false);
			return NIL_CONST;
		}
		if (isSymbolNamed(head, "use")) {
			requireSpecs(specsOf(items, "use"), true);
			return NIL_CONST;
		}
		if (isSymbolNamed(head, "import")) {
			importSpecs(specsOf(items, "import"));
			return NIL_CONST;
		}
		if (isSymbolNamed(head, "in-ns")) {
			return NIL_CONST; // the namespace is flat: every definition is global
		}
		if (isSymbolNamed(head, "set!")) {
			throw new LispReadException("set! is not supported yet: mutable fields need a design");
		}
		if (isSymbolNamed(head, "memfn")) {
			return memfnOf(items);
		}
		if (isSymbolNamed(head, "proxy")) {
			return proxyOf(items);
		}
		if (isSymbolNamed(head, "new")) {
			return newOf(items);
		}
		if (isSymbolNamed(head, "syntax-quote")) {
			isTrue(items.size() == 2, "syntax-quote takes one form");
			return syntaxQuote(items.get(1));
		}
		if (isSymbolNamed(head, "unquote") || isSymbolNamed(head, "unquote-splicing")) {
			throw new LispReadException(((LispSymbol) head).name()
					+ " outside syntax-quote: `~` and `~@` only unquote inside a syntax-quote");
		}
		if (isSymbolNamed(head, "var")) {
			throw new LispReadException("var is not supported yet: #'x needs a design");
		}
		if (isSymbolNamed(head, "with-meta")) {
			throw new LispReadException("with-meta is not supported yet: ^metadata needs a design");
		}
		if (head instanceof LispCons) {
			return cons(sym("funcall"), lowers(items, 0)); // ((fn ...) args)
		}
		if (head == ClojureReader.VECTOR) {
			return cons(sym("vector"), lowers(items, 1));
		}
		if (isSymbolNamed(head, "%hash-map")) {
			return mapBuild(lowers(items, 1));
		}
		if (isSymbolNamed(head, "%hash-set")) {
			return setBuild(lowers(items, 1));
		}
		if (isSymbolNamed(head, "if")) {
			isTrue(items.size() == 3 || items.size() == 4, "if takes a condition, a then and an optional else");
			LispVal test = lower(items.get(1));
			LispVal then = lower(items.get(2));
			LispVal elseForm = items.size() == 4 ? lower(items.get(3)) : NIL_CONST;
			return ifFalsey(test, then, elseForm);
		}
		if (isSymbolNamed(head, "when")) {
			isTrue(items.size() >= 3, "when needs a condition and a body");
			LispVal test = lower(items.get(1));
			return ifFalsey(test, body(items, 2), NIL_CONST);
		}
		if (isSymbolNamed(head, "cond")) {
			return condOf(items);
		}
		if (isSymbolNamed(head, "do")) {
			return body(items, 1);
		}
		if (isSymbolNamed(head, "recur")) {
			if (this.loop == null) {
				throw new LispReadException("recur outside loop");
			}
			String target = this.loop;
			List<LispVal> out = new ArrayList<>();
			out.add(new LispSymbol(target));
			for (int i = 1; i < items.size(); i++) {
				out.add(lower(items.get(i)));
			}
			return list(out);
		}
		return call(items);
	}

	private LispVal def(List<LispVal> items) {
		isTrue(items.size() == 2 || items.size() == 3, "def takes a name and an optional value");
		String name = plainName(items.get(1), "def");
		this.globals.put(name, Kind.VARIABLE);
		this.macros.remove(name); // a definition wins over the macro it shadows
		LispVal value = items.size() == 3 ? lower(items.get(2)) : NIL_CONST;
		return list(sym("setq"), idSym(name), value);
	}

	private LispVal declareForm(List<LispVal> items) {
		for (int i = 1; i < items.size(); i++) {
			this.globals.putIfAbsent(plainName(items.get(i), "declare"), Kind.FUNCTION);
		}
		return NIL_CONST;
	}

	/**
	 * One parameter vector lowered: the real lambda-list items, the destructuring
	 * prologue (flat {@code (symbol init)} pairs, sequential) and the shape facts a
	 * dispatch needs. Plain names are registered in {@code scope}; generated temporaries
	 * need no registration (nothing looks them up by name).
	 */
	private record Clause(List<LispVal> params, List<LispVal> prologue, LispVal body, boolean variadic, int fixed) {

		LispVal wrapped() {
			if (this.prologue.isEmpty()) {
				return this.body;
			}
			return list(sym("let*"), list(this.prologue), this.body);
		}

	}

	private List<LispVal> defuns(List<LispVal> items) {
		int at = 2;
		if (items.size() > at && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		isTrue(items.size() > at + 1 || items.size() == at + 1 && items.get(at) instanceof LispCons,
				"defn needs a parameter vector and a body");
		String name = plainName(items.get(1), "defn");
		this.globals.put(name, Kind.FUNCTION);
		this.macros.remove(name); // a definition wins over the macro it shadows
		if (items.get(at) instanceof LispCons && !isVectorDatum(items.get(at))) {
			return multiDefun(name, items.subList(at, items.size()));
		}
		Clause clause = clause(items.get(at), items.subList(at + 1, items.size()));
		return List.of(list(sym("defun"), idSym(name), list(clause.params()), clause.wrapped()));
	}

	/** Whether the datum is a `[...]` vector (its marker head), not an arity clause. */
	private static boolean isVectorDatum(LispVal form) {
		List<LispVal> parts = items(form);
		return parts != null && !parts.isEmpty() && parts.get(0) == ClojureReader.VECTOR;
	}

	/**
	 * A multi-arity {@code defn}: one {@code defun} per arity plus a dispatch
	 * {@code defun} picking by argument count, the shape {@code case-lambda} takes. Each
	 * clause keeps its own parameters (a variadic clause its {@code &rest}); the dispatch
	 * hands each its arguments positionally. A name no identifier mangles to (a single
	 * {@code %} outside the {@code :} escape) keeps the helpers apart from user
	 * definitions.
	 */
	private List<LispVal> multiDefun(String name, List<LispVal> clauses) {
		List<Clause> parsed = arityClauses(clauses, "defn");
		List<LispVal> forms = new ArrayList<>();
		LispVal args = freshTemp();
		LispVal count = freshTemp();
		List<LispVal> helpers = new ArrayList<>();
		for (Clause clause : parsed) {
			LispSymbol helper = new LispSymbol(PREFIX + name + "%" + (clause.variadic() ? "*" : clause.fixed()));
			helpers.add(helper);
			// the dispatch hands a variadic clause its rest pre-built as a list,
			// so the helper takes it as an ordinary parameter, not &rest
			List<LispVal> helperParams = new ArrayList<>(clause.params());
			helperParams.remove(AMPERSAND_REST);
			forms.add(list(sym("defun"), helper, list(helperParams), clause.wrapped()));
		}
		List<LispVal> arms = new ArrayList<>();
		for (int i = 0; i < parsed.size(); i++) {
			Clause clause = parsed.get(i);
			LispSymbol helper = (LispSymbol) helpers.get(i);
			List<LispVal> call = new ArrayList<>();
			call.add(helper);
			for (int p = 0; p < clause.fixed(); p++) {
				call.add(list(sym("NTH"), new LispInteger(p), args));
			}
			if (clause.variadic()) {
				call.add(list(sym("NTHCDR"), new LispInteger(clause.fixed()), args));
			}
			LispVal test = clause.variadic() ? list(sym(">="), count, new LispInteger(clause.fixed()))
					: list(sym("="), count, new LispInteger(clause.fixed()));
			arms.add(list(test, list(call)));
		}
		arms.add(list(TRUE_CONST,
				list(sym("error"), LispString.literal("wrong number of arguments passed to: " + name))));
		forms.add(list(sym("defun"), idSym(name), list(List.of(AMPERSAND_REST, args)),
				list(sym("let"), list(List.of(list(count, list(sym("length"), args)))), cons(sym("cond"), arms))));
		return forms;
	}

	/**
	 * The arity clauses of a multi-arity {@code defn} or {@code fn}, each a list of a
	 * parameter vector and a body, with at most one variadic clause and one clause per
	 * fixed arity.
	 */
	private List<Clause> arityClauses(List<LispVal> clauses, String owner) {
		isTrue(!clauses.isEmpty(), owner + " needs a parameter vector and a body");
		List<Clause> parsed = new ArrayList<>();
		Set<Integer> fixed = new HashSet<>();
		boolean variadic = false;
		for (LispVal clauseDatum : clauses) {
			List<LispVal> parts = items(clauseDatum);
			if (parts == null || parts.isEmpty()) {
				throw new LispReadException(
						owner + " arity clauses take a parameter vector and a body, not " + clauseDatum.print());
			}
			Clause clause = clause(parts.get(0), parts.subList(1, parts.size()));
			if (clause.variadic()) {
				isTrue(!variadic, owner + " takes at most one variadic clause");
				variadic = true;
			}
			else {
				isTrue(fixed.add(clause.fixed()), owner + " has two clauses for arity " + clause.fixed());
			}
			parsed.add(clause);
		}
		return parsed;
	}

	/**
	 * One lambda over a parameter vector and a not-yet-lowered body, the params scoped.
	 * Destructured parameters travel as generated temporaries with a {@code let*}
	 * prologue rebinding them to their patterns.
	 */
	private Clause clause(LispVal paramVector, List<LispVal> bodyForms) {
		List<LispVal> names = bindingItems(paramVector, "the parameter vector of");
		Map<String, Kind> scope = new HashMap<>();
		List<LispVal> params = new ArrayList<>();
		List<LispVal> prologue = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			LispVal datum = names.get(i);
			if (isSymbolNamed(datum, "&")) {
				isTrue(i + 1 < names.size(), "a & needs a rest name after it");
				isTrue(i + 2 == names.size(), "only one name may follow & in a parameter vector");
				params.add(AMPERSAND_REST);
				LispVal rest = names.get(++i);
				if (rest instanceof LispSymbol) {
					String name = plainName(rest, "the parameter vector of");
					scope.put(name, Kind.VARIABLE);
					params.add(idSym(name));
				}
				else {
					LispSymbol temp = freshTemp();
					params.add(temp);
					destructureInto(rest, temp, prologue, scope, "the parameter vector of");
				}
				continue;
			}
			if (datum instanceof LispSymbol) {
				String param = plainName(datum, "the parameter vector of");
				scope.put(param, Kind.VARIABLE);
				params.add(idSym(param));
				continue;
			}
			LispSymbol temp = freshTemp();
			params.add(temp);
			destructureInto(datum, temp, prologue, scope, "the parameter vector of");
		}
		LispVal body = inScope(scope, () -> bodyOf(bodyForms));
		boolean variadic = params.contains(AMPERSAND_REST);
		int fixed = variadic ? params.indexOf(AMPERSAND_REST) : params.size();
		return new Clause(List.copyOf(params), List.copyOf(prologue), body, variadic, fixed);
	}

	private LispVal fn(List<LispVal> items) {
		if (items.size() > 1 && items.get(1) == ClojureReader.FN_ANON) {
			isTrue(items.size() >= 2, "the anon form #(...) needs a body");
			LispVal form = items.size() == 3 ? items.get(2)
					: new LispCons(items.get(2), list(items.subList(3, items.size())));
			String outer = this.anonArgs;
			this.anonArgs = mangle("anon-args");
			try {
				return list(sym("lambda"), list(AMPERSAND_REST, new LispSymbol(this.anonArgs)), lower(form));
			}
			finally {
				this.anonArgs = outer;
			}
		}
		int at = 1;
		isTrue(items.size() > at, "fn needs a parameter vector and a body");
		String self = null;
		if (items.get(at) instanceof LispSymbol) {
			self = plainName(items.get(at), "fn");
			at++;
		}
		isTrue(items.size() > at, "fn needs a parameter vector and a body");
		if (self == null) {
			return singleOrMultiFn(items, at, "fn");
		}
		Map<String, Kind> scope = new HashMap<>();
		scope.put(self, Kind.FUNCTION);
		String name = self;
		int from = at;
		return inScope(scope, () -> {
			LispVal inner = singleOrMultiFn(items, from, name);
			// a labels self-binding: calls lower directly and the labels
			// expansion rewrites them to the local; the value is the local
			LispVal paramsAndBody = ((LispCons) inner).cdr();
			LispVal binding = new LispCons(idSym(name), paramsAndBody);
			return list(sym("labels"), list(List.of(binding)), list(sym("function"), idSym(name)));
		});
	}

	private LispVal singleOrMultiFn(List<LispVal> items, int at, String owner) {
		if (items.get(at) instanceof LispCons && !isVectorDatum(items.get(at))) {
			return multiFn(items.subList(at, items.size()), owner);
		}
		Clause clause = clause(items.get(at), items.subList(at + 1, items.size()));
		return list(sym("lambda"), list(clause.params()), clause.wrapped());
	}

	/**
	 * A multi-arity {@code fn}: one {@code lambda} over {@code &rest} dispatching per
	 * arity through {@code let*} argument bindings -- no local functions, so a clause
	 * body closes over the outer scope like any lambda body.
	 */
	private LispVal multiFn(List<LispVal> clauses, String owner) {
		List<Clause> parsed = arityClauses(clauses, owner);
		LispVal args = freshTemp();
		LispVal count = freshTemp();
		List<LispVal> arms = new ArrayList<>();
		for (Clause clause : parsed) {
			List<LispVal> bindings = new ArrayList<>();
			int position = 0;
			boolean rest = false;
			for (LispVal param : clause.params()) {
				if (AMPERSAND_REST.equals(param)) {
					rest = true;
					continue;
				}
				bindings.add(list(param, rest ? list(sym("NTHCDR"), new LispInteger(position), args)
						: list(sym("NTH"), new LispInteger(position++), args)));
			}
			bindings.addAll(clause.prologue());
			LispVal test = clause.variadic() ? list(sym(">="), count, new LispInteger(clause.fixed()))
					: list(sym("="), count, new LispInteger(clause.fixed()));
			arms.add(list(test, list(sym("let*"), list(bindings), clause.body())));
		}
		arms.add(list(TRUE_CONST,
				list(sym("error"), LispString.literal("wrong number of arguments passed to: " + owner))));
		return list(sym("lambda"), list(List.of(AMPERSAND_REST, args)),
				list(sym("let"), list(List.of(list(count, list(sym("length"), args)))), cons(sym("cond"), arms)));
	}

	private LispVal let(List<LispVal> items) {
		isTrue(items.size() >= 3, "let needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "let");
		isTrue(bindings.size() % 2 == 0, "a let binding vector pairs a name with a value");
		Map<String, Kind> scope = new HashMap<>();
		this.scopes.add(scope); // let* : each value sees the bindings before it
		LispVal form;
		try {
			List<LispVal> pairs = new ArrayList<>();
			for (int i = 0; i < bindings.size(); i += 2) {
				LispVal pattern = bindings.get(i);
				if (pattern instanceof LispSymbol) {
					String name = plainName(pattern, "let");
					pairs.add(list(idSym(name), lower(bindings.get(i + 1))));
					scope.put(name, Kind.VARIABLE);
					continue;
				}
				LispSymbol temp = freshTemp();
				pairs.add(list(temp, lower(bindings.get(i + 1))));
				destructureInto(pattern, temp, pairs, scope, "let");
			}
			form = list(sym("let*"), list(pairs), body(items, 2));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
		}
		return form;
	}

	private LispVal loop(List<LispVal> items) {
		isTrue(items.size() >= 3, "loop needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "loop");
		isTrue(bindings.size() % 2 == 0, "a loop binding vector pairs a name with a value");
		String name = mangle("loop-") + this.counter++;
		Map<String, Kind> scope = new HashMap<>();
		List<LispVal> paramSyms = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		List<LispVal> prologue = new ArrayList<>();
		this.scopes.add(scope); // let*-like: each init sees the bindings before it
		try {
			for (int i = 0; i < bindings.size(); i += 2) {
				LispVal pattern = bindings.get(i);
				LispVal init = lower(bindings.get(i + 1));
				if (pattern instanceof LispSymbol) {
					String binding = plainName(pattern, "loop");
					paramSyms.add(idSym(binding));
					inits.add(init);
					scope.put(binding, Kind.VARIABLE);
					continue;
				}
				LispSymbol temp = freshTemp();
				paramSyms.add(temp);
				inits.add(init);
				destructureInto(pattern, temp, prologue, scope, "loop");
			}
			String outer = this.loop;
			this.loop = name;
			try {
				LispVal lambdaBody = prologue.isEmpty() ? body(items, 2)
						: list(sym("let*"), list(prologue), body(items, 2));
				// a labels binding is a named function: (name (params...) body...)
				LispVal binding = new LispCons(new LispSymbol(name),
						new LispCons(list(paramSyms), cons(lambdaBody, List.of())));
				// the self call wrapped in the sequential inits: labels parameters
				// bind in parallel, but each init sees the bindings before it
				LispVal self = new LispCons(new LispSymbol(name), list(paramSyms));
				if (paramSyms.isEmpty()) {
					return list(sym("labels"), list(binding), self);
				}
				List<LispVal> initPairs = new ArrayList<>();
				for (int i = 0; i < paramSyms.size(); i++) {
					initPairs.add(list(paramSyms.get(i), inits.get(i)));
				}
				return list(sym("labels"), list(binding), list(sym("let*"), list(initPairs), self));
			}
			finally {
				this.loop = outer;
			}
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
		}
	}

	private LispVal condOf(List<LispVal> items) {
		LispVal out = NIL_CONST;
		// an odd trailing arm is the default: it fires whatever came before
		int pairsEnd = items.size() % 2 == 0 ? items.size() - 1 : items.size();
		if (pairsEnd != items.size()) {
			out = lower(items.get(items.size() - 1));
		}
		for (int i = pairsEnd - 2; i >= 1; i -= 2) {
			out = ifFalsey(condTest(items.get(i)), lower(items.get(i + 1)), out);
		}
		return out;
	}

	private LispVal condTest(LispVal test) {
		if (isSymbolNamed(test, ":else")) {
			return TRUE_CONST;
		}
		return lower(test);
	}

	/**
	 * Binds the false value before anything else runs: a quoted symbol, so every
	 * occurrence spells the same name and {@code eq} holds on every backend.
	 */
	private LispVal falseBinding() {
		return falseBindingForm();
	}

	/**
	 * A Clojure conditional over an already-lowered test: falsey when {@code NIL} or the
	 * false object, truthy otherwise. The test runs once, behind a temporary no user
	 * identifier can spell (user names always start with the prefix).
	 * @param test the lowered test
	 * @param whenTrue the lowered then form
	 * @param whenFalse the lowered else form
	 * @return the form
	 */
	private LispVal ifFalsey(LispVal test, LispVal whenTrue, LispVal whenFalse) {
		LispSymbol temp = freshTemp();
		return list(sym("LET"), list(list(temp, test)), list(sym("IF"), isFalsey(temp), whenFalse, whenTrue));
	}

	/** Whether the bound test value is falsey: {@code NIL} or the false object. */
	private LispVal isFalsey(LispSymbol temp) {
		return list(sym("OR"), list(sym("NULL"), temp), list(sym("EQ"), temp, this.falseVariable));
	}

	/**
	 * A temporary no user identifier can spell: user names always start with the prefix.
	 */
	private LispSymbol freshTemp() {
		return new LispSymbol("__clojure_" + (this.counter++));
	}

	/**
	 * A boolean-answering builtin's Clojure value: {@code T} or the false object, so
	 * printing spells it out. The raw form answers a Common Lisp boolean and appears
	 * once, so its values run once.
	 * @param raw the raw form
	 * @return the form
	 */
	private LispVal booleanAnswer(LispVal raw) {
		return list(sym("IF"), raw, TRUE_CONST, this.falseVariable);
	}

	/** {@code (and a b ...)} answers the first falsey value or the last value. */
	private LispVal andOf(List<LispVal> args) {
		if (args.isEmpty()) {
			return TRUE_CONST; // (and) is true
		}
		if (args.size() == 1) {
			return lower(args.get(0));
		}
		LispSymbol temp = freshTemp();
		return list(sym("LET"), list(list(temp, lower(args.get(0)))),
				list(sym("IF"), isFalsey(temp), temp, andOf(args.subList(1, args.size()))));
	}

	/** {@code (or a b ...)} answers the first truthy value or the last value. */
	private LispVal orOf(List<LispVal> args) {
		if (args.isEmpty()) {
			return NIL_CONST; // (or) is nil
		}
		if (args.size() == 1) {
			return lower(args.get(0));
		}
		LispSymbol temp = freshTemp();
		return list(sym("LET"), list(list(temp, lower(args.get(0)))),
				list(sym("IF"), isFalsey(temp), orOf(args.subList(1, args.size())), temp));
	}

	// clojure.string: each verb over the core string operations

	/**
	 * A {@code clojure.string} call: the vars {@code ns} resolution already vetted, over
	 * the call's own items (whose head is ignored).
	 */
	private LispVal stringCall(String var, List<LispVal> items) {
		int n = items.size() - 1;
		return switch (var) {
			case "join" -> {
				isTrue(n == 1 || n == 2, "join takes a collection and an optional separator");
				yield n == 2 ? joinForm(lower(items.get(1)), lower(items.get(2)))
						: joinForm(LispString.literal(""), lower(items.get(1)));
			}
			case "split" -> {
				isTrue(n == 2 || n == 3, "split takes a string, a pattern and an optional limit");
				yield splitForm(lower(items.get(1)), lower(items.get(2)), n == 3 ? lower(items.get(3)) : NIL_CONST,
						true);
			}
			case "split-lines" -> {
				isTrue(n == 1, "split-lines takes one string");
				yield splitLinesForm(lower(items.get(1)));
			}
			case "upper-case", "lower-case" -> {
				isTrue(n == 1, var + " takes one string");
				yield list(sym(var.equals("upper-case") ? "string-upcase" : "string-downcase"), lower(items.get(1)));
			}
			case "capitalize" -> {
				isTrue(n == 1, "capitalize takes one string");
				yield capitalizeForm(lower(items.get(1)));
			}
			case "trim" -> {
				isTrue(n == 1, "trim takes one string");
				yield list(sym("string-trim"), trimBag(), lower(items.get(1)));
			}
			case "triml" -> {
				isTrue(n == 1, "triml takes one string");
				yield list(sym("string-left-trim"), trimBag(), lower(items.get(1)));
			}
			case "trimr" -> {
				isTrue(n == 1, "trimr takes one string");
				yield list(sym("string-right-trim"), trimBag(), lower(items.get(1)));
			}
			case "trim-newline" -> {
				isTrue(n == 1, "trim-newline takes one string");
				yield trimNewlineForm(lower(items.get(1)));
			}
			case "blank?" -> {
				isTrue(n == 1, "blank? takes one string");
				yield booleanAnswer(blankForm(lower(items.get(1))));
			}
			case "starts-with?", "ends-with?", "includes?" -> {
				isTrue(n == 2, var + " takes two strings");
				yield booleanAnswer(affixForm(var, lower(items.get(1)), lower(items.get(2))));
			}
			case "index-of" -> {
				isTrue(n == 2 || n == 3, "index-of takes a string, a value and an optional start");
				yield n == 3 ? list(sym("search"), lower(items.get(2)), lower(items.get(1)), sym(":start2"),
						lower(items.get(3))) : list(sym("search"), lower(items.get(2)), lower(items.get(1)));
			}
			case "last-index-of" -> {
				isTrue(n == 2 || n == 3, "last-index-of takes a string, a value and an optional end");
				yield lastIndexForm(lower(items.get(1)), lower(items.get(2)), n == 3 ? lower(items.get(3)) : null);
			}
			case "replace" -> {
				isTrue(n == 3, "replace takes a string, a match and a replacement");
				yield replaceForm(lower(items.get(1)), lower(items.get(2)), lower(items.get(3)), false);
			}
			case "replace-first" -> {
				isTrue(n == 3, "replace-first takes a string, a match and a replacement");
				yield replaceForm(lower(items.get(1)), lower(items.get(2)), lower(items.get(3)), true);
			}
			case "escape" -> {
				isTrue(n == 2, "escape takes a string and a map");
				yield escapeForm(lower(items.get(1)), lower(items.get(2)));
			}
			case "re-quote-replacement" -> {
				isTrue(n == 1, "re-quote-replacement takes one string");
				yield replaceForm(
						replaceForm(lower(items.get(1)), LispString.literal("\\"), LispString.literal("\\\\"), false),
						LispString.literal("$"), LispString.literal("\\$"), false);
			}
			case "reverse" -> {
				isTrue(n == 1, "reverse takes one string");
				yield list(sym("coerce"),
						list(sym("reverse"), list(sym("coerce"), lower(items.get(1)), quoted("list"))),
						quoted("string"));
			}
			default -> throw new LispReadException("unknown name: clojure.string/" + var);
		};
	}

	/**
	 * A {@code clojure.string} var as a function value: one rest lambda dispatching on
	 * the argument count through the call lowering, so {@code (map s/upper-case ...)}
	 * runs what a call would run.
	 */
	private LispVal stringValue(String var) {
		// a user-style name lowered on both sides: the datum reference mangles to
		// the same symbol the parameter binds, and the counter keeps it unique
		String plain = "strv-rest" + (this.counter++);
		LispSymbol ref = new LispSymbol(plain);
		return inScope(Map.of(plain, Kind.VARIABLE), () -> {
			List<LispVal> arms = new ArrayList<>();
			for (int arity : stringArities(var)) {
				List<LispVal> callItems = new ArrayList<>();
				callItems.add(new LispSymbol(var));
				for (int i = 0; i < arity; i++) {
					callItems.add(list(new LispSymbol("nth"), ref, new LispInteger(i)));
				}
				arms.add(list(list(sym("="), list(sym("length"), idSym(plain)), new LispInteger(arity)),
						stringCall(var, callItems)));
			}
			arms.add(list(TRUE_CONST,
					list(sym("error"), LispString.literal(var + " called with wrong number of arguments"))));
			return list(sym("lambda"), list(List.of(AMPERSAND_REST, idSym(plain))), cons(sym("cond"), arms));
		});
	}

	private static List<Integer> stringArities(String var) {
		return switch (var) {
			case "join", "index-of", "last-index-of" -> List.of(1, 2);
			case "split" -> List.of(2, 3);
			case "starts-with?", "ends-with?", "includes?", "escape" -> List.of(2);
			case "replace", "replace-first" -> List.of(3);
			default -> List.of(1);
		};
	}

	/**
	 * {@code join}: the separator (nil counts as {@code ""}, like the oracle) between the
	 * {@code str} parts of the seq view, concatenated. Each part converts like a
	 * {@code str} part -- {@code ""} for nil, the colon spelling for a keyword.
	 */
	private LispVal joinForm(LispVal sep, LispVal coll) {
		LispSymbol sepSym = freshTemp();
		LispSymbol parts = freshTemp();
		LispSymbol one = freshTemp();
		LispVal strings = list(sym("mapcar"),
				list(sym("lambda"), list(one), strOf(one, LispString.literal(""), NIL_CONST)), seqForm(coll));
		LispVal interposed = list(sym("cdr"),
				list(sym("mapcan"), list(sym("lambda"), list(one), list(sym("list"), sepSym, one)), parts));
		LispVal joined = list(sym("apply"), list(sym("function"), sym("concatenate")), quoted("string"), interposed);
		return list(sym("let"),
				list(List.of(list(sepSym, list(sym("if"), list(sym("null"), sep), LispString.literal(""), sep)),
						list(parts, strings))),
				joined);
	}

	/**
	 * {@code split}: the string cut at every (non-overlapping, in order) occurrence of
	 * the string pattern -- a character or anything else signals, like the oracle's cast
	 * failure -- as a strict list. An empty match cuts between characters; an empty input
	 * answers nil; a positive limit caps the parts (the last holding the rest); a
	 * negative limit keeps every part; otherwise trailing empties drop.
	 */
	private LispVal splitForm(LispVal text, LispVal pattern, LispVal limit, boolean emptyToNil) {
		LispSymbol str = freshTemp();
		LispSymbol pat = freshTemp();
		LispSymbol lim = freshTemp();
		LispVal chars = list(sym("mapcar"), list(sym("function"), sym("string")),
				list(sym("coerce"), str, quoted("list")));
		LispVal cut = splitLoop(str, pat, lim);
		LispVal whole = list(sym("if"), list(sym("zerop"), list(sym("length"), pat)), chars, cut);
		LispVal result = emptyToNil
				? list(sym("if"), list(sym("string="), str, LispString.literal("")), NIL_CONST, whole) : whole;
		LispVal checked = list(sym("if"), list(sym("or"), list(sym("null"), lim), list(sym("integerp"), lim)), result,
				list(sym("error"), LispString.literal("split takes an integer limit")));
		return list(sym("let"), list(List.of(list(str, text), list(pat,
				list(sym("cond"), list(list(sym("stringp"), pattern), pattern),
						list(list(sym("characterp"), pattern), list(sym("string"), pattern)),
						list(TRUE_CONST, list(sym("error"), LispString.literal("split takes a string to split on"))))),
				list(lim, limit))), result);
	}

	/**
	 * The split loop: a labels self call accumulating the parts in reverse, then the
	 * limit rule -- capped (the last part holding the rest), kept whole, or trailing
	 * empties dropped.
	 */
	private LispVal splitLoop(LispVal str, LispVal pat, LispVal lim) {
		String name = mangle("split-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pos = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol at = freshTemp();
		LispVal found = list(sym("search"), pat, str, sym(":start2"), at);
		LispVal capped = list(sym("and"), list(sym("integerp"), lim), list(sym(">"), lim, new LispInteger(0)),
				list(sym("="), list(sym("length"), acc), list(sym("-"), lim, new LispInteger(1))));
		LispVal step = list(sym("if"), list(sym("null"), found),
				list(sym("reverse"), list(sym("cons"), list(sym("subseq"), str, at), acc)),
				list(sym("if"), capped, list(sym("reverse"), list(sym("cons"), list(sym("subseq"), str, at), acc)),
						list(self, list(sym("+"), found, list(sym("length"), pat)),
								list(sym("cons"), list(sym("subseq"), str, at, found), acc))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(at, acc)), cons(step, List.of())));
		LispVal parts = list(sym("labels"), list(List.of(binding)), list(self, new LispInteger(0), NIL_CONST));
		// a nonzero integer limit keeps every part (a positive one capped the loop
		// above); otherwise trailing empties drop
		LispVal keep = list(sym("and"), list(sym("integerp"), lim), list(sym("not"), list(sym("zerop"), lim)));
		return list(sym("if"), keep, parts, dropTrailing(parts));
	}

	/**
	 * Trailing empty strings dropped: the reversed list past its leading empties,
	 * reversed back.
	 */
	private LispVal dropTrailing(LispVal parts) {
		String name = mangle("trimtail-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol rest = freshTemp();
		LispVal step = list(sym("if"),
				list(sym("and"), rest, list(sym("string="), list(sym("car"), rest), LispString.literal(""))),
				list(self, list(sym("cdr"), rest)), rest);
		LispVal binding = new LispCons(self, new LispCons(list(List.of(rest)), cons(step, List.of())));
		return list(sym("reverse"),
				list(sym("labels"), list(List.of(binding)), list(self, list(sym("reverse"), parts))));
	}

	/** {@code split-lines}: split on newlines, an empty input nil, each line unended. */
	private LispVal splitLinesForm(LispVal text) {
		LispSymbol line = freshTemp();
		LispSymbol len = freshTemp();
		LispVal unended = list(sym("let"), list(List.of(list(len, list(sym("length"), line)))),
				list(sym("if"),
						list(sym("and"), list(sym(">"), len, new LispInteger(0)),
								list(sym("eql"), list(sym("char"), line, list(sym("-"), len, new LispInteger(1))),
										new LispChar('\r'))),
						list(sym("subseq"), line, new LispInteger(0), list(sym("-"), len, new LispInteger(1))), line));
		LispVal lines = splitForm(text, LispString.literal("\n"), NIL_CONST, true);
		return list(sym("mapcar"), list(sym("lambda"), list(line), unended), lines);
	}

	/** {@code capitalize}: the first character up, the rest down. */
	private static LispVal capitalizeForm(LispVal text) {
		LispSymbol str = new LispSymbol("__clojure_cap");
		return list(sym("let"), list(List.of(list(str, text))),
				list(sym("if"), list(sym("zerop"), list(sym("length"), str)), str,
						list(sym("concatenate"), quoted("string"),
								list(sym("string-upcase"),
										list(sym("subseq"), str, new LispInteger(0), new LispInteger(1))),
								list(sym("string-downcase"), list(sym("subseq"), str, new LispInteger(1))))));
	}

	/** The whitespace bag {@code trim} trims: the ASCII whitespace characters. */
	private static LispVal trimBag() {
		return list(sym("quote"), list(List.of(new LispChar(' '), new LispChar('\t'), new LispChar('\n'),
				new LispChar('\r'), new LispChar('\f'))));
	}

	/** {@code trim-newline}: one trailing newline (or carriage-return newline) off. */
	private static LispVal trimNewlineForm(LispVal text) {
		LispSymbol str = new LispSymbol("__clojure_tnl");
		LispSymbol len = new LispSymbol("__clojure_tnl_n");
		LispVal last = list(sym("char"), str, list(sym("-"), len, new LispInteger(1)));
		LispVal prev = list(sym("char"), str, list(sym("-"), len, new LispInteger(2)));
		return list(sym("let*"), list(List.of(list(str, text), list(len, list(sym("length"), str)))),
				list(sym("cond"),
						list(list(sym("and"), list(sym(">"), len, new LispInteger(1)),
								list(sym("eql"), prev, new LispChar('\r')), list(sym("eql"), last, new LispChar('\n'))),
								list(sym("subseq"), str, new LispInteger(0), list(sym("-"), len, new LispInteger(2)))),
						list(list(sym("and"), list(sym(">"), len, new LispInteger(0)),
								list(sym("eql"), last, new LispChar('\n'))),
								list(sym("subseq"), str, new LispInteger(0), list(sym("-"), len, new LispInteger(1)))),
						list(TRUE_CONST, str)));
	}

	/** Whether the value is blank: nil, or a string of only trimmable characters. */
	private static LispVal blankForm(LispVal value) {
		LispSymbol str = new LispSymbol("__clojure_blank");
		return list(sym("let"), list(List.of(list(str, value))), list(sym("or"), list(sym("null"), str),
				list(sym("zerop"), list(sym("length"), list(sym("string-trim"), trimBag(), str)))));
	}

	/**
	 * {@code starts-with?} / {@code ends-with?} / {@code includes?}, answering raw: a
	 * prefix, suffix or substring test through {@code string=} and {@code search}.
	 */
	private static LispVal affixForm(String var, LispVal text, LispVal wanted) {
		LispSymbol str = new LispSymbol("__clojure_aff");
		LispSymbol sub = new LispSymbol("__clojure_aff_sub");
		LispVal test = switch (var) {
			case "starts-with?" -> list(sym("and"), list(sym("<="), list(sym("length"), sub), list(sym("length"), str)),
					list(sym("string="), sub, list(sym("subseq"), str, new LispInteger(0), list(sym("length"), sub))));
			case "ends-with?" ->
				list(sym("and"), list(sym("<="), list(sym("length"), sub), list(sym("length"), str)), list(
						sym("string="), sub,
						list(sym("subseq"), str, list(sym("-"), list(sym("length"), str), list(sym("length"), sub)))));
			default -> list(sym("not"), list(sym("null"), list(sym("search"), sub, str)));
		};
		return list(sym("let"), list(List.of(list(str, text), list(sub, wanted))), test);
	}

	/**
	 * {@code last-index-of}: the last occurrence at or before the end index (clamped to
	 * the string, like the oracle; a negative end is nil). Without an end the whole
	 * string is searched backwards.
	 */
	private static LispVal lastIndexForm(LispVal text, LispVal wanted, @Nullable LispVal end) {
		LispSymbol str = new LispSymbol("__clojure_li");
		LispSymbol sub = new LispSymbol("__clojure_li_sub");
		LispVal tail = list(sym("search"), sub, str, sym(":from-end"), TRUE_CONST);
		if (end == null) {
			return list(sym("let"), list(List.of(list(str, text), list(sub, wanted))), tail);
		}
		LispSymbol to = new LispSymbol("__clojure_li_to");
		LispVal window = list(sym("subseq"), str, new LispInteger(0),
				list(sym("min"), list(sym("+"), to, new LispInteger(1)), list(sym("length"), str)));
		LispVal found = list(sym("search"), sub, window, sym(":from-end"), TRUE_CONST);
		return list(sym("let"), list(List.of(list(str, text), list(sub, wanted), list(to, end))),
				list(sym("if"), list(sym("<"), to, new LispInteger(0)), NIL_CONST, found));
	}

	/**
	 * {@code replace} / {@code replace-first}: every (or the first) occurrence of the
	 * string match swapped for the string-or-character replacement -- anything else
	 * signals, like the oracle's cast failure. An empty match interposes the replacement
	 * between characters.
	 */
	private LispVal replaceForm(LispVal text, LispVal match, LispVal replacement, boolean first) {
		// a string match pairs with a string replacement, a character with a
		// character: anything else is the oracle's cast failure
		LispSymbol str = freshTemp();
		LispSymbol rawMatch = freshTemp();
		LispSymbol rawRep = freshTemp();
		LispSymbol mat = freshTemp();
		LispSymbol rep = freshTemp();
		LispVal bad = list(sym("error"),
				LispString.literal("replace takes a string match and replacement, or a character pair"));
		LispVal matchNorm = list(sym("cond"),
				list(list(sym("and"), list(sym("stringp"), rawMatch), list(sym("stringp"), rawRep)), rawMatch),
				list(list(sym("and"), list(sym("characterp"), rawMatch), list(sym("characterp"), rawRep)),
						list(sym("string"), rawMatch)),
				list(TRUE_CONST, bad));
		LispVal repNorm = list(sym("cond"),
				list(list(sym("and"), list(sym("stringp"), rawMatch), list(sym("stringp"), rawRep)), rawRep),
				list(list(sym("and"), list(sym("characterp"), rawMatch), list(sym("characterp"), rawRep)),
						list(sym("string"), rawRep)),
				list(TRUE_CONST, bad));
		LispVal looped = first ? replaceFirstLoop(str, mat, rep) : replaceLoop(str, mat, rep);
		LispVal interposed = joinForm(rep,
				list(sym("mapcar"), list(sym("function"), sym("string")), list(sym("coerce"), str, quoted("list"))));
		return list(sym("let*"),
				list(List.of(list(str, text), list(rawMatch, match), list(rawRep, replacement), list(mat, matchNorm),
						list(rep, repNorm))),
				list(sym("if"), list(sym("string="), mat, LispString.literal("")), interposed, looped));
	}

	/** Every occurrence swapped: the parts before, between and after, concatenated. */
	private LispVal replaceLoop(LispVal str, LispVal mat, LispVal rep) {
		String name = mangle("replace-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pos = freshTemp();
		LispSymbol acc = freshTemp();
		LispVal found = list(sym("search"), mat, str, sym(":start2"), pos);
		LispVal grown = list(sym("cons"), rep, list(sym("cons"), list(sym("subseq"), str, pos, found), acc));
		LispVal step = list(sym("if"), list(sym("null"), found),
				list(sym("apply"), list(sym("function"), sym("concatenate")), quoted("string"),
						list(sym("reverse"), list(sym("cons"), list(sym("subseq"), str, pos), acc))),
				list(self, list(sym("+"), found, list(sym("length"), mat)), grown));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(pos, acc)), cons(step, List.of())));
		return list(sym("labels"), list(List.of(binding)), list(self, new LispInteger(0), NIL_CONST));
	}

	/** The first occurrence swapped, or the string itself when there is none. */
	private static LispVal replaceFirstLoop(LispVal str, LispVal mat, LispVal rep) {
		LispSymbol at = new LispSymbol("__clojure_rf");
		return list(sym("let"), list(List.of(list(at, list(sym("search"), mat, str)))),
				list(sym("if"), list(sym("null"), at), str,
						list(sym("concatenate"), quoted("string"), list(sym("subseq"), str, new LispInteger(0), at),
								rep, list(sym("subseq"), str, list(sym("+"), at, list(sym("length"), mat))))));
	}

	/**
	 * {@code escape}: each character looked up in the map, a hit (a string or a
	 * character) swapped in, a miss kept as itself, the whole concatenated.
	 */
	private LispVal escapeForm(LispVal text, LispVal table) {
		LispSymbol str = freshTemp();
		LispSymbol cmap = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol one = freshTemp();
		LispSymbol hit = freshTemp();
		LispVal swap = list(sym("let"), list(List.of(list(hit, list(sym("gethash"), one, cmap, miss)))),
				list(sym("if"), list(sym("eq"), hit, miss), list(sym("string"), one),
						list(sym("if"), list(sym("characterp"), hit), list(sym("string"), hit), hit)));
		return list(sym("let"),
				list(List.of(list(str, text), list(cmap, table), list(miss, list(sym("list"), NIL_CONST)))),
				list(sym("apply"), list(sym("function"), sym("concatenate")), quoted("string"), list(sym("mapcar"),
						list(sym("lambda"), list(one), swap), list(sym("coerce"), str, quoted("list")))));
	}

	/** {@code subs}: the substring from the start, past the optional end. */
	private LispVal subsOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 3, "subs takes a string, a start and an optional end");
		return n == 3 ? list(sym("subseq"), lower(items.get(1)), lower(items.get(2)), lower(items.get(3)))
				: list(sym("subseq"), lower(items.get(1)), lower(items.get(2)));
	}

	/** {@code subs} as a value: a two- or three-argument lambda over the primitive. */
	private LispVal subsValue() {
		LispSymbol str = new LispSymbol(mangle("subs-s"));
		LispSymbol from = new LispSymbol(mangle("subs-from"));
		LispSymbol args = new LispSymbol(mangle("subs-args"));
		LispVal two = list(sym("subseq"), str, from);
		LispVal three = list(sym("subseq"), str, from, list(sym("car"), args));
		LispVal arity = list(sym("error"), LispString.literal("subs takes a string, a start and an optional end"));
		LispVal body = list(sym("if"), list(sym("null"), args), two,
				list(sym("if"), list(sym("null"), list(sym("cdr"), args)), three, arity));
		return list(sym("lambda"), list(List.of(str, from, AMPERSAND_REST, args)), body);
	}

	// calls

	private LispVal call(List<LispVal> items) {
		if (!(items.get(0) instanceof LispSymbol op)) {
			throw new LispReadException("a call's head must be a name: " + items.get(0).print());
		}
		String name = op.name();
		if (name.startsWith(":")) {
			return keywordCall(name, items);
		}
		LispVal macro = macroCall(name, items);
		if (macro != null) {
			return macro;
		}
		LispVal special = builtin(name, items);
		if (special != null) {
			return special;
		}
		VarRef qualified = resolveQualified(name);
		if (qualified != null) {
			return stringCall(qualified.var(), items);
		}
		LispVal interop = interopCall(name, items);
		if (interop != null) {
			return interop;
		}
		isTrue(known(name) || this.refers.containsKey(name), "unknown name: " + name);
		VarRef referred = known(name) ? null : this.refers.get(name);
		if (referred != null) {
			return stringCall(referred.var(), items);
		}
		List<LispVal> args = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			args.add(lower(items.get(i)));
		}
		if (!isFunction(name)) {
			// a parameter, a let/loop binding or a def'd variable holds the
			// function in the VALUE cell (Lisp-2): a direct call would read the
			// function cell and miss, so call through funcall instead. A defn
			// (and a declare, which keeps its current error) stays direct.
			List<LispVal> funcall = new ArrayList<>();
			funcall.add(sym("FUNCALL"));
			funcall.add(idSym(name));
			funcall.addAll(args);
			return list(funcall);
		}
		List<LispVal> out = new ArrayList<>();
		out.add(idSym(name));
		out.addAll(args);
		return list(out);
	}

	/** The core names, spelled as the Common Lisp operation they lower to. */
	private @Nullable LispVal builtin(String name, List<LispVal> items) {
		if (!coreAllowed(name)) {
			return null; // excluded by (:refer-clojure ...): a user definition wins
		}
		int n = items.size() - 1;
		switch (name) {
			case "+", "-", "*", "/", "max", "min", "rem", "mod", "abs", "list", "expt", "reverse":
				return plain(name, items);
			case "quot":
				isTrue(n == 2, "quot takes two arguments");
				return list(sym("truncate"), lower(items.get(1)), lower(items.get(2)));
			case "nth":
				return nthOf(items);
			case "and":
				return andOf(items.subList(1, items.size()));
			case "or":
				return orOf(items.subList(1, items.size()));
			case "=":
				return booleanAnswer(equalityRaw(items));
			case "not=":
				return booleanAnswer(list(sym("not"), equalityRaw(items)));
			case "<", ">", "<=", ">=":
				return booleanAnswer(plain(name, items));
			case "inc":
				isTrue(n == 1, "inc takes one argument");
				return list(sym("+"), lower(items.get(1)), new LispInteger(1));
			case "dec":
				isTrue(n == 1, "dec takes one argument");
				return list(sym("-"), lower(items.get(1)), new LispInteger(1));
			case "not":
				isTrue(n == 1, "not takes one argument");
				LispSymbol notTemp = freshTemp();
				return list(sym("LET"), list(list(notTemp, lower(items.get(1)))), booleanAnswer(isFalsey(notTemp)));
			case "str":
				return strCall(items);
			case "pr-str":
				return prStrCall(items);
			case "println":
				return printCall(items, true);
			case "print":
				return printCall(items, false);
			case "prn":
				return prCall(items, true);
			case "pr":
				return prCall(items, false);
			case "newline":
				isTrue(n == 0, "newline takes no argument");
				return list(sym("princ"), LispString.literal("\n"));
			case "count":
				return countOf(items);
			case "seq":
				isTrue(items.size() == 2, "seq takes one collection");
				return seqForm(lower(items.get(1)));
			case "first":
				isTrue(items.size() == 2, "first takes one collection");
				return list(sym("car"), seqForm(lower(items.get(1))));
			case "rest":
			case "next":
				isTrue(items.size() == 2, name + " takes one collection");
				return list(sym("cdr"), seqForm(lower(items.get(1))));
			case "cons":
				isTrue(items.size() == 3, "cons takes an item and a collection");
				return consForm(lower(items.get(1)), lower(items.get(2)));
			case "empty?":
				return booleanAnswer(emptyOf(items));
			case "nil?":
				return booleanAnswer(plain("null", items));
			case "some?":
				isTrue(n == 1, "some? takes one argument");
				return booleanAnswer(list(sym("not"), list(sym("null"), lower(items.get(1)))));
			case "even?":
				return booleanAnswer(plain("evenp", items));
			case "odd?":
				return booleanAnswer(plain("oddp", items));
			case "zero?":
				return booleanAnswer(plain("zerop", items));
			case "pos?":
				return booleanAnswer(plain("plusp", items));
			case "neg?":
				return booleanAnswer(plain("minusp", items));
			case "false?":
				isTrue(n == 1, "false? takes one argument");
				return booleanAnswer(list(sym("EQ"), lower(items.get(1)), this.falseVariable));
			case "true?":
				isTrue(n == 1, "true? takes one argument");
				return booleanAnswer(list(sym("EQ"), lower(items.get(1)), TRUE_CONST));
			case "boolean?":
				isTrue(n == 1, "boolean? takes one argument");
				LispSymbol booleanTemp = freshTemp();
				return list(sym("LET"), list(list(booleanTemp, lower(items.get(1)))), booleanAnswer(list(sym("OR"),
						list(sym("EQ"), booleanTemp, TRUE_CONST), list(sym("EQ"), booleanTemp, this.falseVariable))));
			case "vector":
				return plain("vector", items);
			case "vector?":
				return booleanAnswer(plain("vectorp", items));
			case "map":
				isTrue(n == 2, "map takes one function and one collection");
				return list(sym("mapcar"), fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "filter":
				isTrue(n == 2, "filter takes a predicate and a collection");
				return list(sym("remove-if-not"), fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "reduce":
				isTrue(n == 2 || n == 3, "reduce takes a function, an optional value and a collection");
				if (n == 2) {
					return list(sym("reduce"), fnValue(items.get(1)), seqForm(lower(items.get(2))));
				}
				return list(sym("reduce"), fnValue(items.get(1)), seqForm(lower(items.get(3))), sym(":initial-value"),
						lower(items.get(2)));
			case "apply":
				return applyOf(items);
			case "concat":
				if (items.size() == 1) {
					return NIL_CONST;
				}
				List<LispVal> seqs = new ArrayList<>();
				for (int i = 1; i < items.size(); i++) {
					seqs.add(seqForm(lower(items.get(i))));
				}
				return cons(sym("append"), seqs);
			case "take":
				return takeOf(items);
			case "drop":
				return dropOf(items);
			case "range":
				return rangeOf(items);
			case "dorun":
				return dorunOf(items);
			case "doall":
				return doallOf(items);
			case "gensym":
				return gensymOf(items);
			case "macroexpand-1":
				isTrue(n == 1, "macroexpand-1 takes one form");
				this.usedMacros = true;
				return list(new LispSymbol(MACROEXPAND_1), lower(items.get(1)));
			case "macroexpand":
				isTrue(n == 1, "macroexpand takes one form");
				this.usedMacros = true;
				return list(new LispSymbol(MACROEXPAND), lower(items.get(1)));
			case "assoc":
				return assocOf(items);
			case "dissoc":
				return dissocOf(items);
			case "get":
				return getOf(items);
			case "contains?":
				return containsOf(items);
			case "subs":
				return subsOf(items);
			case "keys":
				return keysOf(items);
			case "vals":
				return valsOf(items);
			case "merge":
				return mergeOf(items);
			case "conj":
				return conjOf(items);
			case "disj":
				return disjOf(items);
			case "set":
				return setOf(items);
			case "hash-map":
				return mapConstructorOf(items, "hash-map");
			case "array-map":
				return mapConstructorOf(items, "array-map");
			case "transient", "persistent!", "assoc!", "dissoc!", "conj!", "disj!":
				throw new LispReadException("transients are not supported yet: " + name);
			case "lazy-seq", "cycle", "repeat", "repeatedly", "iterate":
				throw new LispReadException("lazy sequences are not supported: " + name);
			default:
				return null;
		}
	}

	/** The tag heading a wrapped set: a set is {@code (LIST :C%SET table)}. */
	private static final LispSymbol SET_TAG = new LispSymbol(":C%SET");

	/**
	 * The tag heading a keyword value: a keyword is {@code (LIST :C%KEYWORD name)}
	 * holding its spelling verbatim (without the colon, case-preserved), so {@code :a}
	 * and {@code :A} stay apart. A cons keys an {@code equal} table structurally, so
	 * keywords key structurally and never collide with strings; {@code equal} compares
	 * two spellings case-sensitively through the same shape.
	 */
	private static final LispSymbol KEYWORD_TAG = new LispSymbol(":C%KEYWORD");

	/** A keyword's spelling as data: {@code (:C%KEYWORD "name")}, for quoted forms. */
	private static LispVal keywordDatum(String spelling) {
		return new LispCons(KEYWORD_TAG, new LispCons(new LispString(spelling), NIL_CONST));
	}

	/** A keyword's construction: {@code (LIST :C%KEYWORD "name")}. */
	private static LispVal keywordForm(String spelling) {
		return list(sym("list"), KEYWORD_TAG, LispString.literal(spelling));
	}

	/**
	 * Refuses what a keyword spelling cannot be: {@code ::}-auto-resolve has no namespace
	 * to resolve against (an {@code ns} form defines nothing), and a bare {@code :} names
	 * nothing. A namespaced {@code :a/b} is opaque data otherwise -- its spelling prints
	 * and compares whole, like the oracle's.
	 */
	private static void validateKeyword(String name) {
		if (name.startsWith("::")) {
			throw new LispReadException("auto-resolved keywords are not supported yet: " + name);
		}
		isTrue(name.length() > 1, "a keyword needs a name: " + name);
	}

	/**
	 * A keyword in call position: the map lookup {@code (:k m)} (or with a default
	 * {@code (:k m dflt)}), over the same table-aware read {@code get} lowers to, so sets
	 * answer their member and vectors and strings their indexed element too.
	 */
	private LispVal keywordCall(String name, List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, name + " takes a collection and an optional default");
		validateKeyword(name);
		List<LispVal> getForm = new ArrayList<>();
		getForm.add(new LispSymbol("get"));
		getForm.add(items.get(1));
		getForm.add(items.get(0));
		if (n == 2) {
			getForm.add(items.get(2));
		}
		return getOf(getForm);
	}

	/** {@code (RONTOLISP:PLIST-HASH-TABLE plist :TEST 'EQUAL)}: a fresh equal table. */
	private static LispVal tableFromPlist(LispVal plist) {
		return list(sym("rontolisp:plist-hash-table"), plist, sym(":test"), list(sym("quote"), sym("equal")));
	}

	/** {@code (RONTOLISP:HASH-TABLE-PLIST table)}. */
	private static LispVal tablePlist(LispVal table) {
		return list(sym("rontolisp:hash-table-plist"), table);
	}

	/** {@code (MAKE-HASH-TABLE :TEST 'EQUAL)}. */
	private static LispVal makeTable() {
		return list(sym("make-hash-table"), sym(":test"), list(sym("quote"), sym("equal")));
	}

	/**
	 * Whether the form holds a wrapped set: a cons headed by the tag over a table. The
	 * full shape check keeps user data from misfiring the test.
	 */
	private static LispVal isSetForm(LispVal form) {
		return list(sym("and"), list(sym("consp"), form), list(sym("eq"), list(sym("car"), form), SET_TAG),
				list(sym("hash-table-p"), list(sym("cadr"), form)));
	}

	/** The table inside a wrapped set: {@code (CADR form)}. */
	private static LispVal setInner(LispVal form) {
		return list(sym("cadr"), form);
	}

	/** {@code (LIST :C%SET table)}: the set wrapper. */
	private static LispVal wrapSet(LispVal table) {
		return list(sym("list"), SET_TAG, table);
	}

	/**
	 * A map construction over lowered key/value pairs: an equal table, so vector keys and
	 * nested maps compare structurally. The pairs evaluate once each, left to right.
	 */
	private static LispVal mapBuild(List<LispVal> pairs) {
		return tableFromPlist(cons(sym("list"), pairs));
	}

	/**
	 * A set construction over lowered elements: an equal table holding each member under
	 * itself, wrapped so verbs tell it from a map. Each element is bound once, so a
	 * side-effecting element runs once.
	 */
	private LispVal setBuild(List<LispVal> elements) {
		LispSymbol table = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(table, makeTable()));
		List<LispVal> body = new ArrayList<>();
		for (LispVal element : elements) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, element));
			body.add(list(sym("setf"), list(sym("gethash"), one, table), one));
		}
		body.add(wrapSet(table));
		return letForm(bindings, body);
	}

	/** {@code (LET bindings body...)}: a let over a computed body, spliced flat. */
	private static LispVal letForm(List<LispVal> bindings, List<LispVal> body) {
		List<LispVal> forms = new ArrayList<>();
		forms.add(list(bindings));
		forms.addAll(body);
		return cons(sym("let"), forms);
	}

	private LispVal assocOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 3 && n % 2 == 1, "assoc takes a map and key/value pairs");
		LispSymbol map = freshTemp();
		LispVal grown = cons(sym("append"),
				List.of(list(sym("if"), map, tablePlist(map), NIL_CONST), cons(sym("list"), lowers(items, 2))));
		return list(sym("let"), list(List.of(list(map, lower(items.get(1))))), tableFromPlist(grown));
	}

	private LispVal dissocOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "dissoc takes a map and keys");
		LispSymbol map = freshTemp();
		LispSymbol copy = freshTemp();
		List<LispVal> body = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			body.add(list(sym("remhash"), lower(items.get(i)), copy));
		}
		body.add(copy);
		LispVal rebuilt = letForm(List.of(list(copy, tableFromPlist(tablePlist(map)))), body);
		return list(sym("let"), list(List.of(list(map, lower(items.get(1))))),
				list(sym("if"), map, rebuilt, NIL_CONST));
	}

	/**
	 * The bounds check of an indexed read: a vector for {@code get}, either for the rest.
	 */
	private static LispVal indexForm(LispVal coll, LispVal key, boolean vector) {
		return list(sym("and"), list(sym(vector ? "vectorp" : "stringp"), coll), list(sym("integerp"), key),
				list(sym(">="), key, new LispInteger(0)), list(sym("<"), key, list(sym("length"), coll)));
	}

	private LispVal getOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 3, "get takes a map, a key and an optional default");
		return getForm(lower(items.get(1)), lower(items.get(2)), n == 3 ? lower(items.get(3)) : NIL_CONST);
	}

	/**
	 * The table-aware read over already-lowered collection, key and default: a set
	 * answers its member, a map its value, a vector or a string its indexed element,
	 * anything else the default.
	 */
	private LispVal getForm(LispVal coll, LispVal key, LispVal dflt) {
		LispSymbol collSym = freshTemp();
		LispSymbol keySym = freshTemp();
		LispSymbol dfltSym = freshTemp();
		List<LispVal> bindings = List.of(list(collSym, coll), list(keySym, key), list(dfltSym, dflt));
		return list(sym("let"), list(bindings), cons(sym("cond"), getBranches(collSym, keySym, dfltSym)));
	}

	/**
	 * The branches of a table-aware read over an already-bound collection, key and
	 * default: a set answers its member, a map its value, a vector or a string its
	 * indexed element, anything else the default. The three arrive as side-effect-free
	 * forms (bound temporaries, a lambda parameter), so the branches may name them more
	 * than once.
	 */
	private List<LispVal> getBranches(LispVal coll, LispVal key, LispVal dflt) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(coll), list(sym("gethash"), key, setInner(coll), dflt)));
		branches.add(list(list(sym("hash-table-p"), coll), list(sym("gethash"), key, coll, dflt)));
		branches.add(list(indexForm(coll, key, true), list(sym("elt"), coll, key)));
		branches.add(list(indexForm(coll, key, false), list(sym("char"), coll, key)));
		branches.add(list(TRUE_CONST, dflt));
		return branches;
	}

	private LispVal containsOf(List<LispVal> items) {
		isTrue(items.size() == 3, "contains? takes a collection and a key");
		LispSymbol coll = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol miss = freshTemp();
		List<LispVal> bindings = List.of(list(coll, lower(items.get(1))), list(key, lower(items.get(2))),
				list(miss, list(sym("list"), NIL_CONST)));
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(coll), booleanAnswer(
				list(sym("not"), list(sym("eq"), list(sym("gethash"), key, setInner(coll), miss), miss)))));
		branches.add(list(list(sym("hash-table-p"), coll),
				booleanAnswer(list(sym("not"), list(sym("eq"), list(sym("gethash"), key, coll, miss), miss)))));
		branches.add(list(indexForm(coll, key, true), TRUE_CONST));
		branches.add(list(indexForm(coll, key, false), TRUE_CONST));
		branches.add(list(TRUE_CONST, this.falseVariable));
		return list(sym("let"), list(bindings), cons(sym("cond"), branches));
	}

	private LispVal keysOf(List<LispVal> items) {
		isTrue(items.size() == 2, "keys takes one map");
		return tableKeysOf(items, true);
	}

	private LispVal valsOf(List<LispVal> items) {
		isTrue(items.size() == 2, "vals takes one map");
		return tableKeysOf(items, false);
	}

	/**
	 * {@code keys} (or {@code vals}): the table's keys (or values) accumulated into a
	 * list. The order is the table's walk order, unspecified like the oracle's.
	 */
	private LispVal tableKeysOf(List<LispVal> items, boolean keys) {
		LispSymbol map = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal take = keys ? key : val;
		LispVal drop = keys ? val : key;
		LispVal collect = list(sym("maphash"), list(sym("lambda"), list(List.of(key, val)),
				list(sym("declare"), list(sym("ignore"), drop)), list(sym("setq"), acc, list(sym("cons"), take, acc))),
				map);
		return list(sym("let"), list(List.of(list(map, lower(items.get(1))))),
				list(sym("if"), map, list(sym("let"), list(List.of(list(acc, NIL_CONST))), collect, acc), NIL_CONST));
	}

	private LispVal mergeOf(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return NIL_CONST;
		}
		if (n == 1) {
			return lower(items.get(1));
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> present = new ArrayList<>();
		List<LispVal> plists = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, lower(items.get(i))));
			present.add(one);
			plists.add(list(sym("if"), one, tablePlist(one), NIL_CONST));
		}
		return list(sym("let"), list(bindings),
				list(sym("if"), cons(sym("or"), present), tableFromPlist(cons(sym("append"), plists)), NIL_CONST));
	}

	private LispVal conjOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "conj takes a collection and items");
		LispVal acc = lower(items.get(1));
		for (int i = 2; i < items.size(); i++) {
			acc = conjTwo(acc, items.get(i));
		}
		return acc;
	}

	/**
	 * One conjoined item: a set gains a member, a map gains the item's entries, a vector
	 * gains at the end, a list or nil at the front. Anything else signals, like the
	 * oracle's.
	 */
	private LispVal conjTwo(LispVal coll, LispVal itemDatum) {
		LispSymbol collSym = freshTemp();
		LispSymbol item = freshTemp();
		List<LispVal> bindings = List.of(list(collSym, coll), list(item, lower(itemDatum)));
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(collSym), setAdd(collSym, item)));
		branches.add(list(list(sym("hash-table-p"), collSym),
				tableFromPlist(cons(sym("append"), List.of(tablePlist(collSym), entryPlist(item))))));
		branches.add(list(list(sym("vectorp"), collSym),
				list(sym("coerce"),
						list(sym("append"), list(sym("coerce"), collSym, quoted("list")), list(sym("list"), item)),
						quoted("vector"))));
		branches.add(list(list(sym("or"), list(sym("null"), collSym), list(sym("consp"), collSym)),
				list(sym("cons"), item, collSym)));
		branches.add(list(TRUE_CONST, list(sym("error"), LispString.literal("conj needs a collection and an item"))));
		return list(sym("let"), list(bindings), cons(sym("cond"), branches));
	}

	/** {@code (QUOTE name)} over a lower-case name, for a coerce designator. */
	private static LispVal quoted(String name) {
		return list(sym("quote"), sym(name));
	}

	/**
	 * The entries one conjoined item adds to a map, as a plist: a map's own pairs, a
	 * two-vector's or two-list's pair, or a set's members each as an entry.
	 */
	private LispVal entryPlist(LispVal item) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("hash-table-p"), item), tablePlist(item)));
		branches.add(list(
				list(sym("and"), list(sym("vectorp"), item),
						list(sym("eql"), list(sym("length"), item), new LispInteger(2))),
				list(sym("list"), list(sym("elt"), item, new LispInteger(0)),
						list(sym("elt"), item, new LispInteger(1)))));
		branches.add(list(
				list(sym("and"), list(sym("consp"), item), list(sym("not"), isSetForm(item)),
						list(sym("consp"), list(sym("cdr"), item)), list(sym("null"), list(sym("cddr"), item))),
				list(sym("list"), list(sym("car"), item), list(sym("cadr"), item))));
		branches.add(list(isSetForm(item), membersPlist(item)));
		branches.add(list(TRUE_CONST, list(sym("error"),
				LispString.literal("conj needs a map entry: a map, a [k v] vector or a (k v) list"))));
		return cons(sym("cond"), branches);
	}

	/**
	 * The entries of a set conjoined onto a map, as a plist: each member is itself an
	 * entry, one level deep. A set nested in the set is refused: entries nest one level.
	 */
	private LispVal membersPlist(LispVal item) {
		LispSymbol grown = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal collect = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("setq"), grown, list(sym("append"), grown, memberEntryPlist(key)))),
				setInner(item));
		return list(sym("let"), list(List.of(list(grown, NIL_CONST))), collect, grown);
	}

	/**
	 * One set member's entries as a plist: a map's pairs, a two-vector's or two-list's
	 * pair. Unlike {@link #entryPlist}, this never recurses, so the Java construction
	 * terminates; a set nested in the conjoined set is refused at run time instead.
	 */
	private static LispVal memberEntryPlist(LispVal key) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("hash-table-p"), key), tablePlist(key)));
		branches.add(list(
				list(sym("and"), list(sym("vectorp"), key),
						list(sym("eql"), list(sym("length"), key), new LispInteger(2))),
				list(sym("list"), list(sym("elt"), key, new LispInteger(0)),
						list(sym("elt"), key, new LispInteger(1)))));
		branches.add(list(
				list(sym("and"), list(sym("consp"), key), list(sym("not"), isSetForm(key)),
						list(sym("consp"), list(sym("cdr"), key)), list(sym("null"), list(sym("cddr"), key))),
				list(sym("list"), list(sym("car"), key), list(sym("cadr"), key))));
		branches.add(list(TRUE_CONST, list(sym("error"),
				LispString.literal("conj needs a map entry: a map, a [k v] vector or a (k v) list"))));
		return cons(sym("cond"), branches);
	}

	/** One member added to a set: a fresh table over the old members plus the member. */
	private LispVal setAdd(LispVal coll, LispVal item) {
		LispSymbol table = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal copy = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("setf"), list(sym("gethash"), key, table), key)),
				setInner(coll));
		return list(sym("let"), list(List.of(list(table, makeTable()))), copy,
				list(sym("setf"), list(sym("gethash"), item, table), item), wrapSet(table));
	}

	private LispVal disjOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "disj takes a set and members");
		LispSymbol set = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal copy = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("setf"), list(sym("gethash"), key, table), key)),
				setInner(set));
		List<LispVal> body = new ArrayList<>();
		body.add(list(table, makeTable()));
		LispVal kept = list(sym("let"), list(body), copy, remhashes(items, table), wrapSet(table));
		LispVal needSet = list(sym("error"), LispString.literal("disj needs a set"));
		return list(sym("let"), list(List.of(list(set, lower(items.get(1))))),
				list(sym("if"), set, list(sym("if"), isSetForm(set), kept, needSet), NIL_CONST));
	}

	/**
	 * The {@code remhash} of each of {@code items}' keys from {@code table}, in order.
	 */
	private LispVal remhashes(List<LispVal> items, LispSymbol table) {
		if (items.size() == 2) {
			return table;
		}
		List<LispVal> drops = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			drops.add(list(sym("remhash"), lower(items.get(i)), table));
		}
		drops.add(table);
		return cons(sym("progn"), drops);
	}

	private LispVal setOf(List<LispVal> items) {
		isTrue(items.size() == 2, "set takes one collection");
		LispSymbol coll = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispSymbol one = freshTemp();
		LispSymbol entry = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("vectorp"), coll),
				list(sym("dolist"), list(List.of(one, list(sym("coerce"), coll, quoted("list")))),
						list(sym("setf"), list(sym("gethash"), one, table), one))));
		branches.add(list(list(sym("hash-table-p"), coll),
				list(sym("maphash"),
						list(sym("lambda"), list(List.of(key, val)),
								list(sym("let"), list(List.of(list(entry, list(sym("vector"), key, val)))),
										list(sym("setf"), list(sym("gethash"), entry, table), entry))),
						coll)));
		branches.add(list(isSetForm(coll),
				list(sym("maphash"),
						list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
								list(sym("setf"), list(sym("gethash"), key, table), key)),
						setInner(coll))));
		branches.add(list(TRUE_CONST, list(sym("dolist"), list(List.of(one, coll)),
				list(sym("setf"), list(sym("gethash"), one, table), one))));
		return list(sym("let"), list(List.of(list(coll, lower(items.get(1))), list(table, makeTable()))),
				cons(sym("cond"), branches), wrapSet(table));
	}

	private LispVal mapConstructorOf(List<LispVal> items, String what) {
		isTrue((items.size() - 1) % 2 == 0, what + " takes key/value pairs");
		return mapBuild(lowers(items, 1));
	}

	private LispVal countOf(List<LispVal> items) {
		isTrue(items.size() == 2, "count takes one collection");
		return countForm(lower(items.get(1)));
	}

	/** The count of an already-lowered collection: tables by entries, else length. */
	private LispVal countForm(LispVal lowered) {
		LispSymbol coll = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(coll), list(sym("hash-table-count"), setInner(coll))));
		branches.add(list(list(sym("hash-table-p"), coll), list(sym("hash-table-count"), coll)));
		// the false object counts as empty, like the oracle; anything else takes length
		branches.add(list(list(sym("eq"), coll, this.falseVariable), new LispInteger(0)));
		branches.add(list(TRUE_CONST, list(sym("length"), coll)));
		return list(sym("let"), list(List.of(list(coll, lowered))), cons(sym("cond"), branches));
	}

	private LispVal emptyOf(List<LispVal> items) {
		isTrue(items.size() == 2, "empty? takes one collection");
		return emptyForm(lower(items.get(1)));
	}

	/** Whether an already-lowered collection is empty, answering raw. */
	private LispVal emptyForm(LispVal lowered) {
		LispSymbol coll = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(coll), list(sym("zerop"), list(sym("hash-table-count"), setInner(coll)))));
		branches.add(list(list(sym("hash-table-p"), coll), list(sym("zerop"), list(sym("hash-table-count"), coll))));
		branches.add(list(list(sym("vectorp"), coll), list(sym("zerop"), list(sym("length"), coll))));
		branches.add(list(list(sym("stringp"), coll), list(sym("zerop"), list(sym("length"), coll))));
		branches.add(list(TRUE_CONST, list(sym("null"), coll)));
		return list(sym("let"), list(List.of(list(coll, lowered))), cons(sym("cond"), branches));
	}

	/**
	 * The seq view of an already-lowered collection: a strict LIST, the one sequence
	 * every backend already shares, so no backend learns a representation. Lists pass
	 * through untouched (the list fast path -- no copy); vectors and strings coerce; maps
	 * contribute one two-vector per entry and sets one member per element, both in the
	 * table's walk order, unspecified like the oracle's; nil and the false object are
	 * empty; anything else signals, like the oracle's. The collection runs once, behind a
	 * temporary no user identifier can spell.
	 * @param lowered the lowered collection
	 * @return the form answering the list view
	 */
	private LispVal seqForm(LispVal lowered) {
		LispSymbol coll = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("null"), coll), NIL_CONST));
		branches.add(list(isSetForm(coll), memberList(coll)));
		branches.add(list(list(sym("consp"), coll), coll));
		branches.add(list(list(sym("vectorp"), coll), list(sym("coerce"), coll, quoted("list"))));
		branches.add(list(list(sym("stringp"), coll), list(sym("coerce"), coll, quoted("list"))));
		branches.add(list(list(sym("hash-table-p"), coll), entryList(coll)));
		branches.add(list(list(sym("eq"), coll, this.falseVariable), NIL_CONST));
		branches.add(list(TRUE_CONST, list(sym("error"), LispString.literal("seq needs a collection"))));
		return list(sym("let"), list(List.of(list(coll, lowered))), cons(sym("cond"), branches));
	}

	/** A set's members accumulated into a list, in the table's walk order. */
	private LispVal memberList(LispVal coll) {
		LispSymbol acc = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		return list(sym("let"), list(List.of(list(acc, NIL_CONST))),
				list(sym("maphash"),
						list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
								list(sym("setq"), acc, list(sym("cons"), key, acc))),
						setInner(coll)),
				acc);
	}

	/** A map's entries accumulated into a list of two-vectors, in walk order. */
	private LispVal entryList(LispVal coll) {
		LispSymbol acc = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		return list(sym("let"), list(List.of(list(acc, NIL_CONST))),
				list(sym("maphash"),
						list(sym("lambda"), list(List.of(key, val)),
								list(sym("setq"), acc, list(sym("cons"), list(sym("vector"), key, val), acc))),
						coll),
				acc);
	}

	/**
	 * {@code nth} over any collection: the seq view indexed, past the end the default
	 * (nil without one) instead of the oracle's throw. The collection and the index run
	 * once each.
	 */
	private LispVal nthForm(LispVal coll, LispVal index, LispVal dflt) {
		LispSymbol seq = freshTemp();
		LispSymbol at = freshTemp();
		return list(sym("let"), list(List.of(list(seq, seqForm(coll)), list(at, index))),
				list(sym("if"), list(sym("<"), at, list(sym("length"), seq)), list(sym("nth"), at, seq), dflt));
	}

	private LispVal nthOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 3, "nth takes a collection, an index and an optional default");
		return nthForm(lower(items.get(1)), lower(items.get(2)), n == 3 ? lower(items.get(3)) : NIL_CONST);
	}

	/**
	 * {@code nth} as a value: a lambda with the Clojure argument order, since a bare
	 * {@code #'NTH} would take the index first.
	 */
	private LispVal nthValue() {
		LispSymbol coll = new LispSymbol(mangle("nth-coll"));
		LispSymbol index = new LispSymbol(mangle("nth-index"));
		return list(sym("lambda"), list(List.of(coll, index)), nthForm(coll, index, NIL_CONST));
	}

	/**
	 * {@code quot} as a value: a lambda over the primitive, like {@link #nthValue}.
	 */
	private LispVal quotValue() {
		LispSymbol first = new LispSymbol(mangle("quot-a"));
		LispSymbol second = new LispSymbol(mangle("quot-b"));
		return list(sym("lambda"), list(List.of(first, second)), list(sym("truncate"), first, second));
	}

	/**
	 * A builtin as a function value: a lambda with the Clojure argument order and the
	 * Clojure coercions, so {@code (map inc ...)} runs what a call would run. Only the
	 * builtins whose call lowering is more than a direct Common Lisp call need an entry
	 * here -- a direct call's {@code #'name} in {@link #builtinValue} already answers the
	 * same function. Null when the name has no value form.
	 * @param name the Clojure name
	 * @return the lambda, or null
	 */
	private @Nullable LispVal valueOf(String name) {
		return switch (name) {
			case "inc", "dec" -> incValue(name);
			case "str" -> strValue();
			case "pr-str" -> prStrValue();
			case "seq" -> seqValue();
			case "first" -> firstValue();
			case "rest", "next" -> restValue();
			case "cons" -> consValue();
			case "count" -> countValue();
			case "empty?" -> emptyValue();
			case "map" -> mapValue();
			case "filter" -> filterValue();
			case "reduce" -> reduceValue();
			case "concat" -> concatValue();
			case "take" -> takeValue();
			case "drop" -> dropValue();
			case "range" -> rangeValue();
			case "dorun" -> dorunValue();
			case "doall" -> doallValue();
			case "subs" -> subsValue();
			case "atom", "volatile!" -> atomValue();
			case "deref" -> derefValue();
			case "swap!", "vswap!" -> swapValue();
			case "reset!", "vreset!" -> resetValue();
			case "compare-and-set!" -> compareAndSetValue();
			case "ex-data" -> exHelperValue("C%E-DATA");
			case "ex-message" -> exHelperValue("C%E-MESSAGE");
			case "ex-info" -> exInfoValue();
			case "macroexpand-1" -> macroexpandValue(MACROEXPAND_1);
			case "macroexpand" -> macroexpandValue(MACROEXPAND);
			default -> null;
		};
	}

	/** {@code inc}/{@code dec} as a value: a one-argument lambda over the primitive. */
	private LispVal incValue(String name) {
		LispSymbol x = new LispSymbol(mangle("inc-x"));
		return list(sym("lambda"), list(x), list(sym(name.equals("inc") ? "+" : "-"), x, new LispInteger(1)));
	}

	/**
	 * {@code str} as a value: over any number of arguments, each converted like a
	 * {@code str} part ({@code ""} for {@code nil}) and concatenated. The parts map over
	 * the rest list and spread back through {@code apply}.
	 */
	private LispVal strValue() {
		LispSymbol args = new LispSymbol(mangle("str-args"));
		LispSymbol one = new LispSymbol(mangle("str-one"));
		LispVal oneFn = list(sym("lambda"), list(one), strOf(one, LispString.literal(""), NIL_CONST));
		return list(sym("lambda"), list(AMPERSAND_REST, args),
				list(sym("apply"), list(sym("function"), sym("concatenate")), list(sym("quote"), sym("string")),
						list(sym("mapcar"), oneFn, args)));
	}

	/**
	 * {@code pr-str} as a value: like {@code str} as a value, but every part converts
	 * readably, {@code nil} prints as {@code "nil"}, and the parts join with a single
	 * space (like {@code pr}).
	 */
	private LispVal prStrValue() {
		LispSymbol args = new LispSymbol(mangle("pr-str-args"));
		LispSymbol one = new LispSymbol(mangle("pr-str-one"));
		LispVal oneFn = list(sym("lambda"), list(one), strOf(one, LispString.literal("nil"), TRUE_CONST));
		LispVal strings = list(sym("mapcar"), oneFn, args);
		LispVal interposed = list(sym("cdr"), list(sym("mapcan"),
				list(sym("lambda"), list(one), list(sym("list"), LispString.literal(" "), one)), strings));
		return list(sym("lambda"), list(AMPERSAND_REST, args), list(sym("apply"),
				list(sym("function"), sym("concatenate")), list(sym("quote"), sym("string")), interposed));
	}

	/** {@code seq} as a value: the seq view as a one-argument lambda. */
	private LispVal seqValue() {
		LispSymbol coll = new LispSymbol(mangle("seq-coll"));
		return list(sym("lambda"), list(coll), seqForm(coll));
	}

	/** {@code first} as a value: the head of the seq view. */
	private LispVal firstValue() {
		LispSymbol coll = new LispSymbol(mangle("first-coll"));
		return list(sym("lambda"), list(coll), list(sym("car"), seqForm(coll)));
	}

	/** {@code rest}/{@code next} as a value: the tail of the seq view. */
	private LispVal restValue() {
		LispSymbol coll = new LispSymbol(mangle("rest-coll"));
		return list(sym("lambda"), list(coll), list(sym("cdr"), seqForm(coll)));
	}

	/** {@code cons} as a value: the item over the seq view. */
	private LispVal consValue() {
		LispSymbol item = new LispSymbol(mangle("cons-item"));
		LispSymbol coll = new LispSymbol(mangle("cons-coll"));
		return list(sym("lambda"), list(List.of(item, coll)), list(sym("cons"), item, seqForm(coll)));
	}

	/** {@code count} as a value: the table-aware count. */
	private LispVal countValue() {
		LispSymbol coll = new LispSymbol(mangle("count-coll"));
		return list(sym("lambda"), list(coll), countForm(coll));
	}

	/** {@code empty?} as a value: the table-aware emptiness test, answering raw. */
	private LispVal emptyValue() {
		LispSymbol coll = new LispSymbol(mangle("empty-coll"));
		return list(sym("lambda"), list(coll), emptyForm(coll));
	}

	/** {@code map} as a value: {@code mapcar} over the seq view. */
	private LispVal mapValue() {
		LispSymbol fn = new LispSymbol(mangle("map-fn"));
		LispSymbol coll = new LispSymbol(mangle("map-coll"));
		return list(sym("lambda"), list(List.of(fn, coll)), list(sym("mapcar"), fn, seqForm(coll)));
	}

	/** {@code filter} as a value: {@code remove-if-not} over the seq view. */
	private LispVal filterValue() {
		LispSymbol pred = new LispSymbol(mangle("filter-pred"));
		LispSymbol coll = new LispSymbol(mangle("filter-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), list(sym("remove-if-not"), pred, seqForm(coll)));
	}

	/**
	 * {@code reduce} as a value: over a function and a collection, or a function, a value
	 * and a collection -- the two call shapes, dispatched on the rest count. Any other
	 * count signals, like a call's arity refusal.
	 */
	private LispVal reduceValue() {
		LispSymbol fn = new LispSymbol(mangle("reduce-fn"));
		LispSymbol args = new LispSymbol(mangle("reduce-args"));
		LispVal two = list(sym("reduce"), fn, seqForm(list(sym("car"), args)));
		LispVal three = list(sym("reduce"), fn, seqForm(list(sym("car"), list(sym("cdr"), args))),
				sym(":initial-value"), list(sym("car"), args));
		LispVal arity = list(sym("error"),
				LispString.literal("reduce takes a function, an optional value and a collection"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), two),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), three), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(List.of(fn, AMPERSAND_REST, args)), body);
	}

	/** {@code concat} as a value: every argument's seq view appended. */
	private LispVal concatValue() {
		LispSymbol colls = new LispSymbol(mangle("concat-colls"));
		return list(sym("lambda"), list(AMPERSAND_REST, colls),
				list(sym("apply"), list(sym("function"), sym("append")), list(sym("mapcar"), seqValue(), colls)));
	}

	/** {@code take} as a value: the strict prefix over the seq view. */
	private LispVal takeValue() {
		LispSymbol count = new LispSymbol(mangle("take-count"));
		LispSymbol coll = new LispSymbol(mangle("take-coll"));
		return list(sym("lambda"), list(List.of(count, coll)), takeForm(count, seqForm(coll)));
	}

	/** {@code drop} as a value: the seq view past the strict prefix. */
	private LispVal dropValue() {
		LispSymbol count = new LispSymbol(mangle("drop-count"));
		LispSymbol coll = new LispSymbol(mangle("drop-coll"));
		return list(sym("lambda"), list(List.of(count, coll)), dropForm(count, seqForm(coll)));
	}

	/**
	 * {@code range} as a value: the one-, two- and three-argument call shapes over the
	 * rest list. Any other count signals, like a call's arity refusal.
	 */
	private LispVal rangeValue() {
		LispSymbol args = new LispSymbol(mangle("range-args"));
		LispVal second = list(sym("car"), list(sym("cdr"), args));
		LispVal third = list(sym("car"), list(sym("cdr"), list(sym("cdr"), args)));
		LispVal one = rangeForm(new LispInteger(0), list(sym("car"), args), new LispInteger(1));
		LispVal two = rangeForm(list(sym("car"), args), second, new LispInteger(1));
		LispVal three = rangeForm(list(sym("car"), args), second, third);
		LispVal arity = list(sym("error"),
				LispString.literal("range takes an end, or a start, an end and an optional step"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), one),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), two),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), list(sym("cdr"), args)))), three),
				list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/**
	 * {@code apply} over any leading arguments: each but the last passes through, the
	 * last answers its seq view -- CL {@code apply}'s own shape, so
	 * {@code (apply f x args)} spreads like the oracle's.
	 */
	private LispVal applyOf(List<LispVal> items) {
		isTrue(items.size() >= 3, "apply takes a function and an argument list");
		List<LispVal> out = new ArrayList<>();
		out.add(sym("apply"));
		out.add(fnValue(items.get(1)));
		for (int i = 2; i < items.size() - 1; i++) {
			out.add(lower(items.get(i)));
		}
		out.add(seqForm(lower(items.get(items.size() - 1))));
		return list(out);
	}

	/** {@code take}: the first {@code n} of the seq view, strictly. */
	private LispVal takeOf(List<LispVal> items) {
		isTrue(items.size() == 3, "take takes a count and a collection");
		return takeForm(lower(items.get(1)), seqForm(lower(items.get(2))));
	}

	/**
	 * The first {@code count} of an already-lowered seq view, strictly: a labels self
	 * call accumulating in reverse. Both arrive bound (a temporary, a lambda parameter),
	 * so the walk names them more than once.
	 */
	private LispVal takeForm(LispVal count, LispVal seqView) {
		String name = mangle("take-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol left = freshTemp();
		LispSymbol seq = freshTemp();
		LispSymbol walked = freshTemp();
		LispSymbol cell = freshTemp();
		LispSymbol grown = freshTemp();
		LispVal done = list(sym("reverse"), grown);
		LispVal more = list(self, list(sym("-"), walked, new LispInteger(1)), list(sym("cdr"), cell),
				list(sym("cons"), list(sym("car"), cell), grown));
		LispVal step = list(sym("if"),
				list(sym("or"), list(sym("<="), walked, new LispInteger(0)), list(sym("null"), cell)), done, more);
		LispVal binding = new LispCons(self, new LispCons(list(List.of(walked, cell, grown)), cons(step, List.of())));
		return list(sym("let"), list(List.of(list(left, count), list(seq, seqView))),
				list(sym("labels"), list(List.of(binding)), list(self, left, seq, NIL_CONST)));
	}

	/** {@code drop}: the seq view past the first {@code n}, strictly. */
	private LispVal dropOf(List<LispVal> items) {
		isTrue(items.size() == 3, "drop takes a count and a collection");
		return dropForm(lower(items.get(1)), seqForm(lower(items.get(2))));
	}

	/** The already-lowered seq view past the first {@code count}. */
	private LispVal dropForm(LispVal count, LispVal seqView) {
		LispSymbol left = freshTemp();
		LispSymbol seq = freshTemp();
		return list(sym("let"), list(List.of(list(left, count), list(seq, seqView))),
				list(sym("if"), list(sym("<="), left, new LispInteger(0)), seq, list(sym("nthcdr"), left, seq)));
	}

	/**
	 * {@code (-> x form...)}: each step with the value inserted second (a bare name calls
	 * with it, a keyword step reads through it); a pure datum rewrite.
	 */
	private LispVal threadFirst(List<LispVal> items) {
		isTrue(items.size() >= 2, "-> takes a value and forms to thread it through");
		LispVal acc = items.get(1);
		for (int i = 2; i < items.size(); i++) {
			acc = threadInsert(items.get(i), acc, false);
		}
		return lower(acc);
	}

	/**
	 * {@code (->> x form...)}: each step with the value appended last; a pure datum
	 * rewrite like {@link #threadFirst}.
	 */
	private LispVal threadLast(List<LispVal> items) {
		isTrue(items.size() >= 2, "->> takes a value and forms to thread it through");
		LispVal acc = items.get(1);
		for (int i = 2; i < items.size(); i++) {
			acc = threadInsert(items.get(i), acc, true);
		}
		return lower(acc);
	}

	/**
	 * One threading step around the threaded datum: a proper list not headed by a reader
	 * marker takes the value second (first) or last; anything else -- a bare name, a
	 * keyword, a literal -- calls or reads with it. A list headed by another list inserts
	 * blindly, like the oracle's purely syntactic rule; a marker-headed literal cannot
	 * take the value and signals when called.
	 */
	private static LispVal threadInsert(LispVal form, LispVal acc, boolean last) {
		List<LispVal> parts = items(form);
		if (parts != null && !parts.isEmpty() && !isThreadAtomHead(parts.get(0))) {
			List<LispVal> out = new ArrayList<>();
			out.add(parts.get(0));
			if (!last) {
				out.add(acc);
			}
			out.addAll(parts.subList(1, parts.size()));
			if (last) {
				out.add(acc);
			}
			return list(out);
		}
		return list(List.of(form, acc));
	}

	/** A threading step head that must not be inserted into: a reader marker. */
	private static boolean isThreadAtomHead(LispVal head) {
		return head instanceof LispSymbol s && s.name().startsWith("%");
	}

	/**
	 * {@code (as-> x name form...)}: nested {@code let}s rebinding the name step by step,
	 * so shadowing matches the oracle exactly.
	 */
	private LispVal threadAs(List<LispVal> items) {
		isTrue(items.size() >= 3, "as-> takes a value, a name and forms");
		String name = plainName(items.get(2), "as->");
		if (items.size() == 3) {
			return lower(items.get(1));
		}
		LispVal acc = items.get(items.size() - 1);
		for (int i = items.size() - 2; i >= 3; i--) {
			acc = letDatum(name, items.get(i), acc);
		}
		return lower(letDatum(name, items.get(1), acc));
	}

	/** The datum {@code (let [name init] body)}. */
	private static LispVal letDatum(String name, LispVal init, LispVal body) {
		return list(List.of(new LispSymbol("let"),
				new LispCons(ClojureReader.VECTOR, list(List.of(new LispSymbol(name), init))), body));
	}

	/**
	 * {@code (doto x form...)}: each step threaded first around one temporary, answering
	 * the original value.
	 */
	private LispVal dotoOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "doto takes a value and forms");
		Map<String, Kind> scope = new HashMap<>();
		String temp = "doto-" + this.counter++;
		scope.put(temp, Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return inScope(scope, () -> {
			List<LispVal> form = new ArrayList<>();
			form.add(sym("let"));
			form.add(list(List.of(list(idSym(temp), lower(items.get(1))))));
			for (int i = 2; i < items.size(); i++) {
				form.add(lower(threadInsert(items.get(i), ref, false)));
			}
			form.add(idSym(temp));
			return list(form);
		});
	}

	/**
	 * {@code (cond-> x test form...)} (or {@code cond->>} threading last): each pair
	 * rebinds one temporary to the running value and threads only when its test is
	 * truthy.
	 */
	private LispVal condThread(List<LispVal> items, boolean last) {
		String arrow = last ? "cond->>" : "cond->";
		isTrue(items.size() >= 2 && items.size() % 2 == 0, arrow + " takes a value and test/form pairs");
		Map<String, Kind> scope = new HashMap<>();
		String temp = "condthread-" + this.counter++;
		scope.put(temp, Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return inScope(scope, () -> {
			LispVal acc = items.get(1);
			for (int i = 2; i + 1 < items.size(); i += 2) {
				acc = letDatum(temp, acc, list(
						List.of(new LispSymbol("if"), items.get(i), threadInsert(items.get(i + 1), ref, last), ref)));
			}
			return lower(acc);
		});
	}

	/**
	 * {@code (some-> x form...)} (or {@code some->>} threading last): each step threaded
	 * around one temporary, short-circuiting to nil when it is nil -- but not when it is
	 * false, like the oracle.
	 */
	private LispVal someThread(List<LispVal> items, boolean last) {
		String arrow = last ? "some->>" : "some->";
		isTrue(items.size() >= 2, arrow + " takes a value and forms");
		Map<String, Kind> scope = new HashMap<>();
		String temp = "somethread-" + this.counter++;
		scope.put(temp, Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return inScope(scope, () -> {
			LispVal acc = items.get(1);
			for (int i = 2; i < items.size(); i++) {
				acc = letDatum(temp, acc, list(List.of(new LispSymbol("if"), list(List.of(new LispSymbol("nil?"), ref)),
						LispNil.INSTANCE, threadInsert(items.get(i), ref, last))));
			}
			return lower(acc);
		});
	}

	/**
	 * {@code list*}: a right fold of {@code cons} over the seq view -- of one argument,
	 * just its seq, signalling for a non-collection like the oracle.
	 */
	private LispVal listStar(List<LispVal> items) {
		isTrue(items.size() >= 2, "list* takes a value and more collections");
		if (items.size() == 2) {
			return seqForm(lower(items.get(1)));
		}
		LispVal acc = lower(items.get(items.size() - 1));
		for (int i = items.size() - 2; i >= 1; i--) {
			acc = consForm(lower(items.get(i)), acc);
		}
		return acc;
	}

	private LispVal consForm(LispVal item, LispVal coll) {
		return list(sym("cons"), item, seqForm(coll));
	}

	/**
	 * One level of a {@code doseq}/{@code for} binding vector: a pattern over a
	 * collection plus the {@code :when}/{@code :while}/{@code :let} modifiers that follow
	 * it, in order.
	 */
	private record SeqLevel(LispVal pattern, LispVal coll, List<SeqModifier> modifiers) {
	}

	/** One {@code :when}/{@code :while}/{@code :let} modifier and its datum. */
	private record SeqModifier(String kind, LispVal datum) {
	}

	/**
	 * {@code doseq}: side-effecting iteration over the seq view, answering nil. One
	 * {@code dolist} per binding pair (which the macro expander already shares with every
	 * backend), nested left to right; patterns destructure through the same {@code let}
	 * lowering; {@code :when} skips the element, {@code :while} ends its level's loop
	 * through a block (an outer level's ends the whole form), {@code :let} binds
	 * sequentially. An empty binding vector runs the body once.
	 */
	private LispVal doseqOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "doseq takes a binding vector and a body");
		List<SeqLevel> levels = seqLevels(bindingItems(items.get(1), "doseq"), "doseq");
		Map<String, Kind> scope = new HashMap<>();
		for (SeqLevel level : levels) {
			collectSeqNames(level, scope);
		}
		return inScope(scope, () -> {
			LispVal inner = body(items, 2);
			for (int i = levels.size() - 1; i >= 0; i--) {
				inner = seqLevel(levels.get(i), inner, scope, "doseq");
			}
			return list(sym("progn"), inner, NIL_CONST);
		});
	}

	/**
	 * {@code dotimes}: one strict binding over the integers below the count, answering
	 * nil -- the core {@code dotimes} the macro expander already shares. The count runs
	 * through {@code truncate} first, the oracle's {@code intCast} cast in lowering form:
	 * a float counts its truncation ({@code 2.5} runs {@code 0 1}), and a non-number
	 * signals there instead of in the loop's comparison.
	 */
	private LispVal dotimesOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "dotimes takes a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "dotimes");
		isTrue(bindings.size() == 2, "dotimes takes exactly one name and count");
		String name = plainName(bindings.get(0), "dotimes");
		Map<String, Kind> scope = new HashMap<>();
		scope.put(name, Kind.VARIABLE);
		LispVal count = list(sym("truncate"), lower(bindings.get(1)));
		return inScope(scope, () -> list(sym("dotimes"), list(List.of(idSym(name), count)), body(items, 2)));
	}

	/**
	 * {@code for}: a strict list comprehension over the seq view -- nested {@code dolist}
	 * loops accumulating in reverse, like {@code take}'s labels walk, so no backend
	 * learns a representation. Modifiers behave per level, left to right: {@code :when}
	 * skips the element, {@code :while} ends its level's loop (an outer level's ends the
	 * whole comprehension), {@code :let} binds sequentially. Answers the strict list,
	 * {@code nil} when empty (the {@code rest}/{@code take} divergence, not {@code ()}).
	 */
	private LispVal forOf(List<LispVal> items) {
		isTrue(items.size() == 3, "for takes a binding vector and a body");
		List<SeqLevel> levels = seqLevels(bindingItems(items.get(1), "for"), "for");
		isTrue(!levels.isEmpty(), "for takes at least one binding pair");
		Map<String, Kind> scope = new HashMap<>();
		for (SeqLevel level : levels) {
			collectSeqNames(level, scope);
		}
		return inScope(scope, () -> {
			LispSymbol acc = freshTemp();
			LispVal inner = list(sym("setq"), acc, list(sym("cons"), lower(items.get(2)), acc));
			for (int i = levels.size() - 1; i >= 0; i--) {
				inner = seqLevel(levels.get(i), inner, scope, "for");
			}
			return list(sym("let"), list(List.of(list(acc, NIL_CONST))), inner, list(sym("reverse"), acc));
		});
	}

	/**
	 * The levels of a {@code doseq}/{@code for} binding vector: pattern/collection pairs,
	 * each trailed by its {@code :when}/{@code :while}/{@code :let} modifiers in order.
	 * Any other keyword is the oracle's {@code Invalid ... keyword} refusal.
	 */
	private static List<SeqLevel> seqLevels(List<LispVal> bindings, String owner) {
		List<SeqLevel> levels = new ArrayList<>();
		int i = 0;
		while (i < bindings.size()) {
			LispVal head = bindings.get(i);
			if (head instanceof LispSymbol keyword && keyword.name().startsWith(":")) {
				if (keyword.name().equals(":when") || keyword.name().equals(":while")
						|| keyword.name().equals(":let")) {
					throw new LispReadException(
							"Invalid '" + owner + "' keyword " + keyword.name() + " without a binding before it");
				}
				throw new LispReadException("Invalid '" + owner + "' keyword " + keyword.name());
			}
			isTrue(i + 1 < bindings.size(), "a " + owner + " binding vector pairs a name with a value");
			LispVal pattern = head;
			LispVal coll = bindings.get(i + 1);
			i += 2;
			List<SeqModifier> modifiers = new ArrayList<>();
			while (i < bindings.size() && bindings.get(i) instanceof LispSymbol trailer
					&& trailer.name().startsWith(":")) {
				String kind = trailer.name();
				isTrue(kind.equals(":when") || kind.equals(":while") || kind.equals(":let"),
						"Invalid '" + owner + "' keyword " + kind);
				isTrue(i + 1 < bindings.size(), owner + " " + kind + " takes a form after it");
				modifiers.add(new SeqModifier(kind, bindings.get(i + 1)));
				i += 2;
			}
			levels.add(new SeqLevel(pattern, coll, List.copyOf(modifiers)));
		}
		return levels;
	}

	/**
	 * One binding level wrapped around its inner content: the collection's seq view
	 * iterated by {@code dolist} (patterns through the {@code let} destructuring), the
	 * level's modifiers applied in order around the content. A {@code :while} ends the
	 * level's own loop through a block, so an outer level's ends the whole
	 * {@code doseq}/{@code for} while an inner one's lets the outer loops continue, like
	 * the oracle's.
	 */
	private LispVal seqLevel(SeqLevel level, LispVal inner, Map<String, Kind> scope, String owner) {
		LispVal seq = seqForm(lower(level.coll()));
		LispVal wrap = inner;
		LispSymbol whileBlock = null;
		for (int m = level.modifiers().size() - 1; m >= 0; m--) {
			SeqModifier modifier = level.modifiers().get(m);
			switch (modifier.kind()) {
				case ":when" -> wrap = ifFalsey(lower(modifier.datum()), wrap, NIL_CONST);
				case ":while" -> {
					if (whileBlock == null) {
						whileBlock = freshTemp();
					}
					LispSymbol stop = whileBlock;
					wrap = ifFalsey(lower(modifier.datum()), wrap, list(sym("return-from"), stop, NIL_CONST));
				}
				case ":let" -> wrap = seqLetOf(modifier.datum(), wrap, scope, owner);
				default -> throw new LispReadException("Invalid '" + owner + "' keyword " + modifier.kind());
			}
		}
		LispVal loopForm = dolistOf(level.pattern(), seq, wrap, scope, owner);
		if (whileBlock != null) {
			return list(sym("block"), whileBlock, loopForm);
		}
		return loopForm;
	}

	/**
	 * One {@code dolist} over an already-lowered seq view: a plain name binds the element
	 * directly, a pattern through the {@code let} destructuring over a temporary.
	 */
	private LispVal dolistOf(LispVal pattern, LispVal seq, LispVal wrap, Map<String, Kind> scope, String owner) {
		if (pattern instanceof LispSymbol) {
			String name = plainName(pattern, owner);
			return list(sym("dolist"), list(List.of(idSym(name), seq)), wrap);
		}
		LispSymbol temp = freshTemp();
		List<LispVal> pairs = new ArrayList<>();
		destructureInto(pattern, temp, pairs, scope, owner);
		if (pairs.isEmpty()) {
			return list(sym("dolist"), list(List.of(temp, seq)), wrap);
		}
		return list(sym("dolist"), list(List.of(temp, seq)), list(sym("let*"), list(pairs), wrap));
	}

	/**
	 * A {@code :let} modifier's binding vector around its level's content: sequential
	 * pairs through the {@code let} destructuring, like {@code let} itself.
	 */
	private LispVal seqLetOf(LispVal letVector, LispVal wrap, Map<String, Kind> scope, String owner) {
		List<LispVal> bindings = bindingItems(letVector, owner + " :let");
		isTrue(bindings.size() % 2 == 0, "a " + owner + " :let vector pairs a name with a value");
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.size(); i += 2) {
			LispVal pattern = bindings.get(i);
			if (pattern instanceof LispSymbol) {
				String name = plainName(pattern, owner + " :let");
				pairs.add(list(idSym(name), lower(bindings.get(i + 1))));
				scope.put(name, Kind.VARIABLE);
				continue;
			}
			LispSymbol temp = freshTemp();
			pairs.add(list(temp, lower(bindings.get(i + 1))));
			destructureInto(pattern, temp, pairs, scope, owner + " :let");
		}
		if (pairs.isEmpty()) {
			return wrap;
		}
		return list(sym("let*"), list(pairs), wrap);
	}

	/**
	 * Every name a {@code doseq}/{@code for} level binds, registered before anything
	 * lowers: a later collection (or the body) may use an earlier binding, like
	 * {@code let*}'s sequential scope. Lenient by design -- anything malformed stays for
	 * the lowering to refuse with the {@code let} shape.
	 */
	private static void collectSeqNames(SeqLevel level, Map<String, Kind> scope) {
		collectPatternNames(level.pattern(), scope);
		for (SeqModifier modifier : level.modifiers()) {
			if (modifier.kind().equals(":let")) {
				collectLetNames(modifier.datum(), scope);
			}
		}
	}

	/**
	 * The names a binding pattern binds, without lowering: a plain name binds directly, a
	 * vector positionally ({@code &} the rest, {@code :as} the whole), a map through
	 * {@code :keys}/{@code :syms}/{@code :strs}, explicit locals, {@code :as} and nested
	 * patterns -- mirroring {@code destructureInto}, which still owns every refusal.
	 */
	private static void collectPatternNames(LispVal pattern, Map<String, Kind> scope) {
		if (pattern instanceof LispSymbol name) {
			if (!name.name().startsWith(":") && !name.name().equals("&")) {
				scope.put(name.name(), Kind.VARIABLE);
			}
			return;
		}
		List<LispVal> elements = items(pattern);
		if (elements == null || elements.isEmpty()) {
			return;
		}
		if (elements.get(0) == ClojureReader.VECTOR) {
			List<LispVal> rest = elements.subList(1, elements.size());
			for (int i = 0; i < rest.size(); i++) {
				LispVal element = rest.get(i);
				if (isSymbolNamed(element, ":as")) {
					if (i + 1 < rest.size() && rest.get(i + 1) instanceof LispSymbol named
							&& !named.name().startsWith(":") && !named.name().equals("&")) {
						scope.put(named.name(), Kind.VARIABLE);
					}
					i++;
					continue;
				}
				if (isSymbolNamed(element, "&")) {
					if (i + 1 < rest.size()) {
						collectPatternNames(rest.get(i + 1), scope);
					}
					break;
				}
				collectPatternNames(element, scope);
			}
			return;
		}
		if (isSymbolNamed(elements.get(0), "%hash-map")) {
			List<LispVal> entries = elements.subList(1, elements.size());
			for (int i = 0; i + 1 < entries.size(); i += 2) {
				LispVal head = entries.get(i);
				LispVal arg = entries.get(i + 1);
				if (isSymbolNamed(head, ":or")) {
					continue;
				}
				if (isSymbolNamed(head, ":as")) {
					if (arg instanceof LispSymbol named && !named.name().startsWith(":") && !named.name().equals("&")) {
						scope.put(named.name(), Kind.VARIABLE);
					}
					continue;
				}
				if (head instanceof LispSymbol kind && (kind.name().equals(":keys") || kind.name().equals(":syms")
						|| kind.name().equals(":strs"))) {
					collectKeyNames(kind.name(), arg, scope);
					continue;
				}
				if (head instanceof LispSymbol named && !named.name().startsWith(":")) {
					scope.put(named.name(), Kind.VARIABLE);
					continue;
				}
				collectPatternNames(head, scope);
			}
		}
	}

	/**
	 * The locals one {@code :keys}/{@code :syms}/{@code :strs} directive binds: each
	 * entry its local (a {@code :keys} entry may qualify, binding the short name) --
	 * mirroring {@code bindKeys}.
	 */
	private static void collectKeyNames(String kind, LispVal names, Map<String, Kind> scope) {
		List<LispVal> elements = items(names);
		if (elements == null || elements.isEmpty() || elements.get(0) != ClojureReader.VECTOR) {
			return;
		}
		for (LispVal element : elements.subList(1, elements.size())) {
			if (element instanceof LispSymbol spelled && !spelled.name().startsWith(":")
					&& !spelled.name().equals("&")) {
				String local = spelled.name();
				if ((kind.equals(":keys") || kind.equals(":syms")) && local.lastIndexOf('/') >= 0) {
					local = local.substring(local.lastIndexOf('/') + 1);
					if (local.isEmpty()) {
						continue;
					}
				}
				scope.put(local, Kind.VARIABLE);
			}
		}
	}

	/** The names a {@code :let} modifier's binding vector binds, without lowering. */
	private static void collectLetNames(LispVal letVector, Map<String, Kind> scope) {
		List<LispVal> found = items(letVector);
		if (found == null || found.isEmpty() || found.get(0) != ClojureReader.VECTOR) {
			return;
		}
		List<LispVal> bindings = found.subList(1, found.size());
		for (int i = 0; i + 1 < bindings.size(); i += 2) {
			collectPatternNames(bindings.get(i), scope);
		}
	}

	/**
	 * {@code dorun}: the strict companion of {@code doseq} -- seqs are already strict
	 * lists here, so realizing one is evaluating it; answers nil.
	 */
	private LispVal dorunOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "dorun takes a collection and an optional count");
		if (n == 1) {
			return list(sym("progn"), lower(items.get(1)), NIL_CONST);
		}
		return list(sym("progn"), lower(items.get(1)), lower(items.get(2)), NIL_CONST);
	}

	/**
	 * {@code doall}: like {@code dorun}, but answers the collection itself (never
	 * coerced: a vector stays a vector, like the oracle's).
	 */
	private LispVal doallOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "doall takes a collection and an optional count");
		if (n == 1) {
			return lower(items.get(1));
		}
		return list(sym("progn"), lower(items.get(1)), lower(items.get(2)));
	}

	/**
	 * {@code dorun} as a value: over one collection (or a count and a collection),
	 * answering nil; any other count signals, like a call's arity refusal.
	 */
	private LispVal dorunValue() {
		LispSymbol args = new LispSymbol(mangle("dorun-args"));
		LispVal arity = list(sym("error"), LispString.literal("dorun takes a collection and an optional count"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), NIL_CONST),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), NIL_CONST), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/**
	 * {@code doall} as a value: over one collection (or a count and a collection),
	 * answering the collection; any other count signals.
	 */
	private LispVal doallValue() {
		LispSymbol args = new LispSymbol(mangle("doall-args"));
		LispVal arity = list(sym("error"), LispString.literal("doall takes a collection and an optional count"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), list(sym("car"), args)),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), list(sym("cadr"), args)),
				list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/**
	 * {@code range} with an end: the strict list, built by a labels self call. Without an
	 * end there is no strict lowering -- an infinite seq cannot be spelled -- so it is
	 * refused by name, like {@code lazy-seq}. A zero step signals at run time.
	 */
	private LispVal rangeOf(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			throw new LispReadException("infinite range is not supported: range needs an end");
		}
		isTrue(n >= 1 && n <= 3, "range takes an end, or a start, an end and an optional step");
		LispVal start = n >= 2 ? lower(items.get(1)) : new LispInteger(0);
		LispVal end = n >= 2 ? lower(items.get(2)) : lower(items.get(1));
		LispVal step = n == 3 ? lower(items.get(3)) : new LispInteger(1);
		return rangeForm(start, end, step);
	}

	/** The strict list from {@code start} below {@code end} stepping by {@code step}. */
	private LispVal rangeForm(LispVal start, LispVal end, LispVal step) {
		String name = mangle("range-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol from = freshTemp();
		LispSymbol to = freshTemp();
		LispSymbol by = freshTemp();
		LispSymbol at = freshTemp();
		LispSymbol grown = freshTemp();
		LispVal pastEnd = list(sym("if"), list(sym("plusp"), by), list(sym(">="), at, to), list(sym("<="), at, to));
		LispVal advance = list(self, list(sym("+"), at, by), list(sym("cons"), at, grown));
		LispVal body = list(sym("if"), pastEnd, list(sym("reverse"), grown), advance);
		LispVal binding = new LispCons(self, new LispCons(list(List.of(at, grown)), cons(body, List.of())));
		return list(sym("let"), list(List.of(list(from, start), list(to, end), list(by, step))),
				list(sym("if"), list(sym("zerop"), by),
						list(sym("error"), LispString.literal("range step cannot be zero")),
						list(sym("labels"), list(List.of(binding)), list(self, from, NIL_CONST))));
	}

	/**
	 * {@code =} over any arity: pairs of neighbours compared with the map- and set-aware
	 * two-form below, {@code AND}ed. Zero arguments is true; one evaluates its argument
	 * and is true. The answer is raw ({@code T} or {@code NIL}); the call sites wrap it
	 * in {@link #booleanAnswer} for the Clojure {@code T}-or-false.
	 */
	private LispVal equalityRaw(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return TRUE_CONST;
		}
		if (n == 1) {
			return list(sym("progn"), lower(items.get(1)), TRUE_CONST);
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> names = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, lower(items.get(i))));
			names.add(one);
		}
		if (n == 2) {
			return list(sym("let"), list(bindings), equalityTwo(names.get(0), names.get(1)));
		}
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i + 1 < names.size(); i++) {
			pairs.add(equalityTwo(names.get(i), names.get(i + 1)));
		}
		return list(sym("let"), list(bindings), cons(sym("and"), pairs));
	}

	/**
	 * Two values compared the Clojure way: two wrapped sets by membership both ways
	 * (order-free, deep in the members), two tables entry by entry (deep in the values),
	 * anything else with {@code equal}. The comparison is a labels self call, so nested
	 * maps and sets compare all the way down.
	 */
	private LispVal equalityTwo(LispVal first, LispVal second) {
		LispSymbol eq = freshTemp();
		LispSymbol left = freshTemp();
		LispSymbol right = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("and"), isSetForm(left), isSetForm(right)), setEquality(left, right, eq)));
		branches.add(list(list(sym("and"), list(sym("hash-table-p"), left), list(sym("hash-table-p"), right)),
				mapEquality(left, right, eq)));
		branches.add(list(TRUE_CONST, list(sym("equal"), left, right)));
		LispVal test = cons(sym("cond"), branches);
		LispVal binding = new LispCons(eq, new LispCons(list(List.of(left, right)), cons(test, List.of())));
		return list(sym("labels"), list(List.of(binding)), new LispCons(eq, list(List.of(first, second))));
	}

	/** Two tables are equal when they hold the same count and every entry agrees. */
	private LispVal mapEquality(LispVal left, LispVal right, LispVal eq) {
		LispSymbol ok = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal walk = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)),
						list(sym("when"),
								list(sym("or"), list(sym("eq"), list(sym("gethash"), key, right, miss), miss),
										list(sym("not"), list(eq, val, list(sym("gethash"), key, right)))),
								list(sym("setq"), ok, NIL_CONST))),
				left);
		return list(sym("and"),
				list(sym("eql"), list(sym("hash-table-count"), left), list(sym("hash-table-count"), right)),
				list(sym("let"), list(List.of(list(ok, TRUE_CONST), list(miss, list(sym("list"), NIL_CONST)))), walk,
						ok));
	}

	/**
	 * Two wrapped sets are equal when they hold the same count and every member agrees.
	 */
	private LispVal setEquality(LispVal left, LispVal right, LispVal eq) {
		LispSymbol ok = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol found = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal walk = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("when"),
								list(sym("or"), list(sym("eq"), list(sym("gethash"), key, found, miss), miss),
										list(sym("not"), list(eq, key, list(sym("gethash"), key, found)))),
								list(sym("setq"), ok, NIL_CONST))),
				setInner(left));
		return list(sym("and"),
				list(sym("eql"), list(sym("hash-table-count"), setInner(left)),
						list(sym("hash-table-count"), setInner(right))),
				list(sym("let"), list(List.of(list(ok, TRUE_CONST), list(miss, list(sym("list"), NIL_CONST)),
						list(found, setInner(right)))), walk, ok));
	}

	private LispVal plain(String clName, List<LispVal> items) {
		return cons(sym(clName), lowers(items, 1));
	}

	private List<LispVal> lowers(List<LispVal> items, int from) {
		List<LispVal> out = new ArrayList<>();
		for (int i = from; i < items.size(); i++) {
			out.add(lower(items.get(i)));
		}
		return out;
	}

	/**
	 * A function in argument position: a known defn, a local binding, a lambda, a
	 * keyword.
	 */
	private LispVal fnValue(LispVal form) {
		if (form instanceof LispSymbol s && s.name().startsWith(":")) {
			// no scope can bind a keyword (plainName refuses one), so this is data
			validateKeyword(s.name());
			return keywordFn(form);
		}
		if (form instanceof LispSymbol s && known(s.name())) {
			if (isMacro(s.name())) {
				throw new LispReadException(s.name() + " is a macro, not a function");
			}
			if (isFunction(s.name())) {
				return list(sym("function"), idSym(s.name()));
			}
			return idSym(s.name());
		}
		if (form instanceof LispSymbol s) {
			LispVal predicate = predicateValue(s.name());
			if (predicate != null) {
				return predicate;
			}
		}
		if (form instanceof LispSymbol s) {
			VarRef qualified = resolveQualified(s.name());
			if (qualified == null) {
				qualified = this.refers.get(s.name());
			}
			if (qualified != null) {
				return stringValue(qualified.var());
			}
		}
		return lower(form);
	}

	/**
	 * A keyword as a function value: the lookup over one argument, so
	 * {@code (map :k coll)} reads the key out of each member. The key lowers once, behind
	 * a temporary; the collection is the lambda's parameter, named once per branch by
	 * {@link #getBranches}.
	 * @param keyDatum the keyword datum
	 * @return the form
	 */
	private LispVal keywordFn(LispVal keyDatum) {
		LispSymbol coll = freshTemp();
		LispSymbol key = freshTemp();
		return list(sym("lambda"), list(coll), list(sym("let"), list(List.of(list(key, lower(keyDatum)))),
				cons(sym("cond"), getBranches(coll, key, NIL_CONST))));
	}

	/**
	 * A predicate as a first-class value: a lambda answering a Common Lisp boolean, so a
	 * sequence function called with it keeps testing raw truthiness. A call answers the
	 * Clojure {@code T}-or-false instead, for printing; null when not a predicate.
	 * @param name the Clojure name
	 * @return the lambda, or null
	 */
	private @Nullable LispVal predicateValue(String name) {
		LispSymbol arg = new LispSymbol(mangle("pred"));
		return switch (name) {
			case "false?" -> list(sym("LAMBDA"), list(arg), list(sym("EQ"), arg, this.falseVariable));
			case "true?" -> list(sym("LAMBDA"), list(arg), list(sym("EQ"), arg, TRUE_CONST));
			case "boolean?" -> list(sym("LAMBDA"), list(arg),
					list(sym("OR"), list(sym("EQ"), arg, TRUE_CONST), list(sym("EQ"), arg, this.falseVariable)));
			default -> null;
		};
	}

	private LispVal strCall(List<LispVal> items) {
		if (items.size() == 1) {
			return LispString.literal("");
		}
		List<LispVal> parts = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			parts.add(strOf(lower(items.get(i)), LispString.literal(""), NIL_CONST));
		}
		return concat(parts);
	}

	/**
	 * {@code pr-str}: the readable arm of {@code str} -- every part converts readably
	 * (strings print quoted, like {@code pr}), parts joined with a single space (like
	 * {@code pr}), and {@code nil} prints as {@code "nil"}.
	 */
	private LispVal prStrCall(List<LispVal> items) {
		if (items.size() == 1) {
			return LispString.literal("");
		}
		List<LispVal> parts = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			if (i > 1) {
				parts.add(LispString.literal(" "));
			}
			parts.add(strOf(lower(items.get(i)), LispString.literal("nil"), TRUE_CONST));
		}
		return concat(parts);
	}

	/**
	 * One part's Clojure-notation string: {@code rontolisp::%clojure-str-of} over the
	 * lowered value (false is {@code "false"}, {@code T} is {@code "true"}, {@code NIL}
	 * is the replacement, a keyword its colon spelling, collections in Clojure notation).
	 * The value runs once, as the call's argument.
	 * @param value the lowered value
	 * @param nilReplacement the string {@code NIL} prints as
	 * @param readable whether the conversion reads back
	 * @return the form
	 */
	private LispVal strOf(LispVal value, LispVal nilReplacement, LispVal readable) {
		return list(CLOJURE_STR_OF, value, nilReplacement, readable);
	}

	private LispVal printCall(List<LispVal> items, boolean newline) {
		// Every part writes to the stream directly behind one
		// rontolisp::%clojure-write-datum call, with the single spaces and the
		// newline as their own writes: no with-output-to-string ever reaches a
		// compiled program (a literal one flips a WASM module into EH mode). The
		// call answers nil, like the oracle.
		List<LispVal> writes = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			if (i > 1) {
				writes.add(list(sym("WRITE-CHAR"), new LispChar(32)));
			}
			writes.add(writeDatum(lower(items.get(i)), LispString.literal("nil"), NIL_CONST));
		}
		if (newline) {
			writes.add(list(sym("TERPRI")));
		}
		writes.add(NIL_CONST);
		return cons(sym("PROGN"), writes);
	}

	/**
	 * {@code pr}/{@code prn}: the readable arms of {@code print}/{@code println} -- same
	 * space separator, same newline folding, but every part converts readably, so strings
	 * print quoted like the oracle's.
	 */
	private LispVal prCall(List<LispVal> items, boolean newline) {
		List<LispVal> writes = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			if (i > 1) {
				writes.add(list(sym("WRITE-CHAR"), new LispChar(32)));
			}
			writes.add(writeDatum(lower(items.get(i)), LispString.literal("nil"), TRUE_CONST));
		}
		if (newline) {
			writes.add(list(sym("TERPRI")));
		}
		writes.add(NIL_CONST);
		return cons(sym("PROGN"), writes);
	}

	/**
	 * One part's direct write: {@code rontolisp::%clojure-write-datum} over the lowered
	 * value, answering nil.
	 * @param value the lowered value
	 * @param nilReplacement the string {@code NIL} prints as
	 * @param readable whether the conversion reads back
	 * @return the form
	 */
	private LispVal writeDatum(LispVal value, LispVal nilReplacement, LispVal readable) {
		return list(CLOJURE_WRITE_DATUM, value, nilReplacement, readable);
	}

	private LispVal concat(List<LispVal> parts) {
		return new LispCons(sym("concatenate"), new LispCons(list(sym("quote"), sym("string")), list(parts)));
	}

	private LispVal bodyOf(List<LispVal> forms) {
		if (forms.isEmpty()) {
			return NIL_CONST;
		}
		if (forms.size() == 1) {
			return lower(forms.get(0));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(sym("progn"));
		for (LispVal form : forms) {
			out.add(lower(form));
		}
		return list(out);
	}

	private LispVal body(List<LispVal> items, int from) {
		int n = items.size() - from;
		if (n <= 0) {
			return NIL_CONST;
		}
		if (n == 1) {
			return lower(items.get(from));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(sym("progn"));
		for (int i = from; i < items.size(); i++) {
			out.add(lower(items.get(i)));
		}
		return list(out);
	}

	/**
	 * One binding pattern destructured against an already-lowered init: flat
	 * {@code (symbol init)} pairs appended to {@code pairs}, plain names registered in
	 * {@code scope}. A plain symbol binds directly; a vector binds positionally through
	 * the seq view ({@code &} the rest as a seq, {@code :as} the whole); a map binds
	 * through the table-aware read ({@code :keys}/{@code :syms}/ {@code :strs}, explicit
	 * locals, {@code :as}, {@code :or} defaults, whose keys name the locals they
	 * default).
	 */
	private void destructureInto(LispVal pattern, LispVal init, List<LispVal> pairs, Map<String, Kind> scope,
			String what) {
		if (pattern instanceof LispSymbol) {
			String name = plainName(pattern, what);
			scope.put(name, Kind.VARIABLE);
			pairs.add(list(idSym(name), init));
			return;
		}
		List<LispVal> elements = items(pattern);
		if (elements != null && !elements.isEmpty() && elements.get(0) == ClojureReader.VECTOR) {
			destructureVector(elements.subList(1, elements.size()), init, pairs, scope, what);
			return;
		}
		if (elements != null && !elements.isEmpty() && isSymbolNamed(elements.get(0), "%hash-map")) {
			destructureMap(elements.subList(1, elements.size()), init, pairs, scope, what);
			return;
		}
		throw new LispReadException(what + " needs a plain name or a vector or map pattern, not " + pattern.print());
	}

	/**
	 * A vector pattern against an already-lowered init: each element binds positionally
	 * through the seq view, so maps seq to entry vectors and anything past the end is
	 * nil; {@code &} binds the rest as a seq, {@code :as} the whole. Elements destructure
	 * recursively.
	 */
	private void destructureVector(List<LispVal> elements, LispVal init, List<LispVal> pairs, Map<String, Kind> scope,
			String what) {
		LispSymbol whole = freshTemp();
		pairs.add(list(whole, init));
		int position = 0;
		for (int i = 0; i < elements.size(); i++) {
			LispVal element = elements.get(i);
			if (isSymbolNamed(element, ":as")) {
				isTrue(i + 1 < elements.size(), "a vector pattern :as needs a plain name after it");
				String name = plainName(elements.get(++i), "a vector pattern :as");
				scope.put(name, Kind.VARIABLE);
				pairs.add(list(idSym(name), whole));
				continue;
			}
			if (isSymbolNamed(element, "&")) {
				isTrue(i + 1 < elements.size(), "a vector pattern & needs a single rest pattern after it");
				isTrue(i + 2 == elements.size(), "a vector pattern & takes a single rest pattern after it");
				destructureInto(elements.get(++i), dropView(whole, position), pairs, scope, what);
				continue;
			}
			destructureInto(element, nthForm(whole, new LispInteger(position), NIL_CONST), pairs, scope, what);
			position++;
		}
	}

	/** The seq view past the first {@code n} items: {@code nthcdr} over the view. */
	private LispVal dropView(LispVal coll, int n) {
		if (n == 0) {
			return seqForm(coll);
		}
		return list(sym("nthcdr"), new LispInteger(n), seqForm(coll));
	}

	/**
	 * A map pattern against an already-lowered init: every entry but the
	 * {@code :keys}/{@code :syms}/{@code :strs}/{@code :as}/{@code :or} directives binds
	 * its local through the table-aware read of its key expression, with the {@code :or}
	 * default when present.
	 */
	private void destructureMap(List<LispVal> entries, LispVal init, List<LispVal> pairs, Map<String, Kind> scope,
			String what) {
		LispSymbol whole = freshTemp();
		pairs.add(list(whole, init));
		Map<String, LispVal> defaults = new HashMap<>();
		for (int i = 0; i + 1 < entries.size(); i += 2) {
			if (!isSymbolNamed(entries.get(i), ":or")) {
				continue;
			}
			List<LispVal> orMap = items(entries.get(i + 1));
			if (orMap == null || orMap.isEmpty() || !isSymbolNamed(orMap.get(0), "%hash-map")) {
				throw new LispReadException(
						"a map pattern :or takes a map of defaults, not " + entries.get(i + 1).print());
			}
			List<LispVal> orEntries = orMap.subList(1, orMap.size());
			for (int j = 0; j + 1 < orEntries.size(); j += 2) {
				LispVal defaultKey = orEntries.get(j);
				isTrue(defaultKey instanceof LispSymbol key && !key.name().startsWith(":"),
						"a map pattern :or takes plain names for defaults, not " + defaultKey.print());
				defaults.put(((LispSymbol) defaultKey).name(), orEntries.get(j + 1));
			}
		}
		for (int i = 0; i + 1 < entries.size(); i += 2) {
			LispVal head = entries.get(i);
			LispVal arg = entries.get(i + 1);
			if (isSymbolNamed(head, ":or")) {
				continue;
			}
			if (isSymbolNamed(head, ":as")) {
				String name = plainName(arg, "a map pattern :as");
				scope.put(name, Kind.VARIABLE);
				pairs.add(list(idSym(name), whole));
				continue;
			}
			if (head instanceof LispSymbol kind
					&& (kind.name().equals(":keys") || kind.name().equals(":syms") || kind.name().equals(":strs"))) {
				bindKeys(kind.name(), arg, whole, pairs, scope, defaults);
				continue;
			}
			if (head instanceof LispSymbol) {
				String name = plainName(head, "a map pattern binding");
				scope.put(name, Kind.VARIABLE);
				pairs.add(list(idSym(name), getForm(whole, lower(arg), defaultFor(defaults, name))));
				continue;
			}
			// a nested pattern binds from the same read, without an :or default
			destructureInto(head, getForm(whole, lower(arg), NIL_CONST), pairs, scope, what);
		}
	}

	/**
	 * One {@code :keys}/{@code :syms}/{@code :strs} directive: each entry binds its local
	 * from the keyword, symbol or string key of that spelling (a {@code :keys} entry may
	 * qualify, binding the short name).
	 */
	private void bindKeys(String kind, LispVal names, LispVal whole, List<LispVal> pairs, Map<String, Kind> scope,
			Map<String, LispVal> defaults) {
		List<LispVal> elements = items(names);
		if (elements == null || elements.isEmpty() || elements.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(
					"a map pattern " + kind + " takes a vector of plain names, not " + names.print());
		}
		for (LispVal element : elements.subList(1, elements.size())) {
			isTrue(element instanceof LispSymbol spelled && !spelled.name().startsWith(":")
					&& !spelled.name().equals("&"),
					"a map pattern " + kind + " takes a vector of plain names, not " + element.print());
			String lookup = ((LispSymbol) element).name();
			String local = lookup;
			if ((kind.equals(":keys") || kind.equals(":syms")) && lookup.lastIndexOf('/') >= 0) {
				local = lookup.substring(lookup.lastIndexOf('/') + 1);
				isTrue(!local.isEmpty(),
						"a map pattern " + kind + " takes a vector of plain names, not " + element.print());
			}
			LispVal keyForm = switch (kind) {
				case ":keys" -> lower(new LispSymbol(":" + lookup));
				case ":syms" -> quote(element);
				default -> LispString.literal(local);
			};
			scope.put(local, Kind.VARIABLE);
			pairs.add(list(idSym(local), getForm(whole, keyForm, defaultFor(defaults, local))));
		}
	}

	private LispVal defaultFor(Map<String, LispVal> defaults, String name) {
		LispVal found = defaults.get(name);
		return found == null ? NIL_CONST : lower(found);
	}

	// namespaces: ns clauses, require/use/import as alias wiring

	/** A namespace-qualified var: the namespace and the var it names. */
	private record VarRef(String ns, String var) {
	}

	/** The namespaces whose vars lower to core forms: only {@code clojure.string} yet. */
	private static boolean isKnownNamespace(String ns) {
		return ns.equals("clojure.string");
	}

	/** Whether the namespace exports the var as a lowering. */
	private static boolean isKnownVar(String ns, String var) {
		return ns.equals("clojure.string") && STRING_VARS.contains(var);
	}

	/** The {@code clojure.string} vars this front end implements. */
	private static final Set<String> STRING_VARS = Set.of("join", "split", "split-lines", "upper-case", "lower-case",
			"capitalize", "trim", "triml", "trimr", "trim-newline", "blank?", "starts-with?", "ends-with?", "includes?",
			"index-of", "last-index-of", "replace", "replace-first", "escape", "re-quote-replacement", "reverse");

	/** The {@code java.lang} classes a simple name resolves to without an import. */
	private static final Set<String> JAVA_LANG = Set.of("Object", "String", "Integer", "Long", "Double", "Float",
			"Boolean", "Character", "Byte", "Short", "Math", "System", "Class", "Thread", "Exception",
			"RuntimeException", "Error", "StringBuilder", "Number", "Comparable", "CharSequence");

	/**
	 * Whether a core name is visible: everything outside a
	 * {@code (:refer-clojure :only [...])} set, minus a {@code :exclude} set. An
	 * invisible name is not a builtin, so a user definition of it wins.
	 */
	private boolean coreAllowed(String name) {
		if (this.referClojureOnly != null && !this.referClojureOnly.contains(name)) {
			return false;
		}
		return !this.referClojureExclude.contains(name);
	}

	/**
	 * One {@code (ns name doc? attr-map? clause...)} form: wires the {@code :require} /
	 * {@code :use} aliases and refers, the {@code :import} class names and the
	 * {@code :refer-clojure} filter, and defines nothing. Metadata, the docstring and the
	 * attr map are ignored, like the declaration itself.
	 */
	private void processNs(LispVal form) {
		List<LispVal> items = items(form);
		if (items == null || items.size() < 2) {
			throw new LispReadException("ns takes a name: " + form.print());
		}
		if (!(items.get(1) instanceof LispSymbol)) {
			throw new LispReadException("ns takes a name, not " + items.get(1).print());
		}
		for (int i = 2; i < items.size(); i++) {
			LispVal clause = items.get(i);
			if (clause instanceof LispString) {
				continue; // the docstring
			}
			List<LispVal> parts = items(clause);
			if (parts == null || parts.isEmpty()) {
				continue;
			}
			if (!(parts.get(0) instanceof LispSymbol head) || !head.name().startsWith(":")) {
				continue; // metadata and anything else that declares nothing
			}
			switch (head.name()) {
				case ":require" -> requireSpecs(parts.subList(1, parts.size()), false);
				case ":use" -> requireSpecs(parts.subList(1, parts.size()), true);
				case ":import" -> importSpecs(parts.subList(1, parts.size()));
				case ":refer-clojure" -> referClojure(parts.subList(1, parts.size()));
				default -> throw new LispReadException("ns clause " + head.name() + " is not supported yet");
			}
		}
	}

	/**
	 * The libspecs of a {@code :require} (or {@code :use}, which refers everything by
	 * default): {@code [ns :as alias :refer [vars]/:all]} vectors, prefix symbols and
	 * prefix lists. Requiring an unknown namespace is an error, like the oracle's
	 * missing-library failure.
	 */
	private void requireSpecs(List<LispVal> specs, boolean referAll) {
		String prefix = null;
		for (LispVal spec : specs) {
			LispVal unwrapped = unwrapQuote(spec);
			if (unwrapped instanceof LispSymbol bare) {
				prefix = bare.name();
				continue;
			}
			List<LispVal> parts = items(unwrapped);
			if (parts == null || parts.isEmpty() || !isVectorDatum(unwrapped)) {
				throw new LispReadException("require takes library specs, not " + spec.print());
			}
			if (parts.get(1) instanceof LispSymbol) {
				requireOne(prefix, parts.subList(1, parts.size()), referAll, spec);
			}
			else {
				// a prefix list: (prefix sub...) names prefix.sub... libraries
				List<LispVal> prefixParts = items(unwrapped);
				if (prefixParts == null || prefixParts.isEmpty()
						|| !(prefixParts.get(0) instanceof LispSymbol prefixName)) {
					throw new LispReadException("require takes library specs, not " + spec.print());
				}
				for (int i = 1; i < prefixParts.size(); i++) {
					LispVal sub = unwrapQuote(prefixParts.get(i));
					List<LispVal> subParts = items(sub);
					if (subParts == null || subParts.isEmpty() || !isVectorDatum(sub)) {
						throw new LispReadException("require takes library specs, not " + spec.print());
					}
					requireOne(prefixName.name(), subParts.subList(1, subParts.size()), referAll, spec);
				}
			}
		}
	}

	private void requireOne(@Nullable String prefix, List<LispVal> parts, boolean referAll, LispVal spec) {
		String ns = ((LispSymbol) parts.get(0)).name();
		if (prefix != null) {
			ns = prefix + "." + ns;
		}
		if (!isKnownNamespace(ns)) {
			throw new LispReadException("unknown namespace: " + ns);
		}
		this.aliases.putIfAbsent(ns, ns); // the fully-qualified spelling always resolves
		boolean all = referAll;
		List<String> only = null;
		Set<String> exclude = new HashSet<>();
		for (int i = 1; i < parts.size(); i += 2) {
			if (i + 1 >= parts.size() || !(parts.get(i) instanceof LispSymbol opt)) {
				throw new LispReadException("require takes option/value pairs, not " + spec.print());
			}
			LispVal arg = parts.get(i + 1);
			switch (opt.name()) {
				case ":as" -> {
					if (!(arg instanceof LispSymbol alias)) {
						throw new LispReadException(":as takes an alias, not " + arg.print());
					}
					this.aliases.put(alias.name(), ns);
				}
				case ":refer" -> {
					if (arg instanceof LispSymbol every && every.name().equals(":all")) {
						all = true;
					}
					else {
						only = referNames(arg, spec);
					}
				}
				case ":only" -> only = referNames(arg, spec);
				case ":exclude" -> exclude.addAll(referNames(arg, spec));
				case ":rename" -> throw new LispReadException(":rename is not supported yet: " + spec.print());
				default -> throw new LispReadException("require option " + opt.name() + " is not supported yet");
			}
		}
		if (all) {
			for (String var : STRING_VARS) {
				if (!exclude.contains(var)) {
					this.refers.put(var, new VarRef(ns, var));
				}
			}
		}
		else if (only != null) {
			for (String var : only) {
				if (!exclude.contains(var)) {
					if (!isKnownVar(ns, var)) {
						throw new LispReadException("unknown name: " + ns + "/" + var);
					}
					this.refers.put(var, new VarRef(ns, var));
				}
			}
		}
	}

	private static List<String> referNames(LispVal arg, LispVal spec) {
		List<LispVal> elements = items(arg);
		if (elements == null || elements.isEmpty() || elements.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("a :refer/:only/:exclude takes a vector of names, not " + spec.print());
		}
		List<String> names = new ArrayList<>();
		for (LispVal element : elements.subList(1, elements.size())) {
			if (!(element instanceof LispSymbol named) || named.name().startsWith(":")) {
				throw new LispReadException("a :refer/:only/:exclude takes a vector of names, not " + spec.print());
			}
			names.add(named.name());
		}
		return names;
	}

	/**
	 * The class specs of an {@code :import}: bare classes and {@code (package ...)}
	 * lists.
	 */
	private void importSpecs(List<LispVal> specs) {
		for (LispVal spec : specs) {
			LispVal unwrapped = unwrapQuote(spec);
			if (unwrapped instanceof LispSymbol single) {
				importClass(single.name());
				continue;
			}
			List<LispVal> parts = items(unwrapped);
			if (parts == null || parts.isEmpty() || !(parts.get(0) instanceof LispSymbol pack)) {
				throw new LispReadException("import takes class names, not " + spec.print());
			}
			for (int i = 1; i < parts.size(); i++) {
				if (!(parts.get(i) instanceof LispSymbol named)) {
					throw new LispReadException("import takes class names, not " + spec.print());
				}
				importClass(pack.name() + "." + named.name());
			}
		}
	}

	private void importClass(String fqn) {
		int dot = fqn.lastIndexOf('.');
		this.classNames.put(dot < 0 ? fqn : fqn.substring(dot + 1), fqn);
	}

	/**
	 * The options of a {@code (:refer-clojure ...)} clause: {@code :only},
	 * {@code :exclude}.
	 */
	private void referClojure(List<LispVal> opts) {
		for (int i = 0; i < opts.size(); i += 2) {
			if (i + 1 >= opts.size() || !(opts.get(i) instanceof LispSymbol opt)) {
				throw new LispReadException(":refer-clojure takes option/value pairs");
			}
			switch (opt.name()) {
				case ":only" -> this.referClojureOnly = new HashSet<>(referNames(opts.get(i + 1), opts.get(i + 1)));
				case ":exclude" -> this.referClojureExclude.addAll(referNames(opts.get(i + 1), opts.get(i + 1)));
				case ":rename" -> throw new LispReadException(":rename is not supported yet in :refer-clojure");
				default -> throw new LispReadException(":refer-clojure option " + opt.name() + " is not supported yet");
			}
		}
	}

	/**
	 * The libspecs of a top-level {@code require} / {@code use} / {@code import} call.
	 */
	private static List<LispVal> specsOf(List<LispVal> items, String what) {
		isTrue(items.size() >= 2, what + " takes library specs");
		return items.subList(1, items.size());
	}

	/** A libspec unquoted: {@code 'foo} and {@code foo} spell the same library. */
	private static LispVal unwrapQuote(LispVal spec) {
		List<LispVal> parts = items(spec);
		if (parts != null && parts.size() == 2 && isSymbolNamed(parts.get(0), "quote")
				&& parts.get(1) instanceof LispSymbol) {
			return parts.get(1);
		}
		return spec;
	}

	/**
	 * A slash name resolved through the aliases: {@code s/join} with {@code s} for
	 * {@code clojure.string}, or the fully-qualified {@code clojure.string/join}. Null
	 * when the head is no alias and no known namespace, so the call keeps falling through
	 * to interop and the unknown-name refusal.
	 */
	private @Nullable VarRef resolveQualified(String name) {
		int slash = name.indexOf('/');
		if (slash <= 0) {
			return null;
		}
		String head = name.substring(0, slash);
		String tail = name.substring(slash + 1);
		if (tail.isEmpty() || tail.indexOf('/') >= 0) {
			return null;
		}
		String ns = this.aliases.get(head);
		if (ns == null && isKnownNamespace(head)) {
			ns = head;
		}
		if (ns == null || !isKnownVar(ns, tail)) {
			return null;
		}
		return new VarRef(ns, tail);
	}

	/** A class name resolved: dotted as written, imported, or {@code java.lang}. */
	private String resolveClass(String name) {
		if (name.indexOf('.') >= 0) {
			return name;
		}
		String imported = this.classNames.get(name);
		if (imported != null) {
			return imported;
		}
		if (JAVA_LANG.contains(name)) {
			return "java.lang." + name;
		}
		return name;
	}

	/**
	 * Whether the slash form's head names a class: imported, java.lang, dotted or
	 * capitalized.
	 */
	private boolean isClasslike(String head) {
		return this.classNames.containsKey(head) || JAVA_LANG.contains(head) || head.indexOf('.') >= 0
				|| (!head.isEmpty() && Character.isUpperCase(head.charAt(0)));
	}

	// atoms and quotes

	private LispVal atom(LispVal form) {
		if (!(form instanceof LispSymbol s)) {
			return form; // numbers, strings, characters are self-evaluating
		}
		String name = s.name();
		switch (name) {
			case "nil":
				return NIL_CONST;
			case "true":
				return TRUE_CONST;
			case "false":
				return this.falseVariable;
			default:
				break;
		}
		if (name.startsWith(":")) {
			validateKeyword(name);
			return keywordForm(name.substring(1)); // a keyword is its spelling,
													// case-preserved
		}
		if (name.startsWith("#:")) {
			// a gensym a macro expansion returned: uninterned, so it lowers to
			// itself instead of mangling behind the prefix
			return s;
		}
		if (name.equals("%") || name.length() > 1 && name.charAt(0) == '%'
				&& name.substring(1).chars().allMatch(Character::isDigit)) {
			if (this.anonArgs == null) {
				throw new LispReadException(name + " outside the anon form #(...)");
			}
			String args = this.anonArgs;
			int oneBased = name.equals("%") ? 1 : Integer.parseInt(name.substring(1));
			isTrue(oneBased <= 9, "the anon form #(...) takes at most 9 arguments");
			return list(sym("nth"), new LispInteger(oneBased - 1), new LispSymbol(args));
		}
		if (!known(name)) {
			if (name.equals("nth")) {
				return nthValue();
			}
			if (name.equals("quot")) {
				return quotValue();
			}
			VarRef qualified = resolveQualified(name);
			if (qualified == null) {
				qualified = this.refers.get(name);
			}
			if (qualified != null) {
				return stringValue(qualified.var());
			}
			LispVal synth = valueOf(name);
			if (synth != null) {
				return synth;
			}
			String cl = builtinValue(name);
			if (cl == null) {
				throw new LispReadException("unknown name: " + name);
			}
			return list(sym("function"), sym(cl));
		}
		if (isMacro(name)) {
			throw new LispReadException(name + " is a macro, not a function");
		}
		if (isFunction(name)) {
			return list(sym("function"), idSym(name));
		}
		return idSym(name);
	}

	/** The Common Lisp function a core name names as a value, or null. */
	private static @Nullable String builtinValue(String name) {
		return switch (name) {
			case "+", "-", "*", "/", "max", "min", "rem", "mod", "abs", "cons", "list", "expt", "reverse", "apply", "=",
					"<", ">", "<=", ">=", "not", "length", "car", "cdr", "equal", "evenp", "oddp", "zerop", "plusp",
					"minusp", "vector", "vectorp" ->
				name;
			case "empty?", "nil?" -> "null";
			case "gensym" -> "gensym";
			case "even?" -> "evenp";
			case "odd?" -> "oddp";
			case "zero?" -> "zerop";
			case "pos?" -> "plusp";
			case "neg?" -> "minusp";
			case "some?" -> "not";
			case "count" -> "length";
			case "first" -> "car";
			case "rest" -> "cdr";
			default -> null;
		};
	}

	private LispVal quote(LispVal datum) {
		if (datum instanceof LispSymbol s) {
			String name = s.name();
			if (name.equals("nil")) {
				return list(sym("quote"), NIL_CONST);
			}
			if (name.equals("true")) {
				return list(sym("quote"), TRUE_CONST);
			}
			if (name.equals("false")) {
				return list(sym("quote"), new LispSymbol(FALSE_VALUE_NAME));
			}
			if (name.startsWith(":")) {
				validateKeyword(name);
				return list(sym("quote"), keywordDatum(name.substring(1)));
			}
			if (name.startsWith("#:")) {
				// a gensym a macro expansion returned: it quotes to itself
				return list(sym("quote"), s);
			}
			return list(sym("quote"), idSym(name));
		}
		if (datum instanceof LispCons) {
			List<LispVal> items = items(datum, List.of());
			if (!items.isEmpty() && items.get(0) == ClojureReader.VECTOR) {
				List<LispVal> out = new ArrayList<>();
				out.add(sym("vector"));
				for (int i = 1; i < items.size(); i++) {
					out.add(quote(items.get(i)));
				}
				return list(out);
			}
			if (!items.isEmpty()
					&& (isSymbolNamed(items.get(0), "%hash-map") || isSymbolNamed(items.get(0), "%hash-set"))) {
				return quotedCollection(items);
			}
			LispVal tail = NIL_CONST;
			for (int i = items.size() - 1; i >= 0; i--) {
				tail = new LispCons(quotedConstant(items.get(i)), tail);
			}
			return list(sym("quote"), tail);
		}
		return list(sym("quote"), datum);
	}

	/** One element of a quoted list: the constant, the per-element quote flattened. */
	private LispVal quotedConstant(LispVal datum) {
		LispVal quoted = quote(datum);
		if (quoted instanceof LispCons cell && cell.car() instanceof LispSymbol s && s.name().equals("QUOTE")
				&& cell.cdr() instanceof LispCons inner) {
			return inner.car();
		}
		return quoted;
	}

	/**
	 * A quoted map or set literal: the construction over the quoted elements, so
	 * {@code '{:a x}} builds a table holding the symbol. Markers never reach the
	 * per-element path -- they are skipped here, not quoted.
	 */
	private LispVal quotedCollection(List<LispVal> items) {
		List<LispVal> quoted = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			quoted.add(quotedElement(items.get(i)));
		}
		if (isSymbolNamed(items.get(0), "%hash-map")) {
			return mapBuild(quoted);
		}
		return setBuild(quoted);
	}

	/**
	 * One element of a quoted map or set construction: the construction RUNS, so its
	 * elements are forms, not data -- a keyword travels as its construction (its datum in
	 * a {@code (LIST ...)} element would call the tag as a function); anything else
	 * quotes as usual.
	 */
	private LispVal quotedElement(LispVal datum) {
		if (datum instanceof LispSymbol s && s.name().startsWith(":")) {
			validateKeyword(s.name());
			return keywordForm(s.name().substring(1));
		}
		return quotedConstant(datum);
	}

	// errors: try over handler-case and unwind-protect, throw over error

	/**
	 * {@code (try body... (catch Class var body...)... (finally ...))}: the body guarded
	 * by a {@code handler-case} inside an {@code unwind-protect}. Every catch class
	 * answers the catch-all {@code error} clause -- the classes are not distinguished, so
	 * the first clause handles any condition -- and the clauses keep their order. The
	 * catch var binds the Common Lisp condition, not a host exception.
	 */
	private LispVal tryOf(List<LispVal> items) {
		List<LispVal> body = new ArrayList<>();
		List<List<LispVal>> catches = new ArrayList<>();
		List<LispVal> fin = new ArrayList<>();
		boolean closed = false;
		for (int i = 1; i < items.size(); i++) {
			List<LispVal> part = items(items.get(i));
			if (part != null && !part.isEmpty() && isSymbolNamed(part.get(0), "catch")) {
				isTrue(part.size() >= 3 && part.get(1) instanceof LispSymbol, "catch takes a class, a name and a body");
				plainName(part.get(2), "catch");
				catches.add(part);
				closed = true;
				continue;
			}
			if (part != null && !part.isEmpty() && isSymbolNamed(part.get(0), "finally")) {
				fin.addAll(part.subList(1, part.size()));
				closed = true;
				continue;
			}
			isTrue(!closed, "a try body comes before catch and finally");
			body.add(items.get(i));
		}
		LispVal guarded = body.isEmpty() ? NIL_CONST : bodyOf(body);
		if (!catches.isEmpty()) {
			List<LispVal> clauses = new ArrayList<>();
			for (List<LispVal> caught : catches) {
				String var = ((LispSymbol) caught.get(2)).name();
				LispVal clauseBody = inScope(new HashMap<>(Map.of(var, Kind.VARIABLE)),
						() -> bodyOf(caught.subList(3, caught.size())));
				clauses.add(list(sym("ERROR"), list(idSym(var)), clauseBody));
			}
			List<LispVal> handler = new ArrayList<>();
			handler.add(sym("HANDLER-CASE"));
			handler.add(guarded);
			handler.addAll(clauses);
			guarded = list(handler);
		}
		if (!fin.isEmpty()) {
			List<LispVal> forms = new ArrayList<>();
			forms.add(sym("UNWIND-PROTECT"));
			forms.add(guarded);
			for (LispVal f : fin) {
				forms.add(lower(f));
			}
			guarded = list(forms);
		}
		return guarded;
	}

	// state: atoms and volatiles over a tagged one-vector cell

	/**
	 * The tag heading an atom: {@code (:C%ATOM #(value))}, the set wrapper's shape, so
	 * the verbs can tell an atom from a plain vector. The value lives in a one-vector
	 * cell, which every backend reads and writes; printing spells the wrapper, not the
	 * oracle's object syntax.
	 */
	private static final LispSymbol ATOM_TAG = new LispSymbol(":C%ATOM");

	private static LispVal wrapAtom(LispVal value) {
		return list(sym("list"), ATOM_TAG, list(sym("vector"), value));
	}

	/** Whether the form holds a wrapped atom: the tag over a one-vector cell. */
	private static LispVal isAtomForm(LispVal form) {
		return list(sym("and"), list(sym("consp"), form), list(sym("eq"), list(sym("car"), form), ATOM_TAG),
				list(sym("vectorp"), list(sym("cadr"), form)));
	}

	/** The value inside an already-bound atom: the cell's only element. */
	private static LispVal atomGet(LispVal bound) {
		return list(sym("aref"), list(sym("cadr"), bound), new LispInteger(0));
	}

	/** The cell rewritten to the value: answers the value. */
	private static LispVal atomPut(LispVal bound, LispVal value) {
		return list(sym("setf"), list(sym("aref"), list(sym("cadr"), bound), new LispInteger(0)), value);
	}

	/**
	 * The lowered atom checked and handed to the body builder: anything else signals,
	 * like the oracle's cast failure. The atom runs once, behind a temporary.
	 */
	private LispVal withAtom(LispVal lowered, String op, java.util.function.Function<LispVal, LispVal> body) {
		LispSymbol cell = freshTemp();
		return list(sym("let"), list(List.of(list(cell, lowered))), list(sym("if"), isAtomForm(cell), body.apply(cell),
				list(sym("error"), LispString.literal(op + " needs an atom"))));
	}

	private LispVal atomOf(List<LispVal> items) {
		isTrue(items.size() == 2, "atom takes an initial value");
		return wrapAtom(lower(items.get(1)));
	}

	private LispVal derefOf(List<LispVal> items) {
		isTrue(items.size() == 2, "deref takes one argument");
		return derefForm(lower(items.get(1)));
	}

	/** The value inside the lowered atom. */
	private LispVal derefForm(LispVal lowered) {
		return withAtom(lowered, "deref", ClojureLowering::atomGet);
	}

	private LispVal swapOf(List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		isTrue(items.size() >= 3, op + " takes an atom, a function and arguments");
		LispVal loweredAtom = lower(items.get(1));
		LispVal fun = items.get(2);
		List<LispVal> rest = items.subList(3, items.size());
		return withAtom(loweredAtom, op, cell -> {
			List<LispVal> tail = new ArrayList<>();
			for (LispVal arg : rest) {
				tail.add(lower(arg));
			}
			LispVal spread = list(sym("cons"), atomGet(cell), cons(sym("list"), tail));
			LispSymbol next = freshTemp();
			return list(sym("let"), list(List.of(list(next, list(sym("apply"), fnValue(fun), spread)))),
					atomPut(cell, next), next);
		});
	}

	private LispVal resetOf(List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		isTrue(items.size() == 3, op + " takes an atom and a value");
		LispVal value = lower(items.get(2));
		return withAtom(lower(items.get(1)), op, cell -> {
			LispSymbol next = freshTemp();
			return list(sym("let"), list(List.of(list(next, value))), atomPut(cell, next), next);
		});
	}

	/**
	 * {@code compare-and-set!}: the cell rewritten only when its value is {@code eql} to
	 * the expected one -- value comparison for numbers, identity for everything else,
	 * which is how the oracle's reference comparison answers on coalesced literals --
	 * answering a Clojure boolean.
	 */
	private LispVal compareAndSetOf(List<LispVal> items) {
		isTrue(items.size() == 4, "compare-and-set! takes an atom, an expected value and a new value");
		LispVal wanted = lower(items.get(2));
		LispVal value = lower(items.get(3));
		return withAtom(lower(items.get(1)), "compare-and-set!", cell -> {
			LispSymbol next = freshTemp();
			return list(sym("let"), list(List.of(list(next, value))),
					booleanAnswer(list(sym("if"), list(sym("eql"), wanted, atomGet(cell)),
							list(sym("progn"), atomPut(cell, next), TRUE_CONST), NIL_CONST)));
		});
	}

	/** {@code atom} as a value: a one-argument lambda over the constructor. */
	private LispVal atomValue() {
		LispSymbol init = new LispSymbol(mangle("atom-init"));
		return list(sym("lambda"), list(init), wrapAtom(init));
	}

	/** {@code deref} as a value: a one-argument lambda over the reader. */
	private LispVal derefValue() {
		LispSymbol cell = new LispSymbol(mangle("deref-cell"));
		return list(sym("lambda"), list(cell), derefForm(cell));
	}

	/** {@code swap!} as a value: over an atom, a function and any more arguments. */
	private LispVal swapValue() {
		LispSymbol cell = new LispSymbol(mangle("swap-cell"));
		LispSymbol fun = new LispSymbol(mangle("swap-fun"));
		LispSymbol rest = new LispSymbol(mangle("swap-rest"));
		LispSymbol next = new LispSymbol(mangle("swap-next"));
		LispVal spread = list(sym("cons"), atomGet(cell), rest);
		LispVal update = list(sym("let"), list(List.of(list(next, list(sym("apply"), fun, spread)))),
				atomPut(cell, next), next);
		return list(sym("lambda"), list(List.of(cell, fun, AMPERSAND_REST, rest)),
				withAtom(cell, "swap!", ignored -> update));
	}

	/** {@code reset!} as a value: a two-argument lambda over the writer. */
	private LispVal resetValue() {
		LispSymbol cell = new LispSymbol(mangle("reset-cell"));
		LispSymbol value = new LispSymbol(mangle("reset-value"));
		return list(sym("lambda"), list(List.of(cell, value)),
				withAtom(cell, "reset!", bound -> atomPut(bound, value)));
	}

	/**
	 * {@code ex-data}/{@code ex-message} as a value: a one-argument lambda over the
	 * helper.
	 */
	private LispVal exHelperValue(String helper) {
		this.usedExInfo = true;
		LispSymbol ex = new LispSymbol(mangle("ex-value"));
		return list(sym("lambda"), list(ex), list(new LispSymbol(helper), ex));
	}

	/** {@code ex-info} as a value: a two-argument lambda over the constructor. */
	private LispVal exInfoValue() {
		this.usedExInfo = true;
		LispSymbol message = new LispSymbol(mangle("ex-message"));
		LispSymbol data = new LispSymbol(mangle("ex-data"));
		return list(sym("lambda"), list(List.of(message, data)), list(sym("make-condition"),
				list(sym("quote"), new LispSymbol("C%E-EX-INFO")), sym(":message"), message, sym(":data"), data));
	}

	/** {@code compare-and-set!} as a value: a three-argument lambda over the swap. */
	private LispVal compareAndSetValue() {
		LispSymbol cell = new LispSymbol(mangle("cas-cell"));
		LispSymbol wanted = new LispSymbol(mangle("cas-wanted"));
		LispSymbol value = new LispSymbol(mangle("cas-value"));
		return list(sym("lambda"), list(List.of(cell, wanted, value)),
				withAtom(cell, "compare-and-set!",
						bound -> booleanAnswer(list(sym("if"), list(sym("eql"), wanted, atomGet(bound)),
								list(sym("progn"), atomPut(bound, value), TRUE_CONST), NIL_CONST))));
	}

	// dispatch: multimethods over a method table and a dispatcher defun

	/**
	 * {@code (defmulti name doc? dispatch-fn & opts)}: a method table and a default
	 * dispatch value in two globals no identifier can spell (the suffix follows the
	 * mangled name, like the multi-arity helpers), plus a dispatcher {@code defun}
	 * applying each call's dispatch value to the table. The default dispatch value is
	 * {@code :default} without a {@code :default} option; a miss with no method for the
	 * default signals, like the oracle. With a {@code :hierarchy} option the dispatcher
	 * consults that hierarchy value on a miss (the global one without the option): every
	 * method whose key the dispatch value descends from ({@code isa?}) is a candidate,
	 * the strictly most specific wins, {@code prefer-method} breaks the remaining ties,
	 * and an unbroken tie signals -- like the oracle.
	 */
	private List<LispVal> defmultiForms(List<LispVal> items) {
		isTrue(items.size() >= 3, "defmulti takes a name, a dispatch function and options");
		String name = plainName(items.get(1), "defmulti");
		int at = 2;
		if (items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		List<LispVal> attr = items(items.get(at));
		if (attr != null && !attr.isEmpty() && isSymbolNamed(attr.get(0), "%hash-map")) {
			at++; // the attr map
		}
		isTrue(at < items.size(), "defmulti takes a name, a dispatch function and options");
		LispVal dispatchDatum = items.get(at++);
		LispVal defaultDatum = null;
		LispVal hierarchyDatum = null;
		for (; at < items.size(); at += 2) {
			if (at + 1 >= items.size() || !(items.get(at) instanceof LispSymbol opt)) {
				throw new LispReadException("defmulti takes option/value pairs");
			}
			switch (opt.name()) {
				case ":default" -> defaultDatum = items.get(at + 1);
				case ":hierarchy" -> hierarchyDatum = items.get(at + 1);
				default -> throw new LispReadException("defmulti option " + opt.name() + " is not supported yet");
			}
		}
		this.globals.put(name, Kind.FUNCTION);
		this.macros.remove(name); // a definition wins over the macro it shadows
		LispSymbol methods = new LispSymbol(mangle(name) + "%methods");
		LispSymbol fallback = new LispSymbol(mangle(name) + "%default");
		LispSymbol prefers = new LispSymbol(mangle(name) + "%prefers");
		LispVal dispatchFn = fnValue(dispatchDatum);
		LispVal defaultForm = defaultDatum == null ? keywordForm("default") : lower(defaultDatum);
		LispVal hierarchyForm = hierarchyDatum == null ? hierarchyGlobal() : lower(hierarchyDatum);
		LispSymbol args = freshTemp();
		LispSymbol disp = freshTemp();
		LispSymbol found = freshTemp();
		LispSymbol miss = freshTemp();
		LispVal dispatch = list(sym("let*"),
				list(List.of(list(disp, list(sym("apply"), dispatchFn, args)), list(miss, list(sym("list"), NIL_CONST)),
						list(found, list(sym("gethash"), disp, methods, miss)))),
				list(sym("if"), list(sym("eq"), found, miss), list(new LispSymbol(HIERARCHY_DISPATCH),
						LispString.literal(name), methods, prefers, hierarchyForm, fallback, disp, args),
						list(sym("apply"), found, args)));
		List<LispVal> forms = new ArrayList<>();
		forms.add(list(sym("setq"), methods, makeTable()));
		forms.add(list(sym("setq"), fallback, defaultForm));
		forms.add(list(sym("setq"), prefers, makeTable()));
		forms.add(list(sym("defun"), idSym(name), list(List.of(AMPERSAND_REST, args)), dispatch));
		this.usedHierarchy = true;
		return forms;
	}

	/**
	 * {@code (defmethod name dispatch-value [params...] body...)}: the lambda over the
	 * (possibly destructured) parameters stored in the method table. The multimethod must
	 * be defined -- forward order aside, the pre-scan declares every {@code defmulti}
	 * first.
	 */
	private LispVal defmethodForm(List<LispVal> items) {
		isTrue(items.size() >= 4, "defmethod takes a name, a dispatch value, a parameter vector and a body");
		String name = plainName(items.get(1), "defmethod");
		isTrue(known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(mangle(name) + "%methods");
		Clause clause = clause(items.get(3), items.subList(4, items.size()));
		LispVal lambda = list(sym("lambda"), list(clause.params()), clause.wrapped());
		return list(sym("setf"), list(sym("gethash"), lower(items.get(2)), methods), lambda);
	}

	private LispVal removeMethodOf(List<LispVal> items) {
		isTrue(items.size() == 3, "remove-method takes a multimethod and a dispatch value");
		String name = plainName(items.get(1), "remove-method");
		isTrue(known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(mangle(name) + "%methods");
		return list(sym("progn"), list(sym("remhash"), lower(items.get(2)), methods),
				list(sym("function"), idSym(name)));
	}

	private LispVal getMethodOf(List<LispVal> items) {
		isTrue(items.size() == 3, "get-method takes a multimethod and a dispatch value");
		String name = plainName(items.get(1), "get-method");
		isTrue(known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(mangle(name) + "%methods");
		return list(sym("gethash"), lower(items.get(2)), methods, NIL_CONST);
	}

	// macros: defmacro, lower-time expansion, syntax-quote, macroexpand, gensym

	/** The runtime once-expander behind {@code macroexpand-1}, as spelled in programs. */
	private static final String MACROEXPAND_1 = "C%MACROEXPAND-1";

	/**
	 * The runtime fixpoint expander behind {@code macroexpand}, as spelled in programs.
	 */
	private static final String MACROEXPAND = "C%MACROEXPAND";

	/**
	 * The names a {@code defmacro} cannot take: every special form a call site could
	 * never reach through the macro table (the form intercepts first), the three new
	 * builtins below (whose value paths would disagree with the macro), the two dot-heads
	 * (instance-call position) and, by rule in {@link #defmacroForms}, anything dotted,
	 * suffixed or qualified.
	 */
	private static final Set<String> MACRO_RESERVED = Set.of("quote", "def", "defn", "defmacro", "fn", "let", "loop",
			"declare", "->", "->>", "as->", "doto", "cond->", "cond->>", "some->", "some->>", "list*", "doseq",
			"dotimes", "for", "defmulti", "defmethod", "remove-method", "get-method", "prefer-method", "derive",
			"underive", "isa?", "parents", "ancestors", "descendants", "make-hierarchy", "defprotocol", "defrecord",
			"deftype", "definterface", "reify", "extend-protocol", "extend-type", "extend", "satisfies?", "gen-class",
			"gen-interface", "try", "throw", "ex-info", "ex-data", "ex-message", "atom", "deref", "swap!", "reset!",
			"compare-and-set!", "volatile!", "vreset!", "vswap!", "add-watch", "remove-watch", "comment", "require",
			"use", "import", "in-ns", "set!", "memfn", "proxy", "new", "syntax-quote", "unquote", "unquote-splicing",
			"var", "with-meta", "if", "when", "cond", "do", "recur", ".", "..", "gensym", "macroexpand-1",
			"macroexpand");

	/**
	 * {@code (defmacro name doc? attr? ([params] body...)+)}: a compile-time expander
	 * plus its runtime table entry, so {@code macroexpand-1} sees the same function the
	 * lower-time expansion runs. One entry per arity is overkill here (unlike
	 * {@code defn}, whose arities are callable separately): the expander is a single
	 * lambda over the call's argument list dispatching on its length, applying each
	 * arity's parameters the way {@code multiFn} binds its. A docstring and an attr map
	 * are skipped, like {@code defn} and {@code defmulti}; {@code &} rest, destructured
	 * parameters and several arities work the same way. {@code &form} and {@code &env}
	 * are refused: there is no compilation environment to bind. The definition emits
	 * {@code (progn (setq c%name%macro expander) nil)} -- a lone {@code %} no mangled
	 * identifier spells, so the table stays apart from user definitions, like the
	 * multi-arity helpers -- and registers the expander for the call sites below it; a
	 * call above the definition names the missing expander instead of an unknown name.
	 */
	private List<LispVal> defmacroForms(List<LispVal> items) {
		isTrue(items.size() >= 2, "defmacro needs a name, a parameter vector and a body");
		String name = plainName(items.get(1), "defmacro");
		isTrue(!MACRO_RESERVED.contains(name) && !name.startsWith(".") && !name.endsWith(".") && name.indexOf('/') < 0,
				name + " cannot name a macro: it names a core form");
		int at = 2;
		if (items.size() > at && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		if (items.size() > at) {
			List<LispVal> attr = items(items.get(at));
			if (attr != null && !attr.isEmpty() && isSymbolNamed(attr.get(0), "%hash-map")) {
				at++; // the attr map
			}
		}
		isTrue(items.size() > at, "defmacro needs a parameter vector and a body");
		List<Clause> clauses;
		if (items.get(at) instanceof LispCons && !isVectorDatum(items.get(at))) {
			List<LispVal> raw = items.subList(at, items.size());
			for (LispVal clauseDatum : raw) {
				List<LispVal> parts = items(clauseDatum);
				if (parts != null && !parts.isEmpty()) {
					refuseEnvForm(parts.get(0));
				}
			}
			clauses = arityClauses(raw, "defmacro");
		}
		else {
			refuseEnvForm(items.get(at));
			clauses = List.of(clause(items.get(at), items.subList(at + 1, items.size())));
		}
		LispVal expander = macroExpander(name, clauses);
		LispSymbol table = macroTable(name);
		this.macros.put(name, expander);
		this.globals.put(name, Kind.MACRO);
		this.usedMacros = true;
		LispVal setq = list(sym("setq"), table, expander);
		if (this.macroEvaluator != null) {
			// the macro-time table entry, so a macro body calling macroexpand-1
			// at expansion time sees the macros defined so far
			try {
				this.macroEvaluator.evaluate(setq);
			}
			catch (RuntimeException ex) {
				throw positioned(new LispReadException(exMessage(ex)), items.get(0));
			}
		}
		return List.of(list(sym("progn"), setq, NIL_CONST));
	}

	/** The runtime table global holding a macro's expander. */
	private static LispSymbol macroTable(String name) {
		return new LispSymbol(mangle(name) + "%macro");
	}

	/**
	 * Refuses {@code &form} and {@code &env} anywhere in a macro parameter datum: a macro
	 * body runs with its arguments only, never with a compilation environment.
	 */
	private static void refuseEnvForm(LispVal datum) {
		if (datum instanceof LispSymbol s) {
			if (s.name().equals("&form") || s.name().equals("&env")) {
				throw new LispReadException(s.name() + " is not supported yet: macros receive no environment");
			}
			return;
		}
		List<LispVal> parts = items(datum);
		if (parts == null) {
			return;
		}
		for (LispVal part : parts) {
			refuseEnvForm(part);
		}
	}

	/**
	 * A macro's expander: one lambda over the call's argument list, dispatching on its
	 * length like the multi-arity dispatch and applying each arity's parameters (with
	 * their destructuring prologue) to the lowered body. The same lambda runs at lower
	 * time (through the macro evaluator) and at run time (through the table global), so
	 * the two expansions agree by construction.
	 */
	private LispVal macroExpander(String name, List<Clause> clauses) {
		LispSymbol args = freshTemp();
		if (clauses.size() == 1 && !clauses.get(0).variadic()) {
			Clause only = clauses.get(0);
			return list(sym("lambda"), list(args), list(sym("if"),
					list(sym("="), list(sym("length"), args), new LispInteger(only.fixed())),
					list(sym("apply"), list(sym("lambda"), list(only.params()), only.wrapped()), args),
					list(sym("error"), LispString.literal("wrong number of arguments passed to macro: " + name))));
		}
		LispSymbol count = freshTemp();
		List<LispVal> arms = new ArrayList<>();
		for (Clause clause : clauses) {
			LispVal test = clause.variadic() ? list(sym(">="), count, new LispInteger(clause.fixed()))
					: list(sym("="), count, new LispInteger(clause.fixed()));
			arms.add(
					list(test, list(sym("apply"), list(sym("lambda"), list(clause.params()), clause.wrapped()), args)));
		}
		arms.add(list(TRUE_CONST,
				list(sym("error"), LispString.literal("wrong number of arguments passed to macro: " + name))));
		return list(sym("lambda"), list(args),
				list(sym("let"), list(List.of(list(count, list(sym("length"), args)))), cons(sym("cond"), arms)));
	}

	/**
	 * A call whose head names a macro: the expansion, lowered in place of the call. A
	 * local binding shadows the macro (a parameter, a {@code let} name); a macro name
	 * without its expander is a call above its definition. Null when the head names no
	 * macro.
	 */
	private @Nullable LispVal macroCall(String name, List<LispVal> items) {
		for (Map<String, Kind> scope : this.scopes) {
			if (scope.containsKey(name)) {
				return null; // a local binding shadows the macro
			}
		}
		LispVal expander = this.macros.get(name);
		String macroName = name;
		if (expander == null) {
			VarRef qualified = resolveQualified(name);
			VarRef ref = qualified != null ? qualified : this.refers.get(name);
			if (ref != null) {
				expander = this.macros.get(ref.var());
				macroName = ref.var();
			}
		}
		if (expander == null) {
			if (isMacro(name)) {
				throw new LispReadException("macro `" + name + "` used before its definition");
			}
			return null;
		}
		return expandMacro(macroName, expander, items);
	}

	/**
	 * One macro call expanded: the argument datums travel quoted (the same values a
	 * quoted form answers at run time, so the two expanders agree) into one application
	 * of the expander, the answer decodes back to a datum and lowers like any other form
	 * -- which expands the macros the expansion calls, one depth deeper.
	 */
	private LispVal expandMacro(String name, LispVal expander, List<LispVal> items) {
		if (this.macroDepth >= MAX_MACRO_DEPTH) {
			throw new LispReadException("macro expansion of `" + name + "` did not terminate");
		}
		if (this.macroEvaluator == null) {
			throw new LispReadException(
					"macro `" + name + "` cannot expand without a macro evaluator (lower with one)");
		}
		List<LispVal> quoted = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			quoted.add(quote(items.get(i)));
		}
		LispVal invocation = list(sym("funcall"), expander, cons(sym("list"), quoted));
		LispVal value;
		try {
			value = this.macroEvaluator.evaluate(invocation);
		}
		catch (RuntimeException ex) {
			throw new LispReadException("in macro `" + name + "`: " + exMessage(ex));
		}
		LispVal expansion;
		try {
			expansion = decodeDatum(value);
		}
		catch (LispReadException ex) {
			throw new LispReadException("macro `" + name + "` answered an unreadable value: " + ex.getMessage());
		}
		this.macroDepth++;
		try {
			return lower(expansion);
		}
		finally {
			this.macroDepth--;
		}
	}

	private static String exMessage(RuntimeException ex) {
		String message = ex.getMessage();
		return message == null ? ex.toString() : message;
	}

	/**
	 * A macro answer back to a datum: the inverse of {@link #quote}, so the expansion
	 * lowers the way the quoted call-site data would. Mangled symbols shed the prefix,
	 * keyword and set wrappers answer their datum, vectors and tables their literals, and
	 * a gensym ({@code #:}-spelled, uninterned) travels as itself so the {@code #:}
	 * bypass lowers it back to the same symbol.
	 */
	private LispVal decodeDatum(LispVal value) {
		if (value instanceof LispNil) {
			return new LispSymbol("nil");
		}
		if (value instanceof LispTrue) {
			return new LispSymbol("true");
		}
		if (value instanceof LispSymbol s) {
			String symbol = s.name();
			if (symbol.equals("false") || symbol.equals("T")) {
				return new LispSymbol(symbol.equals("T") ? "true" : "false");
			}
			if (symbol.startsWith("#:")) {
				return new LispSymbol(symbol);
			}
			if (symbol.startsWith(PREFIX)) {
				return new LispSymbol(unmangleName(symbol.substring(PREFIX.length())));
			}
			throw new LispReadException("an unreadable symbol: " + value.print());
		}
		if (value instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol tag) {
				if (tag.name().equals(":C%KEYWORD")) {
					return decodeKeyword(cons);
				}
				if (tag.name().equals(":C%SET")) {
					return decodeSet(cons);
				}
				if (tag.name().equals(":C%ATOM")) {
					throw new LispReadException("an atom cannot travel through a macro expansion");
				}
			}
			List<LispVal> out = new ArrayList<>();
			LispVal run = value;
			while (run instanceof LispCons cell) {
				out.add(decodeDatum(cell.car()));
				run = cell.cdr();
			}
			if (run instanceof LispNil) {
				return list(out);
			}
			LispVal tail = decodeDatum(run);
			for (int i = out.size() - 1; i >= 0; i--) {
				tail = new LispCons(out.get(i), tail);
			}
			return tail;
		}
		if (value instanceof LispArray array && array.dimensions().length == 1) {
			List<LispVal> elements = new ArrayList<>();
			elements.add(ClojureReader.VECTOR);
			for (int i = 0; i < array.totalSize(); i++) {
				elements.add(decodeDatum(array.readFlat(i)));
			}
			return list(elements);
		}
		if (value instanceof LispHashTable table) {
			List<LispVal> elements = new ArrayList<>();
			elements.add(new LispSymbol("%hash-map"));
			for (LispHashTable.Entry entry : table.entries()) {
				elements.add(decodeDatum(entry.key()));
				elements.add(decodeDatum(entry.value()));
			}
			return list(elements);
		}
		if (value instanceof LispString || value instanceof LispChar || value instanceof LispInteger
				|| value instanceof am.ik.rontolisp.LispBigInteger || value instanceof am.ik.rontolisp.LispRatio
				|| value instanceof am.ik.rontolisp.LispDouble) {
			return value;
		}
		throw new LispReadException("an unreadable value: " + value.print());
	}

	private LispVal decodeKeyword(LispCons wrapper) {
		if (wrapper.cdr() instanceof LispCons rest && rest.car() instanceof LispString spelling
				&& rest.cdr() instanceof LispNil) {
			return new LispSymbol(":" + spelling.value());
		}
		throw new LispReadException("an unreadable value: " + wrapper.print());
	}

	private LispVal decodeSet(LispCons wrapper) {
		if (wrapper.cdr() instanceof LispCons rest && rest.car() instanceof LispHashTable table
				&& rest.cdr() instanceof LispNil) {
			List<LispVal> elements = new ArrayList<>();
			elements.add(new LispSymbol("%hash-set"));
			for (LispHashTable.Entry entry : table.entries()) {
				elements.add(decodeDatum(entry.key()));
			}
			return list(elements);
		}
		throw new LispReadException("an unreadable value: " + wrapper.print());
	}

	/**
	 * The inverse of {@link #mangle}: {@code %%} is {@code %}, {@code %c} is {@code :},
	 * anything else travels as spelled.
	 */
	private static String unmangleName(String mangled) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < mangled.length(); i++) {
			char c = mangled.charAt(i);
			if (c == '%' && i + 1 < mangled.length()) {
				char next = mangled.charAt(i + 1);
				if (next == '%') {
					out.append('%');
					i++;
					continue;
				}
				if (next == 'c') {
					out.append(':');
					i++;
					continue;
				}
			}
			out.append(c);
		}
		return out.toString();
	}

	/**
	 * {@code `form}: syntax-quote, lowered to {@code quote} with unquote splicing over
	 * the mangled namespace. Every symbol qualifies (the {@code c%} prefix, the
	 * documented deviation: there are no namespaces to qualify against); {@code ~} lowers
	 * its form as code, {@code ~@} splices a sequence into the enclosing list, vector,
	 * map or set, and each {@code x#} binds one {@code (gensym "x")} per syntax-quote
	 * node, so the name is one symbol per expansion and the same symbol at every
	 * occurrence within it.
	 */
	private LispVal syntaxQuote(LispVal datum) {
		return syntaxQuoteNode(datum);
	}

	private LispVal syntaxQuoteNode(LispVal datum) {
		Map<String, LispSymbol> gens = new LinkedHashMap<>();
		this.syntaxGens.add(gens);
		try {
			LispVal body = syntaxQuoted(datum, 1, gens);
			if (gens.isEmpty()) {
				return body;
			}
			List<LispVal> bindings = new ArrayList<>();
			for (Map.Entry<String, LispSymbol> entry : gens.entrySet()) {
				bindings.add(list(entry.getValue(), list(sym("gensym"), LispString.literal(entry.getKey()))));
			}
			return list(sym("let"), list(bindings), body);
		}
		finally {
			this.syntaxGens.remove(this.syntaxGens.size() - 1);
		}
	}

	private LispVal syntaxQuoted(LispVal datum, int level, Map<String, LispSymbol> gens) {
		List<LispVal> marked = items(datum, List.of());
		if (!marked.isEmpty() && marked.get(0) == ClojureReader.VECTOR) {
			return syntaxQuotedVector(marked.subList(1, marked.size()), level, gens);
		}
		if (!marked.isEmpty() && isSymbolNamed(marked.get(0), "%hash-map")) {
			return syntaxQuotedMap(marked.subList(1, marked.size()), level, gens);
		}
		if (!marked.isEmpty() && isSymbolNamed(marked.get(0), "%hash-set")) {
			return syntaxQuotedSet(marked.subList(1, marked.size()), level, gens);
		}
		if (datum instanceof LispCons) {
			List<LispVal> parts = items(datum);
			if (parts == null) {
				throw new LispReadException("a dotted list is not a Clojure form");
			}
			if (!parts.isEmpty() && parts.get(0) instanceof LispSymbol head) {
				if (head.name().equals("syntax-quote")) {
					isTrue(parts.size() == 2, "syntax-quote takes one form");
					return syntaxQuoteNode(parts.get(1));
				}
				if (head.name().equals("unquote")) {
					isTrue(parts.size() == 2, "unquote takes one form");
					if (level == 1) {
						return unquoted(parts.get(1));
					}
					return list(sym("list"), syntaxQuotedSymbol("unquote"),
							syntaxQuoted(parts.get(1), level - 1, gens));
				}
				if (head.name().equals("unquote-splicing")) {
					isTrue(parts.size() == 2, "unquote-splicing takes one form");
					if (level == 1) {
						throw new LispReadException(
								"unquote-splicing outside a sequence: `~@` only splices inside a list, vector, map or set");
					}
					return list(sym("list"), syntaxQuotedSymbol("unquote-splicing"),
							syntaxQuoted(parts.get(1), level - 1, gens));
				}
			}
			return syntaxQuotedSeq(parts, level, gens);
		}
		if (datum instanceof LispSymbol s) {
			return syntaxQuotedSymbol(s.name(), gens);
		}
		return datum; // numbers, strings and characters are self-evaluating
	}

	private LispVal syntaxQuotedSymbol(String name) {
		return syntaxQuotedSymbol(name, null);
	}

	private LispVal syntaxQuotedSymbol(String name, @Nullable Map<String, LispSymbol> gens) {
		if (name.equals("nil")) {
			return NIL_CONST;
		}
		if (name.equals("true")) {
			return TRUE_CONST;
		}
		if (name.equals("false")) {
			return this.falseVariable;
		}
		if (name.startsWith(":")) {
			validateKeyword(name);
			return keywordForm(name.substring(1));
		}
		if (name.endsWith("#") && name.length() > 1 && gens != null) {
			String stem = name.substring(0, name.length() - 1);
			LispSymbol bound = gens.get(stem);
			if (bound == null) {
				bound = freshTemp();
				gens.put(stem, bound);
			}
			return bound;
		}
		return list(sym("quote"), idSym(name));
	}

	/**
	 * An unquoted form: code, lowered as written -- except {@code ~x#}, which answers the
	 * gensym the enclosing template bound for {@code x#}.
	 */
	private LispVal unquoted(LispVal datum) {
		if (datum instanceof LispSymbol s && s.name().endsWith("#") && s.name().length() > 1) {
			String stem = s.name().substring(0, s.name().length() - 1);
			for (int i = this.syntaxGens.size() - 1; i >= 0; i--) {
				LispSymbol bound = this.syntaxGens.get(i).get(stem);
				if (bound != null) {
					return bound;
				}
			}
		}
		return lower(datum);
	}

	private LispVal syntaxQuotedSeq(List<LispVal> parts, int level, Map<String, LispSymbol> gens) {
		List<LispVal> segments = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		for (LispVal element : parts) {
			List<LispVal> spliced = splicingOf(element, level);
			if (spliced != null) {
				if (!run.isEmpty()) {
					segments.add(cons(sym("list"), run));
					run = new ArrayList<>();
				}
				segments.add(seqForm(lower(spliced.get(1))));
				continue;
			}
			List<LispVal> single = unquoteOf(element, level);
			run.add(single != null ? unquoted(single.get(1)) : syntaxQuoted(element, level, gens));
		}
		if (!run.isEmpty()) {
			segments.add(cons(sym("list"), run));
		}
		if (segments.isEmpty()) {
			return NIL_CONST;
		}
		if (segments.size() == 1) {
			return segments.get(0);
		}
		return cons(sym("append"), segments);
	}

	/**
	 * The {@code (unquote-splicing X)} parts, or null when the element splices nothing.
	 */
	private static @Nullable List<LispVal> splicingOf(LispVal element, int level) {
		if (level != 1) {
			return null;
		}
		List<LispVal> parts = items(element);
		if (parts != null && parts.size() == 2 && isSymbolNamed(parts.get(0), "unquote-splicing")) {
			return parts;
		}
		return null;
	}

	/** The {@code (unquote X)} parts, or null when the element unquotes nothing. */
	private static @Nullable List<LispVal> unquoteOf(LispVal element, int level) {
		if (level != 1) {
			return null;
		}
		List<LispVal> parts = items(element);
		if (parts != null && parts.size() == 2 && isSymbolNamed(parts.get(0), "unquote")) {
			return parts;
		}
		return null;
	}

	private LispVal syntaxQuotedVector(List<LispVal> elements, int level, Map<String, LispSymbol> gens) {
		List<LispVal> segments = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		boolean spliced = false;
		for (LispVal element : elements) {
			List<LispVal> splice = splicingOf(element, level);
			if (splice != null) {
				if (!run.isEmpty()) {
					segments.add(cons(sym("list"), run));
					run = new ArrayList<>();
				}
				segments.add(seqForm(lower(splice.get(1))));
				spliced = true;
				continue;
			}
			List<LispVal> single = unquoteOf(element, level);
			run.add(single != null ? unquoted(single.get(1)) : syntaxQuoted(element, level, gens));
		}
		if (!spliced) {
			return cons(sym("vector"), run);
		}
		if (!run.isEmpty()) {
			segments.add(cons(sym("list"), run));
		}
		LispVal appended = segments.size() == 1 ? segments.get(0) : cons(sym("append"), segments);
		return list(sym("apply"), list(sym("function"), sym("vector")), appended);
	}

	private LispVal syntaxQuotedMap(List<LispVal> pairs, int level, Map<String, LispSymbol> gens) {
		List<LispVal> segments = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		boolean spliced = false;
		for (LispVal element : pairs) {
			List<LispVal> splice = splicingOf(element, level);
			if (splice != null) {
				if (!run.isEmpty()) {
					segments.add(cons(sym("list"), run));
					run = new ArrayList<>();
				}
				// a flat sequence of alternating keys and values, like the
				// literal pairs around it
				segments.add(seqForm(lower(splice.get(1))));
				spliced = true;
				continue;
			}
			List<LispVal> single = unquoteOf(element, level);
			run.add(single != null ? unquoted(single.get(1)) : syntaxQuoted(element, level, gens));
		}
		if (!spliced) {
			return mapBuild(run);
		}
		if (!run.isEmpty()) {
			segments.add(cons(sym("list"), run));
		}
		LispVal appended = segments.size() == 1 ? segments.get(0) : cons(sym("append"), segments);
		return tableFromPlist(appended);
	}

	private LispVal syntaxQuotedSet(List<LispVal> elements, int level, Map<String, LispSymbol> gens) {
		boolean spliced = false;
		for (LispVal element : elements) {
			if (splicingOf(element, level) != null) {
				spliced = true;
				break;
			}
		}
		if (!spliced) {
			List<LispVal> lowered = new ArrayList<>();
			for (LispVal element : elements) {
				List<LispVal> single = unquoteOf(element, level);
				lowered.add(single != null ? unquoted(single.get(1)) : syntaxQuoted(element, level, gens));
			}
			return setBuild(lowered);
		}
		LispSymbol table = freshTemp();
		List<LispVal> inits = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		for (LispVal element : elements) {
			List<LispVal> splice = splicingOf(element, level);
			if (splice != null) {
				if (!run.isEmpty()) {
					inits.add(setSplice(table, cons(sym("list"), run)));
					run = new ArrayList<>();
				}
				inits.add(setSplice(table, seqForm(lower(splice.get(1)))));
				continue;
			}
			List<LispVal> single = unquoteOf(element, level);
			run.add(single != null ? unquoted(single.get(1)) : syntaxQuoted(element, level, gens));
		}
		if (!run.isEmpty()) {
			inits.add(setSplice(table, cons(sym("list"), run)));
		}
		List<LispVal> body = new ArrayList<>(inits);
		body.add(wrapSet(table));
		return list(sym("let"), list(List.of(list(table, makeTable()))), cons(sym("progn"), body));
	}

	private LispVal setSplice(LispSymbol table, LispVal members) {
		LispSymbol one = freshTemp();
		return list(sym("dolist"), list(List.of(one, members)),
				list(sym("setf"), list(sym("gethash"), one, table), one));
	}

	/**
	 * {@code (gensym)} / {@code (gensym prefix)}: the ordinary uninterned symbol, a fresh
	 * one per evaluation -- per expansion inside a macro, per call at run time. An
	 * integer suffix spells itself ({@code (gensym 5)} is {@code #:G5}); anything else
	 * validates at run time, like the oracle.
	 */
	private LispVal gensymOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n <= 1, "gensym takes an optional prefix");
		if (n == 0) {
			return list(sym("gensym"));
		}
		return list(sym("gensym"), lower(items.get(1)));
	}

	/** {@code macroexpand-1} / {@code macroexpand} as a value: a one-argument lambda. */
	private LispVal macroexpandValue(String helper) {
		this.usedMacros = true;
		LispSymbol form = new LispSymbol(mangle("expand-form"));
		return list(sym("lambda"), list(form), list(new LispSymbol(helper), form));
	}

	/**
	 * The macro runtime, spliced once behind the false binding when the program defines
	 * or expands macros: the table lookup over the {@code c%name%macro} globals, the
	 * demangler (mangled symbols back to readable ones for printing; everything else
	 * travels untouched) and the once/fixpoint expanders. Pure lowering over the shared
	 * primitives, so every backend runs it unchanged.
	 */
	private List<LispVal> macroRuntime() {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol op = new LispSymbol("op");
		LispSymbol name = new LispSymbol("name");
		LispSymbol found = new LispSymbol("found");
		LispSymbol cell = new LispSymbol("cell");
		LispSymbol form = new LispSymbol("form");
		LispSymbol next = new LispSymbol("next");
		LispSymbol value = new LispSymbol("x");
		LispSymbol text = new LispSymbol("text");
		LispSymbol at = new LispSymbol("at");
		LispSymbol end = new LispSymbol("end");
		LispSymbol chars = new LispSymbol("chars");
		LispSymbol one = new LispSymbol("one");
		LispSymbol two = new LispSymbol("two");
		LispSymbol vec = new LispSymbol("vec");
		LispSymbol index = new LispSymbol("index");
		LispSymbol acc = new LispSymbol("acc");
		// (defun C%MACRO-FN (op) ...): the expander for a macro call's head, or nil
		LispVal isMangled = hfn("AND", hfn(">=", hfn("LENGTH", name), new LispInteger(2)),
				hfn("CHAR=", hfn("CHAR", name, new LispInteger(0)), new LispChar('c')),
				hfn("CHAR=", hfn("CHAR", name, new LispInteger(1)), new LispChar('%')));
		LispVal tabled = hlet(
				List.of(list(found,
						hfn("INTERN", hfn("CONCATENATE", quoted("string"), name, LispString.literal("%macro"))))),
				hfn("IF", hfn("BOUNDP", found), hlet(List.of(list(cell, hfn("SYMBOL-VALUE", found))),
						hfn("IF", hfn("FUNCTIONP", cell), cell, NIL_CONST)), NIL_CONST));
		runtime.add(hdefun("C%MACRO-FN", List.of(op),
				hfn("IF", hfn("SYMBOLP", op),
						hlet(List.of(list(name, hfn("SYMBOL-NAME", op))), hfn("IF", isMangled, tabled, NIL_CONST)),
						NIL_CONST)));
		// (defun C%UNMANGLE (text at end) ...): the demangled spelling as a string
		LispVal step = hfnDef("STEP", List.of(at, chars), hfn("IF", hfn(">=", at, end),
				hfn("APPLY", list(sym("function"), sym("concatenate")), quoted("string"), hfn("REVERSE", chars)),
				hlet(List.of(list(one, hfn("CHAR", text, at))),
						hfn("IF",
								hfn("AND", hfn("CHAR=", one, new LispChar('%')),
										hfn("<", hfn("+", at, new LispInteger(1)), end)),
								hlet(List.of(list(two, hfn("CHAR", text, hfn("+", at, new LispInteger(1))))),
										hfn("COND",
												list(hfn("CHAR=", two, new LispChar('%')),
														hfn("STEP", hfn("+", at, new LispInteger(2)),
																hfn("CONS", LispString.literal("%"), chars))),
												list(hfn("CHAR=", two, new LispChar('c')),
														hfn("STEP", hfn("+", at, new LispInteger(2)),
																hfn("CONS", LispString.literal(":"), chars))),
												list(TRUE_CONST,
														hfn("STEP", hfn("+", at, new LispInteger(1)),
																hfn("CONS", hfn("STRING", one), chars))))),
								hfn("STEP", hfn("+", at, new LispInteger(1)),
										hfn("CONS", hfn("STRING", one), chars))))));
		runtime.add(hdefun("C%UNMANGLE", List.of(text, at, end), hlabels(List.of(step), hfn("STEP", at, NIL_CONST))));
		// (defun C%DEMANGLE-SYMBOL (s) ...): a mangled symbol back to its readable name,
		// uppercased like every other symbol the printer spells (case folds, print-only)
		runtime.add(hdefun("C%DEMANGLE-SYMBOL", List.of(value), hlet(List.of(list(text, hfn("SYMBOL-NAME", value))),
				hfn("IF",
						hfn("AND", hfn(">=", hfn("LENGTH", text), new LispInteger(2)),
								hfn("CHAR=", hfn("CHAR", text, new LispInteger(0)), new LispChar('c')),
								hfn("CHAR=", hfn("CHAR", text, new LispInteger(1)), new LispChar('%'))),
						hfn("INTERN",
								hfn("STRING-UPCASE", hfn("C%UNMANGLE", text, new LispInteger(2), hfn("LENGTH", text)))),
						value))));
		// (defun C%DEMANGLE-VECTOR (v) ...): the elements demangled, in a fresh vector
		LispVal walkVec = hfnDef("WALK", List.of(index, acc),
				hfn("IF", hfn(">=", index, hfn("LENGTH", vec)), hfn("COERCE", hfn("REVERSE", acc), quoted("vector")),
						hfn("WALK", hfn("+", index, new LispInteger(1)),
								hfn("CONS", hfn("C%DEMANGLE", hfn("AREF", vec, index)), acc))));
		runtime.add(hdefun("C%DEMANGLE-VECTOR", List.of(vec),
				hlabels(List.of(walkVec), hfn("WALK", new LispInteger(0), NIL_CONST))));
		// (defun C%DEMANGLE (x) ...): lists and vectors demangled, anything else itself
		runtime.add(hdefun("C%DEMANGLE", List.of(value), hfn("COND", list(hfn("NULL", value), NIL_CONST),
				list(hfn("SYMBOLP", value), hfn("C%DEMANGLE-SYMBOL", value)),
				list(hfn("CONSP", value),
						hfn("CONS", hfn("C%DEMANGLE", hfn("CAR", value)), hfn("C%DEMANGLE", hfn("CDR", value)))),
				list(hfn("VECTORP", value), hfn("C%DEMANGLE-VECTOR", value)), list(TRUE_CONST, value))));
		// (defun C%MACROEXPAND-1 (form) ...): one expansion, demangled for printing
		runtime.add(hdefun(MACROEXPAND_1, List.of(form),
				hlet(List.of(list(next, hfn("IF", hfn("CONSP", form), hfn("C%MACRO-FN", hfn("CAR", form)), NIL_CONST))),
						hfn("C%DEMANGLE", hfn("IF", next, hfn("FUNCALL", next, hfn("CDR", form)), form)))));
		// (defun C%MACROEXPAND (form) ...): to the fixpoint, demangled once at the end
		LispVal walkExpand = hfnDef("WALK", List.of(form),
				hlet(List.of(list(next, hfn("IF", hfn("CONSP", form), hfn("C%MACRO-FN", hfn("CAR", form)), NIL_CONST))),
						hfn("IF", next, hfn("WALK", hfn("FUNCALL", next, hfn("CDR", form))), form)));
		runtime.add(
				hdefun(MACROEXPAND, List.of(form), hlabels(List.of(walkExpand), hfn("C%DEMANGLE", hfn("WALK", form)))));
		return runtime;
	}

	/**
	 * The false binding as a standalone form, for the macro-time evaluator the driver
	 * builds: the same form a file carries first, so expansions answer the false object
	 * the program compares against.
	 * @return the form
	 */
	public static LispVal falseBindingForm() {
		return list(sym("SETQ"), new LispSymbol(FALSE_VARIABLE), list(sym("QUOTE"), new LispSymbol(FALSE_VALUE_NAME)));
	}

	// hierarchies: derive/underive/isa?/parents/ancestors/descendants/make-hierarchy,
	// prefer-method and defmulti :hierarchy over one shared runtime

	/**
	 * The global hierarchy value: a map of {@code :parents}, {@code :ancestors} and
	 * {@code :descendants} tables, rebound by every global {@code derive}/
	 * {@code underive}. A lone {@code %} no mangled identifier spells, so no user
	 * definition can collide with it (like the multimethod helpers).
	 */
	private static final String HIERARCHY_GLOBAL = "C%H-GLOBAL";

	private static final String HIERARCHY_DISPATCH = "C%H-DISPATCH";

	/** The global hierarchy value as a form. */
	private static LispVal hierarchyGlobal() {
		return new LispSymbol(HIERARCHY_GLOBAL);
	}

	/**
	 * A raw call over already-built forms: the hierarchy runtime is Common Lisp, so its
	 * heads name core operations directly (never mangled, never re-lowered).
	 */
	private static LispVal hfn(String head, LispVal... args) {
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(head));
		out.addAll(List.of(args));
		return list(out);
	}

	/** A hierarchy map key: the keyword wrapper over its spelling. */
	private static LispVal hkey(String spelling) {
		return keywordForm(spelling);
	}

	private static LispVal hdefun(String name, List<LispVal> params, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(sym("defun"));
		form.add(new LispSymbol(name));
		form.add(list(params));
		form.addAll(List.of(body));
		return list(form);
	}

	/**
	 * A labels binding: {@code (name (params) body)} as one binding form.
	 */
	private static LispVal hfnDef(String name, List<LispVal> params, LispVal body) {
		return list(new LispSymbol(name), list(params), body);
	}

	/**
	 * A labels form over prebuilt bindings: no inline nesting, so the parentheses stay
	 * countable.
	 */
	private static LispVal hlabels(List<LispVal> fns, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(sym("labels"));
		form.add(list(fns));
		form.addAll(List.of(body));
		return list(form);
	}

	/**
	 * A let over prebuilt bindings (sequential, like the rest of the lowering).
	 */
	private static LispVal hlet(List<LispVal> bindings, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(sym("let*"));
		form.add(list(bindings));
		form.addAll(List.of(body));
		return list(form);
	}

	private static LispVal hmiss(LispSymbol miss) {
		return list(miss, hfn("LIST", NIL_CONST));
	}

	/**
	 * The hierarchy runtime, spliced once behind the false binding when the program uses
	 * hierarchies: set helpers over the wrapped-set shape, the global hierarchy value,
	 * the transitive {@code isa?}, and the multimethod miss search (candidates through
	 * {@code isa?}, the strictly most specific, {@code prefer-method} ties). Pure
	 * lowering over the shared table runtime, so every backend runs it unchanged.
	 */
	private List<LispVal> hierarchyRuntime() {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol table = new LispSymbol("table");
		LispSymbol key = new LispSymbol("key");
		LispSymbol setv = new LispSymbol("setv");
		LispSymbol member = new LispSymbol("member");
		LispSymbol members = new LispSymbol("members");
		LispSymbol nt = new LispSymbol("nt");
		LispSymbol miss = new LispSymbol("miss");
		LispSymbol found = new LispSymbol("found");
		LispSymbol pl = new LispSymbol("pl");
		LispSymbol p = new LispSymbol("p");
		LispSymbol acc = new LispSymbol("acc");
		LispSymbol x = new LispSymbol("x");
		LispSymbol lst = new LispSymbol("lst");
		LispSymbol h = new LispSymbol("h");
		LispSymbol child = new LispSymbol("child");
		LispSymbol parent = new LispSymbol("parent");
		LispSymbol parents = new LispSymbol("parents");
		LispSymbol anc = new LispSymbol("anc");
		LispSymbol desc = new LispSymbol("desc");
		LispSymbol done = new LispSymbol("done");
		LispSymbol c = new LispSymbol("c");
		LispSymbol c0 = new LispSymbol("c0");
		LispSymbol as = new LispSymbol("as");
		LispSymbol stack = new LispSymbol("stack");
		LispSymbol ps = new LispSymbol("ps");
		LispSymbol parts = new LispSymbol("parts");
		LispSymbol pc = new LispSymbol("pc");
		LispSymbol d = new LispSymbol("d");
		LispSymbol i = new LispSymbol("i");
		LispSymbol n = new LispSymbol("n");
		LispSymbol m = new LispSymbol("m");
		LispSymbol y = new LispSymbol("y");
		LispSymbol others = new LispSymbol("others");
		LispSymbol cs = new LispSymbol("cs");
		LispSymbol cands = new LispSymbol("cands");
		LispSymbol hier = new LispSymbol("hier");
		LispSymbol prefers = new LispSymbol("prefers");
		LispSymbol methods = new LispSymbol("methods");
		LispSymbol dv = new LispSymbol("dv");
		LispSymbol args = new LispSymbol("args");
		LispSymbol name = new LispSymbol("name");
		LispSymbol def = new LispSymbol("default");
		LispSymbol meth = new LispSymbol("meth");
		LispSymbol surv = new LispSymbol("surv");
		LispSymbol pick = new LispSymbol("pick");
		LispSymbol ss = new LispSymbol("ss");
		LispSymbol s = new LispSymbol("s");
		// (defun c%h-copy-table (table) ...)
		runtime.add(hdefun("C%H-COPY-TABLE", List.of(table), tableFromPlist(tablePlist(table))));
		// (defun c%h-empty-set () (list :c%set (make-hash-table ...)))
		runtime.add(hdefun("C%H-EMPTY-SET", List.of(), hfn("LIST", SET_TAG, makeTable())));
		// (defun c%h-get-set (table key) ...) with a nil-table guard
		runtime.add(
				hdefun("C%H-GET-SET", List.of(table, key),
						hfn("IF", hfn("NULL", table), hfn("C%H-EMPTY-SET"),
								letForm(List.of(hmiss(miss)), List.of(letForm(
										List.of(list(found, hfn("GETHASH", key, table, miss))),
										List.of(hfn("IF", hfn("EQ", found, miss), hfn("C%H-EMPTY-SET"), found))))))));
		// (defun c%h-set-add (setv member) ...)
		runtime.add(hdefun("C%H-SET-ADD", List.of(setv, member),
				letForm(List.of(list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", setv)))),
						List.of(hfn("SETF", hfn("GETHASH", member, nt), member), hfn("LIST", SET_TAG, nt)))));
		// (defun c%h-add-all (setv members) ...)
		LispVal addAllWalk = hfnDef("WALK", List.of(p), hfn("IF", hfn("NULL", p), NIL_CONST, hfn("PROGN",
				hfn("SETF", hfn("GETHASH", hfn("CAR", p), nt), hfn("CAR", p)), hfn("WALK", hfn("CDR", p)))));
		runtime.add(hdefun("C%H-ADD-ALL", List.of(setv, members),
				hlet(List.of(list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", setv)))),
						hlabels(List.of(addAllWalk), hfn("WALK", members)), hfn("LIST", SET_TAG, nt))));
		// (defun c%h-set-list (setv) ...): the members of a set wrapper
		LispVal setListWalk = hfnDef("WALK", List.of(p, acc), hfn("IF", hfn("NULL", p), acc,
				hfn("WALK", hfn("CDR", hfn("CDR", p)), hfn("CONS", hfn("CAR", p), acc))));
		runtime.add(hdefun("C%H-SET-LIST", List.of(setv), hlet(List.of(list(pl, tablePlist(hfn("CADR", setv)))),
				hlabels(List.of(setListWalk), hfn("WALK", pl, NIL_CONST)))));
		// (defun c%h-mem? (x lst) ...): equal membership, t-or-nil
		LispVal memWalk = hfnDef("WALK", List.of(p), hfn("IF", hfn("NULL", p), NIL_CONST,
				hfn("IF", hfn("EQUAL", hfn("CAR", p), x), TRUE_CONST, hfn("WALK", hfn("CDR", p)))));
		runtime.add(hdefun("C%H-MEM?", List.of(x, lst), hlabels(List.of(memWalk), hfn("WALK", lst))));
		// (defun c%h-rebuild (parents) ...): transitive ancestors and descendants
		LispVal rebuildEach = hfnDef("EACH", List.of(ps),
				hfn("IF", hfn("NULL", ps), NIL_CONST,
						hfn("PROGN", hfn("SETF", acc, hfn("C%H-ADD-ALL", acc,
								hfn("CONS", hfn("CAR", ps),
										hfn("C%H-SET-LIST", hfn("WALK", hfn("CAR", ps), hfn("CONS", c, stack)))))),
								hfn("EACH", hfn("CDR", ps)))));
		LispVal walkFn = hfnDef("WALK", List.of(c, stack),
				hfn("IF", hfn("EQ", hfn("GETHASH", c, done, miss), miss),
						hfn("IF", hfn("C%H-MEM?", c, stack), hfn("C%H-EMPTY-SET"),
								hlet(List.of(list(acc, hfn("C%H-EMPTY-SET"))),
										hlabels(List.of(rebuildEach),
												hfn("EACH", hfn("C%H-SET-LIST", hfn("C%H-GET-SET", parents, c)))),
										hfn("SETF", hfn("GETHASH", c, done), TRUE_CONST),
										hfn("SETF", hfn("GETHASH", c, anc), acc), acc)),
						hfn("C%H-GET-SET", anc, c)));
		LispVal driveFn = hfnDef("DRIVE", List.of(p), hfn("IF", hfn("NULL", p), NIL_CONST,
				hfn("PROGN", hfn("WALK", hfn("CAR", p), NIL_CONST), hfn("DRIVE", hfn("CDR", hfn("CDR", p))))));
		LispVal invEach = hfnDef("EACH2", List.of(as, c0),
				hfn("IF", hfn("NULL", as), NIL_CONST,
						hfn("PROGN",
								hfn("SETF", hfn("GETHASH", hfn("CAR", as), desc),
										hfn("C%H-SET-ADD", hfn("C%H-GET-SET", desc, hfn("CAR", as)), c0)),
								hfn("EACH2", hfn("CDR", as), c0))));
		LispVal invFn = hfnDef("INV", List.of(p), hfn("IF", hfn("NULL", p), NIL_CONST,
				hfn("PROGN",
						hlabels(List.of(invEach), hfn("EACH2", hfn("C%H-SET-LIST", hfn("CADR", p)), hfn("CAR", p))),
						hfn("INV", hfn("CDR", hfn("CDR", p))))));
		runtime.add(hdefun("C%H-REBUILD", List.of(parents),
				hlet(List.of(list(anc, makeTable()), list(desc, makeTable()), list(done, makeTable()), hmiss(miss)),
						hlabels(List.of(walkFn, driveFn, invFn), hfn("DRIVE", tablePlist(parents)),
								hfn("INV", tablePlist(anc))),
						hfn("LIST", anc, desc))));
		// (defun c%h-new-map (parents anc desc) ...): the three tables as a hierarchy
		// value
		LispSymbol newParents = new LispSymbol("new-parents");
		LispSymbol newAnc = new LispSymbol("new-anc");
		LispSymbol newDesc = new LispSymbol("new-desc");
		LispVal newMap = tableFromPlist(
				hfn("LIST", hkey("parents"), newParents, hkey("ancestors"), newAnc, hkey("descendants"), newDesc));
		runtime.add(hdefun("C%H-NEW-MAP", List.of(newParents, newAnc, newDesc), newMap));
		LispVal remake = hfn("C%H-NEW-MAP", parents, hfn("CAR", parts), hfn("CAR", hfn("CDR", parts)));
		// (defun c%h-derive (h child parent) ...)
		runtime.add(hdefun("C%H-DERIVE", List.of(h, child, parent),
				hlet(List.of(list(parents, hfn("C%H-COPY-TABLE", hfn("GETHASH", hkey("parents"), h)))),
						hfn("SETF", hfn("GETHASH", child, parents),
								hfn("C%H-SET-ADD", hfn("C%H-GET-SET", parents, child), parent)),
						hlet(List.of(list(parts, hfn("C%H-REBUILD", parents))), remake))));
		// (defun c%h-underive (h child parent) ...)
		runtime.add(
				hdefun("C%H-UNDERIVE", List.of(h, child, parent), hlet(
						List.of(list(parents, hfn("C%H-COPY-TABLE",
								hfn("GETHASH", hkey("parents"), h))), hmiss(
										miss)),
						hlet(List.of(list(pc, hfn("GETHASH", child, parents, miss))),
								hfn("IF", hfn("EQ", pc, miss), NIL_CONST,
										hlet(List.of(list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", pc)))),
												hfn("REMHASH", parent, nt),
												hfn("SETF", hfn("GETHASH", child, parents),
														hfn("LIST", SET_TAG, nt))))),
						hlet(List.of(list(parts, hfn("C%H-REBUILD", parents))), remake))));
		// (defun c%h-vec-isa? (h c p i n m) ...): element-wise vector derivation
		runtime.add(hdefun("C%H-VEC-ISA?", List.of(h, c, p, i, n, m),
				hfn("IF", hfn("NOT", hfn("EQL", n, m)), NIL_CONST,
						hfn("IF", hfn(">=", i, n), TRUE_CONST,
								hfn("IF", hfn("C%H-ISA?", h, hfn("AREF", c, i), hfn("AREF", p, i)),
										hfn("C%H-VEC-ISA?", h, c, p, hfn("+", i, new LispInteger(1)), n, m),
										NIL_CONST)))));
		// (defun c%h-isa? (h child parent) ...): equal, vector-wise, or an ancestor walk
		runtime.add(hdefun("C%H-ISA?", List.of(h, child, parent), hfn("IF", hfn("EQUAL", child, parent), TRUE_CONST,
				hfn("IF", hfn("AND", hfn("VECTORP", child), hfn("VECTORP", parent)),
						hfn("C%H-VEC-ISA?", h, child, parent, new LispInteger(0), hfn("LENGTH", child),
								hfn("LENGTH", parent)),
						hfn("IF",
								hfn("C%H-MEM?", parent,
										hfn("C%H-SET-LIST",
												hfn("C%H-GET-SET", hfn("GETHASH", hkey("ancestors"), h), child))),
								TRUE_CONST, NIL_CONST)))));
		// parents/ancestors/descendants reads, and the empty hierarchy value
		runtime.add(hdefun("C%H-PARENTS", List.of(h, child),
				hfn("C%H-GET-SET", hfn("GETHASH", hkey("parents"), h), child)));
		runtime.add(hdefun("C%H-ANCESTORS", List.of(h, child),
				hfn("C%H-GET-SET", hfn("GETHASH", hkey("ancestors"), h), child)));
		runtime.add(hdefun("C%H-DESCENDANTS", List.of(h, child),
				hfn("C%H-GET-SET", hfn("GETHASH", hkey("descendants"), h), child)));
		runtime.add(hdefun("C%H-EMPTY", List.of(), tableFromPlist(hfn("LIST", hkey("parents"), makeTable(),
				hkey("ancestors"), makeTable(), hkey("descendants"), makeTable()))));
		// (defun c%h-preferred? (x y prefers) ...)
		runtime.add(hdefun("C%H-PREFERRED?", List.of(x, y, prefers), hlet(List.of(hmiss(miss)),
				hfn("IF", hfn("EQ", hfn("GETHASH", hfn("CONS", x, y), prefers, miss), miss), NIL_CONST, TRUE_CONST))));
		// (defun c%h-more-specific? (c d hier) ...): c descends from d, not vice versa
		runtime.add(hdefun("C%H-MORE-SPECIFIC?", List.of(c, d, hier), hfn("IF", hfn("C%H-ISA?", hier, c, d),
				hfn("IF", hfn("C%H-ISA?", hier, d, c), NIL_CONST, TRUE_CONST), NIL_CONST)));
		// (defun c%h-survivors (cands hier prefers) ...): undominated candidates
		LispVal badFn = hfnDef("BAD?", List.of(d, others),
				hfn("IF", hfn("NULL", others), NIL_CONST,
						hfn("IF",
								hfn("AND", hfn("C%H-MORE-SPECIFIC?", hfn("CAR", others), d, hier),
										hfn("NOT", hfn("EQUAL", hfn("CAR", others), d)),
										hfn("NOT", hfn("C%H-PREFERRED?", d, hfn("CAR", others), prefers))),
								TRUE_CONST, hfn("BAD?", d, hfn("CDR", others)))));
		LispVal keepFn = hfnDef("KEEP", List.of(cs, acc), hfn("IF", hfn("NULL", cs), acc, hfn("KEEP", hfn("CDR", cs),
				hfn("IF", hfn("BAD?", hfn("CAR", cs), cands), acc, hfn("CONS", hfn("CAR", cs), acc)))));
		runtime.add(hdefun("C%H-SURVIVORS", List.of(cands, hier, prefers),
				hlabels(List.of(badFn, keepFn), hfn("KEEP", cands, NIL_CONST))));
		// (defun c%h-candidates (methods hier dv) ...): methods the value descends from
		LispVal candWalk = hfnDef("WALK", List.of(p, acc),
				hfn("IF", hfn("NULL", p), acc, hfn("WALK", hfn("CDR", hfn("CDR", p)),
						hfn("IF", hfn("C%H-ISA?", hier, dv, hfn("CAR", p)), hfn("CONS", hfn("CAR", p), acc), acc))));
		runtime.add(hdefun("C%H-CANDIDATES", List.of(methods, hier, dv),
				hlet(List.of(list(pl, tablePlist(methods))), hlabels(List.of(candWalk), hfn("WALK", pl, NIL_CONST)))));
		// (defun c%h-pick (surv prefers) ...): the survivor preferred over every other
		LispVal beatsFn = hfnDef("BEATS-ALL?", List.of(s, others),
				hfn("IF", hfn("NULL", others), TRUE_CONST,
						hfn("IF", hfn("EQUAL", hfn("CAR", others), s), hfn("BEATS-ALL?", s, hfn("CDR", others)),
								hfn("IF", hfn("C%H-PREFERRED?", s, hfn("CAR", others), prefers),
										hfn("BEATS-ALL?", s, hfn("CDR", others)), NIL_CONST))));
		LispVal findFn = hfnDef("FIND", List.of(ss), hfn("IF", hfn("NULL", ss), NIL_CONST,
				hfn("IF", hfn("BEATS-ALL?", hfn("CAR", ss), surv), hfn("CAR", ss), hfn("FIND", hfn("CDR", ss)))));
		runtime.add(hdefun("C%H-PICK", List.of(surv, prefers), hlabels(List.of(beatsFn, findFn), hfn("FIND", surv))));
		// (defun c%h-dispatch (name methods prefers hier default dv args) ...)
		LispVal noMethod = hfn("ERROR",
				hfn("CONCATENATE", quoted("string"), LispString.literal("No method in "), name,
						LispString.literal(" for dispatch value: "),
						hfn("RONTOLISP::%CLOJURE-STR-OF", dv, LispString.literal("nil"), NIL_CONST)));
		LispVal ambiguous = hfn("ERROR",
				hfn("CONCATENATE", quoted("string"), LispString.literal("Multiple methods in multimethod '"), name,
						LispString.literal("' match dispatch value: "),
						hfn("RONTOLISP::%CLOJURE-STR-OF", dv, LispString.literal("nil"), NIL_CONST),
						LispString.literal(", and neither is preferred")));
		runtime.add(hdefun(HIERARCHY_DISPATCH, List.of(name, methods, prefers, hier, def, dv, args),
				hlet(List.of(list(cands, hfn("C%H-CANDIDATES", methods, hier, dv))), hfn("IF", hfn("NULL", cands),
						hlet(List.of(hmiss(miss), list(meth, hfn("GETHASH", def, methods, miss))),
								hfn("IF", hfn("EQ", meth, miss), noMethod, hfn("APPLY", meth, args))),
						hfn("IF", hfn("NULL", hfn("CDR", cands)),
								hfn("APPLY", hfn("GETHASH", hfn("CAR", cands), methods), args),
								hlet(List.of(list(surv, hfn("C%H-SURVIVORS", cands, hier, prefers))),
										hfn("IF", hfn("NULL", surv), ambiguous,
												hlet(List.of(list(pick, hfn("C%H-PICK", surv, prefers))), hfn("IF",
														hfn("NULL", pick), ambiguous,
														hfn("APPLY", hfn("GETHASH", pick, methods), args))))))))));
		// the global hierarchy value, bound after its builders
		runtime.add(list(sym("setq"), hierarchyGlobal(), hfn("C%H-EMPTY")));
		return runtime;
	}

	/**
	 * The ex-info runtime, spliced once behind the false binding when the program throws
	 * or carries exception data: a condition with message and data slots (whose report
	 * prints the message), a predicate over it, and the throw/data/message helpers. Pure
	 * lowering over the shared condition runtime, so every backend runs it unchanged.
	 */
	private List<LispVal> exInfoRuntime() {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol cls = new LispSymbol("C%E-EX-INFO");
		LispSymbol cond = new LispSymbol("c");
		LispSymbol stream = new LispSymbol("s");
		LispSymbol value = new LispSymbol("v");
		LispSymbol ex = new LispSymbol("e");
		List<LispVal> slots = List.of(
				list(new LispSymbol("C%E-MESSAGE"), sym(":initarg"), sym(":message"), sym(":reader"),
						new LispSymbol("C%E-EX-INFO-MESSAGE")),
				list(new LispSymbol("C%E-DATA"), sym(":initarg"), sym(":data"), sym(":reader"),
						new LispSymbol("C%E-EX-INFO-DATA")));
		LispVal report = list(sym(":report"), list(sym("lambda"), list(List.of(cond, stream)), list(sym("format"),
				stream, LispString.literal("~a"), list(new LispSymbol("C%E-EX-INFO-MESSAGE"), cond))));
		runtime.add(list(sym("define-condition"), cls, list(List.of(sym("error"))), list(slots), report));
		runtime.add(list(sym("defun"), new LispSymbol("C%E-EX-INFO?"), list(List.of(value)),
				list(sym("typep"), value, list(sym("quote"), cls))));
		runtime.add(list(sym("defun"), new LispSymbol("C%E-THROW"), list(List.of(value)),
				list(sym("if"), list(new LispSymbol("C%E-EX-INFO?"), value), list(sym("error"), value),
						list(sym("error"), list(CLOJURE_STR_OF, value, LispString.literal("nil"), NIL_CONST)))));
		runtime.add(list(sym("defun"), new LispSymbol("C%E-DATA"), list(List.of(ex)), list(sym("if"),
				list(new LispSymbol("C%E-EX-INFO?"), ex), list(new LispSymbol("C%E-EX-INFO-DATA"), ex), NIL_CONST)));
		runtime.add(list(sym("defun"), new LispSymbol("C%E-MESSAGE"), list(List.of(ex)),
				list(sym("if"), list(new LispSymbol("C%E-EX-INFO?"), ex),
						list(new LispSymbol("C%E-EX-INFO-MESSAGE"), ex),
						list(CLOJURE_STR_OF, ex, LispString.literal("nil"), NIL_CONST))));
		return runtime;
	}

	/**
	 * A hierarchy call: {@code derive}/{@code underive} (two forms on the global
	 * hierarchy, three returning an updated hierarchy value), {@code isa?} (two or three,
	 * answering {@code T}-or-false), {@code parents}/{@code ancestors}/
	 * {@code descendants} (one or two, answering sets) and {@code make-hierarchy} (none,
	 * a fresh hierarchy value).
	 */
	private LispVal hierarchyOp(List<LispVal> items) {
		String name = ((LispSymbol) items.get(0)).name();
		int n = items.size() - 1;
		this.usedHierarchy = true;
		return switch (name) {
			case "derive" -> {
				if (n == 2) {
					yield list(sym("progn"), list(sym("setq"), hierarchyGlobal(), list(new LispSymbol("C%H-DERIVE"),
							hierarchyGlobal(), lower(items.get(1)), lower(items.get(2)))), NIL_CONST);
				}
				isTrue(n == 3, "derive takes a child and a parent, or a hierarchy and both");
				yield list(new LispSymbol("C%H-DERIVE"), lower(items.get(1)), lower(items.get(2)), lower(items.get(3)));
			}
			case "underive" -> {
				if (n == 2) {
					yield list(sym("progn"), list(sym("setq"), hierarchyGlobal(), list(new LispSymbol("C%H-UNDERIVE"),
							hierarchyGlobal(), lower(items.get(1)), lower(items.get(2)))), NIL_CONST);
				}
				isTrue(n == 3, "underive takes a child and a parent, or a hierarchy and both");
				yield list(new LispSymbol("C%H-UNDERIVE"), lower(items.get(1)), lower(items.get(2)),
						lower(items.get(3)));
			}
			case "isa?" -> {
				isTrue(n == 2 || n == 3, "isa? takes a child and a parent, or a hierarchy and both");
				LispVal hier = n == 3 ? lower(items.get(1)) : hierarchyGlobal();
				LispVal child = lower(items.get(n == 3 ? 2 : 1));
				LispVal parent = lower(items.get(n == 3 ? 3 : 2));
				yield booleanAnswer(list(new LispSymbol("C%H-ISA?"), hier, child, parent));
			}
			case "parents", "ancestors", "descendants" -> {
				isTrue(n == 1 || n == 2, name + " takes a child, or a hierarchy and a child");
				LispVal hier = n == 2 ? lower(items.get(1)) : hierarchyGlobal();
				LispVal child = lower(items.get(n == 2 ? 2 : 1));
				yield list(new LispSymbol("C%H-" + name.toUpperCase(java.util.Locale.ROOT)), hier, child);
			}
			case "make-hierarchy" -> {
				isTrue(n == 0, "make-hierarchy takes no arguments");
				yield list(new LispSymbol("C%H-EMPTY"));
			}
			default -> throw new LispReadException("unknown name: " + name);
		};
	}

	/**
	 * {@code (prefer-method name x y)}: {@code x} wins over {@code y} when both match a
	 * dispatch of {@code name}. The multimethod must be defined -- forward order aside,
	 * the pre-scan declares every {@code defmulti} first -- and answers itself, like
	 * {@code remove-method}.
	 */
	private LispVal preferMethodOf(List<LispVal> items) {
		isTrue(items.size() == 4, "prefer-method takes a multimethod and two dispatch values");
		String name = plainName(items.get(1), "prefer-method");
		isTrue(known(name), "No such multimethod: " + name);
		LispSymbol prefers = new LispSymbol(mangle(name) + "%prefers");
		this.usedHierarchy = true;
		return list(sym("progn"), list(sym("setf"),
				list(sym("gethash"), list(sym("cons"), lower(items.get(2)), lower(items.get(3))), prefers), TRUE_CONST),
				list(sym("function"), idSym(name)));
	}

	// platform: Java interop over the java: surface

	private static final LispSymbol JAVA_CALL = new LispSymbol("JAVA:CALL");

	private static final LispSymbol JAVA_STATIC = new LispSymbol("JAVA:STATIC");

	private static final LispSymbol JAVA_NEW = new LispSymbol("JAVA:NEW");

	private static final LispSymbol JAVA_FIELD = new LispSymbol("JAVA:FIELD");

	private static final LispSymbol JAVA_PROXY = new LispSymbol("JAVA:PROXY");

	/**
	 * A possible interop head: {@code (.} target method ...), {@code (.. ...)} chains,
	 * {@code (.method target ...)} and {@code (.-field target)} instance forms,
	 * {@code (Class. ...)} construction and {@code (Class/member ...)} statics. Null when
	 * the name is no interop shape, so the call keeps falling through.
	 */
	private @Nullable LispVal interopCall(String name, List<LispVal> items) {
		if (name.equals(".")) {
			return dotForm(items);
		}
		if (name.equals("..")) {
			return dotDotOf(items);
		}
		if (name.startsWith(".")) {
			if (name.startsWith(".-")) {
				isTrue(name.length() > 2, "a field read needs a field name: " + name);
				isTrue(items.size() == 2, name + " takes a target");
				return cons(JAVA_FIELD, List.of(lower(items.get(1)), LispString.literal(name.substring(2))));
			}
			isTrue(name.length() > 1, "a method call needs a method name");
			isTrue(items.size() >= 2, name + " takes a target and arguments");
			return instanceCall(lower(items.get(1)), name.substring(1), items.subList(2, items.size()));
		}
		if (name.endsWith(".") && name.length() > 1 && isClassSpelling(name.substring(0, name.length() - 1))) {
			List<LispVal> args = new ArrayList<>();
			args.add(LispString.literal(resolveClass(name.substring(0, name.length() - 1))));
			args.addAll(lowers(items, 1));
			return cons(JAVA_NEW, args);
		}
		int slash = name.indexOf('/');
		if (slash > 0) {
			String head = name.substring(0, slash);
			String tail = name.substring(slash + 1);
			if (!tail.isEmpty() && tail.indexOf('/') < 0 && isClasslike(head)) {
				String cls = resolveClass(head);
				if (items.size() == 1) {
					// no arguments reads a static field: a zero-argument static
					// method spells (. Class method) instead
					return cons(JAVA_FIELD, List.of(LispString.literal(cls), LispString.literal(tail)));
				}
				List<LispVal> args = new ArrayList<>();
				args.add(LispString.literal(cls));
				args.add(LispString.literal(tail));
				args.addAll(lowers(items, 1));
				return cons(JAVA_STATIC, args);
			}
		}
		return null;
	}

	/**
	 * {@code (. target method args...)}: a static call when the target symbol names a
	 * class (dotted, imported, {@code java.lang} or capitalized), an instance call
	 * otherwise. A {@code (method args...)} list spells the call, with no further
	 * arguments beside it.
	 */
	private LispVal dotForm(List<LispVal> items) {
		isTrue(items.size() >= 3, ". takes a target, a method and arguments");
		LispVal target = items.get(1);
		LispVal member = items.get(2);
		String method;
		List<LispVal> argDatums;
		List<LispVal> nested = items(member);
		if (nested != null && !nested.isEmpty()) {
			isTrue(items.size() == 3, ". with a call list takes no further arguments");
			if (!(nested.get(0) instanceof LispSymbol called)) {
				throw new LispReadException(". takes a method name, not " + nested.get(0).print());
			}
			method = called.name();
			argDatums = nested.subList(1, nested.size());
		}
		else {
			if (!(member instanceof LispSymbol called)) {
				throw new LispReadException(". takes a method name, not " + member.print());
			}
			method = called.name();
			argDatums = items.subList(3, items.size());
		}
		if (target instanceof LispSymbol named && isClasslike(named.name())) {
			List<LispVal> call = new ArrayList<>();
			call.add(LispString.literal(resolveClass(named.name())));
			call.add(LispString.literal(method));
			for (LispVal arg : argDatums) {
				call.add(lower(arg));
			}
			return cons(JAVA_STATIC, call);
		}
		return instanceCall(lower(target), method, argDatums);
	}

	/**
	 * {@code (.. target (step args...) name...)}: nested {@code .} datums around the
	 * running value -- a pure datum rewrite, lowered once built.
	 */
	private LispVal dotDotOf(List<LispVal> items) {
		isTrue(items.size() >= 2, ".. takes a target and steps");
		LispVal acc = items.get(1);
		for (int i = 2; i < items.size(); i++) {
			LispVal step = items.get(i);
			List<LispVal> form = new ArrayList<>();
			form.add(new LispSymbol("."));
			form.add(acc);
			List<LispVal> parts = items(step);
			if (parts != null && !parts.isEmpty()) {
				form.addAll(parts);
			}
			else if (step instanceof LispSymbol) {
				form.add(step);
			}
			else {
				throw new LispReadException(".. takes method names and call lists, not " + step.print());
			}
			acc = list(form);
		}
		return lower(acc);
	}

	/** {@code (new Class args...)}: construction through {@code java:new}. */
	private LispVal newOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "new takes a class and arguments");
		if (!(items.get(1) instanceof LispSymbol named)) {
			throw new LispReadException("new takes a class name, not " + items.get(1).print());
		}
		List<LispVal> args = new ArrayList<>();
		args.add(LispString.literal(resolveClass(named.name())));
		args.addAll(lowers(items, 2));
		return cons(JAVA_NEW, args);
	}

	/**
	 * {@code (memfn name arg...)}: a function of a target and the named arguments calling
	 * the method on it -- the oracle's expansion, so {@code ((memfn toUpperCase) "hi")}
	 * answers {@code "HI"}. String receivers take the mapped core operation, like any
	 * other instance call.
	 */
	private LispVal memfnOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "memfn takes a method name and argument names");
		String method = plainName(items.get(1), "memfn");
		Map<String, Kind> scope = new HashMap<>();
		Set<String> seen = new HashSet<>();
		List<LispVal> params = new ArrayList<>();
		LispSymbol recv = freshTemp();
		params.add(recv);
		List<LispVal> argForms = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			String pname = plainName(items.get(i), "memfn");
			isTrue(seen.add(pname), "memfn argument names must be distinct: " + pname);
			scope.put(pname, Kind.VARIABLE);
			params.add(idSym(pname));
			argForms.add(idSym(pname));
		}
		return inScope(scope, () -> list(sym("lambda"), list(params), instanceCallLowered(recv, method, argForms)));
	}

	/**
	 * {@code (proxy [interface] [] (method [params...] body...)...)}: a single interface
	 * implemented through {@code java:proxy} with a name-dispatching lambda. A
	 * superclass, constructor arguments and multi-arity methods are refused by name; the
	 * methods take the Java arguments only (no {@code this}, which has no binding to
	 * close over). Interpreter and JVM only, like all interop.
	 */
	private LispVal proxyOf(List<LispVal> items) {
		isTrue(items.size() >= 3, "proxy takes a class vector, an argument vector and methods");
		List<LispVal> classes = items(items.get(1));
		if (classes == null || classes.isEmpty() || classes.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("proxy takes a class vector, not " + items.get(1).print());
		}
		if (classes.size() != 2 || !(classes.get(1) instanceof LispSymbol)) {
			throw new LispReadException("proxy takes a single interface, not " + items.get(1).print());
		}
		LispSymbol className = (LispSymbol) classes.get(1);
		List<LispVal> argv = items(items.get(2));
		if (argv == null || argv.size() != 1) {
			throw new LispReadException("proxy constructor arguments are not supported yet: " + items.get(2).print());
		}
		String iface = resolveClass(className.name());
		LispSymbol all = freshTemp();
		LispSymbol got = freshTemp();
		LispSymbol rest = freshTemp();
		LispVal miss = list(sym("error"),
				list(sym("concatenate"), quoted("string"), LispString.literal("no proxy method: "), got));
		LispVal dispatch = miss;
		for (int i = items.size() - 1; i >= 3; i--) {
			List<LispVal> meth = items(items.get(i));
			if (meth == null || meth.size() < 3 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			List<LispVal> params = items(meth.get(1));
			if (params == null || params.isEmpty() || params.get(0) != ClojureReader.VECTOR) {
				throw new LispReadException("a proxy method takes a parameter vector, not " + meth.get(1).print());
			}
			Map<String, Kind> scope = new HashMap<>();
			Set<String> seen = new HashSet<>();
			List<LispVal> fnParams = new ArrayList<>();
			for (int j = 1; j < params.size(); j++) {
				String pname = plainName(params.get(j), "proxy");
				isTrue(seen.add(pname), "proxy parameter names must be distinct: " + pname);
				scope.put(pname, Kind.VARIABLE);
				fnParams.add(idSym(pname));
			}
			Map<String, Kind> use = new HashMap<>(scope);
			LispVal run = inScope(use, () -> list(sym("apply"),
					list(sym("lambda"), list(fnParams), bodyOf(meth.subList(2, meth.size()))), rest));
			LispVal test = list(sym("equal"), got, LispString.literal(methodName));
			dispatch = list(sym("if"), test, run, dispatch);
		}
		LispVal callable = list(sym("lambda"), list(List.of(AMPERSAND_REST, all)), list(sym("let"),
				list(List.of(list(got, list(sym("car"), all)), list(rest, list(sym("cdr"), all)))), dispatch));
		return cons(JAVA_PROXY, List.of(LispString.literal(iface), callable));
	}

	/**
	 * An instance call: the receiver runs once, behind a temporary; a string receiver
	 * answers the mapped core operation (a Lisp string is not a host object, so the
	 * {@code java:} surface cannot take it), anything else goes to {@code java:call}
	 * directly.
	 */
	private LispVal instanceCall(LispVal receiver, String method, List<LispVal> argDatums) {
		List<LispVal> args = new ArrayList<>();
		for (LispVal arg : argDatums) {
			args.add(lower(arg));
		}
		return instanceCallLowered(receiver, method, args);
	}

	/**
	 * An instance call over already-lowered forms: the receiver runs once, behind a
	 * temporary; a string receiver answers the mapped core operation, anything else goes
	 * to {@code java:call} directly.
	 */
	private LispVal instanceCallLowered(LispVal receiver, String method, List<LispVal> args) {
		LispSymbol recv = freshTemp();
		List<LispVal> direct = new ArrayList<>();
		direct.add(recv);
		direct.add(LispString.literal(method));
		direct.addAll(args);
		LispVal mapped = stringMethod(method, recv, args);
		LispVal call = mapped == null ? cons(JAVA_CALL, direct)
				: list(sym("if"), list(sym("stringp"), recv), mapped, cons(JAVA_CALL, direct));
		return list(sym("let"), list(List.of(list(recv, receiver))), call);
	}

	/**
	 * A {@code String} instance method over an already-bound string receiver: the core
	 * operation answering what the oracle answers (a missing {@code indexOf} is
	 * {@code -1}, like the oracle, not the {@code nil} {@code clojure.string} favors).
	 * Null when the method maps to nothing, so the call goes to {@code java:call}.
	 */
	private @Nullable LispVal stringMethod(String method, LispVal recv, List<LispVal> args) {
		return switch (method) {
			case "toUpperCase" -> args.isEmpty() ? list(sym("string-upcase"), recv) : null;
			case "toLowerCase" -> args.isEmpty() ? list(sym("string-downcase"), recv) : null;
			case "trim", "strip" -> args.isEmpty() ? list(sym("string-trim"), trimBag(), recv) : null;
			case "stripLeading" -> args.isEmpty() ? list(sym("string-left-trim"), trimBag(), recv) : null;
			case "stripTrailing" -> args.isEmpty() ? list(sym("string-right-trim"), trimBag(), recv) : null;
			case "length" -> args.isEmpty() ? list(sym("length"), recv) : null;
			case "isEmpty" -> args.isEmpty() ? booleanAnswer(list(sym("zerop"), list(sym("length"), recv))) : null;
			case "isBlank" -> args.isEmpty() ? booleanAnswer(blankForm(recv)) : null;
			case "toString" -> args.isEmpty() ? recv : null;
			case "substring" -> switch (args.size()) {
				case 1 -> list(sym("subseq"), recv, args.get(0));
				case 2 -> list(sym("subseq"), recv, args.get(0), args.get(1));
				default -> null;
			};
			case "charAt" -> args.size() == 1 ? list(sym("char"), recv, args.get(0)) : null;
			case "equals" -> args.size() == 1 ? booleanAnswer(list(sym("string="), recv, args.get(0))) : null;
			case "equalsIgnoreCase" ->
				args.size() == 1 ? booleanAnswer(list(sym("string-equal"), recv, args.get(0))) : null;
			case "contains" -> args.size() == 1 ? booleanAnswer(affixForm("includes?", recv, args.get(0))) : null;
			case "startsWith" -> args.size() == 1 ? booleanAnswer(affixForm("starts-with?", recv, args.get(0))) : null;
			case "endsWith" -> args.size() == 1 ? booleanAnswer(affixForm("ends-with?", recv, args.get(0))) : null;
			case "indexOf" -> indexForm(recv, args, false);
			case "lastIndexOf" -> indexForm(recv, args, true);
			case "replace" -> args.size() == 2 ? replaceForm(recv, args.get(0), args.get(1), false) : null;
			case "replaceFirst" -> args.size() == 2 ? replaceForm(recv, args.get(0), args.get(1), true) : null;
			case "split" -> switch (args.size()) {
				case 1 -> splitForm(recv, args.get(0), NIL_CONST, true);
				case 2 -> splitForm(recv, args.get(0), args.get(1), true);
				default -> null;
			};
			case "concat" -> args.size() == 1 ? list(sym("concatenate"), quoted("string"), recv, args.get(0)) : null;
			case "repeat" -> args.size() == 1 ? list(sym("apply"), list(sym("function"), sym("concatenate")),
					quoted("string"), list(sym("make-list"), args.get(0), sym(":initial-element"), recv)) : null;
			default -> null;
		};
	}

	/**
	 * {@code indexOf} / {@code lastIndexOf} over an already-bound string: the index, or
	 * {@code -1} when missing, like the oracle.
	 */
	private @Nullable LispVal indexForm(LispVal recv, List<LispVal> args, boolean last) {
		LispVal search = switch (args.size()) {
			case 1 -> last ? lastIndexForm(recv, args.get(0), null) : list(sym("search"), args.get(0), recv);
			case 2 -> last ? lastIndexForm(recv, args.get(0), args.get(1))
					: list(sym("search"), args.get(0), recv, sym(":start2"), args.get(1));
			default -> null;
		};
		if (search == null) {
			return null;
		}
		LispSymbol at = freshTemp();
		return list(sym("let"), list(List.of(list(at, search))),
				list(sym("if"), list(sym("null"), at), new LispInteger(-1), at));
	}

	/** Whether the spelling can name a class: a dotted name over identifier parts. */
	private static boolean isClassSpelling(String spelling) {
		if (spelling.isEmpty() || !Character.isJavaIdentifierStart(spelling.charAt(0))) {
			return false;
		}
		for (int i = 1; i < spelling.length(); i++) {
			char c = spelling.charAt(i);
			if (!Character.isJavaIdentifierPart(c) && c != '.') {
				return false;
			}
		}
		return true;
	}

	/** The Common Lisp symbol name of a Clojure identifier: always behind the prefix. */
	static String mangle(String identifier) {
		StringBuilder out = new StringBuilder(PREFIX);
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (c == '%') {
				out.append("%%");
			}
			else if (c == ':') {
				out.append("%c");
			}
			else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private boolean known(String name) {
		for (Map<String, Kind> scope : this.scopes) {
			if (scope.containsKey(name)) {
				return true;
			}
		}
		return this.globals.containsKey(name);
	}

	private boolean isFunction(String name) {
		for (Map<String, Kind> scope : this.scopes) {
			Kind kind = scope.get(name);
			if (kind != null) {
				return kind == Kind.FUNCTION;
			}
		}
		Kind global = this.globals.get(name);
		return global == Kind.FUNCTION;
	}

	private boolean isMacro(String name) {
		for (Map<String, Kind> scope : this.scopes) {
			Kind kind = scope.get(name);
			if (kind != null) {
				return kind == Kind.MACRO;
			}
		}
		return this.globals.get(name) == Kind.MACRO;
	}

	/**
	 * Who evaluates one macro application in the macro-time environment. A session keeps
	 * one lowering across buffers and sets this once, so a macro defined in one buffer
	 * expands in a later one.
	 * @param macroEvaluator the evaluator, or null for none
	 */
	void setMacroEvaluator(@Nullable ClojureMacroEvaluator macroEvaluator) {
		this.macroEvaluator = macroEvaluator;
	}

	private <T> T inScope(Map<String, Kind> scope, java.util.function.Supplier<T> body) {
		this.scopes.add(scope);
		try {
			return body.get();
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
		}
	}

	private enum Kind {

		VARIABLE, FUNCTION, MACRO

	}

	// shape helpers

	private static String plainName(LispVal val, String what) {
		if (val instanceof LispSymbol s && !s.name().startsWith(":") && !s.name().equals("&")) {
			return s.name();
		}
		throw new LispReadException(what + " needs a plain name, not " + val.print());
	}

	/** The items of a {@code [...]} vector datum, without its marker. */
	private static List<LispVal> bindingItems(LispVal vector, String what) {
		List<LispVal> items = items(vector);
		if (items == null || items.isEmpty() || items.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(what + " takes its bindings in a vector: " + vector.print());
		}
		return items.subList(1, items.size());
	}

	private static boolean isSymbolNamed(LispVal val, String name) {
		return val instanceof LispSymbol s && s.name().equals(name);
	}

	private static boolean isNsForm(LispVal val) {
		if (isSymbolNamed(val, "ns")) {
			return true;
		}
		List<LispVal> items = items(val);
		return items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "ns");
	}

	private static List<LispVal> items(LispVal val, List<LispVal> ifNone) {
		List<LispVal> items = items(val);
		return items == null ? ifNone : items;
	}

	private static @Nullable List<LispVal> items(LispVal val) {
		if (val instanceof LispNil) {
			return List.of();
		}
		if (!(val instanceof LispCons)) {
			return null;
		}
		List<LispVal> out = new ArrayList<>();
		LispVal run = val;
		while (run instanceof LispCons cons) {
			out.add(cons.car());
			run = cons.cdr();
		}
		if (!(run instanceof LispNil)) {
			return null;
		}
		return out;
	}

	private static LispVal cons(LispVal car, List<LispVal> rest) {
		return new LispCons(car, list(rest));
	}

	private static LispSymbol sym(String name) {
		return new LispSymbol(name.toUpperCase(java.util.Locale.ROOT));
	}

	/**
	 * A user identifier as a symbol: mangled behind the prefix but otherwise
	 * case-preserved, so {@code Foo} and {@code foo} stay apart. The prefix holds a
	 * lowercase letter, so no result can collide with a core form or built-in. A
	 * {@code #:}-spelled gensym a macro expansion returned travels as itself, so it
	 * lowers back to the same uninterned symbol.
	 */
	private static LispSymbol idSym(String identifier) {
		if (identifier.startsWith("#:")) {
			return new LispSymbol(identifier);
		}
		return new LispSymbol(mangle(identifier));
	}

	private static LispVal list(List<LispVal> items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			tail = new LispCons(items.get(i), tail);
		}
		return tail;
	}

	private static LispVal list(LispVal... items) {
		return list(List.of(items));
	}

	private static void isTrue(boolean ok, String message) {
		if (!ok) {
			throw new LispReadException(message);
		}
	}

}
