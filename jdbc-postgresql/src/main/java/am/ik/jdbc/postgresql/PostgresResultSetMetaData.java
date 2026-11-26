package am.ik.jdbc.postgresql;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * What a RowDescription tells of a result's columns: the label, the type, and what the
 * type's modifier declares; of a column that is a table's, the catalog is asked once for
 * the table, its schema, and whether the column is NOT NULL or numbered by the server. A
 * result is of one database, whose name is not a column's catalog: that is answered
 * empty.
 */
final class PostgresResultSetMetaData implements ResultSetMetaData {

	private final PostgresConnection connection;

	private final Column[] columns;

	// What the catalog tells of each column that is a table's, asked once.
	private @Nullable Attribute @Nullable [] attributes;

	PostgresResultSetMetaData(PostgresConnection connection, Column[] columns) {
		this.connection = connection;
		this.columns = columns;
	}

	/** A column of a table: the table's schema and name, and what its definition says. */
	private static final class Attribute {

		final String schema;

		final String table;

		final boolean notNull;

		// Numbered by the server: an identity column, or one whose default is a
		// sequence's
		// next value (serial).
		final boolean numbered;

		Attribute(String schema, String table, boolean notNull, boolean numbered) {
			this.schema = schema;
			this.table = table;
			this.notNull = notNull;
			this.numbered = numbered;
		}

	}

	// The table column a result's column is, or null for an expression.
	private @Nullable Attribute attribute(int column) throws SQLException {
		Column described = column(column);
		if (described.table == 0) {
			return null;
		}
		@Nullable Attribute[] known = this.attributes;
		if (known == null) {
			known = new Attribute[this.columns.length];
			StringBuilder sql = new StringBuilder("SELECT a.attrelid, a.attnum, n.nspname, c.relname, a.attnotnull,"
					+ " a.attidentity <> '' OR coalesce(pg_catalog.pg_get_expr(d.adbin, d.adrelid) LIKE 'nextval(%', false)"
					+ " FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid = a.attrelid"
					+ " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
					+ " LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum"
					+ " WHERE (a.attrelid, a.attnum) IN (");
			String separator = "";
			for (Column each : this.columns) {
				if (each.table != 0) {
					sql.append(separator)
						.append("('")
						.append(Integer.toUnsignedString(each.table))
						.append("'::oid, ")
						.append(each.attribute)
						.append("::int2)");
					separator = ", ";
				}
			}
			try (ResultSet rows = this.connection.query(sql.append(')').toString())) {
				while (rows.next()) {
					int table = (int) rows.getLong(1);
					int number = rows.getInt(2);
					for (int i = 0; i < this.columns.length; i++) {
						if (this.columns[i].table == table && this.columns[i].attribute == number) {
							known[i] = new Attribute(Objects.requireNonNull(rows.getString(3)),
									Objects.requireNonNull(rows.getString(4)), rows.getBoolean(5), rows.getBoolean(6));
						}
					}
				}
			}
			this.attributes = known;
		}
		return known[column - 1];
	}

	private Column column(int column) throws SQLException {
		if (column < 1 || column > this.columns.length) {
			throw new SQLException("The result set has no column " + column, "42703");
		}
		return this.columns[column - 1];
	}

	@Override
	public int getColumnCount() throws SQLException {
		return this.columns.length;
	}

	@Override
	public String getColumnLabel(int column) throws SQLException {
		return column(column).name;
	}

	@Override
	public String getColumnName(int column) throws SQLException {
		return column(column).name;
	}

	@Override
	public int getColumnType(int column) throws SQLException {
		return TypeId.jdbc(column(column).type);
	}

	@Override
	public String getColumnTypeName(int column) throws SQLException {
		return this.connection.typeName(column(column).type);
	}

	@Override
	public String getColumnClassName(int column) throws SQLException {
		return TypeId.className(column(column).type);
	}

	@Override
	public int getPrecision(int column) throws SQLException {
		Column described = column(column);
		return precision(described.type, described.modifier);
	}

	/**
	 * The digits of a type's largest value; for a date or a time the characters of its
	 * ISO text with every fractional digit (PostgreSQL documentation, "Numeric Types",
	 * "Date/Time Types").
	 * @param modifier the declared length or precision, -1 for none
	 */
	static int precision(int type, int modifier) {
		return switch (type) {
			case TypeId.INT2 -> 5;
			case TypeId.INT4 -> 10;
			case TypeId.INT8 -> 19;
			case TypeId.FLOAT4 -> 6;
			case TypeId.FLOAT8 -> 15;
			case TypeId.BOOL -> 1;
			case TypeId.NUMERIC -> modifier < 0 ? 0 : ((modifier - 4) >> 16) & 0xffff;
			case TypeId.BPCHAR, TypeId.VARCHAR -> modifier < 0 ? Integer.MAX_VALUE : modifier - 4;
			case TypeId.DATE -> 10;
			case TypeId.TIME -> 15;
			case TypeId.TIMETZ -> 21;
			case TypeId.TIMESTAMP -> 26;
			case TypeId.TIMESTAMPTZ -> 32;
			default -> Integer.MAX_VALUE;
		};
	}

	@Override
	public int getScale(int column) throws SQLException {
		Column described = column(column);
		return scale(described.type, described.modifier);
	}

	/** The digits after the point of a type: of a number, or of a time's seconds. */
	static int scale(int type, int modifier) {
		return switch (type) {
			case TypeId.NUMERIC -> modifier < 0 ? 0 : (modifier - 4) & 0xffff;
			case TypeId.TIME, TypeId.TIMETZ, TypeId.TIMESTAMP, TypeId.TIMESTAMPTZ -> modifier < 0 ? 6 : modifier;
			default -> 0;
		};
	}

	@Override
	public int getColumnDisplaySize(int column) throws SQLException {
		Column described = column(column);
		return switch (described.type) {
			case TypeId.INT2 -> 6;
			case TypeId.INT4 -> 11;
			case TypeId.INT8 -> 20;
			// A sign, the digits that tell the value apart, a point and an exponent.
			case TypeId.FLOAT4 -> 15;
			case TypeId.FLOAT8 -> 24;
			case TypeId.NUMERIC -> described.modifier < 0 ? Integer.MAX_VALUE : getPrecision(column) + 2;
			default -> getPrecision(column);
		};
	}

	@Override
	public boolean isSigned(int column) throws SQLException {
		return switch (column(column).type) {
			case TypeId.INT2, TypeId.INT4, TypeId.INT8, TypeId.FLOAT4, TypeId.FLOAT8, TypeId.NUMERIC -> true;
			default -> false;
		};
	}

	@Override
	public boolean isCaseSensitive(int column) throws SQLException {
		return switch (column(column).type) {
			case TypeId.CHAR, TypeId.NAME, TypeId.TEXT, TypeId.BPCHAR, TypeId.VARCHAR, TypeId.JSON, TypeId.JSONB,
					TypeId.XML ->
				true;
			default -> false;
		};
	}

	@Override
	public boolean isAutoIncrement(int column) throws SQLException {
		Attribute attribute = attribute(column);
		return attribute != null && attribute.numbered;
	}

	@Override
	public boolean isSearchable(int column) throws SQLException {
		column(column);
		return true;
	}

	@Override
	public boolean isCurrency(int column) throws SQLException {
		column(column);
		return false;
	}

	@Override
	public int isNullable(int column) throws SQLException {
		Attribute attribute = attribute(column);
		return attribute == null ? columnNullableUnknown : attribute.notNull ? columnNoNulls : columnNullable;
	}

	@Override
	public String getSchemaName(int column) throws SQLException {
		Attribute attribute = attribute(column);
		return attribute == null ? "" : attribute.schema;
	}

	@Override
	public String getTableName(int column) throws SQLException {
		Attribute attribute = attribute(column);
		return attribute == null ? "" : attribute.table;
	}

	@Override
	public String getCatalogName(int column) throws SQLException {
		column(column);
		return "";
	}

	@Override
	public boolean isReadOnly(int column) throws SQLException {
		column(column);
		return false;
	}

	@Override
	public boolean isWritable(int column) throws SQLException {
		column(column);
		return true;
	}

	@Override
	public boolean isDefinitelyWritable(int column) throws SQLException {
		column(column);
		return false;
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return iface.isInstance(this);
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this)) {
			return iface.cast(this);
		}
		throw Failures.unsupported("An unwrap to " + iface.getName());
	}

}
