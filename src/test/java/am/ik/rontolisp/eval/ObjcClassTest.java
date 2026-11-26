package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The new {@code objc} base's class definition -- LispWorks 8.1's
 * {@code define-objc-class}, {@code define-objc-method}, {@code standard-objc-object} and
 * the rest ({@code objc-class.lisp}, {@code objc-macros.lisp}) -- on the interpreter. The
 * corpus ({@code objc-class-corpus.lisp}) is what the JVM class and the {@code --native}
 * executable must print byte for byte; here it is pinned against the committed expected
 * output.
 */
class ObjcClassTest {

	private static String resource(String name) {
		try (InputStream in = ObjcClassTest.class.getResourceAsStream("/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String eval(String source) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal form : LispReader.readAllFromString(source)) {
			result = evaluator.eval(form);
		}
		return result.print();
	}

	@Test
	void theDefiningMacrosNeedNoRuntime() {
		// The manual: the defining macros may run before ensure-objc-initialized, so a
		// class is recorded and made only when the runtime is up -- on any machine.
		assertThat(eval("""
				(objc:define-objc-class my-object () ((slot1 :initform 1)) (:objc-class-name "MyObject"))
				(objc:define-objc-method ("areaOfWidth:height:" (:unsigned :int))
				    ((self my-object) (width (:unsigned :int)) (height (:unsigned :int)))
				  (* width height))
				(objc:define-objc-struct (pair (:foreign-name "_Pair")) (:first :float) (:second :float))
				(list (typep (find-class 'my-object) 'standard-class)
				      (length objc::*method-defs*)
				      (objc::%type-encoding '(:struct pair)))
				""")).isEqualTo("(T 1 \"{_Pair=ff}\")");
		assertThat(eval("""
				(objc:define-objc-class my-object () () (:objc-class-name "MyObject"))
				(handler-case
				    (objc:define-objc-method ("areaOfWidth:height:" :int) ((self my-object) (width :int))
				      width)
				  (error (e) (princ-to-string e)))
				""")).isEqualTo(
				"\"objc:define-objc-method: \\\"areaOfWidth:height:\\\" takes 2 argument(s) but 1 are declared\"");
		assertThat(eval("""
				(handler-case (objc:define-objc-method ("size" :int) ((self no-such-class)) 1)
				  (error (e) (princ-to-string e)))
				"""))
			.isEqualTo("\"objc:define-objc-method: NO-SUCH-CLASS is not a class defined with objc:define-objc-class\"");
	}

	@Test
	void aRedefinedStructureIsLaidOutAgain() {
		// A layout is worked out once per type (objc::*elements*); defining a type again
		// must not leave the old one behind, for the type or for one that nests it.
		assertThat(eval("""
				(objc:define-objc-struct (inner (:foreign-name "_Inner")) (:a :char))
				(objc:define-objc-struct (outer (:foreign-name "_Outer")) (:i inner) (:b :char))
				(let ((before (list (fli:size-of 'inner) (fli:size-of 'outer))))
				  (objc:define-objc-struct (inner (:foreign-name "_Inner")) (:a :double))
				  (list before (fli:size-of 'inner) (fli:size-of 'outer)))
				""")).isEqualTo("((1 2) 8 16)");
	}

	@Test
	void theLibraryNeverDefinesAPrimitive() {
		// A defun in the library under a primitive's name replaces the backend's own on
		// the interpreter and the JVM (a class-half helper once shadowed %method-types).
		for (LispVal form : ObjcLibrary.allForms()) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && "DEFUN".equals(op.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				assertThat(LispNames.OBJC_PRIMITIVES).as(name.name()).doesNotContain(name.name());
			}
		}
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theCorpusPrintsWhatIsCommitted() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
		String out;
		try {
			out = ObjcBaseTest.interpret(resource("objc-class-corpus.lisp"));
		}
		finally {
			System.setErr(oldErr);
		}
		assertThat(out).isEqualTo(resource("objc-class-corpus.expected"));
		// A method that signals is contained: printed, answered as zero.
		assertThat(err.toString(StandardCharsets.UTF_8)).contains("objc: error in a callback: no 9");
	}

}
