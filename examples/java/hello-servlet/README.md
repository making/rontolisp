# Hello World on a servlet container: `hello-servlet/`

[`HelloServlet.java`](src/main/java/HelloServlet.java) is the smallest Servlet application on
[`servlet-httpserver`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver):
one servlet, a lambda, answering every request `Hello World!`
([A Servlet application](../../../doc/en/java/servlet.md)). It is a Maven project
([`examples/java/`](../README.md)) and listens on the port of the `PORT` environment variable,
8080 without one:

```bash
cd examples/java/hello-servlet
mvn package               # target/hello-servlet.wasm, a component
mvn exec:exec             # JDK
wasmtime serve --addr 127.0.0.1:8080 target/hello-servlet.wasm
curl http://localhost:8080/
```

The same file, unchanged, is a Cloudflare Worker:
[`cloudflare-workers/hello-servlet-one-source`](../../cloudflare-workers/hello-servlet-one-source).
