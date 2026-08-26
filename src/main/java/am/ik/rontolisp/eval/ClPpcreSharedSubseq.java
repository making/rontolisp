package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * One rewritten form in cl-ppcre's {@code util.lisp}: {@code nsubseq}.
 *
 * <p>
 * Upstream defines it as a DISPLACED array over the sequence --
 * {@code (make-array (- end start) :element-type (array-element-type sequence)
 * :displaced-to sequence :displaced-index-offset start)} -- so a shared substring costs
 * no copy. rontolisp's displacement is a view over an ARRAY only: a string is its own
 * value type here (a code-point buffer, {@code .kb/characters-code-points.md}), not a
 * {@code LispArray}, and the compile backends represent one as a Java {@code String} and
 * as UTF-8 bytes respectively -- none of which a displaced view can alias. So
 * {@code (nsubseq "abc" 1)} signals on every backend, and with it every cl-ppcre entry
 * point that shares: a FUNCTION replacement to {@code regex-replace}/{@code -all}
 * (cl-unicode's {@code canonicalize-name} is one), {@code :sharedp t} on
 * {@code scan-to-strings} / {@code register-groups-bind} / {@code do-scans}.
 *
 * <p>
 * The rewrite makes {@code nsubseq} COPY, which is what cl-ppcre itself does whenever the
 * caller did not ask to share ({@code (if sharedp #'nsubseq #'subseq)} at each of those
 * call sites): the two are interchangeable for every consumer in the library, since a
 * shared substring is only ever read. What is lost is the saved copy, not a semantics --
 * and nothing here writes through one.
 *
 * <p>
 * This is a rewrite rather than a fix in {@code make-array} on purpose: displacing a
 * string is a real gap and closing it means a string VIEW on all four backends, not a
 * copy dressed up as a view. A {@code make-array} that quietly answered a copy would make
 * every other library's displacement silently stop aliasing.
 */
final class ClPpcreSharedSubseq {

	/** The ASDF system whose component this class rewrites (canonical lower-case). */
	static final String SYSTEM = "cl-ppcre";

	/** The component holding {@code nsubseq}. */
	private static final String COMPONENT = "util.lisp";

	/** The upstream definition, matched verbatim so a changed release fails loudly. */
	private static final String UPSTREAM = """
			(defun nsubseq (sequence start &optional (end (length sequence)))
			  "Returns a subsequence by pointing to location in original sequence."
			  (make-array (- end start)
			              :element-type (array-element-type sequence)
			              :displaced-to sequence
			              :displaced-index-offset start))""";

	private static final String REPLACEMENT = """
			(defun nsubseq (sequence start &optional (end (length sequence)))
			  "Returns a subsequence by pointing to location in original sequence."
			  (subseq sequence start end))""";

	private ClPpcreSharedSubseq() {
	}

	/**
	 * Returns the rewritten source of the given cl-ppcre component, or {@code null} when
	 * it holds nothing this class rewrites.
	 * @param componentFile the component path, relative to the system's base directory
	 * @param source the real component source
	 * @return the rewritten source, or {@code null} to keep the real one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		if (!source.contains(UPSTREAM)) {
			throw new IllegalStateException("the " + SYSTEM + " release in the quicklisp cache no longer defines"
					+ " nsubseq as a displaced array in " + COMPONENT
					+ "; the rewrite in ClPpcreSharedSubseq must be updated for it");
		}
		return source.replace(UPSTREAM, REPLACEMENT);
	}

}
