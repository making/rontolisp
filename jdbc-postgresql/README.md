# jdbc-postgresql

A JDBC driver for PostgreSQL with no dependency: it takes the URLs that start with
`jdbc:postgresql:` and speaks the frontend/backend protocol, version 3.0, over a plain
socket. It is written from PostgreSQL's protocol documentation, RFC 5802 / 7677 (SCRAM)
and the JDBC specification. It implements a part of JDBC 4.3 and claims no compliance.

```java
try (Connection connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/shop", "alice", "secret");
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
names no class of it. A program written for another PostgreSQL driver changes its
dependency and keeps its URL. The package is `@NullMarked` (JSpecify): what may be null is
`@Nullable`, as a `ResultSet` getter's answer for SQL `NULL`. The annotations are a
`provided` dependency, which nothing needs where the classes run.

- **URL**: `jdbc:postgresql:database`, `jdbc:postgresql://host[:port]/[database]`, an IPv6
  address in brackets, `?name=value&name=value` after it (percent-encoded UTF-8). A setting
  of the URL wins over the same one of the properties. The settings read: `user`,
  `password`, `ApplicationName`, `options`, `connectTimeout`, `loginTimeout`,
  `socketTimeout` (seconds), `sslmode`.
- **Logins**: `trust`, `password`, `md5`, SCRAM-SHA-256.
- **Statements**: a `Statement` runs its text as it is and may hold several commands; a
  `PreparedStatement` sends its parameters apart from the text, typed by their setters;
  batches, the rows written (`RETURN_GENERATED_KEYS`, column names), `setMaxRows`,
  `setQueryTimeout` (the server's `statement_timeout`), `cancel()`.
- **Results**: every row is read with its result; a result set walks forward or both ways
  and updates nothing. Values are read as the `java.lang`, `java.math`, `java.sql`,
  `java.time` and `java.util.UUID` classes; a type of the server's own (json, an array, an
  interval) is its text.
- **Transactions**: `setAutoCommit`, `commit`, `rollback`, savepoints, the isolation
  levels, read-only.
- **Errors**: an exception has the server's SQLSTATE and is of the class the JDBC
  specification gives it (`SQLSyntaxErrorException`,
  `SQLIntegrityConstraintViolationException`, `SQLTimeoutException`, ...).
- **Metadata**: `DatabaseMetaData` answers every fact (`supports*`, `getMax*`,
  `getSQLKeywords`, ...) and the catalog's `getCatalogs`, `getSchemas`, `getTableTypes`,
  `getTables`, `getColumns`, `getPrimaryKeys`, `getImportedKeys`, `getExportedKeys`,
  `getCrossReference`, `getIndexInfo` and `getClientInfoProperties`; `ResultSetMetaData`
  tells a column's table and schema, whether it is nullable and whether the server numbers
  it (an identity or a serial column).
- **`PostgresDataSource`**: a `javax.sql.DataSource` with the bean properties `url`,
  `user` and `password`, for a pool or a container that is given a class name.

Not there yet: TLS (`sslmode=require` and `verify-*` are refused; `prefer`, `allow` and
`disable` connect in the clear), `CallableStatement`, COPY, LISTEN / NOTIFY, arrays, large
objects, updatable result sets, a cursor that fetches in pieces, the catalog's
`getTypeInfo`, `getProcedures`, `getFunctions`, their columns, the privileges, the UDTs
and the row identifiers, JDBC escapes. Each is refused where it is asked for
(`SQLFeatureNotSupportedException`) rather than ignored.

A connection is used by one thread at a time; `Statement.cancel()` and `Connection.abort()`
may come from another.

```bash
../mvnw -f jdbc-postgresql/pom.xml spring-javaformat:apply test
```

The tests need Docker: they run against PostgreSQL 17, and ask pgjdbc the same statements
beside the driver where the specification leaves an answer open.
