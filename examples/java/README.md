# Java sample programs

Programs for the **experimental** subset of the Java language: `.java` files read as that
subset, as described in the [Java section](../../doc/en/java/index.md). Each is checked in
[`examples.yaml`](../examples.yaml). Each application has a README of its own:

- [`hello-java/`](hello-java/README.md): a compact source file that prints `Hello World!`
- [`hello-servlet/`](hello-servlet/README.md): the smallest Servlet application
- [`hello-session/`](hello-session/README.md): `hello-servlet` with a session, kept in `wasi:keyvalue`
- [`hello-mvcfn/`](hello-mvcfn/README.md): the smallest WebMvc.fn application
- [`shop/`](shop/README.md): an order book of six packages
- [`no-wasi/`](no-wasi/README.md): a class with no `main`, whose public static methods are the exports of a module with no WASI, called with `wasmtime run --invoke`
- [`http/`](http/README.md): an HTTP server and its client
- [`httpbin/`](httpbin/README.md): a miniature httpbin
- [`guestbook/`](guestbook/README.md): a Servlet application
- [`mvcfn/`](mvcfn/README.md): routes in the style of Spring MVC's WebMvc.fn
- [`jdbc/`](jdbc/README.md): JDBC on PostgreSQL
- [`yavi/`](yavi/README.md): validation with YAVI
- [`mustache/`](mustache/README.md): templates with jmustache
- [`reflection/`](reflection/README.md): classes chosen by name through reflection, as GraalVM reachability metadata declares them
- [`logging/`](logging/README.md): `java.util.logging`, SLF4J and Commons Logging through it, and the same records in the host's `wasi:logging`
- [`shiori/`](shiori/README.md): a web application on PostgreSQL, `db/shiori` in Java
- [`spin-postgres/`](spin-postgres/README.md): JDBC through Spin's connection, TLS included: a servlet whose driver runs on `spin:postgres/postgres`
- [`java-calls-rust/`](java-calls-rust/README.md): a WIT interface that a Rust component implements, composed with `wac`
- [`rust-calls-java/`](rust-calls-java/README.md): a WIT world implemented in Java, which a Rust component calls, composed with `wac`

## The applications that use a library

`hello-java/`, `hello-servlet/`, `hello-session/`, `hello-mvcfn/`, `httpbin/`, `guestbook/`, `mvcfn/`, `jdbc/`,
`yavi/`, `mustache/`, `reflection/`, `logging/`, `shiori/`, `spin-postgres/`, `java-calls-rust/` and `rust-calls-java/` are Maven projects: the sources in `src/main/java`, the
resources in `src/main/resources`, and a standalone `pom.xml` with no parent: it holds the versions it uses and declares Central's
snapshot repository, where the plugin, rontolisp and this repository's modules are
`-SNAPSHOT`s. Copy one directory out of the repository and it builds. In an application's
directory (`mvn` or the repository's `../../../mvnw`):

```bash
mvn package               # javac, then rontolisp-maven-plugin's wasm goal: target/<name>.wasm
mvn exec:exec             # the main class on the JDK running Maven
mvn -Pnative package      # also target/<name>, a native executable
```

The four `hello-*` projects, `spin-postgres/`, `java-calls-rust/` and `rust-calls-java/` have no `-Pnative`
profile, and `hello-java` builds the Preview 1 module only. `rust-calls-java/` has no
main class either: it is a component exporting a WIT world, and its test calls the
exported function under a JDK and on rontolisp's interpreter (the plugin's `test` goal).

A server is a component for
`wasmtime serve` (`spin-postgres`'s is for Spin alone, and its `exec:exec` runs over a socket; `hello-session`'s for a host with
`wasi:keyvalue@0.2.0-draft2`; `hello-servlet`, `hello-session` and `hello-mvcfn`, unchanged, are also Cloudflare
Workers: [`cloudflare-workers/`](../cloudflare-workers/README.md)); a program that ends is a Preview 1 module and a component for `wasmtime run`;
the guestbook and the shiori are also a WAR for a servlet container. The plugin compiles
`src/main/java` from its main class, or with none from the classes `@WitExport` marks, the
libraries from their sources jars
([Maven Plugin](../../doc/en/compiling/maven-plugin.md#a-java-project)). `-Pnative` needs a rontolisp
carrying the native toolchain, which the jar on Central does not: one installed from a
checkout (`rontolisp-native/build.sh`, then `./mvnw install -DskipTests` and
`./mvnw -f rontolisp-maven-plugin/pom.xml install -DskipTests`).

Upgrading a library is an edit of each pom that names it. `examples.yaml` and the tests compile
each from its `src/main/java` with the libraries the pom names, this repository's modules from their sources in this checkout.

An application added here gets a README of its own, in its directory, and a line above.
