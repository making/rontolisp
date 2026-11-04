package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which top-level {@code defun}s a program writes by a literal name, and the definition
 * and forwarder each becomes.
 */
class RedefinedDefunsTest {

	private static String names(String source) {
		return String.join(" ", RedefinedDefuns.names(LispReader.readAllFromString(source)));
	}

	private static String rewritten(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		List<LispVal> out = RedefinedDefuns.rewrite(program, RedefinedDefuns.names(program));
		return String.join(" ", out.stream().map(LispVal::print).toList());
	}

	@Test
	void aLiteralWriteNamesTheDefinition() {
		assertThat(names("(defun f ()) (setf (fdefinition 'f) #'car)")).isEqualTo("F");
		assertThat(names("(defun f ()) (print (setf (symbol-function 'f) #'car))")).isEqualTo("F");
		assertThat(names("(defun f ()) (compile 'f '(lambda ()))")).isEqualTo("F");
		assertThat(names("(defun f ()) (fmakunbound 'f)")).isEqualTo("F");
		assertThat(names("(psetf (fdefinition 'f) #'car) (rotatef (symbol-function 'g) (fdefinition 'h))"))
			.isEqualTo("F G H");
		// A (setf name) list is its writer's name.
		assertThat(names("(compile '(setf f) #'car)")).isEqualTo("%setf-F");
		// Without a definition compile installs nothing, nor does a computed name say
		// which, and quoted data writes nothing.
		assertThat(names("(defun f ()) (compile 'f) (compile 'f nil) (let ((n 'f)) (setf (fdefinition n) #'car))"
				+ " (print '(setf (fdefinition 'f) 1))"))
			.isEmpty();
		// The program's own compile is no write; a generic function's name is only
		// retired where its dispatcher can be defined again.
		assertThat(names("(defun compile (a b) a) (defun f ()) (compile 'f 1)")).isEmpty();
		assertThat(names("(defmethod g (x) x) (fmakunbound 'g)")).isEmpty();
		assertThat(names("(defmethod g (x) x) (setf (fdefinition 'g) #'car)")).isEqualTo("G");
	}

	@Test
	void eachDefinitionInstallsItsOwnFunctionWhereItStood() {
		assertThat(rewritten("(defun f () 1) (f) (defun f () 2) (fmakunbound 'f)"))
			.isEqualTo("(DEFUN |%top-defun$F| NIL 1) (%SET-SYMBOL-FUNCTION 'F #'|%top-defun$F|) (F)"
					+ " (DEFUN |%top-defun$2$$F| NIL 2) (%SET-SYMBOL-FUNCTION 'F #'|%top-defun$2$$F|)"
					+ " (FMAKUNBOUND 'F) (DEFUN F (&REST %SFW-ARGS) (APPLY (%FENV-FUNCTION 'F) %SFW-ARGS))");
		assertThat(NestedDefunRedefinition.originalName("%top-defun$2$$F")).isEqualTo("F");
		assertThat(NestedDefunRedefinition.originalName("%top-defun$F")).isEqualTo("F");
	}

	@Test
	void aNameWithNoDefunStaysAsItIs() {
		List<LispVal> program = LispReader.readAllFromString("(print 1) (setf (fdefinition 'f) #'car)");
		assertThat(RedefinedDefuns.rewrite(program, RedefinedDefuns.names(program))).isSameAs(program);
		// A forwarder is that home already.
		List<LispVal> forwarded = new ArrayList<>(program);
		forwarded.addAll(LispMacroExpander.symbolFunctionForwarderDefuns(List.of("F")));
		assertThat(RedefinedDefuns.rewrite(forwarded, RedefinedDefuns.names(forwarded))).isSameAs(forwarded);
	}

	@Test
	void anExportedDefinitionIsRefused() {
		List<LispVal> program = LispReader
			.readAllFromString("(defun f (x) x) (rontolisp:wasm-export 'f) (setf (fdefinition 'f) #'car)");
		assertThatThrownBy(() -> RedefinedDefuns.rewrite(program, RedefinedDefuns.names(program)))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("exported")
			.hasMessageContaining("replaced by name");
	}

}
