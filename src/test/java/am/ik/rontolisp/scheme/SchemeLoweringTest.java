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
	void aBuiltinShadowedOnlyAfterItsLastEarlyUseKeepsTheDirectCall() {
		// Defined before any use, and a body that mentions it but is not called before
		// the definition: the defun, as for any other procedure.
		assertThat(lowered("(define (abs x) 'mine) (abs 1)")).isEqualTo("""
				(DEFUN |abs| (|x|) '|mine|)
				(|abs| 1)""");
		assertThat(lowered("(define (f x) (abs x)) (define (abs x) 'mine) (f 1)")).isEqualTo("""
				(DEFUN |f| (|x|) (|abs| |x|))
				(DEFUN |abs| (|x|) '|mine|)
				(|f| 1)""");
	}

	@Test
	void aBuiltinUsedBeforeTheFileRedefinesItIsAVariableHoldingTheBuiltinUntilThen() {
		// Kept the host's procedure the way the book's evaluators keep apply.
		assertThat(lowered("(define old-abs abs) (define (abs x) (old-abs x)) (abs -5)")).isEqualTo("""
				(SETQ |abs| (LAMBDA (X) (ABS X)))
				(SETQ |old-abs| |abs|)
				(SETQ |abs| (LAMBDA (|x|) (FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |old-abs|) |x|)))
				(FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |abs|) -5)""");
		// Called at the top level first, and reached through a procedure called before
		// the definition.
		assertThat(lowered("(abs -5) (define (abs x) 'mine)")).isEqualTo("""
				(SETQ |abs| (LAMBDA (X) (ABS X)))
				(FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |abs|) -5)
				(SETQ |abs| (LAMBDA (|x|) '|mine|))""");
		assertThat(lowered("(define (f x) (abs x)) (f 1) (define (abs x) 'mine)")).isEqualTo("""
				(SETQ |abs| (LAMBDA (X) (ABS X)))
				(DEFUN |f| (|x|) (FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |abs|) |x|))
				(|f| 1)
				(SETQ |abs| (LAMBDA (|x|) '|mine|))""");
		// A value, not a procedure, and a SICP constant.
		assertThat(lowered("(define n (list nil)) (define nil 5)")).isEqualTo("""
				(SETQ |nil| NIL)
				(SETQ |n| (LIST |nil|))
				(SETQ |nil| 5)""");
	}

	@Test
	void anyOtherProcedureBindingIsAVariableCalledThroughFuncall() {
		assertThat(lowered("(define f (lambda (x) x)) (set! f car) (f 1) (define (g h) (h f))")).isEqualTo("""
				(SETQ |f| (LAMBDA (|x|) |x|))
				(SETQ |f| #'CAR)
				(FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |f|) 1)
				(DEFUN |g| (|h|) (FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |h|) |f|))""");
	}

	@Test
	void aTopLevelVariableIsASetqNeverADefvar() {
		assertThat(lowered("(define x 1) (define y)")).isEqualTo("""
				(SETQ |x| 1)
				(SETQ |y| NIL)""");
	}

	@Test
	void aProcedureNameInValuePositionIsAFunctionValue() {
		assertThat(lowered("(define (f x) x) (map f '(1)) (map car '((1))) (map pair? '(1))")).isEqualTo(
				"""
						(DEFUN |f| (|x|) |x|)
						(MAPCAR (RONTOLISP::%SCHEME-ENSURE-PROCEDURE #'|f|) '(1))
						(MAPCAR (RONTOLISP::%SCHEME-ENSURE-PROCEDURE #'CAR) '((1)))
						(MAPCAR (RONTOLISP::%SCHEME-ENSURE-PROCEDURE (LAMBDA (X) (IF (CONSP X) T RONTOLISP::%SCHEME-FALSE))) '(1))""");
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
	void aLoopLeavesThroughItsBlockWhereTheLeafMayAnswerSeveralValues() {
		// (setq R (f)) would keep f's first value: the call returns from the loop's
		// block with all of them, while a one-valued leaf still stores.
		assertThat(lowered("(define (last-or l) (let loop ((l l)) "
				+ "(cond ((null? l) (f)) ((null? (cdr l)) (car l)) (else (loop (cdr l))))))"))
			.isEqualTo("""
					(DEFUN |last-or| (|l|) (BLOCK %SCM-B3 (LET ((|l| |l|) (%SCM-R3 NIL)) (TAGBODY %SCM-L2 \
					(IF (NULL |l|) (RETURN-FROM %SCM-B3 (|f|)) (IF (NULL (CDR |l|)) (SETQ %SCM-R3 (CAR |l|)) \
					(PROGN (SETQ |l| (CDR |l|)) (GO %SCM-L2))))) %SCM-R3)))""");
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
		assertThat(lowered("(let fact ((n 5)) (if (= n 0) 1 (* n (fact (- n 1)))))")).isEqualTo(
				"""
						(LET ((|fact| NIL)) (SETQ |fact| (LAMBDA (|n|) (IF (= |n| 0) 1 (* |n| (FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |fact|) (- |n| 1)))))) \
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
				(FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |g|) |x|)))""");
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
		assertThat(lowered("(call-with-values p c)")).isEqualTo(
				"""
						(APPLY (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |c|) (MULTIPLE-VALUE-LIST (FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |p|))))""");
	}

	@Test
	void aRecordTypeIsADefstructWhoseSlotsAreNamedAfterTheirAccessors() {
		assertThat(lowered("(define-record-type point (make-point x y) point? (x point-x set-x!) (y point-y))"))
			.isEqualTo("""
					(DEFSTRUCT (|point| (:CONSTRUCTOR |make-point| (|point-x| |point-y|)) (:PREDICATE |point?|) \
					(:COPIER NIL) (:CONC-NAME NIL)) |point-x| |point-y|)
					(DEFUN |set-x!| (%SCM-R1 %SCM-V2) (SETF (|point-x| %SCM-R1) %SCM-V2) \
					RONTOLISP::%SCHEME-UNSPECIFIED)""");
	}

	@Test
	void anInternalRecordTypeIsAHoistedDefstructItsBodyCallsDirectly() {
		// Named after the enclosing definition and the type, a spelling no identifier
		// mangles to; slots after the fields; no variable bound in the body.
		assertThat(lowered("""
				(define (f n)
				  (define-record-type node (make-node v) node? (v node-v set-node-v!))
				  (define x (make-node n))
				  (set-node-v! x 1)
				  (if (node? x) (node-v x) node-v))""")).isEqualTo("""
				(DEFSTRUCT (|s%%[f node]| (:CONSTRUCTOR |s%%[f node](make-node)| (|v|)) \
				(:PREDICATE |s%%[f node](node?)|) (:COPIER NIL) (:CONC-NAME "s%%[f node] ")) |v|)
				(DEFUN |s%%[f node](set-node-v!)| (%SCM-R1 %SCM-V2) (SETF (|s%%[f node] v| %SCM-R1) %SCM-V2) \
				RONTOLISP::%SCHEME-UNSPECIFIED)
				(DEFUN |f| (|n|) (LET ((|x| NIL)) (SETQ |x| (|s%%[f node](make-node)| |n|)) \
				(|s%%[f node](set-node-v!)| |x| 1) \
				(IF (|s%%[f node](node?)| |x|) (|s%%[f node] v| |x|) #'|s%%[f node] v|)))""");
	}

	@Test
	void internalRecordTypesOfTheSameNameAreDistinctTypes() {
		// A second one in the same definition takes an ordinal; one outside any
		// definition has an empty qualifier. The hoisted forms stand before their form.
		assertThat(lowered("""
				(define (g)
				  (let () (define-record-type t #f t?) 1)
				  (let () (define-record-type t #f t?) 2))
				(display (let () (define-record-type t #f t?) (t? 1)))""")).isEqualTo("""
				(DEFSTRUCT (|s%%[g t]| (:CONSTRUCTOR %SCM-MAKE1 NIL) (:PREDICATE |s%%[g t](t?)|) \
				(:COPIER NIL) (:CONC-NAME "s%%[g t] ")))
				(DEFSTRUCT (|s%%[g t 2]| (:CONSTRUCTOR %SCM-MAKE2 NIL) (:PREDICATE |s%%[g t 2](t?)|) \
				(:COPIER NIL) (:CONC-NAME "s%%[g t 2] ")))
				(DEFUN |g| NIL (LET NIL 1) (LET NIL 2))
				(DEFSTRUCT (|s%%[ t]| (:CONSTRUCTOR %SCM-MAKE3 NIL) (:PREDICATE |s%%[ t](t?)|) \
				(:COPIER NIL) (:CONC-NAME "s%%[ t] ")))
				(RONTOLISP::%SCHEME-DISPLAY (LET NIL (IF (|s%%[ t](t?)| 1) T RONTOLISP::%SCHEME-FALSE)))""");
	}

	@Test
	void anInternalRecordTypeShadowsAndIsScopedToItsBody() {
		assertThat(lowered("""
				(define (h point-x)
				  (list (let () (define-record-type point (mk x) point? (x point-x)) (point-x (mk 1)))
				        point-x))""")).contains("(|s%%[h point] x| (|s%%[h point](mk)| 1))").contains(" |point-x|))");
	}

	@Test
	void anInternalRecordProcedureIsNotAVariable() {
		assertThatThrownBy(() -> lowered("(define (f) (define-record-type p (mk) p?) (define mk 1) mk)"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a body defines mk twice");
		assertThatThrownBy(() -> lowered("(define (f) (define-record-type p (mk) p?) (set! mk 1))"))
			.hasMessageContaining("cannot assign mk: it is not a variable");
		assertThatThrownBy(() -> lowered("(display (if #t (define-record-type p (mk) p?)))"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("define-record-type is only allowed at the top level or in a body");
	}

	@Test
	void aMacroUseBesideAnInternalRecordTypeKeepsItsNamesLocal() {
		// The expander renames the record's procedures like any internal definition, so
		// the template's own point-x still means the global one; a renamed procedure's
		// hoisted name carries the generated spelling.
		assertThat(lowered("""
				(define (point-x p) 'global)
				(define-syntax gx (syntax-rules () ((_ v) (point-x v))))
				(define (f)
				  (define-record-type point (mk x) point? (x point-x))
				  (list (point-x (mk 1)) (gx 2)))"""))
			.contains("(LIST (|s%%[f point] x| (|s%%[f point](%SCM-V2)| 1)) (|point-x| 2))");
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
		assertThat(lowered("(import (scheme process-context)) (exit 2)"))
			.isEqualTo("(LET ((%SCM-EXIT-DONE1 (LIST NIL)))"
					+ " (LET ((%SCM-EXIT-CODE2 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (THROW 'RONTOLISP::%SCHEME-EXIT-TAG 2)"
					+ " %SCM-EXIT-DONE1)))) (IF (EQ %SCM-EXIT-CODE2 %SCM-EXIT-DONE1) NIL"
					+ " (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE2))))");
		assertThat(lowered("(import (prefix (only (scheme base) car) s:)) (s:car x)")).isEqualTo("(CAR |x|)");
		assertThatThrownBy(() -> lowered("(import (scheme time))")).isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:1:1: library (|scheme| |time|) is not available: this experimental front end has"
					+ " (scheme base), (scheme write), (scheme read), (scheme char), (scheme inexact), (scheme cxr),"
					+ " (scheme lazy), (scheme case-lambda), (scheme process-context), (scheme eval), (scheme repl)"
					+ " and (scheme file) only");
		assertThat(lowered("(import (scheme char)) (char-upcase x)")).isEqualTo("(CHAR-UPCASE |x|)");
		assertThat(lowered("(import (scheme base)) (char-upcase x)")).isEqualTo("(|char-upcase| |x|)");
		assertThat(lowered("(char-upcase x)")).isEqualTo("(CHAR-UPCASE |x|)");
		assertThat(lowered("(import (scheme inexact)) (sqrt x)")).isEqualTo("(RONTOLISP::%SCHEME-SQRT |x|)");
		assertThat(lowered("(import (scheme base)) (sqrt x)")).isEqualTo("(|sqrt| |x|)");
		assertThat(lowered("(import (only (scheme cxr) caddr)) (caddr x)")).isEqualTo("(CADDR |x|)");
		assertThat(lowered("(import (scheme file)) (file-exists? x)"))
			.isEqualTo("(IF (PROBE-FILE |x|) T RONTOLISP::%SCHEME-FALSE)");
		assertThat(lowered("(import (scheme base)) (open-input-file x)")).isEqualTo("(|open-input-file| |x|)");
		assertThat(lowered("(open-input-file x)"))
			.isEqualTo("(RONTOLISP::%SCHEME-OPEN-INPUT-FILE \"open-input-file\" |x|)");
	}

	@Test
	void exitThrowsToATagEachTopLevelFormCatches() {
		// emergency-exit ends the process where it stands and never throws, so alone it
		// wraps nothing; exit throws, so its form is caught, and a defun stays bare --
		// the backends only hoist one that is a direct child of the program.
		assertThat(lowered("(import (scheme process-context)) (emergency-exit 7)"))
			.isEqualTo("(RONTOLISP::%SCHEME-EXIT 7)");
		// The trigger is file-level: (f) spells neither exit nor eval, but f throws.
		assertThat(lowered("(import (scheme base) (scheme process-context)) (define (f) (exit 7)) (f)"))
			.startsWith("(DEFUN |f|");
		assertThat(lowered("(import (scheme base) (scheme process-context)) (define (f) (exit 7)) (f)")).contains("""
				(CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (|f|)""");
		// A program spelling neither exit nor eval cannot reach the throw and is
		// emitted exactly as before.
		assertThat(lowered("(display 1) (display 2)")).isEqualTo("(PRINC 1)\n(PRINC 2)");
	}

	@Test
	void evalAndEveryEnvironmentSpecifierLowerToTheRunTimeEvaluator() {
		// The evaluator is %scheme-eval in scheme.lisp; every specifier is the one
		// global environment, a symbol. The env argument may be left out.
		// Run-time data handed to eval may name exit, so every form of a file that
		// spells eval runs inside the exit catch too.
		assertThat(lowered("(eval x user-initial-environment) (eval x) (interaction-environment)"
				+ " system-global-environment (scheme-report-environment 5) (environment '(scheme base))"))
			.isEqualTo(
					"""
							(LET ((%SCM-EXIT-DONE1 (LIST NIL))) (LET ((%SCM-EXIT-CODE2 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (RONTOLISP::%SCHEME-EVAL-IN |x| '|#[environment]|) %SCM-EXIT-DONE1)))) (IF (EQ %SCM-EXIT-CODE2 %SCM-EXIT-DONE1) NIL (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE2))))
							(LET ((%SCM-EXIT-DONE3 (LIST NIL))) (LET ((%SCM-EXIT-CODE4 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (RONTOLISP::%SCHEME-EVAL |x| NIL) %SCM-EXIT-DONE3)))) (IF (EQ %SCM-EXIT-CODE4 %SCM-EXIT-DONE3) NIL (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE4))))
							(LET ((%SCM-EXIT-DONE5 (LIST NIL))) (LET ((%SCM-EXIT-CODE6 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN '|#[environment]| %SCM-EXIT-DONE5)))) (IF (EQ %SCM-EXIT-CODE6 %SCM-EXIT-DONE5) NIL (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE6))))
							(LET ((%SCM-EXIT-DONE7 (LIST NIL))) (LET ((%SCM-EXIT-CODE8 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN '|#[environment]| %SCM-EXIT-DONE7)))) (IF (EQ %SCM-EXIT-CODE8 %SCM-EXIT-DONE7) NIL (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE8))))
							(LET ((%SCM-EXIT-DONE9 (LIST NIL))) (LET ((%SCM-EXIT-CODE10 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (PROGN 5 '|#[environment]|) %SCM-EXIT-DONE9)))) (IF (EQ %SCM-EXIT-CODE10 %SCM-EXIT-DONE9) NIL (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE10))))
							(LET ((%SCM-EXIT-DONE11 (LIST NIL))) (LET ((%SCM-EXIT-CODE12 (CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (RONTOLISP::%SCHEME-ENVIRONMENT (LIST '(|scheme| |base|))) %SCM-EXIT-DONE11)))) (IF (EQ %SCM-EXIT-CODE12 %SCM-EXIT-DONE11) NIL (RONTOLISP::%SCHEME-EXIT %SCM-EXIT-CODE12))))""");
		// (scheme eval) and (scheme repl) are importable; the MIT and R5RS names ride the
		// no-import default only, like the sicp tag.
		assertThat(lowered("(import (scheme eval) (scheme repl)) (eval x (interaction-environment))"
				+ " user-initial-environment (scheme-report-environment 5)")
			.split("\n")).hasSize(3)
			.allSatisfy(form -> assertThat(form).startsWith("(LET ((%SCM-EXIT-DONE")
				.contains("CATCH 'RONTOLISP::%SCHEME-EXIT-TAG"));
		assertThat(lowered("(import (scheme base)) (eval x)")).startsWith("(LET ((%SCM-EXIT-DONE1 (LIST NIL)))")
			.contains("(CATCH 'RONTOLISP::%SCHEME-EXIT-TAG (PROGN (|eval| |x|)");
		// The run-time table behind it is generated from the same entries, one arm per
		// procedure and constant by its mangled name, cut to the names a program spells.
		assertThat(Scheme
			.runtimeForms(name -> name.equals("s%+") || name.equals("car") || name.equals("false"),
					SchemeStandard.RONTOLISP)
			.get(0)
			.print())
			.isEqualTo("(DEFUN RONTOLISP::%SCHEME-BUILTIN (NAME) (CASE NAME ((|s%+|) #'+) ((|car|) #'CAR)"
					+ " ((|false|) RONTOLISP::%SCHEME-FALSE) (T 'RONTOLISP::%SCHEME-UNBOUND)))");
		assertThat(Scheme.runtimeForms(name -> false, SchemeStandard.RONTOLISP).get(1).print()).isEqualTo(
				"(DEFUN RONTOLISP::%SCHEME-LIBRARY-P (NAME) (IF (MEMBER NAME '(|base| |write| |read| |char| |inexact| |cxr|"
						+ " |lazy| |case-lambda| |process-context| |eval| |repl| |file|)) T NIL))");
	}

	@Test
	void theInexactLibraryIsVisibleWithoutImportAndAUserBindingWins() {
		assertThat(lowered("(list (sqrt x) (atan y x) (log x 10))")).isEqualTo(
				"(LIST (RONTOLISP::%SCHEME-SQRT |x|) (RONTOLISP::%SCHEME-ATAN2 |y| |x|) (RONTOLISP::%SCHEME-LOG-BASE |x| 10))");
		assertThat(lowered("(define (sqrt x) x) (sqrt 4)")).contains("(|sqrt| 4)");
		assertThat(lowered("(define (ev exp env) (exp env))"))
			.contains("(FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |exp|) |env|)");
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
	void parallelExecuteAndTestAndSetAreSicpNamesAUserDefineReplaces() {
		assertThat(lowered("(parallel-execute a b) (if (test-and-set! c) 1 2)")).isEqualTo("""
				(RONTOLISP::%SCHEME-PARALLEL-EXECUTE (LIST |a| |b|))
				(IF (RONTOLISP::%SCHEME-TEST-AND-SET! |c|) 1 2)""");
		// The book's own non-atomic version (section 3.4.2) keeps winning.
		assertThat(lowered("(define (test-and-set! cell) (car cell)) (test-and-set! c)")).isEqualTo("""
				(DEFUN |test-and-set!| (|cell|) (CAR |cell|))
				(|test-and-set!| |c|)""");
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
		assertThat(lowered("(define (after-delay delay action) (list delay (delay action)))")).isEqualTo(
				"""
						(DEFUN |after-delay| (|delay| |action|) (LIST |delay| (FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |delay|) |action|)))""");
		assertThat(lowered("(define (cons-stream a b) (cons a b)) (cons-stream 1 2)")).isEqualTo("""
				(DEFUN |cons-stream| (|a| |b|) (CONS |a| |b|))
				(|cons-stream| 1 2)""");
	}

	@Test
	void eachNameIsImportedFromTheR7rsLibraryThatExportsIt() {
		// exact->inexact and inexact->exact are (scheme r5rs) names: no R7RS import
		// reaches them, only the no-import default.
		assertThat(lowered("(import (scheme base) (scheme inexact)) (exact->inexact x) (inexact->exact y)"))
			.isEqualTo("(|exact->inexact| |x|)\n(|inexact->exact| |y|)");
		assertThat(lowered("(exact->inexact x)")).isEqualTo("(FLOAT |x| 1.0)");
		// The current-input-port character procedures and the EOF object are
		// (scheme base); (scheme read) exports read alone.
		assertThat(lowered("(import (scheme base)) (read-char) (peek-char) (read-line) (eof-object)")).isEqualTo("""
				(RONTOLISP::%SCHEME-READ-CHAR)
				(RONTOLISP::%SCHEME-PEEK-CHAR)
				(RONTOLISP::%SCHEME-READ-LINE)
				(RONTOLISP::%SCHEME-EOF-OBJECT)""");
		assertThat(lowered("(import (scheme read)) (read) (read-char) (eof-object)"))
			.isEqualTo("(RONTOLISP::%SCHEME-READ)\n(|read-char|)\n(|eof-object|)");
	}

	private static String strict(String source) {
		List<LispVal> forms = Scheme.read(source, "test.scm", SchemeStandard.R7RS);
		return forms.subList(1, forms.size()).stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	@Test
	void anR7rsProgramBeginsWithAnImportDeclaration() {
		assertThatThrownBy(() -> strict("; a comment\n  (display 1)")).isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:2:3: an R7RS program begins with an import declaration");
		assertThatThrownBy(() -> strict("\n42"))
			.hasMessage("test.scm:2:1: an R7RS program begins with an import declaration");
		assertThatThrownBy(() -> strict(""))
			.hasMessage("test.scm:1:1: an R7RS program begins with an import declaration");
		assertThat(strict("(import (scheme base) (scheme write)) (display 1)")).isEqualTo("(PRINC 1)");
	}

	@Test
	void strictR7rsNeverSeesTheSicpOrR5rsNames() {
		// Unreachable by import in either standard; strict mode has no no-import default,
		// and a name nobody imports or defines is a direct call as always.
		assertThat(strict("(import (scheme base) (scheme inexact)) (exact->inexact x) (1+ x) true"))
			.isEqualTo("(|exact->inexact| |x|)\n(|s%1+| |x|)\n|true|");
		assertThat(strict("(import (scheme base)) (cons-stream a b)")).isEqualTo("(|cons-stream| |a| |b|)");
	}

	@Test
	void strictR7rsRefusesRedefiningOrAssigningAnImport() {
		assertThatThrownBy(() -> strict("(import (scheme base))\n(define (car x) x)"))
			.isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:2:1: cannot redefine car: it is imported (R7RS 5.6.1)");
		assertThatThrownBy(() -> strict("(import (scheme base))\n(define list 1)"))
			.hasMessage("test.scm:2:1: cannot redefine list: it is imported (R7RS 5.6.1)");
		assertThatThrownBy(() -> strict("(import (scheme base))\n(define-values (a cdr) (values 1 2))"))
			.hasMessage("test.scm:2:1: cannot redefine cdr: it is imported (R7RS 5.6.1)");
		assertThatThrownBy(
				() -> strict("(import (scheme base))\n(define-record-type point (make-point x) pair? (x px))"))
			.hasMessage("test.scm:2:1: cannot redefine pair?: it is imported (R7RS 5.6.1)");
		assertThatThrownBy(() -> strict("(import (scheme base))\n(define (when x) x)"))
			.hasMessage("test.scm:2:1: cannot redefine when: it is imported (R7RS 5.6.1)");
		assertThatThrownBy(() -> strict("(import (scheme base))\n(define (f) (set! car 1))"))
			.hasMessage("test.scm:2:13: cannot assign car: it is imported (R7RS 5.6.1)");
		// What the file did not import is free, and so is a local binding of an import.
		assertThat(strict("(import (except (scheme base) cdr))\n(define (cdr x) x) (define (f car) (set! car 1) car)"))
			.isEqualTo("(DEFUN |cdr| (|x|) |x|)\n(DEFUN |f| (|car|) (SETQ |car| 1) |car|)");
		// The default standard lets a user definition win.
		assertThat(lowered("(import (scheme base)) (define (car x) x) (car 1)")).contains("(DEFUN |car| (|x|) |x|)");
	}

	@Test
	void strictR7rsEvalTakesItsEnvironment() {
		assertThatThrownBy(() -> strict("(import (scheme base) (scheme eval))\n(eval 'x)"))
			.hasMessage("test.scm:2:1: wrong number of arguments to eval: 1");
		assertThat(strict("(import (scheme base) (scheme eval))\n(eval 'x (environment '(scheme base)))"))
			.contains("(RONTOLISP::%SCHEME-EVAL-IN '|x| (RONTOLISP::%SCHEME-ENVIRONMENT (LIST '(|scheme| |base|))))");
		// The run-time table behind eval holds no sicp or r5rs name either, and a
		// first-class eval needs its environment too.
		assertThat(Scheme.runtimeForms(name -> true, SchemeStandard.R7RS).get(0).print()).doesNotContain("|s%1+|")
			.doesNotContain("|exact->inexact|")
			.doesNotContain("|user-initial-environment|")
			.contains("((|eval|) (LAMBDA (X ENV) (RONTOLISP::%SCHEME-EVAL-IN X ENV)))")
			.contains("((|car|) #'CAR)");
		assertThat(Scheme.runtimeForms(name -> true, SchemeStandard.RONTOLISP).get(0).print()).contains("|s%1+|");
		assertThat(Scheme.runtimeForms(name -> false, SchemeStandard.R7RS).get(2).print())
			.isEqualTo("(DEFUN RONTOLISP::%SCHEME-EVAL-EXTENSION-KEYWORD-P (NAME) (IF (MEMBER NAME 'NIL) T NIL))");
		assertThat(Scheme.runtimeForms(name -> false, SchemeStandard.RONTOLISP).get(2).print()).isEqualTo(
				"(DEFUN RONTOLISP::%SCHEME-EVAL-EXTENSION-KEYWORD-P (NAME) (IF (MEMBER NAME '(|cons-stream|)) T NIL))");
	}

	@Test
	void aStrictSessionStartsWithEveryR7rsLibraryAndMayRedefine() {
		SchemeSession session = Scheme.session(SchemeStandard.R7RS);
		assertThat(session.read("(display (square 2))").get(1).forms().get(0).print()).contains("%SCHEME-DISPLAY");
		assertThat(session.read("(1+ 2)").get(0).forms().get(0).print()).contains("(|s%1+| 2)");
		assertThat(session.read("(define (car x) x)").get(0).forms().get(0).print())
			.contains("(SETQ |car| (LAMBDA (|x|) |x|))");
	}

	@Test
	void aSyntaxErrorIsPositioned() {
		assertThatThrownBy(() -> lowered("(define (f)\n  (if))")).isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:2:3: malformed if");
		assertThatThrownBy(() -> lowered("(car 1 2)")).hasMessage("test.scm:1:1: wrong number of arguments to car: 2");
		assertThatThrownBy(() -> lowered("(f (define x 1))"))
			.hasMessage("test.scm:1:4: a definition is only allowed at the top level or at the head of a body");
		assertThatThrownBy(() -> lowered("(cond-expand (else 1))"))
			.hasMessage("test.scm:1:1: cond-expand is not supported by this experimental front end yet");
		assertThatThrownBy(() -> lowered("(case-lambda ((x) x) (y))"))
			.hasMessage("test.scm:1:22: a case-lambda clause is (formals body...)");
		assertThatThrownBy(() -> lowered("(case-lambda ((x 1) x))"))
			.hasMessage("test.scm:1:14: expected an identifier, got 1");
		assertThatThrownBy(() -> lowered("(parameterize ((p)) 2)"))
			.hasMessage("test.scm:1:16: a parameterize binding is (parameter value)");
		assertThatThrownBy(() -> lowered("(guard e 1)"))
			.hasMessage("test.scm:1:1: a guard needs (variable clause...) and a body");
	}

	@Test
	void aGuardIsABodyThunkAndAClauseProcedureWhoseMissingElseRaisesAgain() {
		assertThat(lowered("(display (guard (e ((symbol? e) (list 'sym e))) (raise 'x)))")).isEqualTo(
				"(RONTOLISP::%SCHEME-DISPLAY (RONTOLISP::%SCHEME-GUARD (LAMBDA NIL (RONTOLISP::%SCHEME-RAISE '|x|)) "
						+ "(LAMBDA (%SCM-C1) (LET ((|e| %SCM-C1)) (IF (AND (SYMBOLP |e|) |e| (NOT (EQ |e| T)) "
						+ "(NOT (EQ |e| RONTOLISP::%SCHEME-FALSE)) (NOT (EQ |e| RONTOLISP::%SCHEME-UNSPECIFIED))) "
						+ "(LIST '|sym| |e|) (RONTOLISP::%SCHEME-RAISE %SCM-C1))))))");
		// A user else is taken first; a user binding of raise does not reach the
		// re-raise.
		assertThat(lowered("(define (raise x) x) (display (guard (e (else 1)) 2))"))
			.contains("(LAMBDA (%SCM-C1) (LET ((|e| %SCM-C1)) 1))");
	}

	@Test
	void caseLambdaIsOneRestLambdaTakingTheFirstClauseThatAcceptsTheCount() {
		assertThat(lowered("(display (case-lambda ((x) x) ((x . r) r)))"))
			.isEqualTo("(RONTOLISP::%SCHEME-DISPLAY (LAMBDA (&REST %SCM-A1) (LET ((%SCM-N2 (LENGTH %SCM-A1)))"
					+ " (IF (= %SCM-N2 1) (LET ((|x| (NTH 0 %SCM-A1))) |x|)"
					+ " (IF (>= %SCM-N2 1) (LET ((|x| (NTH 0 %SCM-A1)) (|r| (NTHCDR 1 %SCM-A1))) |r|)"
					+ " (RONTOLISP::%SCHEME-CASE-LAMBDA-ARITY %SCM-A1))))))");
		// A clause taking any count ends the chain; nothing counts when it is the only
		// one.
		assertThat(lowered("(display (case-lambda (r r)))"))
			.isEqualTo("(RONTOLISP::%SCHEME-DISPLAY (LAMBDA (&REST %SCM-A1) (LET ((|r| %SCM-A1)) |r|)))");
		// Defined once, each clause is a defun of its own that a direct call picks by
		// its count, a self tail call picking the same clause is a jump, and the
		// procedure's own defun dispatches for everything else.
		assertThat(lowered("(define f (case-lambda ((n) (f n 0)) ((n acc) (if (= n 0) acc (f (- n 1) (+ acc n))))))"
				+ " (display (f 3))"))
			.isEqualTo(
					"""
							(DEFUN |s%%{f 1}| (|n|) (|s%%{f 2}| |n| 0))
							(DEFUN |s%%{f 2}| (%SCM-C6 %SCM-C7) (LET ((|n| %SCM-C6) (|acc| %SCM-C7) (%SCM-R9 NIL)) \
							(TAGBODY %SCM-L8 (IF (= |n| 0) (SETQ %SCM-R9 |acc|) (PROGN (PSETQ |n| (- |n| 1) |acc| (+ |acc| |n|)) \
							(GO %SCM-L8)))) %SCM-R9))
							(DEFUN |f| (&REST %SCM-A10) (LET ((%SCM-N11 (LENGTH %SCM-A10))) \
							(IF (= %SCM-N11 1) (|s%%{f 1}| (NTH 0 %SCM-A10)) (IF (= %SCM-N11 2) \
							(|s%%{f 2}| (NTH 0 %SCM-A10) (NTH 1 %SCM-A10)) (RONTOLISP::%SCHEME-CASE-LAMBDA-ARITY %SCM-A10)))))
							(RONTOLISP::%SCHEME-DISPLAY (|s%%{f 1}| 3))""");
		// A rest clause is applied by the dispatch; a count only a later clause would
		// accept picks the first one; a count none accepts calls the dispatch, which
		// reports it at run time; a first-class use is the dispatch.
		assertThat(lowered("(define g (case-lambda ((x . r) r) ((x y) y))) (display (list (g 1 2) (g) g))"))
			.contains("(IF (>= %SCM-N4 1) (APPLY #'|s%%{g 1}| %SCM-A3) (IF (= %SCM-N4 2)")
			.endsWith("(LIST (|s%%{g 1}| 1 2) (|g|) #'|g|))");
		// A clause that may call another clause calling it back keeps the one
		// dispatching lambda, so a tail call among them stays a jump.
		assertThat(lowered("(define h (case-lambda ((n) (if (= n 0) 0 (h n 1))) ((n k) (h (- n k))))) (display (h 3))"))
			.startsWith("(DEFUN |h| (&REST %SCM-C")
			.contains("(GO %SCM-L")
			.doesNotContain("s%%{")
			.endsWith("(RONTOLISP::%SCHEME-DISPLAY (|h| 3))");
		// A user binding of the name, or of car, reaches neither the keyword nor the
		// dispatch.
		assertThat(lowered("(define (car x) x) (display (let ((case-lambda list)) (case-lambda 1 2)))"))
			.endsWith("(RONTOLISP::%SCHEME-DISPLAY (LET ((|case-lambda| #'LIST)) (FUNCALL"
					+ " (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |case-lambda|) 1 2)))");
	}

	@Test
	void caseLambdaIsImportedFromItsOwnLibraryOnly() {
		assertThat(strict("(import (scheme base)) (case-lambda ((x) x))")).startsWith("(|case-lambda| ");
		assertThat(strict("(import (scheme base) (only (scheme case-lambda) case-lambda)) (case-lambda ((x) x))"))
			.startsWith("(LAMBDA (&REST ");
		assertThat(lowered("(import (prefix (scheme case-lambda) s:)) (s:case-lambda ((x) x))"))
			.startsWith("(LAMBDA (&REST ");
	}

	@Test
	void parameterizeIsTheParametersAndValuesInOrderAndABodyThunk() {
		assertThat(lowered("(define p (make-parameter 1)) (display (parameterize ((p 2) (car 3)) (define x (p)) x))"))
			.isEqualTo(
					"""
							(SETQ |p| (RONTOLISP::%SCHEME-MAKE-PARAMETER 1 NIL))
							(RONTOLISP::%SCHEME-DISPLAY (RONTOLISP::%SCHEME-PARAMETERIZE (LIST |p| 2 #'CAR 3) \
							(LAMBDA NIL (LET ((|x| NIL)) (SETQ |x| (FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |p|))) |x|))))""");
		// No binding: the body alone, no helper.
		assertThat(lowered("(display (parameterize () 1))")).isEqualTo("(RONTOLISP::%SCHEME-DISPLAY (LET NIL 1))");
		// A converter is checked to be a procedure where make-parameter is called.
		assertThat(lowered("(define (f x) x) (make-parameter 1 f)"))
			.contains("(RONTOLISP::%SCHEME-MAKE-PARAMETER 1 (RONTOLISP::%SCHEME-ENSURE-PROCEDURE #'|f|))");
	}

	@Test
	void aPortArgumentSelectsThePortHelperAndACurrentPortIsAParameterValue() {
		// Without a port the call is what it always was, so a program using no port
		// procedure lowers byte for byte as before.
		assertThat(lowered("(display 'x) (newline) (read-char)"))
			.isEqualTo("(RONTOLISP::%SCHEME-DISPLAY '|x|)\n(TERPRI)\n(RONTOLISP::%SCHEME-READ-CHAR)");
		assertThat(lowered("(define p (open-output-string)) (display 'x p) (newline p) (write-string \"abc\" p 1)"))
			.isEqualTo("""
					(SETQ |p| (RONTOLISP::%SCHEME-OPEN-OUTPUT-STRING))
					(RONTOLISP::%SCHEME-DISPLAY-TO '|x| |p|)
					(TERPRI (RONTOLISP::%SCHEME-OUTPUT-STREAM "newline" |p|))
					(RONTOLISP::%SCHEME-WRITE-STRING-TO "abc" |p| 1 NIL)""");
		// A call of a current port answers the port; its value is the parameter object
		// parameterize binds.
		assertThat(lowered("(display (current-output-port))"))
			.isEqualTo("(RONTOLISP::%SCHEME-DISPLAY (RONTOLISP::%SCHEME-CURRENT-PORT 1))");
		assertThat(lowered("(parameterize ((current-output-port 1)) 2)")).isEqualTo(
				"(RONTOLISP::%SCHEME-PARAMETERIZE (LIST (RONTOLISP::%SCHEME-PORT-PARAMETER 1) 1) (LAMBDA NIL 2))");
	}

	@Test
	void aSyntaxRulesMacroIsExpandedBeforeTheLoweringSeesTheProgram() {
		// The definition leaves nothing behind; the use is what its template says.
		assertThat(lowered("(define-syntax ten (syntax-rules () ((_) 10))) (display (ten))")).isEqualTo("(PRINC 10)");
		// A macro expanding to a definition is seen by the defun-or-variable pre-scan...
		assertThat(lowered("""
				(define-syntax def (syntax-rules () ((_ n v) (define n v))))
				(def f (lambda (x) x))
				(f 1)""")).isEqualTo("(DEFUN |f| (%SCM-V1) %SCM-V1)\n(|f| 1)");
		// ...and so is a set! it expands to.
		assertThat(lowered("""
				(define-syntax inc! (syntax-rules () ((_ v) (set! v (+ v 1)))))
				(define (g) 1)
				(inc! g)""")).startsWith("(SETQ |g| (LAMBDA NIL 1))");
	}

	@Test
	void aProgramThatDefinesAMacroRenamesEveryLocalVariable() {
		// No Common Lisp binding may capture the name a free template identifier is
		// emitted as, so a program with a syntax definition binds no local by its own
		// name. A program without one is lowered exactly as before.
		assertThat(lowered("(define-syntax m (syntax-rules () ((_ e) e))) (define (f a) (let ((b a)) (m b)))"))
			.isEqualTo("(DEFUN |f| (%SCM-V1) (LET ((%SCM-V2 %SCM-V1)) %SCM-V2))");
		assertThat(lowered("(define (f a) (let ((b a)) b))")).isEqualTo("(DEFUN |f| (|a|) (LET ((|b| |a|)) |b|))");
	}

	@Test
	void aTemplateBinderCapturesNoUserIdentifierAndAFreeTemplateIdentifierMeansWhatItMeantAtTheDefinition() {
		// swap!'s tmp is a fresh variable, not the user's global tmp.
		assertThat(lowered("""
				(define-syntax swap! (syntax-rules () ((_ a b) (let ((tmp a)) (set! a b) (set! b tmp)))))
				(define tmp 1)
				(define y 2)
				(swap! tmp y)""")).endsWith("(LET ((%SCM-V1 |tmp|)) (SETQ |tmp| |y|) (SETQ |y| %SCM-V1))");
		// The template's `if` is the keyword even where the user bound `if`.
		assertThat(lowered("""
				(define-syntax my-if (syntax-rules () ((_ c a b) (if c a b))))
				(define (f if) (my-if if 1 2))"""))
			.isEqualTo("(DEFUN |f| (%SCM-V1) (IF (EQ %SCM-V1 RONTOLISP::%SCHEME-FALSE) 2 1))");
	}

	@Test
	void aMisusedMacroIsAPositionedError() {
		assertThatThrownBy(() -> lowered("(define-syntax m (syntax-rules () ((_ a) a)))\n(m 1 2)"))
			.isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:2:1: no syntax-rules clause of m matches (m 1 2)");
		assertThatThrownBy(() -> lowered("(define-syntax m (syntax-rules () ((_) 1)))\n(display m)"))
			.hasMessage("test.scm:2:1: the macro m is not a variable");
		assertThatThrownBy(
				() -> lowered("(define-syntax m (syntax-rules () ((_ a) (syntax-error \"bad use\" a))))\n(m (1 x))"))
			.hasMessage("test.scm:2:1: bad use (1 x)");
		assertThatThrownBy(() -> lowered("(define-syntax m (syntax-rules () ((_) (m))))\n(display (m))"))
			.hasMessage("test.scm:2:10: the expansion of m does not terminate");
		assertThatThrownBy(() -> lowered("(define-syntax m (lambda (x) x))"))
			.hasMessage("test.scm:1:1: only syntax-rules transformers are supported");
		assertThatThrownBy(() -> lowered("(define-syntax m (syntax-rules () ((_ a ...) (list a))))\n(m 1)"))
			.hasMessage("test.scm:2:1: the pattern variable a is used without an ellipsis");
		assertThatThrownBy(() -> lowered("(define-syntax m (syntax-rules () ((_ a) (list a ...))))\n(m 1)")).hasMessage(
				"test.scm:2:1: an ellipsis in a syntax-rules template follows no pattern variable that matched under one");
		assertThatThrownBy(() -> lowered("(define (f) (display (define-syntax m (syntax-rules ()))))"))
			.hasMessage("test.scm:1:22: a syntax definition is only allowed at the top level or at the head of a body");
		// An error inside an expansion names where the use stands.
		assertThatThrownBy(() -> lowered("(define-syntax m (syntax-rules () ((_) (if))))\n\n(m)"))
			.hasMessage("test.scm:3:1: malformed if");
	}

	@Test
	void strictR7rsRefusesASyntaxDefinitionOverAnImport() {
		assertThatThrownBy(() -> strict("(import (scheme base)) (define-syntax if (syntax-rules () ((_) 1)))"))
			.hasMessage("test.scm:1:24: cannot redefine if: it is imported (R7RS 5.6.1)");
		assertThat(strict("(import (scheme base)) (define-syntax one (syntax-rules () ((_) 1))) (one)")).isEqualTo("1");
	}

}
