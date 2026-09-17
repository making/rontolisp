package am.ik.rontolisp.scheme;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lowering table of {@code .kb/scheme-frontend.md}, row by row, as the Common Lisp
 * the front end emits. What the emitted forms DO on each backend is
 * {@code SchemeSpecE2eTest}'s business.
 */
class SchemeLoweringTest {

	// Everything after the leading (setq false '|#f|).
	private static String lowered(String source) {
		List<LispVal> forms = Scheme.read(source, "test.scm");
		return forms.subList(1, forms.size()).stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	@Test
	void everyProgramBindsTheFalseValueFirst() {
		assertThat(Scheme.read("", null).stream().map(LispVal::print).toList())
			.containsExactly("(SETQ RONTOLISP::%SCHEME-FALSE '|#f| RONTOLISP::%SCHEME-UNSPECIFIED '|#!unspecific|)");
	}

	@Test
	void anEffectAnswersTheUnspecifiedObjectOnlyWhereItsValueIsRead() {
		// A body form before the last and a top-level form of a file are never read, so
		// they keep their raw shape; the last form of a procedure body is its value.
		assertThat(lowered("(define (f v) (display v) (vector-set! v 0 1)) (display 1) (set! y 2)")).isEqualTo(
				"""
						(DEFUN |f| (|v|) (RONTOLISP::%SCHEME-DISPLAY |v|) (PROGN (SETF (AREF |v| 0) 1) RONTOLISP::%SCHEME-UNSPECIFIED))
						(PRINC 1)
						(SETQ |y| 2)""");
		// The missing arm of an if, when and unless, and a cond or case with no clause
		// taken: the object where the value is read, NIL where it is not.
		assertThat(lowered("(define (g c) (when c 1) (if c 2))")).isEqualTo("""
				(DEFUN |g| (|c|) (IF (EQ |c| RONTOLISP::%SCHEME-FALSE) NIL 1) \
				(IF (EQ |c| RONTOLISP::%SCHEME-FALSE) RONTOLISP::%SCHEME-UNSPECIFIED 2))""");
		assertThat(lowered("(define (h c) (cond (c 1)))")).isEqualTo("""
				(DEFUN |h| (|c|) (IF (EQ |c| RONTOLISP::%SCHEME-FALSE) RONTOLISP::%SCHEME-UNSPECIFIED 1))""");
		// An effect is not a test that could be false: its value is the object.
		assertThat(lowered("(define (k) (if (newline) 1 2))")).isEqualTo(
				"""
						(DEFUN |k| NIL (IF (EQ (PROGN (TERPRI) RONTOLISP::%SCHEME-UNSPECIFIED) RONTOLISP::%SCHEME-FALSE) 2 1))""");
	}

	@Test
	void aProcedureDefinedOnceAndNeverAssignedIsADefunCalledDirectly() {
		assertThat(lowered("(define (f x) (car x)) (define g (lambda (y) (f y))) (g '(1))")).isEqualTo("""
				(DEFUN |f| (|x|) (CAR |x|))
				(DEFUN |g| (|y|) (|f| |y|))
				(|g| '(1))""");
	}

	@Test
	void anyOtherProcedureBindingIsAVariableCalledThroughFuncall() {
		assertThat(lowered("(define f (lambda (x) x)) (set! f car) (f 1) (define (g h) (h f))")).isEqualTo("""
				(SETQ |f| (LAMBDA (|x|) |x|))
				(SETQ |f| #'CAR)
				(FUNCALL |f| 1)
				(DEFUN |g| (|h|) (FUNCALL |h| |f|))""");
	}

	@Test
	void aTopLevelVariableIsASetqNeverADefvar() {
		assertThat(lowered("(define x 1) (define y)")).isEqualTo("""
				(SETQ |x| 1)
				(SETQ |y| NIL)""");
	}

	@Test
	void aProcedureNameInValuePositionIsAFunctionValue() {
		assertThat(lowered("(define (f x) x) (map f '(1)) (map car '((1))) (map pair? '(1))")).isEqualTo("""
				(DEFUN |f| (|x|) |x|)
				(MAPCAR #'|f| '(1))
				(MAPCAR #'CAR '((1)))
				(MAPCAR (LAMBDA (X) (IF (CONSP X) T RONTOLISP::%SCHEME-FALSE)) '(1))""");
	}

	@Test
	void anIdentifierThatCouldCollideIsEscaped() {
		assertThat(lowered("(define (CAR X) (list X 'X 'a:b))")).isEqualTo("""
				(DEFUN |s%CAR| (|s%X|) (LIST |s%X| '|s%X| '|s%a%cb|))""");
	}

	@Test
	void ifTestsAgainstTheFalseValueAndFusesAPredicate() {
		assertThat(lowered("(define (f c x) (list (if c 1 2) (if (pair? x) 1 2) (if (not c) 1 2) (if (memq c x) 1)))"))
			.isEqualTo(
					"""
							(DEFUN |f| (|c| |x|) (LIST (IF (EQ |c| RONTOLISP::%SCHEME-FALSE) 2 1) (IF (CONSP |x|) 1 2) \
							(IF (EQ |c| RONTOLISP::%SCHEME-FALSE) 1 2) (IF (MEMBER |c| |x| :TEST #'EQ) 1 RONTOLISP::%SCHEME-UNSPECIFIED)))""");
	}

	@Test
	void aPredicateInValuePositionIsConverted() {
		assertThat(lowered("(define (f x) (list (pair? x) (memq x x) (and (pair? x) (null? x)) (or x 1) #t #f '()))"))
			.isEqualTo("""
					(DEFUN |f| (|x|) (LIST (IF (CONSP |x|) T RONTOLISP::%SCHEME-FALSE) \
					(OR (MEMBER |x| |x| :TEST #'EQ) RONTOLISP::%SCHEME-FALSE) \
					(IF (AND (CONSP |x|) (NULL |x|)) T RONTOLISP::%SCHEME-FALSE) \
					(LET ((%SCM-T1 |x|)) (IF (EQ %SCM-T1 RONTOLISP::%SCHEME-FALSE) 1 %SCM-T1)) \
					T RONTOLISP::%SCHEME-FALSE NIL))""");
	}

	@Test
	void lambdaFormalsBecomeRestParameters() {
		assertThat(lowered("(lambda args args) (lambda (a . r) r) (lambda (a b) a)")).isEqualTo("""
				(LAMBDA (&REST |args|) |args|)
				(LAMBDA (|a| &REST |r|) |r|)
				(LAMBDA (|a| |b|) |a|)""");
	}

	@Test
	void aNamedLetWhoseNameIsOnlyTailCalledIsATagbody() {
		assertThat(lowered("(let loop ((i 0) (acc 0)) (if (< i 10) (loop (+ i 1) (+ acc i)) acc))")).isEqualTo("""
				(LET ((|i| 0) (|acc| 0) (%SCM-R4 NIL)) (TAGBODY %SCM-L3 (IF (< |i| 10) \
				(PROGN (PSETQ |i| (+ |i| 1) |acc| (+ |acc| |i|)) (GO %SCM-L3)) (SETQ %SCM-R4 |acc|))) %SCM-R4)""");
	}

	@Test
	void aLoopWhoseBodyMakesAClosureRebindsItsVariablesPerIteration() {
		assertThat(lowered("(let loop ((i 0)) (if (< i 3) (begin (keep (lambda () i)) (loop (+ i 1))) 'done))"))
			.isEqualTo("""
					(LET ((%SCM-C4 0) (%SCM-R6 NIL)) (TAGBODY %SCM-L5 (LET ((|i| %SCM-C4)) (IF (< |i| 3) \
					(PROGN (|keep| (LAMBDA NIL |i|)) (PROGN (SETQ %SCM-C4 (+ |i| 1)) (GO %SCM-L5))) \
					(SETQ %SCM-R6 '|done|)))) %SCM-R6)""");
	}

	@Test
	void aNamedLetWhoseNameEscapesIsAProcedure() {
		assertThat(lowered("(let fact ((n 5)) (if (= n 0) 1 (* n (fact (- n 1)))))")).isEqualTo("""
				(LET ((|fact| NIL)) (SETQ |fact| (LAMBDA (|n|) (IF (= |n| 0) 1 (* |n| (FUNCALL |fact| (- |n| 1)))))) \
				(FUNCALL |fact| 5))""");
	}

	@Test
	void aSelfTailCallIsALoopAndAnyOtherSelfCallIsNot() {
		assertThat(lowered("(define (down n acc) (if (= n 0) acc (down (- n 1) (+ acc 1))))")).isEqualTo("""
				(DEFUN |down| (%SCM-C1 %SCM-C2) (LET ((|n| %SCM-C1) (|acc| %SCM-C2) (%SCM-R4 NIL)) \
				(TAGBODY %SCM-L3 (IF (= |n| 0) (SETQ %SCM-R4 |acc|) \
				(PROGN (PSETQ |n| (- |n| 1) |acc| (+ |acc| 1)) (GO %SCM-L3)))) %SCM-R4))""");
		assertThat(lowered("(define (fib n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))")).isEqualTo("""
				(DEFUN |fib| (|n|) (IF (< |n| 2) |n| (+ (|fib| (- |n| 1)) (|fib| (- |n| 2)))))""");
	}

	@Test
	void internalDefinitionsAreLetrecStar() {
		assertThat(lowered("(define (f x) (define a 1) (define (g y) (+ y a)) (g x))")).isEqualTo("""
				(DEFUN |f| (|x|) (LET ((|a| NIL) (|g| NIL)) (SETQ |a| 1) (SETQ |g| (LAMBDA (|y|) (+ |y| |a|))) \
				(FUNCALL |g| |x|)))""");
	}

	@Test
	void derivedFormsDesugarHygienically() {
		// A user binding of `if` cannot capture the `if` that `or` expands into.
		assertThat(lowered("(define (f if) (or if 1))")).isEqualTo("""
				(DEFUN |f| (|if|) (LET ((%SCM-T1 |if|)) (IF (EQ %SCM-T1 RONTOLISP::%SCHEME-FALSE) 1 %SCM-T1)))""");
		assertThat(lowered("(case x ((1 a) 'one) (else 'other))")).isEqualTo("""
				(LET ((%SCM-K1 |x|)) (IF (OR (EQL %SCM-K1 1) (EQL %SCM-K1 '|a|)) '|one| '|other|))""");
	}

	@Test
	void quasiquoteBuildsWithConsAndAppend() {
		assertThat(lowered("`(1 ,x ,@y z) `(plain list) `#(1 ,x)")).isEqualTo("""
				(CONS 1 (CONS |x| (APPEND |y| '(|z|))))
				'(|plain| |list|)
				(COERCE (CONS 1 (CONS |x| NIL)) 'VECTOR)""");
	}

	@Test
	void callWithValuesOverTwoVisibleLambdasIsAMultipleValueBind() {
		assertThat(lowered("(call-with-values (lambda () (values 1 2)) (lambda (a b) (+ a b)))")).isEqualTo("""
				(MULTIPLE-VALUE-BIND (|a| |b|) (VALUES 1 2) (+ |a| |b|))""");
		assertThat(lowered("(call-with-values p c)")).isEqualTo("""
				(APPLY |c| (MULTIPLE-VALUE-LIST (FUNCALL |p|)))""");
	}

	@Test
	void aRecordTypeIsADefstructWhoseSlotsAreNamedAfterTheirAccessors() {
		assertThat(lowered("(define-record-type point (make-point x y) point? (x point-x set-x!) (y point-y))"))
			.isEqualTo("""
					(DEFSTRUCT (|point| (:CONSTRUCTOR |make-point| (|point-x| |point-y|)) (:PREDICATE |point?|) \
					(:COPIER NIL) (:CONC-NAME NIL)) |point-x| |point-y|)
					(DEFUN |set-x!| (%SCM-R1 %SCM-V2) (SETF (|point-x| %SCM-R1) %SCM-V2))""");
	}

	@Test
	void displayOfALiteralNeedsNoPrinter() {
		assertThat(lowered("(display \"text\") (display #\\a) (display 42) (display 1.5)")).isEqualTo("""
				(WRITE-STRING "text")
				(WRITE-CHAR #\\a)
				(PRINC 42)
				(RONTOLISP::%SCHEME-DISPLAY 1.5)""");
	}

	@Test
	void importsSelectWhatIsVisible() {
		assertThat(lowered("(import (scheme base)) (display x)")).isEqualTo("(|display| |x|)");
		assertThat(lowered("(import (scheme base) (scheme write)) (display x)"))
			.isEqualTo("(RONTOLISP::%SCHEME-DISPLAY |x|)");
		assertThat(lowered("(import (scheme process-context)) (exit 2)")).isEqualTo("(RONTOLISP::%SCHEME-EXIT 2)");
		assertThat(lowered("(import (prefix (only (scheme base) car) s:)) (s:car x)")).isEqualTo("(CAR |x|)");
		assertThatThrownBy(() -> lowered("(import (scheme char))")).isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:1:1: library (|scheme| |char|) is not available: this experimental front end has"
					+ " (scheme base), (scheme write), (scheme inexact), (scheme cxr), (scheme lazy) and (scheme process-context) only");
		assertThat(lowered("(import (scheme inexact)) (sqrt x)")).isEqualTo("(RONTOLISP::%SCHEME-SQRT |x|)");
		assertThat(lowered("(import (scheme base)) (sqrt x)")).isEqualTo("(|sqrt| |x|)");
		assertThat(lowered("(import (only (scheme cxr) caddr)) (caddr x)")).isEqualTo("(CADDR |x|)");
	}

	@Test
	void theInexactLibraryIsVisibleWithoutImportAndAUserBindingWins() {
		assertThat(lowered("(list (sqrt x) (atan y x) (log x 10))")).isEqualTo(
				"(LIST (RONTOLISP::%SCHEME-SQRT |x|) (RONTOLISP::%SCHEME-ATAN2 |y| |x|) (RONTOLISP::%SCHEME-LOG-BASE |x| 10))");
		assertThat(lowered("(define (sqrt x) x) (sqrt 4)")).contains("(|sqrt| 4)");
		assertThat(lowered("(define (ev exp env) (exp env))")).contains("(FUNCALL |exp| |env|)");
	}

	@Test
	void theSicpVocabularyIsVisibleOnlyWithNoImportAtAllLikeAReplsEverything() {
		assertThat(lowered("(list true false nil)")).isEqualTo("(LIST T RONTOLISP::%SCHEME-FALSE NIL)");
		assertThat(lowered("(caddr x)")).isEqualTo("(CADDR |x|)");
		// Explicit imports narrow to what they name, exactly like base/write: the sicp
		// tag
		// is reachable by no name and (scheme cxr) only by its own, so importing only
		// (scheme base) leaves filter/caddr undefined in this file -- a call some other
		// file defines.
		assertThat(lowered("(import (scheme base)) (list (filter p l) (caddr x))"))
			.isEqualTo("(LIST (|filter| |p| |l|) (|caddr| |x|))");
	}

	@Test
	void aUserDefineOfASicpOrCxrNameStillWinsLikeSquare() {
		assertThat(lowered("(define (filter pred l) (list 'mine pred l)) (filter p l)")).isEqualTo("""
				(DEFUN |filter| (|pred| |l|) (LIST '|mine| |pred| |l|))
				(|filter| |p| |l|)""");
		assertThat(lowered("(define nil 1) nil")).isEqualTo("""
				(SETQ |nil| 1)
				|nil|""");
	}

	@Test
	void delayAndConsStreamWrapTheirOperandInAPromiseThunk() {
		assertThat(lowered("(list (delay (f x)) (delay-force (g)) (cons-stream 1 (h)) (force p))")).isEqualTo(
				"""
						(LIST (RONTOLISP::%SCHEME-DELAY 0 (LAMBDA NIL (|f| |x|))) (RONTOLISP::%SCHEME-DELAY 1 (LAMBDA NIL (|g|))) \
						(CONS 1 (RONTOLISP::%SCHEME-DELAY 0 (LAMBDA NIL (|h|)))) (RONTOLISP::%SCHEME-FORCE |p|))""");
		assertThat(lowered("(import (scheme lazy)) (delay-force x)"))
			.isEqualTo("(RONTOLISP::%SCHEME-DELAY 1 (LAMBDA NIL |x|))");
		// cons-stream and the stream procedures are the sicp vocabulary: no import names
		// them.
		assertThat(lowered("(import (scheme base) (scheme lazy)) (cons-stream a b)"))
			.isEqualTo("(|cons-stream| |a| |b|)");
	}

	@Test
	void delayAndConsStreamAreShadowableLikeAnyKeyword() {
		assertThat(lowered("(define (after-delay delay action) (list delay (delay action)))")).isEqualTo("""
				(DEFUN |after-delay| (|delay| |action|) (LIST |delay| (FUNCALL |delay| |action|)))""");
		assertThat(lowered("(define (cons-stream a b) (cons a b)) (cons-stream 1 2)")).isEqualTo("""
				(DEFUN |cons-stream| (|a| |b|) (CONS |a| |b|))
				(|cons-stream| 1 2)""");
	}

	@Test
	void aSyntaxErrorIsPositioned() {
		assertThatThrownBy(() -> lowered("(define (f)\n  (if))")).isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:2:3: malformed if");
		assertThatThrownBy(() -> lowered("(car 1 2)")).hasMessage("test.scm:1:1: wrong number of arguments to car: 2");
		assertThatThrownBy(() -> lowered("(f (define x 1))"))
			.hasMessage("test.scm:1:4: a definition is only allowed at the top level or at the head of a body");
		assertThatThrownBy(() -> lowered("(define-syntax m (syntax-rules ()))"))
			.hasMessage("test.scm:1:1: define-syntax is not supported by this experimental front end yet");
	}

}
