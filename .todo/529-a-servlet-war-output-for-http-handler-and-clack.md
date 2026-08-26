# 529. A Servlet war output for `rontolisp:http-handler` and Clack (parent)

Difficulty: High (parent item; each child is sized on its own)

Children: `.todo/530` (the war output and the servlet transport),
`.todo/531` (`:script-name` is fixed empty, so a war under a context path
misroutes), `.todo/532` (Clack on the servlet transport), `.todo/533` (the
Maven plugin builds a war). All OPEN.

Spike files: `.todo/529-a-servlet-war-output-for-http-handler-and-clack/`
(`README.md` reproduces the run).

**A fifth inbound transport, and the cheapest one the project will ever add.**
Today a `rontolisp:http-handler` program serves through the embedded JDK
`HttpServer` on a `.class`/`.jar` output, through `wasi:http` under
`--component`, and through a host-called export under `--no-wasi`. `-o app.war`
would add the transport every JVM shop already has a place to put: Tomcat,
Jetty, WildFly, a Spring Boot war, a container image someone else maintains.

## The spike says yes, and says why it is cheap

Run 2026-08-26 on this machine: embedded Tomcat 11.0.24 (Servlet 6.1) and
Jetty 12.0.31 EE10, one identical war on both, adapter compiled against
`jakarta.servlet-api` 6.0.0.

**The seam already exists and is already transport-neutral.** The Clack cutover
(`.kb/http-server.md`) left `RontoHttpServer` holding two separable halves: the
JDK transport (`start`/`dispatch`/`readRequest`/`writeResponse`) and the value
model it hands over -- `Request`, `Response`, `Handler`, all `public`, all
carrying nothing but transport facts. A compiled program class is emitted as
`public class App implements RontoHttpServer$Handler` with a
`public RontoHttpServer$Response handle(RontoHttpServer$Request)`. A servlet
that fills a `Request` in and writes a `Response` out is the whole adapter:

```
javap -p -cp out App
  public class App implements am.ik.rontolisp.runtime.RontoHttpServer$Handler, java.lang.Runnable
  public am.ik.rontolisp.runtime.RontoHttpServer$Response handle(am.ik.rontolisp.runtime.RontoHttpServer$Request);
```

**Seventy lines of adapter, no rontolisp import.**
`.todo/529-.../RontoHttpServlet.java` compiles against the servlet API and the
emitted runtime classes and nothing else. It is one `service` override plus a
ten-field `Request` fill.

**And the war needs no `web.xml` and no configuration at all.** A
`ServletContainerInitializer` annotated
`@HandlesTypes(RontoHttpServer.Handler.class)` is handed the program class by
the container -- because implementing `Handler` is already what the JVM backend
emits -- and registers the servlet at `/*` programmatically. Nothing has to
carry the class name: not a `web.xml`, not an `<init-param>`, not a generated
subclass. The war's only non-class file is one 52-byte line:

```
WEB-INF/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer
  -> am.ik.rontolisp.runtime.RontoHttpServletInitializer
```

**The war is otherwise an ordinary war.** `WEB-INF/classes/App.class` plus the
runtime classes the `.class` output already writes beside the program
(`RontoHttpServer` + nested types, `RontoHttpClack`, `RontoClackEnv`,
`RontoHashTable` -- `JvmHttpHandlerRuntimeBuilder.RUNTIME_CLASS_FILES`) plus
the two adapter classes.

**What the spike verified served correctly**, each by hand against the running
container:

| | result |
| --- | --- |
| `:path-info`, `:request-method`, `:query-string` | correct |
| `:headers` table (`(gethash "user-agent" headers)`) | correct |
| percent-decoding + UTF-8 (`/caf%C3%A9%20bar` -> `/café bar`) | correct |
| repeated response headers (two `:x-demo` pairs) | both emitted |
| `HEAD` | correct |
| `:raw-body :buffered` POST read with `read-sequence` | correct -- the Clack-critical path |
| `:raw-body :stream` + `async-defun` / `await` / `read-all` | correct on a Tomcat PLATFORM thread |
| an `(unsigned-byte 8)` response body | byte-exact (`ff fe 41` out as `ff fe 41`) |
| `--optimize` | compiles; `handle` is already a shaker root under `usesHttpHandler` |
| adapter compiled against Servlet 6.0, run on a 6.1 container | works |
| the SAME war on Tomcat 11 and on Jetty 12 EE10 | works, unmodified |
| `metadata-complete="true"` in a user `web.xml` | initializer still runs |
| `<absolute-ordering/>` in a user `web.xml` | initializer still runs |

The byte-exact row is free rather than lucky: `Response` already carries
`byte[]`, so the todo-341 Phase 3b invariant (`.kb/http-server.md`, "A binary
response body is byte-exact") holds on this transport by construction, and the
transport writes those bytes with no encode of its own -- the same shape
`writeResponse` has.

The async row is the one that could have gone the other way. `handle` dispatches
through `_invoke_1` + `_await`, and the JDK transport runs every request on a
virtual thread; Tomcat's default pool is platform threads. `await` blocks there
just as happily.

The last two rows are the ones that decide where the service file goes.
`metadata-complete` and `<absolute-ordering/>` are the two documented ways to
suppress web-fragment and annotation processing, and neither reaches an
initializer declared in `WEB-INF/classes` -- both target `WEB-INF/lib` jars. So
the class-directory placement is not merely convenient (it is what makes the
Maven route free, `.todo/533`); it is the more robust of the two placements.

## What is NOT free, and is what the children are

1. **The directive blocks.** `(rontolisp:http-handler 'h 8080)` compiles to
   `RontoHttpServer.serve(port, new Prog())`, which binds a port and never
   returns. In a war the container owns the port and the top level must return.
   `.todo/530`.
2. **`:script-name` is hard-coded `""`** in all three constructions
   (`http-server.lisp:428`, `RontoClackEnv.SCRIPT_NAME`'s contract,
   `LispEvaluator.buildClackEnv`, `RontoHttpClack.buildEnv`). Measured: a war
   deployed at `/myapp` answers `:path-info /myapp/hello`, so every ningle /
   tiny-routes / lack route misses. Every transport rontolisp has today is
   root-mounted, which is why nothing has ever noticed. `.todo/531`.
3. **`am.ik.rontolisp.runtime` imports nothing**, and a servlet adapter imports
   `jakarta.servlet`. That rule is load-bearing (`.kb/jvm-export.md`, "What
   travels") and the exception has to be designed rather than taken. `.todo/530`.
4. **Clack's `run` binds and joins.** A fourth leg, exactly the reactor leg's
   shape. `.todo/532`.
5. **The Maven route is nearly free and should not be an afterthought**:
   `rontolisp-maven-plugin` already compiles `src/main/lisp` into
   `${project.build.outputDirectory}`, which `maven-war-plugin` copies into
   `WEB-INF/classes` on its own -- and with the initializer there is no
   `web.xml` for the plugin to generate or for the user to wire up. `.todo/533`.

## Why a war output and not "write the servlet yourself"

The adapter is short enough that a user could write it -- and then every user
writes a slightly different one, each with its own answer to the context-path
question, the top-level-initialization question and the `Content-Length`
question. The transport is part of the value model (`.kb/http-server.md`: the
shape is declared once, the construction is native to each backend), so it
belongs where the other four transports live. `-o app.war` is also what makes
the claim testable: one E2E deploying a generated war into an embedded
container pins all of it at once.

## Scope boundaries

- **Servlet 6.0+ (`jakarta.*`) only.** No `javax.servlet`. 6.0 is the compile
  target (verified running on a 6.1 container), which covers Tomcat 10.1/11,
  Jetty 12 EE10/EE11 and every current EE server.
- **`provided` scope, explicitly sanctioned for this one dependency.** It is
  never packaged into the exec jar, the native binary or a `.jar` output;
  `resource-config.json` already globs `am/ik/rontolisp/runtime/.*\.class`, so
  the class travels into the native image as a resource without a new entry.
- **Out of scope**: async servlets (`startAsync`), WebSocket
  (`jakarta.websocket`; `clack.socket` is already out of scope per `.todo/223`),
  serving static resources from the war, and `javax`-era containers.
