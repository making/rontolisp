# WebMvc.fn on the Servlet API alone (`webmvc-fn-lite/`, 2026-11-22, l32)

**Invariant: `webmvc-fn-lite/` is an ordinary Java project that knows nothing of rontolisp
-- outside the root reactor, its only dependency `jakarta.servlet:jakarta.servlet-api` --
holding a part of Spring MVC's functional endpoints under the names and the signatures of
Spring Framework's classes, so that one application source compiles against it and against
Spring Framework; what it answers is what Spring Framework answers, pinned by running both.
It has none of Spring Framework's code, and it is not Spring Framework: every surface says
so.** Coordinates `am.ik.spring:webmvc-fn-lite`.

```bash
./mvnw -f webmvc-fn-lite/pom.xml spring-javaformat:apply test   # its own build, beside Spring Framework
```

`.github/workflows/webmvc-fn-lite.yaml` runs that on a change under the directory and, on a
push, deploys it to Maven Central (`.kb/session-workflow.md`, "The deploy job"); `ci.yaml`
runs on a change of its `src/main` (the served corpus compiles it).

## Spring Framework measured (2026-11-22)

**Spring Framework 7.1.0 does not compile from its sources jars, at any of three depths**
(measured as pgjdbc was: [java-frontend.md](java-frontend.md), "pgjdbc measured", "A census
of a library").
Several refusals named here are gone since (reflection k89/l64-l66, `Cloneable` k16); the
sizes are lower bounds, each refused statement hiding what it reaches.

- `spring-webmvc` and its dependencies: 3,360 files, 561,278 lines; the library lacked 299
  of the JDK classes they import, 184 more one level down.
- The article's application (`DispatcherServlet` over a `StaticWebApplicationContext`):
  282 refusals in 5,565 methods lowered over shells; what is left is 11,607,768 B.
- Spring's own `function` package under a servlet of ours: 16 refusals in 2,239 methods;
  the hello world reaches 74 files of 28,822 lines, 5,699,341 B as a component. A servlet
  answering the same hello: 983,203 B.
- Jackson 3.1: 17 refusals in 1,264 methods before the mapper is built; its bean
  introspection is reflection throughout.
- Under a JDK, Spring Framework runs on `servlet-httpserver` (`@HandlesTypes`:
  [servlet-httpserver.md](servlet-httpserver.md), "Discovery").

**Decided: a light jar, written from the API.** Spring's jars would be more than five times
a servlet program. Copying and trimming was rejected: the hello world's reach is 28,822
lines written for a JVM (logging and assertions in every method, caches, reactive branches),
each trimmed file a fork; written here the surface is 45 files of 5,642 lines, pinned
against the original by a test instead of by its text.

## What it is

The surface is the module's `README.md`. Behind it:

- **Path variables reach the request only when a whole route matches**
  (`RequestPredicates.VariablePredicate`: `match` answers them, `test` hands them over). A
  `path(..)` nest hands its routes a `NestedRequest`; the handler gets the variables as the
  attribute `RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE`.
- **`RoutePattern`** was read off Spring's `PathPattern` (18 patterns x 24 paths,
  `matchAndExtract`, `matchStartOfPath`): segments compared decoded, without path
  parameters; the empty pattern matches the empty path and `/`; `*` and a variable match no
  empty segment except the one after a trailing slash (a whole `{name}` not that one
  either); a variable among other text takes as much as lets the rest match; `{*rest}` is
  the decoded rest. Refused when the route is made: `{name:regex}` (it would put the regex
  engine in every program), `**` and `{*name}` anywhere but last (as Spring 7).
- **A response is written as Spring writes it**: a 200 to a GET/HEAD naming the entity tag
  in `If-None-Match` is a 304 (`*` is no match; `If-Match` and dates not read); the first
  converter writing the body's class as the `Content-Type` (no `Accept` negotiation:
  WebMvc.fn has none), else `HttpMediaTypeNotAcceptableException`.
  `ServletServerHttpResponse`'s flush is no flush, so a body inside the container's buffer
  goes with its length (Spring 7.1: `spring.http.response.flush.enabled` off).
- **`DispatcherServlet`** maps the exceptions to statuses as Spring does; the default
  converters are `byte[]` and `String` (Spring's form converter is not there).
- **`JacksonJsonHttpMessageConverter` is no Jackson**: measured against Jackson 3.1 for
  escapes, numbers, `null`, enums, map keys, the narrowest of `Integer`/`Long`/`Double`
  read. An integer past a `long` is refused where Jackson reads a `BigInteger` (it would
  put `java.math` in every program reading JSON).
- **Cookies**: a program naming none never names the Servlet API's `Cookie`; the method
  reference that hands cookies to the response is made only by `cookie(..)`/`cookies(..)`.

## Beside Spring Framework: `ConformanceTest`

One application (`webmvc-fn-lite/src/test/resources/conformance/`, every member of the
surface, ~80 routes) is compiled by `javax.tools` against Spring Framework 7.1.0 + Jackson
3.1.7 and against the module, each started on embedded Tomcat 11.0.27 in its own JVM (class
paths cut from `surefire.test.class.path`); ~190 requests, every answer compared (a 500's
page holds a stack trace: status only).

- **One row differs, by decision** (`DIFFERENT`): a nested path under a servlet mapped to a
  prefix (`/sub/*`) is a 500 on Spring 7.1 (`ServletRequestPathUtils`), answered here.
- Not compared: `TRACE` (Tomcat answers 405 itself), the order of `pathVariables()` (a
  `HashMap`'s there), the default converters' number.
- `OwnConverter` (an `AbstractHttpMessageConverter` of the application's own): the
  `Accept` of a 415 lists the converters' types sorted by specificity, as Spring does
  (`DefaultServerRequest.bySpecificity`).

## Written for the compiler

**A program pays for what it reaches, and on wasm for every function a thread can be
suspended in, twice** ([java-frontend.md](java-frontend.md), "Resumable bodies").
`-Drontolisp.java.resumable=FILE` shows what made each function resumable. Four rules took
the example from 546 of 1,777 resumable functions (1,616,684 B) to 65 (1,180,408 B); since
l34 the analysis itself keeps the first, third and fourth out (the example: 16 of 1,787;
[java-frontend.md](java-frontend.md), "What made functions resumable that cannot suspend"),
and the module keeps the shapes:

- **A class whose initializer may throw is initialized lazily, and its static calls, `new`
  and static reads are then synchronization actions "by name"**: a loop around one takes a
  turn. `MediaType`'s twelve constants are made by a private constructor that validates
  nothing, so the class is eager (alone: 521 -> 179).
- **`equals`, `hashCode`, `toString` and `Map` methods reach every caller of the universal
  dispatchers** (`%hash`, `%equals`, `%string`, `Map.get%v` ..): one resumable
  `HttpHeaders.hashCode()` made `HashMap.get` resumable program-wide. So no class here that
  is a `Map` or is hashed walks an `Enumeration` or calls a lazy class, and fields are of
  their exact class (`LinkedHashMap`), called by name.
- **A loop whose test asks an `Enumeration` or an `Iterator` by hand takes a turn** in any
  program holding a `ConcurrentHashMap` (an enhanced `for` is exempt): the Servlet API's
  enumerations are walked recursively (`drain`, `collect`).
- **`Collections.emptyMap()`/`singletonMap()` bring `AbstractMap.remove`**, such a loop,
  into `Map.remove%v`.

`URI.toASCIIString()` is not called: `HttpHeaders.setLocation` writes it without the
normalization that would bring its tables. What stays resumable is the container's dispatch
and `DispatcherServlet.service` down to the handler.

## Sizes and where the bytes go (2026-11-22, wasmtime 51.0.0)

- **The hello world is 1,101,485 B as a component, 118,282 B (12%) over a servlet's**;
  a fifth of what Spring's own `function` package would need at least, a tenth of the
  whole application's.
- **`java.math` costs ~80 KB** (httpbin: 739,881 B with it, 659,088 without; `BigDecimal`
  pulls `BigInteger` in). tiny-json 0.4.1 therefore keeps a number no `long` holds, or one
  with a fraction or exponent, as its text (`NumberText`) and converts only in
  `value()`/`asNumber()`/`asBigInteger()`/`asDecimal()`. **A class only an `instanceof` or a
  signature names costs nothing** (byte-identical). **A program calling `value()` links
  `java.math` again**: read `isString()`/`asString()`.
- tiny-json 0.2.0's `Character.isLetter`/`isWhitespace` brought the regex engine's Unicode
  tables (~11 KB of code and data); 0.2.2 on lexes with ASCII tests.
- On tiny-json 0.4.1, component / `--native`: httpbin 664,911 / 9,469,112; mvcfn
  1,212,254 / 14,388,024; the guestbook 1,218,217 / 14,693,168.

## The example and its tests

`examples/java/mvcfn/`: `com.example.Routing`, `com.example.Application` (registers the
`DispatcherServlet` with servlet-httpserver's builder, no discovery) and
`com.example.TinyJsonHttpMessageConverter`, over a task list. **The package starts in the
example's own directory**: under a shared source tree the guestbook's annotated servlets
were found by discovery and answered `GET /` -- what a JDK does with two applications in
one classes directory, so no switch narrows discovery
([servlet-httpserver.md](servlet-httpserver.md), "Discovery"). Every example using a library
is a Maven project with its own `src/main/java` ([jvm-export.md](jvm-export.md), "The
plugin's `wasm` goal"). `examples/java/hello-mvcfn` is also a Cloudflare Worker, unchanged
(`examples/cloudflare-workers/hello-mvcfn-one-source/`); `mvcfn` and `hello-mvcfn` answer on
the `reactor` leg as on the others (`.kb/java-frontend.md`, "A server as a reactor").

- **Pinned by** `FetchSpecE2eTest#javaHttpServer`, program `mvcfn`: the JDK (javac over the
  two modules' sources) and the four legs answer the same requests; compile legs in
  `examples.yaml`.
- User doc: `doc/*/java/webmvc-fn.md`; the module's `README.md`; the example's.
- **`examples/java/shiori`**: HTML pages (jmustache, `StringHttpMessageConverter`), a form
  read by a YAVI schema. Its cookies are header fields, never `cookie(..)`, which would name
  the Servlet API's `Cookie`. Pinned on the four targets by `ClPostgresE2eTest`
  ([jdbc-postgresql.md](jdbc-postgresql.md), "In a served program").

## JSpecify and NullAway (l55, 2026-11-23)

Every package is `@NullMarked`; NullAway runs on the main compile, `-Werror`, as
servlet-httpserver's ([servlet-httpserver.md](servlet-httpserver.md), "The builder");
jspecify is `provided`.

- **The nullness of each member is Spring Framework 7.1's**, read from its sources jars, not
  only what NullAway asked (`MultiValueMap<K, V extends @Nullable Object>`,
  `header(String, @Nullable String...)` ..). A member never null here keeps Spring's
  non-null.
- One `@SuppressWarnings("NullAway")`: the JSON converter's `readInternal`, Spring's
  non-null `T`, reads the document `null` as null, as Jackson does.
- **Pinned by** `ConformanceTest` and `FetchSpecE2eTest#javaHttpServer`'s `mvcfn`; the
  nullness forms (`V extends @Nullable Object`, `List<@Nullable Object>`,
  `@Nullable String...`) compile on every leg.

## Provenance

The module's 45 files carry the header rule 5 of
[java-frontend-provenance.md](java-frontend-provenance.md) asks of a port and are listed
under "Spring Framework" in `THIRD-PARTY-NOTICES.txt` (`ThirdPartyNoticesTest`): names,
signatures and behaviour are Spring Framework's, the text is not. Its jar carries
`META-INF/NOTICE` with Spring Framework's notice and `META-INF/LICENSE`. The root `NOTICE`
is unchanged: neither the rontolisp jar nor a program compiled without the module holds it.
The behaviour was learned from Spring Framework's sources (Apache-2.0) and from running
it; the enum of statuses and the names of the header fields were printed from its classes
by reflection.
