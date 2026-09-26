package am.ik.rontolisp.compiler;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lowering of {@code (declare (type (java:object "C") v))} onto the {@code java:}
 * sites it types. What must hold: a reference is typed only where the declaration's
 * binding is the one it refers to (never across a rebinding), and a form with nothing to
 * lower comes back as the same object.
 */
class JavaDeclarationsTest {

	private static final String THE = "(THE (JAVA:OBJECT \"java.util.Collection\") C)";

	private static String lower(String source) {
		return JavaDeclarations.lower(LispReader.readAllFromString(source).get(0), null).print();
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
		LispVal plain = LispReader.readAllFromString("(defun f (c) (java:call c \"size\"))").get(0);
		assertThat(JavaDeclarations.lower(plain, null)).isSameAs(plain);
		LispVal unused = LispReader
			.readAllFromString("(defun f (c) (declare (type (java:object \"java.util.Collection\") c)) (size c))")
			.get(0);
		assertThat(JavaDeclarations.lower(unused, null)).isSameAs(unused);
	}

}
