package am.ik.rontolisp.scheme;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a session lowers differently from a file ({@code .kb/scheme-frontend.md}, "A
 * session"), as emitted forms, and when a typed buffer is complete. What the forms DO at
 * a prompt is {@code RontoLispCliTest}'s business.
 */
class SchemeSessionTest {

	private static String lowered(SchemeSession session, String source) {
		return session.read(source)
			.stream()
			.map(topLevel -> (topLevel.echoes() ? "echo " : "mute ")
					+ topLevel.forms().stream().map(LispVal::print).collect(Collectors.joining(" ")))
			.collect(Collectors.joining("\n"));
	}

	@Test
	void theFalseValueIsBoundOncePerSession() {
		SchemeSession session = Scheme.session();
		assertThat(lowered(session, "1")).isEqualTo("""
				mute (SETQ RONTOLISP::%SCHEME-FALSE '|#f| RONTOLISP::%SCHEME-UNSPECIFIED '|#!unspecific|)
				echo 1""");
		assertThat(lowered(session, "2")).isEqualTo("echo 2");
	}

	@Test
	void aBufferThatFailedToLowerLeavesTheFalseValueToTheNextOne() {
		SchemeSession session = Scheme.session();
		assertThatThrownBy(() -> session.read("(if)")).isInstanceOf(LispReadException.class);
		assertThat(lowered(session, "1")).startsWith("mute (SETQ RONTOLISP::%SCHEME-FALSE");
	}

	@Test
	void everyDefinitionIsAVariableWithATrampolineForTheCallsLoweredBeforeIt() {
		SchemeSession session = Scheme.session();
		session.read("0");
		// The forward reference is a name nobody defined yet: a direct call.
		assertThat(lowered(session, "(define (ev? n) (od? n))")).isEqualTo("""
				mute (SETQ |ev?| (LAMBDA (|n|) (|od?| |n|))) \
				(DEFUN |ev?| (&REST %SCM-A1) (APPLY |ev?| %SCM-A1))""");
		// A later buffer knows ev? is a variable, in call and in value position alike.
		assertThat(lowered(session, "(ev? 1) (map ev? '(1))")).isEqualTo("""
				echo (FUNCALL |ev?| 1)
				echo (MAPCAR |ev?| '(1))""");
		assertThat(lowered(session, "(define x 1)")).isEqualTo("""
				mute (SETQ |x| 1) (DEFUN |x| (&REST %SCM-A2) (APPLY |x| %SCM-A2))""");
	}

	@Test
	void aSelfTailCallStaysALoopUnlessTheSessionAssignedTheName() {
		SchemeSession session = Scheme.session();
		session.read("0");
		assertThat(lowered(session, "(define (count i) (if (= i 0) i (count (- i 1))))")).contains("TAGBODY");
		session.read("(set! walk car)");
		assertThat(lowered(session, "(define (walk i) (if (= i 0) i (walk (- i 1))))")).doesNotContain("TAGBODY")
			.contains("(FUNCALL |walk| (- |i| 1))");
	}

	@Test
	void aDefinitionHasNoValueAndAnEffectAnswersTheUnspecifiedObject() {
		SchemeSession session = Scheme.session();
		session.read("(define x 1)");
		// Whether an expression's value is shown is the VALUE's to say: the echo skips
		// the
		// unspecified object, however the expression producing it was spelled.
		assertThat(lowered(session, "(set! x 2) (display x) (import (scheme base)) x")).isEqualTo("""
				echo (PROGN (SETQ |x| 2) RONTOLISP::%SCHEME-UNSPECIFIED)
				echo (PROGN (RONTOLISP::%SCHEME-DISPLAY |x|) RONTOLISP::%SCHEME-UNSPECIFIED)
				mute\s
				echo |x|""");
	}

	@Test
	void anImportAtThePromptOnlyAddsNames() {
		SchemeSession session = Scheme.session();
		session.read("0");
		assertThat(lowered(session, "(import (prefix (only (scheme base) car) s:)) (s:car '(1)) (cdr '(1))"))
			.isEqualTo("""
					mute\s
					echo (CAR '(1))
					echo (CDR '(1))""");
	}

	@Test
	void aRecordProcedureIsKnownToLaterBuffersAndCannotBeRedefined() {
		SchemeSession session = Scheme.session();
		session.read("(define-record-type point (make-point x) point? (x point-x))");
		assertThat(lowered(session, "(point? 1)")).isEqualTo("echo (IF (|point?| 1) T RONTOLISP::%SCHEME-FALSE)");
		assertThatThrownBy(() -> session.read("(define (point-x p) 0)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot redefine point-x, a record procedure");
		// The whole type may be typed again.
		assertThat(lowered(session, "(define-record-type point (make-point x) point? (x point-x))"))
			.startsWith("mute (DEFSTRUCT");
	}

	@Test
	void aGeneratedGlobalNameIsNeverReusedByALaterBuffer() {
		SchemeSession session = Scheme.session();
		String first = lowered(session, "(define-record-type a #f a? (f))");
		String second = lowered(session, "(define-record-type b #f b? (g))");
		assertThat(first).contains("%SCM-SLOT1");
		assertThat(second).doesNotContain("%SCM-SLOT1").doesNotContain("%SCM-MAKE2");
	}

	@Test
	void aBufferIsCompleteWhenTheReaderDidNotRunOutOfInput() {
		for (String open : List.of("(define (f x)", "\"abc", "#| a #| b |#", "(list #\\( 1", "'", "#;", "(f #;(g)",
				"#(1 2")) {
			assertThat(SchemeSession.isComplete(open)).as(open).isFalse();
		}
		for (String closed : List.of("", "1", "(f x)", "(list #\\( #\\))", "#| ( |# 1", "#;(unclosed? no) 1", "\"(\"",
				"; (\n1", "(display \"a\\\"(\")",
				// Complete but wrong: it must reach the reader to be reported.
				")", "(a . )")) {
			assertThat(SchemeSession.isComplete(closed)).as(closed).isTrue();
		}
	}

}
