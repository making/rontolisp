package am.ik.jvm;

/**
 * JVM array type constants used in the {@code newarray} instruction.
 */
public interface ArrayType {

	/** Array type constant for {@code boolean} arrays. */
	int T_BOOLEAN = 4;

	/** Array type constant for {@code char} arrays. */
	int T_CHAR = 5;

	/** Array type constant for {@code float} arrays. */
	int T_FLOAT = 6;

	/** Array type constant for {@code double} arrays. */
	int T_DOUBLE = 7;

	/** Array type constant for {@code byte} arrays. */
	int T_BYTE = 8;

	/** Array type constant for {@code short} arrays. */
	int T_SHORT = 9;

	/** Array type constant for {@code int} arrays. */
	int T_INT = 10;

	/** Array type constant for {@code long} arrays. */
	int T_LONG = 11;

}
