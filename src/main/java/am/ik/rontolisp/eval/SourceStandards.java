package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.scheme.SchemeStandard;

/**
 * The standard each language's source is read against: one member per language that
 * offers a choice. Program-wide -- every file of the program in that language, the entry
 * file, a file loaded at run time or inlined on the compile path, and the REPL -- so it
 * travels with the {@link SourceLanguage} seam that reads them, and a caller above the
 * seam ({@code cli}) holds it without reaching into a language's package.
 *
 * @param scheme what a Scheme file is read against ({@code --scheme-standard})
 */
public record SourceStandards(SchemeStandard scheme) {

	/** Every language's default: {@code --scheme-standard rontolisp}. */
	public static final SourceStandards DEFAULT = new SourceStandards(SchemeStandard.RONTOLISP);

	/**
	 * Parses the command-line options that pick a standard.
	 * @param scheme the {@code --scheme-standard} value, or {@code null} for the default
	 * @return the standards
	 * @throws IllegalArgumentException when a value names no standard, naming the value
	 */
	public static SourceStandards parse(@Nullable String scheme) {
		return scheme == null ? DEFAULT : new SourceStandards(SchemeStandard.parse(scheme));
	}

}
