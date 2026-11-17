package am.ik.rontolisp.java;

import java.math.BigInteger;

import org.jspecify.annotations.Nullable;

/**
 * A token of the Java lexer.
 *
 * @param kind what it is
 * @param text its spelling: an identifier or keyword, an operator, a string literal's
 * value
 * @param integer an integer literal's magnitude, or {@code null}
 * @param decimal whether an integer literal is written in base ten (the others may fill
 * the sign bit)
 * @param number a floating-point literal's value, or a character literal's code
 * @param offset where it starts in the source
 */
record JavaToken(Kind kind, String text, @Nullable BigInteger integer, boolean decimal, double number, int offset) {

	enum Kind {

		IDENTIFIER, INT, LONG, FLOAT, DOUBLE, CHAR, STRING, OPERATOR, END

	}

	static JavaToken of(Kind kind, String text, int offset) {
		return new JavaToken(kind, text, null, false, 0, offset);
	}

	boolean is(String spelling) {
		return (this.kind == Kind.OPERATOR || this.kind == Kind.IDENTIFIER) && this.text.equals(spelling);
	}

	/** What an error names the token as. */
	String shown() {
		return switch (this.kind) {
			case END -> "the end of the file";
			case STRING -> "a string literal";
			case CHAR -> "a character literal";
			case INT, LONG, FLOAT, DOUBLE -> "a number";
			default -> "'" + this.text + "'";
		};
	}

}
