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
 * function with a map ({@code clojure.run.exec}), and a project's tests run under its
 * {@code :test} alias. The project is the working directory's, like the oracle's; the
 * aliases are the files' ({@link ClojureFiles#aliases}). What runs a namespace or a
 * function is a generated Clojure program, read without a file, so every backend runs or
 * compiles it like any other program: the command line's arguments are baked into it,
 * ahead of whatever arguments the program is later run with.
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
	 * {@code clj -X:aliases [fn] [k v]... [map]}: the function the arguments or the
	 * aliases' {@code :exec-fn} name (qualified through {@code :ns-aliases} and
	 * {@code :ns-default}) called with one map, the aliases' {@code :exec-args} under the
	 * key-value arguments (a vector key is a path) and a trailing map, each argument read
	 * as EDN -- the oracle's {@code clojure.run.exec}, refused in its words.
	 * @param files the project's files and the selected aliases
	 * @param args the arguments after {@code -X}
	 * @param replAliases whether {@code -A} selected aliases too
	 * @return what runs
	 */
	public static Resolved exec(ClojureFiles files, List<String> args, boolean replAliases) {
		ClojureBasis basis = ClojureBasis.create(files, "", false);
		List<String> warnings = warnings(basis, replAliases);
		ArgMap aliasArgs = basis.args();
		List<LispVal> read = new ArrayList<>();
		for (String arg : args) {
			read.add(readArg(arg));
		}
		ExecArgs parsed = ExecArgs.parse(read);
		LispVal fn = parsed.function() != null ? parsed.function() : aliasArgs.get(":exec-fn");
		if (fn == null || ClojureDepsEdn.isNil(fn)) {
			if (!parsed.overrides().isEmpty() && isSymbol(parsed.overrides().get(0))) {
				throw new IllegalArgumentException(
						"Key is missing value: " + ClojureEdn.print(parsed.overrides().getLast()));
			}
			throw new IllegalArgumentException("No function found on command line or in :exec-fn");
		}
		String qualified = qualify(fn, aliasArgs);
		LispVal execArgs = aliasArgs.get(":exec-args");
		LispVal map = execArgs == null || ClojureDepsEdn.isNil(execArgs) ? null : execArgs;
		if (map != null && !ClojureDepsEdn.isMap(map)) {
			throw new IllegalArgumentException("the aliases' :exec-args must be a map, got: " + ClojureEdn.print(map));
		}
		List<LispVal> overrides = parsed.overrides();
		for (int i = 0; i + 1 < overrides.size(); i += 2) {
			map = assocIn(map, overrides.get(i), overrides.get(i + 1));
		}
		if (parsed.trailing() != null) {
			map = map == null ? parsed.trailing() : ClojureDepsEdn.mergeMapDatums(map, parsed.trailing());
		}
		int slash = qualified.indexOf('/');
		StringBuilder out = new StringBuilder();
		out.append(";; clj -X ").append(qualified).append('\n');
		out.append("(require '").append(qualified, 0, slash).append(")\n");
		bakeArguments(args, out);
		out.append('(').append(qualified).append(' ');
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

	/** {@code qualify-fn}: through {@code :ns-aliases}, else {@code :ns-default}. */
	private static String qualify(LispVal fn, ArgMap aliasArgs) {
		if (!isSymbol(fn)) {
			throw new IllegalArgumentException("Expected function symbol: " + ClojureEdn.print(fn));
		}
		String name = ((LispSymbol) fn).name();
		int slash = name.indexOf('/');
		if (slash > 0 && slash < name.length() - 1) {
			Map<String, String> nsAliases = aliasArgs.nsAliases();
			String ns = nsAliases.get(name.substring(0, slash));
			return ns == null ? name : ns + name.substring(slash);
		}
		String nsDefault = aliasArgs.symbol(":ns-default");
		if (nsDefault == null) {
			throw new IllegalArgumentException("Unqualified function can't be resolved: " + name);
		}
		return nsDefault + "/" + name;
	}

	/** {@code assoc}, or {@code assoc-in} for a vector key, on a map datum or nil. */
	private static LispVal assocIn(@Nullable LispVal map, LispVal key, LispVal value) {
		List<LispVal> path = ClojureDepsEdn.isVector(key) ? ClojureDepsEdn.elements(key) : List.of(key);
		if (path.isEmpty()) {
			// (assoc-in m [] v) is (assoc m nil v)
			path = List.of(new LispSymbol("nil"));
		}
		return assocPath(map, path, value);
	}

	private static LispVal assocPath(@Nullable LispVal map, List<LispVal> path, LispVal value) {
		if (map != null && !ClojureDepsEdn.isMap(map)) {
			throw new IllegalArgumentException(
					"-X cannot assoc " + ClojureEdn.print(path.get(0)) + " into " + ClojureEdn.print(map));
		}
		LispVal key = path.get(0);
		LispVal inner = value;
		if (path.size() > 1) {
			LispVal existing = map == null ? null : ClojureDepsEdn.mapGet(map, key);
			inner = assocPath(existing == null || ClojureDepsEdn.isNil(existing) ? null : existing,
					path.subList(1, path.size()), value);
		}
		LispVal entry = ClojureLowerUtil.list(new LispSymbol("%hash-map"), key, inner);
		return map == null ? entry : ClojureDepsEdn.mergeMapDatums(map, entry);
	}

	/**
	 * The {@code -X} arguments split like the oracle's {@code arg-spec}: an optional
	 * function symbol, key-value pairs, an optional trailing map. Where both a reading
	 * with the function and one without fit, the function wins (measured on {@code clj}
	 * 1.12.6, 2026-10-08: {@code a b c {:m 1}} calls {@code a} with {@code {b c, :m 1}}).
	 *
	 * @param function the function symbol, or {@code null}
	 * @param overrides the keys and values, alternating
	 * @param trailing the trailing map, or {@code null}
	 */
	private record ExecArgs(@Nullable LispVal function, List<LispVal> overrides, @Nullable LispVal trailing) {

		static ExecArgs parse(List<LispVal> args) {
			if (!args.isEmpty() && isSymbol(args.get(0))) {
				ExecArgs withFunction = pairs(args.subList(1, args.size()), args.get(0));
				if (withFunction != null) {
					return withFunction;
				}
			}
			ExecArgs without = pairs(args, null);
			if (without == null) {
				List<LispVal> vector = new ArrayList<>();
				vector.add(ClojureReader.VECTOR);
				vector.addAll(args);
				throw new IllegalArgumentException("Problem parsing arguments:  Insufficient input "
						+ ClojureEdn.print(ClojureLowerUtil.list(vector)));
			}
			return without;
		}

		private static @Nullable ExecArgs pairs(List<LispVal> rest, @Nullable LispVal function) {
			if (rest.size() % 2 == 0) {
				return new ExecArgs(function, List.copyOf(rest), null);
			}
			LispVal last = rest.getLast();
			if (!ClojureDepsEdn.isMap(last)) {
				return null;
			}
			return new ExecArgs(function, List.copyOf(rest.subList(0, rest.size() - 1)), last);
		}

	}

}
