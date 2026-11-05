package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which top-level {@code defun}s a program can write while it runs, and what each
 * definition becomes: a program's literal write moves the definition to the run-time
 * function namespace, a write by a name it cannot read gives the definition a cell.
 */
class RedefinedDefunsTest {

	private static String names(String source) {
		return String.join(" ", RedefinedDefuns.names(LispReader.readAllFromString(source)));
	}

	private static boolean computed(String source) {
		return RedefinedDefuns.writesComputedNames(LispReader.readAllFromString(source));
	}

	private static String celled(String source, Map<String, String> dispatchers) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return String.join(" ", RedefinedDefuns.celledDefuns(program, RedefinedDefuns.names(program),
				RedefinedDefuns.writesComputedNames(program) || source.contains("(eval "), dispatchers));
	}

	private static List<LispVal> rewrite(List<LispVal> program, Set<String> celled, Map<String, String> dispatchers) {
		return RedefinedDefuns.rewrite(program, RedefinedDefuns.names(program), celled, dispatchers, name -> false);
	}

	private static String rewritten(String source, Set<String> celled) {
		List<LispVal> out = rewrite(LispReader.readAllFromString(source), celled, Map.of());
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
		// The program's own compile is no write, and retiring needs a definition: a
		// defun's, or a generic function's, which a later definition binds again.
		assertThat(names("(defun compile (a b) a) (defun f ()) (compile 'f 1)")).isEmpty();
		assertThat(names("(fmakunbound 'g)")).isEmpty();
		assertThat(names("(defmethod g (x) x) (fmakunbound 'g)")).isEqualTo("G");
		assertThat(names("(defgeneric g (x)) (fmakunbound 'g)")).isEqualTo("G");
		assertThat(names("(defmethod g (x) x) (setf (fdefinition 'g) #'car)")).isEqualTo("G");
	}

	@Test
	void aWriteByANameTheCompileCannotReadIsFoundInEachOperator() {
		assertThat(computed("(setf (fdefinition n) #'car)")).isTrue();
		assertThat(computed("(defun f (n) (print (setf (symbol-function n) #'car)))")).isTrue();
		assertThat(computed("(psetf (fdefinition a) #'car)")).isTrue();
		assertThat(computed("(rotatef (fdefinition a) (fdefinition 'b))")).isTrue();
		assertThat(computed("(shiftf (symbol-function a) #'car)")).isTrue();
		assertThat(computed("(fmakunbound n)")).isTrue();
		assertThat(computed("(mapc #'fmakunbound '(a))")).isTrue();
		assertThat(computed("(funcall 'fmakunbound 'a)")).isTrue();
		assertThat(computed("(setf (fdefinition 'f) #'car) (rotatef (fdefinition 'f) (symbol-function 'g))"
				+ " (fmakunbound 'f) (fmakunbound '(setf f)) (setf (fdefinition '(setf f)) #'car)"
				+ " (print '(setf (fdefinition x) 1)) (setf x 1) (setf (car x) 1)"))
			.isFalse();
	}

	@Test
	void aCellGoesToTheDefinitionsARunTimeNameCanReach() {
		// Nothing written by a run-time name, nothing with a cell.
		assertThat(celled("(defun a ()) (setf (fdefinition 'b) #'a)", Map.of())).isEmpty();
		// What a value of the program spells, and beside a symbol builder a string.
		assertThat(celled("(defun a ()) (defun b ()) (defun c ()) (defun w (n) (fmakunbound n)) (w 'a)", Map.of()))
			.isEqualTo("A");
		assertThat(celled("(defun a ()) (defun b ()) (fmakunbound (intern \"B\"))", Map.of())).isEqualTo("B");
		assertThat(celled("(defun a ()) (defun b ()) (fmakunbound \"B\")", Map.of())).isEmpty();
		// Every function where data can name one: not what only the compiler names, nor
		// a name a literal write moved to the namespace. An internal name needs spelling.
		assertThat(celled("(defun a ()) (defun %b ()) (defun %g--m1 ()) (defun %c ()) (defun d ())"
				+ " (setf (fdefinition 'd) #'a) (print '%c) (eval 1)", Map.of()))
			.isEqualTo("A %C");
		// A writer's name counts as the function it is, not as an internal one.
		assertThat(celled("(defun |%setf-A| (v)) (eval 1)", Map.of())).isEqualTo("%setf-A");
		// A generic on a lowered built-in is written through its dispatcher's cell, by
		// a literal write too.
		Map<String, String> dispatchers = Map.of("LENGTH", "%D");
		assertThat(celled("(defun %d (x)) (setf (symbol-function 'length) #'car)", dispatchers)).isEqualTo("%D");
		assertThat(celled("(defun %d (x)) (print 1)", dispatchers)).isEmpty();
		assertThat(celled("(defun %d (x)) (eval 1)", dispatchers)).isEqualTo("%D");
	}

	@Test
	void eachDefinitionTakesEffectWhereItStood() {
		// A literal write: each definition installs its own function.
		assertThat(rewritten("(defun f () 1) (f) (defun f () 2) (fmakunbound 'f)", Set.of()))
			.isEqualTo("(DEFUN |%top-defun$F| NIL 1) (%SET-SYMBOL-FUNCTION 'F #'|%top-defun$F|) (F)"
					+ " (DEFUN |%top-defun$2$$F| NIL 2) (%SET-SYMBOL-FUNCTION 'F #'|%top-defun$2$$F|)"
					+ " (FMAKUNBOUND 'F) (DEFUN F (&REST %SFW-ARGS) (APPLY (%FENV-FUNCTION 'F) %SFW-ARGS))");
		assertThat(NestedDefunRedefinition.originalName("%top-defun$2$$F")).isEqualTo("F");
		assertThat(NestedDefunRedefinition.originalName("%top-defun$F")).isEqualTo("F");
		// With a cell: the compiled definition answers again from there.
		assertThat(rewritten("(defun f () 1) (f)", Set.of("F")))
			.isEqualTo("(DEFUN F NIL 1) (%RESTORE-COMPILED-DEFINITION 'F) (F)");
		// A later definition of a generic function binds the name again, wherever a
		// write can have put it; with neither its mark goes.
		String generic = "(defun g (x) x) (%generic-defined-again \"G\")";
		assertThat(rewritten(generic + " (fmakunbound 'g)", Set.of()))
			.isEqualTo("(DEFUN |%top-defun$G| (X) X) (%SET-SYMBOL-FUNCTION 'G #'|%top-defun$G|)"
					+ " (%SET-SYMBOL-FUNCTION 'G #'|%top-defun$G|) (FMAKUNBOUND 'G)"
					+ " (DEFUN G (&REST %SFW-ARGS) (APPLY (%FENV-FUNCTION 'G) %SFW-ARGS))");
		assertThat(rewritten(generic, Set.of("G")))
			.isEqualTo("(DEFUN G (X) X) (%RESTORE-COMPILED-DEFINITION 'G) (%RESTORE-COMPILED-DEFINITION 'G)");
		assertThat(rewritten(generic, Set.of())).isEqualTo("(DEFUN G (X) X)");
		// A generic on a lowered built-in restores by the name a write spells.
		List<LispVal> shadowed = LispReader.readAllFromString("(defun %d (x) x) (%generic-defined-again \"LENGTH\")");
		assertThat(rewrite(shadowed, Set.of("%D"), Map.of("LENGTH", "%D")).stream().map(LispVal::print).toList())
			.containsExactly("(DEFUN %D (X) X)", "(%RESTORE-COMPILED-DEFINITION 'LENGTH)",
					"(%RESTORE-COMPILED-DEFINITION 'LENGTH)");
		// A name holding a generic function metaobject keeps it across a definition,
		// which
		// it cannot hold ahead of the table of them.
		assertThat(
				RedefinedDefuns
					.rewrite(
							LispReader
								.readAllFromString("(defun g (x) x) (defvar %gf-metaobjects% nil) (defun g (x) x)"),
							Set.of(), Set.of("G"), Map.of(), name -> true)
					.stream()
					.map(LispVal::print)
					.toList())
			.containsExactly("(DEFUN G (X) X)", "(%RESTORE-COMPILED-DEFINITION 'G)", "(DEFVAR %GF-METAOBJECTS% NIL)",
					"(DEFUN G (X) X)", "(IF (%GF-INSTALLED-P 'G) NIL (%RESTORE-COMPILED-DEFINITION 'G))");
	}

	@Test
	void aNameWithNoDefunStaysAsItIs() {
		List<LispVal> program = LispReader.readAllFromString("(print 1) (setf (fdefinition 'f) #'car)");
		assertThat(rewrite(program, Set.of(), Map.of())).isSameAs(program);
		// A forwarder is that home already.
		List<LispVal> forwarded = new ArrayList<>(program);
		forwarded.addAll(LispMacroExpander.symbolFunctionForwarderDefuns(List.of("F")));
		assertThat(rewrite(forwarded, Set.of(), Map.of())).isSameAs(forwarded);
	}

	@Test
	void anExportedDefinitionIsRefused() {
		List<LispVal> program = LispReader
			.readAllFromString("(defun f (x) x) (rontolisp:wasm-export 'f) (setf (fdefinition 'f) #'car)");
		assertThatThrownBy(() -> rewrite(program, Set.of(), Map.of())).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("exported")
			.hasMessageContaining("replaced by name");
	}

}
