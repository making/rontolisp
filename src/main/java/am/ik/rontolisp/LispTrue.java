package am.ik.rontolisp;

/**
 * The true value ({@code t}).
 */
public record LispTrue() implements LispVal {

	/** The singleton true instance. */
	public static final LispTrue INSTANCE = new LispTrue();

	@Override
	public String print() {
		return "t";
	}

}
