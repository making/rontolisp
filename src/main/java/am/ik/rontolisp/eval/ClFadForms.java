package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived forms of cl-fad (tier 4, {@code .kb/asdf.md}): its per-implementation arms
 * for rontolisp.
 *
 * <p>
 * cl-fad is a portability layer: each operation that differs per implementation is a run
 * of {@code #+impl} arms closed by a {@code #-(or ...)} arm that signals "not supported"
 * -- the arm rontolisp reads. Each such arm becomes the operation over rontolisp's own
 * primitives, which behave as SBCL's arm does: {@code getenv} is {@code uiop:getenv};
 * {@code list-directory} is {@code directory} with {@code :resolve-symlinks};
 * {@code file-exists-p} is {@code probe-file}, whose answer is a truename in directory
 * form for a directory; and the portable walk in {@code delete-directory-and-files}
 * removes an emptied directory with {@code delete-file}, which deletes an empty directory
 * here ({@code .kb/uiop.md}).
 */
final class ClFadForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "cl-fad";

	private static final String GETENV_MARKER = "#-(or abcl allegro clisp clozure cmu cormanlisp ecl gcl lispworks mcl sbcl scl xcl clasp)\n"
			+ "    (error \"~S is not supported on your implementation\" 'getenv))";

	private static final String LIST_DIRECTORY_MARKER = "#-(or :sbcl :cmu :scl :lispworks :openmcl :allegro :clisp :cormanlisp :ecl :abcl :digitool :clasp)\n"
			+ "  (error \"LIST-DIRECTORY not implemented\"))";

	private static final String FILE_EXISTS_MARKER = "#-(or :sbcl :cmu :scl :lispworks :openmcl :allegro :clisp :cormanlisp :ecl :abcl :digitool :clasp)\n"
			+ "  (error \"FILE-EXISTS-P not implemented\"))";

	private static final String RMDIR_MARKER = "#+(or :abcl :digitool) (delete-file file))";

	private ClFadForms() {
	}

	/**
	 * Rewrites {@code temporary-files.lisp} and {@code fad.lisp}; any other component is
	 * left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not one of these
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		return switch (componentFile) {
			case "temporary-files.lisp" -> replaceOnce(componentFile, source, GETENV_MARKER, "(uiop:getenv x))");
			case "fad.lisp" -> {
				String out = replaceOnce(componentFile, source, LIST_DIRECTORY_MARKER,
						"(directory (directory-wildcard dirname) :resolve-symlinks follow-symlinks))");
				out = replaceOnce(componentFile, out, FILE_EXISTS_MARKER, "(probe-file pathspec))");
				yield replaceOnce(componentFile, out, RMDIR_MARKER, "(delete-file file))");
			}
			default -> null;
		};
	}

	private static String replaceOnce(String componentFile, String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(
					"cl-fad " + componentFile + ": expected exactly one occurrence of the marker <<<" + marker
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ ClFadForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
