package am.ik.rontolisp.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interactive REPL's line reader must hand the typed line to the Lisp reader
 * VERBATIM. JLine's default configuration enables csh-style {@code !} event expansion,
 * whose escape handling strips every backslash from the returned line -- so a character
 * literal like {@code #\,} arrived as {@code #,} and the reader saw an unquote outside
 * backquote ("Comma is illegal outside of backquote"). File and piped input never go
 * through JLine, which is why the same expression worked there.
 */
class JLineReplTest {

	// A dumb terminal fed from a fixed byte sequence; the input blocks (rather than
	// signalling EOF) once drained, like an idle interactive terminal.
	private static Terminal terminalFor(String typed) throws IOException {
		byte[] content = typed.getBytes();
		InputStream in = new InputStream() {
			private int i = 0;

			@Override
			public int read() {
				if (this.i < content.length) {
					return content[this.i++] & 0xff;
				}
				try {
					Thread.sleep(100_000);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return -1;
			}
		};
		return TerminalBuilder.builder().streams(in, new ByteArrayOutputStream()).type("dumb").build();
	}

	@Test
	void readLinePreservesBackslashesInCharacterLiterals(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home)
			throws Exception {
		// Point the history file at a temp dir so the test does not touch the
		// user's real ~/.rontolisp_history.
		String savedHome = System.getProperty("user.home");
		System.setProperty("user.home", home.toString());
		try (Terminal terminal = terminalFor("(print #\\,) \\a \\!\r")) {
			LineReader reader = JLineRepl.buildLineReader(terminal);
			assertThat(reader.readLine("> ")).isEqualTo("(print #\\,) \\a \\!");
		}
		finally {
			System.setProperty("user.home", savedHome);
		}
	}

}
