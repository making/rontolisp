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
