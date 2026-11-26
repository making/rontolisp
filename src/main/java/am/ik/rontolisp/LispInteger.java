package am.ik.rontolisp;

/**
 * An integer number value.
 *
 * @param value the long value
 */
public record LispInteger(long value) implements LispVal {

	@Override
	public String print() {
		return Long.toString(this.value);
	}

}
