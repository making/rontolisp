package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

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
	 * {@code defstruct} accessor names to their 1-based slot position, accumulated by
	 * {@link LispMacroExpander#expandDefstruct} so {@code setf} can treat accessor calls
	 * as places. Kept per evaluator, like the user macro table.
	 */
	private final java.util.Map<String, Integer> structAccessors = new java.util.HashMap<>();

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
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.MAPCAR + " expects 2 arguments, got " + args.size());
			}
			requireList(LispNames.MAPCAR, args.get(1));
			return mapValues(args.get(0), args.get(1));
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
			// (reduce fn list) or (reduce fn list :initial-value init)
			if (args.size() == 2) {
				LispVal list = args.get(1);
				if (!(list instanceof LispCons first)) {
					throw new LispEvalException("reduce requires a non-empty list when no initial value is provided");
				}
				return reduceValues(args.get(0), first.car(), first.cdr());
			}
			if (args.size() == 4 && args.get(2) instanceof LispSymbol kw
					&& LispNames.INITIAL_VALUE_KEYWORD.equals(kw.name())) {
				return reduceValues(args.get(0), args.get(3), args.get(1));
			}
			throw new LispEvalException(
					LispNames.REDUCE + " expects (reduce fn list) or (reduce fn list :initial-value init)");
		}));
		this.globalEnv.defineFunction(LispNames.EVERY, new LispFunction(LispNames.EVERY, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.EVERY + " expects 2 arguments, got " + args.size());
			}
			return everyValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.SOME, new LispFunction(LispNames.SOME, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.SOME + " expects 2 arguments, got " + args.size());
			}
			return someValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.FIND_IF, new LispFunction(LispNames.FIND_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.FIND_IF + " expects 2 arguments, got " + args.size());
			}
			return findIfValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.FIND_IF_NOT, new LispFunction(LispNames.FIND_IF_NOT, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.FIND_IF_NOT + " expects 2 arguments, got " + args.size());
			}
			return findIfNotValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.POSITION_IF, new LispFunction(LispNames.POSITION_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.POSITION_IF + " expects 2 arguments, got " + args.size());
			}
			return positionIfValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.COUNT_IF, new LispFunction(LispNames.COUNT_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.COUNT_IF + " expects 2 arguments, got " + args.size());
			}
			return countIfValues(args.get(0), args.get(1));
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
			// as (funcall fn item element). The default eql designator keeps the historic
			// behavior of the eql-based scan.
			LispVal test = keywordArg(args, 2, LispNames.TEST_KEYWORD, new LispSymbol(LispNames.EQL));
			LispVal item = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (isTruthy(apply(test, List.of(item, cell.car()), this.globalEnv))) {
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
			// as (funcall fn key (car pair)), mirroring member.
			LispVal test = keywordArg(args, 2, LispNames.TEST_KEYWORD, new LispSymbol(LispNames.EQL));
			LispVal key = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (cell.car() instanceof LispCons pair
						&& isTruthy(apply(test, List.of(key, pair.car()), this.globalEnv))) {
					return pair;
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
			LispVal test = keywordArg(args, 2, LispNames.TEST_KEYWORD, new LispSymbol(LispNames.EQL));
			LispVal value = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (cell.car() instanceof LispCons pair
						&& isTruthy(apply(test, List.of(value, pair.cdr()), this.globalEnv))) {
					return pair;
				}
				cur = cell.cdr();
			}
			return LispNil.INSTANCE;
		}));
		this.globalEnv.defineFunction(LispNames.REMOVE_IF, new LispFunction(LispNames.REMOVE_IF, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.REMOVE_IF + " expects 2 arguments, got " + args.size());
			}
			return removeIfValues(args.get(0), args.get(1), false);
		}));
		this.globalEnv.defineFunction(LispNames.REMOVE_IF_NOT, new LispFunction(LispNames.REMOVE_IF_NOT, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.REMOVE_IF_NOT + " expects 2 arguments, got " + args.size());
			}
			return removeIfValues(args.get(0), args.get(1), true);
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
			return sortValues(args.get(0), args.get(1));
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
		try {
			for (LispVal form : LispReader.readAllFromString(source)) {
				eval(form);
			}
		}
		finally {
			this.loadDirStack.removeLast();
		}
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
			case LispSymbol sym -> sym.isKeyword() ? sym : env.lookup(sym.name());
			case LispCons cons -> evalCons(cons, env);
		};
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
				case LispNames.DEFUN:
					return evalDefun(cons, env);
				case LispNames.DEFMACRO:
					return evalDefmacro(cons, env);
				case LispNames.DEFSTRUCT:
					return evalDefstruct(cons, env);
				case LispNames.DEFVAR:
					return evalDefvar(cons, env, false);
				case LispNames.DEFPARAMETER:
				case LispNames.DEFCONSTANT:
					return evalDefvar(cons, env, true);
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
					return eval(LispMacroExpander.expandSetf(cons, this.structAccessors), env);
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
				case LispNames.READ_SEQUENCE:
					return eval(LispMacroExpander.expandReadSequence(cons), env);
				case LispNames.WRITE_SEQUENCE:
					return eval(LispMacroExpander.expandWriteSequence(cons), env);
				case LispNames.PROG2:
					return eval(LispMacroExpander.expandProg2(cons), env);
				case LispNames.PSETQ:
					return eval(LispMacroExpander.expandPsetq(cons), env);
				case LispNames.TYPECASE:
					return eval(LispMacroExpander.expandTypecase(cons), env);
				case LispNames.ETYPECASE:
					return eval(LispMacroExpander.expandEtypecase(cons), env);
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
				case LispNames.RASSOC:
					return eval(LispMacroExpander.expandRassoc(cons), env);
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
				case LispNames.NOTANY:
					return eval(LispMacroExpander.expandNotany(cons), env);
				case LispNames.NOTEVERY:
					return eval(LispMacroExpander.expandNotevery(cons), env);
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
		LispSymbol name = (LispSymbol) parts.get(1);
		LambdaLists.Expanded expanded = LambdaLists.expand(parts.get(2), parts.subList(3, parts.size()));
		// defun installs into the global function namespace, capturing the current
		// lexical environment, and returns the function name like Common Lisp.
		this.globalEnv.defineFunction(name.name(),
				new LispLambda(expanded.required(), expanded.rest(), expanded.body(), env));
		return name;
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

	private LispVal evalDefmacro(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3 || !(parts.get(1) instanceof LispSymbol name)) {
			throw new LispEvalException(LispNames.DEFMACRO + " expects (defmacro name (params...) body...)");
		}
		if (PackageRegistry.isClSymbol(name.name())) {
			throw new LispEvalException(LispNames.DEFMACRO + " cannot redefine the standard operator " + name.name());
		}
		List<LispSymbol> required = new ArrayList<>();
		LispSymbol rest = null;
		if (parts.get(2) instanceof LispCons paramCons) {
			List<LispVal> paramList = paramCons.toList();
			for (int i = 0; i < paramList.size(); i++) {
				if (!(paramList.get(i) instanceof LispSymbol param)) {
					throw new LispEvalException(
							LispNames.DEFMACRO + " parameter must be a symbol: " + paramList.get(i).print());
				}
				if (LispNames.LAMBDA_REST.equals(param.name()) || LispNames.LAMBDA_BODY.equals(param.name())) {
					if (i + 2 != paramList.size() || !(paramList.get(i + 1) instanceof LispSymbol restParam)) {
						throw new LispEvalException(LispNames.DEFMACRO + ": " + param.name()
								+ " must be followed by exactly one parameter symbol");
					}
					rest = restParam;
					break;
				}
				if (param.name().startsWith("&")) {
					throw new LispEvalException(LispNames.DEFMACRO + " supports required parameters and &rest/&body"
							+ " only, got " + param.name());
				}
				required.add(param);
			}
		}
		this.userMacros.put(name.name(), new UserMacro(required, rest, parts.subList(3, parts.size()), env));
		return name;
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
		// defvar is idempotent (Common Lisp semantics): the initial value form is
		// evaluated and bound in the global environment only if the variable is not
		// already bound. (defvar name) with no value leaves it unbound. defparameter and
		// defconstant pass force=true and always (re)assign the initial value. Returns
		// the
		// variable name like Common Lisp.
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
		if (designator instanceof LispSymbol sym) {
			return resolveFunction(sym.name());
		}
		throw new LispEvalException(
				LispNames.FUNCTION + " expects a function name or lambda expression, got " + designator.print());
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
		// parts.get(1) is the bindings list: ((x 1) (y 2))
		LispVal bindings = parts.get(1);
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons bindPair = (LispCons) binding;
				List<LispVal> pair = bindPair.toList();
				String name = ((LispSymbol) pair.get(0)).name();
				LispVal value = eval(pair.get(1), env);
				letEnv.define(name, value);
			}
		}
		// Evaluate body expressions
		LispVal result = LispNil.INSTANCE;
		for (int i = 2; i < parts.size(); i++) {
			result = eval(parts.get(i), letEnv);
		}
		return result;
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
			env.set(name.name(), value);
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

	private LispVal mapValues(LispVal function, LispVal list) {
		List<LispVal> results = new ArrayList<>();
		while (list instanceof LispCons cell) {
			LispVal mapped = apply(function, List.of(cell.car()), this.globalEnv);
			results.add(mapped);
			list = cell.cdr();
		}
		if (results.isEmpty()) {
			return LispNil.INSTANCE;
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
	private LispVal positionIfValues(LispVal predicate, LispVal list) {
		long index = 0;
		while (list instanceof LispCons cell) {
			if (isTruthy(apply(predicate, List.of(cell.car()), this.globalEnv))) {
				return new LispInteger(index);
			}
			index++;
			list = cell.cdr();
		}
		return LispNil.INSTANCE;
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
		for (int i = start; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && keyword.equals(kw.name())) {
				return args.get(i + 1);
			}
		}
		return fallback;
	}

	private boolean isTruthy(LispVal val) {
		return !(val instanceof LispNil);
	}

}
