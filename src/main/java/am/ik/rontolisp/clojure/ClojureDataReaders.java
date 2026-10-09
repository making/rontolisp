package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * A program's data readers, the oracle's {@code *data-readers*}: its startup
 * ({@code load-data-readers}) merges every {@code data_readers.clj} and then every
 * {@code data_readers.cljc} at the root of its classpath -- here every root of the source
 * path, in its order -- into one map from a tag symbol to the var whose function reads
 * the form a tagged literal of the tag carries. Measured on {@code clj} 1.12.6
 * (2026-10-08): only a file's first form is read, a {@code .cljc} one under
 * {@code :read-cond :allow}; it must be a map of symbols to qualified symbols; a tag two
 * files give different vars is refused, the same var twice is not; the namespace is not
 * loaded, so a tag read before something requires it calls an unbound var.
 *
 * <p>
 * A tag in source calls its var while the form is READ ({@link #read}): the lowering
 * reads each datum again after lowering the ones above it ({@link ClojureReader#again}),
 * so the function's namespace is loaded and its definitions are in the macro-time
 * evaluator, where it runs, and its answer becomes the datum in the tag's place, decoded
 * like a macro's. The map is also the root of {@code *data-readers*} ({@link #root}),
 * which {@code read-string} and {@code read} ask first at run time.
 */
final class ClojureDataReaders {

	/** The file names a root may hold, in the order the oracle merges them. */
	static final List<String> FILES = List.of("data_readers.clj", "data_readers.cljc");

	/** A program whose roots hold no data reader. */
	static final ClojureDataReaders NONE = new ClojureDataReaders(Map.of());

	/**
	 * What the call of a reader var answers at macro time when the var is not bound yet:
	 * a Common Lisp keyword, which no Clojure value is.
	 */
	private static final LispSymbol UNBOUND = new LispSymbol(":C%UNBOUND-READER");

	private static final LispSymbol CALL = new LispSymbol("RONTOLISP::%CLOJURE-CALL");

	/**
	 * One {@code data_readers} file at a root.
	 *
	 * @param path where it was read from ({@code lib.jar!/data_readers.clj} in a jar)
	 * @param text its contents
	 */
	record Source(String path, String text) {
	}

	/** Each tag's var ({@code ns/name}), in the order the files give them. */
	private final Map<String, String> vars;

	private ClojureDataReaders(Map<String, String> vars) {
		this.vars = vars;
	}

	/**
	 * The data readers the files map, merged in order like the oracle's
	 * {@code load-data-reader-file}, which a refusal positions in the file.
	 * @param sources the files, every {@code .clj} one ahead of every {@code .cljc} one
	 * @return the data readers
	 */
	static ClojureDataReaders of(List<Source> sources) {
		Map<String, String> vars = new LinkedHashMap<>();
		Map<String, String> origins = new LinkedHashMap<>();
		for (Source source : sources) {
			merge(vars, origins, source);
		}
		return vars.isEmpty() ? NONE : new ClojureDataReaders(Collections.unmodifiableMap(vars));
	}

	private static void merge(Map<String, String> vars, Map<String, String> origins, Source source) {
		// one form, its tags read as the oracle's startup reads them: only the defaults
		ClojureReader reader = new ClojureReader(source.text(), null, source.path().endsWith(".cljc"))
			.again(ClojureReader.Tags.NONE);
		LispVal first;
		try {
			first = reader.readTopLevel();
		}
		catch (LispReadException ex) {
			throw new LispReadException(ex.reason(), reader.here(source.path()));
		}
		LispVal map = first == null ? null : ClojureLowerUtil.stripMeta(first);
		if (map == null || !ClojureDepsEdn.isMap(map)) {
			throw new LispReadException("Not a valid data-reader map", at(reader, source, first));
		}
		List<LispVal> entries = ClojureDepsEdn.mapEntries(map);
		for (int i = 0; i + 1 < entries.size(); i += 2) {
			LispVal key = entries.get(i);
			if (!(ClojureLowerUtil.stripMeta(key) instanceof LispSymbol tagSymbol) || isValueOrKeyword(tagSymbol)) {
				throw new LispReadException("Invalid form in data-reader file: " + ClojureEdn.print(key),
						at(reader, source, key));
			}
			String tag = tagSymbol.name();
			String var = varOf(entries.get(i + 1), reader, source);
			String earlier = vars.get(tag);
			if (earlier != null && !earlier.equals(var)) {
				throw new LispReadException("Conflicting data-reader mapping: " + tag + " is #'" + var + " here and #'"
						+ earlier + " in " + origins.get(tag), at(reader, source, key));
			}
			vars.put(tag, var);
			origins.putIfAbsent(tag, source.path());
		}
	}

	/**
	 * The var a data reader names, {@code ns/name}: the oracle's {@code data-reader-var}
	 * takes the namespace and name of a symbol (or a keyword), so one without a namespace
	 * is its {@code no conversion to symbol}, and any other value cannot be cast to the
	 * {@code Named} both are.
	 */
	private static String varOf(LispVal value, ClojureReader reader, Source source) {
		LispVal bare = ClojureLowerUtil.stripMeta(value);
		if (bare instanceof LispSymbol symbol && !isValue(symbol)) {
			String spelling = symbol.name();
			if (spelling.startsWith(":")) {
				spelling = spelling.substring(1);
			}
			if (ClojureLowering.qualifierSlash(spelling) > 0 && !spelling.startsWith(":")) {
				return spelling;
			}
			throw new LispReadException("no conversion to symbol: " + ClojureEdn.print(value),
					at(reader, source, value));
		}
		throw new LispReadException(ClojureEdn.print(value) + " cannot be cast to clojure.lang.Named",
				at(reader, source, value));
	}

	private static boolean isValue(LispSymbol symbol) {
		String name = symbol.name();
		return name.equals("nil") || name.equals("true") || name.equals("false");
	}

	private static boolean isValueOrKeyword(LispSymbol symbol) {
		return isValue(symbol) || symbol.name().startsWith(":");
	}

	/** Where a datum of a data readers file starts, or the file's start. */
	private static SourceLocation at(ClojureReader reader, Source source, @Nullable LispVal datum) {
		SourceLocation found = datum == null ? null : reader.locate(datum);
		return found == null ? new SourceLocation(source.path(), 1, 1)
				: new SourceLocation(source.path(), found.line(), found.column());
	}

	/**
	 * The var of a tag.
	 * @param tag the tag as written
	 * @return its var ({@code ns/name}), or null when no file maps the tag
	 */
	@Nullable String varOf(String tag) {
		return this.vars.get(tag);
	}

	/**
	 * Whether no file maps a tag.
	 * @return {@code true} for a program without data readers
	 */
	boolean isEmpty() {
		return this.vars.isEmpty();
	}

	/**
	 * Whether a file maps {@code inst} or {@code uuid}, which then reads through it ahead
	 * of the default reader, like the oracle's {@code *data-readers*} ahead of its
	 * {@code default-data-readers}.
	 * @return {@code true} when a default tag has a data reader
	 */
	boolean takesADefaultTag() {
		return this.vars.containsKey("inst") || this.vars.containsKey("uuid");
	}

	/**
	 * Every tag and its var, in the order the files map them.
	 * @return the map
	 */
	Map<String, String> vars() {
		return this.vars;
	}

	/**
	 * A tagged literal read in source: the datum the tag's data reader answers for the
	 * form, or null for {@code #inst} and {@code #uuid} without one, which the default
	 * readers read. The function runs in the macro-time evaluator, which holds every
	 * definition lowered above the literal -- what the oracle's load has evaluated when
	 * it reads it -- under the {@code *ns*} and {@code *file*} of the reading file, its
	 * argument the form as a quote answers it, its answer decoded like a macro's. Refused
	 * like the oracle: a tag no file maps has no reader function (named with what the
	 * source path leaves unread, which may map it), a var whose definition is not lowered
	 * yet (its namespace not loaded, the var not defined) is
	 * {@code Attempting to call unbound fn}, a nil answer the reader's
	 * {@code No dispatch macro}, a value with no spelling to compile (an atom, a
	 * function, a host object) {@code Can't embed object in code}.
	 * @param ctx the hub, between two top-level datums
	 * @param tag the tag as written
	 * @param form the form read after it
	 * @return the datum, or null
	 */
	static @Nullable LispVal read(ClojureLowering ctx, String tag, LispVal form) {
		String var = ctx.sourcePath.dataReaders().varOf(tag);
		if (var == null) {
			if (tag.equals("inst") || tag.equals("uuid")) {
				return null; // the default reader's
			}
			// what the source path leaves unread may map it, like a namespace it may hold
			throw new LispReadException("No reader function for tag " + tag + ctx.sourcePath.notSearched());
		}
		ClojureMacroEvaluator evaluator = ctx.macroEvaluator;
		if (evaluator == null) {
			throw new LispReadException("the data reader #'" + var + " of #" + tag
					+ " cannot run without a macro evaluator (lower with one)");
		}
		LispVal call = callOf(ctx, var, ctx.quote(form));
		if (call == null) {
			throw unbound(var);
		}
		// the function reads the load's specials where the form is read, like a macro
		LispVal invocation = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureCoreSpecials.NS, ClojureCoreSpecials.namespaceObject(ctx.currentNs)),
				ClojureLowerUtil.list(ClojureCoreSpecials.FILE, LispString.literal(ctx.loadingFile)),
				ClojureLowerUtil.list(ClojureCoreSpecials.SOURCE_PATH, LispString.literal(ctx.loadingSourcePath))),
				call);
		ClojureMacroLowering.handOverCaughtClasses(ctx, evaluator);
		LispVal value;
		try {
			value = evaluator.evaluate(invocation);
		}
		catch (RuntimeException ex) {
			throw new LispReadException("in data reader #'" + var + ": " + ClojureMacroLowering.exMessage(ex));
		}
		if (value instanceof LispSymbol answered && answered.name().equals(UNBOUND.name())) {
			throw unbound(var);
		}
		if (value instanceof LispNil) {
			// the oracle's dispatch reader takes a nil answer for no reader at all
			throw new LispReadException("No dispatch macro for: " + Character.toString(tag.codePointAt(0)));
		}
		try {
			return ClojureMacroLowering.decodeDatum(ctx, value);
		}
		catch (LispReadException ex) {
			throw new LispReadException(
					"Can't embed object in code, maybe print-dup not defined: " + strOf(evaluator, value));
		}
	}

	/**
	 * The call of a reader var on the quoted form, or null when the var is unbound: a
	 * function definition is called where the macro-time evaluator has it, a value cell
	 * through the IFn dispatcher where it is bound -- either answering {@link #UNBOUND}
	 * before its definition lowered -- and a {@code clojure.core} or known library var
	 * through its value; a macro is the oracle's arity refusal. A shipped namespace the
	 * oracle loads at startup loads first. The oracle calls the var itself, so a private
	 * one is called too.
	 */
	private static @Nullable LispVal callOf(ClojureLowering ctx, String var, LispVal quoted) {
		int slash = var.indexOf('/');
		String ns = var.substring(0, slash);
		String name = var.substring(slash + 1);
		String key = ClojureLowering.varKey(ns, name);
		loadStartupNamespace(ctx, ns);
		ClojureLowering.Kind kind = ctx.globals.get(key);
		if (kind == ClojureLowering.Kind.FUNCTION) {
			LispSymbol fn = ctx.currentDefnSym(key);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("fboundp"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), fn)),
					ClojureLowerUtil.list(fn, quoted), UNBOUND);
		}
		if (kind == ClojureLowering.Kind.VARIABLE || kind == ClojureLowering.Kind.DECLARED) {
			LispSymbol cell = ClojureLowering.varSym(key);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("boundp"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), cell)),
					ClojureLowerUtil.list(CALL, cell, ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), quoted)),
					UNBOUND);
		}
		if (kind == ClojureLowering.Kind.MACRO) {
			// the oracle calls the macro's function, which takes the form and the
			// environment ahead of the argument
			throw new LispReadException("Wrong number of args (1) passed to: " + var);
		}
		LispVal value = kind != null ? null : libraryValue(ctx, ns, name);
		return value == null ? null
				: ClojureLowerUtil.list(CALL, value, ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), quoted));
	}

	/**
	 * The value of a var no program file defines: a {@code clojure.core} var's, a known
	 * library's ({@code clojure.string}, {@code clojure.set}, ...), else null.
	 */
	private static @Nullable LispVal libraryValue(ClojureLowering ctx, String ns, String name) {
		if (ns.equals("clojure.core")) {
			return ClojureCoreNames.isMacro(name) ? null : ctx.coreValueOrNull(name);
		}
		if (ClojureNamespaceLowering.isKnownVar(ns, name)) {
			return ClojureNamespaceLowering.namespaceValue(ctx, new ClojureLowering.VarRef(ns, name));
		}
		return null;
	}

	/**
	 * Loads a shipped namespace the oracle loads before the program
	 * ({@link ClojureBuiltinNamespaces#isStartup}) that a reader var names, if no form
	 * has yet: its forms go where the hub's hoisted forms go.
	 */
	private static void loadStartupNamespace(ClojureLowering ctx, String ns) {
		if (ClojureBuiltinNamespaces.isStartup(ns) && !ctx.loadedNamespaces.contains(ns)
				&& !ctx.loadingNamespaces.contains(ns)) {
			ClojureNamespaceLowering.preload(ctx, ns);
		}
	}

	private static LispReadException unbound(String var) {
		return new LispReadException("Attempting to call unbound fn: #'" + var);
	}

	/** {@code str} of a value the macro-time evaluator answered, for a refusal. */
	private static String strOf(ClojureMacroEvaluator evaluator, LispVal value) {
		try {
			LispVal str = evaluator.evaluate(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-STR-OF"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), value), LispString.literal(""),
					ClojureLowering.NIL_CONST));
			if (str instanceof LispString text) {
				return text.value();
			}
		}
		catch (RuntimeException ex) {
			// the value as the evaluator prints it
		}
		return value.print();
	}

	/**
	 * The root of {@code *data-readers*}: each tag symbol to its var, in the files'
	 * order. A var a program file defines reads its root, a {@code clojure.core} or known
	 * library var its value, and any other is unbound, like the oracle's, which interns
	 * it in a namespace of the name without loading it, so calling it is
	 * {@code Attempting to call unbound fn}. Each var carries its name and namespace as
	 * its metadata. A shipped namespace the oracle loads at startup loads first, its
	 * forms where the hub's hoisted forms go.
	 * @param ctx the hub, after the program lowered
	 * @return the lowered map
	 */
	static LispVal root(ClojureLowering ctx) {
		List<LispVal> plist = new ArrayList<>();
		plist.add(ClojureLowerUtil.sym("list"));
		for (Map.Entry<String, String> entry : ctx.sourcePath.dataReaders().vars().entrySet()) {
			plist.add(ctx.quote(new LispSymbol(entry.getKey())));
			plist.add(readerVar(ctx, entry.getValue()));
		}
		return ClojureCollectionLowering.tableFromPlist(ClojureLowerUtil.list(plist));
	}

	/**
	 * {@code default-data-readers}: the oracle's map of {@code uuid} to
	 * {@code #'clojure.uuid/default-uuid-reader} and {@code inst} to
	 * {@code #'clojure.instant/read-instant-date}, both namespaces loaded as at the
	 * oracle's startup.
	 * @param ctx the hub
	 * @return the lowered map
	 */
	static LispVal defaults(ClojureLowering ctx) {
		List<LispVal> plist = new ArrayList<>();
		plist.add(ClojureLowerUtil.sym("list"));
		plist.add(ctx.quote(new LispSymbol("uuid")));
		plist.add(readerVar(ctx, "clojure.uuid/default-uuid-reader"));
		plist.add(ctx.quote(new LispSymbol("inst")));
		plist.add(readerVar(ctx, "clojure.instant/read-instant-date"));
		return ClojureCollectionLowering.tableFromPlist(ClojureLowerUtil.list(plist));
	}

	/** The var a data reader names, as a lowered form ({@link #root}). */
	private static LispVal readerVar(ClojureLowering ctx, String var) {
		int slash = var.indexOf('/');
		String ns = var.substring(0, slash);
		String name = var.substring(slash + 1);
		String key = ClojureLowering.varKey(ns, name);
		loadStartupNamespace(ctx, ns);
		LispVal root;
		if (ctx.globals.containsKey(key)) {
			root = ClojureVarLowering.rootOfKey(ctx, key);
		}
		else {
			LispVal value = libraryValue(ctx, ns, name);
			LispVal unbound = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-UNBOUND"),
					LispString.literal(var));
			if (value != null) {
				root = value;
			}
			else if (ctx.session) {
				// a later input may define it: the function, else the value cell, else
				// unbound (the session runs on the interpreter, where both probes are
				// cheap)
				LispSymbol cell = ClojureLowering.varSym(key);
				LispVal quoted = ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), cell);
				root = ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
						ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("fboundp"), quoted),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), cell)),
						ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("boundp"), quoted), cell),
						ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, unbound));
			}
			else {
				root = unbound;
			}
		}
		LispVal meta = ctx.lower(ClojureLowerUtil.list(new LispSymbol("%hash-map"), new LispSymbol(":name"),
				ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(name)), new LispSymbol(":ns"),
				ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(ns))));
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-VAR"), LispString.literal(var),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), root), meta);
	}

	/**
	 * Gives {@code *data-readers*} its root where the run time reads the special: a
	 * program with data readers that reads at run time ({@code read-string},
	 * {@code read}) or names the special defines it over {@link #root} (kept for the
	 * special's definition, {@link ClojureLowering#dataReadersRoot}), so the run-time
	 * reader asks the map first. Once per lowering.
	 * @param ctx the hub, after the program (or a session's buffer) lowered
	 * @return the forms of a shipped namespace the root loaded, to run ahead of the
	 * program
	 */
	static List<LispVal> noteRuntimeReads(ClojureLowering ctx) {
		if (ctx.dataReadersRoot != null || ctx.sourcePath.dataReaders().isEmpty()
				|| !(ctx.usedReader || ctx.usedSpecials.contains("*data-readers*"))) {
			return List.of();
		}
		ctx.usedSpecials.add("*data-readers*");
		List<LispVal> outer = ctx.hoisted;
		ctx.hoisted = new ArrayList<>();
		try {
			ctx.dataReadersRoot = root(ctx);
			return ctx.hoisted;
		}
		finally {
			ctx.hoisted = outer;
		}
	}

}
