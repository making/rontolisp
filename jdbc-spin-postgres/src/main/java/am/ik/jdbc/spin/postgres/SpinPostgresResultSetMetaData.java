package am.ik.jdbc.spin.postgres;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * What the host tells of a result's columns: the label and the type, as one of the cases
 * it converts or a name. It tells no table, no declared length and no precision: a size
 * is the type's, a column's table and schema are answered empty, and whether a column is
 * nullable is not known.
 */
final class SpinPostgresResultSetMetaData implements ResultSetMetaData {

	private final List<Postgres.Column> columns;

	SpinPostgresResultSetMetaData(List<Postgres.Column> columns) {
		this.columns = columns;
	}

	private Postgres.Column column(int column) throws SQLException {
		if (column < 1 || column > this.columns.size()) {
			throw new SQLException("The result set has no column " + column, "42703");
		}
		return this.columns.get(column - 1);
	}

	@Override
	public int getColumnCount() throws SQLException {
		return this.columns.size();
	}

	@Override
	public String getColumnLabel(int column) throws SQLException {
		return column(column).name();
	}

	@Override
	public String getColumnName(int column) throws SQLException {
		return column(column).name();
	}

	@Override
	public int getColumnType(int column) throws SQLException {
		return Values.jdbc(column(column).dataType());
	}

	@Override
	public String getColumnTypeName(int column) throws SQLException {
		return Values.name(column(column).dataType());
	}

	@Override
	public String getColumnClassName(int column) throws SQLException {
		return Values.className(column(column).dataType());
	}

	/**
	 * The digits of a type's largest value; for a date or a time the characters of its
	 * ISO text with every fractional digit (PostgreSQL documentation, "Numeric Types",
	 * "Date/Time Types"). A numeric's and a text's are not told: 0 and the largest.
	 */
	@Override
	public int getPrecision(int column) throws SQLException {
		return precision(Values.name(column(column).dataType()), -1);
	}

	/**
	 * The precision of a type by its name and its modifier ({@code atttypmod}, -1 for
	 * none): a numeric's declared digits and a character type's declared length, which
	 * the catalog tells and a result's column does not.
	 */
	static int precision(String type, int modifier) {
		return switch (type) {
			case "int2" -> 5;
			case "int4" -> 10;
			case "int8" -> 19;
			case "float4" -> 6;
			case "float8" -> 15;
			case "bool" -> 1;
			case "numeric" -> modifier < 0 ? 0 : ((modifier - 4) >> 16) & 0xffff;
			case "bpchar", "varchar" -> modifier < 0 ? Integer.MAX_VALUE : modifier - 4;
			case "date" -> 10;
			case "time" -> 15;
			case "timetz" -> 21;
			case "timestamp" -> 26;
			case "timestamptz" -> 32;
			default -> Integer.MAX_VALUE;
		};
	}

	/** The digits after the point of a time's seconds; a numeric's are not told. */
	@Override
	public int getScale(int column) throws SQLException {
		return scale(Values.name(column(column).dataType()), -1);
	}

	/** The digits after the point of a type: of a number, or of a time's seconds. */
	static int scale(String type, int modifier) {
		return switch (type) {
			case "numeric" -> modifier < 0 ? 0 : (modifier - 4) & 0xffff;
			case "time", "timetz", "timestamp", "timestamptz" -> modifier < 0 ? 6 : modifier;
			default -> 0;
		};
	}

	@Override
	public int getColumnDisplaySize(int column) throws SQLException {
		return switch (column(column).dataType()) {
			case Postgres.DbDataType.Int16() -> 6;
			case Postgres.DbDataType.Int32() -> 11;
			case Postgres.DbDataType.Int64() -> 20;
			// A sign, the digits that tell the value apart, a point and an exponent.
			case Postgres.DbDataType.Floating32() -> 15;
			case Postgres.DbDataType.Floating64() -> 24;
			case Postgres.DbDataType.Decimal() -> Integer.MAX_VALUE;
			default -> getPrecision(column);
		};
	}

	@Override
	public boolean isSigned(int column) throws SQLException {
		return switch (getColumnType(column)) {
			case Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.DOUBLE, Types.NUMERIC -> true;
			default -> false;
		};
	}

	@Override
	public boolean isCaseSensitive(int column) throws SQLException {
		return switch (column(column).dataType()) {
			case Postgres.DbDataType.Str() -> true;
			case Postgres.DbDataType.Jsonb() -> true;
			case Postgres.DbDataType.Other(String name) ->
				name.equals("char") || name.equals("name") || name.equals("json") || name.equals("xml");
			default -> false;
		};
	}

	@Override
	public boolean isAutoIncrement(int column) throws SQLException {
		column(column);
		return false;
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
		column(column);
		return columnNullableUnknown;
	}

	@Override
	public String getSchemaName(int column) throws SQLException {
		column(column);
		return "";
	}

	@Override
	public String getTableName(int column) throws SQLException {
		column(column);
		return "";
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
