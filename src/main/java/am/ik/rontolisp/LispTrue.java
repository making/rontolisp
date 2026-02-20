package am.ik.rontolisp;

public record LispTrue() implements LispVal {

	public static final LispTrue INSTANCE = new LispTrue();

	@Override
	public String print() {
		return "t";
	}

}
