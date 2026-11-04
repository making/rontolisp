package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * cl-reexport (vendored unmodified under {@code src/test/resources/cl-reexport}, LLGPL)
 * on all four backends via {@link AsdfLibraryE2eSupport}. {@code reexport-from} is a
 * function computing its exports at load, which the compile paths read too early; the
 * derived form {@code eval/ClReexportForms} ({@code .kb/asdf.md}, tier 4), whose marker
 * this pins against the release, computes them where the top-level form stands. The
 * chain, the empty package and the calls under a {@code let} are what a literal top-level
 * call does not show; those calls only answer here, the compile paths' registry being
 * frozen at run time. Every line is SBCL 2.2.9's answer to the same program.
 */
class ClReexportE2eTest extends AsdfLibraryE2eSupport {

	private static final String RESOURCES = Path.of("src", "test", "resources").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-reexport)
			(defpackage :re-part-a (:use :cl) (:export :run :other))
			(defpackage :re-part-b (:use :cl) (:export :kept :dropped))
			(defpackage :re-part-empty (:use :cl))
			(defun re-part-a:run () :ran)
			(defun re-part-b:kept () :kept)
			(defpackage :re-whole (:use :cl))
			(in-package :re-whole)
			(cl-reexport:reexport-from :re-part-a)
			(cl-reexport:reexport-from :re-part-b :exclude '(:dropped))
			(cl-reexport:reexport-from :re-part-empty)
			(defpackage :re-chain (:use))
			(cl:in-package :re-chain)
			(cl-reexport:reexport-from :re-whole :include '(:run))
			(cl:in-package :cl-user)
			(print (list (re-whole:run) (re-whole:kept) (re-chain:run)))
			(print (list 're-whole:run (eq 're-whole:run 're-part-a:run) (eq 're-chain:run 're-part-a:run)))
			(print (mapcar (lambda (name) (multiple-value-list (find-symbol name :re-whole)))
			               '("RUN" "OTHER" "KEPT")))
			(print (multiple-value-list (find-symbol "RUN" :re-chain)))
			(defun re-externals (package)
			  (let ((names nil))
			    (do-external-symbols (s package) (push (symbol-name s) names))
			    (sort names #'string<)))
			(print (list (re-externals :re-whole) (re-externals :re-chain)))
			(print (handler-case (cl-reexport:reexport-from :re-part-a :include '(:run) :exclude '(:other))
			         (simple-error () :exclusive)))
			(print (let ((*package* (find-package :re-whole)))
			         (cl-reexport:reexport-from :re-part-b :include '(:dropped))))
			(print (let ((*package* (find-package :re-chain))
			             (from :re-part-a))
			         (cl-reexport:reexport-from from :exclude '(:run))))
			""";

	private static final List<String> EXPECTED = List.of("(:RAN :KEPT :RAN)", "(RE-PART-A:RUN T T)",
			"((RE-PART-A:RUN :EXTERNAL) (RE-PART-A:OTHER :EXTERNAL) (RE-PART-B:KEPT :EXTERNAL))",
			"(RE-PART-A:RUN :EXTERNAL)", "((\"KEPT\" \"OTHER\" \"RUN\") (\"RUN\"))", ":EXCLUSIVE", "T", "T");

	@Override
	protected String systemDir() {
		return RESOURCES + "/cl-reexport";
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(RESOURCES + "/alexandria");
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
		return "ClReexportE2e";
	}

}
