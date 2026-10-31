package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The REAL contextl sources (vendored unmodified under
 * {@code src/test/resources/contextl}, MIT; quicklisp dist contextl-20241012-git, with
 * its lw-compat dependency beside it) load through {@code asdf:load-system}, layer GC
 * included, and run on ALL FOUR backends via {@link AsdfLibraryE2eSupport}, in
 * {@code contextl-user}.
 *
 * <p>
 * The exercise is the layer activation contextl is built around: {@code deflayer} (a
 * sublayer included), {@code define-layered-function}/{@code define-layered-method} with
 * {@code :in} a layer, an {@code :around}, {@code call-next-method},
 * {@code with-active-layers} / {@code with-inactive-layers} in every order demo3 asserts,
 * {@code ensure-active-layer} / {@code ensure-inactive-layer}, {@code active-layers},
 * {@code layer-active-p}, and the layer context cache (an {@code :after} method on
 * {@code adjoin-layer-using-class} runs once per new context). Every activation makes an
 * anonymous {@code standard-layer-class} at RUN time and dispatches on its prototype
 * ({@code .kb/clos.md}, "Classes made at run time"). The output is keywords and strings
 * only: the interpreter prints a {@code contextl-user} symbol qualified inside a form
 * that activated a layer, a residual {@code .kb/clos.md} records.
 */
class ContextlE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "contextl")
		.toAbsolutePath()
		.toString();

	private static final String LW_COMPAT_DIR = Path.of("src", "test", "resources", "lw-compat")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system "contextl")
			(in-package :contextl-user)
			(define-layered-function test ())
			(define-layered-method test :in t () (list :root))
			(deflayer info-layer)
			(deflayer employment-layer)
			(define-layered-method test :in info-layer () (list* :info (call-next-method)))
			(define-layered-method test :in employment-layer () (list* :employment (call-next-method)))
			(print (test))
			(print (with-active-layers (info-layer) (test)))
			(print (with-active-layers (info-layer employment-layer) (test)))
			(print (with-active-layers (employment-layer info-layer) (test)))
			(print (with-active-layers (info-layer employment-layer info-layer) (test)))
			(print (with-active-layers (info-layer employment-layer) (with-inactive-layers (info-layer) (test))))
			(print (with-active-layers (info-layer employment-layer) (with-inactive-layers (employment-layer) (test))))
			(print (with-active-layers (employment-layer employment-layer) (test)))
			(print (with-active-layers (info-layer employment-layer)
			         (mapcar (lambda (layer) (string (layer-name layer))) (active-layers))))
			(print (list (layer-active-p 'info-layer) (with-active-layers (info-layer) (layer-active-p 'info-layer))))
			(define-layered-function greet (who))
			(define-layered-method greet (who) (format nil "Hello, ~A" who))
			(deflayer polite)
			(deflayer very-polite (polite))
			(define-layered-method greet :in polite :around (who) (concatenate 'string (call-next-method) ", please"))
			(define-layered-method greet :in very-polite (who) (concatenate 'string "Dear " (call-next-method)))
			(print (list (greet "Ann") (with-active-layers (polite) (greet "Bob"))
			             (with-active-layers (very-polite) (greet "Cy"))))
			(defvar *adjoined* nil)
			(defclass counted-layer (standard-layer-class) ())
			(define-layered-method adjoin-layer-using-class :after ((class counted-layer) active-context)
			  (push :adjoined *adjoined*))
			(deflayer counted () () (:metaclass counted-layer))
			(dotimes (i 3) (with-active-layers (counted) nil))
			(print *adjoined*)
			(ensure-active-layer 'info-layer)
			(print (test))
			(ensure-inactive-layer 'info-layer)
			(print (test))
			""";

	private static final List<String> EXPECTED = List.of("(:ROOT)", "(:INFO :ROOT)", "(:EMPLOYMENT :INFO :ROOT)",
			"(:INFO :EMPLOYMENT :ROOT)", "(:EMPLOYMENT :INFO :ROOT)", "(:EMPLOYMENT :ROOT)", "(:INFO :ROOT)",
			"(:EMPLOYMENT :ROOT)", "(\"EMPLOYMENT-LAYER\" \"INFO-LAYER\" \"T\")", "(NIL T)",
			"(\"Hello, Ann\" \"Hello, Bob, please\" \"Dear Hello, Cy, please\")", "(:ADJOINED)", "(:INFO :ROOT)",
			"(:ROOT)");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(LW_COMPAT_DIR);
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
		return "TestContextl";
	}

}
