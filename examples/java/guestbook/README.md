# A Servlet application: `guestbook/`

[`Guestbook.java`](src/main/java/guestbook/Guestbook.java) is a guestbook on the Jakarta
Servlet API: a page listing the entries with a form that signs the book, and a JSON API
over the same entries, its JSON built with [tiny-json](https://github.com/making/tiny-json).

| Request | Answer |
| --- | --- |
| `GET /guestbook/` | 302 to `/guestbook/entries` |
| `GET /guestbook/entries` | the HTML page: the entries, escaped, and the form |
| `POST /guestbook/entries` | a new entry from the form's `name` and `message`, then 302 back to the page; `sendError` 400 when one is blank |
| `GET /guestbook/api/entries` | the entries as JSON; `?name=Ann` keeps one author's |
| `GET /guestbook/api/entries/2` | one entry as JSON; `sendError` 404 when there is none |

Nothing is registered. The classes carry the Servlet API's annotations and the container
finds them: a filter on every path (`@WebFilter("/*")`: UTF-8 for the request and the
response, an `X-Content-Type-Options` field, a line in the context's log), the three
servlets by an exact path, a prefix and the context root (`@WebServlet`), and a context
listener that keeps the entries as a context attribute (`@WebListener`). `main` starts the
server, asked to discover them (`discover()`), and there is no `web.xml`. The entries are kept in memory, in a
`ConcurrentHashMap` by ids an `AtomicInteger` hands out, so the requests share them with no
lock; the name a visitor signed with last is kept in the visitor's session and fills the
form.

The application runs on
[`servlet-httpserver`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver),
a container on `com.sun.net.httpserver`, whose sources, the API's and tiny-json's are
compiled with the program ([A Servlet application](../../../doc/en/java/servlet.md)). It is a
Maven project ([`examples/java/`](../README.md)) and listens on the port of the `PORT`
environment variable, 8080 without one:

```bash
cd examples/java/guestbook
mvn package               # target/guestbook.war and target/guestbook.wasm (a component)
mvn exec:exec             # JDK
wasmtime serve --addr 127.0.0.1:8080 target/guestbook.wasm
mvn -Pnative package && ./target/guestbook    # native executable
```

From the repository's root, the command line compiles the same file for the interpreter and
the JVM:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
APP=examples/java/guestbook/src/main/java/guestbook/Guestbook.java
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT --java-source-dep am.ik.json:tiny-json:0.4.1 --java-snapshots"
java -jar $JAR $APP $LIBS                                        # interpreter
java -jar $JAR $APP $LIBS -o Guestbook.class && java Guestbook   # JVM
```

```bash
curl -d 'name=Ann&message=Hi+there' http://localhost:8080/guestbook/entries   # 302, location: entries
curl http://localhost:8080/guestbook/api/entries
```

```
[{"id":1,"name":"rontolisp","message":"Hello from the guestbook!"},{"id":2,"name":"Ann","message":"Hi there"}]
```

| Output | Size |
| --- | --- |
| `Guestbook.class` | 1.5 MB |
| `guestbook.wasm` (component) | 1.1 MB |
| `guestbook` (native, linux-x86_64) | 14 MB |
| `guestbook.war` | 36 KB |

A compiled program has no class path to look for the annotated classes in: rontolisp finds
them while it compiles, in the source tree (`src/main/java`) and under the source path
([A Servlet application](../../../doc/en/java/servlet.md#annotations)).

Under `wasmtime serve` the entries last as long as the host keeps the instance: wasmtime 51
hands one up to 128 requests and drops it after a second with none
([Deviations](../../../doc/en/java/deviations.md)).

With [`servlet-httpserver-keyvalue`](../../../servlet-httpserver-keyvalue) on the source path
the sessions are kept in the host's `wasi:keyvalue` store
([Sessions in `wasi:keyvalue`](../../../doc/en/java/servlet.md#sessions-in-wasikeyvalue)), with
no change to the program. On
[sluice-wicket-runtime-wasi](https://github.com/making/sluice/tree/main/sluice-wicket-runtime-wasi),
which runs each request in an instance of its own, the entries are gone at the next
request and the name is still there, after a restart of the host too:

```bash
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver-keyvalue:0.1.0-SNAPSHOT --java-source-dep am.ik.json:tiny-json:0.4.1 --java-snapshots"
java -jar $JAR $APP $LIBS -o guestbook.wasm --component
sluice-wicket-runtime-wasi --listen 127.0.0.1:8080 --keyvalue-file keyvalue.db \
    --wasm 'guestbook.local=guestbook.wasm?wasi=keyvalue'
```

```bash
curl -c jar -b jar -d 'name=Ann&message=Hi' http://localhost:8080/guestbook/entries
curl -c jar -b jar http://localhost:8080/guestbook/entries | grep value   # value="Ann"
```

`mvn exec:exec` runs the classes javac wrote with the API's jar, tiny-json's and the
container's on the class path, which the container reads for the annotated classes. A
servlet container takes the same classes as a WAR, `target/guestbook.war`, and finds the
annotated ones itself: the API and `servlet-httpserver` are `provided` dependencies, so
`WEB-INF/lib` holds tiny-json and what it depends on, and `main`, the one method naming
`servlet-httpserver`, is never called there.

Measured on Tomcat 11.0.27: every answer of the application is the same; the container's
own differ -- its error pages around `sendError`'s message, `Content-Language: en`, no
reason phrase on the status line.
