package am.ik.rontolisp.eval;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispPromise;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageIntrospection;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.Scope;
import am.ik.rontolisp.VersionInfo;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * Lexical environment for bindings. Following the Lisp-2 model of Common Lisp, variables
 * and functions live in separate namespaces: {@link #lookup(String)} resolves the
 * variable namespace, {@link #lookupFunction(String)} the function namespace.
 */
public final class Environment implements Scope {

	private final Map<String, LispVal> bindings;

	private final Map<String, LispVal> functions;

	@Nullable private final Environment parent;

	/**
	 * Create a new environment with the given parent scope.
	 * @param parent the parent environment, or {@code null} for a top-level scope
	 */
	public Environment(@Nullable Environment parent) {
		this.bindings = new HashMap<>();
		this.functions = new HashMap<>();
		this.parent = parent;
	}

	@Override
	public LispVal lookup(String name) {
		LispVal val = this.bindings.get(name);
		if (val != null) {
			return val;
		}
		if (this.parent != null) {
			return this.parent.lookup(name);
		}
		throw new LispEvalException("The variable " + name + " is unbound");
	}

	/**
	 * Look up a name in the function namespace, searching up the scope chain.
	 * @param name the function name
	 * @return the function value
	 * @throws LispEvalException if the function is undefined
	 */
	public LispVal lookupFunction(String name) {
		LispVal val = lookupFunctionOrNull(name);
		if (val == null) {
			throw new LispEvalException("The function " + name + " is undefined");
		}
		return val;
	}

	/**
	 * Look up a name in the function namespace, searching up the scope chain.
	 * @param name the function name
	 * @return the function value, or {@code null} if undefined
	 */
	public @Nullable LispVal lookupFunctionOrNull(String name) {
		LispVal val = this.functions.get(name);
		if (val != null) {
			return val;
		}
		if (this.parent != null) {
			return this.parent.lookupFunctionOrNull(name);
		}
		return null;
	}

	/**
	 * Define a binding in the function namespace of this environment.
	 * @param name the function name
	 * @param value the function value
	 */
	public void defineFunction(String name, LispVal value) {
		this.functions.put(name, value);
	}

	/**
	 * Returns the names bound in the function namespace of this environment (not
	 * including parent scopes).
	 * @return a snapshot of the function names
	 */
	public Set<String> globalFunctionNames() {
		return Set.copyOf(this.functions.keySet());
	}

	/**
	 * Define a new binding in this environment.
	 * @param name the variable name
	 * @param value the value to bind
	 */
	public void define(String name, LispVal value) {
		this.bindings.put(name, value);
	}

	/**
	 * Returns whether a variable is bound in this environment (not including parent
	 * scopes). Used by {@code defvar} to decide whether to assign the initial value.
	 * @param name the variable name
	 * @return {@code true} if the name is bound in this environment
	 */
	public boolean isBound(String name) {
		return this.bindings.containsKey(name);
	}

	/**
	 * Set an existing binding, searching up the scope chain.
	 * @param name the variable name
	 * @param value the new value
	 */
	public void set(String name, LispVal value) {
		if (this.bindings.containsKey(name)) {
			this.bindings.put(name, value);
			return;
		}
		if (this.parent != null) {
			this.parent.set(name, value);
			return;
		}
		// If not found anywhere, define in current scope
		this.bindings.put(name, value);
	}

	/**
	 * Create the global environment with all built-in functions.
	 * @param out the output stream for print operations
	 * @return the global environment
	 */
	public static Environment createGlobal(PrintStream out) {
		return createGlobal(out, InputStream.nullInputStream());
	}

	/**
	 * Create the global environment with all built-in functions.
	 * @param out the output stream for print operations
	 * @param in the input stream for read operations
	 * @return the global environment
	 */
	public static Environment createGlobal(PrintStream out, InputStream in) {
		Environment env = new Environment(null);
		registerArithmetic(env);
		registerMath(env);
		registerComparison(env);
		registerIO(env, out, in);
		registerPredicates(env);
		registerListOps(env);
		registerSequenceOps(env);
		registerStringOps(env);
		registerCharacters(env);
		registerTypeConversion(env);
		registerHashTables(env);
		registerArrays(env);
		registerPackages(env);
		return env;
	}

	private static void registerHashTables(Environment env) {
		env.defineFunction(LispNames.MAKE_HASH_TABLE, new LispFunction(LispNames.MAKE_HASH_TABLE, args -> {
			// Accepts :test (eql/equal) and ignores other keywords such as :size. Lookup
			// is
			// always structural, so :test is informational only (see LispHashTable).
			boolean equalTest = false;
			for (int i = 0; i + 1 < args.size(); i += 2) {
				if (args.get(i) instanceof LispSymbol kw && LispNames.TEST_KEYWORD.equals(kw.name())) {
					String testName = switch (args.get(i + 1)) {
						case LispSymbol s -> s.name();
						case LispFunction f -> f.name();
						default -> "";
					};
					equalTest = "equal".equals(testName) || "equalp".equals(testName);
				}
			}
			return new LispHashTable(equalTest);
		}));
		env.defineFunction(LispNames.GETHASH, new LispFunction(LispNames.GETHASH, args -> {
			if (args.size() != 2 && args.size() != 3) {
				throw new LispEvalException(LispNames.GETHASH + " expects 2 or 3 arguments, got " + args.size());
			}
			LispHashTable table = requireHashTable(LispNames.GETHASH, args.get(1));
			LispVal dflt = (args.size() == 3) ? args.get(2) : LispNil.INSTANCE;
			return table.get(args.get(0), dflt);
		}));
		env.defineFunction(LispNames.PUTHASH, new LispFunction(LispNames.PUTHASH, args -> {
			requireArgCount(LispNames.PUTHASH, args, 3);
			LispHashTable table = requireHashTable(LispNames.PUTHASH, args.get(1));
			return table.put(args.get(0), args.get(2));
		}));
		env.defineFunction(LispNames.REMHASH, new LispFunction(LispNames.REMHASH, args -> {
			requireArgCount(LispNames.REMHASH, args, 2);
			LispHashTable table = requireHashTable(LispNames.REMHASH, args.get(1));
			return table.remove(args.get(0)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.CLRHASH, new LispFunction(LispNames.CLRHASH, args -> {
			requireArgCount(LispNames.CLRHASH, args, 1);
			LispHashTable table = requireHashTable(LispNames.CLRHASH, args.get(0));
			table.clear();
			return table;
		}));
		env.defineFunction(LispNames.HASH_TABLE_COUNT, new LispFunction(LispNames.HASH_TABLE_COUNT, args -> {
			requireArgCount(LispNames.HASH_TABLE_COUNT, args, 1);
			return new LispInteger(requireHashTable(LispNames.HASH_TABLE_COUNT, args.get(0)).count());
		}));
		env.defineFunction(LispNames.HASH_TABLE_P, new LispFunction(LispNames.HASH_TABLE_P, args -> {
			requireArgCount(LispNames.HASH_TABLE_P, args, 1);
			return (args.get(0) instanceof LispHashTable) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static void registerArrays(Environment env) {
		env.defineFunction(LispNames.MAKE_ARRAY, new LispFunction(LispNames.MAKE_ARRAY, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.MAKE_ARRAY + " expects at least 1 argument");
			}
			LispVal init = LispNil.INSTANCE;
			for (int i = 1; i + 1 < args.size(); i += 2) {
				if (args.get(i) instanceof LispSymbol kw && LispNames.INITIAL_ELEMENT_KEYWORD.equals(kw.name())) {
					init = args.get(i + 1);
				}
			}
			int[] dims = parseDimensions(args.get(0));
			int total = 1;
			for (int d : dims) {
				total *= d;
			}
			LispVal[] data = new LispVal[total];
			for (int i = 0; i < total; i++) {
				data[i] = init;
			}
			return new LispArray(dims, data);
		}));
		env.defineFunction(LispNames.AREF, new LispFunction(LispNames.AREF, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.AREF + " expects an array and subscripts");
			}
			LispArray array = requireArray(LispNames.AREF, args.get(0));
			int[] subs = new int[args.size() - 1];
			for (int i = 1; i < args.size(); i++) {
				subs[i - 1] = (int) asLong(args.get(i));
			}
			return array.aref(subs);
		}));
		env.defineFunction(LispNames.ARRAY_DIMENSIONS, new LispFunction(LispNames.ARRAY_DIMENSIONS, args -> {
			requireArgCount(LispNames.ARRAY_DIMENSIONS, args, 1);
			LispArray array = requireArray(LispNames.ARRAY_DIMENSIONS, args.get(0));
			LispVal dims = LispNil.INSTANCE;
			int[] sizes = array.dimensions();
			for (int i = sizes.length - 1; i >= 0; i--) {
				dims = new LispCons(new LispInteger(sizes[i]), dims);
			}
			return dims;
		}));
		env.defineFunction(LispNames.ASET, new LispFunction(LispNames.ASET, args -> {
			// (%aset array subscript... value)
			if (args.size() < 3) {
				throw new LispEvalException(LispNames.ASET + " expects an array, subscripts and a value");
			}
			LispArray array = requireArray(LispNames.ASET, args.get(0));
			LispVal value = args.get(args.size() - 1);
			int[] subs = new int[args.size() - 2];
			for (int i = 1; i < args.size() - 1; i++) {
				subs[i - 1] = (int) asLong(args.get(i));
			}
			array.aset(value, subs);
			return value;
		}));
	}

	// Parses a make-array dimensions argument (an integer for rank 1, or a list of 1 or 2
	// integers) into a dimension-size array. Only rank 1 and 2 are supported.
	private static int[] parseDimensions(LispVal dimsVal) {
		if (dimsVal instanceof LispInteger n) {
			return new int[] { (int) n.value() };
		}
		if (dimsVal instanceof LispCons || dimsVal instanceof LispNil) {
			List<LispVal> list = (dimsVal instanceof LispCons cons) ? cons.toList() : List.of();
			if (list.isEmpty() || list.size() > 2) {
				throw new LispEvalException(
						LispNames.MAKE_ARRAY + " supports rank 1 and 2 only, got rank " + list.size());
			}
			int[] dims = new int[list.size()];
			for (int i = 0; i < list.size(); i++) {
				dims[i] = (int) asLong(list.get(i));
			}
			return dims;
		}
		throw new LispEvalException(
				LispNames.MAKE_ARRAY + " expects an integer or list of dimensions, got " + dimsVal.print());
	}

	private static LispArray requireArray(String fn, LispVal val) {
		if (val instanceof LispArray array) {
			return array;
		}
		throw new LispEvalException(fn + " expects an array, got " + val.print());
	}

	private static LispHashTable requireHashTable(String fn, LispVal val) {
		if (val instanceof LispHashTable table) {
			return table;
		}
		throw new LispEvalException(fn + " expects a hash table, got " + val.print());
	}

	private static void registerPackages(Environment env) {
		// The version function belongs to the rontolisp package. It is registered under
		// its
		// canonical qualified name so that PackageResolver output resolves to it
		// directly,
		// and it is NOT visible unqualified in the cl-user package.
		String versionName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.VERSION);
		env.defineFunction(versionName, new LispFunction(versionName, args -> {
			if (!args.isEmpty()) {
				throw new LispEvalException(LispNames.VERSION + " expects no arguments, got " + args.size());
			}
			return VersionInfo.plist();
		}));
		// The introspection functions list the symbols of a package by category. The
		// cl listings come from the categorized sets in PackageRegistry; the cl-user
		// function listing reflects the live global function namespace.
		registerIntrospection(env, LispNames.LIST_FUNCTIONS);
		registerIntrospection(env, LispNames.LIST_MACROS);
		registerIntrospection(env, LispNames.LIST_SPECIAL_FORMS);
		// rontolisp:wasm-export marks a function for direct WASM export (see the WASM
		// compiler). It is a compile-time directive for that backend only; on the
		// interpreter (and the JVM backend) it is a no-op that simply returns the named
		// symbol, so the same source runs unchanged on every backend.
		String exportName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WASM_EXPORT);
		env.defineFunction(exportName,
				new LispFunction(exportName, args -> args.isEmpty() ? LispNil.INSTANCE : args.get(0)));
		// rontolisp:wasm-import declares a host function imported into a compiled WASM
		// module (see the WASM compiler). The host does not exist on the interpreter
		// (or the JVM backend), so the directive defines a stub under the declared name
		// that signals an error when called -- the same source still loads on every
		// backend, and only actually calling the import is WASM-specific.
		String importDirective = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WASM_IMPORT);
		env.defineFunction(importDirective, new LispFunction(importDirective, args -> {
			if (args.isEmpty() || !(args.get(0) instanceof LispSymbol target)) {
				throw new LispEvalException(importDirective + " expects a quoted function name, got: "
						+ (args.isEmpty() ? "nothing" : args.get(0).print()));
			}
			env.defineFunction(target.name(), new LispFunction(target.name(), stubArgs -> {
				throw new LispEvalException(target.name() + " is a host function declared by " + importDirective
						+ "; it can only be called from a compiled WASM module");
			}));
			return target;
		}));
		// fetch starts an outgoing HTTP request, JavaScript fetch-style, and immediately
		// returns a promise (a LispPromise wrapping the future, which settles with the
		// result property list) while the request runs on a background thread. It
		// belongs to the rontolisp package (it is not a Common Lisp standard function).
		// The optional second argument is an options property list (:method, :headers,
		// :body); the options are validated eagerly (like JavaScript fetch, which throws
		// synchronously on invalid arguments). The supported methods are GET, HEAD, POST,
		// PUT, DELETE, OPTIONS and PATCH; :body is the request body string (e.g. for
		// POST/PUT).
		String fetchName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH);
		env.defineFunction(fetchName, new LispFunction(fetchName, args -> {
			if (args.isEmpty() || args.size() > 2) {
				throw new LispEvalException(LispNames.FETCH + " expects 1 or 2 arguments, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString url)) {
				throw new LispEvalException(LispNames.FETCH + " expects a string URL, got: " + args.get(0).print());
			}
			LispVal options = args.size() == 2 ? args.get(1) : LispNil.INSTANCE;
			String method = fetchMethod(options);
			List<HttpSupport.Header> requestHeaders = parseHeaderAlist(plistGet(options, ":headers"));
			String body = fetchBody(options);
			return LispPromise.root(HttpSupport.requestAsync(method, url.value(), requestHeaders, body)
				.thenApply(result -> fetchList(new LispSymbol(":status"), new LispInteger(result.status()),
						new LispSymbol(":body"), new LispString(result.body()), new LispSymbol(":headers"),
						buildHeaderAlist(result.headers()))));
		}));
		// then derives a new promise from a value (usually a promise): awaiting the
		// derived promise awaits the base and applies the callback to its value. The
		// callback runs lazily at first await (memoized), which is the timing all three
		// backends implement identically. await itself is registered by the evaluator
		// because resolving a chain applies the callback.
		String thenName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.THEN);
		env.defineFunction(thenName, new LispFunction(thenName, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.THEN + " expects 2 arguments, got " + args.size());
			}
			return LispPromise.chain(args.get(0), args.get(1));
		}));
		// promisep tests whether a value is a promise.
		String promisepName = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.PROMISEP);
		env.defineFunction(promisepName, new LispFunction(promisepName, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.PROMISEP + " expects 1 argument, got " + args.size());
			}
			return (args.get(0) instanceof LispPromise) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static LispVal fetchList(LispVal... elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			result = new LispCons(elements[i], result);
		}
		return result;
	}

	// Returns the value of key in a property list (k1 v1 k2 v2 ...), or nil if absent.
	private static LispVal plistGet(LispVal plist, String key) {
		LispVal current = plist;
		while (current instanceof LispCons cons && cons.cdr() instanceof LispCons valueCell) {
			if (cons.car() instanceof LispSymbol sym && sym.name().equals(key)) {
				return valueCell.car();
			}
			current = valueCell.cdr();
		}
		return LispNil.INSTANCE;
	}

	// The HTTP methods rontolisp:fetch supports, in canonical (upper-case) form.
	private static final List<String> SUPPORTED_METHODS = List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS",
			"PATCH");

	// Resolves the :method option (default GET), normalizing it to upper case. Only the
	// methods in SUPPORTED_METHODS are accepted.
	private static String fetchMethod(LispVal options) {
		LispVal methodVal = plistGet(options, ":method");
		String method;
		if (methodVal instanceof LispNil) {
			return "GET";
		}
		else if (methodVal instanceof LispString str) {
			method = str.value();
		}
		else {
			throw new LispEvalException(LispNames.FETCH + " :method must be a string, got: " + methodVal.print());
		}
		String canonical = method.toUpperCase(Locale.ROOT);
		if (!SUPPORTED_METHODS.contains(canonical)) {
			throw new LispEvalException(LispNames.FETCH + ": unsupported method: " + method + " (supported: "
					+ String.join(", ", SUPPORTED_METHODS) + ")");
		}
		return canonical;
	}

	// Resolves the :body option (default none). Must be a string when present.
	private static @Nullable String fetchBody(LispVal options) {
		LispVal bodyVal = plistGet(options, ":body");
		if (bodyVal instanceof LispNil) {
			return null;
		}
		if (bodyVal instanceof LispString str) {
			return str.value();
		}
		throw new LispEvalException(LispNames.FETCH + " :body must be a string, got: " + bodyVal.print());
	}

	private static List<HttpSupport.Header> parseHeaderAlist(LispVal headers) {
		List<HttpSupport.Header> result = new ArrayList<>();
		LispVal current = headers;
		while (current instanceof LispCons cons) {
			if (!(cons.car() instanceof LispCons pair) || !(pair.car() instanceof LispString name)
					|| !(pair.cdr() instanceof LispString value)) {
				throw new LispEvalException(
						LispNames.FETCH + " :headers must be an alist of (name . value) string pairs");
			}
			result.add(new HttpSupport.Header(name.value(), value.value()));
			current = cons.cdr();
		}
		return result;
	}

	private static LispVal buildHeaderAlist(List<HttpSupport.Header> headers) {
		LispVal result = LispNil.INSTANCE;
		for (int i = headers.size() - 1; i >= 0; i--) {
			HttpSupport.Header header = headers.get(i);
			result = new LispCons(new LispCons(new LispString(header.name()), new LispString(header.value())), result);
		}
		return result;
	}

	private static void registerIntrospection(Environment env, String member) {
		String qualified = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, member);
		env.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() > 1) {
				throw new LispEvalException(
						member + " expects at most one package-designator argument, got " + args.size());
			}
			String pkg = args.isEmpty() ? LispNames.CL_PKG : designatorName(member, args.get(0));
			try {
				return PackageIntrospection
					.symbolList(PackageIntrospection.listNames(member, pkg, env.globalFunctionNames()));
			}
			catch (IllegalArgumentException ex) {
				throw new LispEvalException(Objects.requireNonNullElse(ex.getMessage(), "No such package: " + pkg));
			}
		}));
	}

	private static String designatorName(String member, LispVal designator) {
		if (designator instanceof LispSymbol sym) {
			return sym.isKeyword() ? sym.name().substring(1) : sym.name();
		}
		if (designator instanceof LispString str) {
			return str.value();
		}
		throw new LispEvalException(member + " expects a package name, got: " + designator.print());
	}

	private static void registerArithmetic(Environment env) {
		env.defineFunction(LispNames.ADD, new LispFunction(LispNames.ADD, args -> {
			if (hasDouble(args)) {
				double result = 0;
				for (LispVal arg : args) {
					result += asDouble(arg);
				}
				return new LispDouble(result);
			}
			if (hasRatio(args)) {
				return addRational(args);
			}
			if (hasBigInteger(args)) {
				return addBig(args);
			}
			try {
				long result = 0;
				for (LispVal arg : args) {
					result = Math.addExact(result, asLong(arg));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return addBig(args);
			}
		}));
		env.defineFunction(LispNames.SUB, new LispFunction(LispNames.SUB, args -> {
			if (hasDouble(args)) {
				if (args.size() == 1) {
					return new LispDouble(-asDouble(args.get(0)));
				}
				double result = asDouble(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result -= asDouble(args.get(i));
				}
				return new LispDouble(result);
			}
			if (hasRatio(args)) {
				return subRational(args);
			}
			if (hasBigInteger(args)) {
				return subBig(args);
			}
			try {
				if (args.size() == 1) {
					return new LispInteger(Math.negateExact(asLong(args.get(0))));
				}
				long result = asLong(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result = Math.subtractExact(result, asLong(args.get(i)));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return subBig(args);
			}
		}));
		env.defineFunction(LispNames.MUL, new LispFunction(LispNames.MUL, args -> {
			if (hasDouble(args)) {
				double result = 1;
				for (LispVal arg : args) {
					result *= asDouble(arg);
				}
				return new LispDouble(result);
			}
			if (hasRatio(args)) {
				return mulRational(args);
			}
			if (hasBigInteger(args)) {
				return mulBig(args);
			}
			try {
				long result = 1;
				for (LispVal arg : args) {
					result = Math.multiplyExact(result, asLong(arg));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return mulBig(args);
			}
		}));
		env.defineFunction(LispNames.DIV, new LispFunction(LispNames.DIV, args -> {
			if (hasDouble(args)) {
				if (args.size() == 1) {
					return new LispDouble(1.0 / asDouble(args.get(0)));
				}
				double result = asDouble(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result /= asDouble(args.get(i));
				}
				return new LispDouble(result);
			}
			// Exact rational division (Common Lisp semantics): (/ 1 2) -> 1/2,
			// (/ 4 2) -> 2, and unary (/ x) is the reciprocal.
			BigInteger num;
			BigInteger den;
			int first;
			if (args.size() == 1) {
				num = BigInteger.ONE;
				den = BigInteger.ONE;
				first = 0;
			}
			else {
				num = numeratorOf(args.get(0));
				den = denominatorOf(args.get(0));
				first = 1;
			}
			for (int i = first; i < args.size(); i++) {
				BigInteger divisorNum = numeratorOf(args.get(i));
				if (divisorNum.signum() == 0) {
					throw new LispEvalException("Division by zero");
				}
				num = num.multiply(denominatorOf(args.get(i)));
				den = den.multiply(divisorNum);
			}
			return LispRatio.valueOf(num, den);
		}));
		// mod: modulo whose result takes the sign of the divisor (Common Lisp mod).
		env.defineFunction(LispNames.MOD, new LispFunction(LispNames.MOD, args -> {
			requireArgCount(LispNames.MOD, args, 2);
			if (hasDouble(args)) {
				double a = asDouble(args.get(0));
				double b = asDouble(args.get(1));
				double r = a % b;
				if (r != 0 && ((r < 0) != (b < 0))) {
					r += b;
				}
				return new LispDouble(r);
			}
			if (hasBigInteger(args)) {
				BigInteger a = asBigInteger(args.get(0));
				BigInteger b = asBigInteger(args.get(1));
				BigInteger r = a.remainder(b);
				if (r.signum() != 0 && r.signum() != b.signum()) {
					r = r.add(b);
				}
				return normalizeBig(r);
			}
			return new LispInteger(Math.floorMod(asLong(args.get(0)), asLong(args.get(1))));
		}));
		// rem: remainder whose result takes the sign of the dividend (Common Lisp rem).
		env.defineFunction(LispNames.REM, new LispFunction(LispNames.REM, args -> {
			requireArgCount(LispNames.REM, args, 2);
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) % asDouble(args.get(1)));
			}
			if (hasBigInteger(args)) {
				return normalizeBig(asBigInteger(args.get(0)).remainder(asBigInteger(args.get(1))));
			}
			return new LispInteger(asLong(args.get(0)) % asLong(args.get(1)));
		}));
		env.defineFunction(LispNames.ABS, new LispFunction(LispNames.ABS, args -> {
			requireArgCount(LispNames.ABS, args, 1);
			if (hasDouble(args)) {
				return new LispDouble(Math.abs(asDouble(args.get(0))));
			}
			if (args.get(0) instanceof LispRatio r) {
				return LispRatio.valueOf(r.numerator().abs(), r.denominator());
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().abs());
			}
			long value = asLong(args.get(0));
			if (value == Long.MIN_VALUE) {
				return new LispBigInteger(BigInteger.valueOf(value).negate());
			}
			return new LispInteger(Math.abs(value));
		}));
		// min/max: variadic. Float contagion -- when any argument is a float the result
		// is
		// a float (Common Lisp semantics).
		env.defineFunction(LispNames.MIN, new LispFunction(LispNames.MIN, args -> {
			requireMinArgCount(LispNames.MIN, args, 1);
			LispVal best = args.get(0);
			for (int i = 1; i < args.size(); i++) {
				if (compareNumeric(args.get(i), best) < 0) {
					best = args.get(i);
				}
			}
			return hasDouble(args) ? new LispDouble(asDouble(best)) : best;
		}));
		env.defineFunction(LispNames.MAX, new LispFunction(LispNames.MAX, args -> {
			requireMinArgCount(LispNames.MAX, args, 1);
			LispVal best = args.get(0);
			for (int i = 1; i < args.size(); i++) {
				if (compareNumeric(args.get(i), best) > 0) {
					best = args.get(i);
				}
			}
			return hasDouble(args) ? new LispDouble(asDouble(best)) : best;
		}));
		env.defineFunction(LispNames.ONE_PLUS, new LispFunction(LispNames.ONE_PLUS, args -> {
			requireArgCount(LispNames.ONE_PLUS, args, 1);
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) + 1);
			}
			if (args.get(0) instanceof LispRatio r) {
				return LispRatio.valueOf(r.numerator().add(r.denominator()), r.denominator());
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().add(BigInteger.ONE));
			}
			try {
				return new LispInteger(Math.addExact(asLong(args.get(0)), 1));
			}
			catch (ArithmeticException overflow) {
				return normalizeBig(asBigInteger(args.get(0)).add(BigInteger.ONE));
			}
		}));
		env.defineFunction(LispNames.ONE_MINUS, new LispFunction(LispNames.ONE_MINUS, args -> {
			requireArgCount(LispNames.ONE_MINUS, args, 1);
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) - 1);
			}
			if (args.get(0) instanceof LispRatio r) {
				return LispRatio.valueOf(r.numerator().subtract(r.denominator()), r.denominator());
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().subtract(BigInteger.ONE));
			}
			try {
				return new LispInteger(Math.subtractExact(asLong(args.get(0)), 1));
			}
			catch (ArithmeticException overflow) {
				return normalizeBig(asBigInteger(args.get(0)).subtract(BigInteger.ONE));
			}
		}));
	}

	private static void registerMath(Environment env) {
		// Unary floating-point functions: always return a double (Math.<name>).
		defineUnaryDouble(env, LispNames.SQRT, Math::sqrt);
		defineUnaryDouble(env, LispNames.EXP, Math::exp);
		defineUnaryDouble(env, LispNames.LOG, Math::log);
		defineUnaryDouble(env, LispNames.SIN, Math::sin);
		defineUnaryDouble(env, LispNames.COS, Math::cos);
		defineUnaryDouble(env, LispNames.TAN, Math::tan);
		defineUnaryDouble(env, LispNames.ASIN, Math::asin);
		defineUnaryDouble(env, LispNames.ACOS, Math::acos);
		defineUnaryDouble(env, LispNames.ATAN, Math::atan);
		defineUnaryDouble(env, LispNames.SINH, Math::sinh);
		defineUnaryDouble(env, LispNames.COSH, Math::cosh);
		defineUnaryDouble(env, LispNames.TANH, Math::tanh);
		// random: a non-negative random number below the (positive) limit, of the same
		// type as the limit (integer -> integer, float -> float). The interpreter and the
		// JVM backend draw from Math.random(); the WASM backend draws real entropy from
		// the WASI random_get host function.
		env.defineFunction(LispNames.RANDOM, new LispFunction(LispNames.RANDOM, args -> {
			requireArgCount(LispNames.RANDOM, args, 1);
			LispVal limit = args.get(0);
			if (limit instanceof LispDouble d) {
				if (d.value() <= 0.0) {
					throw new LispEvalException("random expects a positive limit, got: " + limit.print());
				}
				return new LispDouble(Math.random() * d.value());
			}
			if (limit instanceof LispInteger i) {
				if (i.value() <= 0) {
					throw new LispEvalException("random expects a positive limit, got: " + limit.print());
				}
				return new LispInteger((long) (Math.random() * i.value()));
			}
			if (limit instanceof LispBigInteger b) {
				if (b.value().signum() <= 0) {
					throw new LispEvalException("random expects a positive limit, got: " + limit.print());
				}
				// Scale a [0,1) random fraction across the bignum range, then floor.
				return normalizeBig(
						new java.math.BigDecimal(b.value()).multiply(java.math.BigDecimal.valueOf(Math.random()))
							.toBigInteger());
			}
			throw new LispEvalException("random expects an integer or float limit, got: " + limit.print());
		}));
		// get-universal-time: seconds since 1900-01-01 00:00:00 GMT (Common Lisp epoch).
		// The interpreter and JVM backends read the real wall clock; the WASM backend
		// reads
		// wasi:clocks in component mode and a deterministic value in Preview 1 mode.
		env.defineFunction(LispNames.GET_UNIVERSAL_TIME, new LispFunction(LispNames.GET_UNIVERSAL_TIME, args -> {
			requireArgCount(LispNames.GET_UNIVERSAL_TIME, args, 0);
			return new LispInteger(System.currentTimeMillis() / 1000L + 2208988800L);
		}));
		// get-internal-real-time: elapsed real time in internal units (milliseconds
		// here).
		env.defineFunction(LispNames.GET_INTERNAL_REAL_TIME,
				new LispFunction(LispNames.GET_INTERNAL_REAL_TIME, args -> {
					requireArgCount(LispNames.GET_INTERNAL_REAL_TIME, args, 0);
					return new LispInteger(System.currentTimeMillis());
				}));
		// get-internal-run-time: consumed run time in internal units (milliseconds here).
		env.defineFunction(LispNames.GET_INTERNAL_RUN_TIME, new LispFunction(LispNames.GET_INTERNAL_RUN_TIME, args -> {
			requireArgCount(LispNames.GET_INTERNAL_RUN_TIME, args, 0);
			return new LispInteger(System.nanoTime() / 1000000L);
		}));
		// getenv: the value of an environment variable as a string, or nil if unset.
		env.defineFunction(LispNames.GETENV, new LispFunction(LispNames.GETENV, args -> {
			requireArgCount(LispNames.GETENV, args, 1);
			if (!(args.get(0) instanceof LispString name)) {
				throw new LispEvalException("getenv expects a string, got: " + args.get(0).print());
			}
			String value = System.getenv(name.value());
			return value == null ? LispNil.INSTANCE : new LispString(value);
		}));
		// isqrt: exact integer square root (floor of the real square root).
		env.defineFunction(LispNames.ISQRT, new LispFunction(LispNames.ISQRT, args -> {
			requireArgCount(LispNames.ISQRT, args, 1);
			BigInteger n = asBigInteger(args.get(0));
			if (n.signum() < 0) {
				throw new LispEvalException("isqrt expects a non-negative integer, got: " + args.get(0).print());
			}
			return normalizeBig(n.sqrt());
		}));
		// expt: rational^integer stays exact (a negative exponent yields the
		// reciprocal, e.g. (expt 2 -1) -> 1/2); otherwise Math.pow (double).
		env.defineFunction(LispNames.EXPT, new LispFunction(LispNames.EXPT, args -> {
			requireArgCount(LispNames.EXPT, args, 2);
			if (!hasDouble(args) && !(args.get(1) instanceof LispRatio)) {
				long power = asLong(args.get(1));
				if (power >= -Integer.MAX_VALUE && power <= Integer.MAX_VALUE) {
					BigInteger baseNum = numeratorOf(args.get(0));
					BigInteger baseDen = denominatorOf(args.get(0));
					return power >= 0 ? LispRatio.valueOf(baseNum.pow((int) power), baseDen.pow((int) power))
							: LispRatio.valueOf(baseDen.pow((int) -power), baseNum.pow((int) -power));
				}
			}
			return new LispDouble(Math.pow(asDouble(args.get(0)), asDouble(args.get(1))));
		}));
		// gcd: greatest common divisor (always non-negative). Variadic: (gcd) is 0 and
		// (gcd n) is (abs n).
		env.defineFunction(LispNames.GCD, new LispFunction(LispNames.GCD, args -> {
			BigInteger result = BigInteger.ZERO;
			for (LispVal arg : args) {
				result = result.gcd(asBigInteger(arg));
			}
			return normalizeBig(result);
		}));
		// lcm: least common multiple (0 if any argument is 0). Variadic: (lcm) is 1 and
		// (lcm n) is (abs n).
		env.defineFunction(LispNames.LCM, new LispFunction(LispNames.LCM, args -> {
			BigInteger result = BigInteger.ONE;
			for (LispVal arg : args) {
				BigInteger b = asBigInteger(arg);
				if (result.signum() == 0 || b.signum() == 0) {
					result = BigInteger.ZERO;
				}
				else {
					result = result.divide(result.gcd(b)).multiply(b).abs();
				}
			}
			return normalizeBig(result);
		}));
		// signum: sign as -1/0/1, preserving the float/integer type of the argument.
		env.defineFunction(LispNames.SIGNUM, new LispFunction(LispNames.SIGNUM, args -> {
			requireArgCount(LispNames.SIGNUM, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispDouble d) {
				return new LispDouble(Math.signum(d.value()));
			}
			if (arg instanceof LispRatio r) {
				return new LispInteger(r.numerator().signum());
			}
			if (arg instanceof LispBigInteger b) {
				return new LispInteger(b.value().signum());
			}
			return new LispInteger(Long.signum(asLong(arg)));
		}));
		// Bitwise integer operations, computed on exact BigInteger values.
		// logand/logior/logxor are variadic with identities -1/0/0.
		env.defineFunction(LispNames.LOGAND, new LispFunction(LispNames.LOGAND, args -> {
			BigInteger result = BigInteger.valueOf(-1);
			for (LispVal arg : args) {
				result = result.and(asBigInteger(arg));
			}
			return normalizeBig(result);
		}));
		env.defineFunction(LispNames.LOGIOR, new LispFunction(LispNames.LOGIOR, args -> {
			BigInteger result = BigInteger.ZERO;
			for (LispVal arg : args) {
				result = result.or(asBigInteger(arg));
			}
			return normalizeBig(result);
		}));
		env.defineFunction(LispNames.LOGXOR, new LispFunction(LispNames.LOGXOR, args -> {
			BigInteger result = BigInteger.ZERO;
			for (LispVal arg : args) {
				result = result.xor(asBigInteger(arg));
			}
			return normalizeBig(result);
		}));
		env.defineFunction(LispNames.LOGNOT, new LispFunction(LispNames.LOGNOT, args -> {
			requireArgCount(LispNames.LOGNOT, args, 1);
			return normalizeBig(asBigInteger(args.get(0)).not());
		}));
		// ash: shift left for a non-negative count, arithmetic right shift otherwise.
		env.defineFunction(LispNames.ASH, new LispFunction(LispNames.ASH, args -> {
			requireArgCount(LispNames.ASH, args, 2);
			BigInteger value = asBigInteger(args.get(0));
			long count = asLong(args.get(1));
			return normalizeBig(value.shiftLeft((int) count));
		}));
	}

	private static void defineUnaryDouble(Environment env, String name, DoubleUnaryOperator fn) {
		env.defineFunction(name, new LispFunction(name, args -> {
			requireArgCount(name, args, 1);
			return new LispDouble(fn.applyAsDouble(asDouble(args.get(0))));
		}));
	}

	private static void registerComparison(Environment env) {
		// Numeric comparisons are variadic (Common Lisp): they are true when every
		// adjacent
		// pair satisfies the relation, e.g. (< 1 2 3) is t. A single argument is true.
		env.defineFunction(LispNames.EQ,
				new LispFunction(LispNames.EQ, args -> compareChain(LispNames.EQ, args, 0, 0)));
		env.defineFunction(LispNames.LT,
				new LispFunction(LispNames.LT, args -> compareChain(LispNames.LT, args, -1, -1)));
		env.defineFunction(LispNames.GT,
				new LispFunction(LispNames.GT, args -> compareChain(LispNames.GT, args, 1, 1)));
		env.defineFunction(LispNames.LE,
				new LispFunction(LispNames.LE, args -> compareChain(LispNames.LE, args, -1, 0)));
		env.defineFunction(LispNames.GE,
				new LispFunction(LispNames.GE, args -> compareChain(LispNames.GE, args, 0, 1)));
		env.defineFunction(LispNames.EQ_GENERAL, new LispFunction(LispNames.EQ_GENERAL, args -> {
			requireArgCount(LispNames.EQ_GENERAL, args, 2);
			return eqValue(args.get(0), args.get(1));
		}));
		env.defineFunction(LispNames.EQL, new LispFunction(LispNames.EQL, args -> {
			requireArgCount(LispNames.EQL, args, 2);
			return eqlValue(args.get(0), args.get(1));
		}));
		env.defineFunction(LispNames.EQUAL, new LispFunction(LispNames.EQUAL, args -> {
			requireArgCount(LispNames.EQUAL, args, 2);
			return equalValue(args.get(0), args.get(1));
		}));
	}

	// eq: object identity. Like eql, but floats and ratios (which are distinct boxed
	// objects, not interned like small integers or symbols) are never eq.
	private static LispVal eqValue(LispVal a, LispVal b) {
		if ((a instanceof LispDouble && b instanceof LispDouble)
				|| (a instanceof LispRatio && b instanceof LispRatio)) {
			return LispNil.INSTANCE;
		}
		return eqlValue(a, b);
	}

	// eql: like eq, but numbers of the same type and value are eql. Cons cells (and other
	// aggregates) compare by reference identity.
	private static LispVal eqlValue(LispVal a, LispVal b) {
		if (a instanceof LispCons || b instanceof LispCons) {
			return a == b ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}
		if (a instanceof LispNil && b instanceof LispNil) {
			return LispTrue.INSTANCE;
		}
		if (a instanceof LispNil || b instanceof LispNil) {
			return LispNil.INSTANCE;
		}
		return a.equals(b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	// equal: structural equality. Cons cells are compared recursively by car and cdr;
	// everything else falls back to eql (so numbers, symbols, strings, and nil compare by
	// value).
	private static LispVal equalValue(LispVal a, LispVal b) {
		if (a instanceof LispCons consA && b instanceof LispCons consB) {
			return (equalValue(consA.car(), consB.car()) == LispTrue.INSTANCE
					&& equalValue(consA.cdr(), consB.cdr()) == LispTrue.INSTANCE) ? LispTrue.INSTANCE
							: LispNil.INSTANCE;
		}
		return eqlValue(a, b);
	}

	private static boolean isEq(LispVal a, LispVal b) {
		if (a instanceof LispCons || b instanceof LispCons) {
			return a == b;
		}
		if (a instanceof LispNil && b instanceof LispNil) {
			return true;
		}
		if (a instanceof LispNil || b instanceof LispNil) {
			return false;
		}
		return a.equals(b);
	}

	private static void registerSequenceOps(Environment env) {
		env.defineFunction(LispNames.LENGTH, new LispFunction(LispNames.LENGTH, args -> {
			requireArgCount(LispNames.LENGTH, args, 1);
			// length applies to strings and vectors as well as lists (Common Lisp
			// sequences). A rank-2 array is not a sequence, so it is an error.
			if (args.get(0) instanceof LispString str) {
				return new LispInteger(str.value().length());
			}
			if (args.get(0) instanceof LispArray array) {
				if (array.dimensions().length != 1) {
					throw new LispEvalException(LispNames.LENGTH + ": argument is not a sequence (rank-"
							+ array.dimensions().length + " array)");
				}
				return new LispInteger(array.dimensions()[0]);
			}
			long count = 0;
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				count++;
				cur = cell.cdr();
			}
			return new LispInteger(count);
		}));
		env.defineFunction(LispNames.REVERSE, new LispFunction(LispNames.REVERSE, args -> {
			requireArgCount(LispNames.REVERSE, args, 1);
			LispVal result = LispNil.INSTANCE;
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				result = new LispCons(cell.car(), result);
				cur = cell.cdr();
			}
			return result;
		}));
		// member is registered in LispEvaluator so the optional :test keyword designator
		// can be applied through the evaluator (it may name a user function or lambda).
		env.defineFunction(LispNames.FIND, new LispFunction(LispNames.FIND, args -> {
			requireArgCount(LispNames.FIND, args, 2);
			LispVal item = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (isEq(item, cell.car())) {
					return cell.car();
				}
				cur = cell.cdr();
			}
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.POSITION, new LispFunction(LispNames.POSITION, args -> {
			requireArgCount(LispNames.POSITION, args, 2);
			LispVal item = args.get(0);
			LispVal cur = args.get(1);
			long index = 0;
			while (cur instanceof LispCons cell) {
				if (isEq(item, cell.car())) {
					return new LispInteger(index);
				}
				index++;
				cur = cell.cdr();
			}
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.COUNT, new LispFunction(LispNames.COUNT, args -> {
			requireArgCount(LispNames.COUNT, args, 2);
			LispVal item = args.get(0);
			LispVal cur = args.get(1);
			long count = 0;
			while (cur instanceof LispCons cell) {
				if (isEq(item, cell.car())) {
					count++;
				}
				cur = cell.cdr();
			}
			return new LispInteger(count);
		}));
		// assoc/rassoc are registered in LispEvaluator: their :test keyword needs
		// `apply`.
		env.defineFunction(LispNames.ACONS, new LispFunction(LispNames.ACONS, args -> {
			requireArgCount(LispNames.ACONS, args, 3);
			return new LispCons(new LispCons(args.get(0), args.get(1)), args.get(2));
		}));
		env.defineFunction(LispNames.PAIRLIS, new LispFunction(LispNames.PAIRLIS, args -> {
			if (args.size() < 2 || args.size() > 3) {
				throw new LispEvalException(LispNames.PAIRLIS + " expects 2 or 3 arguments, got " + args.size());
			}
			LispVal keys = args.get(0);
			LispVal data = args.get(1);
			LispVal result = (args.size() == 3) ? args.get(2) : LispNil.INSTANCE;
			// Collect pairs front-to-back, then prepend in reverse to preserve key
			// order.
			List<LispCons> pairs = new ArrayList<>();
			while (keys instanceof LispCons keyCell && data instanceof LispCons dataCell) {
				pairs.add(new LispCons(keyCell.car(), dataCell.car()));
				keys = keyCell.cdr();
				data = dataCell.cdr();
			}
			for (int i = pairs.size() - 1; i >= 0; i--) {
				result = new LispCons(pairs.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.COPY_ALIST, new LispFunction(LispNames.COPY_ALIST, args -> {
			requireArgCount(LispNames.COPY_ALIST, args, 1);
			// Copy the spine and each (key . value) pair cell; keys and values are
			// shared.
			List<LispVal> elements = new ArrayList<>();
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				LispVal element = cell.car();
				elements.add((element instanceof LispCons pair) ? new LispCons(pair.car(), pair.cdr()) : element);
				cur = cell.cdr();
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = elements.size() - 1; i >= 0; i--) {
				result = new LispCons(elements.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.LAST, new LispFunction(LispNames.LAST, args -> {
			requireArgCount(LispNames.LAST, args, 1);
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell && cell.cdr() instanceof LispCons) {
				cur = cell.cdr();
			}
			return cur instanceof LispCons ? cur : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.REMOVE, new LispFunction(LispNames.REMOVE, args -> {
			requireArgCount(LispNames.REMOVE, args, 2);
			LispVal item = args.get(0);
			List<LispVal> kept = new java.util.ArrayList<>();
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (!isEq(item, cell.car())) {
					kept.add(cell.car());
				}
				cur = cell.cdr();
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = kept.size() - 1; i >= 0; i--) {
				result = new LispCons(kept.get(i), result);
			}
			return result;
		}));
		// delete is the destructive variant of remove: splice out matching cells in place
		// (Common Lisp semantics; use the return value since the head may change).
		env.defineFunction(LispNames.DELETE, new LispFunction(LispNames.DELETE, args -> {
			requireArgCount(LispNames.DELETE, args, 2);
			LispVal item = args.get(0);
			LispVal head = args.get(1);
			// Drop matching cells from the front by advancing the head.
			while (head instanceof LispCons cell && isEq(item, cell.car())) {
				head = cell.cdr();
			}
			if (!(head instanceof LispCons headCell)) {
				return head;
			}
			// Splice out matching cells in the interior.
			LispCons prev = headCell;
			LispVal cur = headCell.cdr();
			while (cur instanceof LispCons cell) {
				if (isEq(item, cell.car())) {
					prev.setCdr(cell.cdr());
				}
				else {
					prev = cell;
				}
				cur = cell.cdr();
			}
			return head;
		}));
		// substitute new old list: return a fresh copy with every element eql to old
		// replaced by new (non-destructive).
		LispFunction substitute = new LispFunction(LispNames.SUBSTITUTE, args -> {
			requireArgCount(LispNames.SUBSTITUTE, args, 3);
			LispVal newItem = args.get(0);
			LispVal oldItem = args.get(1);
			List<LispVal> out = new java.util.ArrayList<>();
			LispVal cur = args.get(2);
			while (cur instanceof LispCons cell) {
				out.add(isEq(oldItem, cell.car()) ? newItem : cell.car());
				cur = cell.cdr();
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = out.size() - 1; i >= 0; i--) {
				result = new LispCons(out.get(i), result);
			}
			return result;
		});
		env.defineFunction(LispNames.SUBSTITUTE, substitute);
		// nsubstitute is the destructive variant: rewrite matching cars in place and
		// return
		// the (possibly mutated) original list (Common Lisp semantics).
		env.defineFunction(LispNames.NSUBSTITUTE, new LispFunction(LispNames.NSUBSTITUTE, args -> {
			requireArgCount(LispNames.NSUBSTITUTE, args, 3);
			LispVal newItem = args.get(0);
			LispVal oldItem = args.get(1);
			LispVal list = args.get(2);
			LispVal cur = list;
			while (cur instanceof LispCons cell) {
				if (isEq(oldItem, cell.car())) {
					cell.setCar(newItem);
				}
				cur = cell.cdr();
			}
			return list;
		}));
		env.defineFunction(LispNames.BUTLAST, new LispFunction(LispNames.BUTLAST, args -> {
			requireArgCount(LispNames.BUTLAST, args, 1);
			List<LispVal> kept = new java.util.ArrayList<>();
			LispVal cur = args.get(0);
			// Accumulate every element except the last; an empty or single-element list
			// yields nil.
			while (cur instanceof LispCons cell && cell.cdr() instanceof LispCons) {
				kept.add(cell.car());
				cur = cell.cdr();
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = kept.size() - 1; i >= 0; i--) {
				result = new LispCons(kept.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.GETF, new LispFunction(LispNames.GETF, args -> {
			requireArgCount(LispNames.GETF, args, 2);
			// (getf plist indicator): the property list is the first argument.
			LispVal cur = args.get(0);
			LispVal key = args.get(1);
			// Walk the property list two cells at a time, returning the value after the
			// first key eql to the indicator.
			while (cur instanceof LispCons cell && cell.cdr() instanceof LispCons valueCell) {
				if (isEq(key, cell.car())) {
					return valueCell.car();
				}
				cur = valueCell.cdr();
			}
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.REMOVE_DUPLICATES, new LispFunction(LispNames.REMOVE_DUPLICATES, args -> {
			requireArgCount(LispNames.REMOVE_DUPLICATES, args, 1);
			List<LispVal> kept = new java.util.ArrayList<>();
			LispVal cur = args.get(0);
			// Keep an element only when it does not occur again later in the list, so the
			// last occurrence of each value survives (Common Lisp default; eql compare).
			while (cur instanceof LispCons cell) {
				LispVal rest = cell.cdr();
				boolean dup = false;
				while (rest instanceof LispCons restCell) {
					if (isEq(cell.car(), restCell.car())) {
						dup = true;
						break;
					}
					rest = restCell.cdr();
				}
				if (!dup) {
					kept.add(cell.car());
				}
				cur = cell.cdr();
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = kept.size() - 1; i >= 0; i--) {
				result = new LispCons(kept.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.NCONC, new LispFunction(LispNames.NCONC, args -> {
			requireArgCount(LispNames.NCONC, args, 2);
			LispVal a = args.get(0);
			LispVal b = args.get(1);
			// Destructively link the last cons of 'a' to 'b'; return 'b' when 'a' is
			// empty.
			if (!(a instanceof LispCons head)) {
				return b;
			}
			LispCons tail = head;
			while (tail.cdr() instanceof LispCons next) {
				tail = next;
			}
			tail.setCdr(b);
			return head;
		}));
		env.defineFunction(LispNames.IDENTITY, new LispFunction(LispNames.IDENTITY, args -> {
			requireArgCount(LispNames.IDENTITY, args, 1);
			return args.get(0);
		}));
		// gensym: a per-environment counter; the result is an ordinary symbol (rontolisp
		// has no uninterned symbols) whose "#:" prefix keeps it out of the way of
		// user-written names. The compilers require a literal string prefix; here the
		// prefix is any runtime string.
		java.util.concurrent.atomic.AtomicLong gensymCounter = new java.util.concurrent.atomic.AtomicLong();
		env.defineFunction(LispNames.GENSYM, new LispFunction(LispNames.GENSYM, args -> {
			if (args.size() > 1) {
				throw new LispEvalException(LispNames.GENSYM + " expects at most 1 argument, got " + args.size());
			}
			String prefix = "g";
			if (args.size() == 1) {
				if (!(args.get(0) instanceof LispString s)) {
					throw new LispEvalException(
							LispNames.GENSYM + " prefix must be a string, got " + args.get(0).print());
				}
				prefix = s.value();
			}
			return new LispSymbol("#:" + prefix + gensymCounter.incrementAndGet());
		}));
		env.defineFunction(LispNames.COPY_LIST, new LispFunction(LispNames.COPY_LIST, args -> {
			requireArgCount(LispNames.COPY_LIST, args, 1);
			List<LispVal> elements = new java.util.ArrayList<>();
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				elements.add(cell.car());
				cur = cell.cdr();
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = elements.size() - 1; i >= 0; i--) {
				result = new LispCons(elements.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.NREVERSE, new LispFunction(LispNames.NREVERSE, args -> {
			requireArgCount(LispNames.NREVERSE, args, 1);
			// Destructive: rewire each cdr to its predecessor and return the former last
			// cell as the new head (Common Lisp semantics; use the return value).
			LispVal prev = LispNil.INSTANCE;
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				LispVal next = cell.cdr();
				cell.setCdr(prev);
				prev = cell;
				cur = next;
			}
			return prev;
		}));
		env.defineFunction(LispNames.MAKE_LIST, new LispFunction(LispNames.MAKE_LIST, args -> {
			requireArgCount(LispNames.MAKE_LIST, args, 1);
			// (make-list n): a list of n nil elements (:initial-element not supported).
			long n = asLong(args.get(0));
			LispVal result = LispNil.INSTANCE;
			for (long i = 0; i < n; i++) {
				result = new LispCons(LispNil.INSTANCE, result);
			}
			return result;
		}));
		env.defineFunction(LispNames.UNION, new LispFunction(LispNames.UNION, args -> {
			requireArgCount(LispNames.UNION, args, 2);
			// Start from the first list, then prepend each element of the second not
			// already
			// present (eql compare). Order matches the compilers' macro expansion: new
			// elements of the second list appear at the front. CL leaves order
			// unspecified.
			List<LispVal> seen = toJavaList(args.get(0));
			LispVal result = args.get(0);
			LispVal cur = args.get(1);
			while (cur instanceof LispCons cell) {
				if (!listContains(seen, cell.car())) {
					result = new LispCons(cell.car(), result);
					seen.add(cell.car());
				}
				cur = cell.cdr();
			}
			return result;
		}));
		env.defineFunction(LispNames.INTERSECTION, new LispFunction(LispNames.INTERSECTION, args -> {
			requireArgCount(LispNames.INTERSECTION, args, 2);
			// Collect each element of the first list that is a member of the second,
			// prepending so the result order matches the compilers' macro expansion.
			List<LispVal> second = toJavaList(args.get(1));
			LispVal result = LispNil.INSTANCE;
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				if (listContains(second, cell.car())) {
					result = new LispCons(cell.car(), result);
				}
				cur = cell.cdr();
			}
			return result;
		}));
		env.defineFunction(LispNames.SET_DIFFERENCE, new LispFunction(LispNames.SET_DIFFERENCE, args -> {
			requireArgCount(LispNames.SET_DIFFERENCE, args, 2);
			// Collect each element of the first list not present in the second,
			// prepending
			// so the result order matches the compilers' macro expansion.
			List<LispVal> second = toJavaList(args.get(1));
			LispVal result = LispNil.INSTANCE;
			LispVal cur = args.get(0);
			while (cur instanceof LispCons cell) {
				if (!listContains(second, cell.car())) {
					result = new LispCons(cell.car(), result);
				}
				cur = cell.cdr();
			}
			return result;
		}));
		env.defineFunction(LispNames.ADJOIN, new LispFunction(LispNames.ADJOIN, args -> {
			requireArgCount(LispNames.ADJOIN, args, 2);
			LispVal item = args.get(0);
			LispVal lst = args.get(1);
			LispVal cur = lst;
			while (cur instanceof LispCons cell) {
				if (isEq(item, cell.car())) {
					return lst;
				}
				cur = cell.cdr();
			}
			return new LispCons(item, lst);
		}));
	}

	private static List<LispVal> toJavaList(LispVal list) {
		List<LispVal> elements = new java.util.ArrayList<>();
		LispVal cur = list;
		while (cur instanceof LispCons cell) {
			elements.add(cell.car());
			cur = cell.cdr();
		}
		return elements;
	}

	private static boolean listContains(List<LispVal> list, LispVal item) {
		for (LispVal element : list) {
			if (isEq(item, element)) {
				return true;
			}
		}
		return false;
	}

	private static void registerStringOps(Environment env) {
		env.defineFunction(LispNames.STRING_UPCASE, new LispFunction(LispNames.STRING_UPCASE, args -> {
			requireArgCount(LispNames.STRING_UPCASE, args, 1);
			return new LispString(requireString(LispNames.STRING_UPCASE, args.get(0)).toUpperCase(Locale.ROOT));
		}));
		env.defineFunction(LispNames.STRING_DOWNCASE, new LispFunction(LispNames.STRING_DOWNCASE, args -> {
			requireArgCount(LispNames.STRING_DOWNCASE, args, 1);
			return new LispString(requireString(LispNames.STRING_DOWNCASE, args.get(0)).toLowerCase(Locale.ROOT));
		}));
		env.defineFunction(LispNames.STRING_CAPITALIZE, new LispFunction(LispNames.STRING_CAPITALIZE, args -> {
			requireArgCount(LispNames.STRING_CAPITALIZE, args, 1);
			return new LispString(capitalizeString(requireString(LispNames.STRING_CAPITALIZE, args.get(0))));
		}));
		// subseq: strings and lists. (seq start [end]); end defaults to the sequence
		// length.
		env.defineFunction(LispNames.SUBSEQ, new LispFunction(LispNames.SUBSEQ, args -> {
			requireMinArgCount(LispNames.SUBSEQ, args, 2);
			if (args.size() > 3) {
				throw new LispEvalException(LispNames.SUBSEQ + " expects 2 or 3 arguments, got " + args.size());
			}
			LispVal endArg = (args.size() == 3 && !(args.get(2) instanceof LispNil)) ? args.get(2) : null;
			int start = requireIndex(LispNames.SUBSEQ, args.get(1));
			if (args.get(0) instanceof LispString str) {
				String s = str.value();
				int end = (endArg != null) ? requireIndex(LispNames.SUBSEQ, endArg) : s.length();
				if (start < 0 || end > s.length() || start > end) {
					throw new LispEvalException(LispNames.SUBSEQ + ": invalid bounds " + start + ", " + end
							+ " for string of length " + s.length());
				}
				return new LispString(s.substring(start, end));
			}
			if (args.get(0) instanceof LispCons || args.get(0) instanceof LispNil) {
				List<LispVal> elements = new ArrayList<>();
				LispVal cur = args.get(0);
				while (cur instanceof LispCons cell) {
					elements.add(cell.car());
					cur = cell.cdr();
				}
				int end = (endArg != null) ? requireIndex(LispNames.SUBSEQ, endArg) : elements.size();
				if (start < 0 || end > elements.size() || start > end) {
					throw new LispEvalException(LispNames.SUBSEQ + ": invalid bounds " + start + ", " + end
							+ " for list of length " + elements.size());
				}
				LispVal result = LispNil.INSTANCE;
				for (int i = end - 1; i >= start; i--) {
					result = new LispCons(elements.get(i), result);
				}
				return result;
			}
			throw new LispEvalException(LispNames.SUBSEQ + " expects a string or list, got: " + args.get(0).print());
		}));
		env.defineFunction(LispNames.STRING_EQ, new LispFunction(LispNames.STRING_EQ, args -> {
			requireArgCount(LispNames.STRING_EQ, args, 2);
			String a = requireString(LispNames.STRING_EQ, args.get(0));
			String b = requireString(LispNames.STRING_EQ, args.get(1));
			return a.equals(b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.STRING_EQUAL, new LispFunction(LispNames.STRING_EQUAL, args -> {
			requireArgCount(LispNames.STRING_EQUAL, args, 2);
			String a = requireString(LispNames.STRING_EQUAL, args.get(0));
			String b = requireString(LispNames.STRING_EQUAL, args.get(1));
			return a.equalsIgnoreCase(b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.STRING_TRIM, new LispFunction(LispNames.STRING_TRIM, args -> {
			requireArgCount(LispNames.STRING_TRIM, args, 2);
			return new LispString(trimString(LispNames.STRING_TRIM, args.get(0), args.get(1), true, true));
		}));
		env.defineFunction(LispNames.STRING_LEFT_TRIM, new LispFunction(LispNames.STRING_LEFT_TRIM, args -> {
			requireArgCount(LispNames.STRING_LEFT_TRIM, args, 2);
			return new LispString(trimString(LispNames.STRING_LEFT_TRIM, args.get(0), args.get(1), true, false));
		}));
		env.defineFunction(LispNames.STRING_RIGHT_TRIM, new LispFunction(LispNames.STRING_RIGHT_TRIM, args -> {
			requireArgCount(LispNames.STRING_RIGHT_TRIM, args, 2);
			return new LispString(trimString(LispNames.STRING_RIGHT_TRIM, args.get(0), args.get(1), false, true));
		}));
	}

	private static String requireString(String name, LispVal val) {
		if (val instanceof LispString str) {
			return str.value();
		}
		throw new LispEvalException(name + " expects a string, got: " + val.print());
	}

	private static int requireIndex(String name, LispVal val) {
		if (val instanceof LispInteger i) {
			return (int) i.value();
		}
		throw new LispEvalException(name + " expects an integer index, got: " + val.print());
	}

	// Capitalizes the first letter of each alphanumeric word and lowercases the rest,
	// matching Common Lisp string-capitalize.
	private static String capitalizeString(String s) {
		char[] chars = s.toCharArray();
		boolean atWordStart = true;
		for (int i = 0; i < chars.length; i++) {
			char ch = chars[i];
			if (Character.isLetterOrDigit(ch)) {
				chars[i] = atWordStart ? Character.toUpperCase(ch) : Character.toLowerCase(ch);
				atWordStart = false;
			}
			else {
				atWordStart = true;
			}
		}
		return new String(chars);
	}

	// Removes characters that appear in the bag string from the requested ends.
	private static String trimString(String name, LispVal bagVal, LispVal strVal, boolean left, boolean right) {
		String bag = requireString(name, bagVal);
		String s = requireString(name, strVal);
		int start = 0;
		int end = s.length();
		if (left) {
			while (start < end && bag.indexOf(s.charAt(start)) >= 0) {
				start++;
			}
		}
		if (right) {
			while (end > start && bag.indexOf(s.charAt(end - 1)) >= 0) {
				end--;
			}
		}
		return s.substring(start, end);
	}

	private static void registerIO(Environment env, PrintStream out, InputStream in) {
		BufferedReader stdinReader = new BufferedReader(new InputStreamReader(in));
		// Tracks whether standard output is at the beginning of a line, so fresh-line
		// (~&) can emit a newline only when needed.
		boolean[] atLineStart = { true };
		java.util.function.Consumer<String> emit = text -> {
			if (text.isEmpty()) {
				return;
			}
			out.print(text);
			atLineStart[0] = text.charAt(text.length() - 1) == '\n';
		};
		env.defineFunction(LispNames.PRINT, new LispFunction(LispNames.PRINT, args -> {
			requireArgCount(LispNames.PRINT, args, 1);
			LispVal val = args.get(0);
			emit.accept(printString(val) + "\n");
			return val;
		}));
		env.defineFunction(LispNames.PRIN1, new LispFunction(LispNames.PRIN1, args -> {
			requireArgCount(LispNames.PRIN1, args, 1);
			LispVal val = args.get(0);
			emit.accept(printString(val));
			return val;
		}));
		env.defineFunction(LispNames.PRINC, new LispFunction(LispNames.PRINC, args -> {
			requireArgCount(LispNames.PRINC, args, 1);
			LispVal val = args.get(0);
			emit.accept(displayString(val));
			return val;
		}));
		env.defineFunction(LispNames.TERPRI, new LispFunction(LispNames.TERPRI, args -> {
			requireArgCount(LispNames.TERPRI, args, 0);
			emit.accept("\n");
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.FRESH_LINE, new LispFunction(LispNames.FRESH_LINE, args -> {
			requireArgCount(LispNames.FRESH_LINE, args, 0);
			if (!atLineStart[0]) {
				emit.accept("\n");
			}
			return LispNil.INSTANCE;
		}));
		// princ-to-string / prin1-to-string: the string that princ / prin1 would print.
		env.defineFunction(LispNames.PRINC_TO_STRING, new LispFunction(LispNames.PRINC_TO_STRING, args -> {
			requireArgCount(LispNames.PRINC_TO_STRING, args, 1);
			return new LispString(displayString(args.get(0)));
		}));
		env.defineFunction(LispNames.PRIN1_TO_STRING, new LispFunction(LispNames.PRIN1_TO_STRING, args -> {
			requireArgCount(LispNames.PRIN1_TO_STRING, args, 1);
			return new LispString(printString(args.get(0)));
		}));
		// concatenate: only the string result type is supported.
		env.defineFunction(LispNames.CONCATENATE, new LispFunction(LispNames.CONCATENATE, args -> {
			requireMinArgCount(LispNames.CONCATENATE, args, 1);
			if (!(args.get(0) instanceof LispSymbol type) || !"string".equals(type.name())) {
				throw new LispEvalException(
						"concatenate supports only the string result type, got: " + args.get(0).print());
			}
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i < args.size(); i++) {
				if (!(args.get(i) instanceof LispString str)) {
					throw new LispEvalException("concatenate expects strings, got: " + args.get(i).print());
				}
				sb.append(str.value());
			}
			return new LispString(sb.toString());
		}));
		// %string-concat: internal binary string concatenation used by
		// format/concatenate.
		env.defineFunction(LispNames.STRING_CONCAT, new LispFunction(LispNames.STRING_CONCAT, args -> {
			requireArgCount(LispNames.STRING_CONCAT, args, 2);
			if (!(args.get(0) instanceof LispString a) || !(args.get(1) instanceof LispString b)) {
				throw new LispEvalException(
						"%string-concat expects strings, got: " + args.get(0).print() + ", " + args.get(1).print());
			}
			return new LispString(a.value() + b.value());
		}));
		// %error: internal single-argument primitive that signals an error with a
		// pre-built message string. Produced by the error macro expansion.
		env.defineFunction(LispNames.ERROR_INTERNAL, new LispFunction(LispNames.ERROR_INTERNAL, args -> {
			requireArgCount(LispNames.ERROR_INTERNAL, args, 1);
			String message = (args.get(0) instanceof LispString s) ? s.value() : args.get(0).display();
			throw new LispEvalException(message);
		}));
		// File streams opened by open/with-open-file: an integer handle indexes this
		// table, matching the compiled backends (JVM: a static stream table; WASM: the
		// WASI file descriptor).
		Map<Long, Closeable> streams = new HashMap<>();
		long[] nextStreamHandle = { 0 };
		env.defineFunction(LispNames.OPEN, new LispFunction(LispNames.OPEN, args -> {
			requireMinArgCount(LispNames.OPEN, args, 1);
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(LispNames.OPEN + " expects a string filename");
			}
			boolean output = false;
			if (args.size() > 1) {
				if (!(args.get(1) instanceof LispSymbol dir) || !(LispNames.INPUT_KEYWORD.equals(dir.name())
						|| LispNames.OUTPUT_KEYWORD.equals(dir.name()))) {
					throw new LispEvalException(LispNames.OPEN + " supports :input and :output directions");
				}
				output = LispNames.OUTPUT_KEYWORD.equals(dir.name());
			}
			// The optional third argument is the element type: '(unsigned-byte 8) opens a
			// binary stream, 'character (the default) a text stream.
			boolean binary = false;
			if (args.size() > 2) {
				binary = isBinaryElementType(args.get(2));
			}
			try {
				Closeable stream;
				if (binary) {
					stream = output ? new BufferedOutputStream(Files.newOutputStream(Path.of(path.value())))
							: new BufferedInputStream(Files.newInputStream(Path.of(path.value())));
				}
				else {
					stream = output ? Files.newBufferedWriter(Path.of(path.value()))
							: Files.newBufferedReader(Path.of(path.value()));
				}
				long handle = nextStreamHandle[0]++;
				streams.put(handle, stream);
				return new LispInteger(handle);
			}
			catch (IOException ex) {
				throw new LispEvalException(
						LispNames.OPEN + ": cannot open file " + path.value() + ": " + ex.getMessage());
			}
		}));
		env.defineFunction(LispNames.CLOSE, new LispFunction(LispNames.CLOSE, args -> {
			requireArgCount(LispNames.CLOSE, args, 1);
			if (!(args.get(0) instanceof LispInteger handle)) {
				throw new LispEvalException(LispNames.CLOSE + " expects a stream");
			}
			Closeable stream = streams.remove(handle.value());
			if (stream == null) {
				throw new LispEvalException(LispNames.CLOSE + ": not an open stream: " + handle.value());
			}
			try {
				stream.close();
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return LispTrue.INSTANCE;
		}));
		env.defineFunction(LispNames.WRITE_LINE, new LispFunction(LispNames.WRITE_LINE, args -> {
			requireMinArgCount(LispNames.WRITE_LINE, args, 1);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.WRITE_LINE + " expects a string");
			}
			if (args.size() == 1) {
				out.println(str.value());
				return str;
			}
			if (!(args.get(1) instanceof LispInteger handle)
					|| !(streams.get(handle.value()) instanceof BufferedWriter writer)) {
				throw new LispEvalException(LispNames.WRITE_LINE + " expects an output stream");
			}
			try {
				writer.write(str.value());
				writer.write("\n");
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return str;
		}));
		env.defineFunction(LispNames.READ_LINE, new LispFunction(LispNames.READ_LINE, args -> {
			try {
				if (args.isEmpty()) {
					// Drain buffered output so any prompt is visible before we block on
					// stdin.
					out.flush();
					String line = stdinReader.readLine();
					return line == null ? LispNil.INSTANCE : new LispString(line);
				}
				requireArgCount(LispNames.READ_LINE, args, 1);
				if (!(args.get(0) instanceof LispInteger handle)
						|| !(streams.get(handle.value()) instanceof BufferedReader reader)) {
					throw new LispEvalException(LispNames.READ_LINE + " expects an input stream");
				}
				String line = reader.readLine();
				return line == null ? LispNil.INSTANCE : new LispString(line);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		env.defineFunction(LispNames.READ_BYTE, new LispFunction(LispNames.READ_BYTE, args -> {
			requireMinArgCount(LispNames.READ_BYTE, args, 1);
			if (args.size() > 3) {
				throw new LispEvalException(LispNames.READ_BYTE + " expects 1 to 3 arguments");
			}
			if (!(args.get(0) instanceof LispInteger handle)
					|| !(streams.get(handle.value()) instanceof InputStream in2)) {
				throw new LispEvalException(LispNames.READ_BYTE + " expects a binary input stream");
			}
			int b;
			try {
				b = in2.read();
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			if (b < 0) {
				boolean eofError = args.size() < 2 || args.get(1) != LispNil.INSTANCE;
				if (eofError) {
					throw new LispEvalException(LispNames.READ_BYTE + ": end of file on stream " + handle.value());
				}
				return args.size() > 2 ? args.get(2) : LispNil.INSTANCE;
			}
			return new LispInteger(b);
		}));
		env.defineFunction(LispNames.WRITE_BYTE, new LispFunction(LispNames.WRITE_BYTE, args -> {
			requireArgCount(LispNames.WRITE_BYTE, args, 2);
			if (!(args.get(0) instanceof LispInteger value) || value.value() < 0 || value.value() > 255) {
				throw new LispEvalException(LispNames.WRITE_BYTE + " expects an integer between 0 and 255");
			}
			if (!(args.get(1) instanceof LispInteger handle)
					|| !(streams.get(handle.value()) instanceof OutputStream out2)) {
				throw new LispEvalException(LispNames.WRITE_BYTE + " expects a binary output stream");
			}
			try {
				out2.write((int) value.value());
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return value;
		}));
		env.defineFunction(LispNames.READ, new LispFunction(LispNames.READ, args -> {
			try {
				// (read) reads from stdin; (read stream) reads from an open input
				// stream. Both skip blank and comment-only lines and return one datum
				// per call, or nil at end of input.
				BufferedReader reader;
				if (args.isEmpty()) {
					// Drain buffered output so any prompt is visible before we block on
					// stdin.
					out.flush();
					reader = stdinReader;
				}
				else {
					requireArgCount(LispNames.READ, args, 1);
					if (!(args.get(0) instanceof LispInteger handle)
							|| !(streams.get(handle.value()) instanceof BufferedReader streamReader)) {
						throw new LispEvalException(LispNames.READ + " expects an input stream");
					}
					reader = streamReader;
				}
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith(";")) {
						continue;
					}
					return LispReader.readFromString(line);
				}
				return LispNil.INSTANCE;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		// read-from-string: parse the first datum from a string (the optional
		// eof-error-p/eof-value and :start/:end keywords of Common Lisp are not
		// supported).
		env.defineFunction(LispNames.READ_FROM_STRING, new LispFunction(LispNames.READ_FROM_STRING, args -> {
			requireMinArgCount(LispNames.READ_FROM_STRING, args, 1);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.READ_FROM_STRING + " expects a string");
			}
			return LispReader.readFromString(str.value());
		}));
		// parse-integer: parse an integer from a string, with the common :radix,
		// :junk-allowed, :start and :end keywords.
		env.defineFunction(LispNames.PARSE_INTEGER, new LispFunction(LispNames.PARSE_INTEGER, args -> {
			requireMinArgCount(LispNames.PARSE_INTEGER, args, 1);
			if (!(args.get(0) instanceof LispString str)) {
				throw new LispEvalException(LispNames.PARSE_INTEGER + " expects a string");
			}
			int radix = 10;
			boolean junkAllowed = false;
			int start = 0;
			int end = str.value().length();
			for (int i = 1; i + 1 < args.size(); i += 2) {
				String key = (args.get(i) instanceof LispSymbol kw) ? kw.name() : "";
				LispVal value = args.get(i + 1);
				switch (key) {
					case LispNames.RADIX_KEYWORD -> radix = (int) asLong(value);
					case LispNames.JUNK_ALLOWED_KEYWORD -> junkAllowed = !(value instanceof LispNil);
					case LispNames.START_KEYWORD -> start = (int) asLong(value);
					case LispNames.END_KEYWORD -> end = (int) asLong(value);
					default -> throw new LispEvalException(LispNames.PARSE_INTEGER + ": unsupported keyword " + key);
				}
			}
			return parseInteger(str.value(), start, end, radix, junkAllowed);
		}));
	}

	// Shared parse-integer logic: trims whitespace, accepts an optional sign, and
	// accumulates digits in the given radix. With junkAllowed, stops at the first
	// non-digit and returns nil when no digits were seen; otherwise signals on junk.
	private static LispVal parseInteger(String s, int start, int end, int radix, boolean junkAllowed) {
		int i = start;
		while (i < end && Character.isWhitespace(s.charAt(i))) {
			i++;
		}
		int sign = 1;
		if (i < end && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
			sign = s.charAt(i) == '-' ? -1 : 1;
			i++;
		}
		java.math.BigInteger acc = java.math.BigInteger.ZERO;
		java.math.BigInteger base = java.math.BigInteger.valueOf(radix);
		boolean sawDigit = false;
		while (i < end) {
			int digit = Character.digit(s.charAt(i), radix);
			if (digit < 0) {
				break;
			}
			acc = acc.multiply(base).add(java.math.BigInteger.valueOf(digit));
			sawDigit = true;
			i++;
		}
		if (!junkAllowed) {
			while (i < end && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
			if (i != end) {
				throw new LispEvalException(LispNames.PARSE_INTEGER + ": junk in string \"" + s + "\"");
			}
		}
		if (!sawDigit) {
			if (junkAllowed) {
				return LispNil.INSTANCE;
			}
			throw new LispEvalException(LispNames.PARSE_INTEGER + ": no integer in string \"" + s + "\"");
		}
		return normalizeBig(acc.multiply(java.math.BigInteger.valueOf(sign)));
	}

	private static void registerCharacters(Environment env) {
		env.defineFunction(LispNames.CHAR, new LispFunction(LispNames.CHAR, args -> charRef(LispNames.CHAR, args)));
		env.defineFunction(LispNames.SCHAR, new LispFunction(LispNames.SCHAR, args -> charRef(LispNames.SCHAR, args)));
		env.defineFunction(LispNames.CHAR_CODE, new LispFunction(LispNames.CHAR_CODE, args -> {
			requireArgCount(LispNames.CHAR_CODE, args, 1);
			return new LispInteger(requireChar(LispNames.CHAR_CODE, args.get(0)).codePoint());
		}));
		env.defineFunction(LispNames.CODE_CHAR, new LispFunction(LispNames.CODE_CHAR, args -> {
			requireArgCount(LispNames.CODE_CHAR, args, 1);
			return new LispChar((int) asLong(args.get(0)));
		}));
		env.defineFunction(LispNames.CHAR_EQ,
				new LispFunction(LispNames.CHAR_EQ, args -> charCompareChain(LispNames.CHAR_EQ, args, 0, 0)));
		env.defineFunction(LispNames.CHAR_LT,
				new LispFunction(LispNames.CHAR_LT, args -> charCompareChain(LispNames.CHAR_LT, args, -1, -1)));
		env.defineFunction(LispNames.CHAR_LE,
				new LispFunction(LispNames.CHAR_LE, args -> charCompareChain(LispNames.CHAR_LE, args, -1, 0)));
		env.defineFunction(LispNames.CHAR_UPCASE, new LispFunction(LispNames.CHAR_UPCASE, args -> {
			requireArgCount(LispNames.CHAR_UPCASE, args, 1);
			return new LispChar(Character.toUpperCase(requireChar(LispNames.CHAR_UPCASE, args.get(0)).codePoint()));
		}));
		env.defineFunction(LispNames.CHAR_DOWNCASE, new LispFunction(LispNames.CHAR_DOWNCASE, args -> {
			requireArgCount(LispNames.CHAR_DOWNCASE, args, 1);
			return new LispChar(Character.toLowerCase(requireChar(LispNames.CHAR_DOWNCASE, args.get(0)).codePoint()));
		}));
		env.defineFunction(LispNames.CHARACTERP, new LispFunction(LispNames.CHARACTERP, args -> {
			requireArgCount(LispNames.CHARACTERP, args, 1);
			return args.get(0) instanceof LispChar ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ALPHA_CHAR_P, new LispFunction(LispNames.ALPHA_CHAR_P, args -> {
			requireArgCount(LispNames.ALPHA_CHAR_P, args, 1);
			return Character.isLetter(requireChar(LispNames.ALPHA_CHAR_P, args.get(0)).codePoint()) ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.DIGIT_CHAR_P, new LispFunction(LispNames.DIGIT_CHAR_P, args -> {
			requireMinArgCount(LispNames.DIGIT_CHAR_P, args, 1);
			int radix = args.size() > 1 ? (int) asLong(args.get(1)) : 10;
			int weight = Character.digit(requireChar(LispNames.DIGIT_CHAR_P, args.get(0)).codePoint(), radix);
			return weight < 0 ? LispNil.INSTANCE : new LispInteger(weight);
		}));
	}

	private static LispVal charRef(String name, java.util.List<LispVal> args) {
		requireArgCount(name, args, 2);
		String s = requireString(name, args.get(0));
		int index = requireIndex(name, args.get(1));
		if (index < 0 || index >= s.length()) {
			throw new LispEvalException(
					name + ": index " + index + " out of bounds for string of length " + s.length());
		}
		return new LispChar(s.charAt(index));
	}

	private static LispChar requireChar(String name, LispVal val) {
		if (val instanceof LispChar c) {
			return c;
		}
		throw new LispEvalException(name + " expects a character, got: " + val.print());
	}

	// Variadic character comparison, mirroring compareChain for numbers: true when
	// every adjacent pair's code-point comparison falls within [low, high].
	private static LispVal charCompareChain(String name, java.util.List<LispVal> args, int low, int high) {
		requireMinArgCount(name, args, 1);
		for (int i = 0; i + 1 < args.size(); i++) {
			int a = requireChar(name, args.get(i)).codePoint();
			int b = requireChar(name, args.get(i + 1)).codePoint();
			int cmp = Integer.compare(a, b);
			int normalized = cmp < 0 ? -1 : (cmp > 0 ? 1 : 0);
			if (normalized < low || normalized > high) {
				return LispNil.INSTANCE;
			}
		}
		return LispTrue.INSTANCE;
	}

	private static void registerPredicates(Environment env) {
		env.defineFunction(LispNames.NULL, new LispFunction(LispNames.NULL, args -> {
			requireArgCount(LispNames.NULL, args, 1);
			return args.get(0) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.NOT, new LispFunction(LispNames.NOT, args -> {
			requireArgCount(LispNames.NOT, args, 1);
			return args.get(0) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ATOM, new LispFunction(LispNames.ATOM, args -> {
			requireArgCount(LispNames.ATOM, args, 1);
			return !(args.get(0) instanceof LispCons) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.NUMBERP, new LispFunction(LispNames.NUMBERP, args -> {
			requireArgCount(LispNames.NUMBERP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger || arg instanceof LispRatio
					|| arg instanceof LispDouble) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.INTEGERP, new LispFunction(LispNames.INTEGERP, args -> {
			requireArgCount(LispNames.INTEGERP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.FLOATP, new LispFunction(LispNames.FLOATP, args -> {
			requireArgCount(LispNames.FLOATP, args, 1);
			return args.get(0) instanceof LispDouble ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.RATIONALP, new LispFunction(LispNames.RATIONALP, args -> {
			requireArgCount(LispNames.RATIONALP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger || arg instanceof LispRatio)
					? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.SYMBOLP, new LispFunction(LispNames.SYMBOLP, args -> {
			requireArgCount(LispNames.SYMBOLP, args, 1);
			return args.get(0) instanceof LispSymbol ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.STRINGP, new LispFunction(LispNames.STRINGP, args -> {
			requireArgCount(LispNames.STRINGP, args, 1);
			return args.get(0) instanceof LispString ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.LISTP, new LispFunction(LispNames.LISTP, args -> {
			requireArgCount(LispNames.LISTP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispCons || arg instanceof LispNil) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.CONSP, new LispFunction(LispNames.CONSP, args -> {
			requireArgCount(LispNames.CONSP, args, 1);
			return args.get(0) instanceof LispCons ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.KEYWORDP, new LispFunction(LispNames.KEYWORDP, args -> {
			requireArgCount(LispNames.KEYWORDP, args, 1);
			return args.get(0) instanceof LispSymbol sym && sym.isKeyword() ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ZEROP, new LispFunction(LispNames.ZEROP, args -> {
			requireArgCount(LispNames.ZEROP, args, 1);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) == 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			// A normalized LispBigInteger is always outside the long range, hence
			// non-zero, and a normalized LispRatio is never an integer, hence non-zero.
			if (args.get(0) instanceof LispBigInteger || args.get(0) instanceof LispRatio) {
				return LispNil.INSTANCE;
			}
			return asLong(args.get(0)) == 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.PLUSP, new LispFunction(LispNames.PLUSP, args -> {
			requireArgCount(LispNames.PLUSP, args, 1);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) > 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispRatio r) {
				return r.numerator().signum() > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().signum() > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.MINUSP, new LispFunction(LispNames.MINUSP, args -> {
			requireArgCount(LispNames.MINUSP, args, 1);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) < 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispRatio r) {
				return r.numerator().signum() < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().signum() < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.EVENP, new LispFunction(LispNames.EVENP, args -> {
			requireArgCount(LispNames.EVENP, args, 1);
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().testBit(0) ? LispNil.INSTANCE : LispTrue.INSTANCE;
			}
			return asLong(args.get(0)) % 2 == 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.ODDP, new LispFunction(LispNames.ODDP, args -> {
			requireArgCount(LispNames.ODDP, args, 1);
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().testBit(0) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) % 2 != 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static void registerListOps(Environment env) {
		env.defineFunction(LispNames.CONS, new LispFunction(LispNames.CONS, args -> {
			requireArgCount(LispNames.CONS, args, 2);
			return new LispCons(args.get(0), args.get(1));
		}));
		env.defineFunction(LispNames.CAR, new LispFunction(LispNames.CAR, args -> {
			requireArgCount(LispNames.CAR, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.car();
			}
			if (args.get(0) instanceof LispNil) {
				return LispNil.INSTANCE;
			}
			throw new LispEvalException("car expects a cons cell, got: " + args.get(0).print());
		}));
		env.defineFunction(LispNames.CDR, new LispFunction(LispNames.CDR, args -> {
			requireArgCount(LispNames.CDR, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.cdr();
			}
			if (args.get(0) instanceof LispNil) {
				return LispNil.INSTANCE;
			}
			throw new LispEvalException("cdr expects a cons cell, got: " + args.get(0).print());
		}));
		env.defineFunction(LispNames.FIRST, new LispFunction(LispNames.FIRST, args -> {
			requireArgCount(LispNames.FIRST, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.car();
			}
			throw new LispEvalException("first expects a cons cell, got: " + args.get(0).print());
		}));
		env.defineFunction(LispNames.REST, new LispFunction(LispNames.REST, args -> {
			requireArgCount(LispNames.REST, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.cdr();
			}
			throw new LispEvalException("rest expects a cons cell, got: " + args.get(0).print());
		}));
		env.defineFunction(LispNames.NTH, new LispFunction(LispNames.NTH, args -> {
			requireArgCount(LispNames.NTH, args, 2);
			return nthValue(LispNames.NTH, asLong(args.get(0)), args.get(1));
		}));
		env.defineFunction(LispNames.SECOND, new LispFunction(LispNames.SECOND, args -> {
			requireArgCount(LispNames.SECOND, args, 1);
			return nthValue(LispNames.SECOND, 1, args.get(0));
		}));
		env.defineFunction(LispNames.THIRD, new LispFunction(LispNames.THIRD, args -> {
			requireArgCount(LispNames.THIRD, args, 1);
			return nthValue(LispNames.THIRD, 2, args.get(0));
		}));
		env.defineFunction(LispNames.FOURTH, new LispFunction(LispNames.FOURTH, args -> {
			requireArgCount(LispNames.FOURTH, args, 1);
			return nthValue(LispNames.FOURTH, 3, args.get(0));
		}));
		env.defineFunction(LispNames.LIST, new LispFunction(LispNames.LIST, args -> {
			LispVal result = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= 0; i--) {
				result = new LispCons(args.get(i), result);
			}
			return result;
		}));
		env.defineFunction(LispNames.NTHCDR, new LispFunction(LispNames.NTHCDR, args -> {
			requireArgCount(LispNames.NTHCDR, args, 2);
			long n = asLong(args.get(0));
			LispVal list = args.get(1);
			for (long i = 0; i < n; i++) {
				if (list instanceof LispCons cons) {
					list = cons.cdr();
				}
				else {
					return LispNil.INSTANCE;
				}
			}
			return list;
		}));
		env.defineFunction(LispNames.RPLACA, new LispFunction(LispNames.RPLACA, args -> {
			requireArgCount(LispNames.RPLACA, args, 2);
			if (args.get(0) instanceof LispCons cons) {
				cons.setCar(args.get(1));
				return cons;
			}
			throw new LispEvalException("rplaca expects a cons cell, got: " + args.get(0).print());
		}));
		env.defineFunction(LispNames.RPLACD, new LispFunction(LispNames.RPLACD, args -> {
			requireArgCount(LispNames.RPLACD, args, 2);
			if (args.get(0) instanceof LispCons cons) {
				cons.setCdr(args.get(1));
				return cons;
			}
			throw new LispEvalException("rplacd expects a cons cell, got: " + args.get(0).print());
		}));
		env.defineFunction(LispNames.REMF_TAIL, new LispFunction(LispNames.REMF_TAIL, args -> {
			requireArgCount(LispNames.REMF_TAIL, args, 2);
			LispVal current = args.get(0);
			LispVal indicator = args.get(1);
			while (current instanceof LispCons currentCons) {
				LispVal valueCellVal = currentCons.cdr();
				if (!(valueCellVal instanceof LispCons valueCell)) {
					return LispNil.INSTANCE;
				}
				LispVal nextKeyCellVal = valueCell.cdr();
				if (!(nextKeyCellVal instanceof LispCons nextKeyCell)) {
					return LispNil.INSTANCE;
				}
				LispVal key = nextKeyCell.car();
				boolean match;
				if (key instanceof LispCons || indicator instanceof LispCons) {
					match = key == indicator;
				}
				else if (key instanceof LispNil && indicator instanceof LispNil) {
					match = true;
				}
				else if (key instanceof LispNil || indicator instanceof LispNil) {
					match = false;
				}
				else {
					match = key.equals(indicator);
				}
				if (match) {
					LispVal rest = nextKeyCell.cdr();
					if (rest instanceof LispCons restCons) {
						valueCell.setCdr(restCons.cdr());
					}
					else {
						valueCell.setCdr(LispNil.INSTANCE);
					}
					return LispTrue.INSTANCE;
				}
				current = nextKeyCell;
			}
			return LispNil.INSTANCE;
		}));
		env.defineFunction(LispNames.APPEND, new LispFunction(LispNames.APPEND, args -> {
			if (args.isEmpty()) {
				return LispNil.INSTANCE;
			}
			LispVal result = args.getLast();
			for (int i = args.size() - 2; i >= 0; i--) {
				result = appendTwo(args.get(i), result);
			}
			return result;
		}));
	}

	/**
	 * Walks {@code n} cdrs and returns the car, matching the macro expansion
	 * {@code (car (nthcdr n list))}: walking off the end yields nil, whose car is an
	 * error.
	 */
	private static LispVal nthValue(String name, long n, LispVal list) {
		LispVal cur = list;
		for (long i = 0; i < n && cur instanceof LispCons cons; i++) {
			cur = cons.cdr();
		}
		if (cur instanceof LispCons cons) {
			return cons.car();
		}
		throw new LispEvalException(name + " expects a cons cell, got: " + cur.print());
	}

	private static LispVal appendTwo(LispVal list, LispVal tail) {
		if (list instanceof LispNil) {
			return tail;
		}
		if (list instanceof LispCons cons) {
			return new LispCons(cons.car(), appendTwo(cons.cdr(), tail));
		}
		throw new LispEvalException("append expects a list, got: " + list.print());
	}

	private static void registerTypeConversion(Environment env) {
		env.defineFunction(LispNames.FLOAT, new LispFunction(LispNames.FLOAT, args -> {
			requireArgCount(LispNames.FLOAT, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispDouble) {
				return arg;
			}
			if (arg instanceof LispInteger i) {
				return new LispDouble((double) i.value());
			}
			if (arg instanceof LispBigInteger b) {
				return new LispDouble(b.value().doubleValue());
			}
			if (arg instanceof LispRatio r) {
				return new LispDouble(r.doubleValue());
			}
			throw new LispEvalException("float expects a number, got: " + arg.print());
		}));
		env.defineFunction(LispNames.TRUNCATE, new LispFunction(LispNames.TRUNCATE, args -> {
			requireArgCount(LispNames.TRUNCATE, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return new LispInteger((long) d.value());
			}
			if (arg instanceof LispRatio r) {
				return normalizeBig(r.truncate());
			}
			throw new LispEvalException("truncate expects a number, got: " + arg.print());
		}));
		env.defineFunction(LispNames.FLOOR, new LispFunction(LispNames.FLOOR, args -> {
			requireArgCount(LispNames.FLOOR, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return new LispInteger((long) Math.floor(d.value()));
			}
			if (arg instanceof LispRatio r) {
				return normalizeBig(r.floor());
			}
			throw new LispEvalException("floor expects a number, got: " + arg.print());
		}));
		env.defineFunction(LispNames.CEILING, new LispFunction(LispNames.CEILING, args -> {
			requireArgCount(LispNames.CEILING, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return new LispInteger((long) Math.ceil(d.value()));
			}
			if (arg instanceof LispRatio r) {
				return normalizeBig(r.ceiling());
			}
			throw new LispEvalException("ceiling expects a number, got: " + arg.print());
		}));
		env.defineFunction(LispNames.ROUND, new LispFunction(LispNames.ROUND, args -> {
			requireArgCount(LispNames.ROUND, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return new LispInteger((long) Math.rint(d.value()));
			}
			if (arg instanceof LispRatio r) {
				return normalizeBig(r.round());
			}
			throw new LispEvalException("round expects a number, got: " + arg.print());
		}));
		env.defineFunction(LispNames.NUMERATOR, new LispFunction(LispNames.NUMERATOR, args -> {
			requireArgCount(LispNames.NUMERATOR, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispRatio r) {
				return normalizeBig(r.numerator());
			}
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			throw new LispEvalException("numerator expects a rational, got: " + arg.print());
		}));
		env.defineFunction(LispNames.DENOMINATOR, new LispFunction(LispNames.DENOMINATOR, args -> {
			requireArgCount(LispNames.DENOMINATOR, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispRatio r) {
				return normalizeBig(r.denominator());
			}
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return new LispInteger(1);
			}
			throw new LispEvalException("denominator expects a rational, got: " + arg.print());
		}));
	}

	/**
	 * Returns the text that {@code princ} would print for the value (no quotes on
	 * strings). Mirrors the numeric special-casing of the princ builtin.
	 */
	private static String displayString(LispVal val) {
		if (val instanceof LispInteger i) {
			return Long.toString(i.value());
		}
		if (val instanceof LispDouble d) {
			return Double.toString(d.value());
		}
		return val.display();
	}

	/**
	 * Returns the text that {@code prin1} would print for the value (readable form,
	 * strings quoted). Mirrors the numeric special-casing of the prin1 builtin.
	 */
	private static String printString(LispVal val) {
		if (val instanceof LispInteger i) {
			return Long.toString(i.value());
		}
		if (val instanceof LispDouble d) {
			return Double.toString(d.value());
		}
		return val.print();
	}

	private static long asLong(LispVal val) {
		if (val instanceof LispInteger i) {
			return i.value();
		}
		throw new LispEvalException("Expected integer, got: " + val.print());
	}

	private static double asDouble(LispVal val) {
		if (val instanceof LispDouble d) {
			return d.value();
		}
		if (val instanceof LispInteger i) {
			return (double) i.value();
		}
		if (val instanceof LispBigInteger b) {
			return b.value().doubleValue();
		}
		if (val instanceof LispRatio r) {
			return r.doubleValue();
		}
		throw new LispEvalException("Expected number, got: " + val.print());
	}

	private static BigInteger asBigInteger(LispVal val) {
		if (val instanceof LispInteger i) {
			return BigInteger.valueOf(i.value());
		}
		if (val instanceof LispBigInteger b) {
			return b.value();
		}
		throw new LispEvalException("Expected integer, got: " + val.print());
	}

	/**
	 * Normalizes a {@link BigInteger} result, demoting it back to a {@link LispInteger}
	 * when it fits in a {@code long} so that fixnum-range values keep a single
	 * representation.
	 */
	private static LispVal normalizeBig(BigInteger value) {
		// bitLength() < 64 holds exactly for the signed long range [-2^63, 2^63-1].
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	private static LispVal addBig(List<LispVal> args) {
		BigInteger result = BigInteger.ZERO;
		for (LispVal arg : args) {
			result = result.add(asBigInteger(arg));
		}
		return normalizeBig(result);
	}

	private static LispVal subBig(List<LispVal> args) {
		if (args.size() == 1) {
			return normalizeBig(asBigInteger(args.get(0)).negate());
		}
		BigInteger result = asBigInteger(args.get(0));
		for (int i = 1; i < args.size(); i++) {
			result = result.subtract(asBigInteger(args.get(i)));
		}
		return normalizeBig(result);
	}

	private static LispVal mulBig(List<LispVal> args) {
		BigInteger result = BigInteger.ONE;
		for (LispVal arg : args) {
			result = result.multiply(asBigInteger(arg));
		}
		return normalizeBig(result);
	}

	/**
	 * Returns the numerator of a rational value (an integer is treated as itself over
	 * one).
	 */
	private static BigInteger numeratorOf(LispVal val) {
		if (val instanceof LispRatio r) {
			return r.numerator();
		}
		return asBigInteger(val);
	}

	/**
	 * Returns the denominator of a rational value (one for integers).
	 */
	private static BigInteger denominatorOf(LispVal val) {
		if (val instanceof LispRatio r) {
			return r.denominator();
		}
		if (val instanceof LispInteger || val instanceof LispBigInteger) {
			return BigInteger.ONE;
		}
		throw new LispEvalException("Expected rational, got: " + val.print());
	}

	private static LispVal addRational(List<LispVal> args) {
		BigInteger num = BigInteger.ZERO;
		BigInteger den = BigInteger.ONE;
		for (LispVal arg : args) {
			BigInteger argDen = denominatorOf(arg);
			num = num.multiply(argDen).add(numeratorOf(arg).multiply(den));
			den = den.multiply(argDen);
		}
		return LispRatio.valueOf(num, den);
	}

	private static LispVal subRational(List<LispVal> args) {
		if (args.size() == 1) {
			return LispRatio.valueOf(numeratorOf(args.get(0)).negate(), denominatorOf(args.get(0)));
		}
		BigInteger num = numeratorOf(args.get(0));
		BigInteger den = denominatorOf(args.get(0));
		for (int i = 1; i < args.size(); i++) {
			BigInteger argDen = denominatorOf(args.get(i));
			num = num.multiply(argDen).subtract(numeratorOf(args.get(i)).multiply(den));
			den = den.multiply(argDen);
		}
		return LispRatio.valueOf(num, den);
	}

	private static LispVal mulRational(List<LispVal> args) {
		BigInteger num = BigInteger.ONE;
		BigInteger den = BigInteger.ONE;
		for (LispVal arg : args) {
			num = num.multiply(numeratorOf(arg));
			den = den.multiply(denominatorOf(arg));
		}
		return LispRatio.valueOf(num, den);
	}

	/**
	 * Compares two rational values by cross-multiplication (denominators are always
	 * positive, so the comparison direction is preserved).
	 */
	private static int compareRational(LispVal a, LispVal b) {
		return numeratorOf(a).multiply(denominatorOf(b)).compareTo(numeratorOf(b).multiply(denominatorOf(a)));
	}

	/**
	 * Compares two numbers, returning -1, 0 or 1, promoting to the widest type present
	 * (double &gt; ratio &gt; bigint &gt; long).
	 */
	private static int compareNumeric(LispVal a, LispVal b) {
		if (a instanceof LispDouble || b instanceof LispDouble) {
			return Integer.signum(Double.compare(asDouble(a), asDouble(b)));
		}
		if (a instanceof LispRatio || b instanceof LispRatio) {
			return Integer.signum(compareRational(a, b));
		}
		if (a instanceof LispBigInteger || b instanceof LispBigInteger) {
			return Integer.signum(asBigInteger(a).compareTo(asBigInteger(b)));
		}
		return Integer.signum(Long.compare(asLong(a), asLong(b)));
	}

	/**
	 * Evaluates a variadic numeric comparison: true when, for every adjacent pair, the
	 * sign of the comparison falls within {@code [loSign, hiSign]}. A single argument is
	 * trivially true.
	 */
	private static LispVal compareChain(String name, List<LispVal> args, int loSign, int hiSign) {
		requireMinArgCount(name, args, 1);
		for (int i = 1; i < args.size(); i++) {
			int sign = compareNumeric(args.get(i - 1), args.get(i));
			if (sign < loSign || sign > hiSign) {
				return LispNil.INSTANCE;
			}
		}
		return LispTrue.INSTANCE;
	}

	private static boolean hasRatio(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispRatio) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasBigInteger(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispBigInteger) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasDouble(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispDouble) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Classifies an evaluated {@code open} element-type argument: the list
	 * {@code (unsigned-byte 8)} is binary, the symbol {@code character} is text; anything
	 * else is rejected.
	 * @param spec the evaluated element type specifier
	 * @return true for the binary element type
	 */
	private static boolean isBinaryElementType(LispVal spec) {
		if (spec instanceof LispSymbol sym && LispNames.CHARACTER_TYPE.equals(sym.name())) {
			return false;
		}
		if (spec instanceof LispCons cons) {
			List<LispVal> parts = cons.toList();
			if (parts.size() == 2 && parts.get(0) instanceof LispSymbol sym
					&& LispNames.UNSIGNED_BYTE.equals(sym.name()) && parts.get(1) instanceof LispInteger bits
					&& bits.value() == 8) {
				return true;
			}
		}
		throw new LispEvalException(
				LispNames.OPEN + " supports only the 'character or '(unsigned-byte 8) element type");
	}

	private static void requireArgCount(String name, List<LispVal> args, int expected) {
		if (args.size() != expected) {
			throw new LispEvalException(name + " expects " + expected + " arguments, got " + args.size());
		}
	}

	private static void requireMinArgCount(String name, List<LispVal> args, int min) {
		if (args.size() < min) {
			throw new LispEvalException(name + " expects at least " + min + " arguments, got " + args.size());
		}
	}

}
