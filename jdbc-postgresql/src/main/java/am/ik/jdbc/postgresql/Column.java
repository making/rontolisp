package am.ik.jdbc.postgresql;

/**
 * One field of a RowDescription message.
 */
final class Column {

	final String name;

	/** The table the column is of, by its object identifier; 0 for an expression. */
	final int table;

	/** The column's number in that table. */
	final int attribute;

	/** The type's object identifier. */
	final int type;

	/** The type's size in octets, negative for a varying one. */
	final int size;

	/** The type's modifier: a declared length or precision, -1 for none. */
	final int modifier;

	Column(String name, int table, int attribute, int type, int size, int modifier) {
		this.name = name;
		this.table = table;
		this.attribute = attribute;
		this.type = type;
		this.size = size;
		this.modifier = modifier;
	}

}
