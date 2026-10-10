package am.ik.rontolisp.runtime;

import java.util.Arrays;

/**
 * What {@code (java:view value octets :bytes)} makes: the {@code byte[]} an
 * {@code (unsigned-byte 8)} vector is to Java. Java never sees the object itself:
 * wherever a {@code byte[]} fits ({@code byte[]}, {@code Object}, {@code Cloneable},
 * {@code Serializable}) it is handed {@link #bytes()}, and nowhere else does the view
 * convert. The interpreter's octets are a {@code byte[]} of their own, which Java is
 * handed itself, so what either side stores the other reads; a compiled program's are a
 * {@code byte[]} after the width in slot 0, which Java is handed a copy of, written back
 * into the vector when the call that handed it returns ({@link #changed},
 * {@link #store}).
 */
public final class RontoJavaBytesView {

	private final Object value;

	private final byte[] storage;

	private final int offset;

	/**
	 * @param value the Lisp value the view stands for
	 * @param storage the array the octets are stored in
	 * @param offset where the first octet is: 0 when the storage is the octets
	 * themselves, 1 after a compiled program's width
	 */
	public RontoJavaBytesView(Object value, byte[] storage, int offset) {
		this.value = value;
		this.storage = storage;
		this.offset = offset;
	}

	/**
	 * @return the Lisp value the view stands for
	 */
	public Object value() {
		return this.value;
	}

	/**
	 * @return the {@code byte[]} Java is handed: the storage itself when it holds only
	 * the octets, else a fresh copy of them
	 */
	public byte[] bytes() {
		return this.offset == 0 ? this.storage : Arrays.copyOfRange(this.storage, this.offset, this.storage.length);
	}

	/**
	 * Whether two arguments of one call are views of one vector, which Java is then
	 * handed one array for, as the oracle's one {@code byte[]}.
	 * @param one an argument
	 * @param other another argument
	 * @return whether both are views over the same storage
	 */
	public static boolean shared(Object one, Object other) {
		return one instanceof RontoJavaBytesView view && other instanceof RontoJavaBytesView that
				&& view.storage == that.storage;
	}

	/**
	 * Whether Java stored into the copy {@link #bytes()} handed it: its octets differ
	 * from the vector's. Asked of every argument before any is written back, so a vector
	 * a call was handed twice takes what Java stored, not the other copy.
	 * @param value an argument a call was handed
	 * @param handed what the call handed Java for it
	 * @return whether the argument is a view whose copy Java changed
	 */
	public static boolean changed(Object value, Object handed) {
		if (!(value instanceof RontoJavaBytesView view) || !(handed instanceof byte[] copy) || copy == view.storage) {
			return false;
		}
		int length = view.storage.length - view.offset;
		return copy.length != length || !Arrays.equals(copy, 0, length, view.storage, view.offset, view.storage.length);
	}

	/**
	 * Writes what Java stored into the copy {@link #bytes()} handed it back into the
	 * vector.
	 * @param value an argument a call was handed
	 * @param handed what the call handed Java for it
	 */
	public static void store(Object value, Object handed) {
		if (value instanceof RontoJavaBytesView view && handed instanceof byte[] copy && copy != view.storage) {
			System.arraycopy(copy, 0, view.storage, view.offset,
					Math.min(copy.length, view.storage.length - view.offset));
		}
	}

}
