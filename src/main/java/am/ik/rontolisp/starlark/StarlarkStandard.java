package am.ik.rontolisp.starlark;

import java.util.Locale;

/**
 * What every Starlark file of a program is read against ({@code --starlark-standard}):
 * the three rules the specification makes and Go's implementation has as options -- a
 * global bound once, no {@code if} or {@code for} at the top level, no recursion.
 */
public enum StarlarkStandard {

	/**
	 * This implementation's dialect, the default: a global may be bound again, {@code if}
	 * and {@code for} may stand at the top level and a function may call itself -- what
	 * Go's {@code -globalreassign} and {@code -recursion} allow.
	 */
	RONTOLISP("rontolisp"),

	/**
	 * The specification's three rules: binding a global twice and an {@code if} or a
	 * {@code for} at the top level are errors before the program runs, a function called
	 * while a call of its declaration is running one when the program runs.
	 */
	SPEC("spec");

	private final String optionName;

	StarlarkStandard(String optionName) {
		this.optionName = optionName;
	}

	/**
	 * The spelling {@code --starlark-standard} takes.
	 * @return the option value
	 */
	public String optionName() {
		return this.optionName;
	}

	/**
	 * Parses a {@code --starlark-standard} value.
	 * @param value the option value
	 * @return the named standard
	 * @throws IllegalArgumentException when the value names none, naming the value
	 */
	public static StarlarkStandard parse(String value) {
		String name = value.trim().toLowerCase(Locale.ROOT);
		for (StarlarkStandard standard : values()) {
			if (standard.optionName.equals(name)) {
				return standard;
			}
		}
		throw new IllegalArgumentException(
				"--starlark-standard '" + value + "' names no standard this build reads (try rontolisp or spec)");
	}

}
