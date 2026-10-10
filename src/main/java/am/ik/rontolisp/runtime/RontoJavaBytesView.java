package am.ik.rontolisp.runtime;

/**
 * What {@code (java:view value octets :bytes)} makes: the {@code byte[]} an
 * {@code (unsigned-byte 8)} vector is to Java. Java never sees the object itself:
 * wherever a {@code byte[]} fits ({@code byte[]}, {@code Object}, {@code Cloneable},
 * {@code Serializable}) it is handed {@link #bytes()}, and nowhere else does the view
 * convert. An octet vector is a {@code byte[]} of its own -- the interpreter's and a
 * compiled program's alike -- and Java is handed that very array, so what either side
 * stores the other reads, during the call and through any reference Java keeps.
 */
public final class RontoJavaBytesView {

	private final Object value;

	private final byte[] octets;

	/**
	 * @param value the Lisp value the view stands for
	 * @param octets the vector's octets
	 */
	public RontoJavaBytesView(Object value, byte[] octets) {
		this.value = value;
		this.octets = octets;
	}

	/**
	 * @return the Lisp value the view stands for
	 */
	public Object value() {
		return this.value;
	}

	/**
	 * @return the {@code byte[]} Java is handed: the vector's own octets
	 */
	public byte[] bytes() {
		return this.octets;
	}

}
