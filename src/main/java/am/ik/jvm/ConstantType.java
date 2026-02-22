package am.ik.jvm;

/**
 * JVM constant pool entry types as defined in the JVM specification.
 */
public enum ConstantType {

	/** Class reference constant (tag 7). */
	CLASS(7),

	/** Field reference constant (tag 9). */
	FIELDREF(9),

	/** Method reference constant (tag 10). */
	METHODREF(10),

	/** Interface method reference constant (tag 11). */
	INTERFACE_METHODREF(11),

	/** String constant (tag 8). */
	STRING(8),

	/** Integer constant (tag 3). */
	INTEGER(3),

	/** Float constant (tag 4). */
	FLOAT(4),

	/** Long constant (tag 5). */
	LONG(5),

	/** Double constant (tag 6). */
	DOUBLE(6),

	/** Name and type descriptor constant (tag 12). */
	NAME_AND_TYPE(12),

	/** UTF-8 string constant (tag 1). */
	UTF8(1),

	/** Method handle constant (tag 15). */
	METHOD_HANDLE(15),

	/** Method type constant (tag 16). */
	METHOD_TYPE(16),

	/** Invoke dynamic constant (tag 18). */
	INVOKE_DYNAMIC(18);

	private final int value;

	ConstantType(int value) {
		this.value = value;
	}

	/**
	 * Return the tag value for this constant type.
	 * @return the tag value
	 */
	public int value() {
		return value;
	}

}
