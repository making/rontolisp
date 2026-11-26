package am.ik.rontolisp.cli;

/**
 * A compile that {@code --warnings-as-errors} failed: it emitted warnings about the
 * program's own source, every one of them already printed, and so produced no output.
 * <p>
 * Thrown at the compile boundary once the backend has finished and before anything is
 * written, so the message names the count rather than a position -- each warning line
 * already carries its own.
 */
public final class WarningsAsErrorsException extends RuntimeException {

	private final int count;

	/**
	 * @param count how many counted warnings the compile emitted
	 */
	WarningsAsErrorsException(int count) {
		super(count + (count == 1 ? " warning" : " warnings")
				+ " about the program's source, treated as errors (--warnings-as-errors)");
		this.count = count;
	}

	/**
	 * @return how many counted warnings the compile emitted
	 */
	public int count() {
		return this.count;
	}

}
