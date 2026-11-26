# JDBC on PostgreSQL: `jdbc/`

[`Notes.java`](src/main/java/jdbc/Notes.java) keeps notes in PostgreSQL through plain JDBC: a
connection from `DriverManager`, a table, rows written by a `PreparedStatement` (the
generated key read back) and read into a record, an update, a delete, a transaction
committed and one rolled back by a failing statement, and text outside ASCII counted by the
server and by Java. The table is `temporary`, so a run leaves nothing behind.

The program names no class of a driver: it asks for a `jdbc:postgresql:` URL, and
`DriverManager` finds the driver that takes it. Here that is
[`jdbc-postgresql`](https://github.com/making/rontolisp/tree/develop/jdbc-postgresql), whose
sources are compiled with the program ([A JDBC program on PostgreSQL](../../../doc/en/java/jdbc.md)).

The server is the one of [`db/`](../../db/README.md#setup), and so is `DATABASE_URL`. The
program takes it as `postgresql://user:password@host:port/database`, or as a JDBC URL
(`jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres`). It is a Maven project
([`examples/java/`](../README.md)):

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres

cd examples/java/jdbc
mvn package               # target/jdbc.wasm, a component
mvn exec:exec             # JDK
wasmtime run -S inherit-network=y --env DATABASE_URL target/jdbc.wasm
mvn -Pnative package && ./target/jdbc     # native executable
```

From the repository's root, the command line compiles the same file for the interpreter and
the JVM, the driver's sources from this repository:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
APP=examples/java/jdbc/src/main/java/jdbc/Notes.java
LIBS="--java-source-path jdbc-postgresql/src/main/java:jdbc-postgresql/src/main/resources"
java -jar $JAR $APP $LIBS                                # interpreter
java -jar $JAR $APP $LIBS -o Notes.class && java Notes   # JVM
```

```
connected to PostgreSQL
Note[id=1, title=Buy milk, done=false, due=2026-12-01]
Note[id=2, title=お茶を淹れる🍵, done=false, due=null]
Note[id=3, title=Write the JDBC example, done=false, due=2026-11-22]
marked done: 1
Note[id=3, title=Write the JDBC example, done=true, due=2026-11-22]
no note 9: true
お茶を淹れる🍵: 7 characters, 22 bytes in UTF-8; Java counts 7
committed
rolled back: SQLSTATE 23502
all notes:
  Note[id=1, title=Buy milk, done=true, due=2026-12-01]
  Note[id=2, title=お茶を淹れる🍵, done=false, due=null]
  Note[id=3, title=Write the JDBC example, done=true, due=2026-11-22]
  Note[id=4, title=Pay the rent, done=false, due=2026-11-30]
deleted done: 2
left:
  Note[id=2, title=お茶を淹れる🍵, done=false, due=null]
  Note[id=4, title=Pay the rent, done=false, due=2026-11-30]
```

The rolled-back transaction took the next identity values with it (a sequence is not rolled
back), so the next note would be 7.

| Output | Size |
| --- | --- |
| `Notes.class` | 1.3 MB |
| `jdbc.wasm` (component) | 1.0 MB |
| `jdbc` (native, linux-x86_64) | 8.0 MB |

A component reaches the server through the host's network: `-S inherit-network=y`,
and `-S allow-ip-name-lookup=y` when the URL names the host by name. `--env DATABASE_URL`
hands the variable in, since wasmtime passes none unless asked. A Preview 1 module
(a `wasm` goal without `component`) has no sockets.

On a JDK another PostgreSQL driver prints the same lines (pgjdbc's in the place of
`jdbc-postgresql` in the pom).
