package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The reader's upcase premise against the REAL assoc-utils source: the two README
 * examples that depend on Common Lisp's {@code :upcase} readtable case --
 * {@code alist-get} (data keys written {@code :ELEMENTS}, the query written
 * {@code :elements}) and {@code with-keys} (the macro binds
 * {@code (intern (string-upcase ...))} symbols that lowercase body references must reach)
 * -- run verbatim on all four backends. Mixed-case spellings of the standard operators
 * ({@code DEFVAR}, {@code FORMAT}) and of the library's package prefix prove the
 * canonical fold: user symbols upcase, everything built-in still resolves.
 */
class AssocUtilsUpcaseE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "assoc-utils")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :ASSOC-UTILS)
			(DEFVAR *DATA*
			  '((:VERSION . "0.6")
			    (:ELEMENTS
			     ((:TYPE . "node") (:ID . 1)
			      (:TAGS (:NAME . "Monte Piselli - San Giacomo")
			       (:NOTE . "This is the very first node on OpenStreetMap."))))))
			(FORMAT T "~A~%" (ASSOC-UTILS:alist-get *data* '(:elements 0 :tags :note)))
			(format t "~a~%" (assoc-utils:with-keys
			                     ("name" (loc "location") (time "time" 2024))
			                     (list (cons "name" "eitaro") (cons "location" "vienna"))
			                   (declare (string name))
			                   (setf loc (string-upcase loc))
			                   (format nil "Hi, ~a in ~a around ~a!" name loc time)))
			(format t "~a~%" (if (eq ':FOO ':foo) "same" "different"))
			(let ((person (list (cons "name" "Eitaro Fukamachi"))))
			  (setf (assoc-utils:aget person "name") "Eitaro")
			  (format t "~a~%" (assoc-utils:aget person "name")))
			""";

	private static final List<String> EXPECTED = List.of("This is the very first node on OpenStreetMap.",
			"Hi, eitaro in VIENNA around 2024!", "same", "Eitaro");

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
		return "TestAssocUtilsUpcase";
	}

}
