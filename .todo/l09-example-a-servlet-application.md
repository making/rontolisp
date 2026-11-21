# l09. Example: a Servlet application that runs on Java and on wasm

Difficulty: Medium

Asked by the user (2026-11-21). k97 made a Servlet program compile with rontolisp on all four
backends (`doc/en/java/servlet.md`, `.kb/servlet-httpserver.md`). Now ship an example under
`examples/java/` that a reader copies from.

- One application, more than a hello world, using only what the container supports today
  (`.kb/servlet-httpserver.md`). Suggested: several servlets mapped by path, a filter (e.g.
  logging or a header), request parameters and a form POST, a JSON or HTML answer, `sendError`
  / `sendRedirect`, a context listener. No sessions or multipart (l03, l05) unless they have
  landed.
- The same source runs:
  - on the JDK, embedded in `servlet-httpserver` (`java` with the class path, documented);
  - deployed to a servlet container (Tomcat), if a WAR is cheap to build; at least say how;
  - through rontolisp on the interpreter, the JVM, `--native` and `--component`
    (`wasmtime serve`).
- Check it in `examples/examples.yaml` (`ExamplesE2eTest`; `javaSourceDep` for
  `jakarta.servlet-api`, the container from the source path) on the four backends, as
  `examples/java/http/` is. Document it in `examples/java/README.md`; link it from
  `doc/en|ja/java/servlet.md`.
- Report each output's size.
