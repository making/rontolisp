# A miniature httpbin: `httpbin/`

[`Httpbin.java`](src/main/java/httpbin/Httpbin.java) is
[`net/httpbin.lisp`](../../net/httpbin.lisp) on `com.sun.net.httpserver`: `GET /get`,
`POST /post`, `PUT /put`, `PATCH /patch` and `DELETE /delete` answer a JSON document
describing the request -- `args` from the query, `headers`, `method`, `path`, and for a
body `data` (its text) and `json` (the body itself when it is one JSON value, else
`null`). A wrong method is a 405, an unknown path a 404. The document is built with
[tiny-json](https://github.com/making/tiny-json) (its sources jar is compiled with the
program), which also checks a body's JSON and writes the document two spaces a level,
arrays on one line (`JsonWriter.pretty().inlineArrays()`); the program hands the body back as it came, not parsed and written
again. It is a Maven project ([`examples/java/`](../README.md)) and listens on the port of the
`PORT` environment variable, 8080 without one:

```bash
cd examples/java/httpbin
mvn package               # target/httpbin.wasm, a component
mvn exec:exec             # JDK
wasmtime serve --addr 127.0.0.1:8080 target/httpbin.wasm
mvn -Pnative package && ./target/httpbin    # native executable
```

From the repository's root, the command line compiles the same file for the interpreter and
the JVM:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
APP=examples/java/httpbin/src/main/java/httpbin/Httpbin.java
LIBS="--java-source-dep am.ik.json:tiny-json:0.4.1"
java -jar $JAR $APP $LIBS                                    # interpreter
java -jar $JAR $APP $LIBS -o Httpbin.class && java Httpbin   # JVM
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
rest lower; under `wasmtime serve` there is no `Host`.
