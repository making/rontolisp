# A JDBC program on PostgreSQL

A program written against JDBC reaches a PostgreSQL server on every target of the
[subset](index.md) that has [sockets](index.md#sockets) when its driver is one the subset
can compile.
[`jdbc-postgresql`](https://github.com/making/rontolisp/tree/develop/jdbc-postgresql) is
one: a driver that needs nothing but the JDK's own classes, takes the URLs that start with
`jdbc:postgresql:` and is found by `DriverManager` through its services file. It lives in
this repository for now and depends on nothing of rontolisp.

The driver's sources and its resources go on the program's
[source path](index.md#using-a-third-party-library) and are compiled with it:

```console
public class Items {

    public static void main(String[] args) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:5432/postgres";
        try (Connection connection = DriverManager.getConnection(url, "postgres", "secret");
                Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists items");
            statement.execute("create table items (id integer primary key, name text)");
            try (PreparedStatement insert = connection.prepareStatement("insert into items values (?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "お茶");
                insert.executeUpdate();
            }
            try (ResultSet rows = statement.executeQuery("select id, name from items order by id")) {
                while (rows.next()) {
                    System.out.println(rows.getInt(1) + " " + rows.getString(2));
                }
            }
        }
    }

}
```

(the imports are six classes of `java.sql`)

```bash
LIBS="--java-source-path jdbc-postgresql/src/main/java:jdbc-postgresql/src/main/resources"
rontolisp Items.java $LIBS                                     # interpreter
rontolisp Items.java $LIBS -o Items.class && java Items        # JVM
rontolisp Items.java $LIBS --native -o items && ./items        # native executable
rontolisp Items.java $LIBS -o items.wasm --component && wasmtime run -S inherit-network=y items.wasm
```

Under a JDK the same program runs with the driver's jar on the class path, or with another
PostgreSQL driver: the program names no class of either. A program holds what it reaches
of the driver: a component that logs in and runs one query is about 780 KB, and one with a
`PreparedStatement` and non-ASCII text about 810 KB. A plain Preview 1 module has no
network: the connection ends in a `SQLException` whose cause is the socket's.

The driver logs in by `trust`, `password`, `md5` and SCRAM-SHA-256; runs statements,
prepared statements and batches; reads values as the `java.lang`, `java.math`, `java.sql`
and `java.time` classes; keeps transactions and savepoints; answers what the server
refuses as the `SQLException` class of its SQLSTATE; answers `DatabaseMetaData`'s questions
of the tables, columns, keys and indexes; and is a `DataSource` (`PostgresDataSource`) for a
pool given a class name. What it has and what it refuses (TLS, `CallableStatement`, COPY,
arrays, updatable result sets) is in its
[README](https://github.com/making/rontolisp/blob/develop/jdbc-postgresql/README.md).

[`examples/java/jdbc/`](https://github.com/making/rontolisp/tree/develop/examples/java/jdbc)
is a longer program -- the server's address from `DATABASE_URL`, rows read into a record, an
update, a delete, a transaction committed and one rolled back -- with its output on the four
targets and on a JDK.
[`examples/java/shiori/`](https://github.com/making/rontolisp/tree/develop/examples/java/shiori)
is a web application whose requests keep their connections for the next ones, as a pool
does ([A Servlet application](servlet.md)).

A server that requires TLS is reached through a host that owns the connection, with a
second driver: [Under Spin](#under-spin).

## What differs

- **A component needs the network of its host**: `-S inherit-network=y`, and
  `-S allow-ip-name-lookup=y` when the URL names the server by name.
- **A large result is slower to walk outside a JDK.** A megabyte of rows (10,000) is read
  in 15-40 ms on a native executable, a component and the JVM and in about 0.1 s on the
  interpreter, where a JDK takes about 10 ms; reading each value of every row takes another
  0.1-0.15 s, and about 1 s on the interpreter. A small query takes under a millisecond on a
  native executable and a component, and a login about 5 ms.
- **A SCRAM-SHA-256 login takes about 80 ms on a `.wasm`**, which is the key derivation
  the method asks for.

## On a Cloudflare Worker

A `--no-wasi` module built with `--host-sockets` reaches the server through its host's
sockets: on a Cloudflare Worker, `connect()` of `cloudflare:sockets`, which is also what
dials the connection string of a
[Hyperdrive](https://developers.cloudflare.com/hyperdrive/) binding.

```bash
rontolisp Visits.java $LIBS -o src/worker.wasm --no-wasi --host-sockets --host-random --host-output --optimize=size --emit-js-glue
```

`--host-random` gives a SCRAM-SHA-256 login its nonce. A Worker asks three things of the
program:

- **A connection is its request's.** A Worker's socket ends with the request that opened
  it, so a request connects, runs its statements and closes; the pool is Hyperdrive's. A
  connection kept for the next request fails at its first use there with a
  `SQLNonTransientConnectionException` (SQLSTATE `08006`).
- **`main` reaches no database.** It runs when the instance starts, outside any request,
  where a socket cannot be waited for: the instance ends, and the Worker's log names the
  call. (Built with `--host-timer` too, `main` can connect, and its connection ends with
  the first request.)
- **The server's address comes with the request.** `System.getenv` answers `null` on a
  Worker, so the Worker's `index.js` hands the program the binding's connection string, as
  a field of the request it passes on.

[`examples/cloudflare-workers/hello-jdbc/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-jdbc)
is such a Worker, a visit counter of about 1.0 MB (270 KB gzipped). Under `wrangler dev`
with PostgreSQL on the loopback, a request that connects, runs two statements and closes
takes about 20 ms with a `trust` login and about 210 ms with SCRAM-SHA-256, whose key
derivation every connection pays. A deployed Worker was not measured. The driver speaks no
TLS, so a server that requires it of the Worker cannot be reached.

## Under Spin

[`jdbc-spin-postgres`](https://github.com/making/rontolisp/tree/develop/jdbc-spin-postgres)
is a second driver for the same URLs. It opens no socket: every statement runs on
`spin:postgres/postgres`, a [WIT interface](wit.md) of the host
[Spin](https://spinframework.dev) (4.1.0 or later), which opens the connection, pools it
and speaks TLS with the server's certificate verified. A JDBC program changes its driver
and nothing else:

```bash
rontolisp App.java -o app.wasm --component \
  --java-source-path jdbc-spin-postgres/src/main/java:jdbc-spin-postgres/src/main/resources:rontolisp-java-api/src/main/java
```

The component is served by `spin up`, with the server it may reach named in `spin.toml`
(`allowed_outbound_hosts = ["postgres://host:port"]`). `sslmode=require` in the URL asks
for TLS, and the `sslrootcert` property is a certificate of your own, as PEM text.
[`examples/java/spin-postgres/`](https://github.com/making/rontolisp/tree/develop/examples/java/spin-postgres)
is a servlet doing so, in the clear and over TLS; as a component it is 1.5 MB, against
1.6 MB for the same program compiled with the socket driver.

No other host implements the interface, and on a JDK, the interpreter and the JVM nothing
does: there the driver takes no URL and `DriverManager` goes on to the next driver, so a
program with both on its path runs through the host under Spin and over a socket
elsewhere. A component is compiled with this driver alone (a native executable and a
Preview 1 module cannot import the interface).

The interface takes a statement and answers its rows, which sets what the driver refuses,
each with a `SQLFeatureNotSupportedException`:

- **A `timestamptz` column fails the query.** The refusal names the type; cast it in the
  statement (`ts AT TIME ZONE 'UTC'`, or `ts::text`, which `getTimestamp` and
  `getObject(i, OffsetDateTime.class)` read as the instant).
- **A parameter always has a type.** `setObject(i, x, Types.OTHER)` sends a text, so a json
  or an enum is cast in the statement (`?::jsonb`); `setTimestamp` sends a `timestamp`
  without a time zone, and an `OffsetDateTime` or an `Instant` the instant.
- **No `cancel` and no statement of several commands**, and a type the host has no case
  for (`timetz`, `inet`, an enum, an array of `float8`) is read after a cast to text.

The catalog (`getTables`, `getColumns`, the keys and the indexes) answers what
`jdbc-postgresql` answers. A prepared statement is described before it runs (a query's
columns, every statement's parameters), and an array of `int4`, `int8`, `numeric` or
`text` is read by `getArray`; `createArrayOf` and `setArray` take any element type.

A query's rows are handed over together. With `setFetchSize(n)` (`n > 0`) on a statement
whose result sets move forward only, they are read in pieces of at most `n` as the host
reads them from the server, so the program holds one piece, however many rows the query
answers, and its first row comes early: a statement the server refuses while its rows are
read throws where they run out, and a result set closed early leaves the rest to the host.
The pieces are the host's to size: Spin 4.3.0 hands a row a read over, so 10,000 rows came
in 2-3.4 ms instead of 35-50 ms to the first, and in 165-211 ms instead of 46-71 ms to the
last, and 100,000 rows took Spin 11 MB of memory against 110 MB.

The rest is in the driver's
[README](https://github.com/making/rontolisp/blob/develop/jdbc-spin-postgres/README.md).
