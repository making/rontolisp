package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.scheme.Scheme;
import am.ik.rontolisp.scheme.SchemeSession;
import am.ik.rontolisp.scheme.SchemeTopLevel;

/**
 * The source-language seam for a consumer that has no file and reads one buffer at a time
 * -- the CLI REPL, a playground. Everything such a consumer must ask the LANGUAGE rather
 * than assume is here: whether the typed text is complete, what it lowers to and which of
 * its values are worth showing, how a value is written back, and what the prompt says.
 *
 * <p>
 * It is a session, not a function, because a language may need to remember what earlier
 * buffers defined: Scheme's lowering decides how a name is called from what defined it
 * ({@code .kb/scheme-frontend.md}, "A session"). Common Lisp keeps nothing here; its
 * state is the evaluator's.
 */
public final class SourceSession {

	/**
	 * One top-level form of a buffer, as core forms.
	 *
	 * @param forms what to evaluate, in order; the value of the LAST one is the form's
	 * @param echoes whether that value is worth showing (a Scheme definition has none)
	 */
	public record Step(List<LispVal> forms, boolean echoes) {
	}

	// The Scheme echo: a closure over the value rather than a form quoting it, which the
	// resolver would walk -- forever, on a cyclic value. It skips the unspecified object
	// an effect answers, NIL standing for "nothing to show".
	private static final String ECHO = "(lambda (x) (if (eq x rontolisp::%scheme-unspecified) nil"
			+ " (with-output-to-string (*standard-output*) (rontolisp::%scheme-write x))))";

	private final SourceLanguage language;

	private final @Nullable SchemeSession scheme;

	/**
	 * Starts a session read against every language's default standard.
	 * @param language the language typed at the prompt
	 */
	public SourceSession(SourceLanguage language) {
		this(language, SourceStandards.DEFAULT);
	}

	/**
	 * Starts a session.
	 * @param language the language typed at the prompt
	 * @param standards what the typed text is read against ({@code --scheme-standard})
	 */
	public SourceSession(SourceLanguage language, SourceStandards standards) {
		this.language = language;
		this.scheme = language == SourceLanguage.SCHEME ? Scheme.session(standards.scheme()) : null;
	}

	/**
	 * Whether the accumulated input can be read now, or the next line continues it.
	 * @param buffer the text typed so far
	 * @return {@code false} when a form is still open
	 */
	public boolean isComplete(String buffer) {
		return this.scheme != null ? SchemeSession.isComplete(buffer) : isBalanced(buffer);
	}

	/**
	 * Reads a complete buffer into core forms. A Common Lisp buffer textually containing
	 * {@code #.} comes back with its markers unresolved, exactly as
	 * {@link SourceLanguage#read} answers it: the caller resolves each form's just before
	 * it runs ({@link SourceLanguage#usesReadEvalMarkers}).
	 * @param buffer the typed text
	 * @param features the active reader features
	 * @return the top-level forms, in order
	 */
	public List<Step> read(String buffer, Features features) {
		List<Step> steps = new ArrayList<>();
		if (this.scheme != null) {
			for (SchemeTopLevel topLevel : this.scheme.read(buffer)) {
				steps.add(new Step(topLevel.forms(), topLevel.echoes()));
			}
			return steps;
		}
		for (LispVal form : this.language.read(buffer, features, null)) {
			steps.add(new Step(List.of(form), true));
		}
		return steps;
	}

	/**
	 * The prompt for a fresh form. Common Lisp names the CURRENT package, read fresh
	 * every time, because which package a bare symbol interns into is otherwise
	 * invisible; Scheme has no package to show and names itself.
	 * @param evaluator the session's evaluator
	 * @return the prompt, trailing space included
	 */
	public String prompt(LispEvaluator evaluator) {
		return this.scheme != null ? "scheme> " : evaluator.currentPackageName() + "> ";
	}

	/**
	 * Writes a value the way the language's own {@code write} would: {@code prin1}
	 * through the {@code print-object} route for Common Lisp, the Scheme printer
	 * ({@code #t}, {@code #f}, {@code ()}, case-sensitive symbols) for Scheme -- or
	 * nothing, for the unspecified object a Scheme effect answers.
	 * @param value the value to show
	 * @param evaluator the session's evaluator
	 * @return the text, or {@code null} when the value is not shown
	 */
	public @Nullable String echo(LispVal value, LispEvaluator evaluator) {
		if (this.scheme == null) {
			return evaluator.prin1ToStringRouted(value);
		}
		try {
			LispVal echoed = evaluator.printThrough(ECHO, value);
			return echoed instanceof LispString written ? written.value() : null;
		}
		catch (RuntimeException ex) {
			// An echo must never turn a computed value into an error.
			return value.print();
		}
	}

	// Common Lisp's continuation rule: parentheses outside strings, a backslash escaping
	// the next character inside one.
	private static boolean isBalanced(String input) {
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

}
