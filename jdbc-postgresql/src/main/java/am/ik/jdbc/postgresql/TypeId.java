package am.ik.jdbc.postgresql;

import java.sql.Types;

import org.jspecify.annotations.Nullable;

/**
 * The object identifiers of the server's built-in types the driver tells apart
 * (PostgreSQL's catalog {@code pg_type}; the numbers are fixed), and what each is to
 * JDBC.
 */
final class TypeId {

	static final int UNSPECIFIED = 0;

	static final int BOOL = 16;

	static final int BYTEA = 17;

	static final int CHAR = 18;

	static final int NAME = 19;

	static final int INT8 = 20;

	static final int INT2 = 21;

	static final int INT4 = 23;

	static final int TEXT = 25;

	static final int OID = 26;

	static final int JSON = 114;

	static final int XML = 142;

	static final int FLOAT4 = 700;

	static final int FLOAT8 = 701;

	static final int UNKNOWN = 705;

	static final int BPCHAR = 1042;

	static final int VARCHAR = 1043;

	static final int DATE = 1082;

	static final int TIME = 1083;

	static final int TIMESTAMP = 1114;

	static final int TIMESTAMPTZ = 1184;

	static final int TIMETZ = 1266;

	static final int NUMERIC = 1700;

	static final int UUID = 2950;

	static final int JSONB = 3802;

	private TypeId() {
	}

	/**
	 * The constant of {@link Types} a type is read as: the one of what {@code getObject}
	 * answers.
	 */
	static int jdbc(int type) {
		return switch (type) {
			case BOOL -> Types.BOOLEAN;
			case BYTEA -> Types.BINARY;
			case CHAR, BPCHAR -> Types.CHAR;
			case NAME, TEXT, VARCHAR, UNKNOWN -> Types.VARCHAR;
			case INT8, OID -> Types.BIGINT;
			case INT2 -> Types.SMALLINT;
			case INT4 -> Types.INTEGER;
			case FLOAT4 -> Types.REAL;
			case FLOAT8 -> Types.DOUBLE;
			case NUMERIC -> Types.NUMERIC;
			case DATE -> Types.DATE;
			// A time with a time zone is read as the class of java.sql, an instant on the
			// program's wall, so its constant is the one that class goes with.
			case TIME, TIMETZ -> Types.TIME;
			case TIMESTAMP, TIMESTAMPTZ -> Types.TIMESTAMP;
			case XML -> Types.SQLXML;
			default -> Types.OTHER;
		};
	}

	/** The type a parameter of a constant of {@link Types} is sent as. */
	static int of(int jdbc) {
		return switch (jdbc) {
			case Types.BOOLEAN, Types.BIT -> BOOL;
			case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> BYTEA;
			case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
				VARCHAR;
			case Types.BIGINT -> INT8;
			case Types.TINYINT, Types.SMALLINT -> INT2;
			case Types.INTEGER -> INT4;
			case Types.REAL -> FLOAT4;
			case Types.FLOAT, Types.DOUBLE -> FLOAT8;
			case Types.NUMERIC, Types.DECIMAL -> NUMERIC;
			case Types.DATE -> DATE;
			case Types.TIME -> TIME;
			case Types.TIME_WITH_TIMEZONE -> TIMETZ;
			case Types.TIMESTAMP -> TIMESTAMP;
			case Types.TIMESTAMP_WITH_TIMEZONE -> TIMESTAMPTZ;
			default -> UNSPECIFIED;
		};
	}

	/**
	 * The server's name of a type, or {@code null} for one the driver does not tell
	 * apart.
	 */
	static @Nullable String name(int type) {
		return switch (type) {
			case BOOL -> "bool";
			case BYTEA -> "bytea";
			case CHAR -> "char";
			case NAME -> "name";
			case INT8 -> "int8";
			case INT2 -> "int2";
			case INT4 -> "int4";
			case TEXT -> "text";
			case OID -> "oid";
			case JSON -> "json";
			case XML -> "xml";
			case FLOAT4 -> "float4";
			case FLOAT8 -> "float8";
			case UNKNOWN -> "unknown";
			case BPCHAR -> "bpchar";
			case VARCHAR -> "varchar";
			case DATE -> "date";
			case TIME -> "time";
			case TIMESTAMP -> "timestamp";
			case TIMESTAMPTZ -> "timestamptz";
			case TIMETZ -> "timetz";
			case NUMERIC -> "numeric";
			case UUID -> "uuid";
			case JSONB -> "jsonb";
			default -> null;
		};
	}

	/** The name of the class {@code getObject} answers for a type. */
	static String className(int type) {
		return switch (type) {
			case BOOL -> "java.lang.Boolean";
			case BYTEA -> "[B";
			case INT8, OID -> "java.lang.Long";
			case INT2, INT4 -> "java.lang.Integer";
			case FLOAT4 -> "java.lang.Float";
			case FLOAT8 -> "java.lang.Double";
			case NUMERIC -> "java.math.BigDecimal";
			case DATE -> "java.sql.Date";
			case TIME, TIMETZ -> "java.sql.Time";
			case TIMESTAMP, TIMESTAMPTZ -> "java.sql.Timestamp";
			case UUID -> "java.util.UUID";
			default -> "java.lang.String";
		};
	}

}
