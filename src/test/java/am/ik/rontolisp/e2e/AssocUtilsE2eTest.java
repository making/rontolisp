package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): Eitaro Fukamachi's REAL
 * assoc-utils source (vendored unmodified under {@code src/test/resources/assoc-utils},
 * Public Domain) loads via {@code asdf:load-system} and exercises the alist read/convert
 * API on all four backends via {@link AsdfLibraryE2eSupport}.
 *
 * <p>
 * The integration added several general features the library depends on: {@code mapl},
 * {@code equalp} and {@code string<} (rontolisp-source prelude defuns), the
 * {@code define-modify-macro}/{@code define-setf-expander} macros, {@code sort}'s
 * {@code :key} keyword (routed through {@code stable-sort}),
 * {@code (intern name :keyword)} and
 * {@code loop ... being the hash-keys ... using (hash-value ...)}. {@code aget} is a
 * settable place: {@code (setf (aget ...) v)} works on all four backends (pinned by
 * {@link AssocUtilsUpcaseE2eTest}). Lite limitation: on the compile paths {@code alistp}
 * treats {@code return-from} across the {@code mapl} lambda as a lambda-local exit, so a
 * compiled {@code alistp} can report {@code t} for a non-alist; it is not exercised for a
 * non-alist here.
 */
class AssocUtilsE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "assoc-utils")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :assoc-utils)
			(let ((a (list (cons "name" "eitaro") (cons "loc" "vienna"))))
			  (format t "~a~%" (assoc-utils:aget a "name"))
			  (format t "~a~%" (assoc-utils:aget a "missing" "none"))
			  (format t "~a~%" (assoc-utils:alist-keys a))
			  (format t "~a~%" (assoc-utils:alist-values a))
			  (format t "~a~%" (assoc-utils:alist-plist a))
			  (format t "~a~%" (assoc-utils:plist-alist (assoc-utils:alist-plist a)))
			  (format t "~a~%" (assoc-utils:remove-from-alist a "loc"))
			  (format t "~a~%" (assoc-utils:with-keys ((nm "name") (lc "loc")) a
			                     (format nil "~a@~a" nm lc))))
			(let ((b (list (cons "x" 1) (cons "y" 2))))
			  (assoc-utils:delete-from-alistf b "x")
			  (format t "~a~%" b))
			(let ((h (assoc-utils:alist-hash (list (cons "k" "v")))))
			  (format t "~a~%" (assoc-utils:hash-alist h)))
			(format t "~a~%"
			  (assoc-utils:alist-get (list (cons "u" (list (cons "age" 42)))) (list "u" "age")))
			(format t "~a~%"
			  (if (assoc-utils:alist= (list (cons "a" "1") (cons "b" "2"))
			                          (list (cons "b" "2") (cons "a" "1")))
			      "eq" "neq"))
			""";

	private static final List<String> EXPECTED = List.of("eitaro", "none", "(name loc)", "(eitaro vienna)",
			"(NAME eitaro LOC vienna)", "((name . eitaro) (loc . vienna))", "((name . eitaro))", "eitaro@vienna",
			"((y . 2))", "((k . v))", "42", "eq");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
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
	protected String artifactName() {
		return "TestAssocUtils";
	}

}
