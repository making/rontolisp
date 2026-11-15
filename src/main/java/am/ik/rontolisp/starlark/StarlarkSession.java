package am.ik.rontolisp.starlark;

import java.util.List;

import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.starlark.StarlarkSyntax.Def;
import am.ik.rontolisp.starlark.StarlarkSyntax.For;
import am.ik.rontolisp.starlark.StarlarkSyntax.If;
import am.ik.rontolisp.starlark.StarlarkSyntax.Statement;

/**
 * An interactive Starlark session: the buffers a REPL reads one at a time, lowered as one
 * file's run. What the buffers share and why is {@link StarlarkLowering#interact}'s to
 * say ({@code .kb/starlark-frontend.md}, "A session").
 */
public final class StarlarkSession {

	private final StarlarkLowering.Interaction interaction;

	StarlarkSession(StarlarkStandard standard, StarlarkFiles files) {
		this.interaction = new StarlarkLowering.Interaction(standard, files);
	}

	/**
	 * Reads and lowers one buffer.
	 * @param source the typed text: any number of statements
	 * @return the steps to run, in order
	 * @throws LispReadException on what reading a file would refuse, at its position
	 */
	public List<StarlarkTopLevel> read(String source) {
		return StarlarkLowering.interact(this.interaction, StarlarkParser.parse(source, null), source);
	}

	/**
	 * Whether the text can be read now, or the next line continues it. An open bracket,
	 * string or block continues it; a {@code def}, {@code if} or {@code for} ending the
	 * text is complete only at a blank line, since its block may go on. Text that is
	 * complete but WRONG answers {@code true}: it must reach {@link #read} to be
	 * reported.
	 * @param source the typed text so far, each line ending in a line feed
	 * @return {@code false} when more input is needed
	 */
	public static boolean isComplete(String source) {
		List<Statement> statements;
		try {
			statements = StarlarkParser.parse(source, null);
		}
		catch (LispReadException ex) {
			return !ex.isEndOfFile();
		}
		if (statements.isEmpty() || !(statements.getLast() instanceof Def || statements.getLast() instanceof If
				|| statements.getLast() instanceof For)) {
			return true;
		}
		String text = source.endsWith("\n") ? source.substring(0, source.length() - 1) : source;
		return text.substring(text.lastIndexOf('\n') + 1).isBlank();
	}

}
