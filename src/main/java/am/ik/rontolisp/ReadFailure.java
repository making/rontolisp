package am.ik.rontolisp;

/**
 * How the compiled backends' runtime reader recorded the end of a parse (the JVM
 * {@code _readFail} field, the WASM {@code READ_FAIL_ADDR} cell), and what each record
 * reports. Bit 0 is the text running out; the other bits hold ONE error, recorded where
 * it was found while the cursor moves to the end of the text, so the only thing that can
 * follow it is the end-of-input bit the enclosing readers add as they unwind.
 */
public final class ReadFailure {

	private ReadFailure() {
	}

	/** The parse produced a datum. */
	public static final int NONE = 0;

	/** The text ran out before a datum was complete, or held none. */
	public static final int END_OF_INPUT = 1;

	/** A {@code )} where a datum was due. */
	public static final int UNMATCHED_CLOSE = 2;

	/** A {@code .} token with nothing before it in its list. */
	public static final int NOTHING_BEFORE_DOT = 4;

	/** A list's dotted tail followed by anything but the closing {@code )}. */
	public static final int MORE_THAN_ONE_AFTER_DOT = 6;

	/** The bits that hold the error, whatever the end-of-input bit says. */
	public static final int ERROR_MASK = 6;

	/** The report of {@link #UNMATCHED_CLOSE}. */
	public static final String UNMATCHED_CLOSE_MESSAGE = "Unexpected ')'";

	/** The report of {@link #NOTHING_BEFORE_DOT}. */
	public static final String NOTHING_BEFORE_DOT_MESSAGE = "Nothing appears before '.' in list";

	/** The report of {@link #MORE_THAN_ONE_AFTER_DOT}. */
	public static final String MORE_THAN_ONE_AFTER_DOT_MESSAGE = "More than one object follows '.' in list";

	/**
	 * The report of a recorded error.
	 * @param failure a recorded value that holds an error bit
	 * @return the reader-error's report text
	 */
	public static String message(int failure) {
		return switch (failure & ERROR_MASK) {
			case NOTHING_BEFORE_DOT -> NOTHING_BEFORE_DOT_MESSAGE;
			case MORE_THAN_ONE_AFTER_DOT -> MORE_THAN_ONE_AFTER_DOT_MESSAGE;
			default -> UNMATCHED_CLOSE_MESSAGE;
		};
	}

}
