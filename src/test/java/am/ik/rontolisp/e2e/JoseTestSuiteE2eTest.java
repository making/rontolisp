package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * jose's OWN test suite, run on all four backends: {@code jose/tests/jwt} is loaded from
 * the vendored upstream sources ({@code src/test/resources/jose/tests/jwt.lisp},
 * verbatim) and driven through rove, which is the same thing
 * {@code rontolisp test jose/tests/jwt} does from the command line. {@link JoseE2eTest}
 * covers the API surface; this one is the oracle we did not write.
 *
 * <p>
 * Its sibling {@code jose/tests/jws} is deliberately absent, and that is upstream's
 * doing, not a gap: it {@code (:use #:pem)}, and {@code pem} is not in the Quicklisp
 * distribution at all, so there is nothing to load on any implementation.
 *
 * <p>
 * The five tests are the claim-check contract from the other side of the fence -- a
 * non-integer {@code iat}/{@code nbf}/{@code exp} signalling {@code jwt-claims-error}, an
 * {@code nbf} in the future and an {@code exp} in the past signalling through {@code
 * cerror} so {@code (handler-bind (... #'continue))} resumes the decode, and a fixed
 * third-party token decoded with signature verification skipped the same way. Because the
 * timestamps are computed from {@code get-universal-time} at run time, what is pinned is
 * rove's report, not a token.
 *
 * <p>
 * {@link #normalizeLine(String)} strips rove's {@code  (Nms)} duration suffix, printed
 * for any assertion slower than 37 ms -- an interpreter run crosses that and a compiled
 * one does not, which is a property of the machine rather than of jose.
 */
class JoseTestSuiteE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "jose").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :rove)
			(setf rove:*enable-colors* nil)
			(setf *print-pretty* nil)
			(asdf:load-system :jose/tests/jwt)
			(format t "~&jwt passed: ~A~%" (if (rove:run :jose/tests/jwt) "yes" "no"))
			""";

	private static final List<String> EXPECTED = """
			Testing System jose/tests/jwt

			;; testing 'jose/tests/jwt'
			test-number-keys-not-int
			✓ Expect (DECODE :HS256 *SECRET* TOKEN) to signal JWT-CLAIMS-ERROR.
			✓ Expect (DECODE :HS256 *SECRET* TOKEN) to signal JWT-CLAIMS-ERROR.
			✓ Expect (DECODE :HS256 *SECRET* TOKEN) to signal JWT-CLAIMS-ERROR.
			test-nbf
			✓ Expect (DECODE :HS256 *SECRET* TOKEN) to be true.
			test-nbf-in-future
			✓ Expect (DECODE :HS256 *SECRET* TOKEN) to signal JWT-CLAIMS-NOT-YET-VALID.
			✓ Expect (HANDLER-BIND ((JOSE/ERRORS:JWT-CLAIMS-NOT-YET-VALID #'CONTINUE)) (DECODE :HS256 *SECRET* TOKEN)) to be true.
			test-exp
			✓ Expect (DECODE :HS256 *SECRET* TOKEN) to be true.
			test-exp-in-past
			✓ Expect (DECODE :HS256 *SECRET* TOKEN) to signal JWT-CLAIMS-EXPIRED.
			✓ Expect (HANDLER-BIND ((JOSE/ERRORS:JWT-CLAIMS-EXPIRED #'CONTINUE)) (DECODE :HS256 *SECRET* TOKEN)) to be true.
			test-skip-verify
			✓ Expect (EQUAL CLAIMS '(("a" . "b"))) to be true.
			✓ Expect (EQUAL HEADERS '(("alg" . "HS256") ("typ" . "JWT"))) to be true.

			✓ 1 test completed

			Summary:
			All 1 test passed.
			jwt passed: yes
			"""
		.lines()
		.map(String::trim)
		.toList();

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		List<String> path = new java.util.ArrayList<>(JoseSystems.DEPENDENCY_PATH);
		path.add(Path.of("src", "test", "resources", "rove").toAbsolutePath().toString());
		path.add(Path.of("src", "test", "resources", "dissect").toAbsolutePath().toString());
		path.add(Path.of("src", "test", "resources", "cl-ppcre").toAbsolutePath().toString());
		return path;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String normalizeLine(String line) {
		return line.replaceFirst(" \\(\\d+ms\\)$", "");
	}

	@Override
	protected String artifactName() {
		return "JoseTestSuiteE2e";
	}

}
