package am.ik.rontolisp;

public record LispSymbol(String name) implements LispVal {

	@Override
	public String print() {
		return this.name;
	}

}
