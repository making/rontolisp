package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * cl-fad 0.7.6 (vendored unmodified under {@code src/test/resources/cl-fad}, BSD) on all
 * four backends via {@link AsdfLibraryE2eSupport}. Its per-implementation arms are the
 * derived forms {@code eval/ClFadForms} ({@code .kb/asdf.md}, tier 4), whose markers this
 * pins against the release; its load runs {@code (setf logical-pathname-translations)}
 * for {@code TEMPORARY-FILES}. The program lists, probes, walks, copies and deletes a
 * directory tree, and opens a temporary file through a logical host of its own, which
 * {@code translate-logical-pathname} maps into that tree ({@code .kb/pathnames.md}) --
 * through the two macros whose value or cleanup is {@code (pathname stream)} of a file
 * stream. Every line is SBCL 2.2.9's answer to the same program.
 */
class ClFadE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "cl-fad").toAbsolutePath().toString();

	private static final String ALEXANDRIA_DIR = Path.of("src", "test", "resources", "alexandria")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-fad)
			(defparameter *dir* "target/fad-%%WORK%%/")
			(ensure-directories-exist (concatenate 'string *dir* "sub/"))
			(with-open-file (s (concatenate 'string *dir* "a.txt") :direction :output :if-exists :supersede)
			  (write-string "hello" s))
			(with-open-file (s (concatenate 'string *dir* "sub/b.lisp") :direction :output :if-exists :supersede)
			  (write-string "(b)" s))
			(defparameter *root* (cl-fad:directory-exists-p *dir*))
			(defun rel (p) (enough-namestring p *root*))
			(print (list (cl-fad:pathname-as-directory "x/y") (cl-fad:pathname-as-file "x/y/")
			             (cl-fad:directory-pathname-p "x/y")))
			(print (sort (mapcar #'rel (cl-fad:list-directory *dir*)) #'string<))
			(print (list (rel (cl-fad:file-exists-p (concatenate 'string *dir* "a.txt")))
			             (cl-fad:directory-exists-p (concatenate 'string *dir* "a.txt"))))
			(let ((acc nil))
			  (cl-fad:walk-directory *dir* (lambda (f) (push (rel f) acc)))
			  (print (sort acc #'string<)))
			(cl-fad:copy-file (concatenate 'string *dir* "a.txt") (concatenate 'string *dir* "c.txt"))
			(print (with-open-file (s (concatenate 'string *dir* "c.txt")) (read-line s)))
			(setf (logical-pathname-translations "FAD-WORK")
			      (list (list "**;*.*.*" (concatenate 'string (namestring *root*) "**/*.*"))))
			(print (list (rel (translate-logical-pathname "fad-work:sub;b.lisp"))
			             (load-logical-pathname-translations "FAD-WORK")
			             (pathname-match-p "sub/b.lisp" "**/*.lisp")
			             (pathname-match-p "sub/b.lisp" "*.lisp")
			             (pathname-match-p "sub/b.lisp" "/**/*.lisp")))
			(let ((p (cl-fad:with-output-to-temporary-file (s :template "FAD-WORK:TMP-%.TXT")
			           (write-string "t" s))))
			  (print (list (length (rel p)) (with-open-file (in p) (read-line in)))))
			(print (cl-fad:with-open-temporary-file (s :template "FAD-WORK:DEL-%.TXT")
			         (write-string "d" s)
			         (length (rel (pathname s)))))
			(print (sort (mapcar (lambda (p) (length (rel p))) (cl-fad:list-directory *dir*)) #'<))
			(cl-fad:delete-directory-and-files *dir*)
			(print (cl-fad:directory-exists-p *dir*))
			""";

	private static final List<String> EXPECTED = List.of("(#P\"x/y/\" #P\"x/y\" NIL)", "(\"a.txt\" \"sub/\")",
			"(\"a.txt\" NIL)", "(\"a.txt\" \"sub/b.lisp\")", "\"hello\"", "(\"sub/b.lisp\" NIL T T NIL)", "(16 \"t\")",
			"16", "(4 5 5 16)", "NIL");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(ALEXANDRIA_DIR);
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
		return "ClFadE2e";
	}

}
