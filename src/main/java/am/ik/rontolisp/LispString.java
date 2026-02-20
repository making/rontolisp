package am.ik.rontolisp;

public record LispString(String value) implements LispVal {

	@Override
	public String print() {
		return "\"" + this.value + "\"";
	}

}
