package am.ik.rontolisp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * javac translates a backslash-u escape everywhere, comments included: without four hex
 * digits it is an illegal unicode escape compile error, and with four it silently
 * rewrites the comment or the value (the formatter then writes the translated character
 * back). So a Java file must never hold a raw backslash-u outside a string literal -- a
 * doubled backslash shields it even in comments, or plain words like backslash-u say the
 * same thing. This test is the machine half of that rule (.kb/session-workflow.md): it
 * fails when such a sequence appears outside string, character and text-block literals.
 * Only the main lanes are scanned, so the web and native lanes stay unaffected.
 */
class RawBackslashUTest {

	private static final List<Path> ROOTS = List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"));

	private static final char BACKSLASH = '\\';

	private static final char LETTER_U = 'u';

	@Test
	void noRawBackslashUOutsideStringLiterals() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Path root : ROOTS) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> walk = Files.walk(root)) {
				for (Path file : walk.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted()
					.toList()) {
					collectViolations(file, violations);
				}
			}
			catch (UncheckedIOException e) {
				throw e.getCause();
			}
		}
		assertThat(violations)
			.as("A Java file holds a raw backslash-u outside a string literal. javac translates "
					+ "backslash-u escapes everywhere, comments included: without four hex digits it is "
					+ "an illegal unicode escape error, with four it silently rewrites the comment or value. "
					+ "Spell it with a doubled backslash even in comments, or use words like backslash-u. "
					+ "See .kb/session-workflow.md.")
			.isEmpty();
	}

	private void collectViolations(Path file, List<String> violations) throws IOException {
		String text = Files.readString(file);
		boolean inLineComment = false;
		boolean inBlockComment = false;
		boolean inString = false;
		boolean inChar = false;
		boolean inTextBlock = false;
		int line = 1;
		int length = text.length();
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			char next = (i + 1 < length) ? text.charAt(i + 1) : '\0';
			char next2 = (i + 2 < length) ? text.charAt(i + 2) : '\0';
			if (inTextBlock) {
				if (c == '\n') {
					line++;
				}
				if (c == BACKSLASH) {
					i++;
					continue;
				}
				if (c == '"' && next == '"' && next2 == '"') {
					inTextBlock = false;
					i += 2;
				}
				continue;
			}
			if (inString) {
				if (c == BACKSLASH) {
					i++;
					continue;
				}
				if (c == '"') {
					inString = false;
				}
				else if (c == '\n') {
					inString = false;
				}
				continue;
			}
			if (inChar) {
				if (c == BACKSLASH) {
					i++;
					continue;
				}
				if (c == '\'') {
					inChar = false;
				}
				continue;
			}
			if (inLineComment) {
				if (c == '\n') {
					inLineComment = false;
					line++;
				}
				else {
					checkChar(text, i, file, line, violations);
				}
				continue;
			}
			if (inBlockComment) {
				if (c == '\n') {
					line++;
				}
				if (c == '*' && next == '/') {
					inBlockComment = false;
					i++;
					continue;
				}
				checkChar(text, i, file, line, violations);
				continue;
			}
			if (c == '\n') {
				line++;
				continue;
			}
			if (c == '/' && next == '/') {
				inLineComment = true;
				i++;
				continue;
			}
			if (c == '/' && next == '*') {
				inBlockComment = true;
				i++;
				continue;
			}
			if (c == '"' && next == '"' && next2 == '"') {
				inTextBlock = true;
				i += 2;
				continue;
			}
			if (c == '"') {
				inString = true;
				continue;
			}
			if (c == '\'') {
				inChar = true;
				continue;
			}
			checkChar(text, i, file, line, violations);
		}
	}

	private void checkChar(String text, int index, Path file, int line, List<String> violations) {
		if (text.charAt(index) != LETTER_U) {
			return;
		}
		int backslashes = 0;
		int j = index - 1;
		while (j >= 0 && text.charAt(j) == BACKSLASH) {
			backslashes++;
			j--;
		}
		if (backslashes % 2 == 1) {
			violations.add(file + ":" + line);
		}
	}

}
