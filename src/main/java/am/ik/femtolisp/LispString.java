package am.ik.femtolisp;

public record LispString(String value) implements LispVal {

	@Override
	public String print() {
		return "\"" + this.value + "\"";
	}

}
