# rontolisp-java-api

What a Java program compiled by rontolisp's Java compiler names of rontolisp's: plain Java
with no dependency, so the same program compiles with `javac` and runs on a JDK, and
rontolisp gives the names their meaning when it compiles the program. It depends on nothing
of rontolisp.

- `am.ik.rontolisp.wit.WitImport` declares that an interface stands for a WIT interface the
  program imports: its abstract methods are the WIT functions, checked against the WIT when
  rontolisp compiles the program. Its implementation is a service provider:
  `ServiceLoader` finds the program's own under a JDK and on rontolisp's interpreter and
  JVM, and the host's in a WebAssembly module or component rontolisp compiles.
- `am.ik.rontolisp.wit.WitException` is a WIT `result`'s error arm, carrying the error.
- `am.ik.rontolisp.wit.WitStream` and `am.ik.rontolisp.wit.WitFuture` are a WIT `stream<T>`
  and `future<T>` the host answers: the stream read so many values at a time, the future's
  one value waited for. A provider answers its own (`WitStream.of`, `WitFuture.of`,
  `WitFuture.failed`).
- `am.ik.rontolisp.wit.WitExport` declares that a class implements a WIT world the program
  exports: each function of the world is a static method, an interface of it a static
  member class, checked against the WIT when rontolisp compiles the program. Under a JDK and
  on rontolisp's interpreter and JVM the methods are ordinary ones; a program with no `main`
  is the classes this annotation marks, compiled to a component whose entry points they are.

```java
@WitImport(value = "wasi:keyvalue/store", wit = "wit/keyvalue.wit")
public interface Store {

    Bucket open(String identifier);

    interface Bucket extends AutoCloseable {

        Optional<byte[]> get(String key);

        void set(String key, byte[] value);

        void close();
    }
}
```

```java
@WitExport(value = "counter", wit = "wit/vowels.wit")
public class Counter {

    public static int vowelCount(String text) {
        ...
    }
}
```

The library is for what only rontolisp gives a Java program; a name of it has no
implementation of its own beyond plain Java. The manual's page is
[WIT interfaces and worlds](../doc/en/java/wit.md); the examples are
[`examples/wit/keyvalue/PageHits.java`](../examples/wit/keyvalue/PageHits.java) and
[`examples/java/rust-calls-java/`](../examples/java/rust-calls-java).

```bash
../mvnw -f rontolisp-java-api/pom.xml spring-javaformat:apply verify
```

A program compiled by rontolisp takes the sources on its source path
(`--java-source-path rontolisp-java-api/src/main/java`); a JDK takes the jar on its class
path.
