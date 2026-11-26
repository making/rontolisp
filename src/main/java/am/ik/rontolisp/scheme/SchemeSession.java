package am.ik.rontolisp.scheme;

import java.util.List;

import am.ik.rontolisp.reader.LispReadException;

/**
 * An interactive Scheme session: the global scope a whole-file lowering rebuilds per
 * file, kept across the buffers a REPL reads one at a time. What a session lowers
 * differently from a file, and why, is {@link SchemeLowering#interact}'s to say
 * ({@code .kb/scheme-frontend.md}, "A session").
 */
public final class SchemeSession {

	private final SchemeLowering lowering;

	SchemeSession(SchemeStandard standard, SchemeFiles files) {
		this.lowering = SchemeLowering.ofSession(standard, files);
	}

	/**
	 * Reads and lowers one buffer.
	 * @param source the typed text: any number of datums
	 * @return one entry per top-level datum, in order
	 */
	public List<SchemeTopLevel> read(String source) {
		return this.lowering.interact(new SchemeReader(source, null));
	}

	/**
	 * Whether the text is a complete datum sequence, as opposed to one the reader ran out
	 * of input in the middle of: an open list, string, {@code #|} comment, or a {@code '}
	 * / {@code #;} still waiting for its datum. Text that is complete but WRONG answers
	 * {@code true}: it must reach {@link #read} to be reported.
	 * @param source the typed text so far
	 * @return {@code false} when more input is needed
	 */
	public static boolean isComplete(String source) {
		try {
			new SchemeReader(source, null).readAll();
			return true;
		}
		catch (LispReadException ex) {
			return !ex.isEndOfFile();
		}
	}

}
