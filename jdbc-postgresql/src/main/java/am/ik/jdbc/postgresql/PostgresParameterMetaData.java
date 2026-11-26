package am.ik.jdbc.postgresql;

import java.sql.ParameterMetaData;
import java.sql.SQLException;

/**
 * The parameters of a prepared statement as the server described them: how many, and the
 * type it took each for.
 */
final class PostgresParameterMetaData implements ParameterMetaData {

	private final PostgresConnection connection;

	private final int[] types;

	PostgresParameterMetaData(PostgresConnection connection, int[] types) {
		this.connection = connection;
		this.types = types;
	}

	private int type(int parameter) throws SQLException {
		if (parameter < 1 || parameter > this.types.length) {
			throw new SQLException("The statement has no parameter " + parameter, "22023");
		}
		return this.types[parameter - 1];
	}

	@Override
	public int getParameterCount() throws SQLException {
		return this.types.length;
	}

	@Override
	public int getParameterType(int parameter) throws SQLException {
		return TypeId.jdbc(type(parameter));
	}

	@Override
	public String getParameterTypeName(int parameter) throws SQLException {
		return this.connection.typeName(type(parameter));
	}

	@Override
	public String getParameterClassName(int parameter) throws SQLException {
		return TypeId.className(type(parameter));
	}

	@Override
	public int getParameterMode(int parameter) throws SQLException {
		type(parameter);
		return parameterModeIn;
	}

	@Override
	public int isNullable(int parameter) throws SQLException {
		type(parameter);
		return parameterNullableUnknown;
	}

	@Override
	public boolean isSigned(int parameter) throws SQLException {
		return switch (type(parameter)) {
			case TypeId.INT2, TypeId.INT4, TypeId.INT8, TypeId.FLOAT4, TypeId.FLOAT8, TypeId.NUMERIC -> true;
			default -> false;
		};
	}

	@Override
	public int getPrecision(int parameter) throws SQLException {
		type(parameter);
		return 0;
	}

	@Override
	public int getScale(int parameter) throws SQLException {
		type(parameter);
		return 0;
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
