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
 * stays a no-copy identity. Laziness is the memoized-thunk wrapper {@code (:C%LAZY cell)}
 * (b11): {@code lazy-seq} builds one over its body (run at most once per object),
 * {@code lazy-cat} nests {@code concat} over per-member wrappers, and
 * {@code repeat}/{@code cycle}/{@code iterate}/{@code repeatedly} build wrapper chains
 * (their finite arities answer strict lists); an end-less {@code range} stays refused by
 * name, and {@code range} with an end builds the strict list. {@code take} steps through
 * one wrapper at a time and
 * {@code drop}/{@code first}/{@code rest}/{@code next}/{@code seq}/
 * {@code cons}/{@code concat}/{@code map}/{@code filter} realize through the same view,
 * so {@code (take 5 (iterate inc 0))} terminates; printing a wrapper (or a list holding a
 * lazy tail) refuses with {@code #<LazySeq>} instead of hanging. There is no chunking:
 * every element realizes singly. {@code nth} and {@code quot} as VALUES are
 * correctly-ordered lambdas wrapping the primitive (a bare {@code #'NTH} would have the
 * operands backwards). A keyword {@code :foo} is the list {@code (:C%KEYWORD "foo")}
 * holding its spelling verbatim (case-preserved, so {@code :a} and {@code :A} stay apart
 * and compare unequal); {@code println}/{@code print}/{@code str} spell it with its
 * colon, and a keyword in call position {@code (:k m)} (or with a default
 * {@code (:k m dflt)}) is the same table-aware read {@code get} lowers to. {@code false}
 * is a DISTINCT non-{@code NIL} object -- the value of {@code rontolisp::%clojure-false},
 * bound before anything else runs, a symbol spelled {@code false} -- so
 * {@code (= false nil)} is false and {@code (nil?
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
 * ({@code clojure.string} over the core string operations, {@code clojure.java.io} for
 * {@code reader} only); interop lowers to the {@code java:} surface. A {@code defmacro}
 * is a compile-time expander (one lambda over the call's argument list, the same function
 * the runtime table entry holds for {@code macroexpand-1}/{@code macroexpand}) plus
 * datum-to-datum expansion at lower time, so every backend runs expanded code;
 * syntax-quote lowers to {@code quote} with unquote splicing over the mangled namespace
 * ({@code x#} one gensym per expansion); {@code gensym} is the ordinary uninterned
 * symbol. {@code var}/{@code #'} stays refused. The reader spells characters, radix
 * integers and exact {@code M} decimals, and refuses regex literals by name.
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
	 * The namespace {@code ::}-keywords resolve against: the file's {@code ns} name (the
	 * seam reads the whole file, so the form order decides), or the session's
	 * {@code *ns*} (an {@code ns} or {@code in-ns} buffer switches it for the buffers
	 * below it). The oracle starts a REPL in {@code user}, so that is the default.
	 */
	private String currentNs = "user";

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

	/** The enclosing recur targets, innermost last; empty outside any. */
	private final Deque<RecurTarget> recurTargets = new ArrayDeque<>();

	/**
	 * Whether the form being lowered sits in the tail position of the innermost enclosing
	 * recur target's body: only there may a {@code recur} lower, like the oracle.
	 * {@link #lower} clears it (a call argument, an init or a test is never tail) and
	 * {@link #lowerTail} sets it; a body form inherits it for its last form, a target
	 * body (a clause, a {@code loop}, or a {@code lazy-seq} body for its own zero-arity
	 * target) forces it, and {@code doseq}, {@code dotimes} and {@code try} parts force
	 * it off (their bodies are never tail, and the {@code try} body additionally trips
	 * the barrier below).
	 */
	private boolean tailPosition;

	/**
	 * How many {@code try} bodies deep the lowering sits: a {@code recur} with a
	 * {@code try} between it and its target is the oracle's
	 * {@code Cannot recur across try} refusal. Each target captures this depth when
	 * pushed (see {@link #pushRecurTarget}), so a target opened inside the {@code try}
	 * still recurs.
	 */
	private int tryDepth;

	/**
	 * One enclosing {@code recur} target: the Common Lisp function a {@code recur} form
	 * calls with its lowered arguments. A {@code loop} pushes its {@code labels} name, a
	 * named {@code fn} or {@code letfn} entry its {@code labels} name, a {@code defn}
	 * clause its dispatch {@code defun} name, a multi-arity {@code fn} clause the shared
	 * dispatch name, a {@code lazy-seq} body a fresh {@code labels} name of arity 0, and
	 * an anonymous {@code fn} (or {@code #(...)}, or a stored method lambda) a fresh
	 * {@code labels} name the form wraps itself in when the target is used. A plain
	 * lambda that is none of these pushes nothing, so a {@code recur} passes through it
	 * to the enclosing target.
	 */
	private static final class RecurTarget {

		private final String callName;

		private final boolean checked;

		private int arity = -1;

		private boolean variadic;

		private boolean used;

		private @Nullable LispVal firstUse;

		/** The {@link #tryDepth} when this target was pushed. */
		private int depth;

		RecurTarget(String callName, boolean checked) {
			this.callName = callName;
			this.checked = checked;
		}

		String callName() {
			return this.callName;
		}

		boolean checked() {
			return this.checked;
		}

		int arity() {
			return this.arity;
		}

		boolean variadic() {
			return this.variadic;
		}

		int depth() {
			return this.depth;
		}

		void setArity(int arity, boolean variadic) {
			this.arity = arity;
			this.variadic = variadic;
		}

		boolean used() {
			return this.used;
		}

		void markUsed(LispVal recurForm) {
			if (!this.used) {
				this.used = true;
				this.firstUse = recurForm;
			}
		}

	}

	/**
	 * One recur target pushed with the current {@code try} depth captured, so a
	 * {@code recur} is across a {@code try} exactly when the depth grew since.
	 */
	private void pushRecurTarget(RecurTarget target) {
		target.depth = this.tryDepth;
		this.recurTargets.push(target);
	}

	/**
	 * One datum lowered in tail position: the tail slots (an {@code if} arm, a body's
	 * last form) of a form already in tail position. Anything else lowers through
	 * {@link #lower}, which clears the position.
	 */
	private LispVal lowerTail(LispVal form) {
		boolean outer = this.tailPosition;
		this.tailPosition = true;
		try {
			return lowerPositioned(form);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	/**
	 * One tail slot lowered: tail when the enclosing form is in tail position, non-tail
	 * otherwise.
	 */
	private LispVal lowerTailSlot(LispVal form) {
		return this.tailPosition ? lowerTail(form) : lower(form);
	}

	/**
	 * One body lowered in the target body's tail position: a clause or {@code loop} body
	 * forces the position its last form inherits.
	 */
	private LispVal bodyOfTail(List<LispVal> forms) {
		boolean outer = this.tailPosition;
		this.tailPosition = true;
		try {
			return bodyOf(forms);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	private LispVal bodyTail(List<LispVal> items, int from) {
		boolean outer = this.tailPosition;
		this.tailPosition = true;
		try {
			return body(items, from);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	/**
	 * One body lowered outside any tail position: a {@code doseq}, {@code dotimes} or
	 * {@code try} part is never tail, so a {@code recur} inside one is refused even in an
	 * enclosing tail.
	 */
	private LispVal nonTailBody(List<LispVal> items, int from) {
		boolean outer = this.tailPosition;
		this.tailPosition = false;
		try {
			return body(items, from);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	private LispVal nonTailBodyOf(List<LispVal> forms) {
		boolean outer = this.tailPosition;
		this.tailPosition = false;
		try {
			return bodyOf(forms);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	/**
	 * A fresh {@code labels} name for an anonymous recur target: behind the prefix like
	 * every mangled name, so no backend learns a Clojure name.
	 */
	private String freshRecurName() {
		return mangle("fn-") + this.counter++;
	}

	/**
	 * One already-lowered lambda wrapped in a {@code labels} self-binding under
	 * {@code callName}, answering the local: the shape a named {@code fn} always takes,
	 * an anonymous one only when a {@code recur} reached it.
	 */
	private static LispVal labelsSelfCall(String callName, LispVal lambda) {
		return labelsWithHead(callName, List.of(), lambda);
	}

	/** The rest parameter of the {@code #(...)} being lowered, or null outside one. */
	private @Nullable String anonArgs;

	/** The false value, referenced (never rebuilt) wherever {@code false} lowers. */
	private final LispSymbol falseVariable = new LispSymbol(FALSE_VARIABLE);

	/**
	 * The names bound to real functions, per {@link #scopes} level: a head-position call
	 * to one stays a direct call, while any other variable goes through the prelude
	 * dispatcher (its value may hold a collection). Globals live in
	 * {@link #globalDirectFuns}.
	 */
	private final List<Set<String>> directScopes = new ArrayList<>();

	/** The globals bound to real functions, like {@link #directScopes}. */
	private final Set<String> globalDirectFuns = new HashSet<>();

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
	 * Whether a {@code defmulti} dispatch function is being lowered: a {@code class} call
	 * inside one answers nil itself for nil (see {@link #classForm(LispVal)}), so the
	 * dispatcher's null test maps it onto the nil method's marker -- bare or wrapped in
	 * another function -- while an explicit {@code :nil} keyword keeps its row, like the
	 * oracle. Saved and restored around the dispatch lowering (see
	 * {@link #defmultiForms(List)}), so an ordinary {@code class} keeps answering the
	 * {@code :nil} keyword.
	 */
	private boolean inDispatchFn;

	/**
	 * A protocol the program defines or extends: its method names, the method-table
	 * global and the {@code Object}-default global. Protocols dispatch over the
	 * {@code C%PROTOCOL-TAG} of the target (the multimethod shape without the hierarchy
	 * search); the table maps a tag to the method lambda, the default global the
	 * {@code Object} row consulted on a miss.
	 */
	private record ProtocolDef(Set<String> methods, LispSymbol methodsVar, LispSymbol defaultVar) {
	}

	/**
	 * A record or deftype the program defines: whether it is map-like (a record) or
	 * opaque (a deftype), its declared field names and its dispatch-tag spelling.
	 */
	private record TypeDef(boolean record, List<String> fields, String tagSpelling) {
	}

	/** The protocols defined so far, by name (the whole-file pre-scan fills it first). */
	private final Map<String, ProtocolDef> protocols = new HashMap<>();

	/**
	 * The record/deftype names defined so far (the whole-file pre-scan fills it first).
	 */
	private final Map<String, TypeDef> types = new HashMap<>();

	/**
	 * Whether the program uses protocols, records, deftypes or reify: the protocol
	 * runtime (the tag reader) is spliced in once, behind the false binding.
	 */
	private boolean usedProtocols;

	/** Whether the protocol runtime was already spliced in (files splice it inline). */
	private boolean protocolsEmitted;

	/**
	 * Names declared {@code ^:dynamic}: only {@code binding} may rebind them, and only
	 * they may be rebound. {@code *agent*} is dynamic from the start (bound to the acting
	 * agent while a {@code send} runs, nil outside one).
	 */
	private final Set<String> dynamicVars = new HashSet<>(Set.of("*agent*"));

	/**
	 * A {@code let} local's host class, inferred from a construction-literal init: the
	 * FQN, the plain name (for the shadow walk) and the scope depth that owns it. Only
	 * {@code let} records -- its bindings never rebind, unlike {@code loop} targets;
	 * every other binder hides entries through the shadow walk in {@link #hostClassOf}
	 * instead of recording.
	 */
	private record HostClass(String fqn, String name, int depth) {
	}

	/**
	 * The visible {@code let}-inferred host classes by mangled name. Entries die with
	 * their {@code let} (the finally there) or when the name rebinds.
	 */
	private final Map<String, HostClass> hostClasses = new HashMap<>();

	/**
	 * Whether the program uses refs or agents (any of {@code ref}/{@code dosync}/
	 * {@code alter}/{@code commute}/{@code ref-set}/{@code ensure}/{@code agent}/
	 * {@code send}/{@code send-off}/{@code await}, or a {@code binding} of
	 * {@code *agent*}): the STM runtime (the transaction depth, the validator registry
	 * and the agent var) is spliced in once, behind the false binding.
	 */
	private boolean usedStm;

	/** Whether the STM runtime was already spliced in (files splice it inline). */
	private boolean stmEmitted;

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
		if (lowering.usedProtocols) {
			// the protocol runtime runs before anything else, like the false value
			lowering.forms.addAll(1, lowering.protocolRuntime());
		}
		if (lowering.usedExInfo) {
			// the ex-info runtime runs before anything else, like the false value
			lowering.forms.addAll(1, lowering.exInfoRuntime());
		}
		if (lowering.usedStm) {
			// the STM runtime runs before anything else, like the false value
			lowering.forms.addAll(1, lowering.stmRuntime());
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
		if (this.usedProtocols && !this.protocolsEmitted) {
			// The protocol runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(protocolRuntime(), false));
			this.protocolsEmitted = true;
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
		if (this.usedStm && !this.stmEmitted) {
			// The STM runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(stmRuntime(), false));
			this.stmEmitted = true;
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
		else if (isSymbolNamed(items.get(0), "defn") || isSymbolNamed(items.get(0), "defn-")) {
			// a ^:dynamic defn holds its function in the value cell (like a def),
			// so even a forward call routes through it and sees a binding
			this.globals.put(plainName(items.get(1), "defn"),
					nameIsDynamic(items.get(1)) ? Kind.VARIABLE : Kind.FUNCTION);
		}
		else if (isSymbolNamed(items.get(0), "defonce")) {
			this.globals.put(plainName(items.get(1), "defonce"), Kind.VARIABLE);
		}
		else if (isSymbolNamed(items.get(0), "defstruct")) {
			this.globals.put(plainName(items.get(1), "defstruct"), Kind.VARIABLE);
		}
		else if (isSymbolNamed(items.get(0), "defmulti")) {
			this.globals.put(plainName(items.get(1), "defmulti"), Kind.FUNCTION);
		}
		else if (isSymbolNamed(items.get(0), "defprotocol")) {
			declareProtocol(items);
		}
		else if (isSymbolNamed(items.get(0), "defrecord") || isSymbolNamed(items.get(0), "deftype")) {
			declareRecordType(items);
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
		this.directScopes.add(new HashSet<>());
	}

	/**
	 * Whether a variable name holds a real function: the innermost binding decides, like
	 * {@link #known}.
	 */
	private boolean isDirectVar(String name) {
		for (int i = this.scopes.size() - 1; i >= 0; i--) {
			if (this.scopes.get(i).containsKey(name)) {
				return this.directScopes.get(i).contains(name);
			}
		}
		if (this.globals.containsKey(name)) {
			return this.globalDirectFuns.contains(name);
		}
		return false;
	}

	/** Marks the name direct in the innermost scope. */
	private void markDirect(String name) {
		this.directScopes.get(this.directScopes.size() - 1).add(name);
	}

	private List<LispVal> topLevels(LispVal form) {
		try {
			if (isNsForm(form)) {
				processNs(form); // wires the aliases; defines nothing
				return List.of();
			}
			List<LispVal> items = items(form);
			if (items != null && !items.isEmpty()
					&& (isSymbolNamed(items.get(0), "defn") || isSymbolNamed(items.get(0), "defn-"))) {
				return defuns(items);
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defmulti")) {
				return defmultiForms(items);
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defprotocol")) {
				return defprotocolForms(items);
			}
			if (items != null && !items.isEmpty()
					&& (isSymbolNamed(items.get(0), "defrecord") || isSymbolNamed(items.get(0), "deftype"))) {
				return recordTypeForms(items);
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "extend-protocol")) {
				return List.of(extendProtocolForm(items));
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "extend-type")) {
				return List.of(extendTypeForm(items));
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "extend")) {
				return List.of(extendForm(items));
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defmacro")) {
				return defmacroForms(items);
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defonce")) {
				return List.of(defonceForm(items));
			}
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defstruct")) {
				return List.of(defstructForm(items));
			}
			return List.of(lower(form));
		}
		catch (LispReadException ex) {
			throw positioned(ex, form);
		}
	}

	/**
	 * One datum lowered, positioned: a lowering error names the innermost form's /** One
	 * datum lowered outside tail position and positioned: a call argument, an init, a
	 * test or any other non-tail slot clears the position, so only the tail slots
	 * (lowered through {@link #lowerTail}) keep it. A lowering error names the innermost
	 * form's {@code file:line:column} (through the reader's offsets), so
	 * {@code unknown name} and arity errors point at the call. An error that already
	 * carries a position -- a reader error, or one an inner form attached -- passes
	 * through untouched.
	 */
	private LispVal lower(LispVal form) {
		boolean outer = this.tailPosition;
		this.tailPosition = false;
		try {
			return lowerPositioned(form);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	private LispVal lowerPositioned(LispVal form) {
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
		if (isSymbolNamed(head, "defonce")) {
			return defonceForm(items);
		}
		if (isSymbolNamed(head, "defstruct")) {
			return defstructForm(items);
		}
		if (isSymbolNamed(head, "struct")) {
			return structOf(items);
		}
		if (isSymbolNamed(head, "struct-map")) {
			return structMapOf(items);
		}
		if (isSymbolNamed(head, "defn")) {
			// in a body: a single defun, like ever; several arities cannot splice
			// into expression position (a dynamic single-arity one splices its
			// defun plus its defparameter behind a progn instead)
			List<LispVal> forms = defuns(items);
			if (forms.size() == 1) {
				return forms.get(0);
			}
			isTrue(forms.size() == 2, "a multi-arity defn is only allowed at the top level");
			return cons(sym("progn"), forms);
		}
		if (isSymbolNamed(head, "defn-")) {
			// private by convention only: metadata never affects dispatch
			List<LispVal> forms = defuns(items);
			if (forms.size() == 1) {
				return forms.get(0);
			}
			isTrue(forms.size() == 2, "a multi-arity defn is only allowed at the top level");
			return cons(sym("progn"), forms);
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
		if (isSymbolNamed(head, "letfn")) {
			return letfn(items);
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
		if (isSymbolNamed(head, "defprotocol")) {
			List<LispVal> forms = defprotocolForms(items);
			return forms.size() == 1 ? forms.get(0) : cons(sym("progn"), forms);
		}
		if (isSymbolNamed(head, "defrecord") || isSymbolNamed(head, "deftype")) {
			List<LispVal> forms = recordTypeForms(items);
			return forms.size() == 1 ? forms.get(0) : cons(sym("progn"), forms);
		}
		if (isSymbolNamed(head, "extend-protocol")) {
			return extendProtocolForm(items);
		}
		if (isSymbolNamed(head, "extend-type")) {
			return extendTypeForm(items);
		}
		if (isSymbolNamed(head, "extend")) {
			return extendForm(items);
		}
		if (isSymbolNamed(head, "reify")) {
			return reifyForm(items);
		}
		if (isSymbolNamed(head, "satisfies?")) {
			return satisfiesOf(items);
		}
		if (isSymbolNamed(head, "definterface") || isSymbolNamed(head, "gen-class")
				|| isSymbolNamed(head, "gen-interface")) {
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
		if (isSymbolNamed(head, "ref")) {
			return refOf(items);
		}
		if (isSymbolNamed(head, "dosync")) {
			return dosyncOf(items);
		}
		if (isSymbolNamed(head, "alter")) {
			return alterOf(items, "alter");
		}
		if (isSymbolNamed(head, "commute")) {
			return alterOf(items, "commute");
		}
		if (isSymbolNamed(head, "ref-set")) {
			return refSetOf(items);
		}
		if (isSymbolNamed(head, "ensure")) {
			return ensureOf(items);
		}
		if (isSymbolNamed(head, "agent")) {
			return agentOf(items);
		}
		if (isSymbolNamed(head, "send") || isSymbolNamed(head, "send-off")) {
			return sendOf(items);
		}
		if (isSymbolNamed(head, "await")) {
			return awaitOf(items);
		}
		if (isSymbolNamed(head, "shutdown-agents")) {
			isTrue(items.size() == 1, "shutdown-agents takes no arguments");
			return NIL_CONST;
		}
		if (isSymbolNamed(head, "binding")) {
			return bindingOf(items);
		}
		if (isSymbolNamed(head, "with-open")) {
			return withOpenOf(items);
		}
		if (isSymbolNamed(head, "with-out-str")) {
			return withOutStrOf(items);
		}
		if (isSymbolNamed(head, "time")) {
			return timeOf(items);
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
			return inNsOf(items);
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
		if (isSymbolNamed(head, "proxy-super")) {
			throw new LispReadException(
					"proxy-super is not supported yet: proxy methods take the Java arguments only, with no super handle");
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
			// metadata is parsed and dropped: it never affects dispatch, so the
			// object lowers as itself
			isTrue(items.size() == 3, "with-meta takes an object and metadata");
			return lower(items.get(1));
		}
		if (head instanceof LispCons) {
			if (isCollectionHead(head)) {
				return collectionCall(items);
			}
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
		if (head == ClojureReader.REGEX || isSymbolNamed(head, "%regex")) {
			return regexForm(items);
		}
		if (isSymbolNamed(head, "if")) {
			isTrue(items.size() == 3 || items.size() == 4, "if takes a condition, a then and an optional else");
			LispVal test = lower(items.get(1));
			LispVal then = lowerTailSlot(items.get(2));
			LispVal elseForm = items.size() == 4 ? lowerTailSlot(items.get(3)) : NIL_CONST;
			return ifFalsey(test, then, elseForm);
		}
		if (isSymbolNamed(head, "when")) {
			isTrue(items.size() >= 3, "when needs a condition and a body");
			LispVal test = lower(items.get(1));
			return ifFalsey(test, body(items, 2), NIL_CONST);
		}
		if (isSymbolNamed(head, "when-let")) {
			return whenLetOf(items);
		}
		if (isSymbolNamed(head, "if-let")) {
			return ifLetOf(items);
		}
		if (isSymbolNamed(head, "when-not")) {
			return whenNotOf(items);
		}
		if (isSymbolNamed(head, "if-not")) {
			return ifNotOf(items);
		}
		if (isSymbolNamed(head, "when-first")) {
			return whenFirstOf(items);
		}
		if (isSymbolNamed(head, "cond")) {
			return condOf(items);
		}
		if (isSymbolNamed(head, "do")) {
			return body(items, 1);
		}
		if (isSymbolNamed(head, "recur")) {
			return recurOf(form, items);
		}
		return call(items);
	}

	private LispVal def(List<LispVal> items) {
		isTrue(items.size() >= 2, "def takes a name and an optional value");
		LispVal nameDatum = items.get(1);
		String name = plainName(nameDatum, "def");
		boolean dynamic = nameIsDynamic(nameDatum);
		int at = 2;
		if (items.size() > at && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		if (items.size() > at + 1 && isAttrMap(items.get(at))) {
			at++; // the attr map (only with a value behind it: a lone map is the value)
		}
		isTrue(items.size() == at || items.size() == at + 1, "def takes a name and an optional value");
		this.globals.put(name, Kind.VARIABLE);
		this.macros.remove(name); // a definition wins over the macro it shadows
		if (dynamic) {
			this.dynamicVars.add(name);
		}
		LispVal value = items.size() == at + 1 ? lower(items.get(at)) : NIL_CONST;
		if (isDirectFun(value)) {
			this.globalDirectFuns.add(name);
		}
		else {
			this.globalDirectFuns.remove(name);
		}
		if (dynamic) {
			// a dynamic var is a special: defparameter always sets it (like def)
			// and proclaims it, so binding rebinds it with dynamic extent
			return list(sym("defparameter"), idSym(name), value);
		}
		return list(sym("setq"), idSym(name), value);
	}

	/** Whether the datum is an attr map (its marker head), not a value. */
	private static boolean isAttrMap(LispVal datum) {
		List<LispVal> parts = items(datum);
		return parts != null && !parts.isEmpty() && isSymbolNamed(parts.get(0), "%hash-map");
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

	/**
	 * One lambda lowered with its recur targets: the lambda itself, any extra worker
	 * labels entries a used variadic clause needs, and whether any recur reached it. A
	 * used variadic clause splits like the multi-{@code defn} helpers: the worker takes
	 * the rest as an ordinary parameter (so a {@code recur} assigns exactly) while the
	 * head keeps the {@code &rest} shape for normal calls (wrapping, like the oracle).
	 */
	private record SplitLambda(LispVal lambda, List<LispVal> workers, boolean used) {
	}

	/** The parameter shape of one clause datum, read without lowering anything. */
	private record ParamShape(boolean variadic, int fixed) {
	}

	/**
	 * A used variadic clause's worker: the entry or {@code defun} taking the rest as an
	 * ordinary parameter. A lone {@code %} no mangled identifier spells, so workers stay
	 * apart from user definitions, like the multi-{@code defn} helpers.
	 */
	private static String workerName(String callName) {
		return callName + "%*";
	}

	/** The parameter shape of one parameter vector datum, without lowering anything. */
	private static ParamShape paramShape(LispVal paramVector) {
		List<LispVal> names;
		try {
			names = bindingItems(stripMeta(paramVector), "the parameter vector of");
		}
		catch (LispReadException ex) {
			// malformed: the real pass reports the shape, so say nothing here
			return new ParamShape(false, -1);
		}
		for (int i = 0; i < names.size(); i++) {
			if (isSymbolNamed(stripMeta(names.get(i)), "&")) {
				return new ParamShape(true, i);
			}
		}
		return new ParamShape(false, names.size());
	}

	private static boolean isVariadicParams(LispVal paramVector) {
		return paramShape(paramVector).variadic();
	}

	/**
	 * One labels entry for a used variadic clause's worker: the rest as an ordinary
	 * parameter around the clause's prologue and body, so a {@code recur} assigns
	 * exactly.
	 */
	private static LispVal workerEntry(String worker, Clause clause) {
		List<LispVal> params = new ArrayList<>(clause.params());
		params.remove(AMPERSAND_REST);
		return new LispCons(new LispSymbol(worker), new LispCons(list(params), cons(clause.wrapped(), List.of())));
	}

	/** A direct call forwarding each parameter (the rest as one value) to the worker. */
	private static LispVal workerCall(String worker, Clause clause) {
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(worker));
		for (LispVal param : clause.params()) {
			if (!AMPERSAND_REST.equals(param)) {
				call.add(param);
			}
		}
		return list(call);
	}

	/**
	 * One already-lowered lambda behind a {@code labels} head entry plus any worker
	 * entries, answering the head: the shape a named {@code fn} always takes, an
	 * anonymous one only when a {@code recur} reached it.
	 */
	private static LispVal labelsWithHead(String headName, List<LispVal> workers, LispVal lambda) {
		LispVal paramsAndBody = ((LispCons) lambda).cdr();
		List<LispVal> entries = new ArrayList<>(workers);
		entries.add(new LispCons(new LispSymbol(headName), paramsAndBody));
		return list(sym("labels"), list(entries), list(sym("function"), new LispSymbol(headName)));
	}

	/**
	 * A used variadic stored-method lambda splits like every other {@code fn} shape: the
	 * worker takes the rest as an ordinary parameter (the {@code recur} call assigns
	 * exactly) while the {@code &rest} head answers normal calls (wrapping, like the
	 * oracle); an unused variadic keeps its single shape. The body is the clause's
	 * wrapped body, or the fields-bound one for an inline record body.
	 */
	private LispVal splitMethodLambda(String fresh, String worker, Clause clause, LispVal bodyForm) {
		List<LispVal> workerParams = new ArrayList<>(clause.params());
		workerParams.remove(AMPERSAND_REST);
		LispVal entry = new LispCons(new LispSymbol(worker),
				new LispCons(list(workerParams), cons(bodyForm, List.of())));
		LispVal head = list(sym("lambda"), list(clause.params()), workerCall(worker, clause));
		return labelsWithHead(fresh, List.of(entry), head);
	}

	private List<LispVal> defuns(List<LispVal> items) {
		int at = 2;
		if (items.size() > at && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		if (items.size() > at + 1 && isAttrMap(items.get(at))) {
			at++; // the attr map (only with a value behind it: a lone map is the value)
		}
		isTrue(items.size() > at + 1 || items.size() == at + 1 && items.get(at) instanceof LispCons,
				"defn needs a parameter vector and a body");
		String name = plainName(items.get(1), "defn");
		boolean dynamic = nameIsDynamic(items.get(1));
		this.globals.put(name, dynamic ? Kind.VARIABLE : Kind.FUNCTION);
		this.macros.remove(name); // a definition wins over the macro it shadows
		if (dynamic) {
			// a dynamic var is rebindable: the value cell holds the function
			// (proclaimed special, so binding rebinds it with dynamic extent)
			// while the function cell keeps the definition
			this.dynamicVars.add(name);
			this.globalDirectFuns.add(name);
		}
		String callName = idSym(name).name();
		List<LispVal> forms;
		if (items.get(at) instanceof LispCons && !isVectorDatum(items.get(at))) {
			forms = multiDefun(name, items.subList(at, items.size()), callName);
		}
		else {
			boolean variadic = isVariadicParams(items.get(at));
			String worker = workerName(callName);
			RecurTarget target = new RecurTarget(variadic ? worker : callName, true);
			Clause clause = clause(items.get(at), items.subList(at + 1, items.size()), target);
			if (target.used() && clause.variadic()) {
				// a used variadic target splits like the multi-defn helpers: a
				// worker defun taking the rest as an ordinary parameter (the recur
				// call assigns exactly) plus the &rest head for normal calls
				// (wrapping, like the oracle); an unused variadic keeps its shape
				List<LispVal> workerParams = new ArrayList<>(clause.params());
				workerParams.remove(AMPERSAND_REST);
				forms = new ArrayList<>(
						List.of(list(sym("defun"), new LispSymbol(worker), list(workerParams), clause.wrapped()),
								list(sym("defun"), idSym(name), list(clause.params()), workerCall(worker, clause))));
			}
			else {
				forms = new ArrayList<>(
						List.of(list(sym("defun"), idSym(name), list(clause.params()), clause.wrapped())));
			}
		}
		if (dynamic) {
			// the value cell carries the function for calls and value carries
			// (a funcall of it, like a def'd function); recur and the
			// arity-dispatch helpers stay direct calls to the function cell
			forms.add(list(sym("defparameter"), idSym(name), list(sym("function"), idSym(name))));
		}
		return forms;
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
	 * definitions. A {@code recur} in a fixed clause body is checked against that
	 * clause's arity and calls the dispatch, which routes by count back to the same
	 * clause (every fixed count names exactly one clause, so the routing is exact except
	 * where a variadic clause listed before a fixed one also matches the count); a
	 * {@code recur} in a used variadic clause calls its helper directly, which takes the
	 * rest as an ordinary parameter, so the call assigns exactly.
	 */
	private List<LispVal> multiDefun(String name, List<LispVal> clauses, String callName) {
		// the shapes first, without lowering: a used variadic clause recurs to its
		// helper (which takes the rest as an ordinary parameter, so the recur call
		// assigns exactly) rather than the dispatch (whose NTHCDR rest would wrap
		// it in a list); every fixed clause still recurs through the dispatch
		List<ParamShape> shapes = new ArrayList<>();
		for (LispVal clauseDatum : clauses) {
			List<LispVal> parts = items(clauseDatum);
			shapes.add(parts == null || parts.isEmpty() ? new ParamShape(false, -1) : paramShape(parts.get(0)));
		}
		List<String> helperNames = new ArrayList<>();
		List<RecurTarget> targets = new ArrayList<>();
		for (ParamShape shape : shapes) {
			String helperName = PREFIX + name + "%" + (shape.variadic() ? "*" : shape.fixed());
			helperNames.add(helperName);
			targets.add(new RecurTarget(shape.variadic() ? helperName : callName, true));
		}
		List<Clause> parsed = arityClauses(clauses, "defn", targets::get);
		List<LispVal> forms = new ArrayList<>();
		LispVal args = freshTemp();
		LispVal count = freshTemp();
		List<LispVal> helpers = new ArrayList<>();
		for (int i = 0; i < parsed.size(); i++) {
			Clause clause = parsed.get(i);
			LispSymbol helper = new LispSymbol(helperNames.get(i));
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
	 * fixed arity. Each clause body lowers with its own recur target (a clause is its own
	 * recur boundary, like the oracle: the count must match the enclosing clause, never
	 * just any clause of the function).
	 */
	private List<Clause> arityClauses(List<LispVal> clauses, String owner) {
		return arityClauses(clauses, owner, i -> null);
	}

	private List<Clause> arityClauses(List<LispVal> clauses, String owner,
			java.util.function.IntFunction<RecurTarget> targets) {
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
			Clause clause = clause(parts.get(0), parts.subList(1, parts.size()), targets.apply(parsed.size()));
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
	 * prologue rebinding them to their patterns. With a recur target, the body lowers
	 * with it pushed (its arity set from the parsed parameters first, so a {@code recur}
	 * in the body checks against this clause), and the target records whether any
	 * {@code recur} reached it.
	 */
	private Clause clause(LispVal paramVector, List<LispVal> bodyForms) {
		return clause(paramVector, bodyForms, null);
	}

	private Clause clause(LispVal paramVector, List<LispVal> bodyForms, @Nullable RecurTarget target) {
		// metadata on the vector (type hints like ^String) is dropped, like the
		// rest: it never affects dispatch
		List<LispVal> names = bindingItems(stripMeta(paramVector), "the parameter vector of");
		Map<String, Kind> scope = new HashMap<>();
		List<LispVal> params = new ArrayList<>();
		List<LispVal> prologue = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			LispVal datum = stripMeta(names.get(i));
			if (isSymbolNamed(datum, "&")) {
				isTrue(i + 1 < names.size(), "a & needs a rest name after it");
				isTrue(i + 2 == names.size(), "only one name may follow & in a parameter vector");
				params.add(AMPERSAND_REST);
				LispVal rest = stripMeta(names.get(++i));
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
		LispVal body;
		boolean variadic = params.contains(AMPERSAND_REST);
		int fixed = variadic ? params.indexOf(AMPERSAND_REST) : params.size();
		if (target == null) {
			body = inScope(scope, () -> bodyOf(bodyForms));
		}
		else {
			target.setArity(variadic ? fixed + 1 : fixed, variadic);
			pushRecurTarget(target);
			try {
				body = inScope(scope, () -> bodyOfTail(bodyForms));
			}
			finally {
				this.recurTargets.pop();
			}
		}
		return new Clause(List.copyOf(params), List.copyOf(prologue), body, variadic, fixed);
	}

	private LispVal fn(List<LispVal> items) {
		if (items.size() > 1 && items.get(1) == ClojureReader.FN_ANON) {
			isTrue(items.size() >= 2, "the anon form #(...) needs a body");
			LispVal form = items.size() == 3 ? items.get(2)
					: new LispCons(items.get(2), list(items.subList(3, items.size())));
			String outer = this.anonArgs;
			this.anonArgs = mangle("anon-args");
			String argsName = this.anonArgs;
			// the anon form is its own recur target, but an unchecked one: its
			// arity is the highest %N the body uses, known only after the body
			// lowers, so a wrong count signals at run time, like any other call
			RecurTarget target = new RecurTarget(freshRecurName(), false);
			pushRecurTarget(target);
			try {
				LispVal lambda = list(sym("lambda"), list(AMPERSAND_REST, new LispSymbol(argsName)), lowerTail(form));
				if (!target.used()) {
					return lambda;
				}
				return labelsSelfCall(target.callName(), lambda);
			}
			finally {
				this.recurTargets.pop();
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
			String fresh = freshRecurName();
			SplitLambda split = singleOrMultiFn(items, at, "fn", fresh);
			if (!split.used()) {
				return split.lambda();
			}
			return labelsWithHead(fresh, split.workers(), split.lambda());
		}
		Map<String, Kind> scope = new HashMap<>();
		scope.put(self, Kind.FUNCTION);
		String name = self;
		String callName = idSym(name).name();
		int from = at;
		return inScope(scope, () -> {
			SplitLambda split = singleOrMultiFn(items, from, name, callName);
			// a labels self-binding: calls lower directly and the labels
			// expansion rewrites them to the local; the value is the local
			return labelsWithHead(callName, split.workers(), split.lambda());
		});
	}

	private SplitLambda singleOrMultiFn(List<LispVal> items, int at, String owner, String headName) {
		String worker = workerName(headName);
		if (items.get(at) instanceof LispCons && !isVectorDatum(items.get(at))) {
			return multiFn(items.subList(at, items.size()), owner, headName, worker);
		}
		boolean variadic = isVariadicParams(items.get(at));
		RecurTarget target = new RecurTarget(variadic ? worker : headName, true);
		Clause clause = clause(items.get(at), items.subList(at + 1, items.size()), target);
		if (target.used() && clause.variadic()) {
			// a used variadic target splits: the worker takes the rest as an
			// ordinary parameter (the recur call assigns exactly) while the
			// &rest head answers normal calls (wrapping, like the oracle)
			LispVal head = list(sym("lambda"), list(clause.params()), workerCall(worker, clause));
			return new SplitLambda(head, List.of(workerEntry(worker, clause)), true);
		}
		return new SplitLambda(list(sym("lambda"), list(clause.params()), clause.wrapped()), List.of(), target.used());
	}

	/**
	 * A multi-arity {@code fn}: one {@code lambda} over {@code &rest} dispatching per
	 * arity through {@code let*} argument bindings -- no local functions, so a clause
	 * body closes over the outer scope like any lambda body. A {@code recur} in a fixed
	 * clause body is checked against that clause's arity and calls the dispatch, which
	 * routes by count back to the same clause (same routing caveat as a multi-arity
	 * {@code defn}); a {@code recur} in a used variadic clause calls the worker instead,
	 * which takes the rest as an ordinary parameter.
	 */
	private SplitLambda multiFn(List<LispVal> clauses, String owner, String headName, String worker) {
		// the shapes first, without lowering: a used variadic clause recurs to its
		// worker (which takes the rest as an ordinary parameter) while the dispatch
		// arm hands it the rest pre-built; every fixed clause still recurs through
		// the dispatch (the same routing caveat as a multi-arity defn)
		List<RecurTarget> made = new ArrayList<>();
		for (LispVal clauseDatum : clauses) {
			List<LispVal> parts = items(clauseDatum);
			boolean variadic = parts != null && !parts.isEmpty() && isVariadicParams(parts.get(0));
			made.add(new RecurTarget(variadic ? worker : headName, true));
		}
		List<Clause> parsed = arityClauses(clauses, owner, made::get);
		LispVal args = freshTemp();
		LispVal count = freshTemp();
		List<LispVal> arms = new ArrayList<>();
		int split = -1;
		for (int i = 0; i < parsed.size(); i++) {
			Clause clause = parsed.get(i);
			if (clause.variadic() && made.get(i).used()) {
				List<LispVal> call = new ArrayList<>();
				call.add(new LispSymbol(worker));
				for (int p = 0; p < clause.fixed(); p++) {
					call.add(list(sym("NTH"), new LispInteger(p), args));
				}
				call.add(list(sym("NTHCDR"), new LispInteger(clause.fixed()), args));
				arms.add(list(list(sym(">="), count, new LispInteger(clause.fixed())), list(call)));
				split = i;
				continue;
			}
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
		LispVal lambda = list(sym("lambda"), list(List.of(AMPERSAND_REST, args)),
				list(sym("let"), list(List.of(list(count, list(sym("length"), args)))), cons(sym("cond"), arms)));
		boolean used = made.stream().anyMatch(RecurTarget::used);
		if (split >= 0) {
			return new SplitLambda(lambda, List.of(workerEntry(worker, parsed.get(split))), used);
		}
		return new SplitLambda(lambda, List.of(), used);
	}

	private LispVal let(List<LispVal> items) {
		isTrue(items.size() >= 3, "let needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "let");
		isTrue(bindings.size() % 2 == 0, "a let binding vector pairs a name with a value");
		Map<String, Kind> scope = new HashMap<>();
		this.scopes.add(scope); // let* : each value sees the bindings before it
		this.directScopes.add(new HashSet<>());
		LispVal form;
		try {
			List<LispVal> pairs = new ArrayList<>();
			for (int i = 0; i < bindings.size(); i += 2) {
				LispVal pattern = stripMeta(bindings.get(i));
				if (pattern instanceof LispSymbol) {
					String name = plainName(pattern, "let");
					LispVal init = lower(bindings.get(i + 1));
					pairs.add(list(idSym(name), init));
					scope.put(name, Kind.VARIABLE);
					if (isDirectFun(init)) {
						markDirect(name);
					}
					noteHostClass(name, init);
					continue;
				}
				LispSymbol temp = freshTemp();
				pairs.add(list(temp, lower(bindings.get(i + 1))));
				Set<String> bound = new HashSet<>(scope.keySet());
				destructureInto(pattern, temp, pairs, scope, "let");
				forgetHostClasses(scope.keySet(), bound);
			}
			form = list(sym("let*"), list(pairs), body(items, 2));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.directScopes.remove(this.directScopes.size() - 1);
			forgetDeepHosts();
		}
		return form;
	}

	/**
	 * A {@code let} binding's host class, when its lowered init is a construction literal
	 * of a loadable class: the FQN the instance-call wrap consults. Anything else forgets
	 * the name, so rebinding the name hides the old class.
	 */
	private void noteHostClass(String name, LispVal init) {
		String fqn = constructedClass(init);
		if (fqn == null) {
			this.hostClasses.remove(idSym(name).name());
		}
		else {
			this.hostClasses.put(idSym(name).name(), new HostClass(fqn, name, this.scopes.size()));
		}
	}

	/**
	 * Forgets host classes for names a destructuring pattern just bound: pattern members
	 * are collection members, never constructions.
	 */
	private void forgetHostClasses(Set<String> now, Set<String> before) {
		for (String key : now) {
			if (!before.contains(key)) {
				this.hostClasses.remove(idSym(key).name());
			}
		}
	}

	/**
	 * Records an {@code if-let}/{@code when-let} binding the way {@code let} does: a
	 * plain name takes the lowered init's construction class, a destructuring pattern
	 * forgets its members (collection members, never constructions). The binding is
	 * single-shot, so the depth-walk soundness carries over.
	 */
	private void noteOrForgetHost(LispVal pattern, LispVal loweredInit, Set<String> now, Set<String> before) {
		LispVal stripped = stripMeta(pattern);
		if (stripped instanceof LispSymbol) {
			noteHostClass(plainName(stripped, "if-let"), loweredInit);
		}
		else {
			forgetHostClasses(now, before);
		}
	}

	/**
	 * Drops host classes recorded below the current scope depth: their {@code let}
	 * finished, so a later form must not see them.
	 */
	private void forgetDeepHosts() {
		int depth = this.scopes.size();
		this.hostClasses.values().removeIf(held -> held.depth() > depth);
	}

	/**
	 * A {@code recur} form lowered: a direct call to the innermost enclosing target (a
	 * {@code loop}, a named or anonymous {@code fn}, a {@code defn} clause, a
	 * {@code letfn} entry, or a {@code lazy-seq} body of arity 0), checked against that
	 * target's arity. A {@code try} between the {@code recur} and its target is the
	 * oracle's {@code Cannot recur across try} refusal, and a {@code recur} outside its
	 * target body's tail position the oracle's {@code Can only recur from tail position}
	 * refusal; both beat the arity check, like the oracle. Outside any target it stays a
	 * refusal.
	 */
	private LispVal recurOf(LispVal form, List<LispVal> items) {
		if (this.recurTargets.isEmpty()) {
			throw new LispReadException("recur outside loop");
		}
		RecurTarget target = this.recurTargets.peek();
		if (this.tryDepth > target.depth()) {
			throw new LispReadException("Cannot recur across try");
		}
		if (!this.tailPosition) {
			throw new LispReadException("Can only recur from tail position");
		}
		int given = items.size() - 1;
		if (target.checked() && given != target.arity()) {
			throw new LispReadException(
					"wrong number of arguments passed to recur: expected " + target.arity() + ", got " + given);
		}
		target.markUsed(form);
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(target.callName()));
		for (int i = 1; i < items.size(); i++) {
			out.add(lower(items.get(i)));
		}
		return list(out);
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
		this.directScopes.add(new HashSet<>());
		try {
			for (int i = 0; i < bindings.size(); i += 2) {
				LispVal pattern = stripMeta(bindings.get(i));
				LispVal init = lower(bindings.get(i + 1));
				if (pattern instanceof LispSymbol) {
					String binding = plainName(pattern, "loop");
					paramSyms.add(idSym(binding));
					inits.add(init);
					scope.put(binding, Kind.VARIABLE);
					if (isDirectFun(init)) {
						markDirect(binding);
					}
					continue;
				}
				LispSymbol temp = freshTemp();
				paramSyms.add(temp);
				inits.add(init);
				destructureInto(pattern, temp, prologue, scope, "loop");
			}
			RecurTarget target = new RecurTarget(name, true);
			target.setArity(paramSyms.size(), false);
			pushRecurTarget(target);
			try {
				LispVal lambdaBody = prologue.isEmpty() ? bodyTail(items, 2)
						: list(sym("let*"), list(prologue), bodyTail(items, 2));
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
				this.recurTargets.pop();
			}
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.directScopes.remove(this.directScopes.size() - 1);
		}
	}

	/**
	 * {@code (letfn [(f [params] body...)+] body...)}: mutual local functions over one
	 * {@code labels}, so every entry -- and the body -- calls every other directly, the
	 * shape a named {@code fn} self-binding already uses. Every name is pre-scanned first
	 * (the {@code defn} mutual-recursion precedent), so siblings call each other; each
	 * entry then lowers like a named {@code fn} clause, with its own recur target pushing
	 * while its body lowers. Parameters destructure like {@code fn} parameters, and an
	 * entry's name is a function value (through {@code function}, like any other local
	 * function). An empty binding vector is just the body.
	 */
	private LispVal letfn(List<LispVal> items) {
		isTrue(items.size() >= 3, "letfn needs a binding vector and a body");
		List<LispVal> specs = bindingItems(items.get(1), "letfn");
		if (specs.isEmpty()) {
			return body(items, 2);
		}
		Map<String, Kind> scope = new HashMap<>();
		List<List<LispVal>> fnspecs = new ArrayList<>();
		for (LispVal spec : specs) {
			List<LispVal> parts = items(spec);
			if (parts == null || parts.size() < 3) {
				throw new LispReadException("a letfn binding takes a name and a function, not " + spec.print());
			}
			scope.put(plainName(parts.get(0), "letfn"), Kind.FUNCTION);
			fnspecs.add(parts);
		}
		return inScope(scope, () -> {
			// a later entry shadows an earlier one with the same name, like the
			// oracle (labels itself refuses a name twice)
			Map<String, LispVal> bindings = new LinkedHashMap<>();
			for (List<LispVal> parts : fnspecs) {
				String fname = plainName(parts.get(0), "letfn");
				String callName = idSym(fname).name();
				SplitLambda split = singleOrMultiFn(parts, 1, fname, callName);
				for (LispVal worker : split.workers()) {
					LispVal key = ((LispCons) worker).car();
					bindings.put(((LispSymbol) key).name(), worker);
				}
				bindings.put(callName, new LispCons(new LispSymbol(callName), ((LispCons) split.lambda()).cdr()));
			}
			return list(sym("labels"), list(new ArrayList<>(bindings.values())), body(items, 2));
		});
	}

	private LispVal condOf(List<LispVal> items) {
		LispVal out = NIL_CONST;
		// an odd trailing arm is the default: it fires whatever came before
		int pairsEnd = items.size() % 2 == 0 ? items.size() - 1 : items.size();
		if (pairsEnd != items.size()) {
			out = lowerTailSlot(items.get(items.size() - 1));
		}
		for (int i = pairsEnd - 2; i >= 1; i -= 2) {
			out = ifFalsey(condTest(items.get(i)), lowerTailSlot(items.get(i + 1)), out);
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
	 * {@code when-let}: one binding tested, the body only on truthy. The init runs once
	 * behind a temporary; the pattern destructures from it like {@code let}, so a vector
	 * or map pattern tests the whole init value, like the oracle.
	 */
	private LispVal whenLetOf(List<LispVal> items) {
		isTrue(items.size() >= 3, "when-let needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "when-let");
		isTrue(bindings.size() == 2, "when-let takes a single binding pair");
		LispSymbol init = freshTemp();
		Map<String, Kind> scope = new HashMap<>();
		this.scopes.add(scope);
		this.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			LispVal loweredInit = lower(bindings.get(1));
			pairs.add(list(init, loweredInit));
			Set<String> bound = new HashSet<>(scope.keySet());
			destructureInto(bindings.get(0), init, pairs, scope, "when-let");
			noteOrForgetHost(bindings.get(0), loweredInit, scope.keySet(), bound);
			return list(sym("let*"), list(pairs), ifFalsey(init, body(items, 2), NIL_CONST));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.directScopes.remove(this.directScopes.size() - 1);
			forgetDeepHosts();
		}
	}

	/**
	 * {@code if-let}: one binding tested, the then or the else branch. Same shape as
	 * {@link #whenLetOf}, with the else defaulting to nil.
	 */
	private LispVal ifLetOf(List<LispVal> items) {
		isTrue(items.size() == 3 || items.size() == 4, "if-let takes a binding vector, a then and an optional else");
		List<LispVal> bindings = bindingItems(items.get(1), "if-let");
		isTrue(bindings.size() == 2, "if-let takes a single binding pair");
		LispSymbol init = freshTemp();
		Map<String, Kind> scope = new HashMap<>();
		this.scopes.add(scope);
		this.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			LispVal loweredInit = lower(bindings.get(1));
			pairs.add(list(init, loweredInit));
			Set<String> bound = new HashSet<>(scope.keySet());
			destructureInto(bindings.get(0), init, pairs, scope, "if-let");
			noteOrForgetHost(bindings.get(0), loweredInit, scope.keySet(), bound);
			LispVal then = lowerTailSlot(items.get(2));
			LispVal els = items.size() == 4 ? lowerTailSlot(items.get(3)) : NIL_CONST;
			return list(sym("let*"), list(pairs), ifFalsey(init, then, els));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.directScopes.remove(this.directScopes.size() - 1);
			forgetDeepHosts();
		}
	}

	/** {@code when-not}: the body unless the test is truthy. */
	private LispVal whenNotOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "when-not needs a condition and a body");
		return ifFalsey(lower(items.get(1)), NIL_CONST, body(items, 2));
	}

	/** {@code if-not}: the branches swapped, the else defaulting to nil. */
	private LispVal ifNotOf(List<LispVal> items) {
		isTrue(items.size() == 3 || items.size() == 4, "if-not takes a condition, a then and an optional else");
		LispVal then = lowerTailSlot(items.get(2));
		LispVal els = items.size() == 4 ? lowerTailSlot(items.get(3)) : NIL_CONST;
		return ifFalsey(lower(items.get(1)), els, then);
	}

	/**
	 * {@code when-first}: the pattern bound to the head of the seq view, the body only
	 * when the collection is non-empty. The seq runs once behind a temporary.
	 */
	private LispVal whenFirstOf(List<LispVal> items) {
		isTrue(items.size() >= 3, "when-first needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "when-first");
		isTrue(bindings.size() == 2, "when-first takes a single binding pair");
		LispSymbol seq = freshTemp();
		Map<String, Kind> scope = new HashMap<>();
		this.scopes.add(scope);
		this.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			pairs.add(list(seq, seqForm(lower(bindings.get(1)))));
			Set<String> bound = new HashSet<>(scope.keySet());
			destructureInto(bindings.get(0), list(sym("car"), seq), pairs, scope, "when-first");
			forgetHostClasses(scope.keySet(), bound);
			return list(sym("let*"), list(pairs), ifFalsey(seq, body(items, 2), NIL_CONST));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.directScopes.remove(this.directScopes.size() - 1);
			forgetDeepHosts();
		}
	}

	/**
	 * Whether the call head is a collection literal used as a function: a vector, map or
	 * set datum in head position, like {@code (#{:h} :h)} or {@code ([1 2] 0)}.
	 */
	private static boolean isCollectionHead(LispVal head) {
		if (!(head instanceof LispCons cons)) {
			return false;
		}
		LispVal marker = cons.car();
		return marker == ClojureReader.VECTOR || isSymbolNamed(marker, "%hash-map")
				|| isSymbolNamed(marker, "%hash-set");
	}

	/**
	 * A collection literal in call position: a set answers its member, a map its value, a
	 * vector its indexed element, each with an optional default. One or two arguments
	 * besides the collection, like the oracle.
	 */
	private LispVal collectionCall(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "a collection as a function takes a key and an optional default");
		LispVal dflt = n == 2 ? lower(items.get(2)) : NIL_CONST;
		List<LispVal> headItems = items(items.get(0), List.of());
		if (headItems.isEmpty()) {
			throw new LispReadException("a collection as a function takes a key and an optional default");
		}
		LispVal marker = headItems.get(0);
		if (marker == ClojureReader.VECTOR) {
			List<LispVal> elements = new ArrayList<>();
			for (int i = 1; i < headItems.size(); i++) {
				elements.add(lower(headItems.get(i)));
			}
			return nthForm(cons(sym("vector"), elements), lower(items.get(1)), dflt);
		}
		List<LispVal> lowered = new ArrayList<>();
		for (int i = 1; i < headItems.size(); i++) {
			lowered.add(lower(headItems.get(i)));
		}
		if (isSymbolNamed(marker, "%hash-map")) {
			return getForm(mapBuild(lowered), lower(items.get(1)), dflt);
		}
		LispSymbol set = freshTemp();
		LispSymbol key = freshTemp();
		return list(sym("let*"), list(List.of(list(set, setBuild(lowered)), list(key, lower(items.get(1))))),
				list(sym("gethash"), key, setInner(set), dflt));
	}

	/**
	 * A collection literal as a function value: a one-or-two-argument lambda over the
	 * same read a call lowers to, so {@code (filter #{:h} ...)} runs. Null when the datum
	 * is no collection literal.
	 * @param datum the function-position datum
	 * @return the lambda, or null
	 */
	private @Nullable LispVal collectionValue(LispVal datum) {
		List<LispVal> headItems = items(datum);
		if (headItems == null || headItems.isEmpty()) {
			return null;
		}
		LispVal marker = headItems.get(0);
		boolean vector = marker == ClojureReader.VECTOR;
		boolean map = isSymbolNamed(marker, "%hash-map");
		boolean set = isSymbolNamed(marker, "%hash-set");
		if (!vector && !map && !set) {
			return null;
		}
		List<LispVal> lowered = new ArrayList<>();
		for (int i = 1; i < headItems.size(); i++) {
			lowered.add(lower(headItems.get(i)));
		}
		LispSymbol arg = new LispSymbol(mangle("coll-fn-arg"));
		LispSymbol rest = new LispSymbol(mangle("coll-fn-rest"));
		LispVal dflt = list(sym("if"), list(sym("null"), rest), NIL_CONST, list(sym("car"), rest));
		LispVal read;
		if (vector) {
			read = nthForm(cons(sym("vector"), lowered), arg, dflt);
		}
		else if (map) {
			read = getForm(mapBuild(lowered), arg, dflt);
		}
		else {
			LispSymbol table = freshTemp();
			read = list(sym("let"), list(List.of(list(table, setBuild(lowered)))),
					list(sym("gethash"), arg, setInner(table), dflt));
		}
		return list(sym("lambda"), list(List.of(arg, AMPERSAND_REST, rest)), read);
	}

	/**
	 * The prelude call behind every runtime-unknown function: real functions and
	 * collection values alike, over the argument-list form.
	 */
	private LispVal callableApply(LispVal fun, LispVal argList) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-CALL"), fun, argList);
	}

	/**
	 * Whether the function form is already a real function: a {@code function} designator
	 * or a lambda. Anything else (a variable, a call result) may hold a collection at run
	 * time and goes through the dispatcher instead.
	 */
	private static boolean isDirectFun(LispVal fun) {
		if (!(fun instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		return head.name().equals("FUNCTION") || head.name().equals("LAMBDA");
	}

	/**
	 * The function form invoked with the argument forms over its bound value: direct for
	 * real functions, through the prelude dispatcher otherwise, so a variable holding a
	 * set, map, vector or keyword still answers.
	 * @param funForm the function form, as bound
	 * @param bound the bound value (a symbol)
	 * @param args the argument forms
	 * @return the invocation
	 */
	private LispVal callFun(LispVal funForm, LispVal bound, List<LispVal> args) {
		if (isDirectFun(funForm)) {
			List<LispVal> call = new ArrayList<>();
			call.add(sym("funcall"));
			call.add(bound);
			call.addAll(args);
			return list(call);
		}
		return callableApply(bound, cons(sym("list"), args));
	}

	/**
	 * A one-argument function over the dispatcher: for the sequence operators that take a
	 * Common Lisp function designator ({@code mapcar}, {@code remove-if}), so a variable
	 * holding a collection still answers element by element.
	 * @param bound the bound function value (a symbol)
	 * @param arg the element (a symbol)
	 * @return the lambda
	 */
	private LispVal dispatchLambda(LispVal bound, LispVal arg) {
		return list(sym("lambda"), list(arg), callableApply(bound, list(sym("list"), arg)));
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
			return lowerTailSlot(args.get(0));
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
			return lowerTailSlot(args.get(0));
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

	/** Whether NAME is a {@code re-*} core name (lowered beside the big switch). */
	private static boolean isReName(String name) {
		return name.equals("re-pattern") || name.equals("re-matcher") || name.equals("re-find") || name.equals("re-seq")
				|| name.equals("re-matches") || name.equals("re-groups");
	}

	/**
	 * A {@code re-*} call over the call's own items (whose head is ignored): patterns
	 * lower to the spliced regex runtime, so every backend shares the semantics.
	 */
	private LispVal reCall(String name, List<LispVal> items) {
		int n = items.size() - 1;
		return switch (name) {
			case "re-pattern" -> {
				isTrue(n == 1, "re-pattern takes a pattern or a string");
				yield list(new LispSymbol("RONTOLISP::%CLOJURE-RE-PATTERN"), lower(items.get(1)));
			}
			case "re-matcher" -> {
				isTrue(n == 2, "re-matcher takes a pattern and a string");
				yield list(new LispSymbol("RONTOLISP::%CLOJURE-RE-MATCHER"), lower(items.get(1)), lower(items.get(2)));
			}
			case "re-find" -> {
				isTrue(n == 1 || n == 2, "re-find takes a matcher, or a pattern and a string");
				yield n == 2
						? list(new LispSymbol("RONTOLISP::%CLOJURE-RE-FIND"), lower(items.get(1)), lower(items.get(2)))
						: list(new LispSymbol("RONTOLISP::%CLOJURE-RE-FIND-M"), lower(items.get(1)));
			}
			case "re-seq" -> {
				isTrue(n == 2, "re-seq takes a pattern and a string");
				yield list(new LispSymbol("RONTOLISP::%CLOJURE-RE-SEQ"), lower(items.get(1)), lower(items.get(2)));
			}
			case "re-matches" -> {
				isTrue(n == 2, "re-matches takes a pattern and a string");
				yield list(new LispSymbol("RONTOLISP::%CLOJURE-RE-MATCHES"), lower(items.get(1)), lower(items.get(2)));
			}
			case "re-groups" -> {
				isTrue(n == 1, "re-groups takes a matcher");
				yield list(new LispSymbol("RONTOLISP::%CLOJURE-RE-GROUPS"), lower(items.get(1)));
			}
			default -> throw new LispReadException("unknown name: " + name);
		};
	}

	/**
	 * A {@code re-*} var as a function value: one rest lambda dispatching on the argument
	 * count through the call lowering, so {@code (map re-pattern ...)} runs what a call
	 * would run.
	 */
	private LispVal reValue(String name, List<Integer> arities) {
		String plain = "rev-rest" + (this.counter++);
		LispSymbol ref = new LispSymbol(plain);
		return inScope(Map.of(plain, Kind.VARIABLE), () -> {
			List<LispVal> arms = new ArrayList<>();
			for (int arity : arities) {
				List<LispVal> callItems = new ArrayList<>();
				callItems.add(new LispSymbol(name));
				for (int i = 0; i < arity; i++) {
					callItems.add(list(new LispSymbol("nth"), ref, new LispInteger(i)));
				}
				arms.add(list(list(sym("="), list(sym("length"), idSym(plain)), new LispInteger(arity)),
						reCall(name, callItems)));
			}
			arms.add(list(TRUE_CONST,
					list(sym("error"), LispString.literal(name + " called with wrong number of arguments"))));
			return list(sym("lambda"), list(List.of(AMPERSAND_REST, idSym(plain))), cons(sym("cond"), arms));
		});
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

	// clojure.java.io: exactly reader, over the file-stream runtime

	/**
	 * A known-namespace call: the vars {@code ns} resolution already vetted, over the
	 * call's own items (whose head is ignored). {@code clojure.string} lowers to the core
	 * string operations, {@code clojure.java.io} to the file-stream runtime.
	 */
	private LispVal namespaceCall(VarRef ref, List<LispVal> items) {
		if (ref.ns().equals("clojure.java.io")) {
			return jioCall(ref.var(), items);
		}
		return stringCall(ref.var(), items);
	}

	/**
	 * A known-namespace var as a function value: {@code clojure.string} lowers through
	 * the call lowering per arity, {@code clojure.java.io/reader} is a one-argument
	 * lambda over the same open.
	 */
	private LispVal namespaceValue(VarRef ref) {
		if (ref.ns().equals("clojure.java.io")) {
			return jioValue(ref.var());
		}
		return stringValue(ref.var());
	}

	/**
	 * A {@code clojure.java.io} call: exactly {@code reader}, a buffered reader over the
	 * path through the same file-stream runtime {@code slurp} reads through -- an
	 * {@code open} input stream, so {@code line-seq} reads it and {@code with-open}
	 * closes it.
	 */
	private LispVal jioCall(String var, List<LispVal> items) {
		int n = items.size() - 1;
		if (var.equals("reader")) {
			isTrue(n == 1, "reader takes one path");
			return list(sym("open"), lower(items.get(1)));
		}
		throw new LispReadException("unknown name: clojure.java.io/" + var);
	}

	/**
	 * {@code clojure.java.io/reader} as a function value: a one-argument lambda over the
	 * same open.
	 */
	private LispVal jioValue(String var) {
		if (var.equals("reader")) {
			LispSymbol path = new LispSymbol(mangle("reader-path"));
			return list(sym("lambda"), list(path), list(sym("open"), path));
		}
		throw new LispReadException("unknown name: clojure.java.io/" + var);
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
		LispSymbol raw = freshTemp();
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
		// a pattern splits around matches (an empty input one empty part); a string
		// or character splits literally, like ever (the literal-only pin holds)
		LispVal literal = list(sym("let"),
				list(List.of(list(pat,
						list(sym("cond"), list(list(sym("stringp"), raw), raw),
								list(list(sym("characterp"), raw), list(sym("string"), raw)),
								list(TRUE_CONST,
										list(sym("error"), LispString.literal("split takes a string to split on"))))))),
				result);
		LispVal regex = list(new LispSymbol("RONTOLISP::%CLOJURE-RE-SPLIT"), raw, str, lim);
		return list(sym("let"), list(List.of(list(str, text), list(raw, pattern), list(lim, limit))),
				list(sym("if"), isPatternForm(raw), regex, literal));
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
		// a pattern match swaps through the regex runtime (a string replacement
		// interpolating $ groups, anything else applying to the match); a string
		// or character match swaps literally, like ever
		LispVal literal = list(sym("let*"), list(List.of(list(mat, matchNorm), list(rep, repNorm))),
				list(sym("if"), list(sym("string="), mat, LispString.literal("")), interposed, looped));
		LispVal regex = list(new LispSymbol("RONTOLISP::%CLOJURE-RE-REPLACE"), str, rawMatch, rawRep,
				first ? TRUE_CONST : NIL_CONST);
		return list(sym("let*"), list(List.of(list(str, text), list(rawMatch, match), list(rawRep, replacement))),
				list(sym("if"), isPatternForm(rawMatch), regex, literal));
	}

	/**
	 * Whether the lowered value is a regex pattern: one call to the spliced
	 * {@code rontolisp::%clojure-re-pattern-p}.
	 */
	private static LispVal isPatternForm(LispVal lowered) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-RE-PATTERN-P"), lowered);
	}

	/**
	 * Whether the lowered value is a regex matcher: one call to the spliced
	 * {@code rontolisp::%clojure-re-matcher-p}.
	 */
	private static LispVal isMatcherForm(LispVal lowered) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-RE-MATCHER-P"), lowered);
	}

	/**
	 * Whether the lowered value is regex state (a pattern or a matcher): opaque to every
	 * collection verb, like a deftype or reify value.
	 */
	private static LispVal isRegexForm(LispVal lowered) {
		return list(sym("or"), isPatternForm(lowered), isMatcherForm(lowered));
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
		// the re-* names lower beside the big core switch (which stays under the
		// method-size limit): same position, before any qualified or user name
		if (isReName(name) && coreAllowed(name)) {
			return reCall(name, items);
		}
		LispVal special = builtin(name, items);
		if (special != null) {
			return special;
		}
		VarRef qualified = resolveQualified(name);
		if (qualified != null) {
			return namespaceCall(qualified, items);
		}
		LispVal interop = interopCall(name, items);
		if (interop != null) {
			return interop;
		}
		isTrue(known(name) || this.refers.containsKey(name), "unknown name: " + name);
		VarRef referred = known(name) ? null : this.refers.get(name);
		if (referred != null) {
			return namespaceCall(referred, items);
		}
		List<LispVal> args = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			args.add(lower(items.get(i)));
		}
		if (!isFunction(name)) {
			// a parameter, a let/loop binding or a def'd variable holds the
			// function in the VALUE cell (Lisp-2): a direct call would read the
			// function cell and miss, so call through funcall instead. A defn
			// (and a declare, which keeps its current error) stays direct. A
			// variable whose value may hold a collection goes through the
			// prelude dispatcher instead, which funcalls real functions.
			if (!isDirectVar(name)) {
				return callableApply(idSym(name), cons(sym("list"), args));
			}
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
			case "make-array":
				return makeArrayOf(items);
			case "aget":
				return agetOf(items);
			case "aset":
				return asetOf(items);
			case "alength":
				return alengthOf(items);
			case "vector?":
				return booleanAnswer(plain("vectorp", items));
			case "map":
				isTrue(n >= 2, "map takes a function and collections");
				return mapForm(fnValue(items.get(1)), lowers(items, 2));
			case "filter":
				isTrue(n == 2, "filter takes a predicate and a collection");
				return filterForm(fnValue(items.get(1)), lower(items.get(2)));
			case "reduce":
				isTrue(n == 2 || n == 3, "reduce takes a function, an optional value and a collection");
				if (n == 2) {
					return reduceForm(fnValue(items.get(1)), seqForm(lower(items.get(2))), null);
				}
				return reduceForm(fnValue(items.get(1)), seqForm(lower(items.get(3))), lower(items.get(2)));
			case "apply":
				return applyOf(items);
			case "concat":
				if (items.size() == 1) {
					return NIL_CONST;
				}
				return list(new LispSymbol("RONTOLISP::%CLOJURE-CONCAT"), cons(sym("list"), lowers(items, 1)));
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
			case "keep":
				isTrue(n == 2, "keep takes a function and a collection");
				return keepForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "keep-indexed":
				isTrue(n == 2, "keep-indexed takes a function and a collection");
				return keepIndexedForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "map-indexed":
				isTrue(n == 2, "map-indexed takes a function and a collection");
				return mapIndexedForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "every?":
				isTrue(n == 2, "every? takes a predicate and a collection");
				return everyForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "some":
				isTrue(n == 2, "some takes a predicate and a collection");
				return someForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "remove":
				isTrue(n == 2, "remove takes a predicate and a collection");
				return removeForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "distinct":
				isTrue(n == 1, "distinct takes one collection");
				return distinctForm(seqForm(lower(items.get(1))));
			case "partition":
				return partitionOf(items);
			case "take-while":
				isTrue(n == 2, "take-while takes a predicate and a collection");
				return takeWhileForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "drop-while":
				isTrue(n == 2, "drop-while takes a predicate and a collection");
				return dropWhileForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "interleave":
				return interleaveOf(items);
			case "interpose":
				isTrue(n == 2, "interpose takes a separator and a collection");
				return interposeForm(lower(items.get(1)), seqForm(lower(items.get(2))));
			case "zipmap":
				isTrue(n == 2, "zipmap takes keys and values");
				return zipmapForm(seqForm(lower(items.get(1))), seqForm(lower(items.get(2))));
			case "group-by":
				isTrue(n == 2, "group-by takes a function and a collection");
				return groupByForm(fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "sort":
				return sortOf(items);
			case "sort-by":
				return sortByOf(items);
			case "last":
				isTrue(n == 1, "last takes one collection");
				return list(sym("car"), list(sym("last"), seqForm(lower(items.get(1)))));
			case "butlast":
				isTrue(n == 1, "butlast takes one collection");
				return list(sym("butlast"), seqForm(lower(items.get(1))));
			case "second":
				isTrue(n == 1, "second takes one collection");
				return list(sym("cadr"), seqForm(lower(items.get(1))));
			case "update":
				return updateOf(items);
			case "update-in":
				return updateInOf(items);
			case "assoc-in":
				return assocInOf(items);
			case "get-in":
				return getInOf(items);
			case "select-keys":
				isTrue(n == 2, "select-keys takes a map and keys");
				return selectKeysForm(lower(items.get(1)), seqForm(lower(items.get(2))));
			case "merge-with":
				return mergeWithOf(items);
			case "into":
				return intoOf(items);
			case "frequencies":
				isTrue(n == 1, "frequencies takes one collection");
				return frequenciesForm(seqForm(lower(items.get(1))));
			case "comp":
				return compOf(items);
			case "partial":
				isTrue(n >= 1, "partial takes a function and arguments");
				return partialForm(fnValue(items.get(1)), lowers(items, 2));
			case "complement":
				isTrue(n == 1, "complement takes one function");
				return complementForm(fnValue(items.get(1)));
			case "constantly":
				isTrue(n == 1, "constantly takes one value");
				return constantlyForm(lower(items.get(1)));
			case "identity":
				isTrue(n == 1, "identity takes one value");
				return lower(items.get(1));
			case "memoize":
				isTrue(n == 1, "memoize takes one function");
				return memoizeForm(fnValue(items.get(1)));
			case "trampoline":
				isTrue(n >= 1, "trampoline takes a function and arguments");
				return trampolineForm(fnValue(items.get(1)), cons(sym("list"), lowers(items, 2)));
			case "coll?":
				isTrue(n == 1, "coll? takes one value");
				return booleanAnswer(collRaw(lower(items.get(1))));
			case "string?":
				isTrue(n == 1, "string? takes one value");
				return booleanAnswer(plain("stringp", items));
			case "symbol?":
				isTrue(n == 1, "symbol? takes one value");
				return booleanAnswer(symbolRaw(lower(items.get(1))));
			case "instance?":
				return instanceOf(items);
			case "class":
				isTrue(n == 1, "class takes one value");
				return classForm(lower(items.get(1)));
			case "int", "long":
				isTrue(n == 1, name + " takes one value");
				return intForm(lower(items.get(1)));
			case "unchecked-add":
				isTrue(n == 2, "unchecked-add takes two numbers");
				return list(sym("+"), lower(items.get(1)), lower(items.get(2)));
			case "spit":
				return spitOf(items);
			case "slurp":
				isTrue(n == 1, "slurp takes one path");
				return slurpForm(lower(items.get(1)));
			case "line-seq":
				isTrue(n == 1, "line-seq takes one path or reader");
				return lineSeqForm(lower(items.get(1)));
			case "format":
				return formatOf(items);
			case "file-seq":
				throw new LispReadException("file-seq is not supported yet: directory walks need a design");
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
			case "lazy-seq":
				return lazySeqOf(items);
			case "lazy-cat":
				return lazyCatOf(items);
			case "repeat":
				return repeatOf(items);
			case "cycle":
				isTrue(n == 1, "cycle takes one collection");
				return list(new LispSymbol("RONTOLISP::%CLOJURE-CYCLE"), lower(items.get(1)));
			case "repeatedly":
				return repeatedlyOf(items);
			case "iterate":
				isTrue(n == 2, "iterate takes a function and a value");
				return list(new LispSymbol("RONTOLISP::%CLOJURE-ITERATE"), fnValue(items.get(1)), lower(items.get(2)));
			case "future":
				throw new LispReadException("future is not supported yet: there is no thread pool on any backend");
			case "delay":
				throw new LispReadException("delay is not supported yet: lazy memo cells need a design");
			case "force":
				throw new LispReadException("force is not supported yet: lazy memo cells need a design");
			case "promise":
				throw new LispReadException("promise is not supported yet: blocking rendezvous needs a design");
			case "deliver":
				throw new LispReadException("deliver is not supported yet: blocking rendezvous needs a design");
			default:
				return builtinConvenience(name, items, n);
		}
	}

	/**
	 * The b18 core convenience fns, sliced out of {@link #builtin}: that dispatcher had
	 * crossed HotSpot's {@code HugeMethodLimit} (like the backend
	 * {@code compileConsLocated} slices before it), so these names dispatch through one
	 * more call -- null when the name is none of them, like {@code builtin} itself.
	 */
	private @Nullable LispVal builtinConvenience(String name, List<LispVal> items, int n) {
		switch (name) {
			case "mapv":
				return mapvOf(items);
			case "filterv":
				isTrue(n == 2, "filterv takes a predicate and a collection");
				return filtervForm(fnValue(items.get(1)), lower(items.get(2)));
			case "mapcat":
				return mapcatOf(items);
			case "ffirst":
				isTrue(n == 1, "ffirst takes one collection");
				return ffirstForm(seqForm(lower(items.get(1))));
			case "nfirst":
				isTrue(n == 1, "nfirst takes one collection");
				return nfirstForm(seqForm(lower(items.get(1))));
			case "boolean":
				isTrue(n == 1, "boolean takes one value");
				return booleanForm(lower(items.get(1)));
			case "char":
				isTrue(n == 1, "char takes one value");
				return charForm(lower(items.get(1)));
			case "name":
				isTrue(n == 1, "name takes one value");
				return list(new LispSymbol("RONTOLISP::%CLOJURE-NAME"), lower(items.get(1)));
			case "namespace":
				isTrue(n == 1, "namespace takes one value");
				return list(new LispSymbol("RONTOLISP::%CLOJURE-NAMESPACE"), lower(items.get(1)));
			case "keyword":
				return keywordOf(items);
			case "symbol":
				return symbolOf(items);
			case "assert":
				return assertOf(items);
			case "rand":
				return randOf(items);
			case "rand-int":
				isTrue(n == 1, "rand-int takes one bound");
				return randIntForm(lower(items.get(1)));
			case "rand-nth":
				isTrue(n == 1, "rand-nth takes one collection");
				return randNthForm(lower(items.get(1)));
			case "shuffle":
				isTrue(n == 1, "shuffle takes one collection");
				return shuffleForm(lower(items.get(1)));
			case "vec":
				return vecOf(items);
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
	 * The tag heading a nil method's table key: a nil dispatch value answers the one-list
	 * {@code (:C%NIL)}, never the {@code (:C%KEYWORD "nil")} a literal {@code :nil}
	 * keyword lowers to, so the two stay apart like the oracle tells them apart. No user
	 * value spells the tag (a {@code :C%NIL} source spelling lexes as a keyword, which
	 * wraps behind {@code :C%KEYWORD}), the same reason the record, set and keyword
	 * wrappers keep their cars.
	 */
	private static final LispSymbol NIL_TAG = new LispSymbol(":C%NIL");

	/** A nil method's table key construction: {@code (LIST :C%NIL)}. */
	private static LispVal nilMarkerForm() {
		return list(sym("list"), NIL_TAG);
	}

	/**
	 * A keyword's spelling without its colon: {@code ::kw} resolves against the current
	 * namespace, {@code ::alias/kw} against the alias (or the namespace's own name, or a
	 * known namespace without any require), and anything else stays opaque data, printing
	 * and comparing whole like the oracle's. A bare {@code :} names nothing, and an
	 * unknown alias or a second slash is the oracle's {@code Invalid token} refusal.
	 */
	private String resolveKeywordSpelling(String name) {
		isTrue(name.length() > 1, "a keyword needs a name: " + name);
		if (!name.startsWith("::")) {
			return name.substring(1);
		}
		String rest = name.substring(2);
		int slash = rest.indexOf('/');
		if (slash < 0) {
			if (rest.isEmpty()) {
				throw new LispReadException("Invalid token: " + name);
			}
			return this.currentNs + "/" + rest;
		}
		String alias = rest.substring(0, slash);
		String tail = rest.substring(slash + 1);
		if (alias.isEmpty() || tail.isEmpty() || tail.indexOf('/') >= 0) {
			throw new LispReadException("Invalid token: " + name);
		}
		String ns = this.aliases.get(alias);
		if (ns == null) {
			if (alias.equals(this.currentNs) || isKnownNamespace(alias)) {
				ns = alias;
			}
			else {
				throw new LispReadException("Invalid token: " + name);
			}
		}
		return ns + "/" + tail;
	}

	/**
	 * A keyword in call position: the map lookup {@code (:k m)} (or with a default
	 * {@code (:k m dflt)}), over the same table-aware read {@code get} lowers to, so sets
	 * answer their member and vectors and strings their indexed element too.
	 */
	private LispVal keywordCall(String name, List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, name + " takes a collection and an optional default");
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
	 * A regex literal: the source string compiled to a pattern value at run time (parsed
	 * eagerly, like the oracle). The source datum is a reader-produced literal, so it
	 * travels as is -- shared by quoted and syntax-quoted literals.
	 */
	private static LispVal regexForm(List<LispVal> items) {
		isTrue(items.size() == 2, "a regex literal takes a pattern string");
		return list(new LispSymbol("RONTOLISP::%CLOJURE-RE-COMPILE"), items.get(1));
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
		LispVal src = list(sym("if"), isRecordForm(map), typedTableOf(map), map);
		LispVal grown = cons(sym("append"),
				List.of(list(sym("if"), map, tablePlist(src), NIL_CONST), cons(sym("list"), lowers(items, 2))));
		return list(sym("let"), list(List.of(list(map, lower(items.get(1))))),
				rewrapAnswer(map, tableFromPlist(grown)));
	}

	/**
	 * {@code assoc} as a value: over a map and a rest list of alternating keys and
	 * values, grown in one copy like a call (later pairs winning, onto {@code nil} from
	 * empty). An odd rest count signals, like a call's pair refusal.
	 */
	private LispVal assocValue() {
		LispSymbol map = new LispSymbol(mangle("assoc-map"));
		LispSymbol pairs = new LispSymbol(mangle("assoc-pairs"));
		LispSymbol bound = freshTemp();
		LispVal src = list(sym("if"), isRecordForm(bound), typedTableOf(bound), bound);
		LispVal grown = cons(sym("append"), List.of(list(sym("if"), bound, tablePlist(src), NIL_CONST), pairs));
		LispVal build = list(sym("let"), list(List.of(list(bound, map))), rewrapAnswer(bound, tableFromPlist(grown)));
		LispVal arity = list(sym("error"), LispString.literal("assoc takes a map and key/value pairs"));
		LispVal body = list(sym("if"), list(sym("oddp"), list(sym("length"), pairs)), arity, build);
		return list(sym("lambda"), list(List.of(map, AMPERSAND_REST, pairs)), body);
	}

	/**
	 * A rebuilt table back in the record it came from: {@code assoc} (and everything
	 * through {@link #assocPairForm}, like {@code update} and {@code assoc-in}) keeps the
	 * record's tag and fields, like the oracle. Anything else answers the table.
	 */
	private LispVal rewrapAnswer(LispVal map, LispVal tableForm) {
		LispSymbol done = freshTemp();
		return list(sym("let"), list(List.of(list(done, tableForm))),
				list(sym("if"), isRecordForm(map), wrapRecord(typedTagOf(map), typedFieldsOf(map), done), done));
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
		body.add(dissocAnswer(map, copy));
		LispVal src = list(sym("if"), isRecordForm(map), typedTableOf(map), map);
		LispVal rebuilt = letForm(List.of(list(copy, tableFromPlist(tablePlist(src)))), body);
		return list(sym("let"), list(List.of(list(map, lower(items.get(1))))),
				list(sym("if"), map, rebuilt, NIL_CONST));
	}

	/**
	 * A dissociated table back in its record: the record survives only while every
	 * declared field is still present (removing an extension key keeps the type, removing
	 * a base field drops to a plain map), like the oracle. Anything else answers the
	 * table.
	 */
	private LispVal dissocAnswer(LispVal map, LispVal copy) {
		LispSymbol keep = freshTemp();
		LispSymbol field = freshTemp();
		LispSymbol miss = freshTemp();
		LispVal scan = list(sym("dolist"), list(List.of(field, typedFieldsOf(map))), list(sym("if"),
				list(sym("eq"), list(sym("gethash"), field, copy, miss), miss), list(sym("setq"), keep, NIL_CONST)));
		LispVal rewrap = list(sym("if"), keep, wrapRecord(typedTagOf(map), typedFieldsOf(map), copy), copy);
		return list(sym("let"), list(List.of(list(keep, TRUE_CONST), list(miss, list(sym("list"), NIL_CONST)))),
				list(sym("if"), isRecordForm(map), list(sym("progn"), scan, rewrap), copy));
	}

	/**
	 * {@code dissoc} as a value: over a map and a rest list of keys, copied once and
	 * dropped one by one, like a call (of {@code nil}, {@code nil}).
	 */
	private LispVal dissocValue() {
		LispSymbol map = new LispSymbol(mangle("dissoc-map"));
		LispSymbol keys = new LispSymbol(mangle("dissoc-keys"));
		LispSymbol bound = freshTemp();
		LispSymbol copy = freshTemp();
		LispSymbol one = freshTemp();
		LispVal src = list(sym("if"), isRecordForm(bound), typedTableOf(bound), bound);
		LispVal drops = list(sym("dolist"), list(List.of(one, keys)), list(sym("remhash"), one, copy));
		LispVal rebuilt = list(sym("let"), list(List.of(list(copy, tableFromPlist(tablePlist(src))))), drops,
				dissocAnswer(bound, copy));
		return list(sym("lambda"), list(List.of(map, AMPERSAND_REST, keys)),
				list(sym("let"), list(List.of(list(bound, map))), list(sym("if"), bound, rebuilt, NIL_CONST)));
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
		// a record reads through its entry table, like a map; a deftype or reify is
		// opaque and falls to the default, like the oracle
		branches.add(list(isRecordForm(coll), list(sym("gethash"), key, typedTableOf(coll), dflt)));
		branches.add(list(list(sym("hash-table-p"), coll), list(sym("gethash"), key, coll, dflt)));
		branches.add(list(indexForm(coll, key, true), list(sym("elt"), coll, key)));
		branches.add(list(indexForm(coll, key, false), list(sym("char"), coll, key)));
		branches.add(list(TRUE_CONST, dflt));
		return branches;
	}

	/**
	 * {@code get} as a value: over a collection and a key, or those plus a default -- the
	 * two call shapes, dispatched on the rest count. Any other count signals, like a
	 * call's arity refusal.
	 */
	private LispVal getValue() {
		LispSymbol coll = new LispSymbol(mangle("get-coll"));
		LispSymbol key = new LispSymbol(mangle("get-key"));
		LispSymbol rest = new LispSymbol(mangle("get-rest"));
		LispVal two = getForm(coll, key, NIL_CONST);
		LispVal three = getForm(coll, key, list(sym("car"), rest));
		LispVal arity = list(sym("error"), LispString.literal("get takes a map, a key and an optional default"));
		LispVal body = list(sym("cond"), list(list(sym("null"), rest), two),
				list(list(sym("null"), list(sym("cdr"), rest)), three), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(List.of(coll, key, AMPERSAND_REST, rest)), body);
	}

	private LispVal containsOf(List<LispVal> items) {
		isTrue(items.size() == 3, "contains? takes a collection and a key");
		return containsForm(lower(items.get(1)), lower(items.get(2)));
	}

	/**
	 * The presence test over an already-lowered collection and key: a sentinel
	 * {@code gethash} for maps, records and sets, a bounds check for vectors and strings,
	 * answering {@code T}-or-false.
	 */
	private LispVal containsForm(LispVal coll, LispVal key) {
		LispSymbol bound = freshTemp();
		LispSymbol at = freshTemp();
		LispSymbol miss = freshTemp();
		List<LispVal> bindings = List.of(list(bound, coll), list(at, key), list(miss, list(sym("list"), NIL_CONST)));
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(bound), booleanAnswer(
				list(sym("not"), list(sym("eq"), list(sym("gethash"), at, setInner(bound), miss), miss)))));
		branches.add(list(isRecordForm(bound), booleanAnswer(
				list(sym("not"), list(sym("eq"), list(sym("gethash"), at, typedTableOf(bound), miss), miss)))));
		branches.add(list(list(sym("hash-table-p"), bound),
				booleanAnswer(list(sym("not"), list(sym("eq"), list(sym("gethash"), at, bound, miss), miss)))));
		branches.add(list(indexForm(bound, at, true), TRUE_CONST));
		branches.add(list(indexForm(bound, at, false), TRUE_CONST));
		branches.add(list(TRUE_CONST, this.falseVariable));
		return list(sym("let"), list(bindings), cons(sym("cond"), branches));
	}

	/** {@code contains?} as a value: a two-argument lambda over the same test. */
	private LispVal containsValue() {
		LispSymbol coll = new LispSymbol(mangle("contains-coll"));
		LispSymbol key = new LispSymbol(mangle("contains-key"));
		return list(sym("lambda"), list(List.of(coll, key)), containsForm(coll, key));
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
		return tableKeysForm(lower(items.get(1)), keys);
	}

	/** {@code keys}/{@code vals} over an already-lowered map. */
	private LispVal tableKeysForm(LispVal lowered, boolean keys) {
		LispSymbol map = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal take = keys ? key : val;
		LispVal drop = keys ? val : key;
		LispVal collect = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), drop)),
						list(sym("setq"), acc, list(sym("cons"), take, acc))),
				list(sym("if"), isRecordForm(map), typedTableOf(map), map));
		return list(sym("let"), list(List.of(list(map, lowered))),
				list(sym("if"), map, list(sym("let"), list(List.of(list(acc, NIL_CONST))), collect, acc), NIL_CONST));
	}

	/** {@code keys} as a value: a one-argument lambda over the same accumulation. */
	private LispVal keysValue() {
		LispSymbol coll = new LispSymbol(mangle("keys-coll"));
		return list(sym("lambda"), list(coll), tableKeysForm(coll, true));
	}

	/** {@code vals} as a value: a one-argument lambda over the same accumulation. */
	private LispVal valsValue() {
		LispSymbol coll = new LispSymbol(mangle("vals-coll"));
		return list(sym("lambda"), list(coll), tableKeysForm(coll, false));
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
			// a record contributes its entries, like a map; anything opaque signals
			// in the plist walk, like the oracle
			LispVal src = list(sym("if"), isRecordForm(one), typedTableOf(one), one);
			plists.add(list(sym("if"), one, tablePlist(src), NIL_CONST));
		}
		return list(sym("let"), list(bindings), list(sym("if"), cons(sym("or"), present),
				rewrapAnswer(present.get(0), tableFromPlist(cons(sym("append"), plists))), NIL_CONST));
	}

	/**
	 * {@code merge} as a value: over a rest list of maps, every map's pairs appended in
	 * one copy like a call (later maps winning), rewrapped in the first map's record when
	 * there is one. Of no maps, {@code nil}.
	 */
	private LispVal mergeValue() {
		LispSymbol maps = new LispSymbol(mangle("merge-maps"));
		LispSymbol one = freshTemp();
		LispSymbol found = freshTemp();
		LispSymbol grown = freshTemp();
		LispSymbol probe = freshTemp();
		LispVal src = list(sym("if"), isRecordForm(one), typedTableOf(one), one);
		LispVal onePlist = list(sym("if"), one, tablePlist(src), NIL_CONST);
		LispVal gather = list(sym("mapcar"), list(sym("lambda"), list(one), onePlist), maps);
		LispVal spread = list(sym("apply"), list(sym("function"), sym("append")), gather);
		LispVal find = list(sym("dolist"), list(List.of(probe, maps)),
				list(sym("if"), list(sym("and"), list(sym("null"), found), probe), list(sym("setq"), found, probe)));
		// the answer is nil unless some map is present, but the rewrap follows the
		// first map (a nil first map answers a plain map), like the oracle
		LispVal first = list(sym("car"), maps);
		return list(sym("lambda"), list(AMPERSAND_REST, maps),
				list(sym("let*"), list(List.of(list(found, NIL_CONST), list(grown, spread))), find,
						list(sym("if"), found, rewrapAnswer(first, tableFromPlist(grown)), NIL_CONST)));
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
		return conjTwoForm(coll, lower(itemDatum));
	}

	/**
	 * One conjoined item over an already-lowered collection and item: a set gains a
	 * member, a map gains the item's entries, a vector gains at the end, a list or nil at
	 * the front. Anything else signals, like the oracle's.
	 */
	private LispVal conjTwoForm(LispVal coll, LispVal itemLowered) {
		LispSymbol collSym = freshTemp();
		LispSymbol item = freshTemp();
		List<LispVal> bindings = List.of(list(collSym, coll), list(item, itemLowered));
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(collSym), setAdd(collSym, item)));
		// onto a record the entries join the entry table and the type survives, like
		// the oracle; onto anything opaque the oracle signals, like below
		branches.add(list(isRecordForm(collSym), rewrapAnswer(collSym,
				tableFromPlist(cons(sym("append"), List.of(tablePlist(typedTableOf(collSym)), entryPlist(item)))))));
		branches.add(list(list(sym("hash-table-p"), collSym),
				tableFromPlist(cons(sym("append"), List.of(tablePlist(collSym), entryPlist(item))))));
		branches.add(list(list(sym("vectorp"), collSym),
				list(sym("coerce"),
						list(sym("append"), list(sym("coerce"), collSym, quoted("list")), list(sym("list"), item)),
						quoted("vector"))));
		branches.add(list(
				list(sym("and"), list(sym("or"), list(sym("null"), collSym), list(sym("consp"), collSym)),
						list(sym("not"), isTypedForm(collSym)), list(sym("not"), isRegexForm(collSym))),
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

	/**
	 * {@code conj} as a value: over a collection and a rest list of items, folded one by
	 * one through the same per-kind read, so {@code (map conj ...)} and
	 * {@code (swap! a conj x)} run what a call would run.
	 */
	private LispVal conjValue() {
		LispSymbol coll = new LispSymbol(mangle("conj-coll"));
		LispSymbol items = new LispSymbol(mangle("conj-items"));
		LispSymbol acc = freshTemp();
		LispSymbol one = freshTemp();
		LispVal step = list(sym("lambda"), list(List.of(acc, one)), conjTwoForm(acc, one));
		return list(sym("lambda"), list(List.of(coll, AMPERSAND_REST, items)),
				list(sym("reduce"), step, items, sym(":initial-value"), coll));
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

	/**
	 * {@code disj} as a value: over a set and a rest list of members, copied once and
	 * dropped one by one, like a call (of {@code nil}, {@code nil}; of a non-set, a
	 * signal).
	 */
	private LispVal disjValue() {
		LispSymbol set = new LispSymbol(mangle("disj-set"));
		LispSymbol members = new LispSymbol(mangle("disj-members"));
		LispSymbol bound = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispSymbol one = freshTemp();
		LispVal copy = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("setf"), list(sym("gethash"), key, table), key)),
				setInner(bound));
		LispVal drops = list(sym("dolist"), list(List.of(one, members)), list(sym("remhash"), one, table));
		LispVal kept = list(sym("let"), list(List.of(list(table, makeTable()))), copy, drops, wrapSet(table));
		LispVal needSet = list(sym("error"), LispString.literal("disj needs a set"));
		return list(sym("lambda"), list(List.of(set, AMPERSAND_REST, members)),
				list(sym("let"), list(List.of(list(bound, set))),
						list(sym("if"), bound, list(sym("if"), isSetForm(bound), kept, needSet), NIL_CONST)));
	}

	private LispVal setOf(List<LispVal> items) {
		isTrue(items.size() == 2, "set takes one collection");
		return setForm(lower(items.get(1)));
	}

	/**
	 * A set over an already-lowered collection: every member under itself in a fresh
	 * table, wrapped so verbs tell it from a map. The walk populates the table for
	 * effect; the wrapper answers.
	 */
	private LispVal setForm(LispVal lowered) {
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
		return list(sym("let"), list(List.of(list(coll, lowered), list(table, makeTable()))),
				cons(sym("cond"), branches), wrapSet(table));
	}

	/** {@code set} as a value: a one-argument lambda over the same construction. */
	private LispVal setValue() {
		LispSymbol coll = new LispSymbol(mangle("set-coll"));
		return list(sym("lambda"), list(coll), setForm(coll));
	}

	private LispVal mapConstructorOf(List<LispVal> items, String what) {
		isTrue((items.size() - 1) % 2 == 0, what + " takes key/value pairs");
		return mapBuild(lowers(items, 1));
	}

	/**
	 * {@code hash-map}/{@code array-map} as a value: over a rest list of alternating keys
	 * and values, built in one table like a call. An odd rest count signals, like a
	 * call's pair refusal.
	 */
	private LispVal mapConstructorValue(String what) {
		LispSymbol pairs = new LispSymbol(mangle(what + "-pairs"));
		LispVal arity = list(sym("error"), LispString.literal(what + " takes key/value pairs"));
		LispVal body = list(sym("if"), list(sym("oddp"), list(sym("length"), pairs)), arity, tableFromPlist(pairs));
		return list(sym("lambda"), list(AMPERSAND_REST, pairs), body);
	}

	/**
	 * {@code vec} over one collection: the fully realized seq view coerced to a vector,
	 * so {@code (vec nil)} is {@code []}, {@code (vec "ab")} is the character vector, and
	 * lazy inputs realize fully (an infinite input hangs, like the oracle's).
	 */
	private LispVal vecOf(List<LispVal> items) {
		isTrue(items.size() == 2, "vec takes one collection");
		return vecForm(lower(items.get(1)));
	}

	/** {@code vec} over an already-lowered collection. */
	private LispVal vecForm(LispVal lowered) {
		return list(sym("coerce"), list(new LispSymbol("RONTOLISP::%CLOJURE-REALIZE-ALL"), lowered), quoted("vector"));
	}

	/** {@code vec} as a value: a one-argument lambda over the same coercion. */
	private LispVal vecValue() {
		LispSymbol coll = new LispSymbol(mangle("vec-coll"));
		return list(sym("lambda"), list(coll), vecForm(coll));
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
		// a record counts its entries, like a map; anything opaque signals, like the
		// oracle (a bare length would silently answer the wrapper's size)
		branches.add(list(isRecordForm(coll), list(sym("hash-table-count"), typedTableOf(coll))));
		branches.add(list(list(sym("or"), isDeftypeForm(coll), isReifyForm(coll)),
				list(sym("error"), LispString.literal("count needs a collection"))));
		branches.add(list(isRegexForm(coll), list(sym("error"), LispString.literal("count needs a collection"))));
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
		branches.add(list(isRecordForm(coll), list(sym("zerop"), list(sym("hash-table-count"), typedTableOf(coll)))));
		branches.add(list(list(sym("or"), isDeftypeForm(coll), isReifyForm(coll)),
				list(sym("error"), LispString.literal("empty? needs a collection"))));
		branches.add(list(isRegexForm(coll), list(sym("error"), LispString.literal("empty? needs a collection"))));
		branches.add(list(list(sym("hash-table-p"), coll), list(sym("zerop"), list(sym("hash-table-count"), coll))));
		branches.add(list(list(sym("vectorp"), coll), list(sym("zerop"), list(sym("length"), coll))));
		branches.add(list(list(sym("stringp"), coll), list(sym("zerop"), list(sym("length"), coll))));
		branches.add(list(TRUE_CONST, list(sym("null"), coll)));
		return list(sym("let"), list(List.of(list(coll, lowered))), cons(sym("cond"), branches));
	}

	/**
	 * The seq view of an already-lowered collection: one call to the spliced
	 * {@code rontolisp::%clojure-seq}, which realizes a lazy wrapper one level and
	 * otherwise answers the strict LIST every backend already shares (lists pass through
	 * untouched; vectors and strings coerce; maps contribute one two-vector per entry and
	 * sets one member per element, both in the table's walk order, unspecified like the
	 * oracle's; nil and the false object are empty; anything else signals, like the
	 * oracle's). The collection runs once, as the call's argument. Non-listed verbs
	 * consume one level through this view; only
	 * {@code take}/{@code drop}/{@code first}/{@code rest}/{@code next}/{@code seq}/
	 * {@code map}/{@code filter}/{@code concat}/{@code cons} preserve laziness past it
	 * (b11).
	 * @param lowered the lowered collection
	 * @return the form answering the list view
	 */
	private LispVal seqForm(LispVal lowered) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-SEQ"), lowered);
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
			case "repeat" -> repeatValue();
			case "cycle" -> cycleValue();
			case "iterate" -> iterateValue();
			case "repeatedly" -> repeatedlyValue();
			case "range" -> rangeValue();
			case "dorun" -> dorunValue();
			case "doall" -> doallValue();
			case "subs" -> subsValue();
			case "re-pattern" -> reValue("re-pattern", List.of(1));
			case "re-matcher" -> reValue("re-matcher", List.of(2));
			case "re-find" -> reValue("re-find", List.of(1, 2));
			case "re-seq" -> reValue("re-seq", List.of(2));
			case "re-matches" -> reValue("re-matches", List.of(2));
			case "re-groups" -> reValue("re-groups", List.of(1));
			case "keep" -> keepValue();
			case "keep-indexed" -> keepIndexedValue();
			case "map-indexed" -> mapIndexedValue();
			case "every?" -> everyValue();
			case "some" -> someValue();
			case "remove" -> removeValue();
			case "distinct" -> distinctValue();
			case "partition" -> partitionValue();
			case "take-while" -> takeWhileValue();
			case "drop-while" -> dropWhileValue();
			case "interleave" -> interleaveValue();
			case "interpose" -> interposeValue();
			case "zipmap" -> zipmapValue();
			case "group-by" -> groupByValue();
			case "sort" -> sortValue();
			case "sort-by" -> sortByValue();
			case "last" -> lastValue();
			case "butlast" -> butlastValue();
			case "second" -> secondValue();
			case "mapv" -> mapvValue();
			case "filterv" -> filtervValue();
			case "mapcat" -> mapcatValue();
			case "ffirst" -> ffirstValue();
			case "nfirst" -> nfirstValue();
			case "boolean" -> booleanValue();
			case "char" -> charValue();
			case "name" -> nameValue();
			case "namespace" -> namespaceValue();
			case "keyword" -> keywordValue();
			case "symbol" -> symbolValue();
			case "rand" -> randValue();
			case "rand-int" -> randIntValue();
			case "rand-nth" -> randNthValue();
			case "shuffle" -> shuffleValue();
			case "update" -> updateValue();
			case "update-in" -> updateInValue();
			case "assoc-in" -> assocInValue();
			case "get-in" -> getInValue();
			case "select-keys" -> selectKeysValue();
			case "merge-with" -> mergeWithValue();
			case "into" -> intoValue();
			case "frequencies" -> frequenciesValue();
			case "keys" -> keysValue();
			case "vals" -> valsValue();
			case "assoc" -> assocValue();
			case "dissoc" -> dissocValue();
			case "get" -> getValue();
			case "contains?" -> containsValue();
			case "merge" -> mergeValue();
			case "conj" -> conjValue();
			case "disj" -> disjValue();
			case "set" -> setValue();
			case "hash-map" -> mapConstructorValue("hash-map");
			case "array-map" -> mapConstructorValue("array-map");
			case "vec" -> vecValue();
			case "comp" -> compValue();
			case "partial" -> partialValue();
			case "complement" -> complementValue();
			case "constantly" -> constantlyValue();
			case "identity" -> identityValue();
			case "memoize" -> memoizeValue();
			case "trampoline" -> trampolineValue();
			case "coll?" -> collValue();
			case "string?" -> stringPredValue();
			case "symbol?" -> symbolPredValue();
			case "class" -> classValue();
			case "int", "long" -> intValue();
			case "unchecked-add" -> uncheckedAddValue();
			case "spit" -> spitValue();
			case "slurp" -> slurpValue();
			case "line-seq" -> lineSeqValue();
			case "atom", "volatile!" -> atomValue();
			case "deref" -> derefValue();
			case "swap!", "vswap!" -> swapValue();
			case "reset!", "vreset!" -> resetValue();
			case "compare-and-set!" -> compareAndSetValue();
			case "ref" -> refValue();
			case "alter" -> alterValue("alter");
			case "commute" -> alterValue("commute");
			case "ref-set" -> refSetValue();
			case "agent" -> agentValue();
			case "send" -> sendValue("send");
			case "send-off" -> sendValue("send-off");
			case "with-meta" -> withMetaValue();
			case "odd?" -> predValue(x -> list(sym("oddp"), x));
			case "even?" -> predValue(x -> list(sym("evenp"), x));
			case "zero?" -> predValue(x -> list(sym("zerop"), x));
			case "pos?" -> predValue(x -> list(sym("plusp"), x));
			case "neg?" -> predValue(x -> list(sym("minusp"), x));
			case "nil?" -> predValue(x -> list(sym("null"), x));
			case "some?" -> predValue(x -> list(sym("not"), list(sym("null"), x)));
			case "not" -> notValue();
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

	/** {@code cons} as a value: the item over the collection, lazily when lazy. */
	private LispVal consValue() {
		LispSymbol item = new LispSymbol(mangle("cons-item"));
		LispSymbol coll = new LispSymbol(mangle("cons-coll"));
		return list(sym("lambda"), list(List.of(item, coll)), consForm(item, coll));
	}

	/** {@code count} as a value: the table-aware count. */
	private LispVal countValue() {
		LispSymbol coll = new LispSymbol(mangle("count-coll"));
		return list(sym("lambda"), list(coll), countForm(coll));
	}

	/**
	 * {@code empty?} as a value: the table-aware emptiness test, answering
	 * {@code T}-or-false.
	 */
	private LispVal emptyValue() {
		LispSymbol coll = new LispSymbol(mangle("empty-coll"));
		return list(sym("lambda"), list(coll), booleanAnswer(emptyForm(coll)));
	}

	/** {@code map} as a value: over a function and one rest list of collections. */
	private LispVal mapValue() {
		LispSymbol fn = new LispSymbol(mangle("map-fn"));
		LispSymbol colls = new LispSymbol(mangle("map-colls"));
		LispVal arity = list(sym("error"), LispString.literal("map takes a function and collections"));
		LispVal call = list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), fn, colls);
		LispVal body = list(sym("if"), list(sym("null"), colls), arity, call);
		return list(sym("lambda"), list(List.of(fn, AMPERSAND_REST, colls)), body);
	}

	/** {@code filter} as a value: the predicate over the collection. */
	private LispVal filterValue() {
		LispSymbol pred = new LispSymbol(mangle("filter-pred"));
		LispSymbol coll = new LispSymbol(mangle("filter-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), filterForm(pred, coll));
	}

	/**
	 * {@code reduce} as a value: over a function and a collection, or a function, a value
	 * and a collection -- the two call shapes, dispatched on the rest count. Any other
	 * count signals, like a call's arity refusal.
	 */
	private LispVal reduceValue() {
		LispSymbol fn = new LispSymbol(mangle("reduce-fn"));
		LispSymbol args = new LispSymbol(mangle("reduce-args"));
		LispVal two = reduceForm(fn, seqForm(list(sym("car"), args)), null);
		LispVal three = reduceForm(fn, seqForm(list(sym("car"), list(sym("cdr"), args))), list(sym("car"), args));
		LispVal arity = list(sym("error"),
				LispString.literal("reduce takes a function, an optional value and a collection"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), two),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), three), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(List.of(fn, AMPERSAND_REST, args)), body);
	}

	/** {@code concat} as a value: every argument appended, lazily when lazy. */
	private LispVal concatValue() {
		LispSymbol colls = new LispSymbol(mangle("concat-colls"));
		return list(sym("lambda"), list(AMPERSAND_REST, colls),
				list(new LispSymbol("RONTOLISP::%CLOJURE-CONCAT"), colls));
	}

	/** {@code take} as a value: the strict prefix over the collection. */
	private LispVal takeValue() {
		LispSymbol count = new LispSymbol(mangle("take-count"));
		LispSymbol coll = new LispSymbol(mangle("take-coll"));
		return list(sym("lambda"), list(List.of(count, coll)), takeForm(count, coll));
	}

	/** {@code drop} as a value: the collection past the strict prefix. */
	private LispVal dropValue() {
		LispSymbol count = new LispSymbol(mangle("drop-count"));
		LispSymbol coll = new LispSymbol(mangle("drop-coll"));
		return list(sym("lambda"), list(List.of(count, coll)), dropForm(count, coll));
	}

	/**
	 * {@code repeat} as a value: over a value, or a count and a value -- the two call
	 * shapes, dispatched on the rest count. Any other count signals, like a call's arity
	 * refusal.
	 */
	private LispVal repeatValue() {
		LispSymbol args = new LispSymbol(mangle("repeat-args"));
		LispVal one = list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT"), list(sym("car"), args));
		LispVal two = list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT-N"), list(sym("car"), args),
				list(sym("car"), list(sym("cdr"), args)));
		LispVal arity = list(sym("error"), LispString.literal("repeat takes a value, or a count and a value"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), one),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), two), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/** {@code cycle} as a value: a one-argument lambda over the cycled seq. */
	private LispVal cycleValue() {
		LispSymbol coll = new LispSymbol(mangle("cycle-coll"));
		return list(sym("lambda"), list(coll), list(new LispSymbol("RONTOLISP::%CLOJURE-CYCLE"), coll));
	}

	/** {@code iterate} as a value: a two-argument lambda over the iterated seq. */
	private LispVal iterateValue() {
		LispSymbol fun = new LispSymbol(mangle("iterate-fn"));
		LispSymbol start = new LispSymbol(mangle("iterate-start"));
		return list(sym("lambda"), list(List.of(fun, start)),
				list(new LispSymbol("RONTOLISP::%CLOJURE-ITERATE"), fun, start));
	}

	/**
	 * {@code repeatedly} as a value: over a function, or a count and a function -- the
	 * two call shapes, dispatched on the rest count. Any other count signals, like a
	 * call's arity refusal.
	 */
	private LispVal repeatedlyValue() {
		LispSymbol args = new LispSymbol(mangle("repeatedly-args"));
		LispVal one = list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY"), list(sym("car"), args));
		LispVal two = list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY-N"), list(sym("car"), args),
				list(sym("car"), list(sym("cdr"), args)));
		LispVal arity = list(sym("error"),
				LispString.literal("repeatedly takes a function, or a count and a function"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), one),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), two), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
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
		LispVal fun = fnValue(items.get(1));
		List<LispVal> pres = new ArrayList<>();
		for (int i = 2; i < items.size() - 1; i++) {
			pres.add(lower(items.get(i)));
		}
		LispVal last = seqForm(lower(items.get(items.size() - 1)));
		if (isDirectFun(fun)) {
			List<LispVal> out = new ArrayList<>();
			out.add(sym("apply"));
			out.add(fun);
			out.addAll(pres);
			out.add(last);
			return list(out);
		}
		LispSymbol cell = freshTemp();
		LispVal tail = pres.isEmpty() ? last : list(sym("append"), cons(sym("list"), pres), last);
		return list(sym("let*"), list(List.of(list(cell, fun))), callableApply(cell, tail));
	}

	/**
	 * {@code map} over an already-lowered function and seq view: direct for real
	 * functions, through the dispatcher for values that may hold collections.
	 */
	/**
	 * {@code map} over an already-lowered function and already-lowered collections (one
	 * or more): one call to the spliced {@code rontolisp::%clojure-map}, which applies
	 * through the IFn dispatcher (real functions and collection values alike) and answers
	 * a lazy wrapper when any input is lazy, the strict list otherwise. Stops at the
	 * shortest input, like the oracle.
	 */
	private LispVal mapForm(LispVal fun, List<LispVal> colls) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-MAP"), fun, cons(sym("list"), colls));
	}

	/**
	 * {@code filter} over an already-lowered predicate and collection: one call to the
	 * spliced {@code rontolisp::%clojure-filter}, which tests Clojure truthiness (a false
	 * object drops like nil) and answers a lazy wrapper when the input is lazy, the
	 * strict list otherwise.
	 */
	private LispVal filterForm(LispVal fun, LispVal coll) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-FILTER"), fun, coll);
	}

	/**
	 * {@code reduce} over an already-lowered function, seq view and optional initial
	 * value: direct for real functions, through the dispatcher otherwise.
	 */
	private LispVal reduceForm(LispVal fun, LispVal seq, @Nullable LispVal init) {
		if (isDirectFun(fun)) {
			if (init == null) {
				return list(sym("reduce"), fun, seq);
			}
			return list(sym("reduce"), fun, seq, sym(":initial-value"), init);
		}
		LispSymbol cell = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol one = freshTemp();
		LispVal step = list(sym("lambda"), list(List.of(acc, one)),
				callableApply(cell, cons(sym("list"), List.of(acc, one))));
		List<LispVal> call = new ArrayList<>();
		call.add(sym("reduce"));
		call.add(step);
		call.add(seq);
		if (init != null) {
			call.add(sym(":initial-value"));
			call.add(init);
		}
		return list(sym("let*"), list(List.of(list(cell, fun))), list(call));
	}

	/** {@code take}: the first {@code n} of the collection as a strict list. */
	private LispVal takeOf(List<LispVal> items) {
		isTrue(items.size() == 3, "take takes a count and a collection");
		return takeForm(lower(items.get(1)), lower(items.get(2)));
	}

	/**
	 * The first {@code count} of an already-lowered collection: one call to the spliced
	 * {@code rontolisp::%clojure-take}, which steps through one wrapper at a time, so
	 * {@code (take n infinite)} terminates with a strict prefix.
	 */
	private LispVal takeForm(LispVal count, LispVal coll) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-TAKE"), count, coll);
	}

	/** {@code drop}: the collection past its first {@code n}. */
	private LispVal dropOf(List<LispVal> items) {
		isTrue(items.size() == 3, "drop takes a count and a collection");
		return dropForm(lower(items.get(1)), lower(items.get(2)));
	}

	/** The already-lowered collection past the first {@code count}. */
	private LispVal dropForm(LispVal count, LispVal coll) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-DROP"), count, coll);
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
	private LispVal lazySeqOf(List<LispVal> items) {
		RecurTarget target = new RecurTarget(freshRecurName(), true);
		target.setArity(0, false);
		pushRecurTarget(target);
		try {
			LispVal lambda = list(sym("lambda"), list(List.of()), bodyTail(items, 1));
			LispVal thunk = target.used() ? labelsSelfCall(target.callName(), lambda) : lambda;
			return list(new LispSymbol("RONTOLISP::%CLOJURE-MAKE-LAZY"), thunk);
		}
		finally {
			this.recurTargets.pop();
		}
	}

	/**
	 * {@code (lazy-cat e...)}: each expression behind its own {@code lazy-seq},
	 * concatenated lazily -- a datum rewrite onto {@code concat}, so chunk-free laziness
	 * holds per member. Of none, nil.
	 */
	private LispVal lazyCatOf(List<LispVal> items) {
		if (items.size() == 1) {
			return NIL_CONST;
		}
		List<LispVal> form = new ArrayList<>();
		form.add(new LispSymbol("concat"));
		for (int i = 1; i < items.size(); i++) {
			form.add(list(new LispSymbol("lazy-seq"), items.get(i)));
		}
		return lower(list(form));
	}

	/**
	 * {@code (repeat x)} (infinite, lazy) or {@code (repeat n x)} (finite, strict like
	 * the oracle's print).
	 */
	private LispVal repeatOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "repeat takes a value, or a count and a value");
		if (n == 1) {
			return list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT"), lower(items.get(1)));
		}
		return list(new LispSymbol("RONTOLISP::%CLOJURE-REPEAT-N"), lower(items.get(1)), lower(items.get(2)));
	}

	/**
	 * {@code (repeatedly f)} (infinite, lazy) or {@code (repeatedly n f)} (finite, strict
	 * like the oracle's print).
	 */
	private LispVal repeatedlyOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "repeatedly takes a function, or a count and a function");
		if (n == 1) {
			return list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY"), fnValue(items.get(1)));
		}
		return list(new LispSymbol("RONTOLISP::%CLOJURE-REPEATEDLY-N"), lower(items.get(1)), fnValue(items.get(2)));
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
		return list(new LispSymbol("RONTOLISP::%CLOJURE-CONS"), item, coll);
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
			// the body answers nil through the loops, never the target: a recur
			// inside one is not in tail position, like the oracle
			LispVal inner = nonTailBody(items, 2);
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
		// the body answers nil through the loop, never the target: a recur inside
		// one is not in tail position, like the oracle
		return inScope(scope, () -> list(sym("dotimes"), list(List.of(idSym(name), count)), nonTailBody(items, 2)));
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
			LispVal pattern = stripMeta(bindings.get(i));
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
		// two records compare by tag plus entries (never equal to a plain map, like
		// the oracle); a deftype or reify on either side is identity, like the oracle
		branches.add(list(list(sym("and"), isRecordForm(left), isRecordForm(right)),
				list(sym("and"), list(sym("equal"), typedTagOf(left), typedTagOf(right)),
						mapEquality(typedTableOf(left), typedTableOf(right), eq))));
		branches.add(list(list(sym("or"), list(sym("and"), isTypedForm(left), isTypedForm(right)), isDeftypeForm(left),
				isReifyForm(left), isDeftypeForm(right), isReifyForm(right)), list(sym("eq"), left, right)));
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
				return namespaceValue(qualified);
			}
		}
		LispVal collection = collectionValue(form);
		if (collection != null) {
			return collection;
		}
		return lower(form);
	}

	/**
	 * A keyword as a function value: the lookup over one argument plus an optional
	 * default, so {@code (map :k coll)} reads the key out of each member and a
	 * keyword-dispatched multimethod called with several arguments dispatches on the
	 * lookup with the second call argument as the default, like the oracle (the
	 * dispatcher applies the dispatch function to every call argument). The key lowers
	 * once, behind a temporary; the collection is the lambda's first parameter and the
	 * default reads the rest list once -- the same rest-tolerant shape the map/vector/set
	 * siblings lower to. Trailing arguments past the default are ignored, like those
	 * siblings (a lenient superset: the oracle signals past two).
	 * @param keyDatum the keyword datum
	 * @return the form
	 */
	private LispVal keywordFn(LispVal keyDatum) {
		LispSymbol coll = freshTemp();
		LispSymbol rest = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol dflt = freshTemp();
		return list(sym("lambda"), list(List.of(coll, AMPERSAND_REST, rest)), list(sym("let"),
				list(List.of(list(key, lower(keyDatum)),
						list(dflt, list(sym("if"), list(sym("null"), rest), NIL_CONST, list(sym("car"), rest))))),
				cons(sym("cond"), getBranches(coll, key, dflt))));
	}

	/**
	 * A predicate as a first-class value: a lambda answering {@code T}-or-false, like a
	 * call, so {@code (map odd? ...)} prints what the oracle prints; sequence operators
	 * that need raw truthiness test through it explicitly. Null when not a predicate.
	 * @param name the Clojure name
	 * @return the lambda, or null
	 */
	private @Nullable LispVal predicateValue(String name) {
		LispSymbol arg = new LispSymbol(mangle("pred"));
		return switch (name) {
			case "false?" -> list(sym("LAMBDA"), list(arg), booleanAnswer(list(sym("EQ"), arg, this.falseVariable)));
			case "true?" -> list(sym("LAMBDA"), list(arg), booleanAnswer(list(sym("EQ"), arg, TRUE_CONST)));
			case "boolean?" -> list(sym("LAMBDA"), list(arg), booleanAnswer(
					list(sym("OR"), list(sym("EQ"), arg, TRUE_CONST), list(sym("EQ"), arg, this.falseVariable))));
			default -> null;
		};
	}

	/**
	 * A one-argument predicate as a function value, answering {@code T}-or-false like its
	 * call: the raw Common Lisp test runs once, behind a temporary.
	 * @param raw the raw test over the bound value
	 * @return the lambda
	 */
	/** {@code not} as a value: the falsehood of Clojure truthiness. */
	private LispVal notValue() {
		LispSymbol arg = freshTemp();
		return list(sym("lambda"), list(arg),
				list(sym("if"), list(sym("or"), list(sym("null"), arg), list(sym("eq"), arg, this.falseVariable)),
						TRUE_CONST, this.falseVariable));
	}

	private LispVal predValue(java.util.function.Function<LispVal, LispVal> raw) {
		LispSymbol arg = freshTemp();
		return list(sym("lambda"), list(arg), booleanAnswer(raw.apply(arg)));
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
			return lowerTailSlot(forms.get(0));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(sym("progn"));
		for (int i = 0; i < forms.size() - 1; i++) {
			out.add(lower(forms.get(i)));
		}
		out.add(lowerTailSlot(forms.get(forms.size() - 1)));
		return list(out);
	}

	private LispVal body(List<LispVal> items, int from) {
		int n = items.size() - from;
		if (n <= 0) {
			return NIL_CONST;
		}
		if (n == 1) {
			return lowerTailSlot(items.get(from));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(sym("progn"));
		for (int i = from; i < items.size() - 1; i++) {
			out.add(lower(items.get(i)));
		}
		out.add(lowerTailSlot(items.get(items.size() - 1)));
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
		// metadata on a pattern (type hints like ^String) is dropped, like the
		// rest: it never affects dispatch
		pattern = stripMeta(pattern);
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

	// b15 seq verbs: each over the seq view, strict lists, nil-for-empty

	/**
	 * {@code keep}: the non-nil results of the function over the seq view. {@code false}
	 * is kept (only nil drops), and a signalling function signals --
	 * {@code (keep inc [1 nil 2])} throws, like the oracle, instead of skipping.
	 */
	private LispVal keepForm(LispVal fn, LispVal seq) {
		LispSymbol fun = freshTemp();
		LispSymbol coll = freshTemp();
		LispSymbol one = freshTemp();
		LispVal mapped = list(sym("mapcar"), list(sym("lambda"), list(one), callFun(fn, fun, List.of(one))), coll);
		return list(sym("let*"), list(List.of(list(fun, fn), list(coll, seq))),
				list(sym("remove-if"), list(sym("function"), sym("null")), mapped));
	}

	/** {@code keep} as a value: a two-argument lambda over the same removal. */
	private LispVal keepValue() {
		LispSymbol fun = new LispSymbol(mangle("keep-fn"));
		LispSymbol coll = new LispSymbol(mangle("keep-coll"));
		return list(sym("lambda"), list(List.of(fun, coll)), keepForm(fun, seqForm(coll)));
	}

	/**
	 * {@code keep-indexed}: like {@code keep}, but the function takes the index and the
	 * item. A labels self call accumulating in reverse, so it stays tail-recursive.
	 */
	private LispVal keepIndexedForm(LispVal fn, LispVal seq) {
		String name = mangle("keep-indexed-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = freshTemp();
		LispSymbol coll = freshTemp();
		LispSymbol at = freshTemp();
		LispSymbol rest = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol got = freshTemp();
		LispVal invoked = callFun(fn, fun, List.of(at, list(sym("car"), rest)));
		LispVal step = list(sym("if"), list(sym("null"), rest), list(sym("reverse"), acc),
				list(sym("let"), list(List.of(list(got, invoked))),
						list(sym("if"), list(sym("null"), got),
								list(self, list(sym("+"), at, new LispInteger(1)), list(sym("cdr"), rest), acc),
								list(self, list(sym("+"), at, new LispInteger(1)), list(sym("cdr"), rest),
										list(sym("cons"), got, acc)))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(at, rest, acc)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(fun, fn), list(coll, seq))),
				list(sym("labels"), list(List.of(binding)), list(self, new LispInteger(0), coll, NIL_CONST)));
	}

	/** {@code keep-indexed} as a value: a two-argument lambda over the same loop. */
	private LispVal keepIndexedValue() {
		LispSymbol fun = new LispSymbol(mangle("keep-indexed-fn"));
		LispSymbol coll = new LispSymbol(mangle("keep-indexed-coll"));
		return list(sym("lambda"), list(List.of(fun, coll)), keepIndexedForm(fun, seqForm(coll)));
	}

	/**
	 * {@code map-indexed}: the function of index and item over the seq view, strictly.
	 * Same loop as {@link #keepIndexedForm}, keeping every result.
	 */
	private LispVal mapIndexedForm(LispVal fn, LispVal seq) {
		String name = mangle("map-indexed-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = freshTemp();
		LispSymbol coll = freshTemp();
		LispSymbol at = freshTemp();
		LispSymbol rest = freshTemp();
		LispSymbol acc = freshTemp();
		LispVal invoked = callFun(fn, fun, List.of(at, list(sym("car"), rest)));
		LispVal step = list(sym("if"), list(sym("null"), rest), list(sym("reverse"), acc), list(self,
				list(sym("+"), at, new LispInteger(1)), list(sym("cdr"), rest), list(sym("cons"), invoked, acc)));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(at, rest, acc)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(fun, fn), list(coll, seq))),
				list(sym("labels"), list(List.of(binding)), list(self, new LispInteger(0), coll, NIL_CONST)));
	}

	/** {@code map-indexed} as a value: a two-argument lambda over the same loop. */
	private LispVal mapIndexedValue() {
		LispSymbol fun = new LispSymbol(mangle("map-indexed-fn"));
		LispSymbol coll = new LispSymbol(mangle("map-indexed-coll"));
		return list(sym("lambda"), list(List.of(fun, coll)), mapIndexedForm(fun, seqForm(coll)));
	}

	/**
	 * {@code every?}: true when the predicate holds for every member, answering
	 * {@code T}-or-false directly (empty is true, like the oracle).
	 */
	private LispVal everyForm(LispVal fn, LispVal seq) {
		String name = mangle("every-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = freshTemp();
		LispSymbol rest = freshTemp();
		LispSymbol got = freshTemp();
		LispVal step = list(sym("if"), list(sym("null"), rest), TRUE_CONST,
				list(sym("let"), list(List.of(list(got, callFun(fn, fun, List.of(list(sym("car"), rest)))))),
						list(sym("if"),
								list(sym("or"), list(sym("null"), got), list(sym("eq"), got, this.falseVariable)),
								this.falseVariable, list(self, list(sym("cdr"), rest)))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(rest)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(fun, fn))),
				list(sym("labels"), list(List.of(binding)), list(self, seq)));
	}

	/** {@code every?} as a value: a two-argument lambda over the same loop. */
	private LispVal everyValue() {
		LispSymbol pred = new LispSymbol(mangle("every-pred"));
		LispSymbol coll = new LispSymbol(mangle("every-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), everyForm(pred, seqForm(coll)));
	}

	/**
	 * {@code some}: the first truthy predicate result, or nil. The predicate's own value
	 * answers (not the member), like the oracle.
	 */
	private LispVal someForm(LispVal fn, LispVal seq) {
		String name = mangle("some-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fun = freshTemp();
		LispSymbol rest = freshTemp();
		LispSymbol got = freshTemp();
		LispVal step = list(sym("if"), list(sym("null"), rest), NIL_CONST,
				list(sym("let"), list(List.of(list(got, callFun(fn, fun, List.of(list(sym("car"), rest)))))),
						list(sym("if"),
								list(sym("or"), list(sym("null"), got), list(sym("eq"), got, this.falseVariable)),
								list(self, list(sym("cdr"), rest)), got)));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(rest)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(fun, fn))),
				list(sym("labels"), list(List.of(binding)), list(self, seq)));
	}

	/** {@code some} as a value: a two-argument lambda over the same loop. */
	private LispVal someValue() {
		LispSymbol pred = new LispSymbol(mangle("some-pred"));
		LispSymbol coll = new LispSymbol(mangle("some-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), someForm(pred, seqForm(coll)));
	}

	/** {@code remove}: the members the predicate rejects, over the seq view. */
	private LispVal removeForm(LispVal fn, LispVal seq) {
		LispSymbol pred = freshTemp();
		LispSymbol coll = freshTemp();
		LispSymbol one = freshTemp();
		LispSymbol got = freshTemp();
		LispVal invoke = isDirectFun(fn) ? list(sym("funcall"), pred, one)
				: callableApply(pred, list(sym("list"), one));
		LispVal test = list(sym("let"), list(List.of(list(got, invoke))),
				list(sym("not"), list(sym("or"), list(sym("null"), got), list(sym("eq"), got, this.falseVariable))));
		return list(sym("let*"), list(List.of(list(pred, fn), list(coll, seq))),
				list(sym("remove-if"), list(sym("lambda"), list(one), test), coll));
	}

	/** {@code remove} as a value: a two-argument lambda over the same removal. */
	private LispVal removeValue() {
		LispSymbol pred = new LispSymbol(mangle("remove-pred"));
		LispSymbol coll = new LispSymbol(mangle("remove-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), removeForm(pred, seqForm(coll)));
	}

	/**
	 * {@code distinct}: the seq view with later duplicates dropped, first occurrences
	 * kept in order. Membership is {@code equal} (vectors key by identity, like the table
	 * runtime).
	 */
	private LispVal distinctForm(LispVal seq) {
		String name = mangle("distinct-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol table = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol rest = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol one = freshTemp();
		LispVal keep = list(sym("progn"), list(sym("setf"), list(sym("gethash"), one, table), one),
				list(self, list(sym("cdr"), rest), list(sym("cons"), one, acc)));
		LispVal step = list(sym("if"), list(sym("null"), rest), list(sym("reverse"), acc),
				list(sym("let"), list(List.of(list(one, list(sym("car"), rest)))),
						list(sym("if"), list(sym("eq"), list(sym("gethash"), one, table, miss), miss), keep,
								list(self, list(sym("cdr"), rest), acc))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(rest, acc)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(table, makeTable()), list(miss, list(sym("list"), NIL_CONST)))),
				list(sym("labels"), list(List.of(binding)), list(self, seq, NIL_CONST)));
	}

	/** {@code distinct} as a value: a one-argument lambda over the same loop. */
	private LispVal distinctValue() {
		LispSymbol coll = new LispSymbol(mangle("distinct-coll"));
		return list(sym("lambda"), list(coll), distinctForm(seqForm(coll)));
	}

	/** {@code partition}: size, optional step (defaulting to the size), collection. */
	private LispVal partitionOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 3, "partition takes a size, an optional step and a collection");
		LispSymbol size = freshTemp();
		LispSymbol step = freshTemp();
		LispSymbol coll = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(size, lower(items.get(1))));
		bindings.add(list(step, n == 3 ? lower(items.get(2)) : size));
		bindings.add(list(coll, seqForm(lower(items.get(n)))));
		return list(sym("let*"), list(bindings), partitionForm(size, step, coll));
	}

	/**
	 * The partition loop over already-bound size, step and seq: full groups consed, an
	 * incomplete tail dropped, like the oracle. A non-positive size signals.
	 */
	private LispVal partitionForm(LispVal size, LispVal step, LispVal seq) {
		String name = mangle("partition-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol rest = freshTemp();
		LispSymbol part = freshTemp();
		LispVal stepBody = list(sym("if"), list(sym("null"), rest), NIL_CONST,
				list(sym("let"), list(List.of(list(part, takeForm(size, rest)))),
						list(sym("if"), list(sym("<"), list(sym("length"), part), size), NIL_CONST,
								list(sym("cons"), part, list(self, list(sym("nthcdr"), step, rest))))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(rest)), cons(stepBody, List.of())));
		return list(sym("if"), list(sym("<="), size, new LispInteger(0)),
				list(sym("error"), LispString.literal("partition takes a positive size")),
				list(sym("labels"), list(List.of(binding)), list(self, seq)));
	}

	/** {@code partition} as a value: a one- or two-rest lambda over the same loop. */
	private LispVal partitionValue() {
		LispSymbol args = new LispSymbol(mangle("partition-args"));
		LispVal arity = list(sym("error"),
				LispString.literal("partition takes a size, an optional step and a collection"));
		LispVal one = partitionForm(list(sym("car"), args), list(sym("car"), args),
				seqForm(list(sym("car"), list(sym("cdr"), args))));
		LispVal two = partitionForm(list(sym("car"), args), list(sym("car"), list(sym("cdr"), args)),
				seqForm(list(sym("car"), list(sym("cdr"), list(sym("cdr"), args)))));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)),
						list(sym("if"), list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), one, arity)),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), list(sym("cdr"), args)))), two),
				list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/**
	 * {@code take-while}: the strict prefix while the predicate stays truthy
	 * ({@code false} stops, like nil).
	 */
	private LispVal takeWhileForm(LispVal fn, LispVal seq) {
		String name = mangle("take-while-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pred = freshTemp();
		LispSymbol rest = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol got = freshTemp();
		LispVal invoked = callFun(fn, pred, List.of(list(sym("car"), rest)));
		LispVal step = list(sym("if"), list(sym("null"), rest), list(sym("reverse"), acc), list(sym("let"),
				list(List.of(list(got, invoked))),
				list(sym("if"), list(sym("or"), list(sym("null"), got), list(sym("eq"), got, this.falseVariable)),
						list(sym("reverse"), acc),
						list(self, list(sym("cdr"), rest), list(sym("cons"), list(sym("car"), rest), acc)))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(rest, acc)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(pred, fn))),
				list(sym("labels"), list(List.of(binding)), list(self, seq, NIL_CONST)));
	}

	/** {@code take-while} as a value: a two-argument lambda over the same loop. */
	private LispVal takeWhileValue() {
		LispSymbol pred = new LispSymbol(mangle("take-while-pred"));
		LispSymbol coll = new LispSymbol(mangle("take-while-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), takeWhileForm(pred, seqForm(coll)));
	}

	/** {@code drop-while}: the seq view past the truthy prefix, sharing the tail. */
	private LispVal dropWhileForm(LispVal fn, LispVal seq) {
		String name = mangle("drop-while-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol pred = freshTemp();
		LispSymbol rest = freshTemp();
		LispSymbol got = freshTemp();
		LispVal invoked = callFun(fn, pred, List.of(list(sym("car"), rest)));
		LispVal step = list(sym("if"), list(sym("null"), rest), NIL_CONST,
				list(sym("let"), list(List.of(list(got, invoked))),
						list(sym("if"),
								list(sym("or"), list(sym("null"), got), list(sym("eq"), got, this.falseVariable)), rest,
								list(self, list(sym("cdr"), rest)))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(rest)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(pred, fn))),
				list(sym("labels"), list(List.of(binding)), list(self, seq)));
	}

	/** {@code drop-while} as a value: a two-argument lambda over the same loop. */
	private LispVal dropWhileValue() {
		LispSymbol pred = new LispSymbol(mangle("drop-while-pred"));
		LispSymbol coll = new LispSymbol(mangle("drop-while-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), dropWhileForm(pred, seqForm(coll)));
	}

	/** {@code interleave}: round-robin over the seq views, stopping at the shortest. */
	private LispVal interleaveOf(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return NIL_CONST;
		}
		List<LispVal> seqs = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			seqs.add(seqForm(lower(items.get(i))));
		}
		return interleaveGo(cons(sym("list"), seqs));
	}

	/**
	 * The interleave loop over an already-lowered list of seq views: heads appended while
	 * every view is non-empty.
	 */
	private LispVal interleaveGo(LispVal lists) {
		String name = mangle("interleave-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol rest = freshTemp();
		LispVal stop = list(sym("or"), list(sym("null"), rest),
				list(sym("not"), list(sym("every"), list(sym("function"), sym("identity")), rest)));
		LispVal step = list(sym("if"), stop, NIL_CONST,
				list(sym("append"), list(sym("mapcar"), list(sym("function"), sym("car")), rest),
						list(self, list(sym("mapcar"), list(sym("function"), sym("cdr")), rest))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(rest)), cons(step, List.of())));
		return list(sym("labels"), list(List.of(binding)), list(self, lists));
	}

	/** {@code interleave} as a value: every argument's seq view interleaved. */
	private LispVal interleaveValue() {
		LispSymbol colls = new LispSymbol(mangle("interleave-colls"));
		return list(sym("lambda"), list(AMPERSAND_REST, colls), interleaveGo(list(sym("mapcar"), seqValue(), colls)));
	}

	/**
	 * {@code interpose}: the separator between every two members, strictly. The head
	 * answers bare, so a one-member collection never shows the separator.
	 */
	private LispVal interposeForm(LispVal sep, LispVal seq) {
		LispSymbol gap = freshTemp();
		LispSymbol coll = freshTemp();
		LispSymbol one = freshTemp();
		LispVal looped = list(sym("mapcan"), list(sym("lambda"), list(one), list(sym("list"), gap, one)),
				list(sym("cdr"), coll));
		return list(sym("let*"), list(List.of(list(gap, sep), list(coll, seq))),
				list(sym("if"), list(sym("null"), coll), NIL_CONST, list(sym("cons"), list(sym("car"), coll), looped)));
	}

	/** {@code interpose} as a value: a two-argument lambda over the same shape. */
	private LispVal interposeValue() {
		LispSymbol gap = new LispSymbol(mangle("interpose-sep"));
		LispSymbol coll = new LispSymbol(mangle("interpose-coll"));
		return list(sym("lambda"), list(List.of(gap, coll)), interposeForm(gap, seqForm(coll)));
	}

	/**
	 * {@code zipmap}: a fresh map pairing each key with its value, stopping at the
	 * shorter side, like the oracle.
	 */
	private LispVal zipmapForm(LispVal keys, LispVal vals) {
		String name = mangle("zipmap-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol table = freshTemp();
		LispSymbol keyRest = freshTemp();
		LispSymbol valRest = freshTemp();
		LispVal step = list(sym("if"), list(sym("or"), list(sym("null"), keyRest), list(sym("null"), valRest)), table,
				list(sym("progn"),
						list(sym("setf"), list(sym("gethash"), list(sym("car"), keyRest), table),
								list(sym("car"), valRest)),
						list(self, list(sym("cdr"), keyRest), list(sym("cdr"), valRest))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(keyRest, valRest)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(table, makeTable()))),
				list(sym("labels"), list(List.of(binding)), list(self, keys, vals)));
	}

	/** {@code zipmap} as a value: a two-argument lambda over the same loop. */
	private LispVal zipmapValue() {
		LispSymbol keys = new LispSymbol(mangle("zipmap-keys"));
		LispSymbol vals = new LispSymbol(mangle("zipmap-vals"));
		return list(sym("lambda"), list(List.of(keys, vals)), zipmapForm(seqForm(keys), seqForm(vals)));
	}

	/**
	 * {@code group-by}: a fresh map from each function value to the vector of members
	 * answering it, in encounter order. Members accumulate reversed, then convert.
	 */
	private LispVal groupByForm(LispVal fn, LispVal seq) {
		LispSymbol fun = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol one = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal keyed = callFun(fn, fun, List.of(one));
		LispVal collect = list(sym("dolist"), list(List.of(one, seq)),
				list(sym("let"), list(List.of(list(key, keyed))), list(sym("setf"), list(sym("gethash"), key, table),
						list(sym("cons"), one, list(sym("gethash"), key, table, NIL_CONST)))));
		LispVal freeze = list(sym("maphash"), list(sym("lambda"), list(List.of(key, val)), list(sym("setf"),
				list(sym("gethash"), key, table), list(sym("coerce"), list(sym("nreverse"), val), quoted("vector")))),
				table);
		return list(sym("let*"), list(List.of(list(fun, fn), list(table, makeTable()))), collect, freeze, table);
	}

	/** {@code group-by} as a value: a two-argument lambda over the same pass. */
	private LispVal groupByValue() {
		LispSymbol fun = new LispSymbol(mangle("group-by-fn"));
		LispSymbol coll = new LispSymbol(mangle("group-by-coll"));
		return list(sym("lambda"), list(List.of(fun, coll)), groupByForm(fun, seqForm(coll)));
	}

	/** {@code sort}: the seq view copied and sorted, with an optional comparator. */
	private LispVal sortOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "sort takes a collection and an optional comparator");
		LispVal seq = seqForm(lower(items.get(n)));
		if (n == 1) {
			return sortForm(seq, null);
		}
		return sortForm(seq, fnValue(items.get(1)));
	}

	/**
	 * The sort over an already-lowered seq view: a copy (the primitive sorts
	 * destructively) under the default or wrapped comparator.
	 */
	private LispVal sortForm(LispVal seq, @Nullable LispVal cmp) {
		LispSymbol coll = freshTemp();
		LispVal pred;
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(coll, list(sym("copy-list"), seq)));
		if (cmp == null) {
			pred = defaultCmpFn();
		}
		else {
			LispSymbol fun = freshTemp();
			LispSymbol left = freshTemp();
			LispSymbol right = freshTemp();
			LispSymbol got = freshTemp();
			bindings.add(list(fun, cmp));
			LispVal truthy = list(sym("let"), list(List.of(list(got, callFun(cmp, fun, List.of(left, right))))),
					list(sym("if"), list(sym("or"), list(sym("null"), got), list(sym("eq"), got, this.falseVariable)),
							NIL_CONST, TRUE_CONST));
			pred = list(sym("lambda"), list(List.of(left, right)), truthy);
		}
		return list(sym("let*"), list(bindings), list(sym("sort"), coll, pred));
	}

	/**
	 * The default comparator: numbers with {@code <}, strings with {@code string<},
	 * characters with {@code char<}, keywords by spelling; anything else signals instead
	 * of answering wrongly.
	 */
	private LispVal defaultCmpFn() {
		LispSymbol left = new LispSymbol(mangle("sort-a"));
		LispSymbol right = new LispSymbol(mangle("sort-b"));
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("and"), list(sym("numberp"), left), list(sym("numberp"), right)),
				list(sym("<"), left, right)));
		branches.add(list(list(sym("and"), list(sym("stringp"), left), list(sym("stringp"), right)),
				list(sym("string<"), left, right)));
		branches.add(list(list(sym("and"), list(sym("characterp"), left), list(sym("characterp"), right)),
				list(sym("char<"), left, right)));
		branches.add(list(list(sym("and"), keywordTest(left), keywordTest(right)),
				list(sym("string<"), list(sym("cadr"), left), list(sym("cadr"), right))));
		branches
			.add(list(TRUE_CONST, list(sym("error"), LispString.literal("sort needs mutually comparable elements"))));
		return list(sym("lambda"), list(List.of(left, right)), cons(sym("cond"), branches));
	}

	/** Whether the bound value is a keyword wrapper. */
	private static LispVal keywordTest(LispVal value) {
		return list(sym("and"), list(sym("consp"), value), list(sym("eq"), list(sym("car"), value), KEYWORD_TAG));
	}

	/** {@code sort} as a value: a one- or two-argument lambda over the same sort. */
	private LispVal sortValue() {
		LispSymbol args = new LispSymbol(mangle("sort-args"));
		LispVal arity = list(sym("error"), LispString.literal("sort takes a collection and an optional comparator"));
		LispVal one = sortForm(seqForm(list(sym("car"), args)), null);
		LispVal two = sortForm(seqForm(list(sym("car"), list(sym("cdr"), args))), list(sym("car"), args));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), one),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), two), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/** {@code sort-by}: the seq view sorted by key, with an optional comparator. */
	private LispVal sortByOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 3, "sort-by takes a key function, a collection and an optional comparator");
		LispVal coll = seqForm(lower(items.get(n)));
		if (n == 2) {
			return sortByForm(fnValue(items.get(1)), coll, null);
		}
		return sortByForm(fnValue(items.get(1)), coll, fnValue(items.get(2)));
	}

	/**
	 * The key sort over already-lowered key function, seq view and optional comparator:
	 * the comparator (or the default) runs on the keyed values.
	 */
	private LispVal sortByForm(LispVal keyFn, LispVal seq, @Nullable LispVal cmp) {
		LispSymbol key = freshTemp();
		LispSymbol coll = freshTemp();
		LispSymbol left = freshTemp();
		LispSymbol right = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(key, keyFn));
		bindings.add(list(coll, list(sym("copy-list"), seq)));
		LispVal keyedLeft = callFun(keyFn, key, List.of(left));
		LispVal keyedRight = callFun(keyFn, key, List.of(right));
		LispVal predBody;
		if (cmp == null) {
			predBody = defaultCmpBody(keyedLeft, keyedRight);
		}
		else {
			LispSymbol fun = freshTemp();
			LispSymbol got = freshTemp();
			LispVal invoked = callFun(cmp, fun, List.of(keyedLeft, keyedRight));
			bindings.add(list(fun, cmp));
			predBody = list(sym("let"), list(List.of(list(got, invoked))),
					list(sym("if"), list(sym("or"), list(sym("null"), got), list(sym("eq"), got, this.falseVariable)),
							NIL_CONST, TRUE_CONST));
		}
		LispVal pred = list(sym("lambda"), list(List.of(left, right)), predBody);
		return list(sym("let*"), list(bindings), list(sym("sort"), coll, pred));
	}

	/** The default comparison over two already-lowered key forms. */
	private LispVal defaultCmpBody(LispVal left, LispVal right) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("and"), list(sym("numberp"), left), list(sym("numberp"), right)),
				list(sym("<"), left, right)));
		branches.add(list(list(sym("and"), list(sym("stringp"), left), list(sym("stringp"), right)),
				list(sym("string<"), left, right)));
		branches.add(list(list(sym("and"), list(sym("characterp"), left), list(sym("characterp"), right)),
				list(sym("char<"), left, right)));
		branches.add(list(list(sym("and"), keywordTest(left), keywordTest(right)),
				list(sym("string<"), list(sym("cadr"), left), list(sym("cadr"), right))));
		branches
			.add(list(TRUE_CONST, list(sym("error"), LispString.literal("sort needs mutually comparable elements"))));
		return cons(sym("cond"), branches);
	}

	/** {@code sort-by} as a value: key, then one or two more arguments. */
	private LispVal sortByValue() {
		LispSymbol key = new LispSymbol(mangle("sort-by-key"));
		LispSymbol args = new LispSymbol(mangle("sort-by-args"));
		LispVal arity = list(sym("error"),
				LispString.literal("sort-by takes a key function, a collection and an optional comparator"));
		LispVal one = sortByForm(key, seqForm(list(sym("car"), args)), null);
		LispVal two = sortByForm(key, seqForm(list(sym("car"), list(sym("cdr"), args))), list(sym("car"), args));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), one),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), two), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(List.of(key, AMPERSAND_REST, args)), body);
	}

	/** {@code last} as a value: the final member, or nil. */
	private LispVal lastValue() {
		LispSymbol coll = new LispSymbol(mangle("last-coll"));
		return list(sym("lambda"), list(coll), list(sym("car"), list(sym("last"), seqForm(coll))));
	}

	/** {@code butlast} as a value: everything but the final member. */
	private LispVal butlastValue() {
		LispSymbol coll = new LispSymbol(mangle("butlast-coll"));
		return list(sym("lambda"), list(coll), list(sym("butlast"), seqForm(coll)));
	}

	/** {@code second} as a value: the member past the head, or nil. */
	private LispVal secondValue() {
		LispSymbol coll = new LispSymbol(mangle("second-coll"));
		return list(sym("lambda"), list(coll), list(sym("cadr"), seqForm(coll)));
	}

	// b18 core convenience fns: strict vectors, head pairs, names, randomness

	/**
	 * {@code mapv} over an already-lowered function and collections (one or more): one
	 * call to the spliced {@code rontolisp::%clojure-mapv}, which realizes every input
	 * fully (lazy inputs answer strictly too) and coerces to a vector, like the oracle.
	 */
	private LispVal mapvOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 2, "mapv takes a function and collections");
		return mapvForm(fnValue(items.get(1)), lowers(items, 2));
	}

	/** {@code mapv} over an already-lowered function and collections. */
	private LispVal mapvForm(LispVal fun, List<LispVal> colls) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-MAPV"), fun, cons(sym("list"), colls));
	}

	/** {@code mapv} as a value: over a function and one rest list of collections. */
	private LispVal mapvValue() {
		LispSymbol fn = new LispSymbol(mangle("mapv-fn"));
		LispSymbol colls = new LispSymbol(mangle("mapv-colls"));
		LispVal arity = list(sym("error"), LispString.literal("mapv takes a function and collections"));
		LispVal call = list(new LispSymbol("RONTOLISP::%CLOJURE-MAPV"), fn, colls);
		LispVal body = list(sym("if"), list(sym("null"), colls), arity, call);
		return list(sym("lambda"), list(List.of(fn, AMPERSAND_REST, colls)), body);
	}

	/**
	 * {@code filterv} over an already-lowered predicate and collection: one call to the
	 * spliced {@code rontolisp::%clojure-filterv}, the strict vector arm of
	 * {@code filter}.
	 */
	private LispVal filtervForm(LispVal fun, LispVal coll) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-FILTERV"), fun, coll);
	}

	/** {@code filterv} as a value: the predicate over the collection. */
	private LispVal filtervValue() {
		LispSymbol pred = new LispSymbol(mangle("filterv-pred"));
		LispSymbol coll = new LispSymbol(mangle("filterv-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), filtervForm(pred, coll));
	}

	/**
	 * {@code mapcat} over an already-lowered function and collections (one or more): one
	 * call to the spliced {@code rontolisp::%clojure-mapcat}, the strict concat-of-maps
	 * over the seq views (nil-safe, like {@code concat}). A lone function is the oracle's
	 * transducer shape, which stays refused.
	 */
	private LispVal mapcatOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "mapcat takes a function and collections");
		if (n == 1) {
			throw new LispReadException("transducers are not supported yet: mapcat");
		}
		return mapcatForm(fnValue(items.get(1)), lowers(items, 2));
	}

	/** {@code mapcat} over an already-lowered function and collections. */
	private LispVal mapcatForm(LispVal fun, List<LispVal> colls) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-MAPCAT"), fun, cons(sym("list"), colls));
	}

	/** {@code mapcat} as a value: over a function and one rest list of collections. */
	private LispVal mapcatValue() {
		LispSymbol fn = new LispSymbol(mangle("mapcat-fn"));
		LispSymbol colls = new LispSymbol(mangle("mapcat-colls"));
		LispVal arity = list(sym("error"), LispString.literal("mapcat takes a function and collections"));
		LispVal call = list(new LispSymbol("RONTOLISP::%CLOJURE-MAPCAT"), fn, colls);
		LispVal body = list(sym("if"), list(sym("null"), colls), arity, call);
		return list(sym("lambda"), list(List.of(fn, AMPERSAND_REST, colls)), body);
	}

	/**
	 * {@code ffirst} over an already-lowered seq view: the head of the head, each level
	 * through the view (so a vector head seqs before its own head is read).
	 */
	private LispVal ffirstForm(LispVal seq) {
		return list(sym("car"), seqForm(list(sym("car"), seq)));
	}

	/** {@code ffirst} as a value: a one-argument lambda over the same heads. */
	private LispVal ffirstValue() {
		LispSymbol coll = new LispSymbol(mangle("ffirst-coll"));
		return list(sym("lambda"), list(coll), ffirstForm(seqForm(coll)));
	}

	/**
	 * {@code nfirst} over an already-lowered seq view: the tail of the head, each level
	 * through the view (of empty, nil -- the {@code next} shape, not {@code rest}).
	 */
	private LispVal nfirstForm(LispVal seq) {
		return list(sym("cdr"), seqForm(list(sym("car"), seq)));
	}

	/** {@code nfirst} as a value: a one-argument lambda over the same tail. */
	private LispVal nfirstValue() {
		LispSymbol coll = new LispSymbol(mangle("nfirst-coll"));
		return list(sym("lambda"), list(coll), nfirstForm(seqForm(coll)));
	}

	/**
	 * {@code boolean} over an already-lowered value: {@code T} for anything truthy (only
	 * nil and the false object are falsey), the false object otherwise.
	 */
	private LispVal booleanForm(LispVal lowered) {
		LispSymbol one = freshTemp();
		return list(sym("let"), list(List.of(list(one, lowered))), booleanAnswer(list(sym("not"), isFalsey(one))));
	}

	/** {@code boolean} as a value: a one-argument lambda over the same test. */
	private LispVal booleanValue() {
		LispSymbol one = new LispSymbol(mangle("boolean-one"));
		return list(sym("lambda"), list(one), booleanForm(one));
	}

	/**
	 * {@code char} over an already-lowered value: one call to the spliced
	 * {@code rontolisp::%clojure-char} (a character itself, a number through its
	 * truncated code point, anything else a signal).
	 */
	private LispVal charForm(LispVal lowered) {
		return list(new LispSymbol("RONTOLISP::%CLOJURE-CHAR"), lowered);
	}

	/** {@code char} as a value: a one-argument lambda over the same conversion. */
	private LispVal charValue() {
		LispSymbol one = new LispSymbol(mangle("char-one"));
		return list(sym("lambda"), list(one), charForm(one));
	}

	/**
	 * {@code keyword} over one or two arguments: the spliced
	 * {@code rontolisp::%clojure-keyword-1} (a keyword itself, a symbol's demangled
	 * spelling, a string verbatim, nil for anything else) or
	 * {@code rontolisp::%clojure-keyword-2} (the slash-joined spelling).
	 */
	private LispVal keywordOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "keyword takes a name, or a namespace and a name");
		if (n == 1) {
			return list(new LispSymbol("RONTOLISP::%CLOJURE-KEYWORD-1"), lower(items.get(1)));
		}
		return list(new LispSymbol("RONTOLISP::%CLOJURE-KEYWORD-2"), lower(items.get(1)), lower(items.get(2)));
	}

	/** {@code keyword} as a value: the one- and two-argument shapes over a rest list. */
	private LispVal keywordValue() {
		LispSymbol args = new LispSymbol(mangle("keyword-args"));
		LispVal one = list(new LispSymbol("RONTOLISP::%CLOJURE-KEYWORD-1"), list(sym("car"), args));
		LispVal two = list(new LispSymbol("RONTOLISP::%CLOJURE-KEYWORD-2"), list(sym("car"), args),
				list(sym("car"), list(sym("cdr"), args)));
		LispVal arity = list(sym("error"), LispString.literal("keyword takes a name, or a namespace and a name"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), one),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), two), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/**
	 * {@code symbol} over one or two arguments: the spliced
	 * {@code rontolisp::%clojure-symbol-1} (itself for a symbol, the spelled one for a
	 * keyword or a string, else a signal) or {@code rontolisp::%clojure-symbol-2} (a
	 * mangled symbol over the slash-joined spelling, so it prints and compares whole).
	 */
	private LispVal symbolOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "symbol takes a name, or a namespace and a name");
		if (n == 1) {
			return list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-1"), lower(items.get(1)));
		}
		return list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-2"), lower(items.get(1)), lower(items.get(2)));
	}

	/** {@code symbol} as a value: the one- and two-argument shapes over a rest list. */
	private LispVal symbolValue() {
		LispSymbol args = new LispSymbol(mangle("symbol-args"));
		LispVal one = list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-1"), list(sym("car"), args));
		LispVal two = list(new LispSymbol("RONTOLISP::%CLOJURE-SYMBOL-2"), list(sym("car"), args),
				list(sym("car"), list(sym("cdr"), args)));
		LispVal arity = list(sym("error"), LispString.literal("symbol takes a name, or a namespace and a name"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), one),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), two), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/** {@code name} as a value: a one-argument lambda over the spliced helper. */
	private LispVal nameValue() {
		LispSymbol one = new LispSymbol(mangle("name-one"));
		return list(sym("lambda"), list(one), list(new LispSymbol("RONTOLISP::%CLOJURE-NAME"), one));
	}

	/** {@code namespace} as a value: a one-argument lambda over the spliced helper. */
	private LispVal namespaceValue() {
		LispSymbol one = new LispSymbol(mangle("namespace-one"));
		return list(sym("lambda"), list(one), list(new LispSymbol("RONTOLISP::%CLOJURE-NAMESPACE"), one));
	}

	/**
	 * {@code assert} over a test and an optional message: nil when the test is truthy
	 * (nil and the false object are falsey), else a signal. The message evaluates only on
	 * failure (it sits in the else branch), like the oracle's lazy message form.
	 */
	private LispVal assertOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, "assert takes a test and an optional message");
		LispVal test = lower(items.get(1));
		LispVal failure;
		if (n == 2) {
			LispVal text = list(sym("concatenate"), quoted("string"), LispString.literal("Assert failed: "),
					strOf(lower(items.get(2)), LispString.literal(""), NIL_CONST));
			failure = list(sym("error"), text);
		}
		else {
			failure = list(sym("error"), LispString.literal("Assert failed"));
		}
		return ifFalsey(test, NIL_CONST, failure);
	}

	/**
	 * {@code rand} over zero or one arguments: the bare draw is {@code (random 1.0)} (a
	 * double in [0,1)); with a bound it scales one draw (never a domain check -- a
	 * negative bound answers a negative double, like the oracle's multiply).
	 */
	private LispVal randOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 0 || n == 1, "rand takes no bound, or one bound");
		LispVal draw = list(sym("random"), new LispDouble(1.0));
		if (n == 0) {
			return draw;
		}
		return list(sym("*"), lower(items.get(1)), draw);
	}

	/** {@code rand} as a value: the zero- and one-argument shapes over a rest list. */
	private LispVal randValue() {
		LispSymbol args = new LispSymbol(mangle("rand-args"));
		LispVal none = list(sym("random"), new LispDouble(1.0));
		LispVal one = list(sym("*"), list(sym("car"), args), list(sym("random"), new LispDouble(1.0)));
		LispVal arity = list(sym("error"), LispString.literal("rand takes no bound, or one bound"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), none),
				list(list(sym("null"), list(sym("cdr"), args)), one), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/**
	 * {@code rand-int} over an already-lowered bound: the truncation of one scaled draw
	 * (an int in [0,n) for a positive bound; 0 and negative bounds answer without a
	 * domain check, like the oracle's int-of-rand).
	 */
	private LispVal randIntForm(LispVal bound) {
		return list(sym("truncate"), list(sym("*"), bound, list(sym("random"), new LispDouble(1.0))));
	}

	/** {@code rand-int} as a value: a one-argument lambda over the same draw. */
	private LispVal randIntValue() {
		LispSymbol bound = new LispSymbol(mangle("rand-int-bound"));
		return list(sym("lambda"), list(bound), randIntForm(bound));
	}

	/**
	 * {@code rand-nth} over an already-lowered collection: the fully realized list
	 * indexed by one scaled draw. Nil answers nil (the empty list with it, both being nil
	 * -- the seq-view past-the-end rule our {@code nth} keeps); an empty vector, string
	 * or seq signals (like the oracle's throw); maps and sets signal too (none are
	 * indexed there).
	 */
	private LispVal randNthForm(LispVal lowered) {
		LispSymbol whole = freshTemp();
		LispSymbol realized = freshTemp();
		LispVal index = list(sym("truncate"),
				list(sym("*"), list(sym("length"), realized), list(sym("random"), new LispDouble(1.0))));
		LispVal hit = list(sym("nth"), index, realized);
		LispVal emptyErr = list(sym("error"), LispString.literal("rand-nth of an empty collection"));
		LispVal refusal = list(sym("error"), LispString.literal("rand-nth needs a vector, string, list or seq"));
		LispVal pick = list(sym("let"),
				list(List.of(list(realized, list(new LispSymbol("RONTOLISP::%CLOJURE-REALIZE-ALL"), whole)))),
				list(sym("if"), list(sym("null"), realized), emptyErr, hit));
		LispVal seqable = list(sym("or"), list(sym("stringp"), whole), list(sym("vectorp"), whole),
				list(sym("consp"), whole));
		LispVal check = cons(sym("cond"),
				List.of(list(list(sym("null"), whole), NIL_CONST),
						list(list(sym("or"), isSetForm(whole), list(sym("hash-table-p"), whole)), refusal),
						list(seqable, pick), list(TRUE_CONST, refusal)));
		return list(sym("let"), list(List.of(list(whole, lowered))), check);
	}

	/** {@code rand-nth} as a value: a one-argument lambda over the same draw. */
	private LispVal randNthValue() {
		LispSymbol coll = new LispSymbol(mangle("rand-nth-coll"));
		return list(sym("lambda"), list(coll), randNthForm(coll));
	}

	/**
	 * {@code shuffle} over an already-lowered collection: the realized members through
	 * the spliced Fisher-Yates, answering a fresh vector. Nil, strings and maps signal
	 * (none shuffle on the oracle either); sets shuffle through their member list.
	 */
	private LispVal shuffleForm(LispVal lowered) {
		LispSymbol whole = freshTemp();
		LispVal items = list(new LispSymbol("RONTOLISP::%CLOJURE-REALIZE-ALL"), whole);
		LispVal shuffled = list(new LispSymbol("RONTOLISP::%CLOJURE-SHUFFLE"), items);
		LispVal refusal = list(sym("error"), LispString.literal("shuffle needs a vector, list or set"));
		LispVal check = cons(sym("cond"),
				List.of(list(list(sym("null"), whole), refusal), list(list(sym("stringp"), whole), refusal),
						list(list(sym("hash-table-p"), whole), refusal), list(isRecordForm(whole), refusal),
						list(TRUE_CONST, shuffled)));
		return list(sym("let"), list(List.of(list(whole, lowered))), check);
	}

	/** {@code shuffle} as a value: a one-argument lambda over the same permutation. */
	private LispVal shuffleValue() {
		LispSymbol coll = new LispSymbol(mangle("shuffle-coll"));
		return list(sym("lambda"), list(coll), shuffleForm(coll));
	}

	// b15 map verbs: copy-on-write over fresh tables, like assoc/merge

	/**
	 * One association over already-lowered map, key and value: a fresh table over the old
	 * pairs plus the pair, so {@code assoc} onto nil builds from empty.
	 */
	private LispVal assocPairForm(LispVal map, LispVal key, LispVal val) {
		LispSymbol one = freshTemp();
		LispVal src = list(sym("if"), isRecordForm(one), typedTableOf(one), one);
		LispVal grown = cons(sym("append"),
				List.of(list(sym("if"), one, tablePlist(src), NIL_CONST), list(sym("list"), key, val)));
		return list(sym("let*"), list(List.of(list(one, map))), rewrapAnswer(one, tableFromPlist(grown)));
	}

	/** {@code update}: the key rewritten through the function and extra arguments. */
	private LispVal updateOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 3, "update takes a map, a key, a function and arguments");
		LispSymbol map = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol fun = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(map, lower(items.get(1))));
		bindings.add(list(key, lower(items.get(2))));
		bindings.add(list(fun, fnValue(items.get(3))));
		return list(sym("let*"), list(bindings), updateForm(map, key, fun, cons(sym("list"), lowers(items, 4))));
	}

	/**
	 * The update over already-lowered map, key, function and the extra-arguments tail
	 * list: {@code (apply f (cons current tail))} associated back, copy-on-write.
	 */
	private LispVal updateForm(LispVal map, LispVal key, LispVal fun, LispVal tail) {
		LispSymbol one = freshTemp();
		LispSymbol at = freshTemp();
		LispSymbol fn = freshTemp();
		LispVal next = callableApply(fn, list(sym("cons"), getForm(one, at, NIL_CONST), tail));
		LispVal src = list(sym("if"), isRecordForm(one), typedTableOf(one), one);
		LispVal grown = cons(sym("append"),
				List.of(list(sym("if"), one, tablePlist(src), NIL_CONST), list(sym("list"), at, next)));
		return list(sym("let*"), list(List.of(list(one, map), list(at, key), list(fn, fun))),
				rewrapAnswer(one, tableFromPlist(grown)));
	}

	/** {@code update} as a value: map, key, function and any extra arguments. */
	private LispVal updateValue() {
		LispSymbol map = new LispSymbol(mangle("update-map"));
		LispSymbol key = new LispSymbol(mangle("update-key"));
		LispSymbol fun = new LispSymbol(mangle("update-fn"));
		LispSymbol rest = new LispSymbol(mangle("update-rest"));
		return list(sym("lambda"), list(List.of(map, key, fun, AMPERSAND_REST, rest)), updateForm(map, key, fun, rest));
	}

	/** The key vector of {@code update-in}/{@code assoc-in}/{@code get-in}. */
	private static List<LispVal> keysVector(LispVal datum, String what) {
		List<LispVal> keyItems = items(datum);
		if (keyItems == null || keyItems.isEmpty() || keyItems.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(what + " takes a vector of keys, not " + datum.print());
		}
		return keyItems.subList(1, keyItems.size());
	}

	/** {@code update-in}: the nested update, recursing down the key vector. */
	private LispVal updateInOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 3, "update-in takes a map, keys, a function and arguments");
		List<LispVal> keyData = keysVector(items.get(2), "update-in");
		isTrue(!keyData.isEmpty(), "update-in takes a non-empty vector of keys");
		LispSymbol map = freshTemp();
		LispSymbol fun = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(map, lower(items.get(1))));
		bindings.add(list(fun, fnValue(items.get(3))));
		List<LispVal> keys = new ArrayList<>();
		for (LispVal keyDatum : keyData) {
			LispSymbol key = freshTemp();
			bindings.add(list(key, lower(keyDatum)));
			keys.add(key);
		}
		return list(sym("let*"), list(bindings), updateInForm(map, keys, fun, cons(sym("list"), lowers(items, 4))));
	}

	/**
	 * The nested update over already-lowered map, keys, function and extra-arguments
	 * tail: the leaf updates, outer levels re-associate through a one-argument lambda.
	 */
	private LispVal updateInForm(LispVal map, List<LispVal> keys, LispVal fun, LispVal tail) {
		if (keys.size() == 1) {
			return updateForm(map, keys.get(0), fun, tail);
		}
		LispSymbol inner = freshTemp();
		LispVal step = list(sym("lambda"), list(inner), updateInForm(inner, keys.subList(1, keys.size()), fun, tail));
		return updateForm(map, keys.get(0), step, NIL_CONST);
	}

	/** {@code update-in} as a value: the key sequence walked at run time. */
	private LispVal updateInValue() {
		String name = mangle("update-in-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(mangle("update-in-map"));
		LispSymbol keys = new LispSymbol(mangle("update-in-keys"));
		LispSymbol fun = new LispSymbol(mangle("update-in-fn"));
		LispSymbol rest = new LispSymbol(mangle("update-in-rest"));
		LispSymbol left = freshTemp();
		LispSymbol whole = freshTemp();
		LispSymbol inner = freshTemp();
		LispVal step = list(sym("lambda"), list(inner), list(self, list(sym("cdr"), left), inner));
		LispVal go = list(sym("if"), list(sym("null"), left),
				list(sym("error"), LispString.literal("update-in takes a non-empty vector of keys")),
				list(sym("if"), list(sym("null"), list(sym("cdr"), left)),
						updateForm(whole, list(sym("car"), left), fun, rest),
						updateForm(whole, list(sym("car"), left), step, NIL_CONST)));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(left, whole)), cons(go, List.of())));
		return list(sym("lambda"), list(List.of(map, keys, fun, AMPERSAND_REST, rest)),
				list(sym("labels"), list(List.of(binding)), list(self, seqForm(keys), map)));
	}

	/** {@code assoc-in}: the nested association, building missing levels. */
	private LispVal assocInOf(List<LispVal> items) {
		isTrue(items.size() == 4, "assoc-in takes a map, keys and a value");
		List<LispVal> keyData = keysVector(items.get(2), "assoc-in");
		LispSymbol map = freshTemp();
		LispSymbol val = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(map, lower(items.get(1))));
		bindings.add(list(val, lower(items.get(3))));
		if (keyData.isEmpty()) {
			// like the oracle: no keys associates under nil
			return list(sym("let*"), list(bindings), assocPairForm(map, NIL_CONST, val));
		}
		List<LispVal> keys = new ArrayList<>();
		for (LispVal keyDatum : keyData) {
			LispSymbol key = freshTemp();
			bindings.add(list(key, lower(keyDatum)));
			keys.add(key);
		}
		return list(sym("let*"), list(bindings), assocInForm(map, keys, val));
	}

	/**
	 * The nested association over already-lowered map, keys and value: the leaf
	 * associates, outer levels re-associate through a one-argument lambda.
	 */
	private LispVal assocInForm(LispVal map, List<LispVal> keys, LispVal val) {
		if (keys.size() == 1) {
			return assocPairForm(map, keys.get(0), val);
		}
		LispSymbol inner = freshTemp();
		LispVal step = list(sym("lambda"), list(inner), assocInForm(inner, keys.subList(1, keys.size()), val));
		return updateForm(map, keys.get(0), step, NIL_CONST);
	}

	/** {@code assoc-in} as a value: the key sequence walked at run time. */
	private LispVal assocInValue() {
		String name = mangle("assoc-in-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(mangle("assoc-in-map"));
		LispSymbol keys = new LispSymbol(mangle("assoc-in-keys"));
		LispSymbol val = new LispSymbol(mangle("assoc-in-val"));
		LispSymbol left = freshTemp();
		LispSymbol whole = freshTemp();
		LispSymbol inner = freshTemp();
		LispVal step = list(sym("lambda"), list(inner), list(self, list(sym("cdr"), left), inner));
		LispVal go = list(sym("if"), list(sym("null"), list(sym("cdr"), left)),
				assocPairForm(whole, list(sym("car"), left), val),
				updateForm(whole, list(sym("car"), left), step, NIL_CONST));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(left, whole)), cons(go, List.of())));
		return list(sym("lambda"), list(List.of(map, keys, val)),
				list(sym("labels"), list(List.of(binding)), list(self, seqForm(keys), map)));
	}

	/**
	 * {@code get-in}: the read folded down the key vector, the default threaded through
	 * every level, like the oracle.
	 */
	private LispVal getInOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 3, "get-in takes a map, keys and an optional default");
		List<LispVal> keyData = keysVector(items.get(2), "get-in");
		LispVal dflt = n == 3 ? lower(items.get(3)) : NIL_CONST;
		LispVal acc = lower(items.get(1));
		for (LispVal keyDatum : keyData) {
			acc = getForm(acc, lower(keyDatum), dflt);
		}
		return acc;
	}

	/** {@code get-in} as a value: the key sequence walked at run time. */
	private LispVal getInValue() {
		String name = mangle("get-in-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol map = new LispSymbol(mangle("get-in-map"));
		LispSymbol keys = new LispSymbol(mangle("get-in-keys"));
		LispSymbol rest = new LispSymbol(mangle("get-in-rest"));
		LispSymbol left = freshTemp();
		LispSymbol whole = freshTemp();
		LispSymbol dflt = freshTemp();
		LispVal go = list(sym("if"), list(sym("null"), left), whole,
				list(self, list(sym("cdr"), left), getForm(whole, list(sym("car"), left), dflt)));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(left, whole)), cons(go, List.of())));
		return list(sym("lambda"), list(List.of(map, keys, AMPERSAND_REST, rest)), list(sym("let*"),
				list(List.of(list(dflt, list(sym("if"), list(sym("null"), rest), NIL_CONST, list(sym("car"), rest))))),
				list(sym("labels"), list(List.of(binding)), list(self, seqForm(keys), map))));
	}

	/**
	 * {@code select-keys}: a fresh map holding the present keys only. Of nil, the empty
	 * map; anything else that is no map signals.
	 */
	private LispVal selectKeysForm(LispVal map, LispVal keys) {
		LispSymbol whole = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol out = freshTemp();
		LispSymbol one = freshTemp();
		LispSymbol got = freshTemp();
		LispVal keep = list(sym("setf"), list(sym("gethash"), one, out), got);
		LispSymbol src = freshTemp();
		LispVal gather = list(sym("dolist"), list(List.of(one, keys)),
				list(sym("let"), list(List.of(list(got, list(sym("gethash"), one, src, miss)))),
						list(sym("if"), list(sym("eq"), got, miss), NIL_CONST, keep)));
		// a record reads through its entry table and answers a plain map, like the
		// oracle; anything opaque signals, like the oracle
		LispVal norm = list(sym("if"), isRecordForm(whole), typedTableOf(whole), whole);
		return list(sym("let*"),
				list(List.of(list(whole, map), list(miss, list(sym("list"), NIL_CONST)), list(out, makeTable()))),
				list(sym("if"), list(sym("null"), whole), out,
						list(sym("let"), list(List.of(list(src, norm))),
								list(sym("if"), list(sym("hash-table-p"), src), list(sym("progn"), gather, out),
										list(sym("error"), LispString.literal("select-keys needs a map"))))));
	}

	/** {@code select-keys} as a value: a two-argument lambda over the same read. */
	private LispVal selectKeysValue() {
		LispSymbol map = new LispSymbol(mangle("select-keys-map"));
		LispSymbol keys = new LispSymbol(mangle("select-keys-keys"));
		return list(sym("lambda"), list(List.of(map, keys)), selectKeysForm(map, seqForm(keys)));
	}

	/** {@code merge-with}: every map merged, conflicts resolved through the function. */
	private LispVal mergeWithOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "merge-with takes a function and maps");
		if (n == 1) {
			return NIL_CONST;
		}
		if (n == 2) {
			return lower(items.get(2));
		}
		LispVal fun = fnValue(items.get(1));
		List<LispVal> maps = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			maps.add(lower(items.get(i)));
		}
		return mergeWithForm(fun, maps);
	}

	/**
	 * The merge over an already-lowered function and maps: a fresh table grown map by
	 * map, so inputs are never mutated and nil maps contribute nothing.
	 */
	private LispVal mergeWithForm(LispVal fun, List<LispVal> maps) {
		LispSymbol fn = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol miss = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(fn, fun));
		bindings.add(list(acc, makeTable()));
		bindings.add(list(miss, list(sym("list"), NIL_CONST)));
		List<LispVal> syms = new ArrayList<>();
		for (LispVal map : maps) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, map));
			syms.add(one);
		}
		List<LispVal> merges = new ArrayList<>();
		for (LispVal one : syms) {
			LispSymbol key = freshTemp();
			LispSymbol val = freshTemp();
			LispSymbol old = freshTemp();
			LispVal invoked = callFun(fun, fn, List.of(old, val));
			// a record contributes its entries, like a map; anything opaque signals
			// in the maphash, like the oracle
			LispVal src = list(sym("if"), isRecordForm(one), typedTableOf(one), one);
			LispVal join = list(sym("maphash"),
					list(sym("lambda"), list(List.of(key, val)),
							list(sym("let"), list(List.of(list(old, list(sym("gethash"), key, acc, miss)))),
									list(sym("setf"), list(sym("gethash"), key, acc),
											list(sym("if"), list(sym("eq"), old, miss), val, invoked)))),
					src);
			merges.add(list(sym("if"), one, join, NIL_CONST));
		}
		merges.add(list(sym("if"), cons(sym("or"), syms), rewrapAnswer(syms.get(0), acc), NIL_CONST));
		return list(sym("let*"), list(bindings), cons(sym("progn"), merges));
	}

	/**
	 * {@code merge-with} as a value: the function, then any number of maps, grown map by
	 * map through {@code f} like a call and rewrapped in the first rest map's record when
	 * there is one. Of no maps, {@code nil}.
	 */
	private LispVal mergeWithValue() {
		String name = mangle("merge-with-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fn = new LispSymbol(mangle("merge-with-fn"));
		LispSymbol maps = new LispSymbol(mangle("merge-with-maps"));
		LispSymbol left = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispSymbol old = freshTemp();
		LispSymbol found = freshTemp();
		LispSymbol probe = freshTemp();
		// a record contributes its entries, like a map; anything opaque signals
		// in the maphash, like the oracle (the mergeWithForm precedent)
		LispVal head = list(sym("car"), left);
		LispVal src = list(sym("if"), isRecordForm(head), typedTableOf(head), head);
		LispVal join = list(
				sym("maphash"), list(
						sym("lambda"), list(List.of(key,
								val)),
						list(sym("let"), list(List.of(list(old, list(sym("gethash"), key, acc, miss)))),
								list(sym("setf"), list(sym("gethash"), key, acc),
										list(sym("if"), list(sym("eq"), old, miss), val,
												callableApply(fn, cons(sym("list"), List.of(old, val))))))),
				src);
		LispVal answer = list(sym("if"), found, rewrapAnswer(list(sym("car"), maps), acc), NIL_CONST);
		LispVal go = list(sym("if"), list(sym("null"), left), answer, list(sym("progn"),
				list(sym("if"), list(sym("car"), left), join, NIL_CONST), list(self, list(sym("cdr"), left))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(left)), cons(go, List.of())));
		// the answer is nil unless some rest map is present, but the rewrap follows
		// the first rest map (a nil first map answers a plain map), like the oracle
		LispVal find = list(sym("dolist"), list(List.of(probe, maps)),
				list(sym("if"), list(sym("and"), list(sym("null"), found), probe), list(sym("setq"), found, probe)));
		return list(sym("lambda"), list(List.of(fn, AMPERSAND_REST, maps)), list(sym("let*"),
				list(List.of(list(acc, makeTable()), list(miss, list(sym("list"), NIL_CONST)), list(found, NIL_CONST))),
				find, list(sym("labels"), list(List.of(binding)), list(self, maps))));
	}

	/**
	 * {@code into}: the source conjoined onto the target, one member at a time. A
	 * three-argument call names a transducer, which stays refused.
	 */
	private LispVal intoOf(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 3) {
			throw new LispReadException("transducers are not supported yet: into");
		}
		isTrue(n == 2, "into takes a target and a source collection");
		return intoForm(lower(items.get(1)), seqForm(lower(items.get(2))));
	}

	/** The conj fold over an already-lowered target and seq view. */
	private LispVal intoForm(LispVal to, LispVal from) {
		LispSymbol acc = freshTemp();
		LispSymbol one = freshTemp();
		LispVal step = list(sym("lambda"), list(List.of(acc, one)), conjTwoForm(acc, one));
		return list(sym("reduce"), step, from, sym(":initial-value"), to);
	}

	/** {@code into} as a value: a two-argument lambda over the same fold. */
	private LispVal intoValue() {
		LispSymbol to = new LispSymbol(mangle("into-to"));
		LispSymbol from = new LispSymbol(mangle("into-from"));
		return list(sym("lambda"), list(List.of(to, from)), intoForm(to, seqForm(from)));
	}

	/**
	 * {@code frequencies}: the member counts in one pass over the seq view, as a fresh
	 * map.
	 */
	private LispVal frequenciesForm(LispVal seq) {
		LispSymbol table = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol one = freshTemp();
		LispSymbol old = freshTemp();
		LispVal bump = list(sym("setf"), list(sym("gethash"), one, table), list(sym("if"), list(sym("eq"), old, miss),
				new LispInteger(1), list(sym("+"), old, new LispInteger(1))));
		LispVal step = list(sym("dolist"), list(List.of(one, seq)),
				list(sym("let"), list(List.of(list(old, list(sym("gethash"), one, table, miss)))), bump));
		return list(sym("let*"), list(List.of(list(table, makeTable()), list(miss, list(sym("list"), NIL_CONST)))),
				step, table);
	}

	/** {@code frequencies} as a value: a one-argument lambda over the same pass. */
	private LispVal frequenciesValue() {
		LispSymbol coll = new LispSymbol(mangle("frequencies-coll"));
		return list(sym("lambda"), list(coll), frequenciesForm(seqForm(coll)));
	}

	// b15 higher-order functions: closures, no new runtime

	/** {@code comp}: right-nested application, no functions the identity. */
	private LispVal compOf(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return identityValue();
		}
		List<LispVal> fns = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			fns.add(fnValue(items.get(i)));
		}
		if (n == 1) {
			return fns.get(0);
		}
		return compForm(fns);
	}

	/**
	 * The composition over already-lowered functions: the rightmost spreads the
	 * arguments, each outer wraps one result.
	 */
	private LispVal compForm(List<LispVal> fns) {
		LispSymbol args = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> names = new ArrayList<>();
		for (LispVal fn : fns) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, fn));
			names.add(one);
		}
		LispVal inner = callableApply(names.get(names.size() - 1), args);
		for (int i = names.size() - 2; i >= 0; i--) {
			inner = callFun(fns.get(i), names.get(i), List.of(inner));
		}
		return list(sym("let*"), list(bindings), list(sym("lambda"), list(AMPERSAND_REST, args), inner));
	}

	/**
	 * {@code comp} as a value: the function list composed at run time, so
	 * {@code (apply comp fns)} runs.
	 */
	private LispVal compValue() {
		String name = mangle("comp-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fns = freshTemp();
		LispSymbol fun = freshTemp();
		LispSymbol next = freshTemp();
		LispSymbol args = freshTemp();
		LispSymbol one = new LispSymbol(mangle("comp-one"));
		LispSymbol more = new LispSymbol(mangle("comp-more"));
		LispVal identity = list(sym("lambda"), list(List.of(one, AMPERSAND_REST, more)),
				list(sym("declare"), list(sym("ignore"), more)), one);
		LispVal chain = list(sym("lambda"), list(List.of(AMPERSAND_REST, args)),
				callableApply(fun, list(List.of(callableApply(next, args)))));
		LispVal step = list(sym("if"), list(sym("null"), fns), identity,
				list(sym("if"), list(sym("null"), list(sym("cdr"), fns)), list(sym("car"), fns), list(sym("let*"),
						list(List.of(list(fun, list(sym("car"), fns)), list(next, list(self, list(sym("cdr"), fns))))),
						chain)));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(fns)), cons(step, List.of())));
		LispSymbol outer = new LispSymbol(mangle("comp-fns"));
		return list(sym("lambda"), list(AMPERSAND_REST, outer),
				list(sym("labels"), list(List.of(binding)), list(self, outer)));
	}

	/**
	 * {@code partial}: the function over the fixed arguments plus whatever arrives. The
	 * fixed arguments run once, behind temporaries.
	 */
	private LispVal partialForm(LispVal fun, List<LispVal> fixed) {
		LispSymbol fn = freshTemp();
		LispSymbol more = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(fn, fun));
		List<LispVal> names = new ArrayList<>();
		for (LispVal arg : fixed) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, arg));
			names.add(one);
		}
		LispVal tail = names.isEmpty() ? more : list(sym("append"), cons(sym("list"), names), more);
		return list(sym("let*"), list(bindings),
				list(sym("lambda"), list(AMPERSAND_REST, more), callableApply(fn, tail)));
	}

	/** {@code partial} as a value: the function, then the fixed arguments. */
	private LispVal partialValue() {
		LispSymbol fn = new LispSymbol(mangle("partial-fn"));
		LispSymbol fixed = new LispSymbol(mangle("partial-fixed"));
		LispSymbol more = new LispSymbol(mangle("partial-more"));
		LispVal inner = list(sym("lambda"), list(AMPERSAND_REST, more),
				callableApply(fn, list(sym("append"), fixed, more)));
		return list(sym("lambda"), list(List.of(fn, AMPERSAND_REST, fixed)), inner);
	}

	/**
	 * {@code complement}: the predicate negated, answering {@code T}-or-false, like every
	 * boolean-answering builtin.
	 */
	private LispVal complementForm(LispVal fun) {
		LispSymbol fn = freshTemp();
		LispSymbol args = freshTemp();
		LispSymbol got = freshTemp();
		LispVal neg = list(sym("let"), list(List.of(list(got, callableApply(fn, args)))),
				list(sym("if"), list(sym("or"), list(sym("null"), got), list(sym("eq"), got, this.falseVariable)),
						TRUE_CONST, this.falseVariable));
		return list(sym("let*"), list(List.of(list(fn, fun))), list(sym("lambda"), list(AMPERSAND_REST, args), neg));
	}

	/** {@code complement} as a value: a one-argument lambda over the same negation. */
	private LispVal complementValue() {
		LispSymbol fun = new LispSymbol(mangle("complement-fn"));
		return list(sym("lambda"), list(fun), complementForm(fun));
	}

	/**
	 * {@code constantly}: the value answered whatever the arguments -- evaluated once,
	 * behind a temporary.
	 */
	private LispVal constantlyForm(LispVal val) {
		LispSymbol kept = freshTemp();
		LispSymbol rest = freshTemp();
		return list(sym("let*"), list(List.of(list(kept, val))),
				list(sym("lambda"), list(AMPERSAND_REST, rest), list(sym("declare"), list(sym("ignore"), rest)), kept));
	}

	/** {@code constantly} as a value: a one-argument lambda over the same closure. */
	private LispVal constantlyValue() {
		LispSymbol val = new LispSymbol(mangle("constantly-val"));
		return list(sym("lambda"), list(val), constantlyForm(val));
	}

	/** {@code identity} as a value: the one-argument lambda. */
	private LispVal identityValue() {
		LispSymbol val = new LispSymbol(mangle("identity-val"));
		return list(sym("lambda"), list(val), val);
	}

	/**
	 * {@code memoize}: the function cached behind an {@code equal} table, so repeated
	 * arguments run once. The table lives in the closure -- the atom cell's shape,
	 * without the tag -- and the argument list keys structurally.
	 */
	private LispVal memoizeForm(LispVal fun) {
		LispSymbol table = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol fn = freshTemp();
		LispSymbol args = freshTemp();
		LispSymbol hit = freshTemp();
		LispSymbol val = freshTemp();
		LispVal inner = list(sym("lambda"), list(AMPERSAND_REST, args),
				list(sym("let"), list(List.of(list(hit, list(sym("gethash"), args, table, miss)))),
						list(sym("if"), list(sym("eq"), hit, miss),
								list(sym("let"), list(List.of(list(val, callableApply(fn, args)))),
										list(sym("setf"), list(sym("gethash"), args, table), val), val),
								hit)));
		return list(sym("let*"),
				list(List.of(list(table, makeTable()), list(miss, list(sym("list"), NIL_CONST)), list(fn, fun))),
				inner);
	}

	/** {@code memoize} as a value: a one-argument lambda over the same cache. */
	private LispVal memoizeValue() {
		LispSymbol fun = new LispSymbol(mangle("memoize-fn"));
		return list(sym("lambda"), list(fun), memoizeForm(fun));
	}

	/**
	 * {@code trampoline}: the function applied, then every thunk result invoked with no
	 * arguments until a non-function answers. A labels self call, so mutual thunk chains
	 * stay constant-stack on the interpreter.
	 */
	private LispVal trampolineForm(LispVal fun, LispVal argList) {
		String name = mangle("trampoline-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol fn = freshTemp();
		LispSymbol got = freshTemp();
		LispVal step = list(sym("if"), list(sym("functionp"), got), list(self, list(sym("funcall"), got)), got);
		LispVal binding = new LispCons(self, new LispCons(list(List.of(got)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(fn, fun))),
				list(sym("labels"), list(List.of(binding)), list(self, callableApply(fn, argList))));
	}

	/** {@code trampoline} as a value: the function, then any arguments. */
	private LispVal trampolineValue() {
		LispSymbol fun = new LispSymbol(mangle("trampoline-fn"));
		LispSymbol rest = new LispSymbol(mangle("trampoline-rest"));
		return list(sym("lambda"), list(List.of(fun, AMPERSAND_REST, rest)), trampolineForm(fun, rest));
	}

	// b15 predicates and casts

	/**
	 * Whether the lowered value is a collection: a list, vector, map or set. Nil and the
	 * false object are no collections, like the oracle -- and neither are strings, even
	 * though the runtime stores them as vectors (like {@code vector?} sees).
	 */
	private LispVal collRaw(LispVal lowered) {
		LispSymbol one = freshTemp();
		LispVal test = list(sym("and"), list(sym("not"), list(sym("stringp"), one)), list(sym("not"), isRegexForm(one)),
				list(sym("or"), list(sym("consp"), one), list(sym("vectorp"), one), list(sym("hash-table-p"), one),
						isSetForm(one)));
		return list(sym("let*"), list(List.of(list(one, lowered))), test);
	}

	/** {@code coll?} as a value: a one-argument lambda answering {@code T}-or-false. */
	private LispVal collValue() {
		LispSymbol one = new LispSymbol(mangle("coll-one"));
		return list(sym("lambda"), list(one), booleanAnswer(collRaw(one)));
	}

	/** {@code string?} as a value: a one-argument lambda answering {@code T}-or-false. */
	private LispVal stringPredValue() {
		LispSymbol one = new LispSymbol(mangle("string-one"));
		return list(sym("lambda"), list(one), booleanAnswer(list(sym("stringp"), one)));
	}

	/**
	 * Whether the lowered value is a symbol: true for identifiers, false for the
	 * booleans, nil, keywords (which are lists) and everything else.
	 */
	private LispVal symbolRaw(LispVal lowered) {
		LispSymbol one = freshTemp();
		LispVal test = list(sym("and"), list(sym("symbolp"), one), list(sym("not"), list(sym("null"), one)),
				list(sym("not"), list(sym("eq"), one, TRUE_CONST)),
				list(sym("not"), list(sym("eq"), one, this.falseVariable)));
		return list(sym("let*"), list(List.of(list(one, lowered))), test);
	}

	/** {@code symbol?} as a value: a one-argument lambda answering {@code T}-or-false. */
	private LispVal symbolPredValue() {
		LispSymbol one = new LispSymbol(mangle("symbol-one"));
		return list(sym("lambda"), list(one), booleanAnswer(symbolRaw(one)));
	}

	/**
	 * {@code instance?}: the class name mapped onto the predicate the backends share.
	 * Only the core classes lower -- a host width we do not have (or any other class) is
	 * a named refusal instead of a wrong answer.
	 */
	private LispVal instanceOf(List<LispVal> items) {
		isTrue(items.size() == 3, "instance? takes a class and a value");
		if (!(items.get(1) instanceof LispSymbol cls)) {
			throw new LispReadException("instance? takes a class name, not " + items.get(1).print());
		}
		String name = cls.name();
		String simple = name.lastIndexOf('.') >= 0 ? name.substring(name.lastIndexOf('.') + 1) : name;
		LispVal lowered = lower(items.get(2));
		TypeDef known = this.types.get(name);
		if (known == null && name.indexOf('.') < 0) {
			known = this.types.get(simple);
		}
		if (known != null) {
			// a record or deftype name tests the dispatch tag, like a class
			this.usedProtocols = true;
			return booleanAnswer(
					list(sym("equal"), list(new LispSymbol(PROTOCOL_TAG), lowered), typeTagForm(known.tagSpelling())));
		}
		LispVal raw = switch (simple) {
			case "String", "CharSequence" -> list(sym("stringp"), lowered);
			case "Character" -> list(sym("characterp"), lowered);
			case "Boolean" ->
				list(sym("or"), list(sym("eq"), lowered, TRUE_CONST), list(sym("eq"), lowered, this.falseVariable));
			case "Number" -> list(sym("numberp"), lowered);
			case "Long" -> list(sym("integerp"), lowered);
			case "Double" -> list(sym("floatp"), lowered);
			case "Object" -> list(sym("not"), list(sym("null"), lowered));
			case "Keyword" -> keywordTest(lowered);
			case "Symbol" -> symbolRaw(lowered);
			default -> throw new LispReadException("instance? needs a core class, not " + name);
		};
		return booleanAnswer(raw);
	}

	/**
	 * {@code class}: the value's kind as a keyword. The oracle answers host classes,
	 * which no wasm backend has -- the keyword names the kind instead, on every backend
	 * alike. Inside a {@code defmulti} dispatch function (see
	 * {@link #defmultiForms(List)}) a nil answers nil itself instead, so the dispatcher's
	 * null test maps it onto the nil method's marker while an explicit {@code :nil}
	 * keyword keeps its row, like the oracle.
	 */
	private LispVal classForm(LispVal lowered) {
		LispSymbol one = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		// a record or deftype answers its tag keyword (the oracle answers a host
		// class, which no wasm backend has); a reify answers a constant keyword
		branches.add(list(isRecordForm(one), typedTagOf(one)));
		branches.add(list(isDeftypeForm(one), typedTagOf(one)));
		branches.add(list(isReifyForm(one), keywordForm("reify")));
		branches.add(list(list(sym("null"), one), this.inDispatchFn ? NIL_CONST : keywordForm("nil")));
		branches.add(list(list(sym("eq"), one, this.falseVariable), keywordForm("boolean")));
		branches.add(list(list(sym("eq"), one, TRUE_CONST), keywordForm("boolean")));
		branches.add(list(keywordTest(one), keywordForm("keyword")));
		branches.add(list(list(sym("symbolp"), one), keywordForm("symbol")));
		branches.add(list(list(sym("characterp"), one), keywordForm("char")));
		branches.add(list(list(sym("stringp"), one), keywordForm("string")));
		branches.add(list(list(sym("numberp"), one), keywordForm("number")));
		branches.add(list(isSetForm(one), keywordForm("set")));
		branches.add(list(list(sym("hash-table-p"), one), keywordForm("map")));
		branches.add(list(list(sym("vectorp"), one), keywordForm("vector")));
		branches.add(list(isPatternForm(one), keywordForm("pattern")));
		branches.add(list(isMatcherForm(one), keywordForm("matcher")));
		branches.add(list(list(sym("consp"), one), keywordForm("list")));
		branches.add(list(list(sym("functionp"), one), keywordForm("function")));
		branches.add(list(isAtomForm(one), keywordForm("atom")));
		branches.add(list(TRUE_CONST, list(sym("error"), LispString.literal("class needs a value of a known kind"))));
		return list(sym("let*"), list(List.of(list(one, lowered))), cons(sym("cond"), branches));
	}

	/** {@code class} as a value: a one-argument lambda over the same read. */
	private LispVal classValue() {
		LispSymbol one = new LispSymbol(mangle("class-one"));
		return list(sym("lambda"), list(one), classForm(one));
	}

	/**
	 * {@code int}/{@code long} over an already-lowered value: a character reads back
	 * through {@code char-code} (round-tripping {@code char}), anything else truncates,
	 * like the oracle.
	 */
	private LispVal intForm(LispVal lowered) {
		LispSymbol one = freshTemp();
		return list(sym("let"), list(List.of(list(one, lowered))),
				list(sym("if"), list(sym("characterp"), one), list(sym("char-code"), one), list(sym("truncate"), one)));
	}

	/** {@code int}/{@code long} as a value: truncation, like the call. */
	private LispVal intValue() {
		LispSymbol one = new LispSymbol(mangle("int-one"));
		return list(sym("lambda"), list(one), intForm(one));
	}

	/** {@code unchecked-add} as a value: addition without the overflow check. */
	private LispVal uncheckedAddValue() {
		LispSymbol first = new LispSymbol(mangle("unchecked-a"));
		LispSymbol second = new LispSymbol(mangle("unchecked-b"));
		return list(sym("lambda"), list(List.of(first, second)), list(sym("+"), first, second));
	}

	// b15 IO entry points: spit/slurp/line-seq over the eval IO layer (b22 adds the
	// clojure.java.io/reader constructor and the line-seq reader arity)

	/**
	 * {@code spit}: the string written to the path, answering nil. With an
	 * {@code :append} flag the writes append, else the file is superseded.
	 */
	private LispVal spitOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 4, "spit takes a path, a string and an optional :append flag");
		LispVal exists;
		if (n == 4) {
			if (!isSymbolNamed(items.get(3), ":append")) {
				throw new LispReadException("spit takes a path, a string and an optional :append flag");
			}
			exists = ifFalsey(lower(items.get(4)), sym(":append"), sym(":supersede"));
		}
		else {
			exists = sym(":supersede");
		}
		return spitForm(lower(items.get(1)), lower(items.get(2)), exists);
	}

	/** The write over already-lowered path, content and {@code :if-exists} mode. */
	private LispVal spitForm(LispVal path, LispVal content, LispVal exists) {
		LispSymbol file = freshTemp();
		LispSymbol text = freshTemp();
		LispSymbol stream = freshTemp();
		return list(sym("let*"), list(List.of(list(file, path), list(text, content))),
				list(sym("with-open-file"),
						list(List.of(stream, file, sym(":direction"), sym(":output"), sym(":if-exists"), exists)),
						list(sym("write-string"), text, stream), NIL_CONST));
	}

	/** {@code spit} as a value: path, content and an optional append flag. */
	private LispVal spitValue() {
		LispSymbol path = new LispSymbol(mangle("spit-path"));
		LispSymbol content = new LispSymbol(mangle("spit-content"));
		LispSymbol rest = new LispSymbol(mangle("spit-rest"));
		LispVal exists = list(sym("if"), list(sym("null"), rest), sym(":supersede"),
				list(sym("if"), list(sym("car"), rest), sym(":append"), sym(":supersede")));
		return list(sym("lambda"), list(List.of(path, content, AMPERSAND_REST, rest)), spitForm(path, content, exists));
	}

	/**
	 * {@code slurp}: the whole file as a string, read character by character into a
	 * string stream.
	 */
	private LispVal slurpForm(LispVal path) {
		String name = mangle("slurp-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol file = freshTemp();
		LispSymbol stream = freshTemp();
		LispSymbol out = freshTemp();
		LispSymbol got = freshTemp();
		LispVal step = list(sym("let"), list(List.of(list(got, list(sym("read-char"), stream, NIL_CONST, NIL_CONST)))),
				list(sym("if"), list(sym("null"), got), list(sym("get-output-stream-string"), out),
						list(sym("progn"), list(sym("write-char"), got, out), list(self))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of()), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(file, path))),
				list(sym("with-open-file"), list(List.of(stream, file)),
						list(sym("let*"), list(List.of(list(out, list(sym("make-string-output-stream"))))),
								list(sym("labels"), list(List.of(binding)), list(self)))));
	}

	/** {@code slurp} as a value: a one-argument lambda over the same read. */
	private LispVal slurpValue() {
		LispSymbol path = new LispSymbol(mangle("slurp-path"));
		return list(sym("lambda"), list(path), slurpForm(path));
	}

	/**
	 * {@code line-seq}: the lines as a strict list -- of a path, opened and closed around
	 * the read (the documented path deviation: the oracle takes a reader and answers
	 * lazily), or of an already-open reader, which is read but never closed
	 * ({@code with-open} owns closing, like the oracle). A stream value takes the reader
	 * loop, anything else the path form.
	 */
	private LispVal lineSeqForm(LispVal target) {
		LispSymbol src = freshTemp();
		return list(sym("let"), list(List.of(list(src, target))),
				list(sym("if"), list(sym("streamp"), src), lineSeqReaderLoop(src), lineSeqPathForm(src)));
	}

	/**
	 * The {@code read-line} loop over an already-bound stream: no open, no close, so a
	 * {@code with-open} body may consume its reader and the cleanup still closes exactly
	 * once.
	 */
	private LispVal lineSeqReaderLoop(LispVal stream) {
		String name = mangle("line-seq-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol acc = freshTemp();
		LispSymbol got = freshTemp();
		LispVal step = list(sym("let"), list(List.of(list(got, list(sym("read-line"), stream, NIL_CONST, NIL_CONST)))),
				list(sym("if"), list(sym("null"), got), list(sym("reverse"), acc),
						list(self, list(sym("cons"), got, acc))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(acc)), cons(step, List.of())));
		return list(sym("labels"), list(List.of(binding)), list(self, NIL_CONST));
	}

	/** The path form: the same loop opened and closed around the file. */
	private LispVal lineSeqPathForm(LispVal path) {
		String name = mangle("line-seq-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol file = freshTemp();
		LispSymbol stream = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol got = freshTemp();
		LispVal step = list(sym("let"), list(List.of(list(got, list(sym("read-line"), stream, NIL_CONST, NIL_CONST)))),
				list(sym("if"), list(sym("null"), got), list(sym("reverse"), acc),
						list(self, list(sym("cons"), got, acc))));
		LispVal binding = new LispCons(self, new LispCons(list(List.of(acc)), cons(step, List.of())));
		return list(sym("let*"), list(List.of(list(file, path))), list(sym("with-open-file"),
				list(List.of(stream, file)), list(sym("labels"), list(List.of(binding)), list(self, NIL_CONST))));
	}

	/** {@code line-seq} as a value: a one-argument lambda over the same read. */
	private LispVal lineSeqValue() {
		LispSymbol path = new LispSymbol(mangle("line-seq-path"));
		return list(sym("lambda"), list(path), lineSeqForm(path));
	}

	/**
	 * {@code format}: the Java-format string translated to Common Lisp directives over
	 * Clojure-notation arguments. The format string must be literal (its directives
	 * translate at lowering time); {@code %s} converts through {@code str} semantics (nil
	 * spells {@code "null"}, like {@code String/valueOf}), {@code %b} through the boolean
	 * spelling, numbers through the matching numeric directive. Precision only rides
	 * {@code %f}/{@code %e}/{@code %g}; any flag, date or hash directive is a named
	 * refusal instead of a wrong answer.
	 */
	private LispVal formatOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "format takes a format string and arguments");
		if (!(items.get(1) instanceof LispString fmt)) {
			throw new LispReadException("format takes a literal format string, not " + items.get(1).print());
		}
		String pattern = fmt.value();
		StringBuilder cl = new StringBuilder();
		List<LispVal> args = new ArrayList<>();
		int next = 2;
		int i = 0;
		while (i < pattern.length()) {
			char c = pattern.charAt(i);
			if (c != '%') {
				cl.append(c == '~' ? "~~" : c);
				i++;
				continue;
			}
			i++;
			isTrue(i < pattern.length(), "a format string cannot end in %");
			char d = pattern.charAt(i);
			if (d == '%') {
				cl.append('%');
				i++;
				continue;
			}
			if (d == 'n') {
				cl.append("~%");
				i++;
				continue;
			}
			if ("-0#,+ (".indexOf(d) >= 0) {
				throw new LispReadException("format flag %" + d + " is not supported yet");
			}
			StringBuilder width = new StringBuilder();
			while (i < pattern.length() && Character.isDigit(pattern.charAt(i))) {
				width.append(pattern.charAt(i++));
			}
			StringBuilder precision = new StringBuilder();
			if (i < pattern.length() && pattern.charAt(i) == '.') {
				i++;
				while (i < pattern.length() && Character.isDigit(pattern.charAt(i))) {
					precision.append(pattern.charAt(i++));
				}
			}
			isTrue(i < pattern.length(), "a format directive cannot end the string");
			char conv = pattern.charAt(i++);
			isTrue(next < items.size(), "format takes an argument per directive");
			LispVal arg = lower(items.get(next++));
			switch (conv) {
				case 's' -> {
					isTrue(precision.length() == 0, "format precision is not supported yet for %s");
					cl.append('~').append(width).append(width.length() == 0 ? "A" : "@A");
					args.add(strOf(arg, LispString.literal("null"), NIL_CONST));
				}
				case 'd' -> {
					isTrue(precision.length() == 0, "format precision is not supported yet for %d");
					cl.append('~').append(width).append('D');
					args.add(checkedArg(arg, "integerp", "%d takes an integer"));
				}
				case 'x', 'X' -> {
					isTrue(precision.length() == 0, "format precision is not supported yet for %x");
					// Common Lisp spells hexadecimal uppercase, like %X: a lowercase
					// %x downcases the converted string first.
					LispVal hex = list(sym("format"), NIL_CONST, LispString.literal("~X"),
							checkedArg(arg, "integerp", "%x takes an integer"));
					if (conv == 'x') {
						hex = list(sym("string-downcase"), hex);
					}
					cl.append('~').append(width).append(width.length() == 0 ? "A" : "@A");
					args.add(hex);
				}
				case 'o' -> {
					isTrue(precision.length() == 0, "format precision is not supported yet for %o");
					cl.append('~').append(width).append('O');
					args.add(checkedArg(arg, "integerp", "%o takes an integer"));
				}
				case 'c' -> {
					isTrue(precision.length() == 0, "format precision is not supported yet for %c");
					// ~C prints readably, so characters convert through string first.
					LispVal text = list(sym("string"), checkedArg(arg, "characterp", "%c takes a character"));
					cl.append('~').append(width).append(width.length() == 0 ? "A" : "@A");
					args.add(text);
				}
				case 'b' -> {
					isTrue(precision.length() == 0, "format precision is not supported yet for %b");
					cl.append('~').append(width).append(width.length() == 0 ? "A" : "@A");
					LispSymbol test = freshTemp();
					args.add(list(sym("let*"), list(List.of(list(test, arg))),
							list(sym("if"),
									list(sym("or"), list(sym("null"), test), list(sym("eq"), test, this.falseVariable)),
									LispString.literal("false"), LispString.literal("true"))));
				}
				case 'f' -> {
					cl.append('~').append(width);
					if (precision.length() > 0) {
						cl.append(',').append(precision);
					}
					cl.append('F');
					args.add(checkedArg(arg, "floatp", "%f takes a float"));
				}
				default -> throw new LispReadException("format directive %" + conv + " is not supported yet");
			}
		}
		isTrue(next == items.size(), "format takes an argument per directive");
		List<LispVal> call = new ArrayList<>();
		call.add(sym("format"));
		call.add(NIL_CONST);
		call.add(LispString.literal(cl.toString()));
		call.addAll(args);
		return list(call);
	}

	/**
	 * A format argument checked against the directive's type, like the oracle's
	 * conversion check: anything else signals instead of printing wrongly.
	 */
	private LispVal checkedArg(LispVal arg, String predicate, String message) {
		LispSymbol one = freshTemp();
		return list(sym("let*"), list(List.of(list(one, arg))),
				list(sym("if"), list(sym(predicate), one), one, list(sym("error"), LispString.literal(message))));
	}

	// namespaces: ns clauses, require/use/import as alias wiring

	/** A namespace-qualified var: the namespace and the var it names. */
	private record VarRef(String ns, String var) {
	}

	/**
	 * The namespaces whose vars lower to core forms: {@code clojure.string} and
	 * {@code clojure.java.io}.
	 */
	private static boolean isKnownNamespace(String ns) {
		return ns.equals("clojure.string") || ns.equals("clojure.java.io");
	}

	/** Whether the namespace exports the var as a lowering. */
	private static boolean isKnownVar(String ns, String var) {
		return ns.equals("clojure.string") && STRING_VARS.contains(var)
				|| ns.equals("clojure.java.io") && JIO_VARS.contains(var);
	}

	/**
	 * The vars a namespace refers in full: per namespace, so {@code :refer :all} stays
	 * exact.
	 */
	private static Set<String> varsOf(String ns) {
		if (ns.equals("clojure.java.io")) {
			return JIO_VARS;
		}
		return STRING_VARS;
	}

	/**
	 * The {@code clojure.java.io} vars this front end implements: exactly {@code reader}.
	 */
	private static final Set<String> JIO_VARS = Set.of("reader");

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
		this.currentNs = ((LispSymbol) items.get(1)).name();
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
	 * {@code (in-ns 'name)}: switches the session's {@code *ns*} for the
	 * {@code ::}-keywords below it, answering nil. The namespace stays flat -- every
	 * definition is still global -- so only the auto-resolve moves. A quoted symbol, a
	 * bare symbol or a string names it directly (never evaluated); anything else leaves
	 * it alone.
	 */
	private LispVal inNsOf(List<LispVal> items) {
		isTrue(items.size() == 2, "in-ns takes a namespace");
		LispVal arg = items.get(1);
		if (arg instanceof LispSymbol sym) {
			this.currentNs = sym.name();
		}
		else if (arg instanceof LispString text) {
			this.currentNs = text.value();
		}
		else {
			List<LispVal> quoted = items(arg);
			if (quoted != null && quoted.size() == 2 && isSymbolNamed(quoted.get(0), "quote")
					&& quoted.get(1) instanceof LispSymbol sym) {
				this.currentNs = sym.name();
			}
		}
		return NIL_CONST; // the namespace is flat: every definition is global
	}

	/**
	 * The libspecs of a {@code :require} (or {@code :use}, which refers everything by
	 * default): {@code [ns :as alias :refer [vars]/:all]} vectors, prefix symbols and
	 * prefix lists -- each either bare or quoted ({@code 'spec}, the oracle's
	 * bare-{@code require} spelling; the {@code ns} clauses quote implicitly, so both
	 * paths share this parser). An unquoted vector spec stays accepted too (a lenient
	 * superset: the oracle rejects it with a {@code ClassNotFoundException}). Requiring
	 * an unknown namespace is an error, like the oracle's missing-library failure.
	 */
	private void requireSpecs(List<LispVal> specs, boolean referAll) {
		String prefix = null;
		for (LispVal spec : specs) {
			LispVal unwrapped = unwrapQuote(spec);
			if (unwrapped instanceof LispSymbol bare) {
				prefix = bare.name();
				continue;
			}
			if (!isVectorDatum(unwrapped)) {
				// a prefix list: (prefix member...) names prefix.member...
				// libraries -- quoted ('(prefix ...), the oracle's bare-require
				// spelling) or bare (the unquoted-vector leniency extended, shared
				// with the ns clauses which quote implicitly). Each member (bare
				// or quoted) is a symbol or a [...] vector resolved under the
				// prefix through requireOne.
				List<LispVal> prefixParts = items(unwrapped);
				if (prefixParts == null || prefixParts.isEmpty()
						|| !(prefixParts.get(0) instanceof LispSymbol prefixName)
						|| prefixName.name().startsWith(":")) {
					throw new LispReadException("require takes library specs, not " + spec.print());
				}
				if (prefixParts.size() < 2) {
					// (prefix) names the prefix library itself, like a bare symbol.
					requireOne(null, prefixParts, referAll, spec);
					continue;
				}
				for (int i = 1; i < prefixParts.size(); i++) {
					LispVal sub = unwrapQuote(prefixParts.get(i));
					if (sub instanceof LispSymbol sym) {
						requireOne(prefixName.name(), List.of(sym), referAll, spec);
						continue;
					}
					List<LispVal> subParts = items(sub);
					if (subParts == null || subParts.isEmpty() || !isVectorDatum(sub) || subParts.size() < 2
							|| !(subParts.get(1) instanceof LispSymbol)) {
						throw new LispReadException("require takes library specs, not " + spec.print());
					}
					requireOne(prefixName.name(), subParts.subList(1, subParts.size()), referAll, spec);
				}
				continue;
			}
			List<LispVal> parts = items(unwrapped);
			if (parts == null || parts.size() < 2 || !(parts.get(1) instanceof LispSymbol)) {
				throw new LispReadException("require takes library specs, not " + spec.print());
			}
			requireOne(prefix, parts.subList(1, parts.size()), referAll, spec);
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
		if (only != null) {
			// :only narrows in both modes: under use (:refer :all) it wins over
			// the refer-all default, and :exclude subtracts from it either way.
			for (String var : only) {
				if (!exclude.contains(var)) {
					if (!isKnownVar(ns, var)) {
						throw new LispReadException("unknown name: " + ns + "/" + var);
					}
					this.refers.put(var, new VarRef(ns, var));
				}
			}
		}
		else if (all) {
			for (String var : varsOf(ns)) {
				if (!exclude.contains(var)) {
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

	/**
	 * A libspec unquoted: {@code 'spec} and {@code spec} spell the same library, whether
	 * the spec is a bare symbol, a {@code [...]} vector or a prefix list -- the oracle
	 * quotes every bare-{@code require} spec, while the {@code ns} clauses quote
	 * implicitly. Unquoted vector specs stay accepted too (a lenient superset: the oracle
	 * rejects them with a {@code ClassNotFoundException}).
	 */
	private static LispVal unwrapQuote(LispVal spec) {
		List<LispVal> parts = items(spec);
		if (parts != null && parts.size() == 2 && isSymbolNamed(parts.get(0), "quote")) {
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
		if (ns == null) {
			return null;
		}
		if (!isKnownVar(ns, tail)) {
			if (isKnownNamespace(ns)) {
				throw new LispReadException("unknown name: " + ns + "/" + tail);
			}
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
			return keywordForm(resolveKeywordSpelling(name)); // a keyword is its
																// spelling,
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
		if (name.equals("*out*") || name.equals("*in*") || name.equals("*agent*")) {
			// dynamic aliases, not mangled names (see idSym): always resolvable
			if (name.equals("*agent*")) {
				this.usedStm = true;
			}
			return idSym(name);
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
				return namespaceValue(qualified);
			}
			LispVal synth = valueOf(name);
			if (synth != null) {
				return synth;
			}
			String cl = builtinValue(name);
			if (cl == null) {
				LispVal member = interopValue(name);
				if (member != null) {
					return member;
				}
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
					"<", ">", "<=", ">=", "length", "car", "cdr", "equal", "evenp", "oddp", "zerop", "plusp", "minusp",
					"vector", "vectorp", "identity" ->
				name;
			case "gensym" -> "gensym";
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
				return list(sym("quote"), keywordDatum(resolveKeywordSpelling(name)));
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
			if (!items.isEmpty() && items.get(0) == ClojureReader.REGEX
					|| !items.isEmpty() && isSymbolNamed(items.get(0), "%regex")) {
				return regexForm(items);
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
			return keywordForm(resolveKeywordSpelling(s.name()));
		}
		return quotedConstant(datum);
	}

	// errors: try over handler-case and unwind-protect, throw over error

	/**
	 * The {@code try} body lowered behind the barrier: the depth grows while it lowers,
	 * so a {@code recur} inside one trips the barrier no matter where the {@code try}
	 * itself sits. The body is never tail position either.
	 */
	private LispVal tryBodyOf(List<LispVal> body) {
		boolean outerTail = this.tailPosition;
		this.tailPosition = false;
		this.tryDepth++;
		try {
			return bodyOf(body);
		}
		finally {
			this.tryDepth--;
			this.tailPosition = outerTail;
		}
	}

	/**
	 * {@code (try body... (catch Class var body...)... (finally ...))}: the body guarded
	 * by a {@code handler-case} inside an {@code unwind-protect}. Every catch class
	 * answers the catch-all {@code error} clause -- the classes are not distinguished, so
	 * the first clause handles any condition -- and the clauses keep their order. The
	 * catch var binds the Common Lisp condition, not a host exception. The body lowers
	 * behind the {@code try} barrier (a {@code recur} across it is the oracle's
	 * {@code Cannot recur across try} refusal); the catch and finally parts are never
	 * tail position (a {@code recur} there is the oracle's tail refusal), like the
	 * oracle.
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
		LispVal guarded = body.isEmpty() ? NIL_CONST : tryBodyOf(body);
		if (!catches.isEmpty()) {
			List<LispVal> clauses = new ArrayList<>();
			for (List<LispVal> caught : catches) {
				String var = ((LispSymbol) caught.get(2)).name();
				LispVal clauseBody = inScope(new HashMap<>(Map.of(var, Kind.VARIABLE)),
						() -> nonTailBodyOf(caught.subList(3, caught.size())));
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

	// state: refs over the atom cell, agents as synchronous atoms, binding over
	// specials, and the small imperative companions

	/**
	 * The STM runtime, spliced once behind the false binding when the program uses refs
	 * or agents: the open-transaction depth (a {@code dosync} binds it one deeper, so
	 * {@code alter} and friends outside one signal), the validator registry (an alist of
	 * cell/validator pairs, cells compared by identity) and the acting-agent var (bound
	 * while a {@code send} runs, nil outside one). Pure lowering over existing
	 * primitives, so every backend runs it unchanged.
	 */
	private List<LispVal> stmRuntime() {
		List<LispVal> runtime = new ArrayList<>();
		runtime.add(list(sym("defvar"), new LispSymbol("C%STM-DEPTH"), new LispInteger(0)));
		runtime.add(list(sym("defvar"), new LispSymbol("C%STM-VALIDATORS"), NIL_CONST));
		runtime.add(list(sym("defvar"), new LispSymbol("C%AGENT"), NIL_CONST));
		LispSymbol cell = new LispSymbol("cell");
		LispSymbol validator = new LispSymbol("validator");
		LispSymbol rest = new LispSymbol("rest");
		LispSymbol value = new LispSymbol("value");
		LispSymbol found = new LispSymbol("found");
		LispSymbol verdict = new LispSymbol("verdict");
		// (defun c%stm-put (cell validator) ...): register the validator, answer it
		runtime.add(list(sym("defun"), new LispSymbol("C%STM-PUT"), list(List.of(cell, validator)),
				list(sym("setq"), new LispSymbol("C%STM-VALIDATORS"),
						list(sym("cons"), list(sym("cons"), cell, validator), new LispSymbol("C%STM-VALIDATORS"))),
				validator));
		// (defun c%stm-get (cell) ...): the validator registered for the cell, or nil
		LispSymbol walk = new LispSymbol("walk");
		runtime.add(
				list(sym("defun"), new LispSymbol("C%STM-GET"), list(List.of(cell)),
						list(sym("labels"),
								list(List.of(list(walk, list(rest), list(sym("if"), list(sym("null"), rest), NIL_CONST,
										list(sym("if"), list(sym("eq"), list(sym("caar"), rest), cell),
												list(sym("cdar"), rest), list(walk, list(sym("cdr"), rest))))))),
								list(walk, new LispSymbol("C%STM-VALIDATORS")))));
		// (defun c%stm-check (cell value) ...): write the value through the
		// validator, answering it; a failed validator signals and writes nothing
		// (the single-threaded rollback: nothing else ran)
		LispVal write = list(sym("progn"), atomPut(cell, value), value);
		runtime.add(list(sym("defun"), new LispSymbol("C%STM-CHECK"), list(List.of(cell, value)), list(sym("let"),
				list(List.of(list(found, list(new LispSymbol("C%STM-GET"), cell)))),
				list(sym("if"), found,
						list(sym("let"), list(List.of(list(verdict, list(sym("funcall"), found, value)))),
								list(sym("if"),
										list(sym("or"), list(sym("null"), verdict),
												list(sym("eq"), verdict, this.falseVariable)),
										list(sym("error"), LispString.literal("Invalid reference state")), write)),
						write))));
		return runtime;
	}

	/** The STM transaction guard: the body runs only inside {@code dosync}. */
	private static LispVal txnGuard(LispVal guarded) {
		return list(sym("if"), list(sym(">"), new LispSymbol("C%STM-DEPTH"), new LispInteger(0)), guarded,
				list(sym("error"), LispString.literal("No transaction running")));
	}

	/**
	 * A {@code ref}/{@code agent} option list over an already-lowered init: the
	 * {@code :validator} as a callable form, or null. {@code :meta} is dropped, like
	 * every other metadata; anything else is refused by name.
	 */
	private @Nullable LispVal validatorOpt(List<LispVal> items, int from, String op) {
		LispVal validator = null;
		for (int i = from; i < items.size(); i += 2) {
			if (!(items.get(i) instanceof LispSymbol opt) || i + 1 >= items.size()) {
				throw new LispReadException(op + " takes option/value pairs");
			}
			switch (opt.name()) {
				case ":validator" -> {
					isTrue(validator == null, op + " takes one :validator");
					validator = fnValue(items.get(i + 1));
				}
				case ":meta" -> {
				} // dropped, like every other metadata
				default -> throw new LispReadException(op + " option " + opt.name() + " is not supported yet");
			}
		}
		return validator;
	}

	/**
	 * A fresh cell with the validator registered: every {@code ref} and every
	 * {@code agent} with a {@code :validator} builds one.
	 */
	private LispVal checkedCell(LispVal init, LispVal validator) {
		LispSymbol made = freshTemp();
		return list(sym("let"), list(List.of(list(made, wrapAtom(init)))),
				list(new LispSymbol("C%STM-PUT"), made, validator), made);
	}

	/**
	 * {@code (ref init & opts)}: an atom cell, like {@code atom}; with a
	 * {@code :validator} the validator runs on every {@code alter}/{@code commute}/
	 * {@code ref-set} and rejects the write on failure.
	 */
	private LispVal refOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "ref takes an initial value");
		LispVal init = lower(items.get(1));
		LispVal validator = validatorOpt(items, 2, "ref");
		this.usedStm = true;
		if (validator == null) {
			return wrapAtom(init);
		}
		return checkedCell(init, validator);
	}

	/** {@code ref} as a value: a one-argument lambda over the constructor. */
	private LispVal refValue() {
		LispSymbol init = new LispSymbol(mangle("ref-init"));
		return list(sym("lambda"), list(init), wrapAtom(init));
	}

	/**
	 * {@code (dosync body...)}: the body with the transaction depth bound one deeper.
	 * Single-threaded there is nothing to retry and nothing to isolate against, so a
	 * transaction is a dynamic extent; {@code alter} and friends still require one.
	 */
	private LispVal dosyncOf(List<LispVal> items) {
		this.usedStm = true;
		LispSymbol depth = new LispSymbol("C%STM-DEPTH");
		return list(sym("let"), list(List.of(list(depth, list(sym("+"), depth, new LispInteger(1))))), body(items, 1));
	}

	/**
	 * {@code (alter ref fun args...)} and {@code (commute ref fun args...)}: the function
	 * applied to the old value and the arguments, written through the validator.
	 * Single-threaded the two commute identically (the oracle may run a commute's
	 * function twice); both require a transaction.
	 */
	private LispVal alterOf(List<LispVal> items, String op) {
		isTrue(items.size() >= 3, op + " takes a ref, a function and arguments");
		this.usedStm = true;
		return alterBuild(lower(items.get(1)), fnValue(items.get(2)), cons(sym("list"), lowers(items, 3)), op);
	}

	/**
	 * An {@code alter}/{@code commute} over already-lowered forms: the guard, the
	 * application and the validated write, answering the new value.
	 */
	private LispVal alterBuild(LispVal cell, LispVal fun, LispVal argList, String op) {
		return withAtom(cell, op, bound -> {
			LispSymbol next = freshTemp();
			LispVal spread = list(sym("cons"), atomGet(bound), argList);
			LispVal update = list(sym("let"), list(List.of(list(next, list(sym("apply"), fun, spread)))),
					list(new LispSymbol("C%STM-CHECK"), bound, next));
			return txnGuard(update);
		});
	}

	/** {@code alter}/{@code commute} as a value: over a ref, a function and arguments. */
	private LispVal alterValue(String op) {
		LispSymbol cell = new LispSymbol(mangle("alter-cell"));
		LispSymbol fun = new LispSymbol(mangle("alter-fun"));
		LispSymbol rest = new LispSymbol(mangle("alter-rest"));
		return list(sym("lambda"), list(List.of(cell, fun, AMPERSAND_REST, rest)), alterBuild(cell, fun, rest, op));
	}

	/** {@code (ref-set ref value)}: the value written through the validator. */
	private LispVal refSetOf(List<LispVal> items) {
		isTrue(items.size() == 3, "ref-set takes a ref and a value");
		this.usedStm = true;
		LispVal value = lower(items.get(2));
		return withAtom(lower(items.get(1)), "ref-set", bound -> txnGuard(refSetBuild(bound, value)));
	}

	/** A {@code ref-set} over already-lowered forms: the validated write. */
	private LispVal refSetBuild(LispVal bound, LispVal value) {
		LispSymbol next = freshTemp();
		return list(sym("let"), list(List.of(list(next, value))), list(new LispSymbol("C%STM-CHECK"), bound, next));
	}

	/** {@code ref-set} as a value: a two-argument lambda over the write. */
	private LispVal refSetValue() {
		LispSymbol cell = new LispSymbol(mangle("ref-set-cell"));
		LispSymbol value = new LispSymbol(mangle("ref-set-value"));
		return list(sym("lambda"), list(List.of(cell, value)),
				withAtom(cell, "ref-set", bound -> txnGuard(refSetBuild(bound, value))));
	}

	/**
	 * {@code (ensure ref)}: the ref itself, requiring a transaction like the oracle
	 * (where it also snapshots the ref for the transaction's read set).
	 */
	private LispVal ensureOf(List<LispVal> items) {
		isTrue(items.size() == 2, "ensure takes one ref");
		this.usedStm = true;
		return withAtom(lower(items.get(1)), "ensure", bound -> txnGuard(bound));
	}

	/**
	 * {@code (agent init & opts)}: an atom cell, like {@code ref}; a {@code :validator}
	 * runs on every {@code send}.
	 */
	private LispVal agentOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "agent takes an initial value");
		LispVal init = lower(items.get(1));
		LispVal validator = validatorOpt(items, 2, "agent");
		this.usedStm = true;
		if (validator == null) {
			return wrapAtom(init);
		}
		return checkedCell(init, validator);
	}

	/** {@code agent} as a value: a one-argument lambda over the constructor. */
	private LispVal agentValue() {
		LispSymbol init = new LispSymbol(mangle("agent-init"));
		return list(sym("lambda"), list(init), wrapAtom(init));
	}

	/**
	 * {@code (send agent fun args...)} and {@code (send-off ...)}: the function applied
	 * now, through the validator, answering the agent. Agents run synchronously -- there
	 * is no thread pool on any backend, so async ordering is out and {@code await} is
	 * already past when it runs.
	 */
	private LispVal sendOf(List<LispVal> items) {
		String op = ((LispSymbol) items.get(0)).name();
		isTrue(items.size() >= 3, op + " takes an agent, a function and arguments");
		this.usedStm = true;
		return sendBuild(lower(items.get(1)), fnValue(items.get(2)), cons(sym("list"), lowers(items, 3)), op);
	}

	/**
	 * A {@code send} over already-lowered forms: the application with {@code *agent*}
	 * bound to the cell, answering the cell.
	 */
	private LispVal sendBuild(LispVal cell, LispVal fun, LispVal argList, String op) {
		return withAtom(cell, op, bound -> {
			LispSymbol next = freshTemp();
			LispVal spread = list(sym("cons"), atomGet(bound), argList);
			LispVal update = list(sym("let"), list(List.of(list(next, list(sym("apply"), fun, spread)))),
					list(new LispSymbol("C%STM-CHECK"), bound, next));
			return list(sym("let"), list(List.of(list(new LispSymbol("C%AGENT"), bound))),
					list(sym("progn"), update, bound));
		});
	}

	/**
	 * {@code send}/{@code send-off} as a value: over an agent, a function and arguments.
	 */
	private LispVal sendValue(String op) {
		LispSymbol cell = new LispSymbol(mangle("send-cell"));
		LispSymbol fun = new LispSymbol(mangle("send-fun"));
		LispSymbol rest = new LispSymbol(mangle("send-rest"));
		return list(sym("lambda"), list(List.of(cell, fun, AMPERSAND_REST, rest)), sendBuild(cell, fun, rest, op));
	}

	/**
	 * {@code (await agent...)}: every send already ran, so every agent is awaited; each
	 * is still checked, answering nil like the oracle.
	 */
	private LispVal awaitOf(List<LispVal> items) {
		this.usedStm = true;
		List<LispVal> checks = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			checks.add(derefForm(lower(items.get(i))));
		}
		checks.add(NIL_CONST);
		if (checks.size() == 1) {
			return NIL_CONST;
		}
		return cons(sym("progn"), checks);
	}

	/**
	 * One body lowered behind the {@code try} barrier with the tail position kept: a
	 * {@code recur} in it trips the oracle's {@code Cannot recur across try} refusal,
	 * while a target opened inside it still recurs. The {@code binding} and
	 * {@code with-open} bodies lower through here (the oracle wraps both in a
	 * {@code try}), the inits through plain {@code body}.
	 */
	private LispVal barrierBody(List<LispVal> items, int from) {
		this.tryDepth++;
		try {
			return body(items, from);
		}
		finally {
			this.tryDepth--;
		}
	}

	/**
	 * {@code (binding [var init ...] body...)}: each var rebound around the body, like
	 * the oracle -- which is why only {@code ^:dynamic} vars (and
	 * {@code *out*}/{@code *in*}, already special) may be bound. Inits run sequentially,
	 * like {@code let}, and the body closes over the scope the same way. The body lowers
	 * behind the {@code try} barrier (the oracle wraps it in a {@code try/finally}),
	 * while the inits stay outside it.
	 */
	private LispVal bindingOf(List<LispVal> items) {
		isTrue(items.size() >= 3, "binding needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "binding");
		isTrue(bindings.size() % 2 == 0, "a binding vector pairs a var with a value");
		Map<String, Kind> scope = new HashMap<>();
		this.scopes.add(scope);
		this.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			for (int i = 0; i < bindings.size(); i += 2) {
				String name = plainName(bindings.get(i), "binding");
				if (!name.equals("*out*") && !name.equals("*in*") && !this.dynamicVars.contains(name)) {
					throw new LispReadException("binding " + name + " needs a ^:dynamic var: only dynamic vars rebind");
				}
				if (name.equals("*agent*")) {
					this.usedStm = true;
				}
				LispVal init = lower(bindings.get(i + 1));
				pairs.add(list(idSym(name), init));
				scope.put(name, Kind.VARIABLE);
				if (isDirectFun(init)) {
					markDirect(name);
				}
			}
			return list(sym("let*"), list(pairs), barrierBody(items, 2));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.directScopes.remove(this.directScopes.size() - 1);
		}
	}

	/**
	 * {@code (with-open [name init ...] body...)}: the body with each value bound, closed
	 * in reverse order on every exit through {@code unwind-protect}. Closing calls the
	 * {@code close} method, so a Java closeable works where host objects exist (the
	 * interpreter and the JVM -- wasm rejects {@code java:}). A non-empty body lowers
	 * behind the {@code try} barrier (the oracle closes in a {@code finally}); an empty
	 * vector is the plain body, like the oracle's bare {@code do}.
	 */
	private LispVal withOpenOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "with-open needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "with-open");
		isTrue(bindings.size() % 2 == 0, "a with-open binding vector pairs a name with a value");
		Map<String, Kind> scope = new HashMap<>();
		this.scopes.add(scope);
		this.directScopes.add(new HashSet<>());
		try {
			List<LispVal> pairs = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (int i = 0; i < bindings.size(); i += 2) {
				String name = plainName(bindings.get(i), "with-open");
				LispVal init = lower(bindings.get(i + 1));
				pairs.add(list(idSym(name), init));
				names.add(name);
				scope.put(name, Kind.VARIABLE);
				if (isDirectFun(init)) {
					markDirect(name);
				}
			}
			LispVal run = names.isEmpty() ? body(items, 2) : barrierBody(items, 2);
			if (names.isEmpty()) {
				return run;
			}
			List<LispVal> guarded = new ArrayList<>();
			guarded.add(sym("unwind-protect"));
			guarded.add(run);
			for (int i = names.size() - 1; i >= 0; i--) {
				guarded.add(instanceCall(idSym(names.get(i)), "close", List.of()));
			}
			return list(sym("let*"), list(pairs), list(guarded));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.directScopes.remove(this.directScopes.size() - 1);
		}
	}

	/**
	 * {@code (with-out-str body...)}: the body with {@code *standard-output*} bound to a
	 * fresh string stream, answering what it printed. The stream is built with
	 * {@code make-string-output-stream} (never a literal {@code with-output-to-string},
	 * which would flip a WASM module into EH mode), like {@code str}.
	 */
	private LispVal withOutStrOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "with-out-str needs a body");
		LispSymbol stream = freshTemp();
		List<LispVal> run = new ArrayList<>(lowers(items, 1));
		run.add(list(sym("get-output-stream-string"), stream));
		LispVal captured = run.size() == 1 ? run.get(0) : cons(sym("progn"), run);
		return list(sym("let*"), list(List.of(list(stream, list(sym("make-string-output-stream"))),
				list(new LispSymbol("*STANDARD-OUTPUT*"), stream))), captured);
	}

	/**
	 * {@code (time expr)}: the expression timed with {@code get-internal-real-time}
	 * (milliseconds here), reporting {@code Elapsed time: N msecs} like the oracle and
	 * answering the value. Only the value is deterministic -- the report's number never
	 * is, so the spec pins the prefix, never the line.
	 */
	private LispVal timeOf(List<LispVal> items) {
		isTrue(items.size() == 2, "time takes one form");
		LispSymbol start = freshTemp();
		LispSymbol value = freshTemp();
		LispVal elapsed = list(sym("-"), list(sym("get-internal-real-time")), start);
		// the report straight to the stream, like println of one string part
		List<LispVal> parts = new ArrayList<>();
		parts.add(strOf(LispString.literal("Elapsed time: "), LispString.literal(""), NIL_CONST));
		parts.add(strOf(elapsed, LispString.literal(""), NIL_CONST));
		parts.add(strOf(LispString.literal(" msecs"), LispString.literal(""), NIL_CONST));
		LispVal report = cons(sym("progn"), List.of(writeDatum(concat(parts), LispString.literal("nil"), NIL_CONST),
				list(sym("terpri")), NIL_CONST));
		return list(sym("let*"),
				list(List.of(list(start, list(sym("get-internal-real-time"))), list(value, lower(items.get(1))))),
				report, value);
	}

	/**
	 * {@code (defonce name init?)}: {@code def} unless the name is already bound -- a
	 * reload keeps the root, like the oracle.
	 */
	private LispVal defonceForm(List<LispVal> items) {
		isTrue(items.size() == 2 || items.size() == 3, "defonce takes a name and an optional value");
		LispVal nameDatum = items.get(1);
		String name = plainName(nameDatum, "defonce");
		boolean dynamic = nameIsDynamic(nameDatum);
		this.globals.put(name, Kind.VARIABLE);
		this.macros.remove(name); // a definition wins over the macro it shadows
		if (dynamic) {
			this.dynamicVars.add(name);
		}
		LispVal value = items.size() == 3 ? lower(items.get(2)) : NIL_CONST;
		if (isDirectFun(value)) {
			this.globalDirectFuns.add(name);
		}
		else {
			this.globalDirectFuns.remove(name);
		}
		LispVal set = dynamic ? list(sym("defparameter"), idSym(name), value) : list(sym("setq"), idSym(name), value);
		return list(sym("if"), list(sym("boundp"), list(sym("quote"), idSym(name))), idSym(name), set);
	}

	/**
	 * {@code (defstruct name key...)}: the key vector behind the name, so {@code struct}
	 * builds maps with exactly those keys. Keys are keywords, like the oracle.
	 */
	private LispVal defstructForm(List<LispVal> items) {
		isTrue(items.size() >= 2, "defstruct takes a name and keys");
		String name = plainName(items.get(1), "defstruct");
		this.globals.put(name, Kind.VARIABLE);
		this.macros.remove(name); // a definition wins over the macro it shadows
		List<LispVal> keys = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			LispVal key = items.get(i);
			if (!(key instanceof LispSymbol s) || !s.name().startsWith(":")) {
				throw new LispReadException("defstruct takes keyword keys, not " + key.print());
			}
			keys.add(keywordForm(resolveKeywordSpelling(s.name())));
		}
		return list(sym("setq"), idSym(name), cons(sym("vector"), keys));
	}

	/**
	 * {@code (struct struct-map value...)}: a fresh map pairing the struct's keys with
	 * the values, missing values nil. Too many values signal, like the oracle.
	 */
	private LispVal structOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "struct takes a struct and values");
		return structBuild(lower(items.get(1)), cons(sym("list"), lowers(items, 2)), "struct");
	}

	/** A {@code struct} over an already-lowered key vector and value list. */
	private LispVal structBuild(LispVal keys, LispVal values, String op) {
		LispSymbol slots = freshTemp();
		LispSymbol vals = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol have = freshTemp();
		LispSymbol want = freshTemp();
		LispSymbol index = freshTemp();
		LispVal fill = list(sym("dotimes"), list(List.of(index, want)),
				list(sym("setf"), list(sym("gethash"), list(sym("aref"), slots, index), table),
						list(sym("if"), list(sym("<"), index, have), list(sym("nth"), index, vals), NIL_CONST)));
		return list(sym("let*"),
				list(List.of(list(slots, keys), list(vals, values), list(table, makeTable()),
						list(have, list(sym("length"), vals)), list(want, list(sym("length"), slots)))),
				list(sym("if"), list(sym(">"), have, want),
						list(sym("error"), LispString.literal("Too many arguments to " + op + " constructor")),
						list(sym("progn"), fill, table)));
	}

	/**
	 * {@code (struct-map struct-map key value...)}: a fresh map with the struct's keys
	 * (nil unless overridden here), like the oracle.
	 */
	private LispVal structMapOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "struct-map takes a struct and key/value pairs");
		List<LispVal> pairs = lowers(items, 2);
		isTrue(pairs.size() % 2 == 0, "struct-map takes key/value pairs");
		LispSymbol slots = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol index = freshTemp();
		List<LispVal> run = new ArrayList<>();
		run.add(sym("progn"));
		run.add(list(sym("dotimes"), list(List.of(index, list(sym("length"), slots))),
				list(sym("setf"), list(sym("gethash"), list(sym("aref"), slots, index), table), NIL_CONST)));
		for (int i = 0; i < pairs.size(); i += 2) {
			run.add(list(sym("setf"), list(sym("gethash"), pairs.get(i), table), pairs.get(i + 1)));
		}
		run.add(table);
		return list(sym("let*"), list(List.of(list(slots, lower(items.get(1))), list(table, makeTable()))), list(run));
	}

	/** {@code with-meta} as a value: metadata is dropped, so the first argument. */
	private LispVal withMetaValue() {
		LispSymbol object = freshTemp();
		LispSymbol meta = freshTemp();
		return list(sym("lambda"), list(List.of(object, meta)), object);
	}

	// dispatch: multimethods over a method table and a dispatcher defun

	/**
	 * The host spellings a {@code defmethod} dispatch value may name, to the
	 * {@code class}-keyword the dispatcher actually produces for them. The table mirrors
	 * {@code class} (and {@code extend-protocol} targets): every numeric spelling merges
	 * into {@code :number} (the oracle tells {@code Long} from {@code Double}, which no
	 * wasm backend has -- the documented merge), every collection spelling into its kind.
	 */
	private static final Map<String, String> DISPATCH_CLASS_KEYWORDS = Map.ofEntries(Map.entry("String", "string"),
			Map.entry("CharSequence", "string"), Map.entry("Number", "number"), Map.entry("Long", "number"),
			Map.entry("Double", "number"), Map.entry("Integer", "number"), Map.entry("Float", "number"),
			Map.entry("Short", "number"), Map.entry("Byte", "number"), Map.entry("Boolean", "boolean"),
			Map.entry("Keyword", "keyword"), Map.entry("Symbol", "symbol"), Map.entry("Character", "char"),
			Map.entry("Char", "char"), Map.entry("Map", "map"), Map.entry("IPersistentMap", "map"),
			Map.entry("Vector", "vector"), Map.entry("IPersistentVector", "vector"), Map.entry("Set", "set"),
			Map.entry("IPersistentSet", "set"), Map.entry("List", "list"), Map.entry("Seq", "list"),
			Map.entry("Sequential", "list"), Map.entry("Collection", "list"), Map.entry("IPersistentList", "list"),
			Map.entry("IPersistentCollection", "list"), Map.entry("Fn", "function"), Map.entry("IFn", "function"),
			Map.entry("Function", "function"), Map.entry("Atom", "atom"));

	/**
	 * Whether the dispatch datum is the nil spelling: the symbol the reader answers for
	 * {@code nil}, or the empty list (which is nil too). A nil method stores under the
	 * {@code (:C%NIL)} marker (see {@link #nilMarkerForm}), so the corpus's both shapes
	 * ({@code my-print} over {@code class}, {@code my-class} over {@code identity}) share
	 * the one definition while a literal {@code :nil} keyword keeps its own row.
	 */
	private static boolean isNilDatum(LispVal datum) {
		return datum instanceof LispNil || (datum instanceof LispSymbol s && s.name().equals("nil"));
	}

	/**
	 * Whether the name spells {@code Object}: dotted, imported, or {@code java.lang}, a
	 * record or deftype of that name aside (which keeps its tag). An {@code Object}
	 * method matches every dispatch value, like the oracle's, so it lives in the
	 * multimethod's {@code %object} global beside its table row: the dispatcher tries it
	 * past the hierarchy search but ahead of the default, which it always beats.
	 */
	private boolean isObjectClassName(String name) {
		return !this.types.containsKey(name) && resolveClass(name).equals("java.lang.Object");
	}

	/**
	 * A class spelling to the keyword the {@code class} dispatcher produces for it, or
	 * null when the name is no class spelling at all (so the caller lowers it as usual --
	 * a var holding the dispatch value, like the oracle's evaluated position). Record and
	 * deftype names answer their tags; dotted, imported and {@code java.lang} spellings
	 * resolve through {@link #resolveClass} first, so {@code java.util.Map} and
	 * {@code clojure.lang.IPersistentVector} map like their simple names. A capitalized
	 * name that maps to nothing (an {@code Instant}, a {@code Date}, ...) is the
	 * {@code extend-protocol} row's named refusal.
	 */
	private @Nullable LispVal dispatchClassKey(String name) {
		if (this.types.containsKey(name)) {
			return typeTagForm(name);
		}
		if (isObjectClassName(name)) {
			return keywordForm("object");
		}
		boolean classlike = this.classNames.containsKey(name) || JAVA_LANG.contains(name) || name.indexOf('.') >= 0
				|| (!name.isEmpty() && Character.isUpperCase(name.charAt(0)));
		if (!classlike) {
			return null;
		}
		String fqn = resolveClass(name);
		String kind = DISPATCH_CLASS_KEYWORDS.get(fqn.substring(fqn.lastIndexOf('.') + 1));
		if (kind == null) {
			throw new LispReadException("defmethod needs a core class, not " + name);
		}
		return keywordForm(kind);
	}

	/**
	 * A {@code defmethod} (or {@code remove-method}, {@code get-method},
	 * {@code prefer-method}) dispatch value lowered to its table key: {@code nil} onto
	 * the {@code (:C%NIL)} marker (the dispatcher maps a true nil onto it, so no table
	 * ever keys on nil, and a literal {@code :nil} keyword keeps its keyword row), class
	 * spellings onto the keyword the {@code class} dispatcher produces,
	 * {@code ::}-keywords resolved like anywhere else, and literal vectors element by
	 * element (the corpus's {@code [Number]} and {@code [Map Number]} pairs, whose
	 * element-wise derivation the hierarchy search already runs). Anything else lowers as
	 * usual, so plain keywords and values keep their exact shapes.
	 */
	private LispVal dispatchKeyForm(LispVal datum) {
		if (isNilDatum(datum)) {
			return nilMarkerForm();
		}
		return dispatchElementForm(datum);
	}

	/**
	 * A literal vector's element lowered: the table-key shape except that a nil element
	 * stays the {@code :nil} keyword, exactly as before -- a class-mapped dispatch vector
	 * spells its nil element the same way, so a {@code [nil]} row keeps answering it,
	 * while a runtime vector holding a true nil still misses it, like before. Nested
	 * vectors recurse here, never onto the marker.
	 */
	private LispVal dispatchElementForm(LispVal datum) {
		if (isNilDatum(datum)) {
			return keywordForm("nil");
		}
		if (datum instanceof LispSymbol s) {
			String name = s.name();
			if (name.startsWith(":")) {
				return keywordForm(resolveKeywordSpelling(name));
			}
			LispVal cls = dispatchClassKey(name);
			if (cls != null) {
				return cls;
			}
			return lower(datum);
		}
		List<LispVal> parts = items(datum);
		if (parts != null && !parts.isEmpty() && parts.get(0) == ClojureReader.VECTOR) {
			List<LispVal> out = new ArrayList<>();
			out.add(sym("vector"));
			for (int i = 1; i < parts.size(); i++) {
				out.add(dispatchElementForm(parts.get(i)));
			}
			return list(out);
		}
		return lower(datum);
	}

	/**
	 * {@code (defmulti name doc? dispatch-fn & opts)}: a method table, a default dispatch
	 * value and an {@code Object}-method slot in four globals no identifier can spell
	 * (the suffix follows the mangled name, like the multi-arity helpers), plus a
	 * dispatcher {@code defun} applying each call's dispatch value to the table. The
	 * dispatcher maps a true nil onto the {@code (:C%NIL)} marker first (no table ever
	 * keys on nil, on any backend), so a {@code nil} method answers both the
	 * {@code class} nil and the {@code identity} nil while a dispatch value that
	 * literally is the {@code :nil} keyword keeps its keyword row, like the oracle. A
	 * {@code class} call inside the dispatch function answers nil itself for a nil
	 * argument (see {@link #classForm(LispVal)}), so the null test maps it onto the
	 * marker too -- bare or wrapped in another function, like the oracle. The default
	 * dispatch value is {@code :default} without a {@code :default} option (an arbitrary
	 * keyword with one -- the corpus's {@code :everything-else} -- stored per-multimethod
	 * like {@code :default} today); a miss with no method for the default signals, like
	 * the oracle. With a {@code :hierarchy} option the dispatcher consults that hierarchy
	 * value on a miss (the global one without the option): every method whose key the
	 * dispatch value descends from ({@code isa?}) is a candidate, the strictly most
	 * specific wins, {@code prefer-method} breaks the remaining ties, and an unbroken tie
	 * signals -- like the oracle. Past the search but ahead of the default, a defined
	 * {@code Object} method catches the rest, like the oracle's (which it always beats);
	 * without one the slot is nil and the search decides alone.
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
		LispSymbol object = new LispSymbol(mangle(name) + "%object");
		LispVal dispatchFn;
		boolean wasDispatch = this.inDispatchFn;
		this.inDispatchFn = true;
		try {
			dispatchFn = fnValue(dispatchDatum);
		}
		finally {
			this.inDispatchFn = wasDispatch;
		}
		LispVal defaultForm = defaultDatum == null ? keywordForm("default") : lower(defaultDatum);
		LispVal hierarchyForm = hierarchyDatum == null ? hierarchyGlobal() : lower(hierarchyDatum);
		LispSymbol args = freshTemp();
		LispSymbol disp = freshTemp();
		LispSymbol raw = freshTemp();
		LispSymbol found = freshTemp();
		LispSymbol miss = freshTemp();
		LispVal missCall = list(new LispSymbol(HIERARCHY_DISPATCH), LispString.literal(name), methods, prefers,
				hierarchyForm, fallback, disp, args);
		LispVal missForm = list(sym("if"),
				list(sym("and"), object,
						list(sym("null"), list(new LispSymbol("C%H-CANDIDATES"), methods, hierarchyForm, disp))),
				list(sym("apply"), object, args), missCall);
		// A `class` call inside the dispatch function answers nil itself for a nil
		// argument (see classForm), so the one null test maps every class-produced
		// nil onto the marker while an explicit `:nil` keyword keeps its keyword row,
		// like the oracle; a shadowed `class` is the caller's own function.
		LispVal nilTest = list(sym("null"), raw);
		LispVal dispatch = list(sym("let*"), list(List.of(list(raw, list(sym("apply"), dispatchFn, args)),
				list(disp, list(sym("if"), nilTest, nilMarkerForm(), raw)), list(miss, list(sym("list"), NIL_CONST)),
				list(found, list(sym("gethash"), disp, methods, miss)))),
				list(sym("if"), list(sym("eq"), found, miss), missForm, list(sym("apply"), found, args)));
		List<LispVal> forms = new ArrayList<>();
		forms.add(list(sym("setq"), methods, makeTable()));
		forms.add(list(sym("setq"), fallback, defaultForm));
		forms.add(list(sym("setq"), prefers, makeTable()));
		forms.add(list(sym("setq"), object, NIL_CONST));
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
	/**
	 * One single-arity method lambda, {@code labels}-wrapped under a fresh name when a
	 * {@code recur} reaches its body (like an anonymous {@code fn}): a stored method has
	 * no callable name of its own, so without a {@code recur} it stays a bare lambda,
	 * exactly as before. A used variadic target splits into a worker plus its
	 * {@code &rest} head, like every other {@code fn} shape (decided 2026-10-01, b36).
	 */
	private LispVal methodLambda(LispVal params, List<LispVal> bodyForms) {
		String fresh = freshRecurName();
		String worker = workerName(fresh);
		RecurTarget target = new RecurTarget(isVariadicParams(params) ? worker : fresh, true);
		Clause clause = clause(params, bodyForms, target);
		if (target.used() && clause.variadic()) {
			return splitMethodLambda(fresh, worker, clause, clause.wrapped());
		}
		LispVal lambda = list(sym("lambda"), list(clause.params()), clause.wrapped());
		if (!target.used()) {
			return lambda;
		}
		return labelsSelfCall(fresh, lambda);
	}

	private LispVal defmethodForm(List<LispVal> items) {
		isTrue(items.size() >= 4, "defmethod takes a name, a dispatch value, a parameter vector and a body");
		String name = plainName(items.get(1), "defmethod");
		isTrue(known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(mangle(name) + "%methods");
		LispVal lambda = methodLambda(items.get(3), items.subList(4, items.size()));
		LispVal keyDatum = items.get(2);
		if (keyDatum instanceof LispSymbol s && isObjectClassName(s.name())) {
			// the table row (for get-method) and the catch-all slot the dispatcher
			// tries past the hierarchy search but ahead of the default
			LispSymbol object = new LispSymbol(mangle(name) + "%object");
			return cons(sym("progn"),
					List.of(list(sym("setf"), list(sym("gethash"), keywordForm("object"), methods), lambda),
							list(sym("setq"), object, lambda)));
		}
		return list(sym("setf"), list(sym("gethash"), dispatchKeyForm(keyDatum), methods), lambda);
	}

	private LispVal removeMethodOf(List<LispVal> items) {
		isTrue(items.size() == 3, "remove-method takes a multimethod and a dispatch value");
		String name = plainName(items.get(1), "remove-method");
		isTrue(known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(mangle(name) + "%methods");
		LispVal keyDatum = items.get(2);
		if (keyDatum instanceof LispSymbol s && isObjectClassName(s.name())) {
			LispSymbol object = new LispSymbol(mangle(name) + "%object");
			return cons(sym("progn"), List.of(list(sym("remhash"), keywordForm("object"), methods),
					list(sym("setq"), object, NIL_CONST), list(sym("function"), idSym(name))));
		}
		return list(sym("progn"), list(sym("remhash"), dispatchKeyForm(keyDatum), methods),
				list(sym("function"), idSym(name)));
	}

	private LispVal getMethodOf(List<LispVal> items) {
		isTrue(items.size() == 3, "get-method takes a multimethod and a dispatch value");
		String name = plainName(items.get(1), "get-method");
		isTrue(known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(mangle(name) + "%methods");
		return list(sym("gethash"), dispatchKeyForm(items.get(2)), methods, NIL_CONST);
	}

	// protocols/records: defprotocol/defrecord/deftype/reify/extend/satisfies? over the
	// table runtime

	/**
	 * The tag heading a record value: a record is
	 * {@code (LIST :C%RECORD (:C%KEYWORD "Name") (fields...) table)}, beside the
	 * {@code (:C%SET table)} and {@code (:C%KEYWORD spelling)} wrappers. The table is an
	 * {@code equal} table like every map, so every backend prints, hashes and compares it
	 * through the runtime they already share; no backend learns a new value shape.
	 */
	private static final LispSymbol RECORD_TAG = new LispSymbol(":C%RECORD");

	/**
	 * The tag heading a deftype value: the same 4-list as a record, but opaque to the map
	 * verbs (reads miss, writers signal, {@code =} is identity), like the oracle.
	 */
	private static final LispSymbol TYPE_TAG = new LispSymbol(":C%TYPE");

	/**
	 * The tag heading a reify value: {@code (LIST :C%REIFY (gensym))}, one fresh tag per
	 * evaluation, so two instances never share a dispatch row. Opaque like a deftype.
	 */
	private static final LispSymbol REIFY_TAG = new LispSymbol(":C%REIFY");

	/**
	 * The runtime reader of a value's protocol-dispatch tag, as spelled in programs.
	 */
	private static final String PROTOCOL_TAG = "C%PROTOCOL-TAG";

	/**
	 * Whether the form holds a record: a cons headed by the tag with a field list and a
	 * table behind it. The full shape check keeps user data from misfiring the test, like
	 * {@link #isSetForm}.
	 */
	private static LispVal isRecordForm(LispVal form) {
		return list(sym("and"), list(sym("consp"), form), list(sym("eq"), list(sym("car"), form), RECORD_TAG),
				list(sym("consp"), list(sym("cddr"), form)), list(sym("hash-table-p"), list(sym("cadddr"), form)));
	}

	/** Whether the form holds a deftype value: the same shape check over its tag. */
	private static LispVal isDeftypeForm(LispVal form) {
		return list(sym("and"), list(sym("consp"), form), list(sym("eq"), list(sym("car"), form), TYPE_TAG),
				list(sym("consp"), list(sym("cddr"), form)), list(sym("hash-table-p"), list(sym("cadddr"), form)));
	}

	/** Whether the form holds a reify value: a cons headed by its tag. */
	private static LispVal isReifyForm(LispVal form) {
		return list(sym("and"), list(sym("consp"), form), list(sym("eq"), list(sym("car"), form), REIFY_TAG));
	}

	/** Whether the form holds any typed value: a record, a deftype or a reify. */
	private static LispVal isTypedForm(LispVal form) {
		return list(sym("or"), isRecordForm(form), isDeftypeForm(form), isReifyForm(form));
	}

	/** The dispatch tag inside a record or deftype value: {@code (CADR form)}. */
	private static LispVal typedTagOf(LispVal form) {
		return list(sym("cadr"), form);
	}

	/** The entry table inside a record or deftype value: {@code (CADDDR form)}. */
	private static LispVal typedTableOf(LispVal form) {
		return list(sym("cadddr"), form);
	}

	/**
	 * The declared field keywords inside a record or deftype value: {@code (CADDR form)}.
	 */
	private static LispVal typedFieldsOf(LispVal form) {
		return list(sym("caddr"), form);
	}

	/** A record's construction: {@code (LIST :C%RECORD tag fields table)}. */
	private static LispVal wrapRecord(LispVal tag, LispVal fields, LispVal table) {
		return list(sym("list"), RECORD_TAG, tag, fields, table);
	}

	/** A deftype's construction: {@code (LIST :C%TYPE tag fields table)}. */
	private static LispVal wrapDeftype(LispVal tag, LispVal fields, LispVal table) {
		return list(sym("list"), TYPE_TAG, tag, fields, table);
	}

	/** A type's dispatch tag as data: the keyword of its spelling. */
	private static LispVal typeTagForm(String name) {
		return keywordForm(name);
	}

	/**
	 * The protocol runtime, spliced once per program behind the false binding: the tag
	 * reader every dispatcher and {@code satisfies?} shares. A record, deftype or reify
	 * answers its own tag; anything else answers its {@code class} kind keyword, so
	 * extending to a host kind ({@code String}, {@code Number}, ...) dispatches on the
	 * same spelling {@code class} answers. What no branch names (a host object from
	 * interop on the backends that have one) answers a fresh one-list no row can hold, so
	 * the {@code Object} default still catches it instead of signalling.
	 */
	private List<LispVal> protocolRuntime() {
		LispSymbol one = new LispSymbol("x");
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isRecordForm(one), typedTagOf(one)));
		branches.add(list(isDeftypeForm(one), typedTagOf(one)));
		branches.add(list(isReifyForm(one), typedTagOf(one)));
		branches.add(list(list(sym("null"), one), keywordForm("nil")));
		branches.add(list(list(sym("eq"), one, this.falseVariable), keywordForm("boolean")));
		branches.add(list(list(sym("eq"), one, TRUE_CONST), keywordForm("boolean")));
		branches.add(list(keywordTest(one), keywordForm("keyword")));
		branches.add(list(list(sym("symbolp"), one), keywordForm("symbol")));
		branches.add(list(list(sym("characterp"), one), keywordForm("char")));
		branches.add(list(list(sym("stringp"), one), keywordForm("string")));
		branches.add(list(list(sym("numberp"), one), keywordForm("number")));
		branches.add(list(isSetForm(one), keywordForm("set")));
		branches.add(list(list(sym("hash-table-p"), one), keywordForm("map")));
		branches.add(list(list(sym("vectorp"), one), keywordForm("vector")));
		branches.add(list(list(sym("consp"), one), keywordForm("list")));
		branches.add(list(list(sym("functionp"), one), keywordForm("function")));
		branches.add(list(isAtomForm(one), keywordForm("atom")));
		branches.add(list(TRUE_CONST, list(sym("list"), NIL_CONST)));
		return List
			.of(list(sym("defun"), new LispSymbol(PROTOCOL_TAG), list(List.of(one)), cons(sym("cond"), branches)));
	}

	/**
	 * The pre-scan half of {@code defprotocol}: registers the protocol (parsed pure, so
	 * parsing twice is harmless) plus the protocol name and every method name, so a
	 * dispatch call may stand above the definition, like {@code defn}.
	 */
	private ProtocolDef declareProtocol(List<LispVal> items) {
		String name = plainName(items.get(1), "defprotocol");
		ProtocolDef def = parseProtocol(name, items);
		this.protocols.put(name, def);
		this.globals.put(name, Kind.VARIABLE);
		this.macros.remove(name); // a definition wins over the macro it shadows
		for (String method : def.methods()) {
			this.globals.put(method, Kind.FUNCTION);
			this.macros.remove(method); // a definition wins over the macro it shadows
		}
		return def;
	}

	/**
	 * The pre-scan half of {@code defrecord}/{@code deftype}: registers the type plus its
	 * constructors, so a constructor call may stand above the definition, like
	 * {@code defn}. The type name itself is no value (the oracle answers a host class,
	 * which no wasm backend has).
	 */
	private TypeDef declareRecordType(List<LispVal> items) {
		boolean record = isSymbolNamed(items.get(0), "defrecord");
		String name = plainName(items.get(1), record ? "defrecord" : "deftype");
		List<String> fields = recordFields(items);
		TypeDef def = new TypeDef(record, fields, name);
		this.types.put(name, def);
		this.globals.put("->" + name, Kind.FUNCTION);
		if (record) {
			this.globals.put("map->" + name, Kind.FUNCTION);
		}
		return def;
	}

	/**
	 * Parses a {@code defprotocol} datum without emitting: the name's method names in
	 * definition order, with the table and default globals. Docstrings and attr maps are
	 * skipped, like {@code defn} and {@code defmulti}; a signature is one parameter
	 * vector per method (several arities stay refused).
	 */
	private ProtocolDef parseProtocol(String name, List<LispVal> items) {
		int at = 2;
		if (at < items.size() && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		if (at < items.size() && isAttrMap(items.get(at))) {
			at++; // the attr map
		}
		for (; at + 1 < items.size() && items.get(at) instanceof LispSymbol opt
				&& opt.name().startsWith(":"); at += 2) {
			if (opt.name().equals(":extend-via-metadata")) {
				LispVal flag = items.get(at + 1);
				if (!(flag instanceof LispSymbol f && (f.name().equals("false") || f.name().equals("nil")))) {
					throw new LispReadException(
							"extend-via-metadata is not supported yet: metadata never affects dispatch");
				}
			}
			else {
				throw new LispReadException("defprotocol option " + opt.name() + " is not supported yet");
			}
		}
		Set<String> methods = new LinkedHashSet<>();
		for (; at < items.size(); at++) {
			List<LispVal> sig = items(items.get(at));
			if (sig == null || sig.isEmpty() || !(sig.get(0) instanceof LispSymbol)) {
				throw new LispReadException("defprotocol takes method signatures, not " + items.get(at).print());
			}
			String method = ((LispSymbol) sig.get(0)).name();
			isTrue(!method.startsWith(":"), "defprotocol takes method signatures, not " + items.get(at).print());
			isTrue(methods.add(method), "duplicate method in defprotocol " + name + ": " + method);
			int marg = 1;
			if (marg < sig.size() && sig.get(marg) instanceof LispString) {
				marg++; // the method docstring
			}
			isTrue(marg < sig.size() && isVectorDatum(stripMeta(sig.get(marg))),
					"multi-arity protocol methods are not supported yet: " + method);
			List<LispVal> params = bindingItems(stripMeta(sig.get(marg)), "the method " + method + " of");
			isTrue(!params.isEmpty(), "a protocol method takes a target and arguments: " + method);
		}
		isTrue(!methods.isEmpty(), "defprotocol takes at least one method: " + name);
		return new ProtocolDef(methods, new LispSymbol(mangle(name) + "%methods"),
				new LispSymbol(mangle(name) + "%default"));
	}

	/**
	 * {@code (defprotocol name doc? (method [target & args] doc?)...)}: a method-table
	 * global plus an {@code Object}-default global per protocol, one dispatcher
	 * {@code defun} per method, and the protocol name bound to its table. A call
	 * dispatches on the target's tag (exact match, then the {@code Object} row); a miss
	 * with no {@code Object} row signals, like the oracle.
	 */
	private List<LispVal> defprotocolForms(List<LispVal> items) {
		isTrue(items.size() >= 2, "defprotocol takes a name and methods");
		String name = plainName(items.get(1), "defprotocol");
		ProtocolDef def = declareProtocol(items);
		List<LispVal> forms = new ArrayList<>();
		forms.add(list(sym("setq"), def.methodsVar(), makeTable()));
		forms.add(list(sym("setq"), def.defaultVar(), NIL_CONST));
		for (String method : def.methods()) {
			forms.add(dispatcherDefun(name, method, def));
		}
		forms.add(list(sym("setq"), idSym(name), def.methodsVar()));
		this.usedProtocols = true;
		return forms;
	}

	/**
	 * One protocol-method dispatcher: the {@code &rest} shape {@code defmulti} takes, so
	 * arities (fixed or variadic) fall out of the stored lambda. No arguments signals the
	 * wrong-count error instead of dispatching on nil, like the oracle's arity error.
	 */
	private LispVal dispatcherDefun(String protocol, String method, ProtocolDef def) {
		LispSymbol args = freshTemp();
		LispSymbol tag = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol inner = freshTemp();
		LispSymbol found = freshTemp();
		LispVal lookup = list(sym("let*"),
				list(List.of(list(tag, list(new LispSymbol(PROTOCOL_TAG), list(sym("car"), args))),
						list(miss, list(sym("list"), NIL_CONST)),
						list(inner, list(sym("gethash"), tag, def.methodsVar(), miss)),
						list(found,
								list(sym("if"), list(sym("eq"), inner, miss), miss,
										list(sym("gethash"), keywordForm(method), inner, miss))))),
				list(sym("if"), list(sym("eq"), found, miss),
						list(sym("if"), list(sym("null"), def.defaultVar()),
								list(sym("error"),
										LispString.literal("No implementation of method :" + method + " of protocol :"
												+ protocol + " found")),
								list(sym("apply"), def.defaultVar(), args)),
						list(sym("apply"), found, args)));
		LispVal body = list(sym("if"), list(sym("null"), args),
				list(sym("error"), LispString.literal("wrong number of arguments passed to: " + method)), lookup);
		return list(sym("defun"), idSym(method), list(List.of(AMPERSAND_REST, args)), body);
	}

	/**
	 * Stores one method row in a protocol's table: the tag's inner table (made on first
	 * use) mapping the method keyword to the lambda. Later rows win, like the oracle.
	 */
	private LispVal methodStoreForm(LispSymbol methodsVar, LispVal key, String method, LispVal lambda) {
		LispSymbol inner = freshTemp();
		LispSymbol miss = freshTemp();
		LispVal ensure = list(sym("if"), list(sym("eq"), inner, miss),
				list(sym("setf"), list(sym("gethash"), key, methodsVar), list(sym("setf"), inner, makeTable())), inner);
		return list(sym("let*"),
				list(List.of(list(miss, list(sym("list"), NIL_CONST)),
						list(inner, list(sym("gethash"), key, methodsVar, miss)))),
				ensure, list(sym("setf"), list(sym("gethash"), keywordForm(method), inner), lambda));
	}

	/**
	 * The declared fields of a {@code defrecord}/{@code deftype} datum: plain names (type
	 * hints strip, like everywhere else), each once.
	 */
	private List<String> recordFields(List<LispVal> items) {
		boolean record = isSymbolNamed(items.get(0), "defrecord");
		String what = record ? "defrecord" : "deftype";
		isTrue(items.size() >= 3, what + " takes a name and fields");
		List<LispVal> names = bindingItems(stripMeta(items.get(2)), what);
		List<String> fields = new ArrayList<>();
		for (LispVal datum : names) {
			String field = plainName(stripMeta(datum), what);
			isTrue(!fields.contains(field), "duplicate field in " + what + " " + items.get(1).print() + ": " + field);
			fields.add(field);
		}
		return fields;
	}

	/**
	 * One parsed method implementation: the method name, its parameter vector and body.
	 */
	private record TypeMethod(String method, LispVal params, List<LispVal> body) {
	}

	/** One parsed implementation group: the protocol plus its method implementations. */
	private record ImplGroup(String protocol, List<TypeMethod> methods) {
	}

	/**
	 * The implementation groups behind {@code defrecord}/{@code deftype} (each headed by
	 * a known protocol name) or {@code extend-type}: every method must belong to its
	 * protocol, like the oracle's "Can't define method not in interfaces".
	 */
	private List<ImplGroup> implGroups(List<LispVal> rest, String what) {
		List<ImplGroup> groups = new ArrayList<>();
		String protocol = null;
		ProtocolDef def = null;
		List<TypeMethod> methods = null;
		for (LispVal datum : rest) {
			if (datum instanceof LispSymbol s && !s.name().startsWith(":")) {
				if (methods != null) {
					if (protocol == null) {
						throw new LispReadException(what + " takes method implementations");
					}
					groups.add(new ImplGroup(protocol, methods));
				}
				protocol = s.name();
				def = this.protocols.get(protocol);
				if (def == null) {
					throw new LispReadException("No such protocol: " + protocol);
				}
				methods = new ArrayList<>();
				continue;
			}
			if (def == null || methods == null) {
				throw new LispReadException(what + " methods group under a protocol name, not " + datum.print());
			}
			List<LispVal> impl = items(datum);
			if (impl == null || impl.size() < 2 || !(impl.get(0) instanceof LispSymbol)) {
				throw new LispReadException(
						"a method implementation takes a name, parameters and a body, not " + datum.print());
			}
			String method = ((LispSymbol) impl.get(0)).name();
			isTrue(def.methods().contains(method), "Can't define method not in interfaces: " + method);
			String seen = method;
			isTrue(methods.stream().noneMatch(m -> m.method().equals(seen)),
					"duplicate method implementation: " + method);
			isTrue(isVectorDatum(stripMeta(impl.get(1))),
					"multi-arity protocol methods are not supported yet: " + method);
			List<LispVal> params = bindingItems(stripMeta(impl.get(1)), "the method " + method + " of");
			isTrue(!params.isEmpty(), "a protocol method takes a target and arguments: " + method);
			methods.add(new TypeMethod(method, impl.get(1), impl.subList(2, impl.size())));
		}
		if (methods != null) {
			if (protocol == null) {
				throw new LispReadException(what + " takes method implementations");
			}
			groups.add(new ImplGroup(protocol, methods));
		}
		return groups;
	}

	/**
	 * One method-implementation row: the lambda (with the usual destructuring prologue)
	 * stored in the protocol's table under the target's tag. Inline
	 * {@code defrecord}/{@code deftype} bodies see the fields as locals bound from the
	 * instance table -- an outer scope, so an explicit parameter shadows its field, like
	 * the oracle.
	 */
	private LispVal methodRow(String protocol, LispVal key, TypeMethod impl, @Nullable List<String> fields) {
		ProtocolDef def = this.protocols.get(protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		LispVal lambda;
		if (fields == null) {
			lambda = methodLambda(impl.params(), impl.body());
		}
		else {
			Map<String, Kind> scope = new HashMap<>();
			for (String field : fields) {
				scope.put(field, Kind.VARIABLE);
			}
			lambda = inScope(scope, () -> {
				String fresh = freshRecurName();
				String worker = workerName(fresh);
				RecurTarget target = new RecurTarget(isVariadicParams(impl.params()) ? worker : fresh, true);
				Clause clause = clause(impl.params(), impl.body(), target);
				LispVal inner = list(sym("lambda"), list(clause.params()), clause.wrapped());
				if (fields.isEmpty()) {
					if (target.used() && clause.variadic()) {
						return splitMethodLambda(fresh, worker, clause, clause.wrapped());
					}
					return target.used() ? labelsSelfCall(fresh, inner) : inner;
				}
				LispVal self = clause.params().isEmpty() ? NIL_CONST : clause.params().get(0);
				List<LispVal> binds = new ArrayList<>();
				for (String field : fields) {
					binds.add(list(idSym(field),
							list(sym("gethash"), keywordForm(field), typedTableOf(self), NIL_CONST)));
				}
				LispVal fieldBody = list(sym("let*"), list(binds), clause.wrapped());
				LispVal withFields = list(sym("lambda"), list(clause.params()), fieldBody);
				if (target.used() && clause.variadic()) {
					return splitMethodLambda(fresh, worker, clause, fieldBody);
				}
				return target.used() ? labelsSelfCall(fresh, withFields) : withFields;
			});
		}
		return methodStoreForm(def.methodsVar(), key, impl.method(), lambda);
	}

	/**
	 * {@code (defrecord Name [fields] Protocol (method [target & args] body...)...
	 * opts?)}: the positional and map constructors plus one table row per inline method.
	 * A trailing keyword names an unsupported option, like {@code defprotocol}'s.
	 */
	private List<LispVal> recordTypeForms(List<LispVal> items) {
		boolean record = isSymbolNamed(items.get(0), "defrecord");
		String what = record ? "defrecord" : "deftype";
		isTrue(items.size() >= 3, what + " takes a name and fields");
		String name = plainName(items.get(1), what);
		TypeDef def = declareRecordType(items);
		List<LispVal> rest = new ArrayList<>(items.subList(3, items.size()));
		for (LispVal datum : rest) {
			isTrue(!(datum instanceof LispSymbol s && s.name().startsWith(":")),
					what + " option " + datum.print() + " is not supported yet");
		}
		List<ImplGroup> groups = implGroups(rest, what);
		List<LispVal> forms = new ArrayList<>();
		forms.add(positionalCtor(name, def));
		if (record) {
			forms.add(mapCtor(name, def));
		}
		for (ImplGroup group : groups) {
			for (TypeMethod impl : group.methods()) {
				forms.add(methodRow(group.protocol(), typeTagForm(name), impl, def.fields()));
			}
		}
		this.usedProtocols = true;
		return forms;
	}

	/**
	 * The positional constructor: a mangled {@code defun} over the fields, so a wrong
	 * count signals like any other call. The field keywords travel twice (the declared
	 * list for {@code dissoc}'s keep-type rule, the table for the map verbs).
	 */
	private LispVal positionalCtor(String name, TypeDef def) {
		List<LispVal> params = new ArrayList<>();
		List<LispVal> pairs = new ArrayList<>();
		List<LispVal> keys = new ArrayList<>();
		for (String field : def.fields()) {
			params.add(idSym(field));
			LispVal key = keywordForm(field);
			keys.add(key);
			pairs.add(key);
			pairs.add(idSym(field));
		}
		LispVal table = tableFromPlist(cons(sym("list"), pairs));
		LispVal value = def.record() ? wrapRecord(typeTagForm(name), cons(sym("list"), keys), table)
				: wrapDeftype(typeTagForm(name), cons(sym("list"), keys), table);
		return list(sym("defun"), idSym("->" + name), list(params), value);
	}

	/**
	 * The map constructor (records only -- the oracle defines none for deftypes): the
	 * entries copied out of the argument (nil builds empty, a record contributes its
	 * entries), missing fields defaulting to nil, extra entries kept, like the oracle.
	 */
	private LispVal mapCtor(String name, TypeDef def) {
		LispSymbol src = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol pairs = freshTemp();
		LispSymbol miss = freshTemp();
		List<LispVal> keys = new ArrayList<>();
		for (String field : def.fields()) {
			keys.add(keywordForm(field));
		}
		List<LispVal> fill = new ArrayList<>();
		for (LispVal key : keys) {
			fill.add(list(sym("if"), list(sym("eq"), list(sym("gethash"), key, table, miss), miss),
					list(sym("setf"), list(sym("gethash"), key, table), NIL_CONST), NIL_CONST));
		}
		fill.add(wrapRecord(typeTagForm(name), cons(sym("list"), keys), table));
		LispVal whole = list(sym("let*"), list(List.of(list(src, src),
				list(pairs, list(sym("if"), src,
						list(sym("if"), isRecordForm(src), tablePlist(typedTableOf(src)), tablePlist(src)), NIL_CONST)),
				list(miss, list(sym("list"), NIL_CONST)))),
				list(sym("let"), list(List.of(list(table, tableFromPlist(pairs)))), cons(sym("progn"), fill)));
		return list(sym("defun"), idSym("map->" + name), list(List.of(src)), whole);
	}

	/**
	 * Maps an {@code extend} target name to its dispatch-key form, or null for
	 * {@code Object} (the default row). Record and deftype names answer their tags; host
	 * kinds answer the {@code class} keyword spelling, so dispatch agrees with
	 * {@code class}; anything else (a {@code java.time.Instant}, a {@code Date}, ...) is
	 * a named refusal.
	 */
	private @Nullable LispVal extendKeyForm(String typeName, String what) {
		if (typeName.equals("Object")) {
			return null;
		}
		if (typeName.equals("nil")) {
			return keywordForm("nil");
		}
		if (this.types.containsKey(typeName)) {
			return typeTagForm(typeName);
		}
		String kind = switch (typeName) {
			case "String", "CharSequence" -> "string";
			case "Number", "Long", "Double", "Integer", "Float", "Short", "Byte" -> "number";
			case "Boolean" -> "boolean";
			case "Keyword" -> "keyword";
			case "Symbol" -> "symbol";
			case "Character", "Char" -> "char";
			case "Map", "IPersistentMap" -> "map";
			case "Vector", "IPersistentVector" -> "vector";
			case "Set", "IPersistentSet" -> "set";
			case "List", "Seq", "Sequential", "Collection", "IPersistentList", "IPersistentCollection" -> "list";
			case "Fn", "IFn", "Function" -> "function";
			case "Atom" -> "atom";
			default -> null;
		};
		if (kind == null) {
			throw new LispReadException(what + " needs a core type, not " + typeName);
		}
		return keywordForm(kind);
	}

	/**
	 * Stores one extension row: under the target's tag, or (for {@code Object}) the
	 * protocol's default global consulted on a miss. The method must belong to the
	 * protocol, like the inline implementations.
	 */
	private LispVal extendRow(String protocol, @Nullable LispVal key, TypeMethod impl, String what) {
		ProtocolDef def = this.protocols.get(protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		isTrue(def.methods().contains(impl.method()), "Can't define method not in interfaces: " + impl.method());
		LispVal lambda = methodLambda(impl.params(), impl.body());
		if (key == null) {
			return list(sym("setq"), def.defaultVar(), lambda);
		}
		return methodStoreForm(def.methodsVar(), key, impl.method(), lambda);
	}

	/**
	 * {@code (extend-protocol P Type (method [target & args] body...)+ ...)}: one row per
	 * method per type, like {@code defmethod} rows. The type may repeat (later rows win,
	 * like the oracle).
	 */
	private LispVal extendProtocolForm(List<LispVal> items) {
		isTrue(items.size() >= 3, "extend-protocol takes a protocol, a type and methods");
		isTrue(items.get(1) instanceof LispSymbol, "extend-protocol takes a protocol name");
		String protocol = ((LispSymbol) items.get(1)).name();
		if (!this.protocols.containsKey(protocol)) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		List<LispVal> rows = new ArrayList<>();
		int at = 2;
		while (at < items.size()) {
			isTrue(items.get(at) instanceof LispSymbol, "extend-protocol takes a type name");
			String target = ((LispSymbol) items.get(at)).name();
			LispVal key = extendKeyForm(target, "extend-protocol");
			at++;
			while (at < items.size() && !(items.get(at) instanceof LispSymbol)) {
				List<LispVal> impl = items(items.get(at));
				if (impl == null || impl.size() < 2 || !(impl.get(0) instanceof LispSymbol)) {
					throw new LispReadException("a method implementation takes a name, parameters and a body, not "
							+ items.get(at).print());
				}
				String method = ((LispSymbol) impl.get(0)).name();
				isTrue(isVectorDatum(stripMeta(impl.get(1))),
						"multi-arity protocol methods are not supported yet: " + method);
				List<LispVal> params = bindingItems(stripMeta(impl.get(1)), "the method " + method + " of");
				isTrue(!params.isEmpty(), "a protocol method takes a target and arguments: " + method);
				rows.add(extendRow(protocol, key, new TypeMethod(method, impl.get(1), impl.subList(2, impl.size())),
						"extend-protocol"));
				at++;
			}
		}
		this.usedProtocols = true;
		if (rows.isEmpty()) {
			return NIL_CONST;
		}
		return cons(sym("progn"), rows);
	}

	/**
	 * {@code (extend-type T Protocol (method [target & args] body...)+ ...)}: the same
	 * rows, grouped under protocol names. A bare method group (no protocol) is refused:
	 * there is no interface to check it against.
	 */
	private LispVal extendTypeForm(List<LispVal> items) {
		isTrue(items.size() >= 3, "extend-type takes a type, a protocol and methods");
		isTrue(items.get(1) instanceof LispSymbol, "extend-type takes a type name");
		String target = ((LispSymbol) items.get(1)).name();
		LispVal key = extendKeyForm(target, "extend-type");
		List<ImplGroup> groups = implGroups(items.subList(2, items.size()), "extend-type");
		List<LispVal> rows = new ArrayList<>();
		for (ImplGroup group : groups) {
			for (TypeMethod impl : group.methods()) {
				rows.add(extendRow(group.protocol(), key, impl, "extend-type"));
			}
		}
		this.usedProtocols = true;
		if (rows.isEmpty()) {
			return NIL_CONST;
		}
		return cons(sym("progn"), rows);
	}

	/**
	 * {@code (extend T Protocol {method fn ...})}: the same rows from a map literal of
	 * method functions. Anything but a literal map is refused (there is nothing to walk
	 * at lower time).
	 */
	private LispVal extendForm(List<LispVal> items) {
		isTrue(items.size() == 4, "extend takes a type, a protocol and a map of methods");
		isTrue(items.get(1) instanceof LispSymbol, "extend takes a type name");
		isTrue(items.get(2) instanceof LispSymbol, "extend takes a protocol name");
		String target = ((LispSymbol) items.get(1)).name();
		String protocol = ((LispSymbol) items.get(2)).name();
		ProtocolDef def = this.protocols.get(protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		List<LispVal> entries = items(items.get(3));
		if (entries == null || entries.isEmpty() || !isSymbolNamed(entries.get(0), "%hash-map")
				|| entries.size() % 2 == 0) {
			throw new LispReadException("extend takes a map literal of methods, not " + items.get(3).print());
		}
		LispVal key = extendKeyForm(target, "extend");
		List<LispVal> rows = new ArrayList<>();
		for (int i = 1; i < entries.size(); i += 2) {
			isTrue(entries.get(i) instanceof LispSymbol k && k.name().startsWith(":"),
					"extend takes keyword method names, not " + entries.get(i).print());
			String method = ((LispSymbol) entries.get(i)).name().substring(1);
			isTrue(def.methods().contains(method), "Can't define method not in interfaces: " + method);
			LispVal fun = fnValue(entries.get(i + 1));
			LispVal row = key == null ? list(sym("setq"), def.defaultVar(), fun)
					: methodStoreForm(def.methodsVar(), key, method, fun);
			rows.add(row);
		}
		this.usedProtocols = true;
		if (rows.isEmpty()) {
			return NIL_CONST;
		}
		return cons(sym("progn"), rows);
	}

	/**
	 * {@code (satisfies? Protocol x)}: table membership -- the tag's row, or the
	 * {@code Object} row an extension installed, like the oracle. The protocol is a
	 * literal name, like {@code defmethod}'s multimethod.
	 */
	private LispVal satisfiesOf(List<LispVal> items) {
		isTrue(items.size() == 3, "satisfies? takes a protocol and a value");
		isTrue(items.get(1) instanceof LispSymbol, "satisfies? takes a protocol name");
		String protocol = ((LispSymbol) items.get(1)).name();
		ProtocolDef def = this.protocols.get(protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		LispSymbol tag = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol inner = freshTemp();
		LispVal hit = list(sym("not"), list(sym("eq"), inner, miss));
		LispVal answer = list(sym("if"), hit, TRUE_CONST,
				list(sym("if"), list(sym("null"), def.defaultVar()), this.falseVariable, TRUE_CONST));
		this.usedProtocols = true;
		return list(sym("let*"),
				list(List.of(list(tag, list(new LispSymbol(PROTOCOL_TAG), lower(items.get(2)))),
						list(miss, list(sym("list"), NIL_CONST)),
						list(inner, list(sym("gethash"), tag, def.methodsVar(), miss)))),
				answer);
	}

	/**
	 * {@code (reify Protocol (method [target & args] body...)+ ...)}: one fresh tag per
	 * evaluation with a row per method in each protocol's table, answering the opaque
	 * value -- a single-shot map plus methods (never {@code proxy}, which stays the
	 * {@code java:} surface).
	 */
	private LispVal reifyForm(List<LispVal> items) {
		isTrue(items.size() >= 2, "reify takes a protocol and methods");
		List<ImplGroup> groups = implGroups(items.subList(1, items.size()), "reify");
		LispSymbol self = freshTemp();
		List<LispVal> prologue = new ArrayList<>();
		prologue.add(list(self, list(sym("list"), REIFY_TAG, list(sym("gensym"), LispString.literal("reify")))));
		List<LispVal> body = new ArrayList<>();
		for (ImplGroup group : groups) {
			ProtocolDef def = this.protocols.get(group.protocol());
			if (def == null) {
				throw new LispReadException("No such protocol: " + group.protocol());
			}
			for (TypeMethod impl : group.methods()) {
				LispVal lambda = methodLambda(impl.params(), impl.body());
				body.add(methodStoreForm(def.methodsVar(), list(sym("cadr"), self), impl.method(), lambda));
			}
		}
		body.add(self);
		this.usedProtocols = true;
		return list(sym("let*"), list(prologue), cons(sym("progn"), body));
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
	private static final Set<String> MACRO_RESERVED = Set.of("quote", "def", "defn", "defn-", "defonce", "defstruct",
			"struct", "struct-map", "defmacro", "fn", "let", "letfn", "loop", "declare", "->", "->>", "as->", "doto",
			"cond->", "cond->>", "some->", "some->>", "list*", "doseq", "dotimes", "for", "defmulti", "defmethod",
			"remove-method", "get-method", "prefer-method", "derive", "underive", "isa?", "parents", "ancestors",
			"descendants", "make-hierarchy", "defprotocol", "defrecord", "deftype", "definterface", "reify",
			"extend-protocol", "extend-type", "extend", "satisfies?", "gen-class", "gen-interface", "try", "throw",
			"ex-info", "ex-data", "ex-message", "atom", "deref", "swap!", "reset!", "compare-and-set!", "volatile!",
			"vreset!", "vswap!", "add-watch", "remove-watch", "ref", "dosync", "alter", "commute", "ref-set", "ensure",
			"agent", "send", "send-off", "await", "shutdown-agents", "binding", "with-open", "with-out-str", "time",
			"comment", "require", "use", "import", "in-ns", "set!", "memfn", "proxy", "new", "syntax-quote", "unquote",
			"unquote-splicing", "var", "with-meta", "if", "when", "cond", "do", "recur", ".", "..", "gensym",
			"macroexpand-1", "macroexpand");

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
		if (!marked.isEmpty() && (marked.get(0) == ClojureReader.REGEX || isSymbolNamed(marked.get(0), "%regex"))) {
			return regexForm(marked);
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
			return keywordForm(resolveKeywordSpelling(name));
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
		return list(sym("progn"),
				list(sym("setf"), list(sym("gethash"),
						list(sym("cons"), dispatchKeyForm(items.get(2)), dispatchKeyForm(items.get(3))), prefers),
						TRUE_CONST),
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
				return fieldRead(lower(items.get(1)), name.substring(2));
			}
			isTrue(name.length() > 1, "a method call needs a method name");
			isTrue(items.size() >= 2, name + " takes a target and arguments");
			return instanceCall(lower(items.get(1)), name.substring(1), items.subList(2, items.size()));
		}
		if (name.endsWith(".") && name.length() > 1 && isClassSpelling(name.substring(0, name.length() - 1))) {
			String base = name.substring(0, name.length() - 1);
			if (this.types.containsKey(base)) {
				// (T. args...) constructs the record or deftype, like ->T
				return lower(cons(new LispSymbol("->" + base), items.subList(1, items.size())));
			}
			List<LispVal> args = new ArrayList<>();
			args.add(LispString.literal(resolveClass(name.substring(0, name.length() - 1))));
			args.addAll(lowers(items, 1));
			return cons(JAVA_NEW, args);
		}
		int slash = name.indexOf('/');
		if (slash > 0) {
			String head = name.substring(0, slash);
			String tail = name.substring(slash + 1);
			if (!tail.isEmpty() && tail.indexOf('/') < 0 && isClasslike(head) && !this.types.containsKey(head)) {
				String cls = resolveClass(head);
				if (items.size() == 1) {
					// no arguments: the zero-argument static method when the host
					// class has one, else the static field read (whose run-time
					// error names an unknown member or class, like before)
					return staticNoArg(cls, tail);
				}
				List<LispVal> args = new ArrayList<>();
				args.addAll(lowers(items, 1));
				return staticCall(cls, tail, args);
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
			if (argDatums.isEmpty()) {
				// no arguments: the zero-argument static method when the host
				// class has one, else the static field read -- the same rule the
				// (Class/member) spelling follows, so (. Math PI) reads the field
				return staticNoArg(resolveClass(named.name()), method);
			}
			List<LispVal> lowered = new ArrayList<>();
			for (LispVal arg : argDatums) {
				lowered.add(lower(arg));
			}
			return staticCall(resolveClass(named.name()), method, lowered);
		}
		return instanceCall(lower(target), method, argDatums);
	}

	/**
	 * {@code (.. target (step args...) name...)}: the steps threaded left to right, each
	 * lowered as it goes so a step's declared return type carries the known class to the
	 * next one. The first step over a classlike target stays the static path, like
	 * {@code .}; the rest are instance calls.
	 */
	private LispVal dotDotOf(List<LispVal> items) {
		isTrue(items.size() >= 2, ".. takes a target and steps");
		LispVal target = items.get(1);
		if (target instanceof LispSymbol named && isClasslike(named.name())) {
			if (items.size() == 2) {
				return lower(target);
			}
			String cls = resolveClass(named.name());
			DotStep first = dotStep(items.get(2));
			List<LispVal> loweredFirst = new ArrayList<>();
			for (LispVal arg : first.args()) {
				loweredFirst.add(lower(arg));
			}
			LispVal cur = loweredFirst.isEmpty() ? staticNoArg(cls, first.method())
					: staticCall(cls, first.method(), loweredFirst);
			@Nullable String curClass = declaredStaticReturn(cls, first.method(), loweredFirst.size());
			for (int i = 3; i < items.size(); i++) {
				DotStep step = dotStep(items.get(i));
				List<LispVal> loweredArgs = new ArrayList<>();
				for (LispVal arg : step.args()) {
					loweredArgs.add(lower(arg));
				}
				cur = instanceCallLoweredWithClass(cur, curClass, step.method(), loweredArgs);
				curClass = declaredInstanceReturn(curClass, step.method(), loweredArgs.size());
			}
			return cur;
		}
		LispVal cur = lower(target);
		@Nullable String curClass = classOfLowered(cur);
		for (int i = 2; i < items.size(); i++) {
			DotStep step = dotStep(items.get(i));
			List<LispVal> loweredArgs = new ArrayList<>();
			for (LispVal arg : step.args()) {
				loweredArgs.add(lower(arg));
			}
			cur = instanceCallLoweredWithClass(cur, curClass, step.method(), loweredArgs);
			curClass = declaredInstanceReturn(curClass, step.method(), loweredArgs.size());
		}
		return cur;
	}

	/** A {@code ..} step's method name and argument datums. */
	private record DotStep(String method, List<LispVal> args) {
	}

	/** Parses a {@code ..} step datum into its method name and argument datums. */
	private static DotStep dotStep(LispVal step) {
		List<LispVal> parts = items(step);
		if (parts != null && !parts.isEmpty()) {
			if (!(parts.get(0) instanceof LispSymbol called)) {
				throw new LispReadException(".. takes method names and call lists, not " + step.print());
			}
			return new DotStep(called.name(), parts.subList(1, parts.size()));
		}
		if (step instanceof LispSymbol bare) {
			return new DotStep(bare.name(), List.of());
		}
		throw new LispReadException(".. takes method names and call lists, not " + step.print());
	}

	/**
	 * What the host class says about a static member: a static field, the fixed arities
	 * of its non-variadic static methods, the subset answering a boolean, and whether a
	 * variadic one exists.
	 */
	private record StaticMember(boolean field, List<Integer> arities, Set<Integer> booleanArities, boolean variadic) {
	}

	/**
	 * Reads the host class once: whether {@code member} is a public static field, the
	 * sorted distinct fixed arities of its public static non-variadic methods, the subset
	 * whose overloads all answer a boolean, and whether a variadic one exists. An
	 * unloadable class answers all absent, so the call sites keep their old shape and the
	 * run-time error names the class.
	 */
	private static StaticMember staticMember(String className, String member) {
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			boolean field = false;
			try {
				field = java.lang.reflect.Modifier.isStatic(found.getField(member).getModifiers());
			}
			catch (NoSuchFieldException _) {
				// no field of that name: the methods decide below
			}
			Set<Integer> arities = new HashSet<>();
			Map<Integer, Boolean> booleanByArity = new HashMap<>();
			boolean variadic = false;
			for (java.lang.reflect.Method method : found.getMethods()) {
				if (!method.getName().equals(member) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
						|| method.isSynthetic()) {
					continue;
				}
				if (method.isVarArgs()) {
					variadic = true;
				}
				else {
					int fixed = method.getParameterCount();
					arities.add(fixed);
					booleanByArity.merge(fixed, method.getReturnType() == Boolean.TYPE, (a, b) -> a && b);
				}
			}
			List<Integer> sorted = new ArrayList<>(arities);
			sorted.sort(Integer::compareTo);
			Set<Integer> booleanArities = new HashSet<>();
			for (Map.Entry<Integer, Boolean> entry : booleanByArity.entrySet()) {
				if (entry.getValue()) {
					booleanArities.add(entry.getKey());
				}
			}
			return new StaticMember(field, sorted, booleanArities, variadic);
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return new StaticMember(false, List.of(), Set.of(), false);
		}
	}

	/**
	 * A static call through {@code java:static}: a boolean answer is {@code T}-or-false
	 * when every overload at that arity answers a boolean, like every predicate value.
	 */
	private LispVal staticCall(String cls, String member, List<LispVal> args) {
		List<LispVal> call = new ArrayList<>();
		call.add(LispString.literal(cls));
		call.add(LispString.literal(member));
		call.addAll(args);
		LispVal run = cons(JAVA_STATIC, call);
		if (staticMember(cls, member).booleanArities().contains(args.size())) {
			return booleanAnswer(run);
		}
		return run;
	}

	/**
	 * {@code (Class/member)} or {@code (. Class member)} with no arguments: the
	 * zero-argument static method when the host class has one (a static call through
	 * {@code java:static}), else the static field read through {@code java:field} (whose
	 * run-time error names an unknown member or class, like before). The method wins a
	 * field of the same name, like the oracle's unified resolution; a boolean answer is
	 * {@code T}-or-false, like every predicate value.
	 */
	private LispVal staticNoArg(String cls, String member) {
		List<LispVal> args = new ArrayList<>();
		args.add(LispString.literal(cls));
		args.add(LispString.literal(member));
		StaticMember seen = staticMember(cls, member);
		if (seen.arities().contains(0)) {
			LispVal call = cons(JAVA_STATIC, args);
			return seen.booleanArities().contains(0) ? booleanAnswer(call) : call;
		}
		return cons(JAVA_FIELD, args);
	}

	/**
	 * A {@code Class/member} name in value position: the static field read when the host
	 * class has that field, else a member-as-value lambda dispatching by argument count
	 * over the static call (so {@code (every? Character/isWhitespace s)} runs). A member
	 * with only variadic overloads is refused by name (no rest-spread reaches
	 * {@code java:static}); an unknown class or member reads the field, whose run-time
	 * error names what is missing. Null when the name is no classlike slash form, so the
	 * call keeps falling through to the unknown-name refusal.
	 */
	private @Nullable LispVal interopValue(String name) {
		int slash = name.indexOf('/');
		if (slash <= 0) {
			return null;
		}
		String head = name.substring(0, slash);
		String tail = name.substring(slash + 1);
		if (tail.isEmpty() || tail.indexOf('/') >= 0 || !isClasslike(head) || this.types.containsKey(head)) {
			return null;
		}
		String cls = resolveClass(head);
		StaticMember seen = staticMember(cls, name.substring(slash + 1));
		if (seen.field()) {
			return cons(JAVA_FIELD, List.of(LispString.literal(cls), LispString.literal(tail)));
		}
		if (!seen.arities().isEmpty()) {
			return memberLambda(cls, tail, name, seen);
		}
		if (seen.variadic()) {
			throw new LispReadException(name + " is variadic and has no value form");
		}
		return cons(JAVA_FIELD, List.of(LispString.literal(cls), LispString.literal(tail)));
	}

	/**
	 * The member-as-value lambda: one {@code &rest} parameter dispatched per known fixed
	 * arity onto the static call (the run-time overload selection picks among same-arity
	 * overloads), any other count the wrong-argument-count error, like a multi-arity
	 * {@code defn} dispatch. A boolean answer is {@code T}-or-false, like every predicate
	 * value, so {@code (map Character/isWhitespace ...)} prints {@code (true false)}.
	 */
	private LispVal memberLambda(String cls, String member, String spelling, StaticMember seen) {
		LispSymbol args = freshTemp();
		LispSymbol count = freshTemp();
		List<LispVal> arms = new ArrayList<>();
		for (int arity : seen.arities()) {
			List<LispVal> call = new ArrayList<>();
			call.add(LispString.literal(cls));
			call.add(LispString.literal(member));
			for (int p = 0; p < arity; p++) {
				call.add(list(sym("NTH"), new LispInteger(p), args));
			}
			LispVal run = cons(JAVA_STATIC, call);
			if (seen.booleanArities().contains(arity)) {
				run = booleanAnswer(run);
			}
			arms.add(list(list(sym("="), count, new LispInteger(arity)), run));
		}
		arms.add(list(TRUE_CONST,
				list(sym("error"), LispString.literal("wrong number of arguments passed to: " + spelling))));
		return list(sym("lambda"), list(List.of(AMPERSAND_REST, args)),
				list(sym("let"), list(List.of(list(count, list(sym("length"), args)))), cons(sym("cond"), arms)));
	}

	/** {@code (new Class args...)}: construction through {@code java:new}. */
	private LispVal newOf(List<LispVal> items) {
		isTrue(items.size() >= 2, "new takes a class and arguments");
		if (!(items.get(1) instanceof LispSymbol named)) {
			throw new LispReadException("new takes a class name, not " + items.get(1).print());
		}
		if (this.types.containsKey(named.name())) {
			// (new T args...) constructs the record or deftype, like ->T
			return lower(cons(new LispSymbol("->" + named.name()), items.subList(2, items.size())));
		}
		List<LispVal> args = new ArrayList<>();
		args.add(LispString.literal(resolveClass(named.name())));
		args.addAll(lowers(items, 2));
		return cons(JAVA_NEW, args);
	}

	/**
	 * {@code (make-array Class dim...)}: a general array over the dimensions -- the class
	 * spells the element type and is ignored, every array here is general (the book's
	 * {@code interop.clj} {@code painstakingly-create-array} shape). One dimension is the
	 * scalar, several the dimension list, like the oracle's separate-argument shape; only
	 * the Clojure spellings are new, the array itself compiles on all four backends.
	 */
	private LispVal makeArrayOf(List<LispVal> items) {
		isTrue(items.size() >= 3, "make-array takes a class and dimensions");
		if (!(items.get(1) instanceof LispSymbol)) {
			throw new LispReadException("make-array takes a class name, not " + items.get(1).print());
		}
		List<LispVal> dims = lowers(items, 2);
		LispVal shape = dims.size() == 1 ? dims.get(0) : cons(sym("list"), dims);
		return list(sym("make-array"), shape);
	}

	/** {@code (aget array index...)}: the element, through {@code aref}. */
	private LispVal agetOf(List<LispVal> items) {
		isTrue(items.size() >= 3, "aget takes an array and subscripts");
		List<LispVal> ref = new ArrayList<>();
		ref.add(sym("aref"));
		ref.addAll(lowers(items, 1));
		return list(ref);
	}

	/** {@code (aset array index... value)}: the write, through {@code (setf aref)}. */
	private LispVal asetOf(List<LispVal> items) {
		isTrue(items.size() >= 4, "aset takes an array, subscripts and a value");
		List<LispVal> ref = new ArrayList<>();
		ref.add(sym("aref"));
		for (int i = 1; i < items.size() - 1; i++) {
			ref.add(lower(items.get(i)));
		}
		return list(sym("setf"), list(ref), lower(items.get(items.size() - 1)));
	}

	/** {@code (alength array)}: the zeroth dimension, through {@code array-dimension}. */
	private LispVal alengthOf(List<LispVal> items) {
		isTrue(items.size() == 2, "alength takes an array");
		return list(sym("array-dimension"), lower(items.get(1)), new LispInteger(0));
	}

	/**
	 * {@code (.-field target)}: a record or deftype answers its field table's entry
	 * (missing fields signal, like the oracle); anything else takes the host field path,
	 * like before.
	 */
	private LispVal fieldRead(LispVal target, String field) {
		LispSymbol one = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol got = freshTemp();
		LispVal table = list(sym("if"), isReifyForm(one), NIL_CONST, typedTableOf(one));
		LispVal read = list(sym("let"), list(List.of(list(got, list(sym("gethash"), keywordForm(field), table, miss)))),
				list(sym("if"), list(sym("eq"), got, miss),
						list(sym("error"), LispString.literal("No such field: " + field)), got));
		return list(sym("let"), list(List.of(list(one, target), list(miss, list(sym("list"), NIL_CONST)))),
				list(sym("if"), isTypedForm(one), read, cons(JAVA_FIELD, List.of(one, LispString.literal(field)))));
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
	 * to {@code java:call} directly. When the receiver's class is known -- a construction
	 * literal, a {@code let}/{@code if-let}/{@code when-let} local bound to one, or a
	 * {@code ..} step's declared return -- and every overload at that arity answers a
	 * primitive boolean, the call answers {@code T}-or-false, like every predicate value
	 * (the shared {@code java:} unmarshal still maps a host false to nil underneath); any
	 * other receiver keeps the unmarshal.
	 */
	private LispVal instanceCallLowered(LispVal receiver, String method, List<LispVal> args) {
		return instanceCallLoweredWithClass(receiver, null, method, args);
	}

	/**
	 * An instance call with an explicit known receiver class (a {@code ..} step's
	 * declared return): null to read it off the receiver instead, like
	 * {@link #instanceCallLowered}.
	 */
	private LispVal instanceCallLoweredWithClass(LispVal receiver, @Nullable String knownClass, String method,
			List<LispVal> args) {
		LispSymbol recv = freshTemp();
		List<LispVal> direct = new ArrayList<>();
		direct.add(recv);
		direct.add(LispString.literal(method));
		direct.addAll(args);
		LispVal call = cons(JAVA_CALL, direct);
		String cls = knownClass;
		if (cls == null) {
			cls = constructedClass(receiver);
			if (cls == null && receiver instanceof LispSymbol ref) {
				cls = hostClassOf(ref);
			}
		}
		if (cls != null && instanceBooleanAtArity(cls, method, args.size())) {
			call = booleanAnswer(call);
		}
		LispVal stream = streamMethod(method, recv, args);
		if (stream != null) {
			call = list(sym("if"), list(sym("streamp"), recv), stream, call);
		}
		LispVal mapped = stringMethod(method, recv, args);
		LispVal out = mapped == null ? call : list(sym("if"), list(sym("stringp"), recv), mapped, call);
		return list(sym("let"), list(List.of(list(recv, receiver))), out);
	}

	/**
	 * The class a lowered receiver constructs, when it is a construction literal: a
	 * {@code java:new} over a literal class name. No user form lowers to that head
	 * (anything else spelling it is an unknown name), so the class is read off the call
	 * site with no scope analysis.
	 */
	private static @Nullable String constructedClass(LispVal receiver) {
		if (receiver instanceof LispCons cell && isSymbolNamed(cell.car(), "JAVA:NEW")
				&& cell.cdr() instanceof LispCons rest && rest.car() instanceof LispString cls) {
			return cls.value();
		}
		return null;
	}

	/**
	 * A {@code let}-bound name's host class, or null when no visible binding holds one: a
	 * binding above the recording depth shadows it, like any other scope rule, and
	 * anything else was never recorded.
	 */
	private @Nullable String hostClassOf(LispSymbol ref) {
		HostClass held = this.hostClasses.get(ref.name());
		if (held == null) {
			return null;
		}
		for (int i = held.depth(); i < this.scopes.size(); i++) {
			if (this.scopes.get(i).containsKey(held.name())) {
				return null;
			}
		}
		return held.fqn();
	}

	/**
	 * Whether every fixed-arity overload of {@code member} at {@code arity} on the host
	 * class answers a primitive boolean: the instance-call half of the static
	 * {@code T}-or-false rule ({@link #staticMember}). A {@code java:call} may reach a
	 * static through an instance, so statics count too; a boxed answer never qualifies
	 * (it may be null, which the oracle reads as nil, not false). An unloadable class
	 * answers false, so the call keeps its old shape and the run-time error names the
	 * class.
	 */
	private static boolean instanceBooleanAtArity(String className, String member, int arity) {
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			boolean seen = false;
			for (java.lang.reflect.Method method : found.getMethods()) {
				if (!method.getName().equals(member) || method.isSynthetic() || method.isVarArgs()
						|| method.getParameterCount() != arity) {
					continue;
				}
				if (method.getReturnType() != Boolean.TYPE) {
					return false;
				}
				seen = true;
			}
			return seen;
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return false;
		}
	}

	/**
	 * A lowered receiver's known host class, when it is one: a construction literal or a
	 * recorded local. A {@code ..} step's {@code let} wrapper is never one -- the chain
	 * threads the declared return instead.
	 */
	private @Nullable String classOfLowered(LispVal receiver) {
		String cls = constructedClass(receiver);
		if (cls == null && receiver instanceof LispSymbol ref) {
			cls = hostClassOf(ref);
		}
		return cls;
	}

	/**
	 * A {@code ..} step's receiver class for the next step: the single declared return
	 * type shared by every non-variadic overload at that arity, or null when there is
	 * none, several disagree, or it is no host class (a primitive, void, an array, or an
	 * unloadable class). Primitives stay unknown: a boolean answer is already a Lisp
	 * value, so no further host call wraps it.
	 */
	private static @Nullable String declaredInstanceReturn(@Nullable String className, String member, int arity) {
		if (className == null) {
			return null;
		}
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			Set<String> returns = new HashSet<>();
			boolean seen = false;
			for (java.lang.reflect.Method method : found.getMethods()) {
				if (!method.getName().equals(member) || method.isSynthetic() || method.isVarArgs()
						|| method.getParameterCount() != arity) {
					continue;
				}
				seen = true;
				Class<?> ret = method.getReturnType();
				if (ret.isPrimitive() || ret == Void.TYPE || ret.isArray()) {
					return null;
				}
				returns.add(ret.getName());
			}
			return seen && returns.size() == 1 ? returns.iterator().next() : null;
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return null;
		}
	}

	/**
	 * The same single-return rule for a {@code ..} chain's static first step: the
	 * zero-argument static method's return when one exists, else the static field's type,
	 * else null. Anything else keeps the chain unknown, like an instance step with no
	 * single return.
	 */
	private static @Nullable String declaredStaticReturn(String className, String member, int arity) {
		try {
			Class<?> found = Class.forName(className, false, ClojureLowering.class.getClassLoader());
			if (arity == 0) {
				Set<String> returns = new HashSet<>();
				boolean seen = false;
				for (java.lang.reflect.Method method : found.getMethods()) {
					if (!method.getName().equals(member) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
							|| method.isSynthetic() || method.isVarArgs() || method.getParameterCount() != 0) {
						continue;
					}
					seen = true;
					Class<?> ret = method.getReturnType();
					if (ret.isPrimitive() || ret == Void.TYPE || ret.isArray()) {
						return null;
					}
					returns.add(ret.getName());
				}
				if (seen) {
					return returns.size() == 1 ? returns.iterator().next() : null;
				}
				try {
					java.lang.reflect.Field field = found.getField(member);
					if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
						Class<?> type = field.getType();
						return type.isPrimitive() || type.isArray() ? null : type.getName();
					}
				}
				catch (NoSuchFieldException _) {
					// no field either: unknown, like an unknown member
				}
				return null;
			}
			Set<String> returns = new HashSet<>();
			boolean seen = false;
			for (java.lang.reflect.Method method : found.getMethods()) {
				if (!method.getName().equals(member) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
						|| method.isSynthetic() || method.isVarArgs() || method.getParameterCount() != arity) {
					continue;
				}
				seen = true;
				Class<?> ret = method.getReturnType();
				if (ret.isPrimitive() || ret == Void.TYPE || ret.isArray()) {
					return null;
				}
				returns.add(ret.getName());
			}
			return seen && returns.size() == 1 ? returns.iterator().next() : null;
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return null;
		}
	}

	/**
	 * A stream method over an already-bound receiver: {@code write} prints through
	 * {@code princ} (strings bare, characters as glyphs), {@code flush} finishes the
	 * output, {@code readLine} reads through {@code read-line} (nil past the end, like
	 * the oracle) and {@code close} closes the stream, so {@code with-open} over a
	 * {@code clojure.java.io/reader} (an {@code open} file stream) runs on every backend
	 * without reaching {@code java:call}. Null when the method maps to nothing, so the
	 * call goes to {@code java:call}.
	 */
	private @Nullable LispVal streamMethod(String method, LispVal recv, List<LispVal> args) {
		if (method.equals("write") && args.size() == 1) {
			return list(sym("princ"), args.get(0), recv);
		}
		if (method.equals("flush") && args.isEmpty()) {
			return list(sym("finish-output"), recv);
		}
		if (method.equals("readLine") && args.isEmpty()) {
			// nil past the end, like the oracle (a Java reader takes the java:call path)
			return list(sym("read-line"), recv, NIL_CONST, NIL_CONST);
		}
		if (method.equals("close") && args.isEmpty()) {
			return list(sym("close"), recv);
		}
		return null;
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
		for (int i = this.scopes.size() - 1; i >= 0; i--) {
			Kind kind = this.scopes.get(i).get(name);
			if (kind != null) {
				// the innermost binding decides, so a local shadows an outer one
				// (and a global): a parameter holding a collection routes through
				// the dispatcher, a local function stays direct
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
		this.directScopes.add(new HashSet<>());
		try {
			return body.get();
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.directScopes.remove(this.directScopes.size() - 1);
		}
	}

	private enum Kind {

		VARIABLE, FUNCTION, MACRO

	}

	// shape helpers

	private static String plainName(LispVal val, String what) {
		val = stripMeta(val);
		if (val instanceof LispSymbol s && !s.name().startsWith(":") && !s.name().equals("&")) {
			return s.name();
		}
		throw new LispReadException(what + " needs a plain name, not " + val.print());
	}

	/**
	 * A datum with its {@code ^...} metadata dropped: the reader spells
	 * {@code ^:private x} as {@code (with-meta x :private)}, and metadata never affects
	 * dispatch, so every name position unwraps it.
	 */
	private static LispVal stripMeta(LispVal datum) {
		List<LispVal> parts = items(datum);
		while (parts != null && parts.size() == 3 && isSymbolNamed(parts.get(0), "with-meta")) {
			datum = parts.get(1);
			parts = items(datum);
		}
		return datum;
	}

	/**
	 * Whether a {@code ^...} metadata datum declares {@code ^:dynamic}: either the bare
	 * keyword or an attr map holding it, so {@code ^:dynamic} and {@code ^{:dynamic
	 * true}} agree.
	 */
	private static boolean isDynamicMeta(LispVal meta) {
		if (meta instanceof LispSymbol s) {
			return s.name().equals(":dynamic");
		}
		List<LispVal> parts = items(meta);
		if (parts == null || parts.isEmpty() || !isSymbolNamed(parts.get(0), "%hash-map")) {
			return false;
		}
		for (int i = 1; i < parts.size(); i++) {
			if (parts.get(i) instanceof LispSymbol k && k.name().equals(":dynamic")) {
				return true;
			}
		}
		return false;
	}

	/** Whether a name datum carries {@code ^:dynamic} metadata, under any wrapping. */
	private static boolean nameIsDynamic(LispVal nameDatum) {
		List<LispVal> parts = items(nameDatum);
		while (parts != null && parts.size() == 3 && isSymbolNamed(parts.get(0), "with-meta")) {
			if (isDynamicMeta(parts.get(2))) {
				return true;
			}
			nameDatum = parts.get(1);
			parts = items(nameDatum);
		}
		return false;
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
	 * lowers back to the same uninterned symbol. Two dynamic vars are aliases, not
	 * mangled names: {@code *out*} is {@code *standard-output*} (the stream the print
	 * family writes to, already special on every backend), and {@code *agent*} is the
	 * agent var the STM runtime binds while a {@code send} runs.
	 */
	private static LispSymbol idSym(String identifier) {
		if (identifier.startsWith("#:")) {
			return new LispSymbol(identifier);
		}
		if (identifier.equals("*out*")) {
			return new LispSymbol("*STANDARD-OUTPUT*");
		}
		if (identifier.equals("*in*")) {
			return new LispSymbol("*STANDARD-INPUT*");
		}
		if (identifier.equals("*agent*")) {
			return new LispSymbol("C%AGENT");
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
