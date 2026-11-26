package am.ik.jdbc.spin.postgres;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * An array of one dimension: one the host read, of the four element types it has cases
 * for ({@code int4}, {@code int8}, {@code numeric}, {@code text}), or one the program
 * made for a parameter, of any element type. It holds its elements; nothing of it is in
 * the server.
 */
final class SpinPostgresArray implements Array {

	// The elements' type as the server names it.
	private final String base;

	// The elements, of the class each is read as; null once freed.
	private @Nullable Object @Nullable [] elements;

	private SpinPostgresArray(String base, @Nullable Object[] elements) {
		this.base = base;
		this.elements = elements;
	}

	/**
	 * An array the program made: an element type the host has a case for under its own
	 * name ({@code integer} is {@code int4}), any other as the program wrote it.
	 */
	static SpinPostgresArray of(String typeName, Object[] elements) throws SQLException {
		String base = typeName.trim().toLowerCase(Locale.ROOT);
		base = switch (base) {
			case "int", "integer", "int4" -> "int4";
			case "bigint", "int8" -> "int8";
			case "decimal", "numeric" -> "numeric";
			case "character varying", "varchar" -> "varchar";
			default -> base;
		};
		// The name goes into the statement's text, after a cast.
		boolean named = !base.isEmpty();
		for (int i = 0; i < base.length(); i++) {
			char c = base.charAt(i);
			named &= Character.isLetterOrDigit(c) || c == '_' || c == ' ' || c == '.' || c == '"';
		}
		if (!named) {
			throw new SQLException("No type is named " + typeName, "42704");
		}
		return new SpinPostgresArray(base, elements.clone());
	}

	/** The array a value of the host's is, or null for a value that is no such array. */
	static @Nullable SpinPostgresArray read(Postgres.DbValue value) {
		if (value instanceof Postgres.DbValue.ArrayInt32 array) {
			return new SpinPostgresArray("int4", fill(array.value(), new Integer[array.value().size()], false));
		}
		if (value instanceof Postgres.DbValue.ArrayInt64 array) {
			return new SpinPostgresArray("int8", fill(array.value(), new Long[array.value().size()], false));
		}
		if (value instanceof Postgres.DbValue.ArrayDecimal array) {
			return new SpinPostgresArray("numeric", fill(array.value(), new BigDecimal[array.value().size()], true));
		}
		if (value instanceof Postgres.DbValue.ArrayStr array) {
			return new SpinPostgresArray("text", fill(array.value(), new String[array.value().size()], false));
		}
		return null;
	}

	private static @Nullable Object[] fill(List<? extends Optional<?>> elements, @Nullable Object[] read,
			boolean decimals) {
		for (int i = 0; i < read.length; i++) {
			Object element = elements.get(i).orElse(null);
			read[i] = decimals && element != null ? new BigDecimal((String) element) : element;
		}
		return read;
	}

	private @Nullable Object[] elements() throws SQLException {
		@Nullable Object[] held = this.elements;
		if (held == null) {
			throw new SQLException("The array was freed", "HY010");
		}
		return held;
	}

	/**
	 * The array as a parameter: the host's case of its element type when every element is
	 * of that type's class, else the texts of its elements, cast by the server.
	 */
	Postgres.ParameterValue parameter() throws SQLException {
		@Nullable Object[] held = elements();
		boolean integers = all(held, Integer.class, Short.class, Byte.class);
		boolean longs = all(held, Long.class, Integer.class, Short.class, Byte.class);
		if (this.base.equals("int4") && integers) {
			List<Optional<Integer>> numbers = new ArrayList<>();
			for (Object element : held) {
				numbers.add(element == null ? Optional.empty() : Optional.of(((Number) element).intValue()));
			}
			return new Postgres.ParameterValue.ArrayInt32(numbers);
		}
		if (this.base.equals("int8") && longs) {
			List<Optional<Long>> numbers = new ArrayList<>();
			for (Object element : held) {
				numbers.add(element == null ? Optional.empty() : Optional.of(((Number) element).longValue()));
			}
			return new Postgres.ParameterValue.ArrayInt64(numbers);
		}
		List<Optional<String>> texts = new ArrayList<>();
		for (Object element : held) {
			texts.add(element == null ? Optional.empty() : Optional.of(SpinPostgresPreparedStatement.text(element)));
		}
		if (this.base.equals("numeric")
				&& all(held, BigDecimal.class, BigInteger.class, Long.class, Integer.class, Short.class, Byte.class)) {
			return new Postgres.ParameterValue.ArrayDecimal(texts);
		}
		return new Postgres.ParameterValue.ArrayStr(texts);
	}

	/** The type the parameter {@link #parameter()} made is cast to. */
	String cast(Postgres.ParameterValue parameter) {
		return switch (parameter) {
			case Postgres.ParameterValue.ArrayInt32 p -> "int4[]";
			case Postgres.ParameterValue.ArrayInt64 p -> "int8[]";
			case Postgres.ParameterValue.ArrayDecimal p -> "numeric[]";
			default -> this.base.equals("text") || this.base.equals("varchar") ? this.base + "[]"
					: "text[]::" + this.base + "[]";
		};
	}

	// Whether every element that is not null is of one of the classes.
	private static boolean all(@Nullable Object[] elements, Class<?>... classes) {
		for (Object element : elements) {
			boolean of = element == null;
			for (Class<?> given : classes) {
				of |= element != null && element.getClass() == given;
			}
			if (!of) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String getBaseTypeName() throws SQLException {
		elements();
		return this.base;
	}

	@Override
	public int getBaseType() throws SQLException {
		elements();
		return Values.jdbc(this.base);
	}

	@Override
	public Object getArray() throws SQLException {
		return elements().clone();
	}

	@Override
	public Object getArray(@Nullable Map<String, Class<?>> map) throws SQLException {
		mapped(map);
		return getArray();
	}

	/**
	 * The elements from the one at {@code index}, counted from 1, at most {@code count}.
	 */
	@Override
	public Object getArray(long index, int count) throws SQLException {
		@Nullable Object[] held = elements();
		int from = from(held, index, count);
		return Arrays.copyOfRange(held, from, from + Math.min(count, held.length - from));
	}

	@Override
	public Object getArray(long index, int count, @Nullable Map<String, Class<?>> map) throws SQLException {
		mapped(map);
		return getArray(index, count);
	}

	/** A row an element: its index, counted from 1, then its value. */
	@Override
	public ResultSet getResultSet() throws SQLException {
		return getResultSet(1, elements().length);
	}

	@Override
	public ResultSet getResultSet(@Nullable Map<String, Class<?>> map) throws SQLException {
		mapped(map);
		return getResultSet();
	}

	@Override
	public ResultSet getResultSet(long index, int count) throws SQLException {
		@Nullable Object[] held = elements();
		int from = from(held, index, count);
		Postgres.DbDataType type = switch (this.base) {
			case "int4" -> new Postgres.DbDataType.Int32();
			case "int8" -> new Postgres.DbDataType.Int64();
			case "numeric" -> new Postgres.DbDataType.Decimal();
			default -> new Postgres.DbDataType.Str();
		};
		List<List<Postgres.DbValue>> rows = new ArrayList<>();
		for (int i = from; i < from + Math.min(count, held.length - from); i++) {
			@Nullable Object element = held[i];
			Postgres.DbValue value = element == null ? new Postgres.DbValue.DbNull() : switch (type) {
				case Postgres.DbDataType.Int32 t -> new Postgres.DbValue.Int32(((Number) element).intValue());
				case Postgres.DbDataType.Int64 t -> new Postgres.DbValue.Int64(((Number) element).longValue());
				case Postgres.DbDataType.Decimal t -> new Postgres.DbValue.Decimal(
						element instanceof BigDecimal decimal ? decimal.toPlainString() : element.toString());
				default -> new Postgres.DbValue.Str(SpinPostgresPreparedStatement.text(element));
			};
			rows.add(List.of(new Postgres.DbValue.Int32(i + 1), value));
		}
		return new SpinPostgresResultSet(null,
				new Postgres.RowSet(List.of(new Postgres.Column("INDEX", new Postgres.DbDataType.Int32()),
						new Postgres.Column("VALUE", type)), rows),
				ResultSet.TYPE_SCROLL_INSENSITIVE);
	}

	@Override
	public ResultSet getResultSet(long index, int count, @Nullable Map<String, Class<?>> map) throws SQLException {
		mapped(map);
		return getResultSet(index, count);
	}

	private static int from(@Nullable Object[] elements, long index, int count) throws SQLException {
		if (index < 1 || index > elements.length + 1L || count < 0) {
			throw new SQLException(
					"The array of " + elements.length + " elements has none from " + index + " (" + count + ")",
					"2202E");
		}
		return (int) index - 1;
	}

	private static void mapped(@Nullable Map<String, Class<?>> map) throws SQLException {
		if (map != null && !map.isEmpty()) {
			throw Failures.unsupported("An array read through a type map");
		}
	}

	@Override
	public void free() {
		this.elements = null;
	}

}
