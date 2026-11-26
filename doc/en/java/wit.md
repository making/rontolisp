# WIT interfaces and worlds

A program of the [subset](index.md) calls a WIT interface through a Java interface
annotated `@WitImport`, and implements a WIT world through a class annotated `@WitExport`
([Exporting a WIT world](#exporting-a-wit-world)). The annotation comes from
[`rontolisp-java-api`](https://github.com/making/rontolisp/tree/develop/rontolisp-java-api),
a library of plain Java with no dependency, so the same source compiles with `javac` and
runs on a JDK. The interface's implementation is a service provider: the program's own
under a JDK, on the interpreter and on the JVM, the host's in a WASM module or component.
It is the Java side of [`rontolisp:wit-import`](../guides/wit-contracts.md#importing-a-wit-interface-wit-import).

```console
import java.util.List;
import java.util.Optional;

import am.ik.rontolisp.wit.WitImport;

@WitImport(value = "wasi:keyvalue/store", wit = "wit/keyvalue.wit")
public interface Store {

    Bucket open(String identifier);

    interface Bucket extends AutoCloseable {

        Optional<byte[]> get(String key);

        void set(String key, byte[] value);

        KeyResponse listKeys(Optional<Long> cursor);

        @Override
        void close();
    }

    record KeyResponse(List<String> keys, Optional<Long> cursor) {
    }

    sealed interface Error {

        record NoSuchStore() implements Error {
        }

        record AccessDenied() implements Error {
        }

        record Other(String value) implements Error {
        }
    }
}
```

```console
Store store = ServiceLoader.load(Store.class).findFirst().orElseThrow();
try (Store.Bucket bucket = store.open("")) {
    bucket.set("visits", "41".getBytes(StandardCharsets.UTF_8));
}
```

The library goes on the program's [source path](index.md#using-a-third-party-library), and
on the class path of a JDK:

```bash
API="--java-source-path rontolisp-java-api/src/main/java"
rontolisp PageHits.java $API                                     # interpreter
rontolisp PageHits.java $API -o PageHits.class && java PageHits  # JVM
rontolisp PageHits.java $API -o kv.wasm --component && wasmtime run -S keyvalue=y kv.wasm
java -cp rontolisp-java-api.jar PageHits.java                    # a JDK
```

[`examples/wit/keyvalue/PageHits.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
is a counter over `wasi:keyvalue/store` that prints the same report in all four, the
component's from wasmtime's own store.

## The interface

`value` is the WIT interface: its id, or the id without the version, which the WIT read
gives. `wit` is the WIT: a `.wit` file, or the directory of a package as published (its
`.wit` files and `deps/`), named as `Class.getResource` names a resource: relative to the
interface's package, or from the top after a leading `/`. It is looked for where the
interface's class loader looks: the program's source tree, then each root of the source
path in order, a directory or a jar (`--java-source-dep`, the Maven plugin's
`target/classes` first). So a WIT beside the file declaring the interface is found, and so
is one in `src/main/resources` under the package's directory:

```console
src/main/java/com/example/kv/Store.java          @WitImport(..., wit = "wit/keyvalue.wit")
src/main/resources/com/example/kv/wit/keyvalue.wit
```

A `..` is resolved within the root; one that climbs above its top is refused. A program
read from no file (`-e`, a session) looks in the working directory before the source path.

The interface declares the functions the program calls, no more, and each is checked
against the WIT when the program is compiled, on every target: a function the WIT does not
have, a parameter or a result of another type, is refused at the declaration with the WIT
member's line:

```console
Store.java:12:21: listKeys (bucket-list-keys of wasi:keyvalue/store@0.2.0-draft2,
wit/keyvalue.wit:20): its parameter cursor is option<string>, which is Optional<String>
in Java, not Optional<Long>
```

So an interface declaring what two versions of a WIT interface share is checked against
either, whichever `wit` names, and the version of the import is that WIT's.

The names follow the WIT's, in Java's case:

| WIT | Java |
| --- | --- |
| a function `list-keys` | the method `listKeys` |
| a resource `bucket` | the member interface `Bucket`, its methods the resource's |
| a resource's release | `close()` of that interface |
| a resource's constructor, its static function `create` | the methods `bucketNew`, `bucketCreate` of the annotated interface |
| a record, a variant, an enum or flags `key-response` | the member type `KeyResponse` |
| a case or a label `no-such-store` | the record `NoSuchStore`, the enum constant `NO_SUCH_STORE` |

A name that is a Java keyword ends in `_` (`default_`).

## The implementation

`ServiceLoader.load(Store.class)` finds the interface's implementation:

- **under a JDK, on the interpreter and on the JVM**, the program's own: a public class
  with a public constructor of no parameter, named in `META-INF/services/Store` (the
  interface's binary name) of the source tree, as for any service;
- **in a module or component rontolisp compiles**, the host's alone: the module imports
  the WIT interface, and a provider the program names is not consulted.

So the program says nothing about where it runs: a store of its own answers where it is
developed, the host where it is deployed. On a WASM target the module imports the
functions the program calls and no other.

A `--no-wasi` module imports the interface the way a component does, through the canonical
ABI, and its host is the JavaScript glue `--emit-js-glue` writes
([WIT interfaces beyond the flat set](../guides/wasm-host-boundary.md#wit-interfaces-beyond-the-flat-set)).
The glue answers `wasi:keyvalue/store@0.2.0-draft2` over Workers KV itself, so a program
keeping its state there is a Cloudflare Worker as it stands; a call the program makes while
the module loads (from `main`) is refused, since such a host may answer with a promise.

A host's own interface is imported the same way.
[`jdbc-spin-postgres`](https://github.com/making/rontolisp/tree/develop/jdbc-spin-postgres)
declares Spin's `spin:postgres/postgres` (its `Postgres.java`) and is a JDBC driver over
it: Spin opens the connections to PostgreSQL, pools them and, with `sslmode=require`,
speaks TLS ([JDBC, under Spin](jdbc.md#under-spin)). Nothing implements the interface on a
JDK, where the driver leaves the URL to another one.

## The types

| WIT | Java |
| --- | --- |
| `bool` | `boolean` |
| `s8`, `u8` | `byte` |
| `s16`, `u16` | `short` |
| `s32`, `u32` | `int` |
| `s64`, `u64` | `long` |
| `f32`, `f64` | `float`, `double` |
| `char` | `int`, the code point |
| `string` | `String` |
| `list<u8>` | `byte[]` |
| `list<T>` | `List<T>` |
| `option<T>` | `Optional<T>` |
| `tuple<A, B>` | a record of the program's with those components |
| `record` | a member record, a component a field |
| `variant` | a member sealed interface permitting a record for each case (the payload its one component) |
| `enum` | a member enum |
| `flags` | a `Set` of a member enum |
| a resource, `own<R>`, `borrow<R>` | the member interface |
| `result<T, E>` | `WitResult<T, E>`; as a method's answer, also its ok arm, the error arm a `WitException` |
| an `async func` | a method that waits for the host's answer, as a call does |
| `stream<T>` the host answers | `am.ik.rontolisp.wit.WitStream<T>`, of values or of resources |
| `stream<u8>` the host answers | `java.io.InputStream` |
| `stream<u8>` the program hands the host | `java.util.function.Consumer<java.io.OutputStream>`, handed the writable end |
| `future<T>` the host answers | `am.ik.rontolisp.wit.WitFuture<T>`, a `future<result<T, E>>` a `WitFuture<T>` (`Void` for no ok value) |

An unsigned integer is held in the Java type of its width, its bits as Java holds them:
the `u8` 200 is the `byte` -56 (`Byte.toUnsignedInt` gives 200 back).

`result<T, E>` is an `am.ik.rontolisp.wit.WitResult<T, E>` wherever the WIT has one: a
parameter, a list's element, an option's value, a record's field, a variant's payload, an
answer. It is a sealed interface of two records, `WitResult.Ok(T value)` and
`WitResult.Err(E error)`; an arm carrying nothing is `Void`, its value `null`, so
`result<_, E>` is `WitResult<Void, E>`. A tuple in an arm is a record of the program's, as
anywhere else.

```console
// check-all: func(names: list<string>) -> list<result<u32, problem>>;
List<WitResult<Integer, Problem>> checkAll(List<String> names);

// record report { name: string, verdict: result<u32, problem> }
record Report(String name, WitResult<Integer, Problem> verdict) {
}
```

```console
switch (report.verdict()) {
    case WitResult.Ok<Integer, Problem>(Integer length) -> System.out.println(length);
    case WitResult.Err<Integer, Problem>(Problem problem) -> System.out.println(problem);
}
```

A method's answer may instead be declared as its ok arm (`void` for none): the error arm
is then thrown as an `am.ik.rontolisp.wit.WitException`, whose `error()` is the error of
its Java type (a case record, an enum constant, a string; `null` for none). The binding
follows the declaration, so `open` above answers a `Bucket` and throws. The host's binding
words the message `<function> of <interface> answered its error arm: ` followed by the
error's `toString()` (just the first part for `null`), so an uncaught arm names its error
in the log. A provider of the program's throws it for its own error arm.

```console
try {
    store.open("not-a-store-anyone-has");
}
catch (WitException e) {
    if (e.error() instanceof Store.Error.NoSuchStore) {
        System.out.println("no such store");
    }
}
```

A resource is released by `close()`: the handle goes back to the host, and the store stays.
A closed resource throws `IllegalStateException`, and a second `close()` does nothing. An
`own` handle given to a function passes to it, and the object is closed.

### What the host answers in pieces

An `async func` is a method like any other: the call waits for the host's answer. A
`stream<T>` of values the host answers is a `WitStream<T>`, whose `read(max)` waits until
the host has written a value or has gone and answers at most `max` of them, an empty list
at the end; a `future<T>` is a `WitFuture<T>`, whose `get()` waits for its one value. A
`future<result<T, E>>` answers its ok arm and throws a `WitException` for its error arm,
as a method answering a `result` does. Both are `AutoCloseable`: `close()` gives the
readable end back, and the host drops what was not read.

```console
// spin:postgres's query-async: the columns, the rows as the server sends them, the end
record QueryAsync(List<Column> columns, WitStream<List<DbValue>> rows, WitFuture<Void> done) {
}

QueryAsync started = connection.queryAsync("select id, title from note", List.of());
try (WitStream<List<DbValue>> rows = started.rows(); WitFuture<Void> done = started.done()) {
    for (List<List<DbValue>> chunk = rows.read(50); !chunk.isEmpty(); chunk = rows.read(50)) {
        ...
    }
    done.get();                                // the statement's refusal, if any
}
```

In a component the host writes the values, and what a read holds is that read's values,
whatever the stream holds. A stream of resources answers one resource a read (the
connections `wasi:sockets`' `listen` accepts). Under a JDK and on the interpreter and the
JVM the provider answers its own: `WitStream.of(iterator)`, `WitFuture.of(value)`,
`WitFuture.failed(exception)`, or a class of its own.

A `stream<u8>` the host answers is an `InputStream`, read as the host writes it; `close()`
gives the readable end back. A `stream<u8>` the program hands the host is a parameter of
type `Consumer<OutputStream>`: once the host holds the readable end, the binding hands the
consumer an `OutputStream` over the writable end, which the program may keep and write
after the call; a write waits until the host has read it all, and `close()` ends the
stream. A write after the host has dropped its end throws `IOException`.

```console
// tcp-socket's send: func(data: stream<u8>) -> future<result<_, error-code>>
WitFuture<Void> send(Consumer<OutputStream> data);

OutputStream[] writer = new OutputStream[1];
WitFuture<Void> sent = socket.send(out -> writer[0] = out);
try (OutputStream out = writer[0]) {
    out.write(request);
}
sent.get();
```

A read waits where it is, as a call does: every thread of the program waits with it. Under
a JDK and on the interpreter and the JVM the provider hands the consumer an `OutputStream`
of its own.

## Exporting a WIT world

A class annotated `@WitExport` implements a WIT world: each function the world exports is
a static method of the class, named in camel case, and each interface the world exports is
a static member class named after the interface in upper camel case
(`export docs:adder/add;` is `Add`), whose static methods are the interface's functions. It
is the Java side of
[`rontolisp:wit-export`](../guides/wit-contracts.md#implementing-a-wit-world-wit-export).

```console
package example:vowels;

world counter {
  export vowel-count: func(text: string) -> s32;
}
```

```console
import am.ik.rontolisp.wit.WitExport;

@WitExport(value = "counter", wit = "wit/vowels.wit")
public class Counter {

    public static int vowelCount(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if ("aeiouAEIOU".indexOf(text.charAt(i)) >= 0) {
                n++;
            }
        }
        return n;
    }
}
```

`value` is the world's name, and `wit` the WIT, found as an interface's is
([The interface](#the-interface)).

A program whose entry class has no `main` starts at the classes `@WitExport` marks in its
source tree, which join the program. Its entry points are their worlds' exports, and it
compiles to a reactor component: the component exports the worlds and no `wasi:cli/run`,
and the first call of an export runs the program's static initialization. A host, or a
component composed with it, calls the exports:

```bash
API="--java-source-path rontolisp-java-api/src/main/java"
rontolisp Counter.java $API -o counter.wasm --component
wasmtime run --invoke 'vowel-count("hello world")' counter.wasm   # 3
```

[`examples/java/rust-calls-java/`](https://github.com/making/rontolisp/tree/develop/examples/java/rust-calls-java)
composes it with a Rust component that calls `vowel-count`, and builds it with the Maven
plugin, whose `wasm` goal compiles the classes `@WitExport` marks when no main class is set.

With `--no-gc` too, a class of scalar values
([A reactor on `--no-gc`](index.md#a-reactor-on---no-gc)) compiles to a component of the
scalar backend, which runs its static initialization when it is instantiated. The counter
above, its `indexOf` written as comparisons, is 2,040 bytes there and 12,988 without
`--no-gc`. A world carrying a type other than `bool`, the integers, `f64` and `string` is
refused there at its WIT line: the scalar backend has no value for a record or a list.

Under a JDK, on the interpreter and on the JVM the annotation does nothing: `vowelCount` is
an ordinary static method, which a program or a test calls. A program with no `main` is
refused there, and on a module and `--native`: a world is exported by a component. A
program with a `main` is a command, whose `@WitExport` classes are exported nowhere. A
program with neither is a module's reactor:
[A reactor of public static methods](index.md#a-reactor-of-public-static-methods).

Each method is checked against its export when the program is compiled, on every target,
whether the program has a `main` or not: a missing method, one that is not static, a
parameter or a result of another type is refused at the declaration with the WIT line:

```console
Counter.java:6:24: vowelCount (vowel-count of world counter, wit/vowels.wit:4): its result
is s32, which is int in Java, not long
```

### The types of an export

The types are those of [the table above](#the-types), but the ones no world's export
carries, each refused at its WIT line: `flags`, a resource, a `stream`, a `future`, and a
type another interface defines (`use`). A named type is a member type, as an imported
interface's is: of the annotated class for a type the world defines, of the interface's
member class for a type an exported interface defines. A tuple is a record of the
program's, declared anywhere.

```console
interface geometry {
  record point { x: s32, y: s32 }
  variant shape { circle(f64), rect(point), dot }

  flip: func(p: point) -> point;
  area: func(s: shape) -> result<f64, string>;
}

world shapes {
  record summary { count: u32, total: f64 }

  export geometry;
  export summarize: func(areas: list<f64>) -> summary;
}
```

```console
@WitExport(value = "shapes", wit = "wit/shapes.wit")
public class Shapes {

    public record Summary(int count, double total) {
    }

    public static Summary summarize(List<Double> areas) {
        ...
    }

    public static class Geometry {

        public record Point(int x, int y) {
        }

        public sealed interface Shape {

            record Circle(double value) implements Shape {
            }

            record Rect(Point value) implements Shape {
            }

            record Dot() implements Shape {
            }
        }

        public static Point flip(Point p) {
            return new Point(p.y(), p.x());
        }

        public static double area(Shape s) {
            if (s instanceof Shape.Dot) {
                throw new WitException("a dot has no area", "a dot has no area");
            }
            ...
        }
    }
}
```

```bash
wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm   # {x: 2, y: 1}
wasmtime run --invoke 'area(dot)' shapes.wasm            # err("a dot has no area")
```

A method answering a `result` declares a `WitResult<T, E>`, or its ok arm (`void` for
none), as an imported interface's method does. The error arm is then a `WitException` the
method throws, whose `error()` is the error of its Java type (`null` for none); any other
exception is one the export leaves uncaught ([Limitations](#limitations)). An `Optional`,
a `List`, a record or a `WitResult` answered as `null` is a `NullPointerException` there.

[`examples/wit/shapes/Shapes.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes)
implements the world of the Lisp example beside it; the Lisp command of that example calls
either component, composed with it, and prints the same lines.

An unsigned integer is the Java type of its width holding its bits both ways: a `u32` of
4294967295 is the `int` -1, and the `int` -1 an export answers for a `u32` is 4294967295.

An `async func` export is a static method answering its value, as any other. The component
lifts it async, so the call may wait where the method blocks, as in a `Thread.sleep` or a call
of an imported `async func`; a sync export that blocks traps (`cannot block a synchronous
task`). It takes `bool`, the integers, `f64` and `string` alone (any other type in an
`async func` is refused at its WIT line), and `--no-gc` refuses it
([`rontolisp:wit-export`](../reference/functions/rontolisp-wit-export.md)).

```console
export pause: async func(ms: u32) -> u32;
```

```console
public static int pause(int ms) throws InterruptedException {
    Thread.sleep(ms);
    return ms;
}
```

## Limitations

- A WASM module takes the types `rontolisp:wit-import` takes there: a Preview 1 core module
  the flat set (the integers up to 32 bits, the floats, `bool`, `string`, `list<u8>`,
  handles), a component and a `--no-wasi` module everything but `flags`. A member of any
  other type is refused at its WIT line where the program calls it, as `wit-import` refuses
  it.
- A `u64` past `Long.MAX_VALUE` crosses no WASM boundary: the call traps. One exception:
  inside a compound value an export answers (a record's field, a list's element) a
  negative `long` is the `u64` of its bits.
- A native executable (`--native`) runs a Preview 1 module whose runner provides no WIT
  interface, so a program calling one does not start there.
- Refused at the declaration: a `future` the program hands the host (the binding could
  write it only after the call, and a host may read it only within a later call the program
  waits on: wasmtime's HTTP types read a request's trailers within `client.send`); a
  stream of values or of resources the program hands the host, which the guest cannot
  write; a `stream<u8>` the program hands an `async func` (the call waits while the host
  reads what the program writes only after it) or holds in another type; a `future` of a
  `bool`, a `list`, `flags` or an `option` (whose value the boundary cannot tell from a
  future its writer dropped); an
  `option` of a `bool`, a `list`, `flags` or an `option`, whose none the boundary cannot
  tell apart; a resource of another interface; a tuple in an error arm thrown as a
  `WitException`, for which no type is written (a `WitResult` answer carries it).
- A world's export carries no `flags`, resource, `stream` or `future` and no type of
  another interface; an `async func` export takes `bool`, the integers, `f64` and `string`
  alone, and is refused under `--no-gc`. Two exports of one label (a function of the world
  and an interface's, or two interfaces') are two methods, each of its own class.
- An exception an export leaves uncaught is reported on standard error by the line a JDK
  writes (`Exception in thread "main" java.lang.IllegalStateException: ...`), then the call
  traps; so is one the static initialization run by the first call leaves. A component
  compiled with `--no-wasi`, which has no standard error, or with `--no-gc` traps and writes
  nothing.
