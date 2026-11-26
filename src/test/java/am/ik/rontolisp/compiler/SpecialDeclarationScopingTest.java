package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpecialDeclarationScopingTest {

	private static String scoped(String source) {
		StringBuilder printed = new StringBuilder();
		for (LispVal form : SpecialDeclarationScoping.scope(LispReader.readAllFromString(source))) {
			printed.append(printed.isEmpty() ? "" : "\n").append(form.print());
		}
		return printed.toString();
	}

	@Test
	void aBindingNoDeclarationCoversIsRenamedApartWithItsReferences() {
		// cl-ppcre's create-matcher-aux: the function's free declaration covers the
		// init's read of the scanner's special end-string, while the let* binding of the
		// name is lexical and the closure captures IT.
		assertThat(scoped("""
				(defun m (x)
				  (declare (special e))
				  (let* ((e (list e x)))
				    (lambda () e)))
				(defun s () (let ((e 1)) (declare (special e)) (m 2)))
				""")).isEqualTo("""
				(DEFUN M (X) (DECLARE (SPECIAL E)) (LET* ((E%0 (LIST E X))) (LAMBDA NIL E%0)))
				(DEFUN S NIL (LET ((E 1)) (DECLARE (SPECIAL E)) (M 2)))""");
	}

	@Test
	void aFreeDeclarationAndASymbolMacroKeepTheNameInsideARenamedScope() {
		assertThat(scoped("""
				(defun eg (y)
				  (declare (special y))
				  (let ((y t))
				    (list y (locally (declare (special y)) y) (symbol-macrolet ((y 7)) y) 'y)))
				"""))
			.isEqualTo("(DEFUN EG (Y) (DECLARE (SPECIAL Y)) (LET ((Y%0 T)) (LIST Y%0 (LOCALLY (DECLARE (SPECIAL Y)) Y) "
					+ "(SYMBOL-MACROLET ((Y 7)) Y) 'Y)))");
	}

	@Test
	void aRenamedKeyParameterKeepsItsKeyword() {
		assertThat(scoped("""
				(defun r () (declare (special k o)) (list k o))
				(defun f (&optional (o 1) &key (k o) ((:other k2) 2)) (list o k k2))
				""")).endsWith("(DEFUN F (&OPTIONAL (O%0 1) &KEY ((:K K%1) O%0) ((:OTHER K2) 2)) (LIST O%0 K%1 K2))");
	}

	@Test
	void unevaluatedPositionsKeepTheName() {
		// A case key, a typecase type, a tagbody tag, a block name, a function name and
		// quoted data are no references to the variable.
		assertThat(scoped("""
				(defun r () (declare (special v)) v)
				(defun f (v)
				  (case v ((v) (block v (return-from v #'v))) (t (tagbody v (go v))))
				  (typecase v (v 'v)))
				""")).endsWith("(DEFUN F (V%0) (CASE V%0 ((V) (BLOCK V (RETURN-FROM V #'V))) "
				+ "(T (TAGBODY V (GO V)))) (TYPECASE V%0 (V 'V)))");
	}

	@Test
	void aProgramNoLocalDeclarationNamesAVariableInComesBackAsTheSameList() {
		// The same objects, not equal ones: that is what keeps every other program's
		// emitted bytes unchanged.
		for (String source : new String[] { "(defvar *x* 1) (defun f (*x*) (let ((*x* 2)) *x*))",
				"(defun f (x) (declare (fixnum x)) (let ((y x)) y))",
				"(defvar *p* 1) (defun f () (declare (special *p* *print-base*)) *p*)" }) {
			List<LispVal> program = LispReader.readAllFromString(source);
			assertThat(SpecialDeclarationScoping.scope(program)).as(source).isSameAs(program);
		}
		List<LispVal> program = LispReader.readAllFromString("""
				(defun r () (declare (special v)) v)
				(defun g (x) (let ((y x)) (lambda () y)))
				""");
		List<LispVal> scoped = SpecialDeclarationScoping.scope(program);
		assertThat(scoped.get(0)).isSameAs(program.get(0));
		assertThat(scoped.get(1)).isSameAs(program.get(1));
	}

}
