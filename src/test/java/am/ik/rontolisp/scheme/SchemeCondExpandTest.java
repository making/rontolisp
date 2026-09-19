package am.ik.rontolisp.scheme;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code cond-expand} as the Common Lisp the front end emits: a program spelling one
 * lowers exactly as the program spelling the clause it takes
 * ({@code .kb/scheme-frontend.md}, "{@code cond-expand}").
 */
class SchemeCondExpandTest {

	private static SchemeFiles files(Map<String, String> tree) {
		return (from, path) -> {
			String key = directory(from) + path;
			String text = tree.get(key);
			return text == null ? null : new SchemeFiles.Source(key, text);
		};
	}

	private static String directory(@Nullable String file) {
		return file == null || !file.contains("/") ? "" : file.substring(0, file.lastIndexOf('/') + 1);
	}

	private static String lowered(String source, Map<String, String> tree, SchemeStandard standard) {
		List<LispVal> forms = Scheme.read(source, "main.scm", standard, files(tree));
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	private static String lowered(String source) {
		return lowered(source, Map.of(), SchemeStandard.RONTOLISP);
	}

	@Test
	void aTopLevelCondExpandIsTheClauseItTakes() {
		assertThat(lowered("(cond-expand (r7rs (define (f) 1) (define x 2)) (else (define x 3)))\n(display (f))"))
			.isEqualTo(lowered("(define (f) 1) (define x 2)\n(display (f))"));
		assertThat(lowered("(cond-expand (foo (define x 1)) (else (define x 3)))\n(display x)"))
			.isEqualTo(lowered("(define x 3)\n(display x)"));
		assertThat(lowered("(display 1) (cond-expand (foo 1) (else)) (display 2)"))
			.isEqualTo(lowered("(display 1) (display 2)"));
	}

	@Test
	void everyDeclaredFeatureHoldsAndNoOtherDoes() {
		for (String feature : SchemeFeatures.FEATURES) {
			assertThat(lowered("(display (cond-expand (" + feature + " 'yes) (else 'no)))"))
				.isEqualTo(lowered("(display (begin 'yes))"));
		}
		for (String feature : List.of("gauche", "chibi", "exact-complex", "posix", "R7RS", "x86-64")) {
			assertThat(lowered("(display (cond-expand (" + feature + " 'yes) (else 'no)))"))
				.isEqualTo(lowered("(display (begin 'no))"));
		}
	}

	@Test
	void andOrNotComposeRequirements() {
		assertThat(lowered("(display (cond-expand ((and r7rs (not foo) (or foo ratios)) 1) (else 2)))"))
			.isEqualTo(lowered("(display (begin 1))"));
		assertThat(lowered("(display (cond-expand ((or) 1) ((and) 2)))")).isEqualTo(lowered("(display (begin 2))"));
		assertThat(lowered("(display (cond-expand ((not r7rs) 1) (else 2)))"))
			.isEqualTo(lowered("(display (begin 2))"));
	}

	@Test
	void aLibraryRequirementHoldsForWhatAnImportWouldFind() {
		Map<String, String> tree = Map.of("util/strings.sld",
				"(define-library (util strings) (export twice) (import (scheme base))"
						+ " (begin (define (twice s) (string-append s s))))");
		String program = """
				(define-library (m) (export one) (import (scheme base)) (begin (define (one) 1)))
				(import (scheme base) (scheme write))
				(display (list (cond-expand ((library (scheme base)) 'a) (else 'b))
				               (cond-expand ((library (scheme char)) 'a) (else 'b))
				               (cond-expand ((library (scheme r5rs)) 'a) (else 'b))
				               (cond-expand ((library (scheme file)) 'a) (else 'b))
				               (cond-expand ((library (m)) 'a) (else 'b))
				               (cond-expand ((library (util strings)) 'a) (else 'b))
				               (cond-expand ((library (util numbers)) 'a) (else 'b))))""";
		assertThat(lowered(program, tree, SchemeStandard.RONTOLISP)).isEqualTo(lowered("""
				(import (scheme base) (scheme write))
				(display (list (begin 'a) (begin 'a) (begin 'b) (begin 'b) (begin 'a) (begin 'a) (begin 'b)))""", tree,
				SchemeStandard.RONTOLISP));
	}

	@Test
	void aClauseMayHoldTheImportDeclarations() {
		// The leading scans see through it, so an R7RS program may begin with one.
		assertThat(lowered("(cond-expand (r7rs (import (scheme base) (scheme write))))\n(display (square 2))", Map.of(),
				SchemeStandard.R7RS))
			.isEqualTo(lowered("(import (scheme base) (scheme write))\n(display (square 2))", Map.of(),
					SchemeStandard.R7RS));
		assertThat(lowered("""
				(define-library (m) (export one) (import (scheme base)) (begin (define (one) 1)))
				(cond-expand ((library (m)) (import (scheme base) (scheme write) (m)))
				             (else (import (scheme base) (scheme write))))
				(display (one))""", Map.of(), SchemeStandard.R7RS)).isEqualTo(lowered("""
				(define-library (m) (export one) (import (scheme base)) (begin (define (one) 1)))
				(import (scheme base) (scheme write) (m))
				(display (one))""", Map.of(), SchemeStandard.R7RS));
	}

	@Test
	void aBodyCondExpandSplicesItsDefinitions() {
		assertThat(lowered("(define (f) (cond-expand (r7rs (define y 1) (define z 2))) (+ y z))"))
			.isEqualTo(lowered("(define (f) (begin (define y 1) (define z 2)) (+ y z))"));
		assertThat(lowered("(define (f) (cond-expand (foo (define y 1)) (else)) (define z 2) z)"))
			.isEqualTo(lowered("(define (f) (begin) (define z 2) z)"));
	}

	@Test
	void aLibraryDeclarationCondExpandSplicesItsDeclarations() {
		assertThat(lowered("""
				(define-library (m)
				  (cond-expand (rontolisp (export one) (import (scheme base)))
				               (else (export two)))
				  (cond-expand ((library (scheme write)) (begin (define (one) 1))))
				  (cond-expand (gauche (begin (define (two) 2))) (else)))
				(import (scheme base) (m))
				(one)""")).isEqualTo(lowered("""
				(define-library (m) (export one) (import (scheme base)) (begin (define (one) 1)))
				(import (scheme base) (m))
				(one)"""));
	}

	@Test
	void aCondExpandAMacroExpandsIntoIsTakenThere() {
		assertThat(lowered("""
				(define-syntax defx
				  (syntax-rules () ((_ name) (cond-expand (r7rs (define name 1)) (else (define name 2))))))
				(defx a)
				(display a)""")).isEqualTo(lowered("""
				(define-syntax defx (syntax-rules () ((_ name) (begin (define name 1)))))
				(defx a)
				(display a)"""));
		// A template that is never used is never decided.
		assertThat(lowered("(define-syntax m (syntax-rules () ((_) (cond-expand (foo 1)))))\n(display 1)"))
			.isEqualTo(lowered("(display 1)"));
	}

	@Test
	void quotedDataIsLeftAlone() {
		assertThat(lowered("(display '(cond-expand (foo 1)))")).contains("'(|cond-expand| (|foo| 1))");
	}

	@Test
	void anUnquotedCondExpandInAQuasiquoteIsTaken() {
		assertThat(lowered("(display `(a ,(cond-expand (r7rs 1))))")).isEqualTo(lowered("(display `(a ,(begin 1)))"));
	}

	@Test
	void aSessionTakesACondExpandLikeAFile() {
		SchemeSession session = Scheme.session(SchemeStandard.RONTOLISP);
		String taken = session.read("(cond-expand (r7rs (define x 1)) (else (define x 2)))")
			.stream()
			.flatMap(topLevel -> topLevel.forms().stream())
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(taken).contains("(SETQ |x| 1)").doesNotContain("(SETQ |x| 2)");
	}

	@Test
	void featuresAnswersTheDeclaredList() {
		assertThat(lowered("(features)")).endsWith("(RONTOLISP::%SCHEME-FEATURES)");
		assertThat(Scheme.runtimeForms(name -> false, SchemeStandard.RONTOLISP).getLast().print()).isEqualTo(
				"(DEFUN RONTOLISP::%SCHEME-FEATURES NIL (LIST '|r7rs| '|exact-closed| '|ieee-float| '|full-unicode|"
						+ " '|ratios| '|rontolisp|))");
	}

	@Test
	void aCondExpandNoClauseOfWhichIsTakenIsAPositionedError() {
		assertThatThrownBy(() -> lowered("(display 1)\n(cond-expand (foo 1))")).isInstanceOf(LispReadException.class)
			.hasMessage("main.scm:2:1: no cond-expand clause is fulfilled and there is no else clause");
		assertThatThrownBy(() -> lowered("(cond-expand (else 1) (r7rs 2))"))
			.hasMessage("main.scm:1:1: else must be the last cond-expand clause");
		assertThatThrownBy(() -> lowered("(cond-expand ((not a b) 1))"))
			.hasMessage("main.scm:1:1: malformed cond-expand requirement: (not a b)");
		assertThatThrownBy(() -> lowered("(cond-expand ((frob a) 1))"))
			.hasMessage("main.scm:1:1: malformed cond-expand requirement: (frob a)");
		assertThatThrownBy(() -> lowered("(cond-expand ((library \"x\") 1))"))
			.hasMessage("main.scm:1:1: malformed library name: \"x\"");
		assertThatThrownBy(() -> lowered("(cond-expand r7rs)"))
			.hasMessage("main.scm:1:1: malformed cond-expand clause: r7rs");
		assertThatThrownBy(() -> lowered("(define-library (m) (cond-expand (foo (begin))))\n(import (m))"))
			.hasMessage("main.scm:1:21: no cond-expand clause is fulfilled and there is no else clause");
	}

}
