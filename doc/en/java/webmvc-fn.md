# A WebMvc.fn application

Routes written as functions, in the style of Spring MVC's
[functional endpoints](https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html)
(WebMvc.fn), serve on every target of the [subset](index.md). Spring Framework itself is out
of the subset's reach: its application context, its `DispatcherServlet` and Jackson stand on
reflection. [`webmvc-fn-lite`](https://github.com/making/rontolisp/tree/develop/webmvc-fn-lite)
stands for it: a part of WebMvc.fn under the names and the signatures of Spring Framework's
classes, on the Servlet API alone, so that the same source compiles against Spring Framework.
It lives in this repository for now and depends on nothing of rontolisp. It is not Spring
Framework and not a product of its authors.

The routes:

```console
package com.example;

public class Routing {

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
            .GET("/", request -> ServerResponse.ok().body("Hello World!"))
            .GET("/hello/{name}", request -> {
                Map<String, Object> greeting = new LinkedHashMap<>();
                greeting.put("hello", request.pathVariable("name"));
                greeting.put("shout", request.param("shout").isPresent());
                return ServerResponse.ok().body(greeting);
            })
            .build();
    }

}
```

What starts them, a `DispatcherServlet` on [a servlet container](servlet.md):

```console
package com.example;

public class Application {

    public static void main(String[] args) throws Exception {
        DispatcherServlet dispatcherServlet = new DispatcherServlet(applicationContext(new Routing().routes()));
        ServletHttpServer.builder()
            .port(8080)
            .servlet("dispatcherServlet", dispatcherServlet, servlet -> servlet.urlPatterns("/"))
            .start();
    }

    static StaticWebApplicationContext applicationContext(RouterFunction<ServerResponse> routes) {
        StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
        applicationContext.registerBean(DispatcherServlet.HANDLER_MAPPING_BEAN_NAME, HandlerMapping.class, () -> {
            RouterFunctionMapping mapping = new RouterFunctionMapping(routes);
            mapping.setMessageConverters(List.of(new StringHttpMessageConverter(),
                    new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter()));
            return mapping;
        });
        return applicationContext;
    }

}
```

(the imports are Spring Framework's: `org.springframework.web.servlet.function`,
`org.springframework.web.servlet`, `org.springframework.web.context.support`,
`org.springframework.http.converter`; and `am.ik.servlet.httpserver.ServletHttpServer`)

The module's sources jar joins the program's
[source path](index.md#using-a-third-party-library) beside the container's. Both are
`-SNAPSHOT`s on Central's snapshot repository, which `--java-snapshots` adds:

```bash
LIBS="--java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT --java-source-dep am.ik.spring:webmvc-fn-lite:0.1.0-SNAPSHOT --java-snapshots"
rontolisp com/example/Application.java $LIBS                                  # interpreter
rontolisp com/example/Application.java $LIBS -o App.class && java App         # JVM
rontolisp com/example/Application.java $LIBS -o app.wasm --component && wasmtime serve app.wasm
rontolisp com/example/Application.java $LIBS --native -o app && ./app         # native executable
rontolisp com/example/Application.java $LIBS -o worker.wasm --no-wasi --emit-worker-entry   # a reactor: a Cloudflare Worker
```

```
$ curl 'localhost:8080/hello/Ann?shout'
{"hello":"Ann","shout":true}
```

## What is there

Routes (`GET` .. `OPTIONS`, `route`, `add`), routes nested under a path or a predicate
(`path`, `nest`), filters (`filter`, `before`, `after`), error handlers (`onError`); the
predicates by method, path, header fields and parameters with `and`, `or` and `negate`;
path variables, `**` and `{*rest}`; a request's parameters, fields, attributes and body
(`body(Class)`); a response's status, fields and body. The full list, and what is not there,
is in the module's
[README](https://github.com/making/rontolisp/blob/develop/webmvc-fn-lite/README.md). A
member that is not there is `cannot find symbol` where the program is compiled: nothing is
ignored.

Three classes have Spring Framework's names and less behind them:

- **`JacksonJsonHttpMessageConverter` has no Jackson.** It writes and reads the values JSON
  has: a `Map`, a `Collection`, a `String`, a `Number`, a `Boolean`, `null`, an enum constant
  as its name. A body is read into a `Map` or a `List` (`request.body(Map.class)`). An
  object of a class of the program's own -- a record, a bean -- is refused by its name:
  mapping one needs reflection.
- **`DispatcherServlet`** dispatches to handler functions alone: no controllers, no views.
- **`StaticWebApplicationContext`** holds the objects it is handed by name; it injects
  nothing.

A regular expression in a path variable (`{id:[0-9]+}`) is refused when the route is made.

## The same source on a JDK

`javac` compiles the two classes against the module's jar and they run on
`servlet-httpserver` as any [Servlet application](servlet.md) does. Against Spring
Framework's own jars (`spring-webmvc`, with Jackson for the JSON converter) the same source
compiles unchanged, and answers the same: the module's tests run one application on both
and compare every answer. `servlet-httpserver` runs Spring Framework too, under a JDK: its
`ServletContainerInitializer` asks for classes with `@HandlesTypes`, which the container
[hands it](servlet.md#annotations) from the class path.

[`examples/java/hello-mvcfn/`](https://github.com/making/rontolisp/tree/develop/examples/java/hello-mvcfn)
shows it in a Maven project: `mvn -Pspring exec:exec` runs the same source on
`spring-webmvc` in the place of the module.

[`examples/java/mvcfn/`](https://github.com/making/rontolisp/tree/develop/examples/java/mvcfn)
is a small JSON API written this way -- a nested path, path variables, a query parameter, a
request body, a filter and an error handler -- with how to run it on the four targets and
on a JDK. Its JSON is [tiny-json](https://github.com/making/tiny-json)'s, read and written by
a converter of the example's own (an `AbstractHttpMessageConverter`) in the place of
`JacksonJsonHttpMessageConverter`.
[`examples/java/shiori/`](https://github.com/making/rontolisp/tree/develop/examples/java/shiori)
is a web application of HTML pages written this way: its handlers answer the text of
jmustache's templates, and a form is read from `params()` by a YAVI schema.
