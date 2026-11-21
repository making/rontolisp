# k96. A Servlet container on the JDK HTTP server (a module of its own, JVM first)

Difficulty: High

Theme (user, 2026-11-21): a program written against the Servlet API deploys unchanged to a
servlet container (through Java) AND, compiled by rontolisp, serves as a `wasi:http`
component. The container is "servlet on `com.sun.net.httpserver`": an ordinary Java project
that rontolisp compiles from source like any dependency (YAVI's way, `.kb/java-frontend.md`,
"A library from the source path").

Decided by the user:
- **Where:** a module of this repository for now (develop together, fill each other's gaps on
  the spot), to become a repository of its own later. So it **depends on nothing of
  rontolisp** (no `am.ik.rontolisp` import, no rontolisp artifact) and rontolisp's core
  depends on nothing of it. Outside the root reactor like `rontolisp-native/`
  (`.kb/session-workflow.md`, "Builds outside the root reactor"): the core keeps no external
  dependency. Pick a package and coordinates that will survive the move (not under
  `am.ik.rontolisp`).
- **API:** the official `jakarta.servlet:jakarta.servlet-api` (6.1) as the dependency; no
  interface of `jakarta.servlet` is rewritten.

Scope of this item (the JVM, no rontolisp yet):
- An embeddable server: `new ...(port)`, register servlets and filters programmatically
  (`ServletContext.addServlet` / `addFilter` with instances, url patterns per the spec's
  mapping rules), start, stop. No reflection-based discovery in the first cut (annotations
  and `web.xml` need reflection, `.todo/k89`); keep the door open.
- `HttpServletRequest` / `HttpServletResponse` over `HttpExchange`: method, URI parts
  (context path, servlet path, path info, query), parameters (query and form body), headers,
  body as stream and reader, character encoding, status, headers, `sendError`,
  `sendRedirect`, writer and output stream, buffering and commit, content length.
  `FilterChain`, request attributes, `RequestDispatcher` if cheap. Sessions, async, multipart:
  record what is left as items, do not force them in.
- The module's own JUnit tests on the JVM against a real socket (fewer tests: one per
  behaviour family). Behaviour where the spec leaves room is measured against a real
  container (e.g. Tomcat or Jetty in a test scope) and written down.
- `.kb/servlet-httpserver.md` (or the name you choose): what it is, the invariants, and what
  the rontolisp side needs from it (k97 starts from that list).

Do not change rontolisp in this item unless a gap blocks it; k97 compiles the module with
rontolisp.
