# A Servlet container on the JDK HTTP server (`servlet-httpserver/`, 2026-11-21, k96)

**Invariant: `servlet-httpserver/` is an ordinary Java project that knows nothing of rontolisp
-- no `am.ik.rontolisp` import, no rontolisp artifact, outside the root reactor -- and rontolisp
knows nothing of it; its only dependency is the official `jakarta.servlet:jakarta.servlet-api`
6.1, of which it rewrites no interface; everything it does to the network goes through
`com.sun.net.httpserver.HttpServer` / `HttpExchange`, so a transport that has that API has the
container.** It is to become a repository of its own (user decision): coordinates
`am.ik.servlet:servlet-httpserver`, package `am.ik.servlet.httpserver`, both chosen to survive
the move. It implements a part of Jakarta Servlet 6.1 and claims no compatibility: no TCK was
run.

The theme it serves (user, 2026-11-21): one program written against the Servlet API deploys to
a servlet container through Java AND, compiled by rontolisp from source with this module and
the API's sources jar, serves as a `wasi:http` component (`.todo/k97`).

```bash
./mvnw -f servlet-httpserver/pom.xml spring-javaformat:apply test   # its own build, 97 tests, ~10 s
```

`.github/workflows/servlet-httpserver.yaml` runs that on a change under the directory; no job
of `ci.yaml` builds it.

## What it is

- **`ServletHttpServer`** (the one public class): `new ServletHttpServer(port)`,
  `(port, contextPath)`, `(InetSocketAddress, contextPath)`; `getServletContext()` to
  register; `setExecutor`; `start()`; `getPort()`; `stop()` / `stop(seconds)`; `close()`.
  One web application at one context path a server.
- **`start()` binds first** (`HttpServer.create`), so a port in use is a `BindException`
  before anything of the application is initialized; then the context listeners in their
  order (one may register more), the filters in theirs, the servlets with a
  `loadOnStartup >= 0` by their numbers. What a filter or a servlet throws there is thrown
  by `start()`, after what was initialized is destroyed and the port released -- an embedded
  server that starts half an application was rejected. A servlet not loaded on startup is
  initialized by its first request; one that refused (a `ServletException` that is no
  `UnavailableException`) is asked again by the next.
- **Registration** is `ServletContext.addServlet` / `addFilter` / `addListener`, closed once
  the context is initialized (`IllegalStateException`). The overloads taking a `Class` or a
  class name make the instance by reflection in ONE place (`Instances`), which a program
  handing instances never reaches.
- **A request** (`WebContext.handle`): the canonical path (`UrlCodec.canonicalPath`, the
  specification's 3.5.2, whose example table is `UrlCodecTest`), the context, the servlet
  (`WebContext.match`: exact, longest path prefix, extension, default), the filters (the
  mappings by URL pattern in their order -- one added "not after" before the ones "after" --
  then the ones by servlet name; a filter once a chain), the servlet. **Nothing is thrown to
  the HTTP server and the exchange is always answered and closed**: what a servlet throws is
  a 500 while the response is not committed (`UnavailableException`: permanent 404 and the
  servlet out of service and destroyed, temporary 503 with `Retry-After` in seconds).
- **A response is buffered** (`ResponseBody`, 8192 bytes unless `setBufferSize`): one that
  ends inside the buffer is sent with its length; the write that overflows, a flush, or a
  `close()` commits. Once committed the status and the fields are fixed and handed to the
  exchange in one go (`Response.commit`). `sendError`, `sendRedirect` and a returned
  `forward` end the response: what is written afterwards is dropped. The writer encodes
  straight into that buffer (`ResponseWriter`, no buffer of its own, half a surrogate pair
  held), so there is one buffer to reset and to overflow.
- **A dispatch** (`Dispatcher`): the request and the response a servlet hands in may be
  wrappers; the container's own are found through them (`Request.of`, `Response.of`) and
  the path they answer is turned to the target and given back (`Request.dispatch` /
  `restore`). The target's query string is put in front of the parameters for the dispatch.
  An included servlet changes no status and no field (`Response.including`).
- **What a date costs nothing of**: `HttpDates` writes and reads the three forms of an HTTP
  date over a count of days, so the container names neither `java.time` nor `java.text`
  (a formatter is expensive in a compiled program: `.kb/java-frontend.md`, "java.time: what
  a formatter costs").

## What it needs of `com.sun.net.httpserver` (measured on JDK 25, 2026-11-21)

Each is a rule `Response.commit` or `WebContext` keeps; the rontolisp side has to answer the
same way (`.kb/java-frontend.md`, "com.sun.net.httpserver").

- A context at `/app` is handed `/application` too: the container checks the boundary and
  answers 404 itself. A path of no context, a URI `java.net.URI` refuses (`%zz`, an unescaped
  `|`) and a malformed request line never reach it: the server answers 404 / 400.
- `sendResponseHeaders(status, n)`: `-1` for no body (`Content-length: 0` on the wire), `0`
  for chunks, `n > 0` for exactly n octets -- fewer written and the connection is closed
  with NOTHING sent, head included. So the container tells a length only when it holds the
  whole body or the servlet declared one, and a declared length the body did not reach is
  replaced by the real one when the response ends uncommitted.
- A `Content-length` field set by hand is kept beside `Transfer-encoding: chunked` (both
  sent): the container never copies its own `Content-Length` to the exchange, except for a
  HEAD and a 304, where the server writes none and keeps the handler's.
- A HEAD takes `-1` (anything else is a warning in the log); a 204 and a 304 take `-1`.
- `Date` is the server's: a `Date` field of the response is overwritten.
- A field name is normalized (`Content-type`, `X-many`): `getHeaderNames()` answers that form,
  which the specification allows; lookups are case-insensitive.
- The response stream is taken AFTER `sendResponseHeaders` (a stream taken before sends
  nothing), by one caller (`ResponseBody.send` / `finish`).
- `HttpServer.stop(0)` cuts the requests in hand; a handler may still be in its servlet, so
  `stop(seconds)` also waits that long for the container's own pool before `destroy()`.

## Where the specification leaves room (measured against Tomcat 11.0.27 and Jetty 12.1.12)

`ServletContainerTest` asks one application the same requests in the three; where they agree
the row is not here. `measured(container, here, tomcat, jetty)` in the test is each row.

| Question | Here | Tomcat | Jetty |
|---|---|---|---|
| `/app` (the context root, no slash) | 302 to `/app/` | the root's servlet | 301 to `/app/` |
| `/app//exact` (an empty segment) | removed, as 3.5.2 says | removed | 400 |
| `/app/%2e/exact`, `/app/..;/x` (3.5.2's suspicious list) | 400 | resolved | 400 |
| `/app/a%25b` | decoded | decoded | 400 |
| `/prefix` under `/prefix/*`: the mapping's match value | empty | `null` | empty |
| `/prefix/a/..` | `/prefix`, no path info (3.5.3) | the same | path info `/` |
| An escaped octet of a form with no charset | UTF-8 | ISO-8859-1 | UTF-8 |
| A form of a PUT, or after `getInputStream()` | not parameters | not parameters | parameters |
| A parameter with no name (`?=x`) | a parameter | `IllegalStateException` | a parameter |
| A quoted cookie value | quotes kept (RFC 6265) | kept | taken off |
| `Accept-Language: *` | left out | `und` | left out |
| `setContentLength(3)`, six octets written | three sent | three sent | 500 |
| A type's charset: its case; a type set after the writer | as written; charset kept | the same | lower case; dropped |
| `getOutputStream()` with `text/plain` | no charset added | none | `;charset=iso-8859-1` |
| A relative `sendRedirect` location | as given | as given | made absolute to the path |
| A field set after `sendError` | dropped | dropped | sent |
| An exception with the buffer written, uncommitted | the 500 page | 500 with the buffer | the 500 page |
| An exception after the commit | the response ends as it is | the chunks are cut | ends as it is |
| `UnavailableException` for a time | 503, `Retry-After: 5` | 503, a date; then 404 | 503, no field |
| `Set-Cookie` with `Max-Age` | no `Expires`, attributes by name | `Expires` added | `Expires` added |
| A servlet registered as an instance, not loaded on startup | first request | first request | at the start |
| The order of `destroy` | reverse of registration | its own | filters first |
| A HEAD of an empty response | `Content-Length: 0` | none | `Content-Length: 0` |

Decisions the table shows: the specification's own text wins where it speaks (3.5.2's
canonical path and its rejected sequences, a POST's form only, the `IllegalStateException` of
a parameter that cannot be decoded); UTF-8 for a form because the query string's is UTF-8 in
all three; a relative redirect stays relative because a `wasi:http` request has no `Host`
field to make it absolute with. The 6.1 javadoc says a redirect's buffer "is replaced with a
short hypertext note"; neither container sends one and neither does this.

## Not there (each refused where it is asked for, never ignored)

Sessions (`getSession(true)`: `UnsupportedOperationException`; `getSession(false)` is
`null`), asynchronous requests (`isAsyncSupported()` false, `startAsync`:
`IllegalStateException`, as the specification says of a servlet that does not support them),
multipart (`getParts` on a multipart request: `IllegalStateException`; `setMultipartConfig`:
`UnsupportedOperationException`), security constraints and authentication
(`setServletSecurity`: `UnsupportedOperationException`; `authenticate` / `login`:
`ServletException`), error pages and the ERROR dispatch, static resources
(`getResource*` answer `null`, no default servlet: an unmapped path is a 404), discovery by
annotations, `web.xml` or `ServletContainerInitializer`, JSP, HTTPS, upgrades, trailers, a
second context a server. Open items: `.todo/l03` (sessions), `.todo/l04` (asynchronous),
`.todo/l05` (multipart), `.todo/l06` (discovery and resources), `.todo/l07` (the rest).
`ServletContext.log` writes to `System.err`. A form read into parameters is at most 2 MiB
(`Request.MAX_FORM_SIZE`).

## What the rontolisp side needs (measured 2026-11-21 on `f485c352e`; made by k97 the same day)

Measured by compiling a servlet program with `--java-source-path
servlet-httpserver/src/main/java:jakarta.servlet-api-6.1.0-sources.jar` and working past each
refusal on scratch copies of the two source trees: **seven refusals, six of them in the API's
sources** (not ours to edit, so the front end takes them). k97 made five in the front end; the
hello world needs no other, and serves on the four targets.

| Refused | Where | Reached by | Since k97 |
|---|---|---|---|
| the modifier `transient` | `GenericServlet.java:53`, `GenericFilter.java:52`, `ServletRequestEvent.java:32` | every servlet | taken: a field's, meaning nothing (no serialization); refused on a method, a class, a constructor, an initializer and an interface's field as javac refuses it |
| `java.util.EventObject` | `ServletContextEvent` and the other events | `WebContext.start()` | library source (`toString` is the class's binary name and `[source=..]`, a JDK 25's) |
| `java.util.EventListener` | the listener interfaces, `WebContext` | `WebContext.start()` | library source |
| `java.lang.reflect.Method` (`Class.getDeclaredMethods()`, `getSuperclass()`, `Method.getName()`) | `HttpServlet.doOptions` -> `getAllDeclaredMethods` | every `HttpServlet`, through `service` | the names of a class's declared methods (`.kb/java-frontend.md`, "A class as a value") |
| `java.io.CharConversionException` | `ServletOutputStream.print(String)` | `HttpServlet.doTrace` | library source |
| `Cloneable` / `Object.clone()` | `Cookie` | a program naming `Cookie` | `.todo/k16` (`cannot find symbol: class Cloneable` at `Cookie.java:60`) |
| `Locale.forLanguageTag(String)` | this module's `Fields.locales` | `getLocale()` / `getLocales()` | `.todo/l08`: legitimate Java, so the front end's, and a family (a `Locale` of any language and every consumer of one) |

- **`doOptions` was measured before it was decided.** Every `HttpServlet` reaches it through
  `service`, so without it no servlet program compiles; the names alone are what it reads.
  The hello world with `doOptions`' reflection cut out of a scratch copy is 941,069 B as a
  component, with the names 951,279 (+10,210: `Class.getDeclaredMethods()` 953 B of code,
  `%declared-methods` 184 -- one string a class object --, `Method`'s `equals` and `hashCode`
  668, the class objects of the servlet's class and the three above it, `doOptions` and
  `getAllDeclaredMethods` themselves, and the resume bodies they add). A first form making
  a `Method` per name in the lowered code was 3,728 B for the 29 names of three classes;
  one string a class split by `Class` is the form kept. The rest of reflection stays
  `.todo/k89`'s.
- **Pinned by** `FetchSpecE2eTest#javaHttpServer`, the program `servlet` of
  `java-http-server-spec.yaml` (`sourcePath:` this module, `sourceDep:` the API: the four
  legs read both as source, the JDK leg runs the module javac compiled against the API's
  jar): a listener, a filter on every path, servlets by an exact path, a prefix and an
  extension, a form, a forward and an include, the 405 of the API's message bundle, OPTIONS,
  HEAD, TRACE, a redirect, `sendError`, an exception, a response its buffer committed, a
  204, the context root's redirect, a 404 and the stop; every leg answers the JDK's 19
  responses and logs the JDK's lines (the servlet's `init` and `destroy`, the context's).
  Its helper is `note`, not `log`: inside a servlet the simple name `log` is
  `GenericServlet.log`, the context's (JLS 15.12.1, the innermost class with a member of the
  name), which writes to `System.err` -- on every leg, as under a JDK.
- **A real container, by hand** (not a test): the same servlets registered by the same calls
  through a `ServletContainerInitializer` of embedded Tomcat 11.0.27 answer every
  application line the same (bodies, parameters, forward, include, filter, listener,
  redirect, the 405 message); what differs is the container's own -- its error pages,
  `Content-Language: en`, OPTIONS without `TRACE` (its `allowTrace` is false), and
  `setBufferSize(16)` leaving the response uncommitted.
- What already worked, unchanged: the API's `ResourceBundle.getBundle` of its
  `LocalStrings.properties` out of the source root (the 405 of an unsupported method says
  `HTTP method POST is not supported by this URL`), `MessageFormat`, `Cookie`'s
  `TreeMap(String.CASE_INSENSITIVE_ORDER)`, `ConcurrentHashMap`, `EnumSet`, anonymous
  listeners and filters, the executor's pool, a forward and an include, two `Set-Cookie`
  fields and a field added twice sent apart, a form and a reader, the chunked 20,000-octet
  body. Not asked: whether a request field sent twice is two values or one joined
  (`.todo/k15`).
- **Sizes** (2026-11-21, k97's tree, wasmtime 49.0.0): a program of one servlet answering
  `Hello, world!` is 951,279 B as a component, 12,505,528 as a `--native` executable and
  1,095,323 as a class; the corpus program (five servlets, a filter, a listener) 1,073,291 /
  13,632,056 / 1,255,618. A `com.sun.net.httpserver` hello on the same tree is 476,625 /
  8,212,072. **The program pays for what it reaches** of the API and the container: of the
  hello component's 780,804 B of code (`-Drontolisp.wasm.debug-func-sizes`), the API is
  8,262 (61 of its functions) and the container 34,719; the library classes they reach are
  125,109 (`ConcurrentHashMap` 10,955, `HashMap` 9,834, `MessageFormat` 9,579 and
  `Properties` 5,458 for the API's message bundle, `URI` 9,476, `ThreadPoolExecutor` 6,363
  for the container's own pool); the rest is the run time, the funcall dispatch (113,980:
  `_dispatch_spread` alone 36 KB, growing with the functions) and the resume bodies of a
  program whose requests are threads (94,425, where the plain hello has 31,807:
  `.todo/k15`, `.todo/k19`).
- **What a component answers differently, by the transport and not by the container**:
  `wasi:http` hands no `Host` field and no peer, so `getServerName()` is `localhost`,
  `getServerPort()` 80, `getRemoteAddr()` `0.0.0.0`; every response with a body is
  `transfer-encoding: chunked` on the wire whatever length the container told. A
  `--native` executable writes no `Date` field. The reason phrase of a 302 is the JDK
  server's `Temporary Redirect` on the interpreter and the JVM, `Found` on the other two.
- The container starts a cached thread pool unless `setExecutor` is called; what that costs a
  compiled program against a plain call is `.todo/k15`'s question.
- A module of its own is compiled from its `-sources.jar`, which its build attaches
  (`maven-source-plugin`), or from `servlet-httpserver/src/main/java` as a directory root.
  User doc: `doc/*/java/servlet.md`.

## Tests

`servlet-httpserver/src/test/java/am/ik/servlet/httpserver/`: `ServletContainerTest` (ten
families -- mapping, the request line and fields, parameters, the body, buffering and commit,
fields and type, errors and redirects, dispatch, filters, the life of the application with two
requests served at once -- each run in this container, Tomcat and Jetty against a real socket
through `Http`, a client that reads the reply to the end of the connection so the framing
shows), `ServletHttpServerTest` (registration, start and stop, what is refused),
`UrlCodecTest` (the specification's example paths). `Application` is the one application the
three containers are given; `Container` starts each.
