package am.ik.rontolisp.cli;

import java.io.PrintStream;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceSession;
import am.ik.rontolisp.reader.Features;

/**
 * The REPL's shared prompt and read-eval step over the line buffer: when the accumulated
 * input is complete -- the language's {@link SourceSession} says when -- every form in it
 * is evaluated and echoed, and the buffer is cleared. Both REPL drivers -- the plain
 * {@code BufferedReader} loop and {@link JLineRepl} -- take their prompt from here and
 * run their lines through here, so the two REPLs cannot drift.
 *
 * <p>
 * A session on a terminal prompts and reports a failure on standard output, between the
 * prompts. A session on a pipe is a script runner: it writes no prompt, reports a failure
 * on its error stream, and remembers that one happened, so the process can end non-zero
 * at the end of input.
 */
final class ReplBuffer {

	/** What a stack overflow reports, at the prompt and for a whole program alike. */
	static final String STACK_OVERFLOW = "stack overflow (--stack <MiB> raises the limit)";

	private final SourceSession session;

	private final LispEvaluator evaluator;

	private final PrintStream out;

	private final PrintStream errors;

	private final boolean terminal;

	private final StringBuilder buffer = new StringBuilder();

	private boolean failed;

	/**
	 * A buffer for one session.
	 * @param session the REPL's language session
	 * @param evaluator the REPL's evaluator, holding the current package
	 * @param channels where values and failures go, and whether a person is typing
	 */
	ReplBuffer(SourceSession session, LispEvaluator evaluator, Channels channels) {
		this.session = session;
		this.evaluator = evaluator;
		this.out = channels.out();
		this.errors = channels.terminal() ? channels.out() : channels.errors();
		this.terminal = channels.terminal();
	}

	/**
	 * Where a session writes.
	 *
	 * @param out standard output: values, and the program's own output
	 * @param errors the error stream a piped session reports failures on
	 * @param terminal whether the session reads from a terminal, which prompts and
	 * reports failures between the prompts
	 */
	record Channels(PrintStream out, PrintStream errors, boolean terminal) {
	}

	/**
	 * The prompt for the next line: the language's own -- {@code CL-USER> }, the CURRENT
	 * package's name read fresh every line, so an {@code (in-package :foo)} typed at one
	 * prompt shows as {@code FOO> } at the next; {@code scheme> } for Scheme, which has
	 * no package to show. A continuation line (the buffer holds an incomplete form) is
	 * blanked to the same width instead, so the typed text stays in one column. A piped
	 * session has no prompt at all.
	 * @return the prompt to print
	 */
	String prompt() {
		if (!this.terminal) {
			return "";
		}
		String prompt = this.session.prompt(this.evaluator);
		return this.buffer.isEmpty() ? prompt : " ".repeat(prompt.length());
	}

	/**
	 * Adds a typed line, evaluating the buffer once it is complete. A line holding no
	 * datum -- blank, or only a comment -- leaves the buffer empty.
	 * @param line the line, without its terminator
	 */
	void accept(String line) {
		this.buffer.append(line).append('\n');
		if (this.session.isComplete(this.buffer.toString())) {
			eval();
		}
	}

	/**
	 * Whether no form is partly typed: the next line starts a fresh one.
	 * @return {@code true} when the buffer is empty
	 */
	boolean isEmpty() {
		return this.buffer.isEmpty();
	}

	/** Drops a partly typed form (an interrupt at the prompt). */
	void clear() {
		this.buffer.setLength(0);
	}

	/**
	 * Whether any form failed on a pipe: the exit status a script runner reports.
	 * @return {@code true} when a failure was reported on the error stream
	 */
	boolean failedOnAPipe() {
		return this.failed && !this.terminal;
	}

	private void eval() {
		LispEvaluator.ControlState before = this.evaluator.controlState();
		try {
			// #. read-time eval at the REPL: only a buffer textually containing #. pays
			// for the marker read; each form's markers resolve just before it runs, the
			// same timing interpret/loadFile use. The REPL has no file, so it reads the
			// session's language through the source-language seam.
			String source = this.buffer.toString();
			boolean markers = SourceLanguage.usesReadEvalMarkers(source);
			// EVERY form in the buffer is echoed, right after it runs, and as a
			// multiple-value consumer would see it: one value per line, as in any CL
			// REPL ((floor 10 3) echoes 3 then 1; (values) echoes nothing). A form's
			// own output therefore precedes its own value, and two forms typed on one
			// line echo twice -- what SBCL does reading them one at a time. A form the
			// language says has no value (a Scheme define) echoes nothing, and neither
			// does a value the language does not show (a Scheme effect's).
			for (SourceSession.Step step : this.session.read(source, Features.INTERPRETER)) {
				List<LispVal> values = List.of();
				for (int i = 0; i < step.forms().size(); i++) {
					LispVal form = step.forms().get(i);
					LispVal expr = markers ? this.evaluator.resolveReadTimeEvalInCode(form) : form;
					if (step.echoes() && i == step.forms().size() - 1) {
						values = this.evaluator.evalValues(expr);
					}
					else {
						this.evaluator.eval(expr);
					}
				}
				freshLine();
				for (LispVal value : values) {
					// The language's own write: prin1 with the print-object route for
					// Common Lisp (a geom:solid echoes as its method prints it), the
					// Scheme printer for Scheme.
					String echoed = this.session.echo(value, this.evaluator);
					if (echoed != null) {
						this.out.println(echoed);
					}
				}
			}
		}
		catch (LispExitSignal exit) {
			// (exit) / (uiop:quit): the session ends here, with the status asked for.
			this.buffer.setLength(0);
			throw exit;
		}
		catch (RuntimeException ex) {
			// The condition's own text, which file mode and every compiled backend
			// report too: a language does not reword a failure at its REPL.
			fail(String.valueOf(ex.getMessage()));
		}
		catch (StackOverflowError ex) {
			// Unwound to here, the stack is shallow again: what the overflow could not
			// restore on its way out is put back, and the session goes on with every
			// definition it had.
			before.restore();
			fail(STACK_OVERFLOW);
		}
		this.buffer.setLength(0);
	}

	private void fail(String report) {
		this.failed = true;
		freshLine();
		// The program's output so far precedes the report even when the two streams
		// meet again in one file.
		this.out.flush();
		this.errors.println("Error: " + report);
	}

	// The echoed result starts on its own line even when the evaluated form left
	// standard output mid-line (e.g. a print-family call without a trailing newline).
	private void freshLine() {
		try {
			this.evaluator.eval(SourceLanguage.COMMON_LISP.read("(fresh-line)", Features.INTERPRETER, null).get(0));
		}
		catch (RuntimeException ignored) {
			// Echo the result anyway; fresh-line is cosmetic.
		}
	}

}
