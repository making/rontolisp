package am.ik.rontolisp.cli;

import java.io.PrintStream;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceSession;
import am.ik.rontolisp.reader.Features;

/**
 * The REPL's shared prompt and read-eval step over the line buffer: when the accumulated
 * input is complete -- the language's {@link SourceSession} says when -- every form in it
 * is evaluated and echoed, and the buffer is cleared. Both REPL drivers -- the plain
 * {@code BufferedReader} loop and {@link JLineRepl} -- take their prompt from here and
 * run their lines through here, so the two REPLs cannot drift.
 */
final class ReplBuffer {

	/** What a stack overflow reports, at the prompt and for a whole program alike. */
	static final String STACK_OVERFLOW = "stack overflow (--stack <MiB> raises the limit)";

	private ReplBuffer() {
	}

	/**
	 * The prompt for the next line: the language's own -- {@code CL-USER> }, the CURRENT
	 * package's name read fresh every line, so an {@code (in-package :foo)} typed at one
	 * prompt shows as {@code FOO> } at the next; {@code scheme> } for Scheme, which has
	 * no package to show. A continuation line (the buffer holds an incomplete form) is
	 * blanked to the same width instead, so the typed text stays in one column.
	 * @param session the REPL's language session
	 * @param evaluator the REPL's evaluator, holding the current package
	 * @param buffer the accumulated input, empty unless a form is still incomplete
	 * @return the prompt to print
	 */
	static String prompt(SourceSession session, LispEvaluator evaluator, StringBuilder buffer) {
		String prompt = session.prompt(evaluator);
		return buffer.isEmpty() ? prompt : " ".repeat(prompt.length());
	}

	static void eval(SourceSession session, LispEvaluator evaluator, PrintStream out, StringBuilder buffer) {
		LispEvaluator.ControlState before = evaluator.controlState();
		try {
			// #. read-time eval at the REPL: only a buffer textually containing #. pays
			// for the marker read; each form's markers resolve just before it runs, the
			// same timing interpret/loadFile use. The REPL has no file, so it reads the
			// session's language through the source-language seam.
			String source = buffer.toString();
			boolean markers = SourceLanguage.usesReadEvalMarkers(source);
			// EVERY form in the buffer is echoed, right after it runs, and as a
			// multiple-value consumer would see it: one value per line, as in any CL
			// REPL ((floor 10 3) echoes 3 then 1; (values) echoes nothing). A form's
			// own output therefore precedes its own value, and two forms typed on one
			// line echo twice -- what SBCL does reading them one at a time. A form the
			// language says has no value to show (a Scheme define) echoes nothing.
			for (SourceSession.Step step : session.read(source, Features.INTERPRETER)) {
				List<LispVal> values = List.of();
				for (int i = 0; i < step.forms().size(); i++) {
					LispVal form = step.forms().get(i);
					LispVal expr = markers ? evaluator.resolveReadTimeEvalInCode(form) : form;
					if (step.echoes() && i == step.forms().size() - 1) {
						values = evaluator.evalValues(expr);
					}
					else {
						evaluator.eval(expr);
					}
				}
				freshLine(evaluator);
				for (LispVal value : values) {
					// The language's own write: prin1 with the print-object route for
					// Common Lisp (a geom:solid echoes as its method prints it), the
					// Scheme printer for Scheme.
					out.println(session.print(value, evaluator));
				}
			}
		}
		catch (RuntimeException ex) {
			freshLine(evaluator);
			out.println("Error: " + ex.getMessage());
		}
		catch (StackOverflowError ex) {
			// Unwound to here, the stack is shallow again: what the overflow could not
			// restore on its way out is put back, and the session goes on with every
			// definition it had.
			before.restore();
			freshLine(evaluator);
			out.println("Error: " + STACK_OVERFLOW);
		}
		buffer.setLength(0);
	}

	// The echoed result starts on its own line even when the evaluated form left
	// standard output mid-line (e.g. a print-family call without a trailing newline).
	private static void freshLine(LispEvaluator evaluator) {
		try {
			evaluator.eval(SourceLanguage.COMMON_LISP.read("(fresh-line)", Features.INTERPRETER, null).get(0));
		}
		catch (RuntimeException ignored) {
			// Echo the result anyway; fresh-line is cosmetic.
		}
	}

}
