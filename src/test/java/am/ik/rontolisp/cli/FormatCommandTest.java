package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code rontolisp format} subcommand. The formatting itself is pinned by
 * {@code LispFormatterTest}; what matters here is the file handling and the exit codes,
 * since {@code --check} is meant to be a CI gate and an unreadable file must not stop the
 * rest of a tree from being formatted.
 */
class FormatCommandTest {

	private static final String UNFORMATTED = "(defun f (x)\n(print x)\n(terpri))\n";

	private static final String FORMATTED = "(defun f (x)\n  (print x)\n  (terpri))\n";

	@Test
	void formatsAFileInPlaceAndReportsIt(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.lisp"), UNFORMATTED);
		Output output = new Output();
		assertThat(run(output, file.toString())).isZero();
		assertThat(Files.readString(file)).isEqualTo(FORMATTED);
		assertThat(output.text()).contains("formatted " + file);
	}

	@Test
	void leavesAnAlreadyFormattedFileUntouched(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.lisp"), FORMATTED);
		long modified = Files.getLastModifiedTime(file).toMillis();
		Output output = new Output();
		assertThat(run(output, file.toString())).isZero();
		assertThat(output.text()).isEmpty();
		assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(modified);
	}

	@Test
	void walksADirectoryForLispAndAsdFilesOnly(@TempDir Path dir) throws IOException {
		Files.createDirectory(dir.resolve("sub"));
		Path lisp = Files.writeString(dir.resolve("a.lisp"), UNFORMATTED);
		Path asd = Files.writeString(dir.resolve("sub/x.asd"), "(defsystem \"x\"\n:depends-on (\"y\"))\n");
		Path other = Files.writeString(dir.resolve("notes.txt"), "(defun f (x)\n(print x))\n");
		assertThat(run(new Output(), dir.toString())).isZero();
		assertThat(Files.readString(lisp)).isEqualTo(FORMATTED);
		assertThat(Files.readString(asd)).isEqualTo("(defsystem \"x\" :depends-on (\"y\"))\n");
		assertThat(Files.readString(other)).isEqualTo("(defun f (x)\n(print x))\n");
	}

	@Test
	void formatsAFileNamedExplicitlyWhateverItsExtension(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("script.cl"), UNFORMATTED);
		assertThat(run(new Output(), file.toString())).isZero();
		assertThat(Files.readString(file)).isEqualTo(FORMATTED);
	}

	@Test
	void checkListsTheFilesAndExitsOneWithoutWriting(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.lisp"), UNFORMATTED);
		Output output = new Output();
		assertThat(run(output, "--check", dir.toString())).isEqualTo(1);
		assertThat(Files.readString(file)).isEqualTo(UNFORMATTED);
		assertThat(output.text()).contains(file.toString()).contains("1 of 1 file(s) need formatting");
	}

	@Test
	void checkExitsZeroOnAFormattedTree(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("a.lisp"), FORMATTED);
		Output output = new Output();
		assertThat(run(output, "--check", dir.toString())).isZero();
		assertThat(output.text()).isEmpty();
	}

	@Test
	void stdoutPrintsWithoutWriting(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.lisp"), UNFORMATTED);
		Output output = new Output();
		assertThat(run(output, "--stdout", file.toString())).isZero();
		assertThat(output.text()).isEqualTo(FORMATTED);
		assertThat(Files.readString(file)).isEqualTo(UNFORMATTED);
	}

	@Test
	void dashFormatsStandardInput() {
		Output output = new Output();
		assertThat(new FormatCommand(new ByteArrayInputStream(UNFORMATTED.getBytes(StandardCharsets.UTF_8)),
				output.stream())
			.run(new String[] { "-" })).isZero();
		assertThat(output.text()).isEqualTo(FORMATTED);
	}

	@Test
	void widthChangesWhereLinesWrap(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.lisp"), "(some-function (aaaa 1) (bbbb 2) (cccc 3))\n");
		Output output = new Output();
		assertThat(run(output, "--stdout", "--width=30", file.toString())).isZero();
		assertThat(output.text()).isEqualTo("""
				(some-function (aaaa 1)
				               (bbbb 2)
				               (cccc 3))
				""");
	}

	@Test
	void anUnreadableFileFailsTheRunButNotTheOtherFiles(@TempDir Path dir) throws IOException {
		// Alphabetical order puts the broken file first, so the good one is only reached
		// if
		// the walk keeps going.
		Files.writeString(dir.resolve("a-broken.lisp"), "(defun f (x)\n");
		Path good = Files.writeString(dir.resolve("b-good.lisp"), UNFORMATTED);
		assertThat(run(new Output(), dir.toString())).isEqualTo(2);
		assertThat(Files.readString(good)).isEqualTo(FORMATTED);
	}

	@Test
	void missingOrUnknownArgumentsFailWithoutWriting(@TempDir Path dir) {
		assertThat(run(new Output())).isEqualTo(2);
		assertThat(run(new Output(), "--bogus", dir.toString())).isEqualTo(2);
		assertThat(run(new Output(), "--width=nope", dir.toString())).isEqualTo(2);
		assertThat(run(new Output(), dir.resolve("nope.lisp").toString())).isEqualTo(2);
		// An empty directory: nothing to do is a mistake worth reporting, not a silent
		// pass.
		assertThat(run(new Output(), dir.toString())).isEqualTo(2);
	}

	@Test
	void helpExitsZero() {
		Output output = new Output();
		assertThat(run(output, "--help")).isZero();
		assertThat(output.text()).contains("Usage: rontolisp format");
	}

	@Test
	void theCliDispatchesTheSubcommandAndCarriesItsExitCode(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("a.lisp"), UNFORMATTED);
		Output output = new Output();
		RontoLispCli cli = new RontoLispCli(InputStream.nullInputStream(), output.stream());
		cli.run(new String[] { "format", "--check", dir.toString() });
		assertThat(cli.exitCode()).isEqualTo(1);
	}

	private static int run(Output output, String... args) {
		return new FormatCommand(InputStream.nullInputStream(), output.stream()).run(args);
	}

	private static final class Output {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		private final PrintStream stream = new PrintStream(this.bytes, true, StandardCharsets.UTF_8);

		PrintStream stream() {
			return this.stream;
		}

		String text() {
			this.stream.flush();
			return this.bytes.toString(StandardCharsets.UTF_8);
		}

	}

}
