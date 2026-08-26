package am.ik.rontolisp.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import am.ik.rontolisp.eval.LispEvaluator;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * JLine-based interactive REPL. This class is isolated from {@link RontoLispCli} so that
 * the CLI can load and run without JLine on the classpath.
 */
final class JLineRepl {

	private JLineRepl() {
	}

	/**
	 * Builds the REPL's line reader. Event expansion is disabled: JLine's csh-style
	 * {@code !} history expansion treats backslash as an escape character and STRIPS it
	 * from the returned line, which silently corrupts every Lisp character literal
	 * ({@code #\,} arrived as {@code #,} and read as an unquote outside backquote). Lisp
	 * has no use for {@code !} expansion, so the raw line wins.
	 * @param terminal the terminal to read from
	 * @return the configured line reader
	 */
	static LineReader buildLineReader(Terminal terminal) {
		return LineReaderBuilder.builder()
			.terminal(terminal)
			.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
			.variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".rontolisp_history"))
			.build();
	}

	/**
	 * On the native binary, selects JLine's JNI terminal provider instead of the default
	 * FFM one. JLine 4.1+'s FFM provider closes a shared {@code Arena} (Arena.ofShared)
	 * from its signal handler on shutdown, which Native Image supports only under
	 * {@code -H:+SharedArenaSupport} -- and GraalVM 25 refuses to combine that with
	 * {@code -H:+VectorAPISupport}, which the interpreter's {@code --simd} needs to be
	 * fast rather than 6-32x slower than scalar. The JNI provider uses no shared arena,
	 * so pinning it lets the image keep the Vector API intrinsics and the REPL work. Only
	 * applied in the image (a plain {@code java -jar} keeps the FFM provider), and never
	 * overrides an explicit {@code -Dorg.jline.terminal.provider}.
	 */
	private static void selectNativeImageTerminalProvider() {
		if (System.getProperty("org.graalvm.nativeimage.imagecode") != null
				&& System.getProperty("org.jline.terminal.provider") == null) {
			System.setProperty("org.jline.terminal.provider", "jni");
		}
	}

	static void run(LispEvaluator evaluator, PrintStream out, StringBuilder buffer) {
		selectNativeImageTerminalProvider();
		// Disable grapheme cluster (mode 2027) detection. JLine probes for it by
		// sending a DECRQM query (CSI ? 2027 $ p); terminals that do not understand
		// the query echo the trailing "p" as visible garbage before the first prompt.
		try (Terminal terminal = TerminalBuilder.builder().system(true).graphemeCluster(false).build()) {
			LineReader lineReader = buildLineReader(terminal);
			while (true) {
				String prompt = ReplBuffer.prompt(evaluator, buffer);
				String line;
				try {
					line = lineReader.readLine(prompt);
				}
				catch (UserInterruptException _) {
					buffer.setLength(0);
					continue;
				}
				catch (EndOfFileException _) {
					break;
				}
				if ("(quit)".equals(line.trim())) {
					break;
				}
				buffer.append(line).append('\n');
				if (ReplBuffer.isBalanced(buffer.toString())) {
					ReplBuffer.eval(evaluator, out, buffer);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
