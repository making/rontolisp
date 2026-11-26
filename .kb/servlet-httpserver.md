# A Servlet container on the JDK HTTP server (`servlet-httpserver/`)

**Invariant: `servlet-httpserver/` is an ordinary Java project that knows nothing of rontolisp
-- no `am.ik.rontolisp` import, no rontolisp artifact, outside the root reactor -- and rontolisp
knows nothing of it; its only dependency is the official `jakarta.servlet:jakarta.servlet-api`
6.1, of which it rewrites no interface; everything it does to the network goes through
`com.sun.net.httpserver.HttpServer` / `HttpExchange`, so a transport that has that API has the
container.** It is to become a repository of its own (user decision; `.todo/l57`):
coordinates `am.ik.servlet:servlet-httpserver`, package `am.ik.servlet.httpserver`, both chosen
to survive the move. It implements a part of Jakarta Servlet 6.1 and claims no compatibility:
no TCK was run.

The theme (user): one program written against the Servlet API deploys to a servlet container
through Java AND, compiled by rontolisp from source with this module and the API's sources
jar, serves as a `wasi:http` component.

```bash
./mvnw -f servlet-httpserver/pom.xml spring-javaformat:apply test   # its own build, ~20 s
```

`.github/workflows/servlet-httpserver.yaml` runs that on a change under the directory and, on a
push, deploys it to Maven Central (`.kb/session-workflow.md`, "The deploy job"); no job of
`ci.yaml` builds it. Release 21 ("Java 21" below); Servlet 6.1's own floor is 17.

## What it is

- **`start()` binds first**, so a port in use is a `BindException` before anything of the
  application is initialized; then the builder's registrations in their order, then what
  `discover()` finds, then context listeners, filters, servlets with `loadOnStartup >= 0`.
  What a filter or a servlet throws there is thrown by `start()` after what was initialized
  is destroyed and the port released (an embedded server that starts half an application was
  rejected). A servlet that refused its lazy `init` (a `ServletException` that is no
  `UnavailableException`) is asked again by the next request.
- **Registration** is `ServletContext.addServlet` / `addFilter` / `addListener`, closed once
  the context is initialized (`IllegalStateException`). Instances by reflection are made in
  ONE place (`Instances`); a program with no annotated class and no `Class` overload never
  reaches it.
- **A request** (`WebContext.handle`): canonical path (`UrlCodec.canonicalPath`, spec 3.5.2,
  example table in `UrlCodecTest`), servlet match (exact, longest prefix, extension, default),
  filters (by URL pattern in order -- "not after" before "after" -- then by servlet name; a
  filter once a chain). **The exchange is always answered and closed, and nothing is thrown to
  the HTTP server but for a response that failed once committed** ("Error pages, HTTPS, the
  options"). A servlet's exception is a 500 while uncommitted (`UnavailableException`:
  permanent 404 and the servlet destroyed, temporary 503 with `Retry-After` in seconds), via an
  error page where there is one.
- **A response is buffered** (`ResponseBody`, 8192 B unless `setBufferSize`): one that ends
  inside the buffer is sent with its length; overflow, flush or `close()` commits, and the
  status and fields go to the exchange in one go (`Response.commit`). The writer encodes
  straight into that buffer (`ResponseWriter`, half a surrogate pair held): one buffer to reset
  and overflow.
- **A dispatch** (`Dispatcher`): the container's request/response are found through wrappers
  (`Request.of`, `Response.of`); the target's query string goes in front of the parameters; an
  include changes no status and no field (`Response.including`).
- **`HttpDates`** writes and reads the three HTTP date forms over a day count, so the container
  names neither `java.time` nor `java.text` (a formatter is expensive compiled:
  `.kb/java-frontend.md`, "java.time: what a formatter costs").

## The builder

**Invariant: a server is defined by a staged builder whose first step is where it listens;
a definition is data no call changes, applied to a new context at each start -- the
builder's registrations in their order, then what discovery finds where `discover()` asks
for it -- and what an option sets is a function its method makes, so a program holds the
parts of the builder it calls.** (User: a staged builder read as a DSL, lambdas, JSpecify and
NullAway; no deprecated layer for the old constructors.)

- **Stages**: `builder()` answers an `AddressStep` (`port`, `address`), the only way to a
  `Builder`, which alone has `build()` / `start()`. Each `Definition.start()` is a new server
  (an instance given is shared, a class is made by each). Options given in a lambda are fixed
  once it returns (a later call is `IllegalStateException`). Each part calls the Servlet API's
  registration, so the rules are the container's (a duplicate name or pattern is `start()`'s
  `ServletException`). Builder filters are mapped "not after": ahead of declared ones.
- **`Handler`** (a servlet as a lambda, since `Servlet` has five abstract methods) is a
  `GenericServlet.service` (`HandlerServlet`): it answers every method, nothing of
  `HttpServlet`'s dispatch. **`FilterHandler extends Filter`**: an unrelated functional
  interface beside `Filter` would make a lambda given to `filter` ambiguous; as a subtype it is
  the more specific (JLS 15.12.2.5). No listener interface: `onStartup` / `onShutdown` take a
  `Consumer<ServletContext>`.
- **Discovery is asked for, not default.** On by default, a code-registered program would read
  the class path under a JDK (~0.23 s a start of this module's tests) and hold the
  initializers' `ServiceLoader` compiled; asked for, the lambda calling `Discovery.discover` is
  made in `discover()` alone. As an embedded Tomcat `addContext` / Jetty
  `ServletContextHandler` scan nothing either.
- **What an option sets is a function** (`ServletSpec`, `FilterSpec`, `SessionSpec` append a
  `Consumer`). Options kept in fields applied by one `register` reached `setMultipartConfig`
  (parts and `java.nio.file`) from every program: mvcfn +116,698 B. `build()` uses an
  `ArrayList`, not `List.copyOf` (1.9 KB of `ImmutableCollections`).
- **A registration's class name is asked when wanted** (`Registered.getClassName()`):
  `getClass().getName()` at creation is refused for a lambda on rontolisp.
- **Overload choice**: `filter(String, Filter, ..)` vs `filter(String, FilterHandler, ..)` with
  a lambda needs the subtype rule in `JavaLowering.moreSpecific` (java-spec
  `a-type-argument-is-found-from-the-arguments-the-target-and-the-lambdas`).
- **JSpecify and NullAway**: the package is `@NullMarked`, the API's nullable answers
  `@Nullable`; the root's `nullability-maven-plugin` (0.5.3: Error Prone 2.52.0, NullAway
  0.14.3 JSpecify mode) runs on the main compile with `-Werror`. jspecify is `provided` (user:
  no runtime dependency): a JVM ignores an absent annotation class, and the front end drops a
  dependency's annotation of a type no root has ([java-frontend.md](java-frontend.md),
  "Annotations") -- the component is byte-identical with and without jspecify's sources. A JDK
  leg's javac needs the jar: `ExampleProject.provided` is on that class path alone
  (`FetchSpecE2eTest`, `ClPostgresE2eTest`). Same setup on `servlet-httpserver-keyvalue/`,
  `webmvc-fn-lite/`, `jdbc-postgresql/`; not on `rontolisp-java-api/` (depends on nothing).
- **Pinned by** `ServletHttpServerTest` (a definition unchanged by the builder afterwards and
  started again, an option given late, a pattern two servlets map, refusals, a lambda servlet
  with sessions), the module's other tests through `Container`, and
  `FetchSpecE2eTest#javaHttpServer`'s `servlet` program (lambda filter and servlet) on the JDK
  and the four legs.

## Discovery

**Invariant: an application of annotated classes registers nothing -- `main` is
`ServletHttpServer.builder().port(port).discover().start()` -- and the code that finds what
it declares is the same Java under a JDK and compiled by rontolisp: `Discovery` asks the
class loader for indexes of annotated classes, reads the class path where there is one, and
reads the annotations by reflection.** User decision: annotations found automatically, a
class path scan under a JDK, the list prepared when rontolisp compiles, one container source,
no `web.xml`.

- **What is registered**: classes carrying `@WebListener` / `@WebFilter` / `@WebServlet`, in
  binary-name order, one public instance each (`Instances.create`); filters mapped "after".
  Then each `ServletContainerInitializer` of `ServiceLoader` (loaded before the classes are
  read: what they ask for is what the class path is read for), handed its `@HandlesTypes`
  classes, `null` without one or none found; an initializer sees the annotated servlets
  registered (the spec's order). The loader is the thread's context loader when the server is
  made.
- **Refused at the start, each a `ServletException`** (refused, never ignored;
  `DiscoveryTest#anAnnotationThatCannotMeanWhatItSaysIsRefused` holds the words): `@WebServlet`
  on no `HttpServlet`, `@WebFilter` on no `Filter`, `@WebListener` on no listener or on
  `HttpSessionBindingListener`, both `value` and `urlPatterns`, a servlet with no pattern, a
  name or pattern taken, a class that cannot be made, `@ServletSecurity`, a listed class that
  cannot be loaded, an initializer asking for classes with no class path. Tomcat 11.0.27 /
  Jetty 12.1.12 on each (rows of the same test): Tomcat refuses to start for `@WebFilter` on no
  filter, both `value` and `urlPatterns`, a pattern taken; Jetty for a pattern taken; otherwise
  both start and leave the class (404), register it and fail at its request (500), or register
  it as if nothing were wrong (`@ServletSecurity`). Filters of one path run in name order here
  and in directory-listing order there (`Zed`, `Mid`, `Abe` on ext4), so the test pins theirs
  as a set. Of two servlets of one name they register the first the scanner reads, in the
  directory's order (ext4 hashes it per file system), so the `NameTaken` row asks `/free` and
  `/taken` and expects one 405 and one 404, not a measurement of one box.
- **The class path** (`ClassPath.read`, only where `java.class.path` is set): the property's
  entries, manifest `Class-Path` archives (followed in turn: executable jars, surefire's
  manifest-only jar), and each `URLClassLoader`'s `file:` URLs up the chain; each once. The
  module path is not read. Every `.class` is READ, none loaded: a file is kept where its
  constants hold `Ljakarta/servlet/annotation/Web`, and only those are loaded
  (`Class.forName(name, false, loader)`); one naming the annotation without carrying it is
  loaded and left. Skipped: packages `jakarta.servlet` and `am.ik.servlet.httpserver`,
  `META-INF`, `module-info`. Archives via `ZipFile`, roots read in parallel
  (`Discovery.Root`) and merged in root order (a class in two roots is the first's). Rejected:
  loading every class (slow; a class with missing dependencies fails to link).
- **What the read costs** (JDK 25, 64 cores, first call): this module's test class path (42
  roots, 20.2 MB) 230 ms -- 810 ms on one core; Spring's `spring-webmvc` closure (12 archives,
  5,116 classes) 130 ms + 20 ms of manifests. A zip file system one root at a time was 700 /
  430 ms. `ZipFile` costs a compiled program nothing: the method is not reached, and an
  unreached method may name a class the library lacks.
- **No switch to narrow discovery to a package**: Spring's `DispatcherServlet` probe listens
  0.57 s after JVM start, of which the read is 0.2 s (one core: 0.55 of 2.6 s); a switch would
  save a fifth of the start and add a way to miss a class. Two programs sharing one source tree
  under rontolisp is what a JDK does with two applications in one classes directory: give each
  its own tree ([webmvc-fn-lite.md](webmvc-fn-lite.md)).
- **`@HandlesTypes`** (Servlet 6.1, 8.2.4): classes that extend or implement a named type (not
  the type itself; interfaces and abstract classes below it yes) and those carrying a named
  annotation on themselves or a member; name order; `null` for none. vs Tomcat / Jetty in
  `DiscoveryTest` (`packed.Handling`, `annotated.Plugins`): Tomcat the same set; Jetty adds the
  subclass of an annotated class (not `@Inherited`) and misses a class below a JDK type
  (`EventListener` through `ServletContextListener`). How: superclass and interfaces read off
  each file's constant pool (`ClassPath.supertypes`, JVMS 4.1/4.4); a supertype that is no file
  read is loaded and asked `isAssignableFrom` (unloadable answers no). Rejected: matching the
  handled type's name in the constants (a grandchild names only its superclass). **A program
  with no class path refuses an initializer that asks** (`@HandlesTypes needs the class files
  of a class path: X asks for classes`): a compiled program finds by name only what an index
  lists (`.kb/java-frontend.md`, "Annotations read where the program runs"); subtypes of a
  computed type are not answered, and the initializers that ask (Spring's, Jersey's) also
  needed reflection over generic types (made since, l66).
- **The index** (`Discovery.listed`): `loader.getResources("META-INF/annotations/<annotation
  type>")` for the three types, a binary name a line, `#` comments (provider-file syntax).
  It makes a program whose classes are no class path files discoverable: a JDK's source
  launcher with the index files answers, without them 404; it is what a compiled program is
  answered with. The names are CONSTANTS and each `getResources` call is written out: a
  compiled program answers an index for a constant name; a computed name would carry every
  file of its source path. The index names neither side: rontolisp answers the index of ANY
  annotation type. Weighed and left: a made-up file system for the class path walk, the
  program as a module (`ModuleReader.list()`), `ServiceLoader` (a provider is LISTED, which the
  user did not want written).
- **Written for a compiler that drops what is never reached**: `if
  (System.getProperty("java.class.path") != null)` around the scan and `if
  (type.isAnnotationPresent(WebFilter.class))` around each kind's registration are answered
  while compiling, so a compiled program holds neither the walk nor unused kinds. Passing the
  class path as an argument, or `getAnnotation(..) != null` on a local, compiled all of it (the
  walk alone 52 KB of a component). **The condition is answered alone**: `handles != null &&
  System.getProperty("java.class.path") != null` is not; the property test in its own `if` is.
- **Tests**: `DiscoveryTest` builds an exploded web application in a temporary directory
  (sources under `servlet-httpserver/src/test/resources/discovered/`, compiled by
  `javax.tools`, so no other test's class path holds an annotated class) and deploys it to
  Tomcat (`addWebapp`), Jetty (`WebAppContext`, `jetty-ee11-annotations`) and this container in
  a JVM OF ITS OWN whose class path is the application and a manifest-only jar. Rows that
  differ: an initializer with no `@HandlesTypes` gets `null` here and in Tomcat, an empty set
  in Jetty; `@HandlesTypes` sets; filter order. Refusal cases deploy alone to Tomcat and Jetty
  (`Container.Running.started`: whether the application started, since both serve one that
  did not).

## What it needs of `com.sun.net.httpserver` (measured on JDK 25)

Each is a rule `Response.commit` or `WebContext` keeps; the rontolisp side answers the same way
(`.kb/java-frontend.md`, "com.sun.net.httpserver").

- A context at `/app` is handed `/application` too: the container checks the boundary (404).
  A path of no context, a URI `java.net.URI` refuses (`%zz`, unescaped `|`) and a malformed
  request line never reach it: the server answers 404 / 400.
- `sendResponseHeaders(status, n)`: `-1` no body, `0` chunks, `n > 0` exactly n octets --
  fewer written and the connection closes with NOTHING sent, head included. So a length is
  told only for a whole body or a declared one, and a declared length the body did not reach
  is replaced by the real one when the response ends uncommitted.
- A hand-set `Content-length` is kept beside `Transfer-encoding: chunked` (both sent): the
  container never copies its own `Content-Length` to the exchange, except for HEAD and 304.
- HEAD, 204 and 304 take `-1` (else a log warning). `Date` is the server's (a response `Date`
  is overwritten). Field names are normalized (`Content-type`); lookups are case-insensitive.
- The response stream is taken AFTER `sendResponseHeaders` (before, it sends nothing), by one
  caller (`ResponseBody.send` / `finish`).
- `HttpServer.stop(0)` cuts requests in hand; `stop(seconds)` waits for handlers in hand,
  whatever executor, up to the delay; rontolisp's too. The server's wait is the only one
  (`ServletHttpServerTest#anHttpsServerWithItsLogItsFormLimitAndADefaultErrorPage`: a request
  on the caller's pool is answered before the application is destroyed).
- **A handler that throws after a chunked head**: JDK 25 closes the connection with no last
  chunk; `exchange.close()` ends the body on every transport. So `WebContext.handle` throws
  (an `IOException`, the exchange not closed) for a response that failed once committed. How
  each rontolisp transport answers: `.kb/java-frontend.md`, "A handler that throws after a
  chunked head".
- **`TCP_NODELAY`**: the JDK server sets it only where `sun.net.httpserver.nodelay` is true
  (read once, at its first server). Without it every multi-write response waits for the
  client's delayed ACK: 390 req/s, 41 ms each (`oha -c 16`, 11.6 KB body); with it 45,000 --
  64,000. A one-write body is unaffected. `Definition.start` sets the property to `true` unless
  the application set it, inside the `java.class.path` test, so a compiled program holds none
  of it.

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
| An exception after the commit | the chunks are cut | the chunks are cut | ends as it is, the connection closed |
| `UnavailableException` for a time | 503, `Retry-After: 5` | 503, a date; then 404 | 503, no field |
| An error page's `ERROR_MESSAGE`: of an exception; of `sendError(410)` | its message; empty | the same | its `toString()`; the reason (`Gone`) |
| `Set-Cookie` with `Max-Age` | no `Expires`, attributes by name | `Expires` added | `Expires` added |
| A servlet registered as an instance, not loaded on startup | first request | first request | at the start |
| The order of `destroy` | reverse of registration | its own | filters first |
| A HEAD of an empty response | `Content-Length: 0` | none | `Content-Length: 0` |
| The session cookie | `JSESSIONID=..; HttpOnly; Path=/app` | `..; Path=/app; HttpOnly` | `..; Path=/app` |
| `SessionCookieConfig` untouched: name, path, `isHttpOnly()`, attributes | `null`, `null`, true, `{HttpOnly=}` | `null`, `null`, false (sent all the same), `{}` | `JSESSIONID`, `/app`, false, every attribute |
| The default tracking modes; `encodeURL` of a new session | `COOKIE`; as given | `COOKIE, URL`; `;jsessionid=` added | the same as Tomcat |
| A session's interval, the id | 1800 s; 32 hex digits | 1800 s; 32 hex digits | none (-1, an embedded context); `node0..`, the cookie `.node0` more |
| The requested id after `changeSessionId` | the client's, invalid | the new one, valid | the client's, invalid |
| An id no session has, then `getSession(true)` | requested, invalid; a new id | the same | the same |
| The event of a replaced attribute; the order of bound / unbound | the old value; new bound, then old unbound | the new value; the same | the old value; old unbound first |
| A stop with sessions in memory | invalidated, listeners told | the same (values not serializable) | kept, nobody told |

Decisions the table shows: the specification's own text wins where it speaks (3.5.2's
canonical path and rejected sequences, a POST's form only, the `IllegalStateException` of an
undecodable parameter); UTF-8 for a form because the query string's is UTF-8 in all three; a
relative redirect stays relative because a `wasi:http` request has no `Host` to make it
absolute with. The 6.1 javadoc's "short hypertext note" for a redirect: neither container
sends one, nor does this.

## Not there (each refused where it is asked for, never ignored)

Security constraints and authentication (`setServletSecurity`:
`UnsupportedOperationException`; `authenticate` / `login`: `ServletException`), `web.xml`,
`@HandlesTypes` with no class path, JSP, upgrades (`UnsupportedOperationException`), trailers
(`setTrailerFields`: `IllegalStateException`, which its javadoc names; a request's are the
API's empty map), server push (`newPushBuilder()` null), a second context a server, SSL
session tracking (`IllegalArgumentException`), passivation (`HttpSessionActivationListener` is
no listener `addListener` takes), HTTPS on the four compiled targets.

**`web.xml` is not supported, by decision** (user): keep the output small (no XML parser in a
compiled application); added when someone asks. `ServletContext.log` writes to `System.err`;
a form read into parameters is at most 2 MiB unless the builder says otherwise (`log`,
`maxFormSize`).

## What the rontolisp side needs

A servlet program compiled with `--java-source-path
servlet-httpserver/src/main/java:jakarta.servlet-api-6.1.0-sources.jar` met seven refusals,
six in the API's sources (not ours to edit, so the front end takes them):

| Refused | Where | Reached by | Answer |
|---|---|---|---|
| the modifier `transient` | `GenericServlet`, `GenericFilter`, `ServletRequestEvent` | every servlet | taken on a field (no serialization); refused elsewhere as javac refuses it |
| `java.util.EventObject` | the events | `WebContext.start()` | library source |
| `java.util.EventListener` | the listener interfaces | `WebContext.start()` | library source |
| `java.lang.reflect.Method` (`getDeclaredMethods()`, `getSuperclass()`, `Method.getName()`) | `HttpServlet.doOptions` -> `getAllDeclaredMethods` | every `HttpServlet`, through `service` | the names of a class's declared methods (`.kb/java-frontend.md`, "A class as a value") |
| `java.io.CharConversionException` | `ServletOutputStream.print(String)` | `HttpServlet.doTrace` | library source |
| `Cloneable` / `Object.clone()` | `Cookie` | a program naming `Cookie` | front end; pinned by the servlet case's cookie request |
| `Locale.forLanguageTag(String)` | `Fields.locales` | `getLocale()` / `getLocales()` | front end ([java-frontend.md](java-frontend.md), "A locale of any language"); pinned by the `Accept-Language` request |

- **`doOptions`' names were measured before decided**: every `HttpServlet` reaches it, and the
  names cost +10,210 B of the hello component; a `Method` per name in lowered code was 3,728 B
  for 29 names, so one string a class split by `Class` is the form kept.
- **Pinned by** `FetchSpecE2eTest#javaHttpServer`, the program `servlet` of
  `java-http-server-spec.yaml` (`sourcePath:` this module, `sourceDep:` the API; the JDK leg
  runs the module javac compiled against the API's jar): listener, filter, exact / prefix /
  extension servlets, form, forward, include, the API bundle's 405, OPTIONS, HEAD, TRACE,
  redirect, exception, buffer commit, 204, context-root redirect, 404, stop, sessions,
  asynchronous requests, multipart, static resources, error pages; every leg answers the JDK's
  responses and log lines. **Its helper is `note`, not `log`**: inside a servlet the simple name
  `log` is `GenericServlet.log` (JLS 15.12.1), which writes to `System.err`.
- **Embedded Tomcat 11.0.27 by hand** (not a test) answers every application line the same;
  only the container's own differ (error pages, `Content-Language: en`, OPTIONS without
  `TRACE`, `setBufferSize(16)` leaving the response uncommitted).
- **The program pays for what it reaches**: of a hello component's code the API is ~8 KB and
  the container ~35 KB; the library classes they reach (`ConcurrentHashMap`, `HashMap`,
  `MessageFormat` / `Properties` for the API's bundle, `URI`) more; the rest is run time,
  funcall dispatch and resume bodies.
- **What a component answers differently, by the transport**: `wasi:http` hands no `Host` and
  no peer, so `getServerName()` is `localhost`, `getServerPort()` 80, `getRemoteAddr()`
  `0.0.0.0`; every response with a body is chunked on the wire. A `--native` executable writes
  no `Date`. The 302 reason phrase is `Temporary Redirect` on the interpreter and the JVM
  (the JDK server's), `Found` on the other two.
- A module is compiled from its `-sources.jar` (attached by `maven-source-plugin`) or from
  `servlet-httpserver/src/main/java` as a directory root. User doc: `doc/en/java/servlet.md`.

## The example: `examples/java/guestbook/`

A guestbook of one file, registered by annotations alone (`main` is the builder's
`.port(port).contextPath("/guestbook").discover().start()`; a WAR is the classes alone).

- **Pinned by** `FetchSpecE2eTest#javaHttpServer`, program `guestbook` (`example:`), on the
  JDK and the four legs; its compile legs in `examples.yaml` (`javaSourcePath` from the
  example's pom); `MavenBuildE2eTest#anExampleOfTheCheckoutBuildsToAWarAndAComponentThatAnswerAlike`
  (its war on Tomcat and its component on `wasmtime serve` answer alike). **Its JDK leg is
  `compiled: true`**: the source launcher keeps a program's classes in memory, where no scan
  finds them.
- **Tomcat 11.0.27 by hand**: every application answer the same. Trap: Tomcat hands
  `/guestbook` (no slash) to the context root's servlet, where a relative `entries` resolves to
  `/entries`; so `Home` redirects to `getContextPath() + "/entries"`.
- **The form's charset**: the filter's `request.setCharacterEncoding("UTF-8")` is what makes
  `é` the same on Tomcat (form default ISO-8859-1; without it `Ã©`).
- **Discovery's cost** is not the annotations (1.3 KB) but what reading them asks: resume
  bodies of `Discovery`, `java.net.URL` for `getResources`, `ServiceLoader`, dispatcher growth:
  +2.6% for a program that declares nothing, +5.7% for one annotated servlet.
- Under `wasmtime serve` the entries are an instance's (up to 128 requests an instance).
- **JSON through tiny-json, entries with no lock** (user decisions): `am.ik.json:tiny-json`'s
  `JsonObject` / `JsonArray` (`sourceDep` / `javaSourceDep`); entries a `ConcurrentHashMap`
  keyed by an `AtomicInteger`. **A `CopyOnWriteArrayList` was measured and not taken**: +66 KB
  of component, 56 more functions resumable -- its `volatile` array makes every container loop
  calling a `List` through the interface take a turn; the map was in the program already.
  On Tomcat (tiny-json in `WEB-INF/lib`) the JSON API answers the same.

## Sessions

**Invariant: a session is the `SessionData` a `SessionStore` hands back -- loaded by the id a
request names, handed back after every change, deleted when invalidated -- so where sessions
live is the store's business and the container is the same for every store; nothing runs on
a clock.** (User: memory first, then `wasi:keyvalue`.)

- **The store** (`SessionStore`: `load`, `save`, `delete`, `expired(now)`): the builder's
  `sessions(s -> s.store(..))`, else the first `ServiceLoader` provider of the context's loader,
  else memory (`WebContext.sessions()`, at the first request asking). `save` throwing
  `IllegalArgumentException` makes `setAttribute` throw with the attribute restored.
  `MemorySessionStore` keeps the object, so requests share it; a copying store hands each
  request a copy and the last `save` wins (`ServletHttpServerTest`'s `CopyingStore`). Every
  change is saved at once: no end-of-request hook, so nothing of it in a program that never
  asks for a session.
- **Expiry needs no thread**: an expired session is invalidated where found
  (`Sessions.access`), and the first session request after a minute asks the store for the
  expired ones (`sweep`). A request in hand does not keep its session alive.
- **Invalidation** (`Sessions.invalidate`): listeners in reverse order while attributes are
  still there, then the store forgets it, then each attribute is unbound. A stop invalidates
  the memory store's sessions and leaves another store's.
- **The id**: 16 octets of `SecureRandom` (host entropy when compiled: `.kb/java-frontend.md`,
  "java.security and javax.crypto"), drawn again while the store has it; the client's id is
  never reused for a new session.
- **Tracking**: cookie only unless `setSessionTrackingModes` asks for `URL` (`;jsessionid=`,
  7.1.3). Of several cookies of the name, the first that names a session. The cookie is written
  by `CookieConfig` and read by `Fields.cookieValues`, **never through
  `jakarta.servlet.http.Cookie`** (keeps `Cookie` out of a program). `HttpOnly` on by default;
  the path is the context's.
- **Written for pruning**: `Sessions` is made by the first request asking; tracking modes are
  two flags, not an `EnumSet` (an `EnumSet` in every context was 44,825 B of the hello
  component).
- **Pinned by** `ServletContainerTest#aSessionIsTrackedByItsCookieOrItsUrl` (the three
  containers), `#theApplicationIsInitializedServedByManyThreadsAndDestroyed` (the stop),
  `ServletHttpServerTest#sessionsAreKeptInTheStoreTheServerIsGivenOrTheOneItsLoaderNames`
  (copying store by provider file, refused value, expiry at access, cookie config,
  refusals), and `FetchSpecE2eTest#javaHttpServer`'s `servlet` program on the JDK and the four
  legs.

### What a session in an instance's memory is worth under `wasmtime serve` (wasmtime 51.0.0)

- **An instance is dropped after 1 s idle**: on the virtual-thread executor no thread waits,
  so a session counter resets after a 1.5 s gap. The old cached pool kept an idle thread
  waiting 60 s, and an instance with a waiting thread is not idle to the host -- a keep-alive
  by side effect. The host's `--idle-instance-timeout` is the setting (the guide says so).
- **128 requests an instance** (`--max-instance-reuse-count`), then a fresh one.
- **Requests at once go to several instances**, each with its own memory: four concurrent
  requests carrying one session's cookie were answered by fresh instances three times.
- **`-S keyvalue=y` keeps nothing between requests** (the page counter
  `examples/wit/keyvalue/page-hits-server.lisp` answered 1 every time, as
  `doc/en/guides/http-handler.md` says). A `wasi:keyvalue` store is worth it only on a host
  whose provider outlives the request (wasmCloud's, the wicket's), not `wasmtime serve`'s.

### Sessions in `wasi:keyvalue`

**Invariant: the container finds a `SessionStore` by `ServiceLoader` and knows nothing of
WIT; `servlet-httpserver-keyvalue/` (`am.ik.servlet:servlet-httpserver-keyvalue`, package
`am.ik.servlet.httpserver.keyvalue`, outside the root reactor) is a provider of it over
`wasi:keyvalue/store@0.2.0-draft2` imported by `@WitImport`. The switch is the library on
the class path or the source path: no code of the application's, no flag.** A module of its
own because `@WitImport` is `rontolisp-java-api`'s, which this container may not depend on.

- **The host**: the sluice wicket (`--listen`, `?wasi=keyvalue` route over a SQLite file)
  runs a servlet component as it is; without `warm` every request is a fresh instance, so
  memory sessions never survive; keyvalue sessions survive instances and a wicket restart.
  `wasmtime serve -S keyvalue=y` (51.0.0) refuses the component at its start (`component
  imports instance wasi:keyvalue/store@0.2.0-draft2, but a matching implementation was not
  found in the linker`) and keeps nothing anyway. So draft2 alone. Spin not measured
  (`.kb/wasi-component.md` names the hosts).
- **What runs where**: in a component `ServiceLoader.load(KeyValueStore.class)` is the host's
  binding; under a JDK, the interpreter and the JVM it is the library's `MemoryKeyValueStore`
  (`META-INF/services`), so the same code, codec included, runs everywhere it can. `--native`
  refuses at the WIT line (the result type does not cross the Preview 1 boundary). A
  `--no-wasi` module imports the store through the canonical ABI, answered by the glue over
  Workers KV. The WIT is a package resource
  (`servlet-httpserver-keyvalue/src/main/resources/am/ik/servlet/httpserver/keyvalue/wit/keyvalue.wit`,
  the draft's `store` verbatim; `.kb/java-frontend.md`, "WIT imports"), so the sources jar and
  the binary jar both carry it.
- **The value** (`SessionCodec`): a version octet, times, interval, `fresh`, then attributes
  as tagged values of a closed set -- `String`, the eight boxes, `byte[]`, `List` / `Set` /
  `Map` of them. Another value is `IllegalArgumentException` at `setAttribute` (the spec lets
  a distributable container refuse). Not Java serialization: the library has no
  `ObjectOutputStream` (reflection over every class's fields). Another version or a truncated
  value is no session: `load` answers null and the sweep deletes it.
- **The access**: a session whose one change is the request goes to
  `SessionStore.saveAccess` (default `save`). `KeyValueSessionStore.saveAccess` writes only
  when the access moved a tenth of the interval, at most 60 s (`ACCESS_STEP_MILLIS`): a
  read-only visit costs no write. The store's choice, not the spec's; the price is expiry and
  `getLastAccessedTime` up to that step early.
- **The sweep**: Workers KV meters writes and lists (free plan 1,000 each a day, 1,000
  operations an invocation), draft2's `list-keys` carries no metadata and `set` no TTL, so
  expiry can be neither the host's nor in key metadata; in the key name it would cost two
  writes an access. A sweep a minute was 1,440 writes + lists a day. Decided: a turn reads at
  most `SWEEP_READS` (16) sessions; `servlet-httpserver:sweep` holds `<next time>` or `<next
  time> <keys before the resume point>` (a count, not the host's cursor, whose life is
  undocumented); a turn first writes `now + SWEEP_MILLIS` (10 min, so a failed turn starts over
  later), then, stopped at its budget, `now + SWEEP_TURN_MILLIS` (1 min) and the place.
  Insertions shift the place (a key skipped or read twice), which the next pass mends. A
  bucket is opened and closed by every call: the wicket retires a pooled unit whose resource
  table is left non-empty. **Concurrency trap**: two requests of one session at once, the last
  `set` wins, and a write after another instance's `invalidate` brings the session back (no
  `cas`).
- **Cost** (counter servlet, median): an instance a request, memory 7.1 ms (session lost),
  keyvalue 12.8 ms; `warm=1` 1.3 / 1.8 ms. The library is ~45 KB of a component.
- **Pinned by** `KeyValueSessionStoreTest` (provider found, value refused, every value kind
  round-tripped, the access step, the sweep over pages, its turns and throttle),
  `FetchSpecE2eTest#javaHttpServer`'s `keyvalue-sessions` (the JDK, the interpreter, the JVM;
  the component leg on the wicket `-Drontolisp.wicket` names, skipped without it; the reactor
  leg on node under JSPI with a Workers-KV-shaped namespace -- two keys a page, a write landing
  after its response, a write held 2 s refused, a second write to a key within a second lost;
  native skipped: refused), and the guestbook's session on every leg in memory.
- **On a Cloudflare Worker**: the glue's `defaultHost()` answers draft2 over the KV namespace
  `KEYVALUE` (store `""`), and `worker()` puts a request's writes after its answer, one per key
  and a second apart (`.kb/wasm-import.md`, "Answering before the write") -- Workers KV takes
  one write a second to a key (`429` past it). A session read in another location is up to 60 s
  old (KV propagation), and one read by another isolate before the previous answer's `put` is
  the one before it. The example: `examples/cloudflare-workers/hello-session-one-source/` over
  `examples/java/hello-session/`.

## Asynchronous requests

**Invariant: no thread holds a request in hand. Once the dispatch that started an
asynchronous cycle returned, `WebContext.handle` returns and the exchange is answered later --
after its handler returned, as a JDK `HttpExchange` may be, which every transport of
rontolisp's allows (`.kb/java-frontend.md`, "com.sun.net.httpserver") -- by whoever ends the
request. What the container owes the cycle -- the `ASYNC` dispatch, the listeners, the answer
to a timeout or an error, the end of the response -- runs where it is asked for: on a thread
of the executor that `complete()`, `dispatch()`, a listener of reads or writes set from
another thread and the timeout hand it to, on the thread that reports an error or stops the
server. One thread at a time runs it (`Async.steps`, `running`); what is asked during a
dispatch of the container runs once the dispatch returned.**

- **What a thread a request costs** (JDK 25, 2,000 requests in hand, NMT): a returning handler 79
  threads / 10.2 MB committed stacks; a waiting handler on a cached pool 2,008 threads / 129 MB
  committed (62 KB committed, 1 MiB reserved a request); on
  `Executors.newVirtualThreadPerTaskExecutor()` 73 threads / 9.5 MB. All in hand after 360 /
  694 / 444 ms. Hence the virtual-thread default ("Java 21") and no waiting thread at all ("No
  thread a request in hand").
- **The course** (`WebContext.serve`, `Async.begin` / `steps`): the request's dispatch; if it
  called `startAsync`, what was asked meanwhile runs on the handler's thread (`begin`), later
  on whichever thread is handed it (`kick`, `runHere`): a step's error, `complete()`, a reported
  error, a dispatch, a listener set, the deadline (30 s unless `setTimeout`, counted from the
  return of the starting dispatch) -- until none is asked; then the deadline is armed in
  `Timeouts` and the thread goes. A dispatch runs the filters mapped to `ASYNC`; the request
  takes the target's path for good (`Request.dispatchAsync`), the first setting the
  `jakarta.servlet.async.*` attributes. A target that starts no cycle completes the request.
  `onComplete`, then `requestDestroyed`, then the exchange closed (`WebContext.ended`).
- **`dispatch()` goes**, for `startAsync(request, response)` with an `HttpServletRequest`, to
  the URI and query that request answered at the start (in a forward's target, the target's,
  as both other containers do); else to where the container dispatched last. `dispatch(path)`
  needs a leading slash (9.7), else `IllegalArgumentException`; no servlet there is a 404.
- **Errors** (the starting or an `ASYNC` dispatch, a `start` task, a read/write listener --
  its own `onError` first --, or the stop for a request in hand: `IOException: the server
  stopped`): what was asked and not run is dropped, each `AsyncListener.onError` is called, and
  unless one completed or dispatched, the request is answered as failed (`WebContext.fail`:
  500 unless committed). A `start` task that throws is that error at once, where Tomcat and
  Jetty log it and wait for the timeout ("the container MAY take care of the errors").
- **`isAsyncSupported()`** is narrowed by each chain element while it runs (`Chain`).
  `startAsync` is refused where false, outside a container dispatch, twice in one, and after
  the response ended; `setTimeout` / `addListener` once the starting dispatch returned (the
  javadoc).
- **Non-blocking I/O**: the exchange's streams block, so `isReady()` is always true and each
  listener is called once after the dispatch returned (`onDataAvailable` while the body is not
  finished, then `onAllDataRead`; `onWritePossible`). A body is finished once its declared
  length is read (`RequestBody`), at once with neither `Content-Length` nor
  `Transfer-Encoding` (RFC 9112, 6.3); `readAllBytes` / `readNBytes` after a read listener is
  `IllegalStateException` (6.1 javadoc).

Measured against Tomcat 11.0.27 and Jetty 12.1.12 (`ServletContainerTest`'s two asynchronous
families; "by hand" rows from a probe not kept):

| Question | Here | Tomcat | Jetty |
|---|---|---|---|
| `requestDestroyed` of an asynchronous request | once complete, after `onComplete` | the same | at the end of each dispatch, before `onComplete` |
| `getSuppliedRequest()` of a listener added with none | `null` (the javadoc) | the request | the request |
| `setTimeout`, `addListener` once the starting dispatch returned | `IllegalStateException` (the javadoc) | taken (by hand) | taken (by hand) |
| A task of `start` throws | at once: `onError`, 500 | logged; at the timeout: `onTimeout`, 500 | the same as Tomcat |
| An `ASYNC` dispatch throws | `onError`, 500, `onComplete` | the same | no `onError`; 500, `onComplete` |
| Thrown after `startAsync` with the buffer written | the 500 page | 500 with the buffer | the 500 page |
| A read listener's `onDataAvailable` throws | its `onError`, `onError`, 500 | the same, the connection closed with no reply | its `onError`, then the timeout's course |
| The `async.*` attributes of a cycle the pair started in a forward's target | the request's first path | the first path; the mapping the forward's | the forward's |
| `isFinished()` once the declared length is read | true | true | after a read of -1 |
| No body, before a read: `isReady()`, `isFinished()` | true, true | true, true | false, false |
| `setReadListener(null)`; `setWriteListener(null)` | `NullPointerException` both (the javadoc) | the same | `IllegalStateException`; taken |
| A blocking read after `setReadListener` | `IllegalStateException` | the same | reads |
| `dispatch("x")` (relative); to a path no servlet has | `IllegalArgumentException`; 404 | `IllegalArgumentException`; `UnsupportedOperationException` (by hand) | dispatched, 500; 404 (by hand) |
| A timeout of 200 ms (by hand) | 200 ms | 0.8 to 1 s (a background thread's period) | 200 ms |

The three agree (pinned) on: the response waits for `complete()`; `isAsyncStarted()` true
until the starting dispatch returned even after `complete()`; a second `startAsync`, a second
dispatch and `complete()` after a dispatch refused; a dispatch asked during the starting
dispatch runs after it returned; an unanswered timeout is a 500 keeping earlier fields, after
which `complete()`, `dispatch()`, `getResponse()` are refused; `onStartAsync` for the cycle
before; read-listener events with and without a declared length; `onWritePossible` once.

- **Pinned by** `ServletContainerTest#anAsynchronousCycleEndsByCompletionDispatchTimeoutOrError`
  and `#nonBlockingReadsAndWritesAreCalledBackOnce` (the three containers),
  `ServletHttpServerTest#registrationIsOpenUntilTheStart` (refusal words, a request held at the
  stop), and `FetchSpecE2eTest#javaHttpServer`'s `servlet` program on the JDK and the four
  legs (`/app/later/*`: a `start` task, an `ASYNC` dispatch through a filter, a request
  completed by another (`meanwhile`), a timeout listener, an error, a read listener echoing a
  body).
- **A program that never calls `startAsync` pays nothing for the course**: no `Async` is made,
  so none of its methods is lowered. Trap: a registry of in-hand requests calling
  `List.remove(Object)` from code every request reaches brought every collection's `remove`
  and iterators (+20 KB); keep such state inside `Async`.

### No thread a request in hand

Measured (JDK 25.0.5 Graal; 2,000 asynchronous requests in hand, each `setTimeout(0)`,
released together):

| Leg | A thread waiting a request | No thread |
|---|---|---|
| JDK, a cached pool | 2,068 OS threads, 124 MB committed stacks | 182 (the pool's idle threads of the burst), 14.8 MB |
| JDK, the default executor | 130 OS threads, 9.3 MB, 2,000 virtual threads parked | 136, 9.4 MB, none |
| interpreter | 138 OS threads, 10.2 MB, 4,000 virtual threads parked | 145, 10.7 MB, none |
| JVM backend | 134 OS threads, 9.6 MB, 4,000 virtual threads parked | 136, 9.6 MB, none |
| `--native` | a request in hand a nesting of the module's one stack: 400 fine, 800 `wasm trap: call stack exhausted` | 2,000 fine, RSS 46.2 -> 110 MB |
| component | the request's task plus the transport's and the container's frames | the request's task alone |

Virtual threads are counted from `jcmd Thread.dump_to_file -format=json` (NMT does not show
them). JDK probe: an exchange answered 300 ms after its handler returned is a 200; `close()`
with no response closes the connection with nothing; `stop(2)` with an exchange open waits 2 s,
then closes it; an answer after the stop is `IOException: stream is closed`.

- **The container**: `WebContext.handle` returns once the starting dispatch returned
  (`Async.takeOver`, then `begin`); `complete()`, a dispatch, a listener set from another
  thread and the timeout hand `Async.steps` to the executor (`kick`); a reported error and the
  stop run where reported (`runHere`). Deadlines are one heap a context (`Timeouts`, O(log n)
  arm and release) with one daemon thread waiting for the soonest, made by the first deadline
  and stopped with the server; releasing the soonest wakes it, so under a component no host
  timer stays armed for a request that ended.
- **A response failing once committed after the handler returned ends as it is**
  (`WebContext.close`, `handler` false): `com.sun.net.httpserver` cuts an exchange only for a
  handler that throws. The cut stays where the handler's thread is there.
- **Trap: no wait under a monitor.** `WebContext.hold` once called `Async.fail` with the
  `waiting` monitor held, and `fail` reaches a wait, so `JavaResumable` treated every monitor
  entry as possibly blocking: 329 of 1,060 functions resumable against 74 with the call moved
  outside (`-Drontolisp.java.resumable=<file>` lists them). Likewise a monitor in
  `HttpServing.Exchange.done()` made every write path resumable, and `String.valueOf(thrown)`
  reached the generic `%java-to-string` (+34 KB) where `thrown.toString()` does not.
- **Pinned by** `ServletHttpServerTest#anHttpsServerWithItsLogItsFormLimitAndADefaultErrorPage`
  (a single-thread executor serves other requests while an asynchronous one is in hand) and
  `FetchSpecE2eTest#javaHttpServer`'s asynchronous requests on the JDK and every leg.

## Multipart bodies

**Invariant: the parts are read only for a servlet with a multipart configuration, by a
`Multipart` its registration makes, so a program that gives none holds none of the reading;
a part goes to a file only past the threshold and only where there is a directory, so a
program with none (a compiled one: no `java.io.tmpdir`) keeps every part in memory.**

- **The parser** (`MultipartBody`, written from RFC 2046 5.1.1 and RFC 7578; no container's
  source read): read as if a line end came first, so the first delimiter needs none; after a
  delimiter `--` closes the body (epilogue unread), else transport padding and `CRLF`;
  anything else, or an end before the closing delimiter, is an `IOException`. Part fields end
  at an empty line, folded lines unfold, 8 KiB at most (`IllegalStateException`), read in the
  request's charset else UTF-8. `Content-Disposition` must be `form-data` with a `name` (4.2).
  A backslash in a quoted parameter escapes only `"` and `\` (HTML forms escape no backslash,
  so a Windows path stays whole); `filename*` (RFC 8187) wins over `filename`, dropped where
  broken.
- **Storage** (`FormPart`): memory up to `fileSizeThreshold`, past it a file
  `part-<time>-<n>.tmp` made with `CREATE_NEW` (not `createTempFile`, which the subset puts in
  `/tmp` whatever `java.io.tmpdir` says) in an absolute `location`, else `location` below
  `java.io.tmpdir`, else none (memory). Files are deleted once the request is complete (after
  `requestDestroyed`). `write(name)` resolves a relative name against the directory and MOVES
  a part in a file, which is read from there afterwards.
- **Limits**: `maxFileSize` per part; `maxRequestSize` against the declared length, then the
  octets read; each an `IllegalStateException`. A read failure is rethrown by every later
  `getParts` / `getPart`; `getParameter` throws `IllegalStateException` (6.1), caused by an
  `IOException` for a malformed body.
- **Parameters**: parts without a file name, after the query's, of any method, while the body
  is unread; charset: the part's `Content-Type`, else `_charset_` (RFC 7578, 4.6), else the
  request's, else strict UTF-8.
- **Refused**: `getParts` with no configuration or after `getInputStream()` / `getReader()`:
  `IllegalStateException`; not `multipart/form-data`: `ServletException`.

Measured against Tomcat 11.0.27 and Jetty 12.1.12 row by row in
`ServletContainerTest#aMultipartBodyIsReadAsPartsWithinItsLimits` (`measured(..)`). Where they
differ this one follows the RFCs strictly: a malformed body (LF line ends, no closing
delimiter, a delimiter followed by text, no `Content-Disposition` / `name`) is an
`IOException`, where Tomcat often reads on and Jetty answers `ServletException`; a field's own
`charset` and `_charset_` are honored (Tomcat: neither); the query's parameters come first.

- **Trap**: the parse cache and rethrow must not be reachable from `ownParameters` in a
  program with no configuration (a first cut cost the guestbook +5.6 KB; now +1.5 KB and no
  function of `MultipartBody` or `FormPart`).
- **Pinned by** that test, `DiscoveryTest#anApplicationIsWhatItsAnnotationsAndItsInitializersDeclare`
  (`@MultipartConfig(maxFileSize = 4)` on `annotated.Hello`, in the three), and
  `FetchSpecE2eTest#javaHttpServer`'s `servlet` program (`/app/upload`) on the JDK -- part in a
  file of `java.io.tmpdir` -- and the four legs, which keep it in memory.

## Static resources

**Invariant: the application's resources are the files under `META-INF/resources` of what its
class loader finds, after those of a document root (the builder's `documentRoot`); one Java reads them
under a JDK -- each asked what it is (`Located`: a file's or an archive entry's own kind,
length and time) -- and compiled by rontolisp, where they are what the program carried (its
octets; a directory reads as its entries' names; the time is the server's making). What
reads and serves them is made only where there is something to serve, which a compiled
program has answered where it was compiled.**

- **The places** (`WebContext`): `DocumentRoot` (`java.nio.file`), made by `documentRoot`;
  `ClassPathResources`, made where the loader finds `META-INF/resources/` -- asked as a URL
  under a JDK and as a stream elsewhere (a URL made a compiled program carry `URL`'s methods).
  The front end answers that lookup for a constant name no place holds
  ([java-frontend.md](java-frontend.md), "Annotations read where the program runs": the third
  question), so a program with no `META-INF/resources` makes neither place (the guestbook would
  otherwise hold ~22 KB more). Lookup: document root, then class path; directories merge.
  `Resource.normalized`: `.` / `..` resolved, empty segments dropped; a climb above the top, a
  backslash or a control character finds nothing. An archive with no directory entry
  `META-INF/resources/` is not a place (a JDK's loader does not find the directory).
- **The default servlet** (`DefaultServlet`, `default`, on `/` unless the application maps
  one; registered only with a place). GET and HEAD; OPTIONS `Allow: GET, HEAD, OPTIONS`; other
  methods 405. A trailing slash serves `index.html` / `index.htm`; a directory without it
  redirects (query kept). RFC 9110 13.2.2 preconditions in order with no entity tag of its own
  (`If-Match` of a tag 412, `If-None-Match: *` 304). One byte range (14.1), `If-Range` by
  exact date; several ranges or another unit answered whole; HEAD and an empty resource take no
  range; 416 with `Content-Range: bytes */<length>`. An include writes the octets alone and
  throws `FileNotFoundException` for nothing found. `WEB-INF` / `META-INF` (any ASCII case)
  are not served.
- **Compiled ahead of time** (`ClassPathResources.find`, no `java.class.path`):
  `getResourceAsStream` read whole (`Resource.Octets`); a directory is a resource whose first
  line names a resource below it; an empty directory or an archive's reads as an empty file,
  and an empty resource with a welcome file below it is a directory. `Last-Modified` is the
  context's making in whole seconds: resources cannot change after, so a 304 is right for the
  instance's life and a new build answers 200 (a fixed time could not tell builds apart).
- **What a program carries**: the lookups compute their name, so the computed-resource bound
  applies -- under a DIRECTORY of the source path every file of it. A program serving
  resources compiled by the Maven plugin carries everything of `target/classes` that is no
  class file.
- **Measured against Tomcat 11.0.27 and Jetty 12.1.12** (`ServletContainerTest#theDefaultServletServesTheResourcesOfAPath`;
  Tomcat `listings` off, Jetty `dirAllowed` off; each difference is a `measured(..)` row).
  Here: no entity tag and no charset on text types, several ranges or another unit answered
  whole (Tomcat / Jetty send `multipart/byteranges`), `WEB-INF/` / `META-INF/` of the document
  root 404 (Jetty serves them), `getResource` without a leading slash
  `MalformedURLException` (Tomcat finds it), `WEB-INF/classes/META-INF/resources` of a WAR
  served (both others 404, `DiscoveryTest`).
  A JDK's `JarURLConnection.getLastModified()` answers the archive's time, so `Located` takes
  the entry's.
- **The shiori's assets were weighed and left** (`examples/java/shiori`): its
  DispatcherServlet owns `/`; its URLs carry a content hash and are `immutable`, which a
  `Last-Modified` of the server's making would turn into a revalidation per instance; and the
  program would carry the servlet and every file of its source-path directories.
- **A WAR or an exploded directory as the application** (a loader of the container's own over
  `WEB-INF/classes` and `WEB-INF/lib`): out of scope by user decision; a JVM-only path. It
  would need the entry point, the loader's delegation order (6.1, 10.7.2), and discovery over
  the new loader.
- **Pinned by** that test (the three; this container also with `java.class.path` cleared and
  a loader of a directory and a jar), `DiscoveryTest#anApplicationIsWhatItsAnnotationsAndItsInitializersDeclare`
  (archive and class resources), and `FetchSpecE2eTest#javaHttpServer`'s `servlet` program
  (`src/test/resources/servlet-resources` on its source path) on the JDK and the four legs.

## Error pages, HTTPS, the options

**Invariant: an error a response answers -- `sendError`, or what the container answers for
a servlet's exception, a 404, a timeout -- is a state of the response until the dispatch
of the container that asked for it returned; then `Response.complete()` answers it, by
the application's page through an `ERROR` dispatch where `ErrorPages` (made only by the
builder's `errorPage`) has one, else by the container's own page.** Measured against Tomcat
11.0.27 and Jetty 12.1.12 in `ServletContainerTest#anErrorOrARedirectEndsTheResponse`.

- **What the three agree on, pinned**: after `sendError` `isCommitted()` is true and later
  writes are dropped; the page answers after the servlet returned, through `ERROR` filters, by
  `GET` whatever the method (`ERROR_METHOD` holds it); the status is the error's unless the
  page sets one; earlier fields stay; `ERROR_QUERY_STRING` is the client's (after a forward
  too); `ERROR_EXCEPTION` is the root cause of a `ServletException`; an exception's page is its
  nearest class's, then the root cause's (10.9.2), then 500's, then the any-error page
  (`errorPage(location)`, here alone).
- **Where it runs**: in `WebContext.serve`'s `finally` before `requestDestroyed` (Tomcat's
  order), and in `Async.finish` before `onComplete`; a `forward` whose target asked for an
  error leaves it to the request's end. A page that throws is logged; one that asks for an
  error itself gets the container's page (no second dispatch).
- **After the commit** a failure sets `broken`: `complete()` leaves the body unended and
  `handle` throws, so the JDK server closes the connection, as Tomcat cuts the chunks.
- **HTTPS** (`https(SSLContext)`): `Definition.start` binds through a `Binding` the builder
  method made (`HttpsServer`), so a program that never calls it names no TLS class;
  `WebContext.secure` sets scheme, `isSecure()`, default port 443 and the session cookie's
  `Secure`. A compiled program calling it is refused at `javax.net.ssl.SSLContext` (no TLS
  server in the subset, `.todo/050`). Pinned by
  `ServletHttpServerTest#anHttpsServerWithItsLogItsFormLimitAndADefaultErrorPage`.
- **Decided, not made**:
  - *Security*: an embedded container owes no authentication; what a program uses -- a filter
    that authenticates and wraps the request (Spring Security's
    `SecurityContextHolderAwareRequestFilter`) -- needs nothing of the container.
  - *Trailers, upgrades, push*: `HttpExchange` sends and reads no trailer and hands no
    connection to upgrade, so each refuses (see "Not there").
  - *A second context*: two applications are two servers; a component is one `wasi:http`
    handler.
  - *The context root without its slash*: the redirect stays (Jetty's way); Tomcat's answer
    by the root's servlet broke the guestbook's relative links.
  - *The writer's per-write cost*: an ASCII fast path gained ~10% on a 3,000-`print` body and
    nothing on print-once, for more code in every compiled program: not taken.
- **Trap**: the error dispatch lives in `Response`, not `WebContext`: there it was reached
  from every program (+14 KB for a hello); now a hello pays +6 KB and `errorPage` +21 KB.
- **Pinned on the four targets** by `FetchSpecE2eTest#javaHttpServer`'s `servlet` program
  (`errorPage(410, "/oops")`, `errorPage(IllegalStateException.class, "/oops")`).

## Java 21

**Invariant: given no `executor`, the server runs a request on a virtual thread of an
executor of its own (`Executors.newVirtualThreadPerTaskExecutor()`), shut down at the stop;
no thread of the container waits on a monitor for what another thread asks, so a waiting
virtual thread never holds its carrier on any JDK of the release.** (User: release 21, the
public API unchanged.)

- **Why a lock, not a monitor**: on JDK 21.0.13 the virtual-thread executor never had 2,000
  asynchronous requests in hand: `Async.next` waited in `Object.wait`, which before JDK 24
  (JEP 491) pins the carrier, and the scheduler's 256 carriers were all held. With `Async` on a
  `ReentrantLock` and a `Condition`: 72 threads, 9.2 MB, all answered in 3.8 s (JDK 25: 73 /
  9.3 MB; cached pool 2,008 / 124-134 MB). The run's
  clients open 32 connections at a time: the default backlog made 2,000 at once take
  15-28 s).
- **The accept backlog is 1024** (`Definition.BACKLOG`, the plain and the HTTPS server; not a
  builder option: public API stays the user's call). The JDK's default (`create(address, 0)`,
  Send-Q 50 in `ss`) made 2,000 connections opened at once wait for the clients' SYN resent
  (1, 3, 7 s). Measured 2026-11-25 (JDK 21.0.13 and 25.0.5, Linux `somaxconn` 4096, trivial
  handler, 2,000 platform-thread clients at once, all answered, ms): backlog 0 2,100-3,700;
  100 2,150; 256 1,200; 512 1,300; 1,024 / 2,048 / 8,192 400-500; 5,000 clients at 1,024 / 4,096 /
  8,192 670-790. Tomcat 11.0.27 default (`acceptCount` 100) 2,100-2,160, at 1,024 330; Jetty 12.1.14
  default (`acceptQueueSize` 0, the system's) 2,090-2,210, at 1,024 470. The system caps it at its
  `somaxconn`. rontolisp's own transports read no backlog (`deviations.md`, "The HTTP server"),
  so the argument costs 6 B in a compiled program (`HelloServlet`, `--no-wasi --optimize=size`: 797,970 -> 797,976). Pinned where Linux's `ss` exists by
  `ServletHttpServerTest` (Send-Q is `min(1024, somaxconn)`). `Files.readString` of a `/proc/sys`
  file returned `"4"` for `4096\n` on JDK 25: read lines.
- **The default executor** dropped `ThreadPoolExecutor` from every component (-21 to -35 KB).
  Patterns, `sealed` (`Registered`, `Resource`), records and switch expressions are
  size-neutral or smaller; `getFirst()` / `getLast()` cost +135 to +868 B, kept for reading.
- **Considered and kept**:
  - `int` constants, not enums, for `Async`'s states: enums cost +3.5 to +3.9 KB (dispatch
    tables, initialization) for nothing.
  - `ServletEntry`'s monitor around a servlet's first `init`: a `ReentrantLock` is +5,950 B in
    every program; the monitor is entered only while a servlet is uninitialized, so on JDK
    21-23 a request waiting for another's `init` pins its carrier for that `init` alone (JEP
    444's "infrequent" case).
  - Other monitors (`WebContext.waiting`, `sessions()`, `SessionData`,
    `MemoryKeyValueStore`'s map) guard memory alone; `ServletHttpServer.stop` holds its own
    across `HttpServer.stop(delay)`, a wait of the one stopping thread.
  - `Match` stays a class: it is the `HttpServletMapping` handed to the application, which a
    record would make equal by value.
  - `reversed()` not used for reverse loops (a view object for what an index does).
  - `webmvc-fn-lite` (Spring Framework 7's floor), `jdbc-postgresql`, `rontolisp-java-api`
    stay release 17: none depends on the container.
- **What a JDK leg answers differently**: a request's thread is a `java.lang.VirtualThread`
  with no name; the keyvalue programs store a plain `Object` as the refused value (the message
  names its class).
- **Pinned by** `ServletHttpServerTest` (`X-Virtual` of a server given no executor) and
  `FetchSpecE2eTest#javaHttpServer` on the JDK and the four legs.

## As a `--no-wasi` reactor

A program on the container compiled `--no-wasi` is a reactor a Cloudflare Worker calls, with
nothing of the container changed (`.kb/java-frontend.md`, "A server as a reactor"). The
`servlet` corpus program answers there as elsewhere but for: `TRACE` (the host's `Request`
takes none), an asynchronous request another request completes and a listener's timeout
(waits no call can end), and a session id, which needs `--host-random`. A memory session lasts
as long as the instance, which the glue replaces after a trap. The example:
`examples/cloudflare-workers/hello-servlet-one-source/`.

## Tests

`servlet-httpserver/src/test/java/am/ik/servlet/httpserver/`: `ServletContainerTest` (fifteen
families -- mapping, the request line and fields, parameters, the body, buffering and commit,
fields and type, errors and redirects, dispatch, filters, sessions, asynchronous cycles,
non-blocking reads and writes, multipart bodies, static resources, the application's life
with two requests at once -- each run in this container, Tomcat and Jetty against a real
socket through `Http`, a client that reads to the end of the connection so the framing
shows), `ServletHttpServerTest` (builder, registration, start and stop, refusals, HTTPS and
the options), `UrlCodecTest` (the spec's example paths), `DiscoveryTest` (an annotated
application the three discover, and what is refused). `servlet-httpserver-keyvalue/`'s
`KeyValueSessionStoreTest` needs the two SNAPSHOTs it depends on installed
(`.github/workflows/servlet-httpserver-keyvalue.yaml`). `Application` is the one application
the three containers are given in code -- here through the builder's `onStartup`, in Tomcat
and Jetty through an initializer --; `Container` starts each.
