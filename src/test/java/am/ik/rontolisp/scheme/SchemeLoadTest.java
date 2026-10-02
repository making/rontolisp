package am.ik.rontolisp.scheme;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code (scheme load)} procedure's interpreter legs that no corpus case can carry:
 * the loaded file's definitions are the loader's, and a load of a missing file raises a
 * condition {@code file-error?} answers {@code #t} for (the compiled legs inline a
 * literal {@code load} at compile time, so their missing-file answer is the compile error
 * -- {@code LoadInlinerTest}).
 */
class SchemeLoadTest {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final LispEvaluator evaluator = new LispEvaluator(new PrintStream(this.out, true, StandardCharsets.UTF_8));

	private void run(String source, Path entry) {
		SourceStandards standards = SourceStandards.parse("rontolisp");
		this.evaluator.setSourceStandards(standards);
		Path absolute = entry.toAbsolutePath();
		this.evaluator.setLoadBaseDir(java.util.Objects.requireNonNullElse(absolute.getParent(), absolute).toString());
		for (LispVal form : SourceLanguage.SCHEME.read(source, Features.INTERPRETER, entry.toString(), standards,
				SourceLoader.fileSystem())) {
			this.evaluator.eval(form);
		}
	}

	@Test
	void aLoadedFilesDefinitionsAreTheLoaders(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("loaded.scm"), """
				(define loaded-x 41)
				(define (loaded-twice n) (* n 2))
				""", StandardCharsets.UTF_8);
		Path entry = dir.resolve("program.scm");
		Files.writeString(entry, """
				(import (scheme base) (scheme write) (scheme load))
				(load "loaded.scm")
				(write (loaded-twice loaded-x))
				(newline)
				""", StandardCharsets.UTF_8);
		run(Files.readString(entry), entry);
		assertThat(this.out.toString(StandardCharsets.UTF_8)).isEqualTo("82\n");
	}

	@Test
	void aMissingFileIsAFileError(@TempDir Path dir) {
		Path entry = dir.resolve("program.scm");
		run("""
				(import (scheme base) (scheme write) (scheme load))
				(write (guard (e ((file-error? e) 'file-error) ((error-object? e) 'other))
				         (load "no-such-file.scm")))
				(newline)
				""", entry);
		assertThat(this.out.toString(StandardCharsets.UTF_8)).isEqualTo("file-error\n");
	}

}
