package am.ik.jdbc.spin.postgres;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.List;

/**
 * The parameters of a prepared statement as the server read them: how many, and the type
 * it took each for, by its name.
 */
final class SpinPostgresParameterMetaData implements ParameterMetaData {

	private final List<String> types;

	SpinPostgresParameterMetaData(List<String> types) {
		this.types = types;
	}

	private String type(int parameter) throws SQLException {
		if (parameter < 1 || parameter > this.types.size()) {
			throw new SQLException("The statement has no parameter " + parameter, "22023");
		}
		return this.types.get(parameter - 1);
	}

	@Override
	public int getParameterCount() throws SQLException {
		return this.types.size();
	}

	@Override
	public int getParameterType(int parameter) throws SQLException {
		return Values.jdbc(type(parameter));
	}

	@Override
	public String getParameterTypeName(int parameter) throws SQLException {
		return type(parameter);
	}

	@Override
	public String getParameterClassName(int parameter) throws SQLException {
		return Values.className(type(parameter));
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
			case "int2", "int4", "int8", "float4", "float8", "numeric" -> true;
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
