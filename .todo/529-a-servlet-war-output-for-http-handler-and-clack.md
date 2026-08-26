# 529. A Servlet war output for `rontolisp:http-handler` and Clack (parent)

Difficulty: High (parent item; each child is sized on its own)

Children: `.todo/530` (the war output and the servlet transport) -- DONE
(2026-08-26: `-o app.war`, `Features.JVM_SERVLET`, the two `runtime` servlet
classes on `WAR_RUNTIME_CLASS_FILES`, the `%http-server-start` seam registers
and returns, `WarE2eTest` on Tomcat AND Jetty; `.kb/http-server.md`, "The
fifth transport"). `.todo/531` (`:script-name` is fixed empty, so a war under
a context path misroutes) -- DONE (2026-08-26: the raw tuple's eleventh
member, the split in all three constructions -- raw prefix off BEFORE
decoding, both halves decoded, non-prefix degrades -- the servlet's
`getContextPath() + getServletPath()`, the reactor envelope's optional
`"script-name"` key, ci-spec `http-clack-script-name` on all four backends,
`WarE2eTest`'s context-path leg; `.kb/http-server.md`, "The environment
contract"). `.todo/532` (Clack on the servlet transport) -- DONE (2026-08-26:
the shim's fourth `run` leg, `#+rontolisp-servlet`; the initializer's bounded
wait for a registration clackup's default `:use-thread t` puts on a spawned
thread the class-init lock holds back, and the VOLATILE handler slot that makes
that wait read a published value; war legs on both application shapes in
`ClackE2eTest` plus `NingleE2eTest`, over the shared `EmbeddedServletContainer`;
`.kb/clack.md`'s transport list is four bullets now). `.todo/533` (the
Maven plugin builds a war): OPEN.

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
| `startAsync` + one virtual thread per request | works; see below |
| a handler that signals | 500 in 8 ms, no hang |

The byte-exact row is free rather than lucky: `Response` already carries
`byte[]`, so the todo-341 Phase 3b invariant (`.kb/http-server.md`, "A binary
response body is byte-exact") holds on this transport by construction, and the
transport writes those bytes with no encode of its own -- the same shape
`writeResponse` has.

The async row is the one that could have gone the other way. `handle` dispatches
through `_invoke_1` + `_await`, and the JDK transport runs every request on a
virtual thread; Tomcat's default pool is platform threads. `await` blocks there
just as happily.

`metadata-complete` and `<absolute-ordering/>` are the two documented ways to
suppress web-fragment and annotation processing, and neither reaches an
initializer declared in `WEB-INF/classes` -- both target `WEB-INF/lib` jars. So
the class-directory placement is not merely convenient (it is what makes the
Maven route free, `.todo/533`); it is the more robust of the two placements.

## Servlet async is not an optimization here -- it is the invariant

The first spike ran `handle` on the container's own thread. That is wrong, and
the reason is written down: `.kb/concurrent-served-requests.md` opens with
**"`rontolisp:http-handler` / `serve` runs ONE VIRTUAL THREAD PER REQUEST on the
interpreter and the JVM"**, and the three bugs that file records -- special
bindings visible to another request, the stream table handing out a duplicate
handle, a lazy library load losing a name -- are all shared-state-across-requests
bugs. A container pool hands the SAME thread to request after request, and the
compiled class keeps per-thread state in ThreadLocals (`_condTl`, `_hcDepthTl`,
`_handoffTl` are static fields on the emitted class). Measured, sync mode, 20
requests: **5 distinct `http-nio-exec-*` threads, each reused 4 times.**

`startAsync` fixes it exactly, and cheaply: release the container thread, run
the whole existing blocking pipeline on a fresh virtual thread, complete the
`AsyncContext` when it returns. Measured, async mode, 20 requests: **20 distinct
`VirtualThread[#nn]`, each used once** -- the same shape `RontoHttpServer`
already gives the JDK transport with its `newVirtualThreadPerTaskExecutor`.

And it is what lets a war absorb concurrency the pool cannot. Tomcat connector
pinned to 4 threads, handler `(await (wait-for 300))`:

| | 16 concurrent | 64 concurrent |
| --- | --- | --- |
| sync (handler on the container thread) | 1.335 s | -- |
| async (`startAsync` + virtual thread) | **0.386 s** | **0.388 s** |

3.5x at 16, and FLAT from 16 to 64 while the pool stays at 4 -- which is the
actual claim: the container thread is genuinely released. 200 sequential
requests on the fast path cost 1.215 s async against 1.225 s sync, i.e. no
measurable uncontended penalty (that measurement is dominated by client
process startup and is a sanity check, not a throughput number; a real one is
`.todo/530`'s job).

There is no deeper win available beyond this, and it is worth saying why: a JVM
rontolisp future IS a bare `CompletableFuture` and `%async-run` runs the body on
a virtual thread (`.kb/async-await.md`), so awaiting parks a virtual thread
rather than suspending a continuation. Handing the servlet a
`CompletableFuture<Response>` instead of blocking one virtual thread would be a
prettier seam and would buy nothing -- the thread it saves is the cheap one. The
expensive thread is the container's, and `startAsync` is what releases it.

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
- **`startAsync` is IN, and is the default** -- see the section above; it is what
  keeps the one-virtual-thread-per-request invariant. It rides in `.todo/530`
  rather than a child of its own: it is forty lines of the same class and the
  same E2E, and shipping the synchronous shape first would mean knowingly
  shipping a transport that violates a documented invariant.
- **Out of scope**: non-blocking servlet IO (`ReadListener` / `WriteListener`).
  The body path is `readAllBytes` in and `write(byte[])` out; rewriting it
  around the non-blocking callbacks would be a large change to earn back
  blocking on a virtual thread, which is the cheap thread. Revisit only if a
  measurement says otherwise.
- **Out of scope**: WebSocket (`jakarta.websocket`; `clack.socket` is already
  out of scope per `.todo/223`), serving static resources from the war, and
  `javax`-era containers.
