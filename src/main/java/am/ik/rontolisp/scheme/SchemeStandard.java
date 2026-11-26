package am.ik.rontolisp.scheme;

import java.util.Locale;

/**
 * The standard every Scheme file of a program is read against ({@code --scheme-standard},
 * like Gauche's {@code gosh -r7}): the entry file, a file {@code (load ...)}ed at run
 * time or inlined on the compile path, and the REPL session. The value set is open: a
 * case-folding {@code r5rs} is a plausible later member.
 */
public enum SchemeStandard {

	/**
	 * This implementation's own dialect, the default: R7RS plus the
	 * SICP/MIT-compatibility names and the {@code r5rs} names, all visible to a file with
	 * no {@code import} and to the REPL. No compatibility promise with any other
	 * implementation.
	 */
	RONTOLISP("rontolisp"),

	/**
	 * R7RS-small, strictly, within the subset the front end implements: a program begins
	 * with an {@code import} declaration, the {@code sicp} and {@code r5rs} names are
	 * never visible, an imported binding cannot be redefined or assigned, and
	 * {@code eval} needs its environment.
	 */
	R7RS("r7rs");

	private final String optionName;

	SchemeStandard(String optionName) {
		this.optionName = optionName;
	}

	/**
	 * The spelling {@code --scheme-standard} takes.
	 * @return the option value
	 */
	public String optionName() {
		return this.optionName;
	}

	/**
	 * Parses a {@code --scheme-standard} value.
	 * @param value the option value
	 * @return the named standard
	 * @throws IllegalArgumentException when the value names none, naming the value
	 */
	public static SchemeStandard parse(String value) {
		String name = value.trim().toLowerCase(Locale.ROOT);
		for (SchemeStandard standard : values()) {
			if (standard.optionName.equals(name)) {
				return standard;
			}
		}
		throw new IllegalArgumentException(
				"--scheme-standard '" + value + "' names no standard this build reads (try rontolisp or r7rs)");
	}

}
