package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;

/**
 * The browser's interpreter: the playground's REPL and the documentation site's Run
 * cells, in the language picked for it. It lives here rather than beside the {@code @JS}
 * bootstrap in {@code src/web/java} so the JVM test suite runs exactly what the browser
 * runs -- {@code DocExamplesTest} checks a {@code scheme} fence through it.
 *
 * <p>
 * Every buffer is read through {@link SourceSession}, one per language over the one
 * evaluator, so switching the pick keeps every definition and a Scheme session keeps what
 * its earlier buffers defined ({@code .kb/scheme-frontend.md}, "A session").
 *
 * <p>
 * Three ways to run a buffer, each returning the text a page shows:
 * <ul>
 * <li>{@link #eval}: the REPL and the Common Lisp Run cells. Only the LAST form's values
 * are echoed, unlike the CLI REPL (every form): a Run cell's block is a setup plus an
 * expression whose FINAL value is the annotated one.</li>
 * <li>{@link #run}: a whole program, read as a file is, standard output only -- a
 * {@code scheme} fence on the documentation site.</li>
 * <li>{@link #transcript}: every form echoed after its own output, as the CLI REPL does
 * on a pipe -- a {@code scheme} fence carrying {@code ; =>} annotations.</li>
 * </ul>
 * A failure propagates as the evaluator's exception, whose message is the report; an
 * {@code exit} ends the buffer and keeps what it printed.
 */
public final class PlaygroundRepl {

	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

	private final PrintStream out = new PrintStream(this.buffer, true, StandardCharsets.UTF_8);

	private final LispEvaluator evaluator;

	private final SourceLoader loader;

	private final Map<SourceLanguage, SourceSession> sessions = new EnumMap<>(SourceLanguage.class);

	private SourceLanguage language = SourceLanguage.COMMON_LISP;

	/**
	 * An interpreter whose standard input is empty.
	 * @param loader where {@code load}, {@code include} and a WIT directive read files
	 */
	public PlaygroundRepl(SourceLoader loader) {
		this(loader, "");
	}

	/**
	 * An interpreter reading {@code stdin} as its standard input.
	 * @param loader where {@code load}, {@code include} and a WIT directive read files
	 * @param stdin the whole standard input
	 */
	public PlaygroundRepl(SourceLoader loader, String stdin) {
		this.evaluator = new LispEvaluator(this.out, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
		this.evaluator.setSourceLoader(loader);
		this.loader = loader;
	}

	/**
	 * Picks the language the next buffers are read in.
	 * @param language the language
	 * @return this interpreter
	 */
	public PlaygroundRepl pick(SourceLanguage language) {
		this.language = language;
		return this;
	}

	/**
	 * The picked language.
	 * @return the language buffers are read in
	 */
	public SourceLanguage language() {
		return this.language;
	}

	/**
	 * Evaluates a buffer, echoing the last form's values one per line after what the
	 * buffer printed ({@code (floor 10 3)} echoes {@code 3} then {@code 1}); a form with
	 * no value to show (a Scheme definition, an effect) echoes nothing.
	 * @param source the typed text
	 * @return the output, then the echo
	 */
	public String eval(String source) {
		this.buffer.reset();
		SourceSession session = session();
		try {
			List<SourceSession.Step> steps = session.read(source, Features.INTERPRETER);
			List<LispVal> values = List.of();
			for (int s = 0; s < steps.size(); s++) {
				values = evalStep(steps.get(s), s == steps.size() - 1, source);
			}
			this.out.flush();
			StringBuilder shown = new StringBuilder(output());
			boolean first = true;
			for (LispVal value : values) {
				String echoed = session.echo(value, this.evaluator);
				if (echoed != null) {
					shown.append(first ? "" : "\n").append(echoed);
					first = false;
				}
			}
			return shown.toString();
		}
		catch (LispExitSignal exit) {
			return output();
		}
	}

	/**
	 * Runs a whole program, read the way a file in the picked language is read.
	 * @param source the program text
	 * @return what the program printed
	 */
	public String run(String source) {
		this.buffer.reset();
		try {
			boolean markers = SourceLanguage.usesReadEvalMarkers(source);
			for (LispVal form : this.language.read(source, this.evaluator.features(), null, SourceStandards.DEFAULT,
					this.loader)) {
				this.evaluator.eval(markers ? this.evaluator.resolveReadTimeEvalInCode(form) : form);
			}
		}
		catch (LispExitSignal exit) {
			// (exit) ends the program; what it printed before is its output.
		}
		return output();
	}

	/**
	 * Evaluates a buffer the way the CLI REPL does on a pipe: each form's values, one per
	 * line, right after that form's own output, starting on a fresh line.
	 * @param source the typed text
	 * @return the transcript
	 */
	public String transcript(String source) {
		this.buffer.reset();
		SourceSession session = session();
		try {
			for (SourceSession.Step step : session.read(source, Features.INTERPRETER)) {
				List<LispVal> values = evalStep(step, true, source);
				freshLine();
				for (LispVal value : values) {
					String echoed = session.echo(value, this.evaluator);
					if (echoed != null) {
						this.out.println(echoed);
					}
				}
			}
		}
		catch (LispExitSignal exit) {
			// (exit) ends the buffer; what it printed before is its output.
		}
		return output();
	}

	// Runs one step's forms; answers its last form's values when the step is echoed.
	// Each form's #. markers resolve just before it runs, as the CLI REPL does.
	private List<LispVal> evalStep(SourceSession.Step step, boolean echo, String source) {
		boolean markers = SourceLanguage.usesReadEvalMarkers(source);
		List<LispVal> values = List.of();
		for (int i = 0; i < step.forms().size(); i++) {
			LispVal form = step.forms().get(i);
			LispVal expr = markers ? this.evaluator.resolveReadTimeEvalInCode(form) : form;
			if (echo && step.echoes() && i == step.forms().size() - 1) {
				values = this.evaluator.evalValues(expr);
			}
			else {
				this.evaluator.eval(expr);
			}
		}
		return values;
	}

	private SourceSession session() {
		return this.sessions.computeIfAbsent(this.language,
				picked -> new SourceSession(picked, SourceStandards.DEFAULT, this.loader));
	}

	private void freshLine() {
		this.evaluator.eval(SourceLanguage.COMMON_LISP.read("(fresh-line)", Features.INTERPRETER, null).get(0));
	}

	private String output() {
		this.out.flush();
		return this.buffer.toString(StandardCharsets.UTF_8);
	}

}
