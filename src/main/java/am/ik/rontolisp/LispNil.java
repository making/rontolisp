package am.ik.rontolisp;

/**
 * The nil value, representing false and the empty list.
 */
public record LispNil() implements LispVal {

	/** The singleton nil instance. */
	public static final LispNil INSTANCE = new LispNil();

	@Override
	public String print() {
		return "nil";
	}

}
