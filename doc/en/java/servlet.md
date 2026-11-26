# A Servlet application

A program written against the Jakarta Servlet API serves on every target of the
[subset](index.md) when it runs on a servlet container the subset can compile.
[`servlet-httpserver`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver)
is one: a container on the JDK's own HTTP server (`com.sun.net.httpserver`), which the
subset has on the interpreter, the JVM, a WASM component and a native executable
([HTTP](index.md#http)). It lives in this repository for now and depends on nothing of
rontolisp. Under a JDK it needs Java 21.

The container's sources jar and the API's go on the program's
[source path](index.md#using-a-third-party-library) and are compiled with it. The
container is a `-SNAPSHOT` on Central's snapshot repository, which `--java-snapshots` adds:

```console
public class Hello {

    @WebServlet("/hello")
    public static class HelloServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getWriter().println("Hello, " + request.getParameter("name") + "!");
        }

    }

    public static void main(String[] args) throws Exception {
        ServletHttpServer.builder().port(8080).discover().start();
    }

}
```

(the imports are `java.io.IOException`, `am.ik.servlet.httpserver.ServletHttpServer`,
`jakarta.servlet.annotation.WebServlet` and the three of `jakarta.servlet.http`)

```bash
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT --java-snapshots"
rontolisp Hello.java $LIBS                                     # interpreter
rontolisp Hello.java $LIBS -o Hello.class && java Hello        # JVM
rontolisp Hello.java $LIBS -o hello.wasm --component && wasmtime serve hello.wasm
rontolisp Hello.java $LIBS --native -o hello && ./hello        # native executable
rontolisp Hello.java $LIBS -o worker.wasm --no-wasi --emit-worker-entry   # a reactor: a Cloudflare Worker
```

Under `wasmtime serve` the host listens (`--addr`, `0.0.0.0:8080` by default) and the port
the program gives is not bound. A program holds what it reaches of the API and the
container, not all of them. The `--no-wasi` module is called by its host for each request
and binds no port either: [A Cloudflare Worker](index.md#a-cloudflare-worker).

## Defining a server

`ServletHttpServer.builder()` takes where the server listens first (`port(int)` or
`address(InetSocketAddress)`); the rest is optional and in any order. `start()` binds the
address, registers what the builder was given in its order, initializes the application and
serves. `build()` returns the definition instead: nothing changes it, and each `start()` of
it starts a new server.

```console
ServletHttpServer server = ServletHttpServer.builder()
    .port(8080)
    .contextPath("/app")
    .servlet("hello", (request, response) -> response.getWriter().println("Hello!"),
            servlet -> servlet.urlPatterns("/hello"))
    .filter("log", (request, response, chain) -> {
        chain.doFilter(request, response);
        request.getServletContext().log(request.getRequestURI() + " " + response.getStatus());
    }, filter -> filter.urlPatterns("/*"))
    .onStartup(context -> context.log("started"))
    .sessions(sessions -> sessions.timeout(Duration.ofMinutes(30)).cookie(cookie -> cookie.name("SID")))
    .start();
server.port();                        // the bound port: port 0 binds a free one
server.stop(Duration.ofSeconds(5));   // waits for the requests in hand; stop() cuts them
```

- **A servlet** is an instance (an `HttpServlet`), a class made by its public constructor of
  no parameter, or a lambda of the request and the response, which answers every request
  mapped to it whatever its HTTP method. Its options: `urlPatterns`, `loadOnStartup`,
  `asyncSupported`, `initParameter`, `multipartConfig`.
- **A filter** is an instance, a class, or a lambda of the request, the response and the
  chain; `chain.doFilter(request, response)` hands the request on. Its options:
  `urlPatterns`, `servletNames`, `dispatcherTypes` (`REQUEST` unless given),
  `asyncSupported`, `initParameter`. The builder's filters run ahead of those the
  application declares.
- **A listener** is an instance or a class of a listener of the context, a request or a
  session. `onStartup(context -> ..)` and `onShutdown(context -> ..)` are a context
  listener's two events as lambdas; what `onStartup` registers through the context is the
  application's.
- `discover()` adds what the application declares ([Annotations](#annotations)),
  `documentRoot(path)` a directory of resources ([Static resources](#static-resources)),
  `sessions(..)` the store, the timeout, the tracking modes and the cookie
  ([Sessions](#sessions)), `executor(..)` what runs the requests (a virtual thread a
  request unless given). Under a JDK up to 1,024 connections wait
  to be accepted (the system's limit permitting).
- `errorPage(..)` answers an error by a page of the application ([Error pages](#error-pages)),
  `maxFormSize(bytes)` sets the largest form read into parameters (2 MiB unless given, a
  negative number for none), `log((message, thrown) -> ..)` takes what
  `ServletContext.log` and the container write (the standard error unless given), and
  `https(sslContext)` serves HTTPS under a JDK.
- A servlet's or a filter's name given twice, or a URL pattern two servlets map, fails
  `start()` with a `ServletException`.
- A program holds the parts of the builder it calls: one that does not call `discover()`
  holds nothing of discovery, one that gives no `multipartConfig` nothing of the reading of
  parts.

## Annotations

`discover()` registers what the application declares, after what the builder was given.
At `start()` the container finds the classes that carry `@WebServlet`, `@WebFilter` or
`@WebListener` (with their `@WebInitParam`s) and registers each as the annotation says, in
the order of the class names; then it runs every `ServletContainerInitializer` a
`META-INF/services/jakarta.servlet.ServletContainerInitializer` file names. One that asks
for classes with `@HandlesTypes` is handed the classes that extend or implement a type it
names, and those that carry an annotation it names on themselves, a field, a constructor or
a method; `null` where there are none. A class has to be `public` with a `public`
constructor of no parameter. `web.xml` is not read.

Where the classes are looked for depends on what runs the program, and the container's
code is the same:

- **Under a JDK** the container reads the class path: every directory and archive of
  `java.class.path`, the archives their manifests name (`Class-Path`, as an executable
  jar names its libraries), and those of a `URLClassLoader` the program's class loader is
  or has as a parent. The module path is not read. The program is compiled first
  (`javac`); the classes of a file handed to the source launcher (`java Hello.java`) are
  not on the class path, and none is found.
- **Run or compiled by rontolisp** a program has no class path. The annotated
  classes are found while it is read -- every class of the program's source tree and
  of the roots of its [source path](index.md#using-a-third-party-library) that carries
  the annotation, whether the program names it or not -- and the container reads their
  names from an index, `META-INF/annotations/<annotation type>`, a name a line
  ([Deviations](deviations.md#what-differs)). The source tree is the directory the
  program's package starts in: a file of it that carries one of the annotations is part
  of the program. An initializer that asks for classes with `@HandlesTypes` fails the
  start: they are found among class files.

The classes are the API's own types and annotations, so the same classes deploy to another
container as a WAR with no `web.xml`. What the container has -- mapping, filters, forward
and include, parameters and bodies, buffered responses, sessions, asynchronous requests,
multipart bodies, static resources, error pages -- and what it refuses (security,
trailer fields, protocol upgrades) is in its
[README](https://github.com/making/rontolisp/blob/develop/servlet-httpserver/README.md).

[`examples/java/guestbook/`](https://github.com/making/rontolisp/tree/develop/examples/java/guestbook)
is an application of more than one servlet -- a filter, three servlets and a listener found
by their annotations, a form, a redirect, error answers and a JSON API -- with how to run
it on the four targets, on a JDK and as a WAR on Tomcat.

[`examples/java/shiori/`](https://github.com/making/rontolisp/tree/develop/examples/java/shiori)
is a whole web application over PostgreSQL -- a reading journal, the Java version of
[`examples/db/shiori`](https://github.com/making/rontolisp/tree/develop/examples/db/shiori) --
on [WebMvc.fn](webmvc-fn.md), [JDBC](jdbc.md), jmustache's templates, YAVI's forms and
tiny-json. Served as a component, a program that opens a socket needs `-S cli=y` and
`-S inherit-network=y` of `wasmtime serve`; its README says why, and what the four targets
answer and cost.

## Error pages

`errorPage(status, location)` answers a status, `errorPage(ExceptionType.class, location)`
an exception a servlet or a filter throws -- the page of its nearest class, else of the
cause of a `ServletException`, else the page of 500 -- and `errorPage(location)` any other
error. The location is a path of the context, starting with a slash.

```console
ServletHttpServer.builder()
    .port(8080)
    .errorPage(404, "/not-found")
    .errorPage(IllegalArgumentException.class, "/bad-request")
    .errorPage("/error")
```

`sendError` leaves the response committed (`isCommitted()` is true) and the page is
written once the servlet returned: the container dispatches to it as an `ERROR` dispatch
(the filters mapped to `ERROR` run), by `GET` whatever the request's method, the status
the one asked for unless the page sets another, the fields set before kept. The request
tells the error by the attributes of `RequestDispatcher`: `ERROR_STATUS_CODE`,
`ERROR_MESSAGE`, `ERROR_EXCEPTION` and `ERROR_EXCEPTION_TYPE` (the cause of a
`ServletException`), `ERROR_REQUEST_URI`, `ERROR_SERVLET_NAME`, `ERROR_QUERY_STRING`,
`ERROR_METHOD`. With no page the container writes its own. An exception thrown once the
response is committed cuts the connection under a JDK, so the client sees the body is not
whole.

## Sessions

`request.getSession()` makes a session the client keeps by a cookie (`JSESSIONID`,
`HttpOnly`, the context's path); `changeSessionId()`, `invalidate()`, the interval and
the session listeners work as the API says. The sessions are kept in the store the program
gives the builder (`sessions(sessions -> sessions.store(store))`, a `SessionStore`), else
in the first store a `META-INF/services/am.ik.servlet.httpserver.SessionStore` file on the
class path or the source path names, else in the server's memory. A session in memory
lasts as long as what holds the memory:

- **The interpreter, the JVM, a native executable**: the process.
- **A component under `wasmtime serve`**: the instance, which the host replaces. The host
  hands an instance 128 requests at most (`--max-instance-reuse-count`), and requests that
  come at once go to several instances, each with sessions of its own. The host drops an
  instance idle for 1 s (`--idle-instance-timeout`), its sessions with it;
  `--idle-instance-timeout 60s` keeps them while requests come at least once a minute. A
  session therefore survives a sequence of requests, not a browser that sends several at
  once.
- **A `--no-wasi` reactor, a Cloudflare Worker**: the instance, which lasts as long as its
  host keeps it (a Worker's isolate) and is replaced when a call of it fails. A session's
  id is drawn from `SecureRandom`, so the build needs `--host-random`.

### Sessions in `wasi:keyvalue`

[`servlet-httpserver-keyvalue`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver-keyvalue)
keeps the sessions in a key-value store. On the source path in place of the container,
which it brings with [`rontolisp-java-api`](wit.md), it is the store the server finds, with
no code of the program's:

```bash
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver-keyvalue:0.1.0-SNAPSHOT --java-snapshots"
rontolisp Hello.java $LIBS -o hello.wasm --component
```

In a component the store is the host's `wasi:keyvalue/store@0.2.0-draft2`, which outlives
the instance and, where the host keeps it on disk, the host's restart. Under a JDK, on the
interpreter and on the JVM it is a store in memory, as long as the process.

- **A host with `wasi:keyvalue@0.2.0-draft2`**:
  [sluice-wicket-runtime-wasi](https://github.com/making/sluice/tree/main/sluice-wicket-runtime-wasi)
  keeps it in a SQLite file (`--listen 127.0.0.1:8080 --wasm 'hello.local=hello.wasm?wasi=keyvalue'`).
  `wasmtime serve -S keyvalue=y` provides `0.2.0-draft` only, and refuses the component
  at its start (`component imports instance wasi:keyvalue/store@0.2.0-draft2, but a
  matching implementation was not found`); without the library the component runs there
  as before, its sessions in memory.
- **A Cloudflare Worker**: a `--no-wasi` module imports the store through the canonical
  ABI, and the glue `--emit-js-glue` writes answers it over the Workers KV namespace bound
  as `KEYVALUE`
  ([`wasi:keyvalue` over Workers KV](../guides/wasm-host-boundary.md#wasikeyvalue-over-workers-kv)).
  A request's writes are put after its response, one per key and at most one a second,
  since Workers KV takes one write a second to a key, so a request waits for KV's reads
  only. A session written in one location is seen in another up to a minute later, and a
  request sent before the previous one's write is done may read the session before it.
  [`hello-session-one-source`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-session-one-source)
  is one:

  ```bash
  rontolisp HelloSession.java $LIBS -o src/worker.wasm --no-wasi --host-random --emit-worker-entry
  ```
- **A native executable** has no WIT import: the program is refused when it is compiled
  with the library.
- **An attribute is a value of a closed set**: a `String`, a `Boolean`, a `Character`, a
  `Byte`, a `Short`, an `Integer`, a `Long`, a `Float`, a `Double`, a `byte[]`, and a
  `List`, a `Set` or a `Map` of them (read back as an `ArrayList`, a `LinkedHashSet`, a
  `LinkedHashMap`). `setAttribute` of another value throws `IllegalArgumentException` and
  the session is as it was. Java serialization is not used.
- **The session is written whole at every change** -- each `setAttribute`, and a request
  whose access time moved by a tenth of the session's interval, at most a minute, since it
  was written -- under the key `servlet-httpserver:session:<id>` of the default store
  (`""`). A request that only reads its session writes nothing more often, so the session
  may expire, and `getLastAccessedTime()` read, that much earlier. A value changed in
  place is not written until it is set again. Of two requests of one session at once, the
  last write wins.
- **The expired sessions** are looked for over every instance by listing the store's keys,
  a turn at a time: a turn reads at most 16 sessions, the next turn goes on a minute later,
  and a pass over every key starts ten minutes after the last. A session is invalidated,
  and its listeners told, by the instance that finds it expired.

## Asynchronous requests

A servlet registered with `asyncSupported` (`@WebServlet(urlPatterns = "/..", asyncSupported = true)`)
may call `request.startAsync()` when every filter before it is registered so too. The response
is then finished by `AsyncContext.complete()` from any thread, by `AsyncContext.dispatch(..)`
(the filters mapped to `DispatcherType.ASYNC` run), or by the timeout (30 seconds unless
`setTimeout`). The task handed to `AsyncContext.start(task)` runs on the server's executor.
The container tells the `AsyncListener`s of the completion, the timeout and the errors, and
calls a `ReadListener` and a `WriteListener`.

- **A request in asynchronous mode holds no thread.** Once the dispatch that called
  `startAsync` returned, its thread goes on to other requests, under a JDK and on every
  target of rontolisp. What the container owes the request then -- the dispatch, the
  listeners, the answer to a timeout or an error, the end of the response -- runs on a
  thread of the executor when it is asked for: by `complete()`, by `dispatch(..)`, by the
  timeout, which one thread of the server waits for (made by the first request that has
  one). An error another thread reports, and the server's stop, run where they are
  reported.
- **A response that fails once committed after the dispatch returned ends as it is.** The
  container cuts such a response only while the thread the server called the handler on is
  there: `com.sun.net.httpserver` has no other way to cut an exchange.
- **The streams' reads and writes block.** `isReady()` is always true. The container calls
  `onDataAvailable` and `onWritePossible` again only after `isReady()` returned false, so
  each is called once, and a read in them may wait for the client.
- **Under `wasmtime serve`** another request can complete a waiting request's
  `AsyncContext` only when the host hands both to one instance, whose memory holds it.
  Requests that come at once may go to several instances (as with [sessions](#sessions)).

## Multipart bodies

A servlet with a multipart configuration (`@MultipartConfig`, or `multipartConfig(..)` among
its options) reads a `multipart/form-data` body with `request.getParts()` and
`getPart(name)`; the parts without a file name are also its parameters. A part is kept in
memory up to the configuration's `fileSizeThreshold` and in a file past it, in the
`location` directory (an absolute one as given, else below `java.io.tmpdir`), deleted once
the request is complete; `part.write(name)` keeps it.

- **A program run or compiled by rontolisp has no `java.io.tmpdir`**, so its parts stay in
  memory unless `location` is an absolute directory (a component's host may give it none).
  `maxFileSize` and `maxRequestSize` bound what memory holds.
- **The limits** are an `IllegalStateException` of `getParts()` and `getParameter(..)`. A
  body RFC 7578 does not allow -- a part without `Content-Disposition: form-data` and a
  name, no closing delimiter -- is an `IOException` of `getParts()` and an
  `IllegalStateException` of `getParameter(..)`.
- **A field's text** is read in the charset of its own `Content-Type`, else the one the
  field `_charset_` names, else the request's, else UTF-8.

## Static resources

The files under `META-INF/resources` of the program's resources -- in a Maven project, of
its resources directory -- are its static resources, which a servlet named
`default` serves at `/` unless the program maps a servlet of its own there:
`META-INF/resources/index.html` is the page of `/`, `META-INF/resources/css/site.css` that of
`/css/site.css`. `getServletContext().getResource(..)`, `getResourceAsStream(..)` and
`getResourcePaths(..)` answer them too.

- **Run or compiled by rontolisp** the files are those of the source tree and of the source
  path, found when the program is compiled and compiled into it
  ([Resources](index.md#resources)), so a component that `wasmtime serve` runs serves them
  with no file system. A file was modified, as `Last-Modified` says, when the server was
  made: what the program carries cannot change after. The default servlet asks for a file by a
  name it computes, so the program brings every file of each directory of its source path
  (a jar's and the source tree's under `META-INF/resources` alone). A program whose source
  tree and source path hold no `META-INF/resources` holds none of it.
- **Under a JDK** they are the class path's, with each file's own time.
- **`documentRoot(path)`** of the builder adds a directory looked in first,
  read where the program runs: under a JDK, on the interpreter, the JVM and a native
  executable; a component has no file system.

The default servlet answers a GET and a HEAD with the file's length, its media type and its
time, a request conditional on the time (`If-Modified-Since` and the others), one range of
bytes (`Range`), and a directory with its `index.html` or `index.htm`, or with a redirect
where it is asked without its last slash. `WEB-INF` and `META-INF` are not served. Deployed
as a WAR, the files of `WEB-INF/classes/META-INF/resources` are not served by Tomcat or
Jetty, which serve `META-INF/resources` of the archives in `WEB-INF/lib`.

## What differs

- **A component's request has no host and no peer.** `wasi:http` hands the program
  neither a `Host` field nor the client's address: `getServerName()` is `localhost`,
  `getServerPort()` 80 and `getRemoteAddr()` `0.0.0.0`. A body is sent chunked whatever
  length the servlet set.
- **HTTPS needs a JDK.** The subset has no TLS server: a program that calls `https(..)` is
  refused where it names `javax.net.ssl.SSLContext`.
- **An exception after the commit** cuts the connection under a JDK; on the four targets
  the response ends as it is.
- **OPTIONS asks the servlet's class the methods it declares** (`HttpServlet.doOptions`),
  which the subset answers by name: [Deviations](deviations.md#what-differs).
- **The locales of a request** (`getLocale()`, `getLocales()`) are locales of any language;
  what a locale writes is the subset's: [Deviations](deviations.md#a-locale-of-any-language).
