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
		assertThat(lowered(session, "1")).isEqualTo(
				"""
						mute (SETQ RONTOLISP::%SCHEME-FALSE '|#f| RONTOLISP::%SCHEME-UNSPECIFIED '|#!unspecific|)
						echo (LET ((%SCM-EXIT-DONE1 (LIST NIL)) (%SCM-EXIT-VALUE2 NIL)) (LET ((%SCM-EXIT-CODE3 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE2 1) %SCM-EXIT-DONE1)))) (IF (EQ %SCM-EXIT-CODE3 %SCM-EXIT-DONE1) %SCM-EXIT-VALUE2 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE3))))""");
		assertThat(lowered(session, "2")).isEqualTo(
				"echo (LET ((%SCM-EXIT-DONE4 (LIST NIL)) (%SCM-EXIT-VALUE5 NIL)) (LET ((%SCM-EXIT-CODE6 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE5 2) %SCM-EXIT-DONE4)))) (IF (EQ %SCM-EXIT-CODE6 %SCM-EXIT-DONE4) %SCM-EXIT-VALUE5 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE6))))");
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
		// The forward reference is a name nobody defined yet: a direct call. Every
		// entry runs inside the exit catch, answering its last form's value; a defun
		// stays bare and the setq takes the statement guard.
		assertThat(lowered(session, "(define (ev? n) (od? n))")).isEqualTo(
				"""
						mute (LET ((%SCM-EXIT-DONE5 (LIST NIL))) (LET ((%SCM-EXIT-CODE6 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ |ev?| (LAMBDA (|n|) (|od?| |n|))) %SCM-EXIT-DONE5)))) (IF (EQ %SCM-EXIT-CODE6 %SCM-EXIT-DONE5) NIL (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE6)))) (DEFUN |ev?| (&REST %SCM-A4) (APPLY |ev?| %SCM-A4))""");
		// A later buffer knows ev? is a variable, in call and in value position alike.
		assertThat(lowered(session, "(ev? 1) (map ev? '(1))")).isEqualTo(
				"""
						echo (LET ((%SCM-EXIT-DONE7 (LIST NIL)) (%SCM-EXIT-VALUE8 NIL)) (LET ((%SCM-EXIT-CODE9 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE8 (FUNCALL |ev?| 1)) %SCM-EXIT-DONE7)))) (IF (EQ %SCM-EXIT-CODE9 %SCM-EXIT-DONE7) %SCM-EXIT-VALUE8 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE9))))
						echo (LET ((%SCM-EXIT-DONE10 (LIST NIL)) (%SCM-EXIT-VALUE11 NIL)) (LET ((%SCM-EXIT-CODE12 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE11 (MAPCAR |ev?| '(1))) %SCM-EXIT-DONE10)))) (IF (EQ %SCM-EXIT-CODE12 %SCM-EXIT-DONE10) %SCM-EXIT-VALUE11 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE12))))""");
		assertThat(lowered(session, "(define x 1)")).isEqualTo(
				"""
						mute (LET ((%SCM-EXIT-DONE14 (LIST NIL))) (LET ((%SCM-EXIT-CODE15 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ |x| 1) %SCM-EXIT-DONE14)))) (IF (EQ %SCM-EXIT-CODE15 %SCM-EXIT-DONE14) NIL (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE15)))) (DEFUN |x| (&REST %SCM-A13) (APPLY |x| %SCM-A13))""");
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
		assertThat(lowered(session, "(set! x 2) (display x) (import (scheme base)) x")).isEqualTo(
				"""
						echo (LET ((%SCM-EXIT-DONE4 (LIST NIL)) (%SCM-EXIT-VALUE5 NIL)) (LET ((%SCM-EXIT-CODE6 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE5 (PROGN (SETQ |x| 2) RONTOLISP::%SCHEME-UNSPECIFIED)) %SCM-EXIT-DONE4)))) (IF (EQ %SCM-EXIT-CODE6 %SCM-EXIT-DONE4) %SCM-EXIT-VALUE5 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE6))))
						echo (LET ((%SCM-EXIT-DONE7 (LIST NIL)) (%SCM-EXIT-VALUE8 NIL)) (LET ((%SCM-EXIT-CODE9 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE8 (PROGN (RONTOLISP::%SCHEME-DISPLAY |x|) RONTOLISP::%SCHEME-UNSPECIFIED)) %SCM-EXIT-DONE7)))) (IF (EQ %SCM-EXIT-CODE9 %SCM-EXIT-DONE7) %SCM-EXIT-VALUE8 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE9))))
						mute\s
						echo (LET ((%SCM-EXIT-DONE10 (LIST NIL)) (%SCM-EXIT-VALUE11 NIL)) (LET ((%SCM-EXIT-CODE12 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE11 |x|) %SCM-EXIT-DONE10)))) (IF (EQ %SCM-EXIT-CODE12 %SCM-EXIT-DONE10) %SCM-EXIT-VALUE11 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE12))))""");
	}

	@Test
	void anImportAtThePromptOnlyAddsNames() {
		SchemeSession session = Scheme.session();
		session.read("0");
		assertThat(lowered(session, "(import (prefix (only (scheme base) car) s:)) (s:car '(1)) (cdr '(1))")).isEqualTo(
				"""
						mute\s
						echo (LET ((%SCM-EXIT-DONE4 (LIST NIL)) (%SCM-EXIT-VALUE5 NIL)) (LET ((%SCM-EXIT-CODE6 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE5 (CAR '(1))) %SCM-EXIT-DONE4)))) (IF (EQ %SCM-EXIT-CODE6 %SCM-EXIT-DONE4) %SCM-EXIT-VALUE5 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE6))))
						echo (LET ((%SCM-EXIT-DONE7 (LIST NIL)) (%SCM-EXIT-VALUE8 NIL)) (LET ((%SCM-EXIT-CODE9 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (SETQ %SCM-EXIT-VALUE8 (CDR '(1))) %SCM-EXIT-DONE7)))) (IF (EQ %SCM-EXIT-CODE9 %SCM-EXIT-DONE7) %SCM-EXIT-VALUE8 (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE9))))""");
	}

	@Test
	void aRecordProcedureIsKnownToLaterBuffersAndCannotBeRedefined() {
		SchemeSession session = Scheme.session();
		session.read("(define-record-type point (make-point x) point? (x point-x))");
		assertThat(lowered(session, "(point? 1)")).isEqualTo("echo (LET ((%SCM-EXIT-DONE1 (LIST NIL))"
				+ " (%SCM-EXIT-VALUE2 NIL)) (LET ((%SCM-EXIT-CODE3 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG"
				+ " (PROGN (SETQ %SCM-EXIT-VALUE2 (IF (|point?| 1) T RONTOLISP::%SCHEME-FALSE)) %SCM-EXIT-DONE1))))"
				+ " (IF (EQ %SCM-EXIT-CODE3 %SCM-EXIT-DONE1) %SCM-EXIT-VALUE2"
				+ " (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE3))))");
		assertThatThrownBy(() -> session.read("(define (point-x p) 0)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot redefine point-x, a record procedure");
		// The whole type may be typed again: a bare defstruct defines, it cannot throw.
		assertThat(lowered(session, "(define-record-type point (make-point x) point? (x point-x))"))
			.startsWith("mute (DEFSTRUCT");
	}

	@Test
	void aValuesLastFormKeepsItsShapeWithGuardedArguments() {
		// The prompt echoes through evalValues, which takes the multi-value path only
		// for a syntactic producer: (values) echoes nothing however it is wrapped.
		SchemeSession session = Scheme.session();
		session.read("0");
		assertThat(lowered(session, "(values 1 'a)")).startsWith("echo (VALUES (LET ((%SCM-EXIT-DONE")
			.contains("(RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE");
		assertThat(lowered(session, "(values)")).isEqualTo("echo (VALUES)");
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
