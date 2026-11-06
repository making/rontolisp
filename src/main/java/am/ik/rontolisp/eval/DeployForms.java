package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived forms of deploy (tier 4, {@code .kb/asdf.md}, "deploy"): its
 * per-implementation arms for rontolisp, and the one value its image dump cannot carry.
 *
 * <p>
 * deploy reads the command line and the environment with one {@code #+impl} arm each and
 * no generic arm, so here both answered nil: each gets the rontolisp arm,
 * {@code uiop:raw-command-line-arguments} (argv0 first, as SBCL's {@code *posix-argv*})
 * and {@code uiop:getenv}. The runtime directory is argv0's directory only where argv0
 * names a file: a JVM program's argv0 is its class name, and an executable found on
 * {@code PATH} is a bare name, so either falls to deploy's own last arm,
 * {@code *default-pathname-defaults*}, instead of failing the boot.
 *
 * <p>
 * The dump: deploy hands {@code uiop:dump-image} a closure calling
 * {@code call-entry-prepared}, and an image is compiled from source here, which no
 * closure is. So the entry point is a call designator, {@code (call-dumped-entry ENTRY
 * ...)}, which the dump writes as source; its arguments are what deploy's build leaves in
 * the image that its boot reads -- the build time and source checksum it prints, where
 * the data went -- and {@code call-dumped-entry} sets them, makes {@code deployed-p} true
 * as the build did, and boots as deploy does.
 */
final class DeployForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "deploy";

	private static final String ARGV_MARKER = "  #+sbcl sb-ext:*posix-argv*)";

	private static final String GETENV_MARKER = "  #+sbcl (sb-ext:posix-getenv x))";

	private static final String RUNTIME_DIRECTORY_MARKER = "        ((first (command-line-arguments))\n";

	private static final String ENTRY_MARKER = "(lambda ()\n                           (call-entry-prepared entry-point))";

	private static final String DEPLOY_METHOD_MARKER = "(defmethod deploy ((op deploy-op) &key type entry-point)";

	private static final String DUMPED_ENTRY = """
			;; rontolisp: what the build leaves in the image, carried into the program the image
			;; is compiled into as the entry point's arguments, then deploy's boot.
			(defun call-dumped-entry (entry-point build-time source-checksum data-location)
			  (setf *build-time* build-time
			        *source-checksum* source-checksum
			        *data-location* data-location)
			  (setf (fdefinition 'deployed-p) (lambda () T))
			  (call-entry-prepared entry-point))

			""";

	private DeployForms() {
	}

	/**
	 * Rewrites {@code toolkit.lisp} and {@code deploy.lisp}; any other component is left
	 * alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not one of these
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		return switch (componentFile) {
			case "toolkit.lisp" -> {
				String out = replaceOnce(componentFile, source, ARGV_MARKER,
						"  #+sbcl sb-ext:*posix-argv*\n  #+rontolisp (uiop:raw-command-line-arguments))");
				out = replaceOnce(componentFile, out, GETENV_MARKER,
						"  #+sbcl (sb-ext:posix-getenv x)\n  #+rontolisp (uiop:getenv x))");
				yield replaceOnce(componentFile, out, RUNTIME_DIRECTORY_MARKER,
						"        ((and (first (command-line-arguments))\n"
								+ "              (probe-file (pathname-utils:parse-native-namestring"
								+ " (first (command-line-arguments)))))\n");
			}
			case "deploy.lisp" -> {
				String out = replaceOnce(componentFile, source, ENTRY_MARKER,
						"(list 'call-dumped-entry entry-point *build-time* *source-checksum* *data-location*)");
				yield replaceOnce(componentFile, out, DEPLOY_METHOD_MARKER, DUMPED_ENTRY + DEPLOY_METHOD_MARKER);
			}
			default -> null;
		};
	}

	private static String replaceOnce(String componentFile, String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(
					"deploy " + componentFile + ": expected exactly one occurrence of the marker <<<" + marker
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ DeployForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
