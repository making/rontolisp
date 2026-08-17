package am.ik.rontolisp;

/**
 * A floating-point number value.
 *
 * @param value the double value
 */
public record LispDouble(double value) implements LispVal {

	@Override
	public String print() {
		return FloatText.doubleText(this.value);
	}

}
