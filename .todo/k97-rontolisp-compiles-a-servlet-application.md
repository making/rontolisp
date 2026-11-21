# k97. rontolisp compiles a Servlet application: the four backends, `wasi:http` under `--component`

Difficulty: High

**Read `.kb/java-frontend-provenance.md` before writing anything.** Follows k96 (the container
module, `.kb/` file it wrote).

Goal: a hello-world Servlet application plus the k96 container plus
`jakarta.servlet-api`'s sources jar (`--java-dep`, `.kb/java-frontend.md`, "A library from
the source path") compiles with rontolisp and serves identically on the interpreter, the JVM,
`--native` and `--component` (a `wasi:http/handler` component, as `com.sun.net.httpserver`
already does: `.kb/java-frontend.md`, "com.sun.net.httpserver"). The same source also deploys
as a WAR to a servlet container through Java.

- **Measure first:** compile the three together and list every refusal by name (the front
  end refuses by name; the k24..k33 YAVI milestone is the model: `.kb/java-frontend.md`).
  Write the list and the wasm size in `.kb`. Fix what is the front end's in this item where
  it is small; claim an item per family that is not (`.todo/claim-number.sh`), and say which
  ones block the hello world. `jakarta.servlet-api` is not ours to edit: its sources are what
  the front end must accept.
- Gaps the container itself causes are fixed in the container (k96's module) when the Java is
  merely unusual, and in the front end when it is legitimate Java a JDK runs.
- Pin: an E2E that serves the application on the four backends and compares the answers to
  the JVM-run container (and, if cheap, to a real container). Prefer extending the existing
  HTTP server E2E (`HttpHandlerJvmTest`, the java-spec server cases) over a new class.
- Size: report the component's size; a program using one servlet should not hold the whole
  API (pay only for what you use).
- Docs: `doc/en|ja/java/` page for "a Servlet application on rontolisp", deviations if any.

## Where k96 left it (2026-11-21, `.kb/servlet-httpserver.md`, "What the rontolisp side needs")

The measurement asked for above was taken once, on `f485c352e`, with scratch copies patched
past each refusal: **seven refusals, and with them worked around the hello world serves on
the interpreter and as a component under `wasmtime serve`** (961,157 B; a wider program with a
filter, a listener, cookies and dispatches 1,191,534 B). The table in the note names each
refusal, its file and what reaches it. Six are in the API's sources -- `transient`,
`java.util.EventObject`, `java.util.EventListener`, `java.lang.reflect.Method` in
`HttpServlet.doOptions` (the one needing a decision, `.todo/k89`), `java.io.CharConversionException`,
`Cloneable` / `clone()` in `Cookie` (`.todo/k16`) -- and one in the container
(`Locale.forLanguageTag`). So this item is those seven, the JVM and `--native` legs (not
run), the E2E, the size breakdown, and the docs. Use the source path form for the API:
`--java-source-path servlet-httpserver/src/main/java:<the API's sources jar>` (the goal above
says `--java-dep`, which is class files for the host's class loader).
