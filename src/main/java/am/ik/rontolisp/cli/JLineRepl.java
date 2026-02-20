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

	static void run(LispEvaluator evaluator, PrintStream out, StringBuilder buffer) {
		try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
			LineReader lineReader = LineReaderBuilder.builder()
				.terminal(terminal)
				.variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".rontolisp_history"))
				.build();
			while (true) {
				String prompt = buffer.isEmpty() ? "> " : "  ";
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
				if (RontoLispCli.isBalanced(buffer.toString())) {
					RontoLispCli.evalBuffer(evaluator, out, buffer);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
