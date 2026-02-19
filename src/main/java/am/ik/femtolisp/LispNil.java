package am.ik.femtolisp;

public record LispNil() implements LispVal {

	public static final LispNil INSTANCE = new LispNil();

	@Override
	public String print() {
		return "nil";
	}

}
