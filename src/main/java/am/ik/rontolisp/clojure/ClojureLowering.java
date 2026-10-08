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
 * name collides with a core form, a built-in or the reader's markers; a global var's
 * symbol carries its namespace ({@code c%ns/name}, {@code user}'s keeping the bare
 * {@code c%name}), so every namespace has its own vars. {@code defn} is a {@code defun}
 * (the direct call and the tree shaker keep working); a head-position call to a
 * {@code VARIABLE}-kind name (a parameter, a {@code let}/{@code loop} binding, a
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
 * stays a no-copy identity. Laziness is the memoized-thunk wrapper
 * {@code (:C%LAZY cell)}: {@code lazy-seq} builds one over its body (run at most once per
 * object), {@code lazy-cat} nests {@code concat} over per-member wrappers, and
 * {@code repeat}/{@code cycle}/{@code iterate}/{@code repeatedly} build wrapper chains
 * (their finite arities answer strict lists); an end-less {@code range} stays refused by
 * name, and {@code range} with an end builds the strict list. {@code take} steps through
 * one wrapper at a time and
 * {@code drop}/{@code first}/{@code rest}/{@code next}/{@code seq}/
 * {@code cons}/{@code concat}/{@code map}/{@code filter} realize through the same view,
 * so {@code (take 5 (iterate inc 0))} terminates; printing a wrapper (or a list holding a
 * lazy tail) realizes it as it writes, like the oracle (an infinite one prints without
 * end). There is no chunking: every element realizes singly. {@code nth} and {@code quot}
 * as VALUES are correctly-ordered lambdas wrapping the primitive (a bare {@code #'NTH}
 * would have the operands backwards). A keyword {@code :foo} is the list
 * {@code (:C%KEYWORD "foo")} holding its spelling verbatim (case-preserved, so {@code :a}
 * and {@code :A} stay apart and compare unequal);
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
 * catch-all) with {@code throw} over {@code error} -- an exception (an {@code ex-info}, a
 * throwable construction, a caught condition, a host {@code Throwable}) signals as a
 * condition carrying its class, message, data and cause, anything else through its
 * printed rendering; dispatch is a method table plus a dispatcher {@code defun}
 * ({@code defmulti}/{@code defmethod}); namespaces wire aliases and refers
 * ({@code clojure.string} over the core string operations, {@code clojure.set} over its
 * spliced runtime, {@code clojure.java.io} for {@code reader} only, a project namespace's
 * file lowered once ahead of the form that requires it); interop lowers to the
 * {@code java:} surface. A {@code defmacro} is a compile-time expander (one lambda over
 * the call's argument list, the same function the runtime table entry holds for
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

	static final LispVal NIL_CONST = LispNil.INSTANCE;

	static final LispVal TRUE_CONST = LispTrue.INSTANCE;

	static final LispSymbol AMPERSAND_REST = new LispSymbol("&REST");

	/**
	 * The library string builder behind {@code str}/{@code pr-str} parts, the
	 * {@code clojure.string/join} elements and the multimethod miss messages: the value's
	 * Clojure-notation string, so no backend prints the wrappers.
	 */
	static final LispSymbol CLOJURE_STR_OF = new LispSymbol("RONTOLISP::%CLOJURE-STR-OF");

	/**
	 * The library direct writer behind {@code println}/{@code print}/{@code pr}/
	 * {@code prn} parts: the value in Clojure notation straight to
	 * {@code *standard-output*}, answering nil.
	 */
	static final LispSymbol CLOJURE_WRITE_DATUM = new LispSymbol("RONTOLISP::%CLOJURE-WRITE-DATUM");

	/**
	 * The library string builder behind {@code print-str}/{@code prn-str}/
	 * {@code println-str}: a list of values, a readable flag and a newline flag in, the
	 * printed text out.
	 */
	static final LispSymbol CLOJURE_PRINT_STR = new LispSymbol("RONTOLISP::%CLOJURE-PRINT-STR");

	static final LispSymbol ELSE = new LispSymbol(":else");

	final List<LispVal> forms = new ArrayList<>();

	/**
	 * Every global var defined (or pre-scanned) so far, by var key ({@code ns/name},
	 * {@link #varKey}): a namespace's vars are its own, like the oracle's, so two
	 * namespaces may define one name.
	 */
	final Map<String, Kind> globals = new HashMap<>();

	/**
	 * How many {@code defn}/{@code defn-} definitions each var key has lowered so far. A
	 * redefined {@code defn} gets a fresh internal name per definition (see
	 * {@link #defnSym}), so a {@code (def g f)} between two definitions captures the
	 * definition current at that point while the call sites below each definition call
	 * the newest -- the interpreter and the compiled backends lowering the same names.
	 * Keyed by var key, so every namespace versions its own names; a session keeps the
	 * counts across buffers, like the globals.
	 */
	final Map<String, Integer> defnCounts = new HashMap<>();

	/**
	 * The metadata each var's newest definition recorded, by var key: what a {@code #'}
	 * site lowered after it answers (see {@link ClojureVarLowering}). A session keeps it
	 * across buffers, like the globals.
	 */
	final Map<String, ClojureVarLowering.VarMeta> varMetas = new HashMap<>();

	/**
	 * The var keys whose value cell may hold the unbound root: a {@code declare}d name
	 * and a value-less {@code def}, pre-scanned and lowered. A {@code defonce} of one
	 * tests the root too, so it binds an unbound var.
	 */
	final Set<String> unboundCapable = new HashSet<>();

	/**
	 * The var keys a file starts with the unbound root in their value cell, in lowering
	 * order (see {@link ClojureVarLowering#unboundRoot}).
	 */
	final Set<String> unboundRoots = new LinkedHashSet<>();

	/**
	 * Whether this lowering reads a session's buffers one at a time: no pre-scan sees a
	 * later buffer, so a name only declared so far may still be defined.
	 */
	boolean session;

	/**
	 * Whether the program is lowered for a target where the host is: the interpreter and
	 * the JVM, where a {@code java:} member can be called -- and can throw, and take an
	 * exception -- unlike wasm, where {@code java:} is a call-time error. Only such a
	 * program binds a caught host exception ({@link #bindCaught}) and backs its
	 * exceptions with host ones ({@code ClojureStateLowering.exInfoRuntime}); a wasm one
	 * lowers as if no host existed.
	 */
	boolean hostTarget = true;

	/**
	 * The throwable classes the program builds exceptions of (a construction's class,
	 * ex-info's {@code clojure.lang.ExceptionInfo}): what the host exception standing for
	 * one is built as ({@code ClojureStateLowering.hostExceptionOf}).
	 */
	final Set<String> hostExceptionClasses = new LinkedHashSet<>();

	/** The classes of {@link #hostExceptionClasses} the session's builder covers. */
	final Set<String> hostExceptionClassesEmitted = new LinkedHashSet<>();

	/**
	 * The var keys whose top-level root reader a {@code #'} site under a shadowing local
	 * already hoisted (see {@link ClojureVarLowering#varOf}).
	 */
	final Set<String> varRootReaders = new HashSet<>();

	/**
	 * Every namespace the lowering has seen, by name; {@link #currentNs} names the one
	 * the forms lower in.
	 */
	final Map<String, ClojureNsState> namespaces = new HashMap<>();

	/**
	 * The namespace the forms lower in, and the one {@code ::}-keywords resolve against:
	 * the file's {@code ns} name (the seam reads the whole file, so the form order
	 * decides), or the session's {@code *ns*} (an {@code ns} or {@code in-ns} buffer
	 * switches it for the buffers below it). The oracle starts a REPL in {@code user}, so
	 * that is the default.
	 */
	String currentNs = "user";

	/**
	 * The root of {@code *file*}: the entry file's path, absolute where the host has a
	 * working directory, or {@code NO_SOURCE_PATH} (a session, a read without a file).
	 */
	String rootFile = ClojureCoreSpecials.NO_SOURCE_PATH;

	/**
	 * The root of {@code *source-path*}: the entry file's name, or
	 * {@code NO_SOURCE_FILE}.
	 */
	String rootSourcePath = ClojureCoreSpecials.NO_SOURCE_FILE;

	/**
	 * The {@code *file*} of the file the forms lower in: the root, or a required
	 * namespace's path below its source root while its file lowers -- what a macro body
	 * reads where it expands.
	 */
	String loadingFile = ClojureCoreSpecials.NO_SOURCE_PATH;

	/** The {@code *source-path*} beside {@link #loadingFile}. */
	String loadingSourcePath = ClojureCoreSpecials.NO_SOURCE_FILE;

	/**
	 * The library namespaces a {@code require} named ({@code clojure.set},
	 * {@code clojure.test}): {@code find-ns} finds them, like the ones the oracle loads
	 * before the program ({@link #STARTUP_NAMESPACES}).
	 */
	final Set<String> requiredLibraries = new HashSet<>();

	/** The library namespaces {@code clj -M} has loaded before the program runs. */
	static final List<String> STARTUP_NAMESPACES = List.of("clojure.core", "clojure.edn", "clojure.java.io",
			"clojure.string");

	/**
	 * Every namespace an {@code ns} or {@code in-ns} named so far, {@code user} first:
	 * the namespaces {@code run-tests} knows beside the ones that defined a test (the
	 * oracle refuses any other).
	 */
	final Set<String> namespacesSeen = new LinkedHashSet<>(List.of("user"));

	/**
	 * The namespaces an {@code ns} or {@code in-ns} created (the oracle's
	 * {@code find-ns}), {@code user} from the start: a qualified name may reach one by
	 * its full name, without an alias.
	 */
	final Set<String> createdNamespaces = new HashSet<>(Set.of("user"));

	/**
	 * The namespaces loaded so far: an {@code ns} form of the program (the oracle's
	 * {@code ns} marks its namespace loaded), or a file a {@code require} read. A
	 * {@code require} of one reads nothing -- a namespace loads once per program read, so
	 * a second {@code require} keeps every root, like the oracle.
	 */
	final Set<String> loadedNamespaces = new HashSet<>();

	/**
	 * The namespaces loaded from a built-in file ({@link ClojureBuiltinNamespaces}): the
	 * only ones that may require {@code rontolisp.internal.ring}, and whose left-out vars
	 * are refused by name.
	 */
	final Set<String> builtinNamespaces = new HashSet<>();

	/**
	 * The namespaces whose files are lowering, innermost first: a {@code require} of one
	 * of them is the oracle's cyclic-load refusal.
	 */
	final Deque<String> loadingNamespaces = new ArrayDeque<>();

	/**
	 * The file of each {@code load} unit ({@link ClojureNamespaceLowering#loadOne}): the
	 * key a loaded file's init is stored under, to its root-relative path. A namespace
	 * file is not here; its path is the namespace's resource.
	 */
	final Map<String, String> unitFiles = new HashMap<>();

	/**
	 * The root-relative file a namespace or {@code load} unit key was read from.
	 * @param unit the key
	 * @return its path
	 */
	String fileOf(String unit) {
		String file = this.unitFiles.get(unit);
		return file != null ? file : ClojureSourcePath.resourceOf(unit);
	}

	/** Where a project namespace's file is found. */
	ClojureSourcePath sourcePath = new ClojureSourcePath(ClojureFiles.NONE, null);

	/**
	 * The forms of the namespaces loaded while the current top-level datum lowers: the
	 * definitions stay top-level (a {@code defn} keeps its direct call and its
	 * tree-shaker visibility there), while a namespace's statements run from its init
	 * when the {@code require} executes (see {@link #namespaceInits}).
	 */
	List<LispVal> hoisted = new ArrayList<>();

	/**
	 * The statements of every project namespace file loaded so far, by namespace, in file
	 * order: everything that must run when the namespace loads --
	 * {@code def}/{@code defonce} setqs, prints, nested require calls, method rows,
	 * arbitrary calls, ... -- while the definitions ({@code defn} and friends, see
	 * {@link #isDefinitionalDatum}) stay top-level. The require site runs these behind
	 * the namespace's loaded flag (see {@link #requireCall}), so a {@code require} inside
	 * a body loads when the body runs, {@code :reload} re-runs them ({@code def} resets,
	 * {@code defonce} keeps), and a namespace two separately lowered files require still
	 * runs once per process.
	 */
	final Map<String, List<LispVal>> namespaceInits = new LinkedHashMap<>();

	/**
	 * The project namespaces each namespace (or the entry program's namespace) requires
	 * directly, in require order: what a {@code :reload-all} re-runs ahead of the
	 * namespace itself, dependencies first.
	 */
	final Map<String, LinkedHashSet<String>> namespaceDeps = new HashMap<>();

	/**
	 * The namespaces whose loaded flag and init were already emitted in this lowering.
	 */
	final Set<String> initEmitted = new HashSet<>();

	/**
	 * Cuts a namespace init into chunks once the chunk's statements pass this many
	 * printed characters. A chunk is one lambda body, and neither the JVM's 64 KB method
	 * limit nor wasm's function-body cap (256 KiB bodies, the top level chunked at 48 KiB
	 * of emitted body) is measured in statements, so a bare statement count cannot bound
	 * them; printed characters track the emitted size closely enough for straight-line
	 * init code that a 16 KiB cut stays far under both. One huge statement still makes
	 * one huge chunk -- the same accepted gap as a defun that is one long run of
	 * statements on wasm.
	 */
	static final int INIT_CHUNK_TARGET_CHARS = 16 * 1024;

	/** The scopes, innermost last; globals live in {@link #globals}. */
	final List<Map<String, Kind>> scopes = new ArrayList<>();

	/**
	 * The macros defined so far: a user {@code defmacro}'s var key to its expander, a
	 * lowered lambda over the call's argument list (one value) applying each arity's
	 * parameters to the lowered body. Expansion is datum to datum at lower time: the call
	 * site's argument datums travel quoted into one application, the answer decodes back
	 * to a datum and lowers like any other form, so every backend sees only expanded
	 * code.
	 */
	final Map<String, LispVal> macros = new HashMap<>();

	/**
	 * Who evaluates one macro application in the macro-time environment, or null when the
	 * driver supplied none. Every production path supplies one (a file read, a session
	 * buffer); without one a macro definition still registers and still emits its runtime
	 * table entry, but a call site cannot expand.
	 */
	@Nullable ClojureMacroEvaluator macroEvaluator;

	/**
	 * How deep macro expansion currently nests; over {@link #MAX_MACRO_DEPTH} it ends.
	 */
	int macroDepth;

	/**
	 * The {@code #}-suffixed names the enclosing syntax-quotes bound, innermost last: one
	 * map per syntax-quote node, so {@code ~x#} in an unquote answers the same gensym the
	 * template's {@code x#} does.
	 */
	final List<Map<String, LispSymbol>> syntaxGens = new ArrayList<>();

	int counter;

	/** The enclosing recur targets, innermost last; empty outside any. */
	final Deque<RecurTarget> recurTargets = new ArrayDeque<>();

	/** The enclosing proxy methods' {@code this}, innermost last; empty outside any. */
	final Deque<ProxyMethod> proxyMethods = new ArrayDeque<>();

	/**
	 * One enclosing proxy method body: a {@code proxy-super} lowers to a call of the
	 * superclass implementation on this object.
	 *
	 * @param self the lowered {@code this} symbol the method body binds
	 */
	static final class ProxyMethod {

		final LispSymbol self;

		ProxyMethod(LispSymbol self) {
			this.self = self;
		}

	}

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
	boolean tailPosition;

	/**
	 * Whether a {@code def} below a top-level datum answers the var it defined, like the
	 * oracle's REPL; a file's nested {@code def} answers the value and builds no var.
	 */
	boolean nestedDefAnswersVar;

	/**
	 * How many {@code try} bodies deep the lowering sits: a {@code recur} with a
	 * {@code try} between it and its target is the oracle's
	 * {@code Cannot recur across try} refusal. Each target captures this depth when
	 * pushed (see {@link #pushRecurTarget}), so a target opened inside the {@code try}
	 * still recurs.
	 */
	int tryDepth;

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
	static final class RecurTarget {

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
	void pushRecurTarget(RecurTarget target) {
		target.depth = this.tryDepth;
		this.recurTargets.push(target);
	}

	/**
	 * One datum lowered in tail position: the tail slots (an {@code if} arm, a body's
	 * last form) of a form already in tail position. Anything else lowers through
	 * {@link #lower}, which clears the position.
	 */
	LispVal lowerTail(LispVal form) {
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
	LispVal lowerTailSlot(LispVal form) {
		return this.tailPosition ? lowerTail(form) : lower(form);
	}

	/**
	 * One body lowered in the target body's tail position: a clause or {@code loop} body
	 * forces the position its last form inherits.
	 */
	LispVal bodyOfTail(List<LispVal> forms) {
		boolean outer = this.tailPosition;
		this.tailPosition = true;
		try {
			return bodyOf(forms);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	LispVal bodyTail(List<LispVal> items, int from) {
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
	LispVal nonTailBody(List<LispVal> items, int from) {
		boolean outer = this.tailPosition;
		this.tailPosition = false;
		try {
			return body(items, from);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	LispVal nonTailBodyOf(List<LispVal> forms) {
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
	String freshRecurName() {
		return mangle("fn-") + this.counter++;
	}

	/**
	 * One already-lowered lambda wrapped in a {@code labels} self-binding under
	 * {@code callName}, answering the local: the shape a named {@code fn} always takes,
	 * an anonymous one only when a {@code recur} reached it.
	 */
	static LispVal labelsSelfCall(String callName, LispVal lambda) {
		return ClojureBindingLowering.labelsWithHead(callName, List.of(), lambda);
	}

	/** The false value, referenced (never rebuilt) wherever {@code false} lowers. */
	final LispSymbol falseVariable = new LispSymbol(FALSE_VARIABLE);

	/**
	 * The names bound to real functions, per {@link #scopes} level: a head-position call
	 * to one stays a direct call, while any other variable goes through the prelude
	 * dispatcher (its value may hold a collection). Globals live in
	 * {@link #globalDirectFuns}.
	 */
	final List<Set<String>> directScopes = new ArrayList<>();

	/** The globals bound to real functions, by var key, like {@link #directScopes}. */
	final Set<String> globalDirectFuns = new HashSet<>();

	/** Whether the session already emitted the false binding (files always emit it). */
	boolean falseBound;

	/**
	 * Whether the program throws, builds or reads an exception: the exception runtime
	 * (one condition class carrying a class chain, a message, data and a cause) is
	 * spliced in once, behind the false binding.
	 */
	boolean usedExInfo;

	/** Whether the ex-info runtime was already spliced in (files splice it inline). */
	boolean exInfoEmitted;

	/**
	 * The classes a catch tests, by name, to their chains: each needs its predicate
	 * ({@code ClojureThrowables.catchRuntime}), and a program that builds no exception
	 * the exception reader too.
	 */
	final Map<String, List<String>> caughtChains = new LinkedHashMap<>();

	/** The caught classes whose predicates the session already emitted. */
	final Set<String> emittedCatches = new HashSet<>();

	/** Whether the session already emitted the catch runtime's exception reader. */
	boolean catchReaderEmitted;

	/** Whether a {@code catch} clause of any class binds a caught condition. */
	boolean usedCatch;

	/**
	 * Whether {@code class} may read a condition's class: its exception arm survives
	 * where the program defines the exception reader, which a catch then needs.
	 */
	boolean readsConditionClass;

	/** Whether {@code instance?} reads exceptions: the reader always travels. */
	boolean readsExceptionParts;

	/**
	 * Whether the program needs the catch runtime's exception reader: it builds no
	 * exception, and a catch tests a class, {@code instance?} tests one, or {@code class}
	 * can read a caught condition -- in a session any input may, through the {@code *e}
	 * the session records.
	 */
	boolean needsExceptionReader() {
		return !this.usedExInfo && (!this.caughtChains.isEmpty() || this.readsExceptionParts
				|| (this.readsConditionClass && (this.usedCatch || this.session)));
	}

	/**
	 * Whether the program uses hierarchies (any of {@code derive}/{@code underive}/
	 * {@code isa?}/{@code parents}/{@code ancestors}/{@code descendants}/
	 * {@code make-hierarchy}/{@code prefer-method}, or any {@code defmulti} whose
	 * dispatcher consults them): the hierarchy runtime is spliced in once, behind the
	 * false binding.
	 */
	boolean usedHierarchy;

	/** Whether the hierarchy runtime was already spliced in (files splice it inline). */
	boolean hierarchyEmitted;

	/**
	 * The bases of each class whose chain the program resolved (a construction, a catch,
	 * {@code instance?}, a class spelling in a dispatch or hierarchy position) and of
	 * each of its supers, by name ({@link ClojureClassBases}): the rows {@code isa?},
	 * {@code parents} and {@code ancestors} read off a class keyword once
	 * {@link #usedClassChains} is set.
	 */
	final Map<String, List<String>> classBases = new LinkedHashMap<>();

	/**
	 * Whether a dispatch value or a hierarchy operation spells a class: the hierarchy
	 * runtime then reads {@link #classBases} for a class keyword, like the oracle's
	 * {@code isa?} follows Java inheritance.
	 */
	boolean usedClassChains;

	/**
	 * Whether {@code Object} is spelled, or a hierarchy reader can meet a class keyword:
	 * every class {@code class} may answer gets its row ({@link #pendingClassRows}), the
	 * core kinds and the program's types too.
	 */
	boolean allClassRows;

	/**
	 * Whether {@code descendants} is lowered: with {@link #usedClassChains}, a class
	 * keyword is its refusal, an exception of the program's exception runtime.
	 */
	boolean readsDescendants;

	/** Whether the session already emitted the class-chain walk. */
	boolean classChainsEmitted;

	/**
	 * Whether a hierarchy may meet a host class object: the program uses a hierarchy and
	 * names a {@code java:} operator ({@link ClojureHierarchyLowering#namesHost}), so its
	 * class-chain walk asks the host for a class object's supers. Sets
	 * {@link #usedClassChains}.
	 */
	boolean hostClassWalk;

	/**
	 * Whether the forms lowered so far (a session's buffers) named a {@code java:}
	 * operator.
	 */
	boolean namedHost;

	/** Whether the session's emitted class-chain walk is the host class one. */
	boolean hostClassWalkEmitted;

	/**
	 * The catch clauses lowered so far, each to bind the host exception a caught
	 * {@code java:java-exception} stands for ({@link #bindCaught}).
	 */
	final List<CaughtBinding> caughtBindings = new ArrayList<>();

	/**
	 * A catch clause's body and the variable it binds: the clause cell whose car is the
	 * body form.
	 *
	 * @param body the cell holding the clause's body form
	 * @param variable the clause's variable
	 */
	record CaughtBinding(LispCons body, LispSymbol variable) {
	}

	/**
	 * Records a {@code handler-case} clause a {@code catch} (or {@code thrown?}) lowered
	 * to, {@code (type (variable) body)}: a program that names a {@code java:} operator
	 * binds the host exception a caught {@code java:java-exception} stands for, the
	 * oracle's caught object ({@code rontolisp::%clojure-caught}), and one naming none
	 * the condition as before -- no such condition exists there. A file knows which once
	 * it is lowered ({@link #bindCaught}); a session's buffers run as they are lowered,
	 * on the interpreter, where the host is always there, so they bind through it at
	 * once.
	 * @param clause the clause
	 */
	void recordCatch(LispCons clause) {
		LispCons params = (LispCons) clause.cdr();
		LispSymbol variable = (LispSymbol) ((LispCons) params.car()).car();
		CaughtBinding binding = new CaughtBinding((LispCons) params.cdr(), variable);
		if (this.session) {
			bindCaught(binding);
		}
		else {
			this.caughtBindings.add(binding);
		}
	}

	/**
	 * Binds each recorded clause's variable through {@code rontolisp::%clojure-caught}
	 * when the program names a {@code java:} operator: the body form becomes
	 * {@code (let ((variable (%clojure-caught variable))) body)}.
	 */
	void bindCaught() {
		if (this.hostTarget && this.namedHost) {
			for (CaughtBinding binding : this.caughtBindings) {
				bindCaught(binding);
			}
		}
		this.caughtBindings.clear();
	}

	private static void bindCaught(CaughtBinding binding) {
		LispSymbol variable = binding.variable();
		binding.body()
			.setCar(ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List
						.of(ClojureLowerUtil.list(variable, ClojureLowerUtil.list(new LispSymbol(CAUGHT), variable)))),
					binding.body().car()));
	}

	/** The library function a catch binds through ({@link #recordCatch}). */
	static final String CAUGHT = "RONTOLISP::%CLOJURE-CAUGHT";

	/**
	 * Turns on {@link #hostClassWalk} once the program uses a hierarchy and the forms
	 * lowered so far name the host.
	 * @param lowered forms just lowered
	 */
	void noteHost(List<LispVal> lowered) {
		this.namedHost = this.namedHost || ClojureHierarchyLowering.namesHost(lowered);
		if (this.usedHierarchy && this.namedHost) {
			this.hostClassWalk = true;
			this.usedClassChains = true;
		}
	}

	/** The class rows the session already emitted, by name. */
	final Set<String> emittedSupers = new HashSet<>();

	/**
	 * Records the rows of a class chain's classes (own name first) and of their supers.
	 * @param chain the chain
	 */
	void recordChain(List<String> chain) {
		for (String name : chain) {
			recordClass(name);
		}
	}

	/**
	 * Records the row of the class and of each of its supers.
	 * @param name the class's binary name
	 */
	void recordClass(String name) {
		if (this.classBases.containsKey(name)) {
			return;
		}
		List<String> bases = ClojureClassBases.superBasesOf(name);
		if (bases == null) {
			return;
		}
		this.classBases.put(name, bases);
		for (String base : bases) {
			recordClass(base);
		}
	}

	/**
	 * Records a class spelled in a dispatch or hierarchy position, and every class a
	 * value may have without the program naming it whose supers hold it (the runtime
	 * errors', the streams'), so {@code isa?} walks from the class {@code class} answers
	 * to it; {@code Object} records them all.
	 * @param name the class's binary name
	 */
	void recordSpelledClass(String name) {
		recordClass(name);
		this.usedClassChains = true;
		if (name.equals(ClojureClassBases.OBJECT)) {
			this.allClassRows = true;
			return;
		}
		for (String implicit : ClojureClassBases.implicitClasses()) {
			if (implicit.equals(name) || ClojureClassBases.supersOf(implicit).contains(name)) {
				recordClass(implicit);
			}
		}
	}

	/**
	 * The class rows not yet emitted, as {@code (name base ...)} conses, each a class
	 * with known bases, or {@code (name . t)} for a core kind or a type
	 * ({@link ClojureClassBases#KINDS}). With {@link #allClassRows}, every class
	 * {@code class} may answer joins first.
	 */
	List<LispVal> pendingClassRows() {
		List<String> opaque = new ArrayList<>();
		if (this.allClassRows) {
			for (String implicit : ClojureClassBases.implicitClasses()) {
				recordClass(implicit);
			}
			opaque.addAll(ClojureClassBases.KINDS);
			this.types.values().stream().map(TypeDef::tagSpelling).sorted().forEach(opaque::add);
		}
		List<LispVal> rows = new ArrayList<>();
		for (Map.Entry<String, List<String>> row : this.classBases.entrySet()) {
			if (this.emittedSupers.add(row.getKey())) {
				List<LispVal> bases = new ArrayList<>();
				for (String base : row.getValue()) {
					bases.add(LispString.literal(base));
				}
				rows.add(new LispCons(LispString.literal(row.getKey()), ClojureLowerUtil.list(bases)));
			}
		}
		for (String name : opaque) {
			if (this.emittedSupers.add(name)) {
				rows.add(new LispCons(LispString.literal(name), TRUE_CONST));
			}
		}
		return rows;
	}

	/**
	 * Whether a {@code defmulti} dispatch function is being lowered: a {@code class} call
	 * inside one answers nil itself for nil (see {@link #classForm(LispVal)}), so the
	 * dispatcher's null test maps it onto the nil method's marker -- bare, wrapped in
	 * another function, or inlined from a recorded definition at the call site (see
	 * {@link #inlineDispatchCall(String, List)}) -- while an explicit {@code :nil}
	 * keyword keeps its row, like the oracle. Saved and restored around the dispatch
	 * lowering (see {@link #defmultiForms(List)}), so an ordinary {@code class} keeps
	 * answering the {@code :nil} keyword.
	 */
	boolean inDispatchFn;

	/**
	 * The dispatch-function data of class-calling definitions, by name: a {@code defn}
	 * name maps to its rebuilt {@code (fn ...)} datum, a {@code def} (or {@code defonce})
	 * name to its {@code (fn ...)} value datum (or the datum an alias name points at). A
	 * {@code defmulti} over one of these names re-lowers the recorded datum with the
	 * dispatch lowering instead of calling the definition, so a named {@code (class x)}
	 * answers nil itself for nil like the inline datum does, while a direct call to the
	 * definition keeps answering the {@code :nil} keyword. A call to one of these names
	 * nested inside an inline dispatch datum inlines the recorded datum at the call site
	 * the same way. Only class-calling definitions are recorded (a redefinition without
	 * one drops the name); a {@code ^:dynamic} name is never recorded, so a
	 * {@code binding} rebind still routes through the value cell. Recorded in definition
	 * order, so the oracle's define-before-use order is what resolves. Keyed by var key,
	 * and read only for the current namespace's definitions (a recorded datum re-lowers
	 * where its names resolve).
	 */
	final Map<String, LispVal> classDispatchFns = new HashMap<>();

	/**
	 * The recorded definitions currently being inlined into a {@code defmulti} dispatch
	 * function (see {@link #inlineDispatchCall(String, List)}): a name already on the
	 * stack keeps its direct call, so a (mutually) recursive definition still terminates
	 * the lowering.
	 */
	final Set<String> inliningDispatch = new HashSet<>();

	/**
	 * A protocol the program defines or extends: its method names, the method-table
	 * global and the {@code Object}-default global. Protocols dispatch over the
	 * {@code C%PROTOCOL-TAG} of the target (the multimethod shape without the hierarchy
	 * search); the table maps a tag to its row (method keyword to lambda), the default
	 * global holds the {@code Object} row consulted on a miss (nil until the first
	 * {@code Object} extension). A protocol declared {@code :extend-via-metadata true}
	 * also has {@code inlineVar}: the table of the implementations a
	 * {@code defrecord}/{@code deftype}/{@code reify} body holds, which win over the
	 * target's metadata, which wins over the extension rows, like the oracle; null for
	 * every other protocol, whose inline rows share the method table. {@code arities}
	 * maps each method to its signature's parameter count, the target included.
	 */
	record ProtocolDef(Set<String> methods, Map<String, Integer> arities, LispSymbol methodsVar, LispSymbol defaultVar,
			@Nullable LispSymbol inlineVar) {

		/** The table an inline (body) implementation is stored in. */
		LispSymbol inlineTable() {
			return this.inlineVar != null ? this.inlineVar : this.methodsVar;
		}

	}

	/**
	 * A record or deftype the program defines: whether it is map-like (a record) or
	 * opaque (a deftype), its declared field names, its dispatch-tag spelling and its
	 * host class name ({@code my_app.core.Name}: the defining namespace with {@code -}
	 * munged to {@code _}, then the name verbatim, like the oracle's), which a record
	 * prints and a record literal names. {@code mutableFields} are the deftype fields
	 * declared {@code ^:unsynchronized-mutable} or {@code ^:volatile-mutable}, a subset
	 * of {@code fields} in declaration order: they live in a slot vector behind the
	 * public table, so only the type's own inline methods read or {@code set!} them.
	 * {@code inlineMethods} are the protocol methods its body implements, each as
	 * {@link #inlineMethodKey}: the methods of its host class, which an instance call
	 * reaches ({@code (.m r)}), where an {@code extend-type} row is none.
	 * {@code protocols} are the var keys of the protocols its body names, with methods or
	 * none: the interfaces of its host class, which {@code instance?} tests.
	 */
	record TypeDef(boolean record, List<String> fields, String tagSpelling, String className,
			List<String> mutableFields, Set<String> inlineMethods, Set<String> protocols) {
	}

	/**
	 * One entry of {@link TypeDef#inlineMethods}: the protocol's var key and the method
	 * name.
	 */
	static String inlineMethodKey(String protocolKey, String method) {
		return protocolKey + " " + method;
	}

	/**
	 * The slot forms of the deftype mutable fields in the method being lowered, by field
	 * name ({@code (aref slots i)} over the method's slot-vector temporary). A name is
	 * the field only while its innermost scope kind is {@link Kind#MUTABLE_FIELD}.
	 */
	final Map<String, LispVal> mutableFieldPlaces = new LinkedHashMap<>();

	/**
	 * The protocols defined so far, by var key (the whole-file pre-scan fills it first).
	 */
	final Map<String, ProtocolDef> protocols = new HashMap<>();

	/**
	 * The records/deftypes defined so far, by the var key their name would take in the
	 * defining namespace (the whole-file pre-scan fills it first); {@link #typeKeyOf}
	 * resolves a class spelling to one.
	 */
	final Map<String, TypeDef> types = new HashMap<>();

	/**
	 * Whether the program uses protocols, records, deftypes or reify: the protocol
	 * runtime (the tag reader) is spliced in once, behind the false binding.
	 */
	boolean usedProtocols;

	/** Whether the protocol runtime was already spliced in (files splice it inline). */
	boolean protocolsEmitted;

	/**
	 * The vars declared {@code ^:dynamic}, by var key: only {@code binding} may rebind
	 * them, and only they (beside {@code *out*}/{@code *in*} and {@code *agent*}, which
	 * the agent runtime binds while a {@code send} runs) may be rebound.
	 */
	final Set<String> dynamicVars = new HashSet<>();

	/**
	 * The identifiers whose own symbol ({@link ClojureLowerUtil#idSym}) is a special
	 * variable: the stream and agent aliases, and every {@code ^:dynamic} var of
	 * {@code user} (whose var symbol is the bare mangled name). A local of one of these
	 * names binds {@link #localSym}, so it stays lexical like the oracle's instead of
	 * rebinding the special. Filled by the pre-scan of every datum, nested definitions
	 * included: the special proclamation is program-wide, so a var defined below a local
	 * counts. A macro expansion adds none, since a symbol carries no metadata through
	 * one.
	 */
	final Set<String> shadowedSpecials = new HashSet<>(Set.of("*out*", "*in*", "*agent*"));

	/**
	 * The vars a {@code defmulti} defined, by var key, until another definition of the
	 * name: a {@code defmulti} of one of them is a no-op, like the oracle's, which
	 * defines only when the var holds no multimethod yet.
	 */
	final Set<String> multimethods = new HashSet<>();

	/**
	 * The binding-depth counter of a dynamic var: a special beside the var itself, zero
	 * at the root and rebound one deeper by every {@code binding} of the var, so
	 * {@code set!} tests at run time whether the var is thread-bound. Defined beside the
	 * var by every {@code ^:dynamic} definition. The single-{@code %} suffix keeps it
	 * apart from user definitions, like the multi-{@code defn} helpers.
	 * @param key the var key
	 * @return the counter symbol
	 */
	static LispSymbol boundDepthSym(String key) {
		return new LispSymbol(varSym(key).name() + "%bound-depth");
	}

	/**
	 * A {@code let} local's host class, inferred from a construction-literal init: the
	 * FQN, the plain name (for the shadow walk) and the scope depth that owns it. Only
	 * {@code let} records -- its bindings never rebind, unlike {@code loop} targets;
	 * every other binder hides entries through the shadow walk in {@link #hostClassOf}
	 * instead of recording.
	 */
	record HostClass(String fqn, String name, int depth) {
	}

	/**
	 * The visible {@code let}-inferred host classes by mangled name. Entries die with
	 * their {@code let} (the finally there) or when the name rebinds.
	 */
	final Map<String, HostClass> hostClasses = new HashMap<>();

	/**
	 * Whether the program uses refs or agents (any of {@code ref}/{@code dosync}/
	 * {@code alter}/{@code commute}/{@code ref-set}/{@code ensure}/{@code agent}/
	 * {@code send}/{@code send-off}/{@code await}, or a {@code binding} of
	 * {@code *agent*}): the STM runtime (the transaction depth, the validator registry
	 * and the agent var) is spliced in once, behind the false binding.
	 */
	boolean usedStm;

	/** Whether the STM runtime was already spliced in (files splice it inline). */
	boolean stmEmitted;

	/**
	 * The {@code clojure.core} specials the program reads, binds, assigns or takes the
	 * var of: their definitions (a flag's root, a counter) are spliced in once, behind
	 * the false binding ({@link ClojureCoreSpecials#definitions}).
	 */
	final Set<String> usedSpecials = new LinkedHashSet<>();

	/** The specials whose definitions a session already spliced in. */
	final Set<String> emittedSpecials = new HashSet<>();

	/**
	 * Whether the program uses {@code clojure.test}: the test runtime start (the report
	 * stream, the ex-info reader) runs once, behind the false binding.
	 */
	boolean usedTest;

	/** Whether the test runtime start was already emitted (files emit it inline). */
	boolean testEmitted;

	/**
	 * The {@code (file:line)} of the {@code deftest} being lowered, or null outside one:
	 * what a report names for an assertion the reader never saw (a macro's expansion).
	 */
	@Nullable String testLocation;

	/**
	 * Whether the program defines or expands macros: the macro runtime (the table lookup,
	 * the demangler and {@code C%MACROEXPAND-1}/{@code C%MACROEXPAND}) is spliced in
	 * once, behind the false binding.
	 */
	boolean usedMacros;

	/** Whether the macro runtime was already spliced in (files splice it inline). */
	boolean macrosEmitted;

	/**
	 * Whether the program reads ({@code read-string}, {@code read}, as calls or values):
	 * its record and deftype classes are registered once, behind the false binding, so a
	 * record literal the run-time reader meets builds the record.
	 */
	boolean usedReader;

	/**
	 * What {@code *assert*} holds where the next {@code assert} lowers: the oracle reads
	 * it when the macro expands, so a top-level {@code set!} of it to a literal switches
	 * off (or back on) every {@code assert} lowered after it, and a {@code binding}
	 * around an expanded one changes nothing.
	 */
	boolean assertEnabled = true;

	/**
	 * The classes a session already registered for the run-time reader, by class name: a
	 * later buffer registers only what it adds or redefines (files register every class
	 * at once).
	 */
	final Map<String, TypeDef> readRegistered = new HashMap<>();

	/**
	 * How deep lower-time macro expansion may nest before it ends with an error. Each
	 * level is a recursive expansion (a macro whose expansion calls a macro); a wide
	 * recursion like {@code chain} over many forms nests one level per form, so the bound
	 * sits far above any written macro and only catches the self-recursive one.
	 */
	static final int MAX_MACRO_DEPTH = 128;

	/** The reader the datums came out of, for error positions; null when unknown. */
	@Nullable ClojureReader reader;

	static List<LispVal> lower(List<LispVal> datums) {
		return lower(datums, null, null);
	}

	static List<LispVal> lower(List<LispVal> datums, @Nullable ClojureReader reader) {
		return lower(datums, reader, null);
	}

	static List<LispVal> lower(List<LispVal> datums, @Nullable ClojureReader reader,
			@Nullable ClojureMacroEvaluator macroEvaluator) {
		return lower(datums, reader, macroEvaluator, ClojureFiles.NONE);
	}

	static List<LispVal> lower(List<LispVal> datums, @Nullable ClojureReader reader,
			@Nullable ClojureMacroEvaluator macroEvaluator, ClojureFiles files) {
		return lower(datums, reader, macroEvaluator, files, true);
	}

	static List<LispVal> lower(List<LispVal> datums, @Nullable ClojureReader reader,
			@Nullable ClojureMacroEvaluator macroEvaluator, ClojureFiles files, boolean hostTarget) {
		ClojureLowering lowering = new ClojureLowering();
		lowering.hostTarget = hostTarget;
		lowering.reader = reader;
		lowering.macroEvaluator = macroEvaluator;
		lowering.sourcePath = new ClojureSourcePath(files, reader == null ? null : reader.file());
		lowering.sourcePath.entryNamespace(firstNsName(datums));
		lowering.rootFile = lowering.sourcePath.entryPath();
		lowering.rootSourcePath = lowering.sourcePath.entryName();
		lowering.loadingFile = lowering.rootFile;
		lowering.loadingSourcePath = lowering.rootSourcePath;
		lowering.declare(datums);
		lowering.forms.add(lowering.falseBinding());
		// pass two: lower
		for (LispVal datum : datums) {
			lowering.forms.addAll(lowering.topLevels(datum));
		}
		if (lowering.usedMacros) {
			// the macro runtime travels with the program, like the false value
			lowering.forms.addAll(1, ClojureMacroLowering.macroRuntime(lowering));
		}
		// a hierarchy meets a host class object only where the program names the host
		lowering.noteHost(lowering.forms);
		// and a catch binds a host exception only there
		lowering.bindCaught();
		// descendants of a class keyword is an exception of the program's runtime
		lowering.usedExInfo |= lowering.usedClassChains && lowering.readsDescendants;
		if (lowering.usedHierarchy) {
			// the hierarchy runtime runs before anything else, like the false value
			lowering.forms.addAll(1, ClojureHierarchyLowering.hierarchyRuntime(lowering));
		}
		if (lowering.usedProtocols) {
			// the protocol runtime runs before anything else, like the false value
			lowering.forms.addAll(1, ClojureProtocolLowering.protocolRuntime(lowering));
		}
		if (lowering.usedExInfo) {
			// the ex-info runtime runs before anything else, like the false value
			lowering.forms.addAll(1, ClojureStateLowering.exInfoRuntime(lowering));
		}
		if (!lowering.caughtChains.isEmpty() || lowering.needsExceptionReader()) {
			// the caught classes' predicates, and the exception reader a catch asks
			// even where the program builds no exception
			lowering.forms.addAll(1,
					ClojureThrowables.catchRuntime(lowering.caughtChains.values(), lowering.needsExceptionReader()));
		}
		if (lowering.usedStm) {
			// the STM runtime runs before anything else, like the false value
			lowering.forms.addAll(1, ClojureStateLowering.stmRuntime(lowering));
		}
		if (!lowering.usedSpecials.isEmpty()) {
			// the specials are special before anything binds them
			lowering.forms.addAll(1, ClojureCoreSpecials.definitions(lowering, lowering.usedSpecials));
		}
		if (lowering.usedTest) {
			// the test runtime starts before anything else, like the false value
			lowering.forms.addAll(1, ClojureTestLowering.testRuntime(lowering));
		}
		if (lowering.usedReader) {
			// every class of the program is readable before anything runs, like
			// the false value: a record literal names one a later file defines too
			LispVal registration = ClojureReadLowering.registration(lowering.types.values());
			if (registration != null) {
				lowering.forms.add(1, registration);
			}
		}
		// every unbound var holds its root before anything runs, like the false value
		lowering.forms.addAll(1, ClojureVarLowering.unboundRootInits(lowering.unboundRoots));
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
		this.session = true;
		this.reader = buffer;
		List<LispVal> datums = buffer.readAll();
		// A buffer's pre-scan must not clobber what earlier buffers already
		// defined: its inits evaluate against the OLD binding (e.g. (def p
		// (memoize p)) after a (defn p ...) captures the function cell), while
		// lowering updates in order to the new one.
		Map<String, Kind> carried = new HashMap<>(this.globals);
		declare(datums);
		for (Map.Entry<String, Kind> kept : carried.entrySet()) {
			this.globals.put(kept.getKey(), kept.getValue());
		}
		List<ClojureTopLevel> out = new ArrayList<>();
		for (LispVal datum : datums) {
			List<LispVal> forms = topLevels(datum, true);
			// an ns form shows nothing, also when its requires loaded namespaces
			// (their forms ride with it)
			boolean ns = ClojureLowerUtil.isNsForm(datum);
			if (!forms.isEmpty() || !ns) {
				out.add(new ClojureTopLevel(evaluated(forms, !ns), !ns));
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
		// a hierarchy meets a host class object once any buffer named the host
		noteHost(out.stream().flatMap(top -> top.forms().stream()).toList());
		// descendants of a class keyword is an exception of the program's runtime
		this.usedExInfo |= this.usedClassChains && this.readsDescendants;
		if (this.usedHierarchy && !this.hierarchyEmitted) {
			// The hierarchy runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(ClojureHierarchyLowering.hierarchyRuntime(this), false));
			this.hierarchyEmitted = true;
			this.classChainsEmitted = this.usedClassChains;
			this.hostClassWalkEmitted = this.hostClassWalk;
		}
		else if (this.usedClassChains) {
			// The class-chain readers join an earlier buffer's hierarchy runtime (their
			// host class versions once a buffer names the host), and the rows this
			// buffer resolved first join the ones before them.
			List<LispVal> forms = new ArrayList<>();
			if (!this.classChainsEmitted || this.hostClassWalk != this.hostClassWalkEmitted) {
				forms.addAll(ClojureHierarchyLowering.classChainRuntime(this));
				this.hostClassWalkEmitted = this.hostClassWalk;
			}
			if (!this.classChainsEmitted) {
				forms.add(ClojureHierarchyLowering.supersForm(pendingClassRows(), false));
				this.classChainsEmitted = true;
			}
			else {
				List<LispVal> fresh = pendingClassRows();
				if (!fresh.isEmpty()) {
					forms.add(ClojureHierarchyLowering.supersForm(fresh, true));
				}
			}
			if (!forms.isEmpty()) {
				out.add(0, new ClojureTopLevel(forms, false));
			}
		}
		if (this.usedProtocols && !this.protocolsEmitted) {
			// The protocol runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(ClojureProtocolLowering.protocolRuntime(this), false));
			this.protocolsEmitted = true;
		}
		if (this.usedMacros && !this.macrosEmitted) {
			// The macro runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(ClojureMacroLowering.macroRuntime(this), false));
			this.macrosEmitted = true;
		}
		if (this.usedExInfo && !this.exInfoEmitted) {
			// The ex-info runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it. Its reader
			// replaces the catch runtime's, should an earlier buffer have carried it.
			out.add(0, new ClojureTopLevel(ClojureStateLowering.exInfoRuntime(this), false));
			this.exInfoEmitted = true;
		}
		else if (this.exInfoEmitted && ClojureStateLowering.hostExceptions(this)
				&& !this.hostExceptionClassesEmitted.containsAll(this.hostExceptionClasses)) {
			// A buffer building an exception of a class the host builder misses yet
			// defines the builder again, ahead of itself.
			out.add(0, new ClojureTopLevel(List.of(ClojureStateLowering.hostExceptionOf(this)), false));
		}
		List<List<String>> freshCatches = new ArrayList<>();
		for (Map.Entry<String, List<String>> caught : this.caughtChains.entrySet()) {
			if (this.emittedCatches.add(caught.getKey())) {
				freshCatches.add(caught.getValue());
			}
		}
		boolean reader = needsExceptionReader() && !this.exInfoEmitted && !this.catchReaderEmitted;
		if (!freshCatches.isEmpty() || reader) {
			// The predicates of the classes this buffer catches first travel ahead
			// of it, with the exception reader while no buffer built an exception
			// (the exception runtime's own reader replaces it once one does).
			out.add(0, new ClojureTopLevel(ClojureThrowables.catchRuntime(freshCatches, reader), false));
			this.catchReaderEmitted |= reader;
		}
		if (this.usedStm && !this.stmEmitted) {
			// The STM runtime travels ahead of the buffer that first needs
			// it, like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(ClojureStateLowering.stmRuntime(this), false));
			this.stmEmitted = true;
		}
		Set<String> freshSpecials = new LinkedHashSet<>(this.usedSpecials);
		freshSpecials.removeAll(this.emittedSpecials);
		if (!freshSpecials.isEmpty()) {
			// The specials travel ahead of the buffer that first uses one, like
			// the false binding.
			out.add(0, new ClojureTopLevel(ClojureCoreSpecials.definitions(this, freshSpecials), false));
			this.emittedSpecials.addAll(freshSpecials);
		}
		if (this.usedTest && !this.testEmitted) {
			// The test runtime starts ahead of the buffer that first needs it,
			// like the false binding; later buffers reuse it.
			out.add(0, new ClojureTopLevel(ClojureTestLowering.testRuntime(this), false));
			this.testEmitted = true;
		}
		if (this.usedReader) {
			// The classes this buffer adds or redefines become readable ahead of
			// it, like the false binding; earlier buffers registered theirs.
			List<TypeDef> fresh = new ArrayList<>();
			for (TypeDef type : this.types.values()) {
				if (this.readRegistered.get(type.className()) != type) {
					fresh.add(type);
					this.readRegistered.put(type.className(), type);
				}
			}
			LispVal registration = ClojureReadLowering.registration(fresh);
			if (registration != null) {
				out.add(0, new ClojureTopLevel(List.of(registration), false));
			}
		}
		return out;
	}

	/**
	 * One input's forms the way the oracle's REPL evaluates it: its value recorded as
	 * {@code *1} (the earlier ones moving to {@code *2} and {@code *3}; an {@code ns}
	 * records nil), an exception it throws recorded as {@code *e} on its way to the
	 * report ({@code clojure.lisp}, {@code %clojure-repl-result} and
	 * {@code %clojure-repl-error}). An input lowering to nothing records nothing.
	 * @param forms the input's forms
	 * @param echoes whether its value is shown
	 * @return the one form evaluating them
	 */
	private static List<LispVal> evaluated(List<LispVal> forms, boolean echoes) {
		if (forms.isEmpty()) {
			return forms;
		}
		List<LispVal> body = new ArrayList<>();
		body.add(ClojureLowerUtil.sym("progn"));
		body.addAll(forms);
		if (!echoes) {
			body.add(NIL_CONST);
		}
		LispVal handler = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"), ClojureLowerUtil
			.list(ClojureLowerUtil.sym("function"), new LispSymbol("RONTOLISP::%CLOJURE-REPL-ERROR")));
		return List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("handler-bind"), ClojureLowerUtil.list(handler),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-REPL-RESULT"), ClojureLowerUtil.list(body))));
	}

	void declare(List<LispVal> datums) {
		// pass one: every top-level name, so a definition may use one below it; an
		// ns form moves the namespace a record's class name takes, and pass two
		// starts over from the namespace this pass started in
		String ns = this.currentNs;
		try {
			for (LispVal datum : datums) {
				try {
					declareOne(datum);
					scanDynamicDefinitions(datum);
				}
				catch (LispReadException ex) {
					throw positioned(ex, datum);
				}
			}
		}
		finally {
			this.currentNs = ns;
		}
	}

	/**
	 * Records into {@link #shadowedSpecials} every {@code ^:dynamic} {@code def},
	 * {@code defn} or {@code defonce} of {@code user} the datum holds, at any depth: what
	 * a local of that name must not bind, wherever the definition stands.
	 */
	private void scanDynamicDefinitions(LispVal datum) {
		List<LispVal> items = ClojureLowerUtil.items(datum);
		if (items == null) {
			return;
		}
		if (items.size() >= 2 && items.get(0) instanceof LispSymbol head
				&& (head.name().equals("def") || head.name().equals("defn") || head.name().equals("defn-")
						|| head.name().equals("defonce"))
				&& ClojureLowerUtil.nameIsDynamic(items.get(1))
				&& ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol name
				&& varSym(varKey(this.currentNs, name.name())).equals(ClojureLowerUtil.idSym(name.name()))) {
			this.shadowedSpecials.add(name.name());
		}
		for (LispVal item : items) {
			scanDynamicDefinitions(item);
		}
	}

	void declareOne(LispVal datum) {
		List<LispVal> items = ClojureLowerUtil.items(datum);
		if (items == null || items.size() < 2) {
			return;
		}
		if (items.get(0) instanceof LispSymbol head && scannedMacro(head.name())) {
			// a program macro defined above shadows a definition head like declare
			// or defstruct: what it defines is its expansion's business
			return;
		}
		if (ClojureLowerUtil.isSymbolNamed(items.get(0), "ns")
				&& ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol name) {
			this.currentNs = name.name();
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "in-ns") && items.size() == 2) {
			String switched = ClojureNamespaceLowering.inNsName(items.get(1));
			if (switched != null) {
				this.currentNs = switched;
			}
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "def")) {
			String key = preDeclare(items.get(1), "def", Kind.VARIABLE, false);
			if (items.size() == 2) {
				this.unboundCapable.add(key);
			}
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defn")
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), "defn-")) {
			// a ^:dynamic defn holds its function in the value cell (like a def),
			// so even a forward call routes through it and sees a binding
			preDeclare(items.get(1), "defn",
					ClojureLowerUtil.nameIsDynamic(items.get(1)) ? Kind.VARIABLE : Kind.FUNCTION,
					ClojureLowerUtil.isSymbolNamed(items.get(0), "defn-"));
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defonce")) {
			preDeclare(items.get(1), "defonce", Kind.VARIABLE, false);
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defstruct")) {
			preDeclare(items.get(1), "defstruct", Kind.VARIABLE, false);
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defmulti")) {
			preDeclare(items.get(1), "defmulti", Kind.FUNCTION, false);
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defprotocol")) {
			ClojureProtocolLowering.declareProtocol(this, items);
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defrecord")
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), "deftype")) {
			ClojureProtocolLowering.declareRecordType(this, items);
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defmacro")) {
			// a macro name, so a call above its definition names the missing
			// expander instead of an unknown name; the definition still runs in
			// order, like the oracle's compile (a core-named one stays invisible
			// until then, see pendingCoreMacro); a name the definition refuses
			// registers nothing, so the refusal is what the program reports
			if (!(ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol name)
					|| !ClojureMacroLowering.isReservedHead(name.name())) {
				preDeclare(items.get(1), "defmacro", Kind.MACRO, false);
			}
		}
		else if (ClojureTestLowering.isDeftestSpelling(items.get(0))) {
			// a clojure.test definition is a zero-argument function: (name) runs
			// the test, like the oracle
			preDeclare(items.get(1), "deftest", Kind.FUNCTION, false);
		}
		else if (ClojureLowerUtil.isSymbolNamed(items.get(0), "declare")) {
			// a forward declaration: later buffers (and later forms) may call
			// what is only defined below; a real definition still wins
			for (int i = 1; i < items.size(); i++) {
				String key = internDeclared(ClojureLowerUtil.plainName(items.get(i), "declare"),
						ClojureLowerUtil.nameIsPrivate(items.get(i)));
				this.globals.putIfAbsent(key, Kind.DECLARED);
				this.unboundCapable.add(key);
			}
		}
	}

	/**
	 * Whether the pre-scan has met a macro of this unqualified name above the current
	 * form: the current namespace's own, or one it refers.
	 */
	private boolean scannedMacro(String name) {
		if (qualifierSlash(name) >= 0) {
			return false;
		}
		if (this.globals.get(varKey(this.currentNs, name)) == Kind.MACRO) {
			return true;
		}
		VarRef referred = ns().refers.get(name);
		return referred != null && this.globals.get(varKey(referred.ns(), referred.var())) == Kind.MACRO;
	}

	/**
	 * The pre-scan half of a definition: the name interned in the current namespace under
	 * its kind, so a form above the definition (or a later buffer) resolves it.
	 */
	private String preDeclare(LispVal nameDatum, String what, Kind kind, boolean privateHead) {
		String name = ClojureLowerUtil.plainName(nameDatum, what);
		String key = internName(name, privateHead || ClojureLowerUtil.nameIsPrivate(nameDatum));
		this.globals.put(key, kind);
		return key;
	}

	/**
	 * The first {@code ns} name among a file's datums: the namespace its path is laid out
	 * under, which names its source root.
	 */
	static @Nullable String firstNsName(List<LispVal> datums) {
		for (LispVal datum : datums) {
			List<LispVal> items = ClojureLowerUtil.items(datum);
			if (items != null && items.size() >= 2 && ClojureLowerUtil.isSymbolNamed(items.get(0), "ns")
					&& ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol name) {
				return name.name();
			}
		}
		return null;
	}

	ClojureLowering() {
		this.scopes.add(new HashMap<>()); // locals; globals live in globals
		this.directScopes.add(new HashSet<>());
	}

	/** The var key of a name in a namespace: {@code ns/name}. */
	static String varKey(String ns, String name) {
		return ns + "/" + name;
	}

	/**
	 * The symbol a var lowers to: behind the prefix and qualified by its namespace --
	 * except {@code user}'s, which keep the bare mangled name, so a program without an
	 * {@code ns} lowers as it always did and a Common Lisp file calls its functions as
	 * {@code c%name}.
	 */
	static LispSymbol varSym(String key) {
		return key.startsWith("user/") ? ClojureLowerUtil.idSym(key.substring("user/".length()))
				: ClojureLowerUtil.idSym(key);
	}

	/**
	 * The function cell of a {@code defn} definition: the bare var symbol for its first
	 * definition, a fresh internal name per redefinition. The single-{@code %} suffix
	 * keeps the versions apart from user definitions (a lone {@code %} no mangled
	 * identifier spells) and from the multi-arity helpers (whose suffix is a count or
	 * {@code *}), so a redefined multi-arity {@code defn} never collides with its own
	 * helpers.
	 * @param key the var key
	 * @param n the 1-based definition number
	 * @return the function-cell symbol of that definition
	 */
	static LispSymbol defnSym(String key, int n) {
		return n <= 1 ? varSym(key) : new LispSymbol(varSym(key).name() + "%def" + n);
	}

	/**
	 * The function cell a {@code defn} name currently names: the latest definition's
	 * symbol once the name is redefined, the bare var symbol otherwise (a forward
	 * reference, a single definition, or any non-{@code defn} function, which never
	 * versions).
	 * @param key the var key
	 * @return the current function-cell symbol
	 */
	LispSymbol currentDefnSym(String key) {
		return defnSym(key, this.defnCounts.getOrDefault(key, 0));
	}

	/** The current namespace's state, made on first use. */
	ClojureNsState ns() {
		return this.namespaces.computeIfAbsent(this.currentNs, n -> new ClojureNsState());
	}

	/**
	 * A definition of the name in the current namespace: interned there (private or not),
	 * replacing a refer of the same name -- the oracle warns and replaces.
	 * @param name the plain name
	 * @param isPrivate whether the definition is private
	 * @return its var key
	 */
	String intern(String name, boolean isPrivate) {
		String key = internName(name, isPrivate);
		this.multimethods.remove(key);
		return key;
	}

	/** {@link #intern} without ending a multimethod: the pre-scan defines nothing. */
	private String internName(String name, boolean isPrivate) {
		ClojureNsState here = ns();
		here.interns.put(name, isPrivate);
		here.refers.remove(name);
		return varKey(this.currentNs, name);
	}

	/**
	 * A {@code declare} of the name in the current namespace: interned like a definition,
	 * but never changing what a real definition already said about it.
	 * @param name the plain name
	 * @param isPrivate whether the declaration is private
	 * @return its var key
	 */
	String internDeclared(String name, boolean isPrivate) {
		ClojureNsState here = ns();
		here.interns.putIfAbsent(name, isPrivate);
		here.refers.remove(name);
		return varKey(this.currentNs, name);
	}

	/** Whether a namespace's var is private. */
	boolean isPrivateVar(String ns, String name) {
		ClojureNsState state = this.namespaces.get(ns);
		return state != null && Boolean.TRUE.equals(state.interns.get(name));
	}

	/**
	 * Where the namespace part of a qualified name ends: the one {@code /} between a
	 * non-empty namespace and a non-empty name, or -1 for an unqualified name.
	 */
	static int qualifierSlash(String name) {
		int slash = name.indexOf('/');
		if (slash <= 0 || slash == name.length() - 1 || name.indexOf('/', slash + 1) >= 0) {
			return -1;
		}
		return slash;
	}

	/**
	 * The project namespace the head of a qualified name names: an alias of the current
	 * namespace, the current namespace itself, or any namespace an {@code ns} or
	 * {@code in-ns} created, by its full name -- or null (a known library, a class, or
	 * nothing).
	 */
	@Nullable String projectNamespaceOf(String head) {
		String aliased = ns().aliases.get(head);
		if (aliased != null) {
			return ClojureNamespaceLowering.isKnownNamespace(aliased) ? null : aliased;
		}
		if ((head.equals(this.currentNs) || this.createdNamespaces.contains(head))
				&& !ClojureNamespaceLowering.isKnownNamespace(head)) {
			return head;
		}
		return null;
	}

	/**
	 * The var key a name names as a global var of a project namespace, with no regard to
	 * locals or privacy: unqualified, an intern of the current namespace, else a refer to
	 * a project var; qualified, the var of the namespace its head names. Null when it
	 * names none (a known library's var, a builtin, interop, or nothing).
	 */
	@Nullable String lookupVar(String name) {
		int slash = qualifierSlash(name);
		if (slash < 0) {
			String own = varKey(this.currentNs, name);
			if (this.globals.containsKey(own)) {
				return pendingCoreMacro(own, name) ? null : own;
			}
			VarRef referred = ns().refers.get(name);
			if (referred != null && !ClojureNamespaceLowering.isKnownNamespace(referred.ns())) {
				String key = varKey(referred.ns(), referred.var());
				return this.globals.containsKey(key) && !pendingCoreMacro(key, name) ? key : null;
			}
			return null;
		}
		String target = projectNamespaceOf(name.substring(0, slash));
		if (target == null) {
			return null;
		}
		String key = varKey(target, name.substring(slash + 1));
		return this.globals.containsKey(key) ? key : null;
	}

	/**
	 * Whether an unqualified name's var is a macro of a {@code clojure.core} name that
	 * the pre-scan registered but the lowering has not defined yet: invisible until its
	 * definition, so a form above it keeps the core meaning, like the oracle's
	 * form-by-form compile (where the macro's var does not exist yet).
	 */
	boolean pendingCoreMacro(String key, String name) {
		return this.globals.get(key) == Kind.MACRO && !this.macros.containsKey(key) && ClojureCoreNames.contains(name);
	}

	/**
	 * Whether an unqualified name resolves to a {@link #pendingCoreMacro}: the core var
	 * here, a program macro below -- so a syntax-quote spells it
	 * {@code clojure.core/name}, like the oracle's read-time resolution, and its
	 * expansion keeps the core meaning after the macro is defined.
	 */
	boolean shadowedCoreName(String name) {
		if (qualifierSlash(name) >= 0) {
			return false;
		}
		String own = varKey(this.currentNs, name);
		if (this.globals.containsKey(own)) {
			return pendingCoreMacro(own, name);
		}
		VarRef referred = ns().refers.get(name);
		return referred != null && pendingCoreMacro(varKey(referred.ns(), referred.var()), name);
	}

	/**
	 * {@link #lookupVar}, refusing a qualified reference to another namespace's private
	 * var like the oracle's compiler ({@code var: #'ns/name is not public}).
	 */
	@Nullable String resolveVar(String name) {
		String key = lookupVar(name);
		if (key != null && qualifierSlash(name) > 0) {
			int slash = key.indexOf('/');
			String target = key.substring(0, slash);
			if (!target.equals(this.currentNs) && isPrivateVar(target, key.substring(slash + 1))) {
				throw new LispReadException("var: #'" + key + " is not public");
			}
		}
		return key;
	}

	/**
	 * The record or deftype a class spelling names, by var key: the current namespace's
	 * own, an imported or dotted class name matching one's host class name, or -- the
	 * flat lowering's leniency, kept -- the only one of that simple name anywhere. Null
	 * when it names none.
	 */
	@Nullable String typeKeyOf(String name) {
		if (name.indexOf('/') >= 0) {
			return null;
		}
		String own = varKey(this.currentNs, name);
		if (this.types.containsKey(own)) {
			return own;
		}
		String fqn = name.indexOf('.') >= 0 ? name : ns().classNames.get(name);
		if (fqn != null) {
			for (Map.Entry<String, TypeDef> type : this.types.entrySet()) {
				if (type.getValue().className().equals(fqn)) {
					return type.getKey();
				}
			}
			if (name.indexOf('.') >= 0) {
				return null; // a host class
			}
		}
		String only = null;
		for (Map.Entry<String, TypeDef> type : this.types.entrySet()) {
			if (type.getValue().tagSpelling().equals(name)) {
				if (only != null) {
					return null;
				}
				only = type.getKey();
			}
		}
		return only;
	}

	/** The record or deftype a class spelling names ({@link #typeKeyOf}), or null. */
	@Nullable TypeDef typeDefOf(String name) {
		String key = typeKeyOf(name);
		return key == null ? null : this.types.get(key);
	}

	/** Whether a local binding (a parameter, a {@code let} name, ...) holds the name. */
	boolean isLocal(String name) {
		for (Map<String, Kind> scope : this.scopes) {
			if (scope.containsKey(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The symbol a name reads as: a local's own mangled name, else the var it resolves to
	 * -- a redefined {@code defn}'s current definition (see {@link #currentDefnSym}), so
	 * the call sites below each definition call the newest and a value position captures
	 * the definition current at that point -- else the name mangled as written.
	 */
	LispSymbol symOf(String name) {
		if (isLocal(name)) {
			return localSym(name);
		}
		String key = resolveVar(name);
		if (key != null && this.globals.get(key) == Kind.FUNCTION && this.defnCounts.getOrDefault(key, 0) > 1) {
			return currentDefnSym(key);
		}
		return key != null ? varSym(key) : ClojureLowerUtil.idSym(name);
	}

	/**
	 * The symbol a local of this name binds and reads: its own mangled name, or, when
	 * that name is a special variable's ({@link #shadowedSpecials}), the name behind a
	 * lone-{@code %} suffix no identifier spells, so the local binds lexically and a
	 * function called in its scope still reads the var. Every binding site of a local
	 * (parameters, {@code let}, {@code loop}, destructuring, {@code letfn}, ...) and
	 * {@link #symOf} go through here.
	 * @param name the local's plain name
	 * @return the symbol
	 */
	LispSymbol localSym(String name) {
		if (this.shadowedSpecials.contains(name)) {
			return new LispSymbol(mangle(name) + "%local");
		}
		return ClojureLowerUtil.idSym(name);
	}

	/**
	 * Whether a variable name holds a real function: the innermost binding decides, like
	 * {@link #known}.
	 */
	boolean isDirectVar(String name) {
		for (int i = this.scopes.size() - 1; i >= 0; i--) {
			if (this.scopes.get(i).containsKey(name)) {
				return this.directScopes.get(i).contains(name);
			}
		}
		String key = resolveVar(name);
		return key != null && this.globalDirectFuns.contains(key);
	}

	/** Marks the name direct in the innermost scope. */
	void markDirect(String name) {
		this.directScopes.get(this.directScopes.size() - 1).add(name);
	}

	/**
	 * One top-level datum lowered, behind the forms of every namespace its lowering
	 * loaded: a {@code require} anywhere in it loads the namespace before the datum runs.
	 */
	List<LispVal> topLevels(LispVal form) {
		return topLevels(form, false);
	}

	/**
	 * One top-level datum lowered, with the definitions a REPL echoes answering their
	 * var.
	 * @param form the datum
	 * @param echoVars whether a top-level definition answers the var it defined, like the
	 * oracle's REPL; a file's definition shows nothing
	 * @return its forms, the hoisted definitions first
	 */
	List<LispVal> topLevels(LispVal form, boolean echoVars) {
		List<LispVal> outer = this.hoisted;
		boolean outerEcho = this.nestedDefAnswersVar;
		this.hoisted = new ArrayList<>();
		this.nestedDefAnswersVar = echoVars;
		try {
			List<LispVal> own = echoVars ? echoingTopLevelsOf(form) : topLevelsOf(form);
			if (this.hoisted.isEmpty()) {
				return own;
			}
			List<LispVal> all = new ArrayList<>(this.hoisted);
			all.addAll(own);
			return all;
		}
		finally {
			this.hoisted = outer;
			this.nestedDefAnswersVar = outerEcho;
		}
	}

	/**
	 * Lowers a project namespace's file in place, both passes: the definitions ahead of
	 * the top-level datum whose {@code require} loaded them ({@link #hoisted}), the
	 * statements into the namespace's init ({@link #namespaceInits}), which the require
	 * site runs behind the loaded flag. The file starts in the requiring namespace, like
	 * the oracle's {@code load} (its own {@code ns} form switches), and lowers from a
	 * clean cursor -- no local, recur target or {@code try} of the requiring form reaches
	 * it -- restored afterwards.
	 * @param ns the namespace being loaded
	 * @param found its file
	 */
	void loadFile(String ns, ClojureSourcePath.Found found) {
		ClojureReader fileReader = new ClojureReader(found.text(), found.path());
		List<LispVal> datums = fileReader.readAll();
		@Nullable ClojureReader outerReader = this.reader;
		String outerNs = this.currentNs;
		List<Map<String, Kind>> outerScopes = new ArrayList<>(this.scopes);
		List<Set<String>> outerDirect = new ArrayList<>(this.directScopes);
		List<RecurTarget> outerTargets = new ArrayList<>(this.recurTargets);
		Map<String, HostClass> outerHosts = new HashMap<>(this.hostClasses);
		List<Map<String, LispSymbol>> outerGens = new ArrayList<>(this.syntaxGens);
		Set<String> outerInlining = new HashSet<>(this.inliningDispatch);
		boolean outerTail = this.tailPosition;
		int outerTry = this.tryDepth;
		int outerMacroDepth = this.macroDepth;
		boolean outerDispatch = this.inDispatchFn;
		@Nullable String outerTestLocation = this.testLocation;
		boolean outerEcho = this.nestedDefAnswersVar;
		String outerFile = this.loadingFile;
		String outerSourcePath = this.loadingSourcePath;
		this.loadingFile = fileOf(ns);
		this.loadingSourcePath = ClojureSourcePath.lastSegmentOf(this.loadingFile);
		this.nestedDefAnswersVar = false;
		this.scopes.clear();
		this.scopes.add(new HashMap<>());
		this.directScopes.clear();
		this.directScopes.add(new HashSet<>());
		this.recurTargets.clear();
		this.hostClasses.clear();
		this.syntaxGens.clear();
		this.inliningDispatch.clear();
		this.tailPosition = false;
		this.tryDepth = 0;
		this.macroDepth = 0;
		this.inDispatchFn = false;
		this.testLocation = null;
		this.reader = fileReader;
		this.loadingNamespaces.push(ns);
		List<LispVal> loaded = new ArrayList<>();
		List<LispVal> statements = this.namespaceInits.computeIfAbsent(ns, k -> new ArrayList<>());
		try {
			declare(datums);
			for (LispVal datum : datums) {
				if (isInitDef(datum)) {
					// a def/defonce of the namespace runs when the namespace loads
					// (a reload resets a def and keeps a defonce, like the oracle);
					// a dynamic one's declaim and counter ride top-level, where the
					// collectors read them
					statements.addAll(initDefForms(datum, loaded));
				}
				else {
					// like topLevels, but the drained hoisted forms (the namespaces
					// this datum loads) stay top-level while only the datum's own
					// forms join the definitions or the init
					List<LispVal> outer = this.hoisted;
					this.hoisted = new ArrayList<>();
					List<LispVal> own;
					try {
						own = topLevelsOf(datum);
					}
					finally {
						loaded.addAll(this.hoisted);
						this.hoisted = outer;
					}
					if (isDefinitionalDatum(datum)) {
						// a definition's evaluated var metadata runs with the
						// namespace's statements, where the definition stands
						for (LispVal one : own) {
							(ClojureVarLowering.isMetaStore(one) ? statements : loaded).add(one);
						}
					}
					else {
						statements.addAll(own);
					}
				}
			}
			this.loadedNamespaces.add(ns);
		}
		finally {
			this.loadingNamespaces.pop();
			this.reader = outerReader;
			this.currentNs = outerNs;
			this.scopes.clear();
			this.scopes.addAll(outerScopes);
			this.directScopes.clear();
			this.directScopes.addAll(outerDirect);
			this.recurTargets.clear();
			this.recurTargets.addAll(outerTargets);
			this.hostClasses.clear();
			this.hostClasses.putAll(outerHosts);
			this.syntaxGens.clear();
			this.syntaxGens.addAll(outerGens);
			this.inliningDispatch.clear();
			this.inliningDispatch.addAll(outerInlining);
			this.tailPosition = outerTail;
			this.tryDepth = outerTry;
			this.macroDepth = outerMacroDepth;
			this.inDispatchFn = outerDispatch;
			this.testLocation = outerTestLocation;
			this.nestedDefAnswersVar = outerEcho;
			this.loadingFile = outerFile;
			this.loadingSourcePath = outerSourcePath;
		}
		this.hoisted.addAll(loaded);
	}

	/**
	 * How one {@code require}/{@code use} call site (or one {@code ns} clause) loads the
	 * namespaces it names: behind the loaded flag, unconditionally ({@code :reload}), or
	 * with every transitive dependency ({@code :reload-all}).
	 */
	enum LoadMode {

		GUARDED, RELOAD, RELOAD_ALL

	}

	/**
	 * The loaded flag of a namespace: a lone {@code %} followed by a letter other than
	 * {@code c} is spelled by no mangled identifier, so it stays apart from user
	 * definitions.
	 * @param ns the namespace
	 * @return the flag symbol
	 */
	static LispSymbol loadedFlagSym(String ns) {
		return new LispSymbol(mangle(ns) + "%loaded");
	}

	/**
	 * The init driver of a namespace: the lambda calling each init chunk in order.
	 * @param ns the namespace
	 * @return the driver symbol
	 */
	static LispSymbol initSym(String ns) {
		return new LispSymbol(mangle(ns) + "%init");
	}

	/**
	 * One init chunk of a namespace.
	 * @param ns the namespace
	 * @param chunk the 1-based chunk number
	 * @return the chunk symbol
	 */
	static LispSymbol initChunkSym(String ns, int chunk) {
		return new LispSymbol(mangle(ns) + "%init-" + chunk);
	}

	/**
	 * Whether a namespace has statements to run when it loads.
	 * @param ns the namespace
	 * @return {@code true} when its init exists and is non-empty
	 */
	boolean hasInit(String ns) {
		List<LispVal> statements = this.namespaceInits.get(ns);
		if (statements == null) {
			return false;
		}
		for (LispVal statement : statements) {
			// a switch of *ns* alone runs nothing anyone could see it from
			if (!ClojureArms.isSwitch(statement)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The run of one namespace at one call site: its init behind its loaded flag
	 * (guarded), unconditionally ({@code :reload}), or with every transitive dependency
	 * first ({@code :reload-all}), marking each one loaded after it runs. A reload
	 * re-runs the statements, so a {@code def} resets where a {@code defonce} keeps its
	 * value, like the oracle. Null when the namespace has nothing to run (a known
	 * library, the entry's own namespace, or a file of definitions only).
	 * @param ns the namespace
	 * @param mode how it loads
	 * @return the call form, or null
	 */
	@Nullable LispVal requireCall(String ns, LoadMode mode) {
		if (!hasInit(ns)) {
			return null;
		}
		LispSymbol flag = loadedFlagSym(ns);
		LispVal run = loading(ns, ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), initSym(ns)));
		LispVal mark = ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), flag, TRUE_CONST);
		return switch (mode) {
			case GUARDED -> ClojureLowerUtil.list(ClojureLowerUtil.sym("unless"), flag, run, mark);
			case RELOAD -> ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), run, mark);
			case RELOAD_ALL -> {
				// each member runs and marks before the next: a later init's own
				// guarded call to an earlier dependency then skips it, like the
				// oracle's single reload-all pass
				List<LispVal> steps = new ArrayList<>();
				for (String member : reloadClosure(ns)) {
					if (hasInit(member)) {
						steps.add(loading(member,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), initSym(member))));
						steps.add(
								ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), loadedFlagSym(member), TRUE_CONST));
					}
				}
				yield ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), steps);
			}
		};
	}

	/**
	 * A namespace's init run where {@code clojure.main}'s {@code load} binds the load's
	 * specials: {@code *ns*} to itself (the file's {@code ns} switches it, and the
	 * requiring namespace is back afterwards), {@code *file*} to the file's path below
	 * its source root and {@code *source-path*} to its name. The pairs are switches
	 * ({@link ClojureArms.Family#NS_SWITCH} and its siblings), so a program reading none
	 * of the three runs the init as before.
	 * @param ns the namespace
	 * @param run the call of its init
	 * @return the call under the bindings
	 */
	private LispVal loading(String ns, LispVal run) {
		String file = fileOf(ns);
		this.usedSpecials.addAll(List.of("*ns*", "*file*", "*source-path*"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureCoreSpecials.NS, ClojureCoreSpecials.NS),
				ClojureLowerUtil.list(ClojureCoreSpecials.FILE, LispString.literal(file)), ClojureLowerUtil
					.list(ClojureCoreSpecials.SOURCE_PATH, LispString.literal(ClojureSourcePath.lastSegmentOf(file)))),
				run);
	}

	/**
	 * The switch of {@code *ns*} to a namespace an {@code ns} or {@code in-ns} names: a
	 * statement ({@link ClojureArms.Family#NS_SWITCH}) a program reading no {@code *ns*}
	 * sheds.
	 * @param ns the namespace
	 * @return the statement
	 */
	LispVal nsSwitch(String ns) {
		this.usedSpecials.add("*ns*");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureCoreSpecials.NS,
				ClojureCoreSpecials.namespaceObject(ns));
	}

	/**
	 * The namespaces {@code the-ns} and {@code find-ns} find where they lower: the ones
	 * the program created so far, the libraries it required and the ones the oracle loads
	 * first, as a quoted list of names.
	 * @return the lowered list
	 */
	LispVal knownNamespaces() {
		Set<String> known = new java.util.TreeSet<>(this.createdNamespaces);
		known.addAll(this.requiredLibraries);
		known.addAll(STARTUP_NAMESPACES);
		List<LispVal> names = new ArrayList<>();
		for (String name : known) {
			names.add(LispString.literal(name));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(names));
	}

	/**
	 * A namespace with every namespace it requires, transitively, dependencies first and
	 * the namespace itself last, each once.
	 * @param ns the namespace
	 * @return the reload order
	 */
	List<String> reloadClosure(String ns) {
		List<String> out = new ArrayList<>();
		visitReload(ns, new HashSet<>(), out);
		return out;
	}

	private void visitReload(String ns, Set<String> seen, List<String> out) {
		if (!seen.add(ns)) {
			return;
		}
		LinkedHashSet<String> deps = this.namespaceDeps.get(ns);
		if (deps != null) {
			for (String dep : deps) {
				visitReload(dep, seen, out);
			}
		}
		out.add(ns);
	}

	/**
	 * Emits a loaded namespace's run-time definitions once per lowering: the loaded flag
	 * (a {@code defvar}, so a namespace two separately lowered files require still runs
	 * once per process), the init chunks holding its statements, and the driver calling
	 * them in order.
	 * @param ns the namespace just loaded
	 */
	void emitNamespaceInit(String ns) {
		if (!this.initEmitted.add(ns)) {
			return;
		}
		List<LispVal> statements = this.namespaceInits.get(ns);
		if (statements == null || !hasInit(ns)) {
			return;
		}
		List<LispVal> defs = new ArrayList<>();
		defs.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defvar"), loadedFlagSym(ns), NIL_CONST));
		List<LispVal> chunkSyms = new ArrayList<>();
		List<LispVal> chunk = new ArrayList<>();
		int chars = 0;
		boolean statementInChunk = false;
		for (LispVal statement : statements) {
			// a switch weighs nothing and joins the chunk at hand, and the rest weigh
			// what they print without theirs: a program that sheds them cuts its init
			// where it did before they existed
			if (ClojureArms.isSwitch(statement)) {
				chunk.add(statement);
				continue;
			}
			int printed = ClojureArms.withoutSwitches(statement).print().length();
			if (statementInChunk && chars + printed > INIT_CHUNK_TARGET_CHARS) {
				chunkSyms.add(closeInitChunk(ns, chunkSyms.size() + 1, chunk, defs));
				chunk = new ArrayList<>();
				chars = 0;
			}
			chunk.add(statement);
			statementInChunk = true;
			chars += printed;
		}
		chunkSyms.add(closeInitChunk(ns, chunkSyms.size() + 1, chunk, defs));
		List<LispVal> calls = new ArrayList<>();
		for (LispVal chunkSym : chunkSyms) {
			calls.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), chunkSym));
		}
		defs.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), initSym(ns), lambda0(calls)));
		this.hoisted.addAll(defs);
	}

	/**
	 * One init chunk emitted: the chunk symbol holding a lambda over its statements. A
	 * top-level {@code setq} of a lambda (never a {@code defun}, which would hide the
	 * {@code setq}s inside from {@code GlobalVarCollector} and route every call site
	 * through the variable).
	 * @param ns the namespace
	 * @param number the 1-based chunk number
	 * @param chunk its statements
	 * @param defs where the chunk definition goes
	 * @return the chunk symbol
	 */
	private static LispSymbol closeInitChunk(String ns, int number, List<LispVal> chunk, List<LispVal> defs) {
		LispSymbol sym = initChunkSym(ns, number);
		defs.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), sym, lambda0(chunk)));
		return sym;
	}

	/**
	 * A zero-argument lambda over the body forms.
	 * @param body the body
	 * @return the lambda
	 */
	static LispVal lambda0(List<LispVal> body) {
		List<LispVal> parts = new ArrayList<>();
		parts.add(NIL_CONST);
		parts.addAll(body);
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("lambda"), parts);
	}

	/**
	 * A top-level proclamation that the names are special: what a {@code ^:dynamic} var
	 * moved into a namespace init keeps at the head, so {@code SpecialVarCollector} still
	 * sees it there.
	 * @param names the variables
	 * @return the declaim form
	 */
	static LispVal declaimSpecial(LispSymbol... names) {
		List<LispVal> clause = new ArrayList<>();
		clause.add(ClojureLowerUtil.sym("special"));
		clause.addAll(List.of(names));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("declaim"), ClojureLowerUtil.list(clause));
	}

	/**
	 * The call forms with a trailing {@code nil}: what a {@code require}/{@code use}
	 * answers, like the oracle.
	 * @param calls the init calls
	 * @return the call forms plus nil
	 */
	static List<LispVal> withNil(List<LispVal> calls) {
		List<LispVal> out = new ArrayList<>(calls);
		out.add(NIL_CONST);
		return out;
	}

	/**
	 * Whether a top-level datum of a loaded file defines (rather than runs): the branches
	 * of {@link #topLevelsOf} that stay top-level ahead of the requiring form -- the
	 * namespace's {@code defun}s and their companions -- while every other datum becomes
	 * a statement of the namespace's init.
	 * @param datum the datum
	 * @return {@code true} for a definition
	 */
	boolean isDefinitionalDatum(LispVal datum) {
		List<LispVal> items = ClojureLowerUtil.items(datum);
		if (items == null || items.isEmpty() || !(items.get(0) instanceof LispSymbol head)) {
			return false;
		}
		String name = head.name();
		return name.equals("defn") || name.equals("defn-") || name.equals("defmulti") || name.equals("defprotocol")
				|| name.equals("defrecord") || name.equals("deftype") || name.equals("defmacro")
				|| name.equals("defstruct") || ClojureTestLowering.isDeftestHead(this, items.get(0));
	}

	/**
	 * Whether a top-level datum of a loaded file is a {@code def}/{@code defonce}: its
	 * setq runs in the namespace's init, while a dynamic one's declaim and counter ride
	 * top-level.
	 * @param datum the datum
	 * @return {@code true} for a def or defonce
	 */
	static boolean isInitDef(LispVal datum) {
		List<LispVal> items = ClojureLowerUtil.items(datum);
		return items != null && !items.isEmpty() && (ClojureLowerUtil.isSymbolNamed(items.get(0), "def")
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), "defonce"));
	}

	/**
	 * A {@code def}/{@code defonce} datum of a loaded file lowered for the namespace's
	 * init: the setq statement, plus any hoisted top-level forms (the declaim and the
	 * binding-depth counter of a dynamic var, which stay at the head where the collectors
	 * read them).
	 * @param datum the datum
	 * @param hoisted where the hoisted forms go
	 * @return the init statements
	 */
	List<LispVal> initDefForms(LispVal datum, List<LispVal> hoisted) {
		try {
			List<LispVal> items = ClojureLowerUtil.items(datum);
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "def")) {
				return ClojureBindingLowering.defForms(this, datum, items, hoisted);
			}
			if (items == null) {
				throw new LispReadException("defonce takes a name and an optional value");
			}
			return List.of(ClojureStateLowering.defonceForm(this, items, hoisted));
		}
		catch (LispReadException ex) {
			throw positioned(ex, datum);
		}
	}

	/**
	 * {@link #topLevelsOf} for a REPL: a definition (def, defn, defn-, defmacro,
	 * defmulti, defonce, defstruct) answers the var it defined, as the oracle prints
	 * {@code #'user/f}. A {@code defonce} over a bound var and a {@code defmulti} over a
	 * multimethod answer nil. A {@code defprotocol} answers its name, a {@code defrecord}
	 * and a {@code deftype} their class name, a {@code declare} the last name's var.
	 */
	private List<LispVal> echoingTopLevelsOf(LispVal form) {
		List<LispVal> items = ClojureLowerUtil.items(form);
		String name = items == null ? null : definedVarName(items);
		if (items != null && name == null) {
			List<LispVal> answered = echoingNameForms(form, items);
			if (answered != null) {
				return answered;
			}
		}
		if (items == null || name == null) {
			return topLevelsOf(form);
		}
		if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defonce")) {
			try {
				return List.of(ClojureStateLowering.defonceEchoing(this, items,
						() -> ClojureVarLowering.definedVar(this, name)));
			}
			catch (LispReadException ex) {
				throw positioned(ex, form);
			}
		}
		if (ClojureLowerUtil.isSymbolNamed(items.get(0), "defmulti")
				&& this.multimethods.contains(varKey(this.currentNs, name))) {
			// the var already holds a multimethod: nothing is defined, the oracle answers
			// nil
			return topLevelsOf(form);
		}
		List<LispVal> forms = new ArrayList<>(topLevelsOf(form));
		forms.add(ClojureVarLowering.definedVar(this, name));
		return forms;
	}

	/**
	 * The echo of a {@code defprotocol}, {@code defrecord}, {@code deftype} or
	 * {@code declare}: the protocol's name, the class name ({@code ns.Name}, the
	 * namespace munged), the var of the last declared name. Null for any other form.
	 */
	private @Nullable List<LispVal> echoingNameForms(LispVal form, List<LispVal> items) {
		if (items.size() < 2 || !(items.get(0) instanceof LispSymbol head)) {
			return null;
		}
		boolean protocol = head.name().equals("defprotocol");
		boolean type = head.name().equals("defrecord") || head.name().equals("deftype");
		boolean declare = head.name().equals("declare");
		if (!(protocol || type || declare)) {
			return null;
		}
		String ns = this.currentNs;
		List<LispVal> forms = new ArrayList<>(topLevelsOf(form));
		try {
			String last = ClojureLowerUtil.plainName(items.get(declare ? items.size() - 1 : 1), head.name());
			if (declare) {
				forms.add(ClojureVarLowering.definedVar(this, last));
			}
			else {
				forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"),
						new LispSymbol(protocol ? last : ns.replace('-', '_') + "." + last)));
			}
		}
		catch (LispReadException ex) {
			throw positioned(ex, form);
		}
		return forms;
	}

	private static @Nullable String definedVarName(List<LispVal> items) {
		if (items.size() < 2 || !(items.get(0) instanceof LispSymbol head)) {
			return null;
		}
		String op = head.name();
		if (!(op.equals("def") || op.equals("defn") || op.equals("defn-") || op.equals("defmacro")
				|| op.equals("defmulti") || op.equals("defonce") || op.equals("defstruct"))) {
			return null;
		}
		return ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol name && !name.name().startsWith(":")
				&& !name.name().equals("&") ? name.name() : null;
	}

	private List<LispVal> topLevelsOf(LispVal form) {
		try {
			if (ClojureLowerUtil.isNsForm(form)) {
				// wires the aliases and defines nothing itself; its clauses'
				// require calls run here, in program order
				List<LispVal> calls = ClojureNamespaceLowering.processNs(this, form);
				this.namespacesSeen.add(this.currentNs);
				return calls;
			}
			List<LispVal> items = ClojureLowerUtil.items(form);
			if (items != null && !items.isEmpty() && (ClojureLowerUtil.isSymbolNamed(items.get(0), "defn")
					|| ClojureLowerUtil.isSymbolNamed(items.get(0), "defn-"))) {
				return ClojureBindingLowering.defuns(this, form, items);
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "def")) {
				// a ^:dynamic def contributes its defparameter plus its
				// binding-depth counter as two top-level forms, so both keep
				// their head for SpecialVarCollector
				return ClojureBindingLowering.defForms(this, form, items, null);
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "defmulti")) {
				return ClojureDispatchLowering.defmultiForms(this, items);
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "defprotocol")) {
				return ClojureProtocolLowering.defprotocolForms(this, items);
			}
			if (items != null && !items.isEmpty() && (ClojureLowerUtil.isSymbolNamed(items.get(0), "defrecord")
					|| ClojureLowerUtil.isSymbolNamed(items.get(0), "deftype"))) {
				return ClojureProtocolLowering.recordTypeForms(this, items);
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "extend-protocol")) {
				return List.of(ClojureProtocolLowering.extendProtocolForm(this, items));
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "extend-type")) {
				return List.of(ClojureProtocolLowering.extendTypeForm(this, items));
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "extend")) {
				return List.of(ClojureProtocolLowering.extendForm(this, items));
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "defmacro")) {
				return ClojureMacroLowering.defmacroForms(this, form, items);
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "defonce")) {
				return List.of(ClojureStateLowering.defonceForm(this, items));
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "defstruct")) {
				return List.of(ClojureStateLowering.defstructForm(this, items));
			}
			if (items != null && !items.isEmpty() && ClojureTestLowering.isDeftestHead(this, items.get(0))) {
				return ClojureTestLowering.deftestForms(this, items, form);
			}
			if (items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "set!")) {
				// runs before the next top-level form expands, like the oracle's
				LispVal lowered = lower(form);
				Boolean flag = ClojureProtocolLowering.assertSetTo(this, items);
				if (flag != null) {
					this.assertEnabled = flag;
				}
				return List.of(lowered);
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
	LispVal lower(LispVal form) {
		boolean outer = this.tailPosition;
		this.tailPosition = false;
		try {
			return lowerPositioned(form);
		}
		finally {
			this.tailPosition = outer;
		}
	}

	LispVal lowerPositioned(LispVal form) {
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
	LispReadException positioned(LispReadException ex, LispVal datum) {
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

	LispVal lowerInner(LispVal form) {
		if (!(form instanceof LispCons)) {
			return atom(form);
		}
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null) {
			throw new LispReadException("a dotted list is not a Clojure form");
		}
		if (items.isEmpty()) {
			return NIL_CONST;
		}
		if (items.get(0) instanceof LispSymbol op) {
			String core = ClojureCoreNames.coreSpelling(op.name());
			if (core == null) {
				core = renamedCore(op.name());
			}
			if (core != null) {
				// clojure.core/name: the core meaning whatever the program defines
				// under that name, like the oracle's qualified var
				List<LispVal> coreItems = new ArrayList<>(items);
				coreItems.set(0, new LispSymbol(core));
				return lowerRow(form, coreItems, true);
			}
			if (!ClojureMacroLowering.isReservedHead(op.name())) {
				// a program macro wins over every lowering row of its name once
				// defined (a core-named one above its definition is not visible
				// yet, so the row lowers there, like the oracle's per-form compile)
				LispVal macro = ClojureMacroLowering.macroCall(this, op.name(), items);
				if (macro != null) {
					return macro;
				}
			}
		}
		return lowerRow(form, items, false);
	}

	/**
	 * The core name a {@code (:refer-clojure :rename {old new})} spelling stands for, or
	 * null: a local or a var of the program under the same name wins, like any refer.
	 */
	@Nullable String renamedCore(String name) {
		Map<String, String> renames = ns().coreRenames;
		if (renames.isEmpty()) {
			return null;
		}
		String core = renames.get(name);
		return core != null && !known(name) ? core : null;
	}

	/**
	 * A {@code defn} in a body: a single defun (a dynamic single-arity one its defun plus
	 * its defparameters) splices behind a progn, like ever; several arities cannot splice
	 * into expression position.
	 */
	LispVal defnInBody(LispVal form, List<LispVal> items) {
		List<LispVal> forms = ClojureBindingLowering.defuns(this, form, items);
		if (forms.size() == 1) {
			return forms.get(0);
		}
		boolean single = forms.stream().filter(ClojureLowering::isLoweredDefun).count() == 1 && forms.stream()
			.allMatch(f -> ClojureLowering.isLoweredDefun(f) || isLoweredDefparameter(f)
					|| ClojureVarLowering.isMetaStore(f));
		ClojureLowerUtil.isTrue(single, "a multi-arity defn is only allowed at the top level");
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms);
	}

	/** Whether the lowered form is headed by {@code defun}. */
	static boolean isLoweredDefun(LispVal form) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		return parts != null && !parts.isEmpty() && ClojureLowerUtil.isSymbolNamed(parts.get(0), "DEFUN");
	}

	/** Whether the lowered form is headed by {@code defparameter}. */
	static boolean isLoweredDefparameter(LispVal form) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		return parts != null && !parts.isEmpty() && ClojureLowerUtil.isSymbolNamed(parts.get(0), "DEFPARAMETER");
	}

	/**
	 * A form whose head names no program macro: the lowering rows, then a call.
	 * @param form the form
	 * @param items its items
	 * @param coreOnly whether the head was spelled {@code clojure.core/name}, so no
	 * program definition of the name is consulted
	 * @return the lowered form
	 */
	LispVal lowerRow(LispVal form, List<LispVal> items, boolean coreOnly) {
		LispVal head = items.get(0);
		if (ClojureLowerUtil.isSymbolNamed(head, "quote")) {
			ClojureLowerUtil.isTrue(items.size() == 2, "quote takes one form");
			return quote(items.get(1));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "def")) {
			return ClojureBindingLowering.def(this, form, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defonce")) {
			return ClojureStateLowering.defonceForm(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defstruct")) {
			return ClojureStateLowering.defstructForm(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "struct")) {
			return ClojureStateLowering.structOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "struct-map")) {
			return ClojureStateLowering.structMapOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defn")) {
			return defnInBody(form, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defn-")) {
			// private by convention only: metadata never affects dispatch
			return defnInBody(form, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defmacro")) {
			// in a body: a single progn, like ever; the expander registers when
			// reached, the table entry runs with the body
			List<LispVal> forms = ClojureMacroLowering.defmacroForms(this, form, items);
			ClojureLowerUtil.isTrue(forms.size() == 1, "a defmacro lowers to one form");
			return forms.get(0);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "fn") || ClojureLowerUtil.isSymbolNamed(head, "fn*")) {
			return capturingMutableFields(() -> ClojureBindingLowering.fn(this, items));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "let")) {
			return ClojureBindingLowering.let(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "loop")) {
			return ClojureBindingLowering.loop(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "letfn")) {
			return ClojureBindingLowering.letfn(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "declare")) {
			return ClojureVarLowering.declareForm(this, form, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "->")) {
			return ClojureLoopLowering.threadFirst(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "->>")) {
			return ClojureLoopLowering.threadLast(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "as->")) {
			return ClojureLoopLowering.threadAs(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "doto")) {
			return ClojureLoopLowering.dotoOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "cond->")) {
			return ClojureLoopLowering.condThread(this, items, false);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "cond->>")) {
			return ClojureLoopLowering.condThread(this, items, true);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "some->")) {
			return ClojureLoopLowering.someThread(this, items, false);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "some->>")) {
			return ClojureLoopLowering.someThread(this, items, true);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "list*")) {
			return ClojureLoopLowering.listStar(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "doseq")) {
			return ClojureLoopLowering.doseqOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "dotimes")) {
			return ClojureLoopLowering.dotimesOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "for")) {
			return capturingMutableFields(() -> ClojureLoopLowering.forOf(this, items));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defmulti")) {
			List<LispVal> forms = ClojureDispatchLowering.defmultiForms(this, items);
			return forms.size() == 1 ? forms.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defmethod")) {
			return ClojureDispatchLowering.defmethodForm(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "remove-method")) {
			return ClojureDispatchLowering.removeMethodOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "get-method")) {
			return ClojureDispatchLowering.getMethodOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "prefer-method")) {
			return ClojureHierarchyLowering.preferMethodOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "derive") || ClojureLowerUtil.isSymbolNamed(head, "underive")
				|| ClojureLowerUtil.isSymbolNamed(head, "isa?") || ClojureLowerUtil.isSymbolNamed(head, "parents")
				|| ClojureLowerUtil.isSymbolNamed(head, "ancestors")
				|| ClojureLowerUtil.isSymbolNamed(head, "descendants")
				|| ClojureLowerUtil.isSymbolNamed(head, "make-hierarchy")) {
			return ClojureHierarchyLowering.hierarchyOp(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defprotocol")) {
			List<LispVal> forms = ClojureProtocolLowering.defprotocolForms(this, items);
			return forms.size() == 1 ? forms.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "defrecord") || ClojureLowerUtil.isSymbolNamed(head, "deftype")) {
			List<LispVal> forms = ClojureProtocolLowering.recordTypeForms(this, items);
			return forms.size() == 1 ? forms.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "extend-protocol")) {
			return ClojureProtocolLowering.extendProtocolForm(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "extend-type")) {
			return ClojureProtocolLowering.extendTypeForm(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "extend")) {
			return ClojureProtocolLowering.extendForm(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "reify")) {
			return capturingMutableFields(() -> ClojureProtocolLowering.reifyForm(this, items));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "satisfies?")) {
			return ClojureProtocolLowering.satisfiesOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "definterface") || ClojureLowerUtil.isSymbolNamed(head, "gen-class")
				|| ClojureLowerUtil.isSymbolNamed(head, "gen-interface")) {
			throw new LispReadException("protocols are not supported yet: " + ((LispSymbol) head).name());
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "try")) {
			return ClojureStateLowering.tryOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "throw")) {
			ClojureLowerUtil.isTrue(items.size() == 2, "throw takes one form");
			return ClojureStateLowering.exReaderOf(this, items, ClojureStateLowering.THROW);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "ex-info")) {
			return ClojureStateLowering.exInfoOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "ex-data")) {
			return ClojureStateLowering.exReaderOf(this, items, ClojureStateLowering.EX_DATA);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "ex-message")) {
			return ClojureStateLowering.exReaderOf(this, items, ClojureStateLowering.EX_MESSAGE);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "ex-cause")) {
			return ClojureStateLowering.exReaderOf(this, items, ClojureStateLowering.EX_CAUSE);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "atom")) {
			return ClojureStateLowering.atomOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "deref")) {
			return ClojureStateLowering.derefOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "swap!")) {
			return ClojureStateLowering.swapOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "reset!")) {
			return ClojureStateLowering.resetOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "compare-and-set!")) {
			return ClojureStateLowering.compareAndSetOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "volatile!")) {
			ClojureLowerUtil.isTrue(items.size() == 2, "volatile! takes an initial value");
			return ClojureStateLowering.wrapVolatile(lower(items.get(1)));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "ref")) {
			return ClojureStateLowering.refOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "dosync")) {
			return capturingMutableFields(() -> ClojureStateLowering.dosyncOf(this, items));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "alter")) {
			return ClojureStateLowering.alterOf(this, items, "alter");
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "commute")) {
			return ClojureStateLowering.alterOf(this, items, "commute");
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "ref-set")) {
			return ClojureStateLowering.refSetOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "ensure")) {
			return ClojureStateLowering.ensureOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "agent")) {
			return ClojureStateLowering.agentOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "send") || ClojureLowerUtil.isSymbolNamed(head, "send-off")) {
			return ClojureStateLowering.sendOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "await")) {
			return ClojureStateLowering.awaitOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "shutdown-agents")) {
			ClojureLowerUtil.isTrue(items.size() == 1, "shutdown-agents takes no arguments");
			return NIL_CONST;
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "binding")) {
			return ClojureStateLowering.bindingOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "with-open")) {
			return ClojureStateLowering.withOpenOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "with-out-str")) {
			return ClojureStateLowering.withOutStrOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "with-in-str")) {
			return ClojureStateLowering.withInStrOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "time")) {
			return ClojureStateLowering.timeOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "vreset!")) {
			return ClojureStateLowering.resetOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "vswap!")) {
			return ClojureStateLowering.swapOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "add-watch") || ClojureLowerUtil.isSymbolNamed(head, "remove-watch")) {
			throw new LispReadException(
					((LispSymbol) head).name() + " is not supported yet: atom watches need a design");
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "comment")) {
			return NIL_CONST;
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "require")) {
			List<LispVal> calls = ClojureNamespaceLowering.requireSpecs(this,
					ClojureNamespaceLowering.specsOf(items, "require"), false);
			return calls.isEmpty() ? NIL_CONST : ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), withNil(calls));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "load")
				&& (coreOnly || !known("load") && ClojureNamespaceLowering.coreAllowed(this, "load"))) {
			List<LispVal> calls = ClojureNamespaceLowering.loadForms(this, items.subList(1, items.size()));
			return calls.isEmpty() ? NIL_CONST : ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), withNil(calls));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "use")) {
			List<LispVal> calls = ClojureNamespaceLowering.requireSpecs(this,
					ClojureNamespaceLowering.specsOf(items, "use"), true);
			return calls.isEmpty() ? NIL_CONST : ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), withNil(calls));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "import")) {
			ClojureNamespaceLowering.importSpecs(this, ClojureNamespaceLowering.specsOf(items, "import"));
			return NIL_CONST;
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "in-ns")) {
			LispVal switched = ClojureNamespaceLowering.inNsOf(this, items);
			this.namespacesSeen.add(this.currentNs);
			return switched;
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "set!")) {
			return ClojureProtocolLowering.setBangOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "memfn")) {
			return ClojureInteropLowering.memfnOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "proxy")) {
			return capturingMutableFields(() -> ClojureInteropLowering.proxyOf(this, items));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "proxy-super")) {
			return ClojureInteropLowering.proxySuperOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "new")) {
			return ClojureInteropLowering.newOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "syntax-quote")) {
			ClojureLowerUtil.isTrue(items.size() == 2, "syntax-quote takes one form");
			return ClojureMacroLowering.syntaxQuote(this, items.get(1));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "unquote")
				|| ClojureLowerUtil.isSymbolNamed(head, "unquote-splicing")) {
			throw new LispReadException(((LispSymbol) head).name()
					+ " outside syntax-quote: `~` and `~@` only unquote inside a syntax-quote");
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "var")) {
			return ClojureVarLowering.varOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, ClojureLowerUtil.READER_META)) {
			return ClojureCoreLowering.readerMetaOf(this, form);
		}
		if (head instanceof LispCons) {
			LispVal tags = ClojureInteropLowering.paramTags(head);
			if (tags != null && ClojureLowerUtil.stripMeta(head) instanceof LispSymbol member
					&& hostMemberName(member.name())) {
				// (^[types] Class/member args...): the tags name the overload
				LispVal tagged = ClojureInteropLowering.memberCall(this, member.name(), items, tags);
				if (tagged != null) {
					return tagged;
				}
			}
			if (ClojureSeqLowering.isCollectionHead(head)) {
				return ClojureSeqLowering.collectionCall(this, items);
			}
			if (ClojureVarLowering.isVarForm(head)) {
				return ClojureVarLowering.callOf(this, items);
			}
			return computedHeadCall(items);
		}
		if (head == ClojureReader.VECTOR) {
			return ClojureLowerUtil.cons(ClojureLowerUtil.sym("vector"), lowers(items, 1));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "%hash-map")) {
			return ClojureCollectionLowering.mapBuild(lowers(items, 1));
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "%hash-set")) {
			return ClojureCollectionLowering.setBuild(this, lowers(items, 1));
		}
		if (head == ClojureReader.REGEX || ClojureLowerUtil.isSymbolNamed(head, "%regex")) {
			return ClojureCollectionLowering.regexForm(items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "%record")) {
			return ClojureProtocolLowering.recordLiteral(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "if")) {
			ClojureLowerUtil.isTrue(items.size() == 3 || items.size() == 4,
					"if takes a condition, a then and an optional else");
			LispVal test = lower(items.get(1));
			LispVal then = lowerTailSlot(items.get(2));
			LispVal elseForm = items.size() == 4 ? lowerTailSlot(items.get(3)) : NIL_CONST;
			return ifFalsey(test, then, elseForm);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "when")) {
			ClojureLowerUtil.isTrue(items.size() >= 3, "when needs a condition and a body");
			LispVal test = lower(items.get(1));
			return ifFalsey(test, body(items, 2), NIL_CONST);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "when-let")) {
			return ClojureSeqLowering.whenLetOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "if-let")) {
			return ClojureSeqLowering.ifLetOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "when-not")) {
			return ClojureSeqLowering.whenNotOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "if-not")) {
			return ClojureSeqLowering.ifNotOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "when-first")) {
			return ClojureSeqLowering.whenFirstOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "cond")) {
			return ClojureSeqLowering.condOf(this, items);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "do")) {
			return body(items, 1);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "recur")) {
			return ClojureBindingLowering.recurOf(this, form, items);
		}
		return call(form, items, coreOnly);
	}

	/**
	 * One parameter vector lowered: the real lambda-list items, the destructuring
	 * prologue (flat {@code (symbol init)} pairs, sequential) and the shape facts a
	 * dispatch needs. Plain names are registered in {@code scope}; generated temporaries
	 * need no registration (nothing looks them up by name).
	 */
	record Clause(List<LispVal> params, List<LispVal> prologue, LispVal body, boolean variadic, int fixed) {

		LispVal wrapped() {
			if (this.prologue.isEmpty()) {
				return this.body;
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(this.prologue), this.body);
		}

	}

	/**
	 * One lambda lowered with its recur targets: the lambda itself, any extra worker
	 * labels entries a used variadic clause needs, and whether any recur reached it. A
	 * used variadic clause splits like the multi-{@code defn} helpers: the worker takes
	 * the rest as an ordinary parameter (so a {@code recur} assigns exactly) while the
	 * head keeps the {@code &rest} shape for normal calls (wrapping, like the oracle).
	 */
	record SplitLambda(LispVal lambda, List<LispVal> workers, boolean used) {
	}

	/** The parameter shape of one clause datum, read without lowering anything. */
	record ParamShape(boolean variadic, int fixed) {
	}

	/**
	 * A call whose head is a compound form: a head that lowers to a function value is
	 * called directly, any other (a call result that may be a set, map, vector or
	 * keyword) goes through the prelude dispatcher like a bound local does.
	 */
	private LispVal computedHeadCall(List<LispVal> items) {
		LispVal fun = lower(items.get(0));
		List<LispVal> args = lowers(items, 1);
		if (ClojureLowerUtil.yieldsFun(fun)) {
			List<LispVal> call = new ArrayList<>();
			call.add(ClojureLowerUtil.sym("funcall"));
			call.add(fun);
			call.addAll(args);
			return ClojureLowerUtil.list(call);
		}
		return callableApply(fun, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), args));
	}

	/**
	 * The prelude call behind every runtime-unknown function: real functions and
	 * collection values alike, over the argument-list form.
	 */
	LispVal callableApply(LispVal fun, LispVal argList) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CALL"), fun, argList);
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
	LispVal callFun(LispVal funForm, LispVal bound, List<LispVal> args) {
		return callFun(ClojureLowerUtil.yieldsFun(funForm), bound, args);
	}

	/**
	 * {@link #callFun(LispVal, LispVal, List)} with the directness decided by the caller:
	 * a datum-driven {@link ClojureBindingLowering#holdsRealFun} knows a local bound to a
	 * real function, which the lowered symbol alone does not show (and which the scope
	 * cannot be asked about from a generated parameter symbol).
	 * @param real whether the bound value is always a real function
	 * @param bound the bound value (a symbol)
	 * @param args the argument forms
	 * @return the invocation
	 */
	LispVal callFun(boolean real, LispVal bound, List<LispVal> args) {
		if (real) {
			List<LispVal> call = new ArrayList<>();
			call.add(ClojureLowerUtil.sym("funcall"));
			call.add(bound);
			call.addAll(args);
			return ClojureLowerUtil.list(call);
		}
		return callableApply(bound, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), args));
	}

	/**
	 * The function form applied to an argument-list form over its bound value: CL
	 * {@code apply} for real functions, through the prelude dispatcher otherwise -- the
	 * {@link #callFun} of a computed argument list.
	 * @param funForm the function form, as bound
	 * @param bound the bound value (a symbol)
	 * @param argList the argument-list form
	 * @return the invocation
	 */
	LispVal applyFun(LispVal funForm, LispVal bound, LispVal argList) {
		return applyFun(ClojureLowerUtil.yieldsFun(funForm), bound, argList);
	}

	/**
	 * {@link #applyFun(LispVal, LispVal, LispVal)} with the directness decided by the
	 * caller, like {@link #callFun(boolean, LispVal, List)}.
	 * @param real whether the bound value is always a real function
	 * @param bound the bound value (a symbol)
	 * @param argList the argument-list form
	 * @return the invocation
	 */
	LispVal applyFun(boolean real, LispVal bound, LispVal argList) {
		if (real) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), bound, argList);
		}
		return callableApply(bound, argList);
	}

	/**
	 * A function form a spliced runtime worker funcalls: the form itself when it is a
	 * real function form, else {@code rontolisp::%clojure-as-fn} of it, which answers a
	 * real function as itself and wraps any other value (a set, map, vector, keyword or
	 * var) in the IFn dispatcher. The wrap sits at the call site, never in the worker, so
	 * a program passing only real functions carries no dispatcher (about 26 KB of wasm).
	 * @param fun the lowered function form
	 * @return the form, wrapped when it may hold another value
	 */
	static LispVal realFun(LispVal fun) {
		if (ClojureLowerUtil.yieldsFun(fun)) {
			return fun;
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-AS-FN"), fun);
	}

	/**
	 * Binds the false value before anything else runs: a quoted symbol, so every
	 * occurrence spells the same name and {@code eq} holds on every backend.
	 */
	LispVal falseBinding() {
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
	LispVal ifFalsey(LispVal test, LispVal whenTrue, LispVal whenFalse) {
		LispSymbol temp = freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(temp, test)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("IF"), isFalsey(temp), whenFalse, whenTrue));
	}

	/** Whether the bound test value is falsey: {@code NIL} or the false object. */
	LispVal isFalsey(LispSymbol temp) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("OR"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("NULL"), temp),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), temp, this.falseVariable));
	}

	/**
	 * A temporary no user identifier can spell: user names always start with the prefix.
	 */
	LispSymbol freshTemp() {
		return new LispSymbol("__clojure_" + (this.counter++));
	}

	/**
	 * A boolean-answering builtin's Clojure value: {@code T} or the false object, so
	 * printing spells it out. The raw form answers a Common Lisp boolean and appears
	 * once, so its values run once.
	 * @param raw the raw form
	 * @return the form
	 */
	LispVal booleanAnswer(LispVal raw) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("IF"), raw, TRUE_CONST, this.falseVariable);
	}

	LispVal call(LispVal form, List<LispVal> items, boolean coreOnly) {
		if (!(items.get(0) instanceof LispSymbol op)) {
			throw new LispReadException("a call's head must be a name: " + items.get(0).print());
		}
		String name = op.name();
		if (name.startsWith(":")) {
			return ClojureCollectionLowering.keywordCall(this, name, items);
		}
		// a program's own definition or local binding shadows the core name, like
		// the oracle (and like the value position below, which already looks the
		// name up first) -- the current namespace's own or referred var, since
		// every namespace has its own; a clojure.core/ spelling consults none
		boolean known = !coreOnly && known(name);
		// the re-* names lower beside the big core switch (which stays under the
		// method-size limit): same position, before any qualified name
		// a name excluded by (:refer-clojure ...) is no core call unless spelled
		// clojure.core/name
		boolean core = coreOnly || ClojureNamespaceLowering.coreAllowed(this, name);
		if (!known && ClojureStringLowering.isReName(name) && core) {
			return ClojureStringLowering.reCall(this, name, items);
		}
		LispVal special = known || !core ? null : builtin(name, items);
		if (special != null) {
			return special;
		}
		ClojureLowerUtil.isTrue(!coreOnly, "unknown name: " + ClojureCoreNames.PREFIX + name);
		VarRef qualified = ClojureNamespaceLowering.resolveQualified(this, name);
		if (qualified != null) {
			return ClojureNamespaceLowering.namespaceCall(this, qualified, items, form);
		}
		if (!known) {
			// a qualified name whose head names a project namespace is that
			// namespace's var or nothing: never a class
			ClojureNamespaceLowering.refuseMissingVar(this, name);
			LispVal interop = ClojureInteropLowering.interopCall(this, name, items);
			if (interop != null) {
				return interop;
			}
		}
		VarRef referred = known ? null : ClojureNamespaceLowering.libraryRefer(this, name);
		ClojureLowerUtil.isTrue(known || referred != null, "unknown name: " + name);
		if (referred != null) {
			return ClojureNamespaceLowering.namespaceCall(this, referred, items, form);
		}
		if (this.inDispatchFn) {
			// a call to a recorded class-calling definition inside a dispatch
			// function inlines the recorded datum with the dispatch lowering,
			// so nil answers nil itself and hits the nil method, like the
			// inline datum and the oracle
			LispVal inlined = ClojureDispatchLowering.inlineDispatchCall(this, name, items);
			if (inlined != null) {
				return inlined;
			}
		}
		List<LispVal> args = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			args.add(lower(items.get(i)));
		}
		if (!isFunction(name) && !(this.session && isDeclaredOnly(name))) {
			// a parameter, a let/loop binding or a def'd variable holds the
			// function in the VALUE cell (Lisp-2): a direct call would read the
			// function cell and miss, so call through funcall instead. A defn
			// stays direct, and so does a session's declared name (a later
			// buffer may define it); a file's declared-never-defined name calls
			// its unbound root, which signals like the oracle's. A
			// variable whose value may hold a collection goes through the
			// prelude dispatcher instead, which funcalls real functions.
			if (!isDirectVar(name)) {
				return callableApply(symOf(name), ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), args));
			}
			List<LispVal> funcall = new ArrayList<>();
			funcall.add(ClojureLowerUtil.sym("FUNCALL"));
			funcall.add(symOf(name));
			funcall.addAll(args);
			return ClojureLowerUtil.list(funcall);
		}
		List<LispVal> out = new ArrayList<>();
		out.add(symOf(name));
		out.addAll(args);
		return ClojureLowerUtil.list(out);
	}

	/** The core names, spelled as the Common Lisp operation they lower to. */
	@Nullable LispVal builtin(String name, List<LispVal> items) {
		LispVal xform = ClojureTransducerLowering.xformCall(this, name, items);
		if (xform != null) {
			return xform;
		}
		int n = items.size() - 1;
		switch (name) {
			case "+", "-", "*", "/", "max", "min", "rem", "mod", "abs", "list", "expt":
				return plain(name, items);
			case "reverse":
				// over the whole-collection view: a vector or string reverses into a
				// list like the oracle, and a lazy seq realizes first
				ClojureLowerUtil.isTrue(n == 1, "reverse takes one collection");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"),
						ClojureSeqLowering.seqAllForm(this, lower(items.get(1))));
			case "quot":
				ClojureLowerUtil.isTrue(n == 2, "quot takes two arguments");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("truncate"), lower(items.get(1)),
						lower(items.get(2)));
			case "nth":
				return ClojureSeqLowering.nthOf(this, items);
			case "and":
				return ClojureSeqLowering.andOf(this, items.subList(1, items.size()));
			case "or":
				return ClojureSeqLowering.orOf(this, items.subList(1, items.size()));
			case "=":
				return booleanAnswer(ClojureCollectionLowering.equalityRaw(this, items));
			case "not=":
				return booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureCollectionLowering.equalityRaw(this, items)));
			case "<", ">", "<=", ">=":
				return booleanAnswer(plain(name, items));
			case "==":
				// numeric equality across categories: CL = (1 = 1.0, 0.0 = -0.0)
				ClojureLowerUtil.isTrue(n >= 1, "== takes at least one argument");
				return booleanAnswer(plain("=", items));
			case "inc":
				ClojureLowerUtil.isTrue(n == 1, "inc takes one argument");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), lower(items.get(1)), new LispInteger(1));
			case "dec":
				ClojureLowerUtil.isTrue(n == 1, "dec takes one argument");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), lower(items.get(1)), new LispInteger(1));
			case "not":
				ClojureLowerUtil.isTrue(n == 1, "not takes one argument");
				LispSymbol notTemp = freshTemp();
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
						ClojureLowerUtil.list(ClojureLowerUtil.list(notTemp, lower(items.get(1)))),
						booleanAnswer(isFalsey(notTemp)));
			case "str":
				return ClojureStringLowering.strCall(this, items);
			case "pr-str":
				return ClojureStringLowering.prStrCall(this, items);
			case "print-str":
				return ClojureStringLowering.printStrCall(this, items, false, false);
			case "prn-str":
				return ClojureStringLowering.printStrCall(this, items, true, true);
			case "println-str":
				return ClojureStringLowering.printStrCall(this, items, false, true);
			case "println":
				return ClojureStringLowering.printCall(this, items, true);
			case "print":
				return ClojureStringLowering.printCall(this, items, false);
			case "prn":
				return ClojureStringLowering.prCall(this, items, true);
			case "pr":
				return ClojureStringLowering.prCall(this, items, false);
			case "newline":
				ClojureLowerUtil.isTrue(n == 0, "newline takes no argument");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("princ"), LispString.literal("\n"));
			case "methods":
				return ClojureDispatchLowering.methodsOf(this, items);
			case "count":
				return ClojureCollectionLowering.countOf(this, items);
			case "seq":
				ClojureLowerUtil.isTrue(items.size() == 2, "seq takes one collection");
				return ClojureSeqLowering.seqForm(this, lower(items.get(1)));
			case "first":
				ClojureLowerUtil.isTrue(items.size() == 2, "first takes one collection");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("car"),
						ClojureSeqLowering.seqForm(this, lower(items.get(1))));
			case "rest":
			case "next":
				ClojureLowerUtil.isTrue(items.size() == 2, name + " takes one collection");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"),
						ClojureSeqLowering.seqForm(this, lower(items.get(1))));
			case "cons":
				ClojureLowerUtil.isTrue(items.size() == 3, "cons takes an item and a collection");
				return ClojureLoopLowering.consForm(this, lower(items.get(1)), lower(items.get(2)));
			case "empty?":
				return booleanAnswer(ClojureCollectionLowering.emptyOf(this, items));
			case "nil?":
				return booleanAnswer(plain("null", items));
			case "some?":
				ClojureLowerUtil.isTrue(n == 1, "some? takes one argument");
				return booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), lower(items.get(1)))));
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
				ClojureLowerUtil.isTrue(n == 1, "false? takes one argument");
				return booleanAnswer(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), lower(items.get(1)), this.falseVariable));
			case "true?":
				ClojureLowerUtil.isTrue(n == 1, "true? takes one argument");
				return booleanAnswer(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), lower(items.get(1)), TRUE_CONST));
			case "boolean?":
				ClojureLowerUtil.isTrue(n == 1, "boolean? takes one argument");
				LispSymbol booleanTemp = freshTemp();
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
						ClojureLowerUtil.list(ClojureLowerUtil.list(booleanTemp, lower(items.get(1)))),
						booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("OR"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), booleanTemp, TRUE_CONST),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("EQ"), booleanTemp, this.falseVariable))));
			case "vector":
				return plain("vector", items);
			case "make-array":
				return ClojureInteropLowering.makeArrayOf(this, items);
			case "aget":
				return ClojureInteropLowering.agetOf(this, items);
			case "aset":
				return ClojureInteropLowering.asetOf(this, items);
			case "alength":
				return ClojureInteropLowering.alengthOf(this, items);
			case "vector?":
				// a string is a CL vector but no Clojure vector, like the oracle
				ClojureLowerUtil.isTrue(n == 1, "vector? takes one argument");
				LispSymbol vectorTemp = freshTemp();
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
						ClojureLowerUtil.list(ClojureLowerUtil.list(vectorTemp, lower(items.get(1)))),
						booleanAnswer(vectorRaw(vectorTemp)));
			default:
				return builtinSequences(name, items, n);
		}
	}

	/**
	 * The sequence and collection verbs, sliced out of {@link #builtin}: the dispatcher
	 * had crossed HotSpot's {@code HugeMethodLimit} again, and it runs per call form, so
	 * it must stay JIT-compilable. Null when the name is none of them, like
	 * {@code builtin}.
	 */
	@Nullable LispVal builtinSequences(String name, List<LispVal> items, int n) {
		switch (name) {
			case "map":
				ClojureLowerUtil.isTrue(n >= 2, "map takes a function and collections");
				return ClojureSeqLowering.mapForm(this, ClojureBindingLowering.realFnValue(this, items.get(1)),
						lowers(items, 2));
			case "filter":
				ClojureLowerUtil.isTrue(n == 2, "filter takes a predicate and a collection");
				return ClojureSeqLowering.filterForm(this, ClojureBindingLowering.realFnValue(this, items.get(1)),
						lower(items.get(2)));
			case "reduce":
				ClojureLowerUtil.isTrue(n == 2 || n == 3,
						"reduce takes a function, an optional value and a collection");
				if (n == 2) {
					return ClojureSeqLowering.reduceForm(this, ClojureBindingLowering.realFnValue(this, items.get(1)),
							lower(items.get(2)), null);
				}
				LispVal reduceFn = ClojureBindingLowering.realFnValue(this, items.get(1));
				LispVal reduceInit = lower(items.get(2));
				return ClojureSeqLowering.reduceForm(this, reduceFn, lower(items.get(3)), reduceInit);
			case "apply":
				return ClojureSeqLowering.applyOf(this, items);
			case "concat":
				if (items.size() == 1) {
					return NIL_CONST;
				}
				return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CONCAT"),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), lowers(items, 1)));
			case "take":
				return ClojureSeqLowering.takeOf(this, items);
			case "drop":
				return ClojureSeqLowering.dropOf(this, items);
			case "range":
				return ClojureLazyLowering.rangeOf(this, items);
			case "dorun":
				return ClojureLazyLowering.dorunOf(this, items);
			case "doall":
				return ClojureLazyLowering.doallOf(this, items);
			case "gensym":
				return ClojureMacroLowering.gensymOf(this, items);
			case "macroexpand-1":
				ClojureLowerUtil.isTrue(n == 1, "macroexpand-1 takes one form");
				return ClojureMacroLowering.macroexpandCall(this, ClojureMacroLowering.MACROEXPAND_1,
						lower(items.get(1)));
			case "macroexpand":
				ClojureLowerUtil.isTrue(n == 1, "macroexpand takes one form");
				return ClojureMacroLowering.macroexpandCall(this, ClojureMacroLowering.MACROEXPAND,
						lower(items.get(1)));
			case "assoc":
				return ClojureCollectionLowering.assocOf(this, items);
			case "dissoc":
				return ClojureCollectionLowering.dissocOf(this, items);
			case "get":
				return ClojureCollectionLowering.getOf(this, items);
			case "contains?":
				return ClojureCollectionLowering.containsOf(this, items);
			case "subs":
				return ClojureStringLowering.subsOf(this, items);
			case "keep":
				ClojureLowerUtil.isTrue(n == 2, "keep takes a function and a collection");
				return ClojureFilterLowering.keepForm(this, ClojureBindingLowering.realFnValue(this, items.get(1)),
						lower(items.get(2)));
			case "keep-indexed":
				ClojureLowerUtil.isTrue(n == 2, "keep-indexed takes a function and a collection");
				return ClojureFilterLowering.indexedForm(this, ClojureBindingLowering.realFnValue(this, items.get(1)),
						lower(items.get(2)), true);
			case "map-indexed":
				ClojureLowerUtil.isTrue(n == 2, "map-indexed takes a function and a collection");
				return ClojureFilterLowering.indexedForm(this, ClojureBindingLowering.realFnValue(this, items.get(1)),
						lower(items.get(2)), false);
			case "every?":
				ClojureLowerUtil.isTrue(n == 2, "every? takes a predicate and a collection");
				return ClojureFilterLowering.everyForm(this, ClojureBindingLowering.fnArg(this, items.get(1)),
						ClojureSeqLowering.seqForm(this, lower(items.get(2))));
			case "some":
				ClojureLowerUtil.isTrue(n == 2, "some takes a predicate and a collection");
				return ClojureFilterLowering.someForm(this, ClojureBindingLowering.fnArg(this, items.get(1)),
						ClojureSeqLowering.seqForm(this, lower(items.get(2))));
			case "remove":
				ClojureLowerUtil.isTrue(n == 2, "remove takes a predicate and a collection");
				return ClojureFilterLowering.removeForm(this, ClojureBindingLowering.realFnValue(this, items.get(1)),
						lower(items.get(2)));
			case "distinct":
				ClojureLowerUtil.isTrue(n == 1, "distinct takes one collection");
				return ClojureFilterLowering.distinctForm(lower(items.get(1)));
			case "partition":
				return ClojureFilterLowering.partitionOf(this, items);
			case "take-while":
				ClojureLowerUtil.isTrue(n == 2, "take-while takes a predicate and a collection");
				return ClojureFilterLowering.takeWhileForm(this, ClojureBindingLowering.fnArg(this, items.get(1)),
						ClojureSeqLowering.seqForm(this, lower(items.get(2))));
			case "drop-while":
				ClojureLowerUtil.isTrue(n == 2, "drop-while takes a predicate and a collection");
				return ClojureFilterLowering.dropWhileForm(this, ClojureBindingLowering.fnArg(this, items.get(1)),
						ClojureSeqLowering.seqForm(this, lower(items.get(2))));
			case "interleave":
				return ClojureFilterLowering.interleaveOf(this, items);
			case "interpose":
				ClojureLowerUtil.isTrue(n == 2, "interpose takes a separator and a collection");
				return ClojureFilterLowering.interposeForm(lower(items.get(1)), lower(items.get(2)));
			case "zipmap":
				ClojureLowerUtil.isTrue(n == 2, "zipmap takes keys and values");
				return ClojureFilterLowering.zipmapForm(this, ClojureSeqLowering.seqForm(this, lower(items.get(1))),
						ClojureSeqLowering.seqForm(this, lower(items.get(2))));
			case "group-by":
				ClojureLowerUtil.isTrue(n == 2, "group-by takes a function and a collection");
				return ClojureFilterLowering.groupByForm(this, ClojureBindingLowering.fnArg(this, items.get(1)),
						ClojureSeqLowering.seqAllForm(this, lower(items.get(2))));
			case "sort":
				return ClojureFilterLowering.sortOf(this, items);
			case "sort-by":
				return ClojureFilterLowering.sortByOf(this, items);
			case "last":
				ClojureLowerUtil.isTrue(n == 1, "last takes one collection");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("last"), ClojureSeqLowering.seqAllForm(this, lower(items.get(1)))));
			case "butlast":
				ClojureLowerUtil.isTrue(n == 1, "butlast takes one collection");
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("butlast"),
						ClojureSeqLowering.seqAllForm(this, lower(items.get(1))));
			case "second":
				ClojureLowerUtil.isTrue(n == 1, "second takes one collection");
				return ClojureSeqLowering.secondForm(lower(items.get(1)));
			default:
				return builtinFunctions(name, items, n);
		}
	}

	/**
	 * The function-combinator, type and I/O verbs, sliced out of {@link #builtin} for the
	 * same reason as {@link #builtinSequences}; ends in {@link #builtinConvenience}.
	 */
	@Nullable LispVal builtinFunctions(String name, List<LispVal> items, int n) {
		switch (name) {
			case "update":
				return ClojureUpdateLowering.updateOf(this, items);
			case "update-in":
				return ClojureUpdateLowering.updateInOf(this, items);
			case "assoc-in":
				return ClojureUpdateLowering.assocInOf(this, items);
			case "get-in":
				return ClojureUpdateLowering.getInOf(this, items);
			case "select-keys":
				ClojureLowerUtil.isTrue(n == 2, "select-keys takes a map and keys");
				return ClojureUpdateLowering.selectKeysForm(this, lower(items.get(1)),
						ClojureSeqLowering.seqAllForm(this, lower(items.get(2))));
			case "merge-with":
				return ClojureUpdateLowering.mergeWithOf(this, items);
			case "into":
				return ClojureUpdateLowering.intoOf(this, items);
			case "frequencies":
				ClojureLowerUtil.isTrue(n == 1, "frequencies takes one collection");
				return ClojureUpdateLowering.frequenciesForm(this,
						ClojureSeqLowering.seqAllForm(this, lower(items.get(1))));
			case "comp":
				return ClojureFnLowering.compOf(this, items);
			case "partial":
				ClojureLowerUtil.isTrue(n >= 1, "partial takes a function and arguments");
				return ClojureFnLowering.partialForm(this, ClojureBindingLowering.fnArg(this, items.get(1)),
						lowers(items, 2));
			case "complement":
				ClojureLowerUtil.isTrue(n == 1, "complement takes one function");
				return ClojureFnLowering.complementForm(this, ClojureBindingLowering.fnArg(this, items.get(1)));
			case "constantly":
				ClojureLowerUtil.isTrue(n == 1, "constantly takes one value");
				return ClojureFnLowering.constantlyForm(this, lower(items.get(1)));
			case "identity":
				ClojureLowerUtil.isTrue(n == 1, "identity takes one value");
				return lower(items.get(1));
			case "memoize":
				ClojureLowerUtil.isTrue(n == 1, "memoize takes one function");
				return ClojureFnLowering.memoizeForm(this, ClojureBindingLowering.fnArg(this, items.get(1)));
			case "trampoline":
				ClojureLowerUtil.isTrue(n >= 1, "trampoline takes a function and arguments");
				return ClojureFnLowering.trampolineForm(this, ClojureBindingLowering.fnArg(this, items.get(1)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), lowers(items, 2)));
			case "string?":
				ClojureLowerUtil.isTrue(n == 1, "string? takes one value");
				return booleanAnswer(plain("stringp", items));
			case "symbol?":
				ClojureLowerUtil.isTrue(n == 1, "symbol? takes one value");
				return booleanAnswer(ClojureFnLowering.symbolRaw(this, lower(items.get(1))));
			case "instance?":
				return ClojureDispatchLowering.instanceOf(this, items);
			case "class":
				ClojureLowerUtil.isTrue(n == 1, "class takes one value");
				return ClojureDispatchLowering.classForm(this, lower(items.get(1)));
			case "int", "long":
				ClojureLowerUtil.isTrue(n == 1, name + " takes one value");
				return ClojureDispatchLowering.intForm(this, lower(items.get(1)));
			case "spit":
				return ClojureStringLowering.spitOf(this, items);
			case "slurp":
				ClojureLowerUtil.isTrue(n == 1, "slurp takes one path");
				return ClojureStringLowering.slurpForm(this, lower(items.get(1)));
			case "line-seq":
				ClojureLowerUtil.isTrue(n == 1, "line-seq takes one path or reader");
				return ClojureStringLowering.lineSeqForm(this, lower(items.get(1)));
			case "format":
				return ClojureStringLowering.formatOf(this, items);
			case "file-seq":
				throw new LispReadException("file-seq is not supported yet: directory walks need a design");
			case "keys":
				return ClojureCollectionLowering.keysOf(this, items);
			case "vals":
				return ClojureCollectionLowering.valsOf(this, items);
			case "merge":
				return ClojureCollectionLowering.mergeOf(this, items);
			case "conj":
				return ClojureCollectionLowering.conjOf(this, items);
			case "disj":
				return ClojureCollectionLowering.disjOf(this, items);
			case "set":
				return ClojureCollectionLowering.setOf(this, items);
			case "hash-map":
				return ClojureCollectionLowering.mapConstructorOf(this, items, "hash-map");
			case "array-map":
				return ClojureCollectionLowering.mapConstructorOf(this, items, "array-map");
			case "transient", "persistent!", "assoc!", "dissoc!", "conj!", "disj!":
				throw new LispReadException("transients are not supported yet: " + name);
			case "lazy-seq":
				return capturingMutableFields(() -> ClojureLazyLowering.lazySeqOf(this, items));
			case "lazy-cat":
				return ClojureLazyLowering.lazyCatOf(this, items);
			case "repeat":
				return ClojureLazyLowering.repeatOf(this, items);
			case "cycle":
				ClojureLowerUtil.isTrue(n == 1, "cycle takes one collection");
				return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CYCLE"), lower(items.get(1)));
			case "repeatedly":
				return ClojureLazyLowering.repeatedlyOf(this, items);
			case "iterate":
				ClojureLowerUtil.isTrue(n == 2, "iterate takes a function and a value");
				return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ITERATE"),
						ClojureBindingLowering.realFnValue(this, items.get(1)), lower(items.get(2)));
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
	 * The core convenience fns, sliced out of {@link #builtin}: that dispatcher had
	 * crossed HotSpot's {@code HugeMethodLimit} (like the backend
	 * {@code compileConsLocated} slices before it), so these names dispatch through one
	 * more call -- null when the name is none of them, like {@code builtin} itself.
	 */
	@Nullable LispVal builtinConvenience(String name, List<LispVal> items, int n) {
		switch (name) {
			case "mapv":
				return ClojureFilterLowering.mapvOf(this, items);
			case "filterv":
				ClojureLowerUtil.isTrue(n == 2, "filterv takes a predicate and a collection");
				return ClojureFilterLowering.filtervForm(this, ClojureBindingLowering.realFnValue(this, items.get(1)),
						lower(items.get(2)));
			case "mapcat":
				return ClojureFilterLowering.mapcatOf(this, items);
			case "ffirst":
				ClojureLowerUtil.isTrue(n == 1, "ffirst takes one collection");
				return ClojureFilterLowering.ffirstForm(this, ClojureSeqLowering.seqForm(this, lower(items.get(1))));
			case "nfirst":
				ClojureLowerUtil.isTrue(n == 1, "nfirst takes one collection");
				return ClojureFilterLowering.nfirstForm(this, ClojureSeqLowering.seqForm(this, lower(items.get(1))));
			case "boolean":
				ClojureLowerUtil.isTrue(n == 1, "boolean takes one value");
				return ClojureFnLowering.booleanForm(this, lower(items.get(1)));
			case "char":
				ClojureLowerUtil.isTrue(n == 1, "char takes one value");
				return ClojureFnLowering.charForm(this, lower(items.get(1)));
			case "name":
				ClojureLowerUtil.isTrue(n == 1, "name takes one value");
				return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NAME"), lower(items.get(1)));
			case "namespace":
				ClojureLowerUtil.isTrue(n == 1, "namespace takes one value");
				return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NAMESPACE"), lower(items.get(1)));
			case "keyword":
				return ClojureFnLowering.keywordOf(this, items);
			case "symbol":
				return ClojureFnLowering.symbolOf(this, items);
			case "assert":
				return ClojureFnLowering.assertOf(this, items);
			case "rand":
				return ClojureFnLowering.randOf(this, items);
			case "rand-int":
				ClojureLowerUtil.isTrue(n == 1, "rand-int takes one bound");
				return ClojureFnLowering.randIntForm(this, lower(items.get(1)));
			case "rand-nth":
				ClojureLowerUtil.isTrue(n == 1, "rand-nth takes one collection");
				return ClojureFnLowering.randNthForm(this, lower(items.get(1)));
			case "shuffle":
				ClojureLowerUtil.isTrue(n == 1, "shuffle takes one collection");
				return ClojureFnLowering.shuffleForm(this, lower(items.get(1)));
			case "vec":
				return ClojureCollectionLowering.vecOf(this, items);
			case "fn?":
				ClojureLowerUtil.isTrue(n == 1, "fn? takes one argument");
				return booleanAnswer(plain("functionp", items));
			default:
				LispVal predicate = ClojurePredicateLowering.callOf(this, name, items);
				if (predicate != null) {
					return predicate;
				}
				LispVal sorted = ClojureSortedLowering.callOf(this, name, items);
				if (sorted != null) {
					return sorted;
				}
				LispVal core = ClojureCoreLowering.callOf(this, name, items);
				return core != null ? core : ClojureTransducerLowering.callOf(this, name, items);
		}
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
	@Nullable LispVal valueOf(String name) {
		return switch (name) {
			case "inc", "dec" -> ClojureFnLowering.incValue(this, name);
			case "=" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("function"),
					new LispSymbol("RONTOLISP::%CLOJURE-EQUAL-VALUES"));
			case "not=" -> notEqualValue();
			case "==" -> comparisonValue("=");
			case "<", ">", "<=", ">=" -> comparisonValue(name);
			case "vector?" -> ClojureFnLowering.predValue(this, ClojureLowering::vectorRaw);
			case "fn?" ->
				ClojureFnLowering.predValue(this, x -> ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), x));
			case "str" -> ClojureStringLowering.strValue(this);
			case "pr-str" -> ClojureStringLowering.prStrValue(this);
			case "print-str" -> ClojureStringLowering.printStrValue(false, false);
			case "prn-str" -> ClojureStringLowering.printStrValue(true, true);
			case "println-str" -> ClojureStringLowering.printStrValue(false, true);
			case "seq" -> ClojureSeqLowering.seqValue(this);
			case "first" -> ClojureSeqLowering.firstValue(this);
			case "rest", "next" -> ClojureSeqLowering.restValue(this);
			case "cons" -> ClojureSeqLowering.consValue(this);
			case "count" -> ClojureCollectionLowering.countValue(this);
			case "reverse" -> {
				LispSymbol coll = new LispSymbol(mangle("reverse-coll"));
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(coll),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"),
								ClojureSeqLowering.seqAllForm(this, coll)));
			}
			case "empty?" -> ClojureCollectionLowering.emptyValue(this);
			case "map" -> ClojureSeqLowering.mapValue(this);
			case "filter" -> ClojureSeqLowering.filterValue(this);
			case "reduce" -> ClojureSeqLowering.reduceValue(this);
			case "concat" -> ClojureSeqLowering.concatValue(this);
			case "take" -> ClojureSeqLowering.takeValue(this);
			case "drop" -> ClojureSeqLowering.dropValue(this);
			case "repeat" -> ClojureLazyLowering.repeatValue(this);
			case "cycle" -> ClojureLazyLowering.cycleValue(this);
			case "iterate" -> ClojureLazyLowering.iterateValue(this);
			case "repeatedly" -> ClojureLazyLowering.repeatedlyValue(this);
			case "range" -> ClojureLazyLowering.rangeValue(this);
			case "dorun" -> ClojureLazyLowering.dorunValue(this);
			case "doall" -> ClojureLazyLowering.doallValue(this);
			case "subs" -> ClojureStringLowering.subsValue(this);
			case "re-pattern" -> ClojureStringLowering.reValue(this, "re-pattern", List.of(1));
			case "re-matcher" -> ClojureStringLowering.reValue(this, "re-matcher", List.of(2));
			case "re-find" -> ClojureStringLowering.reValue(this, "re-find", List.of(1, 2));
			case "re-seq" -> ClojureStringLowering.reValue(this, "re-seq", List.of(2));
			case "re-matches" -> ClojureStringLowering.reValue(this, "re-matches", List.of(2));
			case "re-groups" -> ClojureStringLowering.reValue(this, "re-groups", List.of(1));
			case "keep" -> ClojureFilterLowering.keepValue(this);
			case "keep-indexed" -> ClojureFilterLowering.indexedValue(this, true);
			case "map-indexed" -> ClojureFilterLowering.indexedValue(this, false);
			case "every?" -> ClojureFilterLowering.everyValue(this);
			case "some" -> ClojureFilterLowering.someValue(this);
			case "remove" -> ClojureFilterLowering.removeValue(this);
			case "distinct" -> ClojureFilterLowering.distinctValue();
			case "partition" -> ClojureFilterLowering.partitionValue();
			case "take-while" -> ClojureFilterLowering.takeWhileValue(this);
			case "drop-while" -> ClojureFilterLowering.dropWhileValue(this);
			case "interleave" -> ClojureFilterLowering.interleaveValue();
			case "interpose" -> ClojureFilterLowering.interposeValue();
			case "zipmap" -> ClojureFilterLowering.zipmapValue(this);
			case "group-by" -> ClojureFilterLowering.groupByValue(this);
			case "sort" -> ClojureFilterLowering.sortValue(this);
			case "sort-by" -> ClojureFilterLowering.sortByValue(this);
			case "last" -> ClojureFilterLowering.lastValue(this);
			case "butlast" -> ClojureFilterLowering.butlastValue(this);
			case "second" -> ClojureFilterLowering.secondValue(this);
			case "mapv" -> ClojureFilterLowering.mapvValue(this);
			case "filterv" -> ClojureFilterLowering.filtervValue(this);
			case "mapcat" -> ClojureFilterLowering.mapcatValue(this);
			case "ffirst" -> ClojureFilterLowering.ffirstValue(this);
			case "nfirst" -> ClojureFilterLowering.nfirstValue(this);
			case "boolean" -> ClojureFnLowering.booleanValue(this);
			case "char" -> ClojureFnLowering.charValue(this);
			case "name" -> ClojureFnLowering.nameValue(this);
			case "namespace" -> ClojureFnLowering.namespaceValue(this);
			case "keyword" -> ClojureFnLowering.keywordValue(this);
			case "symbol" -> ClojureFnLowering.symbolValue(this);
			case "rand" -> ClojureFnLowering.randValue(this);
			case "rand-int" -> ClojureFnLowering.randIntValue(this);
			case "rand-nth" -> ClojureFnLowering.randNthValue(this);
			case "shuffle" -> ClojureFnLowering.shuffleValue(this);
			case "update" -> ClojureUpdateLowering.updateValue(this);
			case "update-in" -> ClojureUpdateLowering.updateInValue(this);
			case "assoc-in" -> ClojureUpdateLowering.assocInValue(this);
			case "get-in" -> ClojureUpdateLowering.getInValue(this);
			case "select-keys" -> ClojureUpdateLowering.selectKeysValue(this);
			case "merge-with" -> ClojureUpdateLowering.mergeWithValue(this);
			case "into" -> ClojureUpdateLowering.intoValue(this);
			case "frequencies" -> ClojureUpdateLowering.frequenciesValue(this);
			case "keys" -> ClojureCollectionLowering.keysValue(this);
			case "vals" -> ClojureCollectionLowering.valsValue(this);
			case "assoc" -> ClojureCollectionLowering.assocValue(this);
			case "dissoc" -> ClojureCollectionLowering.dissocValue(this);
			case "get" -> ClojureCollectionLowering.getValue(this);
			case "contains?" -> ClojureCollectionLowering.containsValue(this);
			case "merge" -> ClojureCollectionLowering.mergeValue(this);
			case "conj" -> ClojureCollectionLowering.conjValue(this);
			case "disj" -> ClojureCollectionLowering.disjValue(this);
			case "set" -> ClojureCollectionLowering.setValue(this);
			case "hash-map" -> ClojureCollectionLowering.mapConstructorValue(this, "hash-map");
			case "array-map" -> ClojureCollectionLowering.mapConstructorValue(this, "array-map");
			case "vec" -> ClojureCollectionLowering.vecValue(this);
			case "comp" -> ClojureFnLowering.compValue(this);
			case "partial" -> ClojureFnLowering.partialValue(this);
			case "complement" -> ClojureFnLowering.complementValue(this);
			case "constantly" -> ClojureFnLowering.constantlyValue(this);
			case "identity" -> ClojureFnLowering.identityValue(this);
			case "memoize" -> ClojureFnLowering.memoizeValue(this);
			case "trampoline" -> ClojureFnLowering.trampolineValue(this);
			case "string?" -> ClojureFnLowering.stringPredValue(this);
			case "symbol?" -> ClojureFnLowering.symbolPredValue(this);
			case "class" -> ClojureDispatchLowering.classValue(this);
			case "int", "long" -> ClojureDispatchLowering.intValue(this);
			case "spit" -> ClojureStringLowering.spitValue(this);
			case "slurp" -> ClojureStringLowering.slurpValue(this);
			case "line-seq" -> ClojureStringLowering.lineSeqValue(this);
			case "atom" -> ClojureStateLowering.atomValue(this);
			case "volatile!" -> ClojureStateLowering.volatileValue();
			case "deref" -> ClojureStateLowering.derefValue(this);
			case "swap!", "vswap!" -> ClojureStateLowering.swapValue(this);
			case "reset!", "vreset!" -> ClojureStateLowering.resetValue(this);
			case "compare-and-set!" -> ClojureStateLowering.compareAndSetValue(this);
			case "ref" -> ClojureStateLowering.refValue(this);
			case "alter" -> ClojureStateLowering.alterValue(this, "alter");
			case "commute" -> ClojureStateLowering.alterValue(this, "commute");
			case "ref-set" -> ClojureStateLowering.refSetValue(this);
			case "agent" -> ClojureStateLowering.agentValue(this);
			case "send" -> ClojureStateLowering.sendValue(this, "send");
			case "send-off" -> ClojureStateLowering.sendValue(this, "send-off");
			case "odd?" ->
				ClojureFnLowering.predValue(this, x -> ClojureLowerUtil.list(ClojureLowerUtil.sym("oddp"), x));
			case "even?" ->
				ClojureFnLowering.predValue(this, x -> ClojureLowerUtil.list(ClojureLowerUtil.sym("evenp"), x));
			case "zero?" ->
				ClojureFnLowering.predValue(this, x -> ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"), x));
			case "pos?" ->
				ClojureFnLowering.predValue(this, x -> ClojureLowerUtil.list(ClojureLowerUtil.sym("plusp"), x));
			case "neg?" ->
				ClojureFnLowering.predValue(this, x -> ClojureLowerUtil.list(ClojureLowerUtil.sym("minusp"), x));
			case "nil?" ->
				ClojureFnLowering.predValue(this, x -> ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), x));
			case "some?" -> ClojureFnLowering.predValue(this, x -> ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), x)));
			case "not" -> ClojureFnLowering.notValue(this);
			case "ex-data" -> ClojureStateLowering.exHelperValue(this, ClojureStateLowering.EX_DATA);
			case "ex-message" -> ClojureStateLowering.exHelperValue(this, ClojureStateLowering.EX_MESSAGE);
			case "ex-cause" -> ClojureStateLowering.exHelperValue(this, ClojureStateLowering.EX_CAUSE);
			case "ex-info" -> ClojureStateLowering.exInfoValue(this);
			case "macroexpand-1" -> ClojureMacroLowering.macroexpandValue(this, ClojureMacroLowering.MACROEXPAND_1);
			case "macroexpand" -> ClojureMacroLowering.macroexpandValue(this, ClojureMacroLowering.MACROEXPAND);
			default -> {
				LispVal predicate = ClojurePredicateLowering.valueOf(this, name);
				if (predicate != null) {
					yield predicate;
				}
				LispVal sorted = ClojureSortedLowering.valueOf(name);
				if (sorted != null) {
					yield sorted;
				}
				LispVal core = ClojureCoreLowering.valueOf(this, name);
				yield core != null ? core : ClojureTransducerLowering.valueOf(name);
			}
		};
	}

	/**
	 * One level of a {@code doseq}/{@code for} binding vector: a pattern over a
	 * collection plus the {@code :when}/{@code :while}/{@code :let} modifiers that follow
	 * it, in order.
	 */
	record SeqLevel(LispVal pattern, LispVal coll, List<SeqModifier> modifiers) {
	}

	/** One {@code :when}/{@code :while}/{@code :let} modifier and its datum. */
	record SeqModifier(String kind, LispVal datum) {
	}

	LispVal plain(String clName, List<LispVal> items) {
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym(clName), lowers(items, 1));
	}

	List<LispVal> lowers(List<LispVal> items, int from) {
		List<LispVal> out = new ArrayList<>();
		for (int i = from; i < items.size(); i++) {
			out.add(lower(items.get(i)));
		}
		return out;
	}

	LispVal bodyOf(List<LispVal> forms) {
		if (forms.isEmpty()) {
			return NIL_CONST;
		}
		if (forms.size() == 1) {
			return lowerTailSlot(forms.get(0));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(ClojureLowerUtil.sym("progn"));
		for (int i = 0; i < forms.size() - 1; i++) {
			out.add(lower(forms.get(i)));
		}
		out.add(lowerTailSlot(forms.get(forms.size() - 1)));
		return ClojureLowerUtil.list(out);
	}

	LispVal body(List<LispVal> items, int from) {
		int n = items.size() - from;
		if (n <= 0) {
			return NIL_CONST;
		}
		if (n == 1) {
			return lowerTailSlot(items.get(from));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(ClojureLowerUtil.sym("progn"));
		for (int i = from; i < items.size() - 1; i++) {
			out.add(lower(items.get(i)));
		}
		out.add(lowerTailSlot(items.get(items.size() - 1)));
		return ClojureLowerUtil.list(out);
	}

	/** A namespace-qualified var: the namespace and the var it names. */
	record VarRef(String ns, String var) {
	}

	/**
	 * A {@code clojure.core/name} in value position: the core value of the name, never a
	 * program definition, library refer or class member of that spelling.
	 */
	LispVal coreValue(String name) {
		LispVal value = coreValueOrNull(name);
		if (value == null) {
			throw new LispReadException("unknown name: " + ClojureCoreNames.PREFIX + name);
		}
		return value;
	}

	/**
	 * A {@code clojure.core} special's value: its special variable, which the program
	 * then defines ({@link #usedSpecials}); the agent var needs the STM runtime.
	 */
	LispVal specialValue(ClojureCoreSpecials.Special special) {
		this.usedSpecials.add(special.name());
		if (special.name().equals("*agent*")) {
			this.usedStm = true;
		}
		return special.symbol();
	}

	/**
	 * A {@code clojure.core} special read as a value: {@link #specialValue}, or for a
	 * stream a call of its {@link ClojureCoreSpecials.Special#reader}, which answers a
	 * stream value where the variable holds the {@code t} designator. A binding or
	 * {@code set!} target stays the variable itself.
	 */
	LispVal specialRead(ClojureCoreSpecials.Special special) {
		LispVal variable = specialValue(special);
		return special.reader() == null ? variable : ClojureLowerUtil.list(special.reader());
	}

	/**
	 * {@link #coreValue}, or null for a core name with no value here (a macro, or a var
	 * the subset does not implement).
	 */
	@Nullable LispVal coreValueOrNull(String name) {
		switch (name) {
			case "nth":
				return ClojureSeqLowering.nthValue(this);
			case "quot":
				return ClojureSeqLowering.quotValue(this);
			default:
				break;
		}
		ClojureCoreSpecials.Special special = ClojureCoreSpecials.of(name);
		if (special != null) {
			return specialRead(special);
		}
		LispVal synth = valueOf(name);
		if (synth != null) {
			return ClojureTransducerLowering.xformValue(this, name, synth);
		}
		String cl = builtinValue(name);
		if (cl == null) {
			return null;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym(cl));
	}

	LispVal atom(LispVal form) {
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
			return ClojureCollectionLowering.keywordForm(ClojureCollectionLowering.resolveKeywordSpelling(this, name)); // a
																														// keyword
																														// is
																														// its
			// spelling,
			// case-preserved
		}
		if (name.startsWith("#:")) {
			// a gensym a macro expansion returned: uninterned, so it lowers to
			// itself instead of mangling behind the prefix
			return s;
		}
		String core = ClojureCoreNames.coreSpelling(name);
		if (core == null) {
			core = renamedCore(name);
		}
		if (core != null) {
			// clojure.core/name: the core value whatever the program defines under
			// that name
			return coreValue(core);
		}
		ClojureCoreSpecials.Special special = ClojureCoreSpecials.of(name);
		if (special != null && !isLocal(name) && resolveVar(name) == null) {
			// a clojure.core special no program var claims: its special variable
			return specialRead(special);
		}
		if (!known(name)) {
			if (name.equals("nth")) {
				return ClojureSeqLowering.nthValue(this);
			}
			if (name.equals("quot")) {
				return ClojureSeqLowering.quotValue(this);
			}
			ClojureNamespaceLowering.refuseMissingVar(this, name);
			VarRef qualified = ClojureNamespaceLowering.resolveQualified(this, name);
			if (qualified == null) {
				qualified = ClojureNamespaceLowering.libraryRefer(this, name);
			}
			if (qualified != null) {
				return ClojureNamespaceLowering.namespaceValue(this, qualified);
			}
			LispVal synth = valueOf(name);
			if (synth != null) {
				return ClojureTransducerLowering.xformValue(this, name, synth);
			}
			String cl = builtinValue(name);
			if (cl == null) {
				LispVal member = ClojureInteropLowering.interopValue(this, name);
				if (member != null) {
					return member;
				}
				LispVal classValue = ClojureInteropLowering.classValue(this, name);
				if (classValue != null) {
					return classValue;
				}
				throw new LispReadException("unknown name: " + name);
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym(cl));
		}
		if (isMacro(name)) {
			throw new LispReadException(name + " is a macro, not a function");
		}
		if (isFunction(name)) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), symOf(name));
		}
		if (this.session && isDeclaredOnly(name)) {
			return ClojureVarLowering.sessionDeclaredRoot(symOf(name));
		}
		return symOf(name);
	}

	/** Whether the bound value is a Clojure vector: a CL vector that is no string. */
	static LispVal vectorRaw(LispVal bound) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("AND"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("VECTORP"), bound), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("NOT"), ClojureLowerUtil.list(ClojureLowerUtil.sym("STRINGP"), bound)));
	}

	/**
	 * {@code not=} as a value: the negated {@code =} over every argument, answering
	 * {@code T}-or-false.
	 */
	LispVal notEqualValue() {
		LispSymbol values = new LispSymbol(mangle("not=-values"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(AMPERSAND_REST, values),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("function"),
										new LispSymbol("RONTOLISP::%CLOJURE-EQUAL-VALUES")),
								values),
						TRUE_CONST), this.falseVariable, TRUE_CONST));
	}

	/**
	 * A numeric comparison as a value ({@code ==}, {@code <}, {@code >}, {@code <=},
	 * {@code >=}): the Common Lisp function over every argument, answering
	 * {@code T}-or-false.
	 * @param clName the Common Lisp function
	 * @return the lambda
	 */
	LispVal comparisonValue(String clName) {
		LispSymbol values = new LispSymbol(mangle(clName + "-values"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(AMPERSAND_REST, values),
				booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym(clName)),
						values)));
	}

	/** The Common Lisp function a core name names as a value, or null. */
	static @Nullable String builtinValue(String name) {
		return switch (name) {
			case "+", "-", "*", "/", "max", "min", "rem", "mod", "abs", "cons", "list", "expt", "apply", "=", "length",
					"car", "cdr", "equal", "evenp", "oddp", "zerop", "plusp", "minusp", "vector", "vectorp",
					"identity" ->
				name;
			case "gensym" -> "gensym";
			case "count" -> "length";
			case "first" -> "car";
			case "rest" -> "cdr";
			default -> null;
		};
	}

	LispVal quote(LispVal datum) {
		LispVal bare = ClojureLowerUtil.stripMeta(datum);
		if (bare != datum) {
			return quote(bare); // reader metadata on quoted data is dropped
		}
		if (datum instanceof LispSymbol s) {
			String name = s.name();
			if (name.equals("nil")) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), NIL_CONST);
			}
			if (name.equals("true")) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), TRUE_CONST);
			}
			if (name.equals("false")) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), new LispSymbol(FALSE_VALUE_NAME));
			}
			if (name.startsWith(":")) {
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureCollectionLowering
					.keywordDatum(ClojureCollectionLowering.resolveKeywordSpelling(this, name)));
			}
			if (name.startsWith("#:")) {
				// a gensym a macro expansion returned: it quotes to itself
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), s);
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.dataSym(name));
		}
		if (datum instanceof LispCons) {
			List<LispVal> items = ClojureLowerUtil.items(datum, List.of());
			if (!items.isEmpty() && items.get(0) == ClojureReader.VECTOR) {
				List<LispVal> out = new ArrayList<>();
				out.add(ClojureLowerUtil.sym("vector"));
				for (int i = 1; i < items.size(); i++) {
					out.add(quote(items.get(i)));
				}
				return ClojureLowerUtil.list(out);
			}
			if (!items.isEmpty() && (ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-map")
					|| ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-set"))) {
				return quotedCollection(items);
			}
			if (!items.isEmpty() && items.get(0) == ClojureReader.REGEX
					|| !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "%regex")) {
				return ClojureCollectionLowering.regexForm(items);
			}
			if (!items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "%record")) {
				return ClojureProtocolLowering.recordLiteral(this, items);
			}
			// one constant when every element is one; a nested vector, map, set or
			// regex literal is built at run time, so the list is too (its element
			// forms in order) -- never the construction code as list data
			List<LispVal> elements = new ArrayList<>();
			boolean constant = true;
			for (LispVal item : items) {
				LispVal element = quote(item);
				elements.add(element);
				constant &= isQuoteForm(element);
			}
			if (!constant) {
				return ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), elements);
			}
			LispVal tail = NIL_CONST;
			for (int i = elements.size() - 1; i >= 0; i--) {
				tail = new LispCons(((LispCons) ((LispCons) elements.get(i)).cdr()).car(), tail);
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), tail);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), datum);
	}

	/** Whether the form is {@code (QUOTE x)}: a constant a quoted list can hold as is. */
	static boolean isQuoteForm(LispVal form) {
		return form instanceof LispCons cell && cell.car() instanceof LispSymbol s && s.name().equals("QUOTE")
				&& cell.cdr() instanceof LispCons;
	}

	/**
	 * A quoted map or set literal: the construction over the quoted elements, so
	 * {@code '{:a x}} builds a table holding the symbol. Markers never reach the
	 * per-element path -- they are skipped here, not quoted.
	 */
	LispVal quotedCollection(List<LispVal> items) {
		List<LispVal> quoted = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			quoted.add(quotedElement(items.get(i)));
		}
		if (ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-map")) {
			return ClojureCollectionLowering.mapBuild(quoted);
		}
		return ClojureCollectionLowering.setBuild(this, quoted);
	}

	/**
	 * One element of a quoted map or set construction: the construction RUNS, so its
	 * elements are forms, not data -- each element's own quote form (a symbol or a list
	 * stays data instead of being evaluated); a keyword travels as its construction.
	 */
	LispVal quotedElement(LispVal datum) {
		if (datum instanceof LispSymbol s && s.name().startsWith(":")) {
			return ClojureCollectionLowering
				.keywordForm(ClojureCollectionLowering.resolveKeywordSpelling(this, s.name()));
		}
		return quote(datum);
	}

	// errors: try over handler-case and unwind-protect, throw over error

	/**
	 * One parsed method implementation: the method name, its parameter vector and body.
	 */
	record TypeMethod(String method, LispVal params, List<LispVal> body) {
	}

	/** One parsed implementation group: the protocol plus its method implementations. */
	record ImplGroup(String protocol, List<TypeMethod> methods) {
	}

	/**
	 * The false binding as a standalone form, for the macro-time evaluator the driver
	 * builds: the same form a file carries first, so expansions answer the false object
	 * the program compares against.
	 * @return the form
	 */
	public static LispVal falseBindingForm() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("SETQ"), new LispSymbol(FALSE_VARIABLE),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("QUOTE"), new LispSymbol(FALSE_VALUE_NAME)));
	}

	/**
	 * The definitions of every {@code clojure.core} special, for the macro-time
	 * evaluator: a macro body binding {@code *out*} (a {@code with-out-str}) or reading a
	 * flag finds it there too.
	 * @return the forms
	 */
	public static List<LispVal> coreSpecialForms() {
		return ClojureCoreSpecials.definitions(new ClojureLowering(), ClojureCoreSpecials.names());
	}

	// hierarchies: derive/underive/isa?/parents/ancestors/descendants/make-hierarchy,
	// prefer-method and defmulti :hierarchy over one shared runtime

	/** A {@code ..} step's method name and argument datums. */
	record DotStep(String method, List<LispVal> args) {
	}

	/**
	 * What the host class says about a static member: a static field, the fixed arities
	 * of its non-variadic static methods, the subset answering a boolean, and whether a
	 * variadic one exists.
	 */
	record StaticMember(boolean field, List<Integer> arities, Set<Integer> booleanArities, boolean variadic) {
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

	/**
	 * Whether the name is bound: a local, or a var of a project namespace it resolves to
	 * ({@link #resolveVar}).
	 */
	boolean known(String name) {
		return isLocal(name) || resolveVar(name) != null;
	}

	/**
	 * Whether a qualified name can only name a host member: no local, core spelling, var,
	 * library var or project namespace claims it. Only there do {@code ^[types]} param
	 * tags select an overload; anywhere else they are plain reader metadata.
	 */
	boolean hostMemberName(String name) {
		int slash = qualifierSlash(name);
		return slash > 0 && ClojureCoreNames.coreSpelling(name) == null && !known(name)
				&& ClojureNamespaceLowering.resolveQualified(this, name) == null
				&& ClojureNamespaceLowering.libraryRefer(this, name) == null
				&& projectNamespaceOf(name.substring(0, slash)) == null;
	}

	boolean isFunction(String name) {
		for (int i = this.scopes.size() - 1; i >= 0; i--) {
			Kind kind = this.scopes.get(i).get(name);
			if (kind != null) {
				// the innermost binding decides, so a local shadows an outer one
				// (and a global): a parameter holding a collection routes through
				// the dispatcher, a local function stays direct
				return kind == Kind.FUNCTION;
			}
		}
		return globalKind(name) == Kind.FUNCTION;
	}

	/**
	 * Whether the name resolves to a var only {@code declare}d so far: no local binds it
	 * and no definition has been seen. Its root is the value cell, which holds the
	 * unbound root until a definition binds it.
	 */
	boolean isDeclaredOnly(String name) {
		for (Map<String, Kind> scope : this.scopes) {
			if (scope.containsKey(name)) {
				return false;
			}
		}
		return globalKind(name) == Kind.DECLARED;
	}

	boolean isMacro(String name) {
		for (Map<String, Kind> scope : this.scopes) {
			Kind kind = scope.get(name);
			if (kind != null) {
				return kind == Kind.MACRO;
			}
		}
		return globalKind(name) == Kind.MACRO;
	}

	/** The kind of the var a name resolves to, or null when it names none. */
	@Nullable Kind globalKind(String name) {
		String key = resolveVar(name);
		return key == null ? null : this.globals.get(key);
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

	<T> T inScope(Map<String, Kind> scope, java.util.function.Supplier<T> body) {
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

	/**
	 * The innermost local binding kind of a name, or null when no local scope binds it (a
	 * global or an unknown name).
	 */
	@Nullable Kind localKind(String name) {
		for (int i = this.scopes.size() - 1; i >= 0; i--) {
			Kind kind = this.scopes.get(i).get(name);
			if (kind != null) {
				return kind;
			}
		}
		return null;
	}

	/**
	 * The deftype mutable fields a closure created here would capture: every field whose
	 * innermost binding is still the field itself, in declaration order.
	 */
	List<String> visibleMutableFields() {
		List<String> names = new ArrayList<>();
		for (String name : this.mutableFieldPlaces.keySet()) {
			if (localKind(name) == Kind.MUTABLE_FIELD) {
				names.add(name);
			}
		}
		return names;
	}

	/**
	 * One closure-creating form ({@code fn}, {@code reify}, {@code lazy-seq},
	 * {@code for}, {@code dosync}, {@code proxy}) lowered inside a deftype method: the
	 * oracle compiles it to a class whose constructor copies each mutable field it reads,
	 * so the closure sees the value at creation and a {@code set!} inside it is the
	 * non-mutable refusal. Each visible mutable field rebinds as a plain local around the
	 * form, initialized from its slot; only the fields the lowered form mentions bind.
	 */
	LispVal capturingMutableFields(java.util.function.Supplier<LispVal> lowering) {
		List<String> names = visibleMutableFields();
		if (names.isEmpty()) {
			return lowering.get();
		}
		Map<String, Kind> scope = new HashMap<>();
		for (String name : names) {
			scope.put(name, Kind.VARIABLE);
		}
		LispVal value = inScope(scope, lowering);
		List<LispVal> bindings = capturedBindings(names, value);
		return bindings.isEmpty() ? value
				: ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), value);
	}

	/** The slot form of a mutable field in scope (see {@link #mutableFieldPlaces}). */
	LispVal mutableFieldPlace(String name) {
		LispVal place = this.mutableFieldPlaces.get(name);
		if (place == null) {
			throw new IllegalStateException("no mutable field in scope: " + name);
		}
		return place;
	}

	/**
	 * The {@code (field slot)} bindings of the captured fields the lowered form mentions.
	 */
	List<LispVal> capturedBindings(List<String> names, LispVal lowered) {
		List<LispVal> bindings = new ArrayList<>();
		for (String name : names) {
			LispSymbol local = localSym(name);
			if (ClojureLowerUtil.mentions(lowered, local.name())) {
				bindings.add(ClojureLowerUtil.list(local, mutableFieldPlace(name)));
			}
		}
		return bindings;
	}

	enum Kind {

		/**
		 * {@code DECLARED}: a global only {@code declare}d so far (a definition replaces
		 * it). A file's is never defined, so it is an unbound var; a session's may be
		 * defined by a later buffer.
		 *
		 * <p>
		 * {@code MUTABLE_FIELD}: a deftype's {@code ^:unsynchronized-mutable}/
		 * {@code ^:volatile-mutable} field inside one of its inline methods, read and
		 * {@code set!} through the instance's slot ({@link #mutableFieldPlaces}).
		 */
		VARIABLE, FUNCTION, MACRO, MUTABLE_FIELD, DECLARED

	}

	// shape helpers

}
