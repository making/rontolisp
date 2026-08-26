package am.ik.rontolisp.cli;

import java.io.PrintStream;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

/**
 * The REPL's shared prompt and read-eval step over the line buffer: when the accumulated
 * input balances, every form in it is evaluated and echoed, and the buffer is cleared.
 * Both REPL drivers -- the plain {@code BufferedReader} loop and {@link JLineRepl} --
 * take their prompt from here and run their lines through here, so the two REPLs cannot
 * drift.
 */
final class ReplBuffer {

	private ReplBuffer() {
	}

	/**
	 * The prompt for the next line: {@code CL-USER> } -- the CURRENT package's name, read
	 * fresh every line, as a Common Lisp REPL names it. An {@code (in-package :foo)}
	 * typed at one prompt therefore shows as {@code FOO> } at the next, which is the
	 * whole point: which package a bare symbol interns into is otherwise invisible. A
	 * continuation line (the buffer holds an unbalanced form) is blanked to the same
	 * width instead, so the typed text stays in one column.
	 * @param evaluator the REPL's evaluator, holding the current package
	 * @param buffer the accumulated input, empty unless a form is still unbalanced
	 * @return the prompt to print
	 */
	static String prompt(LispEvaluator evaluator, StringBuilder buffer) {
		String prompt = evaluator.currentPackageName() + "> ";
		return buffer.isEmpty() ? prompt : " ".repeat(prompt.length());
	}

	static boolean isBalanced(String input) {
		int depth = 0;
		boolean inString = false;
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (inString) {
				if (c == '\\' && i + 1 < input.length()) {
					i++;
				}
				else if (c == '"') {
					inString = false;
				}
			}
			else {
				if (c == '"') {
					inString = true;
				}
				else if (c == '(') {
					depth++;
				}
				else if (c == ')') {
					depth--;
				}
			}
		}
		return depth <= 0 && !inString;
	}

	static void eval(LispEvaluator evaluator, PrintStream out, StringBuilder buffer) {
		try {
			// #. read-time eval at the REPL: only a buffer textually containing #. pays
			// for the marker read; each form's markers resolve just before it runs, the
			// same timing interpret/loadFile use.
			String source = buffer.toString();
			boolean markers = source.contains("#.");
			List<LispVal> exprs = markers ? LispReader.readAllWithReadEvalMarkers(source, Features.INTERPRETER)
					: LispReader.readAllFromString(source);
			// EVERY form in the buffer is echoed, right after it runs, and as a
			// multiple-value consumer would see it: one value per line, as in any CL
			// REPL ((floor 10 3) echoes 3 then 1; (values) echoes nothing). A form's
			// own output therefore precedes its own value, and two forms typed on one
			// line echo twice -- what SBCL does reading them one at a time.
			for (LispVal expr : exprs) {
				List<LispVal> values = evaluator.evalValues(markers ? evaluator.resolveReadTimeEvalInCode(expr) : expr);
				freshLine(evaluator);
				for (LispVal value : values) {
					out.println(value.print());
				}
			}
		}
		catch (RuntimeException ex) {
			freshLine(evaluator);
			out.println("Error: " + ex.getMessage());
		}
		buffer.setLength(0);
	}

	// The echoed result starts on its own line even when the evaluated form left
	// standard output mid-line (e.g. a print-family call without a trailing newline).
	private static void freshLine(LispEvaluator evaluator) {
		try {
			evaluator.eval(LispReader.readAllFromString("(fresh-line)").get(0));
		}
		catch (RuntimeException ignored) {
			// Echo the result anyway; fresh-line is cosmetic.
		}
	}

}
