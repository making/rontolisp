package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The {@code .todo/76}/{@code .todo/81} integration target: Edi Weitz's REAL cl-who
 * sources (vendored unmodified under {@code src/test/resources/cl-who}, BSD) load via
 * {@code asdf:load-system} and render (X)HTML. cl-who's
 * {@code with-html-output-to-string} runs a chain of ordinary defuns (and a generic
 * function) AT MACRO-EXPANSION TIME, so the interpreter path drives {@code LispEvaluator}
 * directly while the compile paths mirror the CLI pipeline ({@code LoadInliner} splices
 * the system, {@code UserMacroExpander} expands the
 * {@code with-html-output}/{@code htm}/{@code str}/{@code esc}/{@code fmt} macros) into
 * the JVM/WASM compilers. Runs on all four backends via {@link AsdfLibraryE2eSupport}.
 *
 * <p>
 * Lite limitations exercised here: the default no-indent {@code :xml} rendering is exact,
 * and {@code (setf (html-mode) :html5)} switches the output mode (a compile-time constant
 * on the compile path); {@code :indent} and dynamic {@code let}-rebinding of the specials
 * are the documented unsupported cases (see {@code .kb/clos.md}/{@code .todo/76}).
 */
class ClWhoE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "cl-who").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-who)
			(princ (cl-who:with-html-output-to-string (s)
			  (:html (:head (:title "Hi")) (:body (:p "Hello" (:a :href "/x" "link"))))))
			(terpri)
			(princ (cl-who:with-html-output-to-string (s)
			  (:div (:span (cl-who:str (+ 1 2)))
			        (:span (cl-who:esc "<a&b>"))
			        (:span (cl-who:fmt "~a-~a" 3 4)))))
			(terpri)
			(princ (cl-who:with-html-output-to-string (s) (:br)))
			(terpri)
			(setf (cl-who:html-mode) :html5)
			(princ (cl-who:with-html-output-to-string (s) (:br)))
			(terpri)
			(setf (cl-who:html-mode) :xml)
			(princ (cl-who:with-html-output-to-string (s)
			  (:p (cl-who:esc (string (code-char 233))))))
			(terpri)
			""";

	private static final List<String> EXPECTED = List.of(
			"<html><head><title>Hi</title></head><body><p>Hello<a href='/x'>link</a></p></body></html>",
			"<div><span>3</span><span>&lt;a&amp;b&gt;</span><span>3-4</span></div>", "<br />", "<br>", "<p>&#xe9;</p>");

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
		return "TestClWho";
	}

}
