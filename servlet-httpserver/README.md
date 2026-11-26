# servlet-httpserver

A servlet container on the JDK's own HTTP server (`com.sun.net.httpserver`), written against
the Jakarta Servlet 6.1 API: one web application, embedded in a `main`. It implements a
part of the specification and claims no compatibility with it: no TCK was run.

```java
ServletHttpServer server = ServletHttpServer.builder()
    .port(8080)                                   // or address(InetSocketAddress); 0: a free port
    .contextPath("/app")
    .servlet("hello", (request, response) -> response.getWriter().println("Hello!"),
            servlet -> servlet.urlPatterns("/hello"))
    .filter("log", (request, response, chain) -> {
        chain.doFilter(request, response);
        request.getServletContext().log(request.getRequestURI() + " " + response.getStatus());
    }, filter -> filter.urlPatterns("/*"))
    .start();
server.port();
server.stop(Duration.ofSeconds(5));
```

An application of annotated classes registers nothing:

```java
@WebServlet("/hello")
public class HelloServlet extends HttpServlet {
    // doGet ..
}
```

```java
ServletHttpServer.builder().port(8080).discover().start();
```

It needs Java 21. The only dependency is `jakarta.servlet:jakarta.servlet-api`. The API is `@NullMarked`
(JSpecify): what may be null is `@Nullable`. The annotations are a `provided` dependency,
which nothing needs where the classes run.

- `ServletHttpServer.builder()` takes where the server listens first: `port(int)` or
  `address(InetSocketAddress)`. The rest is optional and in any order: `contextPath`,
  `servlet`, `filter`, `listener`, `onStartup` and `onShutdown` (a context listener's two
  events as lambdas), `discover`, `documentRoot`, `sessions`, `errorPage`, `maxFormSize`,
  `log`, `https`, `executor`. `start()` starts
  the server; `build()` returns the definition, which nothing changes and which starts a
  new server at each `start()`.
- A servlet is an instance, a class (made by its `public` constructor of no parameter) or
  a lambda of the request and the response; its options are `urlPatterns`,
  `loadOnStartup`, `asyncSupported`, `initParameter`, `multipartConfig`. A filter is an
  instance, a class or a lambda of the request, the response and the chain; its options are
  `urlPatterns`, `servletNames`, `dispatcherTypes`, `asyncSupported`, `initParameter`. A
  listener is an instance or a class. A name given twice, or a pattern two servlets map,
  fails the start.
- `start()` binds the address, registers what the builder was given, in its order, then
  what the application declares where `discover()` asks for it: the classes that carry
  `@WebServlet`, `@WebFilter` or `@WebListener` (with `@WebInitParam`), in the order of
  their names, then what each `ServletContainerInitializer` named by a
  `META-INF/services/jakarta.servlet.ServletContainerInitializer` registers. An
  initializer with `@HandlesTypes` is handed the classes that extend or implement a type
  it names, and those that carry an annotation it names on themselves or on a member. A
  class is `public` with a `public` constructor of no parameter. An annotation that
  cannot mean what it says (`@WebServlet` on a class that is no `HttpServlet`, a name or a
  pattern another servlet has, both `value` and `urlPatterns`) fails the start. `web.xml`
  is not read.
- `stop()` cuts the requests in hand; `stop(Duration)` waits for them that long, whatever
  runs them. The servlets, the filters and the context are destroyed after it.
- `executor(executor)` chooses what runs a request; without one the server runs each on a
  virtual thread of an executor of its own, which it shuts down when it stops.
- `errorPage(status, location)`, `errorPage(ExceptionType.class, location)` and
  `errorPage(location)` (any other error) answer an error by a page of the application:
  an `ERROR` dispatch by `GET`, after the servlet returned, the error told by the
  `jakarta.servlet.error.*` attributes. An exception's page is its nearest class's, else
  the cause's of a `ServletException`, else the page of 500. `sendError` leaves the
  response committed. An exception once the response is committed cuts the connection.
- `maxFormSize(bytes)`: the largest form read into parameters (2 MiB; negative: none).
  `log((message, thrown) -> ..)`: where `ServletContext.log` and the container's log go
  (the standard error). `https(sslContext)`: an `HttpsServer`; `getScheme()` is `https`,
  `isSecure()` true, the port 443 unless `Host` names one, the session cookie `Secure`.
- The JDK's server sets `TCP_NODELAY` only where `sun.net.httpserver.nodelay` is true;
  without it a response of more than one write waits 40 ms for the client's delayed
  acknowledgement (some 390 requests a second against 45,000). The server sets the
  property to `true` when it starts, unless the application set it.

- Sessions are tracked by a cookie (`SessionCookieConfig`; `JSESSIONID`, `HttpOnly` and
  the context's path unless set), or by `;jsessionid=` in URLs where
  `setSessionTrackingModes` asks for `URL`. The session listeners, `changeSessionId`,
  `HttpSession.getAccessor()` and the interval (`setSessionTimeout`, 30 minutes) are
  there. An expired session is invalidated when it is next asked for, and the expired ones
  are looked for at most once a minute, by a request: no thread runs on a clock.
- `sessions(sessions -> sessions.store(store))` chooses where the sessions are kept (`SessionStore`: load,
  save, delete, the expired ones, over `SessionData`; `saveAccess`, a request received, which a
  store whose writes cost may skip while the time moved little); without one they are in the first
  store a `META-INF/services/am.ik.servlet.httpserver.SessionStore` of the class loader
  names (`../servlet-httpserver-keyvalue/`: in `wasi:keyvalue`), else in the server's
  memory, and a stop invalidates them. A store refusing a value (`IllegalArgumentException`
  from `save`) has `setAttribute` throw it, the session as it was.

- Asynchronous requests: `startAsync` (both forms) where the servlet and every filter
  around it support them (`asyncSupported`), `AsyncContext` (`complete`, the three
  `dispatch` forms, `start`, the timeout, `AsyncListener`), `ASYNC` in the filter
  mappings, `ReadListener` and `WriteListener`. The thread a request was dispatched on
  waits until the request is complete and runs what the container owes it -- the
  dispatch, the listeners, the answer to a timeout or an error -- so a request in
  asynchronous mode holds a thread of the executor (a virtual thread unless `executor`
  was given). The exchange's streams
  block: `isReady()` is always true and each listener is called once.

- Multipart bodies (RFC 7578) for a servlet with a multipart configuration
  (`@MultipartConfig` or `multipartConfig`): `getParts`, `getPart`, and the parts
  without a file name as parameters. A part is in memory up to `fileSizeThreshold` and in a
  file past it, in `location` (an absolute directory, else below `java.io.tmpdir`; with
  neither, every part stays in memory), deleted once the request is complete. A limit is
  an `IllegalStateException`; a body RFC 7578 does not allow, an `IOException` of
  `getParts`.

- Static resources: the files under `META-INF/resources` of the class path (the
  application's classes and its archives), after those of a directory `documentRoot`
  names. `getResource`, `getResourceAsStream`, `getResourcePaths` and `getRealPath` (of the
  document root) answer them, and where there are any a servlet named `default` serves
  them at `/`, unless the application maps a servlet of its own there: a GET or a HEAD
  with the length, the media type (by the extension) and `Last-Modified`;
  `If-Modified-Since`, `If-Unmodified-Since`, `If-Match` and `If-None-Match` (no entity
  tags, so only `*` matches); one range of bytes (`Range`, `If-Range` by date; several
  ranges are answered whole); a directory by its welcome file, `index.html` or
  `index.htm`, and asked without its slash, by a redirect. `WEB-INF` and `META-INF` are
  not served. The class path is a place of resources where its loader finds the directory
  `META-INF/resources` itself: an archive written with no entries for its directories
  does not make it one.
  Tomcat and Jetty serve `META-INF/resources` of the archives in `WEB-INF/lib`, not of
  `WEB-INF/classes`.

Not there: security constraints and authentication (`setServletSecurity`,
`@ServletSecurity`, `authenticate`, `login`; a filter of the application's own, Spring
Security's for one, answers `getUserPrincipal` and `isUserInRole` through a request
wrapper), `web.xml`, trailer fields (`setTrailerFields`), protocol upgrades and server
push (`newPushBuilder()` is null). Each is refused where it is asked for rather than
ignored. A server is one application: `getContext` of another path is null.

## Where the annotated classes are looked for

- **The class path**: every directory and archive of `java.class.path`, the archives an
  archive's manifest names (`Class-Path`), and those of a `URLClassLoader` the
  application's class loader is or has as a parent. Each class file is read, not loaded;
  a class is loaded only when its file names one of the annotations, or one an
  initializer asks for, or when it extends or implements a type an initializer asks for
  (read from the class files). The roots are read at once. Not read: the packages
  `jakarta.servlet` and `am.ik.servlet.httpserver`, `META-INF`, the module path. What it
  costs at the start: Spring Framework's `spring-webmvc` and what it needs (12 archives,
  9.2 MB, 5,116 classes), 0.2 s on a machine of many cores, 0.55 s on one core, of
  1.1 s and 2.6 s to the first answer of a hello.
- **It is asked for** (`discover()`): a server whose registrations are code reads no class
  path, and a program compiled ahead of time holds nothing of it. It is not narrowed to a
  package: what that would save is that fifth of the start.
- **An index**: a resource `META-INF/annotations/<annotation type>` (for example
  `META-INF/annotations/jakarta.servlet.annotation.WebServlet`) lists classes that
  carry the annotation, a binary name a line, `#` starting a comment. It is for a program
  whose classes are no files of the class path: one compiled ahead of time, which has no
  class path (`java.class.path` is not set there), or started by the source launcher
  (`java Hello.java`), whose classes are in memory. The classes of both places are
  registered, each once.

```bash
../mvnw -f servlet-httpserver/pom.xml spring-javaformat:apply test
```

The tests ask one application the same requests in this container, in Tomcat and in Jetty:
one registered in code, and one of annotated classes each container discovers.
