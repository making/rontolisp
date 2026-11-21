# servlet-httpserver

A servlet container on the JDK's own HTTP server (`com.sun.net.httpserver`), written against
the Jakarta Servlet 6.1 API: one web application, registered in code, embedded in a `main`.
It implements a part of the specification and claims no compatibility with it: no TCK was
run.

```java
ServletHttpServer server = new ServletHttpServer(8080);
ServletContext context = server.getServletContext();
context.addServlet("hello", new HelloServlet()).addMapping("/hello");
context.addFilter("log", new LogFilter()).addMappingForUrlPatterns(null, false, "/*");
server.start();
```

The only dependency is `jakarta.servlet:jakarta.servlet-api`.

- `new ServletHttpServer(port)`, `(port, contextPath)` or `(address, contextPath)`; port 0
  binds a free port, `getPort()` tells which.
- Servlets, filters and listeners are registered through `getServletContext()` until
  `start()`.
- `stop()` cuts the requests in hand; `stop(seconds)` waits for them that long. The
  servlets, the filters and the context are destroyed after it.
- `setExecutor(executor)` chooses what runs a request; without one the server runs each on
  a thread of a pool of its own.

Not there yet: sessions, asynchronous requests, multipart bodies, security constraints,
error pages, static resources, discovery by annotations or `web.xml`, HTTPS. Each is refused
where it is asked for rather than ignored.

```bash
../mvnw -f servlet-httpserver/pom.xml spring-javaformat:apply test
```

The tests ask one application the same requests in this container, in Tomcat and in Jetty.
