package am.ik.rontolisp.eval;

import java.io.FileNotFoundException;
import java.util.Map;

import am.ik.rontolisp.reader.Features;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The browser's interpreter: what the playground's REPL and the documentation site's Run
 * cells show, in either language.
 */
class PlaygroundReplTest {

	private static SourceLoader files(Map<String, String> files) {
		return path -> {
			String text = files.get(path);
			if (text == null) {
				throw new FileNotFoundException(path);
			}
			return text;
		};
	}

	private static final SourceLoader NO_FILES = files(Map.of());

	@Test
	void commonLispIsTheDefaultAndEchoesOnlyTheLastFormsValues() {
		PlaygroundRepl repl = new PlaygroundRepl(NO_FILES);
		assertThat(repl.language()).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(repl.eval("(princ \"a\") (+ 1 2) (floor 10 3)")).isEqualTo("a3\n1");
		assertThat(repl.eval("(defun sq (x) (* x x))")).isEqualTo("SQ");
		assertThat(repl.eval("(sq 7)")).isEqualTo("49");
	}

	@Test
	void theSchemePickReadsSchemeAndKeepsItsSessionAcrossBuffers() {
		PlaygroundRepl repl = new PlaygroundRepl(NO_FILES).pick(SourceLanguage.SCHEME);
		// A definition has no value to show, an effect's unspecified value neither.
		assertThat(repl.eval("(define (ev? n) (if (= n 0) #t (od? (- n 1))))")).isEmpty();
		assertThat(repl.eval("(define (od? n) (if (= n 0) #f (ev? (- n 1))))")).isEmpty();
		assertThat(repl.eval("(ev? 10)")).isEqualTo("#t");
		assertThat(repl.eval("(display \"hi\")")).isEqualTo("hi");
		assertThat(repl.eval("(list 'a \"s\" #\\x '())")).isEqualTo("(a \"s\" #\\x ())");
		assertThat(repl.eval("(define x 1) (+ x 41)")).isEqualTo("42");
	}

	@Test
	void switchingTheLanguageKeepsTheOneEvaluatorsDefinitions() {
		PlaygroundRepl repl = new PlaygroundRepl(NO_FILES);
		repl.eval("(defun twice (x) (* 2 x))");
		repl.pick(SourceLanguage.SCHEME);
		assertThat(repl.eval("(define (sq x) (* x x)) (sq 4)")).isEqualTo("16");
		repl.pick(SourceLanguage.COMMON_LISP);
		assertThat(repl.eval("(twice 5)")).isEqualTo("10");
		repl.pick(SourceLanguage.SCHEME);
		assertThat(repl.eval("(sq 5)")).isEqualTo("25");
	}

	@Test
	void aFailureCarriesTheConditionsOwnText() {
		PlaygroundRepl repl = new PlaygroundRepl(NO_FILES).pick(SourceLanguage.SCHEME);
		assertThatThrownBy(() -> repl.eval("(3 4)")).hasMessageContaining("The object is not applicable: 3");
		assertThat(repl.eval("(+ 1 1)")).isEqualTo("2");
	}

	@Test
	void exitEndsTheBufferKeepingItsOutput() {
		PlaygroundRepl repl = new PlaygroundRepl(NO_FILES).pick(SourceLanguage.SCHEME);
		assertThat(repl.eval("(display \"before\") (exit 3) (display \"after\")")).isEqualTo("before");
		assertThat(repl.run("(display \"before\") (exit 3) (display \"after\")")).isEqualTo("before");
	}

	@Test
	void aProgramReadsItsStandardInputAndTheFilesItIncludes() {
		PlaygroundRepl repl = new PlaygroundRepl(
				files(Map.of("greet.scm", "(define (greet name) (string-append \"hello, \" name))")),
				"first line\nsecond\n")
			.pick(SourceLanguage.SCHEME);
		assertThat(repl.run("""
				(include "greet.scm")
				(display (greet (read-line)))
				(newline)
				(write (read-line))
				""")).isEqualTo("hello, first line\n\"second\"");
	}

	@Test
	void aTranscriptEchoesEveryFormAfterItsOwnOutput() {
		PlaygroundRepl repl = new PlaygroundRepl(NO_FILES).pick(SourceLanguage.SCHEME);
		assertThat(repl.transcript("""
				(+ 1 2) ; => 3
				(define x 10)
				(display "x is ")
				(* x x) ; => 100
				(values 1 2)
				""")).isEqualTo("3\nx is \n100\n1\n2\n");
	}

	@Test
	void theCompileButtonsReadThePickedLanguageWithItsFiles() {
		SourceLoader loader = files(Map.of("sq.scm", "(define (sq x) (* x x))"));
		assertThat(SourceLanguage.SCHEME.readStrict("(include \"sq.scm\") (display (sq 3))", Features.JVM, loader))
			.isNotEmpty();
		assertThatThrownBy(() -> SourceLanguage.COMMON_LISP.readStrict("#.(+ 1 2)", Features.JVM, loader))
			.hasMessageContaining("#.");
	}

}
