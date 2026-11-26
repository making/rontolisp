# jdbc-spin-postgres

A JDBC driver for PostgreSQL over the connection of the host that runs the program: it
takes the URLs that start with `jdbc:postgresql:` and runs each statement through
[Spin](https://spinframework.dev)'s WIT interface `spin:postgres/postgres`. The host opens
the connection, pools it, speaks PostgreSQL's protocol and, when the URL asks for it, TLS
with the server's certificate verified; the driver opens no socket. It implements a part
of JDBC 4.3 and claims no compliance.

```java
try (Connection connection = DriverManager.getConnection(
            "jdbc:postgresql://db.example:5432/shop?user=alice&password=secret&sslmode=require");
        PreparedStatement select = connection.prepareStatement("select name from items where id = ?")) {
    select.setInt(1, 42);
    try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
            System.out.println(rows.getString("name"));
        }
    }
}
```

`DriverManager` finds the driver through `META-INF/services/java.sql.Driver`: a program
names no class of it and keeps the URL it gives another PostgreSQL driver.

## Where it runs

In a WebAssembly component [rontolisp](../README.md) compiles, served by **Spin 4.1.0 or
later** (measured: 4.1.0, 4.3.0 and canary 4.4.0-pre0). The interface is imported through
[`rontolisp-java-api`](../rontolisp-java-api)'s `@WitImport`
(`wit/postgres.wit`, Spin's file, a resource of the package), and the component may reach
the servers its `spin.toml` allows (`allowed_outbound_hosts = ["postgres://host:port"]`).

Nothing implements the interface under a JDK, nor on rontolisp's interpreter and JVM.
There `connect` answers `null`, as for a URL of another driver, so `DriverManager` goes on
to the next one: a program with this driver and a socket driver
([`jdbc-postgresql`](../jdbc-postgresql), pgjdbc) on its path runs through the host where
there is one and over a socket elsewhere. A component is compiled with this driver alone.
`SpinPostgresDataSource` names this driver and throws where there is no host.

## What it does

- **URL**: `jdbc:postgresql:database`, `jdbc:postgresql://host[:port]/[database]`, an IPv6
  address in brackets, `?name=value&name=value` after it (percent-encoded UTF-8). A setting
  of the URL wins over the same one of the properties. The settings read: `user`,
  `password`, `sslmode`, `ssl`, `sslrootcert`, `ApplicationName`, `options`,
  `connectTimeout`, `loginTimeout`. The host is asked for
  `postgres://user:password@host:port/database?...`.
- **TLS**: `sslmode` is `disable`, `prefer` (the host's default: TLS when the server
  offers it) or `require`. The host verifies the certificate's chain and the server's name
  whenever it speaks TLS, so `verify-ca` and `verify-full` are its `require`.
  `sslrootcert` is a certificate's PEM text, verified against beside the roots the host
  has.
- **Statements**: a `Statement` is one command. A `PreparedStatement` sends each parameter
  as the type its setter names, written into the text as a cast (`($1::int4)`), which the
  server reads as it reads a declared type. Batches, the rows written
  (`RETURN_GENERATED_KEYS`, column names), `setMaxRows`, `setQueryTimeout` (the server's
  `statement_timeout`). Before it runs, `getMetaData()` of a query is the columns of the
  query asked inside one that answers no row, and `getParameterMetaData()` is the
  server's types of the statement prepared under a name of its own (`PREPARE`, then
  `pg_prepared_statements`, then `DEALLOCATE`: three calls).
- **Results**: every row is handed over with its result; a result set walks forward or
  both ways and updates nothing. Values are read as the `java.lang`, `java.math`,
  `java.sql`, `java.time` and `java.util.UUID` classes; a jsonb, a json, an interval, a
  range and an array are the server's text to `getObject` and `getString`, as
  `jdbc-postgresql` reads them.
- **Arrays** of one dimension: `getArray` of an `int4[]`, `int8[]`, `numeric[]` or
  `text[]` (a `varchar[]` is one of text), whose elements are `Integer`, `Long`,
  `BigDecimal` or `String`; `createArrayOf` and `setArray` of any element type, sent as the
  host's case of it or as the texts of its elements, which the server casts
  (`text[]::float8[]`).
- **The catalog**: every fact of `DatabaseMetaData`, and `getTables`, `getColumns`,
  `getPrimaryKeys`, the imported and exported keys, `getCrossReference`, `getIndexInfo`,
  `getSchemas`, `getCatalogs`: queries of `pg_catalog`, each a statement of the program's
  transaction, answering what `jdbc-postgresql` answers (the tests ask both).
- **Transactions**: `setAutoCommit`, `commit`, `rollback`, savepoints, the isolation
  levels, read-only. The host hands a closed connection out again as it is, so `close()`
  rolls back a transaction left open.
- **Errors**: an exception has the server's SQLSTATE and is of the class the JDBC
  specification gives it (`SQLSyntaxErrorException`,
  `SQLIntegrityConstraintViolationException`, `SQLTimeoutException`, ...). A connection the
  host could not open is `08001` with the host's words.
- **`SpinPostgresDataSource`**: a `javax.sql.DataSource` with the bean properties `url`,
  `user`, `password` and `sslRootCert`. It keeps no connection: the host keeps the pool.

## What the interface does not carry

Each is refused where it is asked for (`SQLFeatureNotSupportedException`, `0A000`) rather
than ignored.

- **A `timestamptz` column**: the host fails the whole query, and the refusal names the
  type. Cast it in the statement: `ts AT TIME ZONE 'UTC'` reads as a `timestamp` whose
  fields are UTC's, `ts::text` as a text that `getTimestamp` and
  `getObject(i, OffsetDateTime.class)` read as the instant.
- **A value the host cannot convert**: a numeric `NaN`, an infinite date or timestamp (the
  whole query fails), an array of more than one dimension (the same), and a type it has
  no case for (`timetz`, `inet`, `point`, an enum, an array of another element type, ...),
  which `getBytes` hands over as the server's octets and every other getter refuses with
  the type's name. `::text` in the statement reads any of them.
- **A parameter with no type**: the host converts a parameter by the type the server reads
  it as, so every parameter has one. `setObject(i, x, Types.OTHER)` sends a `text`: a json
  or an enum is cast in the statement (`?::jsonb`). `setTimestamp` sends a `timestamp`
  (the wall's fields); `setObject` of an `OffsetDateTime`, a `ZonedDateTime` or an
  `Instant` sends the instant.
- **An array the host has no case for** (`bool[]`, `float8[]`, ...) is refused by
  `getArray` (`::text[]` in the statement reads its elements' texts), and an array of more
  than one dimension fails the query.
- **A result read in pieces**: the host hands a result over whole (its streaming call,
  `query-async`, is an `async func`, which `@WitImport` does not carry yet).
- `Statement.cancel()`, a statement of several commands (the server refuses the second),
  `CallableStatement`, COPY, LISTEN / NOTIFY, large objects, `SQLXML`, updatable result
  sets, `getMoreResults` past the one result, `PreparedStatement.getMetaData()` of a
  command's rows (`RETURNING`, `SHOW`, `EXPLAIN`) before it has run (the server tells no
  column's name of a statement it does not run), the server's warnings, `isValid`'s limit
  on its time, and the catalog's types, procedures, functions and privileges.

It answers as `jdbc-postgresql` does for the same statement but where the host tells less:
a `varchar` and a `char` column of a result are named `text` (`Types.VARCHAR`), a result
column's table, schema, precision and nullability are not told (`getColumns` tells a
table's), an empty range reads as `(,)`, and a jsonb is the text the host writes for it
(`{"a":1}`). `getArray`, `setArray` and `createArrayOf` are answered here and refused
there.

A connection is used by one thread at a time. The package is `@NullMarked` (JSpecify); the
annotations are a `provided` dependency, which nothing needs where the classes run.

The manual's page is [JDBC](../doc/en/java/jdbc.md#under-spin); the example is
[`examples/java/spin-postgres/`](../examples/java/spin-postgres).

```bash
../mvnw -f rontolisp-java-api/pom.xml install
../mvnw -f jdbc-postgresql/pom.xml install -DskipTests
../mvnw -f jdbc-spin-postgres/pom.xml spring-javaformat:apply test
```

The tests need Docker. Under a JDK the host is a stand-in of the tests', answering the
interface over a socket through `jdbc-postgresql` as Spin was measured to answer it, against
PostgreSQL 17; the same statements are asked of `jdbc-postgresql` beside the driver.
