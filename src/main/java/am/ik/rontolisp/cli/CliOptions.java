package am.ik.rontolisp.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Parser and container for command-line options.
 */
public class CliOptions {

	private final Map<String, String> options;

	private final List<String> arguments;

	private final @Nullable ClojureRun clojureRun;

	/** The positional arguments after the first, where the command takes several. */
	private List<String> further = List.of();

	private static final String NOKEY = "__";

	/**
	 * A {@code deps.edn} run the oracle's way, {@code -M[:aliases] args} or
	 * {@code -X[:aliases] args}: like {@code clj}, the flag ends rontolisp's own options
	 * and everything after it is the run's, verbatim.
	 *
	 * @param exec whether it is {@code -X} (a function called with a map) rather than
	 * {@code -M} ({@code clojure.main})
	 * @param aliases the aliases glued to the flag, as keyword spellings ({@code :dev})
	 * @param arguments the arguments after the flag
	 */
	public record ClojureRun(boolean exec, List<String> aliases, List<String> arguments) {

		/**
		 * The run.
		 * @param exec whether it is {@code -X}
		 * @param aliases the aliases
		 * @param arguments the arguments after the flag
		 */
		public ClojureRun {
			aliases = List.copyOf(aliases);
			arguments = List.copyOf(arguments);
		}

	}

	/**
	 * Create a new instance wrapping the given options map, with no program arguments.
	 * @param options the parsed options
	 */
	public CliOptions(Map<String, String> options) {
		this(options, List.of());
	}

	/**
	 * Create a new instance wrapping the given options map and program arguments.
	 * @param options the parsed options
	 * @param arguments the arguments after the {@code --} separator, in order
	 */
	public CliOptions(Map<String, String> options, List<String> arguments) {
		this(options, arguments, null);
	}

	/**
	 * Create a new instance with a {@code deps.edn} run.
	 * @param options the parsed options
	 * @param arguments the arguments after the {@code --} separator, in order
	 * @param clojureRun the {@code -M}/{@code -X} run, or {@code null}
	 */
	public CliOptions(Map<String, String> options, List<String> arguments, @Nullable ClojureRun clojureRun) {
		this.options = Collections.unmodifiableMap(options);
		this.arguments = List.copyOf(arguments);
		this.clojureRun = clojureRun;
	}

	/**
	 * The {@code -M}/{@code -X} run the command line ends with.
	 * @return the run, or {@code null} when there is none
	 */
	public @Nullable ClojureRun clojureRun() {
		return this.clojureRun;
	}

	/**
	 * The {@code deps.edn} aliases the command line selects, the oracle's order: the
	 * {@code -M}/{@code -X} aliases, then every {@code -A}'s.
	 * @return the aliases' keyword spellings, empty when none is selected
	 */
	public List<String> clojureAliases() {
		List<String> out = new ArrayList<>();
		if (this.clojureRun != null) {
			out.addAll(this.clojureRun.aliases());
		}
		String repl = this.options.get("-A");
		if (repl != null) {
			out.addAll(parseAliases(repl));
		}
		return List.copyOf(out);
	}

	/**
	 * Concatenated alias names as the oracle's {@code parse-kws} reads them:
	 * {@code :a:b/c} is {@code :a} and {@code :b/c}, a blank name is skipped.
	 * @param text the text after the flag
	 * @return the aliases' keyword spellings
	 */
	static List<String> parseAliases(String text) {
		List<String> out = new ArrayList<>();
		for (String name : text.split(":", -1)) {
			if (!name.isBlank()) {
				out.add(":" + name);
			}
		}
		return out;
	}

	/**
	 * The PROGRAM's own arguments: everything after the {@code --} separator, which is
	 * where rontolisp's options end and the interpreted program's begin. They reach the
	 * program as {@code (uiop:command-line-arguments)}; the input file is its
	 * {@code (uiop:argv0)}, so the separator is what keeps a rontolisp option out of a
	 * vector the program is entitled to read as its own.
	 * @return the arguments after {@code --}, empty when there was no separator
	 */
	public List<String> arguments() {
		return this.arguments;
	}

	/**
	 * Return whether this options set is empty.
	 * @return {@code true} if no options are present
	 */
	public boolean isEmpty() {
		return this.options.isEmpty();
	}

	/**
	 * Get the value for the given option key.
	 * @param key the option key
	 * @return the value, or {@code null} if not present
	 */
	@Nullable public String get(String key) {
		return this.options.get(key);
	}

	/**
	 * Check whether the given option key is present.
	 * @param key the option key
	 * @return {@code true} if the key is present
	 */
	public boolean contains(String key) {
		return this.options.containsKey(key);
	}

	/**
	 * Get the positional (non-keyed) argument value.
	 * @return the positional value, or {@code null} if not present
	 */
	@Nullable public String getNokey() {
		return this.get(NOKEY);
	}

	/**
	 * Check whether a positional (non-keyed) argument is present.
	 * @return {@code true} if a positional argument is present
	 */
	public boolean containsNoKey() {
		return this.contains(NOKEY);
	}

	/**
	 * Every positional argument, in order: the input file, then the further ones a
	 * command taking several was given ({@link #build(String[], boolean)}).
	 * @return the positional arguments, empty when there is none
	 */
	public List<String> inputs() {
		String first = getNokey();
		if (first == null) {
			return List.of();
		}
		List<String> inputs = new ArrayList<>();
		inputs.add(first);
		inputs.addAll(this.further);
		return List.copyOf(inputs);
	}

	/**
	 * Parse command-line arguments into a {@link CliOptions} instance.
	 * <p>
	 * A value is written either as the following argument ({@code -o out.wasm}) or glued
	 * to the key with an {@code =} ({@code --optimize=size}). The two forms are not
	 * interchangeable: a key that is no {@link CliOption.Arity#VALUE} still takes NO
	 * following argument, so {@code --optimize -o out.wasm} keeps meaning the bare flag
	 * plus an output file rather than reading {@code -o} as the level. That is why a
	 * valued flag whose bare form must keep working can only be spelled with {@code =}.
	 * <p>
	 * The oracle's {@code deps.edn} alias flags are written as {@code clj} writes them,
	 * the aliases glued on ({@code -A:dev}, {@code -M:dev:test}); {@code -M} and
	 * {@code -X} end the options, everything after one belonging to the run
	 * ({@link #clojureRun}).
	 * <p>
	 * Every key is an entry of {@link CliOptionTable}; any other is refused, as is one
	 * only {@code rontolisp test} takes.
	 * @param args the command-line arguments
	 * @return the parsed options
	 * @throws IllegalArgumentException when an option is unknown or lacks its value
	 */
	public static CliOptions build(String[] args) {
		return build(args, false);
	}

	/**
	 * {@link #build(String[])}, for {@code rontolisp test} where {@code test} is set: it
	 * takes several positional arguments ({@code rontolisp test A.java B.java}), those
	 * after the first {@link #inputs()}'s, and its own options beside the others.
	 * @param args the command-line arguments
	 * @param test whether the command line is {@code rontolisp test}'s
	 * @return the parsed options
	 */
	public static CliOptions build(String[] args, boolean test) {
		final Map<String, String> options = new LinkedHashMap<>();
		final List<String> further = new ArrayList<>();
		final List<String> arguments = new ArrayList<>();
		String key = null;
		boolean separated = false;
		for (int index = 0; index < args.length; index++) {
			String arg = args[index];
			// Everything after the FIRST `--` belongs to the program, verbatim: a second
			// `--`, a leading dash, a name that would otherwise be an option. That is the
			// only way an interpreted program can be handed an argument at all, and the
			// convention upstream's uiop:command-line-arguments documents for an image
			// that is not itself the executable.
			if (separated) {
				arguments.add(arg);
				continue;
			}
			if (key == null && "--".equals(arg)) {
				separated = true;
				continue;
			}
			if (key == null && isClojureFlag(arg)) {
				char flag = arg.charAt(1);
				String aliases = arg.substring(2);
				if (flag == 'T') {
					throw new IllegalArgumentException("'" + arg + "': tools (clj -T) are not supported; run a"
							+ " namespace with -M -m, or a function with -X");
				}
				if (flag == 'A') {
					if (aliases.isEmpty()) {
						throw new IllegalArgumentException("-A requires an alias");
					}
					// every -A adds to the selection, like the oracle's repl_aliases
					options.merge("-A", aliases, String::concat);
					continue;
				}
				// -M / -X end rontolisp's options: what follows is the run's, verbatim
				return new CliOptions(options, arguments, new ClojureRun(flag == 'X', parseAliases(aliases),
						List.of(Arrays.copyOfRange(args, index + 1, args.length))))
					.withFurther(further);
			}
			if (key == null) {
				// --key=value: everything after the FIRST '=' is the value, so a value
				// may itself contain one.
				int eq = arg.indexOf('=');
				if (arg.equals("-") || !arg.startsWith("-")) {
					// There is exactly one positional argument (the input file) but
					// where the command takes several, so a second one is a mistake --
					// and the likeliest mistake is spelling a valued option with a space,
					// where the value lands here and used to silently REPLACE the input
					// file (`--optimize size` compiled a file named "size"). A command
					// taking several checks each.
					if (options.containsKey(NOKEY) && test) {
						further.add(arg);
					}
					else if (options.containsKey(NOKEY)) {
						throw new IllegalArgumentException("unexpected extra argument '" + arg
								+ "': rontolisp takes one input file, and an option value is written"
								+ " with '=' (as in --optimize=size)");
					}
					else {
						options.put(NOKEY, arg);
					}
				}
				else if (eq > 0) {
					CliOption option = option(arg.substring(0, eq), test);
					put(options, option, arg.substring(eq + 1));
				}
				else {
					CliOption option = option(arg, test);
					if (option.takesFollowingValue()) {
						key = arg;
					}
					else {
						options.put(option.names().get(0), "");
					}
				}
			}
			else {
				put(options, option(key, test), arg);
				key = null;
			}
		}
		// A valued option written last with nothing after it used to be dropped, silently
		// changing the mode instead of reporting anything: a trailing -e opened the REPL,
		// a trailing -o interpreted rather than compiled.
		if (key != null) {
			throw new IllegalArgumentException("option '" + key + "' requires a value");
		}
		return new CliOptions(options, arguments).withFurther(further);
	}

	private CliOptions withFurther(List<String> further) {
		this.further = List.copyOf(further);
		return this;
	}

	/**
	 * Whether an argument is one of the oracle's alias flags: {@code -A:aliases},
	 * {@code -M[:aliases]}, {@code -X[:aliases]}, {@code -T...}. The aliases are glued to
	 * the flag, so the flag takes no following value.
	 */
	private static boolean isClojureFlag(String arg) {
		if (arg.length() < 2 || arg.charAt(0) != '-') {
			return false;
		}
		char flag = arg.charAt(1);
		if (flag != 'A' && flag != 'M' && flag != 'X' && flag != 'T') {
			return false;
		}
		return arg.length() == 2 || arg.charAt(2) == ':' || flag == 'T';
	}

	/**
	 * The table's entry for a key as written. An option the table lacks used to be stored
	 * and never read: a misspelt flag changed nothing, silently, and swallowed the
	 * argument after it as its value.
	 */
	private static CliOption option(String key, boolean test) {
		CliOption option = CliOptionTable.find(key);
		if (option == null) {
			throw new IllegalArgumentException("unknown option '" + key + "' (try: rontolisp --help)");
		}
		if (option.testOnly() && !test) {
			throw new IllegalArgumentException(
					"'" + key + "' is an option of rontolisp test (try: rontolisp test --help)");
		}
		return option;
	}

	private static void put(Map<String, String> options, CliOption option, String value) {
		// Every spelling is the SAME key, the first: -e and --eval may be mixed in one
		// command line and still accumulate in the order they were written. A repeatable
		// key appends every occurrence, joined with a newline: -e is one program written
		// in several arguments, --dist one search order, --feature one widening,
		// --java-dep one coordinate list.
		String key = option.names().get(0);
		String previous = options.get(key);
		options.put(key, previous == null || !option.repeatable() ? value : previous + "\n" + value);
	}

	@Override
	public String toString() {
		return "CliOptions{" + "options=" + options + '}';
	}

}
