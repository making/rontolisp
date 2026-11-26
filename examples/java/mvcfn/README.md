# A WebMvc.fn application: `mvcfn/`

Routes written as functions, in the style of Spring MVC's functional endpoints (WebMvc.fn):
[`Routing`](src/main/java/com/example/Routing.java) holds the routes and
[`Application`](src/main/java/com/example/Application.java) starts a `DispatcherServlet` over them on
[`servlet-httpserver`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver).

| Request | Answer |
| --- | --- |
| `GET /` | `Hello World!` |
| `GET /tasks` | the tasks as JSON; `?done=true` keeps the done ones |
| `POST /tasks` | a new task from a JSON body's `title`: 201 with its `Location`; 400 as JSON when the title is blank |
| `GET /tasks/1` | one task as JSON; 404 when there is none, 400 as JSON when the id is no number |
| `PUT /tasks/1` | sets `title` and `done` from a JSON body |
| `DELETE /tasks/1` | 204 |

The routes nest under `path("/tasks", ..)`, read a path variable, a query parameter and a
JSON body (`request.body(JsonObject.class)`), answer JSON, and have a filter that prints
each request with its status and an error handler that turns an `IllegalArgumentException`
into a 400. The JSON is [tiny-json](https://github.com/making/tiny-json)'s `JsonObject` and
`JsonArray`, read and written by a converter of the example's own,
[`TinyJsonHttpMessageConverter`](src/main/java/com/example/TinyJsonHttpMessageConverter.java) (an
`AbstractHttpMessageConverter`). The tasks are kept in memory: records in a
`ConcurrentHashMap`, replaced by `computeIfPresent`, by ids an `AtomicInteger` hands out, so
the requests share them with no lock.

The classes of `org.springframework` are those of
[`webmvc-fn-lite`](https://github.com/making/rontolisp/tree/develop/webmvc-fn-lite), a part
of WebMvc.fn under Spring Framework's names that is compiled with the program
([A WebMvc.fn application](../../../doc/en/java/webmvc-fn.md)). It is a Maven project
([`examples/java/`](../README.md)) and listens on the port of the `PORT` environment variable,
8080 without one:

```bash
cd examples/java/mvcfn
mvn package               # target/mvcfn.wasm, a component
mvn exec:exec             # JDK
wasmtime serve --addr 127.0.0.1:8080 target/mvcfn.wasm
mvn -Pnative package && ./target/mvcfn    # native executable
```

From the repository's root, the command line compiles the same files for the interpreter and
the JVM:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
APP=examples/java/mvcfn/src/main/java/com/example/Application.java
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT --java-source-dep am.ik.spring:webmvc-fn-lite:0.1.0-SNAPSHOT --java-source-dep am.ik.json:tiny-json:0.4.1 --java-snapshots"
java -jar $JAR $APP $LIBS                                   # interpreter
java -jar $JAR $APP $LIBS -o Mvcfn.class && java Mvcfn      # JVM
```

```bash
curl -H 'Content-Type: application/json' -d '{"title": "Compile it to wasm"}' http://localhost:8080/tasks
curl http://localhost:8080/tasks
```

```
{"id":2,"title":"Compile it to wasm","done":false}
[{"id":1,"title":"Write the routes","done":false},{"id":2,"title":"Compile it to wasm","done":false}]
```

| Output | Size |
| --- | --- |
| `Mvcfn.class` | 1.6 MB |
| `mvcfn.wasm` (component) | 1.1 MB |
| `mvcfn` (native, linux-x86_64) | 14 MB |

Under `wasmtime serve` the tasks last as long as the host keeps the instance
([A Servlet application](../../../doc/en/java/servlet.md#sessions)).

The classes compile against Spring Framework too, unchanged: with `spring-webmvc` 7.1 in the place
of the module's jar the answers are the same, request for request (measured with Spring
Framework's `ServletContainerInitializer` kept from `servlet-httpserver`, which refuses an
initializer that asks for classes; on embedded Tomcat only `main` differs).
