package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispPromise;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SpecialVarCollector;
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

	private final Environment globalEnv;

	private final PackageResolver packageResolver = new PackageResolver();

	private boolean jsonLibraryLoaded = false;

	private boolean linalgLibraryLoaded = false;

	private final java.util.Set<String> loadedPreludeNames = new java.util.HashSet<>();

	private boolean urlLibraryLoaded = false;

	/**
	 * User macros defined with {@code defmacro}, keyed by name. A macro call is expanded
	 * (the body evaluated with the unevaluated argument forms bound) and the expansion is
	 * evaluated in its place.
	 */
	private final java.util.Map<String, UserMacro> userMacros = new java.util.HashMap<>();

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
	 * Create a new evaluator with the given output stream.
	 * @param out the output stream for print operations
	 */
	public LispEvaluator(PrintStream out) {
		this.globalEnv = Environment.createGlobal(out);
		registerEval();
	}

	/**
	 * Create a new evaluator with the given output and input streams.
	 * @param out the output stream for print operations
	 * @param in the input stream for read operations
	 */
	public LispEvaluator(PrintStream out, InputStream in) {
		this.globalEnv = Environment.createGlobal(out, in);
		registerEval();
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
		this.globalEnv.defineFunction(LispNames.EVAL, new LispFunction(LispNames.EVAL, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.EVAL + " expects 1 argument, got " + args.size());
			}
			return eval(args.get(0));
		}));
		// macroexpand-1/macroexpand live on the evaluator (not Environment) because they
		// need the user macro table. On the compile path, calls with a literal quoted
		// argument are folded to their expansion by UserMacroExpander.
		this.globalEnv.defineFunction(LispNames.MACROEXPAND_1, new LispFunction(LispNames.MACROEXPAND_1, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.MACROEXPAND_1 + " expects 1 argument, got " + args.size());
			}
			return macroexpand1(args.get(0));
		}));
		this.globalEnv.defineFunction(LispNames.MACROEXPAND, new LispFunction(LispNames.MACROEXPAND, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.MACROEXPAND + " expects 1 argument, got " + args.size());
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
		// boundp/symbol-value read the GLOBAL variable namespace only (like CL, where
		// symbol-value never sees lexical bindings); they live on the evaluator because
		// they capture the global environment. fboundp and find-symbol additionally need
		// the user macro table.
		this.globalEnv.defineFunction(LispNames.BOUNDP, new LispFunction(LispNames.BOUNDP, args -> {
			requireSingleArg(LispNames.BOUNDP, args);
			return switch (args.get(0)) {
				case LispTrue ignored -> LispTrue.INSTANCE;
				case LispNil ignored -> LispTrue.INSTANCE;
				case LispSymbol sym -> sym.isKeyword() || this.dynamicBindings.isBound(sym.name())
						|| this.globalEnv.lookupOrNull(sym.name()) != null ? LispTrue.INSTANCE : LispNil.INSTANCE;
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
		// fboundp is t for anything callable or expandable: functions, user macros, and
		// the built-in macros/special forms (CL: fboundp is true of macros and special
		// operators too).
		this.globalEnv.defineFunction(LispNames.FBOUNDP, new LispFunction(LispNames.FBOUNDP, args -> {
			requireSingleArg(LispNames.FBOUNDP, args);
			if (!(args.get(0) instanceof LispSymbol sym)) {
				throw new LispEvalException(LispNames.FBOUNDP + " expects a symbol, got " + args.get(0).print());
			}
			String name = sym.name();
			boolean bound = SPECIAL_OPERATORS.contains(name) || this.userMacros.containsKey(name)
					|| this.globalEnv.lookupFunctionOrNull(name) != null || LispMacroExpander.isCarCdrComposition(name);
			return bound ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		// find-symbol never creates: the symbol comes back only when the name is already
		// known to the image (a cl symbol, a keyword, or a user definition). The
		// compilers
		// fold a literal call against their compile-time view (cl symbols + user defuns).
		this.globalEnv.defineFunction(LispNames.FIND_SYMBOL, new LispFunction(LispNames.FIND_SYMBOL, args -> {
			if (args.size() == 2) {
				throw new LispEvalException(LispNames.FIND_SYMBOL + " with a package argument is not supported");
			}
			requireSingleArg(LispNames.FIND_SYMBOL, args);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.FIND_SYMBOL + " expects a string, got " + args.get(0).print());
			}
			String name = str.value();
			boolean known = PackageRegistry.isClSymbol(name) || (!name.isEmpty() && name.charAt(0) == ':')
					|| this.userMacros.containsKey(name) || this.globalEnv.lookupFunctionOrNull(name) != null
					|| this.globalEnv.lookupOrNull(name) != null;
			return known ? new LispSymbol(name) : LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.FUNCALL, new LispFunction(LispNames.FUNCALL, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.FUNCALL + " expects at least 1 argument");
			}
			return apply(args.get(0), args.subList(1, args.size()), this.globalEnv);
		}));
		// await lives here rather than in Environment because resolving a then-chain
		// applies the callback, which needs the evaluator's apply.
		String awaitName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.AWAIT);
		this.globalEnv.defineFunction(awaitName, new LispFunction(awaitName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.AWAIT + " expects 1 argument, got " + args.size());
			}
			return awaitValue(args.get(0));
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
		// The JSON functions live here because they dispatch to the Lisp-source
		// library (JsonLibrary), evaluated into the global environment on first use;
		// user lambda lists have no &optional yet, so these variadic dispatchers pad
		// the fixed-arity helper arguments.
		String jsonParseName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.JSON_PARSE);
		this.globalEnv.defineFunction(jsonParseName, new LispFunction(jsonParseName, args -> {
			if (args.size() != 1 && args.size() != 2) {
				throw new LispEvalException(LispNames.JSON_PARSE + " expects 1 or 2 arguments, got " + args.size());
			}
			LispVal as = args.size() == 2 ? args.get(1) : LispNil.INSTANCE;
			return applyJsonHelper(JsonLibrary.HELPER_PARSE, List.of(args.get(0), as));
		}));
		String jsonStringifyName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.JSON_STRINGIFY);
		this.globalEnv.defineFunction(jsonStringifyName, new LispFunction(jsonStringifyName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.JSON_STRINGIFY + " expects 1 argument, got " + args.size());
			}
			return applyJsonHelper(JsonLibrary.HELPER_STRINGIFY, List.of(args.get(0)));
		}));
		this.globalEnv.defineFunction(LispNames.MAPCAR, new LispFunction(LispNames.MAPCAR, args -> {
			if (args.size() < 2) {
				throw new LispEvalException(LispNames.MAPCAR + " expects at least 2 arguments, got " + args.size());
			}
			for (int i = 1; i < args.size(); i++) {
				requireList(LispNames.MAPCAR, args.get(i));
			}
			return mapValues(args.get(0), args.subList(1, args.size()));
		}));
		this.globalEnv.defineFunction(LispNames.MAPC, new LispFunction(LispNames.MAPC, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.MAPC + " expects 2 arguments, got " + args.size());
			}
			requireList(LispNames.MAPC, args.get(1));
			return mapForEffect(args.get(0), args.get(1));
		}));
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
		this.globalEnv.defineFunction(LispNames.EVERY, new LispFunction(LispNames.EVERY, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.EVERY + " expects 2 arguments, got " + args.size());
			}
			return everyValues(args.get(0), Environment.seqAsList(args.get(1)));
		}));
		this.globalEnv.defineFunction(LispNames.SOME, new LispFunction(LispNames.SOME, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.SOME + " expects 2 arguments, got " + args.size());
			}
			return someValues(args.get(0), Environment.seqAsList(args.get(1)));
		}));
		this.globalEnv.defineFunction(LispNames.FIND_IF, new LispFunction(LispNames.FIND_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.FIND_IF + " expects 2 arguments, got " + args.size());
			}
			return findIfValues(args.get(0), Environment.seqAsList(args.get(1)));
		}));
		this.globalEnv.defineFunction(LispNames.FIND_IF_NOT, new LispFunction(LispNames.FIND_IF_NOT, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.FIND_IF_NOT + " expects 2 arguments, got " + args.size());
			}
			return findIfNotValues(args.get(0), Environment.seqAsList(args.get(1)));
		}));
		// The position family is registered here (not in Environment) so the
		// :test/:test-not/:key designators can be applied through the evaluator; the
		// full keyword set (:start/:end/:from-end too) is parsed at runtime so
		// first-class use through apply works (e.g. cl-utilities' split-sequence does
		// (apply #'position delimiter seq :end right other-keys)). The call position
		// routes through the shared macro expansion instead.
		this.globalEnv.defineFunction(LispNames.POSITION, new LispFunction(LispNames.POSITION,
				args -> positionScanValues(LispNames.POSITION, args, PositionScanMode.ITEM)));
		this.globalEnv.defineFunction(LispNames.POSITION_IF, new LispFunction(LispNames.POSITION_IF,
				args -> positionScanValues(LispNames.POSITION_IF, args, PositionScanMode.PREDICATE)));
		this.globalEnv.defineFunction(LispNames.POSITION_IF_NOT, new LispFunction(LispNames.POSITION_IF_NOT,
				args -> positionScanValues(LispNames.POSITION_IF_NOT, args, PositionScanMode.PREDICATE_NOT)));
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
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.REMOVE_IF + " expects 2 arguments, got " + args.size());
			}
			return Environment.seqResult(args.get(1),
					removeIfValues(args.get(0), Environment.seqAsList(args.get(1)), false));
		}));
		this.globalEnv.defineFunction(LispNames.REMOVE_IF_NOT, new LispFunction(LispNames.REMOVE_IF_NOT, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.REMOVE_IF_NOT + " expects 2 arguments, got " + args.size());
			}
			return Environment.seqResult(args.get(1),
					removeIfValues(args.get(0), Environment.seqAsList(args.get(1)), true));
		}));
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
		this.globalEnv.defineFunction(LispNames.MAPCAN, new LispFunction(LispNames.MAPCAN, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.MAPCAN + " expects 2 arguments, got " + args.size());
			}
			requireList(LispNames.MAPCAN, args.get(1));
			return mapcanValues(args.get(0), args.get(1));
		}));
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
					path = name + ".lisp";
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
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.ASDF_LOAD_SYSTEM + " expects 1 argument, got " + args.size());
			}
			String name = AsdfSystems.designator(LispNames.ASDF_LOAD_SYSTEM, args.get(0));
			loadSystem(name);
			return new LispSymbol(name);
		}));
		// ql:quickload = auto-download (real Quicklisp dist) + asdf:load-system. It
		// accepts a single system name or a list of names, downloads each (with its
		// dependencies) into the cache, adds the extracted .asd directories to the search
		// path, and then loads through the same asdf machinery. Returns the list of
		// loaded
		// system names, like real quickload.
		String quickloadName = PackageRegistry.qualify(LispNames.QL_PKG, LispNames.QUICKLOAD);
		this.globalEnv.defineFunction(quickloadName, new LispFunction(quickloadName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.QL_QUICKLOAD + " expects 1 argument, got " + args.size());
			}
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
		String baseDir = this.loadDirStack.peekLast();
		String resolved = SourceLoader.resolve(baseDir, rawPath);
		String source;
		try {
			source = this.sourceLoader.load(resolved);
		}
		catch (IOException ex) {
			throw new LispEvalException(operator + ": cannot read file " + resolved + ": " + ex.getMessage());
		}
		String childDir = SourceLoader.parentDir(resolved);
		this.loadDirStack.addLast(childDir == null ? "" : childDir);
		// Bind the current package around the loaded file so an internal (in-package ...)
		// is scoped to the load and does not leak to the caller, like Common Lisp binding
		// *package* for the duration of load (see .todo/83).
		this.packageResolver.pushPackage();
		try {
			for (LispVal form : LispReader.readAllFromString(source)) {
				eval(form);
			}
		}
		finally {
			this.packageResolver.popPackage();
			this.loadDirStack.removeLast();
		}
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

	private void loadSystem(String name) {
		if (this.loadedSystems.contains(name)) {
			return;
		}
		if (this.loadingSystems.contains(name)) {
			throw new LispEvalException("Circular system :depends-on detected: "
					+ String.join(" -> ", this.loadingSystems) + " -> " + name);
		}
		AsdfSystems.LispSystem system = this.asdfSystems.get(name);
		if (system == null) {
			List<String> searchDirs = new java.util.ArrayList<>();
			String baseDir = this.loadDirStack.peekLast();
			searchDirs.add(baseDir == null ? "" : baseDir);
			searchDirs.addAll(this.systemPath);
			AsdfSystems.LocatedAsd asd = AsdfSystems.locate(name, searchDirs, this.sourceLoader);
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
		// Component paths (and a dependency's .asd lookup) resolve against the system's
		// base directory, not the caller's.
		this.loadDirStack.addLast(system.baseDir());
		try {
			for (String dependency : system.dependsOn()) {
				loadSystem(dependency);
			}
			for (String file : system.files()) {
				loadFile(LispNames.ASDF_LOAD_SYSTEM, file);
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
		return eval(this.packageResolver.resolve(expr), this.globalEnv);
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
			case LispJavaObject j -> j;
			case LispPromise p -> p;
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
		return env.lookup(name);
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
				case LispNames.SLOT_VALUE:
					return eval(LispMacroExpander.expandSlotValue(cons, this.closRegistry), env);
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
					return eval(LispMacroExpander.expandError(cons), env);
				case LispNames.WARN:
					return eval(LispMacroExpander.expandWarn(cons), env);
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
				case LispNames.SETF:
					return eval(LispMacroExpander.expandSetf(cons, this.structAccessors, this.closRegistry), env);
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
				case LispNames.WITH_INPUT_FROM_STRING:
					return eval(LispMacroExpander.expandWithInputFromString(cons), env);
				case LispNames.PUSHNEW:
					return eval(LispMacroExpander.expandPushnew(cons), env);
				case LispNames.DEFTYPE:
					return eval(LispMacroExpander.expandDeftype(cons), env);
				case LispNames.DEFINE_CONDITION:
					return eval(LispMacroExpander.expandDefineCondition(cons), env);
				case LispNames.DEFINE_MODIFY_MACRO:
					return eval(LispMacroExpander.expandDefineModifyMacro(cons), env);
				case LispNames.DEFINE_SETF_EXPANDER:
					return eval(LispMacroExpander.expandDefineSetfExpander(cons), env);
				case LispNames.DEFINE_COMPILER_MACRO:
					return eval(LispMacroExpander.expandDefineCompilerMacro(cons), env);
				case LispNames.RESTART_CASE:
					return eval(LispMacroExpander.expandRestartCase(cons), env);
				case LispNames.MACROLET:
					return evalMacrolet(cons, env);
				case LispNames.MAKE_CONDITION:
					return eval(LispMacroExpander.expandMakeCondition(cons), env);
				case LispNames.DOCUMENTATION:
					return eval(LispMacroExpander.expandDocumentation(cons), env);
				case LispNames.COMPLEX:
					return eval(LispMacroExpander.expandComplexLite(cons), env);
				case LispNames.NE:
					return eval(LispMacroExpander.expandNumericNotEqual(cons), env);
				case LispNames.PARSE_INTEGER:
					// The shared expansion carries the full keyword set and the second
					// return value; the Environment function remains for first-class
					// use (#'parse-integer).
					return eval(LispMacroExpander.expandParseInteger(cons), env);
				case LispNames.READ_SEQUENCE:
					return eval(LispMacroExpander.expandReadSequence(cons), env);
				case LispNames.WRITE_SEQUENCE:
					return eval(LispMacroExpander.expandWriteSequence(cons), env);
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
				case LispNames.PROG2:
					return eval(LispMacroExpander.expandProg2(cons), env);
				case LispNames.PSETQ:
					return eval(LispMacroExpander.expandPsetq(cons), env);
				case LispNames.TYPECASE:
					return eval(LispMacroExpander.expandTypecase(cons), env);
				case LispNames.ETYPECASE:
					return eval(LispMacroExpander.expandEtypecase(cons), env);
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
				case LispNames.FLET:
					return eval(LispMacroExpander.expandFlet(cons), env);
				case LispNames.LABELS:
					return eval(LispMacroExpander.expandLabels(cons), env);
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
				case LispNames.ROTATEF:
					return eval(LispMacroExpander.expandRotatef(cons), env);
				case LispNames.BYTE:
					return eval(LispMacroExpander.expandByte(cons), env);
				case LispNames.BYTE_SIZE:
					return eval(LispMacroExpander.expandByteSize(cons), env);
				case LispNames.BYTE_POSITION:
					return eval(LispMacroExpander.expandBytePosition(cons), env);
				case LispNames.LDB:
					return eval(LispMacroExpander.expandLdb(cons), env);
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
			if (LispMacroExpander.isCarCdrComposition(sym.name())) {
				return eval(LispMacroExpander.expandCarCdrComposition(cons), env);
			}
			// User macros defined with defmacro: expand (evaluating the macro body with
			// the unevaluated argument forms bound) and evaluate the expansion. Checked
			// after the built-in operators, so a user macro can never shadow them.
			if (this.userMacros.containsKey(sym.name())) {
				return eval(expandUserMacro(cons), env);
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

	private LispVal evalDefun(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal nameForm = parts.get(1);
		// (defun (setf name) ...): a setf-function. Install it under the mangled internal
		// name %setf-name and register the place so (setf (name ...) v) dispatches to it.
		LispSymbol setfPlace = LispMacroExpander.setfFunctionPlaceName(nameForm);
		String funcName;
		if (setfPlace != null) {
			funcName = LispMacroExpander.setfFunctionName(setfPlace.name());
			this.structAccessors.put(setfPlace.name(), LispMacroExpander.SETF_FUNCTION_MARKER);
		}
		else {
			funcName = ((LispSymbol) nameForm).name();
		}
		LambdaLists.Expanded expanded = LambdaLists.expand(parts.get(2), parts.subList(3, parts.size()));
		// defun installs into the global function namespace, capturing the current
		// lexical environment, and returns the function name like Common Lisp.
		this.globalEnv.defineFunction(funcName,
				new LispLambda(expanded.required(), expanded.rest(), expanded.body(), env));
		return nameForm;
	}

	private LispVal evalDefstruct(LispCons cons, Environment env) {
		// Expand into the generated defuns (constructor, predicate, copier, accessors)
		// and evaluate each; the accessor registry makes them setf-able places.
		for (LispVal form : LispMacroExpander.expandDefstruct(cons, this.structAccessors)) {
			eval(form, env);
		}
		// defstruct returns the structure name, like Common Lisp.
		return cons.toList().get(1);
	}

	private LispVal evalDefclass(LispCons cons, Environment env) {
		// Expand into the generated defuns (constructor, readers/accessors) and
		// evaluate each, then regenerate the dispatchers that test class specializers:
		// the new class may extend one of their descendant tag sets.
		for (LispVal form : LispMacroExpander.expandDefclass(cons, this.closRegistry, this.structAccessors)) {
			eval(form, env);
		}
		for (ClosRegistry.GenericInfo generic : this.closRegistry.generics().values()) {
			if (generic.hasClassMethod()) {
				eval(LispMacroExpander.generateDispatcher(generic.name(), this.closRegistry), env);
			}
		}
		return cons.toList().get(1);
	}

	private LispVal evalDefgeneric(LispCons cons, Environment env) {
		String generic = LispMacroExpander.registerDefgeneric(cons, this.closRegistry);
		eval(LispMacroExpander.generateDispatcher(generic, this.closRegistry), env);
		return cons.toList().get(1);
	}

	private LispVal evalDefmethod(LispCons cons, Environment env) {
		// Evaluate the generated method-body defun, then redefine the dispatcher so it
		// sees the new method (calls by name always dispatch through the fresh one; a
		// #'name captured earlier keeps the previous dispatcher).
		eval(LispMacroExpander.expandDefmethod(cons, this.closRegistry), env);
		String generic = ((LispSymbol) cons.toList().get(1)).name();
		eval(LispMacroExpander.generateDispatcher(generic, this.closRegistry), env);
		return cons.toList().get(1);
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
	 * Builds a {@link UserMacro} record from a {@code (name lambda-list body...)}
	 * definition, shared by {@link #evalDefmacro} and {@link #evalMacrolet}. A native
	 * "required + &rest/&body" lambda list is stored directly; any extended shape is
	 * wrapped in a {@code destructuring-bind} over an internal rest parameter (validated
	 * eagerly by a dry-run expansion) so both macro-expansion consumers destructure
	 * identically.
	 */
	private UserMacro makeUserMacro(String op, LispSymbol name, LispVal paramForm, List<LispVal> body,
			Environment env) {
		List<LispVal> paramList = paramForm instanceof LispCons paramCons ? paramCons.toList() : List.of();
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
				result = eval(bodyForm, env);
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
		List<LispVal> args = form.cdr() instanceof LispCons argCons ? argCons.toList() : List.of();
		if (args.size() < macro.required().size() || (macro.rest() == null && args.size() > macro.required().size())) {
			throw new LispEvalException(
					"Macro " + name + " expects " + (macro.rest() == null ? String.valueOf(macro.required().size())
							: "at least " + macro.required().size()) + " arguments, got " + args.size());
		}
		Environment macroEnv = new Environment(macro.env());
		for (int i = 0; i < macro.required().size(); i++) {
			macroEnv.define(macro.required().get(i).name(), args.get(i));
		}
		if (macro.rest() != null) {
			LispVal restList = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= macro.required().size(); i--) {
				restList = new LispCons(args.get(i), restList);
			}
			macroEnv.define(macro.rest().name(), restList);
		}
		LispVal expansion = LispNil.INSTANCE;
		for (LispVal bodyExpr : macro.body()) {
			expansion = eval(bodyExpr, macroEnv);
		}
		return expansion;
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
		LispSymbol setfPlace = LispMacroExpander.setfFunctionPlaceName(designator);
		if (setfPlace != null) {
			return resolveFunction(LispMacroExpander.setfFunctionName(setfPlace.name()));
		}
		if (designator instanceof LispSymbol sym) {
			return resolveFunction(sym.name());
		}
		throw new LispEvalException(
				LispNames.FUNCTION + " expects a function name or lambda expression, got " + designator.print());
	}

	private static void requireSingleArg(String name, List<LispVal> args) {
		if (args.size() != 1) {
			throw new LispEvalException(name + " expects 1 argument, got " + args.size());
		}
	}

	/**
	 * Resolves a function designator name against the global function namespace.
	 * @param name the function name
	 * @return the function value
	 * @throws LispEvalException if the name is a special operator or macro, or undefined
	 */
	private LispVal resolveFunction(String name) {
		if (SPECIAL_OPERATORS.contains(name) || this.userMacros.containsKey(name)) {
			throw new LispEvalException(name + " is a macro or special operator, not a function");
		}
		LispVal fn = this.globalEnv.lookupFunctionOrNull(name);
		if (fn != null) {
			return fn;
		}
		// The linalg package is a Lisp-source library (linalg.lisp): evaluate its
		// definitions into the global environment the first time a linalg:-qualified
		// function is resolved, then retry the lookup.
		if (!this.linalgLibraryLoaded && LinalgLibrary.isLinalgQualified(name)) {
			this.linalgLibraryLoaded = true;
			for (LispVal form : LinalgLibrary.forms()) {
				eval(form, this.globalEnv);
			}
			LispVal loaded = this.globalEnv.lookupFunctionOrNull(name);
			if (loaded != null) {
				return loaded;
			}
		}
		// The URL library (url.lisp) loads the same way on the first resolution of one
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
		if (LispMacroExpander.isCarCdrComposition(name)) {
			// Synthesize (lambda (x) (cadr x)) so car/cdr compositions are first-class.
			LispSymbol param = new LispSymbol("x");
			LispVal call = new LispCons(new LispSymbol(name), new LispCons(param, LispNil.INSTANCE));
			return new LispLambda(List.of(param), List.of(call), this.globalEnv);
		}
		throw new LispEvalException("The function " + name + " is undefined");
	}

	private LispVal evalQuote(LispCons cons) {
		LispCons rest = (LispCons) cons.cdr();
		return rest.car();
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
		if (bindings instanceof LispCons bindingsCons) {
			List<LispVal> bindingList = bindingsCons.toList();
			if (this.specialVars.isEmpty()) {
				// No name can be special: bind every init lexically. let is parallel, so
				// each init is evaluated in the OUTER env before the binding takes
				// effect.
				for (LispVal binding : bindingList) {
					List<LispVal> pair = ((LispCons) binding).toList();
					letEnv.define(((LispSymbol) pair.get(0)).name(), eval(pair.get(1), env));
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
				}
				for (int i = 0; i < n; i++) {
					if (this.specialVars.contains(names[i])) {
						if (dynamicNames == null) {
							dynamicNames = new java.util.ArrayList<>(2);
						}
						this.dynamicBindings.push(names[i], vals[i]);
						dynamicNames.add(names[i]);
					}
					else {
						letEnv.define(names[i], vals[i]);
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
		}
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

	/** Evaluates the optional value of a {@code return} form, defaulting to nil. */
	private LispVal evalReturnValue(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		return parts.size() > 1 ? eval(parts.get(1), env) : LispNil.INSTANCE;
	}

	private LispVal evalLambdaForm(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LambdaLists.Expanded expanded = LambdaLists.expand(parts.get(1), parts.subList(2, parts.size()));
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

	// mapcar over one or more lists in parallel, stopping at the shortest list
	// (Common Lisp semantics).
	private LispVal mapValues(LispVal function, List<LispVal> lists) {
		List<LispVal> cursors = new ArrayList<>(lists);
		List<LispVal> results = new ArrayList<>();
		while (true) {
			List<LispVal> callArgs = new ArrayList<>(cursors.size());
			boolean exhausted = false;
			for (int i = 0; i < cursors.size(); i++) {
				if (cursors.get(i) instanceof LispCons cell) {
					callArgs.add(cell.car());
					cursors.set(i, cell.cdr());
				}
				else {
					exhausted = true;
					break;
				}
			}
			if (exhausted) {
				break;
			}
			results.add(apply(function, callArgs, this.globalEnv));
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = results.size() - 1; i >= 0; i--) {
			result = new LispCons(results.get(i), result);
		}
		return result;
	}

	// Apply the function to each element for its side effects and return the
	// original list (Common Lisp mapc semantics).
	private LispVal mapForEffect(LispVal function, LispVal list) {
		LispVal cursor = list;
		while (cursor instanceof LispCons cell) {
			apply(function, List.of(cell.car()), this.globalEnv);
			cursor = cell.cdr();
		}
		return list;
	}

	// Return t when the predicate is non-nil for every element, nil at the first failure
	// (Common Lisp every semantics, single-list form).
	private LispVal everyValues(LispVal predicate, LispVal list) {
		while (list instanceof LispCons cell) {
			if (!isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv))) {
				return LispNil.INSTANCE;
			}
			list = cell.cdr();
		}
		return LispTrue.INSTANCE;
	}

	// Return the first non-nil predicate result, or nil when every element fails
	// (Common Lisp some semantics, single-list form).
	private LispVal someValues(LispVal predicate, LispVal list) {
		while (list instanceof LispCons cell) {
			LispVal result = apply(predicate, List.of(cell.car()), this.globalEnv);
			if (isTruthy(result)) {
				return result;
			}
			list = cell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// Return the first element for which the predicate is true, or nil
	// (Common Lisp find-if semantics, single-list form).
	private LispVal findIfValues(LispVal predicate, LispVal list) {
		while (list instanceof LispCons cell) {
			if (isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv))) {
				return cell.car();
			}
			list = cell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// Return the first element for which the predicate is false, or nil
	// (Common Lisp find-if-not semantics, single-list form; the complement of find-if).
	private LispVal findIfNotValues(LispVal predicate, LispVal list) {
		while (list instanceof LispCons cell) {
			if (!isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv))) {
				return cell.car();
			}
			list = cell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// Return the 0-based index of the first element satisfying the predicate
	// (Common Lisp position-if), or nil. Like position but tests with the predicate.
	/** The matching flavor of a runtime {@code position}-family scan. */
	private enum PositionScanMode {

		/**
		 * {@code position}: the first argument is an item compared by :test/:test-not.
		 */
		ITEM,
		/** {@code position-if}: the first argument is a predicate. */
		PREDICATE,
		/** {@code position-if-not}: the first argument is a negated predicate. */
		PREDICATE_NOT

	}

	// The runtime counterpart of LispMacroExpander.buildPositionScan for first-class
	// use: a forward scan honoring :start/:end, where a :from-end match records the
	// index and keeps scanning (the last match wins). :test/:test-not apply only to
	// position (ITEM mode); a nil keyword value counts as absent, like the expansion.
	private LispVal positionScanValues(String opName, List<LispVal> args, PositionScanMode mode) {
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
					if (!fromEnd) {
						return new LispInteger(index);
					}
					found = new LispInteger(index);
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

	// Return a fresh list of the elements that are kept (Common Lisp
	// remove-if/remove-if-not semantics, no keyword arguments). When keepWhenTrue is
	// false (remove-if) elements failing the predicate are kept; when true
	// (remove-if-not) elements satisfying the predicate are kept.
	private LispVal removeIfValues(LispVal predicate, LispVal list, boolean keepWhenTrue) {
		List<LispVal> kept = new ArrayList<>();
		LispVal cursor = list;
		while (cursor instanceof LispCons cell) {
			if (isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv)) == keepWhenTrue) {
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

	// Apply the function to each element and concatenate the resulting lists (Common Lisp
	// mapcan semantics; the concatenation is non-destructive append rather than nconc).
	private LispVal mapcanValues(LispVal function, LispVal list) {
		List<LispVal> pieces = new ArrayList<>();
		while (list instanceof LispCons cell) {
			pieces.add(apply(function, List.of(cell.car()), this.globalEnv));
			list = cell.cdr();
		}
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

	// Resolves a promise to its value on the calling thread: a root promise joins its
	// future, a chained promise (rontolisp:then) resolves its base, applies the callback
	// and memoizes the result (flattening a promise-returning callback), and a
	// non-promise value passes through unchanged, like JavaScript await. A failed root
	// promise (e.g. a refused connection) signals here -- the same timing as a
	// JavaScript await rejection; the failure skips any chained callbacks.
	private LispVal awaitValue(LispVal v) {
		if (!(v instanceof LispPromise promise)) {
			return v;
		}
		java.util.concurrent.CompletableFuture<LispVal> future = promise.future();
		if (future != null) {
			try {
				return future.join();
			}
			catch (java.util.concurrent.CompletionException ex) {
				Throwable cause = java.util.Objects.requireNonNullElse(ex.getCause(), ex);
				throw new LispEvalException(java.util.Objects.requireNonNullElse(cause.getMessage(), "await failed"));
			}
		}
		if (promise.isSettled()) {
			return promise.settledValue();
		}
		LispVal base = awaitValue(promise.base());
		LispVal resolved = awaitValue(apply(promise.fn(), List.of(base), this.globalEnv));
		promise.settle(resolved);
		return resolved;
	}

	// Adapts one incoming HTTP request to the Lisp handler: builds the request property
	// list, applies the handler and reads the response property list back.
	private HttpHandlerSupport.Response invokeHttpHandler(LispVal handler, HttpHandlerSupport.Request request) {
		LispVal headers = LispNil.INSTANCE;
		List<HttpHandlerSupport.Header> requestHeaders = request.headers();
		for (int i = requestHeaders.size() - 1; i >= 0; i--) {
			HttpHandlerSupport.Header header = requestHeaders.get(i);
			headers = new LispCons(new LispCons(new LispString(header.name()), new LispString(header.value())),
					headers);
		}
		LispVal query = request.query() == null ? LispNil.INSTANCE : new LispString(request.query());
		LispVal requestPlist = plist(new LispSymbol(":method"), new LispString(request.method()),
				new LispSymbol(":path"), new LispString(request.path()), new LispSymbol(":query"), query,
				new LispSymbol(":headers"), headers, new LispSymbol(":body"), new LispString(request.body()));
		LispVal result = apply(handler, List.of(requestPlist), this.globalEnv);
		int status = 200;
		if (httpPlistGet(result, ":status") instanceof LispInteger statusVal) {
			status = (int) statusVal.value();
		}
		String body = "";
		LispVal bodyVal = httpPlistGet(result, ":body");
		if (bodyVal instanceof LispString bodyStr) {
			body = bodyStr.value();
		}
		List<HttpHandlerSupport.Header> responseHeaders = new ArrayList<>();
		LispVal headerAlist = httpPlistGet(result, ":headers");
		while (headerAlist instanceof LispCons cons) {
			if (cons.car() instanceof LispCons pair && pair.car() instanceof LispString name
					&& pair.cdr() instanceof LispString value) {
				responseHeaders.add(new HttpHandlerSupport.Header(name.value(), value.value()));
			}
			headerAlist = cons.cdr();
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
		if (!this.jsonLibraryLoaded) {
			this.jsonLibraryLoaded = true;
			for (LispVal form : JsonLibrary.forms()) {
				eval(form, this.globalEnv);
			}
		}
		return apply(resolveFunction(helperName), args, this.globalEnv);
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
			LispVal result = LispNil.INSTANCE;
			for (LispVal bodyExpr : lambda.body()) {
				result = eval(bodyExpr, lambdaEnv);
			}
			return result;
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
