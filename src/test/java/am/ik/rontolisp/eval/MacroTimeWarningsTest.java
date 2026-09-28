package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code (warn ...)} Lisp code signals while a macro expands on the compile path is a
 * compile-time warning placed at the macro call, and counts toward
 * {@code --warnings-as-errors} by the rule every compiler warning follows -- unless it is
 * a {@code style-warning}, a handler muffled it, or the call is not in the program's own
 * source.
 */
class MacroTimeWarningsTest {

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	private PrintStream savedErr = System.err;

	@BeforeEach
	void open() {
		this.savedErr = System.err;
		System.setErr(new PrintStream(this.err, true, StandardCharsets.UTF_8));
		SourceProvenance.startRecording();
		CompileWarnings.startCounting(file -> false);
	}

	@AfterEach
	void close() {
		CompileWarnings.stopCounting();
		SourceProvenance.stopRecording();
		System.setErr(this.savedErr);
	}

	private static List<LispVal> program(String source) {
		return SourceProvenance
			.readingProgramSource(() -> LispReader.readAllFromString(source, Features.JVM, "prog.lisp"));
	}

	private static List<LispVal> library(String source) {
		return LispReader.readAllFromString(source, Features.JVM, "lib.lisp");
	}

	private List<String> printed() {
		return this.err.toString(StandardCharsets.UTF_8).lines().toList();
	}

	@Test
	void aUserMacrosWarningAtAProgramCallCountsAtTheCall() {
		UserMacroExpander.expand(program("""
				(defmacro m (x) (warn "m got ~a" x) x)
				(print (m 1))
				"""));

		assertThat(CompileWarnings.counted()).isEqualTo(1);
		assertThat(printed()).containsExactly("prog.lisp:2:8: warning: m got 1");
	}

	@Test
	void aWarningFromACallAMacroBuiltCountsAtTheLocatedCallAroundIt() {
		// inner's call has no position of its own: outer built it.
		UserMacroExpander.expand(program("""
				(defmacro inner (x) (warn 'simple-warning :format-control "inner") x)
				(defmacro outer (x) (list 'inner x))
				(print
				  (outer 1))
				"""));

		assertThat(CompileWarnings.counted()).isEqualTo(1);
		assertThat(printed()).containsExactly("prog.lisp:4:3: warning: inner");
	}

	@Test
	void aStyleWarningIsPrintedAtTheCallButNeverCounts() {
		UserMacroExpander.expand(program("""
				(define-condition my-note (style-warning) ())
				(defmacro a (x) (warn 'style-warning) x)
				(defmacro b (x) (warn 'my-note) x)
				(defmacro c (x) (uiop:style-warn "c got ~a" x) x)
				(defmacro d (x) (warn (make-condition 'my-note)) x)
				(print (list (a 1) (b 2) (c 3) (d 4)))
				"""));

		assertThat(CompileWarnings.counted()).isZero();
		assertThat(printed()).hasSize(4)
			.allSatisfy(line -> assertThat(line).startsWith("prog.lisp:6:").contains(": style-warning: "));
		assertThat(printed().get(2)).endsWith("style-warning: c got 3");
	}

	@Test
	void aMuffledWarningIsNeitherPrintedNorCounted() {
		UserMacroExpander.expand(program("""
				(defmacro m (x)
				  (handler-bind ((warning #'muffle-warning))
				    (warn "m got ~a" x))
				  x)
				(print (m 1))
				"""));

		assertThat(CompileWarnings.counted()).isZero();
		assertThat(printed()).isEmpty();
	}

	@Test
	void aLibraryMacrosWarningCountsOnlyAtACallInTheProgram() {
		List<LispVal> forms = new ArrayList<>(library("""
				(defmacro lib-m (x) (warn "lib-m got ~a" x) x)
				(defun lib-f () (lib-m 1))
				"""));
		forms.addAll(program("(print (lib-m 2))"));
		UserMacroExpander.expand(forms);

		// The call inside the library is printed where it is, and not counted.
		assertThat(CompileWarnings.counted()).isEqualTo(1);
		assertThat(printed()).containsExactly("lib.lisp:2:17: warning: lib-m got 1",
				"prog.lisp:1:8: warning: lib-m got 2");
	}

}
