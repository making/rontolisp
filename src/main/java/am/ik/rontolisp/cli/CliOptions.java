package am.ik.rontolisp.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Parser and container for command-line options.
 */
public class CliOptions {

	private static final Set<String> noValueKeys = Set.of("-h", "--help", //
			"-v", "--version", "--dynamic", "--buffered-output", "--component", "--no-wasi", "--host-random",
			"--host-fetch", "--reentrant", "--optimize", "--no-gc", "--simd", "--blas", "--gpu", "--parallel",
			"--no-prune", "--no-main", "--emit-wit", "--emit-js-glue", "--emit-pom", "--color", "--no-color",
			"--disable-colors");

	// A key that may be REPEATED: every occurrence appends to the same value, joined with
	// a newline, instead of the last one winning. -e is one program written in several
	// arguments, so `-e "(defun f () 1)" -e "(print (f))"` reads exactly like the two
	// forms written on two lines of a file.
	// --dist is repeatable for the same reason -e is: `--dist ultralisp --dist URL` is
	// one search order written in two arguments, and the value itself is comma-separated,
	// so the newline join reads as one more separator.
	// --feature is repeatable on the same terms: `--feature sbcl --feature x86-64` is one
	// widening written in two arguments.
	private static final Set<String> repeatableKeys = Set.of("-e", "--dist", "--feature");

	// Long spellings that mean an existing key; the value is stored under the short one,
	// so every reader looks at one name.
	private static final Map<String, String> aliases = Map.of("--eval", "-e", "--reporter", "-r");

	private final Map<String, String> options;

	private final List<String> arguments;

	private static final String NOKEY = "__";

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
		this.options = Collections.unmodifiableMap(options);
		this.arguments = List.copyOf(arguments);
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
	 * Parse command-line arguments into a {@link CliOptions} instance.
	 * <p>
	 * A value is written either as the following argument ({@code -o out.wasm}) or glued
	 * to the key with an {@code =} ({@code --optimize=size}). The two forms are not
	 * interchangeable: a key in {@link #noValueKeys} still takes NO following argument,
	 * so {@code --optimize -o out.wasm} keeps meaning the bare flag plus an output file
	 * rather than reading {@code -o} as the level. That is why a valued flag whose bare
	 * form must keep working can only be spelled with {@code =}.
	 * @param args the command-line arguments
	 * @return the parsed options
	 */
	public static CliOptions build(String[] args) {
		final Map<String, String> options = new LinkedHashMap<>();
		final List<String> arguments = new ArrayList<>();
		String key = null;
		boolean separated = false;
		for (String arg : args) {
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
			if (key == null) {
				// --key=value: everything after the FIRST '=' is the value, so a value
				// may itself contain one.
				int eq = arg.indexOf('=');
				if (arg.equals("-") || !arg.startsWith("-")) {
					// There is exactly one positional argument (the input file), so a
					// second one is a mistake -- and the likeliest mistake is spelling a
					// valued option with a space, where the value lands here and used to
					// silently REPLACE the input file (`--optimize size` compiled a file
					// named "size").
					if (options.containsKey(NOKEY)) {
						throw new IllegalArgumentException("unexpected extra argument '" + arg
								+ "': rontolisp takes one input file, and an option value is written"
								+ " with '=' (as in --optimize=size)");
					}
					options.put(NOKEY, arg);
				}
				else if (eq > 0) {
					put(options, arg.substring(0, eq), arg.substring(eq + 1));
				}
				else {
					key = arg;
					if (noValueKeys.contains(key)) {
						options.put(key, "");
						key = null;
					}
				}
			}
			else {
				put(options, key, arg);
				key = null;
			}
		}
		// A valued option written last with nothing after it used to be dropped, silently
		// changing the mode instead of reporting anything: a trailing -e opened the REPL,
		// a trailing -o interpreted rather than compiled.
		if (key != null) {
			throw new IllegalArgumentException("option '" + key + "' requires a value");
		}
		return new CliOptions(options, arguments);
	}

	private static void put(Map<String, String> options, String rawKey, String value) {
		// The long spelling is the SAME key, not a second one: -e and --eval may be mixed
		// in one command line and still accumulate in the order they were written.
		String key = aliases.getOrDefault(rawKey, rawKey);
		String previous = options.get(key);
		options.put(key, previous == null || !repeatableKeys.contains(key) ? value : previous + "\n" + value);
	}

	@Override
	public String toString() {
		return "CliOptions{" + "options=" + options + '}';
	}

}
