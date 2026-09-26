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
import am.ik.rontolisp.LispComplex;
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
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.LispThread;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispStream;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispStructLiteral;
import am.ik.rontolisp.StructLiteralFolder;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.SpecialVarCollector;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.UncaughtReport;
import am.ik.rontolisp.compiler.ClackEnv;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.runtime.RontoHttpClack;
import am.ik.rontolisp.runtime.RontoHttpServer;
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

	/**
	 * The one-value built-in behind each multiple-value built-in's publishing function
	 * object, which a call position dispatches to ({@link #evalPrimaryValueCall}).
	 */
	private final Map<LispFunction, LispFunction> primaryValueBuiltins = new java.util.IdentityHashMap<>();

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

	private boolean checkpointLibraryLoaded = false;

	private boolean safetensorsLibraryLoaded = false;

	/**
	 * Whether {@link GeomKernels} is installed over the geom.lisp defuns when the library
	 * loads. On for every program; the one caller that turns it OFF is the test that
	 * compares the two paths element for element, which needs the defuns as the oracle.
	 */
	private boolean geomKernels = true;

	private boolean metalLibraryLoaded = false;

	private boolean sceneLibraryLoaded = false;

	private boolean torchLibraryLoaded = false;

	private boolean tokenizersLibraryLoaded = false;

	private boolean ggufLibraryLoaded = false;

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
	// a printer-control variable is off its default) the loaded %print-object-str /
	// %print-object-leaf pair was
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

	private boolean schemeLibraryLoaded = false;

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
	 * Memo of {@link #settledLambdaTail}, keyed by the tail form's cons identity: the
	 * rewrite is a pure function of the form, so like {@link #builtinMacroExpansions}
	 * nothing invalidates it.
	 */
	private final java.util.IdentityHashMap<LispVal, LispVal> lambdaTailSettlements = new java.util.IdentityHashMap<>();

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
	 * The READ-TIME feature set every source this evaluator reads is read with:
	 * {@link Features#INTERPRETER}, widened by whatever the user declared on the command
	 * line ({@code --feature}, {@link #setDeclaredFeatures}). An ASDF system's own
	 * {@code :rontolisp-features} widens it once more, for that system's component files
	 * only. See {@code .kb/reader-features.md}.
	 */
	private Features features = Features.INTERPRETER;

	/**
	 * What every source this evaluator reads is read against
	 * ({@link #setSourceStandards}).
	 */
	private SourceStandards sourceStandards = SourceStandards.DEFAULT;

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
		// *read-suppress* joins them for the same reason: the suppressed read is always
		// spelled (let ((*read-suppress* t)) (read ...)), which is a dynamic binding or
		// nothing.
		this.specialVars.add(LispNames.READ_SUPPRESS_VAR);
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

	/** The {@code funcall} built-in, which {@code evalCons} recognizes by identity. */
	private @Nullable LispFunction funcallBuiltin;

	/**
	 * The {@code apply} built-in, kept so {@link #evalCons} can recognize an
	 * {@code (apply closure ...)} call and apply the closure in its own frame, as it does
	 * a {@code (funcall closure ...)}.
	 */
	private @Nullable LispFunction applyBuiltin;

	/**
	 * The {@code %async-run} built-in, kept so {@link #evalCons} can run an async body
	 * knowing the async function it belongs to ({@link #runAsync}).
	 */
	private @Nullable LispFunction asyncRunBuiltin;

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
	 * The loader {@code load} reads through -- and what a Scheme entry program's
	 * {@code include} and library files are read through, so both name files alike.
	 * @return the source loader
	 */
	public SourceLoader sourceLoader() {
		return this.sourceLoader;
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
	 * Widens the read-time feature set by the names the USER declared ({@code --feature}
	 * on the command line), and seeds the run-time {@code *features*} list with the same
	 * names so a {@code (member :F *features*)} and the {@code #+F} beside it cannot
	 * disagree.
	 * <p>
	 * It reaches the entry program, every file it {@code load}s and every ASDF component
	 * loaded under it -- the source the USER brought. It deliberately does NOT reach the
	 * sources rontolisp itself ships (the prelude, the Lisp-source libraries, the
	 * {@code BuiltinSystems} shims): those are read with the backend's own constant,
	 * because a claim the user makes about a third-party library must not rewrite our own
	 * conditionals underneath it.
	 * @param names the declared feature names, without the leading colon
	 */
	public void setDeclaredFeatures(List<String> names) {
		if (names.isEmpty()) {
			return;
		}
		this.features = Features.INTERPRETER.with(names);
		this.globalEnv.define(LispNames.FEATURES_VAR, Environment.featureKeywordList(this.features.names()));
	}

	/**
	 * Sets the standard each language's source is read against
	 * ({@code --scheme-standard}): every file this evaluator {@code load}s, and the
	 * run-time table behind Scheme's {@code eval}. Set before the program runs.
	 * @param standards the standards
	 */
	public void setSourceStandards(SourceStandards standards) {
		this.sourceStandards = standards;
	}

	/**
	 * The standards in force, for a caller that reads a source on this evaluator's behalf
	 * (the CLI reads the entry program itself).
	 * @return the standards
	 */
	public SourceStandards sourceStandards() {
		return this.sourceStandards;
	}

	/**
	 * The read-time feature set in force, for a caller that reads a source on this
	 * evaluator's behalf (the CLI reads the entry program itself).
	 * @return the feature set
	 */
	public Features features() {
		return this.features;
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
	 * Reads one REPL line through the shared standard input reader -- the same
	 * {@code BufferedReader} the read family uses, so a piped session's look-ahead lives
	 * in exactly one place and a {@code (read)} inside the session sees what was typed
	 * next.
	 * @return the line without its terminator, or {@code null} at end of input
	 * @throws java.io.IOException if the read fails
	 */
	public @Nullable String readReplLine() throws java.io.IOException {
		return this.globalEnv.readReplLine();
	}

	/**
	 * Flushes every output stream the program left open, as a process's end does on the
	 * other backends (the wasm ones write through; the JVM backend flushes its stream
	 * table on the way out). The caller that owns the program's end calls it -- the CLI
	 * once the entry file has run, however it stopped -- because an embedded evaluator
	 * cannot tell its last form from the next.
	 */
	public void flushOpenStreams() {
		this.globalEnv.flushOpenStreams();
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
		this.globalEnv.setDefaultError(this::currentErrorOutput);
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
		// *read-suppress* for the runtime readers: the built-in holds the global
		// environment, so the dynamic binding a (let ((*read-suppress* t)) ...) makes is
		// visible only from here.
		this.globalEnv.setReadSuppressQuery(() -> {
			LispVal suppress = currentSpecialValue(LispNames.READ_SUPPRESS_VAR);
			return suppress != null && !(suppress instanceof LispNil);
		});
		// *features* for the runtime readers, the same way and for the same reason: CL's
		// #+/#- test the LIVE list, so a (push :x *features*) -- or the
		// (let ((*features* '(:x))) ...) a test writes -- decides the guards of every
		// read that follows it. The read-time set cannot answer for that; it was fixed
		// when the frontend read this source.
		this.globalEnv.setReadFeaturesQuery(this::currentReadFeatures);
		this.globalEnv.defineFunction(LispNames.EVAL, new LispFunction(LispNames.EVAL, args -> {
			if (args.size() != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.EVAL + " expects 1 argument, got " + args.size());
			}
			// (eval '(defpackage ...)) makes the form top-level, but it runs at run
			// time under the caller's handlers: register it the way a nested
			// defpackage does, so its clause errors are catchable conditions.
			if (args.get(0) instanceof LispCons form && form.car() instanceof LispSymbol head
					&& LispNames.DEFPACKAGE.equals(LispSymbol.memberName(head.name()))) {
				return registerRuntimeDefpackage(form);
			}
			return eval(args.get(0));
		}, true));
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MACROEXPAND_1 + " expects 1 or 2 arguments, got " + args.size());
			}
			return expandedWithFlag(args.get(0), macroexpand1(args.get(0)));
		}, true));
		this.globalEnv.defineFunction(LispNames.MACROEXPAND, new LispFunction(LispNames.MACROEXPAND, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MACROEXPAND + " expects 1 or 2 arguments, got " + args.size());
			}
			return expandedWithFlag(args.get(0), macroexpand(args.get(0)));
		}, true));
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MACRO_FUNCTION + " expects 1 or 2 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispSymbol sym) || !isMacroName(sym.name())) {
				return LispNil.INSTANCE;
			}
			String name = sym.name();
			return new LispFunction(LispNames.MACRO_FUNCTION + " " + name, callArgs -> {
				// CLHS 3.2.1: a macro function takes EXACTLY two arguments (the form and
				// the environment) -- not "1 or 2" like macro-function itself, which
				// merely accepts an optional environment for the LOOKUP. SBCL 2.2.9
				// signals a program-error for 0, 1 or 4 arguments here.
				if (callArgs.size() != 2) {
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
							"a macro function expects 2 arguments, got " + callArgs.size());
				}
				return macroexpand1(macroCallForm(name, callArgs.get(0)));
			});
		}));
		this.globalEnv.defineFunction(LispNames.SYMBOL_FUNCTION, new LispFunction(LispNames.SYMBOL_FUNCTION, args -> {
			if (args.size() != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SYMBOL_FUNCTION + " expects 1 argument, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.FDEFINITION + " expects 1 argument, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SUBTYPEP + " expects 2 arguments, got " + args.size());
			}
			return subtypep(args.get(0), args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// subtypep's SECOND value, emitted beside the primary by the multiple-value
		// lowering of a subtypep producer (LispMacroExpander.lowerMvProducer). Its own
		// built-in rather than a flag on the one above: the two are separate reads over
		// the same argument temps on every backend.
		this.globalEnv.defineFunction(LispNames.SUBTYPEP_VALID, new LispFunction(LispNames.SUBTYPEP_VALID, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SUBTYPEP_VALID + " expects 2 arguments, got " + args.size());
			}
			return subtypepValid(args.get(0), args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.OBJ_SET + " expects 3 arguments, got " + args.size());
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
			// classOfTypeName only yields members of BUILTIN_CLASS_NAMES, so the answer
			// is never null.
			return java.util.Objects.requireNonNull(
					this.closRegistry.builtinClassMetaobject(classOfTypeName(v).toUpperCase(java.util.Locale.ROOT)));
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.FIND_CLASS + " expects 1 to 3 arguments, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SLOT_BOUNDP + " expects 2 arguments, got " + args.size());
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
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
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
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SLOT_EXISTS_P + " expects 2 arguments, got " + args.size());
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
				List<LispVal> forwarded = new java.util.ArrayList<>(List.of(args.get(1), args.get(0)));
				// A Gray instance honors :start / :end through the generic's own
				// optionals; anything else in the tail keeps today's leniency.
				forwarded.addAll(grayStreamBounds(args, 2));
				return apply(generic, forwarded, this.globalEnv);
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
		// file-length: a broadcast stream answers for its last component (or 0 with
		// none) through the shared helper; anything else reaches the base built-in.
		LispVal baseFileLength = this.globalEnv.lookupFunction(LispNames.FILE_LENGTH);
		this.globalEnv.defineFunction(LispNames.FILE_LENGTH, new LispFunction(LispNames.FILE_LENGTH, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 0);
			if (!args.isEmpty() && dispatchesToGray(args.get(0))) {
				return applyGrayDispatch(GrayStreamsLibrary.BROADCAST_FILE_LENGTH_DISPATCH, List.of(args.get(0)));
			}
			return apply(baseFileLength, args, this.globalEnv);
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
		// write-line: the Gray dispatch must survive a keyword tail -- the bounds are
		// evaluated, then dropped, the same answer write-string's wrapper gives its
		// own bounds. The stream sits at index 1, or there is none when the second
		// argument is a keyword (or absent); anything else reaches the base built-in,
		// which owns the bounds and the errors.
		LispVal baseWriteLine = this.globalEnv.lookupFunction(LispNames.WRITE_LINE);
		this.globalEnv.defineFunction(LispNames.WRITE_LINE, new LispFunction(LispNames.WRITE_LINE, rawArgs -> {
			List<LispVal> args = resolveStreamArg(rawArgs, 1);
			if (args.size() > 1 && !(args.get(1) instanceof LispSymbol kw && kw.name().startsWith(":"))
					&& dispatchesToGray(args.get(1))) {
				List<LispVal> forwarded = new java.util.ArrayList<>(List.of(args.get(0), args.get(1)));
				forwarded.addAll(grayStreamBounds(args, 2));
				return applyGrayDispatch(GRAY_WRITE_LINE_DISPATCH, forwarded);
			}
			return apply(baseWriteLine, args, this.globalEnv);
		}));
		wrapGrayOutputOperator(LispNames.PRINC, 1, GRAY_PRINC_DISPATCH);
		wrapGrayOutputOperator(LispNames.PRIN1, 1, GRAY_PRIN1_DISPATCH);
		wrapGrayOutputOperator(LispNames.PRINT, 1, GRAY_PRINT_DISPATCH);
		// warn's report goes to the current *error-output*, which may be a Gray instance
		// -- mito silences a statement with (let ((*error-output*
		// (make-broadcast-stream)))
		// ...), and a broadcast stream is one -- so it takes the write-line dispatch like
		// any other write to the stream; the handle-based %warn signalled "not an output
		// stream" there.
		LispVal baseWarn = this.globalEnv.lookupFunction(LispNames.WARN_INTERNAL);
		this.globalEnv.defineFunction(LispNames.WARN_INTERNAL, new LispFunction(LispNames.WARN_INTERNAL, args -> {
			LispVal destination = currentErrorOutput();
			if (args.size() == 1 && destination != null) {
				destination = resolveStreamArg(List.of(args.get(0), destination), 1).get(1);
				if (dispatchesToGray(destination)) {
					LispVal message = args.get(0) instanceof LispString ? args.get(0)
							: new LispString(args.get(0).display());
					applyGrayDispatch(GRAY_WRITE_LINE_DISPATCH, List.of(message, destination));
					return LispNil.INSTANCE;
				}
			}
			return apply(baseWarn, args, this.globalEnv);
		}));
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
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.FILE_WRITE_DATE + " expects 1 argument, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.LIST_DIRECTORY + " expects 1 argument, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.HOST_ARGV + " expects no arguments, got " + args.size());
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
		// uiop:remove-package-local-nickname -- unregisters a GLOBAL nickname (no
		// per-package scoping, like the add above); a non-literal call stays a runtime
		// call only the interpreter serves. The optional scope package only guards the
		// removal: the nickname must point at it. Answers t when a mapping was
		// removed, nil when there was none (or it points elsewhere).
		String removeNicknameName = UiopExports.qualified(LispNames.REMOVE_PACKAGE_LOCAL_NICKNAME);
		this.globalEnv.defineFunction(removeNicknameName, new LispFunction(removeNicknameName, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.REMOVE_PACKAGE_LOCAL_NICKNAME
						+ " expects a nickname and an optional package, got " + args.size());
			}
			String nickname = packageNameDesignator(LispNames.REMOVE_PACKAGE_LOCAL_NICKNAME, args.get(0));
			String scope = null;
			if (args.size() == 2) {
				scope = packageNameDesignator(LispNames.REMOVE_PACKAGE_LOCAL_NICKNAME, args.get(1));
			}
			return this.packageResolver.removeLocalNickname(nickname, scope) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// use-package: a literal top-level call is consumed by the PackageResolver (so it
		// works on every backend); this runtime binding serves the computed calls only
		// the
		// interpreter can run -- and, resolving against the very same resolver, it takes
		// effect for the forms read after it, as it does in Common Lisp.
		this.globalEnv.defineFunction(LispNames.USE_PACKAGE, new LispFunction(LispNames.USE_PACKAGE, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.USE_PACKAGE + " expects 1 or 2 arguments, got " + args.size());
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
		// unuse-package: the inverse, with the same literal/computed split. The ANSI
		// package chapter's own safely-delete-package helper drives it -- it unuses a
		// package from every package that uses it before deleting it -- so the runtime
		// binding carries most of the traffic.
		this.globalEnv.defineFunction(LispNames.UNUSE_PACKAGE, new LispFunction(LispNames.UNUSE_PACKAGE, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.UNUSE_PACKAGE + " expects 1 or 2 arguments, got " + args.size());
			}
			List<String> used = new ArrayList<>();
			if (args.get(0) instanceof LispCons list) {
				for (LispVal element : list.toList()) {
					used.add(packageNameDesignator(LispNames.UNUSE_PACKAGE, element));
				}
			}
			else if (!(args.get(0) instanceof LispNil)) {
				used.add(packageNameDesignator(LispNames.UNUSE_PACKAGE, args.get(0)));
			}
			String target = args.size() == 2 ? packageNameDesignator(LispNames.UNUSE_PACKAGE, args.get(1))
					: this.packageResolver.currentPackageName();
			this.packageResolver.unusePackage(used, target);
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
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
							name + " expects 1 or 2 arguments, got " + args.size());
				}
				String target = args.size() == 2 ? packageNameDesignator(name, args.get(1))
						: this.packageResolver.currentPackageName();
				// A symbol or a LIST of symbols, like CL; a symbol homed elsewhere must
				// be accessible in the target, and a name conflict signals, both as a
				// catchable package-error.
				try {
					this.packageResolver.exportSymbols(symbolSpellings(name, args.get(0), target), target, export);
				}
				catch (am.ik.rontolisp.RuntimePackageException ex) {
					return signalPackageError(java.util.Objects.requireNonNullElse(ex.getMessage(), name),
							ex.designator());
				}
				return LispTrue.INSTANCE;
			}));
		}
		this.globalEnv.defineFunction(LispNames.SLOT_MAKUNBOUND, new LispFunction(LispNames.SLOT_MAKUNBOUND, args -> {
			if (args.size() != 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SLOT_MAKUNBOUND + " expects 2 arguments, got " + args.size());
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
		// set: store into the GLOBAL variable namespace only, creating the binding
		// when the name is unbound -- the computed-name counterpart of setq, and what
		// a run-time evaluator defines and assigns program globals through. An
		// already-active dynamic binding is deliberately left alone (like the compiled
		// backends, which have no dynamic cell to address by name): use setq to assign
		// the current dynamic binding.
		this.globalEnv.defineFunction(LispNames.SET, new LispFunction(LispNames.SET, args -> {
			if (args.size() != 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SET + " expects 2 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispSymbol sym)) {
				throw new LispEvalException(LispNames.SET + " expects a symbol, got " + args.get(0).print());
			}
			if (sym.isKeyword() || "NIL".equals(sym.name()) || "T".equals(sym.name()) || sym.name().isEmpty()) {
				throw new LispEvalException(LispNames.SET + " cannot set " + args.get(0).print());
			}
			this.globalEnv.define(sym.name(), args.get(1));
			return args.get(1);
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
		// find-symbol never creates: the symbol comes back only when the package makes
		// the name accessible -- a present member (the package's member table, or a
		// definition made under it: a definition IS an interning), an inherited export
		// of a used package, a standard name, a keyword. The compilers fold a literal
		// call against their compile-time view (cl symbols + user defuns).
		this.globalEnv.defineFunction(LispNames.FIND_SYMBOL, new LispFunction(LispNames.FIND_SYMBOL, args -> {
			PackageResolver.Accessible found = findSymbolAccessible(args);
			return found == null ? LispNil.INSTANCE : symbolOfSpelling(found.spelling());
		}));
		// The SECOND value of find-symbol/intern, lowered beside the primary one by a
		// multiple-value consumer: the status half of the very same lookup, so the two
		// answer nil on exactly the same names -- CL's invariant, and the reason this is
		// one function rather than a status flag threaded through the spill.
		this.globalEnv.defineFunction(LispNames.FIND_SYMBOL_STATUS,
				new LispFunction(LispNames.FIND_SYMBOL_STATUS, args -> {
					PackageResolver.Accessible found = findSymbolAccessible(args);
					return found == null ? LispNil.INSTANCE : new LispSymbol(found.status());
				}));
		// intern overrides the package-blind Environment converter: a bare name is
		// interned into the CURRENT package (the resolver's in-package state), so a
		// macro-time (intern (concatenate ...)) under (in-package p) names the same
		// function as a literal defun in that file -- and a name the package did not
		// provide is RECORDED as its member, so find-symbol answers it from then on. The
		// (intern name :keyword) form keeps the Environment behavior.
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
				if (this.packageResolver.findPackageName(designator) == null) {
					// CLHS: a designator naming no package is an error a handler
					// catches -- not the resolver's read-time failure.
					return signalPackageError("No such package: " + designator, designator);
				}
				return symbolOfSpelling(this.packageResolver.internSpellingIn(designator, str.value(), true));
			}
			requireSingleArg(LispNames.INTERN, args);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.INTERN + " expects a string, got " + args.get(0).print());
			}
			return symbolOfSpelling(this.packageResolver.internSpelling(str.value(), true));
		}));
		// shadow / shadowing-import / unintern / package-shadowing-symbols: the
		// member-table operators, over the LIVE registry like intern. The compiled
		// backends serve the same four from prelude defuns over the %runtime-packages%
		// member table (a read/compile-time package is frozen there).
		this.globalEnv.defineFunction(LispNames.SHADOW, new LispFunction(LispNames.SHADOW, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SHADOW + " expects 1 or 2 arguments, got " + args.size());
			}
			List<String> names = new ArrayList<>();
			// A string designator or a LIST of them, like CL.
			if (args.get(0) instanceof LispCons list) {
				for (LispVal element : list.toList()) {
					names.add(packageNameDesignator(LispNames.SHADOW, element));
				}
			}
			else if (!(args.get(0) instanceof LispNil)) {
				names.add(packageNameDesignator(LispNames.SHADOW, args.get(0)));
			}
			String target = args.size() == 2 ? packageDesignator(LispNames.SHADOW, args.get(1))
					: this.packageResolver.currentPackageName();
			this.packageResolver.shadowSymbols(names, packageName(LispNames.SHADOW, target));
			return LispTrue.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.SHADOWING_IMPORT, new LispFunction(LispNames.SHADOWING_IMPORT, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SHADOWING_IMPORT + " expects 1 or 2 arguments, got " + args.size());
			}
			String target = args.size() == 2 ? packageDesignator(LispNames.SHADOWING_IMPORT, args.get(1))
					: this.packageResolver.currentPackageName();
			target = packageName(LispNames.SHADOWING_IMPORT, target);
			try {
				this.packageResolver
					.shadowingImportSymbols(symbolSpellings(LispNames.SHADOWING_IMPORT, args.get(0), target), target);
			}
			catch (am.ik.rontolisp.RuntimePackageException ex) {
				return signalPackageError(
						java.util.Objects.requireNonNullElse(ex.getMessage(), LispNames.SHADOWING_IMPORT),
						ex.designator());
			}
			return LispTrue.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.UNINTERN, new LispFunction(LispNames.UNINTERN, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.UNINTERN + " expects 1 or 2 arguments, got " + args.size());
			}
			String target = args.size() == 2 ? packageDesignator(LispNames.UNINTERN, args.get(1))
					: this.packageResolver.currentPackageName();
			target = packageName(LispNames.UNINTERN, target);
			String spelling = symbolSpelling(LispNames.UNINTERN, args.get(0), target);
			try {
				return this.packageResolver.uninternSymbol(spelling, target) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			catch (am.ik.rontolisp.RuntimePackageException ex) {
				return signalPackageError(java.util.Objects.requireNonNullElse(ex.getMessage(), LispNames.UNINTERN),
						ex.designator());
			}
		}));
		this.globalEnv.defineFunction(LispNames.PACKAGE_SHADOWING_SYMBOLS,
				new LispFunction(LispNames.PACKAGE_SHADOWING_SYMBOLS, args -> {
					requireSingleArg(LispNames.PACKAGE_SHADOWING_SYMBOLS, args);
					String designator = packageDesignator(LispNames.PACKAGE_SHADOWING_SYMBOLS, args.get(0));
					List<String> spellings = this.packageResolver
						.shadowingSymbols(packageName(LispNames.PACKAGE_SHADOWING_SYMBOLS, designator));
					LispVal out = LispNil.INSTANCE;
					for (int i = spellings.size() - 1; i >= 0; i--) {
						out = new LispCons(symbolOfSpelling(spellings.get(i)), out);
					}
					return out;
				}));
		// %package-iterator-entries: the (symbol status package) triples behind
		// with-package-iterator, over the LIVE registry (the same walk as do-symbols).
		// Overrides the backend-neutral prelude defun, which reads the baked table
		// plus the runtime member table instead (the %do-symbols-list precedent).
		this.globalEnv.defineFunction(LispNames.PACKAGE_ITERATOR_ENTRIES_INTERNAL,
				new LispFunction(LispNames.PACKAGE_ITERATOR_ENTRIES_INTERNAL, args -> {
					if (args.size() != 2) {
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
								LispNames.PACKAGE_ITERATOR_ENTRIES_INTERNAL + " expects 2 arguments, got "
										+ args.size());
					}
					List<LispVal> packages = args.get(0) instanceof LispCons list ? list.toList()
							: args.get(0) instanceof LispNil ? List.of() : List.of(args.get(0));
					java.util.Set<String> types = new java.util.HashSet<>();
					if (args.get(1) instanceof LispCons typeList) {
						for (LispVal type : typeList.toList()) {
							types.add(type.print());
						}
					}
					List<LispVal> entries = new ArrayList<>();
					for (LispVal designator : packages) {
						String name = packageName(LispNames.WITH_PACKAGE_ITERATOR,
								packageDesignator(LispNames.WITH_PACKAGE_ITERATOR, designator));
						LispSymbol pkg = packageKeyword(name);
						for (PackageResolver.Accessible entry : this.packageResolver.accessibleEntries(name)) {
							if (types.contains(entry.status())) {
								entries.add(valueList(List.of(symbolOfSpelling(entry.spelling()),
										new LispSymbol(entry.status()), pkg)));
							}
						}
					}
					return valueList(entries);
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
		// The printer's accessibility question (CLHS 22.1.3.3.1, .kb/pretty-printer.md):
		// whether a package-qualified symbol prints without its qualifier in the
		// current package, over the LIVE registry; the compile paths lower the same
		// call onto a table baked from the final registry. The package spelling and the
		// external flag the prelude caller parsed are what the baked lowering reads;
		// the live answer needs only the symbol.
		this.globalEnv.defineFunction(LispNames.SYMBOL_PRINT_BARE_P_INTERNAL,
				new LispFunction(LispNames.SYMBOL_PRINT_BARE_P_INTERNAL, args -> {
					if (args.size() != 3) {
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
								LispNames.SYMBOL_PRINT_BARE_P_INTERNAL + " expects 3 arguments, got " + args.size());
					}
					return args.get(0) instanceof LispSymbol sym && this.packageResolver.printsBare(sym.name())
							? LispTrue.INSTANCE : LispNil.INSTANCE;
				}));
		// The walk's two leaf primitives: the interpreter loads the prelude lazily, so
		// they simply call the leaf (the compile paths lower them to the leaf or to the
		// bare text, LispMacroExpander.expandPrintCasedLeaf).
		this.globalEnv.defineFunction(LispNames.PRINT_CASED_FOLD_LEAF_INTERNAL,
				new LispFunction(LispNames.PRINT_CASED_FOLD_LEAF_INTERNAL, args -> {
					requireSingleArg(LispNames.PRINT_CASED_FOLD_LEAF_INTERNAL, args);
					return eval(new LispCons(new LispSymbol(LispNames.PRINT_CASE_FOLD_INTERNAL),
							new LispCons(quoteValue(args.get(0)), LispNil.INSTANCE)), this.globalEnv);
				}));
		this.globalEnv.defineFunction(LispNames.PRINT_CASED_RADIXED_LEAF_INTERNAL,
				new LispFunction(LispNames.PRINT_CASED_RADIXED_LEAF_INTERNAL, args -> {
					requireSingleArg(LispNames.PRINT_CASED_RADIXED_LEAF_INTERNAL, args);
					return eval(new LispCons(new LispSymbol(LispNames.PRINT_RADIXED_INTERNAL),
							new LispCons(quoteValue(args.get(0)), LispNil.INSTANCE)), this.globalEnv);
				}));
		// The package half of %print-cased's fast path: under the pristine cl-user no
		// qualified symbol is accessible, so the raw conversion is exact.
		this.globalEnv.defineFunction(LispNames.PRINT_PACKAGE_RAW_P_INTERNAL,
				new LispFunction(LispNames.PRINT_PACKAGE_RAW_P_INTERNAL, args -> {
					if (!args.isEmpty()) {
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
								LispNames.PRINT_PACKAGE_RAW_P_INTERNAL + " expects 0 arguments, got " + args.size());
					}
					return this.packageResolver.currentPackageIsPristineClUser() ? LispTrue.INSTANCE : LispNil.INSTANCE;
				}));
		// list-all-packages / package-use-list / package-used-by-list: the registry
		// queries, over the LIVE registry (so a package created after this program was
		// read counts). A "package" is its upcased canonical name as a keyword, so the
		// listings are lists of those. The compile paths answer the same three from the
		// use table baked in at compile time (LispMacroExpander.expandPackageQuery).
		this.globalEnv.defineFunction(LispNames.LIST_ALL_PACKAGES,
				new LispFunction(LispNames.LIST_ALL_PACKAGES, args -> {
					if (!args.isEmpty()) {
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
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
		// make-package / delete-package / rename-package / package-nicknames: the
		// runtime-tier package API over the LIVE registry (so a package created here is
		// visible to the forms evaluated after it). The compiled backends serve the
		// same operators from prelude defuns over the baked table plus the
		// %runtime-packages% table; failures signal a catchable package-error on all
		// four backends, carrying the offending designator in its package slot.
		this.globalEnv.defineFunction(LispNames.MAKE_PACKAGE, new LispFunction(LispNames.MAKE_PACKAGE, args -> {
			if (args.isEmpty()) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MAKE_PACKAGE + " expects a package name");
			}
			String name = packageDesignator(LispNames.MAKE_PACKAGE, args.get(0));
			List<String> use = new ArrayList<>();
			List<String> nicknames = new ArrayList<>();
			for (int i = 1; i < args.size(); i += 2) {
				if (i + 1 >= args.size() || !(args.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
							LispNames.MAKE_PACKAGE + " expects :use / :nicknames keyword arguments");
				}
				List<String> values = designatorList(LispNames.MAKE_PACKAGE, args.get(i + 1));
				if (LispNames.USE_KEYWORD.equals(key.name())) {
					use.addAll(values);
				}
				else if (LispNames.NICKNAMES_KEYWORD.equals(key.name())) {
					nicknames.addAll(values);
				}
				else {
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
							LispNames.MAKE_PACKAGE + " does not accept " + key.name());
				}
			}
			try {
				return packageKeyword(this.packageResolver.createRuntimePackage(name, use, nicknames));
			}
			catch (am.ik.rontolisp.RuntimePackageException ex) {
				return signalPackageError(java.util.Objects.requireNonNullElse(ex.getMessage(), LispNames.MAKE_PACKAGE),
						ex.designator());
			}
		}));
		this.globalEnv.defineFunction(LispNames.DELETE_PACKAGE, new LispFunction(LispNames.DELETE_PACKAGE, args -> {
			requireSingleArg(LispNames.DELETE_PACKAGE, args);
			String designator = packageDesignator(LispNames.DELETE_PACKAGE, args.get(0));
			try {
				this.packageResolver.deleteRuntimePackage(designator);
				return LispTrue.INSTANCE;
			}
			catch (am.ik.rontolisp.RuntimePackageException ex) {
				return signalPackageError(
						java.util.Objects.requireNonNullElse(ex.getMessage(), LispNames.DELETE_PACKAGE),
						ex.designator());
			}
		}));
		this.globalEnv.defineFunction(LispNames.RENAME_PACKAGE, new LispFunction(LispNames.RENAME_PACKAGE, args -> {
			if (args.size() < 2 || args.size() > 3) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.RENAME_PACKAGE + " expects a package, a new name and optional new nicknames");
			}
			String designator = packageDesignator(LispNames.RENAME_PACKAGE, args.get(0));
			String newName = packageDesignator(LispNames.RENAME_PACKAGE, args.get(1));
			List<String> newNicknames = args.size() == 3 ? designatorList(LispNames.RENAME_PACKAGE, args.get(2))
					: List.of();
			try {
				return packageKeyword(this.packageResolver.renameRuntimePackage(designator, newName, newNicknames));
			}
			catch (am.ik.rontolisp.RuntimePackageException ex) {
				return signalPackageError(
						java.util.Objects.requireNonNullElse(ex.getMessage(), LispNames.RENAME_PACKAGE),
						ex.designator());
			}
		}));
		this.globalEnv.defineFunction(LispNames.PACKAGE_NICKNAMES,
				new LispFunction(LispNames.PACKAGE_NICKNAMES, args -> {
					requireSingleArg(LispNames.PACKAGE_NICKNAMES, args);
					String designator = packageDesignator(LispNames.PACKAGE_NICKNAMES, args.get(0));
					try {
						List<String> nicknames = this.packageResolver.runtimePackageNicknames(designator);
						LispVal out = LispNil.INSTANCE;
						for (int i = nicknames.size() - 1; i >= 0; i--) {
							out = new LispCons(new LispString(nicknames.get(i)), out);
						}
						return out;
					}
					catch (am.ik.rontolisp.RuntimePackageException ex) {
						return signalPackageError(
								java.util.Objects.requireNonNullElse(ex.getMessage(), LispNames.PACKAGE_NICKNAMES),
								ex.designator());
					}
				}));
		// %do-symbols-list: the universe list behind the find-all-symbols /
		// apropos-list prelude defuns (which call it directly) on the interpreter --
		// over the LIVE registry, like evalDoSymbols. Overrides the backend-neutral
		// prelude defun, which reads the compiled backends' injected %baked-packages%
		// table instead (the symbol-package precedent).
		this.globalEnv.defineFunction(LispNames.DO_SYMBOLS_LIST_INTERNAL,
				new LispFunction(LispNames.DO_SYMBOLS_LIST_INTERNAL, args -> {
					if (args.size() != 3) {
						throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
								LispNames.DO_SYMBOLS_LIST_INTERNAL + " expects 3 arguments, got " + args.size());
					}
					String operator = args.get(2) instanceof LispString op ? op.value() : LispNames.DO_SYMBOLS;
					String designator = packageDesignator(operator, args.get(0));
					boolean externalOnly = !(args.get(1) instanceof LispNil);
					if (this.packageResolver.findPackageName(designator) == null) {
						// The same computed-control signal the prelude helper emits,
						// so the message matches on every backend.
						LispVal control = new LispCons(new LispSymbol(LispNames.CONCATENATE), new LispCons(
								quoteValue(new LispString(operator + ": no package named ~A")), LispNil.INSTANCE));
						LispVal form = new LispCons(new LispSymbol(LispNames.ERROR),
								new LispCons(control, new LispCons(quoteValue(args.get(0)), LispNil.INSTANCE)));
						return eval(form, this.globalEnv);
					}
					List<LispSymbol> symbols = externalOnly ? this.packageResolver.externalSymbols(designator)
							: this.packageResolver.accessibleSymbols(designator);
					LispVal out = LispNil.INSTANCE;
					for (int i = symbols.size() - 1; i >= 0; i--) {
						out = new LispCons(symbolOfSpelling(symbols.get(i).name()), out);
					}
					return out;
				}));
		// %package-spelling-normalize: the spelling code uses for an enumerated
		// symbol, over the LIVE registry (a re-export redirect resolved to its home
		// through memberSpelling, anything else unchanged). Overrides the
		// backend-neutral prelude defun, which reads the baked import redirects
		// instead (the symbol-package precedent).
		this.globalEnv.defineFunction(LispNames.PACKAGE_SPELLING_NORMALIZE_INTERNAL,
				new LispFunction(LispNames.PACKAGE_SPELLING_NORMALIZE_INTERNAL, args -> {
					requireSingleArg(LispNames.PACKAGE_SPELLING_NORMALIZE_INTERNAL, args);
					if (args.get(0) instanceof LispTrue || args.get(0) instanceof LispNil) {
						// t and nil are their own canonical spellings.
						return args.get(0);
					}
					if (!(args.get(0) instanceof LispSymbol sym)) {
						throw new LispEvalException(LispNames.PACKAGE_SPELLING_NORMALIZE_INTERNAL
								+ " expects a symbol, got " + args.get(0).print());
					}
					String spelling = sym.name();
					if (spelling.startsWith("#:") || spelling.startsWith(":")) {
						return sym;
					}
					PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(spelling);
					if (qualified == null || qualified.internal()) {
						return sym;
					}
					String home = this.packageResolver.findPackageName(qualified.pkg());
					if (home == null) {
						return sym;
					}
					String normalized = this.packageResolver.memberSpelling(home, qualified.member());
					return normalized == null ? sym : new LispSymbol(normalized);
				}));
		// import: the same split as use-package/export -- a literal top-level call is
		// consumed by the PackageResolver (so it works on every backend), and this
		// runtime binding serves the computed calls only the interpreter can run,
		// resolving against the very same resolver so it takes effect for the forms read
		// after it.
		this.globalEnv.defineFunction(LispNames.IMPORT, new LispFunction(LispNames.IMPORT, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.IMPORT + " expects 1 or 2 arguments, got " + args.size());
			}
			String target = args.size() == 2 ? packageNameDesignator(LispNames.IMPORT, args.get(1))
					: this.packageResolver.currentPackageName();
			// A symbol or a LIST of symbols, like CL; a different symbol of the same
			// name already present in the target is a name conflict (package-error).
			try {
				this.packageResolver.importSymbols(symbolSpellings(LispNames.IMPORT, args.get(0), target), target);
			}
			catch (am.ik.rontolisp.RuntimePackageException ex) {
				return signalPackageError(java.util.Objects.requireNonNullElse(ex.getMessage(), LispNames.IMPORT),
						ex.designator());
			}
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
		this.funcallBuiltin = new LispFunction(LispNames.FUNCALL, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.FUNCALL + " expects at least 1 argument");
			}
			return apply(args.get(0), args.subList(1, args.size()), this.globalEnv);
		}, true);
		this.globalEnv.defineFunction(LispNames.FUNCALL, this.funcallBuiltin);
		// %async-run (the async-defun/async-lambda lowering primitive) lives here rather
		// than in Environment because running the body thunk needs the evaluator's
		// apply. rontolisp:await itself is a special form (evalCons), not a function.
		// evalCons calls runAsync itself when the call is in a lambda's body, so the
		// condition trace can name the async function; this function value is what any
		// other route (funcall, apply) reaches.
		String asyncRunName = LispNames.ASYNC_RUN_QUALIFIED;
		this.asyncRunBuiltin = new LispFunction(asyncRunName, args -> runAsync(args, null));
		this.globalEnv.defineFunction(asyncRunName, this.asyncRunBuiltin);
		// %future-force: the FUNCTION spelling of await's resolve, for synchronous
		// boundaries (the http-reactor transport resolving a future-valued application
		// answer). A function, not a special form, so the lexical await-placement rule
		// does not apply; a non-future passes through, and a future's values come back
		// as await's do (the compile paths compile it to the same resolve).
		String futureForceName = LispNames.FUTURE_FORCE_QUALIFIED;
		this.globalEnv.defineFunction(futureForceName, new LispFunction(futureForceName, args -> {
			if (args.size() != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.FUTURE_FORCE_INTERNAL + " expects 1 argument, got " + args.size());
			}
			return awaitValues(args.get(0));
		}, true));
		// %stream-new: the from-thunk stream constructor every backend shares -- a read
		// thunk, a close thunk and a drained flag is all a stream IS. Here rather than in
		// Environment for the %async-run reason: pulling a chunk means APPLYING a Lisp
		// function. The resolve is the evaluator's too, so LispStream never sees a future
		// -- a thunk that answers one (an async-lambda, a suspending host import on the
		// WASM tiers) settles here, at the read, exactly where the WASM tiers resolve it.
		String streamNewName = LispNames.STREAM_NEW_INTERNAL_QUALIFIED;
		this.globalEnv.defineFunction(streamNewName, new LispFunction(streamNewName, args -> {
			if (args.size() != 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.STREAM_NEW_INTERNAL + " expects 2 arguments, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MAKE_THREAD + " expects 1 or 2 arguments, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.THREADP + " expects 1 argument, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.CURRENT_THREAD + " expects no arguments, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.HTTP_HANDLER + " expects 1 to 4 arguments, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.JSON_PARSE + " expects 1 argument, got " + args.size());
			}
			return applyJsonHelper(JsonLibrary.HELPER_PARSE, List.of(args.get(0)));
		}));
		String jsonStringifyName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.JSON_STRINGIFY);
		this.globalEnv.defineFunction(jsonStringifyName, new LispFunction(jsonStringifyName, args -> {
			if (args.size() != 1) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.JSON_STRINGIFY + " expects 1 argument, got " + args.size());
			}
			return applyJsonHelper(JsonLibrary.HELPER_STRINGIFY, List.of(args.get(0)));
		}));
		this.globalEnv.defineFunction(LispNames.MAPCAR, new LispFunction(LispNames.MAPCAR,
				args -> mapValues(LispNames.MAPCAR, args.get(0), requireMapLists(LispNames.MAPCAR, args), false)));
		this.globalEnv.defineFunction(LispNames.MAPC, new LispFunction(LispNames.MAPC,
				args -> mapForEffect(LispNames.MAPC, args.get(0), requireMapLists(LispNames.MAPC, args), false)));
		// maplist/mapcon/mapl are macro-expanded in call position (evalCons), but a
		// first-class #'maplist has to resolve to something: without these the value path
		// answered "The function MAPLIST is undefined" while both compile backends
		// happily wrapped it.
		this.globalEnv.defineFunction(LispNames.MAPLIST, new LispFunction(LispNames.MAPLIST,
				args -> mapValues(LispNames.MAPLIST, args.get(0), requireMapLists(LispNames.MAPLIST, args), true)));
		this.globalEnv.defineFunction(LispNames.MAPCON, new LispFunction(LispNames.MAPCON,
				args -> mapcanValues(LispNames.MAPCON, args.get(0), requireMapLists(LispNames.MAPCON, args), true)));
		this.globalEnv.defineFunction(LispNames.MAPL, new LispFunction(LispNames.MAPL,
				args -> mapForEffect(LispNames.MAPL, args.get(0), requireMapLists(LispNames.MAPL, args), true)));
		this.globalEnv.defineFunction(LispNames.MAPHASH, new LispFunction(LispNames.MAPHASH, args -> {
			if (args.size() != 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MAPHASH + " expects 2 arguments, got " + args.size());
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
		// count/count-if and the whole remove/substitute family run one shared scan
		// (sequenceScanValues) -- the runtime twin of the expander's, so a first-class
		// #'remove and a (remove ...) call form take the same keywords and decide the
		// same way. They are registered HERE, not in Environment, because the :test and
		// :key designators are applied through the evaluator.
		this.globalEnv.defineFunction(LispNames.COUNT, new LispFunction(LispNames.COUNT,
				args -> sequenceScanValues(LispNames.COUNT, args, SeqScanMode.ITEM, SeqScanAction.COUNT, false)));
		this.globalEnv.defineFunction(LispNames.COUNT_IF,
				new LispFunction(LispNames.COUNT_IF, args -> sequenceScanValues(LispNames.COUNT_IF, args,
						SeqScanMode.PREDICATE, SeqScanAction.COUNT, false)));
		this.globalEnv.defineFunction(LispNames.REMOVE, new LispFunction(LispNames.REMOVE,
				args -> sequenceScanValues(LispNames.REMOVE, args, SeqScanMode.ITEM, SeqScanAction.REMOVE, false)));
		this.globalEnv.defineFunction(LispNames.DELETE, new LispFunction(LispNames.DELETE,
				args -> sequenceScanValues(LispNames.DELETE, args, SeqScanMode.ITEM, SeqScanAction.REMOVE, true)));
		this.globalEnv.defineFunction(LispNames.SUBSTITUTE,
				new LispFunction(LispNames.SUBSTITUTE, args -> sequenceScanValues(LispNames.SUBSTITUTE, args,
						SeqScanMode.ITEM, SeqScanAction.SUBSTITUTE, false)));
		this.globalEnv.defineFunction(LispNames.NSUBSTITUTE,
				new LispFunction(LispNames.NSUBSTITUTE, args -> sequenceScanValues(LispNames.NSUBSTITUTE, args,
						SeqScanMode.ITEM, SeqScanAction.SUBSTITUTE, true)));
		// The five set operations are registered HERE, not in Environment, for the same
		// reason the position and sequence-scan families are: the :test / :test-not /
		// :key designators are applied through the evaluator. setOperationValues is the
		// runtime twin of expandUnion / expandIntersection / expandSetDifference /
		// expandAdjoin / expandSubsetp -- same membership rule, same result order -- so
		// (apply #'set-difference x y :test f) and (set-difference x y :test f) agree.
		this.globalEnv.defineFunction(LispNames.UNION,
				new LispFunction(LispNames.UNION, args -> setOperationValues(LispNames.UNION, SetOp.UNION, args)));
		this.globalEnv.defineFunction(LispNames.INTERSECTION, new LispFunction(LispNames.INTERSECTION,
				args -> setOperationValues(LispNames.INTERSECTION, SetOp.INTERSECTION, args)));
		this.globalEnv.defineFunction(LispNames.SET_DIFFERENCE, new LispFunction(LispNames.SET_DIFFERENCE,
				args -> setOperationValues(LispNames.SET_DIFFERENCE, SetOp.DIFFERENCE, args)));
		this.globalEnv.defineFunction(LispNames.ADJOIN,
				new LispFunction(LispNames.ADJOIN, args -> setOperationValues(LispNames.ADJOIN, SetOp.ADJOIN, args)));
		this.globalEnv.defineFunction(LispNames.SUBSETP, new LispFunction(LispNames.SUBSETP,
				args -> setOperationValues(LispNames.SUBSETP, SetOp.SUBSETP, args)));
		// The -if spellings take :key (and only :key -- the predicate IS the test), so
		// the arity is "at least 2" and the tail is validated by the same rule the
		// expansion applies to a call form.
		this.globalEnv.defineFunction(LispNames.MEMBER_IF, new LispFunction(LispNames.MEMBER_IF, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MEMBER_IF + " expects at least 2 arguments, got " + args.size());
			}
			requireKeyKeyword(LispNames.MEMBER_IF, args, 2);
			return memberIfValues(args.get(0), args.get(1), presentKeyword(args, 2, LispNames.KEY_KEYWORD));
		}));
		this.globalEnv.defineFunction(LispNames.MEMBER, new LispFunction(LispNames.MEMBER, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.MEMBER + " expects at least 2 arguments, got " + args.size());
			}
			// (member item list) compares with eql; (member item list :test fn) applies
			// fn
			// as (funcall fn item element), and :key applies a selector to each element
			// before the test. The default eql designator keeps the historic behavior of
			// the eql-based scan.
			requireTestKeyKeywords(LispNames.MEMBER, args, 2);
			RuntimeTest test = runtimeTest(args, 2);
			LispVal keyFn = presentKeyword(args, 2, LispNames.KEY_KEYWORD);
			LispVal item = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				LispVal elem = (keyFn == null) ? cell.car() : apply(keyFn, List.of(cell.car()), this.globalEnv);
				if (testMatches(test, item, elem)) {
					return cell;
				}
				cur = cell.cdr();
			}
			return Environment.requireListArgument(LispNames.MEMBER, cur);
		}));
		this.globalEnv.defineFunction(LispNames.ASSOC_IF, new LispFunction(LispNames.ASSOC_IF, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ASSOC_IF + " expects at least 2 arguments, got " + args.size());
			}
			requireKeyKeyword(LispNames.ASSOC_IF, args, 2);
			return assocIfValues(args.get(0), args.get(1), presentKeyword(args, 2, LispNames.KEY_KEYWORD));
		}));
		this.globalEnv.defineFunction(LispNames.RASSOC_IF, new LispFunction(LispNames.RASSOC_IF, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.RASSOC_IF + " expects at least 2 arguments, got " + args.size());
			}
			requireKeyKeyword(LispNames.RASSOC_IF, args, 2);
			return rassocIfValues(args.get(0), args.get(1), presentKeyword(args, 2, LispNames.KEY_KEYWORD));
		}));
		this.globalEnv.defineFunction(LispNames.ASSOC, new LispFunction(LispNames.ASSOC, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ASSOC + " expects at least 2 arguments, got " + args.size());
			}
			// (assoc key alist) compares with eql; (assoc key alist :test fn) applies fn
			// as (funcall fn key (car pair)), and :key applies a selector to each pair's
			// car before the test, mirroring member.
			requireTestKeyKeywords(LispNames.ASSOC, args, 2);
			RuntimeTest test = runtimeTest(args, 2);
			LispVal keyFn = presentKeyword(args, 2, LispNames.KEY_KEYWORD);
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
			return Environment.requireListArgument(LispNames.ASSOC, cur);
		}));
		this.globalEnv.defineFunction(LispNames.RASSOC, new LispFunction(LispNames.RASSOC, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.RASSOC + " expects at least 2 arguments, got " + args.size());
			}
			// The mirror of assoc: matches each pair's cdr instead of its car.
			requireTestKeyKeywords(LispNames.RASSOC, args, 2);
			RuntimeTest test = runtimeTest(args, 2);
			LispVal keyFn = presentKeyword(args, 2, LispNames.KEY_KEYWORD);
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
			return Environment.requireListArgument(LispNames.RASSOC, cur);
		}));
		this.globalEnv.defineFunction(LispNames.REMOVE_IF,
				new LispFunction(LispNames.REMOVE_IF, args -> sequenceScanValues(LispNames.REMOVE_IF, args,
						SeqScanMode.PREDICATE, SeqScanAction.REMOVE, false)));
		this.globalEnv.defineFunction(LispNames.REMOVE_IF_NOT,
				new LispFunction(LispNames.REMOVE_IF_NOT, args -> sequenceScanValues(LispNames.REMOVE_IF_NOT, args,
						SeqScanMode.PREDICATE_NOT, SeqScanAction.REMOVE, false)));
		// substitute-if/-if-not and their destructive n- twins: like substitute, but the
		// element is selected by a predicate instead of an eql comparison. Registered
		// here
		// rather than in Environment because they call back into apply (the :key selector
		// and the predicate itself).
		this.globalEnv.defineFunction(LispNames.SUBSTITUTE_IF,
				new LispFunction(LispNames.SUBSTITUTE_IF, args -> sequenceScanValues(LispNames.SUBSTITUTE_IF, args,
						SeqScanMode.PREDICATE, SeqScanAction.SUBSTITUTE, false)));
		this.globalEnv.defineFunction(LispNames.SUBSTITUTE_IF_NOT,
				new LispFunction(LispNames.SUBSTITUTE_IF_NOT, args -> sequenceScanValues(LispNames.SUBSTITUTE_IF_NOT,
						args, SeqScanMode.PREDICATE_NOT, SeqScanAction.SUBSTITUTE, false)));
		this.globalEnv.defineFunction(LispNames.NSUBSTITUTE_IF,
				new LispFunction(LispNames.NSUBSTITUTE_IF, args -> sequenceScanValues(LispNames.NSUBSTITUTE_IF, args,
						SeqScanMode.PREDICATE, SeqScanAction.SUBSTITUTE, true)));
		this.globalEnv.defineFunction(LispNames.NSUBSTITUTE_IF_NOT,
				new LispFunction(LispNames.NSUBSTITUTE_IF_NOT, args -> sequenceScanValues(LispNames.NSUBSTITUTE_IF_NOT,
						args, SeqScanMode.PREDICATE_NOT, SeqScanAction.SUBSTITUTE, true)));
		// delete-if/delete-if-not are the destructive variants of
		// remove-if/remove-if-not:
		// splice out matching cells in place (Common Lisp semantics; use the return
		// value).
		this.globalEnv.defineFunction(LispNames.DELETE_IF,
				new LispFunction(LispNames.DELETE_IF, args -> sequenceScanValues(LispNames.DELETE_IF, args,
						SeqScanMode.PREDICATE, SeqScanAction.REMOVE, true)));
		this.globalEnv.defineFunction(LispNames.DELETE_IF_NOT,
				new LispFunction(LispNames.DELETE_IF_NOT, args -> sequenceScanValues(LispNames.DELETE_IF_NOT, args,
						SeqScanMode.PREDICATE_NOT, SeqScanAction.REMOVE, true)));
		// remove-duplicates/delete-duplicates take the same 17.2.1 keywords with a
		// DIFFERENT meaning for the window, so they have their own runtime twin of the
		// expansion (removeDuplicatesValues) -- registered here, not in Environment,
		// because the :test/:test-not/:key designators are applied through the
		// evaluator. Both spellings share it: delete-duplicates' contract lets the
		// caller use only the RESULT, which is what the expansion relies on too.
		for (String dedupName : List.of(LispNames.REMOVE_DUPLICATES, LispNames.DELETE_DUPLICATES)) {
			this.globalEnv.defineFunction(dedupName,
					new LispFunction(dedupName, args -> removeDuplicatesValues(dedupName, args)));
		}
		this.globalEnv.defineFunction(LispNames.MAPCAN, new LispFunction(LispNames.MAPCAN,
				args -> mapcanValues(LispNames.MAPCAN, args.get(0), requireMapLists(LispNames.MAPCAN, args), false)));
		this.globalEnv.defineFunction(LispNames.SORT, new LispFunction(LispNames.SORT, args -> {
			if (args.size() != 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.SORT + " expects 2 arguments, got " + args.size());
			}
			// A string/vector argument sorts as a list of its elements and is written
			// back into its own storage (Common Lisp sequences; .todo/623 keeps a
			// fill-pointered/adjustable argument's fill pointer, adjustable flag and
			// identity, matching every implementation that sorts a vector in place).
			return Environment.seqResultDestructive(args.get(0),
					sortValues(Environment.seqAsList(args.get(0)), args.get(1)));
		}));
		// stable-sort is registered here (not in Environment) so the predicate and :key
		// designators can be applied through the evaluator, like member/assoc. A Java
		// list sort is stable; the result is always a fresh list, matching the shared
		// macro expansion the call position routes through.
		this.globalEnv.defineFunction(LispNames.STABLE_SORT, new LispFunction(LispNames.STABLE_SORT, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.STABLE_SORT + " expects at least 2 arguments, got " + args.size());
			}
			requireKeyKeyword(LispNames.STABLE_SORT, args, 2);
			LispVal keyArg = optionalKeywordArg(args, 2, LispNames.KEY_KEYWORD);
			LispVal keyFn = keyArg instanceof LispNil ? null : keyArg;
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
			// A string/vector argument sorts as a list of its elements and is written
			// back into its own storage, matching the SORT builtin above (.todo/623).
			return Environment.seqResultDestructive(args.get(0), result);
		}));
		this.applyBuiltin = new LispFunction(LispNames.APPLY, args -> {
			if (args.size() < 2) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.APPLY + " expects at least 2 arguments, got " + args.size());
			}
			return applyValues(args);
		}, true);
		this.globalEnv.defineFunction(LispNames.APPLY, this.applyBuiltin);
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.PROVIDE + " expects 1 argument, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.REQUIRE + " expects 1 or 2 arguments, got " + args.size());
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
					// Downcased like ASDF's coerce-name (see LoadInliner): the spelling
					// lives in the source-language seam, beside the extension.
					path = SourceLanguage.fileNameForModule(name);
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ASDF_LOAD_SYSTEM + " expects 1 argument, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.ASDF_TEST_SYSTEM + " expects 1 argument, got " + args.size());
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
		}, true));
		// ql:quickload = auto-download (real Quicklisp dist) + asdf:load-system. It
		// accepts a single system name or a list of names, downloads each (with its
		// dependencies) into the cache, adds the extracted .asd directories to the search
		// path, and then loads through the same asdf machinery. Returns the list of
		// loaded
		// system names, like real quickload.
		String quickloadName = PackageRegistry.qualify(LispNames.QL_PKG, LispNames.QUICKLOAD);
		this.globalEnv.defineFunction(quickloadName, new LispFunction(quickloadName, args -> {
			if (args.isEmpty()) {
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.QL_QUICKLOAD + " expects 1 argument, got " + args.size());
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.QL_DIST_INSTALL_DIST + " expects 1 argument, got 0");
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
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						LispNames.QL_UPDATE_DIST + " expects 1 argument, got 0");
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
		installValuePublishingFunctions();
		registerJava();
		registerObjc();
		registerFfi();
	}

	/**
	 * Makes the FUNCTION objects of the multiple-value built-ins the syntactic tier
	 * recognizes in call position -- {@code gethash}, {@code find-symbol},
	 * {@code intern}, {@code subtypep}, {@code read-from-string} and
	 * {@code array-displacement} -- answer their second value too, as the floor family's
	 * does: a {@code funcall}, an {@code apply}, a variable or a {@code mapcar} reaches
	 * the function, which publishes the value the companion built-in the lowering reads
	 * it through computes ({@code LispMacroExpander.lowerMvProducer}). The call position
	 * keeps the one-value built-in ({@link #evalPrimaryValueCall}): a consumer or a
	 * function tail has lowered its producer before the dispatch, so only the function
	 * pays for the second value. Runs last, after {@link #foldStructLiteralsOf} wrapped
	 * {@code read-from-string}.
	 */
	private void installValuePublishingFunctions() {
		LispVal absent = new LispSymbol("%gethash-absent%");
		LispFunction gethash = registeredBuiltin(LispNames.GETHASH);
		installValuePublishing(gethash, new LispFunction(LispNames.GETHASH, args -> {
			if (args.size() != 2 && args.size() != 3) {
				return gethash.body().apply(args);
			}
			LispVal found = gethash.body().apply(List.of(args.get(0), args.get(1), absent));
			boolean present = found != absent;
			this.globalEnv.publishSpill(new LispCons(present ? LispTrue.INSTANCE : LispNil.INSTANCE, LispNil.INSTANCE));
			return present ? found : args.size() == 3 ? args.get(2) : LispNil.INSTANCE;
		}, true));
		publishSecondValue(LispNames.FIND_SYMBOL, LispNames.FIND_SYMBOL_STATUS, Integer.MAX_VALUE);
		publishSecondValue(LispNames.INTERN, LispNames.FIND_SYMBOL_STATUS, Integer.MAX_VALUE, true);
		publishSecondValue(LispNames.SUBTYPEP, LispNames.SUBTYPEP_VALID, Integer.MAX_VALUE);
		// Only the one-argument read-from-string has its stop index (the keyword and
		// optional arguments are not implemented, .kb/read-load-streams.md): with more,
		// the function answers one value, as the call does.
		publishSecondValue(LispNames.READ_FROM_STRING, LispNames.READ_FROM_STRING_END, 1);
		publishSecondValue(LispNames.ARRAY_DISPLACEMENT, LispNames.ARRAY_DISP_OFFSET, Integer.MAX_VALUE);
	}

	/**
	 * Rebinds {@code name} to a function that answers the one-value built-in's value and
	 * publishes {@code secondName}'s answer over the same arguments as the second value
	 * -- while it has at most {@code maxArgs} arguments; one value beyond that.
	 */
	private void publishSecondValue(String name, String secondName, int maxArgs) {
		publishSecondValue(name, secondName, maxArgs, false);
	}

	/**
	 * As {@link #publishSecondValue(String, String, int)}, computing the second value
	 * BEFORE the primary when {@code secondFirst} is set: {@code intern}'s status is the
	 * accessibility the name had before the intern (nil for a fresh name, CL's answer),
	 * and the intern itself records the name.
	 */
	private void publishSecondValue(String name, String secondName, int maxArgs, boolean secondFirst) {
		LispFunction primary = registeredBuiltin(name);
		LispFunction second = registeredBuiltin(secondName);
		installValuePublishing(primary, new LispFunction(name, args -> {
			if (secondFirst) {
				LispVal status = args.size() > maxArgs ? LispNil.INSTANCE : second.body().apply(args);
				LispVal value = primary.body().apply(args);
				this.globalEnv
					.publishSpill(args.size() > maxArgs ? LispNil.INSTANCE : new LispCons(status, LispNil.INSTANCE));
				return value;
			}
			LispVal value = primary.body().apply(args);
			this.globalEnv.publishSpill(args.size() > maxArgs ? LispNil.INSTANCE
					: new LispCons(second.body().apply(args), LispNil.INSTANCE));
			return value;
		}, true));
	}

	/** Binds the publishing function and records the one-value built-in behind it. */
	private void installValuePublishing(LispFunction primary, LispFunction publishing) {
		this.globalEnv.defineFunction(publishing.name(), publishing);
		this.primaryValueBuiltins.put(publishing, primary);
	}

	/** The registered built-in {@code name}. */
	private LispFunction registeredBuiltin(String name) {
		if (!(this.globalEnv.lookupFunctionOrNull(name) instanceof LispFunction builtin)) {
			throw new IllegalStateException(name + " must be registered");
		}
		return builtin;
	}

	/**
	 * A call-position {@code (gethash ...)}/{@code (find-symbol ...)}/... : the one-value
	 * built-in while the name is still bound to its publishing function, else whatever
	 * the name is bound to now (a user generic that shadows it), through the same
	 * {@link #apply} seam and in the same order as an ordinary call. See
	 * {@link #installValuePublishingFunctions}.
	 */
	private LispVal evalPrimaryValueCall(LispCons cons, Environment env, String name) {
		LispVal function = resolveFunction(name);
		List<LispVal> args = evalArgs(cons, env, cons.properLength() - 1);
		LispFunction primary = function instanceof LispFunction bound ? this.primaryValueBuiltins.get(bound) : null;
		return apply(primary != null ? primary : function, args, env);
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
		loadFile(operator, rawPath, systemName, this.features);
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
			// Both the read and the #. question are the source-language seam's, picked
			// by THIS file's extension, so one program may mix languages file by file.
			SourceLanguage language = SourceLanguage.forFile(resolved, null);
			boolean markers = SourceLanguage.usesReadEvalMarkers(source);
			for (LispVal form : language.read(source, features, resolved, this.sourceStandards, this.sourceLoader)) {
				eval(markers ? resolveReadTimeEvalInCode(form) : form);
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
	 * CL's second value of {@code subtypep} -- whether the answer is a decision -- over
	 * the same lattice the primary comes from.
	 */
	private boolean subtypepValid(LispVal subV, LispVal superV) {
		seedMopClassesForTypeSpecifier(subV);
		seedMopClassesForTypeSpecifier(superV);
		return LispMacroExpander.subtypepValid(subV, superV, this.closRegistry);
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
		while (true) {
			switch (specifier) {
				case LispSymbol sym -> this.closRegistry.ensureMopClassesSeededFor(sym.name());
				case LispCons cons -> {
					if (!(cons.car() instanceof LispSymbol head) || !LispNames.QUOTE.equals(plainName(head.name()))) {
						seedMopClassesForTypeSpecifier(cons.car());
						specifier = cons.cdr();
						continue;
					}
				}
				default -> {
				}
			}
			return;
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
			// CLHS glossary: a STRING DESIGNATOR is a character, a symbol or a string,
			// and a package designator is a string designator (or a package). #\G names
			// the package "G", and t/nil are the symbols named "T"/"NIL".
			case LispChar ch -> ch.display();
			case LispTrue ignored -> "T";
			case LispNil ignored -> "NIL";
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
			case LispComplex ignored -> "complex";
			case LispString ignored -> "string";
			case LispChar ignored -> "character";
			case LispTrue ignored -> "boolean";
			case LispNil ignored -> "null";
			case LispSymbol s -> s.isKeyword() ? "keyword" : "symbol";
			case LispCons ignored -> "cons";
			case LispHashTable ignored -> "hash-table";
			case am.ik.rontolisp.LispQuantizedMatrix ignored -> "quantized-matrix";
			// Its own named type on every backend (.kb/objc.md, "The type").
			case LispObjcObject ignored -> LispNames.OBJC_OBJECT_TYPE;
			case LispFunction ignored -> "function";
			default -> "t";
		};
	}

	/**
	 * The built-in class name {@code class-of} answers for a non-instance value: the
	 * {@code %class-designator} view narrowed over ARRAYS, which that view answers
	 * {@code t} for. A string is left to {@code builtinTypeName} (the narrower
	 * {@code string}); every other array is a {@code vector} at rank 1 and an
	 * {@code array} above or below it. The result set is
	 * {@code ClosRegistry.BUILTIN_CLASS_NAMES}, which
	 * {@code LispMacroExpander.expandClassOf} reproduces on the compile paths.
	 */
	private static String classOfTypeName(LispVal v) {
		return switch (v) {
			case LispArray array when array.dimensions().length == 1
					&& array.elementTypeCode() == am.ik.rontolisp.ArrayElementTypes.BIT ->
				"bit-vector";
			case LispArray array -> array.dimensions().length == 1 ? "vector" : "array";
			case LispFloatArray array -> array.rank() == 1 ? "vector" : "array";
			case LispIntVector ignored -> "vector";
			default -> builtinTypeName(v);
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
	 * labels, everything else evaluates in order for effect. A {@code (go tag)} in a
	 * statement's tail is answered by {@link #evalTagbodyStatement} without a throw; one
	 * thrown anywhere else inside (dynamically) resumes at that label; falling off the
	 * end returns nil. The compilers support the lexical subset only: a compiled
	 * {@code go} must target a lexically enclosing tagbody in the same function.
	 */
	private LispVal evalTagbody(LispCons cons, Environment env) {
		List<LispVal> body = cons.cdr() instanceof LispCons items ? items.toList() : List.of();
		TagbodyLabels labels = TagbodyLabels.of(body);
		@org.jspecify.annotations.Nullable
		String[] keys = labels.keys();
		int pc = 0;
		while (pc < keys.length) {
			if (keys[pc] != null) {
				pc++;
				continue;
			}
			try {
				int jump = evalTagbodyStatement(body.get(pc), env, labels);
				pc = jump == NO_JUMP ? pc + 1 : jump + 1;
			}
			catch (GoSignal go) {
				int target = labels.indexOf(go.tag);
				if (target == NO_JUMP) {
					// Not one of ours: an outer tagbody owns the tag.
					throw go;
				}
				pc = target + 1;
			}
		}
		return LispNil.INSTANCE;
	}

	/**
	 * A tagbody's labels: the {@link #goTagKey} of every body item, null for a statement.
	 * A tagbody holds a handful of labels, so a scan beats building a map per entry. Also
	 * the source of the JUMP TOKENS a statement's tail answers with: a {@code (go L)}
	 * that {@link #evalCons}'s loop reaches in the tail of a statement of this tagbody
	 * answers {@link #jump} of L's index instead of throwing, and {@link #jumpIndex}
	 * reads the index back. A token is an integer object private to this activation, told
	 * apart by IDENTITY, so no value a program can compute equals one; it never reaches a
	 * program, since a tail's value is the statement's value and a statement's value goes
	 * nowhere but {@link #evalTagbody}.
	 */
	static final class TagbodyLabels {

		private final @org.jspecify.annotations.Nullable String[] keys;

		private @org.jspecify.annotations.Nullable LispVal @org.jspecify.annotations.Nullable [] jumps;

		private TagbodyLabels(@org.jspecify.annotations.Nullable String[] keys) {
			this.keys = keys;
		}

		/** The labels of a tagbody's body items. */
		static TagbodyLabels of(List<LispVal> body) {
			@org.jspecify.annotations.Nullable
			String[] keys = new String[body.size()];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = goTagKey(body.get(i));
			}
			return new TagbodyLabels(keys);
		}

		@org.jspecify.annotations.Nullable
		String[] keys() {
			return this.keys;
		}

		/**
		 * The body index of the label {@code key} (the last one if repeated), or
		 * {@link #NO_JUMP}.
		 */
		int indexOf(String key) {
			for (int i = this.keys.length - 1; i >= 0; i--) {
				if (key.equals(this.keys[i])) {
					return i;
				}
			}
			return NO_JUMP;
		}

		/**
		 * The token a statement tail answers to resume after the label at {@code index}.
		 */
		LispVal jump(int index) {
			if (this.jumps == null) {
				this.jumps = new LispVal[this.keys.length];
			}
			LispVal token = this.jumps[index];
			if (token == null) {
				token = new LispInteger(index);
				this.jumps[index] = token;
			}
			return token;
		}

		/**
		 * The label index a statement's value asks to resume after, or {@link #NO_JUMP}
		 * for an ordinary value.
		 */
		int jumpIndex(LispVal value) {
			if (this.jumps != null && value instanceof LispInteger) {
				for (int i = 0; i < this.jumps.length; i++) {
					if (this.jumps[i] == value) {
						return i;
					}
				}
			}
			return NO_JUMP;
		}

	}

	/**
	 * What {@link #evalTagbodyStatement} answers when the statement completed normally.
	 */
	static final int NO_JUMP = -1;

	/**
	 * Evaluates one tagbody statement for effect and answers the body index of the label
	 * a {@code go} in its TAIL jumps to, or {@link #NO_JUMP}. The statement runs through
	 * {@link #evalCons}'s loop with the tagbody's labels, so a {@code (go L)} in any tail
	 * context the loop follows -- an {@code if} arm, the last form of a {@code progn}, a
	 * {@code let}, a block or a called function's body, a macro's expansion -- whose tag
	 * is one of {@code labels} is answered, not thrown; the loop never enters another
	 * tagbody, so this is the innermost one either way. Every other {@code go} (not in a
	 * tail, a tag of an outer tagbody, one from a closure) is still a {@code GoSignal}.
	 * @param form the statement
	 * @param env its environment
	 * @param labels the tagbody's labels
	 * @return the label index to resume after, or {@link #NO_JUMP}
	 */
	int evalTagbodyStatement(LispVal form, Environment env, TagbodyLabels labels) {
		if (!(form instanceof LispCons cons)) {
			evalAtom(form, env);
			return NO_JUMP;
		}
		return labels.jumpIndex(evalCons(cons, env, labels));
	}

	/**
	 * The key a {@code go} tag has in a tagbody's label table -- a symbol by its plain
	 * name, an integer by its digits (CLHS 5.3) -- or null when it is neither.
	 */
	@org.jspecify.annotations.Nullable
	private static String goTagKey(@org.jspecify.annotations.Nullable LispVal tag) {
		if (tag instanceof LispSymbol tagSym) {
			return plainName(tagSym.name());
		}
		if (tag instanceof LispInteger tagInt) {
			return String.valueOf(tagInt.value());
		}
		return null;
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
			LispVal value;
			try {
				value = raw.body().apply(args);
			}
			catch (am.ik.rontolisp.reader.LispReadException e) {
				// A runtime read error is a catchable condition: input that ran out
				// mid-datum is end-of-file, a bad token is reader-error (CLHS 23.1).
				// Without this conversion a bad datum handed to read/read-from-string
				// would blow through handler-case as a raw Java exception. The
				// compiled backends' emitted readers signal a catchable simple-error
				// for the same input instead (.kb/read-load-streams.md).
				throw e.isEndOfFile() ? readEndOfFile(args) : readError(String.valueOf(e.getMessage()), args);
			}
			catch (IllegalArgumentException e) {
				throw new LispEvalException(String.valueOf(e.getMessage()));
			}
			catch (LispEvalException e) {
				// The #. resolver's *read-eval*-nil refusal arrives typed as
				// reader-error but streamless; give it the stream like any other
				// runtime-read failure. Anything else passes through untouched.
				if (e.condition() == null && ClosRegistry.READER_ERROR_CLASS_NAME.equals(e.conditionClassName())) {
					throw readError(String.valueOf(e.getMessage()), args);
				}
				throw e;
			}
			try {
				return StructLiteralFolder.fold(value, this.closRegistry);
			}
			catch (IllegalArgumentException e) {
				// A #S(...) the fold refuses (unknown type, bad slot) is a
				// reader-error like any other malformed runtime datum.
				throw readError(String.valueOf(e.getMessage()), args);
			}
		}));
	}

	/**
	 * The {@code reader-error} condition for a malformed runtime-read datum, carrying the
	 * frontend's message and a string-input stream over the offending text (what
	 * {@code stream-error-stream} reads back).
	 * @param message the frontend's message
	 * @param args the {@code read-from-string} call's arguments
	 */
	private LispEvalException readError(String message, List<LispVal> args) {
		return new LispEvalException(message,
				ClosRegistry.newReaderErrorCondition(new LispString(message), readErrorStream(args)));
	}

	/**
	 * The {@code end-of-file} condition for a runtime read that ran out of input,
	 * carrying a string-input stream over the offending text.
	 * @param args the {@code read-from-string} call's arguments
	 */
	private LispEvalException readEndOfFile(List<LispVal> args) {
		return new LispEvalException(ClosRegistry.END_OF_FILE_MESSAGE,
				ClosRegistry.newEndOfFileCondition(readErrorStream(args)));
	}

	/**
	 * A string-input stream value over the text a runtime read failed on -- the
	 * {@code stream} slot of its condition. Built through the
	 * {@code make-string-input-stream} built-in, so the value is exactly what user code
	 * holding such a stream holds.
	 * @param args the {@code read-from-string} call's arguments
	 */
	private LispVal readErrorStream(List<LispVal> args) {
		if (!args.isEmpty() && args.get(0) instanceof LispString text && this.globalEnv
			.lookupFunctionOrNull(LispNames.MAKE_STRING_INPUT_STREAM_INTERNAL) instanceof LispFunction make) {
			return make.body().apply(List.of(text));
		}
		return LispNil.INSTANCE;
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
			// A reader-error (not a plain error): the runtime read's fold gives it
			// the offending stream on the way out.
			throw LispEvalException.ofClass(ClosRegistry.READER_ERROR_CLASS_NAME,
					"cannot read #. while *read-eval* is nil");
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
		return LispTrees.rebuildSpine(form, node -> resolvedReadTimeEvalNode(node, inCode),
				car -> resolveReadTimeEval(car, inCode));
	}

	/**
	 * What a node {@link #resolveReadTimeEval} does not walk into becomes -- an atom
	 * stays, a marker is its value, a quoted datum and a {@code defpackage}'s clauses are
	 * resolved as data -- or {@code null} for an ordinary cell.
	 */
	private @Nullable LispVal resolvedReadTimeEvalNode(LispVal form, boolean inCode) {
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
		return null;
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
	 * The type-mention half of the uiop lazy trigger: a quoted uiop type name in a
	 * {@code typep}/{@code typecase} form is not a function or variable resolution, so
	 * nothing would load the deftype behind it (the compile paths splice it from the
	 * quoted occurrence, but the interpreter only loads functions, variables and the
	 * condition/class set). Loads every library definition the form names -- usually just
	 * the deftype, through the same idempotent {@link #loadUiopDefinition} the call
	 * positions use. Both spellings count: the form may reach here unresolved.
	 */
	private void ensureUiopTypesFor(LispVal form) {
		for (String name : uiopNamesMentioned(form)) {
			loadUiopDefinition(name);
		}
	}

	private static java.util.List<String> uiopNamesMentioned(LispVal form) {
		java.util.List<String> names = new java.util.ArrayList<>();
		collectUiopNames(form, names);
		return names;
	}

	// The spine is a LOOP, not a recursion: this runs on the typecase arm at whatever
	// depth the program has already reached, so a frame per list element would spend
	// the stack a deeply recursive program still needs (cl-mustache's spec suite
	// renders its templates ~800 KiB of stack down). Only NESTING recurses, which a
	// source form bounds the way the reader does.
	private static void collectUiopNames(LispVal form, java.util.List<String> names) {
		for (LispVal val = form;;) {
			switch (val) {
				case LispSymbol sym -> {
					String name = sym.name();
					if (UiopLibrary.definesName(name)) {
						names.add(name);
					}
					else {
						PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
						if (qn != null && UiopExports.isUiopFamily(qn.pkg())) {
							String homePackage = UiopExports.homePackage(qn.member());
							if (homePackage != null) {
								String home = homePackage + ":" + qn.member();
								if (UiopLibrary.definesName(home)) {
									names.add(home);
								}
							}
						}
					}
				}
				case LispCons cons -> {
					collectUiopNames(cons.car(), names);
					val = cons.cdr();
					continue;
				}
				default -> {
				}
			}
			return;
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

	/** Evaluates the {@code checkpoint} library ({@code CheckpointLibrary}) once. */
	private void ensureCheckpointLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.checkpointLibraryLoaded) {
				return;
			}
			this.checkpointLibraryLoaded = true;
			for (LispVal form : CheckpointLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
	}

	/** Evaluates the {@code safetensors} library ({@code SafetensorsLibrary}) once. */
	private void ensureSafetensorsLoaded() {
		synchronized (this.libraryLoadLock) {
			if (this.safetensorsLibraryLoaded) {
				return;
			}
			this.safetensorsLibraryLoaded = true;
			for (LispVal form : SafetensorsLibrary.forms()) {
				eval(form, this.globalEnv);
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
			for (AsdfSystems.LispSystem defined : AsdfSystems.parseAsdSource(asd.source(), asd.path(), this.features,
					this.asdfSystemPackages)) {
				this.asdfSystems.putIfAbsent(defined.name(), defined);
			}
			system = this.asdfSystems.get(name);
			if (system == null) {
				// A NAME/SUB of a :package-inferred-system: the .asd declares no
				// components, so the name is answered from the file it points at.
				AsdfSystems.inferPackageInferredSystems(name, this.asdfSystems, this.asdfSystemPackages,
						this.sourceLoader, this.features);
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
		Features systemFeatures = this.features.with(system.features());
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
		AsdfSystems.LispSystem system = AsdfSystems.parseDefsystem(cons, baseDir == null ? "" : baseDir, this.features);
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
		this.globalEnv.clearSpill();
		LispVal primary = evalResolved(resolved);
		return consumeValues(primary);
	}

	/**
	 * Reads the values a form just produced off the {@code %mv-spill} channel, clearing
	 * it: the primary first, then what the channel holds -- nothing when the form
	 * answered no value at all ({@code LispMacroExpander.MV_ZERO_VALUES}), where the
	 * primary is only the nil {@code values} answers for want of one.
	 * @param primary what the form answered
	 * @return the form's values, primary first (empty for {@code (values)})
	 */
	private List<LispVal> consumeValues(LispVal primary) {
		LispVal spill = this.globalEnv.spill();
		// The values have been consumed: leave no leftovers for the next form's echo.
		this.globalEnv.clearSpill();
		if (spill == LispMacroExpander.MV_ZERO_VALUES) {
			return List.of();
		}
		List<LispVal> values = new ArrayList<>();
		values.add(primary);
		values.addAll(spilledValues(spill));
		return values;
	}

	/**
	 * Captures the evaluator's control state -- the stacks every binding form pushes
	 * before its body and pops in a {@code finally} -- so a caller that survives a
	 * {@link StackOverflowError} can put it back. The overflow does unwind through those
	 * {@code finally} blocks, but the deepest ones run with no stack left and can
	 * overflow again before they restore anything: a special stays bound, a {@code load}
	 * leaves its package current. A caller about to go on evaluating (a REPL prompt)
	 * takes a state before the form and {@link ControlState#restore restores} it after
	 * the overflow.
	 * @return the state now
	 */
	public ControlState controlState() {
		return new ControlState(this.dynamicBindings.depths(), this.handlerCaseTypes.get().size(),
				this.functionBodyDepth, this.packageResolver.packageStackDepth(),
				this.packageResolver.currentPackageName(), this.loadDirStack.size(), this.loadingSystems.size(),
				this.out.muted);
	}

	/**
	 * The control state {@link #controlState} captured. Nothing a program ASSIGNED is in
	 * it: a {@code setq} that ran before the overflow stays, as it would after any error.
	 */
	public final class ControlState {

		private final Map<String, Integer> bindingDepths;

		private final int handlerCaseFrames;

		private final int functionBodyDepth;

		private final int packageStackDepth;

		private final String currentPackage;

		private final int loadDirDepth;

		private final int loadingSystemsDepth;

		private final boolean muted;

		private ControlState(Map<String, Integer> bindingDepths, int handlerCaseFrames, int functionBodyDepth,
				int packageStackDepth, String currentPackage, int loadDirDepth, int loadingSystemsDepth,
				boolean muted) {
			this.bindingDepths = bindingDepths;
			this.handlerCaseFrames = handlerCaseFrames;
			this.functionBodyDepth = functionBodyDepth;
			this.packageStackDepth = packageStackDepth;
			this.currentPackage = currentPackage;
			this.loadDirDepth = loadDirDepth;
			this.loadingSystemsDepth = loadingSystemsDepth;
			this.muted = muted;
		}

		/** Puts the evaluator's control stacks back to this state. */
		public void restore() {
			LispEvaluator evaluator = LispEvaluator.this;
			evaluator.dynamicBindings.truncateTo(this.bindingDepths);
			ArrayDeque<List<LispVal>> frames = evaluator.handlerCaseTypes.get();
			while (frames.size() > this.handlerCaseFrames) {
				frames.removeLast();
			}
			evaluator.functionBodyDepth = this.functionBodyDepth;
			evaluator.packageResolver.restorePackageState(this.packageStackDepth, this.currentPackage);
			while (evaluator.loadDirStack.size() > this.loadDirDepth) {
				evaluator.loadDirStack.removeLast();
			}
			while (evaluator.loadingSystems.size() > this.loadingSystemsDepth) {
				evaluator.loadingSystems.removeLast();
			}
			evaluator.out.muted = this.muted;
		}

	}

	/**
	 * Renders a value the way {@code prin1} would: through the {@code print-object} route
	 * when this evaluation has turned it on (a {@code defmethod print-object}, a
	 * condition in reach, a non-default printer-control variable), else the raw readable
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
				|| this.closRegistry.routesConditionReports() || printControlsInEffect();
		if (!routed) {
			return value.print();
		}
		try {
			return printThrough("(lambda (x) (prin1-to-string x))", value) instanceof LispString rendered
					? rendered.value() : value.print();
		}
		catch (RuntimeException ex) {
			return value.print();
		}
	}

	/**
	 * Calls a printer written as a one-argument lambda on a value the program computed.
	 * The value is handed to the closure as an ARGUMENT, never quoted into a form: a
	 * top-level form is walked by the package resolver first, and that walk never ends on
	 * a cyclic value.
	 * @param printer the source of a {@code (lambda (x) ...)} answering the text
	 * @param value the value to print
	 * @return what the printer answered
	 */
	LispVal printThrough(String printer, LispVal value) {
		LispVal closure = eval(SourceLanguage.COMMON_LISP.read(printer, Features.INTERPRETER, null).get(0));
		return applyGlobal(closure, List.of(value));
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
		if (expr instanceof LispCons cons) {
			return evalCons(cons, env, null);
		}
		return evalAtom(expr, env);
	}

	/**
	 * Evaluates a form that is not a cons: a variable reference or a self-evaluating
	 * object. The tail of {@link #evalCons}'s loop ends here when the form that replaced
	 * the current one is an atom.
	 * @param expr the atom
	 * @param env the lexical environment
	 * @return the value
	 */
	private LispVal evalAtom(LispVal expr, Environment env) {
		return switch (expr) {
			case LispSymbol sym -> singleValue(evalSymbolRef(sym, env));
			// An array literal is a CONSTRUCTOR, not a constant: each evaluation answers
			// a fresh, independently mutable array, which is what both compile backends
			// already emit at the site (LiteralArrays).
			case LispArray a -> singleValue(LiteralArrays.materialize(a));
			case LispFloatArray fa -> singleValue(LiteralArrays.materialize(fa));
			case am.ik.rontolisp.LispIntVector iv -> singleValue(LiteralArrays.materialize(iv));
			// A #S(...) literal the top-level fold did not reach (one produced by a
			// runtime read, say) is folded here, so evaluating it always yields the
			// instance rather than a carrier leaking into user data.
			case LispStructLiteral literal -> singleValue(StructLiteralFolder.fold(literal, this.closRegistry));
			// Every other value -- a number, a string, a character, nil and t, a
			// function or closure, a stream, an instance, a quantized matrix a
			// macro-time form handed back -- is self-evaluating.
			default -> singleValue(expr);
		};
	}

	/**
	 * Marks the value just produced as ONE value: clears the {@code %mv-spill} channel --
	 * the interpreter's value-count register ({@code Environment.clearSpill}) -- and
	 * answers the value. Every primitive step that is not a publish or a call of user
	 * code goes through here or through {@link #evalArgs}/{@link #apply}: an atom, a
	 * literal, a constructing special form ({@code quote}, {@code setq}, a definer, a
	 * loop's nil), a built-in's return. A form that merely passes a sub-form's value on
	 * ({@code if}, {@code let}, {@code progn}, a block, a user function's body) leaves
	 * the channel to that sub-form, which is how a {@code (values ...)} tail reaches the
	 * consumer behind any number of returns while a {@code values} whose value went into
	 * a variable, an argument or a discarded position never does (.kb/multiple-values.md,
	 * "The interpreter's value-count register").
	 * @param value the value produced
	 * @return {@code value}
	 */
	private LispVal singleValue(LispVal value) {
		this.globalEnv.clearSpill();
		return value;
	}

	/**
	 * The evaluation seam ({@link #evalCons}'s frame): a raw Java failure escaping the
	 * evaluation of a form -- where no built-in seam ({@link #apply}) caught it first --
	 * becomes a condition the program can handle, classified where it is DETECTED: an
	 * {@code IllegalArgumentException} or an {@code IndexOutOfBoundsException} is how the
	 * macro expander and the special forms report a malformed form, so it is a
	 * {@code program-error} (CLHS 3.5.1); a cast, an arithmetic failure or a negative
	 * size takes {@link #rawFailureConditionClass}'s rule, as at the built-in seam. The
	 * innermost frame converts, so the message is the one closest to the failure. An
	 * {@code UnsupportedOperationException} (a rontolisp limitation, not a program error)
	 * and anything else stay raw: catching a limitation would let a program run on past
	 * what this implementation cannot do.
	 */
	private static LispEvalException rawEvaluationFailure(String className, RuntimeException raw) {
		String message = raw instanceof ClassCastException ? ClosRegistry.TYPE_ERROR_MESSAGE : raw.getMessage();
		if (message == null || message.isBlank()) {
			message = raw.getClass().getSimpleName();
		}
		LispEvalException wrapped = LispEvalException.ofClass(className, message);
		wrapped.initCause(raw);
		return wrapped;
	}

	/**
	 * Whether a printer-control variable ({@code LispMacroExpander.PRINT_CONTROL_VARS})
	 * currently holds a non-default value -- {@code *print-case*} other than
	 * {@code :upcase}, a non-nil {@code *print-length*} / {@code *print-level*} /
	 * {@code *print-radix*}, a nil {@code *print-gensym*}, a {@code *print-base*} other
	 * than 10 -- i.e. whether a printing operator has to route through the
	 * {@code %print-cased} renderer. Read dynamic-first, like any special: the value a
	 * {@code let} binding established on this thread wins over the global default. This
	 * is the interpreter's twin of the compile paths' "the program mentions the variable"
	 * scan; the two agree because the renderer re-reads the variables itself.
	 * @return true when the printer must apply a printer-control variable
	 */
	private boolean printControlsInEffect() {
		// *package* is the seventh control (CLHS 22.1.3.3.1): outside the pristine
		// cl-user a qualified symbol may be accessible and print bare, which only the
		// %print-cased walk decides (LispMacroExpander.printsUnderAPackage is the
		// compile paths' twin).
		if (!this.packageResolver.currentPackageIsPristineClUser()) {
			return true;
		}
		LispVal printControls = currentSpecialValue(LispNames.PRINT_CASE_VAR);
		if (printControls instanceof LispSymbol mode && !LispNames.PRINT_CASE_UPCASE.equals(mode.name())) {
			return true;
		}
		if (!(currentSpecialValue(LispNames.PRINT_LENGTH_VAR) instanceof LispNil)
				|| !(currentSpecialValue(LispNames.PRINT_LEVEL_VAR) instanceof LispNil)
				|| !(currentSpecialValue(LispNames.PRINT_RADIX_VAR) instanceof LispNil)
				|| currentSpecialValue(LispNames.PRINT_GENSYM_VAR) instanceof LispNil) {
			return true;
		}
		return !(currentSpecialValue(LispNames.PRINT_BASE_VAR) instanceof LispInteger base && base.value() == 10);
	}

	/**
	 * The feature set the runtime {@code read} / {@code read-from-string} test their
	 * {@code #+}/{@code #-} guards against: the live {@code *features*} list, entry by
	 * entry, as each symbol prints. Keeping the printed spelling is what preserves the
	 * keyword distinction CL's own comparison makes -- an unqualified {@code #+X} asks
	 * about the KEYWORD {@code :X} (the feature expression is read with {@code *package*}
	 * bound to {@code KEYWORD}, CLHS 24.1.2.1.1) and so does not match a
	 * {@code *features*} entry that is a symbol in some other package. A value that is
	 * not a list of symbols leaves the static set in place rather than signalling: a
	 * reader must not be the thing that reports a malformed {@code *features*}.
	 */
	private am.ik.rontolisp.reader.Features currentReadFeatures() {
		LispVal features = currentSpecialValue(LispNames.FEATURES_VAR);
		if (!(features instanceof LispCons cons) || !cons.isProperList()) {
			return features instanceof LispNil ? am.ik.rontolisp.reader.Features.ofRuntimeList(List.of())
					: am.ik.rontolisp.reader.Features.INTERPRETER;
		}
		List<String> names = new ArrayList<>();
		for (LispVal entry : cons.toList()) {
			if (entry instanceof LispSymbol symbol) {
				names.add(symbol.name());
			}
		}
		return am.ik.rontolisp.reader.Features.ofRuntimeList(names);
	}

	/**
	 * The current value of a standard special variable -- the printer controls, the
	 * reader controls -- dynamic binding first, global default behind it.
	 */
	private @Nullable LispVal currentSpecialValue(String name) {
		return this.dynamicBindings.isBound(name) ? this.dynamicBindings.get(name) : this.globalEnv.lookupOrNull(name);
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
		while (true) {
			switch (form) {
				case LispSymbol sym -> {
					return TorchLibrary.isTorchQualified(sym.name());
				}
				case LispCons cons -> {
					if (referencesTorch(cons.car())) {
						return true;
					}
					form = cons.cdr();
				}
				default -> {
					return false;
				}
			}
		}
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
		while (true) {
			switch (form) {
				case LispSymbol sym -> {
					return GeomLibrary.isGeomQualified(sym.name());
				}
				case LispCons cons -> {
					if (referencesGeom(cons.car())) {
						return true;
					}
					form = cons.cdr();
				}
				default -> {
					return false;
				}
			}
		}
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
			if (LispNames.WRITE_TO_STRING.equals(name) && args.size() > 1) {
				// A keyword tail: rebuild the call with its keywords in place (the
				// lowering matches them syntactically) and its values quoted, then take
				// the same lowering the operator form takes.
				LispVal tail = LispNil.INSTANCE;
				for (int i = args.size() - 1; i >= 1; i--) {
					tail = new LispCons(i % 2 == 1 ? args.get(i) : quoteValue(args.get(i)), tail);
				}
				LispVal form = new LispCons(new LispSymbol(name), new LispCons(quoteValue(args.get(0)), tail));
				return eval(LispMacroExpander.expandWriteToStringKeywords((LispCons) form), this.globalEnv);
			}
			if (!args.isEmpty() && args.size() <= 2 && printControlsInEffect()) {
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
		// The synonym resolved but an open stream value KEPT: the expansion reads its
		// kind to tell a character stream (LispMacroExpander.CharacterStreams).
		LispVal stream = Environment.synonymTarget(eval(parts.get(2), env));
		if (stream instanceof LispInstance inst && !inst.hasTag(am.ik.rontolisp.LispLayout.STREAM_TAG)) {
			// The first occurrence of a keyword counts (CLHS 3.4.1.4), as in the
			// expansion.
			LispVal start = null;
			LispVal end = null;
			for (int i = 3; i + 1 < parts.size(); i += 2) {
				if (parts.get(i) instanceof LispSymbol kw) {
					if (":START".equals(kw.name()) && start == null) {
						start = eval(parts.get(i + 1), env);
					}
					else if (":END".equals(kw.name()) && end == null) {
						end = eval(parts.get(i + 1), env);
					}
				}
			}
			// A Gray stream bypasses the shared expansion below, so the bounds check
			// runs here instead -- the same defun, over the same evaluated values
			// (.todo/932).
			LispVal grayCheckEnd = end == null ? LispNil.INSTANCE : end;
			LispVal grayCheckCall = new LispCons(new LispSymbol(LispNames.CHECK_SEQUENCE_BOUNDS_INTERNAL),
					new LispCons(quoteValue(seq), new LispCons(quoteValue(start == null ? new LispInteger(0) : start),
							new LispCons(quoteValue(grayCheckEnd), LispNil.INSTANCE))));
			eval(grayCheckCall, env);
			return applyGrayDispatch(read ? GRAY_READ_SEQUENCE_DISPATCH : GRAY_WRITE_SEQUENCE_DISPATCH, List.of(seq,
					stream, start == null ? new LispInteger(0) : start, end == null ? LispNil.INSTANCE : end));
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

	/**
	 * Evaluates a compound form: the interpreter's innermost method, and a LOOP. A form
	 * in TAIL position of the current one -- the arm an {@code if} takes, the last form
	 * of a {@code progn}, a {@code let} body or a block, a macro's expansion, the body of
	 * a function a call applies -- REPLACES the current form (and environment) and the
	 * loop goes round again, so a chain of tail calls runs in constant Java stack: a
	 * proper tail call, with no bounce and no allocation per call
	 * (.kb/interpreter-tail-calls.md). A form whose frame must outlive its value keeps it
	 * by construction: {@code
	 * handler-case}, {@code unwind-protect}, {@code catch}, {@code progv}, a {@code let}
	 * binding a special, a lambda with a special parameter, a built-in call -- each is a
	 * method of its own that evaluates its parts through {@link #eval} and returns.
	 *
	 * <p>
	 * What the frame carries for the forms it absorbed: {@code owner}, the exit target of
	 * every block entered in this frame (the first block's scope; a {@code return-from}
	 * aimed at any of them ends the frame with the exit's value, since each of those
	 * blocks IS the frame's continuation); {@code inBody}, whether the frame entered a
	 * function body, so {@link #functionBodyDepth} is raised once per frame however many
	 * bodies tail calls replace, and lowered on every exit; {@code funcallSeam}, whether
	 * a {@code (funcall closure ..)} or {@code (apply closure ..)} was absorbed, so what
	 * the closure raises still runs the handler-bind handlers the built-in seam would
	 * have run. With {@code labels} (a tagbody statement's, else null) a tail {@code (go
	 * L)} to one of them answers the label's jump token instead of throwing
	 * ({@link #evalTagbodyStatement}).
	 *
	 * <p>
	 * The operator table is split so that neither half crosses HotSpot's 8000-bytecode
	 * HugeMethodLimit; see {@link #evalConsRareOperator} and
	 * {@link #rareOperatorExpansion}. This is also the evaluation seam
	 * ({@link #rawEvaluationFailure}): a raw Java failure escaping a form is classified
	 * into a condition here.
	 * @param cons the form
	 * @param env its lexical environment
	 * @param labels the labels of the tagbody whose statement's tail this is, or null
	 * @return the value (a jump token when a statement's tail was a go)
	 */
	private LispVal evalCons(LispCons cons, Environment env, @Nullable TagbodyLabels labels) {
		Environment owner = null;
		boolean inBody = false;
		boolean funcallSeam = false;
		// Where a condition leaving this frame was (ConditionTrace): the innermost form
		// read from a named file the loop has stepped onto and the program function its
		// code is written in (the scope's Environment.lexicalFunction). A type test and
		// two stores per step; read only when a condition escapes.
		LocatedCons located = null;
		String locatedIn = null;
		LispLambda frameLambda = null;
		LispVal result;
		try {
			frame: while (true) {
				LispVal next;
				if (cons instanceof LocatedCons here) {
					located = here;
					locatedIn = env.lexicalFunction();
				}
				dispatch: {
					LispVal head = cons.car();
					// A dotted tail is only meaningful as data (inside quote); in call
					// position it would otherwise be silently dropped by the toList()
					// walks below. The walk also answers the argument count, so the
					// fall-through function call below allocates its argument list
					// exactly sized instead of walking again.
					int properLength = head instanceof LispSymbol qs && LispNames.QUOTE.equals(qs.name()) ? 1
							: cons.properLength();
					if (properLength < 0) {
						throw new LispEvalException("Improper list in call position: " + cons.print());
					}
					LispVal function;
					List<LispVal> args;
					if (head instanceof LispSymbol sym) {
						switch (sym.name()) {
							case LispNames.QUOTE:
							case LispNames.UNSPELLED_QUOTE:
								result = evalQuote(cons);
								break frame;
							case LispNames.IF: {
								if (properLength < 3) {
									// The malformed shapes, reported as before.
									result = evalIf(cons, env);
									break frame;
								}
								LispCons rest = (LispCons) cons.cdr();
								LispCons arms = (LispCons) rest.cdr();
								if (isTruthy(eval(rest.car(), env))) {
									next = arms.car();
									break dispatch;
								}
								if (arms.cdr() instanceof LispCons elseCell) {
									next = elseCell.car();
									break dispatch;
								}
								result = singleValue(LispNil.INSTANCE);
								break frame;
							}
							case LispNames.LET: {
								Environment letEnv = lexicalLet(cons, env);
								if (letEnv == null) {
									// A special or *package* binding keeps its frame for
									// the restore.
									result = evalLetIn(cons, env, labels);
									break frame;
								}
								next = evalAllButLast(((LispCons) cons.cdr()).cdr(), letEnv);
								if (next == null) {
									result = LispNil.INSTANCE;
									break frame;
								}
								env = letEnv;
								break dispatch;
							}
							case LispNames.PROGV:
								result = evalProgv(cons, env);
								break frame;
							case LispNames.DEFUN:
								result = evalDefun(cons, env);
								break frame;
							case LispNames.DEFMACRO:
								result = evalDefmacro(cons, env);
								break frame;
							case LispNames.DEFSTRUCT:
								result = singleValue(evalDefstruct(cons, env));
								break frame;
							case LispNames.DEFCLASS:
								ensureAsdfClassesFor(cons);
								ensureGeomClassesFor(cons);
								result = singleValue(evalDefclass(cons, env));
								break frame;
							case LispNames.DEFGENERIC:
								result = singleValue(evalDefgeneric(cons, env));
								break frame;
							case LispNames.DEFMETHOD:
								ensureAsdfClassesFor(cons);
								ensureGeomClassesFor(cons);
								result = singleValue(evalDefmethod(cons, env));
								break frame;
							case LispNames.MAKE_INSTANCE:
								ensureAsdfClassesFor(cons);
								ensureGeomClassesFor(cons);
								next = LispMacroExpander.expandMakeInstance(cons, this.closRegistry);
								break dispatch;
							case LispNames.CHANGE_CLASS:
								next = LispMacroExpander.expandChangeClass(resolveChangeClassDesignator(cons, env),
										this.closRegistry, false);
								break dispatch;
							case LispNames.SLOT_VALUE:
								result = singleValue(evalSlotValue(cons, env));
								break frame;
							case LispNames.WITH_SLOTS:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWithSlots);
								break dispatch;
							case LispNames.WITH_ACCESSORS:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWithAccessors);
								break dispatch;
							case LispNames.DEFVAR:
								result = evalDefvar(cons, env, false);
								break frame;
							case LispNames.DEFPARAMETER:
								result = evalDefvar(cons, env, true);
								break frame;
							case LispNames.DEFCONSTANT:
								result = evalDefconstant(cons, env);
								break frame;
							case LispNames.ASDF_DEFSYSTEM:
								// A special form: the system options are plain data, not
								// evaluated.
								result = singleValue(evalDefsystem(cons));
								break frame;
							case LispNames.FUNCTION:
								result = singleValue(evalFunction(cons, env));
								break frame;
							case LispNames.PROGN: {
								next = evalAllButLast(cons.cdr(), env);
								if (next == null) {
									result = singleValue(LispNil.INSTANCE);
									break frame;
								}
								break dispatch;
							}
							case LispNames.SETQ:
								result = evalSetq(cons, env);
								break frame;
							case LispNames.LAMBDA:
								result = evalLambdaForm(cons, env);
								break frame;
							case LispNames.ASYNC_QUALIFIED:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandAsync);
								break dispatch;
							case LispNames.ASYNC_DEFUN_QUALIFIED:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandAsyncDefun);
								break dispatch;
							case LispNames.ASYNC_LAMBDA_QUALIFIED:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandAsyncLambda);
								break dispatch;
							case LispNames.AWAIT_QUALIFIED:
								result = evalAwait(cons, env);
								break frame;
							case LispNames.WHILE:
								result = evalWhile(cons, env);
								break frame;
							case LispNames.COND:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandCond);
								break dispatch;
							case LispNames.CASE:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandCase);
								break dispatch;
							case LispNames.ECASE:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandEcase);
								break dispatch;
							case LispNames.CCASE:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandCcase);
								break dispatch;
							case LispNames.ERROR:
								ensureWitLoadedForConditionClass(cons);
								ensureConditionReportRuntimeLoaded();
								// The signal hook (handler-bind handlers at the signal
								// point) is on once the restart
								// runtime is loaded: before the first restart-system form
								// is evaluated no handler can
								// be established, so the historical expansion is
								// behavior-identical -- and the
								// interpreter re-expands per evaluation, so later signals
								// see the hook.
								next = LispMacroExpander.expandError(cons, this.closRegistry, true,
										this.restartRuntimeLoaded);
								break dispatch;
							case LispNames.CERROR:
								ensureConditionReportRuntimeLoaded();
								next = LispMacroExpander.expandCerror(cons, this.closRegistry,
										this.restartRuntimeLoaded);
								break dispatch;
							case LispNames.WARN:
								ensureWitLoadedForConditionClass(cons);
								ensureConditionReportRuntimeLoaded();
								next = LispMacroExpander.expandWarn(cons, this.closRegistry, this.restartRuntimeLoaded);
								break dispatch;
							case LispNames.SIGNAL:
								ensureWitLoadedForConditionClass(cons);
								ensureConditionReportRuntimeLoaded();
								next = LispMacroExpander.expandSignalMacro(cons, this.closRegistry,
										this.restartRuntimeLoaded);
								break dispatch;
							case LispNames.SIGNAL_COND_INTERNAL:
								result = singleValue(evalSignalCond(cons, env));
								break frame;
							case LispNames.HANDLER_CASE:
								ensureWitLoadedForConditionClass(cons);
								ensureConditionReportRuntimeLoaded();
								result = evalHandlerCase(cons, env);
								break frame;
							case LispNames.HANDLER_BIND:
								ensureWitLoadedForConditionClass(cons);
								ensureConditionReportRuntimeLoaded();
								ensureRestartRuntimeLoaded();
								next = LispMacroExpander.expandHandlerBind(cons, this.closRegistry);
								break dispatch;
							case LispNames.RESTART_BIND:
								ensureRestartRuntimeLoaded();
								next = builtinMacroExpansion(cons, LispMacroExpander::expandRestartBind);
								break dispatch;
							case LispNames.WITH_SIMPLE_RESTART:
								ensureRestartRuntimeLoaded();
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWithSimpleRestart);
								break dispatch;
							case LispNames.IGNORE_ERRORS:
								ensureConditionReportRuntimeLoaded();
								next = builtinMacroExpansion(cons, LispMacroExpander::expandIgnoreErrors);
								break dispatch;
							case LispNames.STABLE_SORT:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandStableSort);
								break dispatch;
							case LispNames.COPY_SEQ:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandCopySeq);
								break dispatch;
							case LispNames.AND:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandAnd);
								break dispatch;
							case LispNames.OR:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandOr);
								break dispatch;
							case LispNames.WHEN:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWhen);
								break dispatch;
							case LispNames.DOTIMES:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandDotimes);
								break dispatch;
							case LispNames.DO:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandDo);
								break dispatch;
							case LispNames.DO_STAR:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandDoStar);
								break dispatch;
							case LispNames.LOOP:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandLoop);
								break dispatch;
							case LispNames.BLOCK_INTERNAL:
							case LispNames.BLOCK: {
								// (%block body...) is the iteration macros' implicit nil
								// block; (block name body...)
								// the user's. Either runs in this frame, in a scope of
								// its own that is the block's
								// identity (or shares the frame's, below).
								LispVal body = cons.cdr();
								String name = NIL_BLOCK;
								if (LispNames.BLOCK.equals(sym.name())) {
									if (!(body instanceof LispCons nameCell)) {
										throw new LispEvalException(LispNames.BLOCK + " expects a block name");
									}
									name = blockName(nameCell.car());
									body = nameCell.cdr();
								}
								Environment blockEnv = new Environment(env);
								if (owner == null) {
									owner = blockEnv;
								}
								blockEnv.installBlock(name, owner);
								next = evalAllButLast(body, blockEnv);
								if (next == null) {
									result = singleValue(LispNil.INSTANCE);
									break frame;
								}
								env = blockEnv;
								break dispatch;
							}
							case LispNames.GO: {
								// In the tail of a tagbody statement, a go to one of that
								// tagbody's labels is the
								// statement's answer; any other go is the thrown signal
								// of the second half of the
								// table.
								if (labels != null && properLength == 2) {
									String key = goTagKey(((LispCons) cons.cdr()).car());
									int target = key == null ? NO_JUMP : labels.indexOf(key);
									if (target != NO_JUMP) {
										result = labels.jump(target);
										break frame;
									}
								}
								break;
							}
							case LispNames.RETURN_FROM:
								result = evalReturnFrom(cons, env);
								break frame;
							case LispNames.CATCH:
								result = evalCatch(cons, env);
								break frame;
							case LispNames.THROW:
								result = evalThrow(cons, env);
								break frame;
							case LispNames.UNWIND_PROTECT:
								result = evalUnwindProtect(cons, env);
								break frame;
							case LispNames.RETURN:
								throw blockExit(NIL_BLOCK, evalReturnValue(cons, env), env);
							case LispNames.PROG1:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandProg1);
								break dispatch;
							case LispNames.TIME:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandTime);
								break dispatch;
							case LispNames.UNLESS:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandUnless);
								break dispatch;
							case LispNames.ONE_PLUS:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandOnePlus);
								break dispatch;
							case LispNames.ONE_MINUS:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandOneMinus);
								break dispatch;
							case LispNames.ZEROP:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandZerop);
								break dispatch;
							case LispNames.PLUSP:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandPlusp);
								break dispatch;
							case LispNames.MINUSP:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandMinusp);
								break dispatch;
							case LispNames.EVENP:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandEvenp);
								break dispatch;
							case LispNames.ODDP:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandOddp);
								break dispatch;
							case LispNames.FIRST:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandFirst);
								break dispatch;
							case LispNames.REST:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandRest);
								break dispatch;
							case LispNames.NTH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandNth);
								break dispatch;
							case LispNames.SECOND:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandSecond);
								break dispatch;
							case LispNames.THIRD:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandThird);
								break dispatch;
							case LispNames.FOURTH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandFourth);
								break dispatch;
							case LispNames.FIFTH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandFifth);
								break dispatch;
							case LispNames.SIXTH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandSixth);
								break dispatch;
							case LispNames.SEVENTH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandSeventh);
								break dispatch;
							case LispNames.EIGHTH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandEighth);
								break dispatch;
							case LispNames.NINTH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandNinth);
								break dispatch;
							case LispNames.TENTH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandTenth);
								break dispatch;
							case LispNames.SETF: {
								// (setf (macro-function 'new) (macro-function 'existing))
								// is a write to the MACRO
								// table, which lives here and nowhere else -- the shared
								// expander cannot lower it to a
								// runtime form, so it is carried out during evaluation
								// instead of being expanded.
								LispVal macroAlias = aliasMacroFunction(cons);
								if (macroAlias != null) {
									result = singleValue(macroAlias);
									break frame;
								}
								// A prelude-provided (setf PLACE) writer (the (defun
								// (setf get) ...) beside the get
								// defun) registers its place only when the prelude entry
								// loads; a setf place reference
								// must trigger that load the same way a function call
								// would.
								ensurePreludeSetfPlacesLoaded(cons);
								next = expandSetfMaybeUserExpander(expandUserMacroPlaces(cons));
								break dispatch;
							}
							case LispNames.SCHAR_SET:
								// Not a plain builtin call: a write through a place
								// holding a string LITERAL rebinds
								// that place instead of mutating the source constant,
								// which the callee cannot do for
								// itself.
								result = evalScharSet(cons, env);
								break frame;
							case LispNames.PUSH:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandPush);
								break dispatch;
							case LispNames.POP:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandPop);
								break dispatch;
							case LispNames.REMF:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandRemf);
								break dispatch;
							case LispNames.LET_STAR:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandLetStar);
								break dispatch;
							case LispNames.DOLIST:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandDolist);
								break dispatch;
							case LispNames.INCF:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandIncf);
								break dispatch;
							case LispNames.DECF:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandDecf);
								break dispatch;
							case LispNames.FORMAT:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandFormat);
								break dispatch;
							case LispNames.WITH_OPEN_FILE:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWithOpenFile);
								break dispatch;
							case LispNames.WITH_OUTPUT_TO_STRING:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWithOutputToString);
								break dispatch;
							case LispNames.PPRINT_LOGICAL_BLOCK:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandPprintLogicalBlock);
								break dispatch;
							case LispNames.WITH_ARENA_QUALIFIED:
								// A reclamation boundary for --no-gc; a real GC already
								// reclaims, so the interpreter
								// runs the body as a plain progn.
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWithArena);
								break dispatch;
							case LispNames.WITH_MUTEX_QUALIFIED:
							case LispNames.WITH_LOCK_HELD_QUALIFIED:
							case LispNames.WITH_RECURSIVE_LOCK_HELD_QUALIFIED:
								// Acquire / body / release-on-every-exit;
								// bordeaux-threads' with-lock-held is the same
								// shape over the same primitives, and its recursive twin
								// is the same again -- the
								// shim's lock is reentrant.
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWithMutex);
								break dispatch;
							case LispNames.WIT_EXPORT_QUALIFIED:
								result = singleValue(evalWitExport(cons));
								break frame;
							case LispNames.WIT_IMPORT_QUALIFIED:
								result = singleValue(evalWitImport(cons));
								break frame;
							case LispNames.TORCH_NO_GRAD_QUALIFIED:
								// The expansion let-binds torch::*grad-enabled*, so the
								// library's defparameter must
								// have declared it special BEFORE the let binds.
								ensureTorchLoaded();
								next = builtinMacroExpansion(cons, LispMacroExpander::expandTorchNoGrad);
								break dispatch;
							case LispNames.USOCKET_WITH_CLIENT_SOCKET_QUALIFIED:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandUsocketWithClientSocket);
								break dispatch;
							case LispNames.USOCKET_WITH_CONNECTED_SOCKET_QUALIFIED:
							case LispNames.USOCKET_WITH_SERVER_SOCKET_QUALIFIED:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandUsocketWithConnectedSocket);
								break dispatch;
							case LispNames.USOCKET_WITH_SOCKET_LISTENER_QUALIFIED:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandUsocketWithSocketListener);
								break dispatch;
							case LispNames.USOCKET_GUARD_QUALIFIED:
								next = builtinMacroExpansion(cons, c -> LispMacroExpander.expandUsocketGuard(c, true));
								break dispatch;
							case LispNames.WITH_INPUT_FROM_STRING:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandWithInputFromString);
								break dispatch;
							case LispNames.PUSHNEW:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandPushnew);
								break dispatch;
							case LispNames.DEFTYPE:
								result = singleValue(evalDeftype(cons));
								break frame;
							case LispNames.DEFINE_CONDITION: {
								// A condition type is an ordinary CLOS-subset class; the
								// :report form is registered for
								// the error/signal/warn message building.
								LispVal defined = evalDefclass(
										(LispCons) LispMacroExpander.defineConditionToDefclass(cons, this.closRegistry),
										env);
								// The report renderer partitions the registry, so a new
								// condition class makes the
								// loaded one stale; rebuilding here keeps it in step.
								ensureConditionReportRuntimeLoaded();
								result = singleValue(defined);
								break frame;
							}
							case LispNames.DEFINE_MODIFY_MACRO:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandDefineModifyMacro);
								break dispatch;
							case LispNames.DEFINE_SETF_EXPANDER:
								result = singleValue(registerSetfExpander(cons));
								break frame;
							case LispNames.DEFSETF:
								result = singleValue(registerDefsetf(cons));
								break frame;
							case LispNames.DEFINE_COMPILER_MACRO:
								result = singleValue(evalDefineCompilerMacro(cons, env));
								break frame;
							case LispNames.RESTART_CASE:
								ensureRestartRuntimeLoaded();
								next = builtinMacroExpansion(cons, LispMacroExpander::expandRestartCase);
								break dispatch;
							case LispNames.MACROLET:
								result = evalMacrolet(cons, env);
								break frame;
							case LispNames.MAKE_CONDITION:
								ensureConditionReportRuntimeLoaded();
								next = LispMacroExpander.expandMakeCondition(cons, this.closRegistry);
								break dispatch;
							case LispNames.DOCUMENTATION:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandDocumentation);
								break dispatch;
							case LispNames.COPY_READTABLE:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandCopyReadtable);
								break dispatch;
							case LispNames.SET_DISPATCH_MACRO_CHARACTER:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandSetDispatchMacroCharacter);
								break dispatch;
							case LispNames.READTABLE_CASE:
								next = builtinMacroExpansion(cons, LispMacroExpander::expandReadtableCase);
								break dispatch;
							case LispNames.OBJ_REF:
							case LispNames.OBJ_SET: {
								// A defstruct accessor's checked read / store carries a
								// trailing FAILURE form, evaluated -- after the object
								// and
								// the value -- only when the object is no instance.
								// Without
								// it these are the plain functions below.
								boolean read = LispNames.OBJ_REF.equals(sym.name());
								if (properLength != (read ? 4 : 5)) {
									break;
								}
								List<LispVal> parts = cons.toList();
								List<LispVal> primitiveArgs = new ArrayList<>(3);
								primitiveArgs.add(eval(parts.get(1), env));
								primitiveArgs.add(parts.get(2));
								if (!read) {
									primitiveArgs.add(eval(parts.get(3), env));
								}
								if (!(primitiveArgs.get(0) instanceof LispInstance inst)) {
									next = parts.getLast();
									break dispatch;
								}
								int slot = requireSlotIndex(sym.name(), inst, primitiveArgs);
								if (!read) {
									inst.setSlot(slot, primitiveArgs.get(2));
								}
								result = singleValue(read ? inst.slot(slot) : primitiveArgs.get(2));
								break frame;
							}
						}
						// Neither the tail-transparent expansions nor the value forms of
						// the second half claimed the
						// operator, then the ordinary call.
						LispVal expansion = rareOperatorExpansion(cons, sym.name());
						if (expansion != null) {
							next = expansion;
							break dispatch;
						}
						LispVal rare = evalConsRareOperator(cons, env, sym.name());
						if (rare != UNHANDLED) {
							result = rare;
							break frame;
						}
						if (LispNames.isCarCdrComposition(sym.name())) {
							next = builtinMacroExpansion(cons, LispMacroExpander::expandCarCdrComposition);
							break dispatch;
						}
						// The uiop MACROS -- but only for a package-qualified operator. A
						// name with no colon cannot be
						// a uiop member, and this path is the fall-through every ordinary
						// call takes, so one indexOf
						// here spares BOTH probes' splitQualified for (char s j) and (+ j
						// 1) alike (4% of run-time
						// samples in the todo-598 profile).
						if (sym.name().indexOf(':') > 0) {
							// First the ones with a real expansion -- the one dispatcher
							// both compilers and
							// FreeVarAnalyzer also call, which is what makes the four
							// backends agree by construction
							// rather than by four parallel switch statements kept in
							// step.
							LispVal uiopMacro = LispMacroExpander.expandUiopMacro(cons, true);
							if (uiopMacro != null) {
								next = uiopMacro;
								break dispatch;
							}
							// Then a uiop macro nothing implements yet: it lowers to
							// not-implemented-error with its
							// argument forms dropped -- the same expansion both compilers
							// apply, so an unimplemented
							// (uiop:with-input-file ...) signals here too rather than
							// running its body first. The
							// function-kind members are ordinary calls and fall through
							// to the lazy load below.
							LispVal uiopStub = LispMacroExpander.expandUnimplementedUiopMacro(cons);
							if (uiopStub != null) {
								next = uiopStub;
								break dispatch;
							}
						}
						// User macros defined with defmacro: expand (evaluating the macro
						// body with the unevaluated
						// argument forms bound) and evaluate the expansion. Checked after
						// the built-in operators, so a
						// user macro can never shadow them.
						if (this.userMacros.containsKey(sym.name())) {
							next = expandUserMacro(cons);
							break dispatch;
						}
						// Compiler macros: applied last, so a defmacro and every built-in
						// operator still win, and
						// memoized per call site so the expansion (and the
						// load-time-value slot inside it) is built
						// once for this occurrence.
						if (!this.compilerMacros.isEmpty() && this.compilerMacros.containsKey(sym.name())) {
							LispVal compilerExpansion = expandCompilerMacro(cons);
							if (compilerExpansion != cons) {
								next = compilerExpansion;
								break dispatch;
							}
						}
						// Lisp-2: a symbol in call position is resolved in the function
						// namespace only; variable
						// bindings of the same name do not shadow it.
						function = resolveFunction(sym.name());
						args = evalArgs(cons, env, properLength - 1);
					}
					else {
						// Non-symbol head: a lambda form such as ((lambda (x) x) 5)
						function = eval(head, env);
						args = evalArgs(cons, env, properLength - 1);
					}
					// (funcall closure ...) -- every call of a procedure held in a
					// variable, which is what a Scheme
					// session makes of each definition -- and (apply closure ...) apply
					// the closure HERE instead of
					// through the built-in, which is what makes a tail call through a
					// value proper. The built-in's
					// signal-point seam is kept for what the closure raises
					// (funcallSeam).
					if (function == this.funcallBuiltin && !args.isEmpty() && args.get(0) instanceof LispLambda) {
						function = args.get(0);
						args = args.subList(1, args.size());
						funcallSeam = true;
					}
					else if (function == this.applyBuiltin && args.size() >= 2 && args.get(0) instanceof LispLambda) {
						function = args.get(0);
						funcallSeam = true;
						args = spreadApplyArguments(args);
					}
					else if (function == this.asyncRunBuiltin) {
						// The async function whose body this thunk is, named from THIS
						// frame: the body's own thread will never see the defun.
						try {
							String asyncName = frameLambda == null ? null : frameLambda.name();
							result = singleValue(
									runAsync(args, asyncName == null ? null : UncaughtReport.functionName(asyncName)));
						}
						catch (LispEvalException e) {
							throw withHandlerBindHandlersRun(e);
						}
						break frame;
					}
					if (function instanceof LispLambda lambda) {
						Environment lambdaEnv = lexicalLambdaScope(lambda, args);
						if (lambdaEnv != null) {
							frameLambda = lambda;
							// The body runs in this frame. See expandMacroCall: the depth
							// tells a macro expansion
							// whether its call site is a TOP-LEVEL form (whose file's
							// package is still current) or one
							// buried in a function body evaluated long after its file was
							// read.
							if (!inBody) {
								this.functionBodyDepth++;
								inBody = true;
							}
							List<LispVal> body = lambda.body();
							if (body.size() == 1 && body.get(0) instanceof LispCons form
									&& form.car() instanceof LispSymbol blockHead
									&& LispNames.BLOCK.equals(blockHead.name())
									&& form.cdr() instanceof LispCons nameCell) {
								// A defun/defmethod body IS one block form. Its block
								// runs in the call's own scope --
								// fresh, private to this activation, and covering exactly
								// the block's lexical extent,
								// so it can BE the block's identity: the commonest call
								// still allocates one scope, not
								// two.
								if (owner == null) {
									owner = lambdaEnv;
								}
								lambdaEnv.installBlock(blockName(nameCell.car()), owner);
								next = evalAllButLast(nameCell.cdr(), lambdaEnv);
								if (next == null) {
									result = singleValue(LispNil.INSTANCE);
									break frame;
								}
								env = lambdaEnv;
								break dispatch;
							}
							int last = body.size() - 1;
							if (last < 0) {
								result = LispNil.INSTANCE;
								break frame;
							}
							for (int i = 0; i < last; i++) {
								eval(body.get(i), lambdaEnv);
							}
							env = lambdaEnv;
							next = body.get(last);
							break dispatch;
						}
					}
					// A built-in, a lambda with a special parameter (apply pops its
					// dynamic binding after the body), or
					// a value that names no function.
					result = apply(function, args, env);
					break frame;
				}
				if (next instanceof LispCons nextCons) {
					cons = nextCons;
					continue;
				}
				result = evalAtom(next, env);
				break;
			}
		}
		catch (BlockReturnSignal signal) {
			if (owner == null || signal.target() != owner) {
				throw signal;
			}
			result = signal.value();
		}
		catch (LispEvalException e) {
			e.trace().passing(located, locatedIn);
			throw funcallSeam ? withHandlerBindHandlersRun(e) : e;
		}
		catch (IllegalArgumentException | IndexOutOfBoundsException raw) {
			LispEvalException failure = rawEvaluationFailure(ClosRegistry.PROGRAM_ERROR_CLASS_NAME, raw);
			failure.trace().passing(located, locatedIn);
			throw funcallSeam ? withHandlerBindHandlersRun(failure) : failure;
		}
		catch (ClassCastException | ArithmeticException | NegativeArraySizeException raw) {
			LispEvalException failure = rawEvaluationFailure(rawFailureConditionClass(raw), raw);
			failure.trace().passing(located, locatedIn);
			throw funcallSeam ? withHandlerBindHandlersRun(failure) : failure;
		}
		finally {
			if (inBody) {
				this.functionBodyDepth--;
			}
		}
		return result;
	}

	/**
	 * Evaluates every form of a body but the last, for effect, and answers the last one
	 * -- the form that replaces the current one in {@link #evalCons}'s loop -- or null
	 * for an empty body.
	 * @param body the body forms, a proper list
	 * @param env the environment
	 * @return the last form, or null
	 */
	private @Nullable LispVal evalAllButLast(LispVal body, Environment env) {
		if (!(body instanceof LispCons cell)) {
			return null;
		}
		while (cell.cdr() instanceof LispCons rest) {
			eval(cell.car(), env);
			cell = rest;
		}
		return cell.car();
	}

	/**
	 * The scope of a call of {@code lambda} on {@code args} whose every parameter binds
	 * lexically -- the arguments checked against the lambda list and bound in a fresh
	 * scope over the closure's -- or {@code null} when a parameter is proclaimed special:
	 * its dynamic binding must be popped when the body ends, which {@link #apply} does in
	 * a frame of its own. This is the tail-transparent call of {@link #evalCons}'s loop:
	 * the body runs in the caller's frame.
	 * @param lambda the function
	 * @param args its arguments
	 * @return the body's scope, or null
	 */
	private @Nullable Environment lexicalLambdaScope(LispLambda lambda, List<LispVal> args) {
		checkArity(lambda, args);
		int required = lambda.params().size();
		if (!this.specialVars.isEmpty()) {
			for (int i = 0; i < required; i++) {
				if (this.specialVars.contains(lambda.params().get(i).name())) {
					return null;
				}
			}
			if (lambda.rest() != null && this.specialVars.contains(lambda.rest().name())) {
				return null;
			}
		}
		Environment lambdaEnv = callScope(lambda);
		for (int i = 0; i < required; i++) {
			lambdaEnv.define(lambda.params().get(i).name(), args.get(i));
		}
		if (lambda.rest() != null) {
			LispVal restList = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= required; i--) {
				restList = new LispCons(args.get(i), restList);
			}
			lambdaEnv.define(lambda.rest().name(), restList);
		}
		return lambdaEnv;
	}

	/**
	 * A fresh scope for one call of {@code lambda}, under the scope it closed over. A
	 * program function's own ({@link LispLambda#sourced}) starts its code
	 * ({@link Environment#lexicalFunction}); any other lambda's code is the code of the
	 * function it was written in, which its closure already says.
	 */
	private static Environment callScope(LispLambda lambda) {
		Environment scope = new Environment((Environment) lambda.closure());
		if (lambda.sourced() && lambda.name() != null) {
			scope.lexicalFunction(lambda.name());
		}
		return scope;
	}

	/**
	 * Signals the program-error of a call whose argument count the lambda list refuses.
	 */
	private static void checkArity(LispLambda lambda, List<LispVal> args) {
		int required = lambda.params().size();
		if (args.size() < required) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					ClosRegistry.arityMessage(required, lambda.rest() != null, args.size()));
		}
		if (lambda.rest() == null && args.size() > required) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					ClosRegistry.arityMessage(required, false, args.size()));
		}
	}

	/**
	 * The tail-transparent half of {@link #evalCons}'s second operator table: the
	 * built-in macros whose expansion REPLACES the form in the loop -- most of them pure
	 * functions of the form, memoized per call site ({@link #builtinMacroExpansion},
	 * .kb/interpreter-expansion-memo.md), a few re-expanded per evaluation because they
	 * read evaluator state (the class registry, the user-macro table, a printer hook).
	 * Answers null for an operator it does not claim, including the arms that decline a
	 * particular shape to the ordinary function call (a {@code reduce} without
	 * {@code :from-end}, a {@code sort} without {@code :key}, a print with no hook).
	 *
	 * <p>
	 * The split from {@link #evalConsRareOperator} is by KIND, not by frequency: a value
	 * form ends the frame, an expansion continues it, and the loop must know which. Keep
	 * both halves clear of HotSpot's 8000-bytecode HugeMethodLimit;
	 * {@code LispEvaluatorHotMethodSizeTest} fails the build if either crosses it.
	 * @param cons the form being evaluated
	 * @param name the operator name
	 * @return the form to evaluate in its place, or null
	 */
	private @Nullable LispVal rareOperatorExpansion(LispCons cons, String name) {
		switch (name) {
			case LispNames.PRINT, LispNames.PRINC, LispNames.PRIN1, LispNames.PRINC_TO_STRING,
					LispNames.PRIN1_TO_STRING, LispNames.WRITE_TO_STRING, LispNames.PRINC_PIECE_INTERNAL,
					LispNames.PRIN1_PIECE_INTERNAL: {
				// Routed through print-object only when the program defines a method on
				// it, and through %print-cased only while a printer-control variable
				// holds a non-default value; otherwise the ordinary Environment function
				// runs, unchanged. The renderer is INLINED here rather than called as a
				// generated defun: the interpreter re-expands per call, so it always sees
				// the current method set (a defmethod may follow the first print). The
				// control gate is the CURRENT VALUE rather than the compile paths'
				// "the program mentions the variable" scan -- the interpreter has no
				// whole-program pass to run one in -- and the two agree because
				// %print-cased re-reads the variables itself.
				if (LispNames.WRITE_TO_STRING.equals(name) && cons.isProperList() && cons.toList().size() > 2) {
					// A keyword tail binds the printer variables around the one-argument
					// primitive, the same lowering both compilers take.
					return LispMacroExpander.expandWriteToStringKeywords(cons);
				}
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
				boolean printControls = printControlsInEffect();
				ensurePrintObjectRuntimeLoadedIfRouted(printControls);
				LispVal hooked = LispMacroExpander.expandPrintObjectHook(cons, this.closRegistry, printControls);
				if (hooked != null) {
					return hooked;
				}
				break;
			}
			// complex is a real function now (Environment.registerComplex): no
			// macro case here, so a call resolves to it like any other built-in.
			// Both compilers route it through their complex implementations
			// (JvmComplexCompiler, WasmComplexCompiler).
			case LispNames.NE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandNumericNotEqual);
			case LispNames.PARSE_INTEGER:
				// The shared expansion carries the full keyword set and the second
				// return value; the Environment function remains for first-class
				// use (#'parse-integer).
				return builtinMacroExpansion(cons, LispMacroExpander::expandParseInteger);
			// read has no case here and no Environment function: it is a prelude defun
			// over read-char / unread-char / read-from-string (LispPreludeLibrary), so
			// an ordinary function resolution loads it and #'read is that same defun.
			case LispNames.MAKE_STRING:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMakeString);
			case LispNames.WRITE_LINE:
				// The shared bounds lowering carries :start / :end (the Environment
				// function remains for the plain shape and for first-class use). No
				// memo: the lowering mints fresh temps per call, and a bounded
				// write-line is not hot enough to cache.
				return LispMacroExpander.lowerWriteLineBounds(cons);
			// REPLACE is intentionally NOT expanded here: the interpreter uses the
			// destructive built-in (Environment) so a make-string buffer filled by
			// successive replaces (cl-who's string-list-to-string) mutates in place.
			// The compilers still expand it to a fresh concatenate (no runtime string
			// mutation there; cl-who resolves it at macro-expansion time).
			case LispNames.LOWER_CASE_P:
				return builtinMacroExpansion(cons, LispMacroExpander::expandLowerCaseP);
			case LispNames.UPPER_CASE_P:
				return builtinMacroExpansion(cons, LispMacroExpander::expandUpperCaseP);
			case LispNames.CONSTANTP:
				return builtinMacroExpansion(cons, LispMacroExpander::expandConstantp);
			case LispNames.STREAMP:
				return LispMacroExpander.expandStreamp(cons, true, true, this.closRegistry);
			case LispNames.SIMPLE_STRING_P:
				return builtinMacroExpansion(cons, LispMacroExpander::expandSimpleStringP);
			// make-broadcast-stream goes through the SAME expansion the compile paths
			// use, so every broadcast stream -- with components or without -- is the
			// Gray class on every backend from one definition. The Java built-in
			// below stays only so #'make-broadcast-stream remains a value; it keeps
			// the old sink shape (a zero-component broadcast as a VALUE is still
			// the discarding sink -- .kb/read-load-streams.md).
			case LispNames.MAKE_BROADCAST_STREAM:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMakeBroadcastStream);
			case LispNames.PROG2:
				return builtinMacroExpansion(cons, LispMacroExpander::expandProg2);
			case LispNames.PSETQ:
				return builtinMacroExpansion(cons, LispMacroExpander::expandPsetq);
			case LispNames.PSETF:
				return builtinMacroExpansion(cons, LispMacroExpander::expandPsetf);
			case LispNames.TYPECASE:
				ensureAsdfClassesFor(cons);
				ensureGeomClassesFor(cons);
				ensureUiopTypesFor(cons);
				return LispMacroExpander.expandTypecase(cons, this.closRegistry);
			case LispNames.ETYPECASE:
				ensureAsdfClassesFor(cons);
				ensureGeomClassesFor(cons);
				ensureUiopTypesFor(cons);
				return LispMacroExpander.expandEtypecase(cons, this.closRegistry);
			case LispNames.CTYPECASE:
				ensureAsdfClassesFor(cons);
				ensureGeomClassesFor(cons);
				ensureUiopTypesFor(cons);
				return LispMacroExpander.expandCtypecase(cons, this.closRegistry);
			case LispNames.CHECK_TYPE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandCheckType);
			case LispNames.ASSERT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandAssert);
			case LispNames.DECLARE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandDeclare);
			case LispNames.DEFPACKAGE:
				// A defpackage that is NOT a top-level form -- inside a defun body, an
				// (eval-when ...), a macro expansion. The PackageResolver left it
				// verbatim for exactly this moment: register it now, against the live
				// registry, so the packages a helper function defines exist for
				// everything evaluated after the call. resolve() is the whole
				// registration (it is the same entry the top-level directive takes) and
				// answers the package keyword the standard returns, which is exactly
				// the "expansion" this form has. Its clause errors become the
				// conditions CL names, which a handler around the form can catch.
				return registerRuntimeDefpackage(cons);
			case LispNames.DECLAIM:
				// (declaim (special ...)) proclaims specialness before the form
				// collapses to nil; other declarations remain no-ops.
				SpecialVarCollector.collectForm(cons, this.specialVars);
				return builtinMacroExpansion(cons, LispMacroExpander::expandDeclaim);
			case LispNames.PROCLAIM:
				SpecialVarCollector.collectForm(cons, this.specialVars);
				return builtinMacroExpansion(cons, LispMacroExpander::expandProclaim);
			case LispNames.THE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandThe);
			case LispNames.EVAL_WHEN:
				return builtinMacroExpansion(cons, LispMacroExpander::expandEvalWhen);
			case LispNames.WITH_COMPILATION_UNIT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandWithCompilationUnit);
			case LispNames.LOCALLY:
				return builtinMacroExpansion(cons, LispMacroExpander::expandLocally);
			case LispNames.WITH_STANDARD_IO_SYNTAX:
				return builtinMacroExpansion(cons, LispMacroExpander::expandWithStandardIoSyntax);
			case LispNames.FLET:
				return LispMacroExpander.expandFlet(preExpandLocalMacros(cons));
			case LispNames.LABELS:
				return LispMacroExpander.expandLabels(preExpandLocalMacros(cons));
			case LispNames.MULTIPLE_VALUE_BIND:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMultipleValueBind);
			case LispNames.MULTIPLE_VALUE_LIST:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMultipleValueList);
			case LispNames.MULTIPLE_VALUE_CALL:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMultipleValueCall);
			case LispNames.NTH_VALUE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandNthValue);
			case LispNames.MULTIPLE_VALUE_SETQ:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMultipleValueSetq);
			case LispNames.MULTIPLE_VALUE_PROG1:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMultipleValueProg1);
			case LispNames.ROTATEF:
				return builtinMacroExpansion(cons, LispMacroExpander::expandRotatef);
			case LispNames.SHIFTF:
				return builtinMacroExpansion(cons, LispMacroExpander::expandShiftf);
			case LispNames.TYPEP:
				seedMopClassesForTypepForm(cons);
				ensureAsdfClassesFor(cons);
				ensureGeomClassesFor(cons);
				ensureUiopTypesFor(cons);
				return LispMacroExpander.expandTypep(cons, this.closRegistry);
			case LispNames.UPGRADED_COMPLEX_PART_TYPE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandUpgradedComplexPartType);
			case LispNames.PRINT_UNREADABLE_OBJECT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandPrintUnreadableObject);
			case LispNames.WITH_OPEN_STREAM:
				return builtinMacroExpansion(cons, c -> LispMacroExpander.expandWithOpenStream(c, true));
			case LispNames.WITH_PACKAGE_ITERATOR:
				return builtinMacroExpansion(cons, LispMacroExpander::expandWithPackageIterator);
			case LispNames.WITH_HASH_TABLE_ITERATOR:
				return builtinMacroExpansion(cons, LispMacroExpander::expandWithHashTableIterator);
			case LispNames.DO_ALL_SYMBOLS:
				return builtinMacroExpansion(cons, LispMacroExpander::expandDoAllSymbols);
			case LispNames.PROG:
				return builtinMacroExpansion(cons, c -> LispMacroExpander.expandProg(c, false));
			case LispNames.PROG_STAR:
				return builtinMacroExpansion(cons, c -> LispMacroExpander.expandProg(c, true));
			case LispNames.SYMBOL_MACROLET:
				// The substitution walk expands a user macro it meets before substituting
				// into its expansion (macro arguments may be data, the expansion is
				// code),
				// so it gets this evaluator's one-step expander as the hook.
				return LispMacroExpander.expandSymbolMacrolet(cons, this.symbolMacroUserMacroHook);
			case LispNames.BYTE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandByte);
			case LispNames.BYTE_SIZE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandByteSize);
			case LispNames.BYTE_POSITION:
				return builtinMacroExpansion(cons, LispMacroExpander::expandBytePosition);
			case LispNames.LDB:
				return builtinMacroExpansion(cons, LispMacroExpander::expandLdb);
			case LispNames.MAKE_SEQUENCE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMakeSequence);
			case LispNames.DPB:
				return builtinMacroExpansion(cons, LispMacroExpander::expandDpb);
			case LispNames.DESTRUCTURING_BIND:
				return builtinMacroExpansion(cons, LispMacroExpander::expandDestructuringBind);
			case LispNames.FFLOOR:
			case LispNames.FCEILING:
			case LispNames.FROUND:
			case LispNames.FTRUNCATE:
				// (ffloor a [b]) -> (float (floor a [b])): the same exact quotient
				// floor/ceiling/round/truncate already compute (the case above), with
				// only the primary value floated (todo-667). The remainder, reached only
				// through a multiple-value consumer, is handled by
				// LispMacroExpander#lowerMvProducer before this dispatch is ever
				// reached.
				return builtinMacroExpansion(cons, LispMacroExpander::expandFFamily);
			case LispNames.LIST_STAR:
				return builtinMacroExpansion(cons, LispMacroExpander::expandListStar);
			case LispNames.ACONS:
				return builtinMacroExpansion(cons, LispMacroExpander::expandAcons);
			case LispNames.ELT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandElt);
			case LispNames.VECTOR:
				return builtinMacroExpansion(cons, LispMacroExpander::expandVector);
			case LispNames.SVREF:
				return builtinMacroExpansion(cons, LispMacroExpander::expandSvref);
			case LispNames.ARRAY_RANK:
				return builtinMacroExpansion(cons, LispMacroExpander::expandArrayRank);
			case LispNames.ARRAY_DIMENSION:
				return builtinMacroExpansion(cons, LispMacroExpander::expandArrayDimension);
			case LispNames.ARRAY_TOTAL_SIZE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandArrayTotalSize);
			case LispNames.ARRAY_ROW_MAJOR_INDEX:
				return builtinMacroExpansion(cons, LispMacroExpander::expandArrayRowMajorIndex);
			case LispNames.MAP_INTO:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMapInto);
			case LispNames.RASSOC:
				return builtinMacroExpansion(cons, LispMacroExpander::expandRassoc);
			// The sequence/alist functions taking :test/:key evaluate through the
			// shared macro expansion (like rassoc) so keyword handling matches the
			// compilers exactly; the Environment/LispEvaluator registrations remain
			// for first-class use (#'find etc.).
			case LispNames.FIND:
				return builtinMacroExpansion(cons, LispMacroExpander::expandFind);
			case LispNames.FIND_IF:
				return builtinMacroExpansion(cons, LispMacroExpander::expandFindIf);
			case LispNames.FIND_IF_NOT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandFindIfNot);
			case LispNames.POSITION:
				return builtinMacroExpansion(cons, LispMacroExpander::expandPosition);
			case LispNames.POSITION_IF:
				return builtinMacroExpansion(cons, LispMacroExpander::expandPositionIf);
			case LispNames.POSITION_IF_NOT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandPositionIfNot);
			case LispNames.COMPLEMENT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandComplement);
			case LispNames.COUNT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandCount);
			case LispNames.REMOVE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandRemove);
			case LispNames.DELETE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandDelete);
			case LispNames.REMOVE_DUPLICATES:
			case LispNames.DELETE_DUPLICATES:
				return builtinMacroExpansion(cons, LispMacroExpander::expandRemoveDuplicates);
			case LispNames.UNION:
				return builtinMacroExpansion(cons, LispMacroExpander::expandUnion);
			case LispNames.INTERSECTION:
				return builtinMacroExpansion(cons, LispMacroExpander::expandIntersection);
			case LispNames.SET_DIFFERENCE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandSetDifference);
			case LispNames.ADJOIN:
				return builtinMacroExpansion(cons, LispMacroExpander::expandAdjoin);
			case LispNames.SUBSETP:
				return builtinMacroExpansion(cons, LispMacroExpander::expandSubsetp);
			case LispNames.SUBSTITUTE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandSubstitute);
			case LispNames.NSUBSTITUTE:
				return builtinMacroExpansion(cons, LispMacroExpander::expandNsubstitute);
			case LispNames.SUBSTITUTE_IF:
				return builtinMacroExpansion(cons, LispMacroExpander::expandSubstituteIf);
			case LispNames.SUBSTITUTE_IF_NOT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandSubstituteIfNot);
			case LispNames.NSUBSTITUTE_IF:
				return builtinMacroExpansion(cons, LispMacroExpander::expandNsubstituteIf);
			case LispNames.NSUBSTITUTE_IF_NOT:
				return builtinMacroExpansion(cons, LispMacroExpander::expandNsubstituteIfNot);
			case LispNames.REVAPPEND:
				return builtinMacroExpansion(cons, LispMacroExpander::expandRevappend);
			case LispNames.NRECONC:
				return builtinMacroExpansion(cons, LispMacroExpander::expandNreconc);
			case LispNames.MAP:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMap);
			case LispNames.MAPLIST:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMaplist);
			case LispNames.MAPCON:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMapcon);
			case LispNames.MAPL:
				return builtinMacroExpansion(cons, LispMacroExpander::expandMapl);
			case LispNames.NOTANY:
				return builtinMacroExpansion(cons, LispMacroExpander::expandNotany);
			case LispNames.NOTEVERY:
				return builtinMacroExpansion(cons, LispMacroExpander::expandNotevery);
			case LispNames.REDUCE: {
				// :from-end/:key lower to a plain reduce; other forms fall through to
				// the native reduce builtin resolved below.
				LispVal expandedReduce = LispMacroExpander.expandReduce(cons);
				if (expandedReduce != null) {
					return expandedReduce;
				}
				break;
			}
			case LispNames.SORT: {
				// (sort seq pred :key ...) routes through stable-sort; a plain
				// (sort seq pred) falls through to the native 2-argument builtin.
				LispVal expandedSort = LispMacroExpander.expandSortWithKey(cons);
				if (expandedSort != null) {
					return expandedSort;
				}
				break;
			}
		}
		return null;
	}

	/**
	 * The value half of {@link #evalCons}'s second operator table: the special forms and
	 * primitive calls that answer a VALUE (and so end the loop's frame), answering
	 * {@link #UNHANDLED} for an operator it does not claim and for the handful of arms
	 * that deliberately fall through to the ordinary function call (a one-argument
	 * {@code floor}, a {@code coerce} to an ordinary sequence type).
	 *
	 * <p>
	 * The table is split for one reason: {@code evalCons} is the interpreter's innermost
	 * method, and at 8209 bytecodes it sat just past HotSpot's {@code HugeMethodLimit}
	 * (8000, enforced by the default {@code -XX:+DontCompileHugeMethods}), so it was
	 * never JIT-compiled and every evaluated form ran through the bytecode interpreter --
	 * worth 2.7x on an arithmetic-heavy workload. Keep every half clear of that limit
	 * when adding operators; {@code LispEvaluatorHotMethodSizeTest} fails the build if
	 * any crosses it.
	 * @param cons the form being evaluated
	 * @param env the environment
	 * @param name the operator name
	 * @return the value, or {@link #UNHANDLED}
	 */
	private LispVal evalConsRareOperator(LispCons cons, Environment env, String name) {
		switch (name) {
			case LispNames.HB_GUARD_INTERNAL:
				return evalHbGuard(cons, env);
			case LispNames.PROGRAM_ERROR_INTERNAL: {
				// The lowered argument-shape rejection: a class-named error with no
				// instance until a handler synthesizes one (LispEvalException.ofClass).
				LispVal message = cons.cdr() instanceof LispCons rest ? eval(rest.car(), env) : LispNil.INSTANCE;
				throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
						message instanceof LispString s ? s.value() : message.display());
			}
			case LispNames.FILE_ERROR_INTERNAL: {
				// (%file-error pathname message): the prelude file operations' signal, a
				// file-error instance carrying the pathname as given.
				List<LispVal> args = ((LispCons) cons.cdr()).toList();
				LispVal pathname = eval(args.get(0), env);
				LispVal message = eval(args.get(1), env);
				String text = message instanceof LispString s ? s.value() : message.display();
				throw new LispEvalException(text, ClosRegistry.newFileErrorCondition(pathname, new LispString(text)));
			}
			case LispNames.READ_SEQUENCE:
				return singleValue(evalSequenceWithGrayDispatch(cons, env, true));
			case LispNames.WRITE_SEQUENCE:
				return singleValue(evalSequenceWithGrayDispatch(cons, env, false));
			case LispNames.WRITE_CHAR:
				return singleValue(evalWriteCharWithGrayDispatch(cons, env));
			case LispNames.LOAD_TIME_VALUE:
				return singleValue(evalLoadTimeValue(cons, env));
			case LispNames.DO_EXTERNAL_SYMBOLS:
			case LispNames.DO_SYMBOLS:
				return evalDoSymbols(cons, env, name);
			case LispNames.DEFINE_SYMBOL_MACRO:
				return singleValue(evalDefineSymbolMacro(cons));
			case LispNames.TAGBODY:
				return singleValue(evalTagbody(cons, env));
			case LispNames.GO: {
				// A tag is a symbol or an integer (CLHS 5.3), keyed the way
				// evalTagbody keys its label table.
				String key = goTagKey((cons.cdr() instanceof LispCons tagCons) ? tagCons.car() : null);
				if (key != null) {
					throw new GoSignal(key);
				}
				throw new LispEvalException(LispNames.GO + " expects a tag: " + cons.print());
			}
			case LispNames.FLOOR:
			case LispNames.CEILING:
			case LispNames.ROUND:
			case LispNames.TRUNCATE: {
				// (floor a b), and the (floor (/ a b)) its lowering leaves behind: the
				// quotient of the two operands, NOT of the double (/ a b) rounds them
				// to. A float divides exactly here, so the quotient is the mathematical
				// integer CLHS asks for at any magnitude -- a bignum past the long
				// range, like every other numeric operator -- and the remainder beside
				// it stays rem/mod (.kb/linalg-simd.md, "mod/rem"). The one-argument
				// form falls through to the ordinary built-in function.
				LispVal[] operands = floorFamilyOperands(cons);
				if (operands != null) {
					return singleValue(evalFloorFamilyDivision(name, operands[0], operands[1], env));
				}
				// The one-argument call rounds here too, one value: the FUNCTION
				// publishes its remainder (a funcall, a mapcar), which a call in an
				// ordinary position would only pay for -- a consumer or a function
				// tail has lowered its producer before this dispatch.
				if (cons.cdr() instanceof LispCons argCell && argCell.cdr() instanceof LispNil) {
					return singleValue(Environment.roundToInteger(name, eval(argCell.car(), env)));
				}
				break;
			}
			case LispNames.GETHASH:
			case LispNames.FIND_SYMBOL:
			case LispNames.INTERN:
			case LispNames.SUBTYPEP:
			case LispNames.READ_FROM_STRING:
			case LispNames.ARRAY_DISPLACEMENT:
				// One value in call position; the FUNCTION publishes the second
				// (installValuePublishingFunctions), as the floor family's does above.
				return evalPrimaryValueCall(cons, env, name);
			case LispNames.COERCE: {
				// A packed (unsigned-byte 8|16|32) result type is the one designator
				// expandCoerce cannot express (it collapses a compound spec to its head);
				// the shared lowering answers the same %seq-int-vector call every backend
				// uses (.kb/concatenate-result-families.md).
				LispVal packed = ConcatenateForms.packedVectorCoerce(cons, this.closRegistry);
				if (packed != null) {
					return eval(packed, env);
				}
				return singleValue(evalSequenceCoerce(cons, env));
			}
			case LispNames.SEARCH:
			case LispNames.MISMATCH:
				// The ordinary function call, with a native scan in front of it: both are
				// prelude defuns whose elt-per-element inner loop costs the interpreter
				// ~2.5 us per element PAIR. The arm declines to this same call.
				return singleValue(evalSequenceScan(cons, env, name));
		}
		return UNHANDLED;
	}

	/**
	 * The dividend and divisor of a two-argument {@code floor}-family call, or of the
	 * {@code (/ a b)} its single-value and multiple-value lowerings leave behind.
	 * {@code null} for the plain one-argument form, which the built-in function answers.
	 */
	private static LispVal @Nullable [] floorFamilyOperands(LispCons cons) {
		if (!cons.isProperList()) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() == 3) {
			return new LispVal[] { parts.get(1), parts.get(2) };
		}
		if (parts.size() == 2 && parts.get(1) instanceof LispCons inner && inner.isProperList()
				&& inner.car() instanceof LispSymbol op && LispNames.DIV.equals(plainName(op.name()))) {
			List<LispVal> divParts = inner.toList();
			if (divParts.size() == 3) {
				return new LispVal[] { divParts.get(1), divParts.get(2) };
			}
		}
		return null;
	}

	/**
	 * Evaluates a {@code floor}-family call over a dividend and a divisor. A float
	 * operand takes the exact route ({@link ExactRounding}); everything else divides
	 * first and rounds the quotient, which is exact already for integers and ratios.
	 */
	private LispVal evalFloorFamilyDivision(String name, LispVal dividendForm, LispVal divisorForm, Environment env) {
		LispVal dividend = eval(dividendForm, env);
		LispVal divisor = eval(divisorForm, env);
		try {
			LispVal exact = ExactRounding.quotient(dividend, divisor, ExactRounding.mode(name));
			if (exact != null) {
				return exact;
			}
			return Environment.roundToInteger(name, applyGlobalFunction(LispNames.DIV, dividend, divisor));
		}
		catch (OperandTypeException e) {
			// The division is the rounding operator's own step (the compiled backends
			// emit it as one helper call under that operator).
			throw e.named(name);
		}
	}

	/** Calls a global built-in on already-evaluated arguments. */
	private LispVal applyGlobalFunction(String name, LispVal... args) {
		if (this.globalEnv.lookupFunctionOrNull(name) instanceof LispFunction fn) {
			return fn.body().apply(List.of(args));
		}
		throw new LispEvalException("Undefined function: " + name);
	}

	/**
	 * The expansion of a built-in macro form through {@link #builtinMacroExpansions}:
	 * computed once per call site and re-evaluated thereafter, like a {@code defmacro}
	 * call ({@link #expandUserMacro}) and like the compile backends. Callers may only
	 * pass an expander that is a pure function of the form -- see the memo field's
	 * contract and {@code .kb/interpreter-expansion-memo.md}. The expander runs outside
	 * the monitor (an expansion may recurse into the reader or another expansion); two
	 * threads racing on one call site both expand and the last write wins, a wasted
	 * expansion rather than a wrong answer. The EVALUATION -- the expansion replaces the
	 * form in {@link #evalCons}'s loop -- is outside it too, on the hit path as much as
	 * the miss path: the expansion is a whole program, and a program can hand over to
	 * another thread (the macOS main thread, a socket read) that then needs the monitor
	 * for a memo of its own -- holding it across the eval parks both halves.
	 * @param cons the macro call form
	 * @param expander the pure syntactic expansion of one built-in macro
	 * @return the (memoized) expansion
	 */
	private LispVal builtinMacroExpansion(LispCons cons, java.util.function.Function<LispCons, LispVal> expander) {
		LispVal cached;
		synchronized (this.builtinMacroExpansions) {
			cached = this.builtinMacroExpansions.get(cons);
		}
		if (cached != null) {
			return cached;
		}
		LispVal expansion = expander.apply(cons);
		synchronized (this.builtinMacroExpansions) {
			if (this.builtinMacroExpansions.size() < EXPANSION_MEMO_LIMIT) {
				this.builtinMacroExpansions.put(cons, expansion);
			}
		}
		return expansion;
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
		// The funcName rides on the value so it prints #<function NAME>, the text both
		// compiled backends answer from their function-name table (a defun's value is a
		// lambda here, a funcId there -- the name is the one identity either keeps).
		// Whether its body was read from a named file decides whether the uncaught
		// report may name it (ConditionTrace): a library's function never does.
		boolean sourced = LispTrees.anyCons(blockForm, form -> form instanceof LocatedCons);
		this.globalEnv.defineFunction(funcName,
				new LispLambda(expanded.required(), expanded.rest(), List.of(blockForm), env, funcName, sourced));
		return singleValue(nameForm);
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
		// The implicit nil block every iteration macro establishes: a (return ...)
		// in the body exits the whole form, skipping the result form.
		Environment blockEnv = new Environment(env);
		blockEnv.installBlock(NIL_BLOCK);
		try {
			for (LispSymbol sym : symbols) {
				Environment iterEnv = new Environment(blockEnv);
				iterEnv.define(var.name(), symbolOfSpelling(sym.name()));
				for (LispVal bodyForm : parts.subList(2, parts.size())) {
					eval(bodyForm, iterEnv);
				}
			}
		}
		catch (BlockReturnSignal signal) {
			if (signal.target() == blockEnv) {
				return signal.value();
			}
			throw signal;
		}
		if (spec.size() >= 3) {
			Environment resultEnv = new Environment(blockEnv);
			resultEnv.define(var.name(), LispNil.INSTANCE);
			return eval(spec.get(2), resultEnv);
		}
		return singleValue(LispNil.INSTANCE);
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
		return singleValue(name);
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
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					"Macro " + name + " expects " + (macro.rest() == null ? String.valueOf(macro.required().size())
							: "at least " + macro.required().size()) + " arguments, got " + args.size());
		}
		Environment macroEnv = new Environment(macro.env());
		macroEnv.lexicalFunction(name);
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
			markExpansionConstants(expansion, new java.util.IdentityHashMap<>());
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
	 * Marks every string a macro built into its fresh expansion tree a source constant:
	 * baked into the program exactly like a reader {@code "..."}, so separate expansions
	 * with equal contents compare {@code eq} on the interpreter the way coalesced
	 * literals do on the compiled backends (CLHS 3.2.4.4 permits either). Strings the
	 * macro spliced from its input are already marked; marking them again is a no-op.
	 * Guarded by identity because an expansion may close a cycle.
	 * @param form the fresh expansion tree
	 * @param seen the objects already visited
	 */
	private static void markExpansionConstants(LispVal form, java.util.IdentityHashMap<LispVal, Boolean> seen) {
		if (!(form instanceof LispCons || form instanceof LispArray || form instanceof LispString)
				|| seen.containsKey(form)) {
			return;
		}
		seen.put(form, Boolean.TRUE);
		if (form instanceof LispString string) {
			string.markSourceLiteral();
		}
		else if (form instanceof LispCons cons) {
			LispVal rest = cons;
			while (rest instanceof LispCons cell) {
				markExpansionConstants(cell.car(), seen);
				rest = cell.cdr();
			}
			markExpansionConstants(rest, seen);
		}
		else if (form instanceof LispArray array) {
			for (LispVal element : array.data()) {
				markExpansionConstants(element, seen);
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
		this.globalEnv
			.publishSpill(new LispCons(expansion == form ? LispNil.INSTANCE : LispTrue.INSTANCE, LispNil.INSTANCE));
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
		return singleValue(name);
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
			// A CHARACTER is a string designator (CLHS glossary), hence a package
			// designator: (find-package #\G) answers the package named "G". t is the
			// symbol named "T", like nil above.
			case LispChar ch -> ch.display();
			case LispTrue ignored -> "T";
			default -> throw new LispEvalException(operator + " expects a package designator, got " + val.print());
		};
	}

	/**
	 * A package designator or a list of them (the {@code :use} / {@code :nicknames}
	 * values), coerced to bare package names.
	 */
	private static List<String> designatorList(String operator, LispVal val) {
		if (val instanceof LispNil) {
			return List.of();
		}
		if (val instanceof LispCons cons) {
			List<String> out = new ArrayList<>();
			for (LispVal element : cons.toList()) {
				out.add(packageDesignator(operator, element));
			}
			return out;
		}
		return List.of(packageDesignator(operator, val));
	}

	/**
	 * Registers a {@code defpackage} at RUN time -- one nested in code, or handed to
	 * {@code eval} -- turning its clause errors into the conditions CL names: a
	 * {@code program-error} for a malformed form, a {@code package-error} otherwise, and
	 * for an import of a name the source package lacks a {@code package-error} with a
	 * {@code continue} restart that interns the name there and tries again.
	 * @param cons the {@code defpackage} form
	 * @return the package keyword
	 */
	private LispVal registerRuntimeDefpackage(LispCons cons) {
		while (true) {
			try {
				return this.packageResolver.resolve(cons);
			}
			catch (am.ik.rontolisp.DefpackageException ex) {
				String message = java.util.Objects.requireNonNullElse(ex.getMessage(), LispNames.DEFPACKAGE);
				if (ex.kind() == am.ik.rontolisp.DefpackageException.Kind.PROGRAM_ERROR) {
					throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME, message);
				}
				String missing = ex.missingSymbol();
				if (missing == null) {
					return signalPackageError(message, ex.designator());
				}
				// The correctable case (SBCL's "INTERN it."): a CONTINUE restart that
				// interns the name in the source package, after which the definition
				// is tried again and finds it.
				eval(packageErrorForm(message, ex.designator(), true), this.globalEnv);
				this.packageResolver.internSpellingIn(ex.designator(), missing, true);
			}
		}
	}

	/**
	 * Signals a catchable {@code package-error} carrying the reason in its message and
	 * the offending designator (upcased, keyword-shaped like {@code find-package}
	 * answers; nil for an empty designator, which designates no package anywhere) in its
	 * {@code package} slot. Evaluates the same typed {@code (error 'package-error ...)}
	 * form user code signals, so the message and the condition the handlers see are
	 * identical to the compiled backends' prelude path; never returns normally.
	 * @param message the reason text
	 * @param designator the offending package designator as given
	 * @return nothing (always throws)
	 */
	private LispVal signalPackageError(String message, String designator) {
		return eval(packageErrorForm(message, designator, false), this.globalEnv);
	}

	/**
	 * The {@code (error 'package-error ...)} form {@link #signalPackageError} evaluates;
	 * with {@code continuable} it is wrapped in a {@code restart-case} offering
	 * {@code continue}, which returns normally.
	 */
	private LispVal packageErrorForm(String message, String designator, boolean continuable) {
		List<LispVal> parts = new ArrayList<>();
		parts.add(new LispSymbol(LispNames.ERROR));
		parts.add(quoteValue(new LispSymbol(ClosRegistry.PACKAGE_ERROR_CLASS_NAME)));
		parts.add(new LispSymbol(":PACKAGE"));
		parts.add(designator.isEmpty() ? LispNil.INSTANCE
				: quoteValue(packageKeyword(designator.toUpperCase(java.util.Locale.ROOT))));
		parts.add(new LispSymbol(":FORMAT-CONTROL"));
		parts.add(quoteValue(new LispString(ClosRegistry.textControl(message))));
		if (continuable) {
			LispVal form = LispNil.INSTANCE;
			for (int i = parts.size() - 1; i >= 0; i--) {
				form = new LispCons(parts.get(i), form);
			}
			parts.clear();
			parts.add(new LispSymbol(LispNames.RESTART_CASE));
			parts.add(form);
			parts.add(new LispCons(new LispSymbol(LispNames.CONTINUE), new LispCons(LispNil.INSTANCE, new LispCons(
					new LispSymbol(":REPORT"),
					new LispCons(new LispString("INTERN it."), new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))))));
		}
		LispVal form = LispNil.INSTANCE;
		for (int i = parts.size() - 1; i >= 0; i--) {
			form = new LispCons(parts.get(i), form);
		}
		return form;
	}

	private static void requireSingleArg(String name, List<LispVal> args) {
		if (args.size() != 1) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					name + " expects 1 argument, got " + args.size());
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
	/**
	 * The current -- dynamic-first -- value of {@code *error-output*}: the seeded handle
	 * 2 (the process standard error) unless the program rebound it.
	 */
	private @Nullable LispVal currentErrorOutput() {
		if ((!this.specialVars.isEmpty() || this.progvUsed)
				&& this.dynamicBindings.isBound(LispNames.ERROR_OUTPUT_VAR)) {
			return this.dynamicBindings.get(LispNames.ERROR_OUTPUT_VAR);
		}
		return this.globalEnv.lookupOrNull(LispNames.ERROR_OUTPUT_VAR);
	}

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

	/**
	 * The first {@code :start} / {@code :end} values of a runtime keyword tail, for the
	 * Gray dispatchers that forward bounds to the generics' own optionals -- or the empty
	 * list when neither appears, keeping the two-argument dispatch. Anything else in the
	 * tail keeps the dispatchers' leniency (only the base built-ins validate).
	 * @param args the call's argument values
	 * @param from the index the keyword tail begins at
	 * @return the argument suffix to append (at most start and end), possibly empty
	 */
	private static List<LispVal> grayStreamBounds(List<LispVal> args, int from) {
		LispVal start = null;
		LispVal end = null;
		for (int k = from; k + 1 < args.size(); k += 2) {
			if (args.get(k) instanceof LispSymbol kw) {
				if (":START".equals(kw.name()) && start == null) {
					start = args.get(k + 1);
				}
				else if (":END".equals(kw.name()) && end == null) {
					end = args.get(k + 1);
				}
			}
		}
		if (start == null && end == null) {
			return List.of();
		}
		// A nil bound is ABSENT, never an explicit nil: user methods default start
		// to 0 (the echo stream does), and an explicit nil would override that.
		if (end == null || end instanceof LispNil) {
			return start == null || start instanceof LispNil ? List.of() : List.of(start);
		}
		return start == null || start instanceof LispNil ? List.of(new LispInteger(0), end) : List.of(start, end);
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
			// The keyword helpers a desugared &key prologue calls
			// (LambdaLists.runtimeDefun):
			// the ONE Lisp definition the compilers prepend to a program, evaluated here
			// on
			// the first call -- a lambda-creation-time expansion is what introduces the
			// reference, so no earlier hook can see it coming.
			if (LambdaLists.isRuntimeHelper(name)) {
				eval(LambdaLists.runtimeDefun(name), this.globalEnv);
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
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
			// The checkpoint package (checkpoint.lisp: tensor staging shared by the
			// checkpoint readers) and the safetensors package (safetensors.lisp, the
			// reader over it) load the same way, on the first resolution of a qualified
			// function; a reader's checkpoint: calls load the staging through this
			// same hook on their first call.
			if (!this.checkpointLibraryLoaded && CheckpointLibrary.isCheckpointQualified(name)) {
				ensureCheckpointLoaded();
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			if (!this.safetensorsLibraryLoaded && SafetensorsLibrary.isSafetensorsQualified(name)) {
				ensureSafetensorsLoaded();
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
			// The tokenizer package is a Lisp-source library (tokenizers.lisp): the BPE
			// tokenizers a published language model ships with, over nothing but cl.
			// Loaded the same way on the first resolution of a tokenizer:-qualified
			// function.
			if (!this.tokenizersLibraryLoaded && TokenizersLibrary.isTokenizerQualified(name)) {
				this.tokenizersLibraryLoaded = true;
				for (LispVal form : TokenizersLibrary.forms()) {
					eval(form, this.globalEnv);
				}
				LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
				if (loaded != null) {
					return loaded;
				}
			}
			// The gguf package is a Lisp-source library (gguf.lisp): the reader for a
			// GGUF checkpoint, over nothing but cl and ANSI CL file I/O. Loaded the same
			// way on the first resolution of a gguf:-qualified function.
			if (!this.ggufLibraryLoaded && GgufLibrary.isGgufQualified(name)) {
				this.ggufLibraryLoaded = true;
				for (LispVal form : GgufLibrary.forms()) {
					eval(form, this.globalEnv);
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
					VecSimd.install(this.globalEnv, this, this.parallel);
				}
				// Opt-in --blas: vec:matvec / vec:matvec-into are a GEMV, so they are
				// intercepted here on TOP of the lane kernel exactly as linalg:dot is on
				// the hook above -- and here rather than there because the two libraries
				// load independently (.kb/linalg-blas.md).
				if (this.blas) {
					LinalgBlas.installVec(this.globalEnv, this);
				}
				// Opt-in --gpu: vec:matvec is the one device member outside linalg:, and
				// it is installed here, on TOP of the lane kernel, when THIS library
				// loads -- the two libraries load independently, so it cannot ride on
				// the linalg hook above (.kb/gpu.md). Installed after --blas, so the
				// device is asked FIRST and declines to the best CPU path this
				// invocation enabled.
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
			// The run-time half of the experimental Scheme front end (scheme.lisp) loads
			// the same way on the first resolution of a rontolisp::%scheme- helper, which
			// only a lowered Scheme program names.
			if (!this.schemeLibraryLoaded && SchemeLibrary.isSchemeFunction(name)) {
				this.schemeLibraryLoaded = true;
				// evalResolved, not the bare eval the neighbours use:
				// %scheme-error-message
				// rebinds *standard-output*, and only a form whose special bindings were
				// registered (SpecialVarCollector) binds it DYNAMICALLY for its callees.
				for (LispVal form : SchemeLibrary.forms(this.sourceStandards)) {
					evalResolved(form);
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
			// %struct-type-error, every defstruct accessor's failure arm, is generated
			// the
			// same way.
			if (LispNames.STRUCT_TYPE_ERROR_INTERNAL.equals(name) && this.loadedPreludeNames.add(name)) {
				eval(LispMacroExpander.structTypeErrorDefun(true), this.globalEnv);
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
	 * {@code define-condition}, or a printer-control variable entering or leaving its
	 * non-default values. The compile path emits the pair once because
	 * {@code expandTopLevelDefinitions} runs with a complete registry, which the
	 * interpreter never has.
	 *
	 * <p>
	 * A generated DEFUN rather than a body inlined at the print site (which is what this
	 * was before nested rendering): the renderer walks a list/vector by recursing into
	 * itself, and an inlined form cannot recurse.
	 * @param printControls whether a printer-control variable currently holds a
	 * non-default value
	 */
	private void ensurePrintObjectRuntimeLoaded(boolean printControls) {
		synchronized (this.libraryLoadLock) {
			java.util.List<String> tags = LispMacroExpander.printObjectTags(this.closRegistry);
			int stamp = tags.hashCode() * 4 + (this.closRegistry.routesConditionReports() ? 1 : 0)
					+ (printControls ? 2 : 0);
			if (stamp == this.printObjectRuntimeStamp) {
				return;
			}
			this.printObjectRuntimeStamp = stamp;
			// The interpreter always emits the vector arm: the gate the compile paths use
			// exists to keep the array runtime out of an array-free ARTIFACT, and there
			// is no artifact here.
			for (LispVal form : LispMacroExpander.printObjectStrDefuns(this.closRegistry, printControls, true)) {
				eval(form, this.globalEnv);
			}
		}
	}

	/**
	 * {@link #ensurePrintObjectRuntimeLoaded} guarded by the gate that decides whether
	 * the pair is generated at all: a program with no {@code print-object} method and no
	 * condition in reach is rewritten straight onto {@code %print-cased} (or not at all),
	 * and must not carry a renderer it never calls.
	 * @param printControls whether a printer-control variable currently holds a
	 * non-default value
	 */
	private void ensurePrintObjectRuntimeLoadedIfRouted(boolean printControls) {
		if (!LispMacroExpander.printObjectTags(this.closRegistry).isEmpty()
				|| this.closRegistry.routesConditionReports()) {
			ensurePrintObjectRuntimeLoaded(printControls);
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
		return singleValue(rest.car());
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
		this.globalEnv.clearSpill();
		LispVal primary = evalResolved(call);
		List<LispVal> values = consumeValues(primary);
		// A method answering no value at all still names a creation form: nil.
		return values.isEmpty() ? List.of(primary) : values;
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
		return singleValue(LispNil.INSTANCE);
	}

	/**
	 * The scope of a {@code let} whose every binding is lexical, established -- the inits
	 * evaluated in {@code env} (a {@code let} is parallel) and the names defined in a
	 * fresh scope -- or {@code null}, with nothing evaluated, when the form needs a frame
	 * of its own: a binding of a special (a dynamic binding to pop) or of
	 * {@code *package*} (the resolver's package to restore), or a shape the plain walk
	 * does not cover, all of which {@link #evalLetIn} handles. This is the
	 * tail-transparent {@code let} of {@link #evalCons}'s loop: its body runs in the
	 * caller's frame.
	 * @param cons the let form
	 * @param env the environment the form is evaluated in
	 * @return the body's scope, or null
	 */
	private @Nullable Environment lexicalLet(LispCons cons, Environment env) {
		if (!(cons.cdr() instanceof LispCons bindingsCell)) {
			return null;
		}
		LispVal bindings = LispMacroExpander.normalizeBindingList(bindingsCell.car());
		if (!(bindings instanceof LispNil) && !(bindings instanceof LispCons)) {
			return null;
		}
		boolean checkSpecial = !this.specialVars.isEmpty();
		for (LispVal entry = bindings; entry instanceof LispCons cell; entry = cell.cdr()) {
			if (!(cell.car() instanceof LispCons pair) || !(pair.car() instanceof LispSymbol name)
					|| !(pair.cdr() instanceof LispCons)) {
				return null;
			}
			if (LispNames.PACKAGE_VAR.equals(name.name()) || checkSpecial && this.specialVars.contains(name.name())) {
				return null;
			}
		}
		Environment letEnv = new Environment(env);
		for (LispVal entry = bindings; entry instanceof LispCons cell; entry = cell.cdr()) {
			LispCons pair = (LispCons) cell.car();
			letEnv.define(((LispSymbol) pair.car()).name(), eval(((LispCons) pair.cdr()).car(), env));
		}
		return letEnv;
	}

	/**
	 * A {@code let} evaluated in a frame of its own, because a binding must be undone
	 * when the body ends (a special, {@code *package*}) or because the form's shape is
	 * not the plain one {@link #lexicalLet} covers -- so the last body form still runs
	 * through {@link #evalCons}'s loop and, as a tagbody statement's tail
	 * ({@code tagbodyLabels} non-null), still answers a tail {@code go}'s jump token
	 * after the bindings are undone.
	 */
	private LispVal evalLetIn(LispCons cons, Environment env,
			@org.jspecify.annotations.Nullable TagbodyLabels tagbodyLabels) {
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
			int last = parts.size() - 1;
			for (int i = 2; i < last; i++) {
				eval(parts.get(i), letEnv);
			}
			if (last < 2) {
				return LispNil.INSTANCE;
			}
			LispVal tail = parts.get(last);
			return tail instanceof LispCons tailCons ? evalCons(tailCons, letEnv, tagbodyLabels)
					: evalAtom(tail, letEnv);
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
		// An assignment answers ONE value however many the value form produced: a
		// (setq x (f)) in a function's tail returns f's primary alone, as in CL.
		return singleValue(value);
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
		if (parts.size() != 4 && parts.size() != 5) {
			throw new LispEvalException(LispNames.SCHAR_SET + " expects a string, an index and a character");
		}
		LispVal target = eval(parts.get(1), env);
		LispVal index = eval(parts.get(2), env);
		LispVal character = eval(parts.get(3), env);
		Consumer<LispString> rebind = parts.get(1) instanceof LispSymbol place
				? rebuilt -> assignVariable(place.name(), rebuilt, env) : null;
		// The place head names the store a wrong-type string or subscript reports under.
		return singleValue(Environment.scharSet(List.of(target, index, character), rebind,
				LispMacroExpander.scharSetOperator(cons)));
	}

	private LispVal evalWhile(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal test = parts.get(1);
		while (isTruthy(eval(test, env))) {
			for (int i = 2; i < parts.size(); i++) {
				eval(parts.get(i), env);
			}
		}
		return singleValue(LispNil.INSTANCE);
	}

	/**
	 * Runs a block body in a scope ALREADY marked as establishing it and being its own
	 * exit target -- {@code apply} reuses a call's own scope for the block a
	 * {@code defun} body is wrapped in. A block form in a tail position runs in
	 * {@link #evalCons}'s frame instead, which catches the exits of every block it
	 * entered. A block runs in a scope of its own, and that scope's exit target IS the
	 * block's identity: a closure built inside the body captures it like any other
	 * lexical, which is what makes {@code (return-from name v)} inside a callback exit
	 * this activation rather than the innermost same-named block that is dynamically
	 * active where the callback runs (a {@code handler-bind} handler runs deep inside the
	 * signalling function's loops). An exit aimed at another block -- or at another
	 * activation of this one -- propagates.
	 * @param parts the block form's elements
	 * @param bodyStart the index of the first body form
	 * @param blockEnv the scope establishing the block, i.e. its identity
	 * @return the body's value, or the exiting value
	 */
	private LispVal runBlockIn(List<LispVal> parts, int bodyStart, Environment blockEnv) {
		try {
			if (parts.size() <= bodyStart) {
				return singleValue(LispNil.INSTANCE);
			}
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
		LispVal value = parts.size() == 3 ? eval(parts.get(2), env) : singleValue(LispNil.INSTANCE);
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
			return parts.size() > 2 ? result : singleValue(result);
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
		LispVal value = parts.size() == 3 ? eval(parts.get(2), env) : singleValue(LispNil.INSTANCE);
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
		List<LispVal> allValues = List.of();
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
				// :no-error is a multiple-value consumer. Clear the spill channel first
				// so
				// anything published before the protected form runs (a prior top-level
				// values call) does not bleed in; capture the protected form's extras
				// from %mv-spill, then clear the channel again so the clause body starts
				// fresh. The same channel is what multiple-value-bind reads (see
				// .kb/multiple-values.md).
				//
				// A syntactic producer (gethash, floor-family, find-symbol, intern,
				// array-displacement) only emits its secondary values THROUGH its macro
				// lowering -- a direct eval of (gethash k h) returns one value. Apply the
				// compiler's tail-position rewrite (spillEscapingMvProducers) so the
				// protected form publishes them to spill just as the compile path does.
				this.globalEnv.clearSpill();
				LispVal protectedForEval = LispMacroExpander.spillEscapingMvProducers(protectedForm);
				value = eval(protectedForEval, env);
				if (noErrorClause != null) {
					allValues = consumeValues(value);
				}
				// Without a :no-error clause the form answers the protected form's
				// values as they stand: the channel is left to the consumer behind it.
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
				return clauseParts.size() > 2 ? result : singleValue(result);
			}
			throw e;
		}
		if (noErrorClause != null) {
			List<LispVal> clauseParts = noErrorClause.toList();
			Environment clauseEnv = new Environment(env);
			// Bind the protected form's full value list to the :no-error variable list,
			// missing values as nil and surplus values dropped -- the same shape
			// multiple-value-bind uses (.kb/multiple-values.md, "missing -> nil,
			// surplus evaluated and dropped"). The variable list here is the
			// required-only shape; &optional/&rest/&key are not accepted in this
			// backend (see .kb/multiple-values.md, "The :no-error variable list").
			if (clauseParts.get(1) instanceof LispCons varList) {
				List<LispVal> varVals = varList.toList();
				for (int i = 0; i < varVals.size(); i++) {
					LispVal varVal = varVals.get(i);
					if (!(varVal instanceof LispSymbol sym)) {
						throw new LispEvalException(
								LispNames.HANDLER_CASE + " :no-error variable must be a symbol: " + varVal.print());
					}
					clauseEnv.define(sym.name(), i < allValues.size() ? allValues.get(i) : LispNil.INSTANCE);
				}
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = 2; i < clauseParts.size(); i++) {
				result = eval(clauseParts.get(i), clauseEnv);
			}
			return clauseParts.size() > 2 ? result : singleValue(result);
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
		if (e instanceof OperandTypeException operand
				&& this.closRegistry.newReportingCondition(ClosRegistry.TYPE_ERROR_CLASS_NAME, messageVal, java.util.Map
					.of("DATUM", operand.datum(), "EXPECTED-TYPE", operand.expectedType())) instanceof LispVal c) {
			return c;
		}
		if (className != null && this.closRegistry.newReportingCondition(className, messageVal) instanceof LispVal c) {
			return c;
		}
		LispLayout layout = java.util.Objects
			.requireNonNull(this.closRegistry.findLayoutByTag(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-ERROR"));
		return new LispInstance(layout, new LispVal[] { ClosRegistry.textControl(messageVal), LispNil.INSTANCE });
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
	 * a definition registers here, and only the runtime {@code intern} writes the package
	 * registry's member table.
	 */
	private boolean definedInImage(String name) {
		return this.userMacros.containsKey(name) || this.globalEnv.lookupFunctionOrNull(name) != null
				|| this.globalEnv.hasBinding(name);
	}

	/**
	 * The one lookup behind {@code find-symbol} and its status value: the accessible
	 * symbol of the (verbatim) name in the designated package -- the current one for the
	 * one-argument form -- or null. A designator naming no package signals a
	 * {@code package-error}, as {@code intern}'s does (libraries probing an OPTIONAL
	 * system guard on {@code find-package} first, as postmodern's json-encoder does). The
	 * image probe makes a defun (or a defstruct-GENERATED defun/defvar) under
	 * {@code (in-package
	 * pkg)} count as a member, so {@code (find-symbol "POINT-P" pkg)} finds a defstruct
	 * predicate (trivia level2's predicatep).
	 */
	private PackageResolver.@Nullable Accessible findSymbolAccessible(List<LispVal> args) {
		if (args.size() == 2) {
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.FIND_SYMBOL + " expects a string, got " + args.get(0).print());
			}
			String designator = packageDesignator(LispNames.FIND_SYMBOL, args.get(1));
			if (this.packageResolver.findPackageName(designator) == null) {
				// CLHS: a designator naming no package (nil included) is an error a
				// handler catches. signalPackageError does not return normally.
				signalPackageError("No such package: " + designator, designator);
				return null;
			}
			return this.packageResolver.accessible(designator, str.value(), this::definedInImage);
		}
		requireSingleArg(LispNames.FIND_SYMBOL, args);
		if (!(args.get(0) instanceof LispString str)) {
			throw new LispEvalException(LispNames.FIND_SYMBOL + " expects a string, got " + args.get(0).print());
		}
		// intern/find-symbol take the name verbatim under the uppercase-canonical
		// model -- (find-symbol "car") is NIL, (find-symbol "CAR") names CAR. A
		// ":"-prefixed name is a keyword spelling, kept for the programs that probe
		// one this way.
		String name = str.value();
		if (!name.isEmpty() && name.charAt(0) == ':') {
			return new PackageResolver.Accessible(name, LispNames.STATUS_EXTERNAL);
		}
		return this.packageResolver.accessible(this.packageResolver.currentPackageName(), name, this::definedInImage);
	}

	/**
	 * The value a canonical symbol spelling denotes: {@code t} and {@code nil} are the
	 * singletons (a {@code LispSymbol} spelled {@code "T"} would print as {@code t} and
	 * not be {@code eq} to it), everything else the symbol of that spelling. Every
	 * operator that ANSWERS a symbol it looked up by name goes through this --
	 * {@code find-symbol}, {@code intern}, the enumerations -- so
	 * {@code (eq (find-symbol "T" :cl) t)} holds.
	 */
	private static LispVal symbolOfSpelling(String spelling) {
		return switch (spelling) {
			case "T" -> LispTrue.INSTANCE;
			case "NIL" -> LispNil.INSTANCE;
			default -> new LispSymbol(spelling);
		};
	}

	/**
	 * The stored spelling of a SYMBOL argument of the package operators ({@code export} /
	 * {@code import} / {@code shadowing-import} / {@code unintern}): {@code t} and
	 * {@code nil} are the standard symbols of those names, and a STRING -- not a symbol
	 * in CL, accepted here for the programs that spell one -- names the target package's
	 * own symbol.
	 */
	private static String symbolSpelling(String operator, LispVal val, String targetPackage) {
		return switch (val) {
			case LispSymbol sym -> sym.name();
			case LispTrue ignored -> "T";
			case LispNil ignored -> "NIL";
			case LispString str -> LispNames.CL_USER_PKG.equalsIgnoreCase(targetPackage) ? str.value()
					: PackageRegistry.qualifyInternal(targetPackage, str.value());
			default -> throw new LispEvalException(operator + " expects a symbol, got " + val.print());
		};
	}

	/**
	 * The spellings of a symbol-or-list-of-symbols argument (see
	 * {@link #symbolSpelling}); a bare {@code nil} is the empty list.
	 */
	private static List<String> symbolSpellings(String operator, LispVal val, String targetPackage) {
		List<String> out = new ArrayList<>();
		if (val instanceof LispCons list) {
			for (LispVal element : list.toList()) {
				out.add(symbolSpelling(operator, element, targetPackage));
			}
		}
		else if (!(val instanceof LispNil)) {
			out.add(symbolSpelling(operator, val, targetPackage));
		}
		return out;
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
		LispVal spill = this.globalEnv.spill();
		for (int i = 2; i < parts.size(); i++) {
			eval(parts.get(i), env);
		}
		this.globalEnv.publishSpill(spill);
	}

	/** Evaluates the optional value of a {@code return} form, defaulting to nil. */
	private LispVal evalReturnValue(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		return parts.size() > 1 ? eval(parts.get(1), env) : singleValue(LispNil.INSTANCE);
	}

	private LispVal evalLambdaForm(LispCons cons, Environment env) {
		checkAwaitPlacement(cons);
		List<LispVal> parts = cons.toList();
		// No lite return-from rewrite (and no block wrap): CL lambdas establish no
		// block, so a (return-from f v) inside a lambda called within f's dynamic
		// extent exits F -- the named signal propagates through the call.
		List<LispVal> body = parts.subList(2, parts.size());
		if (!body.isEmpty() && body.get(body.size() - 1) instanceof LispCons tail) {
			// A syntactic multiple-value producer in the tail publishes its secondary
			// value, as in a defun's (evalDefun); the compile paths run the same
			// rewrite in LispMacroExpander.injectMvSpillGlobal.
			LispVal settled = settledLambdaTail(tail);
			if (settled != tail) {
				List<LispVal> settledBody = new ArrayList<>(body);
				settledBody.set(settledBody.size() - 1, settled);
				body = settledBody;
			}
		}
		LambdaLists.Expanded expanded = LambdaLists.expand(parts.get(1), body, false);
		return singleValue(new LispLambda(expanded.required(), expanded.rest(), expanded.body(), env));
	}

	/**
	 * {@link LispMacroExpander#spillEscapingMvProducers} of a lambda body's tail,
	 * memoized by the tail's cons identity ({@link #lambdaTailSettlements}): a closure is
	 * made on every evaluation of its {@code lambda} form, and a
	 * {@code flet}/{@code labels} expansion rebuilds its lambdas each time around the
	 * same body conses.
	 */
	private LispVal settledLambdaTail(LispCons tail) {
		LispVal cached;
		synchronized (this.lambdaTailSettlements) {
			cached = this.lambdaTailSettlements.get(tail);
		}
		if (cached != null) {
			return cached;
		}
		LispVal settled = LispMacroExpander.spillEscapingMvProducers(tail);
		synchronized (this.lambdaTailSettlements) {
			if (this.lambdaTailSettlements.size() < EXPANSION_MEMO_LIMIT) {
				this.lambdaTailSettlements.put(tail, settled);
			}
		}
		return settled;
	}

	// The map* family (mapcar/mapc/mapcan/maplist/mapl/mapcon) operates on lists; a
	// non-list (e.g. a string) is the operator's LIST type-error rather than the empty
	// list, which would hide a caller's mistake. nil is a valid empty list. For mapping
	// over a string or vector, use the generic map.
	private void requireList(String name, LispVal value) {
		Environment.requireListArgument(name, value);
	}

	// Validates a map* family call's arguments -- a function designator plus at least one
	// list, every one of which must be a list -- and returns just the lists. Every member
	// of the family takes N lists in Common Lisp, so the arity check is a lower bound for
	// all of them, not just mapcar.
	private List<LispVal> requireMapLists(String name, List<LispVal> args) {
		if (args.size() < 2) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					name + " expects at least 2 arguments, got " + args.size());
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
	// are diffed against. With 'pieces' (mapcan/mapcon) every value must be a list, the
	// operator's type-error as soon as one is not.
	private List<LispVal> mapFamilyValues(String name, LispVal function, List<LispVal> lists, boolean tails) {
		return mapFamilyValues(name, function, lists, tails, false);
	}

	private List<LispVal> mapFamilyValues(String name, LispVal function, List<LispVal> lists, boolean tails,
			boolean pieces) {
		List<LispVal> cursors = new ArrayList<>(lists);
		List<LispVal> results = new ArrayList<>();
		while (true) {
			List<LispVal> callArgs = new ArrayList<>(cursors.size());
			for (LispVal cursor : cursors) {
				if (cursor instanceof LispCons cell) {
					callArgs.add(tails ? cell : cell.car());
				}
				else {
					// The walk's end: every cursor is a list, a dotted tail being the
					// operator's type-error.
					for (LispVal end : cursors) {
						requireList(name, end);
					}
					return results;
				}
			}
			for (int i = 0; i < cursors.size(); i++) {
				cursors.set(i, ((LispCons) cursors.get(i)).cdr());
			}
			LispVal value = apply(function, callArgs, this.globalEnv);
			if (pieces) {
				requireList(name, value);
			}
			results.add(value);
		}
	}

	// Collect the walk's values into a fresh list (mapcar / maplist).
	private LispVal mapValues(String name, LispVal function, List<LispVal> lists, boolean tails) {
		List<LispVal> results = mapFamilyValues(name, function, lists, tails);
		LispVal result = LispNil.INSTANCE;
		for (int i = results.size() - 1; i >= 0; i--) {
			result = new LispCons(results.get(i), result);
		}
		return result;
	}

	// Apply the function for its side effects only and return the first list (Common Lisp
	// mapc / mapl semantics).
	private LispVal mapForEffect(String name, LispVal function, List<LispVal> lists, boolean tails) {
		mapFamilyValues(name, function, lists, tails);
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
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					opName + " expects at least 2 arguments, got " + args.size());
		}
		LispVal test = null;
		LispVal testNot = null;
		LispVal keyFn = null;
		boolean fromEnd = false;
		long start = 0;
		Long end = null;
		List<String> allowed = mode == PositionScanMode.ITEM
				? List.of(LispNames.TEST_KEYWORD, LispNames.TEST_NOT_KEYWORD, LispNames.KEY_KEYWORD,
						LispNames.START_KEYWORD, LispNames.END_KEYWORD, LispNames.FROM_END_KEYWORD)
				: List.of(LispNames.KEY_KEYWORD, LispNames.START_KEYWORD, LispNames.END_KEYWORD,
						LispNames.FROM_END_KEYWORD);
		requireKeywordTail(opName, args, 2, allowed);
		for (int i = 2; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol kw)) {
				// Admitted by :allow-other-keys t.
				continue;
			}
			LispVal value = args.get(i + 1);
			boolean absent = value instanceof LispNil;
			switch (kw.name()) {
				case LispNames.TEST_KEYWORD, LispNames.TEST_NOT_KEYWORD -> {
					if (mode != PositionScanMode.ITEM) {
						// Admitted by :allow-other-keys t (the predicate IS the test).
						continue;
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
				default -> {
					// :allow-other-keys itself, or a key it admitted.
				}
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

	// Return the tail of the list starting at the first element satisfying the predicate
	// (Common Lisp member-if), or nil. Like find-if but yields the cons rather than the
	// element.
	private LispVal memberIfValues(LispVal predicate, LispVal list, @Nullable LispVal keyFn) {
		while (list instanceof LispCons cell) {
			if (isTruthy(apply(predicate, List.of(keyed(keyFn, cell.car())), this.globalEnv))) {
				return cell;
			}
			list = cell.cdr();
		}
		return Environment.requireListArgument(LispNames.MEMBER_IF, list);
	}

	// The :key designator applied to one element, or the element itself when the
	// designator is absent (or nil, which presentKeyword already reads as absent).
	private LispVal keyed(@Nullable LispVal keyFn, LispVal element) {
		return keyFn == null ? element : apply(keyFn, List.of(element), this.globalEnv);
	}

	/** The set operation a first-class {@link #setOperationValues} call names. */
	private enum SetOp {

		/** {@code union}: list-1 plus each element of list-2 not already present. */
		UNION,
		/** {@code intersection}: the elements of list-1 present in list-2. */
		INTERSECTION,
		/** {@code set-difference}: the elements of list-1 absent from list-2. */
		DIFFERENCE,
		/** {@code subsetp}: whether every element of list-1 is present in list-2. */
		SUBSETP,
		/** {@code adjoin}: list-2 with the item prepended unless it is present. */
		ADJOIN

	}

	/**
	 * The runtime twin of the set-operation expansions: one scan for all five, so a
	 * first-class {@code #'union} takes the same
	 * {@code :test}/{@code :test-not}/{@code :key} set as the call form and answers in
	 * the same order.
	 */
	private LispVal setOperationValues(String name, SetOp op, List<LispVal> args) {
		if (args.size() < 2) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					name + " expects at least 2 arguments, got " + args.size());
		}
		requireTestKeyKeywords(name, args, 2);
		RuntimeTest test = runtimeTest(args, 2);
		LispVal keyFn = presentKeyword(args, 2, LispNames.KEY_KEYWORD);
		LispVal first = args.get(0);
		LispVal second = args.get(1);
		if (op == SetOp.ADJOIN) {
			// CL keys BOTH sides of the comparison but conses the item UNKEYED.
			return setMember(test, keyFn, keyed(keyFn, first), second) ? second : new LispCons(first, second);
		}
		if (op == SetOp.UNION) {
			// The accumulator starts as list-1 and membership is looked for in it as it
			// grows, exactly as expandUnion's do loop does.
			LispVal accumulator = first;
			LispVal cursor = second;
			while (cursor instanceof LispCons cell) {
				if (!setMember(test, keyFn, keyed(keyFn, cell.car()), accumulator)) {
					accumulator = new LispCons(cell.car(), accumulator);
				}
				cursor = cell.cdr();
			}
			return accumulator;
		}
		LispVal accumulator = LispNil.INSTANCE;
		LispVal cursor = first;
		while (cursor instanceof LispCons cell) {
			boolean present = setMember(test, keyFn, keyed(keyFn, cell.car()), second);
			if (op == SetOp.SUBSETP) {
				if (!present) {
					return LispNil.INSTANCE;
				}
			}
			else if (present == (op == SetOp.INTERSECTION)) {
				accumulator = new LispCons(cell.car(), accumulator);
			}
			cursor = cell.cdr();
		}
		return op == SetOp.SUBSETP ? LispTrue.INSTANCE : accumulator;
	}

	// The membership test the five share: the ALREADY-KEYED probe against each element
	// of the list, keyed the same way -- the inner (member ... :test :key) call every
	// set expansion emits.
	private boolean setMember(RuntimeTest test, @Nullable LispVal keyFn, LispVal probe, LispVal list) {
		while (list instanceof LispCons cell) {
			if (testMatches(test, probe, keyed(keyFn, cell.car()))) {
				return true;
			}
			list = cell.cdr();
		}
		return false;
	}

	// Return the first pair whose car satisfies the predicate (Common Lisp assoc-if), or
	// nil. Like assoc but tests with the predicate rather than eql.
	private LispVal assocIfValues(LispVal predicate, LispVal alist, @Nullable LispVal keyFn) {
		while (alist instanceof LispCons cell) {
			if (cell.car() instanceof LispCons pair
					&& isTruthy(apply(predicate, List.of(keyed(keyFn, pair.car())), this.globalEnv))) {
				return pair;
			}
			alist = cell.cdr();
		}
		return Environment.requireListArgument(LispNames.ASSOC_IF, alist);
	}

	// Return the first pair whose cdr satisfies the predicate (Common Lisp rassoc-if), or
	// nil. The mirror of assocIfValues.
	private LispVal rassocIfValues(LispVal predicate, LispVal alist, @Nullable LispVal keyFn) {
		while (alist instanceof LispCons cell) {
			if (cell.car() instanceof LispCons pair
					&& isTruthy(apply(predicate, List.of(keyed(keyFn, pair.cdr())), this.globalEnv))) {
				return pair;
			}
			alist = cell.cdr();
		}
		return Environment.requireListArgument(LispNames.RASSOC_IF, alist);
	}

	// The -if family takes :key only (no :test -- the predicate IS the test), so its
	// keyword tail gets its own validator rather than requireTestKeyKeywords.
	private static void requireKeyKeyword(String name, List<LispVal> args, int start) {
		requireKeywordTail(name, args, start, List.of(LispNames.KEY_KEYWORD));
	}

	/**
	 * Signals the {@code program-error} a malformed keyword tail of a first-class call
	 * deserves, through the SAME rule and text the expansion-time check applies to a call
	 * form ({@code LispMacroExpander.keywordTailProblem}) -- including
	 * {@code :allow-other-keys} suppression -- so {@code (funcall #'remove ...)} and
	 * {@code (remove ...)} cannot disagree.
	 */
	private static void requireKeywordTail(String name, List<LispVal> args, int start, List<String> allowed) {
		String problem = LispMacroExpander.keywordTailProblem(name, args, start, allowed);
		if (problem != null) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME, problem);
		}
	}

	private LispVal reduceValues(LispVal function, LispVal accumulator, LispVal list) {
		while (list instanceof LispCons cell) {
			accumulator = apply(function, List.of(accumulator, cell.car()), this.globalEnv);
			list = cell.cdr();
		}
		return accumulator;
	}

	// Apply the function and concatenate the resulting lists (Common Lisp mapcan / mapcon
	// semantics; the concatenation is non-destructive append rather than nconc). A piece
	// that is no list is the operator's type-error, as on the compile paths.
	private LispVal mapcanValues(String name, LispVal function, List<LispVal> lists, boolean tails) {
		List<LispVal> pieces = mapFamilyValues(name, function, lists, tails, true);
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
		return apply(args.get(0), spreadApplyArguments(args), this.globalEnv);
	}

	/**
	 * The argument list of the call an {@code apply} makes: the arguments between the
	 * function and the last one taken literally, the last one -- which must be a proper
	 * list -- spread.
	 * @param args the apply built-in's arguments, the function first
	 * @return the arguments of the call
	 */
	private static List<LispVal> spreadApplyArguments(List<LispVal> args) {
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
		return callArgs;
	}

	private List<LispVal> evalArgs(LispCons cons, Environment env) {
		return evalArgs(cons, env, 10);
	}

	// The count is a capacity hint (evalCons already walked the form to check
	// properness, so it knows the exact size); the loop still stops at the chain's
	// actual end, so a form rewritten mid-evaluation merely re-grows the list.
	//
	// An argument is a single-value context: whatever the argument forms left on the
	// %mv-spill channel is cleared once every argument is in -- before the callee
	// runs, which publishes its own. Without this, (list (f)) answered f's extras as
	// the list's own to a consumer (the REPL echo above all). See singleValue.
	private List<LispVal> evalArgs(LispCons cons, Environment env, int count) {
		List<LispVal> args = new ArrayList<>(Math.max(count, 0));
		LispVal rest = cons.cdr();
		while (rest instanceof LispCons argCons) {
			args.add(eval(argCons.car(), env));
			rest = argCons.cdr();
		}
		this.globalEnv.clearSpill();
		return args;
	}

	/**
	 * {@code %async-run}: runs the thunk an async-defun/async-lambda lowering built on
	 * its own virtual thread and answers the future. A condition escaping the thunk is
	 * marked as having crossed the boundary ({@link ConditionTrace#crossedAsync}) before
	 * the future stores it, since the frames that see it next -- the await's -- are
	 * another function's.
	 * @param args the thunk, alone
	 * @param asyncFunction the async function's name, or null when unknown (an async
	 * lambda, or a call through a function value)
	 * @return the future
	 */
	private LispVal runAsync(List<LispVal> args, @Nullable String asyncFunction) {
		if (args.size() != 1) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					LispNames.ASYNC_RUN + " expects 1 argument, got " + args.size());
		}
		// The body is code of no function: a condition in its own forms names none, the
		// hop line names the async function (.kb/error-handling.md, "Which function").
		LispVal thunk = args.get(0) instanceof LispLambda lambda ? asyncBody(lambda) : args.get(0);
		// The body's values are captured where it completes: the channel holds its
		// extra values the moment the thunk returns, on the thread that ran it, and
		// they travel in the future -- the awaiter publishes them from there, never
		// from the channel it shares with every other thread.
		return AsyncRuntime.run(future -> {
			LispVal primary;
			try {
				primary = apply(thunk, List.of(), this.globalEnv);
			}
			catch (LispEvalException e) {
				e.trace().crossedAsync(asyncFunction, future.future());
				throw e;
			}
			future.settleExtras(this.globalEnv.spill());
			return primary;
		});
	}

	/**
	 * An async lowering's thunk, run under a scope that starts code of no function
	 * ({@link Environment#lexicalFunction}), so neither its forms nor the lambdas they
	 * build name the function the async form was written in.
	 */
	private static LispLambda asyncBody(LispLambda thunk) {
		Environment scope = new Environment((Environment) thunk.closure());
		scope.lexicalFunction(null);
		return new LispLambda(thunk.params(), thunk.rest(), thunk.body(), scope, thunk.name(), thunk.sourced());
	}

	// The rontolisp:await special form: evaluates its one operand and resolves it.
	private LispVal evalAwait(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					LispNames.AWAIT + " expects 1 argument, got " + (parts.size() - 1));
		}
		return awaitValues(eval(parts.get(1), env));
	}

	// awaitValue as a multiple-value producer (the await special form): answers the
	// primary value and publishes the extra values the resolved future settled with --
	// the last future of a flattened chain decides, a non-future operand is one value.
	private LispVal awaitValues(LispVal v) {
		LispVal extras = LispNil.INSTANCE;
		while (v instanceof LispFuture future) {
			v = joinFuture(future);
			extras = future.extras();
		}
		this.globalEnv.publishSpill(extras);
		return v;
	}

	// Resolves a value like JavaScript await: a future joins its computation (releasing
	// the eager-start handoff first when it would block, so this is the async body's
	// suspension point), re-signaling a stored error -- a Lisp-originated condition
	// (LispEvalException) crosses intact so handler-case around the await catches it by
	// type -- and flattening nested futures; a non-future passes through unchanged.
	private LispVal awaitValue(LispVal v) {
		while (v instanceof LispFuture future) {
			v = joinFuture(future);
		}
		return v;
	}

	// One step of the resolve: joins one future's computation, unflattened.
	private static LispVal joinFuture(LispFuture future) {
		java.util.concurrent.CompletableFuture<LispVal> cf = future.future();
		if (!cf.isDone()) {
			AsyncRuntime.releaseHandoffIfPending();
		}
		try {
			return cf.join();
		}
		catch (java.util.concurrent.CompletionException ex) {
			Throwable cause = java.util.Objects.requireNonNullElse(ex.getCause(), ex);
			if (cause instanceof LispEvalException lispError) {
				// This await is the one re-signalling it (ConditionTrace, "the await that
				// re-signalled it").
				lispError.trace().reawaited(cf);
				throw lispError;
			}
			throw new LispEvalException(java.util.Objects.requireNonNullElse(cause.getMessage(), "await failed"));
		}
	}

	// The single-argument thread-handle check shared by the thread primitives.
	private static LispThread requireThread(String fn, List<LispVal> args) {
		if (args.size() != 1) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					fn + " expects 1 argument, got " + args.size());
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

	// Raw bytes -> the (unsigned-byte 8) vector a body stream answers them as. The
	// request's bytes are this request's alone, so the vector takes them over uncopied.
	private static LispIntVector octetVector(byte[] bytes) {
		return LispIntVector.wrapOctets(bytes);
	}

	// An (unsigned-byte 8) response body -> the raw octets, a copy the program can no
	// longer write. A wider vector narrows element by element, as a byte sink would.
	private static byte[] octetsBytes(LispIntVector octets) {
		if (octets.width() == 8) {
			return octets.octets().clone();
		}
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
			//
			// A built-in's answer is ONE value unless the built-in itself publishes
			// or passes values along (LispFunction.passesValues): a callback it ran --
			// sort's predicate, mapcar's function, a print-object method -- may have
			// published, and that publish must not travel out as the built-in's own
			// values. So the channel is cleared after every other built-in returns
			// (see singleValue).
			try {
				LispVal result = builtIn.body().apply(args);
				if (!builtIn.passesValues()) {
					this.globalEnv.clearSpill();
				}
				return result;
			}
			catch (OperandTypeException e) {
				// A coercion funnel cannot know which operator it serves; the built-in
				// whose body raised the error does (OperandTypes).
				throw withHandlerBindHandlersRun(e.named(builtIn.name()));
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
			// The frame-keeping application: a call evalCons's loop did not absorb --
			// through a built-in (mapcar, sort, the funcall of a non-closure), or a
			// lambda
			// with a special parameter, whose dynamic binding is popped below after the
			// body. Its body's last form still runs through the loop, so tail calls
			// INSIDE the body are proper; only this activation keeps a Java frame.
			checkArity(lambda, args);
			int required = lambda.params().size();
			Environment lambdaEnv = callScope(lambda);
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
		// Not a designator at all: CL's type-error. NIL IS a symbol, so it is the
		// undefined-function the symbol arm above reports for any unbound name.
		LispEvalException failure = function instanceof LispNil
				? LispEvalException.ofClass(ClosRegistry.UNDEFINED_FUNCTION_CLASS_NAME,
						ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX + "NIL"
								+ ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX)
				: LispEvalException.ofClass(ClosRegistry.TYPE_ERROR_CLASS_NAME,
						ClosRegistry.NOT_A_FUNCTION_MESSAGE_PREFIX + function.print());
		throw failure;
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
		// A nil VALUE is the absent designator, the same reading the expansion gives a
		// literal (remove 'a x :test nil): CL's default is eql either way.
		LispVal test = presentKeyword(args, start, LispNames.TEST_KEYWORD);
		if (test != null) {
			return new RuntimeTest(test, false);
		}
		LispVal testNot = presentKeyword(args, start, LispNames.TEST_NOT_KEYWORD);
		return testNot != null ? new RuntimeTest(testNot, true) : new RuntimeTest(new LispSymbol(LispNames.EQL), false);
	}

	private boolean testMatches(RuntimeTest test, LispVal a, LispVal b) {
		return test.negated() != isTruthy(apply(test.fn(), List.of(a, b), this.globalEnv));
	}

	/** The match flavor of a first-class {@code remove}/{@code substitute}/count scan. */
	private enum SeqScanMode {

		/** The operand is an ITEM, compared by :test / :test-not. */
		ITEM,
		/** The operand is a PREDICATE ({@code remove-if}, {@code count-if}, ...). */
		PREDICATE,
		/** The operand is a NEGATED predicate (the {@code -if-not} spellings). */
		PREDICATE_NOT

	}

	/** What a first-class scan does with the elements it accepts. */
	private enum SeqScanAction {

		/** {@code count} / {@code count-if}: answer how many there were. */
		COUNT,
		/** {@code remove} / {@code delete} and their -if spellings: drop them. */
		REMOVE,
		/** {@code substitute} / {@code nsubstitute} and theirs: replace them. */
		SUBSTITUTE

	}

	/**
	 * The runtime counterpart of the expander's bounded scan
	 * ({@code LispMacroExpander.SeqScanScaffold}) for the whole
	 * {@code count}/{@code remove}/{@code substitute} family, first-class use included:
	 * one walk honoring {@code :test}/{@code :test-not}/{@code :key} and CLHS 17.2.1's
	 * {@code :start}/{@code :end}/{@code :count}/{@code :from-end}, so
	 * {@code (funcall #'remove x l :count 1)} decides exactly what {@code (remove x l
	 * :count 1)} decides.
	 *
	 * <p>
	 * {@code :from-end} reverses the order the elements are VISITED in, not merely which
	 * matches a {@code :count} keeps: the {@code :test} and {@code :key} designators are
	 * called in that order, which a side-effecting one sees (ANSI's count-list.9).
	 * Elements outside {@code :start}/{@code :end}, and every element once the count is
	 * spent, are never handed to a designator at all.
	 *
	 * <p>
	 * A DESTRUCTIVE spelling over a list rewrites that list's own cons cells --
	 * {@code rplaca} for the substitute family, a {@code rplacd} splice for
	 * {@code delete}. The splice runs only when no bounding keyword was given, matching
	 * what the compile paths do with a bounded {@code delete} (CLHS lets a destructive
	 * operator answer a fresh sequence, and the caller must use the RESULT either way). A
	 * DESTRUCTIVE substitute over a vector/string has no cons cells to rewrite, so ANSI's
	 * "the argument itself changes" is served the {@code sort}/{@code nreverse} way
	 * instead ({@code Environment.seqResultDestructive}): the freshly-built result is
	 * written back into the argument's own storage. {@code delete} over a vector/string
	 * is NOT the same answer -- it removes elements, so the result cannot be the
	 * argument's own storage, and stays a fresh sequence.
	 * @param name the operator, for the messages
	 * @param args the evaluated arguments
	 * @param mode how an element is matched
	 * @param action what a matching element gets
	 * @param destructive whether this is the {@code n-} / {@code delete} spelling
	 * @return the operator's value
	 */
	private LispVal sequenceScanValues(String name, List<LispVal> args, SeqScanMode mode, SeqScanAction action,
			boolean destructive) {
		int seqIndex = action == SeqScanAction.SUBSTITUTE ? 2 : 1;
		if (args.size() <= seqIndex) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					name + " expects at least " + (seqIndex + 1) + " arguments, got " + args.size());
		}
		int tail = seqIndex + 1;
		List<String> allowed = new ArrayList<>();
		if (mode == SeqScanMode.ITEM) {
			allowed.add(LispNames.TEST_KEYWORD);
			allowed.add(LispNames.TEST_NOT_KEYWORD);
		}
		allowed.add(LispNames.KEY_KEYWORD);
		allowed.add(LispNames.START_KEYWORD);
		allowed.add(LispNames.END_KEYWORD);
		if (action != SeqScanAction.COUNT) {
			allowed.add(LispNames.COUNT_KEYWORD);
		}
		allowed.add(LispNames.FROM_END_KEYWORD);
		requireKeywordTail(name, args, tail, allowed);
		LispVal keyFn = presentKeyword(args, tail, LispNames.KEY_KEYWORD);
		LispVal startValue = presentKeyword(args, tail, LispNames.START_KEYWORD);
		LispVal endValue = presentKeyword(args, tail, LispNames.END_KEYWORD);
		LispVal countValue = action == SeqScanAction.COUNT ? null : presentKeyword(args, tail, LispNames.COUNT_KEYWORD);
		boolean fromEnd = presentKeyword(args, tail, LispNames.FROM_END_KEYWORD) != null;
		long start = startValue == null ? 0 : Environment.requireIndex(name, startValue);
		Long end = endValue == null ? null : (long) Environment.requireIndex(name, endValue);
		// CLHS 17.2.1: a negative :count acts as zero, a nil one as no limit at all.
		Long budget = countValue == null ? null : Math.max(0L, requireCount(name, countValue));
		LispVal operand = args.get(seqIndex - 1);
		LispVal original = args.get(seqIndex);
		boolean listArgument = original instanceof LispCons || original instanceof LispNil;
		List<LispVal> elements = new ArrayList<>();
		List<LispCons> cells = new ArrayList<>();
		for (LispVal cursor = Environment.seqAsList(original); cursor instanceof LispCons cell; cursor = cell.cdr()) {
			elements.add(cell.car());
			if (listArgument) {
				cells.add(cell);
			}
		}
		int size = elements.size();
		long last = end == null ? size : Math.min(end, size);
		boolean[] acted = new boolean[size];
		RuntimeTest test = runtimeTest(args, tail);
		int hits = 0;
		for (int step = 0; step < size; step++) {
			int index = fromEnd ? size - 1 - step : step;
			if (index < start || index >= last) {
				continue;
			}
			if (budget != null && budget <= 0) {
				break;
			}
			LispVal element = elements.get(index);
			LispVal probe = keyFn == null ? element : apply(keyFn, List.of(element), this.globalEnv);
			boolean match = switch (mode) {
				case ITEM -> testMatches(test, operand, probe);
				case PREDICATE -> isTruthy(apply(operand, List.of(probe), this.globalEnv));
				case PREDICATE_NOT -> !isTruthy(apply(operand, List.of(probe), this.globalEnv));
			};
			if (match) {
				acted[index] = true;
				hits++;
				if (budget != null) {
					budget--;
				}
			}
		}
		if (action == SeqScanAction.COUNT) {
			return new LispInteger(hits);
		}
		if (destructive && listArgument && action == SeqScanAction.SUBSTITUTE) {
			for (int i = 0; i < size; i++) {
				if (acted[i]) {
					cells.get(i).setCar(args.get(0));
				}
			}
			return original;
		}
		if (destructive && listArgument && action == SeqScanAction.REMOVE && startValue == null && endValue == null
				&& countValue == null && !fromEnd) {
			// The splice: the surviving cons cells are rewired and reused, and the new
			// head comes back (Common Lisp semantics -- use the return value).
			LispCons head = null;
			LispCons tip = null;
			for (int i = 0; i < size; i++) {
				if (acted[i]) {
					continue;
				}
				LispCons cell = cells.get(i);
				if (tip == null) {
					head = cell;
				}
				else {
					tip.setCdr(cell);
				}
				tip = cell;
			}
			if (tip != null) {
				tip.setCdr(LispNil.INSTANCE);
			}
			return head == null ? LispNil.INSTANCE : head;
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = size - 1; i >= 0; i--) {
			if (action == SeqScanAction.SUBSTITUTE) {
				result = new LispCons(acted[i] ? args.get(0) : elements.get(i), result);
			}
			else if (!acted[i]) {
				result = new LispCons(elements.get(i), result);
			}
		}
		if (destructive && action == SeqScanAction.SUBSTITUTE) {
			// Reaching here means listArgument was false (the list case returned above by
			// rplaca-ing its own cons cells): a vector/string argument has no cons cells
			// to rewrite, so ANSI's "the argument itself changes" is served by writing
			// the
			// freshly-built result back into the argument's own storage
			// (seqResultDestructive, the sort/nreverse precedent) instead of answering it
			// as a new sequence (.todo/773 -- .todo/623's plain seqResult reuse answered
			// a
			// fresh sequence here and left the argument unchanged).
			return Environment.seqResultDestructive(original, result);
		}
		return Environment.seqResult(original, result);
	}

	/**
	 * The runtime counterpart of {@code LispMacroExpander.expandRemoveDuplicates} for
	 * {@code remove-duplicates} / {@code delete-duplicates} used as FUNCTION VALUES, so
	 * {@code (apply #'remove-duplicates seq '(:test #'equal))} decides exactly what
	 * {@code (remove-duplicates seq :test #'equal)} decides. The compile paths reach the
	 * same agreement through {@code BuiltinFunctionWrappers.sequenceScanFamily}, which
	 * feeds the runtime keywords back into the call-position expansion.
	 *
	 * <p>
	 * These two are NOT the count/remove/substitute scan ({@link #sequenceScanValues}):
	 * CLHS 17.2.1's window bounds which elements are CONSIDERED here, so an element
	 * outside {@code :start}/{@code :end} is kept VERBATIM and never handed to a
	 * designator, and {@code :from-end} picks which occurrence of a duplicate set
	 * survives (the first instead of the last) rather than reversing the walk. The
	 * duplicate is looked for by INDEX on the side of the element the direction picks --
	 * {@code [i+1, end)} keeping the last, {@code [start, i)} keeping the first --
	 * exactly as the expansion spells it (.kb/sequence-bounding-keywords.md).
	 * @param name the operator, for the messages
	 * @param args the evaluated arguments
	 * @return the deduplicated sequence, in the argument's own representation
	 */
	private LispVal removeDuplicatesValues(String name, List<LispVal> args) {
		if (args.isEmpty()) {
			throw LispEvalException.ofClass(ClosRegistry.PROGRAM_ERROR_CLASS_NAME,
					name + " expects at least 1 argument, got 0");
		}
		requireKeywordTail(name, args, 1, List.of(LispNames.TEST_KEYWORD, LispNames.TEST_NOT_KEYWORD,
				LispNames.KEY_KEYWORD, LispNames.START_KEYWORD, LispNames.END_KEYWORD, LispNames.FROM_END_KEYWORD));
		LispVal keyFn = presentKeyword(args, 1, LispNames.KEY_KEYWORD);
		LispVal startValue = presentKeyword(args, 1, LispNames.START_KEYWORD);
		LispVal endValue = presentKeyword(args, 1, LispNames.END_KEYWORD);
		boolean keepFirst = presentKeyword(args, 1, LispNames.FROM_END_KEYWORD) != null;
		long start = startValue == null ? 0 : Environment.requireIndex(name, startValue);
		Long end = endValue == null ? null : (long) Environment.requireIndex(name, endValue);
		RuntimeTest test = runtimeTest(args, 1);
		LispVal original = args.get(0);
		List<LispVal> elements = new ArrayList<>();
		for (LispVal cursor = Environment.seqAsList(original); cursor instanceof LispCons cell; cursor = cell.cdr()) {
			elements.add(cell.car());
		}
		int size = elements.size();
		long last = end == null ? size : Math.min(end, size);
		List<LispVal> kept = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			LispVal element = elements.get(i);
			if (i < start || i >= last) {
				// Outside the window: kept verbatim, never compared.
				kept.add(element);
				continue;
			}
			LispVal probe = keyFn == null ? element : apply(keyFn, List.of(element), this.globalEnv);
			long lower = keepFirst ? start : i + 1;
			long upper = keepFirst ? i : last;
			boolean duplicate = false;
			for (long j = lower; j < upper; j++) {
				LispVal candidate = elements.get((int) j);
				LispVal other = keyFn == null ? candidate : apply(keyFn, List.of(candidate), this.globalEnv);
				if (testMatches(test, probe, other)) {
					duplicate = true;
					break;
				}
			}
			if (!duplicate) {
				kept.add(element);
			}
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = kept.size() - 1; i >= 0; i--) {
			result = new LispCons(kept.get(i), result);
		}
		return Environment.seqResult(original, result);
	}

	// A keyword argument's value, or null when it is absent OR nil: CL's own default for
	// every keyword this family takes is what a nil value asks for (:start nil is 0,
	// :end nil is the whole sequence, :count nil is no limit, :key nil is identity),
	// so the two spellings collapse here exactly as they do in the expansion.
	private static @Nullable LispVal presentKeyword(List<LispVal> args, int start, String keyword) {
		LispVal value = optionalKeywordArg(args, start, keyword);
		return value == null || value instanceof LispNil ? null : value;
	}

	// The :count argument as an integer; CLHS 17.2.1 reads a negative one as zero, which
	// the caller clamps.
	private static long requireCount(String name, LispVal value) {
		if (value instanceof LispInteger integer) {
			return integer.value();
		}
		throw LispEvalException.ofClass(ClosRegistry.TYPE_ERROR_CLASS_NAME,
				name + " expects an integer :count, got: " + value.print());
	}

	// Validates the keyword tail of a sequence/alist call: keyword/value pairs only, and
	// every keyword must be :test, :test-not or :key. Unsupported keywords (:from-end,
	// :start, ...) are rejected loudly rather than silently ignored, mirroring the
	// compile-time check in LispMacroExpander.
	private static void requireTestKeyKeywords(String name, List<LispVal> args, int start) {
		requireKeywordTail(name, args, start,
				List.of(LispNames.TEST_KEYWORD, LispNames.TEST_NOT_KEYWORD, LispNames.KEY_KEYWORD));
	}

	private boolean isTruthy(LispVal val) {
		return !(val instanceof LispNil);
	}

}
