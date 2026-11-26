# servlet-httpserver-keyvalue

The sessions of [`servlet-httpserver`](../servlet-httpserver) in a key-value store,
`wasi:keyvalue/store@0.2.0-draft2`. On the class path, or on the source path of a program
rontolisp compiles, it is the `SessionStore` the server finds
(`META-INF/services/am.ik.servlet.httpserver.SessionStore`), with no code of the
application's.

- `KeyValueStore` is the WIT interface, imported through
  [`rontolisp-java-api`](../rontolisp-java-api)'s `@WitImport` (`wit/keyvalue.wit`, a
  resource of the package in `src/main/resources`, the draft's `store` verbatim). In a WebAssembly component rontolisp compiles the host is its
  implementation, and in a `--no-wasi` module the glue rontolisp writes, over a Cloudflare
  Worker's KV namespace; under a JDK, and on rontolisp's interpreter and JVM,
  `MemoryKeyValueStore`.
- `KeyValueSessionStore` writes a session whole, at every change, under the key
  `servlet-httpserver:session:<id>` of the default store (`""`): its times, its interval and
  its attributes, each a `String`, a box of a primitive, a `byte[]`, or a `List`, `Set` or
  `Map` of them. Another value is refused by `setAttribute` (`IllegalArgumentException`). A
  request that only reads the session writes it once its access time moved by a tenth of
  the interval, at most a minute (`SessionStore.saveAccess`).
- The expired sessions are looked for over every instance sharing the store a turn at a
  time: at most 16 sessions read a turn, the next turn a minute later, a pass over every key
  ten minutes after the last (the key `servlet-httpserver:sweep`).
- The package is `@NullMarked` (JSpecify), as the server's; the annotations are a `provided`
  dependency, which nothing needs where the classes run.

The manual's page is [A Servlet application](../doc/en/java/servlet.md#sessions-in-wasikeyvalue);
the examples are [`examples/java/guestbook/`](../examples/java/guestbook) and
[`examples/java/hello-session/`](../examples/java/hello-session), a Cloudflare Worker as
[`examples/cloudflare-workers/hello-session-one-source/`](../examples/cloudflare-workers/hello-session-one-source).

```bash
../mvnw -f servlet-httpserver/pom.xml install -DskipTests
../mvnw -f rontolisp-java-api/pom.xml install
../mvnw -f servlet-httpserver-keyvalue/pom.xml spring-javaformat:apply test
```
