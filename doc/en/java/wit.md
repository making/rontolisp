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

One mapping serves both annotations. A named type is a member type of the class standing
for the WIT scope that defines it: the `@WitImport` interface for an imported interface;
for a world, the `@WitExport` class for the world's own types and its member class for an
exported interface's. Each is checked against its definition, once.

| WIT | Java | Declared |
| --- | --- | --- |
| `bool` | `boolean` | |
| `s8`, `u8` | `byte` | |
| `s16`, `u16` | `short` | |
| `s32`, `u32` | `int` | |
| `s64`, `u64` | `long` | |
| `f32`, `f64` | `float`, `double` | |
| `char` | `int`, the code point | |
| `string` | `String` | |
| `list<u8>` | `byte[]` | |
| `list<T>` | `List<T>`, `T` boxed | |
| `option<T>` | `Optional<T>` | |
| `result<T, E>` | `am.ik.rontolisp.wit.WitResult<T, E>`, `Void` for an arm carrying nothing; as a method's answer, also its ok arm, the error arm thrown as an `am.ik.rontolisp.wit.WitException` | `rontolisp-java-api` |
| `tuple<A, B>` | a record whose components are those types, in order | anywhere in the program |
| `record` | a record, a field a component in lower camel case | a member of the scope's class |
| `variant` | a sealed interface permitting a record for each case, the payload its one component | a member of the scope's class, the records inside it |
| `enum` | an enum, a case a constant (`NO_SUCH_STORE`) | a member of the scope's class |
| `flags` | a `Set` of an enum, a label a constant | a member of the scope's class |
| an alias (`type id = u32`) | the type it names | |
| a record, a variant, an enum or flags taken with `use` | as above, under the name the clause gives it | a member of the class of the scope taking it |
| a resource, `own<R>`, `borrow<R>` | an interface, its methods the resource's, `close()` its release | a member of the `@WitImport` interface |
| a resource taken with `use` | the interface standing for it in the other scope | a member of the `@WitImport` interface standing for the interface that defines it |
| `stream<u8>` the host answers | `java.io.InputStream` | |
| `stream<u8>` the program hands the host | `java.util.function.Consumer<java.io.OutputStream>`, handed the writable end; in an `option`, `Optional<Consumer<OutputStream>>` | |
| `stream<T>` of values or of resources | `am.ik.rontolisp.wit.WitStream<T>` | `rontolisp-java-api` |
| `future<T>` | `am.ik.rontolisp.wit.WitFuture<T>`; a `future<result<T, E>>` is a `WitFuture<T>` (`Void` for no ok value) whose `get()` throws the error arm | `rontolisp-java-api` |
| an `async func` | a method answering its value: an import's waits for the host's answer, an export's may block | |
| `error-context` | none: a WIT naming it is refused | |

Where each type may stand is [Where each type crosses](#where-each-type-crosses), which
output carries it [What each output takes](#what-each-output-takes), and what an unsigned
integer, a `u64` past `Long.MAX_VALUE`, `null` or a closed resource does
[At the boundary](#at-the-boundary).

The worked examples: the key-value `Store` at the top (a resource, an `option`, a record, a
`result` thrown), the `QueryAsync` rows and the socket's `send`
([What the host answers in pieces](#what-the-host-answers-in-pieces)), the HTTP request
([A future the program hands the host](#a-future-the-program-hands-the-host)), and the
`Shapes` world and its `pause` ([The types of an export](#the-types-of-an-export)).

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
An `own` handle given to a function passes to it, and the object is closed.

A resource an interface takes from another with `use` is declared once, in the
`@WitImport` interface standing for the interface that defines it, and named from there:
`wasi:http/client`'s `send` takes the `request` and answers the `response` of
`wasi:http/types`. That interface declares the member interface whether or not a function
of its own takes or answers the resource. A record, a variant, an enum or flags taken
with `use` is a member type of the interface taking it, declared there again.

```console
// interface client { use types.{request, response, error-code};
//                    send: async func(request: request) -> result<response, error-code>; }
@WitImport(value = "wasi:http/client@0.3.0", wit = "wit/http.wit")
public interface Client {

    Types.Response send(Types.Request request);

    sealed interface ErrorCode { ... }
}
```

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

An `option<stream<u8>>` parameter is an `Optional<Consumer<OutputStream>>`:
`Optional.empty()` hands the host no stream, and a consumer is handed the writable end as
above.

[`examples/wit/sockets/Echo.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/sockets) is an echo over
`wasi:sockets@0.3.0` that uses each of them, an `async func` and a `result` in both
spellings: against wasmtime's own network in a component, against a provider of its own
over `java.net` under a JDK, on the interpreter and on the JVM, with the same output.

### A future the program hands the host

A `future<T>` parameter is a `WitFuture<T>` the program made: `WitFuture.of(value)`,
`WitFuture.failed(exception)` for the error arm of a `future<result<T, E>>` (a
`WitException` whose `error()` is the error of its Java type), or a class of its own. In
a component the binding calls its `get()` once, before the call, and leaves the value
where the host finds it whenever it reads the future: within that call or within a later
one. The call does not wait for the host to read it, and nothing is left for the program
to release. Under a JDK and on the interpreter and the JVM the provider is handed the
object and calls `get()` when it reads.

```console
// request.new: static func(headers: headers, contents: option<stream<u8>>,
//     trailers: future<result<option<trailers>, error-code>>, options: option<request-options>)
//     -> tuple<request, future<result<_, error-code>>>;
Sent requestNew(Fields headers, Optional<Consumer<OutputStream>> contents,
        WitFuture<Optional<Fields>> trailers, Optional<RequestOptions> options);

record Sent(Request request, WitFuture<Void> transmission) {
}
```

```console
Types.Sent sent = types.requestNew(types.fieldsNew(), Optional.empty(),
        WitFuture.of(Optional.empty()), Optional.empty());      // no body, no trailers
sent.request().setAuthority(Optional.of("example.com"));
Types.Response response = client.send(sent.request());          // waits for the response
Types.Body body = types.responseConsumeBody(response, WitFuture.of(null));
try (InputStream in = body.contents()) {
    System.out.println(new String(in.readAllBytes(), StandardCharsets.UTF_8));
}
```

So a program is an HTTP client over `wasi:http@0.3.0` under `wasmtime run -S http=y`: it
sends a request with no body and reads the response. A request's body is the limit: a
write to the `OutputStream` waits until the host has read it, and wasmtime reads a
request's body within `client.send`, which the program is waiting on. A body that ends
at once (the consumer closes the stream) is sent, and its trailers are then read from
the future.

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
`--no-gc`. What it carries there is in [What each output takes](#what-each-output-takes).

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

An export's types are [the table's](#the-types), named the same way: a type of the world is
a member type of the annotated class, a type of an exported interface one of the
interface's member class, a type taken with `use` one of the class of the scope taking it,
under the clause's name, and a tuple any record of the program's. A resource, a `stream`
and a `future` are refused at their WIT line
([Where each type crosses](#where-each-type-crosses)).

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
exception is one the export leaves uncaught ([Limitations](#limitations)).

[`examples/wit/shapes/Shapes.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes)
implements the world of the Lisp example beside it; the Lisp command of that example calls
either component, composed with it, and prints the same lines.

An `async func` export is a static method answering its value, as any other, and it takes
the types any other export takes: a record, a list, a variant or a `result` crosses an
`async func` as it crosses a `func`. A component lifts it async, so the call may wait where
the method blocks, as in a `Thread.sleep` or a call of an imported `async func`; a plain
`func` that blocks traps (`cannot block a synchronous task`). What each output makes of it
is in [What each output takes](#what-each-output-takes).

```console
export pause: async func(ms: u32) -> u32;
export summarize: async func(areas: list<f64>) -> summary;
```

```console
public static int pause(int ms) throws InterruptedException {
    Thread.sleep(ms);
    return ms;
}

public static Summary summarize(List<Double> areas) throws InterruptedException {
    Thread.sleep(1);
    ...
}
```

[`examples/wit/shapes/Shapes.java`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes)'s `totalArea` is an
`async func` that sleeps and answers a `WitResult`; the Lisp command beside it binds the
interface with `:blocking t` and waits for the answer.

### Generating the skeleton

`--scaffold-wit` writes the class from the world when its output is a `.java` file, or
with `--source-language java` when it prints:

```bash
rontolisp --scaffold-wit wit/shapes.wit -o Shapes.java
```

```console
@WitExport(value = "shapes", wit = "wit/shapes.wit")
public class Shapes {

    public record Summary(int count, double total) {
    }

    /**
     * How many areas, and their sum.
     *
     * WIT: {@code summarize: func(areas: list<f64>) -> summary}
     */
    public static Summary summarize(List<Double> areas) {
        throw new UnsupportedOperationException("summarize is not implemented yet");
    }

    public static class Geometry {
        ...
        public static Optional<BoundsResult> bounds(List<Point> ps) {
            throw new UnsupportedOperationException("bounds is not implemented yet");
        }
    }
}
```

Each export is a static method that throws until it is filled in, the WIT's `///`
comments and its signature its Javadoc. Each type is declared where
[The types](#the-types) declares it: a `result` is a `WitResult`, a tuple a record named
after where it stands (`BoundsResult`), or after its alias (`type ipv4-address = tuple<...>`
is `Ipv4Address`), its components `v0`, `v1` and so on. Each interface the world imports is
a `@WitImport` member interface of the class, with its resources and types, and so is
each interface whose resource one of them takes with `use`; a method finds it with
`ServiceLoader.load(Shapes.Store.class)`. The class is named after the file, or after the
world when printed. The file compiles unchanged, so the exports can be filled in one at a
time.

The annotations name the WIT from the Java file's directory, so the WIT goes below it: a
path climbing above it is refused when the program compiles, and the generator warns.

What the check refuses is not written. The generator names each item it left out, in the
check's words, on standard error and at the top of the file:

```console
rontolisp: warning: --scaffold-wit left out flag (flag of test:skip/host, wit/app.wit:4):
its parameter x is option<bool>, an option of a type one of whose values the boundary
cannot tell from none
```

An imported function left out is absent from its interface, which declares what the
program calls. An export left out (a world exporting a resource, importing a function of
its own, or exporting a type refused here) leaves a class the check refuses until the WIT
changes.

## Where each type crosses

What a declaration may hold where, on a target that carries the type
([What each output takes](#what-each-output-takes)). Each refusal is made when the program
compiles, on every target, at the Java declaration, naming the WIT member's line.

| WIT | Import parameter | Import result | Export parameter | Export result | Inside another type |
| --- | --- | --- | --- | --- | --- |
| a scalar, `char`, `string`, `list`, `tuple`, `record`, `variant`, `enum`, `flags` | yes | yes | yes | yes | yes |
| `option<T>` | yes | yes | yes | yes | yes |
| `option` of a `bool`, a `list`, `flags` or an `option` | refused: its none is a value too | refused | refused | refused | refused |
| `result<T, E>` | yes | yes, or its ok arm with the error arm thrown | yes | yes, or its ok arm with the error arm thrown | yes, as a `WitResult` |
| a tuple in an error arm thrown as a `WitException` | | refused: no type is written for it (answer the `WitResult`) | | refused, likewise | |
| a resource of the interface | yes; an `own` handed over closes the object | yes | not yet: a Java class does not export a resource (a Common Lisp program does) | not yet | yes in an import (a record's field, a list's element) |
| a resource taken with `use` | yes, declared in the interface standing for the other one | yes, likewise | not yet | not yet | yes in an import |
| `stream<u8>` | yes, `Consumer<OutputStream>`, but not to an `async func`: the call waits while the host reads what the program writes after it | yes, `InputStream` | refused: an export carries no stream | refused | in an import result; in a parameter only as an `option` of its own |
| `stream<T>` of values or of resources | refused: the guest cannot write one | yes, `WitStream<T>` | refused | refused | in an import result |
| `future<T>` | yes, a `WitFuture<T>` the program made | yes, but not of a `bool`, a `list`, `flags` or an `option`: its value is a future its writer dropped too | refused: an export carries no future | refused | in an import result; in a parameter refused |
| `error-context` | refused: the WIT is refused as naming a type it does not define | | | | |

An exported interface that declares a resource, or whose `use` clause names one or a type
holding a handle, is refused at the world's `export` line whether or not a function takes
it. A `use` of a resource in the world itself is left out, and a function of the world
naming it is refused. A Common Lisp program exports a resource from a component
([`rontolisp:wit-export`](../reference/functions/rontolisp-wit-export.md#resources)), and a
Java program imports it from that component as any resource.

## What each output takes

| Output | `@WitImport` | `@WitExport` (a program with no `main`) | An `async func` export | An exception an export leaves uncaught |
| --- | --- | --- | --- | --- |
| `--component` | every type of [Where each type crosses](#where-each-type-crosses) | a reactor component exporting the worlds | lifted async: the call may wait where the method blocks | written to standard error as a JDK writes it, then the call traps |
| `--component --no-wasi` | refused: such a component imports nothing | as `--component` | lifted async, but the component imports no clock: a method that sleeps traps | the call traps, writing nothing |
| `--no-wasi` module, its host the `--emit-js-glue` glue | through the canonical ABI, every type but a `stream` and a `future`; no `async func`. Not reached from `main`: a JavaScript host may answer with a promise | refused: a world is exported by a component | | |
| Preview 1 module (`-o out.wasm`) | the flat set: the integers up to 32 bits, the floats, `bool`, `string`, `list<u8>`, handles; an `async func` of those a call | refused | | |
| `--no-gc` module | not yet: the scalar backend has no object for the binding `ServiceLoader` answers, so the lookup is refused | refused | | |
| `--no-gc --component` | not yet, likewise | of `bool`, the integers, `f64` and `string` alone; anything else refused at its WIT line | lifted async, of those types; nothing in the scalar subset waits | the call traps, writing nothing |
| `--native` | none: its runner provides no WIT interface, so a program calling a member of the flat set does not start, and any other member is refused | refused | | |
| a JDK, the interpreter, the JVM | the program's own provider, every type; nothing is converted | the annotation does nothing: ordinary static methods, and a program with no `main` is refused | the method, called as any other | as any exception |

On every target but a JDK the declarations are checked against the WIT. A member a target
does not carry is refused at its WIT line where the program calls it, as `wit-import`
refuses it.

## At the boundary

- An unsigned integer is the Java type of its width holding its bits, both ways: the `u8`
  200 is the `byte` -56 (`Byte.toUnsignedInt` gives 200 back), and the `int` -1 an export
  answers for a `u32` is 4294967295.
- A `u64` past `Long.MAX_VALUE` crosses no WASM boundary: an import answering one, and an
  export called with one, trap. An export's negative `long` answered for a `u64` traps too,
  but inside a compound value it answers (a record's field, a list's element) it is the
  `u64` of its bits.
- `null` is no WIT value. On a WASM target a call handing `null` to an import traps, and so
  does an export answering it, after writing a `NullPointerException` where its answer is an
  `Optional`, a `List`, a record or a `WitResult`. Under a JDK, on the interpreter and on
  the JVM the provider is handed the `null`.
- A closed resource throws `IllegalStateException` (`tcp-socket of wasi:sockets/types@0.3.0
  is closed`), and a second `close()` does nothing. A read of a closed `InputStream` throws
  `IOException`.

## Limitations

- A stream the program writes is read by the host when the host chooses: a write that
  waits for a read the host makes only within a later call never returns. So a
  `wasi:http` request carries no body under wasmtime, which reads it within `client.send`.
- An exception an export leaves uncaught, or one the static initialization run by the
  first call leaves, is reported on standard error by the line a JDK writes
  (`Exception in thread "main" java.lang.IllegalStateException: ...`), then the call traps.
  A component compiled with `--no-wasi`, which has no standard error, or with `--no-gc`
  traps and writes nothing.
- Two exports of one label (a function of the world and an interface's, or two
  interfaces') are two methods, each of its own class.
