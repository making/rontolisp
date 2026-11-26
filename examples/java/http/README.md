# An HTTP server and its client: `http/`

[`http/Server.java`](Server.java) is a server on the JDK's own API,
`com.sun.net.httpserver`: `GET /`, `GET /greet?name=Ann`, `POST /echo` (the body as it
came, under the request's `Content-Type`; a 405 for any other method) and `GET /countdown`, whose body is written in chunks.
[`http/Client.java`](Client.java) is `java.net.http`'s client asking those four.

One source is a server on every target:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/java/http/Server.java                                  # interpreter, port 8080
java -jar $JAR examples/java/http/Server.java -o Server.class && java Server   # JVM, port 8080
java -jar $JAR examples/java/http/Server.java -o server --native && ./server   # native executable, port 8080

# wasi:http: the component is a wasi:http handler, and the host owns the port
java -jar $JAR examples/java/http/Server.java -o server.wasm --component
wasmtime serve --addr 127.0.0.1:8080 server.wasm

# a reactor: a module its host calls for each request, as a Cloudflare Worker does
java -jar $JAR examples/java/http/Server.java -o worker.wasm --no-wasi --emit-js-glue
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
The `--no-wasi` module binds no port either: `worker.js`, written beside it, is the
JavaScript that makes a Cloudflare Worker of it
([`cloudflare-workers/hello-servlet-one-source`](../../cloudflare-workers/hello-servlet-one-source)
is such a project).

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
