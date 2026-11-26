package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The OPT-IN {@code tiny-routes/lite} system ({@code .kb/asdf.md}): the same vendored
 * tiny-routes tree as {@link TinyRoutesE2eTest}, loaded through the
 * {@code tiny-routes-lite.asd} replacement with {@code path-template.lisp} substituted by
 * the ppcre-free matcher -- and, unlike there, WITHOUT cl-ppcre on the system path, which
 * is the point: the lite system declares no {@code :cl-ppcre} dependency, so this class
 * passing at all proves the engine is out of the load.
 *
 * <p>
 * The exercise runs the shared {@link TinyRoutesLiteCorpus} (whose expected output the
 * REAL engine also produces, {@link TinyRoutesLiteUpstreamParityTest} -- that pair is the
 * "matches identically" half of the lite contract) and then the "refuses loudly" half:
 * every rejected template shape signals a self-describing error at route-BUILD time, and
 * the messages are pinned verbatim on all four backends.
 */
class TinyRoutesLiteE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "tiny-routes")
		.toAbsolutePath()
		.toString();

	// The rejected shapes: a regex metacharacter in a keyword template, a quantifier,
	// :regex t -- each refused at route-build time -- and the no-colon control case,
	// which is exact-matched (never a regex upstream), so it builds fine.
	private static final String REJECTS = """
			(in-package :cl-user)

			(defun try-build (thunk)
			  (handler-case (progn (funcall thunk) (print :no-error))
			    (error (e) (format t "~a~%" (princ-to-string e)))))

			(try-build (lambda () (tiny-routes:wrap-request-matches-path-template
			                       (lambda (req) req) "/x/:id.json")))
			(try-build (lambda () (tiny-routes:wrap-request-matches-path-template
			                       (lambda (req) req) "/:a/*")))
			(try-build (lambda () (tiny-routes:wrap-request-matches-path-template
			                       (lambda (req) req) "^/v[0-9]+$" :regex t)))
			(try-build (lambda () (tiny-routes:wrap-request-matches-path-template
			                       (lambda (req) req) "/a.b")))
			""";

	private static final List<String> REJECTS_EXPECTED = List.of(
			"tiny-routes/lite: path template \"/x/:id.json\" contains the regex metacharacter .; the ppcre-free"
					+ " matcher accepts only literal characters and :name tokens -- load the full \"tiny-routes\""
					+ " system for regex-capable templates",
			"tiny-routes/lite: path template \"/:a/*\" contains the regex metacharacter *; the ppcre-free"
					+ " matcher accepts only literal characters and :name tokens -- load the full \"tiny-routes\""
					+ " system for regex-capable templates",
			"tiny-routes/lite: :regex path templates need cl-ppcre -- load the full \"tiny-routes\" system"
					+ " instead of \"tiny-routes/lite\"",
			":NO-ERROR");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return "(asdf:load-system \"tiny-routes/lite\")\n" + TinyRoutesLiteCorpus.CORPUS + REJECTS;
	}

	@Override
	protected List<String> expected() {
		List<String> expected = new ArrayList<>(TinyRoutesLiteCorpus.CORPUS_EXPECTED);
		expected.addAll(REJECTS_EXPECTED);
		return expected;
	}

	@Override
	protected String artifactName() {
		return "TinyRoutesLiteProgram";
	}

}
