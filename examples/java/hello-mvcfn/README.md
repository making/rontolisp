# Hello World in WebMvc.fn: `hello-mvcfn/`

[`HelloMvcfn.java`](src/main/java/HelloMvcfn.java) is the smallest WebMvc.fn application: one
`GET /` route, answering `Hello World!`, behind a `DispatcherServlet` on
[`servlet-httpserver`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver).
The classes of `org.springframework` are those of
[`webmvc-fn-lite`](https://github.com/making/rontolisp/tree/develop/webmvc-fn-lite)
([A WebMvc.fn application](../../../doc/en/java/webmvc-fn.md)). It is a Maven project
([`examples/java/`](../README.md)) and listens on the port of the `PORT` environment variable,
8080 without one:

```bash
cd examples/java/hello-mvcfn
mvn package               # target/hello-mvcfn.wasm, a component
mvn exec:exec             # JDK
wasmtime serve --addr 127.0.0.1:8080 target/hello-mvcfn.wasm
curl http://localhost:8080/
```

The same file, unchanged, is a Cloudflare Worker:
[`cloudflare-workers/hello-mvcfn-one-source`](../../cloudflare-workers/hello-mvcfn-one-source).

## On Spring Framework

`webmvc-fn-lite` has the names and the signatures of the part of Spring MVC it covers, so the
source is the same. It is not Spring Framework. `-Pspring` swaps the dependency for Spring Framework's own `spring-webmvc`
(7.1.0) and skips the wasm goal, which cannot compile Spring Framework
([`webmvc-fn-lite`](https://github.com/making/rontolisp/tree/develop/webmvc-fn-lite)):

```bash
mvn -Pspring exec:exec    # the same source on spring-webmvc, on the JDK
curl http://localhost:8080/
```

`Hello World!` comes back from both.
