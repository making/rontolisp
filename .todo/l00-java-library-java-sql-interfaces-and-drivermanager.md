# l00. Java library: `java.sql`'s interfaces and `DriverManager`

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` before writing anything.** Theme: JDBC for PostgreSQL
(`.todo/l01`). `java.sql` has `Date`, `Time`, `Timestamp` and `import module java.sql`
(k37, `.kb/java-frontend.md`, "java.util.Date and java.sql").

- The interfaces a driver implements and a program calls: `Driver`, `Connection`,
  `Statement`, `PreparedStatement`, `CallableStatement`, `ResultSet`, `ResultSetMetaData`,
  `DatabaseMetaData`, `ParameterMetaData`, `Savepoint`, `Array`, `Blob`, `Clob`, `NClob`,
  `Ref`, `RowId`, `SQLXML`, `Struct`, `Wrapper`, `Types`, `JDBCType`, `SQLType`,
  `DriverPropertyInfo`, `SQLException` and its subclasses, `SQLWarning`, `BatchUpdateException`;
  `javax.sql.DataSource` and what pgjdbc's data sources implement. Written from the API's
  signatures; the default methods' answers measured.
- `DriverManager`: `registerDriver`, `getConnection` (url, properties, user/password),
  `getDriver(s)`, the drivers a program's `META-INF/services/java.sql.Driver` names through
  `ServiceLoader` (`.kb/java-frontend.md`, "ServiceLoader"), login timeout, log writer.
- Pin with a driver of the program's own (a fake that answers rows) on the four backends;
  pgjdbc itself is l01.
