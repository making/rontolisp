package am.ik.rontolisp.compiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code --warnings-as-errors} counts: a warning that reaches the output and is
 * about the program's own source -- never one of a discarded attempt, a spliced
 * library's, a dist-installed dependency's, or a note.
 */
class CompileWarningsTest {

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	private PrintStream savedErr = System.err;

	@BeforeEach
	void open() {
		this.savedErr = System.err;
		System.setErr(new PrintStream(this.err, true, StandardCharsets.UTF_8));
		SourceProvenance.startRecording();
		CompileWarnings.startCounting(file -> file.startsWith("/deps/"));
	}

	@AfterEach
	void close() {
		CompileWarnings.discardAttempt();
		CompileWarnings.stopCounting();
		SourceProvenance.stopRecording();
		System.setErr(this.savedErr);
	}

	private static LispVal programForm(String source, @Nullable String file) {
		return SourceProvenance
			.readingProgramSource(() -> LispReader.readAllFromString(source, Features.INTERPRETER, file))
			.get(0);
	}

	private String printed() {
		return this.err.toString(StandardCharsets.UTF_8);
	}

	@Test
	void aWarningAboutTheProgramsSourceCountsAndNamesItsPosition() {
		CompileWarnings.warn(programForm("\n  (f 1)", "prog.lisp"), "the function F is undefined");

		assertThat(CompileWarnings.counted()).isEqualTo(1);
		assertThat(printed()).isEqualTo("prog.lisp:2:3: warning: the function F is undefined" + System.lineSeparator());
	}

	@Test
	void anInlineProgramCountsThoughItHasNoFileToName() {
		// -e: the seam read it, so it is the program's, with no position to print.
		CompileWarnings.warn(programForm("(f 1)", null), "the function F is undefined");

		assertThat(CompileWarnings.counted()).isEqualTo(1);
		assertThat(printed()).startsWith("warning: the function F is undefined");
	}

	@Test
	void aSplicedLibrarysWarningIsPrintedButNotCounted() {
		// A splice reads library source straight through the reader, past the seam.
		LispVal library = LispReader.readAllFromString("(f 1)", Features.INTERPRETER).get(0);
		CompileWarnings.warn(library, "the function F is undefined");
		CompileWarnings.warn(null, "about no form at all");

		assertThat(CompileWarnings.counted()).isZero();
		assertThat(printed()).contains("warning: the function F is undefined").contains("warning: about no form");
	}

	@Test
	void aDependencysFileIsPrintedButNotCounted() {
		CompileWarnings.warn(programForm("(f 1)", "/deps/software/lib/a.lisp"), "the function F is undefined");

		assertThat(CompileWarnings.counted()).isZero();
		assertThat(printed()).contains("/deps/software/lib/a.lisp:1:1: warning:");
	}

	@Test
	void aNoteIsNeverCounted() {
		CompileWarnings.note("--host-fetch: this module imports env.fetch");

		assertThat(CompileWarnings.counted()).isZero();
		assertThat(printed()).isEqualTo("--host-fetch: this module imports env.fetch" + System.lineSeparator());
	}

	@Test
	void aDiscardedAttemptsWarningsAreNeitherPrintedNorCounted() {
		LispVal call = programForm("(f 1)", "prog.lisp");
		CompileWarnings.startAttempt();
		CompileWarnings.warn(call, "the function F is undefined");
		CompileWarnings.discardAttempt();
		assertThat(CompileWarnings.counted()).isZero();
		assertThat(printed()).isEmpty();

		// The attempt that ships prints and counts, once per line however often its own
		// passes reach the site.
		CompileWarnings.startAttempt();
		CompileWarnings.warn(call, "the function F is undefined");
		CompileWarnings.warn(call, "the function F is undefined");
		assertThat(CompileWarnings.counted()).as("nothing counts before the flush").isZero();
		CompileWarnings.flushAttempt();
		assertThat(CompileWarnings.counted()).isEqualTo(1);
		assertThat(printed().lines().toList()).containsExactly("prog.lisp:1:1: warning: the function F is undefined");
	}

	@Test
	void aFormAMacroBuiltIsPlacedAndCountedAtTheLocatedFormAroundIt() {
		LispCons macroCall = (LispCons) programForm("\n(m 1)", "prog.lisp");
		LispCons built = new LispCons(new LispSymbol("F"), LispNil.INSTANCE);

		LispCons previous = SourceProvenance.enterForm(macroCall);
		try {
			CompileWarnings.warn(built, "the function F is undefined");
		}
		finally {
			SourceProvenance.leaveForm(previous);
		}
		// Outside the located form, the same form is placed nowhere and counts nothing.
		CompileWarnings.warn(built, "the function G is undefined");

		assertThat(CompileWarnings.counted()).isEqualTo(1);
		assertThat(printed().lines().toList()).containsExactly("prog.lisp:2:1: warning: the function F is undefined",
				"warning: the function G is undefined");
	}

}
