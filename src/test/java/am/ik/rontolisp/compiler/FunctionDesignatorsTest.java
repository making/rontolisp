package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compile paths' one spelling of a built-in's function value: every gate that injects
 * a reference-gated wrapper scans for {@code (function name)}, so a quoted designator and
 * a literal {@code (symbol-function 'name)} of such a built-in are rewritten to it before
 * any gate runs.
 */
class FunctionDesignatorsTest {

	@Test
	void aLiteralSymbolFunctionOfAReferenceGatedBuiltinIsItsFunctionForm() {
		assertThat(normalized("(list (symbol-function 'format) (fdefinition 'arrayp) (funcall 'arrayp 1))"))
			.isEqualTo("(LIST #'FORMAT #'ARRAYP (FUNCALL #'ARRAYP 1))");
		// A catalog wrapper is injected whatever the spelling, and a program's own
		// function is not a built-in.
		assertThat(normalized("(list (symbol-function 'car) (symbol-function 'my-fn))"))
			.isEqualTo("(LIST (SYMBOL-FUNCTION 'CAR) (SYMBOL-FUNCTION 'MY-FN))");
	}

	@Test
	void aSymbolFunctionPlaceAndALocalBindingAreLeftAlone() {
		assertThat(normalized("(setf (symbol-function 'format) (symbol-function 'arrayp))"))
			.isEqualTo("(SETF (SYMBOL-FUNCTION 'FORMAT) #'ARRAYP)");
		assertThat(normalized("(psetf (fdefinition 'arrayp) (fdefinition 'format))"))
			.isEqualTo("(PSETF (FDEFINITION 'ARRAYP) #'FORMAT)");
		assertThat(normalized("(flet ((format (x) x)) (symbol-function 'format))"))
			.isEqualTo("(FLET ((FORMAT (X) X)) (SYMBOL-FUNCTION 'FORMAT))");
	}

	@Test
	void aQuotedSetfFunctionNameIsTheWritersInternalName() {
		assertThat(normalized("(list (fdefinition '(setf box)) (fboundp '(setf box)) (fmakunbound '(setf box)))"))
			.isEqualTo("(LIST #'(SETF BOX) (FBOUNDP '|%setf-BOX|) (PROGN (FMAKUNBOUND '|%setf-BOX|) '(SETF BOX)))");
		assertThat(normalized("(setf (fdefinition '(setf box)) #'list)"))
			.isEqualTo("(SETF (FDEFINITION '|%setf-BOX|) #'LIST)");
		// A computed name, and a list that names no function, stay as written.
		assertThat(normalized("(list (fboundp (list 'setf 'box)) (fboundp '(box)))"))
			.isEqualTo("(LIST (FBOUNDP (LIST 'SETF 'BOX)) (FBOUNDP '(BOX)))");
	}

	@Test
	void aGatedBuiltinOnlyDataNamesGetsItsReferenceWhereDataCanBecomeACall() {
		// A computed call can reach a symbol held in quoted data, so the gated aref gets
		// the #'aref every gate reads; the composition cadr gets a wrapper from
		// dataCalledNames instead, and a program that cannot call data, or names no
		// gated built-in in it -- a type or slot name is no function -- comes back as
		// itself.
		String source = "(defvar *r* '((aref x) (cadr x) (car x))) (print (funcall (caar *r*) #(1) 0))";
		List<LispVal> program = FunctionDesignators.normalizeBuiltinDesignators(LispReader.readAllFromString(source));
		assertThat(program).hasSize(3);
		assertThat(program.getLast().print()).isEqualTo("#'AREF");
		assertThat(FunctionDesignators.dataCalledNames(program)).containsExactlyInAnyOrder("AREF", "CADR");
		for (String unchanged : List.of("(defvar *r* '((aref x) (cadr x))) (print (aref #(1) 0))",
				"(defvar *r* '((car x))) (print (funcall (caar *r*) (slot-value *r* 'log) (typep 1 'complex)))")) {
			List<LispVal> read = LispReader.readAllFromString(unchanged);
			assertThat(FunctionDesignators.normalizeBuiltinDesignators(read)).isSameAs(read);
		}
	}

	private static String normalized(String source) {
		List<LispVal> program = FunctionDesignators.normalizeBuiltinDesignators(LispReader.readAllFromString(source));
		return program.getFirst().print();
	}

}
