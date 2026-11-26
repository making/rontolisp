package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureDepsEdn.ArgMap;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Running a {@code deps.edn} project from the command line the way the oracle's
 * {@code clj} does: {@code -M} runs {@code clojure.main} over the selected aliases'
 * {@code :main-opts} and the arguments after them ({@code -m my.app args} calls
 * {@code my.app/-main}, a path runs a script, nothing is the REPL), {@code -X} calls a
 * function with a map, and a project's tests run under its {@code :test} alias. The
 * project is the working directory's, like the oracle's; the aliases are the files'
 * ({@link ClojureFiles#aliases}). What runs a namespace or a function is a generated
 * Clojure program, read without a file, so every backend runs or compiles it like any
 * other program: the command line's arguments are baked into it, ahead of whatever
 * arguments the program is later run with.
 */
public final class ClojureMain {

	/** What the command line runs. */
	public sealed interface Entry permits Program, Script, Repl {

	}

	/**
	 * A generated Clojure program, read without a file.
	 *
	 * @param source its text
	 */
	public record Program(String source) implements Entry {
	}

	/**
	 * A source file run like any entry file.
	 *
	 * @param file its path
	 * @param arguments the program's arguments
	 */
	public record Script(String file, List<String> arguments) implements Entry {
	}

	/**
	 * The REPL.
	 *
	 * @param arguments the program's arguments
	 */
	public record Repl(List<String> arguments) implements Entry {
	}

	/**
	 * What runs, and the oracle's warnings about the selection, each one line for
	 * standard error.
	 *
	 * @param entry what runs
	 * @param warnings the warnings
	 */
	public record Resolved(Entry entry, List<String> warnings) {
	}

	/** The namespaces a project test run picks, cognitect's test-runner default. */
	private static final String TEST_SUFFIX = "-test";

	private ClojureMain() {
	}

	/**
	 * {@code clj -M:aliases args}: {@code clojure.main} over the aliases'
	 * {@code :main-opts} (the last alias naming some wins) followed by the arguments.
	 * {@code -m NS args} calls {@code NS/-main} with the arguments, {@code -r} or nothing
	 * is the REPL, a path runs that file; {@code clojure.main}'s init options
	 * ({@code -e}, {@code -i}) are refused by name.
	 * @param files the project's files and the selected aliases
	 * @param args the arguments after {@code -M}
	 * @param replAliases whether {@code -A} selected aliases, which the oracle warns of
	 * when the selection names {@code :main-opts}
	 * @return what runs
	 */
	public static Resolved main(ClojureFiles files, List<String> args, boolean replAliases) {
		ClojureBasis basis = ClojureBasis.create(files, "", false);
		List<String> warnings = warnings(basis, replAliases);
		List<String> mainOpts = basis.args().strings(":main-opts");
		List<String> argv = new ArrayList<>(mainOpts);
		argv.addAll(args);
		return new Resolved(mainEntry(argv), warnings);
	}

	private static Entry mainEntry(List<String> argv) {
		if (argv.isEmpty()) {
			return new Repl(List.of());
		}
		String first = argv.get(0);
		List<String> rest = List.copyOf(argv.subList(1, argv.size()));
		return switch (first) {
			case "-m", "--main" -> {
				if (rest.isEmpty()) {
					throw new IllegalArgumentException(first + " needs a namespace to run: -m my.app");
				}
				yield new Program(namespaceProgram(rest.get(0), rest.subList(1, rest.size())));
			}
			case "-r", "--repl" -> new Repl(rest);
			case "-i", "--init", "-e", "--eval", "--report", "-h", "-?", "--help" -> throw new IllegalArgumentException(
					"clojure.main's " + first + " is not supported: run a namespace (-m my.app) or a file");
			case "-" -> throw new IllegalArgumentException(
					"clojure.main's - (a script read from standard input) is not supported: name the file");
			default -> new Script(first, rest);
		};
	}

	private static String namespaceProgram(String ns, List<String> args) {
		String name = symbolNamed(ns);
		if (name == null || name.indexOf('/') >= 0) {
			throw new IllegalArgumentException("-m takes a namespace name, got: " + ns);
		}
		StringBuilder out = new StringBuilder();
		out.append(";; clj -M -m ").append(name).append('\n');
		out.append("(require '").append(name).append(")\n");
		bakeArguments(args, out);
		out.append("(apply ").append(name).append("/-main *command-line-args*)\n");
		return out.toString();
	}

	/**
	 * {@code clj -X:aliases [fn] [k v]... [map]}: one function called with one map. The
	 * arguments, each read as EDN, are an optional function symbol, keys with their
	 * values, and an optional last map; the map starts as the aliases'
	 * {@code :exec-args}, takes each value at its key (a vector key is a path of keys)
	 * and then the last map's entries. Written from the CLI's documentation of {@code -X}
	 * and from what {@code clj} 1.12.6 answers to each shape of command line; the
	 * refusals are in its words.
	 * @param files the project's files and the selected aliases
	 * @param args the arguments after {@code -X}
	 * @param replAliases whether {@code -A} selected aliases too
	 * @return what runs
	 */
	public static Resolved exec(ClojureFiles files, List<String> args, boolean replAliases) {
		ClojureBasis basis = ClojureBasis.create(files, "", false);
		List<String> warnings = warnings(basis, replAliases);
		ArgMap aliasArgs = basis.args();
		List<LispVal> data = args.stream().map(ClojureMain::readArg).toList();
		int keysFrom = leadingFunctions(data);
		String function = execFunction(data, keysFrom, aliasArgs);
		LispVal map = execMap(data, keysFrom, aliasArgs.get(":exec-args"));
		StringBuilder out = new StringBuilder();
		out.append(";; clj -X ").append(function).append('\n');
		out.append("(require '").append(function, 0, function.indexOf('/')).append(")\n");
		bakeArguments(args, out);
		out.append('(').append(function).append(' ');
		out.append(map == null ? "nil" : "(quote " + ClojureEdn.print(map) + ")");
		out.append(")\n");
		return new Resolved(new Program(out.toString()), warnings);
	}

	/**
	 * {@code rontolisp test} for a Clojure project: the test namespaces -- the one a file
	 * declares, else every namespace whose name ends in {@code -test} below the selected
	 * aliases' {@code :extra-paths} ({@code test} for the root map's {@code :test}
	 * alias), cognitect's test-runner default -- required and run by
	 * {@code clojure.test/run-tests}, the process exiting 0 when every test passed and 1
	 * when one failed, errored, or none ran.
	 * @param files the project's files and the selected aliases
	 * @param file the test file, or {@code null} for the project's test namespaces
	 * @return what runs
	 */
	public static Resolved test(ClojureFiles files, @Nullable String file) {
		ClojureBasis basis = ClojureBasis.create(files, "", false);
		List<String> warnings = warnings(basis, false);
		TreeSet<String> namespaces = new TreeSet<>();
		String where;
		if (file != null) {
			String text = files.read(file);
			if (text == null) {
				throw new IllegalArgumentException(file + ": no such file");
			}
			String ns = ClojureLowering.firstNsName(new ClojureReader(text, file).readAll());
			if (ns == null) {
				throw new IllegalArgumentException(file + " declares no namespace to test");
			}
			namespaces.add(ns);
			where = file;
		}
		else {
			List<String> dirs = basis.extraPaths();
			if (dirs.isEmpty()) {
				throw new IllegalArgumentException("the selected aliases " + files.aliases()
						+ " add no :extra-paths, where a project's test namespaces are looked for");
			}
			for (String dir : dirs) {
				collectTestNamespaces(files, dir, namespaces);
			}
			where = String.join(", ", dirs);
			if (namespaces.isEmpty()) {
				throw new IllegalArgumentException(
						"no test namespace (a namespace whose name ends in " + TEST_SUFFIX + ") below " + where);
			}
		}
		StringBuilder quoted = new StringBuilder();
		for (String ns : namespaces) {
			quoted.append(" '").append(ns);
		}
		StringBuilder out = new StringBuilder();
		out.append(";; rontolisp test: the namespaces' clojure.test run, its verdict the exit code\n");
		out.append("(require 'clojure.test").append(quoted).append(")\n");
		out.append("(let [summary (clojure.test/run-tests").append(quoted).append(")]\n");
		out.append("  (when (zero? (:test summary))\n");
		out.append("    (binding [*out* *err*] (println \"rontolisp test: no tests were run in\" ")
			.append(ClojureEdn.print(LispString.literal(where)))
			.append(")))\n");
		out.append("  (System/exit (if (and (pos? (:test summary)) (clojure.test/successful? summary)) 0 1)))\n");
		return new Resolved(new Program(out.toString()), warnings);
	}

	private static void collectTestNamespaces(ClojureFiles files, String dir, TreeSet<String> out) {
		List<String> entries = files.list(dir);
		if (entries == null) {
			return;
		}
		for (String entry : entries) {
			if (entry.endsWith("/")) {
				collectTestNamespaces(files, files.resolve(dir, entry.substring(0, entry.length() - 1)), out);
				continue;
			}
			if (!entry.endsWith(".clj") && !entry.endsWith(".cljc")) {
				continue;
			}
			String path = files.resolve(dir, entry);
			String text = files.read(path);
			if (text == null) {
				continue;
			}
			String ns = ClojureLowering.firstNsName(new ClojureReader(text, path).readAll());
			if (ns != null && ns.endsWith(TEST_SUFFIX)) {
				out.add(ns);
			}
		}
	}

	/**
	 * The oracle's warnings about the selection, in its order: a selected alias no map
	 * defines, then {@code :main-opts} named while {@code -A} selected aliases -- in any
	 * mode, {@code -X} included (measured on {@code clj} 1.12.6, 2026-10-08).
	 */
	private static List<String> warnings(ClojureBasis basis, boolean replAliases) {
		List<String> out = new ArrayList<>();
		if (!basis.undeclared().isEmpty()) {
			out.add("WARNING: Specified aliases are undeclared and are not being used: ["
					+ String.join(" ", basis.undeclared()) + "]");
		}
		if (replAliases && !basis.args().strings(":main-opts").isEmpty()) {
			out.add("WARNING: Use of :main-opts with -A is deprecated. Use -M instead.");
		}
		return out;
	}

	/**
	 * The arguments the command line gives a generated program, ahead of those it is run
	 * with: {@code *command-line-args*} as the oracle's {@code clojure.main} binds it.
	 */
	private static void bakeArguments(List<String> args, StringBuilder out) {
		if (args.isEmpty()) {
			return;
		}
		out.append("(set! *command-line-args* (seq (concat [");
		for (int i = 0; i < args.size(); i++) {
			out.append(i == 0 ? "" : " ").append(ClojureEdn.print(LispString.literal(args.get(i))));
		}
		out.append("] *command-line-args*)))\n");
	}

	/** The symbol a text spells, or {@code null} when it spells something else. */
	private static @Nullable String symbolNamed(String text) {
		try {
			List<LispVal> read = new ClojureReader(text, null).readAll();
			return read.size() == 1 && isSymbol(read.get(0)) && ((LispSymbol) read.get(0)).name().equals(text) ? text
					: null;
		}
		catch (LispReadException ex) {
			return null;
		}
	}

	private static boolean isSymbol(LispVal value) {
		return value instanceof LispSymbol symbol && !symbol.name().startsWith(":") && !ClojureDepsEdn.isNil(value)
				&& !symbol.name().equals("true") && !symbol.name().equals("false");
	}

	/** One argument read as EDN, like the oracle's {@code edn/read-string}. */
	private static LispVal readArg(String arg) {
		if (arg.equals("-")) {
			throw new IllegalArgumentException("-X arguments read from standard input (-) are not supported");
		}
		try {
			List<LispVal> read = ClojureReader.forEdn(arg, "<argument>").readAll();
			return read.isEmpty() ? new LispSymbol("nil") : read.get(0);
		}
		catch (LispReadException ex) {
			throw new IllegalArgumentException("Unreadable arg: " + ClojureEdn.print(LispString.literal(arg)));
		}
	}

	/**
	 * How many of the {@code -X} arguments name the function: one when the first is a
	 * symbol and what follows it is keys with their values and at most a last map, none
	 * when the arguments are that by themselves. A first symbol that could be either is
	 * the function ({@code clj -X:a run b c '{:m 1}'} calls {@code run} with {@code {b c,
	 * :m 1}}).
	 */
	private static int leadingFunctions(List<LispVal> data) {
		if (!data.isEmpty() && isSymbol(data.get(0)) && areKeysAndValues(data, 1)) {
			return 1;
		}
		if (areKeysAndValues(data, 0)) {
			return 0;
		}
		List<LispVal> vector = new ArrayList<>();
		vector.add(ClojureReader.VECTOR);
		vector.addAll(data);
		throw new IllegalArgumentException(
				"Problem parsing arguments:  Insufficient input " + ClojureEdn.print(ClojureLowerUtil.list(vector)));
	}

	/**
	 * Whether the arguments from {@code from} pair up, a map allowed to stand alone last.
	 */
	private static boolean areKeysAndValues(List<LispVal> data, int from) {
		return (data.size() - from) % 2 == 0 || ClojureDepsEdn.isMap(data.getLast());
	}

	/**
	 * The function {@code -X} calls, qualified: the command line's, else the aliases'
	 * {@code :exec-fn}. A qualified name's namespace part may be one of the aliases'
	 * {@code :ns-aliases}; a bare name belongs to their {@code :ns-default}.
	 */
	private static String execFunction(List<LispVal> data, int keysFrom, ArgMap aliasArgs) {
		LispVal named = keysFrom == 1 ? data.get(0) : aliasArgs.get(":exec-fn");
		if (named == null || ClojureDepsEdn.isNil(named)) {
			// clj then takes a first symbol for the function after all and misses a value
			if (!data.isEmpty() && isSymbol(data.get(0))) {
				throw new IllegalArgumentException("Key is missing value: " + ClojureEdn.print(data.getLast()));
			}
			throw new IllegalArgumentException("No function found on command line or in :exec-fn");
		}
		if (!isSymbol(named)) {
			throw new IllegalArgumentException("Expected function symbol: " + ClojureEdn.print(named));
		}
		String name = ((LispSymbol) named).name();
		int slash = name.indexOf('/');
		if (slash <= 0 || slash == name.length() - 1) {
			String home = aliasArgs.symbol(":ns-default");
			if (home == null) {
				throw new IllegalArgumentException("Unqualified function can't be resolved: " + name);
			}
			return home + "/" + name;
		}
		String standsFor = aliasArgs.nsAliases().get(name.substring(0, slash));
		return standsFor == null ? name : standsFor + name.substring(slash);
	}

	/**
	 * The map {@code -X} hands the function, or {@code null} for none: {@code execArgs}
	 * with every value of the command line stored at its key, in order, and the last
	 * map's entries over that.
	 */
	private static @Nullable LispVal execMap(List<LispVal> data, int keysFrom, @Nullable LispVal execArgs) {
		LispVal map = execArgs == null || ClojureDepsEdn.isNil(execArgs) ? null : execArgs;
		if (map != null && !ClojureDepsEdn.isMap(map)) {
			throw new IllegalArgumentException("the aliases' :exec-args must be a map, got: " + ClojureEdn.print(map));
		}
		int values = (data.size() - keysFrom) / 2;
		for (int i = 0; i < values; i++) {
			LispVal key = data.get(keysFrom + 2 * i);
			// a vector key is a path of keys; an empty one stores under nil, like clj
			List<LispVal> path = ClojureDepsEdn.isVector(key) ? ClojureDepsEdn.elements(key) : List.of(key);
			map = stored(map, path.isEmpty() ? List.of(new LispSymbol("nil")) : path, 0,
					data.get(keysFrom + 2 * i + 1));
		}
		if ((data.size() - keysFrom) % 2 == 1) {
			map = map == null ? data.getLast() : ClojureDepsEdn.mergeMapDatums(map, data.getLast());
		}
		return map;
	}

	/**
	 * {@code map} with {@code value} stored under the keys of {@code path} from
	 * {@code at} on.
	 */
	private static LispVal stored(@Nullable LispVal map, List<LispVal> path, int at, LispVal value) {
		LispVal key = path.get(at);
		if (map != null && !ClojureDepsEdn.isMap(map)) {
			throw new IllegalArgumentException(
					"-X cannot assoc " + ClojureEdn.print(key) + " into " + ClojureEdn.print(map));
		}
		LispVal held = value;
		if (at + 1 < path.size()) {
			LispVal below = map == null ? null : ClojureDepsEdn.mapGet(map, key);
			held = stored(below == null || ClojureDepsEdn.isNil(below) ? null : below, path, at + 1, value);
		}
		LispVal entry = ClojureLowerUtil.list(new LispSymbol("%hash-map"), key, held);
		return map == null ? entry : ClojureDepsEdn.mergeMapDatums(map, entry);
	}

}
