package am.ik.rontolisp.starlark;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL Starlark front end: a file is read by the specification's grammar and
 * LOWERED to the Common Lisp core forms every pipeline already consumes. Partial
 * conformance by design and no compatibility promise; the lowering table, the value model
 * and the stated deviations are in {@code .kb/starlark-frontend.md} and on the
 * user-facing {@code doc/en/starlark/} section.
 *
 * <p>
 * This package depends on the AST types and {@code reader} only. It is reached through
 * the source-language seam ({@code eval/SourceLanguage.STARLARK}), never directly, and
 * its run-time helpers are Common Lisp source shipped like every other library
 * ({@code eval/StarlarkLibrary}).
 */
public final class Starlark {

	private Starlark() {
	}

	/**
	 * Reads a Starlark file and lowers it to Common Lisp core forms.
	 * @param source the file's text
	 * @param file the origin file for diagnostics, or {@code null} when unknown
	 * @param standard what the file is read against ({@code --starlark-standard})
	 * @return the top-level forms
	 * @throws LispReadException on a lexical or syntax error, a name nothing binds, a
	 * construct the front end does not have yet, or one the standard refuses, each with
	 * its position
	 */
	public static List<LispVal> read(String source, @Nullable String file, StarlarkStandard standard) {
		return StarlarkLowering.lower(StarlarkParser.parse(source, file), standard, source, file);
	}

	/**
	 * The run-time library's definitions generated from this package's tables -- the
	 * attributes of each type, the code points a string's {@code repr} escapes -- which
	 * {@code eval/StarlarkLibrary} appends to {@code starlark.lisp}.
	 * @return the forms, in the library's canonical shape
	 */
	public static List<LispVal> runtimeForms() {
		return StarlarkLowering.runtimeForms();
	}

}
