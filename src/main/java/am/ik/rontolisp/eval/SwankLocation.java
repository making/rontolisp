package am.ik.rontolisp.eval;

/**
 * Where a compiler note, a definition or a debugger's frame points, in an editor buffer,
 * a file, or a printed form.
 */
public sealed interface SwankLocation {

	/**
	 * A place in an editor buffer.
	 *
	 * @param buffer the buffer's name
	 * @param start the buffer position the compiled text starts at
	 * @param offset the offset within the compiled text
	 */
	record Buffer(String buffer, int start, int offset) implements SwankLocation {
	}

	/**
	 * A place in an editor buffer visiting a file.
	 *
	 * @param buffer the buffer's name
	 * @param file the file
	 * @param start the buffer position the compiled text starts at
	 * @param offset the offset within the compiled text
	 */
	record BufferAndFile(String buffer, String file, int start, int offset) implements SwankLocation {
	}

	/**
	 * A place in a file.
	 *
	 * @param file the file
	 * @param position the 1-based character position
	 */
	record File(String file, int position) implements SwankLocation {
	}

	/**
	 * A form with no text to point into, printed: a function typed at the REPL, the form
	 * a frame waits in marked {@code (#:***HERE*** form)}.
	 *
	 * @param form the printed form
	 */
	record SourceForm(String form) implements SwankLocation {
	}

}
