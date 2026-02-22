package am.ik.rontolisp;

/**
 * A string literal value.
 *
 * @param value the string content
 */
public record LispString(String value) implements LispVal {

	@Override
	public String print() {
		return "\"" + this.value + "\"";
	}

}
