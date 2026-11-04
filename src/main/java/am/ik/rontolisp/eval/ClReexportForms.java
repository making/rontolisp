package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived form of cl-reexport (tier 4, {@code .kb/asdf.md}): a compiler macro beside
 * its {@code reexport-from}. The function computes {@code import} and {@code export} at
 * load, where a compile path, which reads the whole program before running any of it, has
 * already refused the {@code pkg:name} it makes readable. The compiler macro runs the
 * library's own helpers where the call stands and expands to the two literal directives,
 * which both resolvers consume. A call it cannot compute there stays a call of the
 * function: a computed argument, a package not defined yet, and the {@code :include} plus
 * {@code :exclude} the function signals on.
 */
final class ClReexportForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "cl-reexport";

	private static final String COMPONENT = "src/cl-reexport.lisp";

	private static final String FUNCTION = "(defun reexport-from (package-from &key include exclude)";

	private static final String COMPILER_MACRO = """
			(define-compiler-macro reexport-from (&whole form package-from &key include exclude)
			  (if (and (constantp package-from) (constantp include) (constantp exclude)
			           (not (and (eval include) (eval exclude)))
			           (find-package (eval package-from)))
			      (let ((symbols (include-symbols (eval include)
			                       (exclude-symbols (eval exclude)
			                         (external-symbols (eval package-from))))))
			        `(progn (import ',symbols) (export ',symbols)))
			      form))

			""";

	private ClReexportForms() {
	}

	/**
	 * Rewrites {@code src/cl-reexport.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		int first = source.indexOf(FUNCTION);
		if (first < 0 || source.indexOf(FUNCTION, first + 1) >= 0) {
			throw new IllegalStateException(
					"cl-reexport " + COMPONENT + ": expected exactly one occurrence of the marker <<<" + FUNCTION
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ ClReexportForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + COMPILER_MACRO + source.substring(first);
	}

}
