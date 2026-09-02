package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispObjcObject;
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
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.SpecialVarCollector;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.ClackEnv;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.runtime.RontoHttpClack;
import am.ik.rontolisp.runtime.RontoHttpServer;
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
	 * {@link RontoHttpServer}), and the flags are set before the definitions are
	 * installed -- so without this a request arriving mid-load skips the loader and then
	 * fails to resolve the very function that is being defined. Reentrant: loading a
	 * library evaluates its forms, which resolve further names through the same gates.
	 */
	private final Object libraryLoadLock = new Object();

	private boolean jsonLibraryLoaded = false;

	private boolean linalgLibraryLoaded = false;

	private boolean appkitLibraryLoaded = false;

	private boolean geomLibraryLoaded = false;

	/**
	 * Whether {@link GeomKernels} is installed over the geom.lisp defuns when the library
	 * loads. On for every program; the one caller that turns it OFF is the test that
	 * compares the two paths element for element, which needs the defuns as the oracle.
	 */
	private boolean geomKernels = true;

	private boolean metalLibraryLoaded = false;

	private boolean sceneLibraryLoaded = false;

	private boolean torchLibraryLoaded = false;

	private boolean vecLibraryLoaded = false;

	private boolean ironcladNativeInstalled = false;

	private boolean simd = false;

	private boolean blas = false;

	private boolean gpu = false;

	private boolean parallel = false;

	private final java.util.Set<String> loadedPreludeNames = new java.util.HashSet<>();

	// The function object each loaded LispPreludeLibrary entry installed. A native fast
	// arm over a prelude operator (search/mismatch, SequenceScanFast) serves ONLY while
	// the name still resolves to this exact object, so a user redefinition -- which the
	// lazy loader already honours by never loading the prelude entry over it -- takes the
	// call back whole rather than half. Concurrent because a served request may resolve a
	// name while another thread is loading one (the libraryLoadLock covers the load, not
	// this read).
	private final java.util.Map<String, LispVal> preludeDefinitions = new java.util.concurrent.ConcurrentHashMap<>();

	// The uiop definitions already evaluated into the global environment, keyed by their
	// home-package spelling. uiop is 429 externals of which a program touches a handful,
	// so it loads ONE name at a time (the loadedPreludeNames pattern) rather than whole.
	private final java.util.Set<String> loadedUiopNames = new java.util.HashSet<>();

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

	// The routing shape (the print-object tag set, whether conditions report, whether
	// *print-case* converts) the loaded %print-object-str / %print-object-leaf pair was
	// generated from, or -1 before the first load. The pair is RE-generated whenever that
	// moves, which is what lets a defmethod print-object evaluated after the first print
	// still take effect -- the compile path emits it once because its registry is
	// complete.
	private int printObjectRuntimeStamp = -1;

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
	 * Global symbol macros defined with {@code define-symbol-macro}, keyed by name. A
	 * reference to one of these names in a VALUE position evaluates the expansion, and a
	 * {@code setq}/{@code setf} of it writes through the expansion as a place -- the name
	 * is not a variable, so {@code symbol-value} never sees it. The table is consulted
	 * only after the ordinary lexical/global lookup has come up empty, which keeps every
	 * variable read that is not a symbol macro off this path entirely (the compile paths
	 * hold the same table in {@code UserMacroExpander} and substitute statically).
	 */
	private final java.util.Map<String, LispVal> globalSymbolMacros = new java.util.HashMap<>();

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
	 * Memo of {@link #expandUserMacro}, keyed by the CALL SITE's cons identity. CL
	 * expands a macro call once, when the code containing it is processed; the
	 * interpreter used to re-expand on EVERY evaluation, so a macro call in a loop body
	 * re-interpreted the whole macro body per iteration -- and every memo below missed
	 * too, because each iteration handed them a freshly consed expansion. Expanding once
	 * per source occurrence is what the compile path ({@link UserMacroExpander}) already
	 * does, so the memo moves the interpreter TOWARD cross-backend identity: a macro body
	 * that reads a global while expanding now freezes the first answer everywhere alike.
	 * Every write to {@link #userMacros} goes through {@link #putUserMacro} /
	 * {@link #removeUserMacro}, which drop the memo -- a redefined {@code defmacro} or a
	 * {@code macrolet} entering or leaving scope changes what a call site means.
	 * <p>
	 * Guarded by its own monitor, never held across an expansion: a macro call is
	 * ordinary Lisp and so is reachable from a served request, which is one virtual
	 * thread per request ({@code .kb/concurrent-served-requests.md}). Two threads racing
	 * on the same call site both expand and the last write wins -- each expansion is
	 * self-consistent, so that is a wasted expansion, not a wrong answer.
	 */
	private final java.util.IdentityHashMap<LispVal, LispVal> userMacroExpansions = new java.util.IdentityHashMap<>();

	/**
	 * Memo of the BUILT-IN macro arms' expansions ({@link #evalBuiltinMacro}), keyed by
	 * the call site's cons identity, exactly like {@link #userMacroExpansions} above: the
	 * interpreter used to re-expand {@code cond}/{@code do}/{@code when}/{@code incf} and
	 * a hundred more on EVERY evaluation -- 15% of run-time samples in the todo-598
	 * profile -- where the three compile backends expand once at compile time. Only an
	 * arm whose expander is a pure function of the form (nothing but the cons and
	 * compile-time-constant flags; {@code LispMacroExpander} holds no mutable static
	 * state) may go through this memo -- the arms whose expansion reads evaluator state
	 * ({@code error}/{@code warn}/{@code signal}/{@code cerror} and the
	 * {@code restartRuntimeLoaded} gate, the {@code closRegistry} consumers, {@code setf}
	 * and its user expanders, the print family's per-call {@code print-object} routing,
	 * {@code flet}/{@code labels}/{@code symbol-macrolet} and the live user-macro table)
	 * stay re-expanded per evaluation. The full enumeration and the stated semantic
	 * change (a form REWRITTEN between evaluations keeps its first expansion):
	 * {@code .kb/interpreter-expansion-memo.md}. Built-in operators cannot be shadowed or
	 * redefined, so unlike {@link #userMacroExpansions} nothing ever invalidates this
	 * map; the same monitor-around-lookup, expansion-outside-the-monitor discipline
	 * applies.
	 */
	private final java.util.IdentityHashMap<LispVal, LispVal> builtinMacroExpansions = new java.util.IdentityHashMap<>();

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
	 * Upper bound on the three identity memos above. A program that builds call forms at
	 * runtime and feeds them to {@code eval} would otherwise retain one entry per form
	 * forever; past the bound the expansion is simply recomputed, which is exactly the
	 * behavior before compiler macros were applied at all.
	 */
	private static final int EXPANSION_MEMO_LIMIT = 20_000;

	/**
	 * A user macro: required parameters, an optional {@code &rest}/{@code &body}
	 * parameter, the body forms, and the environment captured at definition time.
	 */
	private record UserMacro(List<LispSymbol> required, @Nullable LispSymbol rest, List<LispVal> body, Environment env,
			String definitionPackage) {
	}

	/**
	 * How many user-defined function bodies are currently on the stack. Zero means the
	 * form being evaluated is a TOP-LEVEL one, whose file's package the resolver still
	 * has current -- which is what decides the package a user macro expands in (see
	 * {@code expandMacroCall}).
	 */
	private int functionBodyDepth;

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
	 * no-op. This is CL's {@code *modules*}, and the VARIABLE is the authority rather
	 * than a Java set beside it -- a program may push onto {@code *modules*} itself
	 * (esrap's editor-support reads it to decide whether swank is present), and then a
	 * {@code require} must see that. Kept in the global environment, so REPL state
	 * persists across inputs like the resolver's current package.
	 */
	private java.util.List<String> providedModules() {
		List<String> names = new java.util.ArrayList<>();
		LispVal modules = this.globalEnv.lookupOrNull(LispNames.MODULES_VAR);
		while (modules instanceof LispCons cons) {
			if (cons.car() instanceof LispString name) {
				names.add(name.value());
			}
			else if (cons.car() instanceof LispSymbol name) {
				names.add(name.name());
			}
			modules = cons.cdr();
		}
		return names;
	}

	/**
	 * The systems registered by {@code asdf:defsystem} (evaluated inline or read out of a
	 * {@code NAME.asd} file), by name. Kept per evaluator like the provided-module set.
	 */
	private final java.util.Map<String, AsdfSystems.LispSystem> asdfSystems = new java.util.LinkedHashMap<>();

	/**
	 * "Package P lives in system S", merged from the {@code register-system-packages}
	 * forms of every {@code .asd} read so far. Read when a package-inferred system turns
	 * a component file's {@code defpackage} dependency into a system name.
	 */
	private final java.util.Map<String, String> asdfSystemPackages = new java.util.HashMap<>();

	/**
	 * The systems already loaded by {@code asdf:load-system} (loading again is a no-op).
	 * Insertion-ordered so {@code asdf:registered-systems} answers deterministically.
	 */
	private final java.util.Set<String> loadedSystems = new java.util.LinkedHashSet<>();

	/** Whether the asdf runtime (asdf.lisp -- the component metaobjects) is loaded. */
	private boolean asdfRuntimeLoaded;

	/** The systems currently being loaded, for {@code :depends-on} cycle detection. */
	private final java.util.Deque<String> loadingSystems = new java.util.ArrayDeque<>();

	/**
	 * Extra directories searched for {@code NAME.asd} files by {@code asdf:load-system},
	 * after the directory of the loading file (the CLI threads {@code --system-path} and
	 * {@code RONTOLISP_SOURCE_REGISTRY} here).
	 */
	private List<String> systemPath = List.of();

	/**
	 * The program's own argument vector, argv0 first -- what {@code %host-argv} answers
	 * and therefore what the {@code uiop/image} command-line family reads. Empty by
	 * default: an EMBEDDED run (the tests, the browser playground) has no command line of
	 * its own, and upstream's answer for an implementation it cannot ask is nil too. The
	 * CLI threads the input file plus everything after the {@code --} separator here.
	 */
	private List<String> commandLineArguments = List.of();

	/**
	 * The dist downloader behind {@code ql:quickload} / {@code ql-dist:install-dist}:
	 * created lazily on first use (so a program that never calls either touches no
	 * network/cache), or injected by the CLI ({@code --dist}) resp. a test via
	 * {@link #setDistClient}.
	 */
	@Nullable private DistClient distClient;

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
		// The pretty-printer control variables join them: `write` BINDS all of them
		// around one print (CL's own definition of its keywords), and esrap rebinds
		// *print-pprint-dispatch* around its result printer.
		this.specialVars.add(LispNames.PRINT_PRETTY_VAR);
		this.specialVars.add(LispNames.PRINT_CIRCLE_VAR);
		this.specialVars.add(LispNames.PRINT_RIGHT_MARGIN_VAR);
		this.specialVars.add(LispNames.PRINT_MISER_WIDTH_VAR);
		this.specialVars.add(LispNames.PRINT_LINES_VAR);
		this.specialVars.add(LispNames.PRINT_PPRINT_DISPATCH_VAR);
		this.specialVars.add(LispNames.PRINT_LENGTH_VAR);
		this.specialVars.add(LispNames.PRINT_LEVEL_VAR);
		this.specialVars.add(LispNames.PRINT_BASE_VAR);
		this.specialVars.add(LispNames.PRINT_RADIX_VAR);
		this.specialVars.add(LispNames.PRINT_CASE_VAR);
		this.specialVars.add(LispNames.PRINT_ARRAY_VAR);
		this.specialVars.add(LispNames.PRINT_GENSYM_VAR);
		this.specialVars.add(LispNames.TRACE_OUTPUT_VAR);
		this.specialVars.add(LispNames.DEBUG_IO_VAR);
		this.specialVars.add(LispNames.QUERY_IO_VAR);
		this.specialVars.add(LispNames.TERMINAL_IO_VAR);
		// *read-eval* joins them: (let ((*read-eval* nil)) (read ...)) must bind
		// dynamically for the #. check in resolveReadTimeEval to see it.
		this.specialVars.add(LispNames.READ_EVAL_VAR);
		// *default-pathname-defaults* joins them: a portable program binds it around a
		// block of path work ((let ((*default-pathname-defaults* d)) ...)), which is a
		// dynamic binding or nothing.
		this.specialVars.add(LispNames.DEFAULT_PATHNAME_DEFAULTS_VAR);
		// *features* joins them for the same reason, and it is the one the COMPILE
		// PATHS force: they seed the variable with a defvar (LispMacroExpander), which
		// proclaims it special, so a (let ((*features* ...)) ...) around a call to
		// uiop:featurep -- whose &optional default reads the variable, exactly what
		// upstream's parameter list invites -- binds dynamically there. Without this
		// the interpreter would bind it lexically and answer differently.
		this.specialVars.add(LispNames.FEATURES_VAR);
		// The load-report switches join them: a portable loader binds them around a load
		// ((let ((*load-verbose* nil)) (load f))), which is a dynamic binding or nothing.
		// The compile paths get the same proclamation from the injected defvar.
		this.specialVars.add(LispNames.LOAD_VERBOSE_VAR);
		this.specialVars.add(LispNames.LOAD_PRINT_VAR);
		this.specialVars.add(LispNames.COMPILE_VERBOSE_VAR);
		this.specialVars.add(LispNames.COMPILE_PRINT_VAR);
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
	 * The {@code deftype} names this evaluator has registered, in registration order --
	 * including the macro-GENERATED ones, which is why the compile path's
	 * {@code UserMacroExpander} reads them back out (see
	 * {@code emitMacroGeneratedDeftypes}).
	 * @return the registered deftype names
	 */
	public java.util.Set<String> deftypeNames() {
		return this.closRegistry.deftypeNames();
	}

	/**
	 * The registered expansion of a {@code deftype} name, or null.
	 * @param name the deftype name
	 * @return the literal type specifier it expands to
	 */
	public @Nullable LispVal findDeftype(String name) {
		return this.closRegistry.findDeftype(name);
	}

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
	 * The current package's name, UPCASED as Common Lisp prints it -- the value of
	 * {@code *package*} named rather than printed. The REPL prompt reads it before every
	 * line so that an {@code (in-package ...)} typed at one prompt is visible at the
	 * next, the way {@code CL-USER>} names the package in any Common Lisp REPL.
	 * @return the current package name, upcased
	 */
	public String currentPackageName() {
		return this.packageResolver.currentPackageName().toUpperCase(java.util.Locale.ROOT);
	}

	/**
	 * Sets the program's argument vector, argv0 first -- the value the {@code uiop/image}
	 * command-line family reads ({@code (uiop:command-line-arguments)} is its rest,
	 * {@code (uiop:argv0)} its first). The CLI threads the input file and the arguments
	 * after the {@code --} separator here; an embedded run leaves it empty.
	 * @param arguments the argument vector, argv0 first
	 */
	public void setCommandLineArguments(List<String> arguments) {
		this.commandLineArguments = List.copyOf(arguments);
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
	 * Suppresses {@link GeomKernels}, so {@code geom:read-obj}, {@code geom:mesh} and
	 * {@code geom:wireframe} run as the {@code geom.lisp} defuns alone. There is no flag
	 * behind this and no reason for a program to ask for it: the natives answer what the
	 * defuns answer, bit for bit. It exists so the test that PROVES that has an oracle to
	 * compare against.
	 * @param enabled whether to install the geom natives on the next geom load
	 */
	void setGeomKernels(boolean enabled) {
		this.geomKernels = enabled;
	}

	/**
	 * Enables the opt-in {@code --blas} acceleration of the {@code linalg:} matrix
	 * product: when the linalg library is loaded, {@code linalg:dot} is overridden with
	 * the {@code gemm} / {@code gemv} of a tuned CBLAS found in the operating system
	 * ({@link LinalgBlas}). Off by default, and orthogonal to {@link #setSimd}: with both
	 * on, a product the library declines falls through to the Vector API kernel. The
	 * caller must have checked {@link LinalgBlas#available()}.
	 * @param blas whether to route the linalg: matrix product to a tuned CBLAS
	 */
	public void setBlas(boolean blas) {
		this.blas = blas;
	}

	/**
	 * Enables the opt-in {@code --gpu} acceleration of the {@code linalg:} matrix
	 * product: when the linalg library is loaded, {@code linalg:dot} is overridden with a
	 * device product ({@link LinalgGpu}). Off by default, and orthogonal to
	 * {@link #setSimd} and {@link #setBlas}: it is installed LAST of the three, so a
	 * product the device declines -- and at the shapes rontolisp examples run today that
	 * is nearly all of them -- falls through to the tuned CBLAS if one was asked for,
	 * then to the Vector API kernel, then to the scalar defun. The caller must have
	 * checked {@link LinalgGpu#available()}. {@code vec:matvec} is overridden the same
	 * way when the vec library loads ({@link LinalgGpu#installVec}).
	 * @param gpu whether to route the linalg: matrix product to a GPU
	 */
	public void setGpu(boolean gpu) {
		this.gpu = gpu;
	}

	/**
	 * Enables the opt-in {@code --parallel} row split of the {@code --simd} matrix
	 * products: {@code vec:matvec} / {@code vec:matvec-into}, {@code linalg:dot}'s matrix
	 * cases and the stacked {@code linalg:matmul} run over a row range per thread
	 * ({@link SimdParallel}) when a call is worth it. A modifier of {@link #setSimd} --
	 * without it nothing is intercepted and the flag is inert -- and bit-identical to it:
	 * the rows are independent chains, so which thread runs which row cannot change a
	 * result. The reductions are never split.
	 * @param parallel whether to split the matrix products across threads
	 */
	public void setParallel(boolean parallel) {
		this.parallel = parallel;
	}

	/**
	 * Installs the dist downloader used by {@code ql:quickload} and
	 * {@code ql-dist:install-dist}. The CLI passes one carrying the {@code --dist} /
	 * {@code RONTOLISP_DISTS} dists; a test injects one with an in-memory
	 * {@link DistClient.Downloader} and a temporary cache directory. Left {@code null}
	 * otherwise, where the default client ({@link DistClient#createDefault}) is created
	 * on first use.
	 * @param client the dist client
	 */
	public void setDistClient(DistClient client) {
		this.distClient = client;
	}

	/**
	 * Returns the dist client, creating the default one (Quicklisp only) on first use.
	 * @return the dist client
	 */
	private DistClient distClient() {
		if (this.distClient == null) {
			this.distClient = DistClient.createDefault();
		}
		return this.distClient;
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
		// #'read-from-string folding. read is not wrapped: it is prelude rontolisp whose
		// whole parse goes through read-from-string, so it inherits the fold.
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
			return expandedWithFlag(args.get(0), macroexpand1(args.get(0)));
		}));
		this.globalEnv.defineFunction(LispNames.MACROEXPAND, new LispFunction(LispNames.MACROEXPAND, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.MACROEXPAND + " expects 1 or 2 arguments, got " + args.size());
			}
			return expandedWithFlag(args.get(0), macroexpand(args.get(0)));
		}));
		// macro-function: the expander of a macro NAME, or nil for a function, a special
		// operator and an unknown name -- what a caller asking "can I apply this" reads.
		// It lives here (not in the prelude, which serves the compiled backends) because
		// the answer is the macro table this evaluator holds: a user defmacro, a macrolet
		// body's local macro, and the built-in expander LispMacroExpander dispatches on.
		// The returned function is the real single-step expander, callable as
		// (funcall f form env) like CL's; env is accepted and ignored (there is no
		// lexical macro environment to consult -- macrolet is expanded away before any
		// body runs, so the global answer is the only one).
		this.globalEnv.defineFunction(LispNames.MACRO_FUNCTION, new LispFunction(LispNames.MACRO_FUNCTION, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.MACRO_FUNCTION + " expects 1 or 2 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispSymbol sym) || !isMacroName(sym.name())) {
				return LispNil.INSTANCE;
			}
			String name = sym.name();
			return new LispFunction(LispNames.MACRO_FUNCTION + " " + name, callArgs -> {
				if (callArgs.isEmpty() || callArgs.size() > 2) {
					throw new LispEvalException("a macro function expects 1 or 2 arguments, got " + callArgs.size());
				}
				return macroexpand1(macroCallForm(name, callArgs.get(0)));
			});
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
		// concatenate re-registered WITH the class registry, so a result-type designator
		// naming a user deftype (fast-http's simple-byte-vector) resolves through its
		// registered expansion to the family -- the same resolution the compile paths
		// get from ConcatenateForms.resultSpec(designator, closRegistry).
		this.globalEnv.defineFunction(LispNames.CONCATENATE, Environment.concatenateBuiltin(this.closRegistry));
		// make-array for the same reason: an :element-type naming a user deftype
		// (salza2's octet) must select the representation its expansion designates --
		// a packed (unsigned-byte 8) vector, not a general array of nil.
		this.globalEnv.defineFunction(LispNames.MAKE_ARRAY, Environment.makeArrayBuiltin(this.closRegistry));
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
			// for the wider layout, and a type keeping machinery beside its declared
			// slots (LispLayout.SYNONYM_STREAM's reader closure) is handed that cell as
			// an ordinary trailing argument (LispLayout.capacity).
			LispVal[] slots = new LispVal[layout.capacity()];
			for (int i = 0; i < slots.length; i++) {
				slots[i] = i + 1 < args.size() ? args.get(i + 1) : LispNil.INSTANCE;
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
		this.globalEnv.defineFunction(LispNames.MOP_FILL_SLOTS, new LispFunction(LispNames.MOP_FILL_SLOTS, args -> {
			// (%mop-fill-slots obj initargs initforms-p) -- the metaclass
			// protocol's system initarg fill (the shared-initialize primaries of
			// mop-protocol.lisp call it): store each supplied initarg into its
			// slot (leftmost wins), then -- when initforms-p is true, i.e.
			// initialize rather than reinitialize -- each still-unbound slot's
			// initform. Registry-backed, so it stays correct as classes are
			// defined; the compile paths generate a per-class dispatch defun of
			// the same name.
			if (args.size() != 3 || !(args.get(0) instanceof LispInstance obj)) {
				throw new LispEvalException(LispNames.MOP_FILL_SLOTS + " expects (instance initargs initforms-p)");
			}
			ClosRegistry.ClassInfo info = this.closRegistry.findClass(obj.layout().printName());
			if (info == null) {
				// Not a registered class (a struct instance through a user
				// reinitialize-instance, say): no fill, like the generated dispatch's
				// fall-through -- the shared-initialize default still answers the
				// instance.
				return obj;
			}
			boolean initforms = !(args.get(2) instanceof LispNil);
			java.util.List<ClosRegistry.SlotSpec> slots = info.slots();
			for (int i = 0; i < slots.size() && i < obj.slotCount(); i++) {
				ClosRegistry.SlotSpec slot = slots.get(i);
				// Only a DECLARED :initarg fills from the initargs, per CL -- see the
				// generated twin in LispMacroExpander.
				LispVal supplied = slot.initargSupplied() ? leftmostInitargValue(args.get(1), slot.initargKeyword())
						: null;
				if (supplied != null) {
					obj.setSlot(i, supplied);
				}
				else if (initforms && slot.initformSupplied() && obj.slot(i) instanceof LispInstance marker
						&& marker.hasTag(ClosRegistry.UNBOUND_TAG)) {
					obj.setSlot(i, eval(slot.initform(), this.globalEnv));
				}
			}
			return obj;
		}));
		this.globalEnv.defineFunction(LispNames.CLASS_DIRECT_SUBCLASSES_INTERNAL,
				new LispFunction(LispNames.CLASS_DIRECT_SUBCLASSES_INTERNAL, args -> {
					// (%class-direct-subclasses designator) -- the registered classes
					// whose direct superclasses contain the designated class, as
					// metaobjects (driver-built instances answer through the memo). The
					// shim's closer-mop:class-direct-subclasses rides it; the compile
					// paths generate a dispatch defun over the static registry.
					requireSingleArg(LispNames.CLASS_DIRECT_SUBCLASSES_INTERNAL, args);
					LispVal designator = args.get(0);
					if (designator instanceof LispInstance inst && this.closRegistry.isClassMetaobject(inst)) {
						designator = inst.slot(0);
					}
					if (!(designator instanceof LispSymbol sym)) {
						return LispNil.INSTANCE;
					}
					ClosRegistry.ClassInfo target = this.closRegistry.findClass(sym.name());
					if (target == null) {
						return LispNil.INSTANCE;
					}
					java.util.List<LispVal> subs = new java.util.ArrayList<>();
					for (String subName : this.closRegistry.directSubclassNames(target.name())) {
						LispVal metaobject = this.closRegistry.classMetaobject(subName);
						if (metaobject != null) {
							subs.add(metaobject);
						}
					}
					LispVal result = LispNil.INSTANCE;
					for (int i = subs.size() - 1; i >= 0; i--) {
						result = new LispCons(subs.get(i), result);
					}
					return result;
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
		// The runtime-slot-name dispatch pair the compile paths generate as defuns:
		// the shared setf/with-slots expansions emit calls to them for an AMBIGUOUS
		// literal slot name too (they are outlined), and those expansions serve
		// the interpreter as well -- here they are the registry-backed reads/writes.
		this.globalEnv.defineFunction(LispNames.SLOT_VALUE_RUNTIME,
				new LispFunction(LispNames.SLOT_VALUE_RUNTIME, args -> {
					if (args.size() != 2) {
						throw new LispEvalException(
								LispNames.SLOT_VALUE_RUNTIME + " expects 2 arguments, got " + args.size());
					}
					SlotRef slot = instanceSlotRef(args.get(0), args.get(1));
					if (slot == null) {
						throw new LispEvalException(LispNames.SLOT_VALUE + ": unknown slot " + args.get(1).print()
								+ " on " + args.get(0).print());
					}
					return slot.read();
				}));
		this.globalEnv.defineFunction(LispNames.SLOT_VALUE_SET_RUNTIME,
				new LispFunction(LispNames.SLOT_VALUE_SET_RUNTIME, args -> {
					if (args.size() != 3) {
						throw new LispEvalException(
								LispNames.SLOT_VALUE_SET_RUNTIME + " expects 3 arguments, got " + args.size());
					}
					SlotRef slot = instanceSlotRef(args.get(0), args.get(1));
					if (slot == null) {
						throw new LispEvalException(LispNames.SLOT_VALUE + ": unknown slot " + args.get(1).print()
								+ " on " + args.get(0).print());
					}
					slot.write(args.get(2));
					return args.get(2);
				}));
		this.globalEnv.defineFunction(LispNames.SLOT_EXISTS_P, new LispFunction(LispNames.SLOT_EXISTS_P, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.SLOT_EXISTS_P + " expects 2 arguments, got " + args.size());
			}
			// Exists = the value is an instance whose layout declares the slot; an
			// unbound slot exists (slot-boundp is the boundness test).
			return instanceSlotRef(args.get(0), args.get(1)) != null ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// Gray-stream dispatch: write-string (and write-char, which lowers to it)
		// handed an INSTANCE as its stream calls rontolisp's own Gray protocol
		// (eval.GrayStreamsLibrary) instead of the handle-based built-in, so a user
		// output-stream class receives the writes. Portability layers
		// (trivial-gray-streams) adapt onto that protocol through their shim system;
		// the core knows no third-party name.
		LispVal baseWriteString = this.globalEnv.lookupFunction(LispNames.WRITE_STRING);
		this.globalEnv.defineFunction(LispNames.WRITE_STRING, new LispFunction(LispNames.WRITE_STRING, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 1);
			if (args.size() >= 2 && dispatchesToGray(args.get(1))) {
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
		this.globalEnv.defineFunction(LispNames.READ_BYTE, new LispFunction(LispNames.READ_BYTE, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 0);
			if (!args.isEmpty() && dispatchesToGray(args.get(0))) {
				return applyGrayDispatch(GRAY_READ_BYTE_DISPATCH,
						List.of(args.get(0), args.size() >= 2 ? args.get(1) : LispTrue.INSTANCE,
								args.size() >= 3 ? args.get(2) : LispNil.INSTANCE));
			}
			return apply(baseReadByte, args, this.globalEnv);
		}));
		LispVal baseReadChar = this.globalEnv.lookupFunction(LispNames.READ_CHAR);
		this.globalEnv.defineFunction(LispNames.READ_CHAR, new LispFunction(LispNames.READ_CHAR, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 0);
			if (!args.isEmpty() && dispatchesToGray(args.get(0))) {
				return applyGrayDispatch(GRAY_READ_CHAR_DISPATCH,
						List.of(args.get(0), args.size() >= 2 ? args.get(1) : LispTrue.INSTANCE,
								args.size() >= 3 ? args.get(2) : LispNil.INSTANCE));
			}
			return apply(baseReadChar, args, this.globalEnv);
		}));
		LispVal baseReadLine = this.globalEnv.lookupFunction(LispNames.READ_LINE);
		this.globalEnv.defineFunction(LispNames.READ_LINE, new LispFunction(LispNames.READ_LINE, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 0);
			if (!args.isEmpty() && dispatchesToGray(args.get(0))) {
				// eof-error-p defaults to NIL, the read-line lite convention the
				// handle-based built-in documents.
				return applyGrayDispatch(GRAY_READ_LINE_DISPATCH,
						List.of(args.get(0), args.size() >= 2 ? args.get(1) : LispNil.INSTANCE,
								args.size() >= 3 ? args.get(2) : LispNil.INSTANCE));
			}
			return apply(baseReadLine, args, this.globalEnv);
		}));
		LispVal baseReadCharNoHang = this.globalEnv.lookupFunction(LispNames.READ_CHAR_NO_HANG);
		this.globalEnv.defineFunction(LispNames.READ_CHAR_NO_HANG,
				new LispFunction(LispNames.READ_CHAR_NO_HANG, rawArgs -> {
					List<LispVal> args = resolveStreamArg(rawArgs, 0);
					if (!args.isEmpty() && dispatchesToGray(args.get(0))) {
						return applyGrayDispatch(GRAY_READ_CHAR_NO_HANG_DISPATCH,
								List.of(args.get(0), args.size() >= 2 ? args.get(1) : LispTrue.INSTANCE,
										args.size() >= 3 ? args.get(2) : LispNil.INSTANCE));
					}
					return apply(baseReadCharNoHang, args, this.globalEnv);
				}));
		// peek-char's stream is argument ONE (the peek-type precedes it), and the
		// peek-type travels into the helper: the skipping forms are looped there, over
		// stream-peek-char and the protocol's pushback, rather than by the built-in's own
		// %peek-char loop, which cannot see an instance.
		LispVal basePeekChar = this.globalEnv.lookupFunction(LispNames.PEEK_CHAR);
		this.globalEnv.defineFunction(LispNames.PEEK_CHAR, new LispFunction(LispNames.PEEK_CHAR, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 1);
			if (args.size() >= 2 && dispatchesToGray(args.get(1))) {
				return applyGrayDispatch(GRAY_PEEK_CHAR_DISPATCH,
						List.of(args.get(0), args.get(1), args.size() >= 3 ? args.get(2) : LispTrue.INSTANCE,
								args.size() >= 4 ? args.get(3) : LispNil.INSTANCE));
			}
			return apply(basePeekChar, args, this.globalEnv);
		}));
		LispVal baseUnreadChar = this.globalEnv.lookupFunction(LispNames.UNREAD_CHAR);
		this.globalEnv.defineFunction(LispNames.UNREAD_CHAR, new LispFunction(LispNames.UNREAD_CHAR, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 1);
			if (args.size() == 2 && dispatchesToGray(args.get(1))) {
				return applyGrayDispatch(GRAY_UNREAD_CHAR_DISPATCH, List.of(args.get(0), args.get(1)));
			}
			return apply(baseUnreadChar, args, this.globalEnv);
		}));
		// open-stream-p / stream-element-type: the close rule, applied to the other two
		// operators CL spells as plain functions a stream class may own with a defmethod
		// of its own (dexador's decoding-stream defines both). The Gray default answers
		// only while the program registers no generic for the name, which is exactly the
		// condition GrayStreamsLibrary.OWNABLE_OPERATORS checks on the compile path.
		wrapGrayOwnableOperator(LispNames.OPEN_STREAM_P, GRAY_OPEN_STREAM_P_DISPATCH);
		// input-stream-p / output-stream-p answer the DIRECTION a Gray instance was
		// built with -- a typep against the two direction base classes, not a predicate
		// generic per class. Ownable like the other three: a class may define a method
		// on either name, and the Gray default steps aside for it.
		wrapGrayOwnableOperator(LispNames.INPUT_STREAM_P, GRAY_INPUT_STREAM_P_DISPATCH);
		wrapGrayOwnableOperator(LispNames.OUTPUT_STREAM_P, GRAY_OUTPUT_STREAM_P_DISPATCH);
		wrapGrayOwnableOperator(LispNames.STREAM_ELEMENT_TYPE, GRAY_STREAM_ELEMENT_TYPE_DISPATCH);
		// streamp: a Gray stream IS a stream in Common Lisp, so the FUNCTION VALUE has to
		// answer what the operator form's lowering answers. The lowering bakes the
		// registry's descendant tags; this one reads them at call time, which is the same
		// set.
		LispVal baseStreamp = this.globalEnv.lookupFunction(LispNames.STREAMP);
		this.globalEnv.defineFunction(LispNames.STREAMP, new LispFunction(LispNames.STREAMP, args -> {
			if (args.size() == 1 && isGrayStreamInstance(args.get(0))) {
				return LispTrue.INSTANCE;
			}
			return apply(baseStreamp, args, this.globalEnv);
		}));
		LispVal baseWriteByte = this.globalEnv.lookupFunction(LispNames.WRITE_BYTE);
		this.globalEnv.defineFunction(LispNames.WRITE_BYTE, new LispFunction(LispNames.WRITE_BYTE, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 1);
			if (args.size() == 2 && dispatchesToGray(args.get(1))) {
				return applyGrayDispatch(GRAY_WRITE_BYTE_DISPATCH, List.of(args.get(0), args.get(1)));
			}
			return apply(baseWriteByte, args, this.globalEnv);
		}));
		LispVal baseListen = this.globalEnv.lookupFunction(LispNames.LISTEN);
		this.globalEnv.defineFunction(LispNames.LISTEN, new LispFunction(LispNames.LISTEN, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 0);
			if (args.size() == 1 && dispatchesToGray(args.get(0))) {
				return applyGrayDispatch(GRAY_LISTEN_DISPATCH, List.of(args.get(0)));
			}
			return apply(baseListen, args, this.globalEnv);
		}));
		LispVal baseFilePosition = this.globalEnv.lookupFunction(LispNames.FILE_POSITION);
		this.globalEnv.defineFunction(LispNames.FILE_POSITION, new LispFunction(LispNames.FILE_POSITION, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 0);
			if (!args.isEmpty() && dispatchesToGray(args.get(0))) {
				if (args.size() == 1) {
					return applyGrayDispatch(GRAY_FILE_POSITION_DISPATCH, List.of(args.get(0)));
				}
				if (args.size() == 2) {
					return applyGrayDispatch(GRAY_FILE_POSITION_SET_DISPATCH, List.of(args.get(0), args.get(1)));
				}
			}
			return apply(baseFilePosition, args, this.globalEnv);
		}));
		// The line-oriented and print-family output operators: the same
		// instance test, the same helpers the compile-path rewrite targets. Without
		// these, exactly the two write generics reached a Gray instance and everything
		// else -- terpri, fresh-line, write-line, princ/prin1/print,
		// force-output/finish-output/clear-output -- signalled "not an output stream" on
		// the interpreter and wrote PAST the instance on the compile paths.
		wrapGrayOutputOperator(LispNames.TERPRI, 0, GRAY_TERPRI_DISPATCH);
		wrapGrayOutputOperator(LispNames.FRESH_LINE, 0, GRAY_FRESH_LINE_DISPATCH);
		wrapGrayOutputOperator(LispNames.FORCE_OUTPUT, 0, GRAY_FORCE_OUTPUT_DISPATCH);
		wrapGrayOutputOperator(LispNames.FINISH_OUTPUT, 0, GRAY_FINISH_OUTPUT_DISPATCH);
		wrapGrayOutputOperator(LispNames.CLEAR_OUTPUT, 0, GRAY_CLEAR_OUTPUT_DISPATCH);
		wrapGrayOutputOperator(LispNames.WRITE_LINE, 1, GRAY_WRITE_LINE_DISPATCH);
		wrapGrayOutputOperator(LispNames.PRINC, 1, GRAY_PRINC_DISPATCH);
		wrapGrayOutputOperator(LispNames.PRIN1, 1, GRAY_PRIN1_DISPATCH);
		wrapGrayOutputOperator(LispNames.PRINT, 1, GRAY_PRINT_DISPATCH);
		// *print-case* as a FIRST-CLASS value: (mapcar #'princ-to-string names) under a
		// :downcase binding never reaches the operator seam in evalConsRareOperator, so
		// the case route has to sit in the function value too -- the compile paths get it
		// for free (a #'-reference compiles to a wrapper defun whose body IS the operator
		// form, which the seam rewrites). Wrapped AFTER the Gray wrappers, so the
		// no-case path still reaches them and the rewritten write-string is Gray-aware.
		for (String printer : List.of(LispNames.PRINC, LispNames.PRIN1, LispNames.PRINT, LispNames.PRINC_TO_STRING,
				LispNames.PRIN1_TO_STRING, LispNames.WRITE_TO_STRING)) {
			wrapPrintCaseOperator(printer);
		}
		// close is the one operator a program can legitimately own: CL spells a stream's
		// close as a method on CLOSE ITSELF, and a defmethod on a built-in name already
		// dispatches on every backend (.kb/clos.md) -- fast-io's stream classes do
		// exactly that. The Gray default therefore stands down as soon as the program
		// registers a close generic, which is the same condition the compile-path rewrite
		// checks, so the two seams agree. Deliberately NOT synonym-resolved either:
		// closing a synonym stream closes the SYNONYM, not the stream it forwards to
		// (CLHS 21.1.3), which the built-in already answers t for -- and the helper's
		// instance arm answers the same. An OPEN stream is an instance too and is the one
		// kind that must NOT take the Gray arm -- it is what close really has work to do
		// for -- so it goes to the built-in by tag. The :abort tail is accepted and
		// ignored, like the built-in's.
		LispVal baseClose = this.globalEnv.lookupFunction(LispNames.CLOSE);
		this.globalEnv.defineFunction(LispNames.CLOSE, new LispFunction(LispNames.CLOSE, args -> {
			boolean closeable = args.size() == 1
					|| (args.size() == 3 && args.get(1) instanceof LispSymbol kw && ":ABORT".equals(kw.name()));
			if (closeable && dispatchesToGray(args.get(0)) && this.closRegistry.findGeneric(LispNames.CLOSE) == null) {
				return applyGrayDispatch(GRAY_CLOSE_DISPATCH, List.of(args.get(0)));
			}
			return apply(baseClose, args, this.globalEnv);
		}));
		// %probe-file: mediated by the SourceLoader rather than java.nio.file.Files, so a
		// host without a filesystem (the browser playground's in-memory loader) answers
		// from whatever IT can load. Working-directory-relative like open, not resolved
		// against the load stack. String-in/string-out; the public probe-file is prelude
		// Lisp that coerces a pathname argument and wraps the answer in a pathname value.
		this.globalEnv.defineFunction(LispNames.PROBE_FILE_INTERNAL,
				new LispFunction(LispNames.PROBE_FILE_INTERNAL, args -> {
					if (args.size() != 1) {
						throw new LispEvalException(
								LispNames.PROBE_FILE_INTERNAL + " expects 1 argument, got " + args.size());
					}
					if (!(args.get(0) instanceof LispString path)) {
						throw new LispEvalException(LispNames.PROBE_FILE_INTERNAL + " expects a string pathname");
					}
					// The truename is the namestring itself (see LispNames.PROBE_FILE).
					return this.sourceLoader.exists(path.value()) ? path : LispNil.INSTANCE;
				}));
		// file-write-date: the same SourceLoader mediation as %probe-file, for the same
		// reason -- a host without a filesystem has no modification times and answers the
		// nil Common Lisp already prescribes for "cannot be determined".
		this.globalEnv.defineFunction(LispNames.FILE_WRITE_DATE, new LispFunction(LispNames.FILE_WRITE_DATE, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.FILE_WRITE_DATE + " expects 1 argument, got " + args.size());
			}
			String path = PathnameOps.designatorNamestring(args.get(0));
			if (path == null) {
				throw new LispEvalException(LispNames.FILE_WRITE_DATE + " expects a pathname designator");
			}
			Long universal = this.sourceLoader.writeDate(path);
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
		// %host-argv: the program's own argument vector as a list of strings, argv0
		// first. It lives HERE rather than in Environment because the value is state the
		// caller supplies (setCommandLineArguments) -- an embedded run has no command
		// line and answers nil. The five public uiop/image names are Lisp over it
		// (uiop-image.lisp), so command-line-arguments is (rest (%host-argv)) on all four
		// backends.
		this.globalEnv.defineFunction(LispNames.HOST_ARGV, new LispFunction(LispNames.HOST_ARGV, args -> {
			if (!args.isEmpty()) {
				throw new LispEvalException(LispNames.HOST_ARGV + " expects no arguments, got " + args.size());
			}
			LispVal argv = LispNil.INSTANCE;
			for (int i = this.commandLineArguments.size() - 1; i >= 0; i--) {
				argv = new LispCons(new LispString(this.commandLineArguments.get(i)), argv);
			}
			return argv;
		}));
		// uiop:add-package-local-nickname -- lite: registers a GLOBAL nickname (no
		// per-package scoping); the mechanism libraries recommend for shortening long
		// package names (jzon's README: (uiop:add-package-local-nickname '#:jzon
		// '#:com.inuoe.jzon)). The optional third argument (the package to scope the
		// nickname to) is accepted and ignored.
		String addNicknameName = UiopExports.qualified(LispNames.ADD_PACKAGE_LOCAL_NICKNAME);
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
				case LispSymbol sym -> sym.isKeyword() || LispNames.PACKAGE_VAR.equals(sym.name())
						|| this.dynamicBindings.isBound(sym.name()) || this.globalEnv.hasBinding(sym.name())
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
					if (LispNames.PACKAGE_VAR.equals(sym.name())) {
						yield currentPackageValue();
					}
					if (this.dynamicBindings.isBound(sym.name())) {
						yield this.dynamicBindings.get(sym.name());
					}
					LispVal value = this.globalEnv.lookupOrNull(sym.name());
					if (value == null) {
						throw LispEvalException.ofClass(ClosRegistry.UNBOUND_VARIABLE_CLASS_NAME,
								ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX + sym.name()
										+ ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX);
					}
					yield value;
				}
				default -> throw new LispEvalException(
						LispNames.SYMBOL_VALUE + " expects a symbol, got " + args.get(0).print());
			};
		}));
		// (make-synonym-stream 'sym): the synonym-stream VALUE -- an instance of the
		// fixed LispLayout.SYNONYM_STREAM layout holding the symbol, plus (in the cell
		// reserved beside it) a reader closure answering that variable's CURRENT value.
		// Every stream-designator resolution calls that reader, which is what makes the
		// forwarding per-operation and dynamic-binding aware. The compiled backends
		// build the same value from a compiled (lambda () sym) --
		// LispMacroExpander.expandMakeSynonymStream.
		this.globalEnv.defineFunction(LispNames.MAKE_SYNONYM_STREAM,
				new LispFunction(LispNames.MAKE_SYNONYM_STREAM, args -> {
					requireSingleArg(LispNames.MAKE_SYNONYM_STREAM, args);
					if (!(args.get(0) instanceof LispSymbol sym) || sym.isKeyword()) {
						throw new LispEvalException(
								LispNames.MAKE_SYNONYM_STREAM + " expects a symbol, got " + args.get(0).print());
					}
					LispFunction reader = new LispFunction(LispNames.STREAM_TARGET,
							ignored -> symbolValueOf(sym.name()));
					return new LispInstance(LispLayout.SYNONYM_STREAM, new LispVal[] { sym, reader });
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
			removeUserMacro(sym.name());
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
					removeUserMacro(sym.name());
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
						throw LispEvalException.ofClass(ClosRegistry.UNDEFINED_FUNCTION_CLASS_NAME,
								ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX + sym.name()
										+ ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX);
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
		// The SECOND value of find-symbol/intern, lowered beside the primary one by a
		// multiple-value consumer. Every arm mirrors an arm of find-symbol above, so the
		// two answer nil on exactly the same names -- CL's invariant, and the reason this
		// is one function rather than a status flag threaded through the spill.
		this.globalEnv.defineFunction(LispNames.FIND_SYMBOL_STATUS,
				new LispFunction(LispNames.FIND_SYMBOL_STATUS, args -> {
					if (args.size() == 2) {
						if (!(args.get(0) instanceof LispString str)) {
							throw new LispEvalException(
									LispNames.FIND_SYMBOL + " expects a string, got " + args.get(0).print());
						}
						if (args.get(1) instanceof LispNil) {
							return LispNil.INSTANCE;
						}
						String designator = packageDesignator(LispNames.FIND_SYMBOL, args.get(1));
						String pkgName = this.packageResolver.findPackageName(designator);
						if (pkgName == null) {
							return LispNil.INSTANCE;
						}
						String status = this.packageResolver.memberStatus(designator, str.value());
						if (status != null) {
							return new LispSymbol(status);
						}
						// A definition IS an interning: a defun registered under the
						// package's canonical spelling is internal to it.
						String candidate = LispNames.CL_USER_PKG.equals(pkgName) ? str.value()
								: PackageRegistry.qualifyInternal(pkgName, str.value());
						return definedInImage(candidate) ? new LispSymbol(LispNames.STATUS_INTERNAL) : LispNil.INSTANCE;
					}
					requireSingleArg(LispNames.FIND_SYMBOL, args);
					if (!(args.get(0) instanceof LispString str)) {
						throw new LispEvalException(
								LispNames.FIND_SYMBOL + " expects a string, got " + args.get(0).print());
					}
					String name = str.value();
					if (!name.isEmpty() && name.charAt(0) == ':') {
						return new LispSymbol(LispNames.STATUS_EXTERNAL);
					}
					if (PackageRegistry.isClSymbol(name)) {
						String status = this.packageResolver.memberStatus(this.packageResolver.currentPackageName(),
								name);
						return new LispSymbol(status != null ? status : LispNames.STATUS_INHERITED);
					}
					if (definedInImage(name)) {
						return new LispSymbol(LispNames.STATUS_INTERNAL);
					}
					String spelling = this.packageResolver.internSpelling(name);
					return !spelling.equals(name) && definedInImage(spelling)
							? new LispSymbol(LispNames.STATUS_INTERNAL) : LispNil.INSTANCE;
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
		// list-all-packages / package-use-list / package-used-by-list: the registry
		// queries, over the LIVE registry (so a package created after this program was
		// read counts). A "package" is its upcased canonical name as a keyword, so the
		// listings are lists of those. The compile paths answer the same three from the
		// use table baked in at compile time (LispMacroExpander.expandPackageQuery).
		this.globalEnv.defineFunction(LispNames.LIST_ALL_PACKAGES,
				new LispFunction(LispNames.LIST_ALL_PACKAGES, args -> {
					if (!args.isEmpty()) {
						throw new LispEvalException(
								LispNames.LIST_ALL_PACKAGES + " expects 0 arguments, got " + args.size());
					}
					return packageKeywordList(this.packageResolver.runtimePackageUseTable().keySet());
				}));
		this.globalEnv.defineFunction(LispNames.PACKAGE_USE_LIST, new LispFunction(LispNames.PACKAGE_USE_LIST, args -> {
			requireSingleArg(LispNames.PACKAGE_USE_LIST, args);
			return packageKeywordList(packageUseEntry(LispNames.PACKAGE_USE_LIST,
					packageDesignator(LispNames.PACKAGE_USE_LIST, args.get(0))));
		}));
		this.globalEnv.defineFunction(LispNames.PACKAGE_USED_BY_LIST,
				new LispFunction(LispNames.PACKAGE_USED_BY_LIST, args -> {
					requireSingleArg(LispNames.PACKAGE_USED_BY_LIST, args);
					String name = packageName(LispNames.PACKAGE_USED_BY_LIST,
							packageDesignator(LispNames.PACKAGE_USED_BY_LIST, args.get(0)));
					List<String> users = new ArrayList<>();
					this.packageResolver.runtimePackageUseTable().forEach((pkg, used) -> {
						if (used.contains(name)) {
							users.add(pkg);
						}
					});
					return packageKeywordList(users);
				}));
		// import: the same split as use-package/export -- a literal top-level call is
		// consumed by the PackageResolver (so it works on every backend), and this
		// runtime binding serves the computed calls only the interpreter can run,
		// resolving against the very same resolver so it takes effect for the forms read
		// after it.
		this.globalEnv.defineFunction(LispNames.IMPORT, new LispFunction(LispNames.IMPORT, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.IMPORT + " expects 1 or 2 arguments, got " + args.size());
			}
			List<String> symbols = new ArrayList<>();
			// A symbol or a LIST of symbols, like CL.
			if (args.get(0) instanceof LispCons list) {
				for (LispVal element : list.toList()) {
					symbols.add(importSpelling(element));
				}
			}
			else if (!(args.get(0) instanceof LispNil)) {
				symbols.add(importSpelling(args.get(0)));
			}
			String target = args.size() == 2 ? packageNameDesignator(LispNames.IMPORT, args.get(1))
					: this.packageResolver.currentPackageName();
			this.packageResolver.importSymbols(symbols, target);
			return LispTrue.INSTANCE;
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
		// %future-force: the FUNCTION spelling of await's resolve, for synchronous
		// boundaries (the http-reactor transport resolving a future-valued application
		// answer). A function, not a special form, so the lexical await-placement rule
		// does not apply; a non-future passes through, like await.
		String futureForceName = LispNames.FUTURE_FORCE_QUALIFIED;
		this.globalEnv.defineFunction(futureForceName, new LispFunction(futureForceName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(
						LispNames.FUTURE_FORCE_INTERNAL + " expects 1 argument, got " + args.size());
			}
			return awaitValue(args.get(0));
		}));
		// %stream-new: the from-thunk stream constructor every backend shares -- a read
		// thunk, a close thunk and a drained flag is all a stream IS. Here rather than in
		// Environment for the %async-run reason: pulling a chunk means APPLYING a Lisp
		// function. The resolve is the evaluator's too, so LispStream never sees a future
		// -- a thunk that answers one (an async-lambda, a suspending host import on the
		// WASM tiers) settles here, at the read, exactly where the WASM tiers resolve it.
		String streamNewName = LispNames.STREAM_NEW_INTERNAL_QUALIFIED;
		this.globalEnv.defineFunction(streamNewName, new LispFunction(streamNewName, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.STREAM_NEW_INTERNAL + " expects 2 arguments, got " + args.size());
			}
			LispVal readFn = args.get(0);
			LispVal closeFn = args.get(1);
			return LispStream.pull(() -> awaitValue(apply(readFn, List.of(), this.globalEnv)),
					() -> apply(closeFn, List.of(), this.globalEnv));
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
		String currentThreadName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.CURRENT_THREAD);
		this.globalEnv.defineFunction(currentThreadName, new LispFunction(currentThreadName, args -> {
			if (!args.isEmpty()) {
				throw new LispEvalException(LispNames.CURRENT_THREAD + " expects no arguments, got " + args.size());
			}
			return AsyncRuntime.currentThreadHandle();
		}));
		// http-handler lives here rather than in Environment because serving a request
		// applies the handler function, which needs the evaluator's apply. It runs a
		// blocking embedded HTTP server; the handler receives a CLACK ENVIRONMENT plist
		// and returns a CLACK RESPONSE, (status headers [body]) -- the shape is declared
		// once, in http-server.lisp, for every backend. When compiled with --component
		// the same directive instead exports wasi:http/handler (see the WASM compiler).
		String httpHandlerName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.HTTP_HANDLER);
		this.globalEnv.defineFunction(httpHandlerName, new LispFunction(httpHandlerName, args -> {
			if (args.isEmpty() || args.size() > 4) {
				throw new LispEvalException(LispNames.HTTP_HANDLER + " expects 1 to 4 arguments, got " + args.size());
			}
			int port = 8080;
			if (args.size() >= 2 && !(args.get(1) instanceof LispSymbol)) {
				if (!(args.get(1) instanceof LispInteger portArg)) {
					throw new LispEvalException(
							LispNames.HTTP_HANDLER + " expects an integer port, got: " + args.get(1).print());
				}
				port = (int) portArg.value();
			}
			boolean bufferBody = httpHandlerBufferBody(args);
			final LispVal handler = args.get(0);
			ensureHttpServerLoaded();
			try {
				RontoHttpServer.serve(port, request -> invokeHttpHandler(handler, request, bufferBody));
			}
			catch (RontoHttpServer.ServerException ex) {
				// The server lives in the TRAVELLING runtime package, which carries no
				// rontolisp import and so cannot raise a Lisp error itself; the
				// interpreter is where its failure becomes one and handler-case can see
				// it.
				throw new LispEvalException(ex.reason());
			}
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
			if (args.size() < 3 || args.size() > 5) {
				throw new LispEvalException(LispNames.HTTP_SERVER_START
						+ " expects (handler port address [:raw-body mode]), got " + args.size());
			}
			boolean bufferBody = httpHandlerBufferBody(args);
			final LispVal handler = args.get(0);
			if (!(args.get(1) instanceof LispInteger portArg)) {
				throw new LispEvalException(
						LispNames.HTTP_SERVER_START + " expects an integer port, got: " + args.get(1).print());
			}
			// "" is the wildcard: the server takes anything that is not a non-empty
			// string that way, which is also how compiled bytecode's nil (a real null)
			// arrives there.
			String address = switch (args.get(2)) {
				case LispString str -> str.value();
				case LispNil ignored -> "";
				default -> throw new LispEvalException(LispNames.HTTP_SERVER_START
						+ " expects a string (or nil) address, got: " + args.get(2).print());
			};
			ensureHttpServerLoaded();
			final long handle;
			try {
				handle = RontoHttpServer.startServer((int) portArg.value(), address,
						request -> invokeHttpHandler(handler, request, bufferBody));
			}
			catch (RontoHttpServer.ServerException ex) {
				throw new LispEvalException(ex.reason());
			}
			return new LispInteger(handle);
		}));
		String httpServerJoinName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.HTTP_SERVER_JOIN);
		this.globalEnv.defineFunction(httpServerJoinName, new LispFunction(httpServerJoinName, args -> {
			RontoHttpServer.joinServer(requireHttpServerHandle(LispNames.HTTP_SERVER_JOIN, args));
			return LispNil.INSTANCE;
		}));
		String httpServerStopName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.HTTP_SERVER_STOP);
		this.globalEnv.defineFunction(httpServerStopName, new LispFunction(httpServerStopName, args -> {
			RontoHttpServer.stopServer(requireHttpServerHandle(LispNames.HTTP_SERVER_STOP, args));
			return LispNil.INSTANCE;
		}));
		String httpServerPortName = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
				LispNames.HTTP_SERVER_PORT);
		this.globalEnv.defineFunction(httpServerPortName, new LispFunction(httpServerPortName, args -> {
			return new LispInteger(
					RontoHttpServer.serverPort(requireHttpServerHandle(LispNames.HTTP_SERVER_PORT, args)));
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
					// CL: an empty sequence with no :initial-value calls the function
					// with
					// ZERO arguments and returns that -- (reduce #'append '()) is nil,
					// not
					// an error. The compile paths get the same rule from the shared
					// LispMacroExpander.expandReduce guard.
					return apply(args.get(0), List.of(), this.globalEnv);
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
			RuntimeTest test = runtimeTest(args, 2);
			LispVal keyFn = optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD);
			LispVal item = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				LispVal elem = (keyFn == null) ? cell.car() : apply(keyFn, List.of(cell.car()), this.globalEnv);
				if (testMatches(test, item, elem)) {
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
			RuntimeTest test = runtimeTest(args, 2);
			LispVal keyFn = optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD);
			LispVal key = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (cell.car() instanceof LispCons pair) {
					LispVal elem = (keyFn == null) ? pair.car() : apply(keyFn, List.of(pair.car()), this.globalEnv);
					if (testMatches(test, key, elem)) {
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
			RuntimeTest test = runtimeTest(args, 2);
			LispVal keyFn = optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD);
			LispVal value = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (cell.car() instanceof LispCons pair) {
					LispVal elem = (keyFn == null) ? pair.cdr() : apply(keyFn, List.of(pair.cdr()), this.globalEnv);
					if (testMatches(test, value, elem)) {
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
			// A string/vector argument sorts as a list of its elements and is rebuilt
			// back in its own representation, matching the SORT builtin above and the
			// (stable-sort ...) call-position macro expansion.
			return Environment.seqResult(args.get(0), result);
		}));
		this.globalEnv.defineFunction(LispNames.APPLY, new LispFunction(LispNames.APPLY, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.APPLY + " expects at least 2 arguments, got " + args.size());
			}
			return applyValues(args);
		}));
		this.globalEnv.defineFunction(LispNames.LOAD, new LispFunction(LispNames.LOAD, args -> {
			if (args.isEmpty() || args.size() % 2 == 0) {
				throw new LispEvalException(LispNames.LOAD + " expects a pathname and :option value pairs, got "
						+ args.size() + " arguments");
			}
			String path = PathnameOps.designatorNamestring(args.get(0));
			if (path == null) {
				throw new LispEvalException(LispNames.LOAD + " expects a pathname designator");
			}
			// CL's keyword options. :verbose and :print ask for progress output this
			// load does not produce and :external-format for a decoder that does not
			// exist (every backend reads UTF-8), so those three are accepted and
			// ignored; :if-does-not-exist is real. The compile paths lower the same
			// four in LispMacroExpander.lowerLoadOptions -- keep the two in step.
			boolean errorIfMissing = true;
			for (int i = 1; i < args.size(); i += 2) {
				if (!(args.get(i) instanceof LispSymbol key) || !key.name().startsWith(":")) {
					throw new LispEvalException(LispNames.LOAD + " expects :option value pairs");
				}
				switch (key.name()) {
					case ":VERBOSE", ":PRINT", ":EXTERNAL-FORMAT" -> {
					}
					case ":IF-DOES-NOT-EXIST" -> errorIfMissing = isTruthy(args.get(i + 1));
					default -> throw new LispEvalException(LispNames.LOAD + ": unsupported option " + key.name());
				}
			}
			if (!errorIfMissing && !sourceReadable(path)) {
				return LispNil.INSTANCE;
			}
			loadFile(LispNames.LOAD, path);
			return LispTrue.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.PROVIDE, new LispFunction(LispNames.PROVIDE, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.PROVIDE + " expects 1 argument, got " + args.size());
			}
			String name = moduleDesignator(LispNames.PROVIDE, args.get(0));
			// A duplicate provide is a no-op, like Common Lisp. Module names go onto
			// *modules* as STRINGS, which is what CL specifies and what a (member "X"
			// *modules* :test #'string=) probe expects.
			if (!providedModules().contains(name)) {
				this.globalEnv.set(LispNames.MODULES_VAR,
						new LispCons(new LispString(name), this.globalEnv.lookup(LispNames.MODULES_VAR)));
			}
			return new LispSymbol(name);
		}));
		this.globalEnv.defineFunction(LispNames.REQUIRE, new LispFunction(LispNames.REQUIRE, args -> {
			if (args.size() != 1 && args.size() != 2) {
				throw new LispEvalException(LispNames.REQUIRE + " expects 1 or 2 arguments, got " + args.size());
			}
			String name = moduleDesignator(LispNames.REQUIRE, args.get(0));
			if (!providedModules().contains(name)) {
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
			String name = asdfDesignator(LispNames.ASDF_LOAD_SYSTEM, args.get(0));
			loadSystem(name);
			return new LispSymbol(name);
		}));
		// The runtime component metaobjects (asdf.lisp / AsdfRuntimeLibrary) define
		// asdf:find-system, the component readers, registered-systems,
		// system-source-directory, system-relative-pathname and component-pathname in
		// Lisp source shared with the compile paths; the interpreter's halves of the
		// per-backend seam are these two primitives over the LIVE per-evaluator
		// registry, which loadSystem populates before invoking a system's component
		// files -- so find-system is guaranteed to hit for the system currently
		// loading. A built-in shim system is findable even before it is loaded:
		// lack's find-package-or-load probes (asdf:find-system name nil) and loads on
		// a hit -- the route by which (clackup app :server :rontolisp) pulls in the
		// clack-handler-rontolisp backend at run time.
		String systemRecordName = "%ASDF-SYSTEM-RECORD";
		this.globalEnv.defineFunction(systemRecordName, new LispFunction(systemRecordName, args -> {
			if (args.size() != 1 || !(args.get(0) instanceof LispString key)) {
				throw new LispEvalException(systemRecordName + " expects a system name string");
			}
			AsdfSystems.LispSystem system = this.asdfSystems.get(key.value());
			if (system != null) {
				return AsdfRuntimeLibrary.recordFor(system, this.loadedSystems.contains(key.value()));
			}
			if (BuiltinSystems.isBuiltin(key.value())) {
				return AsdfRuntimeLibrary.builtinRecord(this.loadedSystems.contains(key.value()));
			}
			return LispNil.INSTANCE;
		}));
		String systemNamesName = "%ASDF-SYSTEM-NAMES";
		this.globalEnv.defineFunction(systemNamesName, new LispFunction(systemNamesName, args -> {
			if (!args.isEmpty()) {
				throw new LispEvalException(systemNamesName + " expects no arguments");
			}
			// Declared systems in registration order, then any loaded built-in shims
			// the registry does not list.
			java.util.List<LispVal> names = new java.util.ArrayList<>();
			for (String registered : this.asdfSystems.keySet()) {
				names.add(new LispString(registered));
			}
			for (String loadedName : this.loadedSystems) {
				if (!this.asdfSystems.containsKey(loadedName) && BuiltinSystems.isBuiltin(loadedName)) {
					names.add(new LispString(loadedName));
				}
			}
			LispVal tail = LispNil.INSTANCE;
			for (int i = names.size() - 1; i >= 0; i--) {
				tail = new LispCons(names.get(i), tail);
			}
			return tail;
		}));
		// asdf:test-system: load the system, follow its :in-order-to test-op chain,
		// then run its recorded :perform (test-op (o c) BODY) with the operation nil
		// and the component bound to the system's metaobject (fukamachi's .asd shape:
		// :perform (test-op (op c) (symbol-call :rove :run c))).
		String testSystemName = PackageRegistry.qualify(LispNames.ASDF_PKG, LispNames.TEST_SYSTEM);
		this.globalEnv.defineFunction(testSystemName, new LispFunction(testSystemName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.ASDF_TEST_SYSTEM + " expects 1 argument, got " + args.size());
			}
			ensureAsdfRuntimeLoaded();
			String name = asdfDesignator(LispNames.ASDF_TEST_SYSTEM, args.get(0));
			runTestOp(name, new java.util.HashSet<>());
			return LispTrue.INSTANCE;
		}));
		// merge-pathnames* / file-exists-p / native-namestring used to be Java built-ins
		// here. They are Lisp source now (uiop-pathname.lisp, uiop-filesystem.lisp),
		// which
		// is what gives them to the JVM and both WASM backends as well: merge-pathnames*
		// with non-literal arguments was "The function UIOP:MERGE-PATHNAMES* is
		// undefined"
		// on all three. The interpreter lazy-loads them like any other uiop definition.
		// uiop:get-pathname-defaults retired its Java built-in the same way: it is
		// Lisp source in uiop-filesystem.lisp now, reading the
		// *default-pathname-defaults* special instead of answering the literal ""
		// that predated it.
		// uiop:symbol-call -- real UIOP's late-binding call: look NAME up in PACKAGE at
		// run time and apply it to the remaining arguments. The interpreter can do this
		// for real (the resolver knows every package's members and the global function
		// table is live); the compile backends have no runtime name-to-function table,
		// so there the call lowers to the generic uiop call-time error instead. lack's
		// find-package-or-load reaches it only on the quicklisp branch, which rontolisp
		// never takes (:quicklisp is not in *features*, so the asdf branch runs).
		String symbolCallName = UiopExports.qualified(LispNames.SYMBOL_CALL);
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
		// ql-dist:install-dist adds a Quicklisp-format distribution -- Ultralisp, or any
		// distinfo URL -- to the dists quickload downloads from, searched after the ones
		// already installed. Returns the dist name, and installing twice is a no-op.
		String installDistName = PackageRegistry.qualify(LispNames.QL_DIST_PKG, LispNames.INSTALL_DIST);
		this.globalEnv.defineFunction(installDistName, new LispFunction(installDistName, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.QL_DIST_INSTALL_DIST + " expects 1 argument, got 0");
			}
			// (ql-dist:install-dist "..." :prompt nil) -- real Quicklisp asks before it
			// downloads; nothing here prompts, so the options are ignored like
			// quickload's.
			ignoreLoadOptions(LispNames.QL_DIST_INSTALL_DIST, args.subList(1, args.size()));
			String spec = AsdfSystems.designator(LispNames.QL_DIST_INSTALL_DIST, args.get(0));
			try {
				return new LispString(distClient().installDist(spec));
			}
			catch (IllegalArgumentException ex) {
				throw new LispEvalException(LispNames.QL_DIST_INSTALL_DIST + ": " + ex.getMessage());
			}
		}));
		// ql:update-dist drops a dist's cached indexes: the next quickload re-reads
		// systems.txt/releases.txt and so sees the releases published since.
		String updateDistName = PackageRegistry.qualify(LispNames.QL_PKG, LispNames.UPDATE_DIST);
		this.globalEnv.defineFunction(updateDistName, new LispFunction(updateDistName, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.QL_UPDATE_DIST + " expects 1 argument, got 0");
			}
			ignoreLoadOptions(LispNames.QL_UPDATE_DIST, args.subList(1, args.size()));
			String name = AsdfSystems.designator(LispNames.QL_UPDATE_DIST, args.get(0));
			try {
				distClient().updateDist(name);
			}
			catch (IOException ex) {
				throw new LispEvalException(LispNames.QL_UPDATE_DIST + ": " + ex.getMessage());
			}
			return new LispString(name);
		}));
		registerJava();
		registerObjc();
		registerFfi();
	}

	// Registers the interpreter side of the `objc` package (eval/ObjcInterop over
	// am.ik.objc, the foreign-function binding to the Objective-C runtime). Registered
	// here beside java: for the same reason -- a callback (a button's action) applies a
	// user function and so needs the evaluator's apply. Unlike java: it needs no
	// reflection, so it works in the native binary; every compiler refuses it
	// (CompileFrontend).
	private void registerObjc() {
		ObjcInterop.register(this.globalEnv, (function, callArgs) -> apply(function, callArgs, this.globalEnv));
	}

	// Registers the interpreter side of the `ffi` package (eval/FfiInterop over
	// am.ik.ffi, the foreign-function binding to plain C -- the primitives CFFI's
	// backend stands on). Registered here beside objc: for the same reason -- a callback
	// C calls applies a user function and so needs the evaluator's apply. No reflection,
	// so it works in the native binary; both WASM backends refuse it (CompileFrontend).
	private void registerFfi() {
		FfiInterop.register(this.globalEnv, (function, callArgs) -> apply(function, callArgs, this.globalEnv));
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
	/**
	 * Whether the source at {@code rawPath} can be read from where the current load
	 * resolves it -- the {@code :if-does-not-exist} probe of {@code load}. It is the
	 * interpreter's rendering of the {@code probe-file} guard the compile paths lower to,
	 * so an unreadable file (not merely a missing one) answers false on both.
	 */
	private boolean sourceReadable(String rawPath) {
		try {
			this.sourceLoader.load(SourceLoader.resolve(this.loadDirStack.peekLast(), rawPath));
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

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
		// A COMPONENT is loaded by its resolved path -- that is what real ASDF hands
		// load, and it is what makes *load-pathname* equal asdf:component-pathname, the
		// correlation rove's file-to-package map is built on. A plain load keeps the
		// spelling it was called with, like CL.
		this.specialVars.add(LispNames.LOAD_PATHNAME_VAR);
		this.specialVars.add(LispNames.LOAD_TRUENAME_VAR);
		this.dynamicBindings.push(LispNames.LOAD_PATHNAME_VAR, new LispString(systemName != null ? resolved : rawPath));
		this.dynamicBindings.push(LispNames.LOAD_TRUENAME_VAR, new LispString(resolved));
		try {
			// Only a file that textually contains #. pays for the marker read + the
			// per-form substitution walk; every other file keeps the plain read.
			// The resolved path rides along so a reader error inside a loaded file names
			// that file and line, exactly like the compile path's LoadInliner splice.
			if (source.contains("#.")) {
				for (LispVal form : LispReader.readAllWithReadEvalMarkers(source, features, resolved)) {
					eval(resolveReadTimeEvalInCode(form));
				}
			}
			else {
				for (LispVal form : LispReader.readAllFromString(source, features, resolved)) {
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
	 * Binds {@code *load-pathname*} / {@code *load-truename*} for a file whose forms this
	 * evaluator is about to process -- the same push {@link #loadFile} does around a file
	 * it reads, exposed for the COMPILE path, where the file was already read and spliced
	 * by {@code LoadInliner} and only its {@code (%begin-file P T)} bracket is left.
	 * {@code UserMacroExpander} calls this as it crosses that bracket, so a {@code #.}
	 * datum it resolves against this evaluator -- and a spliced system's replayed
	 * top-level form -- sees the load context the interpreter would have established, and
	 * the compiled program's run-time value (the assignments the bracket lowers to)
	 * agrees with it by construction.
	 * @param pathname the {@code *load-pathname*} value (the spelling {@code load} was
	 * called with; a component's resolved path)
	 * @param truename the {@code *load-truename*} value (the path it resolved to)
	 */
	public void pushLoadContext(String pathname, String truename) {
		this.specialVars.add(LispNames.LOAD_PATHNAME_VAR);
		this.specialVars.add(LispNames.LOAD_TRUENAME_VAR);
		this.dynamicBindings.push(LispNames.LOAD_PATHNAME_VAR, new LispString(pathname));
		this.dynamicBindings.push(LispNames.LOAD_TRUENAME_VAR, new LispString(truename));
	}

	/**
	 * Undoes one {@link #pushLoadContext}, restoring the enclosing file's values (nil
	 * outside every file).
	 */
	public void popLoadContext() {
		this.dynamicBindings.pop(LispNames.LOAD_TRUENAME_VAR);
		this.dynamicBindings.pop(LispNames.LOAD_PATHNAME_VAR);
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
		seedMopClassesForTypeSpecifier(subV);
		seedMopClassesForTypeSpecifier(superV);
		return LispMacroExpander.subtypep(subV, superV, this.closRegistry);
	}

	/**
	 * The {@code typep} half of {@link #seedMopClassesForTypeSpecifier}: unwraps the
	 * quoted specifier of a {@code (typep value 'spec)} FORM (unlike {@code subtypep},
	 * whose arguments reach the built-in as values) and seeds from it.
	 */
	private void seedMopClassesForTypepForm(LispCons cons) {
		if (!cons.isProperList()) {
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() == 3 && parts.get(2) instanceof LispCons quoted && quoted.car() instanceof LispSymbol head
				&& LispNames.QUOTE.equals(plainName(head.name())) && quoted.cdr() instanceof LispCons specCell) {
			seedMopClassesForTypeSpecifier(specCell.car());
		}
	}

	/**
	 * Registers the MOP base classes when a {@code typep}/{@code subtypep} type specifier
	 * names one of them, so {@code (typep x 'class)} answers even when this is the
	 * program's first MOP surface (the registry seeds lazily, and the interpreter expands
	 * the test BEFORE the {@code find-class} call in its own argument runs). Walks
	 * compound specifiers, and stops at a quote so an {@code (eql ...)} / {@code (member
	 * ...)} datum cannot trigger it.
	 */
	private void seedMopClassesForTypeSpecifier(LispVal specifier) {
		switch (specifier) {
			case LispSymbol sym -> this.closRegistry.ensureMopClassesSeededFor(sym.name());
			case LispCons cons -> {
				if (!(cons.car() instanceof LispSymbol head) || !LispNames.QUOTE.equals(plainName(head.name()))) {
					seedMopClassesForTypeSpecifier(cons.car());
					seedMopClassesForTypeSpecifier(cons.cdr());
				}
			}
			default -> {
			}
		}
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
	 * Resolves a {@code change-class} whose class argument is COMPUTED (upstream ASDF's
	 * {@code (change-class ret class)}) into the literal-name form the shared expansion
	 * takes: the instance and the designator evaluate here, in CL's left-to-right order
	 * (the instance value is self-evaluating, so re-evaluating the rebuilt form is
	 * effect-free), and a class-metaobject designator continues as its name. A form whose
	 * class argument is already a literal quoted symbol passes through untouched.
	 */
	private LispCons resolveChangeClassDesignator(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3) {
			return cons;
		}
		if (parts.get(2) instanceof LispCons q && q.car() instanceof LispSymbol qs
				&& LispNames.QUOTE.equals(qs.name())) {
			return cons;
		}
		LispVal instance = eval(parts.get(1), env);
		LispVal designator = eval(parts.get(2), env);
		if (designator instanceof LispInstance meta && meta.slotCount() > 0
				&& this.closRegistry.isClassMetaobject(meta)) {
			designator = meta.slot(0);
		}
		if (!(designator instanceof LispSymbol nameSym)) {
			throw new LispEvalException(
					LispNames.CHANGE_CLASS + " expects a class designator, got " + designator.print());
		}
		List<LispVal> rebuilt = new java.util.ArrayList<>();
		rebuilt.add(parts.get(0));
		rebuilt.add(instance);
		rebuilt.add(new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(nameSym, LispNil.INSTANCE)));
		rebuilt.addAll(parts.subList(3, parts.size()));
		return (LispCons) LispCons.rebuiltList(cons, rebuilt);
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

	/** A readable/writable slot location: an instance slot or a shared class cell. */
	private sealed interface SlotRef permits InstanceSlotRef, CellSlotRef {

		LispVal read();

		void write(LispVal value);

	}

	/** An instance together with the 0-based index of one of its slots. */
	private record InstanceSlotRef(LispInstance instance, int index) implements SlotRef {

		@Override
		public LispVal read() {
			return this.instance.slot(this.index);
		}

		@Override
		public void write(LispVal value) {
			this.instance.setSlot(this.index, value);
		}

	}

	/**
	 * The shared global cell of a {@code :allocation :class} slot -- reads and writes go
	 * to the {@code defvar}'d cell variable, never to the instance mirror.
	 */
	private record CellSlotRef(Environment env, String cellVar) implements SlotRef {

		@Override
		public LispVal read() {
			return this.env.lookup(this.cellVar);
		}

		@Override
		public void write(LispVal value) {
			this.env.set(this.cellVar, value);
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
	private @Nullable SlotRef instanceSlotRef(LispVal instance, LispVal slotName) {
		if (!(instance instanceof LispInstance inst) || !(slotName instanceof LispSymbol slotSym)) {
			return null;
		}
		String base = plainName(slotSym.name());
		// A :allocation :class slot answers its shared cell, never the instance
		// mirror -- the registry's effective spec (inherited or re-declared) names the
		// cell the instance's own class shares.
		String tag = inst.layout().tag();
		if (tag.startsWith(LispLayout.CLASS_TAG_PREFIX)) {
			ClosRegistry.ClassInfo info = this.closRegistry
				.findClass(tag.substring(LispLayout.CLASS_TAG_PREFIX.length()));
			if (info != null) {
				for (ClosRegistry.SlotSpec spec : info.slots()) {
					if (spec.baseName().equalsIgnoreCase(base) && spec.sharedCellVar() != null) {
						return new CellSlotRef(this.globalEnv, spec.sharedCellVar());
					}
				}
			}
		}
		List<String> slotNames = inst.layout().slotNames();
		for (int i = 0; i < slotNames.size(); i++) {
			// Case-insensitive: a Java-side caller (conditionSlotValue passes the
			// built-in "format-control"/"format-arguments") spells the slot lowercase,
			// while an upcase-read condition registers its slots upcased -- the same
			// reconciliation LispMacroExpander.expandConditionSlotReader makes.
			if (slotNames.get(i).equalsIgnoreCase(base)) {
				return new InstanceSlotRef(inst, i);
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
	 * needs an evaluator; this is where it happens. This entry substitutes every value
	 * RAW -- the runtime {@code read} family's contract, where the whole form is data.
	 * @param form the form as read
	 * @return the form with every read-time-eval marker resolved
	 */
	public LispVal resolveReadTimeEval(LispVal form) {
		return resolveReadTimeEval(form, false);
	}

	/**
	 * Like {@link #resolveReadTimeEval(LispVal)} but for a form about to be EVALUATED
	 * (the load/compile pipelines): a marker in an evaluated position splices a SYMBOL
	 * value QUOTED, so the value stands for the object it renders -- sxql's
	 * {@code (intern name #.*package*)} splices the package value, which rontolisp
	 * renders as a plain symbol where CL's package object would self-evaluate. Every
	 * other value splices raw like CL's object splice: notably a CONS value in code
	 * position IS code (fast-http's {@code #.`(eval-when ...)} defconstant generator). A
	 * marker inside a {@code (quote ...)} datum or a {@code defpackage} form splices raw
	 * (data), and a marker inside backquote construction code arrives as the reader's
	 * renamed TEMPLATE variant, which always splices quoted.
	 * @param form the form as read
	 * @return the form with every read-time-eval marker resolved
	 */
	public LispVal resolveReadTimeEvalInCode(LispVal form) {
		return resolveReadTimeEval(form, true);
	}

	private LispVal resolveReadTimeEval(LispVal form, boolean inCode) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.READ_EVAL.equals(head.name())
				&& cons.cdr() instanceof LispCons datumCons && datumCons.cdr() instanceof LispNil) {
			requireReadEvalEnabled();
			// The datum itself is always evaluated, so nested markers are in code.
			LispVal value = eval(resolveReadTimeEval(datumCons.car(), true));
			// ONLY a symbol value quotes: rontolisp renders a package object as a
			// symbol, and quoting keeps it the OBJECT it is in CL (where a package
			// self-evaluates). A cons value stays raw exactly like CL -- the spliced
			// list IS code (fast-http's #.`(eval-when ... (defconstant ...))
			// generator relies on it), and a caller wanting list DATA spells '#.
			// there as it must in CL.
			if (inCode && value instanceof LispSymbol sym && !sym.name().startsWith(":")) {
				return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
			}
			return value;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.READ_EVAL_TEMPLATE.equals(head.name())
				&& cons.cdr() instanceof LispCons datumCons && datumCons.cdr() instanceof LispNil) {
			// A marker inside backquote construction code (the reader's renamed
			// variant): the value is template DATA, so it substitutes quoted --
			// evaluating the construction code embeds the value itself.
			requireReadEvalEnabled();
			LispVal value = eval(resolveReadTimeEval(datumCons.car(), true));
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
		}
		if (inCode && cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(op.name())
				&& cons.cdr() instanceof LispCons datumCell && datumCell.cdr() instanceof LispNil) {
			// The well-formed (quote DATUM) shape: the datum is data, so a marker
			// inside it splices raw.
			LispVal datum = resolveReadTimeEval(datumCell.car(), false);
			if (datum == datumCell.car()) {
				return form;
			}
			return new LispCons(cons.car(), new LispCons(datum, LispNil.INSTANCE));
		}
		if (inCode && cons.car() instanceof LispSymbol dataOp
				&& LispNames.DEFPACKAGE.equals(LispSymbol.memberName(dataOp.name()))) {
			// defpackage's clauses are unevaluated data: alexandria-2 splices its
			// re-export list as (:export . #.(let ...)).
			LispVal rest = resolveReadTimeEval(cons.cdr(), false);
			if (rest == cons.cdr()) {
				return form;
			}
			return new LispCons(cons.car(), rest);
		}
		LispVal car = resolveReadTimeEval(cons.car(), inCode);
		LispVal cdr = resolveReadTimeEval(cons.cdr(), inCode);
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
	 * dependencies) from the installed dists into the local cache, adds the extracted
	 * {@code .asd} directories to the system search path, and then loads it through
	 * {@link #loadSystem} -- so quickload is {@code asdf:load-system} with an
	 * auto-download step in front.
	 */
	private void quickload(String name) {
		// A built-in system ("usocket") is satisfied by the embedded library: no
		// download, no cache, no DistClient.
		if (BuiltinSystems.isBuiltin(name)) {
			loadSystem(name);
			return;
		}
		List<String> asdDirs;
		try {
			asdDirs = distClient().ensureAvailable(name);
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

	/**
	 * Evaluates the asdf runtime (asdf.lisp -- the component metaobject classes,
	 * find-system and the readers, {@code AsdfRuntimeLibrary}) into the global
	 * environment once. Triggered lazily: on the resolution of a name it defines
	 * (function or the {@code asdf:*user-cache*} variable), on any
	 * {@code defsystem}/{@code load-system}/{@code quickload}/{@code test-system}, and on
	 * a class-resolving form ({@code defmethod}/{@code typep}/{@code typecase}/
	 * {@code make-instance}/{@code defclass}) that mentions one of the component class
	 * names -- so a specializer like {@code (system asdf:system)} resolves even before
	 * any system machinery ran.
	 */
	private void ensureAsdfRuntimeLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.asdfRuntimeLoaded) {
				return;
			}
			this.asdfRuntimeLoaded = true;
			for (LispVal form : AsdfRuntimeLibrary.classForms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * The class-mention half of the lazy trigger: seeds the asdf classes when the form
	 * mentions one of them anywhere ({@code AsdfRuntimeLibrary.mentionsComponentClass}).
	 * Cheap after the first load (one boolean).
	 */
	private void ensureAsdfClassesFor(LispVal form) {
		if (!this.asdfRuntimeLoaded && AsdfRuntimeLibrary.mentionsComponentClass(form)) {
			ensureAsdfRuntimeLoaded();
		}
	}

	/**
	 * Evaluates the {@code geom} library (geom.lisp -- solid modeling over linalg,
	 * {@code GeomLibrary}) into the global environment once, then installs
	 * {@link GeomKernels} over the three members a model FILE spends its load time in.
	 *
	 * <p>
	 * The natives are not opt-in the way {@code --simd} is: each one is the defun's own
	 * arithmetic transcribed and declines to the defun for anything it does not cover, so
	 * there is no input on which enabling them changes an answer -- only inputs on which
	 * they answer it two orders of magnitude sooner.
	 */
	private void ensureGeomLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.geomLibraryLoaded) {
				return;
			}
			this.geomLibraryLoaded = true;
			for (LispVal form : GeomLibrary.forms()) {
				eval(form, this.globalEnv);
			}
			if (this.geomKernels) {
				GeomKernels.install(this.globalEnv, this);
			}
		}
	}

	/**
	 * Evaluates the {@code metal} library (metal.lisp -- a Metal drawing surface over the
	 * {@code objc:} verbs, {@code MetalLibrary}) into the global environment once.
	 */
	private void ensureMetalLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.metalLibraryLoaded) {
				return;
			}
			this.metalLibraryLoaded = true;
			for (LispVal form : MetalLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * The class-mention half of geom's lazy trigger, the {@link #ensureAsdfClassesFor}
	 * twin: a {@code defmethod} specializer, a {@code typep}, a {@code typecase} clause,
	 * a {@code make-instance} or a {@code defclass} superclass may name
	 * {@code geom:solid} before any geom FUNCTION has been resolved, and without this it
	 * would see no such class. Cheap after the first load (one boolean).
	 */
	private void ensureGeomClassesFor(LispVal form) {
		if (!this.geomLibraryLoaded && GeomLibrary.mentionsGeomClass(form)) {
			ensureGeomLoaded();
		}
	}

	/**
	 * A runtime system designator that also accepts the component METAOBJECT
	 * {@code asdf:find-system} answers (rove passes the object straight back into
	 * {@code load-system}): an asdf component instance answers its name slot, anything
	 * else goes through {@link AsdfSystems#designator}.
	 */
	private static String asdfDesignator(String context, LispVal val) {
		if (val instanceof am.ik.rontolisp.LispInstance inst
				&& inst.layout().kind() == am.ik.rontolisp.LispLayout.Kind.CLASS
				&& inst.layout().printName().startsWith(LispNames.ASDF_PKG + ":") && inst.slotCount() > 0
				&& inst.slot(0) instanceof LispString nameSlot) {
			return nameSlot.value();
		}
		return AsdfSystems.designator(context, val);
	}

	/**
	 * The interpreter's {@code asdf:test-system}: load the system, follow its recorded
	 * {@code :in-order-to} test-op chain (each edge loaded and tested the same way,
	 * {@code visited} terminating a cycle), then apply the recorded
	 * {@code :perform (test-op ...)} body with the operation parameter nil and the
	 * component parameter bound to the system's metaobject.
	 */
	private void runTestOp(String name, java.util.Set<String> visited) {
		if (!visited.add(name)) {
			return;
		}
		loadSystem(name);
		AsdfSystems.LispSystem system = this.asdfSystems.get(name);
		if (system == null) {
			// A built-in shim system: no test-op wiring to run.
			return;
		}
		for (String edge : system.testOpEdges()) {
			runTestOp(edge, visited);
		}
		AsdfSystems.TestOp testOp = system.testOp();
		if (testOp == null) {
			return;
		}
		// ((lambda (o c) BODY...) nil (asdf:find-system "name")): the LAST parameter is
		// the component, every earlier one (the operation) is nil.
		java.util.List<LispVal> lambda = new java.util.ArrayList<>();
		lambda.add(new LispSymbol(LispNames.LAMBDA));
		lambda.add(consList(testOp.params()));
		lambda.addAll(testOp.body());
		java.util.List<LispVal> call = new java.util.ArrayList<>();
		call.add(consList(lambda));
		for (int i = 0; i < testOp.params().size(); i++) {
			if (i == testOp.params().size() - 1) {
				call.add(consList(java.util.List.of(new LispSymbol(LispNames.ASDF_FIND_SYSTEM), new LispString(name))));
			}
			else {
				call.add(LispNil.INSTANCE);
			}
		}
		eval(consList(call), this.globalEnv);
	}

	private void loadSystem(String name) {
		if (this.loadedSystems.contains(name)) {
			return;
		}
		// The component metaobject classes must exist before any system's own forms
		// evaluate: a loaded file may defmethod on asdf:system (rove's run-system).
		ensureAsdfRuntimeLoaded();
		if (this.loadingSystems.contains(name)) {
			throw new LispEvalException("Circular system :depends-on detected: "
					+ String.join(" -> ", this.loadingSystems) + " -> " + name);
		}
		String refusal = ShimLibraries.refusalReason(name);
		if (refusal != null) {
			throw new LispEvalException("Cannot load system '" + name + "': " + refusal);
		}
		String conflict = ShimLibraries.conflictingSystem(name);
		if (conflict != null && this.loadedSystems.contains(conflict)) {
			throw new LispEvalException("Cannot load system '" + name + "': it defines the same packages as '"
					+ conflict + "', which is already loaded -- load one of the two, not both");
		}
		if (BuiltinSystems.isBuiltin(name)) {
			// A system rontolisp provides itself (e.g. "usocket" or a dependency shim):
			// evaluate the embedded library instead of locating a NAME.asd. Its own
			// built-in :depends-on edges come first, exactly like a third-party
			// system's (flexi-streams needs the Gray protocol trivial-gray-streams
			// splices before its vector-stream defclass runs).
			for (String dependency : BuiltinSystems.dependencies(name)) {
				loadSystem(dependency);
			}
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
					Features.INTERPRETER, this.asdfSystemPackages)) {
				this.asdfSystems.putIfAbsent(defined.name(), defined);
			}
			system = this.asdfSystems.get(name);
			if (system == null) {
				// A NAME/SUB of a :package-inferred-system: the .asd declares no
				// components, so the name is answered from the file it points at.
				AsdfSystems.inferPackageInferredSystems(name, this.asdfSystems, this.asdfSystemPackages,
						this.sourceLoader, Features.INTERPRETER);
				system = this.asdfSystems.get(name);
			}
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
			// :defsystem-depends-on first: real ASDF loads those while the .asd is READ,
			// so they precede the system's own dependencies (they are not sideway
			// dependencies of it -- component-sideway-dependencies never lists them).
			for (String dependency : system.defsystemDependsOn()) {
				loadSystem(dependency);
			}
			for (String dependency : system.dependsOn()) {
				loadSystem(dependency);
			}
			for (String file : system.files()) {
				List<LispVal> leafShim = ShimLibraries.leafModuleForms(name, file, system.baseDir(), this.sourceLoader);
				if (leafShim != null) {
					// A substituted leaf module: evaluate the shim forms through the
					// package resolver (the defpackage must register before the
					// dependent components resolve), like the replaced file would.
					// Bracketed exactly as loadFile brackets a real component, so a
					// shim that selects a package with (in-package ...) -- the cffi
					// backend does, being a near-verbatim analogue of upstream's own
					// implementation file -- confines it to itself.
					this.packageResolver.pushPackage();
					try {
						for (LispVal form : leafShim) {
							eval(form);
						}
					}
					finally {
						this.packageResolver.popPackage();
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
		installIroncladNative();
	}

	/**
	 * Replaces ironclad's {@code pbkdf2-derive-key} with the native kernel once the
	 * system that defines it has finished loading -- keyed on the definition, not on a
	 * system name, so it fires whether the caller asked for the {@code ironclad}
	 * aggregate or only for {@code ironclad/kdf/pkcs5}. Interpreter only, and always on:
	 * the kernel computes the same spec-defined bytes ({@link IroncladNative}).
	 */
	private void installIroncladNative() {
		if (this.ironcladNativeInstalled
				|| this.globalEnv.lookupFunctionOrNull(IroncladNative.PBKDF2_DERIVE_KEY) == null) {
			return;
		}
		this.ironcladNativeInstalled = true;
		IroncladNative.install(this.globalEnv, this);
	}

	/**
	 * Evaluates an {@code (asdf:defsystem NAME ...)} special form: the options are plain
	 * data (never evaluated), so the form is parsed like a {@code .asd} entry and the
	 * system registered for a later {@code asdf:load-system}. Component paths resolve
	 * against the directory of the source being loaded.
	 */
	private LispVal evalDefsystem(LispCons cons) {
		ensureAsdfRuntimeLoaded();
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

	/**
	 * Renders a value the way {@code prin1} would: through the {@code print-object} route
	 * when this evaluation has turned it on (a {@code defmethod print-object}, a
	 * condition in reach, a converting {@code *print-case*}), else the raw readable
	 * rendering. The REPL echoes results through this, so a value whose class carries a
	 * {@code print-object} method -- a {@code geom:solid}, a torch tensor -- echoes as
	 * the method prints it, exactly as the printing operators would. An unrouted
	 * evaluation takes {@code value.print()} directly, so the everyday echo is unchanged;
	 * a failure inside the routed rendering falls back to it too, because an echo must
	 * never turn a computed value into an error.
	 * @param value the value to render
	 * @return the prin1 text
	 */
	public String prin1ToStringRouted(LispVal value) {
		boolean routed = !LispMacroExpander.printObjectTags(this.closRegistry).isEmpty()
				|| this.closRegistry.routesConditionReports() || printCaseInEffect();
		if (!routed) {
			return value.print();
		}
		LispVal quoted = new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
		LispVal form = new LispCons(new LispSymbol(LispNames.PRIN1_TO_STRING), new LispCons(quoted, LispNil.INSTANCE));
		try {
			return eval(form) instanceof LispString rendered ? rendered.value() : value.print();
		}
		catch (RuntimeException ex) {
			return value.print();
		}
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
			// An array literal is a CONSTRUCTOR, not a constant: each evaluation answers
			// a
			// fresh, independently mutable array, which is what both compile backends
			// already emit at the site (LiteralArrays).
			case LispArray a -> LiteralArrays.materialize(a);
			case LispFloatArray fa -> LiteralArrays.materialize(fa);
			case am.ik.rontolisp.LispIntVector iv -> LiteralArrays.materialize(iv);
			case LispJavaObject j -> j;
			case LispObjcObject o -> o;
			case am.ik.rontolisp.LispForeignPointer p -> p;
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
	 * Whether {@code *print-case*} currently holds something other than {@code :upcase},
	 * i.e. whether a printing operator has to route through the case-applying renderer.
	 * Read dynamic-first, like any special: the value a {@code let} binding established
	 * on this thread wins over the global default.
	 * @return true when the printer must apply a case conversion
	 */
	private boolean printCaseInEffect() {
		LispVal value = this.dynamicBindings.isBound(LispNames.PRINT_CASE_VAR)
				? this.dynamicBindings.get(LispNames.PRINT_CASE_VAR)
				: this.globalEnv.lookupOrNull(LispNames.PRINT_CASE_VAR);
		return value instanceof LispSymbol mode && !LispNames.PRINT_CASE_UPCASE.equals(mode.name());
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
		if (LispNames.PACKAGE_VAR.equals(name)) {
			return currentPackageValue();
		}
		if ((!this.specialVars.isEmpty() || this.progvUsed) && this.dynamicBindings.isBound(name)) {
			return this.dynamicBindings.get(name);
		}
		LispVal value = env.lookupOrNull(name);
		if (value == null && !this.globalSymbolMacros.isEmpty()) {
			// define-symbol-macro: the name is not a variable, so it only ever reaches
			// here with nothing bound. Evaluating the expansion in the CURRENT
			// environment is the whole semantics (cffi's defcvar reads a C global
			// through a generated accessor call).
			LispVal expansion = this.globalSymbolMacros.get(name);
			if (expansion != null) {
				return eval(expansion, env);
			}
		}
		if (value == null && !this.usocketLibraryLoaded && UsocketLibrary.isUsocketQualified(name)) {
			// The usocket library also exports variables (usocket:*wildcard-host*), so a
			// program whose FIRST usocket reference is a variable read must trigger the
			// same lazy load as a function resolution.
			ensureUsocketLoaded();
			value = this.globalEnv.lookupOrNull(name);
		}
		if (value == null && !this.metalLibraryLoaded && MetalLibrary.isMetalQualified(name)) {
			// The metal library exports CONSTANTS (metal:+triangle+, metal:+line+, the
			// cull and compare modes), and a program may well read one before it calls
			// any metal function -- so a variable read triggers the lazy load too.
			ensureMetalLoaded();
			value = this.globalEnv.lookupOrNull(name);
		}
		if (value == null && UiopLibrary.definesName(name)) {
			// uiop exports variables too (49 of them), so the same lazy load has to be
			// reachable from a variable read.
			loadUiopDefinition(name);
			value = this.globalEnv.lookupOrNull(name);
		}
		if (value == null && !this.asdfRuntimeLoaded && AsdfRuntimeLibrary.definesName(name)) {
			// asdf:*user-cache* is a variable, so the asdf runtime's lazy load must be
			// reachable from a variable read too.
			ensureAsdfRuntimeLoaded();
			value = this.globalEnv.lookupOrNull(name);
		}
		if (value == null) {
			throw LispEvalException.ofClass(ClosRegistry.UNBOUND_VARIABLE_CLASS_NAME,
					ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX + name + ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX);
		}
		return value;
	}

	/**
	 * Evaluates ONE uiop definition ({@code eval.UiopLibrary}) into the global
	 * environment, once -- with two things pulled in around it, both because a CLASS
	 * cannot be lazy the way a function can. Every uiop condition and class goes in first
	 * whatever the name is ({@code UiopLibrary.conditionAndClassNames}): a quoted
	 * condition name is not a function resolution, so nothing would trigger its own load,
	 * and a handler's type test is built from the class tags known when the
	 * {@code handler-bind} was expanded, so a class first registered while the body runs
	 * is invisible to the handler meant to catch it. And the name's whole
	 * {@code UiopLibrary.closureOf} closure goes in with it, which is exactly what the
	 * compile paths splice.
	 * @param name the home-qualified uiop name
	 * @return its global function binding, or {@code null} when the definition binds a
	 * variable rather than a function
	 */
	@Nullable private LispVal loadUiopDefinition(String name) {
		synchronized (this.libraryLoadLock) {
			for (String conditionName : UiopLibrary.conditionAndClassNames()) {
				if (this.loadedUiopNames.add(conditionName)) {
					for (LispVal form : UiopLibrary.formsFor(conditionName)) {
						eval(form, this.globalEnv);
					}
				}
			}
			// The CLOSURE, not just this name: a definition may reach another one it
			// never calls (style-warn signals a quoted condition name), and the compile
			// paths splice exactly this set -- loading less here is how the interpreter
			// ends up with a condition class the other three backends have.
			for (String reachable : UiopLibrary.closureOf(name)) {
				if (this.loadedUiopNames.add(reachable)) {
					for (LispVal form : UiopLibrary.formsFor(reachable)) {
						eval(form, this.globalEnv);
					}
				}
			}
			return this.globalEnv.lookupFunctionOrNull(name);
		}
	}

	/**
	 * Evaluates the usocket library definitions ({@code usocket.lisp}) into the global
	 * environment once; shared by the function/variable lazy-load hooks and the built-in
	 * ASDF system {@code "usocket"} ({@code asdf:load-system}/{@code ql:quickload}).
	 */
	/**
	 * Evaluates the torch library definitions ({@code torch.lisp}) into the global
	 * environment once; shared by the function lazy-load hook in {@code resolveFunction}
	 * and the {@code torch:no-grad} macro case (whose expansion dynamically rebinds
	 * {@code torch::*grad-enabled*}, so the {@code defparameter} must exist -- and be
	 * declared special -- BEFORE the {@code let} binds it).
	 */
	private void ensureTorchLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.torchLibraryLoaded) {
				return;
			}
			this.torchLibraryLoaded = true;
			for (LispVal form : TorchLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * Whether the form spells a {@code torch:}/{@code torch::} qualified symbol anywhere
	 * -- the trigger for pre-loading the library in the printing-operator case, where the
	 * routing decision precedes the argument evaluation that would otherwise load it.
	 * @param form the form to scan
	 * @return whether a torch-qualified symbol occurs in it
	 */
	private static boolean referencesTorch(LispVal form) {
		return switch (form) {
			case LispSymbol sym -> TorchLibrary.isTorchQualified(sym.name());
			case LispCons cons -> referencesTorch(cons.car()) || referencesTorch(cons.cdr());
			default -> false;
		};
	}

	/**
	 * The {@code geom} twin of {@link #referencesTorch}: {@code geom.lisp} carries
	 * {@code print-object} methods on {@code geom:node}/{@code geom:solid}, so the first
	 * {@code (print (geom:box ...))} of a session must load the library BEFORE the
	 * routing decision, not during the argument evaluation after it.
	 * @param form the form to scan
	 * @return whether a geom-qualified symbol occurs in it
	 */
	private static boolean referencesGeom(LispVal form) {
		return switch (form) {
			case LispSymbol sym -> GeomLibrary.isGeomQualified(sym.name());
			case LispCons cons -> referencesGeom(cons.car()) || referencesGeom(cons.cdr());
			default -> false;
		};
	}

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

	private boolean httpServerLoaded;

	private boolean httpReactorLoaded;

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

	private static final String GRAY_TERPRI_DISPATCH = GrayStreamsLibrary.TERPRI_DISPATCH;

	private static final String GRAY_FRESH_LINE_DISPATCH = GrayStreamsLibrary.FRESH_LINE_DISPATCH;

	private static final String GRAY_WRITE_LINE_DISPATCH = GrayStreamsLibrary.WRITE_LINE_DISPATCH;

	private static final String GRAY_FORCE_OUTPUT_DISPATCH = GrayStreamsLibrary.FORCE_OUTPUT_DISPATCH;

	private static final String GRAY_FINISH_OUTPUT_DISPATCH = GrayStreamsLibrary.FINISH_OUTPUT_DISPATCH;

	private static final String GRAY_CLEAR_OUTPUT_DISPATCH = GrayStreamsLibrary.CLEAR_OUTPUT_DISPATCH;

	private static final String GRAY_PRINC_DISPATCH = GrayStreamsLibrary.PRINC_DISPATCH;

	private static final String GRAY_PRIN1_DISPATCH = GrayStreamsLibrary.PRIN1_DISPATCH;

	private static final String GRAY_PRINT_DISPATCH = GrayStreamsLibrary.PRINT_DISPATCH;

	private static final String GRAY_CLOSE_DISPATCH = GrayStreamsLibrary.CLOSE_DISPATCH;

	private static final String GRAY_WRITE_CHAR_DISPATCH = GrayStreamsLibrary.WRITE_CHAR_DISPATCH;

	private static final String GRAY_READ_CHAR_NO_HANG_DISPATCH = GrayStreamsLibrary.READ_CHAR_NO_HANG_DISPATCH;

	private static final String GRAY_PEEK_CHAR_DISPATCH = GrayStreamsLibrary.PEEK_CHAR_DISPATCH;

	private static final String GRAY_UNREAD_CHAR_DISPATCH = GrayStreamsLibrary.UNREAD_CHAR_DISPATCH;

	private static final String GRAY_OPEN_STREAM_P_DISPATCH = GrayStreamsLibrary.OPEN_STREAM_P_DISPATCH;

	private static final String GRAY_INPUT_STREAM_P_DISPATCH = GrayStreamsLibrary.INPUT_STREAM_P_DISPATCH;

	private static final String GRAY_OUTPUT_STREAM_P_DISPATCH = GrayStreamsLibrary.OUTPUT_STREAM_P_DISPATCH;

	private static final String GRAY_STREAM_ELEMENT_TYPE_DISPATCH = GrayStreamsLibrary.STREAM_ELEMENT_TYPE_DISPATCH;

	/**
	 * Wraps one stream-taking output built-in so a CLOS-instance stream at
	 * {@code streamIndex} routes to the given {@code rontolisp::%gray-*-dispatch} helper
	 * of gray.lisp and everything else reaches the built-in unchanged. The helpers hold
	 * the one copy of the instance test and the fallback, shared verbatim with the
	 * compile path's call-site rewrite ({@code GrayStreamsLibrary.process}); the fallback
	 * re-enters this wrap once, which terminates because a non-instance goes straight to
	 * the base function.
	 * @param name the built-in's name
	 * @param streamIndex the argument position holding the stream
	 * @param helperName the dispatch helper to apply
	 */
	private void wrapGrayOutputOperator(String name, int streamIndex, String helperName) {
		LispVal base = this.globalEnv.lookupFunction(name);
		this.globalEnv.defineFunction(name, new LispFunction(name, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, streamIndex);
			if (args.size() == streamIndex + 1 && dispatchesToGray(args.get(streamIndex))) {
				List<LispVal> forwarded = streamIndex == 0 ? List.of(args.get(0)) : List.of(args.get(0), args.get(1));
				return applyGrayDispatch(helperName, forwarded);
			}
			return apply(base, args, this.globalEnv);
		}));
	}

	/**
	 * Wraps one of the unary stream queries a program may OWN -- {@code open-stream-p} /
	 * {@code stream-element-type}, the two that join {@code close} in
	 * {@code GrayStreamsLibrary.OWNABLE_OPERATORS} -- so a CLOS-instance stream gets the
	 * Gray default answer while the program registers no generic for the name. A program
	 * that defines its own method takes the name over outright (the shadowed-built-in
	 * machinery, {@code .kb/clos.md}), and the compile path stands down on exactly the
	 * same condition.
	 * @param name the built-in's name
	 * @param helperName the dispatch helper to apply
	 */
	private void wrapGrayOwnableOperator(String name, String helperName) {
		LispVal base = this.globalEnv.lookupFunction(name);
		this.globalEnv.defineFunction(name, new LispFunction(name, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 0);
			if (args.size() == 1 && dispatchesToGray(args.get(0)) && this.closRegistry.findGeneric(name) == null) {
				return applyGrayDispatch(helperName, List.of(args.get(0)));
			}
			return apply(base, args, this.globalEnv);
		}));
	}

	/**
	 * Whether the value is an instance of a class that subclasses
	 * {@code rontolisp:fundamental-stream} -- i.e. a Gray stream, which {@code streamp}
	 * and {@code (typep x 'stream)} must both answer t for.
	 * @param value the value to test
	 * @return true when the value is a Gray stream instance
	 */
	private boolean isGrayStreamInstance(LispVal value) {
		return value instanceof LispInstance inst
				&& this.closRegistry.descendantTags(LispMacroExpander.GRAY_FUNDAMENTAL_STREAM_CLASS)
					.contains(inst.layout().tag());
	}

	/**
	 * Wraps a printing operator's FUNCTION VALUE so a {@code #'}-reference honors
	 * {@code *print-case*} exactly as the operator form does: while the variable holds a
	 * converting value the call is rebuilt as the source form it would have been (its
	 * evaluated arguments quoted in place) and handed to the SAME expansion the operator
	 * seam uses, so no second rendering rule can drift from it. With the variable at
	 * {@code :upcase} -- the default, and every program that never binds it -- the
	 * wrapped built-in runs unchanged.
	 * @param name the operator name
	 */
	private void wrapPrintCaseOperator(String name) {
		LispVal base = this.globalEnv.lookupFunction(name);
		this.globalEnv.defineFunction(name, new LispFunction(name, args -> {
			if (!args.isEmpty() && args.size() <= 2 && printCaseInEffect()) {
				LispVal form = new LispCons(new LispSymbol(name), quotedArguments(args));
				ensurePrintObjectRuntimeLoadedIfRouted(true);
				LispVal hooked = LispMacroExpander.expandPrintObjectHook((LispCons) form, this.closRegistry, true);
				if (hooked != null) {
					return eval(hooked, this.globalEnv);
				}
			}
			return apply(base, args, this.globalEnv);
		}));
	}

	/** The evaluated arguments as a quoted argument LIST, ready to re-evaluate. */
	private static LispVal quotedArguments(List<LispVal> args) {
		LispVal list = LispNil.INSTANCE;
		for (int i = args.size() - 1; i >= 0; i--) {
			list = new LispCons(quoteValue(args.get(i)), list);
		}
		return list;
	}

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
		LispVal stream = Environment.streamTarget(eval(parts.get(2), env));
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

	/**
	 * Evaluates {@code (write-char char stream)}: {@code write-char} is a macro expansion
	 * ({@code write-string} of the one-character string), not a function, so its Gray
	 * dispatch is intercepted here the way {@code read-sequence}/{@code write-sequence}
	 * are. An INSTANCE stream routes to the write-char dispatch helper, which reaches
	 * {@code rontolisp:stream-write-char} -- the one method full Gray requires, and the
	 * only writer a class that defines just it has. Anything else re-enters the shared
	 * expansion with the two evaluated values QUOTED in place (no double evaluation).
	 */
	private LispVal evalWriteCharWithGrayDispatch(LispCons cons, Environment env) {
		java.util.List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			// Let the expansion handle the stream-less form and signal arity errors.
			return eval(LispMacroExpander.expandWriteChar(cons), env);
		}
		LispVal ch = eval(parts.get(1), env);
		LispVal stream = Environment.streamTarget(eval(parts.get(2), env));
		if (stream instanceof LispInstance) {
			return applyGrayDispatch(GRAY_WRITE_CHAR_DISPATCH, List.of(ch, stream));
		}
		LispCons rebuilt = new LispCons(parts.get(0),
				new LispCons(quoteValue(ch), new LispCons(quoteValue(stream), LispNil.INSTANCE)));
		return eval(LispMacroExpander.expandWriteChar(rebuilt), env);
	}

	private static LispVal quoteValue(LispVal value) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
	}

	/**
	 * The {@code :raw-body} mode of an {@code (rontolisp:http-handler handler [port]
	 * [:raw-body :buffered])} call: {@code true} for the buffered, synchronously readable
	 * body a Clack application needs, {@code false} (the default) for rontolisp's
	 * asynchronous request stream.
	 * @param args the directive arguments
	 * @return whether the buffered body was asked for
	 */
	static boolean httpHandlerBufferBody(List<LispVal> args) {
		for (int i = 0; i + 1 < args.size(); i++) {
			if (args.get(i) instanceof LispSymbol key && key.isKeyword()
					&& LispNames.RAW_BODY_KEYWORD.equalsIgnoreCase(key.name())) {
				if (!(args.get(i + 1) instanceof LispSymbol mode) || !mode.isKeyword()
						|| !(LispNames.BUFFERED_KEYWORD.equalsIgnoreCase(mode.name())
								|| LispNames.STREAM_KEYWORD.equalsIgnoreCase(mode.name()))) {
					throw new LispEvalException(LispNames.HTTP_HANDLER
							+ " :raw-body expects :stream or :buffered, got: " + args.get(i + 1).print());
				}
				return LispNames.BUFFERED_KEYWORD.equalsIgnoreCase(mode.name());
			}
		}
		return false;
	}

	private void ensureHttpServerLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.httpServerLoaded) {
				return;
			}
			this.httpServerLoaded = true;
			// The server library defines a Gray stream class, so the protocol has to be
			// in place first. Both loads are EAGER, at server-start time: a served
			// request runs on its own virtual thread, and a lazy first-request load
			// races every other in-flight request (.kb/concurrent-served-requests.md).
			ensureGrayStreamsLoaded();
			for (LispVal form : HttpServerLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * Evaluates the host-driven-reactor transport ({@code http-reactor.lisp}) into the
	 * global environment once, on the first {@code rontolisp::%http-reactor-*} function
	 * lookup -- a Clack handler backend's {@code run}/{@code handle}/{@code dispatch}
	 * delegating there. Its bodies call {@code %http-make-env} and friends, which the
	 * {@code RONTOLISP::%HTTP-} hook loads on their own first call.
	 */
	private void ensureHttpReactorLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.httpReactorLoaded) {
				return;
			}
			this.httpReactorLoaded = true;
			for (LispVal form : HttpReactorLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
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

	/**
	 * The value of the leftmost pair of the initarg plist whose key is the given keyword
	 * (compared by spelling), or null when absent -- absent and supplied-nil must stay
	 * distinguishable for the {@code %mop-fill-slots} fill.
	 */
	private static @Nullable LispVal leftmostInitargValue(LispVal plist, String keyword) {
		LispVal cursor = plist;
		while (cursor instanceof LispCons pair) {
			if (pair.car() instanceof LispSymbol key && keyword.equals(key.name())
					&& pair.cdr() instanceof LispCons valueCell) {
				return valueCell.car();
			}
			cursor = pair.cdr() instanceof LispCons rest ? rest.cdr() : LispNil.INSTANCE;
		}
		return null;
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
			// The chain-fill construction of metaobject instances (expandMakeInstance)
			// is valid from here on: the protocol's shared-initialize fill primaries
			// are about to be defined.
			this.closRegistry.setMopProtocolActive();
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

	private boolean uiopConditionClassesLoaded;

	/**
	 * Whether {@link #ensureUiopConditionClassesLoaded} is mid-flight. The report
	 * renderer is rebuilt from the whole class table, so rebuilding it once per class
	 * registered would be twenty passes for one answer; the outer call rebuilds once,
	 * after the last class lands.
	 */
	private boolean loadingUiopConditionClasses;

	/**
	 * Registers every uiop condition and class
	 * ({@code UiopLibrary.conditionAndClassNames} -- 19 {@code define-condition}s and one
	 * {@code defclass}) the first time the program touches the condition system at all,
	 * which is what {@link #ensureConditionReportRuntimeLoaded} marks.
	 *
	 * <p>
	 * A CLASS cannot be lazy the way a function can. Two things go wrong if uiop's arrive
	 * only when some uiop FUNCTION is first resolved: a handler's type test is built from
	 * the class tags known at EXPANSION time, so a class registered while the body runs
	 * is invisible to the handler that was meant to catch it; and a program that only
	 * NAMES a uiop condition ({@code (make-condition 'uiop:simple-style-warning)}) never
	 * resolves a uiop function at all. Both were cross-backend divergences --
	 * {@code (handler-bind ((warning #'muffle-warning)) (uiop:style-warn "x"))} muffled
	 * the warning on the JVM and both WASM backends, which splice every reachable uiop
	 * definition before anything runs, and printed it here.
	 *
	 * <p>
	 * Registering them on first condition-system use rather than in the constructor
	 * confines the cost to programs that have conditions at all, and it introduces no
	 * divergence of its own: a program can only observe a class it NAMES, and naming it
	 * is exactly what makes the compile path splice its definition too
	 * ({@code UiopLibrary.process} collects quoted symbols).
	 */
	private void ensureUiopConditionClassesLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.uiopConditionClassesLoaded) {
				return;
			}
			// Set before evaluating: each define-condition below re-enters
			// ensureConditionReportRuntimeLoaded, which is what called us.
			this.uiopConditionClassesLoaded = true;
			this.loadingUiopConditionClasses = true;
			try {
				for (String name : UiopLibrary.conditionAndClassNames()) {
					if (this.loadedUiopNames.add(name)) {
						for (LispVal form : UiopLibrary.formsFor(name)) {
							eval(form, this.globalEnv);
						}
					}
				}
			}
			finally {
				this.loadingUiopConditionClasses = false;
			}
		}
	}

	private LispVal evalCons(LispCons cons, Environment env) {
		LispVal head = cons.car();
		// A dotted tail is only meaningful as data (inside quote); in call position it
		// would otherwise be silently dropped by the toList() walks below. The walk
		// also answers the argument count, so the fall-through function call below
		// allocates its argument list exactly sized instead of walking again.
		int properLength = head instanceof LispSymbol qs && LispNames.QUOTE.equals(qs.name()) ? 1 : cons.properLength();
		if (properLength < 0) {
			throw new LispEvalException("Improper list in call position: " + cons.print());
		}
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.QUOTE:
				case LispNames.UNSPELLED_QUOTE:
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
					ensureAsdfClassesFor(cons);
					ensureGeomClassesFor(cons);
					return evalDefclass(cons, env);
				case LispNames.DEFGENERIC:
					return evalDefgeneric(cons, env);
				case LispNames.DEFMETHOD:
					ensureAsdfClassesFor(cons);
					ensureGeomClassesFor(cons);
					return evalDefmethod(cons, env);
				case LispNames.MAKE_INSTANCE:
					ensureAsdfClassesFor(cons);
					ensureGeomClassesFor(cons);
					return eval(LispMacroExpander.expandMakeInstance(cons, this.closRegistry), env);
				case LispNames.CHANGE_CLASS:
					return eval(LispMacroExpander.expandChangeClass(resolveChangeClassDesignator(cons, env),
							this.closRegistry, false), env);
				case LispNames.SLOT_VALUE:
					return evalSlotValue(cons, env);
				case LispNames.WITH_SLOTS:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithSlots);
				case LispNames.WITH_ACCESSORS:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithAccessors);
				case LispNames.DEFVAR:
					return evalDefvar(cons, env, false);
				case LispNames.DEFPARAMETER:
					return evalDefvar(cons, env, true);
				case LispNames.DEFCONSTANT:
					return evalDefconstant(cons, env);
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
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandAsync);
				case LispNames.ASYNC_DEFUN_QUALIFIED:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandAsyncDefun);
				case LispNames.ASYNC_LAMBDA_QUALIFIED:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandAsyncLambda);
				case LispNames.AWAIT_QUALIFIED:
					return evalAwait(cons, env);
				case LispNames.WHILE:
					return evalWhile(cons, env);
				case LispNames.COND:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandCond);
				case LispNames.CASE:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandCase);
				case LispNames.ECASE:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandEcase);
				case LispNames.CCASE:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandCcase);
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
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandRestartBind);
				case LispNames.WITH_SIMPLE_RESTART:
					ensureRestartRuntimeLoaded();
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithSimpleRestart);
				case LispNames.IGNORE_ERRORS:
					ensureConditionReportRuntimeLoaded();
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandIgnoreErrors);
				case LispNames.STABLE_SORT:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandStableSort);
				case LispNames.COPY_SEQ:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandCopySeq);
				case LispNames.AND:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandAnd);
				case LispNames.OR:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandOr);
				case LispNames.WHEN:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWhen);
				case LispNames.DOTIMES:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandDotimes);
				case LispNames.DO:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandDo);
				case LispNames.DO_STAR:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandDoStar);
				case LispNames.LOOP:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandLoop);
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
					throw blockExit(NIL_BLOCK, evalReturnValue(cons, env), env);
				case LispNames.PROG1:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandProg1);
				case LispNames.TIME:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandTime);
				case LispNames.UNLESS:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandUnless);
				case LispNames.ONE_PLUS:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandOnePlus);
				case LispNames.ONE_MINUS:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandOneMinus);
				case LispNames.ZEROP:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandZerop);
				case LispNames.PLUSP:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandPlusp);
				case LispNames.MINUSP:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandMinusp);
				case LispNames.EVENP:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandEvenp);
				case LispNames.ODDP:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandOddp);
				case LispNames.FIRST:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandFirst);
				case LispNames.REST:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandRest);
				case LispNames.NTH:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandNth);
				case LispNames.SECOND:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandSecond);
				case LispNames.THIRD:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandThird);
				case LispNames.FOURTH:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandFourth);
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
				case LispNames.SCHAR_SET:
					// Not a plain builtin call: a write through a place holding a string
					// LITERAL rebinds that place instead of mutating the source constant,
					// which the callee cannot do for itself.
					return evalScharSet(cons, env);
				case LispNames.PUSH:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandPush);
				case LispNames.POP:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandPop);
				case LispNames.REMF:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandRemf);
				case LispNames.LET_STAR:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandLetStar);
				case LispNames.DOLIST:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandDolist);
				case LispNames.INCF:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandIncf);
				case LispNames.DECF:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandDecf);
				case LispNames.FORMAT:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandFormat);
				case LispNames.WITH_OPEN_FILE:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithOpenFile);
				case LispNames.WITH_OUTPUT_TO_STRING:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithOutputToString);
				case LispNames.PPRINT_LOGICAL_BLOCK:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandPprintLogicalBlock);
				case LispNames.WITH_ARENA_QUALIFIED:
					// A reclamation boundary for --no-gc; a real GC already reclaims, so
					// the interpreter runs the body as a plain progn.
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithArena);
				case LispNames.WITH_MUTEX_QUALIFIED:
				case LispNames.WITH_LOCK_HELD_QUALIFIED:
				case LispNames.WITH_RECURSIVE_LOCK_HELD_QUALIFIED:
					// Acquire / body / release-on-every-exit; bordeaux-threads'
					// with-lock-held is the same shape over the same primitives, and its
					// recursive twin is the same again -- the shim's lock is reentrant.
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithMutex);
				case LispNames.WIT_EXPORT_QUALIFIED:
					return evalWitExport(cons);
				case LispNames.WIT_IMPORT_QUALIFIED:
					return evalWitImport(cons);
				case LispNames.TORCH_NO_GRAD_QUALIFIED:
					// The expansion let-binds torch::*grad-enabled*, so the library's
					// defparameter must have declared it special BEFORE the let binds.
					ensureTorchLoaded();
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandTorchNoGrad);
				case LispNames.USOCKET_WITH_CLIENT_SOCKET_QUALIFIED:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandUsocketWithClientSocket);
				case LispNames.USOCKET_WITH_CONNECTED_SOCKET_QUALIFIED:
				case LispNames.USOCKET_WITH_SERVER_SOCKET_QUALIFIED:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandUsocketWithConnectedSocket);
				case LispNames.USOCKET_WITH_SOCKET_LISTENER_QUALIFIED:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandUsocketWithSocketListener);
				case LispNames.USOCKET_GUARD_QUALIFIED:
					return evalBuiltinMacro(cons, env, c -> LispMacroExpander.expandUsocketGuard(c, true));
				case LispNames.WITH_INPUT_FROM_STRING:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithInputFromString);
				case LispNames.PUSHNEW:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandPushnew);
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
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandDefineModifyMacro);
				case LispNames.DEFINE_SETF_EXPANDER:
					return registerSetfExpander(cons);
				case LispNames.DEFSETF:
					return registerDefsetf(cons);
				case LispNames.DEFINE_COMPILER_MACRO:
					return evalDefineCompilerMacro(cons, env);
				case LispNames.RESTART_CASE:
					ensureRestartRuntimeLoaded();
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandRestartCase);
				case LispNames.MACROLET:
					return evalMacrolet(cons, env);
				case LispNames.MAKE_CONDITION:
					ensureConditionReportRuntimeLoaded();
					return eval(LispMacroExpander.expandMakeCondition(cons, this.closRegistry), env);
				case LispNames.DOCUMENTATION:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandDocumentation);
				case LispNames.COPY_READTABLE:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandCopyReadtable);
				case LispNames.SET_DISPATCH_MACRO_CHARACTER:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandSetDispatchMacroCharacter);
				case LispNames.READTABLE_CASE:
					return evalBuiltinMacro(cons, env, LispMacroExpander::expandReadtableCase);
			}
			// The operator table is split so that neither half crosses HotSpot's
			// 8000-bytecode HugeMethodLimit; see evalConsRareOperator.
			LispVal rare = evalConsRareOperator(cons, env, sym.name());
			if (rare != UNHANDLED) {
				return rare;
			}
			if (LispNames.isCarCdrComposition(sym.name())) {
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandCarCdrComposition);
			}
			// The uiop MACROS -- but only for a package-qualified operator. A name
			// with no colon cannot be a uiop member, and this path is the fall-through
			// every ordinary call takes, so one indexOf here spares BOTH probes'
			// splitQualified for (char s j) and (+ j 1) alike (4% of run-time samples
			// in the todo-598 profile).
			if (sym.name().indexOf(':') > 0) {
				// First the ones with a real expansion -- the one dispatcher both
				// compilers and FreeVarAnalyzer also call, which is what makes the
				// four backends agree by construction rather than by four parallel
				// switch statements kept in step.
				LispVal uiopMacro = LispMacroExpander.expandUiopMacro(cons, true);
				if (uiopMacro != null) {
					return eval(uiopMacro, env);
				}
				// Then a uiop macro nothing implements yet: it lowers to
				// not-implemented-error with its argument forms dropped -- the same
				// expansion both compilers apply, so an unimplemented
				// (uiop:with-input-file ...) signals here too rather than running its
				// body first. The function-kind members are ordinary calls and fall
				// through to the lazy load below.
				LispVal uiopStub = LispMacroExpander.expandUnimplementedUiopMacro(cons);
				if (uiopStub != null) {
					return eval(uiopStub, env);
				}
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
			List<LispVal> args = evalArgs(cons, env, properLength - 1);
			return apply(function, args, env);
		}
		// Non-symbol head: a lambda form such as ((lambda (x) x) 5)
		LispVal function = eval(head, env);
		List<LispVal> args = evalArgs(cons, env, properLength - 1);
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
			case LispNames.HB_GUARD_INTERNAL:
				return evalHbGuard(cons, env);
			case LispNames.PRINT, LispNames.PRINC, LispNames.PRIN1, LispNames.PRINC_TO_STRING,
					LispNames.PRIN1_TO_STRING, LispNames.WRITE_TO_STRING, LispNames.PRINC_PIECE_INTERNAL,
					LispNames.PRIN1_PIECE_INTERNAL: {
				// Routed through print-object only when the program defines a method on
				// it, and through %print-cased only while *print-case* holds something
				// other than :upcase; otherwise the ordinary Environment function runs,
				// unchanged. The renderer is INLINED here rather than called as a
				// generated defun: the interpreter re-expands per call, so it always sees
				// the current method set (a defmethod may follow the first print). The
				// case gate is the CURRENT VALUE rather than the compile paths'
				// "the program mentions the variable" scan -- the interpreter has no
				// whole-program pass to run one in -- and the two agree because
				// %print-cased re-reads the variable itself.
				if (this.closRegistry.routesConditionReports()) {
					// Already routing: only the freshness check, so a condition class
					// defined between two prints renders through its report too.
					ensureConditionReportRuntimeLoaded();
				}
				// The routing decision is taken BEFORE the argument is evaluated, and the
				// torch library -- whose records carry (:print-object ...) methods -- is
				// loaded lazily by that very evaluation. Without this the FIRST
				// (print (torch:tensor ...)) of a session decides against a registry the
				// method has not reached yet and renders the raw #S(...) form, while
				// every
				// later print routes. The same ordering seam as the torch:no-grad case
				// above (.kb/torch.md); torch and geom are the only lazily loaded
				// libraries that define a print-object method (geom's are on
				// geom:node/geom:solid, .kb/geom.md).
				if (!this.torchLibraryLoaded && referencesTorch(cons)) {
					ensureTorchLoaded();
				}
				if (!this.geomLibraryLoaded && referencesGeom(cons)) {
					ensureGeomLoaded();
				}
				boolean printCase = printCaseInEffect();
				ensurePrintObjectRuntimeLoadedIfRouted(printCase);
				LispVal hooked = LispMacroExpander.expandPrintObjectHook(cons, this.closRegistry, printCase);
				if (hooked != null) {
					return eval(hooked, env);
				}
				break;
			}
			case LispNames.COMPLEX:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandComplexLite);
			case LispNames.NE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandNumericNotEqual);
			case LispNames.PARSE_INTEGER:
				// The shared expansion carries the full keyword set and the second
				// return value; the Environment function remains for first-class
				// use (#'parse-integer).
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandParseInteger);
			// read has no case here and no Environment function: it is a prelude defun
			// over read-char / unread-char / read-from-string (LispPreludeLibrary), so
			// an ordinary function resolution loads it and #'read is that same defun.
			case LispNames.READ_SEQUENCE:
				return evalSequenceWithGrayDispatch(cons, env, true);
			case LispNames.WRITE_SEQUENCE:
				return evalSequenceWithGrayDispatch(cons, env, false);
			case LispNames.MAKE_STRING:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMakeString);
			// REPLACE is intentionally NOT expanded here: the interpreter uses the
			// destructive built-in (Environment) so a make-string buffer filled by
			// successive replaces (cl-who's string-list-to-string) mutates in place.
			// The compilers still expand it to a fresh concatenate (no runtime string
			// mutation there; cl-who resolves it at macro-expansion time).
			case LispNames.LOWER_CASE_P:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandLowerCaseP);
			case LispNames.UPPER_CASE_P:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandUpperCaseP);
			case LispNames.CONSTANTP:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandConstantp);
			case LispNames.STREAMP:
				return eval(LispMacroExpander.expandStreamp(cons, true, true, this.closRegistry), env);
			case LispNames.SIMPLE_STRING_P:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandSimpleStringP);
			// make-broadcast-stream goes through the SAME expansion the compile paths
			// use, so the component form (a Gray output stream looping its components)
			// exists on every backend from one definition. The component-less form
			// expands to %make-string-output-stream, which is the very primitive the
			// Java built-in below it calls -- that one stays only so
			// #'make-broadcast-stream remains a value.
			case LispNames.MAKE_BROADCAST_STREAM:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMakeBroadcastStream);
			case LispNames.PROG2:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandProg2);
			case LispNames.PSETQ:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandPsetq);
			case LispNames.PSETF:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandPsetf);
			case LispNames.TYPECASE:
				ensureAsdfClassesFor(cons);
				ensureGeomClassesFor(cons);
				return eval(LispMacroExpander.expandTypecase(cons, this.closRegistry), env);
			case LispNames.ETYPECASE:
				ensureAsdfClassesFor(cons);
				ensureGeomClassesFor(cons);
				return eval(LispMacroExpander.expandEtypecase(cons, this.closRegistry), env);
			case LispNames.CTYPECASE:
				ensureAsdfClassesFor(cons);
				ensureGeomClassesFor(cons);
				return eval(LispMacroExpander.expandCtypecase(cons, this.closRegistry), env);
			case LispNames.CHECK_TYPE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandCheckType);
			case LispNames.ASSERT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandAssert);
			case LispNames.DECLARE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandDeclare);
			case LispNames.DECLAIM:
				// (declaim (special ...)) proclaims specialness before the form
				// collapses to nil; other declarations remain no-ops.
				SpecialVarCollector.collectForm(cons, this.specialVars);
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandDeclaim);
			case LispNames.PROCLAIM:
				SpecialVarCollector.collectForm(cons, this.specialVars);
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandProclaim);
			case LispNames.THE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandThe);
			case LispNames.EVAL_WHEN:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandEvalWhen);
			case LispNames.WITH_COMPILATION_UNIT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithCompilationUnit);
			case LispNames.WRITE_CHAR:
				return evalWriteCharWithGrayDispatch(cons, env);
			case LispNames.LOCALLY:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandLocally);
			case LispNames.WITH_STANDARD_IO_SYNTAX:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithStandardIoSyntax);
			case LispNames.FLET:
				return eval(LispMacroExpander.expandFlet(preExpandLocalMacros(cons)), env);
			case LispNames.LABELS:
				return eval(LispMacroExpander.expandLabels(preExpandLocalMacros(cons)), env);
			case LispNames.MULTIPLE_VALUE_BIND:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMultipleValueBind);
			case LispNames.MULTIPLE_VALUE_LIST:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMultipleValueList);
			case LispNames.MULTIPLE_VALUE_CALL:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMultipleValueCall);
			case LispNames.NTH_VALUE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandNthValue);
			case LispNames.MULTIPLE_VALUE_SETQ:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMultipleValueSetq);
			case LispNames.MULTIPLE_VALUE_PROG1:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMultipleValueProg1);
			case LispNames.ROTATEF:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandRotatef);
			case LispNames.SHIFTF:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandShiftf);
			case LispNames.LOAD_TIME_VALUE:
				return evalLoadTimeValue(cons, env);
			case LispNames.TYPEP:
				seedMopClassesForTypepForm(cons);
				ensureAsdfClassesFor(cons);
				ensureGeomClassesFor(cons);
				return eval(LispMacroExpander.expandTypep(cons, this.closRegistry), env);
			case LispNames.PRINT_UNREADABLE_OBJECT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandPrintUnreadableObject);
			case LispNames.WITH_OPEN_STREAM:
				return evalBuiltinMacro(cons, env, c -> LispMacroExpander.expandWithOpenStream(c, true));
			case LispNames.WITH_PACKAGE_ITERATOR:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithPackageIterator);
			case LispNames.WITH_HASH_TABLE_ITERATOR:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandWithHashTableIterator);
			case LispNames.DO_EXTERNAL_SYMBOLS:
			case LispNames.DO_SYMBOLS:
				return evalDoSymbols(cons, env, name);
			case LispNames.PROG:
				return evalBuiltinMacro(cons, env, c -> LispMacroExpander.expandProg(c, false));
			case LispNames.PROG_STAR:
				return evalBuiltinMacro(cons, env, c -> LispMacroExpander.expandProg(c, true));
			case LispNames.DEFINE_SYMBOL_MACRO:
				return evalDefineSymbolMacro(cons);
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
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandByte);
			case LispNames.BYTE_SIZE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandByteSize);
			case LispNames.BYTE_POSITION:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandBytePosition);
			case LispNames.LDB:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandLdb);
			case LispNames.MAKE_SEQUENCE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMakeSequence);
			case LispNames.DPB:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandDpb);
			case LispNames.DESTRUCTURING_BIND:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandDestructuringBind);
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
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandListStar);
			case LispNames.ACONS:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandAcons);
			case LispNames.ENDP:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandEndp);
			case LispNames.ELT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandElt);
			case LispNames.VECTOR:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandVector);
			case LispNames.SVREF:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandSvref);
			case LispNames.ARRAY_RANK:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandArrayRank);
			case LispNames.ARRAY_DIMENSION:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandArrayDimension);
			case LispNames.ARRAY_TOTAL_SIZE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandArrayTotalSize);
			case LispNames.ARRAY_ROW_MAJOR_INDEX:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandArrayRowMajorIndex);
			case LispNames.COERCE: {
				// A packed (unsigned-byte 8|16|32) result type is the one designator
				// expandCoerce cannot express (it collapses a compound spec to its head);
				// the shared lowering answers the same %seq-int-vector call every backend
				// uses (.kb/concatenate-result-families.md).
				LispVal packed = ConcatenateForms.packedVectorCoerce(cons, this.closRegistry);
				if (packed != null) {
					return eval(packed, env);
				}
				return evalSequenceCoerce(cons, env);
			}
			case LispNames.MAP_INTO:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMapInto);
			case LispNames.SEARCH:
			case LispNames.MISMATCH:
				// The ordinary function call, with a native scan in front of it: both are
				// prelude defuns whose elt-per-element inner loop costs the interpreter
				// ~2.5 us per element PAIR. The arm declines to this same call.
				return evalSequenceScan(cons, env, name);
			case LispNames.RASSOC:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandRassoc);
			// The sequence/alist functions taking :test/:key evaluate through the
			// shared macro expansion (like rassoc) so keyword handling matches the
			// compilers exactly; the Environment/LispEvaluator registrations remain
			// for first-class use (#'find etc.).
			case LispNames.FIND:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandFind);
			case LispNames.FIND_IF:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandFindIf);
			case LispNames.FIND_IF_NOT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandFindIfNot);
			case LispNames.POSITION:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandPosition);
			case LispNames.POSITION_IF:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandPositionIf);
			case LispNames.POSITION_IF_NOT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandPositionIfNot);
			case LispNames.COMPLEMENT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandComplement);
			case LispNames.COUNT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandCount);
			case LispNames.REMOVE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandRemove);
			case LispNames.DELETE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandDelete);
			case LispNames.REMOVE_DUPLICATES:
			case LispNames.DELETE_DUPLICATES:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandRemoveDuplicates);
			case LispNames.UNION:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandUnion);
			case LispNames.INTERSECTION:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandIntersection);
			case LispNames.SET_DIFFERENCE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandSetDifference);
			case LispNames.ADJOIN:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandAdjoin);
			case LispNames.SUBSETP:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandSubsetp);
			case LispNames.SUBSTITUTE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandSubstitute);
			case LispNames.NSUBSTITUTE:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandNsubstitute);
			case LispNames.SUBSTITUTE_IF:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandSubstituteIf);
			case LispNames.SUBSTITUTE_IF_NOT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandSubstituteIfNot);
			case LispNames.NSUBSTITUTE_IF:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandNsubstituteIf);
			case LispNames.NSUBSTITUTE_IF_NOT:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandNsubstituteIfNot);
			case LispNames.REVAPPEND:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandRevappend);
			case LispNames.NRECONC:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandNreconc);
			case LispNames.MAP:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMap);
			case LispNames.MAPLIST:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMaplist);
			case LispNames.MAPCON:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMapcon);
			case LispNames.MAPL:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandMapl);
			case LispNames.NOTANY:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandNotany);
			case LispNames.NOTEVERY:
				return evalBuiltinMacro(cons, env, LispMacroExpander::expandNotevery);
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
	 * Evaluates a built-in macro form through {@link #builtinMacroExpansions}: the
	 * expansion is computed once per call site and re-evaluated thereafter, like a
	 * {@code defmacro} call ({@link #expandUserMacro}) and like the compile backends.
	 * Callers may only pass an expander that is a pure function of the form -- see the
	 * memo field's contract and {@code .kb/interpreter-expansion-memo.md}. The expander
	 * runs outside the monitor (an expansion may recurse into the reader or another
	 * expansion); two threads racing on one call site both expand and the last write
	 * wins, a wasted expansion rather than a wrong answer. The EVALUATION is outside it
	 * too, on the hit path as much as the miss path: the expansion is a whole program,
	 * and a program can hand over to another thread (the macOS main thread, a socket
	 * read) that then needs the monitor for a memo of its own -- holding it across the
	 * eval parks both halves.
	 * @param cons the macro call form
	 * @param env the environment
	 * @param expander the pure syntactic expansion of one built-in macro
	 * @return the value of the (memoized) expansion
	 */
	private LispVal evalBuiltinMacro(LispCons cons, Environment env,
			java.util.function.Function<LispCons, LispVal> expander) {
		LispVal cached;
		synchronized (this.builtinMacroExpansions) {
			cached = this.builtinMacroExpansions.get(cons);
		}
		if (cached != null) {
			return eval(cached, env);
		}
		LispVal expansion = expander.apply(cons);
		synchronized (this.builtinMacroExpansions) {
			if (this.builtinMacroExpansions.size() < EXPANSION_MEMO_LIMIT) {
				this.builtinMacroExpansions.put(cons, expansion);
			}
		}
		return eval(expansion, env);
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
		// A syntactic multiple-value producer (gethash/floor-family/find-symbol/
		// intern/array-displacement) in the body's tail publishes through %mv-spill,
		// so its secondary value survives the function return like a values tail
		// does (defmethod bodies arrive here as defuns). The interpreter's spill
		// global always exists, so no gate is needed; the compile paths run the same
		// rewrite in LispMacroExpander.injectMvSpillGlobal.
		blockForm = LispMacroExpander.spillEscapingMvProducers(blockForm);
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
		// Regenerate the dispatchers that test struct specializers (the struct-side
		// twin of evalDefclass's regeneration): this struct may have widened a
		// struct specializer's descendant tag set or the structure-object
		// enumeration (sxql defines convert-for-sql's structure-object method in
		// operator.lisp and the clause structs it must catch in clause.lisp).
		for (ClosRegistry.GenericInfo generic : this.closRegistry.generics().values()) {
			if (generic.hasStructMethod(this.closRegistry)) {
				defineDispatcher(generic.name(), env);
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
		LispVal driverMetaobject = null;
		for (LispVal form : LispMacroExpander.expandDefclass(cons, this.closRegistry, this.structAccessors)) {
			LispVal value = eval(form, env);
			if (isEnsureClassDriverCall(form)) {
				driverMetaobject = value;
			}
		}
		// A user initialize-instance :around may have INJECTED direct superclasses the
		// static registration never saw (mito's dao-table-class pushes dao-class and the
		// auto-pk/timestamp mixins). The driver-built metaobject is the truth, per AMOP;
		// reconcile the static side -- layout, ancestors (typep/dispatch), constructor,
		// accessors -- by re-registering the class with the metaobject's superclass
		// list. The driver call itself is NOT re-run (its :arounds are user code).
		if (driverMetaobject instanceof LispInstance metaobject) {
			LispCons widened = LispMacroExpander.widenDefclassToMetaobjectSupers(cons, metaobject);
			if (widened != null) {
				for (LispVal form : LispMacroExpander.expandDefclass(widened, this.closRegistry,
						this.structAccessors)) {
					if (!isEnsureClassDriverCall(form)) {
						eval(form, env);
					}
				}
				// The static re-registration invalidated the find-class memo; the
				// driver-built metaobject stays the class's canonical view.
				if (cons.toList().get(1) instanceof LispSymbol nameSym) {
					this.closRegistry.registerClassMetaobject(nameSym.name(), metaobject);
				}
			}
		}
		for (ClosRegistry.GenericInfo generic : this.closRegistry.generics().values()) {
			if (generic.hasClassMethod()) {
				defineDispatcher(generic.name(), env);
			}
		}
		return cons.toList().get(1);
	}

	/**
	 * Whether the generated form is the metaclass-protocol driver call
	 * ({@code %ensure-class-with-metaclass}) an {@code expandDefclass} of a
	 * {@code :metaclass} defclass ends with.
	 */
	private static boolean isEnsureClassDriverCall(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.ENSURE_CLASS_WITH_METACLASS.equals(plainName(op.name()));
	}

	/**
	 * The macro-time-evaluator half of the injected-superclass reconciliation (see
	 * {@code evalDefclass}): after this evaluator ran a {@code :metaclass} defclass, the
	 * registered metaobject carries the direct superclasses a user
	 * {@code initialize-instance :around} injected. The compile paths re-register the
	 * class statically from the FORM, so {@code UserMacroExpander} asks here for the
	 * widened spelling to emit instead; null when nothing was injected (or the form is
	 * not a {@code :metaclass} defclass).
	 * @param defclass the defclass form as the compile pipeline will see it
	 * @return the widened defclass form, or null
	 */
	@Nullable public LispCons widenedMetaclassDefclassOrNull(LispCons defclass) {
		if (!LispMacroExpander.defclassUsesMetaclass(defclass)) {
			return null;
		}
		List<LispVal> parts = defclass.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispSymbol nameSym)) {
			return null;
		}
		LispInstance metaobject = this.closRegistry.classMetaobject(nameSym.name());
		if (metaobject == null) {
			return null;
		}
		return LispMacroExpander.widenDefclassToMetaobjectSupers(defclass, metaobject);
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
		// The dispatcher's last-resort signal is a call of the shared
		// %no-applicable-method defun (one AST shape on every backend), so define it --
		// and seed the condition class its typed signal names -- before the first
		// dispatcher that may reach it.
		if (this.globalEnv.lookupFunctionOrNull(LispNames.NO_APPLICABLE_METHOD_RUNTIME) == null) {
			this.closRegistry.ensureNoApplicableErrorSeeded();
			eval(LispMacroExpander.noApplicableMethodDefun(), env);
		}
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
	 * Evaluates {@code (do-external-symbols (var package [result]) body...)} and its
	 * {@code do-symbols} sibling: iterates the designated package's symbols (canonically
	 * spelled, in sorted order -- the registry records exports from {@code defpackage},
	 * so this is the real listing, unlike the empty {@code with-package-iterator} lite).
	 * The result form is evaluated with the variable bound to nil, per CL.
	 * @param cons the form
	 * @param env the environment
	 * @param operator the operator name, which also selects the symbol source:
	 * {@code do-external-symbols} takes the exports, {@code do-symbols} everything
	 * accessible
	 * @return the result form's value, or nil
	 */
	private LispVal evalDoSymbols(LispCons cons, Environment env, String operator) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispCons specCons) || specCons.toList().isEmpty()
				|| !(specCons.toList().get(0) instanceof LispSymbol var)) {
			throw new LispEvalException(operator + " expects ((var package [result]) body...): " + cons.print());
		}
		List<LispVal> spec = specCons.toList();
		String designator = spec.size() >= 2 ? packageDesignator(operator, eval(spec.get(1), env))
				: this.packageResolver.currentPackageName();
		List<LispSymbol> symbols = LispNames.DO_SYMBOLS.equals(operator)
				? this.packageResolver.accessibleSymbols(designator) : this.packageResolver.externalSymbols(designator);
		for (LispSymbol sym : symbols) {
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
		putUserMacro(name.name(),
				makeUserMacro(LispNames.DEFMACRO, name, parts.get(2), parts.subList(3, parts.size()), env));
		return name;
	}

	/**
	 * Registers a {@code (define-symbol-macro name expansion)}: from here on a reference
	 * to {@code name} in a value position evaluates {@code expansion}, and a
	 * {@code setq}/{@code setf} of it writes through {@code expansion} as a place. The
	 * expansion form is stored UNEVALUATED -- it is code, re-evaluated at every
	 * reference.
	 * @param cons the define-symbol-macro form
	 * @return the defined name
	 */
	private LispVal evalDefineSymbolMacro(LispCons cons) {
		LispSymbol name = LispMacroExpander.defineSymbolMacroName(cons);
		if (PackageRegistry.isClSymbol(name.name())) {
			throw new LispEvalException(
					LispNames.DEFINE_SYMBOL_MACRO + " cannot redefine the standard operator " + name.name());
		}
		this.globalSymbolMacros.put(name.name(), cons.toList().get(2));
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
			return new UserMacro(List.of(), argsVar, List.of(wrapped), env, this.packageResolver.currentPackageName());
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
			return new UserMacro(List.of(), argsVar, List.of(wrapped), env, this.packageResolver.currentPackageName());
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
		return new UserMacro(required, rest, body, env, this.packageResolver.currentPackageName());
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
			putUserMacro(name.name(), macro);
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
					putUserMacro(n, saved.get(n));
				}
				else {
					removeUserMacro(n);
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
	 * Installs a user macro, invalidating the expansion memo.
	 * @param name the macro name
	 * @param macro the macro
	 */
	private void putUserMacro(String name, UserMacro macro) {
		this.userMacros.put(name, macro);
		invalidateUserMacroExpansions();
	}

	/**
	 * Drops a user macro, invalidating the expansion memo when the table really changed.
	 * @param name the macro name
	 */
	private void removeUserMacro(String name) {
		if (this.userMacros.remove(name) != null) {
			invalidateUserMacroExpansions();
		}
	}

	/**
	 * Drops every memoized user-macro expansion. Called on any change to the macro table:
	 * a cached expansion is only valid for the macro definitions that produced it, and a
	 * {@code macrolet} entering or leaving scope is such a change. Dropping the whole
	 * memo rather than the affected call sites is the cheap and obviously correct answer
	 * -- the memo refills as the call sites are reached again.
	 */
	private void invalidateUserMacroExpansions() {
		synchronized (this.userMacroExpansions) {
			if (!this.userMacroExpansions.isEmpty()) {
				this.userMacroExpansions.clear();
			}
		}
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
	 * Returns the names of every user macro this evaluator holds, sorted -- the macro
	 * table {@code UserMacroExpander} bakes into a compiled program so its
	 * {@code macro-function} answers for the program's own macros too.
	 * @return the sorted user macro names
	 */
	public List<String> userMacroNames() {
		return this.userMacros.keySet().stream().sorted().toList();
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
		putUserMacro(alias, macro.getValue());
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
		putUserMacro(name.name(), makeUserMacro(LispNames.MACROLET, name, paramForm, body, this.globalEnv));
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
			putUserMacro(name, macro);
		}
		else {
			removeUserMacro(name);
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
		ensureUiopSetfPlaceLoaded(cons);
		ensureUsocketSetfPlaceLoaded(cons);
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
	 * Loads the uiop definition of a {@code setf} PLACE before the form is expanded --
	 * the third trigger of the interpreter's lazy load, beside the function and the
	 * variable lookup. A uiop member's writer is a {@code (defun (setf name) ...)} in the
	 * same definition group as its reader ({@code eval.UiopLibrary}), and what registers
	 * the place is EVALUATING that defun; a program whose first touch of the member is
	 * the write ({@code (setf (uiop:getenv k) v)} -- rove's {@code with-local-envs})
	 * would otherwise expand against an empty registry and fail with "setf does not
	 * support place", while the compile paths, which splice the definition ahead of the
	 * program, handle it.
	 * @param cons the setf form, whose place heads are inspected
	 */
	private void ensureUiopSetfPlaceLoaded(LispCons cons) {
		if (!cons.isProperList()) {
			return;
		}
		List<LispVal> parts = cons.toList();
		for (int i = 1; i + 1 < parts.size(); i += 2) {
			if (parts.get(i) instanceof LispCons place && place.car() instanceof LispSymbol accessor
					&& !this.structAccessors.containsKey(accessor.name()) && UiopLibrary.definesName(accessor.name())) {
				loadUiopDefinition(accessor.name());
			}
		}
	}

	/**
	 * The usocket twin of {@link #ensureUiopSetfPlaceLoaded}: the shim's
	 * {@code (defun (setf usocket:socket-option) ...)} registers its place only when
	 * {@code usocket.lisp} loads, and a program whose first usocket touch is the write
	 * ({@code (setf (usocket:socket-option s :receive-timeout) n)} -- dexador's read
	 * timeout) would otherwise expand against an empty registry.
	 * @param cons the setf form, whose place heads are inspected
	 */
	private void ensureUsocketSetfPlaceLoaded(LispCons cons) {
		if (this.usocketLibraryLoaded || !cons.isProperList()) {
			return;
		}
		List<LispVal> parts = cons.toList();
		for (int i = 1; i + 1 < parts.size(); i += 2) {
			if (parts.get(i) instanceof LispCons place && place.car() instanceof LispSymbol accessor
					&& !this.structAccessors.containsKey(accessor.name())
					&& UsocketLibrary.isUsocketQualified(accessor.name())) {
				ensureUsocketLoaded();
				return;
			}
		}
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
	 * macro parameters and evaluates the macro body, returning the expansion form. The
	 * answer is memoized per call site ({@link #userMacroExpansions}), so a macro call
	 * reached a million times is expanded once.
	 * @param form the macro call form; its operator must be a defined user macro
	 * @return the expansion
	 */
	public LispVal expandUserMacro(LispCons form) {
		synchronized (this.userMacroExpansions) {
			LispVal cached = this.userMacroExpansions.get(form);
			if (cached != null) {
				return cached;
			}
		}
		String name = ((LispSymbol) form.car()).name();
		UserMacro macro = this.userMacros.get(name);
		if (macro == null) {
			throw new LispEvalException(name + " is not a user macro");
		}
		// Outside the monitor: a macro body is a whole program, it re-enters this method
		// for the macro calls inside it, and it may take the library load lock.
		LispVal expansion = expandMacroCall(name, macro, form);
		synchronized (this.userMacroExpansions) {
			if (this.userMacroExpansions.size() < EXPANSION_MEMO_LIMIT) {
				this.userMacroExpansions.put(form, expansion);
			}
		}
		return expansion;
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
		// Common Lisp expands a macro while COMPILING the calling file, so *package* is
		// that file's. The interpreter expands lazily instead, at call time, and only a
		// TOP-LEVEL call site still has its file's package current -- a call buried in a
		// function body is expanded long after the file was read, with whatever package
		// the caller happens to be in. For those the macro's own DEFINING package is the
		// closest available answer (and the exact one for the overwhelmingly common case
		// of a macro used in the file that defines it): fast-http's callback-data
		// expands (alexandria:format-symbol t "~A-~A" :callbacks name) into an accessor
		// call, and a cl-user caller got a bare CALLBACKS-HEADER-FIELD instead of the
		// fast-http.parser one. A top-level call site KEEPS the current package, which
		// is both correct and what a macro generating names for the file that calls it
		// needs (trivia's lispn:define-namespace).
		String callerPackage = this.packageResolver.currentPackageName();
		boolean swapPackage = this.functionBodyDepth > 0 && !callerPackage.equals(macro.definitionPackage());
		if (swapPackage) {
			this.packageResolver.setCurrentPackage(macro.definitionPackage());
		}
		try {
			LispVal expansion = LispNil.INSTANCE;
			for (LispVal bodyExpr : macro.body()) {
				expansion = eval(bodyExpr, macroEnv);
			}
			return expansion;
		}
		finally {
			if (swapPackage) {
				this.packageResolver.setCurrentPackage(callerPackage);
			}
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

	/**
	 * Publishes {@code macroexpand-1}/{@code macroexpand}'s {@code expanded-p} second
	 * value and returns the primary. Both expanders answer the SAME reference when the
	 * operator is not a macro, so identity decides the flag; it travels through the
	 * {@code %mv-spill} channel (the {@code parse-integer} precedent), which is what
	 * carries it across the call boundary into a consumer.
	 * @param form the form that was handed in
	 * @param expansion what the expander answered
	 * @return the expansion
	 */
	private LispVal expandedWithFlag(LispVal form, LispVal expansion) {
		this.globalEnv.define(LispNames.MV_SPILL,
				new LispCons(expansion == form ? LispNil.INSTANCE : LispTrue.INSTANCE, LispNil.INSTANCE));
		return expansion;
	}

	/**
	 * Whether the name has a macro function: a user {@code defmacro} (a {@code macrolet}
	 * body's local macro and a {@code (setf macro-function)} alias register in the same
	 * table) or a built-in operator with no function value that is not one of the 25 ANSI
	 * special operators -- the CL macros the expander dispatches on, plus the ones
	 * rontolisp implements as special forms of its own.
	 */
	private boolean isMacroName(String name) {
		return isUserMacro(name)
				|| (SPECIAL_OPERATORS.contains(name) && !PackageRegistry.namesWithoutMacroFunction().contains(name));
	}

	/**
	 * The form a macro function received, headed by the macro it belongs to. CL applies
	 * the expander to the WHOLE form and the expander reads the car as data (only
	 * {@code &whole} looks at it), so {@code (funcall (macro-function 'when) '(foo t 1))}
	 * expands through {@code when} exactly as it does in CL.
	 */
	private static LispVal macroCallForm(String name, LispVal form) {
		if (form instanceof LispCons cons && !(cons.car() instanceof LispSymbol head && head.name().equals(name))) {
			return new LispCons(new LispSymbol(name), cons.cdr());
		}
		return form;
	}

	/**
	 * {@code (defconstant name value)}: an ordinary forcing defvar, plus the value
	 * recorded for {@code (eql name)} parameter specializers -- CLHS 7.6.2 evaluates an
	 * eql specializer form when the method is defined, so a method naming a constant
	 * dispatches on the constant's VALUE. Recorded from the EVALUATED value, which the
	 * interpreter has and the compile-path walk (literal value forms only) does not.
	 */
	private LispVal evalDefconstant(LispCons cons, Environment env) {
		LispVal name = evalDefvar(cons, env, true);
		if (name instanceof LispSymbol sym && this.globalEnv.isBound(sym.name())) {
			LispMacroExpander.registerConstantValue(sym.name(), this.globalEnv.lookup(sym.name()), this.closRegistry);
		}
		return name;
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
			// A GENERIC function's #' value is late-bound: defmethod REDEFINES the
			// dispatcher under the name (evalDefmethod), so a captured snapshot would
			// silently miss every method a later-loaded system adds -- dbi stashes
			// #'disconnect in its connection pool at load time and dbd-postgres
			// defines its method afterwards. The wrapper resolves the CURRENT binding
			// at call time (real CL semantics: #'name IS the generic object methods
			// join). Non-generic names keep the direct value.
			if (this.closRegistry.findGeneric(sym.name()) != null) {
				String name = sym.name();
				return new LispFunction(name, args -> apply(resolveFunction(name), args, this.globalEnv));
			}
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

	/** A list of package VALUES (the {@code find-package} keyword shape) from names. */
	private static LispVal packageKeywordList(java.util.Collection<String> names) {
		LispVal result = LispNil.INSTANCE;
		List<String> reversed = new ArrayList<>(names);
		java.util.Collections.reverse(reversed);
		for (String name : reversed) {
			result = new LispCons(packageKeyword(name), result);
		}
		return result;
	}

	/**
	 * The UPCASED canonical name of a registered package, signalling like Common Lisp's
	 * package-error when the designator names none.
	 */
	private String packageName(String operator, String designator) {
		String found = this.packageResolver.findPackageName(designator);
		if (found == null) {
			throw new LispEvalException(operator + ": no package named " + designator);
		}
		return found.toUpperCase(java.util.Locale.ROOT);
	}

	/** The use list of a registered package, by canonical name. */
	private List<String> packageUseEntry(String operator, String designator) {
		List<String> used = this.packageResolver.runtimePackageUseTable().get(packageName(operator, designator));
		return used == null ? List.of() : used;
	}

	/**
	 * The spelling an {@code import} argument names: a SYMBOL's stored spelling, verbatim
	 * -- unlike a package designator, the qualifier is the whole point here (it says
	 * which package the symbol comes from).
	 */
	private static String importSpelling(LispVal val) {
		return switch (val) {
			case LispString str -> str.value();
			case LispSymbol sym -> sym.name().startsWith("#:") ? sym.name().substring(2) : sym.name();
			default -> throw new LispEvalException(LispNames.IMPORT + " expects a symbol, got " + val.print());
		};
	}

	/**
	 * Coerces a runtime package designator -- a string, a keyword/symbol, or a package
	 * value (which IS a keyword here, see {@code find-package}) -- to the bare package
	 * name.
	 */
	private static String packageDesignator(String operator, LispVal val) {
		return switch (val) {
			case LispString str -> str.value();
			// A symbol designates a package by its symbol-name -- the MEMBER part of a
			// qualified spelling (CL: the qualifier says where the symbol lives, not
			// what it names). A #.*package* splice re-resolved as quoted data arrives
			// qualified (rte-pkg::rte-pkg) and still designates RTE-PKG.
			case LispSymbol sym -> LispSymbol.memberName(sym.name());
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
	/**
	 * The current value of the global (dynamic-first) variable a synonym stream names --
	 * {@code symbol-value}'s rule, which is what the reader closure of an interpreter
	 * synonym stream answers.
	 * @param name the variable name
	 * @return the current value
	 */
	private LispVal symbolValueOf(String name) {
		if (LispNames.PACKAGE_VAR.equals(name)) {
			return currentPackageValue();
		}
		if (this.dynamicBindings.isBound(name)) {
			return this.dynamicBindings.get(name);
		}
		LispVal value = this.globalEnv.lookupOrNull(name);
		if (value == null) {
			throw LispEvalException.ofClass(ClosRegistry.UNBOUND_VARIABLE_CLASS_NAME,
					ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX + name + ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX);
		}
		return value;
	}

	/**
	 * The argument list with its stream argument resolved through a SYNONYM STREAM. Every
	 * Gray-dispatching built-in wrap runs this first: a synonym stream is an instance
	 * too, so without it the wrap would send the synonym itself to the Gray generic
	 * instead of forwarding to what it names (which may in turn BE a Gray instance).
	 * @param args the call arguments
	 * @param index the position of the stream argument
	 * @return the same list when nothing forwards, else a copy with the resolved
	 * designator
	 */
	private static List<LispVal> resolveStreamArg(List<LispVal> args, int index) {
		if (index >= args.size() || !Environment.isSynonymStream(args.get(index))) {
			return args;
		}
		List<LispVal> resolved = new java.util.ArrayList<>(args);
		// The SYNONYM half only: the base built-ins resolve an open stream to its handle
		// themselves, and the Gray arm below has to see the value to tell the two apart.
		resolved.set(index, Environment.synonymTarget(args.get(index)));
		return resolved;
	}

	/**
	 * Whether a resolved stream argument goes to the Gray protocol rather than to the
	 * handle-based built-in. Every stream is an instance now, so "is an instance" is no
	 * longer the question: an OPEN stream ({@code LispLayout.STREAM}) is exactly the kind
	 * the built-in owns.
	 * @param value the resolved stream argument
	 * @return true for a Gray (or synonym) instance, false for an open stream
	 */
	private static boolean dispatchesToGray(LispVal value) {
		return value instanceof LispInstance && !Environment.isStreamValue(value);
	}

	private static int requireSlotIndex(String name, LispInstance inst, List<LispVal> args) {
		if (args.size() < 2 || !(args.get(1) instanceof LispInteger idx)) {
			throw new LispEvalException(name + " expects a slot index");
		}
		long k = idx.value();
		// capacity, not slotCount: the addressable storage of an instance is what its
		// layout RESERVED, which the compile paths index without a check of their own.
		if (k < 0 || k >= inst.layout().capacity()) {
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
		if (this.userMacros.containsKey(name)) {
			throw new LispEvalException(name + " is a macro or special operator, not a function");
		}
		// A BuiltinFunctionWrappers entry IS the function value of a built-in that
		// evalCons lowers in operator position but Environment never binds as a
		// LispFunction (elt, coerce, vector, map, typep, /=, ...): the catalog is by
		// construction "built-in FUNCTIONS every backend lowers in call position", so
		// evaluating its lambda here answers exactly what the compile paths answer,
		// from the same table -- rather than a second list of Java builtins that drifts
		// from it. It runs BEFORE the special-operator guard for the same reason the
		// lookup above does: a name with a wrapper is a function, whatever the operator
		// table calls it (typep is the CL function rontolisp implements as a special
		// form). The synthesized lambda cannot recurse: every wrapped operator has a
		// real lowering, so its body never resolves back to this branch.
		LispVal wrapper = BuiltinFunctionWrappers.lambdaFor(name);
		if (wrapper != null) {
			return eval(wrapper, this.globalEnv);
		}
		if (SPECIAL_OPERATORS.contains(name)) {
			throw new LispEvalException(name + " is a macro or special operator, not a function");
		}
		// Everything below LOADS something into the shared global environment, so it runs
		// under the library lock: a concurrently served request must either see the load
		// finished or wait for it, never fall through a gate whose flag is already set
		// while the definitions it guards are still being evaluated.
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
					LinalgSimd.install(this.globalEnv, this, this.parallel);
				}
				// Opt-in --blas, installed LAST so that what it declines to is the
				// simd native when there is one and the scalar defun otherwise.
				if (this.blas) {
					LinalgBlas.install(this.globalEnv, this);
				}
				// Opt-in --gpu, installed after --blas so that the device is asked
				// FIRST and what it declines to is the best CPU path this invocation
				// enabled (.kb/gpu.md).
				if (this.gpu) {
					LinalgGpu.install(this.globalEnv, this);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The appkit package is a Lisp-source library (appkit.lisp) over the objc:
			// verbs: load it the same way on the first resolution of an appkit:-qualified
			// function. Nothing here asks whether this machine has AppKit -- the objc:
			// call inside the first widget signals if it does not.
			if (!this.appkitLibraryLoaded && AppKitLibrary.isAppkitQualified(name)) {
				this.appkitLibraryLoaded = true;
				for (LispVal form : AppKitLibrary.forms()) {
					eval(form, this.globalEnv);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The geom package is a Lisp-source library (geom.lisp) over linalg: solid
			// modeling, loaded the same way on the first resolution of a geom:-qualified
			// function. Unlike appkit it needs nothing of the host -- the linalg
			// definitions its bodies call load through this same hook on their first
			// call.
			if (!this.geomLibraryLoaded && GeomLibrary.isGeomQualified(name)) {
				ensureGeomLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The metal package is a Lisp-source library (metal.lisp) over the objc:
			// verbs: a Metal drawing surface on an appkit window, loaded the same way on
			// the first resolution of a metal:-qualified function. Its appkit:timer
			// clock loads the widget layer through this same hook on its first call.
			if (!this.metalLibraryLoaded && MetalLibrary.isMetalQualified(name)) {
				ensureMetalLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The scene package is a Lisp-source library (scene.lisp) over geom and
			// metal: the 3-D viewer, loaded the same way on the first resolution of a
			// scene:-qualified function. Everything it stands on -- geom, metal, linalg,
			// appkit -- loads through this same hook on first call.
			if (!this.sceneLibraryLoaded && SceneLibrary.isSceneQualified(name)) {
				this.sceneLibraryLoaded = true;
				for (LispVal form : SceneLibrary.forms()) {
					eval(form, this.globalEnv);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The torch package is a Lisp-source library (torch.lisp) over linalg: load
			// it the same way on the first resolution of a torch:-qualified function
			// (the linalg definitions its bodies call load through this same hook on
			// their first call).
			if (!this.torchLibraryLoaded && TorchLibrary.isTorchQualified(name)) {
				ensureTorchLoaded();
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
					VecSimd.install(this.globalEnv, this.parallel);
				}
				// Opt-in --gpu: vec:matvec is the one device member outside linalg:, and
				// it is installed here, on TOP of the lane kernel, when THIS library
				// loads -- the two libraries load independently, so it cannot ride on
				// the linalg hook above (.kb/gpu.md).
				if (this.gpu) {
					LinalgGpu.installVec(this.globalEnv, this);
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
				LispVal loaded = loadPreludeDefinition(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The uiop family (uiop-*.lisp plus the not-implemented-error stubs
			// UiopLibrary synthesizes for every export nothing implements yet) loads the
			// same way, one definition at a time.
			if (UiopLibrary.definesName(name)) {
				LispVal loaded = loadUiopDefinition(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The asdf runtime (asdf.lisp: the component metaobject classes,
			// find-system and the readers) loads whole on the first resolution of one
			// of its names.
			if (!this.asdfRuntimeLoaded && AsdfRuntimeLibrary.definesName(name)) {
				ensureAsdfRuntimeLoaded();
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
			// The host-driven-reactor transport (http-reactor.lisp): a Clack handler
			// backend's run/handle/dispatch delegate to it, so its first touch is
			// always a function call. Before the broader %HTTP- hook below, which
			// would answer the same prefix by loading http-server.lisp (and the Gray
			// protocol with it) for nothing -- a reactor loads that model on the
			// first %http-make-env call, not on the run that stores the app.
			if (!this.httpReactorLoaded && name.startsWith("RONTOLISP::%HTTP-REACTOR-")) {
				ensureHttpReactorLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The server-side HTTP value model (http-server.lisp) loads eagerly when a
			// server starts; a program calling one of its functions DIRECTLY (the
			// ci-spec shape cases exercise the environment builder and the response
			// normalizer without serving) lazy-loads it here, the usocket/restart
			// pattern. The prefix test keeps ordinary undefined names cheap.
			if (!this.httpServerLoaded && name.startsWith("RONTOLISP::%HTTP-")) {
				ensureHttpServerLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// An instance-initialization generic called with no user method anywhere
			// (upstream ASDF's (apply 'reinitialize-instance system keys)): CL supplies
			// the system default, so synthesize the generic with it -- the same
			// machinery the first user defmethod on one of these names runs -- and
			// define the dispatchers it registered.
			if (LispMacroExpander.isCallableSystemGenericName(name) && this.closRegistry.findGeneric(name) == null) {
				for (LispVal form : LispMacroExpander.synthesizeCalledSystemGeneric(name, this.closRegistry)) {
					eval(form, this.globalEnv);
				}
				for (ClosRegistry.GenericInfo info : this.closRegistry.generics().values()) {
					if (this.globalEnv.lookupFunctionOrNull(info.name()) == null) {
						defineDispatcher(info.name(), this.globalEnv);
					}
				}
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
			throw LispEvalException.ofClass(ClosRegistry.UNDEFINED_FUNCTION_CLASS_NAME,
					ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX + name
							+ ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX);
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
	 *
	 * <p>
	 * Also the one place uiop's condition CLASSES are registered
	 * ({@link #ensureUiopConditionClassesLoaded}): "the program has conditions" is
	 * exactly when they have to exist, and a class cannot be lazy the way a function can.
	 */
	private void ensureConditionReportRuntimeLoaded() {
		synchronized (this.libraryLoadLock) {
			ensureUiopConditionClassesLoaded();
			if (this.loadingUiopConditionClasses) {
				// Re-entered from one of those define-conditions: the outer call rebuilds
				// the renderer once, when the whole group is registered.
				return;
			}
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
	 * Evaluates the generated print-object runtime ({@code %print-object-str} and
	 * {@code %print-object-leaf}) into the global environment, and re-evaluates it
	 * whenever the routing has moved -- a later {@code defmethod print-object}, a later
	 * {@code define-condition}, or {@code *print-case*} entering or leaving its
	 * converting modes. The compile path emits the pair once because
	 * {@code expandTopLevelDefinitions} runs with a complete registry, which the
	 * interpreter never has.
	 *
	 * <p>
	 * A generated DEFUN rather than a body inlined at the print site (which is what this
	 * was before nested rendering): the renderer walks a list/vector by recursing into
	 * itself, and an inlined form cannot recurse.
	 * @param printCase whether {@code *print-case*} currently converts
	 */
	private void ensurePrintObjectRuntimeLoaded(boolean printCase) {
		synchronized (this.libraryLoadLock) {
			java.util.List<String> tags = LispMacroExpander.printObjectTags(this.closRegistry);
			int stamp = tags.hashCode() * 4 + (this.closRegistry.routesConditionReports() ? 1 : 0)
					+ (printCase ? 2 : 0);
			if (stamp == this.printObjectRuntimeStamp) {
				return;
			}
			this.printObjectRuntimeStamp = stamp;
			// The interpreter always emits the vector arm: the gate the compile paths use
			// exists to keep the array runtime out of an array-free ARTIFACT, and there
			// is no artifact here.
			for (LispVal form : LispMacroExpander.printObjectStrDefuns(this.closRegistry, printCase, true)) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * {@link #ensurePrintObjectRuntimeLoaded} guarded by the gate that decides whether
	 * the pair is generated at all: a program with no {@code print-object} method and no
	 * condition in reach is rewritten straight onto {@code %print-cased} (or not at all),
	 * and must not carry a renderer it never calls.
	 * @param printCase whether {@code *print-case*} currently converts
	 */
	private void ensurePrintObjectRuntimeLoadedIfRouted(boolean printCase) {
		if (!LispMacroExpander.printObjectTags(this.closRegistry).isEmpty()
				|| this.closRegistry.routesConditionReports()) {
			ensurePrintObjectRuntimeLoaded(printCase);
		}
	}

	/**
	 * Evaluates the runtime format renderer into the global environment, once per
	 * evaluator. The forms are the ones {@code expandTopLevelDefinitions} injects on the
	 * compile path, so the interpreter and every compiled backend render a runtime
	 * control string with the same code.
	 *
	 * <p>
	 * The {@code ~/name/} arm is loaded unconditionally, which the compile path does NOT
	 * do: there the arm makes every function dispatchable and it is injected only for a
	 * program whose control strings spell the directive ({@code .kb/format.md}). Nothing
	 * is dead-code eliminated here, and the name resolves against a live symbol table, so
	 * the interpreter has nothing to gain from the same narrowing.
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
			for (LispVal form : FormatRenderer.functionDesignatorDefuns()) {
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
		// Hands the datum back AS IS, and must: at run time a (quote <value>) form is
		// also how a LIVE value is spliced back into a form for re-evaluation
		// (quoteValue, four sites, one of them read-sequence's Gray-dispatch rebuild),
		// so materializing an array here would hand a destructive operation a copy.
		// This sharing is also the RULE, not a residual: since todo 579 both compile
		// backends memoize a quoted datum to the same effect, so '#(1 2 3) is one
		// shared constant everywhere while a bare #(1 2 3) is fresh everywhere --
		// .kb/quoted-data.md.
		return rest.car();
	}

	/**
	 * Evaluates one {@link LispPreludeLibrary} entry into the global environment and
	 * records the function object it installed.
	 *
	 * <p>
	 * The record is what lets a native fast arm tell the prelude's own definition from a
	 * user's: {@link SequenceScanFast} serves a {@code search}/{@code mismatch} call only
	 * while the name still resolves to the object this method saw, so a
	 * {@code (defun search ...)} of the caller's own keeps the whole call, not the shapes
	 * the arm happens to decline.
	 * @param name the prelude entry to load; the caller has already claimed it in
	 * {@code loadedPreludeNames}
	 * @return the function the entry installed, or {@code null} when it defines no
	 * function of that name (a {@code (setf PLACE)} writer)
	 */
	private @Nullable LispVal loadPreludeDefinition(String name) {
		for (LispVal form : LispPreludeLibrary.formsFor(name)) {
			eval(form, this.globalEnv);
		}
		LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
		if (loaded != null) {
			this.preludeDefinitions.put(name, loaded);
		}
		return loaded;
	}

	/**
	 * Evaluates a {@code search} or {@code mismatch} call: the ordinary function call,
	 * with {@link SequenceScanFast}'s native scan in front of it.
	 *
	 * <p>
	 * Both are Lisp-source prelude {@code defun}s on every backend, and both index their
	 * operands with {@code elt} inside an interpreted loop -- 104 us for a five-character
	 * needle in a 46-character string, against 0.9 us for the same source compiled. The
	 * arm answers only the shapes it can prove identical and DECLINES the rest, which
	 * then run the prelude body unchanged; see {@link SequenceScanFast} and
	 * {@code .kb/seq-coerce-runtime.md}.
	 *
	 * <p>
	 * The function is resolved and the arguments evaluated exactly as the ordinary call
	 * path does, in that order, so a decline costs one array scan and changes nothing --
	 * not the evaluation order, not the number of evaluations, not the error a bad
	 * operand raises.
	 * @param cons the call
	 * @param env the evaluation environment
	 * @param name {@link LispNames#SEARCH} or {@link LispNames#MISMATCH}
	 * @return the operator's value
	 */
	private LispVal evalSequenceScan(LispCons cons, Environment env, String name) {
		LispVal function = resolveFunction(name);
		List<LispVal> args = evalArgs(cons, env);
		if (function == this.preludeDefinitions.get(name)) {
			LispVal fast = LispNames.SEARCH.equals(name) ? SequenceScanFast.search(args)
					: SequenceScanFast.mismatch(args);
			if (fast != null) {
				return fast;
			}
		}
		return apply(function, args, env);
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
				loadPreludeDefinition(head.name());
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
	 * Whether the program defines any {@code make-load-form} method at all -- the cheap
	 * gate {@link LoadFormSubstituter} tests before walking a top-level form for literal
	 * instances. Almost no program has one, and those pay nothing.
	 * @return {@code true} when a {@code make-load-form} generic with at least one
	 * specialized method is registered
	 */
	public boolean hasMakeLoadFormMethods() {
		ClosRegistry.GenericInfo generic = this.closRegistry.findGeneric(LispNames.MAKE_LOAD_FORM);
		return generic != null && generic.methods().values().stream().anyMatch(m -> !m.isDefault());
	}

	/**
	 * The {@code make-load-form} values of a literal instance the compile path is about
	 * to dump, or null when the program defines no method specialized on its type.
	 *
	 * <p>
	 * Null is the ORDINARY answer: rontolisp's built-in default for an instance literal
	 * is the structural dump the quote compilers implement (the CLHS
	 * {@code structure-object} licence to answer a constructor form), which is also what
	 * {@code make-load-form-saving-slots} spells, so a type nobody wrote a method for
	 * keeps travelling exactly as before. A method REPLACES that default -- see
	 * {@code .kb/make-load-form.md} and {@link LoadFormSubstituter}, the only caller.
	 * @param instance the literal instance
	 * @return the creation form and the optional init form, or null when no method
	 * specializes on the instance's type
	 */
	@org.jspecify.annotations.Nullable
	public List<LispVal> makeLoadFormValues(LispInstance instance) {
		if (!hasMakeLoadFormMethodFor(instance)) {
			return null;
		}
		LispVal call = consListOf(List.of(new LispSymbol(LispNames.MAKE_LOAD_FORM), instance));
		this.globalEnv.define(LispNames.MV_SPILL, LispNil.INSTANCE);
		LispVal primary = evalResolved(call);
		List<LispVal> values = new ArrayList<>();
		values.add(primary);
		values.addAll(spilledValues(this.globalEnv.lookup(LispNames.MV_SPILL)));
		this.globalEnv.define(LispNames.MV_SPILL, LispNil.INSTANCE);
		return values;
	}

	/**
	 * Whether some {@code make-load-form} method specializes on the instance's own type
	 * or one of its ancestors (class precedence list for a class, {@code :include} chain
	 * for a struct). An unspecialized (default) method does not count: it would answer
	 * every instance in the program, and the built-in structural dump is the better
	 * answer for the ones nobody wrote a method for.
	 */
	private boolean hasMakeLoadFormMethodFor(LispInstance instance) {
		ClosRegistry.GenericInfo generic = this.closRegistry.findGeneric(LispNames.MAKE_LOAD_FORM);
		if (generic == null) {
			return false;
		}
		String typeName = instance.layout().printName();
		java.util.Set<String> names = new java.util.LinkedHashSet<>();
		names.add(typeKey(typeName));
		ClosRegistry.ClassInfo info = this.closRegistry.findClass(typeName);
		if (info != null) {
			names.add(typeKey(info.name()));
			info.cpl().forEach(c -> names.add(typeKey(c)));
		}
		this.closRegistry.structAncestorNames(typeName).forEach(a -> names.add(typeKey(a)));
		for (ClosRegistry.MethodInfo method : generic.methods().values()) {
			if (method.specializers().isEmpty()) {
				continue;
			}
			ClosRegistry.Specializer first = method.specializers().get(0);
			if ((first.kind() == ClosRegistry.SpecializerKind.CLASS
					|| first.kind() == ClosRegistry.SpecializerKind.TYPE) && first.name() != null
					&& names.contains(typeKey(first.name()))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The package-stripped, upcased spelling a specializer and a type name compare by.
	 */
	private static String typeKey(String name) {
		return memberName(name).toUpperCase(Locale.ROOT);
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

	// Iterative on purpose: a Java-recursive walk adds a frame per cons visited, and this
	// runs on every flet/labels entry -- inside a recursive Lisp function, that overhead
	// compounds once per recursion level and can exhaust the JVM stack well before the
	// interpreter's own recursion would (cl-mustache's recursive renderer hit this).
	// An explicit, heap-backed stack keeps this check's own footprint at O(1) frames.
	private static boolean treeContainsMacrolet(LispVal form) {
		ArrayDeque<LispVal> pending = new ArrayDeque<>();
		pending.push(form);
		while (!pending.isEmpty()) {
			LispVal current = pending.pop();
			if (!(current instanceof LispCons cons)) {
				continue;
			}
			if (cons.car() instanceof LispSymbol sym && LispNames.MACROLET.equals(sym.name())) {
				return true;
			}
			pending.push(cons.car());
			pending.push(cons.cdr());
		}
		return false;
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
		// path so there is no per-let allocation or finally cost). A *package* binding
		// is the resolver's current package swapped for the let's extent (see
		// currentPackageValue); savedPackage is what the finally restores.
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
					if (LispNames.PACKAGE_VAR.equals(bindingName) && savedPackage == null) {
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
					if (LispNames.PACKAGE_VAR.equals(names[i]) && savedPackage == null) {
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
			// Restore on ANY exit: normal return, a non-local exit (BlockReturnSignal),
			// or
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
	 * The runtime value of {@code *package*}: the resolver's current package as the
	 * package keyword {@code find-package} answers. On the interpreter the variable IS
	 * the resolver state -- one cell, read through here, written through by
	 * {@link #assignCurrentPackage} -- so everything that consults "the current package"
	 * (a 1-argument {@code intern}, {@code read}, a macro expansion) and the value the
	 * program reads can never disagree. Common Lisp's {@code *package*} is dynamic: a
	 * defun reads the package current when it is CALLED, which is exactly this, and a
	 * {@code let} of it (rove's {@code run-suite-tests}) is a save/set/restore over the
	 * same cell ({@link #rebindCurrentPackage}). Not thread-scoped, unlike the specials
	 * in {@code DynamicBindings}: the resolver is one per evaluator.
	 */
	private LispVal currentPackageValue() {
		return packageKeyword(this.packageResolver.currentPackageName());
	}

	/**
	 * Assigns {@code *package*} ({@code setq}): the value must designate a registered
	 * package -- a string, a symbol, or the package keyword -- else it signals, like
	 * Common Lisp's type error on a non-package.
	 */
	private void assignCurrentPackage(LispVal value) {
		String found = packageNameOf(value);
		if (found == null) {
			throw new LispEvalException(
					LispNames.PACKAGE_VAR + " must be set to a package designator, got " + value.print());
		}
		this.packageResolver.setCurrentPackage(found);
	}

	/**
	 * The runtime half of a {@code (let ((*package* X)) ...)} rebinding: swaps the
	 * resolver's current package to the bound value for the let's extent -- so a called
	 * function reads the bound package, and a macro-time {@code (intern ...)} homes where
	 * CL would. Returns the saved package name, or null when the value is not a package
	 * designator (the binding then has no effect).
	 */
	@org.jspecify.annotations.Nullable
	private String rebindCurrentPackage(LispVal value) {
		String found = packageNameOf(value);
		if (found == null) {
			return null;
		}
		String saved = this.packageResolver.currentPackageName();
		this.packageResolver.setCurrentPackage(found);
		return saved;
	}

	/** The registered package a runtime designator names, or null. */
	@org.jspecify.annotations.Nullable
	private String packageNameOf(LispVal value) {
		String designator = switch (value) {
			case LispString str -> str.value();
			case LispSymbol sym -> LispSymbol.displayName(sym.name());
			default -> null;
		};
		return designator == null ? null : this.packageResolver.findPackageName(designator);
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
			String n = name.name();
			if (!this.globalSymbolMacros.isEmpty() && env.lookupOrNull(n) == null
					&& this.globalSymbolMacros.containsKey(n)) {
				// define-symbol-macro: assigning the name assigns its expansion PLACE,
				// so the value form is evaluated by the setf machinery, not here.
				value = eval(consList(
						List.of(new LispSymbol(LispNames.SETF), this.globalSymbolMacros.get(n), parts.get(i + 1))),
						env);
				continue;
			}
			value = eval(parts.get(i + 1), env);
			assignVariable(n, value, env);
		}
		return value;
	}

	/**
	 * Stores {@code value} into the variable {@code name}, the way {@code setq} does:
	 * through the active dynamic binding of a special, into the resolver's current
	 * package for {@code *package*}, and lexically otherwise.
	 * @param name the variable name
	 * @param value the value to store
	 * @param env the lexical environment
	 */
	private void assignVariable(String name, LispVal value, Environment env) {
		if (LispNames.PACKAGE_VAR.equals(name)) {
			// *package* IS the resolver's current package (see currentPackageValue):
			// the assignment writes straight through -- into the active let binding
			// when one is in extent (evalLet restores the saved package on exit),
			// else permanently, which is what a top-level (in-package P) resolves to.
			assignCurrentPackage(value);
			return;
		}
		// A special with an active dynamic binding is assigned in that binding
		// (visible to callees within the extent); otherwise env.set walks to the
		// global default (a special never has a lexical binding to shadow).
		if ((!this.specialVars.isEmpty() || this.progvUsed) && this.dynamicBindings.isBound(name)) {
			this.dynamicBindings.setCurrent(name, value);
		}
		else {
			env.set(name, value);
		}
	}

	/**
	 * Evaluates {@code (%schar-set place index char)} -- the indexed string write every
	 * {@code (setf (schar|char|aref|elt ...) ...)} place lowers to.
	 *
	 * <p>
	 * A string LITERAL is the source constant, shared by every evaluation of its form, so
	 * writing into it would rewrite the program text. The compiled backends never do:
	 * their {@code %schar-set-runtime} rebuilds the string and {@code setq}s it back into
	 * the place, which is why that place must be a VARIABLE. This is the interpreter's
	 * half of the same rule, so all four backends agree -- and it carries the same limit,
	 * an alias taken before the write still sees the literal's own content
	 * ({@code .kb/string-write-runtime.md}). Every other string is still written in
	 * place, which is what a {@code make-string} buffer needs.
	 * @param cons the %schar-set call
	 * @param env the lexical environment
	 * @return the character written
	 */
	private LispVal evalScharSet(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 4) {
			throw new LispEvalException(LispNames.SCHAR_SET + " expects a string, an index and a character");
		}
		LispVal target = eval(parts.get(1), env);
		LispVal index = eval(parts.get(2), env);
		LispVal character = eval(parts.get(3), env);
		Consumer<LispString> rebind = parts.get(1) instanceof LispSymbol place
				? rebuilt -> assignVariable(place.name(), rebuilt, env) : null;
		return Environment.scharSet(List.of(target, index, character), rebind);
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
	 * Evaluates the internal {@code %block} return boundary the iteration macros wrap
	 * their expansion in: an implicit {@code (block nil ...)}, so a {@code return} fired
	 * in its LEXICAL scope yields the returned value instead.
	 */
	private LispVal evalBlock(LispCons cons, Environment env) {
		return runBlock(cons.toList(), 1, NIL_BLOCK, env);
	}

	/**
	 * Evaluates a user {@code (block name body...)}: runs the body and yields the value
	 * of a matching {@code (return-from name value)} fired in its lexical scope while
	 * this activation is still running. {@code (block nil ...)} is the same construct the
	 * iteration macros establish implicitly, so a plain {@code return} exits it too.
	 */
	private LispVal evalNamedBlock(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new LispEvalException(LispNames.BLOCK + " expects a block name");
		}
		return runBlock(parts, 2, blockName(parts.get(1)), env);
	}

	/**
	 * Runs a block body in a scope of its own, that scope BEING the block's identity: a
	 * closure built inside the body captures it like any other lexical, which is what
	 * makes {@code (return-from name v)} inside a callback exit this activation rather
	 * than the innermost same-named block that is dynamically active where the callback
	 * runs (a {@code handler-bind} handler runs deep inside the signalling function's
	 * loops). An exit aimed at another block -- or at another activation of this one --
	 * propagates.
	 * @param parts the block form's elements
	 * @param bodyStart the index of the first body form
	 * @param name the block name ({@link #NIL_BLOCK} for the nil block)
	 * @param env the scope the block form is evaluated in
	 * @return the body's value, or the exiting value
	 */
	private LispVal runBlock(List<LispVal> parts, int bodyStart, String name, Environment env) {
		Environment blockEnv = new Environment(env);
		blockEnv.installBlock(name);
		return runBlockIn(parts, bodyStart, blockEnv);
	}

	/**
	 * Runs a block body in a scope ALREADY marked as establishing it -- see
	 * {@link #runBlock}, which creates that scope, and {@code apply}, which reuses a
	 * call's own scope for the block a {@code defun} body is wrapped in.
	 * @param parts the block form's elements
	 * @param bodyStart the index of the first body form
	 * @param blockEnv the scope establishing the block, i.e. its identity
	 * @return the body's value, or the exiting value
	 */
	private LispVal runBlockIn(List<LispVal> parts, int bodyStart, Environment blockEnv) {
		try {
			LispVal result = LispNil.INSTANCE;
			for (int i = bodyStart; i < parts.size(); i++) {
				result = eval(parts.get(i), blockEnv);
			}
			return result;
		}
		catch (BlockReturnSignal signal) {
			if (signal.target() == blockEnv) {
				return signal.value();
			}
			throw signal;
		}
	}

	/**
	 * Evaluates {@code (return-from name [value])}: throws the non-local exit the
	 * matching {@code block} activation catches. {@code (return-from nil v)} names the
	 * nil block, which is what plain {@code return} and the iteration macros use.
	 */
	private LispVal evalReturnFrom(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			throw new LispEvalException(LispNames.RETURN_FROM + " expects (return-from name [value])");
		}
		LispVal value = parts.size() == 3 ? eval(parts.get(2), env) : LispNil.INSTANCE;
		throw blockExit(blockName(parts.get(1)), value, env);
	}

	/**
	 * The exit signal a {@code return}/{@code return-from} raises: the target block is
	 * resolved LEXICALLY, up the scope chain of the exit site, exactly as the compiled
	 * backends resolve it. A name no enclosing scope establishes is an error at the exit
	 * site, and so is an exit whose block already returned -- the scope survives in the
	 * closure, but nothing on the stack answers to it, so the signal reaches the
	 * top-level entry and is reported there.
	 * @param name the block name being exited
	 * @param value the value the block should yield
	 * @param env the scope the exit form is evaluated in
	 * @return the signal to throw
	 */
	private static RuntimeException blockExit(String name, LispVal value, Environment env) {
		Environment target = env.findBlock(name);
		if (target == null) {
			return new LispEvalException(LispNames.RETURN_FROM + ": no enclosing block named " + name);
		}
		return new BlockReturnSignal(target, name, value);
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

	/**
	 * The name of the nil block: the one {@code (block nil ...)} establishes, the
	 * iteration macros establish implicitly through {@code %block}, and a plain
	 * {@code (return v)} exits. Making it an ordinary name is what lets one lexical
	 * lookup serve every block.
	 */
	private static final String NIL_BLOCK = "NIL";

	/**
	 * The block name a designator form denotes; {@code nil} denotes {@link #NIL_BLOCK}.
	 */
	private static String blockName(LispVal designator) {
		if (designator instanceof LispNil) {
			return NIL_BLOCK;
		}
		if (designator instanceof LispSymbol sym && !sym.isKeyword()) {
			return sym.name();
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
	 * {@code return}/{@code return-from} non-local exit ({@link BlockReturnSignal})
	 * passes through uncaught.
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
			// In restart mode the clause types also go on the DYNAMIC handler stack, so
			// %run-handlers stops at this handler-case instead of running an enclosing
			// handler-bind's handler for a condition this form is nearer to.
			LispVal protectedForm = LispMacroExpander.handlerCaseProtectedForm(parts.get(1), clauseTypes,
					this.closRegistry, this.restartRuntimeLoaded);
			try {
				value = eval(protectedForm, env);
			}
			finally {
				frames.removeLast();
			}
		}
		catch (LispEvalException e) {
			LispVal condition = e.condition() != null ? e.condition() : synthesizeCondition(e);
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
	 * Builds the condition instance a {@code handler-case} / {@code handler-bind} landing
	 * synthesizes for an error that was signaled without a condition object (a plain
	 * {@code %error}, or a runtime failure inside a built-in). The class is the one the
	 * failure NAMED at its throw site ({@link LispEvalException#ofClass}) -- so
	 * {@code (car 1)} is caught by a {@code type-error} clause -- and
	 * {@code simple-error} only when nothing named one, which is what a plain
	 * {@code (error "text")} is.
	 */
	private LispVal synthesizeCondition(LispEvalException e) {
		String message = e.getMessage();
		LispVal messageVal = message == null ? LispNil.INSTANCE : new LispString(message);
		String className = e.conditionClassName();
		if (className != null && this.closRegistry.newReportingCondition(className, messageVal) instanceof LispVal c) {
			return c;
		}
		LispLayout layout = java.util.Objects
			.requireNonNull(this.closRegistry.findLayoutByTag(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-ERROR"));
		return new LispInstance(layout, new LispVal[] { messageVal, LispNil.INSTANCE });
	}

	/**
	 * Evaluates the internal {@code (%hb-guard body)} landing pad the
	 * {@code handler-bind} expansion wraps its body in. On this backend the built-in seam
	 * in {@link #apply} already runs handlers at the signal point, so the pad only
	 * catches what never crossed that seam (an undefined function, an internal
	 * {@code %error} form evaluated directly); the identity mark keeps the two from both
	 * firing for one condition.
	 */
	private LispVal evalHbGuard(LispCons cons, Environment env) {
		LispVal body = ((LispCons) cons.cdr()).car();
		try {
			return eval(body, env);
		}
		catch (LispEvalException e) {
			throw withHandlerBindHandlersRun(e);
		}
	}

	/**
	 * Runs the {@code handler-bind} cluster stack for the condition an escaping error
	 * carries (synthesizing the {@code simple-error} of a plain error first), unless
	 * {@code %run-handlers} already completed a walk for the IDENTICAL instance (the
	 * {@code %handlers-ran%} mark it sets at the end of a walk -- the signal hook and
	 * this seam never both fire for one condition). Answers the exception to rethrow: the
	 * original when it already carried the instance, otherwise a replacement carrying it,
	 * so an outer {@code handler-case} dispatches on the same instance the handlers saw.
	 * Runs through the same {@code %run-handlers} defun the signal hook calls, so the
	 * CLHS cluster rebinding applies; a handler that transfers control (a restart, a
	 * {@code return-from}) throws its own signal out of here instead.
	 */
	private LispEvalException withHandlerBindHandlersRun(LispEvalException e) {
		if (!this.restartRuntimeLoaded) {
			return e;
		}
		LispVal condition = e.condition() != null ? e.condition() : synthesizeCondition(e);
		if (condition != handlersRanMark()) {
			LispVal fn = this.globalEnv.lookupFunctionOrNull(LispNames.RUN_HANDLERS_INTERNAL);
			if (fn != null) {
				apply(fn, List.of(condition), this.globalEnv);
			}
		}
		if (e.condition() == condition) {
			return e;
		}
		String message = e.getMessage();
		LispEvalException typed = new LispEvalException(message == null ? "" : message, condition);
		typed.initCause(e);
		return typed;
	}

	/**
	 * The current {@code %handlers-ran%} mark, read the way a symbol reference reads a
	 * special (the active dynamic binding first, else the global default), or null when
	 * the restart runtime has not defined it.
	 */
	private @Nullable LispVal handlersRanMark() {
		String name = LispNames.HANDLERS_RAN_VAR;
		if ((!this.specialVars.isEmpty() || this.progvUsed) && this.dynamicBindings.isBound(name)) {
			return this.dynamicBindings.get(name);
		}
		return this.globalEnv.lookupOrNull(name);
	}

	/**
	 * The condition class a raw Java failure inside a built-in is signaled as. The rule
	 * is the one the JVM backend emits at its handler landing pad -- a cast failure and
	 * an out-of-range index are {@code type-error} (CLHS says so for {@code aref}), a
	 * zero divisor is {@code division-by-zero} and any other arithmetic failure is its
	 * parent {@code arithmetic-error} -- so a program catching {@code (car 1)} as a
	 * {@code type-error} behaves the same interpreted and compiled. The two must be
	 * changed together; they are pinned by the same-named cases in
	 * {@code LispEvaluatorTest} and {@code JvmLispCompilerTest}.
	 */
	private static String rawFailureConditionClass(RuntimeException raw) {
		if (!(raw instanceof ArithmeticException)) {
			return ClosRegistry.TYPE_ERROR_CLASS_NAME;
		}
		String message = raw.getMessage();
		return message != null && message.contains(ClosRegistry.DIVISION_BY_ZERO_MESSAGE_TOKEN)
				? ClosRegistry.DIVISION_BY_ZERO_CLASS_NAME : ClosRegistry.ARITHMETIC_ERROR_CLASS_NAME;
	}

	/**
	 * The message a raw Java failure inside a built-in is wrapped with: the exception's
	 * own message when it is self-describing (contains a letter, e.g. {@code "aref:
	 * index out of bounds"}), else the built-in's name prefixes the bare payload
	 * ({@code "make-array: -1"} for a {@code NegativeArraySizeException}). A CAST failure
	 * is the exception: its host text names Java classes, so it reports
	 * {@link ClosRegistry#TYPE_ERROR_MESSAGE} -- the same text the compiled backends
	 * substitute.
	 */
	private static String builtinFailureMessage(String name, RuntimeException raw) {
		if (raw instanceof ClassCastException) {
			// The host's text for a cast failure names Java classes; the compiled
			// backends replace it with the same constant at their landing pad.
			return ClosRegistry.TYPE_ERROR_MESSAGE;
		}
		String message = raw.getMessage();
		String prefix = name.toLowerCase(java.util.Locale.ROOT);
		if (message == null || message.isBlank()) {
			return prefix + " signalled " + raw.getClass().getSimpleName();
		}
		return message.chars().anyMatch(Character::isLetter) ? message : prefix + ": " + message;
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
	 * ({@link BlockReturnSignal}). A cleanup form that itself signals replaces the
	 * pending unwind (CL semantics: the newer exit wins), which is exactly what a Java
	 * {@code finally} does.
	 *
	 * <p>
	 * The cleanups run for effect: their values are discarded, and so is whatever they
	 * left on the {@code %mv-spill} channel -- the whole form answers the protected
	 * form's values, ALL of them (see {@link #runUnwindCleanups}).
	 */
	private LispVal evalUnwindProtect(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new LispEvalException(LispNames.UNWIND_PROTECT + " expects a protected form");
		}
		LispVal result;
		try {
			result = eval(parts.get(1), env);
		}
		catch (LispExitSignal exit) {
			// uiop:quit is the HOST's exit on every backend -- System.exit on the JVM,
			// proc_exit / wasi:cli/exit on the two wasm ones -- and those end the process
			// where they stand. Running a cleanup here would make the interpreter the one
			// backend where something still happens after a quit.
			throw exit;
		}
		catch (RuntimeException | Error ex) {
			runUnwindCleanups(parts, env);
			throw ex;
		}
		runUnwindCleanups(parts, env);
		return result;
	}

	/**
	 * Runs the cleanup forms of an {@code unwind-protect} with the {@code %mv-spill}
	 * channel ({@link LispNames#MV_SPILL}) saved across them: a cleanup's values are
	 * discarded, so the SECONDARY values the protected form published must survive it --
	 * {@code (unwind-protect (values 1 2 3) (release))} answers 1, 2 and 3 however many
	 * values {@code release} returns. Applies to the unwind path too, where the values of
	 * a {@code return-from} in the protected form are already in flight.
	 * @param parts the {@code unwind-protect} form's parts (the cleanups start at index
	 * 2)
	 * @param env the evaluation environment
	 */
	private void runUnwindCleanups(List<LispVal> parts, Environment env) {
		if (parts.size() < 3) {
			return;
		}
		LispVal spill = this.globalEnv.lookupOrNull(LispNames.MV_SPILL);
		for (int i = 2; i < parts.size(); i++) {
			eval(parts.get(i), env);
		}
		if (spill != null) {
			this.globalEnv.define(LispNames.MV_SPILL, spill);
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

	/**
	 * Evaluates {@code (coerce value 'list|'string|'vector)} through a native converter
	 * when the value's representation is one this arm can answer for, and through the
	 * shared {@code expandCoerce} lowering otherwise.
	 *
	 * <p>
	 * This is the declining-primitive shape ({@code .kb/binary-sequence-io.md}): the fast
	 * arm answers or DECLINES, and a decline runs exactly the expansion that ran before,
	 * over the value it has already evaluated (re-quoted, so the value form is evaluated
	 * once either way). Nothing about the operator's contract moves -- an unsupported
	 * result type, a rank-2 array, a non-character element on the way to a string all
	 * fall through and signal what they always signalled.
	 *
	 * <p>
	 * It matters far beyond {@code coerce} itself: every generic sequence expansion wraps
	 * its scan in {@code (coerce seq 'list)} and its result in {@code (coerce res
	 * 'string)} ({@code seqAsListForm} / {@code seqResultDispatchForm}), and the
	 * expansion's own conversion is {@code (map 'list #'identity s)} -- a funcall of
	 * {@code #'identity} per element, INTERPRETED. That was ~0.5 us per character, so
	 * {@code (position #\Space <46-char string>)} spent 23.5 us of its 28.8 us building a
	 * list it then stopped scanning at element 1. See {@code .kb/seq-coerce-runtime.md}.
	 * @param cons the coerce expression
	 * @param env the evaluation environment
	 * @return the coerced value
	 */
	private LispVal evalSequenceCoerce(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		String type = parts.size() == 3 ? coerceSequenceTypeName(parts.get(2)) : null;
		if (type == null) {
			// A COMPUTED result type: the registry rides along so a designator naming a
			// user deftype resolves before the family dispatch reads its head.
			return eval(LispMacroExpander.expandCoerce(cons, true, false, false, this.closRegistry), env);
		}
		LispVal value = eval(parts.get(1), env);
		LispVal fast = coerceSequenceFast(value, type);
		if (fast != null) {
			return fast;
		}
		// Declined. The value is already evaluated, so the expansion runs over a quote
		// of it rather than over the original form.
		LispVal quoted = new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
		LispVal rebuilt = new LispCons(new LispSymbol(LispNames.COERCE),
				new LispCons(quoted, new LispCons(parts.get(2), LispNil.INSTANCE)));
		return eval(LispMacroExpander.expandCoerce((LispCons) rebuilt, true, false, false, this.closRegistry), env);
	}

	// The literal sequence result type a (coerce x 'type) form names, normalized the way
	// expandCoerce normalizes it (the "simple"/"base" aliases collapse, a package
	// qualifier is dropped), or null for anything else -- a computed designator, a
	// compound spec, a float type, an unresolvable deftype name. Only LIST, STRING and
	// VECTOR are answered, because those are the three the native converter serves.
	private static @Nullable String coerceSequenceTypeName(LispVal typeForm) {
		if (!(typeForm instanceof LispCons quote) || !(quote.car() instanceof LispSymbol head)
				|| !LispNames.QUOTE.equals(head.name()) || !(quote.cdr() instanceof LispCons rest)
				|| !(rest.cdr() instanceof LispNil) || !(rest.car() instanceof LispSymbol name)) {
			return null;
		}
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name.name());
		String member = qualified == null ? name.name() : qualified.member();
		return switch (member) {
			case "LIST" -> "LIST";
			case "STRING", "BASE-STRING" -> "STRING";
			// The SIMPLE- spellings are their own arm: "already of the result type"
			// means a SIMPLE string there, so a fill-pointered character vector has to
			// be rebuilt rather than answered (.kb/declarations-type-checks.md).
			case "SIMPLE-STRING", "SIMPLE-BASE-STRING" -> "SIMPLE-STRING";
			case "VECTOR", "SIMPLE-VECTOR" -> "VECTOR";
			default -> null;
		};
	}

	// The native conversion, or null when this arm declines and the shared expansion has
	// to run. Each arm answers exactly what the matching expandCoerce body answers:
	// 'list is (if (listp x) x (if (stringp x) <chars> <aref scan>)), 'string is
	// (if (stringp x) x (map 'string #'identity <as list>)), and 'vector is
	// (if (or (listp x) (stringp x)) <fill> x) -- the identity tail included, which is
	// why the vector arm never declines.
	private static @Nullable LispVal coerceSequenceFast(LispVal value, String requestedType) {
		String type = requestedType;
		if ("SIMPLE-STRING".equals(type)) {
			if (value instanceof LispString str) {
				// A simple string IS of the result type; any other string has to be
				// rebuilt, which this arm declines to the shared expansion's copy-seq
				// rather than keeping a second copy of.
				return str.fillPointer() < 0 && !str.adjustable() && str.displacedTo() == null ? value : null;
			}
			type = "STRING";
		}
		if ("LIST".equals(type)) {
			if (value instanceof LispCons || value instanceof LispNil) {
				return value;
			}
			return sequenceElementsAsList(value);
		}
		if ("STRING".equals(type)) {
			if (value instanceof LispString) {
				return value;
			}
			LispVal elements = (value instanceof LispCons || value instanceof LispNil) ? value
					: sequenceElementsAsList(value);
			if (elements == null) {
				return null;
			}
			StringBuilder sb = new StringBuilder();
			for (LispVal cur = elements; cur instanceof LispCons cell; cur = cell.cdr()) {
				if (!(cell.car() instanceof LispChar c)) {
					// A non-character element is the expansion's error to raise.
					return null;
				}
				sb.appendCodePoint(c.codePoint());
			}
			return new LispString(sb.toString());
		}
		if (!(value instanceof LispCons) && !(value instanceof LispNil) && !(value instanceof LispString)) {
			// (coerce x 'vector) over anything that is neither a list nor a string is
			// the identity, exactly as coerceToVectorBody's else arm is.
			return value;
		}
		LispVal elements = (value instanceof LispString) ? sequenceElementsAsList(value) : value;
		if (elements == null) {
			return null;
		}
		List<LispVal> flat = new ArrayList<>();
		for (LispVal cur = elements; cur instanceof LispCons cell; cur = cell.cdr()) {
			flat.add(cell.car());
		}
		LispVal[] data = flat.toArray(new LispVal[0]);
		return new LispArray(new int[] { data.length }, data);
	}

	// The elements of a rank-1 non-list sequence as a fresh list, or null when the value
	// is not a representation this arm serves (a rank-2 array, a hash table, a number).
	// Element by element this is what (aref v i) over (length v) answers, and for a
	// string what (map 'list #'identity s) answers -- one LispChar per CODE POINT.
	private static @Nullable LispVal sequenceElementsAsList(LispVal value) {
		if (value instanceof LispString str) {
			LispVal result = LispNil.INSTANCE;
			for (int i = str.codePointCount() - 1; i >= 0; i--) {
				result = new LispCons(new LispChar(str.codePointAt(i)), result);
			}
			return result;
		}
		if (value instanceof LispArray arr && arr.dimensions().length == 1) {
			LispVal result = LispNil.INSTANCE;
			for (int i = arr.effectiveLength() - 1; i >= 0; i--) {
				result = new LispCons(arr.readFlat(i), result);
			}
			return result;
		}
		if (value instanceof LispFloatArray packed && packed.rank() == 1) {
			LispVal result = LispNil.INSTANCE;
			for (int i = packed.totalSize() - 1; i >= 0; i--) {
				result = new LispCons(packed.readFlat(i), result);
			}
			return result;
		}
		if (value instanceof LispIntVector vector) {
			LispVal result = LispNil.INSTANCE;
			for (int i = vector.length() - 1; i >= 0; i--) {
				result = new LispCons(new LispInteger(vector.elementAt(i)), result);
			}
			return result;
		}
		return null;
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
	// the predicate is true when its first argument strictly precedes its second).
	// A merge sort, arm for arm the one the compile paths call
	// (LispMacroExpander.sortRuntimeWrapper): the same middle split -- the left half is
	// the longer one on an odd length -- and the same one question per step,
	// (pred right left), answering the left element unless that is true. So the four
	// backends answer one permutation, and it is a stable one (.kb/sort.md). A fresh
	// list is built: the argument's cells are left alone here, unlike the compile
	// paths, which relink them.
	private LispVal sortValues(LispVal list, LispVal predicate) {
		List<LispVal> elems = new ArrayList<>();
		LispVal cursor = list;
		while (cursor instanceof LispCons cell) {
			elems.add(cell.car());
			cursor = cell.cdr();
		}
		LispVal[] values = elems.toArray(new LispVal[0]);
		mergeSortRange(values, new LispVal[values.length], 0, values.length, predicate);
		LispVal result = LispNil.INSTANCE;
		for (int i = values.length - 1; i >= 0; i--) {
			result = new LispCons(values[i], result);
		}
		return result;
	}

	// One merge sort level over values[from, to): sort both halves, then merge them
	// through buffer and copy back.
	private void mergeSortRange(LispVal[] values, LispVal[] buffer, int from, int to, LispVal predicate) {
		int length = to - from;
		if (length < 2) {
			return;
		}
		int middle = from + (length + 1) / 2;
		mergeSortRange(values, buffer, from, middle, predicate);
		mergeSortRange(values, buffer, middle, to, predicate);
		int left = from;
		int right = middle;
		int out = 0;
		while (left < middle && right < to) {
			if (isTruthy(apply(predicate, List.of(values[right], values[left]), this.globalEnv))) {
				buffer[out++] = values[right++];
			}
			else {
				buffer[out++] = values[left++];
			}
		}
		while (left < middle) {
			buffer[out++] = values[left++];
		}
		while (right < to) {
			buffer[out++] = values[right++];
		}
		System.arraycopy(buffer, 0, values, from, length);
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
		return evalArgs(cons, env, 10);
	}

	// The count is a capacity hint (evalCons already walked the form to check
	// properness, so it knows the exact size); the loop still stops at the chain's
	// actual end, so a form rewritten mid-evaluation merely re-grows the list.
	private List<LispVal> evalArgs(LispCons cons, Environment env, int count) {
		List<LispVal> args = new ArrayList<>(Math.max(count, 0));
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
	// The opaque %http-server-* handle: an integer index into RontoHttpServer's
	// handle table (the socket/mutex handle convention).
	private static long requireHttpServerHandle(String fn, List<LispVal> args) {
		if (args.size() != 1 || !(args.get(0) instanceof LispInteger handle)) {
			throw new LispEvalException(fn + " expects a server handle, got "
					+ (args.size() == 1 ? args.get(0).print() : args.size() + " arguments"));
		}
		return handle.value();
	}

	/**
	 * Runs one served request through the SHARED server library: the transport facts go
	 * over as a positional raw tuple, {@code rontolisp::%http-serve-request} builds the
	 * Clack environment, applies the handler and normalizes its Clack response, and the
	 * canonical {@code (status header-alist body-string)} triple comes back. Nothing
	 * about the environment's or the response's shape is decided here -- that is the
	 * point: the JVM backend and the WASI component hand the same tuple to the same
	 * library.
	 * @param handler the Lisp handler (a function value)
	 * @param request the transport facts
	 * @param bufferBody whether the handler asked for the buffered ({@code :raw-body
	 * :buffered}, what Clack needs) request body rather than rontolisp's asynchronous
	 * stream
	 * @return the response to write back
	 */
	private RontoHttpServer.Response invokeHttpHandler(LispVal handler, RontoHttpServer.Request request,
			boolean bufferBody) {
		// :raw-body -- the default is rontolisp's asynchronous stream (one settled chunk;
		// the server has already read the body), and :buffered is the Java-backed
		// bivalent octet stream a Clack application reads with read-line / read-byte /
		// file-position. The buffered form is a stream-table handle, so it is closed
		// here, when the request ends -- upstream handlers never close :raw-body, and a
		// long-running server must not grow its table per request. Only a request that
		// HAS a body pays for it (upstream guards :raw-body with (when raw-body ...)).
		long bodyHandle = -1;
		final LispVal rawBody;
		if (bufferBody) {
			if (request.body().length == 0) {
				rawBody = LispNil.INSTANCE;
			}
			else {
				bodyHandle = this.globalEnv.openHttpBodyStream(request.body());
				rawBody = am.ik.rontolisp.compiler.StreamDesignators.streamValue(bodyHandle, LispLayout.Kinds.BODY);
			}
		}
		else if (request.body().length == 0) {
			LispStream empty = LispStream.open();
			empty.close();
			rawBody = empty;
		}
		else {
			// One settled OCTET chunk: the request's bytes as they came, the shape every
			// HTTP body stream has (a fetched reply's, too), so a handler relaying the
			// body forwards it byte-exact and read-all decodes it.
			rawBody = LispStream.settled(octetVector(request.body()));
		}
		try {
			LispVal env = buildClackEnv(request, rawBody);
			// An async-defun handler returns a future; each request runs on its own
			// virtual thread, so awaiting it here is the natural per-request suspension.
			return normalizeClackResponse(awaitValue(apply(handler, List.of(env), this.globalEnv)));
		}
		finally {
			if (bodyHandle >= 0) {
				this.globalEnv.closeHttpBodyStream(bodyHandle);
			}
		}
	}

	/**
	 * Builds the Clack environment for one served request. The KEY SET and its order are
	 * {@link ClackEnv#FIELDS}; only the per-field value extraction is this backend's, so
	 * an unmapped field fails loudly here -- the same drift guard every backend applies
	 * to the same declaration. Package-private so the mounted script-name split -- which
	 * no transport of THIS backend can produce (only the Servlet war mounts the
	 * application) -- stays testable.
	 */
	LispVal buildClackEnv(RontoHttpServer.Request request, LispVal rawBody) {
		String target = request.target();
		int q = target.indexOf('?');
		String path = q < 0 ? target : target.substring(0, q);
		LispVal query = q < 0 ? LispNil.INSTANCE : new LispString(target.substring(q + 1));
		// The mounted split: the RAW mount prefix comes off the target path BEFORE
		// percent-decoding, and both halves come out decoded; a non-prefix scriptName
		// degrades to the root-mounted split (same arithmetic as RontoHttpClack.buildEnv
		// and %http-make-env -- the three constructions of one declared shape).
		String script = request.scriptName();
		String scriptName = "";
		String pathInfo = path;
		if (!script.isEmpty() && path.startsWith(script)) {
			scriptName = RontoHttpClack.percentDecode(script);
			pathInfo = path.substring(script.length());
		}
		// The header table: lowercased names, repeated headers joined with ", " in wire
		// order (the Clack handler-backend rule), and never nil -- lack-request gethashes
		// it unguarded.
		LispHashTable headers = new LispHashTable();
		for (RontoHttpServer.Header header : request.headers()) {
			LispString name = new LispString(header.name().toLowerCase(Locale.ROOT));
			LispVal seen = headers.get(name, LispNil.INSTANCE);
			headers.put(name, new LispString(
					seen instanceof LispString prev ? prev.value() + ", " + header.value() : header.value()));
		}
		String host = headerValue(headers, "host");
		String contentLength = headerValue(headers, "content-length");
		String serverName = request.localName();
		long serverPort = request.localPort();
		if (host != null) {
			int colon = host.lastIndexOf(':');
			String tail = colon < 0 ? "" : host.substring(colon + 1);
			if (colon >= 0 && !tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
				serverName = host.substring(0, colon);
				serverPort = Long.parseLong(tail);
			}
			else {
				serverName = host;
			}
		}
		List<LispVal> entries = new ArrayList<>(ClackEnv.FIELDS.size() * 2);
		for (String field : ClackEnv.FIELDS) {
			entries.add(new LispSymbol(field));
			entries.add(switch (field) {
				case ClackEnv.REQUEST_METHOD -> new LispSymbol(":" + request.method().toUpperCase(Locale.ROOT));
				case ClackEnv.SCRIPT_NAME -> new LispString(scriptName);
				case ClackEnv.PATH_INFO -> new LispString(RontoHttpClack.percentDecode(pathInfo));
				case ClackEnv.QUERY_STRING -> query;
				// "" is the transport's "unknown" (RontoHttpServer.Request carries no
				// nulls -- the travelling package has no @Nullable to spell one with).
				case ClackEnv.SERVER_NAME -> new LispString(serverName.isEmpty() ? "localhost" : serverName);
				case ClackEnv.SERVER_PORT -> new LispInteger(serverPort == 0 ? 80 : serverPort);
				case ClackEnv.SERVER_PROTOCOL -> new LispSymbol(":" + request.protocol().toUpperCase(Locale.ROOT));
				case ClackEnv.REQUEST_URI -> new LispString(target);
				case ClackEnv.URL_SCHEME -> new LispString(request.scheme());
				case ClackEnv.REMOTE_ADDR ->
					request.remoteAddr().isEmpty() ? LispNil.INSTANCE : new LispString(request.remoteAddr());
				case ClackEnv.REMOTE_PORT ->
					request.remotePort() == 0 ? LispNil.INSTANCE : new LispInteger(request.remotePort());
				case ClackEnv.HEADERS -> headers;
				case ClackEnv.CONTENT_TYPE -> {
					String value = headerValue(headers, "content-type");
					yield value == null ? LispNil.INSTANCE : new LispString(value);
				}
				case ClackEnv.CONTENT_LENGTH -> parseContentLength(contentLength);
				case ClackEnv.RAW_BODY -> rawBody;
				default -> throw new LispEvalException(
						LispNames.HTTP_HANDLER + " has no extraction for environment field " + field);
			});
		}
		return plist(entries.toArray(new LispVal[0]));
	}

	private static @Nullable String headerValue(LispHashTable headers, String name) {
		return headers.get(new LispString(name), LispNil.INSTANCE) instanceof LispString value ? value.value() : null;
	}

	private static LispVal parseContentLength(@Nullable String value) {
		if (value == null) {
			return LispNil.INSTANCE;
		}
		int end = 0;
		while (end < value.length() && Character.isDigit(value.charAt(end))) {
			end++;
		}
		return end == 0 ? LispNil.INSTANCE : new LispInteger(Long.parseLong(value.substring(0, end)));
	}

	/**
	 * Turns the Clack response a handler returned -- {@code (status headers)},
	 * {@code (status headers body)} or the delayed {@code (lambda (responder) ...)} form
	 * -- into the response this server writes.
	 */
	private RontoHttpServer.Response normalizeClackResponse(LispVal response) {
		if (!(response instanceof LispCons res)) {
			// Clack's DELAYED response: call it with a responder that captures the real
			// response. The streaming WRITER form -- where the responder must answer a
			// writer closure -- is refused by the closure it gets back.
			LispVal[] captured = new LispVal[1];
			LispVal responder = new LispFunction(LispNames.HTTP_HANDLER, args -> {
				captured[0] = args.isEmpty() ? LispNil.INSTANCE : args.get(0);
				return new LispFunction(LispNames.HTTP_HANDLER, ignored -> {
					throw new LispEvalException(
							LispNames.HTTP_HANDLER + ": the streaming writer response protocol is not supported");
				});
			});
			apply(response, List.of(responder), this.globalEnv);
			if (captured[0] == null) {
				throw new LispEvalException(LispNames.HTTP_HANDLER + ": a delayed response delivered no response");
			}
			return normalizeClackResponse(captured[0]);
		}
		if (!(res.car() instanceof LispInteger status)) {
			throw new LispEvalException(LispNames.HTTP_HANDLER
					+ ": a handler must return (status headers) or (status headers body), got: " + response.print());
		}
		return new RontoHttpServer.Response((int) status.value(), responseHeaders(second(res)),
				responseBody(third(res)));
	}

	// The Clack response headers -- a keyword plist, or (widening, so a fetch result's
	// :headers can be handed straight back) a dotted alist. Every pair becomes its own
	// header line, which is what makes repeated :set-cookie correct by construction; the
	// framing headers are dropped because the transport computes them from the body.
	private static List<RontoHttpServer.Header> responseHeaders(LispVal headers) {
		List<RontoHttpServer.Header> out = new ArrayList<>();
		if (headers instanceof LispCons first && first.car() instanceof LispCons) {
			for (LispVal cursor = headers; cursor instanceof LispCons cons; cursor = cons.cdr()) {
				if (cons.car() instanceof LispCons pair) {
					addResponseHeader(out, pair.car(), pair.cdr());
				}
			}
			return out;
		}
		LispVal cursor = headers;
		while (cursor instanceof LispCons key && key.cdr() instanceof LispCons value) {
			addResponseHeader(out, key.car(), value.car());
			cursor = value.cdr();
		}
		return out;
	}

	private static void addResponseHeader(List<RontoHttpServer.Header> out, LispVal key, LispVal value) {
		String name = switch (key) {
			case LispString str -> str.value();
			case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
			default -> null;
		};
		if (name == null) {
			return;
		}
		name = name.toLowerCase(Locale.ROOT);
		if ("content-length".equals(name) || "transfer-encoding".equals(name)) {
			return;
		}
		out.add(new RontoHttpServer.Header(name,
				value instanceof LispString str ? str.value() : Environment.displayString(value)));
	}

	// The Clack response body, as the OCTETS the transport puts on the wire. A BARE
	// STRING is refused as Clack itself refuses it (lack's finalize-response wraps a
	// string controller result in a list, so a bare string here is a malformed
	// response); a PATHNAME body -- lack/app/file's file-serving form, a distinct value
	// -- falls to the unsupported-type arm until the transport can serve a file.
	private byte[] responseBody(LispVal body) {
		switch (body) {
			case LispNil ignored -> {
				return EMPTY_BODY;
			}
			case LispString ignored -> throw new LispEvalException(LispNames.HTTP_HANDLER
					+ ": a response body must be a list of strings, not a bare string -- wrap it, e.g. (list body)");
			case LispCons parts -> {
				StringBuilder out = new StringBuilder();
				for (LispVal cursor = parts; cursor instanceof LispCons cons; cursor = cons.cdr()) {
					switch (cons.car()) {
						case LispString part -> out.append(part.value());
						// A NIL element contributes the empty string, as upstream
						// renders it: clack-handler-hunchentoot writes every chunk
						// through flex:string-to-octets, which answers #() for NIL.
						// A controller that returns nil reaches lack's
						// finalize-response, which answers the body list (NIL).
						case LispNil ignored -> {
						}
						default -> throw new LispEvalException(
								LispNames.HTTP_HANDLER + ": a list response body must hold strings");
					}
				}
				return out.toString().getBytes(StandardCharsets.UTF_8);
			}
			case LispStream stream -> {
				// A proxied fetch body: drained here (buffered send). Its chunks are
				// OCTET vectors (every HTTP body stream's shape) and go out as they are
				// --
				// what makes the relay byte-exact; a string chunk (a guest make-stream)
				// is UTF-8 encoded.
				java.io.ByteArrayOutputStream drained = new java.io.ByteArrayOutputStream();
				LispVal chunk = awaitValue(LispFuture.of(stream.read()));
				while (!(chunk instanceof LispNil)) {
					switch (chunk) {
						case LispIntVector octets -> drained.writeBytes(octetsBytes(octets));
						case LispString chunkStr ->
							drained.writeBytes(chunkStr.value().getBytes(StandardCharsets.UTF_8));
						default -> throw new LispEvalException(
								LispNames.HTTP_HANDLER + ": a stream response body must hold strings or octets");
					}
					chunk = awaitValue(LispFuture.of(stream.read()));
				}
				return drained.toByteArray();
			}
			default -> {
				// The cold arms (an (unsigned-byte 8) vector today) live once, in the
				// shared library, rather than four times over. %http-body-string hands
				// the octets back UNFLATTENED -- only the transport knows whether it can
				// write bytes -- and this one can: they go out as they are.
				LispVal normalized = apply(resolveFunction(
						PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, HttpServerLibrary.BODY_STRING)),
						List.of(body), this.globalEnv);
				return switch (normalized) {
					case LispIntVector octets -> octetsBytes(octets);
					case LispString str -> str.value().getBytes(StandardCharsets.UTF_8);
					default -> EMPTY_BODY;
				};
			}
		}
	}

	private static final byte[] EMPTY_BODY = new byte[0];

	// Raw bytes -> the (unsigned-byte 8) vector a body stream answers them as.
	private static LispIntVector octetVector(byte[] bytes) {
		long[] data = new long[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			data[i] = bytes[i] & 0xFF;
		}
		return new LispIntVector(8, data);
	}

	// An (unsigned-byte 8) response body -> the raw octets. The elements are already
	// masked to the width, so the narrowing cannot lose anything.
	private static byte[] octetsBytes(LispIntVector octets) {
		byte[] out = new byte[octets.length()];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) octets.elementAt(i);
		}
		return out;
	}

	private static LispVal second(LispCons cons) {
		return cons.cdr() instanceof LispCons rest ? rest.car() : LispNil.INSTANCE;
	}

	private static LispVal third(LispCons cons) {
		return cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispCons rest2 ? rest2.car()
				: LispNil.INSTANCE;
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

	/**
	 * The elements of a lambda body that is nothing but ONE named {@code block} form --
	 * what {@code evalDefun} and {@code expandDefmethod} wrap every function body in --
	 * or {@code null} for any other body. See the call site in {@link #apply}.
	 * @param body the lambda's body forms
	 * @return the block form's elements, or null
	 */
	private static @Nullable List<LispVal> soleBlockForm(List<LispVal> body) {
		if (body.size() != 1 || !(body.get(0) instanceof LispCons form)
				|| !(form.car() instanceof LispSymbol head && LispNames.BLOCK.equals(head.name()))) {
			return null;
		}
		List<LispVal> parts = form.toList();
		return parts.size() >= 2 ? parts : null;
	}

	private LispVal apply(LispVal function, List<LispVal> args, Environment env) {
		if (function instanceof LispSymbol sym) {
			// A symbol is a function designator naming its global function (CL-style).
			function = resolveFunction(sym.name());
		}
		if (function instanceof LispFunction builtIn) {
			// The signal-point seam: an error a BUILT-IN raises runs the handler-bind
			// cluster stack HERE, before unwinding (restarts established below the
			// handler-bind are still active, unwind-protect cleanups have not run --
			// the CL order). A raw Java failure (a bad index, a negative dimension, a
			// cast) is wrapped into a LispEvalException first, so handler-case sees it
			// too. Zero cost until an exception escapes, and zero beyond one boolean
			// read while the restart runtime is not loaded (no handler can exist).
			try {
				return builtIn.body().apply(args);
			}
			catch (LispEvalException e) {
				throw withHandlerBindHandlersRun(e);
			}
			catch (IndexOutOfBoundsException | NegativeArraySizeException | ArithmeticException
					| ClassCastException raw) {
				LispEvalException wrapped = LispEvalException.ofClass(rawFailureConditionClass(raw),
						builtinFailureMessage(builtIn.name(), raw));
				wrapped.initCause(raw);
				throw withHandlerBindHandlersRun(wrapped);
			}
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
			// See expandMacroCall: the depth tells a macro expansion whether its call
			// site is a TOP-LEVEL form (whose file's package is still current) or one
			// buried in a function body evaluated long after its file was read.
			this.functionBodyDepth++;
			try {
				List<LispVal> blockForm = soleBlockForm(lambda.body());
				if (blockForm != null) {
					// A defun/defmethod body IS one block form. Its block runs in the
					// call's own scope -- fresh, private to this activation, and covering
					// exactly the block's lexical extent, so it can BE the block's
					// identity: the commonest call still allocates one scope, not two.
					lambdaEnv.installBlock(blockName(blockForm.get(1)));
					return runBlockIn(blockForm, 2, lambdaEnv);
				}
				LispVal result = LispNil.INSTANCE;
				for (LispVal bodyExpr : lambda.body()) {
					result = eval(bodyExpr, lambdaEnv);
				}
				return result;
			}
			finally {
				this.functionBodyDepth--;
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

	/**
	 * The {@code :test} / {@code :test-not} designator of a first-class sequence or alist
	 * call, as ONE predicate: a {@code :test-not} designator's result is INVERTED.
	 * Mirrors {@code LispMacroExpander.TestSpec}, so
	 * {@code (apply #'member ... :test-not f)} decides the same way the compiled call
	 * does.
	 */
	private record RuntimeTest(LispVal fn, boolean negated) {
	}

	private RuntimeTest runtimeTest(List<LispVal> args, int start) {
		LispVal test = optionalKeywordArg(args, start, LispNames.TEST_KEYWORD);
		if (test != null) {
			return new RuntimeTest(test, false);
		}
		LispVal testNot = optionalKeywordArg(args, start, LispNames.TEST_NOT_KEYWORD);
		return testNot != null ? new RuntimeTest(testNot, true) : new RuntimeTest(new LispSymbol(LispNames.EQL), false);
	}

	private boolean testMatches(RuntimeTest test, LispVal a, LispVal b) {
		return test.negated() != isTruthy(apply(test.fn(), List.of(a, b), this.globalEnv));
	}

	// Validates the keyword tail of a sequence/alist call: keyword/value pairs only, and
	// every keyword must be :test, :test-not or :key. Unsupported keywords (:from-end,
	// :start, ...) are rejected loudly rather than silently ignored, mirroring the
	// compile-time check in LispMacroExpander.
	private static void requireTestKeyKeywords(String name, List<LispVal> args, int start) {
		for (int i = start; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol kw) || (!LispNames.TEST_KEYWORD.equals(kw.name())
					&& !LispNames.TEST_NOT_KEYWORD.equals(kw.name()) && !LispNames.KEY_KEYWORD.equals(kw.name()))) {
				throw new LispEvalException(
						name + " expects keyword arguments :test/:test-not/:key, got: " + args.get(i).print());
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
