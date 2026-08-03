package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.macro.FormatRenderer;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.macro.MopEvalCapture;
import am.ik.rontolisp.macro.MopProtocol;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispFuture;
import am.ik.rontolisp.LispThread;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispStream;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispStructLiteral;
import am.ik.rontolisp.StructLiteralFolder;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.SpecialVarCollector;
import am.ik.rontolisp.compiler.HttpPlistShape;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * Tree-walking interpreter for Lisp expressions.
 */
public final class LispEvaluator {

	/**
	 * Names that are special operators or macros and therefore have no function value:
	 * {@code (function name)} / {@code #'name} on these is an error, mirroring Common
	 * Lisp. Function-like macros (1+, zerop, ...) are excluded because they are also
	 * registered as functions in the global environment.
	 */
	private static final java.util.Set<String> SPECIAL_OPERATORS = PackageRegistry.specialOperatorNames();

	/**
	 * The "this operator is not mine" answer of {@link #evalConsRareOperator}, compared
	 * by identity. A freshly built symbol that appears in no AST and is returned by no
	 * evaluation, so it can never collide with a real value.
	 */
	private static final LispVal UNHANDLED = new LispSymbol("%unhandled-operator%");

	private final Environment globalEnv;

	private final PackageResolver packageResolver = new PackageResolver();

	/**
	 * Serializes every lazy load of a Lisp-source library / generated runtime into the
	 * global environment, and every read of the flag that guards one. The evaluator is
	 * SHARED across concurrently served requests (one virtual thread per request, see
	 * {@link HttpHandlerSupport}), and the flags are set before the definitions are
	 * installed -- so without this a request arriving mid-load skips the loader and then
	 * fails to resolve the very function that is being defined (.todo/193). Reentrant:
	 * loading a library evaluates its forms, which resolve further names through the same
	 * gates.
	 */
	private final Object libraryLoadLock = new Object();

	private boolean jsonLibraryLoaded = false;

	private boolean linalgLibraryLoaded = false;

	private boolean vecLibraryLoaded = false;

	private boolean simd = false;

	private final java.util.Set<String> loadedPreludeNames = new java.util.HashSet<>();

	// Whether the GENERATED restart runtime (the two stack globals plus
	// %run-handlers/find-restart/invoke-restart/...) has been evaluated into the
	// global environment. Doubles as the signal-hook gate: before the first
	// restart-system form no handler can be established, so error/warn/signal keep
	// their historical expansions until then (the interpreter re-expands per
	// evaluation, so later signals pick the hook up).
	private boolean restartRuntimeLoaded = false;

	// Whether the runtime format renderer (FormatRenderer.defuns(), the same forms the
	// compile path injects) has been evaluated into the global environment. The
	// interpreter cannot inject a top-level defun the way expandTopLevelDefinitions does,
	// so the renderer is loaded on the first resolution of one of its names -- which is
	// what a runtime-control (format ...) call and #'format both go through.
	private boolean formatRendererLoaded = false;

	// The registry shape (class count, report count) the loaded %condition-report-str
	// was generated from, or -1 before the first load. The renderer PARTITIONS the
	// registry, so a define-condition evaluated later makes it stale -- unlike the
	// compile path, where the registry is complete before the renderer is emitted.
	private int conditionReportRuntimeStamp = -1;

	// Forms already verified against the rontolisp:await placement rules, by identity:
	// a lambda form evaluated repeatedly (a closure created in a loop) is walked once.
	private final java.util.Set<LispVal> awaitCheckedForms = java.util.Collections
		.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

	// Runs the shared rontolisp:await placement check once per form identity: await is
	// legal only inside async-defun/async-lambda bodies and at top level, so a plain
	// defun/lambda body must not contain one (LispAsync recognizes the %async-run thunk
	// the async lowerings synthesize). A validated form's %async-run thunk lambdas are
	// pre-approved: when the surrounding form was legal, the thunk's awaits were checked
	// in async context, so evaluating the bare thunk as a lambda form later (which is
	// how %async-run receives it) must not re-check it as a plain lambda.
	private void checkAwaitPlacement(LispCons cons) {
		if (!this.awaitCheckedForms.add(cons)) {
			return;
		}
		try {
			am.ik.rontolisp.macro.LispAsync.check(cons, false);
		}
		catch (IllegalArgumentException ex) {
			throw new LispEvalException(java.util.Objects.requireNonNullElse(ex.getMessage(),
					am.ik.rontolisp.macro.LispAsync.AWAIT_PLACEMENT_MESSAGE));
		}
		preapproveAsyncRunThunks(cons);
	}

	private void preapproveAsyncRunThunks(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return;
		}
		List<LispVal> parts = cons.toList();
		if (cons.car() instanceof LispSymbol sym) {
			if (LispNames.QUOTE.equals(sym.name())) {
				return;
			}
			if (LispNames.ASYNC_RUN_QUALIFIED.equals(sym.name()) && parts.size() == 2
					&& parts.get(1) instanceof LispCons thunk) {
				this.awaitCheckedForms.add(thunk);
			}
		}
		for (LispVal part : parts) {
			preapproveAsyncRunThunks(part);
		}
	}

	private boolean urlLibraryLoaded = false;

	private boolean usocketLibraryLoaded = false;

	private boolean witLibraryLoaded = false;

	/**
	 * User macros defined with {@code defmacro}, keyed by name. A macro call is expanded
	 * (the body evaluated with the unevaluated argument forms bound) and the expansion is
	 * evaluated in its place.
	 */
	private final java.util.Map<String, UserMacro> userMacros = new java.util.HashMap<>();

	/**
	 * Compiler macros defined with {@code define-compiler-macro}, keyed by name. Unlike a
	 * {@code defmacro} these coexist with an ordinary function of the same name, so they
	 * live in their own table: {@link #isUserMacro} must not see them, or the function
	 * would be shadowed outright.
	 */
	private final java.util.Map<String, UserMacro> compilerMacros = new java.util.HashMap<>();

	/**
	 * One-step user-macro expander handed to
	 * {@link LispMacroExpander#expandSymbolMacrolet(LispCons, LispMacroExpander.UserMacroHook)}:
	 * the substitution walk must expand a user macro it meets before substituting into
	 * its expansion (a nested trivia {@code match} inside a symbol-macrolet body
	 * references the macro names only through its own expansion). Consults the live
	 * table, so {@code macrolet}-local macros active at evaluation time are seen too.
	 */
	private final LispMacroExpander.UserMacroHook symbolMacroUserMacroHook = form -> form.car() instanceof LispSymbol op
			&& this.userMacros.containsKey(op.name()) ? expandUserMacro(form) : null;

	/**
	 * Memo of {@link #expandCompilerMacro}, keyed by the CALL SITE's cons identity: a
	 * compiler macro is a compile-time hint, so applying it once per source occurrence
	 * (rather than once per evaluation) is both the point of the optimization and what
	 * makes the {@code load-time-value} memo below hit -- the cached expansion is one
	 * object, so its {@code load-time-value} occurrence is one object too.
	 */
	private final java.util.IdentityHashMap<LispVal, LispVal> compilerMacroExpansions = new java.util.IdentityHashMap<>();

	/**
	 * Memo of evaluated {@code (load-time-value ...)} occurrences, keyed by cons identity
	 * -- CL's "evaluated once" for interpreted code. Holds a one-element list so a
	 * {@code nil} result still counts as computed.
	 */
	private final java.util.IdentityHashMap<LispVal, List<LispVal>> loadTimeValues = new java.util.IdentityHashMap<>();

	/**
	 * Upper bound on the two identity memos above. A program that builds call forms at
	 * runtime and feeds them to {@code eval} would otherwise retain one entry per form
	 * forever; past the bound the expansion is simply recomputed, which is exactly the
	 * behavior before compiler macros were applied at all.
	 */
	private static final int EXPANSION_MEMO_LIMIT = 20_000;

	/**
	 * A user macro: required parameters, an optional {@code &rest}/{@code &body}
	 * parameter, the body forms, and the environment captured at definition time.
	 */
	private record UserMacro(List<LispSymbol> required, @Nullable LispSymbol rest, List<LispVal> body,
			Environment env) {
	}

	private SourceLoader sourceLoader = SourceLoader.fileSystem();

	/**
	 * The directories against which relative {@code load} paths resolve, innermost last
	 * (the directory of the file currently being loaded). Seeded by
	 * {@link #setLoadBaseDir} with the entry file's directory; each runtime {@code load}
	 * pushes the loaded file's directory so a nested {@code load} resolves relative to
	 * that file. The empty string means "no base directory" (working-directory-relative);
	 * it is never {@code null} because {@link java.util.ArrayDeque} forbids null
	 * elements.
	 */
	private final java.util.Deque<String> loadDirStack = new java.util.ArrayDeque<>();

	/**
	 * The modules marked loaded by {@code provide}: a {@code require} of a member is a
	 * no-op (the Common Lisp {@code *modules*} set, kept per evaluator so REPL state
	 * persists across inputs like the resolver's current package).
	 */
	private final java.util.Set<String> providedModules = new java.util.HashSet<>();

	/**
	 * The systems registered by {@code asdf:defsystem} (evaluated inline or read out of a
	 * {@code NAME.asd} file), by name. Kept per evaluator like the provided-module set.
	 */
	private final java.util.Map<String, AsdfSystems.LispSystem> asdfSystems = new java.util.HashMap<>();

	/**
	 * The systems already loaded by {@code asdf:load-system} (loading again is a no-op).
	 */
	private final java.util.Set<String> loadedSystems = new java.util.HashSet<>();

	/** The systems currently being loaded, for {@code :depends-on} cycle detection. */
	private final java.util.Deque<String> loadingSystems = new java.util.ArrayDeque<>();

	/**
	 * Extra directories searched for {@code NAME.asd} files by {@code asdf:load-system},
	 * after the directory of the loading file (the CLI threads {@code --system-path} and
	 * {@code RONTOLISP_SOURCE_REGISTRY} here).
	 */
	private List<String> systemPath = List.of();

	/**
	 * The Quicklisp downloader behind {@code ql:quickload}: created lazily on first use
	 * (so a program that never calls {@code ql:quickload} touches no network/cache), or
	 * injected by a test via {@link #setQuicklispClient}.
	 */
	@Nullable private QuicklispClient quicklispClient;

	/**
	 * {@code defstruct} accessor names to their 1-based slot position, accumulated by
	 * {@link LispMacroExpander#expandDefstruct} so {@code setf} can treat accessor calls
	 * as places. Kept per evaluator, like the user macro table.
	 */
	private final java.util.Map<String, Integer> structAccessors = new java.util.HashMap<>();

	/**
	 * User-defined {@code setf} expansions by accessor name, from
	 * {@code define-setf-expander}/{@code defsetf}. Consulted when {@code setf} (and the
	 * modify macros) see a place {@code (accessor ...)} whose head is registered here;
	 * the place is expanded through {@link #expandUserSetfExpanderPlace}. Kept per
	 * evaluator like the struct accessor and CLOS registries, so the compile-path
	 * macro-time evaluator ({@code UserMacroExpander}) reuses the same machinery.
	 */
	private final java.util.Map<String, UserSetf> setfExpanders = new java.util.HashMap<>();

	/** A registered user {@code setf} expansion for one accessor. */
	private sealed interface UserSetf permits SetfExpanderForm, DefsetfShort, DefsetfLong {

	}

	/**
	 * A {@code define-setf-expander}: its parameter lambda list (already stripped of
	 * {@code &environment}) and body; the optional environment variable is bound to nil.
	 */
	private record SetfExpanderForm(LispVal lambdaList, java.util.List<LispVal> body,
			@org.jspecify.annotations.Nullable String envVar) implements UserSetf {
	}

	/**
	 * A {@code defsetf} short form: {@code (setf (access a...) v)} ->
	 * {@code (update a... v)}.
	 */
	private record DefsetfShort(String updateFn) implements UserSetf {
	}

	/**
	 * A {@code defsetf} long form: the access lambda list, the store-value variable list,
	 * and the body evaluated at expansion time to yield the store form.
	 */
	private record DefsetfLong(java.util.List<LispVal> argParams, java.util.List<LispVal> storeParams,
			java.util.List<LispVal> body) implements UserSetf {
	}

	/**
	 * Counter for the temporaries generated by user setf expansions / get-setf-expansion.
	 */
	private final java.util.concurrent.atomic.AtomicInteger setfTempCounter = new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * The names proclaimed <em>special</em> (dynamic binding) by
	 * {@code defvar}/{@code defparameter}/{@code defconstant} and
	 * {@code (declaim (special ...))}/{@code (proclaim '(special ...))}, accumulated as
	 * top-level forms are evaluated. A {@code let}/{@code let*}/{@code progv} of one of
	 * these names establishes a thread-scoped dynamic binding ({@link #dynamicBindings})
	 * rather than a lexical binding; variable reads and {@code setq} consult it.
	 * Concurrent because HTTP-handler requests read it from separate virtual threads
	 * (they only ever read it -- specials are declared by top-level forms before serving
	 * begins).
	 */
	private final java.util.Set<String> specialVars = java.util.concurrent.ConcurrentHashMap.newKeySet();

	{
		// Seeded: *print-escape* is proclaimed special without a defvar in user code,
		// because the print-object route BINDS it around the method call so a method can
		// tell prin1 from princ. *print-readably* joins it so a program may bind that
		// too (nothing here does).
		this.specialVars.add(LispNames.PRINT_ESCAPE_VAR);
		this.specialVars.add(LispNames.PRINT_READABLY_VAR);
		// *read-eval* joins them: (let ((*read-eval* nil)) (read ...)) must bind
		// dynamically for the #. check in resolveReadTimeEval to see it.
		this.specialVars.add(LispNames.READ_EVAL_VAR);
	}

	/**
	 * The thread-scoped dynamic bindings of special variables (see
	 * {@link DynamicBindings}).
	 */
	private final DynamicBindings dynamicBindings = new DynamicBindings();

	/**
	 * True once any {@code progv} has run. {@code progv} can dynamically bind a symbol
	 * that was never declared special, so once it is in play the variable-read fast path
	 * must consult {@link #dynamicBindings} even for names absent from
	 * {@link #specialVars}. Set-once, never cleared.
	 */
	private volatile boolean progvUsed = false;

	/**
	 * The CLOS registry (classes, generics, slot positions) behind
	 * {@code defclass}/{@code defgeneric}/{@code defmethod}/{@code make-instance}/
	 * {@code slot-value}. Kept per evaluator, like the struct accessor registry.
	 */
	private final ClosRegistry closRegistry = new ClosRegistry();

	/**
	 * Generic-function name -&gt; the internal name the Java-backed built-in it shadows
	 * was stashed under, for the generics whose name a program defined a method on. The
	 * stash happens EXACTLY ONCE per name and every later dispatcher regeneration (there
	 * is one per {@code defmethod}) reuses it: re-probing the binding would find the
	 * dispatcher where the built-in was and silently drop the default method, and
	 * re-stashing what it found would make the dispatcher's own fall-through recurse
	 * forever. See {@link #builtinDefaultMethodFor}.
	 */
	private final Map<String, String> builtinDefaultMethods = new HashMap<>();

	/**
	 * The {@code compile} built-in's capture target when this evaluator is a compile
	 * path's MACRO-TIME evaluator: {@code UserMacroExpander} attaches a list here, and an
	 * intercepted definition-time method construction (see {@link MopEvalCapture}) is
	 * recorded into it instead of evaluated, to be spliced into the program as top-level
	 * forms. Null on a live interpreter, which evaluates the capture in place.
	 */
	@Nullable private List<LispVal> mopEvalSpliceSink;

	/**
	 * The program's output stream, wrapped so expansion-time output can be silenced; see
	 * {@link MutablePrintStream}.
	 */
	private final MutablePrintStream out;

	/**
	 * Create a new evaluator with the given output stream.
	 * @param out the output stream for print operations
	 */
	public LispEvaluator(PrintStream out) {
		this.out = new MutablePrintStream(out);
		this.globalEnv = Environment.createGlobal(this.out);
		registerEval();
	}

	/**
	 * Create a new evaluator with the given output and input streams.
	 * @param out the output stream for print operations
	 * @param in the input stream for read operations
	 */
	public LispEvaluator(PrintStream out, InputStream in) {
		this.out = new MutablePrintStream(out);
		this.globalEnv = Environment.createGlobal(this.out, in);
		registerEval();
	}

	/**
	 * The evaluator's output stream with a mute switch. Only one thing uses it: a
	 * compiler-macro body runs at EXPANSION time, and its diagnostics (cl-utilities'
	 * {@code partition} compiler macros {@code warn} before declining) are a property of
	 * the expansion, not program output. The compile path already swallows them -- the
	 * macro-time evaluator there writes to a null stream -- so muting here is what keeps
	 * the interpreter's output identical to the compiled backends'.
	 *
	 * <p>
	 * {@code PrintStream} funnels every {@code print}/{@code println} through its own
	 * {@code write(byte[], int, int)}, so overriding the two write methods covers the
	 * whole surface.
	 */
	private static final class MutablePrintStream extends PrintStream {

		private boolean muted;

		private MutablePrintStream(PrintStream delegate) {
			super(delegate, true);
		}

		@Override
		public void write(int b) {
			if (!this.muted) {
				super.write(b);
			}
		}

		@Override
		public void write(byte[] buf, int off, int len) {
			if (!this.muted) {
				super.write(buf, off, len);
			}
		}

	}

	/**
	 * Sets the loader used to resolve {@code (load "path")} source text. Defaults to the
	 * local filesystem; environments without a filesystem (e.g. the browser playground)
	 * can install an in-memory loader.
	 * @param loader the source loader
	 */
	public void setSourceLoader(SourceLoader loader) {
		this.sourceLoader = java.util.Objects.requireNonNull(loader);
	}

	/**
	 * Sets the base directory against which a top-level relative {@code load} path
	 * resolves -- normally the directory of the entry file being interpreted, so that a
	 * program run from anywhere can {@code (load "sibling.lisp")} its companions (like
	 * Common Lisp's {@code *load-pathname*}). Pass {@code null} (the REPL / stdin
	 * default) to keep top-level loads working-directory-relative.
	 * @param dir the entry file's directory, or {@code null} for
	 * working-directory-relative
	 */
	public void setLoadBaseDir(@Nullable String dir) {
		this.loadDirStack.clear();
		this.loadDirStack.addLast(dir == null ? "" : dir);
	}

	/**
	 * Attaches the {@code compile} built-in's capture target -- see
	 * {@link #mopEvalSpliceSink}. {@code UserMacroExpander} sets this on the macro-time
	 * evaluator so a definition-time method construction intercepted during macro-time
	 * evaluation is recorded for splicing instead of evaluated here.
	 * @param sink the list intercepted top-level forms are appended to
	 */
	public void setMopEvalSpliceSink(List<LispVal> sink) {
		this.mopEvalSpliceSink = sink;
	}

	/** The package-stripped member of a possibly qualified name. */
	private static String memberName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	/**
	 * Sets the extra directories searched for {@code NAME.asd} files by
	 * {@code asdf:load-system}, after the directory of the loading file. The CLI threads
	 * the {@code --system-path} option and the {@code RONTOLISP_SOURCE_REGISTRY}
	 * environment variable here.
	 * @param systemPath the directories to search, in order
	 */
	public void setSystemPath(List<String> systemPath) {
		this.systemPath = List.copyOf(systemPath);
	}

	/**
	 * Enables the opt-in {@code --simd} acceleration of the {@code vec:} kernels: when
	 * the vec library is loaded, its seven vectorizable defuns are overridden with the
	 * {@code jdk.incubator.vector} natives of {@link VecSimd}. Off by default -- the
	 * scalar {@code vec.lisp} reference is the cross-backend byte-identity oracle. The
	 * caller must have checked {@link VecSimd#available()}; enabling it on a runtime
	 * without the incubator module would fail at the first {@code vec:} call.
	 * @param simd whether to vectorize the vec: kernels
	 */
	public void setSimd(boolean simd) {
		this.simd = simd;
	}

	/**
	 * Installs the Quicklisp downloader used by {@code ql:quickload}. Mainly a test seam
	 * (inject a client with an in-memory {@link QuicklispClient.Downloader} and a
	 * temporary cache directory); left {@code null} in production, where the default
	 * client ({@link QuicklispClient#createDefault}) is created on first use.
	 * @param client the Quicklisp client
	 */
	public void setQuicklispClient(QuicklispClient client) {
		this.quicklispClient = client;
	}

	private void registerEval() {
		// The stream-argument-less print family resolves its destination through the
		// current -- dynamic-first -- value of *standard-output*, so a
		// (let ((*standard-output* stream)) ...) redirects it (the t default keeps the
		// process standard output). Environment holds no dynamic store, so the read is
		// layered on here.
		this.globalEnv.setDefaultOutput(() -> {
			if ((!this.specialVars.isEmpty() || this.progvUsed)
					&& this.dynamicBindings.isBound(LispNames.STANDARD_OUTPUT_VAR)) {
				return this.dynamicBindings.get(LispNames.STANDARD_OUTPUT_VAR);
			}
			return this.globalEnv.lookupOrNull(LispNames.STANDARD_OUTPUT_VAR);
		});
		// warn resolves its destination through the current value of *error-output* (the
		// seeded handle 2 = the process standard error unless the program rebound it),
		// so (let ((*error-output* s)) (warn ...)) captures the report.
		this.globalEnv.setDefaultError(() -> {
			if ((!this.specialVars.isEmpty() || this.progvUsed)
					&& this.dynamicBindings.isBound(LispNames.ERROR_OUTPUT_VAR)) {
				return this.dynamicBindings.get(LispNames.ERROR_OUTPUT_VAR);
			}
			return this.globalEnv.lookupOrNull(LispNames.ERROR_OUTPUT_VAR);
		});
		// The same rule on the input side: the stream-argument-less read family resolves
		// its source through the current value of *standard-input*.
		this.globalEnv.setDefaultInput(() -> {
			if ((!this.specialVars.isEmpty() || this.progvUsed)
					&& this.dynamicBindings.isBound(LispNames.STANDARD_INPUT_VAR)) {
				return this.dynamicBindings.get(LispNames.STANDARD_INPUT_VAR);
			}
			return this.globalEnv.lookupOrNull(LispNames.STANDARD_INPUT_VAR);
		});
		// The runtime readers return DATA, and a #S(...) datum must be the instance it
		// denotes exactly as a source literal is. Environment holds no registry, so the
		// fold is layered on here, where this evaluator's registry is in scope; wrapping
		// the function BINDING (rather than the call sites) also keeps
		// #'read/#'read-from-string folding.
		foldStructLiteralsOf(LispNames.READ);
		foldStructLiteralsOf(LispNames.READ_FROM_STRING);
		// #. read-time eval for the runtime readers: with the resolver installed,
		// read/read-from-string read a #.-bearing datum in marker mode and this evaluator
		// substitutes each datum's value in place (Environment.readRuntimeDatum). Without
		// it (a bare Environment) the error-mode read signals, matching the compiled
		// backends' embedded readers.
		this.globalEnv.setReadTimeEvalResolver(this::resolveReadTimeEval);
		this.globalEnv.defineFunction(LispNames.EVAL, new LispFunction(LispNames.EVAL, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.EVAL + " expects 1 argument, got " + args.size());
			}
			return eval(args.get(0));
		}));
		// compile: coerce a literal (lambda ...) definition to a function in the null
		// lexical environment. A no-argument definition that DEFINES METHODS over class
		// metaobjects -- postmodern's build-dao-methods (funcall (compile nil `(lambda ()
		// ,code))) idiom -- is intercepted instead: the metaobject literals fold to
		// static references (MopEvalCapture) and the body is either evaluated in place
		// (live interpreter) or recorded through the splice sink UserMacroExpander
		// attaches so it joins the compiled program as top-level forms.
		this.globalEnv.defineFunction(LispNames.COMPILE, new LispFunction(LispNames.COMPILE, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(
						LispNames.COMPILE + " expects (compile name definition), got " + args.size() + " argument(s)");
			}
			LispVal definition = MopEvalCapture.foldClassMetaobjects(args.get(1), this.closRegistry);
			if (!(definition instanceof LispCons defCons) || !(defCons.car() instanceof LispSymbol head)
					|| !LispNames.LAMBDA.equals(memberName(head.name())) || !defCons.isProperList()) {
				throw new LispEvalException(
						LispNames.COMPILE + " expects a (lambda ...) definition, got " + args.get(1).print());
			}
			List<LispVal> defParts = defCons.toList();
			if (defParts.size() >= 2 && defParts.get(1) instanceof LispNil && MopEvalCapture.definesMethods(defCons)) {
				// Definition-time method construction ("expand and splice"): the folded
				// body forms are the definition's whole effect, so they run (or splice)
				// as top-level forms and the returned function is a no-op.
				List<LispVal> body = List.copyOf(defParts.subList(2, defParts.size()));
				if (this.mopEvalSpliceSink != null) {
					this.mopEvalSpliceSink.addAll(body);
					return new LispFunction("compiled-definition", callArgs -> LispNil.INSTANCE);
				}
				return new LispFunction("compiled-definition", callArgs -> {
					LispVal result = LispNil.INSTANCE;
					for (LispVal bodyForm : body) {
						result = eval(bodyForm);
					}
					return result;
				});
			}
			LispVal fn = eval(defCons);
			if (args.get(0) instanceof LispSymbol name && !name.isKeyword()) {
				// (compile 'name def) also installs the function under the name and
				// returns the name, per CL.
				this.globalEnv.defineFunction(name.name(), fn);
				return name;
			}
			return fn;
		}));
		// macroexpand-1/macroexpand live on the evaluator (not Environment) because they
		// need the user macro table. On the compile path, calls with a literal quoted
		// argument are folded to their expansion by UserMacroExpander.
		// The optional second argument is CL's macro-expansion environment; there is no
		// lexical macro environment to consult (macrolet is expanded away before any
		// body runs), so it is accepted and ignored -- which is what lets a portable
		// code walker's (macroexpand form env) load on every backend.
		this.globalEnv.defineFunction(LispNames.MACROEXPAND_1, new LispFunction(LispNames.MACROEXPAND_1, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.MACROEXPAND_1 + " expects 1 or 2 arguments, got " + args.size());
			}
			return macroexpand1(args.get(0));
		}));
		this.globalEnv.defineFunction(LispNames.MACROEXPAND, new LispFunction(LispNames.MACROEXPAND, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.MACROEXPAND + " expects 1 or 2 arguments, got " + args.size());
			}
			return macroexpand(args.get(0));
		}));
		this.globalEnv.defineFunction(LispNames.SYMBOL_FUNCTION, new LispFunction(LispNames.SYMBOL_FUNCTION, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.SYMBOL_FUNCTION + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispSymbol sym)) {
				throw new LispEvalException(
						LispNames.SYMBOL_FUNCTION + " expects a symbol, got " + args.get(0).print());
			}
			return resolveFunction(sym.name());
		}));
		// fdefinition = symbol-function for symbol designators (no setf-function names).
		this.globalEnv.defineFunction(LispNames.FDEFINITION, new LispFunction(LispNames.FDEFINITION, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.FDEFINITION + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispSymbol sym)) {
				throw new LispEvalException(LispNames.FDEFINITION + " expects a symbol, got " + args.get(0).print());
			}
			return resolveFunction(sym.name());
		}));
		// subtypep over the built-in type lattice + the CLOS class registry. A single
		// primary value: t when sub is known to be a subtype of super, nil otherwise.
		this.globalEnv.defineFunction(LispNames.SUBTYPEP, new LispFunction(LispNames.SUBTYPEP, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.SUBTYPEP + " expects 2 arguments, got " + args.size());
			}
			return subtypep(args.get(0), args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// The instance primitives. Every struct/class/condition instance is built, read,
		// written and type-tested through these; nothing else may touch the slot
		// storage, which is what keeps the value model swappable behind one seam.
		this.globalEnv.defineFunction(LispNames.OBJ_NEW, new LispFunction(LispNames.OBJ_NEW, args -> {
			if (args.isEmpty() || !(args.get(0) instanceof LispSymbol tag)) {
				throw new LispEvalException(LispNames.OBJ_NEW + " expects an instance tag symbol");
			}
			LispLayout layout = this.closRegistry.findLayoutByTag(tag.name());
			if (layout == null) {
				throw new LispEvalException(LispNames.OBJ_NEW + ": unknown instance type " + tag.name());
			}
			// capacity, not slotCount: a change-class target's ancestors reserve room
			// for the wider layout (LispLayout.capacity).
			LispVal[] slots = new LispVal[layout.capacity()];
			for (int i = 0; i < slots.length; i++) {
				slots[i] = i + 1 < args.size() && i < layout.slotCount() ? args.get(i + 1) : LispNil.INSTANCE;
			}
			return new LispInstance(layout, slots);
		}));
		this.globalEnv.defineFunction(LispNames.OBJ_BECOME, new LispFunction(LispNames.OBJ_BECOME, args -> {
			if (args.size() != 2 || !(args.get(1) instanceof LispSymbol tag)) {
				throw new LispEvalException(LispNames.OBJ_BECOME + " expects (obj 'tag), got " + args.size());
			}
			LispInstance inst = requireInstance(LispNames.OBJ_BECOME, args);
			LispLayout layout = this.closRegistry.findLayoutByTag(tag.name());
			if (layout == null) {
				throw new LispEvalException(LispNames.OBJ_BECOME + ": unknown instance type " + tag.name());
			}
			inst.becomeLayout(layout);
			return inst;
		}));
		this.globalEnv.defineFunction(LispNames.OBJ_REF, new LispFunction(LispNames.OBJ_REF, args -> {
			LispInstance inst = requireInstance(LispNames.OBJ_REF, args);
			return inst.slot(requireSlotIndex(LispNames.OBJ_REF, inst, args));
		}));
		this.globalEnv.defineFunction(LispNames.OBJ_SET, new LispFunction(LispNames.OBJ_SET, args -> {
			if (args.size() != 3) {
				throw new LispEvalException(LispNames.OBJ_SET + " expects 3 arguments, got " + args.size());
			}
			LispInstance inst = requireInstance(LispNames.OBJ_SET, args);
			inst.setSlot(requireSlotIndex(LispNames.OBJ_SET, inst, args), args.get(2));
			return args.get(2);
		}));
		this.globalEnv.defineFunction(LispNames.OBJ_IS, new LispFunction(LispNames.OBJ_IS, args -> {
			if (args.isEmpty() || !(args.get(0) instanceof LispInstance inst)) {
				return LispNil.INSTANCE;
			}
			for (int i = 1; i < args.size(); i++) {
				if (args.get(i) instanceof LispSymbol tag && inst.hasTag(tag.name())) {
					return LispTrue.INSTANCE;
				}
			}
			return LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.OBJ_TAG, new LispFunction(LispNames.OBJ_TAG, args -> {
			requireSingleArg(LispNames.OBJ_TAG, args);
			return args.get(0) instanceof LispInstance inst ? new LispSymbol(inst.layout().tag()) : LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.OBJ_P, new LispFunction(LispNames.OBJ_P, args -> {
			requireSingleArg(LispNames.OBJ_P, args);
			return args.get(0) instanceof LispInstance ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.OBJ_SLOTS, new LispFunction(LispNames.OBJ_SLOTS, args -> {
			requireSingleArg(LispNames.OBJ_SLOTS, args);
			if (!(args.get(0) instanceof LispInstance inst)) {
				return LispNil.INSTANCE;
			}
			LispVal list = LispNil.INSTANCE;
			for (int i = inst.slotCount() - 1; i >= 0; i--) {
				list = new LispCons(inst.slot(i), list);
			}
			return list;
		}));
		this.globalEnv.defineFunction(LispNames.CLASS_OF, new LispFunction(LispNames.CLASS_OF, args -> {
			requireSingleArg(LispNames.CLASS_OF, args);
			// The class METAOBJECT of any value: the memoized standard-class instance
			// find-class answers for an instance's type, or the built-in class
			// metaobject of everything else -- (eq (class-of x) (find-class name))
			// holds. The old tag/type-name view lives on as %class-designator.
			LispVal v = args.get(0);
			if (v instanceof LispInstance inst) {
				LispVal metaobject = this.closRegistry.classMetaobject(inst.layout().tag());
				if (metaobject != null) {
					return metaobject;
				}
				// A layout-only internal type (the unbound marker); T is the one class
				// that covers everything.
				return java.util.Objects.requireNonNull(this.closRegistry.builtinClassMetaobject("T"));
			}
			// builtinTypeName only yields members of BUILTIN_CLASS_NAMES, so the answer
			// is never null.
			return java.util.Objects.requireNonNull(
					this.closRegistry.builtinClassMetaobject(builtinTypeName(v).toUpperCase(java.util.Locale.ROOT)));
		}));
		this.globalEnv.defineFunction(LispNames.CLASS_DESIGNATOR_INTERNAL,
				new LispFunction(LispNames.CLASS_DESIGNATOR_INTERNAL, args -> {
					requireSingleArg(LispNames.CLASS_DESIGNATOR_INTERNAL, args);
					// The light view class-of had before the metaobject migration: the
					// instance-tag symbol of a struct/CLOS instance, or a built-in type
					// name (printed uppercase like every symbol under the reader's
					// upcase premise; the compile backends return INTEGER/STRING/...
					// too).
					LispVal v = args.get(0);
					if (v instanceof LispInstance inst) {
						return new LispSymbol(inst.layout().tag());
					}
					return new LispSymbol(builtinTypeName(v).toUpperCase(java.util.Locale.ROOT));
				}));
		this.globalEnv.defineFunction(LispNames.FIND_CLASS, new LispFunction(LispNames.FIND_CLASS, args -> {
			// (find-class symbol &optional (errorp t) environment) -- a registered class
			// answers with its memoized metaobject (eq-stable across calls), anything
			// else signals unless errorp is nil. Defined here, ahead of the prelude's
			// always-nil stub, because the answer needs the class registry.
			if (args.isEmpty() || args.size() > 3) {
				throw new LispEvalException(LispNames.FIND_CLASS + " expects 1 to 3 arguments, got " + args.size());
			}
			boolean errorp = args.size() < 2 || !(args.get(1) instanceof LispNil);
			if (args.get(0) instanceof LispSymbol sym) {
				LispVal metaobject = this.closRegistry.classMetaobject(sym.name());
				if (metaobject == null) {
					// The built-in classes (integer, string, ..., t) exist too, so
					// (eq (class-of 42) (find-class 'integer)) holds.
					metaobject = this.closRegistry.builtinClassMetaobject(sym.name());
				}
				if (metaobject != null) {
					return metaobject;
				}
			}
			else if (args.get(0) instanceof LispTrue) {
				// (find-class t) -- T the class of everything; the reader yields the
				// boolean, not a symbol.
				return java.util.Objects.requireNonNull(this.closRegistry.builtinClassMetaobject("T"));
			}
			if (errorp) {
				throw new LispEvalException(LispNames.FIND_CLASS + ": there is no class named "
						+ (args.get(0) instanceof LispSymbol s ? s.name() : args.get(0).print()));
			}
			return LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.ALLOCATE_INSTANCE,
				new LispFunction(LispNames.ALLOCATE_INSTANCE, args -> {
					// (allocate-instance class &rest initargs) -- an instance with EVERY
					// slot unbound: no initforms, no initialize-instance (dao-from-fields
					// fills the slots by setf slot-value afterwards). The initargs are
					// accepted and ignored, per CL: they are for methods on it, which the
					// static subset has none of. The class may be a metaobject (the
					// find-class/class-of answer) or a class name; only registered CLOS
					// classes allocate -- a built-in or struct class signals, like CL's
					// built-in-class behavior.
					if (args.isEmpty()) {
						throw new LispEvalException(LispNames.ALLOCATE_INSTANCE + " expects a class, got 0 arguments");
					}
					LispVal designator = args.get(0);
					if (designator instanceof LispInstance inst && this.closRegistry.isClassMetaobject(inst)) {
						designator = inst.slot(0);
					}
					LispLayout layout = designator instanceof LispSymbol sym
							? this.closRegistry.findClassLayout(sym.name()) : null;
					if (layout == null) {
						throw new LispEvalException(LispNames.ALLOCATE_INSTANCE + ": not an allocatable class: "
								+ (designator instanceof LispSymbol s ? s.name() : args.get(0).print()));
					}
					LispLayout unbound = java.util.Objects
						.requireNonNull(this.closRegistry.findLayoutByTag(ClosRegistry.UNBOUND_TAG));
					LispVal[] slots = new LispVal[layout.capacity()];
					for (int i = 0; i < slots.length; i++) {
						slots[i] = i < layout.slotCount() ? new LispInstance(unbound, new LispVal[0])
								: LispNil.INSTANCE;
					}
					return new LispInstance(layout, slots);
				}));
		// (%mop-make-instance designator initargs...) -- the metaclass protocol's
		// runtime-class make-instance: the designator (a class metaobject or a name
		// symbol) picks the class at RUN time, which the static make-instance expansion
		// cannot. Re-enters the ordinary make-instance evaluation with every argument
		// quote-wrapped, so the constructor semantics and the initialization-generic
		// hooks stay the single implementation. The same body doubles as the function
		// value of make-instance itself (the (apply #'make-instance class args) idiom
		// of postmodern's make-dao); the literal-call path never consults it.
		java.util.function.Function<List<LispVal>, LispVal> runtimeMakeInstance = args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(
						LispNames.MOP_MAKE_INSTANCE + " expects a class designator, got 0 arguments");
			}
			LispVal designator = args.get(0);
			if (designator instanceof LispInstance inst && this.closRegistry.isClassMetaobject(inst)) {
				designator = inst.slot(0);
			}
			if (!(designator instanceof LispSymbol classSym)) {
				throw new LispEvalException(
						LispNames.MOP_MAKE_INSTANCE + " expects a class designator, got " + args.get(0).print());
			}
			LispVal call = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= 1; i--) {
				call = new LispCons(quoteForm(args.get(i)), call);
			}
			call = new LispCons(new LispSymbol(LispNames.MAKE_INSTANCE), new LispCons(quoteForm(classSym), call));
			return eval(call, this.globalEnv);
		};
		this.globalEnv.defineFunction(LispNames.MOP_MAKE_INSTANCE,
				new LispFunction(LispNames.MOP_MAKE_INSTANCE, runtimeMakeInstance::apply));
		this.globalEnv.defineFunction(LispNames.MAKE_INSTANCE,
				new LispFunction(LispNames.MAKE_INSTANCE, runtimeMakeInstance::apply));
		this.globalEnv.defineFunction(LispNames.SLOT_VALUE_SET_RUNTIME,
				new LispFunction(LispNames.SLOT_VALUE_SET_RUNTIME, args -> {
					// (%slot-value-set-runtime obj name value) -- what the shared setf
					// expansion emits for a runtime slot name (the compile paths generate
					// a dispatch defun of the same name); resolves natively like the
					// runtime-name slot-value read.
					if (args.size() != 3) {
						throw new LispEvalException(LispNames.SLOT_VALUE_SET_RUNTIME + " expects (obj name value), got "
								+ args.size() + " arguments");
					}
					SlotRef slot = instanceSlotRef(args.get(0), args.get(1));
					if (slot == null) {
						throw new LispEvalException(LispNames.SLOT_VALUE + ": unknown slot " + args.get(1).print()
								+ " on " + args.get(0).print());
					}
					slot.write(args.get(2));
					return args.get(2);
				}));
		this.globalEnv.defineFunction(LispNames.REGISTER_CLASS_METAOBJECT,
				new LispFunction(LispNames.REGISTER_CLASS_METAOBJECT, args -> {
					// (%register-class-metaobject name metaobject) -- primes the registry
					// memo, so find-class/class-of answer the driver-built metaclass
					// instance instead of materializing the plain standard-class view.
					if (args.size() != 2 || !(args.get(0) instanceof LispSymbol nameSym)
							|| !(args.get(1) instanceof LispInstance metaobject)) {
						throw new LispEvalException(
								LispNames.REGISTER_CLASS_METAOBJECT + " expects a class name and a metaobject");
					}
					this.closRegistry.registerClassMetaobject(nameSym.name(), metaobject);
					return metaobject;
				}));
		this.globalEnv.defineFunction(LispNames.CLASS_SLOT_DEFS_INTERNAL,
				new LispFunction(LispNames.CLASS_SLOT_DEFS_INTERNAL, args -> {
					requireSingleArg(LispNames.CLASS_SLOT_DEFS_INTERNAL, args);
					// ((slot-name declared-type) ...) for the type's full slot list; nil
					// for anything that is not a registered class or struct designator. A
					// class METAOBJECT designates through its name slot (what class-of
					// hands a slot-walking serializer since the metaobject migration).
					LispVal designator = args.get(0);
					if (designator instanceof LispInstance inst && this.closRegistry.isClassMetaobject(inst)) {
						designator = inst.slot(0);
					}
					if (!(designator instanceof LispSymbol sym)) {
						return LispNil.INSTANCE;
					}
					List<ClosRegistry.SlotDef> defs = this.closRegistry.slotDefs(sym.name());
					if (defs == null) {
						return LispNil.INSTANCE;
					}
					LispVal result = LispNil.INSTANCE;
					for (int i = defs.size() - 1; i >= 0; i--) {
						LispVal pair = new LispCons(new LispSymbol(defs.get(i).name()),
								new LispCons(new LispSymbol(defs.get(i).type()), LispNil.INSTANCE));
						result = new LispCons(pair, result);
					}
					return result;
				}));
		this.globalEnv.defineFunction(LispNames.SIMPLE_CONDITION_FORMAT_CONTROL,
				new LispFunction(LispNames.SIMPLE_CONDITION_FORMAT_CONTROL, args -> {
					requireSingleArg(LispNames.SIMPLE_CONDITION_FORMAT_CONTROL, args);
					return conditionSlotValue(args.get(0), "format-control");
				}));
		this.globalEnv.defineFunction(LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS,
				new LispFunction(LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS, args -> {
					requireSingleArg(LispNames.SIMPLE_CONDITION_FORMAT_ARGUMENTS, args);
					return conditionSlotValue(args.get(0), "format-arguments");
				}));
		this.globalEnv.defineFunction(LispNames.SLOT_BOUNDP, new LispFunction(LispNames.SLOT_BOUNDP, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.SLOT_BOUNDP + " expects 2 arguments, got " + args.size());
			}
			// Bound = the type declares the slot AND it does not hold the unbound
			// marker (a slot written with no :initform, or emptied by
			// slot-makunbound, holds one).
			SlotRef slot = instanceSlotRef(args.get(0), args.get(1));
			return slot != null && !isUnboundMarker(slot.read()) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// Gray-stream dispatch: write-string (and write-char, which lowers to it)
		// handed an INSTANCE as its stream calls rontolisp's own Gray protocol
		// (eval.GrayStreamsLibrary) instead of the handle-based built-in, so a user
		// output-stream class receives the writes. Portability layers
		// (trivial-gray-streams) adapt onto that protocol through their shim system;
		// the core knows no third-party name.
		LispVal baseWriteString = this.globalEnv.lookupFunction(LispNames.WRITE_STRING);
		this.globalEnv.defineFunction(LispNames.WRITE_STRING, new LispFunction(LispNames.WRITE_STRING, args -> {
			if (args.size() >= 2 && args.get(1) instanceof LispInstance) {
				ensureGrayStreamsLoaded();
				LispVal generic = resolveFunction(
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.GRAY_STREAM_WRITE_STRING));
				return apply(generic, List.of(args.get(1), args.get(0)), this.globalEnv);
			}
			return apply(baseWriteString, args, this.globalEnv);
		}));
		// Read-side and file-position Gray dispatch: the stream-taking built-ins
		// handed an INSTANCE delegate to the rontolisp::%gray-*-dispatch helpers of
		// gray.lisp, so the eof-error-p/eof-value contract and the :eof translation
		// live in ONE place shared with the compile path's call-site rewrites
		// (GrayStreamsLibrary.process). The helpers' non-instance fallbacks call the
		// wrapped built-ins again, which is one extra hop and no recursion (the wrap
		// routes non-instances straight to the base function).
		LispVal baseReadByte = this.globalEnv.lookupFunction(LispNames.READ_BYTE);
		this.globalEnv.defineFunction(LispNames.READ_BYTE, new LispFunction(LispNames.READ_BYTE, args -> {
			if (!args.isEmpty() && args.get(0) instanceof LispInstance) {
				return applyGrayDispatch(GRAY_READ_BYTE_DISPATCH,
						List.of(args.get(0), args.size() >= 2 ? args.get(1) : LispTrue.INSTANCE,
								args.size() >= 3 ? args.get(2) : LispNil.INSTANCE));
			}
			return apply(baseReadByte, args, this.globalEnv);
		}));
		LispVal baseReadChar = this.globalEnv.lookupFunction(LispNames.READ_CHAR);
		this.globalEnv.defineFunction(LispNames.READ_CHAR, new LispFunction(LispNames.READ_CHAR, args -> {
			if (!args.isEmpty() && args.get(0) instanceof LispInstance) {
				return applyGrayDispatch(GRAY_READ_CHAR_DISPATCH,
						List.of(args.get(0), args.size() >= 2 ? args.get(1) : LispTrue.INSTANCE,
								args.size() >= 3 ? args.get(2) : LispNil.INSTANCE));
			}
			return apply(baseReadChar, args, this.globalEnv);
		}));
		LispVal baseReadLine = this.globalEnv.lookupFunction(LispNames.READ_LINE);
		this.globalEnv.defineFunction(LispNames.READ_LINE, new LispFunction(LispNames.READ_LINE, args -> {
			if (!args.isEmpty() && args.get(0) instanceof LispInstance) {
				// eof-error-p defaults to NIL, the read-line lite convention the
				// handle-based built-in documents.
				return applyGrayDispatch(GRAY_READ_LINE_DISPATCH,
						List.of(args.get(0), args.size() >= 2 ? args.get(1) : LispNil.INSTANCE,
								args.size() >= 3 ? args.get(2) : LispNil.INSTANCE));
			}
			return apply(baseReadLine, args, this.globalEnv);
		}));
		LispVal baseWriteByte = this.globalEnv.lookupFunction(LispNames.WRITE_BYTE);
		this.globalEnv.defineFunction(LispNames.WRITE_BYTE, new LispFunction(LispNames.WRITE_BYTE, args -> {
			if (args.size() == 2 && args.get(1) instanceof LispInstance) {
				return applyGrayDispatch(GRAY_WRITE_BYTE_DISPATCH, List.of(args.get(0), args.get(1)));
			}
			return apply(baseWriteByte, args, this.globalEnv);
		}));
		LispVal baseListen = this.globalEnv.lookupFunction(LispNames.LISTEN);
		this.globalEnv.defineFunction(LispNames.LISTEN, new LispFunction(LispNames.LISTEN, args -> {
			if (args.size() == 1 && args.get(0) instanceof LispInstance) {
				return applyGrayDispatch(GRAY_LISTEN_DISPATCH, List.of(args.get(0)));
			}
			return apply(baseListen, args, this.globalEnv);
		}));
		LispVal baseFilePosition = this.globalEnv.lookupFunction(LispNames.FILE_POSITION);
		this.globalEnv.defineFunction(LispNames.FILE_POSITION, new LispFunction(LispNames.FILE_POSITION, args -> {
			if (!args.isEmpty() && args.get(0) instanceof LispInstance) {
				if (args.size() == 1) {
					return applyGrayDispatch(GRAY_FILE_POSITION_DISPATCH, List.of(args.get(0)));
				}
				if (args.size() == 2) {
					return applyGrayDispatch(GRAY_FILE_POSITION_SET_DISPATCH, List.of(args.get(0), args.get(1)));
				}
			}
			return apply(baseFilePosition, args, this.globalEnv);
		}));
		// probe-file: mediated by the SourceLoader rather than java.nio.file.Files, so a
		// host without a filesystem (the browser playground's in-memory loader) answers
		// from whatever IT can load. Working-directory-relative like open, not resolved
		// against the load stack.
		this.globalEnv.defineFunction(LispNames.PROBE_FILE, new LispFunction(LispNames.PROBE_FILE, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.PROBE_FILE + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(LispNames.PROBE_FILE + " expects a string pathname");
			}
			// The truename is the namestring itself (see LispNames.PROBE_FILE).
			return this.sourceLoader.exists(path.value()) ? path : LispNil.INSTANCE;
		}));
		// file-write-date: the same SourceLoader mediation as probe-file, for the same
		// reason -- a host without a filesystem has no modification times and answers the
		// nil Common Lisp already prescribes for "cannot be determined".
		this.globalEnv.defineFunction(LispNames.FILE_WRITE_DATE, new LispFunction(LispNames.FILE_WRITE_DATE, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.FILE_WRITE_DATE + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(LispNames.FILE_WRITE_DATE + " expects a string pathname");
			}
			Long universal = this.sourceLoader.writeDate(path.value());
			return universal == null ? LispNil.INSTANCE : new LispInteger(universal);
		}));
		// %list-directory: the one directory-LISTING primitive, mediated by the same
		// SourceLoader as probe-file so a host without a filesystem simply says nil.
		// Answers (t . names) for a readable directory -- the leading t is what tells
		// an EMPTY directory from a missing one -- and nil otherwise; everything
		// user-facing (directory, uiop:directory-exists-p / directory-files /
		// subdirectories / collect-sub*directories) is Lisp source over it, in
		// LispPreludeLibrary, so no listing rule can drift between backends.
		this.globalEnv.defineFunction(LispNames.LIST_DIRECTORY, new LispFunction(LispNames.LIST_DIRECTORY, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.LIST_DIRECTORY + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(LispNames.LIST_DIRECTORY + " expects a string pathname");
			}
			List<String> entries = this.sourceLoader.listDirectory(path.value());
			if (entries == null) {
				return LispNil.INSTANCE;
			}
			LispVal names = LispNil.INSTANCE;
			for (int i = entries.size() - 1; i >= 0; i--) {
				names = new LispCons(new LispString(entries.get(i)), names);
			}
			return new LispCons(LispTrue.INSTANCE, names);
		}));
		// uiop:add-package-local-nickname -- lite: registers a GLOBAL nickname (no
		// per-package scoping); the mechanism libraries recommend for shortening long
		// package names (jzon's README: (uiop:add-package-local-nickname '#:jzon
		// '#:com.inuoe.jzon)). The optional third argument (the package to scope the
		// nickname to) is accepted and ignored.
		String addNicknameName = PackageRegistry.qualify(LispNames.UIOP_PKG, LispNames.ADD_PACKAGE_LOCAL_NICKNAME);
		this.globalEnv.defineFunction(addNicknameName, new LispFunction(addNicknameName, args -> {
			if (args.size() < 2 || args.size() > 3) {
				throw new LispEvalException(
						LispNames.ADD_PACKAGE_LOCAL_NICKNAME + " expects a nickname and a package, got " + args.size());
			}
			String nickname = packageNameDesignator(LispNames.ADD_PACKAGE_LOCAL_NICKNAME, args.get(0));
			String actual = packageNameDesignator(LispNames.ADD_PACKAGE_LOCAL_NICKNAME, args.get(1));
			this.packageResolver.registerLocalNickname(nickname, actual);
			return new LispSymbol(actual);
		}));
		// use-package: a literal top-level call is consumed by the PackageResolver (so it
		// works on every backend); this runtime binding serves the computed calls only
		// the
		// interpreter can run -- and, resolving against the very same resolver, it takes
		// effect for the forms read after it, as it does in Common Lisp.
		this.globalEnv.defineFunction(LispNames.USE_PACKAGE, new LispFunction(LispNames.USE_PACKAGE, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.USE_PACKAGE + " expects 1 or 2 arguments, got " + args.size());
			}
			List<String> used = new ArrayList<>();
			// A designator or a LIST of designators, like CL.
			if (args.get(0) instanceof LispCons list) {
				for (LispVal element : list.toList()) {
					used.add(packageNameDesignator(LispNames.USE_PACKAGE, element));
				}
			}
			else if (!(args.get(0) instanceof LispNil)) {
				used.add(packageNameDesignator(LispNames.USE_PACKAGE, args.get(0)));
			}
			String target = args.size() == 2 ? packageNameDesignator(LispNames.USE_PACKAGE, args.get(1))
					: this.packageResolver.currentPackageName();
			this.packageResolver.usePackage(used, target);
			return LispTrue.INSTANCE;
		}));
		// export/unexport: the same split as use-package -- a literal top-level call is
		// consumed by the PackageResolver (so it works on every backend), and these
		// runtime bindings serve the computed calls only the interpreter can run,
		// resolving against the very same resolver so they take effect for the forms read
		// after them.
		for (String name : List.of(LispNames.EXPORT, LispNames.UNEXPORT)) {
			boolean export = LispNames.EXPORT.equals(name);
			this.globalEnv.defineFunction(name, new LispFunction(name, args -> {
				if (args.isEmpty() || args.size() > 2) {
					throw new LispEvalException(name + " expects 1 or 2 arguments, got " + args.size());
				}
				List<String> symbols = new ArrayList<>();
				// A symbol or a LIST of symbols, like CL.
				if (args.get(0) instanceof LispCons list) {
					for (LispVal element : list.toList()) {
						symbols.add(packageNameDesignator(name, element));
					}
				}
				else if (!(args.get(0) instanceof LispNil)) {
					symbols.add(packageNameDesignator(name, args.get(0)));
				}
				String target = args.size() == 2 ? packageNameDesignator(name, args.get(1))
						: this.packageResolver.currentPackageName();
				this.packageResolver.exportSymbols(symbols, target, export);
				return LispTrue.INSTANCE;
			}));
		}
		this.globalEnv.defineFunction(LispNames.SLOT_MAKUNBOUND, new LispFunction(LispNames.SLOT_MAKUNBOUND, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.SLOT_MAKUNBOUND + " expects 2 arguments, got " + args.size());
			}
			SlotRef slot = instanceSlotRef(args.get(0), args.get(1));
			if (slot == null) {
				throw new LispEvalException(LispNames.SLOT_MAKUNBOUND + ": no such slot " + args.get(1).print());
			}
			slot.write(unboundMarkerValue());
			return args.get(0);
		}));
		// boundp/symbol-value read the GLOBAL variable namespace only (like CL, where
		// symbol-value never sees lexical bindings); they live on the evaluator because
		// they capture the global environment. fboundp and find-symbol additionally need
		// the user macro table.
		this.globalEnv.defineFunction(LispNames.BOUNDP, new LispFunction(LispNames.BOUNDP, args -> {
			requireSingleArg(LispNames.BOUNDP, args);
			return switch (args.get(0)) {
				case LispTrue ignored -> LispTrue.INSTANCE;
				case LispNil ignored -> LispTrue.INSTANCE;
				case LispSymbol sym ->
					sym.isKeyword() || this.dynamicBindings.isBound(sym.name()) || this.globalEnv.hasBinding(sym.name())
							? LispTrue.INSTANCE : LispNil.INSTANCE;
				default ->
					throw new LispEvalException(LispNames.BOUNDP + " expects a symbol, got " + args.get(0).print());
			};
		}));
		this.globalEnv.defineFunction(LispNames.SYMBOL_VALUE, new LispFunction(LispNames.SYMBOL_VALUE, args -> {
			requireSingleArg(LispNames.SYMBOL_VALUE, args);
			return switch (args.get(0)) {
				case LispTrue t -> t;
				case LispNil nil -> nil;
				case LispSymbol sym -> {
					if (sym.isKeyword()) {
						yield sym;
					}
					if (this.dynamicBindings.isBound(sym.name())) {
						yield this.dynamicBindings.get(sym.name());
					}
					LispVal value = this.globalEnv.lookupOrNull(sym.name());
					if (value == null) {
						throw new LispEvalException("The variable " + sym.name() + " is unbound");
					}
					yield value;
				}
				default -> throw new LispEvalException(
						LispNames.SYMBOL_VALUE + " expects a symbol, got " + args.get(0).print());
			};
		}));
		// (make-synonym-stream 'sym): the stream designator the symbol currently names.
		// *standard-output* / *standard-input* answer the nil DESIGNATOR, which every
		// output / input operation resolves through them at the time of that operation
		// -- so THOSE synonyms forward per-operation like CL's. Any other symbol is
		// lite: resolved
		// HERE, not on every operation through the resulting stream (see
		// LispMacroExpander.expandMakeSynonymStream, the compiled backends' half of the
		// same contract).
		this.globalEnv.defineFunction(LispNames.MAKE_SYNONYM_STREAM,
				new LispFunction(LispNames.MAKE_SYNONYM_STREAM, args -> {
					requireSingleArg(LispNames.MAKE_SYNONYM_STREAM, args);
					if (!(args.get(0) instanceof LispSymbol sym) || sym.isKeyword()) {
						throw new LispEvalException(
								LispNames.MAKE_SYNONYM_STREAM + " expects a symbol, got " + args.get(0).print());
					}
					if (LispNames.STANDARD_OUTPUT_VAR.equals(sym.name())
							|| LispNames.STANDARD_INPUT_VAR.equals(sym.name())) {
						return LispNil.INSTANCE;
					}
					if (this.dynamicBindings.isBound(sym.name())) {
						return this.dynamicBindings.get(sym.name());
					}
					LispVal value = this.globalEnv.lookupOrNull(sym.name());
					if (value == null) {
						throw new LispEvalException("The variable " + sym.name() + " is unbound");
					}
					return value;
				}));
		// fboundp is t for anything callable or expandable: functions, user macros, and
		// the built-in macros/special forms (CL: fboundp is true of macros and special
		// operators too).
		this.globalEnv.defineFunction(LispNames.FBOUNDP, new LispFunction(LispNames.FBOUNDP, args -> {
			requireSingleArg(LispNames.FBOUNDP, args);
			if (args.get(0) instanceof LispNil) {
				// nil IS a symbol in CL and names no function -- trivia level2 probes
				// (fboundp (find-symbol ...)) whose argument is nil on a miss.
				return LispNil.INSTANCE;
			}
			if (!(args.get(0) instanceof LispSymbol sym)) {
				throw new LispEvalException(LispNames.FBOUNDP + " expects a symbol, got " + args.get(0).print());
			}
			String name = sym.name();
			boolean bound = SPECIAL_OPERATORS.contains(name) || this.userMacros.containsKey(name)
					|| this.globalEnv.lookupFunctionOrNull(name) != null || LispNames.isCarCdrComposition(name);
			return bound ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// fmakunbound: drop the global function binding AND any user macro of the same
		// name, so the name is undefined again for every later reference (fboundp, a
		// call, funcall through the symbol). Built-in macros and special operators are
		// part of the language, not of the image's function namespace, so removing them
		// is not attempted -- fboundp keeps answering t for those. The name is returned,
		// like CL. postmodern's deallocate-prepared-statement retires the functions
		// defprepared generated this way.
		this.globalEnv.defineFunction(LispNames.FMAKUNBOUND, new LispFunction(LispNames.FMAKUNBOUND, args -> {
			requireSingleArg(LispNames.FMAKUNBOUND, args);
			if (!(args.get(0) instanceof LispSymbol sym)) {
				throw new LispEvalException(LispNames.FMAKUNBOUND + " expects a symbol, got " + args.get(0).print());
			}
			this.globalEnv.undefineFunction(sym.name());
			this.userMacros.remove(sym.name());
			return sym;
		}));
		// (setf (symbol-function 'f) fn) / (setf (fdefinition 'f) fn) lower here:
		// install fn as f's global function binding -- fmakunbound's write-side twin,
		// so a same-named user macro stops shadowing the new definition -- and return
		// fn, the setf value.
		this.globalEnv.defineFunction(LispNames.SET_SYMBOL_FUNCTION_INTERNAL,
				new LispFunction(LispNames.SET_SYMBOL_FUNCTION_INTERNAL, args -> {
					if (args.size() != 2 || !(args.get(0) instanceof LispSymbol sym)) {
						throw new LispEvalException("(setf symbol-function) expects a symbol name, got "
								+ (args.isEmpty() ? "nothing" : args.get(0).print()));
					}
					this.globalEnv.defineFunction(sym.name(), args.get(1));
					this.userMacros.remove(sym.name());
					return args.get(1);
				}));
		// The compile paths' setf-only-alias forwarder body reads the binding through
		// this primitive; the interpreter serves it against the live environment so
		// one program means the same thing on every backend.
		this.globalEnv.defineFunction(LispNames.FENV_FUNCTION_INTERNAL,
				new LispFunction(LispNames.FENV_FUNCTION_INTERNAL, args -> {
					requireSingleArg(LispNames.FENV_FUNCTION_INTERNAL, args);
					if (!(args.get(0) instanceof LispSymbol sym)) {
						throw new LispEvalException(
								LispNames.FENV_FUNCTION_INTERNAL + " expects a symbol, got " + args.get(0).print());
					}
					LispVal fn = this.globalEnv.lookupFunctionOrNull(sym.name());
					if (fn == null) {
						throw new LispEvalException("The function " + sym.name() + " is undefined");
					}
					return fn;
				}));
		// find-symbol never creates: the symbol comes back only when the name is already
		// known to the image (a cl symbol, a keyword, or a user definition). The
		// compilers
		// fold a literal call against their compile-time view (cl symbols + user defuns).
		this.globalEnv.defineFunction(LispNames.FIND_SYMBOL, new LispFunction(LispNames.FIND_SYMBOL, args -> {
			if (args.size() == 2) {
				// (find-symbol name pkg): "interned in the package" is judged by the
				// package registry (owns/exports/imports) -- no intern table exists.
				// The result keeps the canonical qualified spelling, so plist/dispatch
				// lookups keyed by a resolver-canonicalized quote match (ironclad's
				// massage-symbol -> (get sym '%digest-length) chain).
				if (!(args.get(0) instanceof LispString str)) {
					throw new LispEvalException(
							LispNames.FIND_SYMBOL + " expects a string, got " + args.get(0).print());
				}
				// A package that does not exist provides no symbol: nil, not an error.
				// CL signals a package-error here, but the compile paths cannot (they
				// have no registry at run time), and probing an OPTIONAL system with
				// (find-symbol "TIMESTAMP" :simple-date) is exactly what libraries do
				// (postmodern's json-encoder) -- so all four backends answer nil.
				if (args.get(1) instanceof LispNil) {
					return LispNil.INSTANCE;
				}
				String designator = packageDesignator(LispNames.FIND_SYMBOL, args.get(1));
				String pkgName = this.packageResolver.findPackageName(designator);
				if (pkgName == null) {
					return LispNil.INSTANCE;
				}
				String spelling = this.packageResolver.memberSpelling(designator, str.value());
				if (spelling != null) {
					return new LispSymbol(spelling);
				}
				// A definition IS an interning: a defun (or a defstruct-GENERATED
				// defun/defvar) under (in-package pkg) registers only in the global
				// namespaces under its canonical spelling, never in the package
				// registry (no intern table). Probe those namespaces so
				// (find-symbol "POINT-P" pkg) finds a defstruct predicate (trivia
				// level2's predicatep).
				String candidate = LispNames.CL_USER_PKG.equals(pkgName) ? str.value()
						: PackageRegistry.qualifyInternal(pkgName, str.value());
				return definedInImage(candidate) ? new LispSymbol(candidate) : LispNil.INSTANCE;
			}
			requireSingleArg(LispNames.FIND_SYMBOL, args);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.FIND_SYMBOL + " expects a string, got " + args.get(0).print());
			}
			// intern/find-symbol take the name verbatim under the uppercase-canonical
			// model -- (find-symbol "car") is NIL, (find-symbol "CAR") names CAR.
			String name = str.value();
			boolean known = PackageRegistry.isClSymbol(name) || (!name.isEmpty() && name.charAt(0) == ':')
					|| definedInImage(name);
			if (known) {
				return new LispSymbol(name);
			}
			// The current-package half of the definition-is-an-interning probe above.
			String spelling = this.packageResolver.internSpelling(name);
			if (!spelling.equals(name) && definedInImage(spelling)) {
				return new LispSymbol(spelling);
			}
			return LispNil.INSTANCE;
		}));
		// intern overrides the package-blind Environment converter: a bare name is
		// interned into the CURRENT package (the resolver's in-package state), so a
		// macro-time (intern (concatenate ...)) under (in-package p) names the same
		// function as a literal defun in that file. The (intern name :keyword) form
		// keeps the Environment behavior.
		this.globalEnv.defineFunction(LispNames.INTERN, new LispFunction(LispNames.INTERN, args -> {
			if (args.size() == 2) {
				if (LispMacroExpander.isKeywordPackageDesignator(args.get(1))) {
					if (!(args.get(0) instanceof LispString str)) {
						throw new LispEvalException(LispNames.INTERN + " expects a string, got " + args.get(0).print());
					}
					return new LispSymbol(":" + str.value());
				}
				// A general package designator (string / symbol / find-package keyword):
				// intern into that package through the resolver, so the spelling agrees
				// with what a literal defun in that package would have produced
				// (alexandria's ensure-symbol/format-symbol).
				if (!(args.get(0) instanceof LispString str)) {
					throw new LispEvalException(LispNames.INTERN + " expects a string, got " + args.get(0).print());
				}
				String designator = packageDesignator(LispNames.INTERN, args.get(1));
				return new LispSymbol(this.packageResolver.internSpellingIn(designator, str.value()));
			}
			requireSingleArg(LispNames.INTERN, args);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.INTERN + " expects a string, got " + args.get(0).print());
			}
			return new LispSymbol(this.packageResolver.internSpelling(str.value()));
		}));
		// find-package: rontolisp has no package objects, so a "package" at runtime is
		// the UPCASED canonical package name as a keyword -- eq-comparable by name, and
		// upcased so the compile paths' spelling (which comes from reader-upcased
		// literals) agrees. Returns nil for an unknown package, like CL.
		this.globalEnv.defineFunction(LispNames.FIND_PACKAGE, new LispFunction(LispNames.FIND_PACKAGE, args -> {
			requireSingleArg(LispNames.FIND_PACKAGE, args);
			String found = this.packageResolver.findPackageName(packageDesignator(LispNames.FIND_PACKAGE, args.get(0)));
			return found == null ? LispNil.INSTANCE : packageKeyword(found);
		}));
		// symbol-package: the same keyword shape find-package yields, so the two are
		// eq-comparable (ironclad's massage-symbol pattern); nil for an uninterned (#:)
		// symbol. Overrides the backend-neutral prelude defun, which cannot tell cl
		// from cl-user without the registry.
		this.globalEnv.defineFunction(LispNames.SYMBOL_PACKAGE, new LispFunction(LispNames.SYMBOL_PACKAGE, args -> {
			requireSingleArg(LispNames.SYMBOL_PACKAGE, args);
			String name = switch (args.get(0)) {
				case LispSymbol sym -> sym.name();
				case LispTrue ignored -> "T";
				case LispNil ignored -> "NIL";
				default -> throw new LispEvalException(
						LispNames.SYMBOL_PACKAGE + " expects a symbol, got " + args.get(0).print());
			};
			String pkg = this.packageResolver.symbolPackageName(name);
			return pkg == null ? LispNil.INSTANCE : packageKeyword(pkg);
		}));
		// error / signal / warn are real CL functions (cl-base64 signals via
		// (apply #'error args)), so they get function values that rebuild the literal
		// call from the evaluated arguments and re-enter the evaluator -- identical
		// semantics to the lowered form, condition-designator protocol included. A
		// keyword, string, or other self-evaluating argument stays literal (the
		// designator matchers read them from the form); symbols and lists are quoted.
		for (String opName : List.of(LispNames.ERROR, LispNames.SIGNAL, LispNames.WARN)) {
			this.globalEnv.defineFunction(opName,
					new LispFunction(opName, args -> eval(rebuildSignalForm(opName, args), this.globalEnv)));
		}
		// format is a lowered operator in call position, but also a real CL function
		// (jzon's condition reports run (apply #'format stream control args)). The
		// control string is a RUNTIME value here, so the function value renders it with
		// the shared runtime renderer -- the very code the compiled #'format wrapper
		// calls, so a control string reaching format through a function value renders
		// identically on all four backends. The destination dispatch mirrors
		// formatDestinationDispatch: nil answers the string, t writes to the default
		// stream (so a with-output-to-string capture applies), anything else is a stream.
		this.globalEnv.defineFunction(LispNames.FORMAT, new LispFunction(LispNames.FORMAT, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.FORMAT + " expects a destination and a control string");
			}
			LispVal destination = args.get(0);
			LispVal rendered = eval(
					FormatRenderer.call(quotedValue(args.get(1)), quotedValue(valueList(args.subList(2, args.size())))),
					this.globalEnv);
			if (destination instanceof LispNil) {
				return rendered;
			}
			LispVal writeArgs = (destination instanceof LispTrue) ? LispNil.INSTANCE
					: new LispCons(quotedValue(destination), LispNil.INSTANCE);
			eval(new LispCons(new LispSymbol(LispNames.WRITE_STRING), new LispCons(quotedValue(rendered), writeArgs)),
					this.globalEnv);
			return LispNil.INSTANCE;
		}));
		// cerror (lite): no restart machinery exists, so the "continuable" part is
		// dropped -- (cerror continue-format datum args...) signals like
		// (error datum args...).
		this.globalEnv.defineFunction(LispNames.CERROR, new LispFunction(LispNames.CERROR, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.CERROR + " expects a continue format control and a datum");
			}
			return eval(rebuildSignalForm(LispNames.ERROR, args.subList(1, args.size())), this.globalEnv);
		}));
		this.globalEnv.defineFunction(LispNames.FUNCALL, new LispFunction(LispNames.FUNCALL, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.FUNCALL + " expects at least 1 argument");
			}
			return apply(args.get(0), args.subList(1, args.size()), this.globalEnv);
		}));
		// %async-run (the async-defun/async-lambda lowering primitive) lives here rather
		// than in Environment because running the body thunk needs the evaluator's
		// apply. rontolisp:await itself is a special form (evalCons), not a function.
		String asyncRunName = LispNames.ASYNC_RUN_QUALIFIED;
		this.globalEnv.defineFunction(asyncRunName, new LispFunction(asyncRunName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.ASYNC_RUN + " expects 1 argument, got " + args.size());
			}
			LispVal thunk = args.get(0);
			return AsyncRuntime.run(() -> apply(thunk, List.of(), this.globalEnv));
		}));
		// The thread primitives live here rather than in Environment because running the
		// spawned function needs the evaluator's apply (the %async-run precedent). The
		// handle is opaque (LispThread; the JVM backend hands out a marker-headed array
		// instead, and the WASM backends have none). The optional bindings alist of
		// (symbol . value) pairs is established as dynamic bindings in the NEW thread
		// only: DynamicBindings is thread-scoped, so the spawned thread inherits no
		// bindings from its spawner and its own pushes die with it -- no pop is needed.
		String makeThreadName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_THREAD);
		this.globalEnv.defineFunction(makeThreadName, new LispFunction(makeThreadName, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.MAKE_THREAD + " expects 1 or 2 arguments, got " + args.size());
			}
			LispVal fn = args.get(0);
			List<String> bindingNames = new ArrayList<>();
			List<LispVal> bindingValues = new ArrayList<>();
			if (args.size() == 2) {
				LispVal cur = args.get(1);
				while (cur instanceof LispCons cons) {
					if (!(cons.car() instanceof LispCons pair) || !(pair.car() instanceof LispSymbol sym)) {
						throw new LispEvalException(LispNames.MAKE_THREAD
								+ " expects an alist of (symbol . value) bindings, got " + args.get(1).print());
					}
					bindingNames.add(sym.name());
					bindingValues.add(pair.cdr());
					cur = cons.cdr();
				}
				if (!(cur instanceof LispNil)) {
					throw new LispEvalException(LispNames.MAKE_THREAD
							+ " expects an alist of (symbol . value) bindings, got " + args.get(1).print());
				}
			}
			if (!bindingNames.isEmpty()) {
				// Like progv, a bound name need not be proclaimed special, so reads must
				// consult the dynamic store even when specialVars is empty.
				this.progvUsed = true;
			}
			return AsyncRuntime.spawnThread(() -> {
				for (int i = 0; i < bindingNames.size(); i++) {
					this.dynamicBindings.push(bindingNames.get(i), bindingValues.get(i));
				}
				return apply(fn, List.of(), this.globalEnv);
			});
		}));
		String joinThreadName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.JOIN_THREAD);
		this.globalEnv.defineFunction(joinThreadName, new LispFunction(joinThreadName, args -> {
			LispThread thread = requireThread(LispNames.JOIN_THREAD, args);
			try {
				LispVal value = thread.result().join();
				// Also wait for the thread itself to die, so thread-alive-p answers nil
				// deterministically after a join (the result settles inside the body,
				// a beat before the thread's teardown).
				thread.thread().join();
				return value;
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new LispEvalException(LispNames.JOIN_THREAD + ": interrupted while joining the thread");
			}
			catch (java.util.concurrent.CompletionException ex) {
				// Re-signal what the thread died on: the original condition-carrying
				// runtime exception, so handler-case in the joiner dispatches by type.
				if (ex.getCause() instanceof RuntimeException cause) {
					throw cause;
				}
				throw new LispEvalException(LispNames.JOIN_THREAD + ": the thread died: " + ex.getCause());
			}
		}));
		String threadpName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.THREADP);
		this.globalEnv.defineFunction(threadpName, new LispFunction(threadpName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.THREADP + " expects 1 argument, got " + args.size());
			}
			return args.get(0) instanceof LispThread ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		String threadAlivePName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.THREAD_ALIVE_P);
		this.globalEnv.defineFunction(threadAlivePName, new LispFunction(threadAlivePName, args -> {
			LispThread thread = requireThread(LispNames.THREAD_ALIVE_P, args);
			return thread.thread().isAlive() ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		String destroyThreadName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.DESTROY_THREAD);
		this.globalEnv.defineFunction(destroyThreadName, new LispFunction(destroyThreadName, args -> {
			LispThread thread = requireThread(LispNames.DESTROY_THREAD, args);
			thread.thread().interrupt();
			return thread;
		}));
		// http-handler lives here rather than in Environment because serving a request
		// applies the handler function, which needs the evaluator's apply. It runs a
		// blocking embedded HTTP server; the handler receives a request property list
		// (:method / :path / :query / :headers / :body) and returns a response property
		// list
		// (:status / :headers / :body). When compiled with --component the same directive
		// instead exports wasi:http/incoming-handler (see the WASM compiler).
		String httpHandlerName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.HTTP_HANDLER);
		this.globalEnv.defineFunction(httpHandlerName, new LispFunction(httpHandlerName, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.HTTP_HANDLER + " expects 1 or 2 arguments, got " + args.size());
			}
			int port = 8080;
			if (args.size() == 2) {
				if (!(args.get(1) instanceof LispInteger portArg)) {
					throw new LispEvalException(
							LispNames.HTTP_HANDLER + " expects an integer port, got: " + args.get(1).print());
				}
				port = (int) portArg.value();
			}
			final LispVal handler = args.get(0);
			HttpHandlerSupport.serve(port, request -> invokeHttpHandler(handler, request));
			return LispNil.INSTANCE; // serve() blocks forever; unreachable in practice
		}));
		// The stoppable HTTP server seam behind the clack-handler-rontolisp shim
		// (internal rontolisp::%http-server-*): start takes a FUNCTION VALUE (unlike
		// the directive's quoted name), binds address:port and returns an opaque
		// handle; join blocks until stop (or the acceptor thread's interrupt -- the
		// clack :use-thread t stop path); stop is idempotent. Registered here like
		// http-handler because serving applies the handler via the evaluator.
		String httpServerStartName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.HTTP_SERVER_START);
		this.globalEnv.defineFunction(httpServerStartName, new LispFunction(httpServerStartName, args -> {
			if (args.size() != 3) {
				throw new LispEvalException(
						LispNames.HTTP_SERVER_START + " expects (handler port address), got " + args.size());
			}
			final LispVal handler = args.get(0);
			if (!(args.get(1) instanceof LispInteger portArg)) {
				throw new LispEvalException(
						LispNames.HTTP_SERVER_START + " expects an integer port, got: " + args.get(1).print());
			}
			String address = switch (args.get(2)) {
				case LispString str -> str.value();
				case LispNil ignored -> null;
				default -> throw new LispEvalException(LispNames.HTTP_SERVER_START
						+ " expects a string (or nil) address, got: " + args.get(2).print());
			};
			long handle = HttpHandlerSupport.startServer((int) portArg.value(), address,
					request -> invokeHttpHandler(handler, request));
			return new LispInteger(handle);
		}));
		String httpServerJoinName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.HTTP_SERVER_JOIN);
		this.globalEnv.defineFunction(httpServerJoinName, new LispFunction(httpServerJoinName, args -> {
			HttpHandlerSupport.joinServer(requireHttpServerHandle(LispNames.HTTP_SERVER_JOIN, args));
			return LispNil.INSTANCE;
		}));
		String httpServerStopName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.HTTP_SERVER_STOP);
		this.globalEnv.defineFunction(httpServerStopName, new LispFunction(httpServerStopName, args -> {
			HttpHandlerSupport.stopServer(requireHttpServerHandle(LispNames.HTTP_SERVER_STOP, args));
			return LispNil.INSTANCE;
		}));
		String httpServerPortName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.HTTP_SERVER_PORT);
		this.globalEnv.defineFunction(httpServerPortName, new LispFunction(httpServerPortName, args -> {
			return new LispInteger(
					HttpHandlerSupport.serverPort(requireHttpServerHandle(LispNames.HTTP_SERVER_PORT, args)));
		}));
		// The JSON functions live here because they dispatch to the Lisp-source
		// library (JsonLibrary), evaluated into the global environment on first use.
		String jsonParseName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.JSON_PARSE);
		this.globalEnv.defineFunction(jsonParseName, new LispFunction(jsonParseName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.JSON_PARSE + " expects 1 argument, got " + args.size());
			}
			return applyJsonHelper(JsonLibrary.HELPER_PARSE, List.of(args.get(0)));
		}));
		String jsonStringifyName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.JSON_STRINGIFY);
		this.globalEnv.defineFunction(jsonStringifyName, new LispFunction(jsonStringifyName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.JSON_STRINGIFY + " expects 1 argument, got " + args.size());
			}
			return applyJsonHelper(JsonLibrary.HELPER_STRINGIFY, List.of(args.get(0)));
		}));
		this.globalEnv.defineFunction(LispNames.MAPCAR, new LispFunction(LispNames.MAPCAR,
				args -> mapValues(args.get(0), requireMapLists(LispNames.MAPCAR, args), false)));
		this.globalEnv.defineFunction(LispNames.MAPC, new LispFunction(LispNames.MAPC,
				args -> mapForEffect(args.get(0), requireMapLists(LispNames.MAPC, args), false)));
		// maplist/mapcon/mapl are macro-expanded in call position (evalCons), but a
		// first-class #'maplist has to resolve to something: without these the value path
		// answered "The function MAPLIST is undefined" while both compile backends
		// happily wrapped it.
		this.globalEnv.defineFunction(LispNames.MAPLIST, new LispFunction(LispNames.MAPLIST,
				args -> mapValues(args.get(0), requireMapLists(LispNames.MAPLIST, args), true)));
		this.globalEnv.defineFunction(LispNames.MAPCON, new LispFunction(LispNames.MAPCON,
				args -> mapcanValues(args.get(0), requireMapLists(LispNames.MAPCON, args), true)));
		this.globalEnv.defineFunction(LispNames.MAPL, new LispFunction(LispNames.MAPL,
				args -> mapForEffect(args.get(0), requireMapLists(LispNames.MAPL, args), true)));
		this.globalEnv.defineFunction(LispNames.MAPHASH, new LispFunction(LispNames.MAPHASH, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.MAPHASH + " expects 2 arguments, got " + args.size());
			}
			if (!(args.get(1) instanceof LispHashTable table)) {
				throw new LispEvalException(LispNames.MAPHASH + " expects a hash table, got " + args.get(1).print());
			}
			for (LispHashTable.Entry entry : new ArrayList<>(table.entries())) {
				apply(args.get(0), List.of(entry.key(), entry.value()), this.globalEnv);
			}
			return LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.REDUCE, new LispFunction(LispNames.REDUCE, args -> {
			// (reduce fn seq) or (reduce fn seq :initial-value init); a string sequence
			// folds over a list of its characters (Environment.seqAsList).
			if (args.size() == 2) {
				LispVal list = Environment.seqAsList(args.get(1));
				if (!(list instanceof LispCons first)) {
					throw new LispEvalException(
							"reduce requires a non-empty sequence when no initial value is provided");
				}
				return reduceValues(args.get(0), first.car(), first.cdr());
			}
			if (args.size() == 4 && args.get(2) instanceof LispSymbol kw
					&& LispNames.INITIAL_VALUE_KEYWORD.equals(kw.name())) {
				return reduceValues(args.get(0), args.get(3), Environment.seqAsList(args.get(1)));
			}
			throw new LispEvalException(
					LispNames.REDUCE + " expects (reduce fn list) or (reduce fn list :initial-value init)");
		}));
		this.globalEnv.defineFunction(LispNames.EVERY, new LispFunction(LispNames.EVERY,
				args -> everyValues(args.get(0), predicateSequences(LispNames.EVERY, args))));
		this.globalEnv.defineFunction(LispNames.SOME, new LispFunction(LispNames.SOME,
				args -> someValues(args.get(0), predicateSequences(LispNames.SOME, args))));
		// The find family IS the position family's scan with the matching ELEMENT as
		// the answer, so both share one runtime and take the same keyword set.
		this.globalEnv.defineFunction(LispNames.FIND, new LispFunction(LispNames.FIND,
				args -> positionScanValues(LispNames.FIND, args, PositionScanMode.ITEM, true)));
		this.globalEnv.defineFunction(LispNames.FIND_IF, new LispFunction(LispNames.FIND_IF,
				args -> positionScanValues(LispNames.FIND_IF, args, PositionScanMode.PREDICATE, true)));
		this.globalEnv.defineFunction(LispNames.FIND_IF_NOT, new LispFunction(LispNames.FIND_IF_NOT,
				args -> positionScanValues(LispNames.FIND_IF_NOT, args, PositionScanMode.PREDICATE_NOT, true)));
		// The position family is registered here (not in Environment) so the
		// :test/:test-not/:key designators can be applied through the evaluator; the
		// full keyword set (:start/:end/:from-end too) is parsed at runtime so
		// first-class use through apply works (e.g. cl-utilities' split-sequence does
		// (apply #'position delimiter seq :end right other-keys)). The call position
		// routes through the shared macro expansion instead.
		this.globalEnv.defineFunction(LispNames.POSITION, new LispFunction(LispNames.POSITION,
				args -> positionScanValues(LispNames.POSITION, args, PositionScanMode.ITEM, false)));
		this.globalEnv.defineFunction(LispNames.POSITION_IF, new LispFunction(LispNames.POSITION_IF,
				args -> positionScanValues(LispNames.POSITION_IF, args, PositionScanMode.PREDICATE, false)));
		this.globalEnv.defineFunction(LispNames.POSITION_IF_NOT, new LispFunction(LispNames.POSITION_IF_NOT,
				args -> positionScanValues(LispNames.POSITION_IF_NOT, args, PositionScanMode.PREDICATE_NOT, false)));
		this.globalEnv.defineFunction(LispNames.COUNT_IF, new LispFunction(LispNames.COUNT_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.COUNT_IF + " expects 2 arguments, got " + args.size());
			}
			return countIfValues(args.get(0), Environment.seqAsList(args.get(1)));
		}));
		this.globalEnv.defineFunction(LispNames.MEMBER_IF, new LispFunction(LispNames.MEMBER_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.MEMBER_IF + " expects 2 arguments, got " + args.size());
			}
			return memberIfValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.MEMBER, new LispFunction(LispNames.MEMBER, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.MEMBER + " expects at least 2 arguments, got " + args.size());
			}
			// (member item list) compares with eql; (member item list :test fn) applies
			// fn
			// as (funcall fn item element), and :key applies a selector to each element
			// before the test. The default eql designator keeps the historic behavior of
			// the eql-based scan.
			requireTestKeyKeywords(LispNames.MEMBER, args, 2);
			LispVal test = keywordArg(args, 2, LispNames.TEST_KEYWORD, new LispSymbol(LispNames.EQL));
			LispVal keyFn = optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD);
			LispVal item = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				LispVal elem = (keyFn == null) ? cell.car() : apply(keyFn, List.of(cell.car()), this.globalEnv);
				if (isTruthy(apply(test, List.of(item, elem), this.globalEnv))) {
					return cell;
				}
				cur = cell.cdr();
			}
			return LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.ASSOC_IF, new LispFunction(LispNames.ASSOC_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.ASSOC_IF + " expects 2 arguments, got " + args.size());
			}
			return assocIfValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.RASSOC_IF, new LispFunction(LispNames.RASSOC_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.RASSOC_IF + " expects 2 arguments, got " + args.size());
			}
			return rassocIfValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.ASSOC, new LispFunction(LispNames.ASSOC, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.ASSOC + " expects at least 2 arguments, got " + args.size());
			}
			// (assoc key alist) compares with eql; (assoc key alist :test fn) applies fn
			// as (funcall fn key (car pair)), and :key applies a selector to each pair's
			// car before the test, mirroring member.
			requireTestKeyKeywords(LispNames.ASSOC, args, 2);
			LispVal test = keywordArg(args, 2, LispNames.TEST_KEYWORD, new LispSymbol(LispNames.EQL));
			LispVal keyFn = optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD);
			LispVal key = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (cell.car() instanceof LispCons pair) {
					LispVal elem = (keyFn == null) ? pair.car() : apply(keyFn, List.of(pair.car()), this.globalEnv);
					if (isTruthy(apply(test, List.of(key, elem), this.globalEnv))) {
						return pair;
					}
				}
				cur = cell.cdr();
			}
			return LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.RASSOC, new LispFunction(LispNames.RASSOC, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.RASSOC + " expects at least 2 arguments, got " + args.size());
			}
			// The mirror of assoc: matches each pair's cdr instead of its car.
			requireTestKeyKeywords(LispNames.RASSOC, args, 2);
			LispVal test = keywordArg(args, 2, LispNames.TEST_KEYWORD, new LispSymbol(LispNames.EQL));
			LispVal keyFn = optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD);
			LispVal value = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (cell.car() instanceof LispCons pair) {
					LispVal elem = (keyFn == null) ? pair.cdr() : apply(keyFn, List.of(pair.cdr()), this.globalEnv);
					if (isTruthy(apply(test, List.of(value, elem), this.globalEnv))) {
						return pair;
					}
				}
				cur = cell.cdr();
			}
			return LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.REMOVE_IF, new LispFunction(LispNames.REMOVE_IF, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.REMOVE_IF + " expects 2 arguments, got " + args.size());
			}
			requireKeyKeyword(LispNames.REMOVE_IF, args, 2);
			return Environment.seqResult(args.get(1), removeIfValues(args.get(0), Environment.seqAsList(args.get(1)),
					false, optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD)));
		}));
		this.globalEnv.defineFunction(LispNames.REMOVE_IF_NOT, new LispFunction(LispNames.REMOVE_IF_NOT, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.REMOVE_IF_NOT + " expects 2 arguments, got " + args.size());
			}
			requireKeyKeyword(LispNames.REMOVE_IF_NOT, args, 2);
			return Environment.seqResult(args.get(1), removeIfValues(args.get(0), Environment.seqAsList(args.get(1)),
					true, optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD)));
		}));
		// substitute-if/-if-not and their destructive n- twins: like substitute, but the
		// element is selected by a predicate instead of an eql comparison. Registered
		// here
		// rather than in Environment because they call back into apply (the :key selector
		// and the predicate itself).
		this.globalEnv.defineFunction(LispNames.SUBSTITUTE_IF,
				new LispFunction(LispNames.SUBSTITUTE_IF, args -> substituteIfValues(LispNames.SUBSTITUTE_IF, args)));
		this.globalEnv.defineFunction(LispNames.SUBSTITUTE_IF_NOT, new LispFunction(LispNames.SUBSTITUTE_IF_NOT,
				args -> substituteIfValues(LispNames.SUBSTITUTE_IF_NOT, args)));
		this.globalEnv.defineFunction(LispNames.NSUBSTITUTE_IF, new LispFunction(LispNames.NSUBSTITUTE_IF,
				args -> nsubstituteIfValues(LispNames.NSUBSTITUTE_IF, args)));
		this.globalEnv.defineFunction(LispNames.NSUBSTITUTE_IF_NOT, new LispFunction(LispNames.NSUBSTITUTE_IF_NOT,
				args -> nsubstituteIfValues(LispNames.NSUBSTITUTE_IF_NOT, args)));
		// delete-if/delete-if-not are the destructive variants of
		// remove-if/remove-if-not:
		// splice out matching cells in place (Common Lisp semantics; use the return
		// value).
		this.globalEnv.defineFunction(LispNames.DELETE_IF, new LispFunction(LispNames.DELETE_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.DELETE_IF + " expects 2 arguments, got " + args.size());
			}
			return deleteIfValues(args.get(0), args.get(1), true);
		}));
		this.globalEnv.defineFunction(LispNames.DELETE_IF_NOT, new LispFunction(LispNames.DELETE_IF_NOT, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.DELETE_IF_NOT + " expects 2 arguments, got " + args.size());
			}
			return deleteIfValues(args.get(0), args.get(1), false);
		}));
		this.globalEnv.defineFunction(LispNames.MAPCAN, new LispFunction(LispNames.MAPCAN,
				args -> mapcanValues(args.get(0), requireMapLists(LispNames.MAPCAN, args), false)));
		this.globalEnv.defineFunction(LispNames.SORT, new LispFunction(LispNames.SORT, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.SORT + " expects 2 arguments, got " + args.size());
			}
			// A string sequence sorts as a list of its characters and is rebuilt as a
			// string (Common Lisp sequences).
			return Environment.seqResult(args.get(0), sortValues(Environment.seqAsList(args.get(0)), args.get(1)));
		}));
		// stable-sort is registered here (not in Environment) so the predicate and :key
		// designators can be applied through the evaluator, like member/assoc. A Java
		// list sort is stable; the result is always a fresh list, matching the shared
		// macro expansion the call position routes through.
		this.globalEnv.defineFunction(LispNames.STABLE_SORT, new LispFunction(LispNames.STABLE_SORT, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(
						LispNames.STABLE_SORT + " expects at least 2 arguments, got " + args.size());
			}
			LispVal keyFn = null;
			for (int i = 2; i < args.size(); i += 2) {
				if (!(args.get(i) instanceof LispSymbol kw) || !LispNames.KEY_KEYWORD.equals(kw.name())
						|| i + 1 >= args.size()) {
					throw new LispEvalException(
							LispNames.STABLE_SORT + " expects keyword arguments :key, got: " + args.get(i).print());
				}
				keyFn = args.get(i + 1) instanceof LispNil ? null : args.get(i + 1);
			}
			LispVal pred = args.get(1);
			List<LispVal[]> decorated = new java.util.ArrayList<>();
			LispVal cur = Environment.seqAsList(args.get(0));
			while (cur instanceof LispCons cell) {
				LispVal keyVal = (keyFn == null) ? cell.car() : apply(keyFn, List.of(cell.car()), this.globalEnv);
				decorated.add(new LispVal[] { keyVal, cell.car() });
				cur = cell.cdr();
			}
			decorated.sort((x, y) -> {
				if (isTruthy(apply(pred, List.of(x[0], y[0]), this.globalEnv))) {
					return -1;
				}
				return isTruthy(apply(pred, List.of(y[0], x[0]), this.globalEnv)) ? 1 : 0;
			});
			LispVal result = LispNil.INSTANCE;
			for (int i = decorated.size() - 1; i >= 0; i--) {
				result = new LispCons(decorated.get(i)[1], result);
			}
			return result;
		}));
		this.globalEnv.defineFunction(LispNames.APPLY, new LispFunction(LispNames.APPLY, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.APPLY + " expects at least 2 arguments, got " + args.size());
			}
			return applyValues(args);
		}));
		this.globalEnv.defineFunction(LispNames.LOAD, new LispFunction(LispNames.LOAD, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.LOAD + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(LispNames.LOAD + " expects a string argument");
			}
			loadFile(LispNames.LOAD, path.value());
			return LispTrue.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.PROVIDE, new LispFunction(LispNames.PROVIDE, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.PROVIDE + " expects 1 argument, got " + args.size());
			}
			String name = moduleDesignator(LispNames.PROVIDE, args.get(0));
			// A duplicate provide is a no-op, like Common Lisp.
			this.providedModules.add(name);
			return new LispSymbol(name);
		}));
		this.globalEnv.defineFunction(LispNames.REQUIRE, new LispFunction(LispNames.REQUIRE, args -> {
			if (args.size() != 1 && args.size() != 2) {
				throw new LispEvalException(LispNames.REQUIRE + " expects 1 or 2 arguments, got " + args.size());
			}
			String name = moduleDesignator(LispNames.REQUIRE, args.get(0));
			if (!this.providedModules.contains(name)) {
				String path;
				if (args.size() == 2) {
					if (!(args.get(1) instanceof LispString str)) {
						throw new LispEvalException(
								LispNames.REQUIRE + " expects a string file path, got " + args.get(1).print());
					}
					path = str.value();
				}
				else {
					// Downcased like ASDF's coerce-name (see LoadInliner).
					path = name.toLowerCase(java.util.Locale.ROOT) + ".lisp";
				}
				// The required file is expected to (provide name) itself, which marks
				// the module; requiring loads the file either way (like Common Lisp).
				loadFile(LispNames.REQUIRE, path);
			}
			return new LispSymbol(name);
		}));
		// asdf:load-system lives here, next to load/require, because it drives the same
		// loadFile machinery and the per-evaluator system registry. Unlike the compile
		// path (LoadInliner), the runtime function accepts a computed system name.
		String loadSystemName = PackageRegistry.qualify(LispNames.ASDF_PKG, LispNames.LOAD_SYSTEM);
		this.globalEnv.defineFunction(loadSystemName, new LispFunction(loadSystemName, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.ASDF_LOAD_SYSTEM + " expects 1 argument, got " + args.size());
			}
			// Keyword options are accepted and ignored, like the compile path's --
			// a library that loads a system at run time spells the call that way
			// (lack's find-package-or-load passes :verbose nil).
			ignoreLoadOptions(LispNames.ASDF_LOAD_SYSTEM, args.subList(1, args.size()));
			String name = AsdfSystems.designator(LispNames.ASDF_LOAD_SYSTEM, args.get(0));
			loadSystem(name);
			return new LispSymbol(name);
		}));
		// asdf:find-system + asdf:system-source-directory: the runtime companions of
		// asdf:defsystem/asdf:load-system. A library reads its bundled data files at
		// load time via
		// (asdf:system-source-directory (asdf:find-system 'lib nil)) -- the uax-15
		// precomputed-tables.lisp seed case. Both consult the per-evaluator asdfSystems
		// registry, which loadSystem populates before invoking a system's component
		// files, so find-system is guaranteed to hit for the system currently loading.
		String findSystemName = PackageRegistry.qualify(LispNames.ASDF_PKG, LispNames.FIND_SYSTEM);
		this.globalEnv.defineFunction(findSystemName, new LispFunction(findSystemName, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(
						LispNames.ASDF_FIND_SYSTEM + " expects (name [error-p]), got " + args.size() + " arguments");
			}
			String name = AsdfSystems.designator(LispNames.ASDF_FIND_SYSTEM, args.get(0));
			boolean errorP = args.size() < 2 || !(args.get(1) instanceof LispNil);
			AsdfSystems.LispSystem system = this.asdfSystems.get(name);
			if (system == null) {
				// A built-in system (a shim) is findable even before it is loaded:
				// lack's find-package-or-load probes (asdf:find-system name nil) and
				// loads on a hit -- the route by which (clackup app :server :rontolisp)
				// pulls in the clack-handler-rontolisp backend at run time.
				if (BuiltinSystems.isBuiltin(name)) {
					return new LispString(name);
				}
				if (errorP) {
					throw new LispEvalException(LispNames.ASDF_FIND_SYSTEM + ": system not registered: " + name);
				}
				return LispNil.INSTANCE;
			}
			// The "system object" is materialized as its downcase-canonical name (a
			// string): system-source-directory accepts it back verbatim.
			return new LispString(name);
		}));
		// asdf:component-pathname is the same lookup under the name a library actually
		// calls: the only component object rontolisp materializes IS a system.
		for (String member : List.of(LispNames.SYSTEM_SOURCE_DIRECTORY, LispNames.COMPONENT_PATHNAME)) {
			String qualified = PackageRegistry.qualify(LispNames.ASDF_PKG, member);
			this.globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
				if (args.size() != 1) {
					throw new LispEvalException(qualified + " expects 1 argument, got " + args.size());
				}
				String name = AsdfSystems.designator(qualified, args.get(0));
				AsdfSystems.LispSystem system = this.asdfSystems.get(name);
				if (system == null) {
					throw new LispEvalException(qualified + ": system not registered: " + name);
				}
				String base = system.baseDir();
				if (base == null || base.isEmpty()) {
					return new LispString("./");
				}
				return new LispString(base.endsWith("/") ? base : base + "/");
			}));
		}
		// asdf:system-relative-pathname: the one-call form of
		// (merge-pathnames* relative (system-source-directory system)). A library that
		// bundles a data file next to its .asd names it this way -- quri's etld.lisp
		// reads its 152 KB effective-TLD list through it.
		String systemRelativePathnameName = PackageRegistry.qualify(LispNames.ASDF_PKG,
				LispNames.SYSTEM_RELATIVE_PATHNAME);
		this.globalEnv.defineFunction(systemRelativePathnameName, new LispFunction(systemRelativePathnameName, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.ASDF_SYSTEM_RELATIVE_PATHNAME + " expects (system relative), got "
						+ args.size() + " arguments");
			}
			String name = AsdfSystems.designator(LispNames.ASDF_SYSTEM_RELATIVE_PATHNAME, args.get(0));
			AsdfSystems.LispSystem system = this.asdfSystems.get(name);
			if (system == null) {
				throw new LispEvalException(
						LispNames.ASDF_SYSTEM_RELATIVE_PATHNAME + ": system not registered: " + name);
			}
			if (!(args.get(1) instanceof LispString relative)) {
				throw new LispEvalException(LispNames.ASDF_SYSTEM_RELATIVE_PATHNAME
						+ " expects a namestring as its second argument, got " + args.get(1).print());
			}
			String base = system.baseDir();
			if (base == null || base.isEmpty()) {
				base = "./";
			}
			return new LispString(PathnameOps.mergePathnames(relative.value(), base.endsWith("/") ? base : base + "/"));
		}));
		// uiop:merge-pathnames* -- the safer defaults-aware merge, portable across
		// ASDF-loaded libraries. See PathnameOps for the string-level semantics.
		String mergePathnamesStarName = PackageRegistry.qualify(LispNames.UIOP_PKG, LispNames.MERGE_PATHNAMES_STAR);
		this.globalEnv.defineFunction(mergePathnamesStarName, new LispFunction(mergePathnamesStarName, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.UIOP_MERGE_PATHNAMES_STAR
						+ " expects (specified [defaults]), got " + args.size() + " arguments");
			}
			String specified = PathnameOps.namestring(LispNames.UIOP_MERGE_PATHNAMES_STAR, args.get(0));
			String defaults = args.size() > 1 ? PathnameOps.namestring(LispNames.UIOP_MERGE_PATHNAMES_STAR, args.get(1))
					: "";
			return new LispString(PathnameOps.mergePathnames(specified, defaults));
		}));
		// uiop:file-exists-p == probe-file (same contract: the truename on success, nil
		// otherwise). Kept identical to the compile paths' lowering in
		// LispMacroExpander.expandUiopStubCall.
		String fileExistsPName = PackageRegistry.qualify(LispNames.UIOP_PKG, LispNames.FILE_EXISTS_P);
		LispVal probeFile = this.globalEnv.lookupFunction(LispNames.PROBE_FILE);
		this.globalEnv.defineFunction(fileExistsPName, new LispFunction(fileExistsPName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(fileExistsPName + " expects 1 argument, got " + args.size());
			}
			return apply(probeFile, args, this.globalEnv);
		}));
		// uiop::get-pathname-defaults (internal in real UIOP too) -- the pathname
		// relative names resolve against. Every backend resolves a relative path
		// against the host's working directory, and "" is the namestring designating
		// exactly that, so (merge-pathnames X (get-pathname-defaults)) yields X.
		// Kept identical to the compile paths' lowering in
		// LispMacroExpander.expandUiopStubCall.
		String pathnameDefaultsName = PackageRegistry.qualifyInternal(LispNames.UIOP_PKG,
				LispNames.GET_PATHNAME_DEFAULTS);
		this.globalEnv.defineFunction(pathnameDefaultsName, new LispFunction(pathnameDefaultsName, args -> {
			if (!args.isEmpty()) {
				throw new LispEvalException(
						pathnameDefaultsName + " expects no arguments, got " + args.size() + " arguments");
			}
			return new LispString("");
		}));
		// uiop:symbol-call -- real UIOP's late-binding call: look NAME up in PACKAGE at
		// run time and apply it to the remaining arguments. The interpreter can do this
		// for real (the resolver knows every package's members and the global function
		// table is live); the compile backends have no runtime name-to-function table,
		// so there the call lowers to the generic uiop call-time error instead. lack's
		// find-package-or-load reaches it only on the quicklisp branch, which rontolisp
		// never takes (:quicklisp is not in *features*, so the asdf branch runs).
		String symbolCallName = PackageRegistry.qualify(LispNames.UIOP_PKG, LispNames.SYMBOL_CALL);
		this.globalEnv.defineFunction(symbolCallName, new LispFunction(symbolCallName, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.UIOP_SYMBOL_CALL + " expects (package name &rest args), got "
						+ args.size() + " arguments");
			}
			String designator = packageDesignator(LispNames.UIOP_SYMBOL_CALL, args.get(0));
			String member = packageDesignator(LispNames.UIOP_SYMBOL_CALL, args.get(1));
			if (this.packageResolver.findPackageName(designator) == null) {
				throw new LispEvalException(LispNames.UIOP_SYMBOL_CALL + ": package " + designator + " does not exist");
			}
			// find-symbol* semantics: an absent name is an error, not nil -- the caller
			// is about to apply it.
			String spelling = this.packageResolver.memberSpelling(designator, member);
			if (spelling == null) {
				throw new LispEvalException(
						LispNames.UIOP_SYMBOL_CALL + ": symbol " + member + " is not present in package " + designator);
			}
			return apply(resolveFunction(spelling), args.subList(2, args.size()), this.globalEnv);
		}));
		// ql:quickload = auto-download (real Quicklisp dist) + asdf:load-system. It
		// accepts a single system name or a list of names, downloads each (with its
		// dependencies) into the cache, adds the extracted .asd directories to the search
		// path, and then loads through the same asdf machinery. Returns the list of
		// loaded
		// system names, like real quickload.
		String quickloadName = PackageRegistry.qualify(LispNames.QL_PKG, LispNames.QUICKLOAD);
		this.globalEnv.defineFunction(quickloadName, new LispFunction(quickloadName, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.QL_QUICKLOAD + " expects 1 argument, got " + args.size());
			}
			// (ql:quickload "x" :silent t) -- the options are ignored, see load-system.
			ignoreLoadOptions(LispNames.QL_QUICKLOAD, args.subList(1, args.size()));
			List<LispVal> designators = args.get(0) instanceof LispCons list && list.isProperList() ? list.toList()
					: List.of(args.get(0));
			List<LispVal> loaded = new java.util.ArrayList<>();
			for (LispVal designator : designators) {
				String name = AsdfSystems.designator(LispNames.QL_QUICKLOAD, designator);
				quickload(name);
				loaded.add(new LispSymbol(name));
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = loaded.size() - 1; i >= 0; i--) {
				result = new LispCons(loaded.get(i), result);
			}
			return result;
		}));
		registerJava();
	}

	/**
	 * Reads and evaluates every top-level form of the given file in the global
	 * environment, the shared machinery behind {@code load} and {@code require}. A
	 * relative path resolves against the directory of the file doing the load (the top of
	 * {@code loadDirStack}), so a program run from any working directory can
	 * {@code (load "sibling.lisp")} its companions, matching the compile-time include.
	 * Forms route through the top-level entry so package directives in the loaded file
	 * are processed; the loaded file's directory is pushed so a nested load resolves
	 * relative to it.
	 */
	private void loadFile(String operator, String rawPath) {
		loadFile(operator, rawPath, null);
	}

	/**
	 * Loads one file. When it is a COMPONENT of an ASDF system, that system's name is
	 * given too, so {@link ShimLibraries#rewriteComponentSource} can rewrite forms of the
	 * real source (uax-15's table building); the base directory the component and the
	 * bundled data files resolve against is the system's, already on the load-dir stack.
	 */
	private void loadFile(String operator, String rawPath, @Nullable String systemName) {
		loadFile(operator, rawPath, systemName, Features.INTERPRETER);
	}

	/**
	 * Loads one file with an explicit feature set. Only an ASDF component passes anything
	 * but {@link Features#INTERPRETER}: a system that declares
	 * {@code :rontolisp-features} has its own components read with the interpreter's
	 * features WIDENED by that declaration (see {@code AsdfSystems.LispSystem#features}).
	 */
	private void loadFile(String operator, String rawPath, @Nullable String systemName, Features features) {
		String baseDir = this.loadDirStack.peekLast();
		String resolved = SourceLoader.resolve(baseDir, rawPath);
		String source;
		try {
			source = this.sourceLoader.load(resolved);
		}
		catch (IOException ex) {
			throw new LispEvalException(operator + ": cannot read file " + resolved + ": " + ex.getMessage());
		}
		if (systemName != null) {
			source = ShimLibraries.rewriteComponentSource(systemName, rawPath, source, baseDir, this.sourceLoader);
		}
		String childDir = SourceLoader.parentDir(resolved);
		this.loadDirStack.addLast(childDir == null ? "" : childDir);
		// Bind the current package around the loaded file so an internal (in-package ...)
		// is scoped to the load and does not leak to the caller, like Common Lisp binding
		// *package* for the duration of load.
		this.packageResolver.pushPackage();
		// *load-pathname* / *load-truename* for the duration of the file, so a library
		// that locates a data directory relative to its own source finds it. Bound
		// dynamically (not assigned) so a nested load restores the outer file's values.
		this.specialVars.add(LispNames.LOAD_PATHNAME_VAR);
		this.specialVars.add(LispNames.LOAD_TRUENAME_VAR);
		this.dynamicBindings.push(LispNames.LOAD_PATHNAME_VAR, new LispString(rawPath));
		this.dynamicBindings.push(LispNames.LOAD_TRUENAME_VAR, new LispString(resolved));
		try {
			// Only a file that textually contains #. pays for the marker read + the
			// per-form substitution walk; every other file keeps the plain read.
			if (source.contains("#.")) {
				for (LispVal form : LispReader.readAllWithReadEvalMarkers(source, features)) {
					eval(resolveReadTimeEval(form));
				}
			}
			else {
				for (LispVal form : LispReader.readAllFromString(source, features)) {
					eval(form);
				}
			}
		}
		finally {
			this.dynamicBindings.pop(LispNames.LOAD_TRUENAME_VAR);
			this.dynamicBindings.pop(LispNames.LOAD_PATHNAME_VAR);
			this.packageResolver.popPackage();
			this.loadDirStack.removeLast();
		}
	}

	/**
	 * Replaces every {@code (%read-eval datum)} marker in a form with the value of
	 * evaluating the datum in the global environment -- the {@code #.} read-time-eval
	 * semantics. The value is substituted raw (not quoted), matching CL: a
	 * self-evaluating value (number, character, string, array) is a literal in any
	 * context, while a symbol/list value placed in code position is evaluated as code,
	 * exactly as a real read-time substitution would behave. Called on each top-level
	 * form just before it is evaluated, so a marker sees all preceding definitions of the
	 * same file.
	 */
	/**
	 * Whether {@code sub} names a subtype of {@code super} -- delegates to the shared
	 * lattice + class-registry walk the compilers fold at compile time.
	 */
	private boolean subtypep(LispVal subV, LispVal superV) {
		return LispMacroExpander.subtypep(subV, superV, this.closRegistry);
	}

	/** The package-stripped member name of a possibly qualified symbol name. */
	private static String plainName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	/**
	 * The package name a runtime designator value denotes: a string, or a symbol whose
	 * {@code #:}/{@code :} prefix is stripped ({@code '#:jzon} and {@code :jzon} both
	 * name the package {@code jzon}).
	 */
	private static String packageNameDesignator(String operator, LispVal designator) {
		return switch (designator) {
			case LispString str -> str.value();
			case LispSymbol sym -> sym.name().startsWith("#:") ? sym.name().substring(2)
					: sym.name().startsWith(":") ? sym.name().substring(1) : sym.name();
			default ->
				throw new LispEvalException(operator + " expects a package designator, got " + designator.print());
		};
	}

	/**
	 * The built-in type name of a runtime value (the {@code %class-designator} view; the
	 * result set is {@code ClosRegistry.BUILTIN_CLASS_NAMES}).
	 */
	private static String builtinTypeName(LispVal v) {
		return switch (v) {
			case LispInteger ignored -> "integer";
			case LispBigInteger ignored -> "integer";
			case LispRatio ignored -> "ratio";
			case LispDouble ignored -> "float";
			case LispString ignored -> "string";
			case LispChar ignored -> "character";
			case LispTrue ignored -> "boolean";
			case LispNil ignored -> "null";
			case LispSymbol s -> s.isKeyword() ? "keyword" : "symbol";
			case LispCons ignored -> "cons";
			case LispHashTable ignored -> "hash-table";
			case LispFunction ignored -> "function";
			default -> "t";
		};
	}

	/**
	 * The non-local transfer a {@code (go tag)} throws; the enclosing {@code tagbody}
	 * whose label set contains the tag catches it and resumes at that label.
	 */
	private static final class GoSignal extends RuntimeException {

		private final String tag;

		GoSignal(String tag) {
			super(null, null, false, false);
			this.tag = tag;
		}

	}

	/**
	 * Evaluates {@code (tagbody {tag | form}...)}: symbols and integers are go-tag
	 * labels, everything else evaluates in order for effect. A {@code (go tag)} thrown
	 * anywhere inside (dynamically) resumes at that label; falling off the end returns
	 * nil. The compilers support the lexical subset only: a compiled {@code go} must
	 * target a lexically enclosing tagbody in the same function.
	 */
	private LispVal evalTagbody(LispCons cons, Environment env) {
		List<LispVal> body = cons.toList().subList(1, cons.toList().size());
		java.util.Map<String, Integer> labels = new java.util.HashMap<>();
		for (int i = 0; i < body.size(); i++) {
			if (body.get(i) instanceof LispSymbol label) {
				labels.put(plainName(label.name()), i);
			}
			else if (body.get(i) instanceof LispInteger label) {
				labels.put(String.valueOf(label.value()), i);
			}
		}
		int pc = 0;
		while (pc < body.size()) {
			LispVal form = body.get(pc);
			if (form instanceof LispSymbol || form instanceof LispInteger) {
				pc++;
				continue;
			}
			try {
				eval(form, env);
				pc++;
			}
			catch (GoSignal go) {
				Integer target = labels.get(go.tag);
				if (target == null) {
					// Not one of ours: an outer tagbody owns the tag.
					throw go;
				}
				pc = target + 1;
			}
		}
		return LispNil.INSTANCE;
	}

	/**
	 * Evaluates {@code (slot-value obj slot)}. A literal quoted slot name goes through
	 * the shared macro expansion (positional {@code nth}, compile-path parity); a
	 * computed name -- a variable or expression, e.g. a serializer walking
	 * {@code %class-slot-defs} results as data -- resolves the slot at runtime by base
	 * name (interpreter only).
	 */
	private LispVal evalSlotValue(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		boolean literalName = parts.size() == 3 && parts.get(2) instanceof LispCons q
				&& q.car() instanceof LispSymbol qs && LispNames.QUOTE.equals(qs.name());
		if (parts.size() == 3 && !literalName) {
			LispVal instance = eval(parts.get(1), env);
			LispVal slotName = eval(parts.get(2), env);
			SlotRef slot = instanceSlotRef(instance, slotName);
			if (slot == null) {
				throw new LispEvalException(
						LispNames.SLOT_VALUE + ": unknown slot " + slotName.print() + " on " + instance.print());
			}
			return slot.read();
		}
		return eval(LispMacroExpander.expandSlotValue(cons, this.closRegistry), env);
	}

	/** The value of a condition instance's slot by base name ({@code nil} if absent). */
	private LispVal conditionSlotValue(LispVal instance, String baseName) {
		SlotRef slot = instanceSlotRef(instance, new LispSymbol(baseName));
		return slot == null ? LispNil.INSTANCE : slot.read();
	}

	/** An instance together with the 0-based index of one of its slots. */
	private record SlotRef(LispInstance instance, int index) {

		LispVal read() {
			return this.instance.slot(this.index);
		}

		void write(LispVal value) {
			this.instance.setSlot(this.index, value);
		}

	}

	/**
	 * Whether a value is the slot-unbound marker -- one layout-tag compare, the same test
	 * the compiled backends' {@code %obj-is} performs.
	 */
	private static boolean isUnboundMarker(LispVal value) {
		return value instanceof LispInstance instance && instance.hasTag(ClosRegistry.UNBOUND_TAG);
	}

	/** A fresh slot-unbound marker instance (what {@code slot-makunbound} stores). */
	private LispVal unboundMarkerValue() {
		LispLayout layout = this.closRegistry.findLayoutByTag(ClosRegistry.UNBOUND_TAG);
		return LispInstance.ofNilSlots(java.util.Objects.requireNonNull(layout));
	}

	/**
	 * The slot of an instance named by a (runtime) symbol, or null when the value is not
	 * an instance or its layout has no slot of that name. The layout rides on the value,
	 * so this resolves a {@code defstruct} instance as readily as a CLOS one.
	 */
	private static @Nullable SlotRef instanceSlotRef(LispVal instance, LispVal slotName) {
		if (!(instance instanceof LispInstance inst) || !(slotName instanceof LispSymbol slotSym)) {
			return null;
		}
		String base = plainName(slotSym.name());
		List<String> slotNames = inst.layout().slotNames();
		for (int i = 0; i < slotNames.size(); i++) {
			// Case-insensitive: a Java-side caller (conditionSlotValue passes the
			// built-in "format-control"/"format-arguments") spells the slot lowercase,
			// while an upcase-read condition registers its slots upcased -- the same
			// reconciliation LispMacroExpander.expandConditionSlotReader makes.
			if (slotNames.get(i).equalsIgnoreCase(base)) {
				return new SlotRef(inst, i);
			}
		}
		return null;
	}

	/**
	 * Resolves every {@code (%read-eval datum)} marker in the form (the shape
	 * {@link am.ik.rontolisp.reader.LispReader#readAllWithReadEvalMarkers} wraps a
	 * {@code #.} datum in): each datum is evaluated against the global environment and
	 * its value substituted in place, recursively. Unchanged subtrees keep their
	 * identity. Also used by the compile path ({@code UserMacroExpander}), where this
	 * evaluator is the macro-time interpreter.
	 * @param form a top-level form possibly carrying read-eval markers
	 * @return the form with every marker replaced by its datum's value
	 */
	/**
	 * Replaces every {@code #S(NAME :SLOT value ...)} literal in the form with the
	 * instance it denotes, against this evaluator's registry -- the interpreter's half of
	 * the read-time construction Common Lisp performs inside its reader (the compile
	 * path's half runs per form inside
	 * {@link LispMacroExpander#expandTopLevelDefinitions}). Called at the top-level
	 * entries only, so a literal sees the {@code defstruct} of every PRECEDING top-level
	 * form and of no later one, which is the ordering CL's form-at-a-time load gives.
	 * @param form a top-level form possibly carrying struct literals
	 * @return the form with every literal replaced by its instance
	 */
	public LispVal resolveStructLiterals(LispVal form) {
		return StructLiteralFolder.fold(form, this.closRegistry);
	}

	/**
	 * Rebinds a runtime reader built-in ({@code read} / {@code read-from-string}) to
	 * itself plus a struct-literal fold, so a {@code #S(...)} datum read at run time is
	 * the instance it denotes and never the unresolved carrier. Rebinding the FUNCTION
	 * rather than the call sites keeps {@code #'read-from-string} and every library that
	 * funcalls it folding too.
	 */
	private void foldStructLiteralsOf(String name) {
		if (!(this.globalEnv.lookupFunctionOrNull(name) instanceof LispFunction raw)) {
			return;
		}
		this.globalEnv.defineFunction(name, new LispFunction(name, args -> {
			try {
				return StructLiteralFolder.fold(raw.body().apply(args), this.closRegistry);
			}
			catch (am.ik.rontolisp.reader.LispReadException | IllegalArgumentException e) {
				// A runtime read error is a catchable condition (CL's reader-error is an
				// error subtype): without this conversion a bad datum handed to
				// read/read-from-string would blow through handler-case as a raw Java
				// exception, while the compiled backends' emitted readers signal a
				// catchable simple-error -- the same parity contract the reader follows.
				throw new LispEvalException(String.valueOf(e.getMessage()));
			}
		}));
	}

	/**
	 * Signals when the current -- dynamic-first -- value of {@code *read-eval*} is nil:
	 * CLHS forbids reading {@code #.} then. Checked at marker RESOLUTION rather than at
	 * read, which is the same instant for the runtime read built-ins and, for the
	 * form-at-a-time load/compile paths, lets a top-level {@code (setq *read-eval* nil)}
	 * disable {@code #.} in every later form -- CL's one-form-at-a-time timing.
	 */
	private void requireReadEvalEnabled() {
		LispVal value = (!this.specialVars.isEmpty() || this.progvUsed)
				&& this.dynamicBindings.isBound(LispNames.READ_EVAL_VAR)
						? this.dynamicBindings.get(LispNames.READ_EVAL_VAR)
						: this.globalEnv.lookupOrNull(LispNames.READ_EVAL_VAR);
		if (value instanceof LispNil) {
			throw new LispEvalException("cannot read #. while *read-eval* is nil");
		}
	}

	/**
	 * Replaces the reader's {@code #.} markers in an already-read form by the value of
	 * the marked datum, rebuilding only the conses that actually change. The reader
	 * leaves the marker in place instead of evaluating it, because read-time evaluation
	 * needs an evaluator; this is where it happens.
	 * @param form the form as read
	 * @return the form with every read-time-eval marker resolved
	 */
	public LispVal resolveReadTimeEval(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.READ_EVAL.equals(head.name())
				&& cons.cdr() instanceof LispCons datumCons && datumCons.cdr() instanceof LispNil) {
			requireReadEvalEnabled();
			return eval(resolveReadTimeEval(datumCons.car()));
		}
		if (cons.car() instanceof LispSymbol head && LispNames.READ_EVAL_TEMPLATE.equals(head.name())
				&& cons.cdr() instanceof LispCons datumCons && datumCons.cdr() instanceof LispNil) {
			// A marker inside backquote construction code (the reader's renamed
			// variant): the value is template DATA, so it substitutes quoted --
			// evaluating the construction code embeds the value itself.
			requireReadEvalEnabled();
			LispVal value = eval(resolveReadTimeEval(datumCons.car()));
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
		}
		LispVal car = resolveReadTimeEval(cons.car());
		LispVal cdr = resolveReadTimeEval(cons.cdr());
		if (car == cons.car() && cdr == cons.cdr()) {
			return form;
		}
		return new LispCons(car, cdr);
	}

	/**
	 * Loads the named system: dependency systems first (recursively), then the component
	 * files in their {@code :depends-on}/{@code :serial} order, each through
	 * {@link #loadFile}. An already-loaded system is a no-op. An unknown system is
	 * located as {@code NAME.asd} in the directory of the loading file and then on the
	 * {@link #setSystemPath system path}; the {@code .asd} file is parsed as plain data
	 * (never evaluated), like the compile-time {@code LoadInliner} pass.
	 */
	/**
	 * Implements {@code ql:quickload}: downloads the named system (and its transitive
	 * dependencies) from the Quicklisp distribution into the local cache, adds the
	 * extracted {@code .asd} directories to the system search path, and then loads it
	 * through {@link #loadSystem} -- so quickload is {@code asdf:load-system} with an
	 * auto-download step in front.
	 */
	private void quickload(String name) {
		// A built-in system ("usocket") is satisfied by the embedded library: no
		// download, no cache, no QuicklispClient.
		if (BuiltinSystems.isBuiltin(name)) {
			loadSystem(name);
			return;
		}
		if (this.quicklispClient == null) {
			this.quicklispClient = QuicklispClient.createDefault();
		}
		List<String> asdDirs;
		try {
			asdDirs = this.quicklispClient.ensureAvailable(name);
		}
		catch (IOException ex) {
			throw new LispEvalException(LispNames.QL_QUICKLOAD + ": " + ex.getMessage());
		}
		List<String> merged = new java.util.ArrayList<>(this.systemPath);
		for (String dir : asdDirs) {
			if (!merged.contains(dir)) {
				merged.add(dir);
			}
		}
		this.systemPath = List.copyOf(merged);
		loadSystem(name);
	}

	/**
	 * Checks the trailing keyword options of a runtime {@code asdf:load-system} /
	 * {@code ql:quickload} call, which are accepted and ignored
	 * ({@link AsdfSystems#checkIgnoredLoadOptions}), rethrowing the shape error as a Lisp
	 * condition so a program's {@code handler-case} can see it.
	 */
	private static void ignoreLoadOptions(String context, List<LispVal> options) {
		try {
			AsdfSystems.checkIgnoredLoadOptions(context, options);
		}
		catch (IllegalStateException ex) {
			throw new LispEvalException(Objects.requireNonNullElse(ex.getMessage(), context));
		}
	}

	private void loadSystem(String name) {
		if (this.loadedSystems.contains(name)) {
			return;
		}
		if (this.loadingSystems.contains(name)) {
			throw new LispEvalException("Circular system :depends-on detected: "
					+ String.join(" -> ", this.loadingSystems) + " -> " + name);
		}
		if (BuiltinSystems.isBuiltin(name)) {
			// A system rontolisp provides itself (e.g. "usocket" or a dependency shim):
			// evaluate the embedded library instead of locating a NAME.asd.
			if (LispNames.USOCKET_PKG.equals(name)) {
				ensureUsocketLoaded();
			}
			else if (!this.loadedSystems.contains(name)) {
				if (LispNames.CLOSER_MOP_PKG.equalsIgnoreCase(name)) {
					// The shim's classp is (typep x 'standard-class): the MOP base
					// classes must be registered before that type test expands.
					this.closRegistry.ensureMopClassesSeeded();
				}
				for (LispVal form : BuiltinSystems.forms(name, Features.INTERPRETER)) {
					// Through the package resolver (the leaf-module rule): a shim that
					// carries its own defpackage (clack-handler-rontolisp) must register
					// it, and for the canonical-shape shims resolution is an identity.
					eval(form);
				}
			}
			this.loadedSystems.add(name);
			return;
		}
		AsdfSystems.LispSystem system = this.asdfSystems.get(name);
		if (system == null) {
			List<String> searchDirs = new java.util.ArrayList<>();
			String baseDir = this.loadDirStack.peekLast();
			searchDirs.add(baseDir == null ? "" : baseDir);
			searchDirs.addAll(this.systemPath);
			AsdfSystems.LocatedAsd asd = AsdfSystems.locate(name, searchDirs, this.sourceLoader);
			// .asd forms read upcased like all source; AsdfSystems matches clause
			// keywords case-insensitively and coerce-names (downcases) system
			// designators.
			for (AsdfSystems.LispSystem defined : AsdfSystems.parseAsdSource(asd.source(), asd.path(),
					Features.INTERPRETER)) {
				this.asdfSystems.putIfAbsent(defined.name(), defined);
			}
			system = this.asdfSystems.get(name);
			if (system == null) {
				throw new LispEvalException(asd.path() + " does not define system '" + name + "'");
			}
		}
		this.loadingSystems.addLast(name);
		// A system that declares :rontolisp-features has its own component files read
		// with the interpreter's features widened by that declaration -- the static
		// encoding of the eval-when *features* push a real .asd would do.
		Features systemFeatures = Features.INTERPRETER.with(system.features());
		// Component paths (and a dependency's .asd lookup) resolve against the system's
		// base directory, not the caller's.
		this.loadDirStack.addLast(system.baseDir());
		try {
			for (String dependency : system.dependsOn()) {
				loadSystem(dependency);
			}
			for (String file : system.files()) {
				List<LispVal> leafShim = ShimLibraries.leafModuleForms(name, file);
				if (leafShim != null) {
					// A substituted leaf module: evaluate the shim forms through the
					// package resolver (the defpackage must register before the
					// dependent components resolve), like the replaced file would.
					for (LispVal form : leafShim) {
						eval(form);
					}
					continue;
				}
				loadFile(LispNames.ASDF_LOAD_SYSTEM, file, name, systemFeatures);
			}
		}
		finally {
			this.loadDirStack.removeLast();
			this.loadingSystems.removeLast();
		}
		this.loadedSystems.add(name);
	}

	/**
	 * Evaluates an {@code (asdf:defsystem NAME ...)} special form: the options are plain
	 * data (never evaluated), so the form is parsed like a {@code .asd} entry and the
	 * system registered for a later {@code asdf:load-system}. Component paths resolve
	 * against the directory of the source being loaded.
	 */
	private LispVal evalDefsystem(LispCons cons) {
		String baseDir = this.loadDirStack.peekLast();
		AsdfSystems.LispSystem system = AsdfSystems.parseDefsystem(cons, baseDir == null ? "" : baseDir,
				Features.INTERPRETER);
		this.asdfSystems.put(system.name(), system);
		return new LispSymbol(system.name());
	}

	/**
	 * Parses a runtime module-name designator: a symbol (a keyword {@code :util} or a
	 * quoted symbol evaluating to {@code util}) or a string.
	 */
	private static String moduleDesignator(String operator, LispVal designator) {
		return switch (designator) {
			case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
			case LispString str -> str.value();
			default -> throw new LispEvalException(
					operator + " expects a module name (symbol or string), got " + designator.print());
		};
	}

	// Registers the interpreter side of the `java` interop package (a reflection
	// bridge). Registered here, alongside eval/load, because java:proxy applies a user
	// callback and so needs the evaluator's apply. The reflection needs runtime
	// metadata a native image lacks, so interpreting `java:` works only under
	// `java -jar rontolisp.jar`; the JVM compiler supports the same functions via its
	// embedded bridge (codegen.jvm.JavaBridgeTemplate), the WASM backend rejects them.
	private void registerJava() {
		JavaInterop.Caller caller = (function, callArgs) -> apply(function, callArgs, this.globalEnv);
		String jnew = PackageRegistry.qualify(LispNames.JAVA_PKG, LispNames.JAVA_NEW);
		this.globalEnv.defineFunction(jnew, new LispFunction(jnew, args -> {
			if (args.isEmpty() || !(args.get(0) instanceof LispString cls)) {
				throw new LispEvalException(jnew + " expects a class-name string, got "
						+ (args.isEmpty() ? "no arguments" : args.get(0).print()));
			}
			return JavaInterop.newInstance(cls.value(), args.subList(1, args.size()), caller);
		}));
		String jcall = PackageRegistry.qualify(LispNames.JAVA_PKG, LispNames.JAVA_CALL);
		this.globalEnv.defineFunction(jcall, new LispFunction(jcall, args -> {
			if (args.size() < 2 || !(args.get(1) instanceof LispString method)) {
				throw new LispEvalException(jcall + " expects (java:call object \"method\" args...)");
			}
			return JavaInterop.callInstance(args.get(0), method.value(), args.subList(2, args.size()), caller);
		}));
		String jstatic = PackageRegistry.qualify(LispNames.JAVA_PKG, LispNames.JAVA_STATIC);
		this.globalEnv.defineFunction(jstatic, new LispFunction(jstatic, args -> {
			if (args.size() < 2 || !(args.get(0) instanceof LispString cls)
					|| !(args.get(1) instanceof LispString method)) {
				throw new LispEvalException(jstatic + " expects (java:static \"class\" \"method\" args...)");
			}
			return JavaInterop.callStatic(cls.value(), method.value(), args.subList(2, args.size()), caller);
		}));
		String jfield = PackageRegistry.qualify(LispNames.JAVA_PKG, LispNames.JAVA_FIELD);
		this.globalEnv.defineFunction(jfield, new LispFunction(jfield, args -> {
			if (args.size() != 2 || !(args.get(1) instanceof LispString field)) {
				throw new LispEvalException(jfield + " expects (java:field class-or-object \"field\")");
			}
			return JavaInterop.field(args.get(0), field.value());
		}));
		String jproxy = PackageRegistry.qualify(LispNames.JAVA_PKG, LispNames.JAVA_PROXY);
		this.globalEnv.defineFunction(jproxy, new LispFunction(jproxy, args -> {
			if (args.size() != 2 || !(args.get(0) instanceof LispString iface)) {
				throw new LispEvalException(jproxy + " expects (java:proxy \"interface\" callable)");
			}
			return JavaInterop.proxy(iface.value(), args.get(1), caller);
		}));
	}

	/**
	 * Evaluate an expression in the global environment.
	 * @param expr the expression to evaluate
	 * @return the result
	 */
	public LispVal eval(LispVal expr) {
		// Resolve packages at the top-level entry only; nested evaluation and macro
		// expansion operate on the already-resolved canonical form.
		LispVal resolved = resolveStructLiterals(this.packageResolver.resolve(expr));
		// Register special declarations BEFORE evaluating, so a defun body's local
		// (declare (special x)) makes later let bindings of x dynamic (the same
		// pessimistic program-wide reading the compilers get from SpecialVarCollector).
		SpecialVarCollector.collectForm(resolved, this.specialVars);
		try {
			return eval(resolved, this.globalEnv);
		}
		catch (BlockReturnSignal signal) {
			// A named return-from whose block was never established (or whose exit
			// extent already ended) surfaces as an ordinary error, not a raw signal.
			throw new LispEvalException(LispNames.RETURN_FROM + ": no enclosing block named " + signal.name());
		}
		catch (ThrowSignal signal) {
			throw new LispEvalException(unmatchedThrowMessage(signal));
		}
	}

	/**
	 * Evaluates a top-level form as a MULTIPLE-VALUE consumer: returns every value the
	 * form produced, which is what a CL REPL echoes one value per line. {@code (floor 10
	 * 3)} yields the quotient AND the remainder, {@code (values)} yields no value at all,
	 * and an ordinary form yields exactly its single value.
	 * <p>
	 * The two producer routes of the multiple-value tier are both covered: a SYNTACTIC
	 * producer (a literal {@code values}, the {@code floor} family with a divisor,
	 * {@code gethash}, {@code array-displacement}) is echoed through
	 * {@code multiple-value-list}, since its extra values only exist inside a consumer's
	 * expansion; every other form is evaluated with the {@code %mv-spill} channel cleared
	 * first and its extra values read back from the channel afterwards, which is how a
	 * user function's tail {@code (values ...)} reaches the echo. The form is NOT wrapped
	 * in that case: an ordinary {@link #eval(LispVal)} runs, so a top-level definition
	 * form still evaluates at top level.
	 * @param expr the top-level form
	 * @return the form's values, primary first (empty for {@code (values)})
	 */
	public List<LispVal> evalValues(LispVal expr) {
		// Resolution is not idempotent under a :shadow package (see evalResolved), so the
		// form is resolved once here and evaluated through evalResolved.
		LispVal resolved = this.packageResolver.resolve(expr);
		if (LispMacroExpander.isSyntacticMultipleValueProducer(resolved)) {
			LispVal capture = new LispCons(new LispSymbol(LispNames.MULTIPLE_VALUE_LIST),
					new LispCons(resolved, LispNil.INSTANCE));
			return spilledValues(evalResolved(capture));
		}
		this.globalEnv.define(LispNames.MV_SPILL, LispNil.INSTANCE);
		LispVal primary = evalResolved(resolved);
		List<LispVal> values = new ArrayList<>();
		values.add(primary);
		values.addAll(spilledValues(this.globalEnv.lookup(LispNames.MV_SPILL)));
		// The values have been consumed: leave no leftovers for the next form's echo.
		this.globalEnv.define(LispNames.MV_SPILL, LispNil.INSTANCE);
		return values;
	}

	/** The elements of a value list (nil -- no values -- included). */
	private static List<LispVal> spilledValues(LispVal list) {
		return list instanceof LispCons cons ? cons.toList() : List.of();
	}

	/** The error a {@code throw} with no matching {@code catch} surfaces as. */
	private static String unmatchedThrowMessage(ThrowSignal signal) {
		return LispNames.THROW + ": no enclosing catch for tag " + signal.tag().print();
	}

	/**
	 * Resolves a top-level form through this evaluator's package resolver without
	 * evaluating it. An {@code in-package}/{@code defpackage} directive updates the
	 * resolver state as a side effect. Used by {@code UserMacroExpander} so
	 * package-qualified macro definitions and their call sites match canonically.
	 * @param form the top-level form
	 * @return the resolved form
	 */
	public LispVal resolvePackages(LispVal form) {
		return this.packageResolver.resolve(form);
	}

	/**
	 * Returns whether the resolver's CURRENT package shadows the given bare name (see
	 * {@link PackageResolver#currentPackageShadows}).
	 * @param name the bare symbol name
	 * @return {@code true} when the current package shadows the name
	 */
	public boolean currentPackageShadows(String name) {
		return this.packageResolver.currentPackageShadows(name);
	}

	/**
	 * Evaluates a top-level form that was ALREADY resolved through
	 * {@link #resolvePackages} -- the {@code UserMacroExpander} pipeline, which resolves
	 * each form itself so macro call sites match the canonical registered names. Skipping
	 * the second resolution that {@link #eval(LispVal)} would apply matters: resolution
	 * is not idempotent under a {@code :shadow} package, where a shadowed CL name's
	 * canonical BARE spelling re-resolves to the shadowing package's own symbol
	 * (re-resolving cl-ppcre's {@code defconstant} macro turned its {@code
	 * cl:defconstant} template head back into the shadowed macro's name -- a
	 * self-recursive macro that expanded forever).
	 * @param expr the resolved top-level form
	 * @return the result
	 */
	public LispVal evalResolved(LispVal expr) {
		expr = resolveStructLiterals(expr);
		SpecialVarCollector.collectForm(expr, this.specialVars);
		try {
			return eval(expr, this.globalEnv);
		}
		catch (BlockReturnSignal signal) {
			throw new LispEvalException(LispNames.RETURN_FROM + ": no enclosing block named " + signal.name());
		}
		catch (ThrowSignal signal) {
			throw new LispEvalException(unmatchedThrowMessage(signal));
		}
	}

	/**
	 * Registers an already-resolved top-level
	 * {@code defvar}/{@code defparameter}/{@code defconstant} into this evaluator WITHOUT
	 * running its value expression: the expression is parked as a thunk in the global
	 * environment and evaluated only if something READS the variable -- which, in the
	 * {@code UserMacroExpander} pipeline this serves, means only if a macro body reads
	 * the global at expansion time. Everything else about the definition is eager: the
	 * name is proclaimed special immediately, and {@code defvar}'s idempotence still sees
	 * a pending expression as bound.
	 * <p>
	 * The distinction is the whole compile-time cost of a library that builds tables at
	 * load time: those value expressions are for the RUNTIME program, which compiles and
	 * runs them itself, so evaluating them here as well was pure duplicated work. Only a
	 * global that an expansion actually consults has to exist at macro time.
	 * <p>
	 * A value expression that fails to evaluate leaves the name unbound with a warning
	 * (the same outcome as evaluating it eagerly and catching), reported at the point of
	 * the read rather than at the definition.
	 * @param expr the resolved top-level definition form
	 */
	public void registerLazyGlobal(LispVal expr) {
		LispVal form = resolveStructLiterals(expr);
		SpecialVarCollector.collectForm(form, this.specialVars);
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispSymbol name)) {
			return;
		}
		this.specialVars.add(name.name());
		// (defvar name) with no value form leaves the name unbound; defvar assigns only
		// when unbound, defparameter/defconstant always (re)assign.
		boolean force = !LispNames.DEFVAR.equals(op.name());
		if (parts.size() <= 2 || (!force && this.globalEnv.isBound(name.name()))) {
			return;
		}
		LispVal valueForm = parts.get(2);
		// The expression runs later, under whatever in-package state the expander has
		// reached by then, so the package current AT THE DEFINITION is captured and
		// restored around the run: an init form calling (intern ...) must home its symbol
		// into the package it was written in, exactly as evaluating it here would have.
		String definingPackage = this.packageResolver.currentPackageName();
		this.globalEnv.defineLazy(name.name(), () -> {
			String savedPackage = this.packageResolver.currentPackageName();
			this.packageResolver.setCurrentPackage(definingPackage);
			try {
				return eval(valueForm, this.globalEnv);
			}
			catch (RuntimeException ex) {
				System.err.println("warning: skipping macro-time evaluation of " + op.print() + " " + name.print()
						+ ": " + ex.getMessage());
				return null;
			}
			finally {
				this.packageResolver.setCurrentPackage(savedPackage);
			}
		});
	}

	/**
	 * Evaluate an expression in the given environment.
	 * @param expr the expression to evaluate
	 * @param env the lexical environment
	 * @return the result
	 */
	public LispVal eval(LispVal expr, Environment env) {
		return switch (expr) {
			case LispInteger i -> i;
			case LispBigInteger b -> b;
			case LispRatio r -> r;
			case LispDouble d -> d;
			case LispString s -> s;
			case LispChar c -> c;
			case LispNil n -> n;
			case LispTrue t -> t;
			case LispFunction f -> f;
			case LispLambda l -> l;
			case LispHashTable h -> h;
			case LispArray a -> a;
			case LispFloatArray fa -> fa;
			case am.ik.rontolisp.LispIntVector iv -> iv;
			case LispJavaObject j -> j;
			case LispFuture f -> f;
			case am.ik.rontolisp.LispThread th -> th;
			case LispStream s -> s;
			case LispInstance inst -> inst;
			// A #S(...) literal the top-level fold did not reach (one produced by a
			// runtime read, say) is folded here, so evaluating it always yields the
			// instance rather than a carrier leaking into user data.
			case LispStructLiteral literal -> StructLiteralFolder.fold(literal, this.closRegistry);
			case LispSymbol sym -> evalSymbolRef(sym, env);
			case LispCons cons -> evalCons(cons, env);
		};
	}

	/**
	 * Evaluates a bare symbol reference. Keywords self-evaluate. A special variable with
	 * an active dynamic binding reads that binding (dynamic extent, visible across
	 * function calls); otherwise the reference falls through to the ordinary
	 * lexical/global lookup, which finds the special's global default. Non-special names
	 * never reach the dynamic store, so the cheap emptiness gate keeps ordinary lexical
	 * reads (the common case) off the thread-local path entirely.
	 */
	private LispVal evalSymbolRef(LispSymbol sym, Environment env) {
		if (sym.isKeyword()) {
			return sym;
		}
		String name = sym.name();
		if ((!this.specialVars.isEmpty() || this.progvUsed) && this.dynamicBindings.isBound(name)) {
			return this.dynamicBindings.get(name);
		}
		LispVal value = env.lookupOrNull(name);
		if (value == null && !this.usocketLibraryLoaded && UsocketLibrary.isUsocketQualified(name)) {
			// The usocket library also exports variables (usocket:*wildcard-host*), so a
			// program whose FIRST usocket reference is a variable read must trigger the
			// same lazy load as a function resolution.
			ensureUsocketLoaded();
			value = this.globalEnv.lookupOrNull(name);
		}
		if (value == null) {
			throw new LispEvalException("The variable " + name + " is unbound");
		}
		return value;
	}

	/**
	 * Evaluates the usocket library definitions ({@code usocket.lisp}) into the global
	 * environment once; shared by the function/variable lazy-load hooks and the built-in
	 * ASDF system {@code "usocket"} ({@code asdf:load-system}/{@code ql:quickload}).
	 */
	private void ensureUsocketLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.usocketLibraryLoaded) {
				return;
			}
			this.usocketLibraryLoaded = true;
			for (LispVal form : UsocketLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	private boolean grayStreamsLoaded;

	/**
	 * Evaluates rontolisp's Gray-stream protocol ({@code gray.lisp}) once, on the first
	 * write to a CLOS-instance stream (or before the trivial-gray-streams shim system's
	 * adapter, which subclasses it).
	 */
	/** Whether the defclass form names a rontolisp Gray base class as a superclass. */
	private static final java.util.Set<String> GRAY_BASE_CLASSES = java.util.Set.of(LispNames.GRAY_CHAR_OUTPUT_STREAM,
			LispNames.GRAY_CHAR_INPUT_STREAM, LispNames.GRAY_FUNDAMENTAL_STREAM, LispNames.GRAY_INPUT_STREAM,
			LispNames.GRAY_OUTPUT_STREAM, LispNames.GRAY_BINARY_INPUT_STREAM, LispNames.GRAY_BINARY_OUTPUT_STREAM);

	private static boolean referencesGrayBaseClass(LispCons cons) {
		java.util.List<LispVal> parts = cons.toList();
		if (parts.size() < 3 || !(parts.get(2) instanceof LispCons supers)) {
			return false;
		}
		for (LispVal sup : supers.toList()) {
			if (sup instanceof LispSymbol sym) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && GRAY_BASE_CLASSES.contains(qn.member())) {
					return true;
				}
			}
		}
		return false;
	}

	private static final String GRAY_READ_BYTE_DISPATCH = GrayStreamsLibrary.READ_BYTE_DISPATCH;

	private static final String GRAY_READ_CHAR_DISPATCH = GrayStreamsLibrary.READ_CHAR_DISPATCH;

	private static final String GRAY_READ_LINE_DISPATCH = GrayStreamsLibrary.READ_LINE_DISPATCH;

	private static final String GRAY_WRITE_BYTE_DISPATCH = GrayStreamsLibrary.WRITE_BYTE_DISPATCH;

	private static final String GRAY_LISTEN_DISPATCH = GrayStreamsLibrary.LISTEN_DISPATCH;

	private static final String GRAY_FILE_POSITION_DISPATCH = GrayStreamsLibrary.FILE_POSITION_DISPATCH;

	private static final String GRAY_FILE_POSITION_SET_DISPATCH = GrayStreamsLibrary.FILE_POSITION_SET_DISPATCH;

	private static final String GRAY_READ_SEQUENCE_DISPATCH = GrayStreamsLibrary.READ_SEQUENCE_DISPATCH;

	private static final String GRAY_WRITE_SEQUENCE_DISPATCH = GrayStreamsLibrary.WRITE_SEQUENCE_DISPATCH;

	/**
	 * Applies a {@code rontolisp::%gray-*-dispatch} helper of gray.lisp (loading it on
	 * first use). The helpers hold the instance test, the :eof translation and the
	 * fallback to the handle built-in, shared verbatim with the compile path.
	 */
	private LispVal applyGrayDispatch(String helperName, List<LispVal> args) {
		ensureGrayStreamsLoaded();
		LispVal helper = resolveFunction(LispNames.RONTOLISP_PKG + "::" + helperName);
		return apply(helper, args, this.globalEnv);
	}

	/**
	 * Evaluates {@code (read-sequence seq stream ...)} / {@code (write-sequence seq
	 * stream ...)}: the sequence and stream arguments are evaluated once (in the macro
	 * expansion's order), and an INSTANCE stream routes to the Gray sequence dispatch
	 * helper so {@code rontolisp:stream-read-sequence}/{@code -write-sequence} methods
	 * are honored like on the compile path. Anything else re-enters the shared macro
	 * expansion with the two evaluated values quoted in place (no double evaluation); the
	 * {@code :start}/{@code :end} value expressions stay unevaluated and keep their
	 * position.
	 */
	private LispVal evalSequenceWithGrayDispatch(LispCons cons, Environment env, boolean read) {
		java.util.List<LispVal> parts = cons.toList();
		if (parts.size() < 3) {
			// Let the expansion signal the arity error.
			return eval(read ? LispMacroExpander.expandReadSequence(cons) : LispMacroExpander.expandWriteSequence(cons),
					env);
		}
		LispVal seq = eval(parts.get(1), env);
		LispVal stream = eval(parts.get(2), env);
		if (stream instanceof LispInstance) {
			LispVal start = new LispInteger(0);
			LispVal end = LispNil.INSTANCE;
			for (int i = 3; i + 1 < parts.size(); i += 2) {
				if (parts.get(i) instanceof LispSymbol kw) {
					if (":START".equals(kw.name())) {
						start = eval(parts.get(i + 1), env);
					}
					else if (":END".equals(kw.name())) {
						end = eval(parts.get(i + 1), env);
					}
				}
			}
			return applyGrayDispatch(read ? GRAY_READ_SEQUENCE_DISPATCH : GRAY_WRITE_SEQUENCE_DISPATCH,
					List.of(seq, stream, start, end));
		}
		java.util.List<LispVal> rebuilt = new java.util.ArrayList<>();
		rebuilt.add(parts.get(0));
		rebuilt.add(quoteValue(seq));
		rebuilt.add(quoteValue(stream));
		rebuilt.addAll(parts.subList(3, parts.size()));
		LispVal tail = LispNil.INSTANCE;
		for (int i = rebuilt.size() - 1; i >= 0; i--) {
			tail = new LispCons(rebuilt.get(i), tail);
		}
		LispCons rebuiltCons = (LispCons) tail;
		return eval(read ? LispMacroExpander.expandReadSequence(rebuiltCons)
				: LispMacroExpander.expandWriteSequence(rebuiltCons), env);
	}

	private static LispVal quoteValue(LispVal value) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
	}

	private void ensureGrayStreamsLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.grayStreamsLoaded) {
				return;
			}
			this.grayStreamsLoaded = true;
			for (LispVal form : GrayStreamsLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	private boolean mopProtocolLoaded;

	/**
	 * Evaluates the metaclass protocol once, on the first {@code :metaclass} defclass:
	 * the MOP base-class seeding, the seeded classes' keyword constructors (their
	 * defclass never ran, so {@code %mop-make-instance} could not dispatch to them
	 * otherwise), and the {@code MopProtocol} default methods plus the
	 * {@code %ensure-class-with-metaclass} driver. User protocol methods defined BEFORE
	 * this point (postmodern's hooks precede its first DAO class) auto-created their
	 * generics; the defaults merge into them like any later defmethod.
	 */
	private void ensureMopProtocolLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.mopProtocolLoaded) {
				return;
			}
			this.mopProtocolLoaded = true;
			this.closRegistry.ensureMopClassesSeeded();
			for (LispVal form : LispMacroExpander.seededMopConstructorDefuns(this.closRegistry)) {
				eval(form, this.globalEnv);
			}
			for (LispVal form : MopProtocol.forms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * Evaluates the WIT runtime ({@code wit.lisp}: the provider registry,
	 * {@code rontolisp:wit-provide} and the {@code rontolisp:wit-error} condition -- the
	 * provider mechanism, and no provider for any concrete interface) into the global
	 * environment once. Triggered by a {@code rontolisp:wit-import} directive and by the
	 * first resolution of one of the runtime's own names.
	 */
	private void ensureWitLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.witLibraryLoaded) {
				return;
			}
			this.witLibraryLoaded = true;
			for (LispVal form : WitLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * Loads the WIT runtime when a condition form names one of its classes
	 * ({@code rontolisp:wit-error}). A class name is a quoted DATUM, never a resolved
	 * function name, so the trigger in the function-resolution path above cannot see it
	 * -- and {@code error} would then expand against a {@link ClosRegistry} that has
	 * never heard of the class and build a bogus condition whose payload reader answers
	 * {@code :payload}. The compile path has no such gap (its pre-pass walks the AST,
	 * quoted symbols included), so without this the interpreter DIVERGES from the JVM on
	 * the same source.
	 */
	private void ensureWitLoadedForConditionClass(LispCons cons) {
		if (WitLibrary.referencesWitRuntime(cons)) {
			ensureWitLoaded();
		}
	}

	private LispVal evalCons(LispCons cons, Environment env) {
		LispVal head = cons.car();
		// A dotted tail is only meaningful as data (inside quote); in call position it
		// would otherwise be silently dropped by the toList() walks below.
		if (!(head instanceof LispSymbol qs && LispNames.QUOTE.equals(qs.name())) && !cons.isProperList()) {
			throw new LispEvalException("Improper list in call position: " + cons.print());
		}
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.QUOTE:
					return evalQuote(cons);
				case LispNames.IF:
					return evalIf(cons, env);
				case LispNames.LET:
					return evalLet(cons, env);
				case LispNames.PROGV:
					return evalProgv(cons, env);
				case LispNames.DEFUN:
					return evalDefun(cons, env);
				case LispNames.DEFMACRO:
					return evalDefmacro(cons, env);
				case LispNames.DEFSTRUCT:
					return evalDefstruct(cons, env);
				case LispNames.DEFCLASS:
					return evalDefclass(cons, env);
				case LispNames.DEFGENERIC:
					return evalDefgeneric(cons, env);
				case LispNames.DEFMETHOD:
					return evalDefmethod(cons, env);
				case LispNames.MAKE_INSTANCE:
					return eval(LispMacroExpander.expandMakeInstance(cons, this.closRegistry), env);
				case LispNames.CHANGE_CLASS:
					return eval(LispMacroExpander.expandChangeClass(cons, this.closRegistry, false), env);
				case LispNames.SLOT_VALUE:
					return evalSlotValue(cons, env);
				case LispNames.WITH_SLOTS:
					return eval(LispMacroExpander.expandWithSlots(cons), env);
				case LispNames.WITH_ACCESSORS:
					return eval(LispMacroExpander.expandWithAccessors(cons), env);
				case LispNames.DEFVAR:
					return evalDefvar(cons, env, false);
				case LispNames.DEFPARAMETER:
				case LispNames.DEFCONSTANT:
					return evalDefvar(cons, env, true);
				case LispNames.ASDF_DEFSYSTEM:
					// A special form: the system options are plain data, not evaluated.
					return evalDefsystem(cons);
				case LispNames.FUNCTION:
					return evalFunction(cons, env);
				case LispNames.PROGN:
					return evalProgn(cons, env);
				case LispNames.SETQ:
					return evalSetq(cons, env);
				case LispNames.LAMBDA:
					return evalLambdaForm(cons, env);
				case LispNames.ASYNC_QUALIFIED:
					return eval(LispMacroExpander.expandAsync(cons), env);
				case LispNames.ASYNC_DEFUN_QUALIFIED:
					return eval(LispMacroExpander.expandAsyncDefun(cons), env);
				case LispNames.ASYNC_LAMBDA_QUALIFIED:
					return eval(LispMacroExpander.expandAsyncLambda(cons), env);
				case LispNames.AWAIT_QUALIFIED:
					return evalAwait(cons, env);
				case LispNames.WHILE:
					return evalWhile(cons, env);
				case LispNames.COND:
					return eval(LispMacroExpander.expandCond(cons), env);
				case LispNames.CASE:
					return eval(LispMacroExpander.expandCase(cons), env);
				case LispNames.ECASE:
					return eval(LispMacroExpander.expandEcase(cons), env);
				case LispNames.CCASE:
					return eval(LispMacroExpander.expandCcase(cons), env);
				case LispNames.ERROR:
					ensureWitLoadedForConditionClass(cons);
					ensureConditionReportRuntimeLoaded();
					// The signal hook (handler-bind handlers at the signal point) is on
					// once the restart runtime is loaded: before the first
					// restart-system form is evaluated no handler can be established,
					// so the historical expansion is behavior-identical -- and the
					// interpreter re-expands per evaluation, so later signals see the
					// hook.
					return eval(LispMacroExpander.expandError(cons, this.closRegistry, true, this.restartRuntimeLoaded),
							env);
				case LispNames.CERROR:
					ensureConditionReportRuntimeLoaded();
					return eval(LispMacroExpander.expandCerror(cons, this.closRegistry, this.restartRuntimeLoaded),
							env);
				case LispNames.WARN:
					ensureWitLoadedForConditionClass(cons);
					ensureConditionReportRuntimeLoaded();
					return eval(LispMacroExpander.expandWarn(cons, this.closRegistry, this.restartRuntimeLoaded), env);
				case LispNames.SIGNAL:
					ensureWitLoadedForConditionClass(cons);
					ensureConditionReportRuntimeLoaded();
					return eval(LispMacroExpander.expandSignalMacro(cons, this.closRegistry, this.restartRuntimeLoaded),
							env);
				case LispNames.SIGNAL_COND_INTERNAL:
					return evalSignalCond(cons, env);
				case LispNames.HANDLER_CASE:
					ensureWitLoadedForConditionClass(cons);
					ensureConditionReportRuntimeLoaded();
					return evalHandlerCase(cons, env);
				case LispNames.HANDLER_BIND:
					ensureWitLoadedForConditionClass(cons);
					ensureConditionReportRuntimeLoaded();
					ensureRestartRuntimeLoaded();
					return eval(LispMacroExpander.expandHandlerBind(cons, this.closRegistry), env);
				case LispNames.RESTART_BIND:
					ensureRestartRuntimeLoaded();
					return eval(LispMacroExpander.expandRestartBind(cons), env);
				case LispNames.WITH_SIMPLE_RESTART:
					ensureRestartRuntimeLoaded();
					return eval(LispMacroExpander.expandWithSimpleRestart(cons), env);
				case LispNames.IGNORE_ERRORS:
					ensureConditionReportRuntimeLoaded();
					return eval(LispMacroExpander.expandIgnoreErrors(cons), env);
				case LispNames.STABLE_SORT:
					return eval(LispMacroExpander.expandStableSort(cons), env);
				case LispNames.COPY_SEQ:
					return eval(LispMacroExpander.expandCopySeq(cons), env);
				case LispNames.AND:
					return eval(LispMacroExpander.expandAnd(cons), env);
				case LispNames.OR:
					return eval(LispMacroExpander.expandOr(cons), env);
				case LispNames.WHEN:
					return eval(LispMacroExpander.expandWhen(cons), env);
				case LispNames.DOTIMES:
					return eval(LispMacroExpander.expandDotimes(cons), env);
				case LispNames.DO:
					return eval(LispMacroExpander.expandDo(cons), env);
				case LispNames.DO_STAR:
					return eval(LispMacroExpander.expandDoStar(cons), env);
				case LispNames.LOOP:
					return eval(LispMacroExpander.expandLoop(cons), env);
				case LispNames.BLOCK_INTERNAL:
					return evalBlock(cons, env);
				case LispNames.BLOCK:
					return evalNamedBlock(cons, env);
				case LispNames.RETURN_FROM:
					return evalReturnFrom(cons, env);
				case LispNames.CATCH:
					return evalCatch(cons, env);
				case LispNames.THROW:
					return evalThrow(cons, env);
				case LispNames.UNWIND_PROTECT:
					return evalUnwindProtect(cons, env);
				case LispNames.RETURN:
					throw new LispReturnSignal(evalReturnValue(cons, env));
				case LispNames.PROG1:
					return eval(LispMacroExpander.expandProg1(cons), env);
				case LispNames.TIME:
					return eval(LispMacroExpander.expandTime(cons), env);
				case LispNames.UNLESS:
					return eval(LispMacroExpander.expandUnless(cons), env);
				case LispNames.ONE_PLUS:
					return eval(LispMacroExpander.expandOnePlus(cons), env);
				case LispNames.ONE_MINUS:
					return eval(LispMacroExpander.expandOneMinus(cons), env);
				case LispNames.ZEROP:
					return eval(LispMacroExpander.expandZerop(cons), env);
				case LispNames.PLUSP:
					return eval(LispMacroExpander.expandPlusp(cons), env);
				case LispNames.MINUSP:
					return eval(LispMacroExpander.expandMinusp(cons), env);
				case LispNames.EVENP:
					return eval(LispMacroExpander.expandEvenp(cons), env);
				case LispNames.ODDP:
					return eval(LispMacroExpander.expandOddp(cons), env);
				case LispNames.FIRST:
					return eval(LispMacroExpander.expandFirst(cons), env);
				case LispNames.REST:
					return eval(LispMacroExpander.expandRest(cons), env);
				case LispNames.NTH:
					return eval(LispMacroExpander.expandNth(cons), env);
				case LispNames.SECOND:
					return eval(LispMacroExpander.expandSecond(cons), env);
				case LispNames.THIRD:
					return eval(LispMacroExpander.expandThird(cons), env);
				case LispNames.FOURTH:
					return eval(LispMacroExpander.expandFourth(cons), env);
				case LispNames.SETF: {
					// (setf (macro-function 'new) (macro-function 'existing)) is a write
					// to
					// the MACRO table, which lives here and nowhere else -- the shared
					// expander cannot lower it to a runtime form, so it is carried out
					// during evaluation instead of being expanded.
					LispVal macroAlias = aliasMacroFunction(cons);
					if (macroAlias != null) {
						return macroAlias;
					}
					// A prelude-provided (setf PLACE) writer (the (defun (setf get) ...)
					// beside the get defun) registers its place only when the prelude
					// entry loads; a setf place reference must trigger that load the same
					// way a function call would.
					ensurePreludeSetfPlacesLoaded(cons);
					return eval(expandSetfMaybeUserExpander(expandUserMacroPlaces(cons)), env);
				}
				case LispNames.PUSH:
					return eval(LispMacroExpander.expandPush(cons), env);
				case LispNames.POP:
					return eval(LispMacroExpander.expandPop(cons), env);
				case LispNames.REMF:
					return eval(LispMacroExpander.expandRemf(cons), env);
				case LispNames.LET_STAR:
					return eval(LispMacroExpander.expandLetStar(cons), env);
				case LispNames.DOLIST:
					return eval(LispMacroExpander.expandDolist(cons), env);
				case LispNames.INCF:
					return eval(LispMacroExpander.expandIncf(cons), env);
				case LispNames.DECF:
					return eval(LispMacroExpander.expandDecf(cons), env);
				case LispNames.FORMAT:
					return eval(LispMacroExpander.expandFormat(cons), env);
				case LispNames.WITH_OPEN_FILE:
					return eval(LispMacroExpander.expandWithOpenFile(cons), env);
				case LispNames.WITH_OUTPUT_TO_STRING:
					return eval(LispMacroExpander.expandWithOutputToString(cons), env);
				case LispNames.WITH_ARENA_QUALIFIED:
					// A reclamation boundary for --no-gc; a real GC already reclaims, so
					// the interpreter runs the body as a plain progn.
					return eval(LispMacroExpander.expandWithArena(cons), env);
				case LispNames.WITH_MUTEX_QUALIFIED:
				case LispNames.WITH_LOCK_HELD_QUALIFIED:
					// Acquire / body / release-on-every-exit; bordeaux-threads'
					// with-lock-held is the same shape over the same primitives.
					return eval(LispMacroExpander.expandWithMutex(cons), env);
				case LispNames.WIT_EXPORT_QUALIFIED:
					return evalWitExport(cons);
				case LispNames.WIT_IMPORT_QUALIFIED:
					return evalWitImport(cons);
				case LispNames.USOCKET_WITH_CLIENT_SOCKET_QUALIFIED:
					return eval(LispMacroExpander.expandUsocketWithClientSocket(cons), env);
				case LispNames.USOCKET_WITH_CONNECTED_SOCKET_QUALIFIED:
				case LispNames.USOCKET_WITH_SERVER_SOCKET_QUALIFIED:
					return eval(LispMacroExpander.expandUsocketWithConnectedSocket(cons), env);
				case LispNames.USOCKET_WITH_SOCKET_LISTENER_QUALIFIED:
					return eval(LispMacroExpander.expandUsocketWithSocketListener(cons), env);
				case LispNames.USOCKET_GUARD_QUALIFIED:
					return eval(LispMacroExpander.expandUsocketGuard(cons, true), env);
				case LispNames.WITH_INPUT_FROM_STRING:
					return eval(LispMacroExpander.expandWithInputFromString(cons), env);
				case LispNames.PUSHNEW:
					return eval(LispMacroExpander.expandPushnew(cons), env);
				case LispNames.DEFTYPE:
					return evalDeftype(cons);
				case LispNames.DEFINE_CONDITION: {
					// A condition type is an ordinary CLOS-subset class; the :report form
					// is registered for the error/signal/warn message building.
					LispVal defined = evalDefclass(
							(LispCons) LispMacroExpander.defineConditionToDefclass(cons, this.closRegistry), env);
					// The report renderer partitions the registry, so a new condition
					// class makes the loaded one stale; rebuilding here keeps it in step.
					ensureConditionReportRuntimeLoaded();
					return defined;
				}
				case LispNames.DEFINE_MODIFY_MACRO:
					return eval(LispMacroExpander.expandDefineModifyMacro(cons), env);
				case LispNames.DEFINE_SETF_EXPANDER:
					return registerSetfExpander(cons);
				case LispNames.DEFSETF:
					return registerDefsetf(cons);
				case LispNames.DEFINE_COMPILER_MACRO:
					return evalDefineCompilerMacro(cons, env);
				case LispNames.RESTART_CASE:
					ensureRestartRuntimeLoaded();
					return eval(LispMacroExpander.expandRestartCase(cons), env);
				case LispNames.MACROLET:
					return evalMacrolet(cons, env);
				case LispNames.MAKE_CONDITION:
					ensureConditionReportRuntimeLoaded();
					return eval(LispMacroExpander.expandMakeCondition(cons, this.closRegistry), env);
				case LispNames.DOCUMENTATION:
					return eval(LispMacroExpander.expandDocumentation(cons), env);
				case LispNames.COPY_READTABLE:
					return eval(LispMacroExpander.expandCopyReadtable(cons), env);
				case LispNames.SET_DISPATCH_MACRO_CHARACTER:
					return eval(LispMacroExpander.expandSetDispatchMacroCharacter(cons), env);
				case LispNames.READTABLE_CASE:
					return eval(LispMacroExpander.expandReadtableCase(cons), env);
			}
			// The operator table is split so that neither half crosses HotSpot's
			// 8000-bytecode HugeMethodLimit; see evalConsRareOperator.
			LispVal rare = evalConsRareOperator(cons, env, sym.name());
			if (rare != UNHANDLED) {
				return rare;
			}
			if (LispNames.isCarCdrComposition(sym.name())) {
				return eval(LispMacroExpander.expandCarCdrComposition(cons), env);
			}
			// User macros defined with defmacro: expand (evaluating the macro body with
			// the unevaluated argument forms bound) and evaluate the expansion. Checked
			// after the built-in operators, so a user macro can never shadow them.
			if (this.userMacros.containsKey(sym.name())) {
				return eval(expandUserMacro(cons), env);
			}
			// Compiler macros: applied last, so a defmacro and every built-in operator
			// still win, and memoized per call site so the expansion (and the
			// load-time-value slot inside it) is built once for this occurrence.
			if (!this.compilerMacros.isEmpty() && this.compilerMacros.containsKey(sym.name())) {
				LispVal expansion = expandCompilerMacro(cons);
				if (expansion != cons) {
					return eval(expansion, env);
				}
			}
			// Lisp-2: a symbol in call position is resolved in the function namespace
			// only; variable bindings of the same name do not shadow it.
			LispVal function = resolveFunction(sym.name());
			List<LispVal> args = evalArgs(cons, env);
			return apply(function, args, env);
		}
		// Non-symbol head: a lambda form such as ((lambda (x) x) 5)
		LispVal function = eval(head, env);
		List<LispVal> args = evalArgs(cons, env);
		return apply(function, args, env);
	}

	/**
	 * The second half of {@link #evalCons}'s operator table, answering {@link #UNHANDLED}
	 * for an operator it does not claim (and for the handful of arms that deliberately
	 * fall through to the ordinary function call, e.g. a one-argument {@code floor} or a
	 * {@code sort} without {@code :key}).
	 *
	 * <p>
	 * The split exists for one reason: {@code evalCons} is the interpreter's innermost
	 * method, and at 8209 bytecodes it sat just past HotSpot's {@code HugeMethodLimit}
	 * (8000, enforced by the default {@code -XX:+DontCompileHugeMethods}), so it was
	 * never JIT-compiled and every evaluated form ran through the bytecode interpreter --
	 * worth 2.7x on an arithmetic-heavy workload. Keep BOTH halves clear of that limit
	 * when adding operators; {@code LispEvaluatorHotMethodSizeTest} fails the build if
	 * either crosses it.
	 * @param cons the form being evaluated
	 * @param env the environment
	 * @param name the operator name
	 * @return the value, or {@link #UNHANDLED}
	 */
	private LispVal evalConsRareOperator(LispCons cons, Environment env, String name) {
		switch (name) {
			case LispNames.PRINT, LispNames.PRINC, LispNames.PRIN1, LispNames.PRINC_TO_STRING,
					LispNames.PRIN1_TO_STRING: {
				// Routed through print-object only when the program defines a method on
				// it; otherwise the ordinary Environment function runs, unchanged. The
				// renderer is INLINED here rather than called as a generated defun: the
				// interpreter re-expands per call, so it always sees the current method
				// set (a defmethod may follow the first print).
				if (this.closRegistry.routesConditionReports()) {
					// Already routing: only the freshness check, so a condition class
					// defined between two prints renders through its report too.
					ensureConditionReportRuntimeLoaded();
				}
				LispVal hooked = LispMacroExpander.expandPrintObjectHook(cons, this.closRegistry, true);
				if (hooked != null) {
					return eval(hooked, env);
				}
				break;
			}
			case LispNames.COMPLEX:
				return eval(LispMacroExpander.expandComplexLite(cons), env);
			case LispNames.NE:
				return eval(LispMacroExpander.expandNumericNotEqual(cons), env);
			case LispNames.PARSE_INTEGER:
				// The shared expansion carries the full keyword set and the second
				// return value; the Environment function remains for first-class
				// use (#'parse-integer).
				return eval(LispMacroExpander.expandParseInteger(cons), env);
			case LispNames.READ: {
				// The full CL tail (eof-error-p / eof-value / recursive-p) lowers to
				// the 0/1-argument call the Environment function implements, so the
				// same shape loads on every backend.
				LispVal readCompat = LispMacroExpander.expandReadCompat(cons);
				if (readCompat != null) {
					return eval(readCompat, env);
				}
				break;
			}
			case LispNames.READ_SEQUENCE:
				return evalSequenceWithGrayDispatch(cons, env, true);
			case LispNames.WRITE_SEQUENCE:
				return evalSequenceWithGrayDispatch(cons, env, false);
			case LispNames.MAKE_STRING:
				return eval(LispMacroExpander.expandMakeString(cons), env);
			// REPLACE is intentionally NOT expanded here: the interpreter uses the
			// destructive built-in (Environment) so a make-string buffer filled by
			// successive replaces (cl-who's string-list-to-string) mutates in place.
			// The compilers still expand it to a fresh concatenate (no runtime string
			// mutation there; cl-who resolves it at macro-expansion time).
			case LispNames.LOWER_CASE_P:
				return eval(LispMacroExpander.expandLowerCaseP(cons), env);
			case LispNames.UPPER_CASE_P:
				return eval(LispMacroExpander.expandUpperCaseP(cons), env);
			case LispNames.CONSTANTP:
				return eval(LispMacroExpander.expandConstantp(cons), env);
			case LispNames.STREAMP:
				return eval(LispMacroExpander.expandStreamp(cons), env);
			case LispNames.SIMPLE_STRING_P:
				return eval(LispMacroExpander.expandSimpleStringP(cons), env);
			case LispNames.PROG2:
				return eval(LispMacroExpander.expandProg2(cons), env);
			case LispNames.PSETQ:
				return eval(LispMacroExpander.expandPsetq(cons), env);
			case LispNames.PSETF:
				return eval(LispMacroExpander.expandPsetf(cons), env);
			case LispNames.TYPECASE:
				return eval(LispMacroExpander.expandTypecase(cons, this.closRegistry), env);
			case LispNames.ETYPECASE:
				return eval(LispMacroExpander.expandEtypecase(cons, this.closRegistry), env);
			case LispNames.CHECK_TYPE:
				return eval(LispMacroExpander.expandCheckType(cons), env);
			case LispNames.ASSERT:
				return eval(LispMacroExpander.expandAssert(cons), env);
			case LispNames.DECLARE:
				return eval(LispMacroExpander.expandDeclare(cons), env);
			case LispNames.DECLAIM:
				// (declaim (special ...)) proclaims specialness before the form
				// collapses to nil; other declarations remain no-ops.
				SpecialVarCollector.collectForm(cons, this.specialVars);
				return eval(LispMacroExpander.expandDeclaim(cons), env);
			case LispNames.PROCLAIM:
				SpecialVarCollector.collectForm(cons, this.specialVars);
				return eval(LispMacroExpander.expandProclaim(cons), env);
			case LispNames.THE:
				return eval(LispMacroExpander.expandThe(cons), env);
			case LispNames.EVAL_WHEN:
				return eval(LispMacroExpander.expandEvalWhen(cons), env);
			case LispNames.WRITE_CHAR:
				return eval(LispMacroExpander.expandWriteChar(cons), env);
			case LispNames.LOCALLY:
				return eval(LispMacroExpander.expandLocally(cons), env);
			case LispNames.WITH_STANDARD_IO_SYNTAX:
				return eval(LispMacroExpander.expandWithStandardIoSyntax(cons), env);
			case LispNames.FLET:
				return eval(LispMacroExpander.expandFlet(preExpandLocalMacros(cons)), env);
			case LispNames.LABELS:
				return eval(LispMacroExpander.expandLabels(preExpandLocalMacros(cons)), env);
			case LispNames.MULTIPLE_VALUE_BIND:
				return eval(LispMacroExpander.expandMultipleValueBind(cons), env);
			case LispNames.MULTIPLE_VALUE_LIST:
				return eval(LispMacroExpander.expandMultipleValueList(cons), env);
			case LispNames.MULTIPLE_VALUE_CALL:
				return eval(LispMacroExpander.expandMultipleValueCall(cons), env);
			case LispNames.NTH_VALUE:
				return eval(LispMacroExpander.expandNthValue(cons), env);
			case LispNames.MULTIPLE_VALUE_SETQ:
				return eval(LispMacroExpander.expandMultipleValueSetq(cons), env);
			case LispNames.MULTIPLE_VALUE_PROG1:
				return eval(LispMacroExpander.expandMultipleValueProg1(cons), env);
			case LispNames.ROTATEF:
				return eval(LispMacroExpander.expandRotatef(cons), env);
			case LispNames.SHIFTF:
				return eval(LispMacroExpander.expandShiftf(cons), env);
			case LispNames.LOAD_TIME_VALUE:
				return evalLoadTimeValue(cons, env);
			case LispNames.TYPEP:
				return eval(LispMacroExpander.expandTypep(cons, this.closRegistry), env);
			case LispNames.PRINT_UNREADABLE_OBJECT:
				return eval(LispMacroExpander.expandPrintUnreadableObject(cons), env);
			case LispNames.WITH_OPEN_STREAM:
				return eval(LispMacroExpander.expandWithOpenStream(cons, true), env);
			case LispNames.WITH_PACKAGE_ITERATOR:
				return eval(LispMacroExpander.expandWithPackageIterator(cons), env);
			case LispNames.DO_EXTERNAL_SYMBOLS:
				return evalDoExternalSymbols(cons, env);
			case LispNames.PROG:
				return eval(LispMacroExpander.expandProg(cons, false), env);
			case LispNames.PROG_STAR:
				return eval(LispMacroExpander.expandProg(cons, true), env);
			case LispNames.SYMBOL_MACROLET:
				// The substitution walk expands a user macro it meets before substituting
				// into its expansion (macro arguments may be data, the expansion is
				// code),
				// so it gets this evaluator's one-step expander as the hook.
				return eval(LispMacroExpander.expandSymbolMacrolet(cons, this.symbolMacroUserMacroHook), env);
			case LispNames.TAGBODY:
				return evalTagbody(cons, env);
			case LispNames.GO: {
				if (!(cons.cdr() instanceof LispCons tagCons) || !(tagCons.car() instanceof LispSymbol tagSym)) {
					throw new LispEvalException(LispNames.GO + " expects a tag: " + cons.print());
				}
				throw new GoSignal(plainName(tagSym.name()));
			}
			case LispNames.BYTE:
				return eval(LispMacroExpander.expandByte(cons), env);
			case LispNames.BYTE_SIZE:
				return eval(LispMacroExpander.expandByteSize(cons), env);
			case LispNames.BYTE_POSITION:
				return eval(LispMacroExpander.expandBytePosition(cons), env);
			case LispNames.LDB:
				return eval(LispMacroExpander.expandLdb(cons), env);
			case LispNames.MAKE_SEQUENCE:
				return eval(LispMacroExpander.expandMakeSequence(cons), env);
			case LispNames.DPB:
				return eval(LispMacroExpander.expandDpb(cons), env);
			case LispNames.DESTRUCTURING_BIND:
				return eval(LispMacroExpander.expandDestructuringBind(cons), env);
			case LispNames.FLOOR:
			case LispNames.CEILING:
			case LispNames.ROUND:
			case LispNames.TRUNCATE: {
				// (floor a b) -> (floor (/ a b)); the one-argument form falls
				// through to the ordinary built-in function.
				LispVal withDivisor = LispMacroExpander.expandFloorFamilyDivisor(cons);
				if (withDivisor != null) {
					return eval(withDivisor, env);
				}
				break;
			}
			case LispNames.LIST_STAR:
				return eval(LispMacroExpander.expandListStar(cons), env);
			case LispNames.ACONS:
				return eval(LispMacroExpander.expandAcons(cons), env);
			case LispNames.ENDP:
				return eval(LispMacroExpander.expandEndp(cons), env);
			case LispNames.ELT:
				return eval(LispMacroExpander.expandElt(cons), env);
			case LispNames.VECTOR:
				return eval(LispMacroExpander.expandVector(cons), env);
			case LispNames.SVREF:
				return eval(LispMacroExpander.expandSvref(cons), env);
			case LispNames.ARRAY_RANK:
				return eval(LispMacroExpander.expandArrayRank(cons), env);
			case LispNames.ARRAY_DIMENSION:
				return eval(LispMacroExpander.expandArrayDimension(cons), env);
			case LispNames.ARRAY_TOTAL_SIZE:
				return eval(LispMacroExpander.expandArrayTotalSize(cons), env);
			case LispNames.ARRAY_ROW_MAJOR_INDEX:
				return eval(LispMacroExpander.expandArrayRowMajorIndex(cons), env);
			case LispNames.COERCE:
				return eval(LispMacroExpander.expandCoerce(cons), env);
			case LispNames.MAP_INTO:
				return eval(LispMacroExpander.expandMapInto(cons), env);
			case LispNames.RASSOC:
				return eval(LispMacroExpander.expandRassoc(cons), env);
			// The sequence/alist functions taking :test/:key evaluate through the
			// shared macro expansion (like rassoc) so keyword handling matches the
			// compilers exactly; the Environment/LispEvaluator registrations remain
			// for first-class use (#'find etc.).
			case LispNames.FIND:
				return eval(LispMacroExpander.expandFind(cons), env);
			case LispNames.FIND_IF:
				return eval(LispMacroExpander.expandFindIf(cons), env);
			case LispNames.FIND_IF_NOT:
				return eval(LispMacroExpander.expandFindIfNot(cons), env);
			case LispNames.POSITION:
				return eval(LispMacroExpander.expandPosition(cons), env);
			case LispNames.POSITION_IF:
				return eval(LispMacroExpander.expandPositionIf(cons), env);
			case LispNames.POSITION_IF_NOT:
				return eval(LispMacroExpander.expandPositionIfNot(cons), env);
			case LispNames.COMPLEMENT:
				return eval(LispMacroExpander.expandComplement(cons), env);
			case LispNames.COUNT:
				return eval(LispMacroExpander.expandCount(cons), env);
			case LispNames.REMOVE:
				return eval(LispMacroExpander.expandRemove(cons), env);
			case LispNames.DELETE:
				return eval(LispMacroExpander.expandDelete(cons), env);
			case LispNames.REMOVE_DUPLICATES:
				return eval(LispMacroExpander.expandRemoveDuplicates(cons), env);
			case LispNames.UNION:
				return eval(LispMacroExpander.expandUnion(cons), env);
			case LispNames.INTERSECTION:
				return eval(LispMacroExpander.expandIntersection(cons), env);
			case LispNames.SET_DIFFERENCE:
				return eval(LispMacroExpander.expandSetDifference(cons), env);
			case LispNames.ADJOIN:
				return eval(LispMacroExpander.expandAdjoin(cons), env);
			case LispNames.SUBSTITUTE:
				return eval(LispMacroExpander.expandSubstitute(cons), env);
			case LispNames.NSUBSTITUTE:
				return eval(LispMacroExpander.expandNsubstitute(cons), env);
			case LispNames.SUBSTITUTE_IF:
				return eval(LispMacroExpander.expandSubstituteIf(cons), env);
			case LispNames.SUBSTITUTE_IF_NOT:
				return eval(LispMacroExpander.expandSubstituteIfNot(cons), env);
			case LispNames.NSUBSTITUTE_IF:
				return eval(LispMacroExpander.expandNsubstituteIf(cons), env);
			case LispNames.NSUBSTITUTE_IF_NOT:
				return eval(LispMacroExpander.expandNsubstituteIfNot(cons), env);
			case LispNames.REVAPPEND:
				return eval(LispMacroExpander.expandRevappend(cons), env);
			case LispNames.NRECONC:
				return eval(LispMacroExpander.expandNreconc(cons), env);
			case LispNames.MAP:
				return eval(LispMacroExpander.expandMap(cons), env);
			case LispNames.MAPLIST:
				return eval(LispMacroExpander.expandMaplist(cons), env);
			case LispNames.MAPCON:
				return eval(LispMacroExpander.expandMapcon(cons), env);
			case LispNames.MAPL:
				return eval(LispMacroExpander.expandMapl(cons), env);
			case LispNames.NOTANY:
				return eval(LispMacroExpander.expandNotany(cons), env);
			case LispNames.NOTEVERY:
				return eval(LispMacroExpander.expandNotevery(cons), env);
			case LispNames.REDUCE: {
				// :from-end/:key lower to a plain reduce; other forms fall through to
				// the native reduce builtin resolved below.
				LispVal expandedReduce = LispMacroExpander.expandReduce(cons);
				if (expandedReduce != null) {
					return eval(expandedReduce, env);
				}
				break;
			}
			case LispNames.SORT: {
				// (sort seq pred :key ...) routes through stable-sort; a plain
				// (sort seq pred) falls through to the native 2-argument builtin.
				LispVal expandedSort = LispMacroExpander.expandSortWithKey(cons);
				if (expandedSort != null) {
					return eval(expandedSort, env);
				}
				break;
			}
		}
		return UNHANDLED;
	}

	/**
	 * {@code (rontolisp:wit-export "world.wit" :world name)}: the interpreter cannot
	 * export anything, but it can still hold the program to the world's contract, so the
	 * same mismatch a {@code --component} build would reject is caught by a plain
	 * {@code rontolisp prog.lisp} run. The check therefore runs against the functions
	 * defined <em>so far</em> -- put the directive at the end of the file (where the
	 * scaffold puts it), after any {@code load} that defines the implementation.
	 */
	private LispVal evalWitExport(LispCons cons) {
		WitExportDirective.Directive directive = WitExportDirective.parse(cons);
		String resolved = SourceLoader.resolve(this.loadDirStack.peekLast(), directive.path());
		String source;
		try {
			source = this.sourceLoader.load(resolved);
		}
		catch (IOException ex) {
			throw new LispEvalException(
					LispNames.WIT_EXPORT_QUALIFIED + ": cannot read file " + resolved + ": " + ex.getMessage());
		}
		try {
			WitExportDirective.lower(directive, source, resolved, this::exportedLambdaList,
					WitExportDirective.Backend.OTHER);
		}
		catch (UnsupportedOperationException ex) {
			throw new LispEvalException(LispNames.WIT_EXPORT_QUALIFIED + ": " + ex.getMessage());
		}
		return LispNil.INSTANCE;
	}

	/**
	 * {@code (rontolisp:wit-import "kv.wit" :interface "..." :package kv)}: binds the WIT
	 * interface's functions as ordinary {@code defun}s dispatching through the
	 * interface's provider ({@code rontolisp:wit-provide}, or a built-in one), and
	 * defines the package that exports them. A special form, so the bindings exist for
	 * the rest of the file -- put the directive at the top, before the code that calls
	 * the interface.
	 */
	private LispVal evalWitImport(LispCons cons) {
		WitImportDirective.Directive directive = WitImportDirective.parse(cons);
		String resolved = SourceLoader.resolve(this.loadDirStack.peekLast(), directive.path());
		String source;
		try {
			source = this.sourceLoader.load(resolved);
		}
		catch (IOException ex) {
			throw new LispEvalException(
					LispNames.WIT_IMPORT_QUALIFIED + ": cannot read file " + resolved + ": " + ex.getMessage());
		}
		List<LispVal> bindings;
		try {
			// null drop filter: the interpreter binds a drop for every resource. It
			// produces no artifact whose bytes have to stay identical, and a program may
			// well reach a drop through funcall or eval.
			bindings = WitImportDirective.lower(directive, source, resolved, WitExportDirective.Backend.OTHER, null,
					null);
		}
		catch (UnsupportedOperationException ex) {
			throw new LispEvalException(LispNames.WIT_IMPORT_QUALIFIED + ": " + ex.getMessage());
		}
		ensureWitLoaded();
		for (LispVal binding : bindings) {
			// Through the resolver, so the synthesized (defpackage kv ...) registers and
			// the kv:-qualified defun names that follow it resolve -- exactly what a
			// hand-written defpackage in the source would do.
			eval(this.packageResolver.resolve(binding), this.globalEnv);
		}
		return LispNil.INSTANCE;
	}

	// The lambda list of a global defun, in the shape WitExportDirective checks: the
	// required parameters, plus a "&REST" marker when the function is variadic (an
	// exported function must take required parameters only). A built-in (a LispFunction,
	// not a LispLambda) is not a program-defined function, so it reads as undefined.
	private @Nullable List<String> exportedLambdaList(String name) {
		LispVal function = this.globalEnv.lookupFunctionOrNull(name);
		if (!(function instanceof LispLambda lambda)) {
			return null;
		}
		List<String> lambdaList = new ArrayList<>(lambda.params().size() + 1);
		for (LispSymbol param : lambda.params()) {
			lambdaList.add(param.name());
		}
		if (lambda.rest() != null) {
			lambdaList.add("&REST");
		}
		return lambdaList;
	}

	private LispVal evalDefun(LispCons cons, Environment env) {
		checkAwaitPlacement(cons);
		if (treeContainsMacrolet(cons)) {
			// A body carrying a macrolet is pre-expanded at DEFINITION time, like CL's
			// compile-time expansion: the local macros' expander functions run under the
			// defining package (a macro-time (intern ...) must home there, not in the
			// caller's package -- ironclad's finalize-registers name synthesis), and the
			// baked body no longer needs the local macros at call time.
			LispVal expanded = UserMacroExpander.expandAll(cons, this);
			if (expanded instanceof LispCons expandedCons) {
				cons = expandedCons;
			}
		}
		List<LispVal> parts = cons.toList();
		LispVal nameForm = parts.get(1);
		// (defun (setf name) ...): a setf-function. Install it under the mangled internal
		// name %setf-name and register the place so (setf (name ...) v) dispatches to it.
		LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(nameForm);
		String funcName;
		if (setfPlace != null) {
			funcName = LispMacroExpander.setfFunctionName(setfPlace.name());
			this.structAccessors.put(setfPlace.name(), LispMacroExpander.SETF_FUNCTION_MARKER);
		}
		else {
			funcName = ((LispSymbol) nameForm).name();
		}
		// Native block/return-from: skip the lite name-dropping rewrite and instead
		// wrap the body in a block named after the function, so (return-from name v)
		// exits the function even from inside a do/loop (whose %block does not catch
		// the named signal).
		LambdaLists.Expanded expanded = LambdaLists.expand(parts.get(2), parts.subList(3, parts.size()), false);
		LispSymbol blockNameSym = setfPlace != null ? setfPlace : (LispSymbol) nameForm;
		List<LispVal> blockParts = new ArrayList<>();
		blockParts.add(new LispSymbol(LispNames.BLOCK));
		blockParts.add(blockNameSym);
		blockParts.addAll(expanded.body());
		LispVal blockForm = LispNil.INSTANCE;
		for (int i = blockParts.size() - 1; i >= 0; i--) {
			blockForm = new LispCons(blockParts.get(i), blockForm);
		}
		// defun installs into the global function namespace, capturing the current
		// lexical environment, and returns the function name like Common Lisp.
		this.globalEnv.defineFunction(funcName,
				new LispLambda(expanded.required(), expanded.rest(), List.of(blockForm), env));
		return nameForm;
	}

	private LispVal evalDefstruct(LispCons cons, Environment env) {
		// Expand into the generated defuns (constructor, predicate, copier, accessors)
		// and evaluate each; the accessor registry makes them setf-able places.
		for (LispVal form : LispMacroExpander.expandDefstruct(cons, this.structAccessors, this.closRegistry,
				this.packageResolver::spellsAsExternal)) {
			eval(form, env);
		}
		// A struct predicate bakes the descendant tags known when it was generated, so
		// this (:include parent) definition has just widened every ancestor's tag set:
		// redefine their predicates against the registry as it now stands, which is what
		// makes (parent-p child) true even though the parent's defstruct came first.
		String structName = cons.toList().get(1) instanceof LispCons header ? headerStructName(header)
				: symbolNameOf(cons.toList().get(1));
		if (structName != null) {
			for (String ancestor : this.closRegistry.structAncestorNames(structName)) {
				String predicateName = this.closRegistry.structPredicates().get(ancestor);
				if (predicateName != null && !ancestor.equals(ClosRegistry.normalize(structName))) {
					eval(LispMacroExpander.structPredicateDefun(ancestor, predicateName, this.closRegistry), env);
				}
			}
		}
		// defstruct returns the structure name, like Common Lisp.
		return cons.toList().get(1);
	}

	private @Nullable String headerStructName(LispCons header) {
		return symbolNameOf(header.car());
	}

	private @Nullable String symbolNameOf(LispVal form) {
		return (form instanceof LispSymbol sym) ? sym.name() : null;
	}

	private LispVal evalDefclass(LispCons cons, Environment env) {
		// A defclass extending rontolisp's Gray base classes pulls gray.lisp in
		// eagerly: the superclass must be registered before the expansion checks it
		// (the write-string dispatch alone loads too late for a bare-protocol user
		// class that never went through the trivial-gray-streams shim).
		if (!this.grayStreamsLoaded && referencesGrayBaseClass(cons)) {
			ensureGrayStreamsLoaded();
		}
		// A defclass extending a seeded MOP base class (a metaclass definition, a
		// slot-definition subclass) needs the seeding before the superclass lookup; one
		// carrying (:metaclass M) additionally loads the metaclass protocol, whose
		// driver the expansion below calls as its last generated form.
		if (LispMacroExpander.defclassNamesMopBaseSuperclass(cons)) {
			this.closRegistry.ensureMopClassesSeeded();
		}
		if (LispMacroExpander.defclassUsesMetaclass(cons)) {
			ensureMopProtocolLoaded();
		}
		// Expand into the generated defuns (constructor, readers/accessors) and
		// evaluate each, then regenerate the dispatchers that test class specializers:
		// the new class may extend one of their descendant tag sets.
		for (LispVal form : LispMacroExpander.expandDefclass(cons, this.closRegistry, this.structAccessors)) {
			eval(form, env);
		}
		for (ClosRegistry.GenericInfo generic : this.closRegistry.generics().values()) {
			if (generic.hasClassMethod()) {
				defineDispatcher(generic.name(), env);
			}
		}
		return cons.toList().get(1);
	}

	private LispVal evalDefgeneric(LispCons cons, Environment env) {
		// A (defgeneric (setf name) ...) rides the %setf- writer-generic convention.
		cons = LispMacroExpander.normalizeSetfMethodForm(cons, this.structAccessors);
		java.util.List<LispVal> methodDefuns = new java.util.ArrayList<>();
		String generic = LispMacroExpander.registerDefgeneric(cons, this.closRegistry, methodDefuns);
		for (LispVal defun : methodDefuns) {
			eval(defun, env);
		}
		defineDispatcher(generic, env);
		return cons.toList().get(1);
	}

	private LispVal evalDefmethod(LispCons cons, Environment env) {
		// A (defmethod (setf name) ...) rides the %setf- writer-generic convention, so
		// it merges with any defclass :accessor writer methods on the same place.
		cons = LispMacroExpander.normalizeSetfMethodForm(cons, this.structAccessors);
		// Evaluate the generated method-body defun, then redefine the dispatcher so it
		// sees the new method (calls by name always dispatch through the fresh one; a
		// #'name captured earlier keeps the previous dispatcher).
		eval(LispMacroExpander.expandDefmethod(cons, this.closRegistry), env);
		String generic = ((LispSymbol) cons.toList().get(1)).name();
		defineDispatcher(generic, env);
		// The expansion may have REGISTERED a further generic (the
		// instance-initialization protocol's shared-initialize) whose dispatcher does not
		// exist yet; define any such dispatcher too.
		for (ClosRegistry.GenericInfo info : this.closRegistry.generics().values()) {
			if (this.globalEnv.lookupFunctionOrNull(info.name()) == null) {
				defineDispatcher(info.name(), env);
			}
		}
		return cons.toList().get(1);
	}

	/**
	 * Installs (or reinstalls) a generic function's dispatcher defun, stashing the
	 * Java-backed built-in of the same name first so it survives as the generic's default
	 * method.
	 * @param genericName the generic-function name
	 * @param env the environment to evaluate the dispatcher defun in
	 */
	private void defineDispatcher(String genericName, Environment env) {
		String fallback = builtinDefaultMethodFor(genericName);
		eval(LispMacroExpander.generateDispatcher(genericName, this.closRegistry, fallback), env);
	}

	/**
	 * Stashes the built-in a generic function's dispatcher is about to shadow, so the
	 * dispatcher's fall-through can call it: a program methoding a CL built-in name
	 * (fast-io's {@code close}/{@code open-stream-p}/... methods on its stream classes)
	 * must not lose the built-in behavior for every other argument -- in CL these are
	 * generic functions whose standard methods a user method joins rather than replaces.
	 *
	 * <p>
	 * A Java-backed built-in is a {@link LispFunction}; a user {@code defun} (a prelude
	 * or library definition included) is a {@code LispLambda} and is deliberately left to
	 * be shadowed, as is a name that is not defined yet. A HIT is memoized because the
	 * dispatcher is regenerated on every {@code defmethod}: the second pass would
	 * otherwise find nothing to stash and drop the default method. A MISS needs no memo
	 * -- a dispatcher is itself a {@code LispLambda}, so re-probing a shadowed name keeps
	 * answering null.
	 * @param genericName the generic-function name
	 * @return the internal name the built-in is stashed under, or null when the name has
	 * no built-in to preserve
	 */
	private @Nullable String builtinDefaultMethodFor(String genericName) {
		String stashed = this.builtinDefaultMethods.get(genericName);
		if (stashed != null) {
			return stashed;
		}
		if (!(this.globalEnv.lookupFunctionOrNull(genericName) instanceof LispFunction builtin)) {
			return null;
		}
		String internal = LispMacroExpander.builtinDefaultMethodName(genericName);
		this.globalEnv.defineFunction(internal, builtin);
		this.builtinDefaultMethods.put(genericName, internal);
		return internal;
	}

	/**
	 * Evaluates {@code (do-external-symbols (var package [result]) body...)}: iterates
	 * the designated package's external symbols (canonically spelled, in sorted order --
	 * the registry records exports from {@code defpackage}, so this is the real listing,
	 * unlike the empty {@code with-package-iterator} lite). The result form is evaluated
	 * with the variable bound to nil, per CL.
	 */
	private LispVal evalDoExternalSymbols(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispCons specCons) || specCons.toList().isEmpty()
				|| !(specCons.toList().get(0) instanceof LispSymbol var)) {
			throw new LispEvalException(
					LispNames.DO_EXTERNAL_SYMBOLS + " expects ((var package [result]) body...): " + cons.print());
		}
		List<LispVal> spec = specCons.toList();
		String designator = spec.size() >= 2 ? packageDesignator(LispNames.DO_EXTERNAL_SYMBOLS, eval(spec.get(1), env))
				: this.packageResolver.currentPackageName();
		for (LispSymbol sym : this.packageResolver.externalSymbols(designator)) {
			Environment iterEnv = new Environment(env);
			iterEnv.define(var.name(), sym);
			for (LispVal bodyForm : parts.subList(2, parts.size())) {
				eval(bodyForm, iterEnv);
			}
		}
		if (spec.size() >= 3) {
			Environment resultEnv = new Environment(env);
			resultEnv.define(var.name(), LispNil.INSTANCE);
			return eval(spec.get(2), resultEnv);
		}
		return LispNil.INSTANCE;
	}

	/**
	 * Internal rest parameter binding the whole unevaluated argument list of a macro
	 * whose lambda list needs destructuring (see {@link #evalDefmacro}).
	 */
	private static final String MACRO_ARGS_VAR = "__macro_args";

	private LispVal evalDefmacro(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3 || !(parts.get(1) instanceof LispSymbol name)) {
			throw new LispEvalException(LispNames.DEFMACRO + " expects (defmacro name (params...) body...)");
		}
		if (PackageRegistry.isClSymbol(name.name())) {
			throw new LispEvalException(LispNames.DEFMACRO + " cannot redefine the standard operator " + name.name());
		}
		this.userMacros.put(name.name(),
				makeUserMacro(LispNames.DEFMACRO, name, parts.get(2), parts.subList(3, parts.size()), env));
		return name;
	}

	/**
	 * Registers a {@code define-compiler-macro}. A compiler macro is a HINT: CL lets an
	 * implementation ignore one entirely, so every shape rontolisp cannot carry -- a
	 * {@code (setf name)} function designator, a standard operator (which the shared
	 * expander lowers on every backend before a compiler macro could see it), a lambda
	 * list the macro machinery rejects -- is silently not registered rather than an
	 * error.
	 * @param cons the {@code (define-compiler-macro name (params...) body...)} form
	 * @param env the defining environment
	 * @return the name, like {@code defmacro}
	 */
	private LispVal evalDefineCompilerMacro(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3 || !(parts.get(1) instanceof LispSymbol name) || PackageRegistry.isClSymbol(name.name())) {
			return parts.size() >= 2 ? parts.get(1) : LispNil.INSTANCE;
		}
		try {
			this.compilerMacros.put(name.name(), makeUserMacro(LispNames.DEFINE_COMPILER_MACRO, name, parts.get(2),
					parts.subList(3, parts.size()), env));
		}
		catch (RuntimeException ex) {
			this.compilerMacros.remove(name.name());
		}
		return name;
	}

	/**
	 * Whether a compiler macro is defined for the operator name.
	 * @param name the canonical operator name
	 * @return {@code true} when {@link #expandCompilerMacro} may rewrite a call to it
	 */
	public boolean hasCompilerMacro(String name) {
		return this.compilerMacros.containsKey(name);
	}

	/**
	 * Applies the call form's compiler macro ONCE, memoized by call-site identity.
	 * Returns {@code form} itself -- the caller's signal to stop -- when the macro
	 * declines, when its body signals, or when none is defined.
	 *
	 * <p>
	 * Both escape hatches are load-bearing. The universal decline idiom is "return the
	 * {@code &whole} parameter", and that parameter is a FRESHLY consed copy of the call,
	 * so the comparison has to be on printed shape; an identity test would take the
	 * expansion as progress and re-expand forever. And a body that signals (ironclad's
	 * {@code make-digest} reaches for package objects at expansion time) must not fail
	 * the program: CLHS explicitly permits ignoring a compiler macro, so a hint that
	 * cannot be honoured is simply not honoured.
	 * @param form the call form; its operator must be a symbol
	 * @return the expansion, or {@code form} when the macro does not apply
	 */
	public LispVal expandCompilerMacro(LispCons form) {
		LispVal cached = this.compilerMacroExpansions.get(form);
		if (cached != null) {
			return cached;
		}
		LispVal expansion = computeCompilerMacroExpansion(form);
		if (this.compilerMacroExpansions.size() < EXPANSION_MEMO_LIMIT) {
			this.compilerMacroExpansions.put(form, expansion);
		}
		return expansion;
	}

	private LispVal computeCompilerMacroExpansion(LispCons form) {
		if (!(form.car() instanceof LispSymbol op)) {
			return form;
		}
		UserMacro macro = this.compilerMacros.get(op.name());
		if (macro == null) {
			return form;
		}
		boolean savedMute = this.out.muted;
		this.out.muted = true;
		try {
			LispVal expansion = expandMacroCall(op.name(), macro, form);
			return expansion.print().equals(form.print()) ? form : expansion;
		}
		catch (RuntimeException | StackOverflowError ex) {
			return form;
		}
		finally {
			this.out.muted = savedMute;
		}
	}

	/**
	 * Evaluates {@code (load-time-value form [read-only-p])} at most once per source
	 * occurrence, memoized by the form's cons identity -- CL's contract for interpreted
	 * code, and the same one {@code LispMacroExpander.hoistLoadTimeValues} gives the
	 * compile path with a synthesized global. The memo holds a one-element list so a
	 * {@code nil} result still counts as computed.
	 */
	private LispVal evalLoadTimeValue(LispCons cons, Environment env) {
		List<LispVal> computed = this.loadTimeValues.get(cons);
		if (computed != null) {
			return computed.get(0);
		}
		LispVal value = eval(LispMacroExpander.expandLoadTimeValue(cons), env);
		if (this.loadTimeValues.size() < EXPANSION_MEMO_LIMIT) {
			this.loadTimeValues.put(cons, List.of(value));
		}
		return value;
	}

	/**
	 * Builds a {@link UserMacro} record from a {@code (name lambda-list body...)}
	 * definition, shared by {@link #evalDefmacro} and {@link #evalMacrolet}. A native
	 * "required + &rest/&body" lambda list is stored directly; any extended shape is
	 * wrapped in a {@code destructuring-bind} over an internal rest parameter (validated
	 * eagerly by a dry-run expansion) so both macro-expansion consumers destructure
	 * identically. An {@code &environment} parameter (legal anywhere at the top level of
	 * a macro lambda list) is stripped first and bound to nil around the body -- there is
	 * no environment object (see {@link LispNames#LAMBDA_ENVIRONMENT}).
	 */
	private UserMacro makeUserMacro(String op, LispSymbol name, LispVal paramForm, List<LispVal> body,
			Environment env) {
		List<LispVal> paramList = paramForm instanceof LispCons paramCons ? paramCons.toList() : List.of();
		int envIndex = -1;
		for (int i = 0; i < paramList.size(); i++) {
			if (paramList.get(i) instanceof LispSymbol sym && LispNames.LAMBDA_ENVIRONMENT.equals(sym.name())) {
				envIndex = i;
				break;
			}
		}
		if (envIndex >= 0) {
			if (envIndex + 1 >= paramList.size() || !(paramList.get(envIndex + 1) instanceof LispSymbol envVar)
					|| envVar.name().startsWith("&")) {
				throw new LispEvalException(op + " " + name.name() + ": " + LispNames.LAMBDA_ENVIRONMENT
						+ " must be followed by exactly one parameter symbol");
			}
			List<LispVal> stripped = new ArrayList<>(paramList);
			stripped.remove(envIndex + 1);
			stripped.remove(envIndex);
			LispVal strippedForm = LispNil.INSTANCE;
			for (int i = stripped.size() - 1; i >= 0; i--) {
				strippedForm = new LispCons(stripped.get(i), strippedForm);
			}
			List<LispVal> letParts = new ArrayList<>();
			letParts.add(new LispSymbol(LispNames.LET));
			letParts.add(new LispCons(new LispCons(envVar, new LispCons(LispNil.INSTANCE, LispNil.INSTANCE)),
					LispNil.INSTANCE));
			letParts.addAll(body);
			LispVal wrappedBody = LispNil.INSTANCE;
			for (int i = letParts.size() - 1; i >= 0; i--) {
				wrappedBody = new LispCons(letParts.get(i), wrappedBody);
			}
			return makeUserMacro(op, name, strippedForm, List.of(wrappedBody), env);
		}
		// &whole var (first element, per CL; after the &environment strip so the two
		// compose): bind var to the whole macro call form, (cons 'name args), and
		// destructure the REMAINING pattern over the argument list as usual. Forces the
		// destructuring path so the internal rest variable exists for the whole-form
		// rebuild.
		if (!paramList.isEmpty() && paramList.get(0) instanceof LispSymbol wholeKw
				&& LispNames.LAMBDA_WHOLE.equals(wholeKw.name())) {
			if (paramList.size() < 2 || !(paramList.get(1) instanceof LispSymbol wholeVar)
					|| wholeVar.name().startsWith("&")) {
				throw new LispEvalException(op + " " + name.name() + ": " + LispNames.LAMBDA_WHOLE
						+ " must be followed by exactly one parameter symbol");
			}
			List<LispVal> stripped = paramList.subList(2, paramList.size());
			LispVal strippedForm = LispNil.INSTANCE;
			for (int i = stripped.size() - 1; i >= 0; i--) {
				strippedForm = new LispCons(stripped.get(i), strippedForm);
			}
			LispSymbol argsVar = new LispSymbol(MACRO_ARGS_VAR);
			LispVal wholeForm = new LispCons(new LispSymbol(LispNames.CONS),
					new LispCons(new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(name, LispNil.INSTANCE)),
							new LispCons(argsVar, LispNil.INSTANCE)));
			List<LispVal> letParts = new ArrayList<>();
			letParts.add(new LispSymbol(LispNames.LET));
			letParts
				.add(new LispCons(new LispCons(wholeVar, new LispCons(wholeForm, LispNil.INSTANCE)), LispNil.INSTANCE));
			letParts.addAll(body);
			LispVal letForm = LispNil.INSTANCE;
			for (int i = letParts.size() - 1; i >= 0; i--) {
				letForm = new LispCons(letParts.get(i), letForm);
			}
			List<LispVal> dbParts = new ArrayList<>();
			dbParts.add(new LispSymbol(LispNames.DESTRUCTURING_BIND));
			dbParts.add(strippedForm);
			dbParts.add(argsVar);
			dbParts.add(letForm);
			LispVal wrapped = LispNil.INSTANCE;
			for (int i = dbParts.size() - 1; i >= 0; i--) {
				wrapped = new LispCons(dbParts.get(i), wrapped);
			}
			try {
				LispMacroExpander.expandDestructuringBind((LispCons) wrapped);
			}
			catch (IllegalArgumentException ex) {
				throw new LispEvalException(op + " " + name.name() + ": " + ex.getMessage());
			}
			return new UserMacro(List.of(), argsVar, List.of(wrapped), env);
		}
		if (!isSimpleMacroLambdaList(paramList)) {
			LispSymbol argsVar = new LispSymbol(MACRO_ARGS_VAR);
			List<LispVal> dbParts = new ArrayList<>();
			dbParts.add(new LispSymbol(LispNames.DESTRUCTURING_BIND));
			dbParts.add(paramForm);
			dbParts.add(argsVar);
			dbParts.addAll(body);
			LispVal wrapped = LispNil.INSTANCE;
			for (int i = dbParts.size() - 1; i >= 0; i--) {
				wrapped = new LispCons(dbParts.get(i), wrapped);
			}
			try {
				// Dry-run the expansion to validate the lambda list at definition time
				// (&whole/&environment and malformed specs signal here, not at first
				// use).
				LispMacroExpander.expandDestructuringBind((LispCons) wrapped);
			}
			catch (IllegalArgumentException ex) {
				throw new LispEvalException(op + " " + name.name() + ": " + ex.getMessage());
			}
			return new UserMacro(List.of(), argsVar, List.of(wrapped), env);
		}
		List<LispSymbol> required = new ArrayList<>();
		LispSymbol rest = null;
		for (int i = 0; i < paramList.size(); i++) {
			LispSymbol param = (LispSymbol) paramList.get(i);
			if (LispNames.LAMBDA_REST.equals(param.name()) || LispNames.LAMBDA_BODY.equals(param.name())) {
				rest = (LispSymbol) paramList.get(i + 1);
				break;
			}
			required.add(param);
		}
		return new UserMacro(required, rest, body, env);
	}

	/**
	 * Evaluates {@code (macrolet ((name lambda-list body...)...) body...)}: each local
	 * macro is installed into the user-macro table for the dynamic extent of the body
	 * evaluation (shadowing any outer macro of the same name, restored afterwards) and
	 * the body is evaluated with the local macros active. Local macros are defined in the
	 * global environment (CL's null lexical environment for macro functions), so their
	 * bodies see global helpers but not the surrounding runtime bindings. Lite scoping
	 * caveat: because the table is global for the extent, a function called from the body
	 * that happens to reference a local macro name would also see it (same pre-existing
	 * interpreter limitation as {@code flet}/{@code labels}); the compile path
	 * (UserMacroExpander) is lexically correct.
	 */
	private LispVal evalMacrolet(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispCons || parts.get(1) instanceof LispNil)) {
			throw new LispEvalException(LispNames.MACROLET + " expects a definition list");
		}
		List<LispVal> defs = parts.get(1) instanceof LispCons defsCons ? defsCons.toList() : List.of();
		java.util.Map<String, UserMacro> saved = new java.util.HashMap<>();
		java.util.Set<String> added = new java.util.HashSet<>();
		for (LispVal def : defs) {
			if (!(def instanceof LispCons defCons) || !defCons.isProperList() || defCons.toList().size() < 2
					|| !(defCons.toList().get(0) instanceof LispSymbol name) || name.isKeyword()) {
				throw new LispEvalException(LispNames.MACROLET + " definition must be (name lambda-list body...)");
			}
			List<LispVal> dp = defCons.toList();
			UserMacro macro = makeUserMacro(LispNames.MACROLET, name, dp.get(1), dp.subList(2, dp.size()),
					this.globalEnv);
			if (!added.contains(name.name()) && this.userMacros.containsKey(name.name())) {
				saved.put(name.name(), this.userMacros.get(name.name()));
			}
			this.userMacros.put(name.name(), macro);
			added.add(name.name());
		}
		try {
			LispVal result = LispNil.INSTANCE;
			for (LispVal bodyForm : parts.subList(2, parts.size())) {
				// Pre-expand with the local macros active BEFORE evaluating: a body form
				// that only CAPTURES code (a defun/defgeneric method body) must bake the
				// expansion in now -- by the time the captured body runs, the local
				// macros are gone and the call would resolve as an undefined function
				// (jzon's %coerced-fields-slots inside its coerced-fields defgeneric).
				result = eval(UserMacroExpander.expandAll(bodyForm, this), env);
			}
			return result;
		}
		finally {
			for (String n : added) {
				if (saved.containsKey(n)) {
					this.userMacros.put(n, saved.get(n));
				}
				else {
					this.userMacros.remove(n);
				}
			}
		}
	}

	/**
	 * Returns whether the macro lambda list is the native shape (required symbols plus an
	 * optional trailing {@code &rest}/{@code &body} pair). Anything else -- a nested
	 * pattern, {@code &optional}/{@code &key}/..., a non-symbol -- takes the
	 * destructuring path.
	 */
	private static boolean isSimpleMacroLambdaList(List<LispVal> paramList) {
		for (int i = 0; i < paramList.size(); i++) {
			if (!(paramList.get(i) instanceof LispSymbol param)) {
				return false;
			}
			if (param.name().startsWith("&")) {
				return (LispNames.LAMBDA_REST.equals(param.name()) || LispNames.LAMBDA_BODY.equals(param.name()))
						&& i + 2 == paramList.size() && paramList.get(i + 1) instanceof LispSymbol restParam
						&& !restParam.name().startsWith("&");
			}
		}
		return true;
	}

	/**
	 * Returns whether the given name is a user macro defined with {@code defmacro}.
	 * @param name the operator name
	 * @return {@code true} if a user macro of that name is defined
	 */
	public boolean isUserMacro(String name) {
		return this.userMacros.containsKey(name);
	}

	/**
	 * Carries out {@code (setf (macro-function 'new) (macro-function 'existing))} -- a
	 * macro ALIAS, the shape lisp-namespace uses to give {@code namespace-let} the short
	 * name {@code nslet}. The new name is bound to the SAME expander, so the two names
	 * expand identically forever after (a later redefinition of either name replaces only
	 * that name's entry, as in CL, where the alias captured the function object).
	 *
	 * <p>
	 * Only this shape is supported: the macro namespace has no runtime representation on
	 * any backend (macros are gone before a backend sees the program), so there is no
	 * macro FUNCTION object to store -- a value that is not literally
	 * {@code (macro-function 'name)} of a {@code defmacro}-defined macro is rejected
	 * rather than silently dropped. Returns {@code null} when the form is some other
	 * {@code setf}, so the caller falls through to the ordinary place expansion.
	 * @param cons the setf form
	 * @return the alias name symbol, or {@code null} when the form is not a macro alias
	 */
	private @Nullable LispVal aliasMacroFunction(LispCons cons) {
		if (!LispMacroExpander.isSetfMacroFunctionForm(cons)) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		String alias = parts.size() == 3 ? LispMacroExpander.macroFunctionArgumentName(parts.get(1)) : null;
		String target = alias == null ? null : LispMacroExpander.macroFunctionArgumentName(parts.get(2));
		if (alias == null || target == null) {
			throw new LispEvalException("setf " + LispNames.MACRO_FUNCTION
					+ " only supports aliasing a user macro -- (setf (macro-function 'new) (macro-function 'existing)): "
					+ cons.print());
		}
		java.util.Map.Entry<String, UserMacro> macro = lookupUserMacro(target);
		if (macro == null) {
			throw new LispEvalException("setf " + LispNames.MACRO_FUNCTION + ": " + target
					+ " is not a user macro (only a defmacro-defined macro can be aliased)");
		}
		this.userMacros.put(alias, macro.getValue());
		return new LispSymbol(alias);
	}

	/**
	 * Looks a user macro up with the package tolerance a QUOTED name needs: quoted
	 * symbols are not package-resolved, so {@code 'namespace-let} must still find the
	 * {@code lispn::namespace-let} the {@code defmacro} registered (and vice versa).
	 * Exact spelling first, then the qualified spelling's member, then a UNIQUE member
	 * match -- the same ladder {@link #lookupSetfExpander} walks.
	 */
	private java.util.Map.@Nullable Entry<String, UserMacro> lookupUserMacro(String name) {
		UserMacro exact = this.userMacros.get(name);
		if (exact != null) {
			return java.util.Map.entry(name, exact);
		}
		if (PackageRegistry.splitQualified(name) instanceof PackageRegistry.QualifiedName qn) {
			UserMacro byMember = this.userMacros.get(qn.member());
			if (byMember != null) {
				return java.util.Map.entry(qn.member(), byMember);
			}
		}
		java.util.Map.Entry<String, UserMacro> found = null;
		for (var entry : this.userMacros.entrySet()) {
			if (PackageRegistry.splitQualified(entry.getKey()) instanceof PackageRegistry.QualifiedName eqn
					&& eqn.member().equals(name)) {
				if (found != null) {
					return null;
				}
				found = java.util.Map.entry(entry.getKey(), entry.getValue());
			}
		}
		return found;
	}

	/**
	 * Returns the body forms of the {@code (setf PLACE)} writer function installed by
	 * {@code (defun (setf PLACE) ...)}, or {@code null} if no such user-defined writer is
	 * registered. The compile-path macro expander ({@link UserMacroExpander}) uses this
	 * to judge whether a top-level {@code (setf (PLACE ...) V)} is a pure configuration
	 * setter safe to replay into this macro-time evaluator.
	 * @param place the resolved place name
	 * @return the writer's body forms, or {@code null}
	 */
	public @Nullable List<LispVal> setfWriterBody(String place) {
		return this.globalEnv
			.lookupFunctionOrNull(LispMacroExpander.setfFunctionName(place)) instanceof LispLambda lambda
					? lambda.body() : null;
	}

	/**
	 * Returns whether {@code name} designates a special variable (proclaimed by
	 * {@code defvar}/{@code defparameter}/{@code defconstant} or a {@code special}
	 * declaration) or an otherwise-bound global variable. Used to confirm that a
	 * candidate config setter mutates global configuration state rather than a lexical or
	 * a data structure.
	 * @param name the resolved variable name
	 * @return {@code true} if the name is a special or bound global variable
	 */
	public boolean isGlobalOrSpecialVariable(String name) {
		return this.specialVars.contains(name) || this.globalEnv.isBound(name);
	}

	/**
	 * Installs a lexical (macrolet) local macro into the user-macro table for the compile
	 * path ({@link am.ik.rontolisp.eval.UserMacroExpander} expands macrolet bodies by
	 * temporarily registering the locals here). The macro is defined in the global
	 * environment, matching {@link #evalMacrolet}. Returns the previously bound macro (or
	 * {@code null}) as an opaque token for {@link #popLocalMacro} to restore.
	 * @param name the local macro name
	 * @param paramForm the macro lambda list
	 * @param body the macro body forms
	 * @return the previous binding token (may be {@code null})
	 */
	public @Nullable Object pushLocalMacro(LispSymbol name, LispVal paramForm, List<LispVal> body) {
		UserMacro previous = this.userMacros.get(name.name());
		this.userMacros.put(name.name(), makeUserMacro(LispNames.MACROLET, name, paramForm, body, this.globalEnv));
		return previous;
	}

	/**
	 * Restores a user-macro binding saved by {@link #pushLocalMacro}. A {@code null}
	 * token means the name was unbound before and is removed again.
	 * @param name the local macro name
	 * @param previous the token returned by {@link #pushLocalMacro}
	 */
	public void popLocalMacro(String name, @Nullable Object previous) {
		if (previous instanceof UserMacro macro) {
			this.userMacros.put(name, macro);
		}
		else {
			this.userMacros.remove(name);
		}
	}

	/**
	 * Expands user-macro calls sitting in the place positions of a {@code setf} form
	 * (arguments 1, 3, 5, ...) until each place is no longer a user-macro call, so a
	 * {@code defmacro}-defined accessor is a valid setf place (CL's macroexpanding setf,
	 * limited to user macros; the compile path gets this for free from
	 * {@link UserMacroExpander} expanding the whole tree first).
	 * @param cons the setf form
	 * @return the setf form with macro places expanded
	 */
	private LispCons expandUserMacroPlaces(LispCons cons) {
		List<LispVal> parts = cons.toList();
		boolean changed = false;
		for (int i = 1; i + 1 < parts.size(); i += 2) {
			LispVal place = parts.get(i);
			while (place instanceof LispCons placeCons && placeCons.car() instanceof LispSymbol placeSym
					&& this.userMacros.containsKey(placeSym.name())) {
				place = expandUserMacro(placeCons);
				changed = true;
			}
			parts.set(i, place);
		}
		if (!changed) {
			return cons;
		}
		LispVal rebuilt = LispNil.INSTANCE;
		for (int i = parts.size() - 1; i >= 0; i--) {
			rebuilt = new LispCons(parts.get(i), rebuilt);
		}
		return (LispCons) rebuilt;
	}

	/**
	 * Registers a {@code (define-setf-expander name lambda-list body...)}: the lambda
	 * list (with {@code &environment} stripped, its variable bound to nil in the body)
	 * and body become the expander that {@link #setfExpansionFiveValues} runs to produce
	 * the five setf-expansion values.
	 * @param cons the define-setf-expander form
	 * @return the accessor name symbol
	 */
	private LispVal registerSetfExpander(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3 || !(parts.get(1) instanceof LispSymbol nameSym)) {
			throw new LispEvalException(
					LispNames.DEFINE_SETF_EXPANDER + " expects a name and lambda list: " + cons.print());
		}
		List<LispVal> llItems = parts.get(2) instanceof LispCons llCons ? llCons.toList() : List.of();
		String envVar = null;
		List<LispVal> cleaned = new ArrayList<>();
		for (int i = 0; i < llItems.size(); i++) {
			if (llItems.get(i) instanceof LispSymbol s && "&ENVIRONMENT".equals(s.name())) {
				if (i + 1 < llItems.size() && llItems.get(i + 1) instanceof LispSymbol e) {
					envVar = e.name();
				}
				i++;
				continue;
			}
			cleaned.add(llItems.get(i));
		}
		this.setfExpanders.put(nameSym.name(),
				new SetfExpanderForm(consList(cleaned), new ArrayList<>(parts.subList(3, parts.size())), envVar));
		return nameSym;
	}

	/**
	 * Registers a {@code defsetf} short form {@code (defsetf access update [doc])} or
	 * long form {@code (defsetf access (lambda-list) (store-vars) body...)}.
	 * @param cons the defsetf form
	 * @return the accessor name symbol
	 */
	private LispVal registerDefsetf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3 || !(parts.get(1) instanceof LispSymbol nameSym)) {
			throw new LispEvalException(LispNames.DEFSETF + " expects an accessor name: " + cons.print());
		}
		if (parts.get(2) instanceof LispSymbol updateSym) {
			// Short form: (setf (access a...) v) -> (update a... v).
			this.setfExpanders.put(nameSym.name(), new DefsetfShort(updateSym.name()));
			return nameSym;
		}
		if (parts.size() >= 5 && (parts.get(2) instanceof LispCons || parts.get(2) instanceof LispNil)
				&& (parts.get(3) instanceof LispCons || parts.get(3) instanceof LispNil)) {
			List<LispVal> argParams = parts.get(2) instanceof LispCons c ? c.toList() : List.of();
			List<LispVal> storeParams = parts.get(3) instanceof LispCons c ? c.toList() : List.of();
			this.setfExpanders.put(nameSym.name(),
					new DefsetfLong(argParams, storeParams, new ArrayList<>(parts.subList(4, parts.size()))));
			return nameSym;
		}
		throw new LispEvalException(LispNames.DEFSETF + " unsupported form: " + cons.print());
	}

	/**
	 * Whether {@code name} (or its package-stripped member) is a registered setf-expander
	 * accessor. Used by the compile-path macro expander to decide whether a setf place is
	 * a user place.
	 * @param name the accessor name as spelled at the call site
	 * @return true when a user setf expansion is registered for it
	 */
	public boolean hasSetfExpander(String name) {
		return lookupSetfExpander(name) != null;
	}

	@org.jspecify.annotations.Nullable
	private UserSetf lookupSetfExpander(String name) {
		UserSetf exact = this.setfExpanders.get(name);
		if (exact != null) {
			return exact;
		}
		if (PackageRegistry.splitQualified(name) instanceof PackageRegistry.QualifiedName qn) {
			UserSetf byMember = this.setfExpanders.get(qn.member());
			if (byMember != null) {
				return byMember;
			}
		}
		for (var entry : this.setfExpanders.entrySet()) {
			if (PackageRegistry.splitQualified(entry.getKey()) instanceof PackageRegistry.QualifiedName eqn
					&& eqn.member().equals(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Rewrites a {@code setf} whose place is a registered user expander into primitive
	 * forms; any other setf falls through to the shared static {@code expandSetf}.
	 * Multiple place/value pairs expand independently. Public so the compile-path
	 * macro-time evaluator reuses it.
	 * @param cons the setf form
	 * @return the expanded form
	 */
	public LispVal expandSetfMaybeUserExpander(LispCons cons) {
		if (this.setfExpanders.isEmpty()) {
			return LispMacroExpander.expandSetf(cons, this.structAccessors, this.closRegistry);
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() > 3 && parts.size() % 2 == 1) {
			List<LispVal> forms = new ArrayList<>();
			for (int i = 1; i + 1 < parts.size(); i += 2) {
				forms.add(expandSetfMaybeUserExpander(
						(LispCons) consList(List.of(new LispSymbol(LispNames.SETF), parts.get(i), parts.get(i + 1)))));
			}
			List<LispVal> progn = new ArrayList<>();
			progn.add(new LispSymbol(LispNames.PROGN));
			progn.addAll(forms);
			return consList(progn);
		}
		if (parts.size() == 3 && parts.get(1) instanceof LispCons place && place.car() instanceof LispSymbol accessor
				&& lookupSetfExpander(accessor.name()) != null) {
			return expandUserSetfPlace(place, parts.get(2));
		}
		return LispMacroExpander.expandSetf(cons, this.structAccessors, this.closRegistry);
	}

	/**
	 * Builds the primitive expansion of {@code (setf place value)} for a registered user
	 * place: binds the expander's temps to their value forms and the store variable to
	 * the new value, then evaluates the store form.
	 */
	private LispVal expandUserSetfPlace(LispCons place, LispVal value) {
		UserSetf entry = java.util.Objects.requireNonNull(lookupSetfExpander(((LispSymbol) place.car()).name()));
		List<LispVal> five = userPlaceFiveValues(place, entry);
		List<LispVal> temps = asJavaList(five.get(0));
		List<LispVal> values = asJavaList(five.get(1));
		List<LispVal> stores = asJavaList(five.get(2));
		LispVal storeForm = five.get(3);
		List<LispVal> bindings = new ArrayList<>();
		for (int i = 0; i < temps.size(); i++) {
			bindings.add(consList(List.of(temps.get(i), i < values.size() ? values.get(i) : LispNil.INSTANCE)));
		}
		if (stores.size() <= 1) {
			LispVal storeVar = stores.isEmpty() ? freshSetfTemp("new") : stores.get(0);
			bindings.add(consList(List.of(storeVar, value)));
			return letStarForm(bindings, List.of(storeForm));
		}
		// Multiple store variables: bind them from the value form's multiple values.
		List<LispVal> mvb = new ArrayList<>();
		mvb.add(new LispSymbol(LispNames.MULTIPLE_VALUE_BIND));
		mvb.add(consList(stores));
		mvb.add(value);
		mvb.add(storeForm);
		return letStarForm(bindings, List.of(consList(mvb)));
	}

	/**
	 * The five setf-expansion values
	 * ({@code [temps values stores store-form access-form]}, each a Lisp datum) of a
	 * registered user place. A {@code define-setf-expander} place runs its expander; a
	 * {@code defsetf} short/long place is expanded mechanically. The place head is always
	 * a registered accessor (the only caller is {@link #expandUserSetfPlace});
	 * {@code get-setf-expansion} on a plain symbol or a built-in place is handled by the
	 * Lisp-prelude definition instead.
	 */
	private List<LispVal> userPlaceFiveValues(LispCons place, UserSetf entry) {
		LispSymbol accessor = (LispSymbol) place.car();
		List<LispVal> args = place.cdr() instanceof LispCons c ? c.toList() : List.of();
		if (entry instanceof SetfExpanderForm exp) {
			return callSetfExpander(exp, args);
		}
		List<LispVal> temps = new ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			temps.add(freshSetfTemp("arg"));
		}
		if (entry instanceof DefsetfShort shortForm) {
			LispSymbol store = freshSetfTemp("new");
			List<LispVal> update = new ArrayList<>();
			update.add(new LispSymbol(shortForm.updateFn()));
			update.addAll(temps);
			update.add(store);
			return List.of(consList(temps), consList(new ArrayList<>(args)), consList(List.of(store)), consList(update),
					consList(prepend(accessor, temps)));
		}
		DefsetfLong lng = (DefsetfLong) entry;
		List<LispVal> storeSyms = new ArrayList<>();
		for (int i = 0; i < Math.max(1, lng.storeParams().size()); i++) {
			storeSyms.add(freshSetfTemp("new"));
		}
		LispVal storeForm = evalDefsetfLongBody(lng, temps, storeSyms);
		return List.of(consList(temps), consList(new ArrayList<>(args)), consList(storeSyms), storeForm,
				consList(prepend(accessor, temps)));
	}

	/**
	 * Runs a {@code define-setf-expander} body over the place-argument forms, returning
	 * its five values as a Java list. The expander is rebuilt as a lambda and applied to
	 * the quoted argument forms; {@code multiple-value-list} collects the five values.
	 */
	private List<LispVal> callSetfExpander(SetfExpanderForm exp, List<LispVal> args) {
		List<LispVal> lambdaForm = new ArrayList<>();
		lambdaForm.add(new LispSymbol(LispNames.LAMBDA));
		lambdaForm.add(exp.lambdaList());
		if (exp.envVar() != null) {
			List<LispVal> letForm = new ArrayList<>();
			letForm.add(new LispSymbol(LispNames.LET));
			letForm.add(consList(List.of(consList(List.of(new LispSymbol(exp.envVar()), LispNil.INSTANCE)))));
			letForm.addAll(exp.body());
			lambdaForm.add(consList(letForm));
		}
		else {
			lambdaForm.addAll(exp.body());
		}
		List<LispVal> call = new ArrayList<>();
		call.add(consList(lambdaForm));
		for (LispVal a : args) {
			call.add(quoteForm(a));
		}
		LispVal mvl = consList(List.of(new LispSymbol(LispNames.MULTIPLE_VALUE_LIST), consList(call)));
		List<LispVal> five = new ArrayList<>(asJavaList(eval(mvl, this.globalEnv)));
		while (five.size() < 5) {
			five.add(LispNil.INSTANCE);
		}
		return five;
	}

	/**
	 * Evaluates a {@code defsetf} long-form body with its params bound to the temp
	 * symbols.
	 */
	private LispVal evalDefsetfLongBody(DefsetfLong lng, List<LispVal> temps, List<LispVal> storeSyms) {
		List<LispVal> params = new ArrayList<>();
		for (LispVal p : lng.argParams()) {
			if (p instanceof LispSymbol s && "&ENVIRONMENT".equals(s.name())) {
				break;
			}
			params.add(p);
		}
		params.addAll(lng.storeParams());
		List<LispVal> lambdaForm = new ArrayList<>();
		lambdaForm.add(new LispSymbol(LispNames.LAMBDA));
		lambdaForm.add(consList(params));
		lambdaForm.addAll(lng.body());
		List<LispVal> call = new ArrayList<>();
		call.add(consList(lambdaForm));
		for (int i = 0; i < params.size(); i++) {
			LispVal arg = i < temps.size() ? temps.get(i) : storeSyms.get(i - temps.size());
			call.add(quoteForm(arg));
		}
		return eval(consList(call), this.globalEnv);
	}

	private LispSymbol freshSetfTemp(String prefix) {
		return new LispSymbol("__gse_" + prefix + this.setfTempCounter.incrementAndGet());
	}

	private static LispVal quoteForm(LispVal v) {
		return consList(List.of(new LispSymbol(LispNames.QUOTE), v));
	}

	private LispVal letStarForm(List<LispVal> bindings, List<LispVal> body) {
		List<LispVal> form = new ArrayList<>();
		form.add(new LispSymbol(LispNames.LET_STAR));
		form.add(bindings.isEmpty() ? LispNil.INSTANCE : consList(bindings));
		form.addAll(body);
		return consList(form);
	}

	private static List<LispVal> prepend(LispVal head, List<LispVal> tail) {
		List<LispVal> out = new ArrayList<>();
		out.add(head);
		out.addAll(tail);
		return out;
	}

	private static List<LispVal> asJavaList(LispVal v) {
		if (v instanceof LispCons cons) {
			return cons.toList();
		}
		if (v instanceof LispNil) {
			return List.of();
		}
		return List.of(v);
	}

	private static LispVal consList(List<LispVal> items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			result = new LispCons(items.get(i), result);
		}
		return result;
	}

	/**
	 * Expands a user macro call by one step: binds the unevaluated argument forms to the
	 * macro parameters and evaluates the macro body, returning the expansion form.
	 * @param form the macro call form; its operator must be a defined user macro
	 * @return the expansion
	 */
	public LispVal expandUserMacro(LispCons form) {
		String name = ((LispSymbol) form.car()).name();
		UserMacro macro = this.userMacros.get(name);
		if (macro == null) {
			throw new LispEvalException(name + " is not a user macro");
		}
		return expandMacroCall(name, macro, form);
	}

	/**
	 * Runs one macro expansion: binds the unevaluated argument forms to the macro's
	 * parameters and evaluates its body. Shared by {@code defmacro} expansion and
	 * {@link #expandCompilerMacro}, which differ only in which table the macro came from.
	 */
	private LispVal expandMacroCall(String name, UserMacro macro, LispCons form) {
		List<LispVal> args = form.cdr() instanceof LispCons argCons ? argCons.toList() : List.of();
		if (args.size() < macro.required().size() || (macro.rest() == null && args.size() > macro.required().size())) {
			throw new LispEvalException(
					"Macro " + name + " expects " + (macro.rest() == null ? String.valueOf(macro.required().size())
							: "at least " + macro.required().size()) + " arguments, got " + args.size());
		}
		Environment macroEnv = new Environment(macro.env());
		// A macro parameter named like a proclaimed special must also bind DYNAMICALLY:
		// symbol reads consult the dynamic store first, so a lexical binding would be
		// shadowed by an active dynamic binding of the same name and the macro body
		// would read that value instead of the argument form (cl-ppcre's
		// case-insensitive-mode-p has a parameter named flags, expanded while flags is
		// dynamically bound).
		List<String> dynamicParams = null;
		for (int i = 0; i < macro.required().size(); i++) {
			String paramName = macro.required().get(i).name();
			macroEnv.define(paramName, args.get(i));
			if (!this.specialVars.isEmpty() && this.specialVars.contains(paramName)) {
				this.dynamicBindings.push(paramName, args.get(i));
				dynamicParams = dynamicParams == null ? new ArrayList<>(2) : dynamicParams;
				dynamicParams.add(paramName);
			}
		}
		if (macro.rest() != null) {
			LispVal restList = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= macro.required().size(); i--) {
				restList = new LispCons(args.get(i), restList);
			}
			String restName = macro.rest().name();
			macroEnv.define(restName, restList);
			if (!this.specialVars.isEmpty() && this.specialVars.contains(restName)) {
				this.dynamicBindings.push(restName, restList);
				dynamicParams = dynamicParams == null ? new ArrayList<>(2) : dynamicParams;
				dynamicParams.add(restName);
			}
		}
		try {
			LispVal expansion = LispNil.INSTANCE;
			for (LispVal bodyExpr : macro.body()) {
				expansion = eval(bodyExpr, macroEnv);
			}
			return expansion;
		}
		finally {
			if (dynamicParams != null) {
				for (int i = dynamicParams.size() - 1; i >= 0; i--) {
					this.dynamicBindings.pop(dynamicParams.get(i));
				}
			}
		}
	}

	/**
	 * Expands the top-level form once when its operator is a user macro or a built-in
	 * macro ({@code macroexpand-1}). Subforms are not walked. Returns the form itself
	 * (same reference) when the operator is not a macro, so callers can detect
	 * non-expansion by identity.
	 * @param form the form to expand
	 * @return the expansion, or {@code form} unchanged
	 */
	public LispVal macroexpand1(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			if (isUserMacro(sym.name())) {
				return expandUserMacro(cons);
			}
			LispVal expanded = LispMacroExpander.expandBuiltinMacro(cons);
			if (expanded != null) {
				return expanded;
			}
		}
		return form;
	}

	/**
	 * Repeats {@link #macroexpand1(LispVal)} on the top-level form until it stops
	 * expanding ({@code macroexpand}).
	 * @param form the form to expand
	 * @return the full expansion of the top-level form
	 */
	public LispVal macroexpand(LispVal form) {
		LispVal expanded = macroexpand1(form);
		while (expanded != form) {
			form = expanded;
			expanded = macroexpand1(form);
		}
		return form;
	}

	private LispVal evalDefvar(LispCons cons, Environment env, boolean force) {
		List<LispVal> parts = cons.toList();
		LispSymbol name = (LispSymbol) parts.get(1);
		// defvar/defparameter/defconstant proclaim the name special: a later
		// let/let*/progv of it establishes a dynamic (not lexical) binding.
		this.specialVars.add(name.name());
		// defvar is idempotent (Common Lisp semantics): the initial value form is
		// evaluated and bound in the global environment only if the variable is not
		// already bound. (defvar name) with no value leaves it unbound. defparameter and
		// defconstant pass force=true and always (re)assign the initial value. The global
		// binding is the special's default value, seen whenever no dynamic binding is in
		// effect. Returns the variable name like Common Lisp.
		if (parts.size() > 2 && (force || !this.globalEnv.isBound(name.name()))) {
			this.globalEnv.define(name.name(), eval(parts.get(2), env));
		}
		return name;
	}

	private LispVal evalFunction(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new LispEvalException(LispNames.FUNCTION + " expects exactly one argument");
		}
		LispVal designator = parts.get(1);
		if (designator instanceof LispCons lambdaForm && lambdaForm.car() instanceof LispSymbol op
				&& LispNames.LAMBDA.equals(op.name())) {
			return evalLambdaForm(lambdaForm, env);
		}
		// #'(setf name): the writer function installed by (defun (setf name) ...).
		LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(designator);
		if (setfPlace != null) {
			return resolveFunction(LispMacroExpander.setfFunctionName(setfPlace.name()));
		}
		if (designator instanceof LispSymbol sym) {
			return resolveFunction(sym.name());
		}
		throw new LispEvalException(
				LispNames.FUNCTION + " expects a function name or lambda expression, got " + designator.print());
	}

	/**
	 * The runtime "package value" of a canonical package name: the upcased name as a
	 * keyword. See {@code find-package}.
	 */
	private static LispSymbol packageKeyword(String canonicalName) {
		return new LispSymbol(":" + canonicalName.toUpperCase(java.util.Locale.ROOT));
	}

	/**
	 * Coerces a runtime package designator -- a string, a keyword/symbol, or a package
	 * value (which IS a keyword here, see {@code find-package}) -- to the bare package
	 * name.
	 */
	private static String packageDesignator(String operator, LispVal val) {
		return switch (val) {
			case LispString str -> str.value();
			case LispSymbol sym -> LispSymbol.displayName(sym.name());
			// nil is the symbol named "NIL", so it designates a package by that name --
			// which no image has, so (find-package nil) is nil rather than a type error.
			case LispNil ignored -> "NIL";
			default -> throw new LispEvalException(operator + " expects a package designator, got " + val.print());
		};
	}

	private static void requireSingleArg(String name, List<LispVal> args) {
		if (args.size() != 1) {
			throw new LispEvalException(name + " expects 1 argument, got " + args.size());
		}
	}

	/** The first argument of an instance primitive, which must be an instance. */
	private static LispInstance requireInstance(String name, List<LispVal> args) {
		if (args.isEmpty() || !(args.get(0) instanceof LispInstance inst)) {
			throw new LispEvalException(
					name + " expects an instance, got " + (args.isEmpty() ? "no arguments" : args.get(0).print()));
		}
		return inst;
	}

	/** The second argument of an instance primitive: a 0-based slot index in range. */
	private static int requireSlotIndex(String name, LispInstance inst, List<LispVal> args) {
		if (args.size() < 2 || !(args.get(1) instanceof LispInteger idx)) {
			throw new LispEvalException(name + " expects a slot index");
		}
		long k = idx.value();
		if (k < 0 || k >= inst.slotCount()) {
			throw new LispEvalException(name + ": slot index " + k + " is outside " + inst.layout().tag());
		}
		return (int) k;
	}

	/**
	 * Rebuilds a literal {@code (error ...)}/{@code (signal ...)}/{@code (warn ...)}/
	 * {@code (format ...)} call from already-evaluated arguments, for the function values
	 * of those operators. Self-evaluating values (strings, numbers, keywords, characters)
	 * stay literal so the designator matchers see the same shape a source-level call has;
	 * anything else is quoted to survive re-evaluation.
	 */
	// (quote value) -- how an already-evaluated value is handed back to the evaluator.
	private static LispVal quotedValue(LispVal value) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
	}

	// The values as a Lisp list VALUE (not a form): the argument list %fmt-render walks.
	private static LispVal valueList(List<LispVal> values) {
		LispVal list = LispNil.INSTANCE;
		for (int i = values.size() - 1; i >= 0; i--) {
			list = new LispCons(values.get(i), list);
		}
		return list;
	}

	private static LispVal rebuildSignalForm(String opName, List<LispVal> args) {
		LispVal form = LispNil.INSTANCE;
		for (int i = args.size() - 1; i >= 0; i--) {
			LispVal a = args.get(i);
			boolean selfEvaluating = a instanceof LispString || a instanceof LispInteger || a instanceof LispBigInteger
					|| a instanceof LispRatio || a instanceof LispDouble || a instanceof LispChar
					|| a instanceof LispNil || a instanceof LispTrue
					|| (a instanceof LispSymbol sym && sym.isKeyword());
			LispVal wrapped = selfEvaluating ? a
					: new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(a, LispNil.INSTANCE));
			form = new LispCons(wrapped, form);
		}
		return new LispCons(new LispSymbol(opName), form);
	}

	/**
	 * Resolves a function designator name against the global function namespace.
	 * @param name the function name
	 * @return the function value
	 * @throws LispEvalException if the name is a special operator or macro, or undefined
	 */
	private LispVal resolveFunction(String name) {
		// A registered function value wins over the macro/special-operator guard:
		// some standard operators are BOTH lowered specially in call position and
		// real functions (error/signal/warn -- CL functions that cl-base64 reaches
		// via (apply #'error ...)).
		LispVal fn = this.globalEnv.lookupFunctionOrNull(name);
		if (fn != null) {
			return fn;
		}
		if (SPECIAL_OPERATORS.contains(name) || this.userMacros.containsKey(name)) {
			throw new LispEvalException(name + " is a macro or special operator, not a function");
		}
		// Everything below LOADS something into the shared global environment, so it runs
		// under the library lock: a concurrently served request must either see the load
		// finished or wait for it, never fall through a gate whose flag is already set
		// while the definitions it guards are still being evaluated (.todo/193).
		synchronized (this.libraryLoadLock) {
			// Another thread may have finished the load while this one waited.
			LispVal loadedElsewhere = this.globalEnv.lookupFunctionOrNull(name);
			if (loadedElsewhere != null) {
				return loadedElsewhere;
			}
			// The linalg package is a Lisp-source library (linalg.lisp): evaluate its
			// definitions into the global environment the first time a linalg:-qualified
			// function is resolved, then retry the lookup.
			if (!this.linalgLibraryLoaded && LinalgLibrary.isLinalgQualified(name)) {
				this.linalgLibraryLoaded = true;
				for (LispVal form : LinalgLibrary.forms()) {
					eval(form, this.globalEnv);
				}
				// Opt-in --simd: override the accelerated defuns just evaluated with the
				// Vector API natives. Each native captures the defun it replaces and
				// falls
				// back to it for the inputs it does not handle (general arrays, mixed
				// widths, shape errors), so linalg.lisp stays the single source of truth.
				if (this.simd) {
					LinalgSimd.install(this.globalEnv, this);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The vec package is a Lisp-source library (vec.lisp), the scalar reference
			// over the packed double-float array type: load it the same way on the first
			// resolution of a vec:-qualified function.
			if (!this.vecLibraryLoaded && VecLibrary.isVecQualified(name)) {
				this.vecLibraryLoaded = true;
				for (LispVal form : VecLibrary.forms()) {
					eval(form, this.globalEnv);
				}
				// Opt-in --simd: override the vectorizable defuns just evaluated with the
				// Vector API natives. mean/norm keep their scalar bodies and pick the
				// natives up through the global function namespace (Lisp-2).
				if (this.simd) {
					VecSimd.install(this.globalEnv);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The URL library (url.lisp) loads the same way on the first resolution of
			// one
			// of its public rontolisp: functions.
			if (!this.urlLibraryLoaded && UrlLibrary.isUrlFunction(name)) {
				this.urlLibraryLoaded = true;
				for (LispVal form : UrlLibrary.forms()) {
					eval(form, this.globalEnv);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The usocket package (usocket.lisp, the usocket-compatible shim over the
			// rontolisp:tcp-* built-ins) loads the same way on the first resolution of a
			// usocket:-qualified function.
			if (!this.usocketLibraryLoaded && UsocketLibrary.isUsocketQualified(name)) {
				ensureUsocketLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The WIT runtime (wit.lisp: the provider registry, rontolisp:wit-provide and
			// the
			// rontolisp:wit-error condition) loads on the first resolution of one of its
			// names, so a program may bind a provider before the wit-import directive
			// that
			// uses it.
			if (!this.witLibraryLoaded && WitLibrary.isWitRuntimeName(name)) {
				ensureWitLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// equalp/string< are recursive rontolisp-source defuns (LispPreludeLibrary),
			// loaded on first resolution like the linalg/url libraries.
			if (LispPreludeLibrary.isPreludeFunction(name) && this.loadedPreludeNames.add(name)) {
				for (LispVal form : LispPreludeLibrary.formsFor(name)) {
					eval(form, this.globalEnv);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// %slot-read / %slot-bound-p are the out-of-line halves of the
			// boundness-checked
			// slot reads. They are GENERATED, not written anywhere, so the compile path
			// emits
			// their defuns into the program and the interpreter defines them here, from
			// the
			// same AST.
			if ((LispNames.SLOT_READ_INTERNAL.equals(name) || LispNames.SLOT_BOUND_P_INTERNAL.equals(name))
					&& this.loadedPreludeNames.add(name)) {
				for (LispVal form : LispMacroExpander.slotUnboundDefuns()) {
					eval(form, this.globalEnv);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The condition-report renderer is GENERATED like the slot helpers: a
			// resolution
			// that reaches it before any condition form ran loads the same defuns the
			// compile path injects, rather than failing.
			if (LispNames.CONDITION_REPORT_STR_INTERNAL.equals(name)
					|| LispNames.FORMAT_CONDITION_INTERNAL.equals(name)) {
				ensureConditionReportRuntimeLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The restart runtime (find-restart/invoke-restart/... and %run-handlers) is
			// GENERATED the same way: the compile path injects the defuns via
			// expandTopLevelDefinitions, the interpreter evaluates the same AST on the
			// first resolution of one of the names (or on the first restart-system form,
			// see ensureRestartRuntimeLoaded).
			if (!this.restartRuntimeLoaded && (LispMacroExpander.RESTART_RUNTIME_FUNCTION_NAMES.contains(name)
					|| LispNames.RUN_HANDLERS_INTERNAL.equals(name))) {
				ensureRestartRuntimeLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The runtime format renderer is Lisp source the compile path injects as
			// defuns; the interpreter evaluates the same forms on the first resolution of
			// one of its names (a runtime-control format call, #'format, ~?, a condition
			// report). The prefix test keeps an ordinary undefined name from parsing it.
			if (!this.formatRendererLoaded && name.startsWith(FormatRenderer.NAME_PREFIX)
					&& FormatRenderer.definesFunction(name)) {
				ensureFormatRendererLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			if (LispNames.isCarCdrComposition(name)) {
				// Synthesize (lambda (x) (cadr x)) so car/cdr compositions are
				// first-class.
				LispSymbol param = new LispSymbol("x");
				LispVal call = new LispCons(new LispSymbol(name), new LispCons(param, LispNil.INSTANCE));
				return new LispLambda(List.of(param), List.of(call), this.globalEnv);
			}
			throw new LispEvalException("The function " + name + " is undefined");
		}
	}

	/**
	 * Evaluates the generated condition-report runtime ({@code %condition-report-str} and
	 * {@code %format-condition}) into the global environment, and re-evaluates it
	 * whenever a later {@code define-condition} has changed the registry it partitions --
	 * the compile path emits it once because {@code expandTopLevelDefinitions} runs with
	 * a complete registry, which the interpreter never has. Called from the condition
	 * forms (which is where the routing turns ON: before one of them runs, no condition
	 * value can exist and every printing operator stays in its historical shape) and from
	 * the printing operators once it is on.
	 */
	private void ensureConditionReportRuntimeLoaded() {
		synchronized (this.libraryLoadLock) {
			int stamp = this.closRegistry.classes().size() * 31 + this.closRegistry.conditionReports().size();
			if (stamp == this.conditionReportRuntimeStamp) {
				return;
			}
			this.conditionReportRuntimeStamp = stamp;
			this.closRegistry.setRoutesConditionReports(true);
			for (LispVal form : LispMacroExpander.conditionReportDefuns(this.closRegistry)) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * Evaluates the runtime format renderer into the global environment, once per
	 * evaluator. The forms are the ones {@code expandTopLevelDefinitions} injects on the
	 * compile path, so the interpreter and every compiled backend render a runtime
	 * control string with the same code.
	 */
	private void ensureFormatRendererLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.formatRendererLoaded) {
				return;
			}
			this.formatRendererLoaded = true;
			for (LispVal form : FormatRenderer.defuns()) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * Evaluates the generated restart-runtime forms (the two stack globals plus the
	 * restart defuns) into the global environment, once per evaluator -- the
	 * {@code slotUnboundDefuns} pattern. Called by the restart-system special-form cases
	 * BEFORE their expansion is evaluated (the expansions read the stack globals) and by
	 * {@code resolveFunction} on the first resolution of a restart-runtime function name.
	 * A name the program already defined itself is skipped, so a user redefinition wins
	 * like it does on the compile path.
	 */
	private void ensureRestartRuntimeLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.restartRuntimeLoaded) {
				return;
			}
			this.restartRuntimeLoaded = true;
			for (LispVal form : LispMacroExpander.restartRuntimeGlobalForms()) {
				eval(form, this.globalEnv);
			}
			java.util.Set<String> userDefinedNames = new java.util.HashSet<>();
			for (String name : LispMacroExpander.RESTART_RUNTIME_FUNCTION_NAMES) {
				if (this.globalEnv.lookupFunctionOrNull(name) != null) {
					userDefinedNames.add(name);
				}
			}
			for (LispVal form : LispMacroExpander.restartRuntimeDefunForms(userDefinedNames)) {
				eval(form, this.globalEnv);
			}
		}
	}

	private LispVal evalQuote(LispCons cons) {
		LispCons rest = (LispCons) cons.cdr();
		return rest.car();
	}

	/**
	 * Loads the prelude entry of every {@code (setf (PLACE ...) v)} place head that is a
	 * prelude function not yet loaded, so a prelude-provided {@code (defun (setf PLACE)
	 * ...)} writer (the {@code get} entry) registers its place before the setf expansion
	 * resolves it.
	 */
	private void ensurePreludeSetfPlacesLoaded(LispCons cons) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i + 1 < parts.size(); i += 2) {
			if (parts.get(i) instanceof LispCons placeCons && placeCons.car() instanceof LispSymbol head
					&& LispPreludeLibrary.isPreludeFunction(head.name()) && this.loadedPreludeNames.add(head.name())) {
				for (LispVal form : LispPreludeLibrary.formsFor(head.name())) {
					eval(form, this.globalEnv);
				}
			}
		}
	}

	/**
	 * Evaluates a {@code deftype}: the zero-parameter quoted-literal shape registers
	 * through the shared expander; a parameterized or computed one additionally evaluates
	 * its body with every lambda-list parameter bound to its default (or {@code *}, CL's
	 * unsupplied-deftype-argument value) and registers the resulting specifier -- the
	 * bare-name use ironclad's {@code simple-octet-vector} sees in
	 * {@code etypecase}/{@code check-type}. A body that genuinely needs its arguments (or
	 * fails to evaluate) stays an unresolved specifier, as before.
	 */
	private LispVal evalDeftype(LispCons cons) {
		foldDeftype(cons);
		return LispNil.INSTANCE;
	}

	/**
	 * Registers a {@code deftype}: the zero-parameter quoted-literal shape goes through
	 * the shared expander; a parameterized or computed one additionally evaluates its
	 * body with every lambda-list parameter bound to its default (or {@code *}, CL's
	 * unsupplied-deftype-argument value) and registers the resulting specifier -- the
	 * bare-name use ironclad's {@code simple-octet-vector} gets in
	 * {@code etypecase}/{@code check-type}. A body that genuinely needs its arguments (or
	 * fails to evaluate) stays an unresolved specifier, as before. Returns the folded
	 * specifier so the compile-path pre-pass ({@code UserMacroExpander}) can emit the
	 * equivalent zero-parameter deftype, which is the shape the compilers' own registry
	 * pass understands.
	 * @param cons the deftype form (canonical, package-resolved spelling)
	 * @return the folded literal type specifier, or null when nothing was folded here
	 */
	@org.jspecify.annotations.Nullable
	public LispVal foldDeftype(LispCons cons) {
		LispMacroExpander.expandDeftype(cons, this.closRegistry);
		List<LispVal> parts = cons.toList();
		if (parts.size() >= 4 && parts.get(1) instanceof LispSymbol nameSym
				&& this.closRegistry.findDeftype(nameSym.name()) == null) {
			LispVal quotedStar = consListOf(List.of(new LispSymbol(LispNames.QUOTE), new LispSymbol("*")));
			List<LispVal> bindings = new java.util.ArrayList<>();
			LispVal cur = parts.get(2);
			while (cur instanceof LispCons c) {
				LispVal item = c.car();
				if (item instanceof LispSymbol p && !p.name().startsWith("&")) {
					bindings.add(consListOf(List.of(p, quotedStar)));
				}
				else if (item instanceof LispCons pc && pc.car() instanceof LispSymbol p) {
					List<LispVal> pcParts = pc.toList();
					bindings.add(consListOf(List.of(p, pcParts.size() > 1 ? pcParts.get(1) : quotedStar)));
				}
				cur = c.cdr();
			}
			List<LispVal> body = new java.util.ArrayList<>(parts.subList(3, parts.size()));
			if (body.size() > 1 && body.get(0) instanceof LispString) {
				body.remove(0);
			}
			List<LispVal> letParts = new java.util.ArrayList<>();
			letParts.add(new LispSymbol(LispNames.LET_STAR));
			letParts.add(bindings.isEmpty() ? LispNil.INSTANCE : consListOf(bindings));
			letParts.addAll(body);
			try {
				LispVal spec = eval(consListOf(letParts), this.globalEnv);
				if (spec instanceof LispSymbol || spec instanceof LispCons) {
					this.closRegistry.registerDeftype(nameSym.name(), spec);
					return spec;
				}
			}
			catch (RuntimeException bodyNeedsArguments) {
				// Left unresolved: typep/typecase of the name still errors, as before.
			}
		}
		return null;
	}

	/**
	 * Pre-expands user macros (macrolet included) inside an {@code flet}/{@code labels}
	 * form when its body contains a {@code macrolet}: the flet expansion rewrites its
	 * local-function CALL SITES textually, so a call site that only appears after a
	 * nested macrolet expands (ironclad's {@code sha256-round} calling the flet-bound
	 * {@code sigma1}) must be materialized first or it misses the rewrite. The common
	 * macrolet-free case skips the walk.
	 */
	private LispCons preExpandLocalMacros(LispCons cons) {
		if (!treeContainsMacrolet(cons)) {
			return cons;
		}
		return UserMacroExpander.expandAll(cons, this) instanceof LispCons expanded ? expanded : cons;
	}

	private static boolean treeContainsMacrolet(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.MACROLET.equals(sym.name())) {
			return true;
		}
		return treeContainsMacrolet(cons.car()) || treeContainsMacrolet(cons.cdr());
	}

	/** Builds a proper list from the given elements. */
	private static LispVal consListOf(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

	private LispVal evalIf(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal condition = eval(parts.get(1), env);
		if (isTruthy(condition)) {
			return eval(parts.get(2), env);
		}
		if (parts.size() > 3) {
			return eval(parts.get(3), env);
		}
		return LispNil.INSTANCE;
	}

	private LispVal evalLet(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		Environment letEnv = new Environment(env);
		// parts.get(1) is the bindings list: ((x 1) (y 2)); a bare symbol entry is
		// an init-less binding to nil.
		LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
		// A special name gets a thread-scoped dynamic binding instead of a lexical one;
		// dynamicNames records those to pop on any exit (left null on the common
		// all-lexical
		// path so there is no per-let allocation or finally cost).
		List<String> dynamicNames = null;
		String savedPackage = null;
		if (bindings instanceof LispCons bindingsCons) {
			List<LispVal> bindingList = bindingsCons.toList();
			if (this.specialVars.isEmpty()) {
				// No name can be special: bind every init lexically. let is parallel, so
				// each init is evaluated in the OUTER env before the binding takes
				// effect.
				for (LispVal binding : bindingList) {
					List<LispVal> pair = ((LispCons) binding).toList();
					String bindingName = ((LispSymbol) pair.get(0)).name();
					LispVal bindingValue = eval(pair.get(1), env);
					if (LispMacroExpander.PACKAGE_REBIND_VAR.equals(bindingName) && savedPackage == null) {
						savedPackage = rebindCurrentPackage(bindingValue);
					}
					letEnv.define(bindingName, bindingValue);
				}
			}
			else {
				// Some names may be special: evaluate ALL inits in the outer env first
				// (parallel let -- a later init must not see an earlier binding in the
				// same
				// let), then establish the lexical and dynamic bindings.
				int n = bindingList.size();
				String[] names = new String[n];
				LispVal[] vals = new LispVal[n];
				for (int i = 0; i < n; i++) {
					List<LispVal> pair = ((LispCons) bindingList.get(i)).toList();
					names[i] = ((LispSymbol) pair.get(0)).name();
					vals[i] = eval(pair.get(1), env);
					if (LispMacroExpander.PACKAGE_REBIND_VAR.equals(names[i]) && savedPackage == null) {
						savedPackage = rebindCurrentPackage(vals[i]);
					}
				}
				for (int i = 0; i < n; i++) {
					// A special name is ALSO defined lexically with the same value (dual
					// binding): a closure built in the body and called after the dynamic
					// extent pops must still see the bound value -- CL gets this via a
					// lexical rebinding shadowing the special (a free (declare (special
					// x)) does not affect an inner LET binding of x), which the
					// pessimistic program-wide special set cannot distinguish
					// (cl-ppcre's matcher closures capture end-string this way). The
					// dual binding diverges only under setq, which updates the dynamic
					// side alone.
					letEnv.define(names[i], vals[i]);
					if (this.specialVars.contains(names[i])) {
						if (dynamicNames == null) {
							dynamicNames = new java.util.ArrayList<>(2);
						}
						this.dynamicBindings.push(names[i], vals[i]);
						dynamicNames.add(names[i]);
					}
				}
			}
		}
		try {
			LispVal result = LispNil.INSTANCE;
			for (int i = 2; i < parts.size(); i++) {
				result = eval(parts.get(i), letEnv);
			}
			return result;
		}
		finally {
			// Restore on ANY exit: normal return, a non-local exit (LispReturnSignal), or
			// an error unwind (LispEvalException) -- both are unchecked, so finally
			// fires.
			if (dynamicNames != null) {
				for (int i = dynamicNames.size() - 1; i >= 0; i--) {
					this.dynamicBindings.pop(dynamicNames.get(i));
				}
			}
			if (savedPackage != null) {
				this.packageResolver.setCurrentPackage(savedPackage);
			}
		}
	}

	/**
	 * The runtime half of a {@code (let ((*package* X)) ...)} rebinding (see
	 * {@code LispMacroExpander.PACKAGE_REBIND_VAR}): swaps the resolver's current package
	 * to the bound value for the let's extent so a macro-time {@code (intern ...)} homes
	 * where CL would. Returns the saved package name, or null when the value is not a
	 * package designator (the binding then has no effect).
	 */
	@org.jspecify.annotations.Nullable
	private String rebindCurrentPackage(LispVal value) {
		String designator = switch (value) {
			case LispString str -> str.value();
			case LispSymbol sym -> LispSymbol.displayName(sym.name());
			default -> null;
		};
		if (designator == null) {
			return null;
		}
		String found = this.packageResolver.findPackageName(designator);
		if (found == null) {
			return null;
		}
		String saved = this.packageResolver.currentPackageName();
		this.packageResolver.setCurrentPackage(found);
		return saved;
	}

	/**
	 * Evaluates {@code (progv symbols values body...)}: binds each symbol in the
	 * runtime-computed {@code symbols} list dynamically to the corresponding value in
	 * {@code values} (nil when the values list is shorter), for the extent of the body,
	 * restored on any exit. Unlike {@code let}, the bound symbols need not have been
	 * proclaimed special and are not permanently marked special.
	 */
	private LispVal evalProgv(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3) {
			throw new LispEvalException(LispNames.PROGV + " expects a symbols list, a values list, and a body");
		}
		List<LispVal> symbols = properListElements(eval(parts.get(1), env), LispNames.PROGV);
		List<LispVal> values = properListElements(eval(parts.get(2), env), LispNames.PROGV);
		// From here on, variable reads must consult the dynamic store even for names that
		// were never declared special (progv can bind an arbitrary symbol dynamically).
		this.progvUsed = true;
		List<String> pushed = new java.util.ArrayList<>(symbols.size());
		try {
			for (int i = 0; i < symbols.size(); i++) {
				if (!(symbols.get(i) instanceof LispSymbol sym)) {
					throw new LispEvalException(
							LispNames.PROGV + " expects a list of symbols, got " + symbols.get(i).print());
				}
				LispVal value = i < values.size() ? values.get(i) : LispNil.INSTANCE;
				this.dynamicBindings.push(sym.name(), value);
				pushed.add(sym.name());
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = 3; i < parts.size(); i++) {
				result = eval(parts.get(i), env);
			}
			return result;
		}
		finally {
			for (int i = pushed.size() - 1; i >= 0; i--) {
				this.dynamicBindings.pop(pushed.get(i));
			}
		}
	}

	/** Returns the elements of a proper list value ({@code nil} or a cons chain). */
	private static List<LispVal> properListElements(LispVal value, String operator) {
		if (value instanceof LispNil) {
			return List.of();
		}
		if (value instanceof LispCons cons) {
			return cons.toList();
		}
		throw new LispEvalException(operator + " expects a list, got " + value.print());
	}

	private LispVal evalProgn(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal result = LispNil.INSTANCE;
		for (int i = 1; i < parts.size(); i++) {
			result = eval(parts.get(i), env);
		}
		return result;
	}

	private LispVal evalSetq(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if ((parts.size() - 1) % 2 != 0) {
			throw new IllegalArgumentException("setq requires an even number of arguments");
		}
		LispVal value = LispNil.INSTANCE;
		for (int i = 1; i < parts.size(); i += 2) {
			LispSymbol name = (LispSymbol) parts.get(i);
			value = eval(parts.get(i + 1), env);
			String n = name.name();
			// A special with an active dynamic binding is assigned in that binding
			// (visible to callees within the extent); otherwise env.set walks to the
			// global default (a special never has a lexical binding to shadow).
			if ((!this.specialVars.isEmpty() || this.progvUsed) && this.dynamicBindings.isBound(n)) {
				this.dynamicBindings.setCurrent(n, value);
			}
			else {
				env.set(n, value);
			}
		}
		return value;
	}

	private LispVal evalWhile(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal test = parts.get(1);
		while (isTruthy(eval(test, env))) {
			for (int i = 2; i < parts.size(); i++) {
				eval(parts.get(i), env);
			}
		}
		return LispNil.INSTANCE;
	}

	/**
	 * Evaluates the internal {@code %block} return boundary: runs the body normally, but
	 * if a {@code return} fires inside it, yields the returned value instead.
	 */
	private LispVal evalBlock(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		try {
			LispVal result = LispNil.INSTANCE;
			for (int i = 1; i < parts.size(); i++) {
				result = eval(parts.get(i), env);
			}
			return result;
		}
		catch (LispReturnSignal signal) {
			return signal.value();
		}
	}

	/**
	 * Evaluates a user {@code (block name body...)}: runs the body and yields the value
	 * of a matching {@code (return-from name value)} fired inside its dynamic extent. A
	 * non-matching named signal propagates (an outer block catches it); {@code (block
	 * nil ...)} additionally catches plain {@code return}, mirroring the implicit
	 * {@code nil} block the loop macros establish.
	 */
	private LispVal evalNamedBlock(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new LispEvalException(LispNames.BLOCK + " expects a block name");
		}
		String name = blockName(parts.get(1));
		try {
			LispVal result = LispNil.INSTANCE;
			for (int i = 2; i < parts.size(); i++) {
				result = eval(parts.get(i), env);
			}
			return result;
		}
		catch (BlockReturnSignal signal) {
			if (signal.name().equals(name)) {
				return signal.value();
			}
			throw signal;
		}
		catch (LispReturnSignal signal) {
			if (name == null) {
				return signal.value();
			}
			throw signal;
		}
	}

	/**
	 * Evaluates {@code (return-from name [value])}: throws the named non-local exit
	 * caught by the matching {@code block}. {@code (return-from nil v)} is plain
	 * {@code return} (the loop macros' implicit block), so it throws the unnamed signal
	 * the {@code %block} boundaries catch.
	 */
	private LispVal evalReturnFrom(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			throw new LispEvalException(LispNames.RETURN_FROM + " expects (return-from name [value])");
		}
		LispVal value = parts.size() == 3 ? eval(parts.get(2), env) : LispNil.INSTANCE;
		String name = blockName(parts.get(1));
		if (name == null) {
			throw new LispReturnSignal(value);
		}
		throw new BlockReturnSignal(name, value);
	}

	/**
	 * Evaluates {@code (catch tag body...)}: evaluates the tag ONCE, runs the body as an
	 * implicit {@code progn} and yields its value -- unless a {@code throw} to an
	 * {@code eq} tag fires within the body's dynamic extent, in which case the thrown
	 * value becomes the form's value. A throw to a different tag propagates, so the
	 * innermost matching catcher wins.
	 */
	private LispVal evalCatch(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new LispEvalException(LispNames.CATCH + " expects (catch tag body...)");
		}
		LispVal tag = eval(parts.get(1), env);
		try {
			LispVal result = LispNil.INSTANCE;
			for (int i = 2; i < parts.size(); i++) {
				result = eval(parts.get(i), env);
			}
			return result;
		}
		catch (ThrowSignal signal) {
			if (Environment.isEqStrict(signal.tag(), tag)) {
				return signal.value();
			}
			throw signal;
		}
	}

	/**
	 * Evaluates {@code (throw tag [result])}: throws the dynamic non-local exit the
	 * matching {@code catch} yields. CL requires the result form; it defaults to
	 * {@code nil} here for symmetry with {@code return-from}.
	 */
	private LispVal evalThrow(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			throw new LispEvalException(LispNames.THROW + " expects (throw tag result)");
		}
		LispVal tag = eval(parts.get(1), env);
		LispVal value = parts.size() == 3 ? eval(parts.get(2), env) : LispNil.INSTANCE;
		throw new ThrowSignal(tag, value);
	}

	/** The block name a designator form denotes: null for the {@code nil} block. */
	@org.jspecify.annotations.Nullable
	private static String blockName(LispVal designator) {
		if (designator instanceof LispNil) {
			return null;
		}
		if (designator instanceof LispSymbol sym && !sym.isKeyword()) {
			return "NIL".equals(sym.name()) ? null : sym.name();
		}
		throw new LispEvalException(LispNames.BLOCK + ": block name must be a symbol, got " + designator.print());
	}

	/**
	 * The clause type specifiers of every {@code handler-case} established on the current
	 * thread of control, innermost last. {@code signal} raises its condition only when
	 * some active clause TYPE actually matches it and falls through to nil otherwise --
	 * the CL contract: an active handler-case for an unrelated type must not turn a
	 * signal into an unwind (trivia level2's pattern expander signals its own
	 * wildcard/guard-pattern conditions inside user handler-case bodies). Thread-scoped
	 * for the same reason as {@link DynamicBindings}.
	 */
	private final ThreadLocal<ArrayDeque<List<LispVal>>> handlerCaseTypes = ThreadLocal.withInitial(ArrayDeque::new);

	/**
	 * Evaluates {@code (handler-case expr (type ([var]) body...)... [(:no-error ([var])
	 * body...)])}: the expression runs with a handler established; an error signaled
	 * during it ({@link LispEvalException}) is dispatched to the first clause whose
	 * condition type matches the carried condition (a plain error synthesizes a
	 * {@code simple-error} instance from the message) and rethrown when none does. The
	 * {@code :no-error} clause (at most one variable -- the primary value, multiple
	 * values being syntactic) runs on normal completion, outside the handler. A
	 * {@code return}/{@code return-from} non-local exit ({@link LispReturnSignal}) passes
	 * through uncaught.
	 */
	private LispVal evalHandlerCase(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new LispEvalException(LispNames.HANDLER_CASE + " expects an expression");
		}
		List<LispVal> errorClauses = new ArrayList<>();
		LispCons noErrorClause = null;
		for (int i = 2; i < parts.size(); i++) {
			if (!(parts.get(i) instanceof LispCons clause) || !(clause.cdr() instanceof LispCons)) {
				throw new LispEvalException(
						LispNames.HANDLER_CASE + " expects (type (var) body...) clauses: " + parts.get(i).print());
			}
			if (clause.car() instanceof LispSymbol head && ":NO-ERROR".equals(head.name())) {
				noErrorClause = clause;
			}
			else {
				errorClauses.add(clause);
			}
		}
		LispVal value;
		try {
			List<LispVal> clauseTypes = new ArrayList<>(errorClauses.size());
			for (LispVal clauseVal : errorClauses) {
				clauseTypes.add(((LispCons) clauseVal).car());
			}
			ArrayDeque<List<LispVal>> frames = this.handlerCaseTypes.get();
			frames.addLast(clauseTypes);
			try {
				value = eval(parts.get(1), env);
			}
			finally {
				frames.removeLast();
			}
		}
		catch (LispEvalException e) {
			LispVal condition = e.condition() != null ? e.condition() : synthesizeSimpleError(e.getMessage());
			for (LispVal clauseVal : errorClauses) {
				LispCons clause = (LispCons) clauseVal;
				List<LispVal> clauseParts = clause.toList();
				Environment clauseEnv = new Environment(env);
				LispSymbol condTemp = new LispSymbol("__handler_case_cond");
				clauseEnv.define(condTemp.name(), condition);
				LispVal test = LispMacroExpander.makeHandlerTypeTest(condTemp, clauseParts.get(0), this.closRegistry);
				if (eval(test, clauseEnv) == LispNil.INSTANCE) {
					continue;
				}
				if (clauseParts.get(1) instanceof LispCons varList && varList.car() instanceof LispSymbol var) {
					clauseEnv.define(var.name(), condition);
				}
				LispVal result = LispNil.INSTANCE;
				for (int i = 2; i < clauseParts.size(); i++) {
					result = eval(clauseParts.get(i), clauseEnv);
				}
				return result;
			}
			throw e;
		}
		if (noErrorClause != null) {
			List<LispVal> clauseParts = noErrorClause.toList();
			Environment clauseEnv = new Environment(env);
			if (clauseParts.get(1) instanceof LispCons varList && varList.car() instanceof LispSymbol var) {
				clauseEnv.define(var.name(), value);
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = 2; i < clauseParts.size(); i++) {
				result = eval(clauseParts.get(i), clauseEnv);
			}
			return result;
		}
		return value;
	}

	/**
	 * Builds the {@code simple-error} instance a {@code handler-case} synthesizes for an
	 * error that was signaled without a condition object (a plain {@code %error}, or a
	 * runtime failure inside a built-in).
	 */
	private LispVal synthesizeSimpleError(@Nullable String message) {
		LispVal messageVal = message == null ? LispNil.INSTANCE : new LispString(message);
		LispLayout layout = java.util.Objects
			.requireNonNull(this.closRegistry.findLayoutByTag(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-ERROR"));
		return new LispInstance(layout, new LispVal[] { messageVal, LispNil.INSTANCE });
	}

	/**
	 * Evaluates the internal {@code (%signal-cond condition message)} primitive behind
	 * {@code signal}: raises the condition as a {@link LispEvalException} when a
	 * {@code handler-case} handler is established, and returns nil otherwise.
	 */
	private LispVal evalSignalCond(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new LispEvalException(LispNames.SIGNAL_COND_INTERNAL + " expects a condition and a message");
		}
		LispVal condition = eval(parts.get(1), env);
		LispVal message = eval(parts.get(2), env);
		if (anyHandlerCaseMatches(condition)) {
			throw new LispEvalException(message instanceof am.ik.rontolisp.LispString s ? s.value() : message.display(),
					condition);
		}
		return LispNil.INSTANCE;
	}

	/**
	 * Whether the (canonical-spelling) name is defined in any global namespace --
	 * function, macro, or variable. The find-symbol "a definition is an interning" probe:
	 * definitions register here, never in the package registry.
	 */
	private boolean definedInImage(String name) {
		return this.userMacros.containsKey(name) || this.globalEnv.lookupFunctionOrNull(name) != null
				|| this.globalEnv.hasBinding(name);
	}

	/**
	 * Whether any active {@code handler-case} clause type matches the condition -- the
	 * same test {@link #evalHandlerCase} applies when catching, run at the SIGNAL point
	 * so an unmatched signal can return nil without unwinding (CL: {@code signal} only
	 * unwinds to a handler that will handle it).
	 */
	private boolean anyHandlerCaseMatches(LispVal condition) {
		ArrayDeque<List<LispVal>> frames = this.handlerCaseTypes.get();
		if (frames.isEmpty()) {
			return false;
		}
		Environment testEnv = new Environment(this.globalEnv);
		LispSymbol condTemp = new LispSymbol("__signal_cond");
		testEnv.define(condTemp.name(), condition);
		for (List<LispVal> clauseTypes : frames) {
			for (LispVal typeSpec : clauseTypes) {
				LispVal test = LispMacroExpander.makeHandlerTypeTest(condTemp, typeSpec, this.closRegistry);
				if (eval(test, testEnv) != LispNil.INSTANCE) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Evaluates {@code (unwind-protect protected cleanup...)}: the cleanup forms run on
	 * every exit from the protected form -- normal return, an error unwind
	 * ({@link LispEvalException}) and a {@code return}/{@code return-from} non-local exit
	 * ({@link LispReturnSignal}). A cleanup form that itself signals replaces the pending
	 * unwind (CL semantics: the newer exit wins), which is exactly what a Java
	 * {@code finally} does.
	 */
	private LispVal evalUnwindProtect(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new LispEvalException(LispNames.UNWIND_PROTECT + " expects a protected form");
		}
		try {
			return eval(parts.get(1), env);
		}
		finally {
			for (int i = 2; i < parts.size(); i++) {
				eval(parts.get(i), env);
			}
		}
	}

	/** Evaluates the optional value of a {@code return} form, defaulting to nil. */
	private LispVal evalReturnValue(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		return parts.size() > 1 ? eval(parts.get(1), env) : LispNil.INSTANCE;
	}

	private LispVal evalLambdaForm(LispCons cons, Environment env) {
		checkAwaitPlacement(cons);
		List<LispVal> parts = cons.toList();
		// No lite return-from rewrite (and no block wrap): CL lambdas establish no
		// block, so a (return-from f v) inside a lambda called within f's dynamic
		// extent exits F -- the named signal propagates through the call.
		LambdaLists.Expanded expanded = LambdaLists.expand(parts.get(1), parts.subList(2, parts.size()), false);
		return new LispLambda(expanded.required(), expanded.rest(), expanded.body(), env);
	}

	// The map* family (mapcar/mapc/mapcan/maplist/mapcon) operates on lists; passing a
	// non-list (e.g. a string) signals an error rather than silently behaving like the
	// empty list, which would hide a caller's mistake. nil is a valid empty list. For
	// mapping over a string or vector, use the generic map.
	private void requireList(String name, LispVal value) {
		if (!(value instanceof LispNil) && !(value instanceof LispCons)) {
			throw new LispEvalException(
					name + ": argument is not a list: " + value.print() + " (use map for strings/vectors)");
		}
	}

	// Validates a map* family call's arguments -- a function designator plus at least one
	// list, every one of which must be a list -- and returns just the lists. Every member
	// of the family takes N lists in Common Lisp, so the arity check is a lower bound for
	// all of them, not just mapcar.
	private List<LispVal> requireMapLists(String name, List<LispVal> args) {
		if (args.size() < 2) {
			throw new LispEvalException(name + " expects at least 2 arguments, got " + args.size());
		}
		List<LispVal> lists = args.subList(1, args.size());
		for (LispVal list : lists) {
			requireList(name, list);
		}
		return lists;
	}

	// The shared walk behind the whole map* family: call the function once per position
	// with one argument per list, stopping as soon as the SHORTEST list runs out (Common
	// Lisp's termination rule). 'tails' passes the successive cdrs themselves
	// (maplist/mapcon/mapl) instead of their cars (mapcar/mapc/mapcan); every member
	// differs only in that axis and in what its caller does with the collected values,
	// so one walker keeps the six in step -- they are the reference the compile backends
	// are diffed against.
	private List<LispVal> mapFamilyValues(LispVal function, List<LispVal> lists, boolean tails) {
		List<LispVal> cursors = new ArrayList<>(lists);
		List<LispVal> results = new ArrayList<>();
		while (true) {
			List<LispVal> callArgs = new ArrayList<>(cursors.size());
			for (LispVal cursor : cursors) {
				if (cursor instanceof LispCons cell) {
					callArgs.add(tails ? cell : cell.car());
				}
				else {
					return results;
				}
			}
			for (int i = 0; i < cursors.size(); i++) {
				cursors.set(i, ((LispCons) cursors.get(i)).cdr());
			}
			results.add(apply(function, callArgs, this.globalEnv));
		}
	}

	// Collect the walk's values into a fresh list (mapcar / maplist).
	private LispVal mapValues(LispVal function, List<LispVal> lists, boolean tails) {
		List<LispVal> results = mapFamilyValues(function, lists, tails);
		LispVal result = LispNil.INSTANCE;
		for (int i = results.size() - 1; i >= 0; i--) {
			result = new LispCons(results.get(i), result);
		}
		return result;
	}

	// Apply the function for its side effects only and return the first list (Common Lisp
	// mapc / mapl semantics).
	private LispVal mapForEffect(LispVal function, List<LispVal> lists, boolean tails) {
		mapFamilyValues(function, lists, tails);
		return lists.get(0);
	}

	// The argument sequences of an every/some call, each coerced to a list once. CL
	// specifies (every predicate &rest sequences) with at least one sequence; the walk
	// below stops as soon as the SHORTEST one runs out. This is the interpreter's
	// reference implementation, which the compile backends' shared macro expansion
	// (LispMacroExpander.expandEverySomeFamily) is diffed against.
	private static List<LispVal> predicateSequences(String name, List<LispVal> args) {
		if (args.size() < 2) {
			throw new LispEvalException(
					name + " expects at least 2 arguments (a predicate and one sequence), got " + args.size());
		}
		List<LispVal> lists = new ArrayList<>(args.size() - 1);
		for (int i = 1; i < args.size(); i++) {
			lists.add(Environment.seqAsList(args.get(i)));
		}
		return lists;
	}

	// The element tuple at the current position, or null once any cursor has run out.
	// Advances every cursor past the returned tuple.
	private static @Nullable List<LispVal> nextElementTuple(List<LispVal> cursors) {
		List<LispVal> callArgs = new ArrayList<>(cursors.size());
		for (LispVal cursor : cursors) {
			if (!(cursor instanceof LispCons cell)) {
				return null;
			}
			callArgs.add(cell.car());
		}
		for (int i = 0; i < cursors.size(); i++) {
			cursors.set(i, ((LispCons) cursors.get(i)).cdr());
		}
		return callArgs;
	}

	// Return t when the predicate is non-nil for every element tuple, nil at the first
	// failure (Common Lisp every semantics, over any number of sequences).
	private LispVal everyValues(LispVal predicate, List<LispVal> lists) {
		List<LispVal> cursors = new ArrayList<>(lists);
		List<LispVal> callArgs;
		while ((callArgs = nextElementTuple(cursors)) != null) {
			if (!isTruthy(apply(predicate, callArgs, this.globalEnv))) {
				return LispNil.INSTANCE;
			}
		}
		return LispTrue.INSTANCE;
	}

	// Return the first non-nil predicate result, or nil when every element tuple fails
	// (Common Lisp some semantics, over any number of sequences).
	private LispVal someValues(LispVal predicate, List<LispVal> lists) {
		List<LispVal> cursors = new ArrayList<>(lists);
		List<LispVal> callArgs;
		while ((callArgs = nextElementTuple(cursors)) != null) {
			LispVal result = apply(predicate, callArgs, this.globalEnv);
			if (isTruthy(result)) {
				return result;
			}
		}
		return LispNil.INSTANCE;
	}

	// Return the 0-based index of the first element satisfying the predicate
	// (Common Lisp position-if), or nil. Like position but tests with the predicate.
	/** The matching flavor of a runtime {@code position}-family scan. */
	private enum PositionScanMode {

		/**
		 * {@code position}/{@code find}: the first argument is an item compared by
		 * :test/:test-not.
		 */
		ITEM,
		/** {@code position-if}/{@code find-if}: the first argument is a predicate. */
		PREDICATE,
		/**
		 * {@code position-if-not}/{@code find-if-not}: the first argument is a negated
		 * predicate.
		 */
		PREDICATE_NOT

	}

	// The runtime counterpart of LispMacroExpander.buildPositionScan for first-class
	// use: a forward scan honoring :start/:end, where a :from-end match records the
	// match and keeps scanning (the last match wins). :test/:test-not apply only to
	// position/find (ITEM mode); a nil keyword value counts as absent, like the
	// expansion. elementResult selects the find family's answer (the matching element)
	// over the position family's (its index) -- the two differ in nothing else.
	private LispVal positionScanValues(String opName, List<LispVal> args, PositionScanMode mode,
			boolean elementResult) {
		if (args.size() < 2) {
			throw new LispEvalException(opName + " expects at least 2 arguments, got " + args.size());
		}
		LispVal test = null;
		LispVal testNot = null;
		LispVal keyFn = null;
		boolean fromEnd = false;
		long start = 0;
		Long end = null;
		for (int i = 2; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol kw) || i + 1 >= args.size()) {
				throw new LispEvalException(opName + " expects :keyword value pairs, got: " + args.get(i).print());
			}
			LispVal value = args.get(i + 1);
			boolean absent = value instanceof LispNil;
			switch (kw.name()) {
				case LispNames.TEST_KEYWORD, LispNames.TEST_NOT_KEYWORD -> {
					if (mode != PositionScanMode.ITEM) {
						throw new LispEvalException(opName + " does not take " + kw.name());
					}
					if (LispNames.TEST_KEYWORD.equals(kw.name())) {
						test = absent ? null : value;
					}
					else {
						testNot = absent ? null : value;
					}
				}
				case LispNames.KEY_KEYWORD -> keyFn = absent ? null : value;
				case LispNames.FROM_END_KEYWORD -> fromEnd = !absent;
				case LispNames.START_KEYWORD -> start = absent ? 0 : Environment.requireIndex(opName, value);
				case LispNames.END_KEYWORD -> end = absent ? null : (long) Environment.requireIndex(opName, value);
				default -> throw new LispEvalException(opName
						+ " expects keyword arguments :test/:test-not/:key/:start/:end/:from-end, got: " + kw.name());
			}
		}
		LispVal item = args.get(0);
		LispVal cur = Environment.seqAsList(args.get(1));
		long index = 0;
		LispVal found = LispNil.INSTANCE;
		while (cur instanceof LispCons cell && (end == null || index < end)) {
			if (index >= start) {
				LispVal elem = (keyFn == null) ? cell.car() : apply(keyFn, List.of(cell.car()), this.globalEnv);
				boolean match = switch (mode) {
					case ITEM -> testNot != null ? !isTruthy(apply(testNot, List.of(item, elem), this.globalEnv))
							: isTruthy(apply(test != null ? test : new LispSymbol(LispNames.EQL), List.of(item, elem),
									this.globalEnv));
					case PREDICATE -> isTruthy(apply(item, List.of(elem), this.globalEnv));
					case PREDICATE_NOT -> !isTruthy(apply(item, List.of(elem), this.globalEnv));
				};
				if (match) {
					LispVal answer = elementResult ? cell.car() : new LispInteger(index);
					if (!fromEnd) {
						return answer;
					}
					found = answer;
				}
			}
			index++;
			cur = cell.cdr();
		}
		return found;
	}

	// Return the number of elements satisfying the predicate (Common Lisp count-if),
	// as an integer. Like count but tests with the predicate rather than eql.
	private LispVal countIfValues(LispVal predicate, LispVal list) {
		long count = 0;
		while (list instanceof LispCons cell) {
			if (isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv))) {
				count++;
			}
			list = cell.cdr();
		}
		return new LispInteger(count);
	}

	// Return the tail of the list starting at the first element satisfying the predicate
	// (Common Lisp member-if), or nil. Like find-if but yields the cons rather than the
	// element.
	private LispVal memberIfValues(LispVal predicate, LispVal list) {
		while (list instanceof LispCons cell) {
			if (isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv))) {
				return cell;
			}
			list = cell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// Return the first pair whose car satisfies the predicate (Common Lisp assoc-if), or
	// nil. Like assoc but tests with the predicate rather than eql.
	private LispVal assocIfValues(LispVal predicate, LispVal alist) {
		while (alist instanceof LispCons cell) {
			if (cell.car() instanceof LispCons pair
					&& isTruthy(apply(predicate, List.of(pair.car()), this.globalEnv))) {
				return pair;
			}
			alist = cell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// Return the first pair whose cdr satisfies the predicate (Common Lisp rassoc-if), or
	// nil. The mirror of assocIfValues.
	private LispVal rassocIfValues(LispVal predicate, LispVal alist) {
		while (alist instanceof LispCons cell) {
			if (cell.car() instanceof LispCons pair
					&& isTruthy(apply(predicate, List.of(pair.cdr()), this.globalEnv))) {
				return pair;
			}
			alist = cell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// Return a fresh list of the elements that are kept (Common Lisp
	// remove-if/remove-if-not semantics, no keyword arguments). When keepWhenTrue is
	// false (remove-if) elements failing the predicate are kept; when true
	// (remove-if-not) elements satisfying the predicate are kept.
	private LispVal removeIfValues(LispVal predicate, LispVal list, boolean keepWhenTrue) {
		return removeIfValues(predicate, list, keepWhenTrue, null);
	}

	// The predicate sees the keyed value (:key selector); the kept elements are the
	// originals, like remove's.
	private LispVal removeIfValues(LispVal predicate, LispVal list, boolean keepWhenTrue, @Nullable LispVal keyFn) {
		List<LispVal> kept = new ArrayList<>();
		LispVal cursor = list;
		while (cursor instanceof LispCons cell) {
			LispVal probe = keyFn == null ? cell.car() : apply(keyFn, List.of(cell.car()), this.globalEnv);
			if (isTruthy(apply(predicate, List.of(probe), this.globalEnv)) == keepWhenTrue) {
				kept.add(cell.car());
			}
			cursor = cell.cdr();
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = kept.size() - 1; i >= 0; i--) {
			result = new LispCons(kept.get(i), result);
		}
		return result;
	}

	// (substitute-if new pred seq &key key) and its -if-not complement: a fresh sequence
	// whose elements satisfying (resp. failing) the predicate are replaced by new. The
	// result keeps the argument's sequence kind, like substitute.
	private LispVal substituteIfValues(String name, List<LispVal> args) {
		if (args.size() < 3) {
			throw new LispEvalException(name + " expects at least 3 arguments, got " + args.size());
		}
		requireKeyKeyword(name, args, 3);
		LispVal keyFn = optionalKeywordArg(args, 3, LispNames.KEY_KEYWORD);
		boolean negated = LispNames.SUBSTITUTE_IF_NOT.equals(name);
		LispVal newItem = args.get(0);
		LispVal predicate = args.get(1);
		List<LispVal> out = new ArrayList<>();
		LispVal cursor = Environment.seqAsList(args.get(2));
		while (cursor instanceof LispCons cell) {
			out.add(matchesSubstituteIf(predicate, keyFn, cell.car()) != negated ? newItem : cell.car());
			cursor = cell.cdr();
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = out.size() - 1; i >= 0; i--) {
			result = new LispCons(out.get(i), result);
		}
		return Environment.seqResult(args.get(2), result);
	}

	// The destructive twins: rewrite the matching cars in place and return the (possibly
	// mutated) original list.
	private LispVal nsubstituteIfValues(String name, List<LispVal> args) {
		if (args.size() < 3) {
			throw new LispEvalException(name + " expects at least 3 arguments, got " + args.size());
		}
		requireKeyKeyword(name, args, 3);
		LispVal keyFn = optionalKeywordArg(args, 3, LispNames.KEY_KEYWORD);
		boolean negated = LispNames.NSUBSTITUTE_IF_NOT.equals(name);
		LispVal newItem = args.get(0);
		LispVal predicate = args.get(1);
		LispVal list = args.get(2);
		LispVal cursor = list;
		while (cursor instanceof LispCons cell) {
			if (matchesSubstituteIf(predicate, keyFn, cell.car()) != negated) {
				cell.setCar(newItem);
			}
			cursor = cell.cdr();
		}
		return list;
	}

	private boolean matchesSubstituteIf(LispVal predicate, @Nullable LispVal keyFn, LispVal element) {
		LispVal selected = (keyFn == null) ? element : apply(keyFn, List.of(element), this.globalEnv);
		return isTruthy(apply(predicate, List.of(selected), this.globalEnv));
	}

	// The -if family takes :key only (no :test -- the predicate IS the test), so its
	// keyword tail gets its own validator rather than requireTestKeyKeywords.
	private static void requireKeyKeyword(String name, List<LispVal> args, int start) {
		for (int i = start; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol kw) || !LispNames.KEY_KEYWORD.equals(kw.name())) {
				throw new LispEvalException(name + " expects keyword argument :key, got: " + args.get(i).print());
			}
			if (i + 1 >= args.size()) {
				throw new LispEvalException(name + " expects a value after " + kw.name());
			}
		}
	}

	// Destructively splice out every cell whose car satisfies the predicate
	// (deleteWhenTrue) or fails it (delete-if-not). The surviving cells are reused and
	// the
	// new head is returned (Common Lisp semantics).
	private LispVal deleteIfValues(LispVal predicate, LispVal list, boolean deleteWhenTrue) {
		LispVal head = list;
		// Drop matching cells from the front by advancing the head.
		while (head instanceof LispCons cell
				&& isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv)) == deleteWhenTrue) {
			head = cell.cdr();
		}
		if (!(head instanceof LispCons headCell)) {
			return head;
		}
		// Splice out matching cells in the interior.
		LispCons prev = headCell;
		LispVal cursor = headCell.cdr();
		while (cursor instanceof LispCons cell) {
			if (isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv)) == deleteWhenTrue) {
				prev.setCdr(cell.cdr());
			}
			else {
				prev = cell;
			}
			cursor = cell.cdr();
		}
		return head;
	}

	private LispVal reduceValues(LispVal function, LispVal accumulator, LispVal list) {
		while (list instanceof LispCons cell) {
			accumulator = apply(function, List.of(accumulator, cell.car()), this.globalEnv);
			list = cell.cdr();
		}
		return accumulator;
	}

	// Apply the function and concatenate the resulting lists (Common Lisp mapcan / mapcon
	// semantics; the concatenation is non-destructive append rather than nconc).
	private LispVal mapcanValues(LispVal function, List<LispVal> lists, boolean tails) {
		List<LispVal> pieces = mapFamilyValues(function, lists, tails);
		LispVal result = LispNil.INSTANCE;
		for (int i = pieces.size() - 1; i >= 0; i--) {
			result = appendTwo(pieces.get(i), result);
		}
		return result;
	}

	// Build a fresh list of the elements of 'a' followed by 'b' (the tail 'b' is shared).
	private LispVal appendTwo(LispVal a, LispVal b) {
		List<LispVal> elems = new ArrayList<>();
		while (a instanceof LispCons cell) {
			elems.add(cell.car());
			a = cell.cdr();
		}
		LispVal result = b;
		for (int i = elems.size() - 1; i >= 0; i--) {
			result = new LispCons(elems.get(i), result);
		}
		return result;
	}

	// Sort a list ascending using the comparison predicate (Common Lisp sort semantics;
	// the
	// predicate is true when its first argument strictly precedes its second).
	// Implemented
	// with insertion sort calling the predicate, which keeps the ordering
	// self-consistent.
	private LispVal sortValues(LispVal list, LispVal predicate) {
		List<LispVal> elems = new ArrayList<>();
		LispVal cursor = list;
		while (cursor instanceof LispCons cell) {
			elems.add(cell.car());
			cursor = cell.cdr();
		}
		for (int i = 1; i < elems.size(); i++) {
			LispVal key = elems.get(i);
			int j = i - 1;
			while (j >= 0 && isTruthy(apply(predicate, List.of(key, elems.get(j)), this.globalEnv))) {
				elems.set(j + 1, elems.get(j));
				j--;
			}
			elems.set(j + 1, key);
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = elems.size() - 1; i >= 0; i--) {
			result = new LispCons(elems.get(i), result);
		}
		return result;
	}

	// Apply a function to a spread argument list (Common Lisp apply semantics): the
	// leading
	// arguments are taken literally and the final argument must be a list whose elements
	// are
	// spread as the remaining arguments.
	private LispVal applyValues(List<LispVal> args) {
		LispVal function = args.get(0);
		List<LispVal> callArgs = new ArrayList<>();
		for (int i = 1; i < args.size() - 1; i++) {
			callArgs.add(args.get(i));
		}
		LispVal tail = args.get(args.size() - 1);
		while (tail instanceof LispCons cell) {
			callArgs.add(cell.car());
			tail = cell.cdr();
		}
		if (!(tail instanceof LispNil)) {
			throw new LispEvalException(LispNames.APPLY + ": last argument must be a list");
		}
		return apply(function, callArgs, this.globalEnv);
	}

	private List<LispVal> evalArgs(LispCons cons, Environment env) {
		List<LispVal> args = new ArrayList<>();
		LispVal rest = cons.cdr();
		while (rest instanceof LispCons argCons) {
			args.add(eval(argCons.car(), env));
			rest = argCons.cdr();
		}
		return args;
	}

	// The rontolisp:await special form: evaluates its one operand and resolves it.
	private LispVal evalAwait(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new LispEvalException(LispNames.AWAIT + " expects 1 argument, got " + (parts.size() - 1));
		}
		return awaitValue(eval(parts.get(1), env));
	}

	// Resolves a value like JavaScript await: a future joins its computation (releasing
	// the eager-start handoff first when it would block, so this is the async body's
	// suspension point), re-signaling a stored error -- a Lisp-originated condition
	// (LispEvalException) crosses intact so handler-case around the await catches it by
	// type -- and flattening nested futures; a non-future passes through unchanged.
	private LispVal awaitValue(LispVal v) {
		while (v instanceof LispFuture future) {
			java.util.concurrent.CompletableFuture<LispVal> cf = future.future();
			if (!cf.isDone()) {
				AsyncRuntime.releaseHandoffIfPending();
			}
			try {
				v = cf.join();
			}
			catch (java.util.concurrent.CompletionException ex) {
				Throwable cause = java.util.Objects.requireNonNullElse(ex.getCause(), ex);
				if (cause instanceof LispEvalException lispError) {
					throw lispError;
				}
				throw new LispEvalException(java.util.Objects.requireNonNullElse(cause.getMessage(), "await failed"));
			}
		}
		return v;
	}

	// The single-argument thread-handle check shared by the thread primitives.
	private static LispThread requireThread(String fn, List<LispVal> args) {
		if (args.size() != 1) {
			throw new LispEvalException(fn + " expects 1 argument, got " + args.size());
		}
		if (!(args.get(0) instanceof LispThread thread)) {
			throw new LispEvalException(fn + " expects a thread handle, got " + args.get(0).print());
		}
		return thread;
	}

	// Adapts one incoming HTTP request to the Lisp handler: builds the request property
	// list, applies the handler and reads the response property list back.
	// The opaque %http-server-* handle: an integer index into HttpHandlerSupport's
	// handle table (the socket/mutex handle convention).
	private static long requireHttpServerHandle(String fn, List<LispVal> args) {
		if (args.size() != 1 || !(args.get(0) instanceof LispInteger handle)) {
			throw new LispEvalException(fn + " expects a server handle, got "
					+ (args.size() == 1 ? args.get(0).print() : args.size() + " arguments"));
		}
		return handle.value();
	}

	private HttpHandlerSupport.Response invokeHttpHandler(LispVal handler, HttpHandlerSupport.Request request) {
		LispVal headers = LispNil.INSTANCE;
		List<HttpHandlerSupport.Header> requestHeaders = request.headers();
		for (int i = requestHeaders.size() - 1; i >= 0; i--) {
			HttpHandlerSupport.Header header = requestHeaders.get(i);
			headers = new LispCons(new LispCons(new LispString(header.name()), new LispString(header.value())),
					headers);
		}
		LispVal query = request.query() == null ? LispNil.INSTANCE : new LispString(request.query());
		// The handler sees the request body as an asynchronous stream (one settled
		// chunk here; the server buffers the body before dispatch). A bodyless request
		// yields an already-drained stream, so its first read observes end of stream.
		LispStream requestBody;
		if (request.body().isEmpty()) {
			requestBody = LispStream.open();
			requestBody.close();
		}
		else {
			requestBody = LispStream.settled(new LispString(request.body()));
		}
		// The plist shape (keys, order) is derived from the http-plist WIT request
		// record; only the per-field value extraction is this backend's, so an unmapped
		// record field fails loudly here.
		List<LispVal> requestEntries = new ArrayList<>();
		for (HttpPlistShape.Field field : HttpPlistShape.requestFields()) {
			requestEntries.add(new LispSymbol(field.keyword()));
			requestEntries.add(switch (field.name()) {
				case "method" -> new LispString(request.method());
				case "path" -> new LispString(request.path());
				case "query" -> query;
				case "headers" -> headers;
				case "body" -> requestBody;
				default -> throw new LispEvalException(
						LispNames.HTTP_HANDLER + " has no extraction for request field " + field.name());
			});
		}
		LispVal requestPlist = plist(requestEntries.toArray(new LispVal[0]));
		// An async-defun handler returns a future; each request runs on its own virtual
		// thread, so awaiting it here is the natural per-request suspension.
		LispVal result = awaitValue(apply(handler, List.of(requestPlist), this.globalEnv));
		// The response plist is read back per the same WIT record (its response half),
		// with the shape's declared defaults for missing keys.
		int status = HttpPlistShape.RESPONSE_STATUS_DEFAULT;
		String body = HttpPlistShape.RESPONSE_BODY_DEFAULT;
		List<HttpHandlerSupport.Header> responseHeaders = new ArrayList<>();
		for (HttpPlistShape.Field field : HttpPlistShape.responseFields()) {
			LispVal value = httpPlistGet(result, field.keyword());
			switch (field.name()) {
				case "status" -> {
					if (value instanceof LispInteger statusVal) {
						status = (int) statusVal.value();
					}
				}
				case "body" -> {
					if (value instanceof LispString bodyStr) {
						body = bodyStr.value();
					}
					else if (value instanceof LispStream bodyStream) {
						// A streaming response body is drained here (buffered send);
						// true chunked transfer follows with the JVM handler-interface
						// rework.
						StringBuilder drained = new StringBuilder();
						LispVal chunk = awaitValue(LispFuture.of(bodyStream.read()));
						while (!(chunk instanceof LispNil)) {
							if (!(chunk instanceof LispString chunkStr)) {
								throw new LispEvalException(LispNames.HTTP_HANDLER
										+ ": a response body stream must carry string chunks, got: " + chunk.print());
							}
							drained.append(chunkStr.value());
							chunk = awaitValue(LispFuture.of(bodyStream.read()));
						}
						body = drained.toString();
					}
				}
				case "headers" -> {
					LispVal headerAlist = value;
					while (headerAlist instanceof LispCons cons) {
						if (cons.car() instanceof LispCons pair && pair.car() instanceof LispString name
								&& pair.cdr() instanceof LispString headerValue) {
							responseHeaders.add(new HttpHandlerSupport.Header(name.value(), headerValue.value()));
						}
						headerAlist = cons.cdr();
					}
				}
				default -> throw new LispEvalException(
						LispNames.HTTP_HANDLER + " has no extraction for response field " + field.name());
			}
		}
		return new HttpHandlerSupport.Response(status, responseHeaders, body);
	}

	// Builds a property list from alternating key/value LispVals.
	private static LispVal plist(LispVal... elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			result = new LispCons(elements[i], result);
		}
		return result;
	}

	// Returns the value of key in a property list, or nil if absent.
	private static LispVal httpPlistGet(LispVal plist, String key) {
		LispVal current = plist;
		while (current instanceof LispCons cons && cons.cdr() instanceof LispCons valueCell) {
			if (cons.car() instanceof LispSymbol sym && sym.name().equals(key)) {
				return valueCell.car();
			}
			current = valueCell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// Evaluates the Lisp-source JSON library into the global environment on first
	// use, then applies the named fixed-arity helper.
	private LispVal applyJsonHelper(String helperName, List<LispVal> args) {
		synchronized (this.libraryLoadLock) {
			if (!this.jsonLibraryLoaded) {
				this.jsonLibraryLoaded = true;
				for (LispVal form : JsonLibrary.forms()) {
					eval(form, this.globalEnv);
				}
			}
		}
		return apply(resolveFunction(helperName), args, this.globalEnv);
	}

	/**
	 * Applies a function value against the global environment. The seam
	 * {@link LinalgSimd}'s natives use to fall back to the scalar {@code linalg.lisp}
	 * defun they replaced, for an input their lane loops do not handle.
	 * @param function the function value (a lambda, a native, or a symbol designator)
	 * @param args the evaluated arguments
	 * @return the function's result
	 */
	LispVal applyGlobal(LispVal function, List<LispVal> args) {
		return apply(function, args, this.globalEnv);
	}

	private LispVal apply(LispVal function, List<LispVal> args, Environment env) {
		if (function instanceof LispSymbol sym) {
			// A symbol is a function designator naming its global function (CL-style).
			function = resolveFunction(sym.name());
		}
		if (function instanceof LispFunction builtIn) {
			return builtIn.body().apply(args);
		}
		if (function instanceof LispLambda lambda) {
			int required = lambda.params().size();
			if (args.size() < required) {
				throw new LispEvalException("Function expects " + (lambda.rest() == null ? "" : "at least ") + required
						+ " argument" + (required == 1 ? "" : "s") + ", got " + args.size());
			}
			if (lambda.rest() == null && args.size() > required) {
				throw new LispEvalException("Function expects " + required + " argument" + (required == 1 ? "" : "s")
						+ ", got " + args.size());
			}
			Environment lambdaEnv = new Environment((Environment) lambda.closure());
			// A parameter whose name is proclaimed special binds DYNAMICALLY, as in CL:
			// symbol reads consult the dynamic store before the lexical chain, so a
			// lexical binding of a special name would be shadowed by any active outer
			// dynamic binding instead of holding the argument (cl-ppcre's convert
			// phase passes such names around while they are dynamically bound). It is
			// ALSO defined lexically with the same value: a closure built in this body
			// and called after the extent pops must still see the argument (cl-ppcre's
			// create-scanner-aux parameter reg-num, special only because convert.lisp
			// pessimistically proclaimed the name, is captured by the scanner closure)
			// -- the dual binding diverges only if the parameter is setq'd, which
			// updates the dynamic side alone.
			List<String> dynamicParams = null;
			for (int i = 0; i < required; i++) {
				String paramName = lambda.params().get(i).name();
				lambdaEnv.define(paramName, args.get(i));
				if (!this.specialVars.isEmpty() && this.specialVars.contains(paramName)) {
					this.dynamicBindings.push(paramName, args.get(i));
					dynamicParams = dynamicParams == null ? new ArrayList<>(2) : dynamicParams;
					dynamicParams.add(paramName);
				}
			}
			if (lambda.rest() != null) {
				LispVal restList = LispNil.INSTANCE;
				for (int i = args.size() - 1; i >= required; i--) {
					restList = new LispCons(args.get(i), restList);
				}
				String restName = lambda.rest().name();
				lambdaEnv.define(restName, restList);
				if (!this.specialVars.isEmpty() && this.specialVars.contains(restName)) {
					this.dynamicBindings.push(restName, restList);
					dynamicParams = dynamicParams == null ? new ArrayList<>(2) : dynamicParams;
					dynamicParams.add(restName);
				}
			}
			try {
				LispVal result = LispNil.INSTANCE;
				for (LispVal bodyExpr : lambda.body()) {
					result = eval(bodyExpr, lambdaEnv);
				}
				return result;
			}
			finally {
				if (dynamicParams != null) {
					for (int i = dynamicParams.size() - 1; i >= 0; i--) {
						this.dynamicBindings.pop(dynamicParams.get(i));
					}
				}
			}
		}
		throw new LispEvalException("Not a function: " + function.print());
	}

	// Scans a keyword/value argument tail starting at the given index for the named
	// keyword, returning the value following the first match, or the fallback when
	// absent.
	private static LispVal keywordArg(List<LispVal> args, int start, String keyword, LispVal fallback) {
		LispVal value = optionalKeywordArg(args, start, keyword);
		return value != null ? value : fallback;
	}

	// Like keywordArg but returns null when the keyword is absent (for keywords with no
	// default designator, such as :key).
	private static @Nullable LispVal optionalKeywordArg(List<LispVal> args, int start, String keyword) {
		for (int i = start; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && keyword.equals(kw.name())) {
				return args.get(i + 1);
			}
		}
		return null;
	}

	// Validates the keyword tail of a sequence/alist call: keyword/value pairs only, and
	// every keyword must be :test or :key. Unsupported keywords (:from-end, :start, ...)
	// are rejected loudly rather than silently ignored, mirroring the compile-time check
	// in LispMacroExpander.
	private static void requireTestKeyKeywords(String name, List<LispVal> args, int start) {
		for (int i = start; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol kw)
					|| (!LispNames.TEST_KEYWORD.equals(kw.name()) && !LispNames.KEY_KEYWORD.equals(kw.name()))) {
				throw new LispEvalException(
						name + " expects keyword arguments :test/:key, got: " + args.get(i).print());
			}
			if (i + 1 >= args.size()) {
				throw new LispEvalException(name + " expects a value after " + kw.name());
			}
		}
	}

	private boolean isTruthy(LispVal val) {
		return !(val instanceof LispNil);
	}

}
