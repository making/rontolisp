# Java sample programs

Programs for the **experimental** subset of the Java language: `.java` files read as that
subset, as described in the [Java section](../../doc/en/java/index.md). Each is checked in
[`examples.yaml`](../examples.yaml).

## An order book: `shop/`

It runs identically on the interpreter, the JVM, WASM and `--component`.

`shop/` is an order book of six packages, one class to a file. Running `shop/Main.java`
reads the rest from the source tree, `examples/java/`: `shop.catalog.Item` is
`shop/catalog/Item.java`.

| Package | Shows |
| --- | --- |
| [`shop`](shop/Main.java) | The entry point: single-type and static imports, an order refused by the program's own exception, one thread per order joined before the report, `printf` |
| [`shop.util`](shop/util) | `Money`, a static method imported by name elsewhere |
| [`shop.catalog`](shop/catalog) | An enum; an abstract class with a `protected` field and two subclasses, `ArrayList` |
| [`shop.order`](shop/order) | A record whose compact constructor validates; a sealed interface of two nested records; `Optional` |
| [`shop.pricing`](shop/pricing) | A functional interface with a `default` method, lambdas and method references, a `switch` over the sealed interface with record patterns and a guard, package-private helpers |
| [`shop.report`](shop/report) | `synchronized` methods the threads share, `HashMap.merge`, a `Comparator` chain |

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/java/shop/Main.java                                # interpreter
java -jar $JAR examples/java/shop/Main.java -o Main.class && java Main     # JVM
java -jar $JAR examples/java/shop/Main.java -o shop.wasm && wasmtime run shop.wasm
java -jar $JAR examples/java/shop/Main.java -o shop-c.wasm --component && wasmtime run shop-c.wasm
```

A JDK 25 runs it as it stands (`java examples/java/shop/Main.java`), printing the same
output.

## An HTTP server and its client: `http/`

[`http/Server.java`](http/Server.java) is a server on the JDK's own API,
`com.sun.net.httpserver`: `GET /`, `GET /greet?name=Ann`, `POST /echo` (the body as it
came, under the request's `Content-Type`; a 405 for any other method) and `GET /countdown`, whose body is written in chunks.
[`http/Client.java`](http/Client.java) is `java.net.http`'s client asking those four.

One source is a server on every target:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/java/http/Server.java                                  # interpreter, port 8080
java -jar $JAR examples/java/http/Server.java -o Server.class && java Server   # JVM, port 8080
java -jar $JAR examples/java/http/Server.java -o server --native && ./server   # native executable, port 8080

# wasi:http: the component is a wasi:http handler, and the host owns the port
java -jar $JAR examples/java/http/Server.java -o server.wasm --component
wasmtime serve -S cli=y --addr 127.0.0.1:8080 server.wasm
```

```bash
curl http://localhost:8080/                       # Hello from com.sun.net.httpserver on rontolisp, curl/8.x
curl 'http://localhost:8080/greet?name=Ann'       # Hello, Ann!
curl --json '{"foo": 100}' http://localhost:8080/echo   # {"foo": 100}
curl -N http://localhost:8080/countdown           # 3, 2, 1, liftoff: a line a chunk
```

Under `wasmtime serve` the host starts the program -- `main`, to its end -- for the
requests it likes, the port is `--addr`'s, not the one in the source, and each line of
`/countdown` is on its way when its write returns; the other targets send that body whole.

`Client.java` serves the same application itself, on a free local port, asks it and stops
it, so it needs a target with threads:

```bash
java -jar $JAR examples/java/http/Client.java                                  # interpreter
java -jar $JAR examples/java/http/Client.java -o Client.class && java Client   # JVM
```

```
200 Hello from com.sun.net.httpserver on rontolisp, examples/java
content-type: text/plain; charset=utf-8
200 Hello, Ann!
200 application/json {"foo": 100}
405 allow POST: POST a body to /echo
200 3 2 1 liftoff
404 no route for GET /missing
```

A JDK 25 runs both as they stand (`java examples/java/http/Server.java`,
`java examples/java/http/Client.java`).

## A miniature httpbin: `httpbin/`

[`httpbin/Httpbin.java`](httpbin/Httpbin.java) is
[`net/httpbin.lisp`](../net/httpbin.lisp) on `com.sun.net.httpserver`: `GET /get`,
`POST /post`, `PUT /put`, `PATCH /patch` and `DELETE /delete` answer a JSON document
describing the request -- `args` from the query, `headers`, `method`, `path`, and for a
body `data` (its text) and `json` (the body itself when it is one JSON value, else
`null`). A wrong method is a 405, an unknown path a 404. The program writes the JSON
itself, and checks a body's without parsing it, so what it hands back is what came. It
listens on the port of the `PORT` environment variable, 8080 without one:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/java/httpbin/Httpbin.java                                    # interpreter
java -jar $JAR examples/java/httpbin/Httpbin.java -o Httpbin.class && java Httpbin   # JVM
java -jar $JAR examples/java/httpbin/Httpbin.java -o httpbin --native && ./httpbin   # native executable

java -jar $JAR examples/java/httpbin/Httpbin.java -o httpbin.wasm --component
wasmtime serve -S cli=y --addr 127.0.0.1:8080 httpbin.wasm
```

```bash
curl 'http://localhost:8080/get?a=1&b=two'
curl --json '{"name": "rontolisp"}' http://localhost:8080/post
```

```
{
  "args": {},
  "data": "{\"name\": \"rontolisp\"}",
  "headers": {
    "Accept": "application/json",
    "Content-length": "21",
    "Content-type": "application/json",
    "Host": "localhost:8080",
    "User-agent": "curl/8.5.0"
  },
  "json": {"name": "rontolisp"},
  "method": "POST",
  "path": "/post"
}
```

A field's name is as `com.sun.net.httpserver` hands it, its first letter upper and the
rest lower; under `wasmtime serve` there is no `Host`. A JDK 25 runs it as it stands
(`java examples/java/httpbin/Httpbin.java`).

## Validation with YAVI: `yavi/`

Two programs over [YAVI](https://github.com/making/yavi), one for each of its styles.

[`yavi/Member.java`](yavi/Member.java) checks a sign-up record with `ValidatorBuilder` and
prints each violation's message: a blank, a malformed address, numbers out of range, a
size over its limit, and a nickname of six `char`s that YAVI counts as three, composed.

[`yavi/Booking.java`](yavi/Booking.java) reads a record with a schema (`Yavi.schema()` ..
`fromMap`) from an input of unknown type -- the map a JSON body decodes to, then the
strings a form sends -- and makes the record only of a valid input: an enum from its
constant's name, a `LocalDate` from its text, a `long` or a `double` as the `Integer`
asked for, and a violation for a property that is missing, of another type or out of its
limits.

YAVI is not built in. `--java-source-dep` reads it from its sources jar (Maven Central,
through `~/.m2/repository`) and compiles what the program reaches with the program, on
every backend:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
DEP="--java-source-dep am.ik.yavi:yavi:0.18.0"
java -jar $JAR $DEP examples/java/yavi/Member.java                                  # interpreter
java -jar $JAR $DEP examples/java/yavi/Member.java -o Member.class && java Member   # JVM
java -jar $JAR $DEP examples/java/yavi/Member.java -o member.wasm && wasmtime run member.wasm
java -jar $JAR $DEP examples/java/yavi/Member.java -o member-c.wasm --component && wasmtime run member-c.wasm
```

```
Ann: valid
kana, 6 chars: valid
typos: 4 violations
  "name" must not be blank
  "email" must be a valid email address
  "age" must be greater than or equal to 0
  The size of "nickname" must be less than or equal to 3. The given size is 5
empty: 3 violations
  "name" must not be blank
  "email" must not be blank
  "age" must not be null
long: 2 violations
  The size of "name" must be less than or equal to 20. The given size is 22
  "age" must be less than 150
```

`Booking.java` runs the same way:

```
as sent: Booking[guest=Ann, room=TWIN, checkIn=2026-12-24, nights=3]
a long: Booking[guest=Ken, room=SUITE, checkIn=2027-01-02, nights=14]
a double: Booking[guest=Ken, room=SUITE, checkIn=2027-01-02, nights=2]
a fraction: 1 violations
  "nights" must be of type integer
other types: 4 violations
  "guest" must be of type string
  "room" must be one of the following values: [SINGLE, TWIN, SUITE]
  "checkIn" must be of type LocalDate
  "nights" must be of type integer
out of limits: 3 violations
  "guest" must not be blank
  "checkIn" has to be after or equals to 2026-12-01
  "nights" must be less than or equal to 14
nothing: 4 violations
  "guest" is required
  "room" is required
  "checkIn" is required
  "nights" is required
a form: Booking[guest=Ann, room=SINGLE, checkIn=2026-12-31, nights=2]
a form's typo: 2 violations
  "checkIn" must be of type LocalDate
  "nights" must be of type integer
```

`member.wasm` is about 660 KB and `booking.wasm` about 1.2 MB: what each program reaches
of YAVI, not the whole library. A schema reaches more, because YAVI's reader of an untyped
input names every type it can read one as, whichever of them the schema asks for.
Whether a library works is decided by what the program reaches of it: only what the front
end supports compiles, and a class the subset lacks is an error at the place in the
library that reaches it. See
[Using a third-party library](../../doc/en/java/index.md#using-a-third-party-library)
([サードパーティのライブラリを使う](../../doc/ja/java/index.md#サードパーティのライブラリを使う)).

A JDK 25 runs both as they stand, with YAVI on the class path:
`java -cp yavi-0.18.0.jar examples/java/yavi/Member.java`.
