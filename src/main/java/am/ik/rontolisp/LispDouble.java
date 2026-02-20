package am.ik.rontolisp;

public record LispDouble(double value) implements LispVal {

	@Override
	public String print() {
		return Double.toString(this.value);
	}

}
