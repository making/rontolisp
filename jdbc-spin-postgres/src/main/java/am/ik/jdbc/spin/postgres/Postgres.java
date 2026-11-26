package am.ik.jdbc.spin.postgres;

import java.util.List;
import java.util.Optional;

import am.ik.rontolisp.wit.WitFuture;
import am.ik.rontolisp.wit.WitImport;
import am.ik.rontolisp.wit.WitStream;

/**
 * The WIT interface {@code spin:postgres/postgres} as the driver calls it: the host owns
 * the connection -- it opens it, pools it, speaks PostgreSQL's protocol and, when the
 * address asks for it, TLS. In a WebAssembly component rontolisp compiles the host is its
 * implementation (Spin 4.1.0 or later); under a JDK, and on rontolisp's interpreter and
 * JVM, the first service provider listed in {@code META-INF/services}, and the driver
 * takes no URL where there is none.
 *
 * <p>
 * What the driver calls is declared -- {@code connection.open}, the connection builder,
 * {@code query}, {@code query-async} and {@code execute} -- and the other {@code async}
 * members are left out. The types those reach are declared whole, since the component
 * model checks the types an import carries against the host's: a variant is a sealed
 * interface whose records are its cases, a WIT tuple a record of the same components.
 * {@code query-async} is a call that waits for the columns; its rows are a stream the
 * host writes as the server sends them, its end a future carrying the statement's
 * refusal.
 */
@WitImport(value = "spin:postgres/postgres@4.2.0", wit = "wit/postgres.wit")
interface Postgres {

	// [static]connection.open: Spin's pooled connection to `address`
	// (postgres://user:password@host:port/database?sslmode=...)
	Connection connectionOpen(String address);

	// [constructor]connection-builder
	ConnectionBuilder connectionBuilderNew(String address);

	interface ConnectionBuilder extends AutoCloseable {

		// a PEM certificate the server's chain is verified against, beside the host's
		void setCaRoot(String certificate);

		Connection build();

		@Override
		void close();

	}

	interface Connection extends AutoCloseable {

		RowSet query(String statement, List<ParameterValue> params);

		// the rows as the server sends them, the future their end
		QueryAsync queryAsync(String statement, List<ParameterValue> params);

		long execute(String statement, List<ParameterValue> params);

		@Override
		void close();

	}

	record RowSet(List<Column> columns, List<List<DbValue>> rows) {
	}

	// query-async's tuple: the columns, the rows (a row a list of values), and the end,
	// whose error arm is the statement's refusal
	record QueryAsync(List<Column> columns, WitStream<List<DbValue>> rows, WitFuture<Void> done) {
	}

	record Column(String name, DbDataType dataType) {
	}

	record Interval(long micros, int days, int months) {
	}

	enum RangeBoundKind {

		INCLUSIVE, EXCLUSIVE

	}

	// the WIT's tuples
	record Ymd(int year, byte month, byte day) {
	}

	record Hms(byte hour, byte minute, byte second, int nanosecond) {
	}

	record YmdHms(int year, byte month, byte day, byte hour, byte minute, byte second, int nanosecond) {
	}

	record Bound32(int value, RangeBoundKind kind) {
	}

	record Bound64(long value, RangeBoundKind kind) {
	}

	record BoundDecimal(String value, RangeBoundKind kind) {
	}

	record Range32(Optional<Bound32> lower, Optional<Bound32> upper) {
	}

	record Range64(Optional<Bound64> lower, Optional<Bound64> upper) {
	}

	record RangeDecimals(Optional<BoundDecimal> lower, Optional<BoundDecimal> upper) {
	}

	record Extra(String name, String value) {
	}

	record DbError(String asText, String severity, String code, String message, Optional<String> detail,
			List<Extra> extras) {
	}

	// the error arm of every result: the WitException's error()
	sealed interface Error {

		record ConnectionFailed(String value) implements Error {
		}

		record BadParameter(String value) implements Error {
		}

		record QueryFailed(QueryError value) implements Error {
		}

		record ValueConversionFailed(String value) implements Error {
		}

		record Other(String value) implements Error {
		}

	}

	sealed interface QueryError {

		record Text(String value) implements QueryError {
		}

		record DbError(Postgres.DbError value) implements QueryError {
		}

	}

	sealed interface DbDataType {

		record Boolean() implements DbDataType {
		}

		record Int8() implements DbDataType {
		}

		record Int16() implements DbDataType {
		}

		record Int32() implements DbDataType {
		}

		record Int64() implements DbDataType {
		}

		record Floating32() implements DbDataType {
		}

		record Floating64() implements DbDataType {
		}

		record Str() implements DbDataType {
		}

		record Binary() implements DbDataType {
		}

		record Date() implements DbDataType {
		}

		record Time() implements DbDataType {
		}

		record Datetime() implements DbDataType {
		}

		record Timestamp() implements DbDataType {
		}

		record Uuid() implements DbDataType {
		}

		record Jsonb() implements DbDataType {
		}

		record Decimal() implements DbDataType {
		}

		record RangeInt32() implements DbDataType {
		}

		record RangeInt64() implements DbDataType {
		}

		record RangeDecimal() implements DbDataType {
		}

		record ArrayInt32() implements DbDataType {
		}

		record ArrayInt64() implements DbDataType {
		}

		record ArrayDecimal() implements DbDataType {
		}

		record ArrayStr() implements DbDataType {
		}

		record Interval() implements DbDataType {
		}

		record Other(String value) implements DbDataType {
		}

	}

	sealed interface DbValue {

		record Boolean(boolean value) implements DbValue {
		}

		record Int8(byte value) implements DbValue {
		}

		record Int16(short value) implements DbValue {
		}

		record Int32(int value) implements DbValue {
		}

		record Int64(long value) implements DbValue {
		}

		record Floating32(float value) implements DbValue {
		}

		record Floating64(double value) implements DbValue {
		}

		record Str(String value) implements DbValue {
		}

		record Binary(byte[] value) implements DbValue {
		}

		record Date(Ymd value) implements DbValue {
		}

		record Time(Hms value) implements DbValue {
		}

		record Datetime(YmdHms value) implements DbValue {
		}

		record Timestamp(long value) implements DbValue {
		}

		record Uuid(String value) implements DbValue {
		}

		record Jsonb(byte[] value) implements DbValue {
		}

		record Decimal(String value) implements DbValue {
		}

		record RangeInt32(Range32 value) implements DbValue {
		}

		record RangeInt64(Range64 value) implements DbValue {
		}

		record RangeDecimal(RangeDecimals value) implements DbValue {
		}

		record ArrayInt32(List<Optional<Integer>> value) implements DbValue {
		}

		record ArrayInt64(List<Optional<Long>> value) implements DbValue {
		}

		record ArrayDecimal(List<Optional<String>> value) implements DbValue {
		}

		record ArrayStr(List<Optional<String>> value) implements DbValue {
		}

		record Interval(Postgres.Interval value) implements DbValue {
		}

		record DbNull() implements DbValue {
		}

		record Unsupported(byte[] value) implements DbValue {
		}

	}

	sealed interface ParameterValue {

		record Boolean(boolean value) implements ParameterValue {
		}

		record Int8(byte value) implements ParameterValue {
		}

		record Int16(short value) implements ParameterValue {
		}

		record Int32(int value) implements ParameterValue {
		}

		record Int64(long value) implements ParameterValue {
		}

		record Floating32(float value) implements ParameterValue {
		}

		record Floating64(double value) implements ParameterValue {
		}

		record Str(String value) implements ParameterValue {
		}

		record Binary(byte[] value) implements ParameterValue {
		}

		record Date(Ymd value) implements ParameterValue {
		}

		record Time(Hms value) implements ParameterValue {
		}

		record Datetime(YmdHms value) implements ParameterValue {
		}

		record Timestamp(long value) implements ParameterValue {
		}

		record Uuid(String value) implements ParameterValue {
		}

		record Jsonb(byte[] value) implements ParameterValue {
		}

		record Decimal(String value) implements ParameterValue {
		}

		record RangeInt32(Range32 value) implements ParameterValue {
		}

		record RangeInt64(Range64 value) implements ParameterValue {
		}

		record RangeDecimal(RangeDecimals value) implements ParameterValue {
		}

		record ArrayInt32(List<Optional<Integer>> value) implements ParameterValue {
		}

		record ArrayInt64(List<Optional<Long>> value) implements ParameterValue {
		}

		record ArrayDecimal(List<Optional<String>> value) implements ParameterValue {
		}

		record ArrayStr(List<Optional<String>> value) implements ParameterValue {
		}

		record Interval(Postgres.Interval value) implements ParameterValue {
		}

		record DbNull() implements ParameterValue {
		}

	}

}
