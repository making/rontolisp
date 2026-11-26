package am.ik.rontolisp.wit;

/**
 * A WIT {@code result<T, E>} as a value, wherever the WIT has one in a {@link WitImport}
 * interface or in the world of a {@link WitExport} class: a parameter, a list's element,
 * an option's value, a record's field, a variant's payload, a function's answer. An arm
 * carrying nothing is {@code Void}, its value {@code null}: {@code result<_, E>} is
 * {@code WitResult<Void, E>}. A function's answer may instead be written as its ok arm,
 * the error arm thrown as a {@link WitException}.
 *
 * @param <T> the Java type of the ok arm
 * @param <E> the Java type of the error arm
 */
public sealed interface WitResult<T, E> {

	/**
	 * The ok arm.
	 *
	 * @param <T> the Java type of the ok arm
	 * @param <E> the Java type of the error arm
	 * @param value the value the arm carries, {@code null} for an arm carrying nothing
	 */
	record Ok<T, E>(T value) implements WitResult<T, E> {
	}

	/**
	 * The error arm.
	 *
	 * @param <T> the Java type of the ok arm
	 * @param <E> the Java type of the error arm
	 * @param error the error the arm carries, {@code null} for an arm carrying nothing
	 */
	record Err<T, E>(E error) implements WitResult<T, E> {
	}

}
