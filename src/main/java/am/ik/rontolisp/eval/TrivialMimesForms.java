package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived form of trivial-mimes (tier 4, {@code .kb/asdf.md}): the table its load
 * builds, available on every backend.
 *
 * <p>
 * {@code mime-types.lisp} ends in a top-level {@code (build-mime-db)}, which reads the
 * first of {@code /etc/mime.types} and the {@code mime.types} the release bundles beside
 * its source that exists, and signals when neither does. A compiled program runs that
 * call when it starts, so it needed one of the two files on the machine it RUNS on: a
 * WASM module without {@code /etc} or the build machine's Quicklisp tree preopened, a
 * {@code --no-wasi} module, or a JVM class moved to a machine without
 * {@code /etc/mime.types} (macOS has none) stopped before its first form -- and with it
 * every dexador and lack-app-file program. The call becomes the same choice with the
 * bundled file read through a LITERAL path, which {@code cli.CompileTimePathnameFolder}
 * inlines into the artifact: {@code /etc/mime.types} still wins where the program runs
 * with one, and the bundled table answers everywhere else. The loop that reads it is
 * upstream's {@code build-mime-db} body; {@code build-mime-db} and
 * {@code find-mime.types} themselves stay verbatim for a caller of their own.
 */
final class TrivialMimesForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "trivial-mimes";

	private static final String COMPONENT = "mime-types.lisp";

	private static final String LOAD_MARKER = "\n(build-mime-db)\n";

	private TrivialMimesForms() {
	}

	/**
	 * Rewrites {@code mime-types.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @param baseDir the system's base directory, which holds the bundled
	 * {@code mime.types}
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source, @Nullable String baseDir) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		int first = source.indexOf(LOAD_MARKER);
		if (first < 0 || source.indexOf(LOAD_MARKER, first + 1) >= 0) {
			throw new IllegalStateException("trivial-mimes " + COMPONENT
					+ ": expected exactly one occurrence of the marker <<<" + LOAD_MARKER.strip() + ">>> (found "
					+ (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
					+ TrivialMimesForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		String bundled = (baseDir == null || baseDir.isEmpty() ? "./" : baseDir.endsWith("/") ? baseDir : baseDir + "/")
				+ "mime.types";
		String load = """

				(if (probe-file #p"/etc/mime.types")
				    (build-mime-db #p"/etc/mime.types")
				    (with-open-file (stream "%s" :external-format :utf-8)
				      (loop for line = (read-line stream NIL)
				            while line
				            for tokens = (%%read-tokens line)
				            when (valid-name-p (first tokens))
				              do (apply #'mime-add tokens))))
				""".formatted(bundled.replace("\\", "\\\\").replace("\"", "\\\""));
		return source.substring(0, first) + load + source.substring(first + LOAD_MARKER.length());
	}

}
