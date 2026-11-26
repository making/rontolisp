# webmvc-fn-lite

A part of the functional endpoints of Spring MVC
([WebMvc.fn](https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html))
under the names and the signatures of Spring Framework's classes, on the Jakarta Servlet
API alone. An application written against it compiles against Spring Framework unchanged,
and the other way round as far as it stays inside the part.

It is not Spring Framework and not a product of its authors (`META-INF/NOTICE`). It is for
where Spring Framework itself cannot go: an application compiled from source by
[rontolisp](https://github.com/making/rontolisp), which has no reflection for an
application context, a `DispatcherServlet` or Jackson to stand on. On a JDK, use Spring
Framework.

```java
public class Routing {

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
            .GET("/", request -> ServerResponse.ok().body("Hello World!"))
            .path("/tasks", tasks -> tasks
                .GET("/{id}", request -> ServerResponse.ok().body(find(request.pathVariable("id"))))
                .POST("", contentType(MediaType.APPLICATION_JSON), this::create))
            .onError(IllegalArgumentException.class,
                    (ex, request) -> ServerResponse.badRequest().body(Map.of("error", ex.getMessage())))
            .build();
    }

}
```

```java
StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
applicationContext.registerBean(DispatcherServlet.HANDLER_MAPPING_BEAN_NAME, HandlerMapping.class, () -> {
    RouterFunctionMapping mapping = new RouterFunctionMapping(new Routing().routes());
    mapping.setMessageConverters(List.of(new StringHttpMessageConverter(),
            new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter()));
    return mapping;
});
servletContext.addServlet("dispatcherServlet", new DispatcherServlet(applicationContext)).addMapping("/");
```

The only dependency is `jakarta.servlet:jakarta.servlet-api`; any servlet container runs it.
The packages are `@NullMarked` (JSpecify), and a member is `@Nullable` where Spring
Framework 7's same member is. The annotations are a `provided` dependency, which nothing
needs where the classes run.

## What is there

- **Routes**: `RouterFunctions.route()` and its builder -- `GET` / `HEAD` / `POST` / `PUT` /
  `PATCH` / `DELETE` / `OPTIONS` in their four forms, `route`, `add`, `nest`, `path`,
  `filter`, `before`, `after`, `onError` --, `RouterFunctions.route(predicate, handler)`
  and `nest`, `RouterFunction` (`and`, `andOther`, `andRoute`, `andNest`, `filter`),
  `HandlerFunction`, `HandlerFilterFunction`.
- **Predicates**: `RequestPredicates.all`, `method`, `methods`, `path`, the seven method
  shortcuts, `headers`, `contentType`, `accept`, `param`; `and`, `or`, `negate`.
- **Path patterns**: segments with `?`, `*` and `{name}`, `**` and `{*name}` as the last
  segment. A regular expression in a variable (`{id:[0-9]+}`) and `**` or `{*name}`
  anywhere else are refused when the route is made.
- **`ServerRequest`**: `method`, `uri`, `path`, `headers` (`accept`, `contentType`,
  `contentLength`, `header`, `firstHeader`, `asHttpHeaders`), `param`, `params`,
  `pathVariable`, `pathVariables`, `attribute`, `attributes`, `body(Class)`, `cookies`,
  `session`, `principal`, `remoteAddress`, `messageConverters`, `servletRequest`,
  `ServerRequest.create`.
- **`ServerResponse`**: `status`, `ok`, `created`, `accepted`, `noContent`, `seeOther`,
  `temporaryRedirect`, `permanentRedirect`, `badRequest`, `notFound`,
  `unprocessableContent`, `from`; `header`, `headers`, `cookie`, `cookies`, `allow`,
  `eTag` (a `GET` or a `HEAD` naming the tag in `If-None-Match` is answered 304),
  `location`, `varyBy`, `contentType`, `contentLength`, `body(Object)`, `build()`,
  `build(WriteFunction)`.
- **Converters**: `HttpMessageConverter` and `AbstractHttpMessageConverter` for an
  application's own, `StringHttpMessageConverter`, `ByteArrayHttpMessageConverter`,
  `JacksonJsonHttpMessageConverter` (below).
- **HTTP**: `HttpStatus`, `HttpStatusCode`, `HttpMethod`, `MediaType`, `HttpHeaders`,
  `MultiValueMap`, `LinkedMultiValueMap`, `ResponseStatusException`.
- **The servlet**: `DispatcherServlet` over a `StaticWebApplicationContext`
  (`registerBean(name, type, supplier)`) holding a `RouterFunctionMapping`, or
  `RouterFunction`s alone. No handler is a 404 (`No endpoint GET /x.`); a
  `ResponseStatusException` its status and reason; an unsupported `Content-Type` 415, a
  body no converter writes 406, a body that cannot be read 400; any other exception is
  the container's 500.

## What stands for what

- **`JacksonJsonHttpMessageConverter` has no Jackson.** It writes and reads the values
  JSON has -- a `Map`, a `Collection` or an array of objects, a `String`, a `Number`, a
  `Boolean`, `null`, an enum constant as its name -- as Jackson does with no
  configuration, and reads a body into a `LinkedHashMap`, an `ArrayList`, a `String`, an
  `Integer`, a `Long`, a `Double` or a `Boolean`. An object of any other class -- a record,
  a bean -- is refused by its name (500): mapping one needs reflection. An integer past a
  `long` is refused (400) where Jackson reads a `BigInteger`.
- **`DispatcherServlet` dispatches to handler functions alone**: no controllers, no views,
  no `HandlerInterceptor`, no CORS, no `MessageSource`. `TRACE` is a route like any other,
  where Spring Framework leaves it to `HttpServlet`.
- **`StaticWebApplicationContext` is a map of names to suppliers**, each called once: no
  injection, no lifecycle callbacks, no events.

## What is not there

Everything else of Spring Framework: `uriBuilder`, `bind`, `multipartData`,
`body(ParameterizedTypeReference)`, `checkNotModified`, `ServerRequest.from`, `lastModified`,
`cacheControl`, `render`, `stream`, `async`, `sse`, `resources`, `withAttribute`, the
`Visitor`s, API versions, the deprecated members and constants. A member that is not there
is `cannot find symbol` where the application is compiled.

## Beside Spring Framework

`ConformanceTest` compiles one application
([`src/test/resources/conformance`](src/test/resources/conformance)) against Spring
Framework 7.1 with Jackson 3 and against this module, runs both on embedded Tomcat and asks
them the same requests, some 190: the answers are the same -- status, fields, body. One
row differs by decision: Spring Framework refuses a path nested under a servlet mapped to a
path prefix (`/sub/*`), which is answered here.

```bash
../mvnw -f webmvc-fn-lite/pom.xml spring-javaformat:apply test
```

## With rontolisp

The sources go on the program's source path beside a servlet container's, here
[`servlet-httpserver`](../servlet-httpserver):

```bash
rontolisp com/example/Application.java -o app.wasm --component \
    --java-source-path servlet-httpserver/src/main/java:webmvc-fn-lite/src/main/java \
    --java-source-dep jakarta.servlet:jakarta.servlet-api:6.1.0
```

[`examples/java/mvcfn`](../examples/java/mvcfn) is such an application.
