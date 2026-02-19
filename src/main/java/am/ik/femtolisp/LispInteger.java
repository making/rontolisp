package am.ik.femtolisp;

public record LispInteger(long value) implements LispVal {

	@Override
	public String print() {
		return Long.toString(this.value);
	}

}
