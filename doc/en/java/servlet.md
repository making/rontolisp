# A Servlet application

A program written against the Jakarta Servlet API serves on every target of the
[subset](index.md) when it runs on a servlet container the subset can compile.
[`servlet-httpserver`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver)
is one: a container on the JDK's own HTTP server (`com.sun.net.httpserver`), which the
subset has on the interpreter, the JVM, a WASM component and a native executable
([HTTP](index.md#http)). It lives in this repository for now and depends on nothing of
rontolisp.

The container's sources and the API's sources jar go on the program's
[source path](index.md#using-a-third-party-library) and are compiled with it:

```console
public class Hello {

    static class HelloServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getWriter().println("Hello, " + request.getParameter("name") + "!");
        }

    }

    public static void main(String[] args) throws Exception {
        ServletHttpServer server = new ServletHttpServer(8080);
        server.getServletContext().addServlet("hello", new HelloServlet()).addMapping("/hello");
        server.start();
    }

}
```

(the imports are `java.io.IOException`, `am.ik.servlet.httpserver.ServletHttpServer` and
the three of `jakarta.servlet.http`)

```bash
LIBS="--java-source-path servlet-httpserver/src/main/java --java-source-dep jakarta.servlet:jakarta.servlet-api:6.1.0"
rontolisp Hello.java $LIBS                                     # interpreter
rontolisp Hello.java $LIBS -o Hello.class && java Hello        # JVM
rontolisp Hello.java $LIBS -o hello.wasm --component && wasmtime serve -S cli=y hello.wasm
rontolisp Hello.java $LIBS --native -o hello && ./hello        # native executable
```

Under `wasmtime serve` the host listens (`--addr`, `0.0.0.0:8080` by default) and the port
the program gives is not bound. A program holds what it reaches of the API and the
container, not all of them: the component above is about 950 KB, of which the API's
code is 8 KB.

Servlets, filters and listeners are registered in code through `getServletContext()`
before `start()`; the container reads no `web.xml` and no annotation. The classes are the
API's own types, so the same servlets run on another container, which registers them
from `web.xml`, annotations or a `ServletContainerInitializer`. What the container has --
mapping, filters, forward and include, parameters and bodies, buffered responses -- and
what it refuses (sessions, asynchronous requests, multipart bodies, security, error pages
and static resources) is in its
[README](https://github.com/making/rontolisp/blob/develop/servlet-httpserver/README.md).

## What differs

- **A component's request has no host and no peer.** `wasi:http` hands the program
  neither a `Host` field nor the client's address: `getServerName()` is `localhost`,
  `getServerPort()` 80 and `getRemoteAddr()` `0.0.0.0`. A body is sent chunked whatever
  length the servlet set.
- **OPTIONS asks the servlet's class the methods it declares** (`HttpServlet.doOptions`),
  which the subset answers by name: [Deviations](deviations.md#what-differs).
- **Not compiled yet**, each refused by name where the program reaches it: cookies
  (`jakarta.servlet.http.Cookie` is `Cloneable`, which the subset does not have) and the
  locales of a request (`getLocale()`, `getLocales()`: a `Locale` of any language).
