package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lowering of a variable's host type onto the {@code java:} sites it types -- a
 * {@code (declare (type (java:object "C") v))}, a {@code let} initializer's static type,
 * a {@code (declaim (type (java:object "C") v))}. What must hold: a reference is typed
 * only where the binding that typed it is the one it refers to (never across a
 * rebinding), a binding anything may assign is never inferred, and a form with nothing to
 * lower comes back as the same object.
 */
class JavaDeclarationsTest {

	private static final String THE = "(THE (JAVA:OBJECT \"java.util.Collection\") C)";

	private static final String SB = "(THE (JAVA:OBJECT \"java.lang.StringBuilder\" :EXACT) SB)";

	// Every form of the source through one pass, in order, printed one per line.
	private static String lower(String source) {
		JavaDeclarations pass = new JavaDeclarations(ReflectiveJavaClasses.instance());
		List<String> printed = new ArrayList<>();
		for (LispVal form : LispReader.readAllFromString(source)) {
			printed.add(pass.lower(form, null).print());
		}
		return String.join("\n", printed);
	}

	@Test
	void aDeclaredParameterTypesItsSites() {
		assertThat(lower("""
				(defun drop (c)
				  (declare (type (java:object "java.util.Collection") c))
				  (java:call c "remove" 1))
				""")).contains("(JAVA:CALL " + THE + " \"remove\" 1)");
		assertThat(lower("""
				(lambda (c) "doc"
				  (declare (type (java:object "java.util.Collection") c))
				  (java:call c "size"))
				""")).contains("(JAVA:CALL " + THE + " \"size\")");
	}

	@Test
	void aLetBindingTypesItsBodyAndArguments() {
		assertThat(lower("""
				(let ((c (make)) (n 1))
				  (declare (type (java:object "java.util.Collection") c))
				  (java:call x "add" c)
				  (java:field c "f"))
				""")).contains("(JAVA:CALL X \"add\" " + THE + ")").contains("(JAVA:FIELD " + THE + " \"f\")");
	}

	@Test
	void aRebindingEndsTheDeclaration() {
		String inner = lower("""
				(let ((c (make)))
				  (declare (type (java:object "java.util.Collection") c))
				  (list (java:call c "size")
				        (let ((c (other))) (java:call c "size"))
				        (dolist (c items) (java:call c "size"))
				        (mapcar (lambda (c) (java:call c "size")) items)))
				""");
		assertThat(inner).contains("(LIST (JAVA:CALL " + THE + " \"size\")")
			.contains("(LET ((C (OTHER))) (JAVA:CALL C \"size\"))")
			.contains("(DOLIST (C ITEMS) (JAVA:CALL C \"size\"))")
			.contains("(LAMBDA (C) (JAVA:CALL C \"size\"))");
	}

	@Test
	void aClosureSeesTheDeclarationItCloses() {
		assertThat(lower("""
				(defun f (c)
				  (declare (type (java:object "java.util.Collection") c))
				  (flet ((g (x) (java:call c "contains" x))) (g 1)))
				""")).contains("(JAVA:CALL " + THE + " \"contains\" X)");
	}

	@Test
	void aFreeDeclarationTypesTheBodyOnly() {
		assertThat(lower("""
				(let ((c (make)))
				  (list (java:call c "size")
				        (locally (declare (type (java:object "java.util.Collection") c))
				          (java:call c "size"))))
				""")).contains("(LIST (JAVA:CALL C \"size\") (LOCALLY").contains("(JAVA:CALL " + THE + " \"size\")");
	}

	@Test
	void aMacroletDropsTheDeclaration() {
		assertThat(lower("""
				(let ((c (make)))
				  (declare (type (java:object "java.util.Collection") c))
				  (macrolet ((m (x) x)) (java:call c "size")))
				""")).contains("(JAVA:CALL C \"size\")");
	}

	@Test
	void quotedDataIsNeverRewritten() {
		assertThat(lower("""
				(defun f (c)
				  (declare (type (java:object "java.util.Collection") c))
				  '(java:call c "size"))
				""")).contains("'(JAVA:CALL C \"size\")").doesNotContain("THE");
	}

	@Test
	void aFormWithNothingToLowerIsReturnedAsItIs() {
		JavaDeclarations pass = new JavaDeclarations(ReflectiveJavaClasses.instance());
		LispVal plain = LispReader.readAllFromString("(defun f (c) (java:call c \"size\"))").get(0);
		assertThat(pass.lower(plain, null)).isSameAs(plain);
		LispVal unused = LispReader
			.readAllFromString("(defun f (c) (declare (type (java:object \"java.util.Collection\") c)) (size c))")
			.get(0);
		assertThat(pass.lower(unused, null)).isSameAs(unused);
		LispVal noJava = LispReader.readAllFromString("(let ((x 1)) (print x))").get(0);
		assertThat(pass.lower(noJava, null)).isSameAs(noJava);
	}

	// --- let-initializer inference ---

	@Test
	void aLetBindingTakesItsInitializersType() {
		assertThat(lower("""
				(let ((sb (java:new "java.lang.StringBuilder")))
				  (java:call sb "length"))
				""")).contains("(JAVA:CALL " + SB + " \"length\")");
		// A literal's kinds, spelled as the declared type that answers them.
		assertThat(
				lower("""
						(let ((n 3) (s "abc") (x 2.5))
						  (list (java:static "java.lang.Math" "abs" n) (java:call y "concat" s) (java:static "java.lang.Math" "abs" x)))
						"""))
			.contains("(THE (JAVA:OBJECT \"int\") N)")
			.contains("(THE (JAVA:OBJECT \"java.lang.String\") S)")
			.contains("(THE (JAVA:OBJECT \"double\") X)");
		// A resolved call's declared type; an upper bound.
		assertThat(lower("""
				(let* ((sb (java:new "java.lang.StringBuilder"))
				       (r (java:call sb "reverse"))
				       (n (java:call r "length"))
				       (c (the (java:object "java.util.Collection") (make))))
				  (list (java:static "java.lang.Math" "abs" n) (java:call c "size")))
				""")).contains("(R (JAVA:CALL " + SB + " \"reverse\"))")
			.contains("(N (JAVA:CALL (THE (JAVA:OBJECT \"java.lang.StringBuilder\") R) \"length\"))")
			.contains("(THE (JAVA:OBJECT \"int\") N)")
			.contains("(JAVA:CALL (THE (JAVA:OBJECT \"java.util.Collection\") C) \"size\")");
	}

	@Test
	void aTypedVariableTypesTheBindingItInitializes() {
		assertThat(lower("""
				(defun f (c)
				  (declare (type (java:object "java.util.Collection") c))
				  (let ((d c)) (java:call d "size")))
				""")).contains("(JAVA:CALL (THE (JAVA:OBJECT \"java.util.Collection\") D) \"size\")");
	}

	@Test
	void aParallelLetDoesNotSeeItsOwnBindings() {
		assertThat(lower("""
				(let ((sb (java:new "java.lang.StringBuilder")) (n (java:call sb "length")))
				  (java:static "java.lang.Math" "abs" n))
				""")).contains("(N (JAVA:CALL SB \"length\"))").doesNotContain("(THE (JAVA:OBJECT \"int\") N)");
	}

	@Test
	void anAssignedBindingIsNotInferred() {
		for (String assignment : List.of("(setq sb (other))", "(setf sb (other))", "(psetq sb (other) y 1)",
				"(multiple-value-setq (sb y) (other))", "(incf sb)", "(push 1 sb)", "(psetf sb (other) y 1)",
				"(shiftf sb (other))", "(rotatef sb y)", "(mapc (lambda (x) (setq sb x)) items)",
				"(flet ((g () (setq sb nil))) (g))", "(when y (setq sb nil))", "(dolist (x items) (setq sb x))",
				"(macrolet ((m () '(setq sb nil))) (m))", "(defvar sb nil)")) {
			assertThat(lower("""
					(let ((sb (java:new "java.lang.StringBuilder")) (y 1))
					  %s
					  (java:call sb "length"))
					""".formatted(assignment))).as(assignment).contains("(JAVA:CALL SB \"length\")");
		}
		// A let* initializer after the binding is in its scope.
		assertThat(lower("""
				(let* ((sb (java:new "java.lang.StringBuilder")) (y (setq sb nil)))
				  (java:call sb "length"))
				""")).contains("(JAVA:CALL SB \"length\")");
	}

	@Test
	void anAssignmentAMacroExpandsToIsSeen() {
		// The compile path hands the pass user macros expanded; the interpreter hands it
		// a hook that expands one step.
		JavaDeclarations pass = new JavaDeclarations(ReflectiveJavaClasses.instance());
		LispVal form = LispReader.readAllFromString("""
				(let ((sb (java:new "java.lang.StringBuilder")))
				  (clear)
				  (java:call sb "length"))
				""").get(0);
		LispVal expansion = LispReader.readAllFromString("(setq sb nil)").get(0);
		assertThat(pass.lower(form, call -> call.car().print().equals("CLEAR") ? expansion : null).print())
			.contains("(JAVA:CALL SB \"length\")");
		assertThat(pass.lower(form, null).print()).contains("(JAVA:CALL " + SB + " \"length\")");
	}

	@Test
	void formsThatOnlyReadTheBindingKeepItsType() {
		for (String use : List.of("(print sb)", "(setf (gethash 'k table) sb)", "(push sb items)",
				"(when y (print sb))", "(cond (y (print sb)) (t nil))", "(dolist (x items) (print (list x sb)))",
				"(dotimes (i 3) (print sb))", "(loop for x in items do (print sb))", "(let ((z sb)) (print z))",
				"(setq y (list sb))", "(format nil \"~a\" sb)", "(with-output-to-string (out) (print sb out))",
				"(mapc (lambda (x) (print (list x sb))) items)", "(handler-case (print sb) (error (e) e))",
				"(unwind-protect (print sb) (print y))")) {
			assertThat(lower("""
					(let ((sb (java:new "java.lang.StringBuilder")) (y 1))
					  %s
					  (java:call sb "length"))
					""".formatted(use))).as(use).contains("(JAVA:CALL " + SB + " \"length\")");
		}
	}

	@Test
	void aSpecialBindingIsNotInferred() {
		assertThat(lower("""
				(defvar *sb* nil)
				(let ((*sb* (java:new "java.lang.StringBuilder"))) (java:call *sb* "length"))
				""")).contains("(JAVA:CALL *SB* \"length\")");
		assertThat(lower("""
				(progn
				  (defparameter *sb* nil)
				  (let ((*sb* (java:new "java.lang.StringBuilder"))) (java:call *sb* "length")))
				""")).contains("(JAVA:CALL *SB* \"length\")");
		assertThat(lower("""
				(let ((sb (java:new "java.lang.StringBuilder")))
				  (declare (special sb))
				  (java:call sb "length"))
				""")).contains("(JAVA:CALL SB \"length\")");
	}

	@Test
	void aDeclarationWinsOverTheInitializer() {
		assertThat(lower("""
				(let ((sb (java:new "java.lang.StringBuilder")))
				  (declare (type (java:object "java.lang.CharSequence") sb))
				  (java:call sb "length"))
				""")).contains("(JAVA:CALL (THE (JAVA:OBJECT \"java.lang.CharSequence\") SB) \"length\")");
	}

	// --- proclamations ---

	@Test
	void aProclaimedTypeTypesTheFormsAfterIt() {
		String lowered = lower("""
				(defun before () (java:call *sb* "length"))
				(declaim (type (java:object "java.lang.StringBuilder") *sb*))
				(defvar *sb* (java:new "java.lang.StringBuilder"))
				(defun after () (java:call *sb* "length"))
				(defun rebound (*sb*) (java:call *sb* "length"))
				(declaim (type t *sb*))
				(defun untyped () (java:call *sb* "length"))
				""");
		String the = "(THE (JAVA:OBJECT \"java.lang.StringBuilder\") *SB*)";
		assertThat(lowered.lines().toList()).containsExactly("(DEFUN BEFORE NIL (JAVA:CALL *SB* \"length\"))",
				"(DECLAIM (TYPE (JAVA:OBJECT \"java.lang.StringBuilder\") *SB*))",
				"(DEFVAR *SB* (JAVA:NEW \"java.lang.StringBuilder\"))",
				"(DEFUN AFTER NIL (JAVA:CALL " + the + " \"length\"))",
				"(DEFUN REBOUND (*SB*) (JAVA:CALL *SB* \"length\"))", "(DECLAIM (TYPE T *SB*))",
				"(DEFUN UNTYPED NIL (JAVA:CALL *SB* \"length\"))");
		assertThat(lower("""
				(proclaim '(type (java:object "java.util.Collection") *c*))
				(java:call *c* "size")
				""")).contains("(JAVA:CALL (THE (JAVA:OBJECT \"java.util.Collection\") *C*) \"size\")");
	}

	// A top-level progn counts element by element, as the compile path's flattened
	// program has it.
	@Test
	void aProclamationInsideAPrognCountsFromTheNextElement() {
		assertThat(lower("""
				(progn
				  (java:call *c* "size")
				  (declaim (type (java:object "java.util.Collection") *c*))
				  (java:call *c* "size"))
				""")).contains("(PROGN (JAVA:CALL *C* \"size\") (DECLAIM")
			.contains("(JAVA:CALL (THE (JAVA:OBJECT \"java.util.Collection\") *C*) \"size\"))");
	}

}
