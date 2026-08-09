package am.ik.rontolisp.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Parser and container for command-line options.
 */
public class CliOptions {

	private static final Set<String> noValueKeys = Set.of("-h", "--help", //
			"-v", "--version", "--dynamic", "--buffered-output", "--component", "--no-wasi", "--host-random",
			"--optimize", "--no-gc", "--simd", "--no-prune", "--emit-wit");

	private final Map<String, String> options;

	private static final String NOKEY = "__";

	/**
	 * Create a new instance wrapping the given options map.
	 * @param options the parsed options
	 */
	public CliOptions(Map<String, String> options) {
		this.options = Collections.unmodifiableMap(options);
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
		String key = null;
		for (String arg : args) {
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
					options.put(arg.substring(0, eq), arg.substring(eq + 1));
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
				options.put(key, arg);
				key = null;
			}
		}
		return new CliOptions(options);
	}

	@Override
	public String toString() {
		return "CliOptions{" + "options=" + options + '}';
	}

}
