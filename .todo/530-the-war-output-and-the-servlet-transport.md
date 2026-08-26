# 530. `-o app.war`: the war output and the Servlet transport

Difficulty: High (a new output format, a new emission mode in the JVM backend,
and the first sanctioned exception to `am.ik.rontolisp.runtime` importing
nothing. The bytecode work is small; the invariants it touches are not)

Child of `.todo/529`, which holds the spike, the measurements and the scope
boundaries. Spike files:
`.todo/529-a-servlet-war-output-for-http-handler-and-clack/`.

Blocks `.todo/532` (Clack needs the register arm) and `.todo/533` (the Maven
plugin needs the servlet class and the register arm). Independent of
`.todo/531`, which a root-context war does not need.

## Today

`-o app.war` reaches the JVM backend and then dies in `JvmArtifactOptions`
with a message about a class name:

```
$ java -jar $JAR app.lisp -o app.war
error: -o app.war does not name a class, so the class name has to be given: add --class-name com.example.Kernels
```

## What lands

### 1. The artifact: `cli/JvmWarWriter`

`JvmJarWriter`'s sibling, same rules -- one fixed `FIXED_TIME` stamp, entries in
a fixed order, so two compiles of one program are byte-identical
(`.kb/emitted-output-determinism.md`). Layout:

```
META-INF/MANIFEST.MF                              no Main-Class: nobody java -jars a war
WEB-INF/classes/App.class                         the program
WEB-INF/classes/am/ik/rontolisp/runtime/*.class   the travelling closure + the two adapter classes
WEB-INF/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer
```

**No `web.xml`, and no file naming the program class.** That last entry is the
only non-class file in the war, it is one line, and it is the same line in every
war rontolisp ever emits -- see section 2. `JvmWarWriter` therefore has no
templating to do at all: it is `JvmJarWriter` with a different entry prefix and
a different manifest.

No `Main-Class`: a war has no entry point. `Enable-Native-Access` stays --
inert when unused, and a `--blas`/`--gpu` war still wants it.

A user who wants their own `web.xml` (a filter, a security constraint, a
`<session-config>`) can add one to the war afterwards and the initializer keeps
working -- verified against both `metadata-complete="true"` and
`<absolute-ordering/>`, neither of which reaches an initializer declared in
`WEB-INF/classes`. Emitting one ourselves would take that away for no gain.

CLI plumbing that follows:

- `RontoLispCli.jvmOutput()` accepts `.war`, so every flag that reaches the JVM
  backend reaches it (`--optimize`, `--simd`, `--blas`, `--gpu`, `--parallel`).
- `JvmArtifactOptions.internalClassName` derives the class from the war stem the
  way `classNameFromJarPath` does for a jar -- `-o app.war` is `App`. Factor the
  one method to take the extension rather than copy it.
- `--maven-coordinates` / `--emit-pom`: a war IS a Maven artifact, so allow both
  and write `META-INF/maven/` exactly as the jar does. The message that today
  says "rides inside a jar's META-INF/maven" changes to name both.
- `--no-main` and `-o *.war` are mutually exclusive: a war never has a main, so
  the flag is either redundant or a mistake. Refuse it by name.
- A war whose program has NO `rontolisp:http-handler` and no
  `%http-server-start` is refused at compile time with the reason: there is
  nothing for the container to call.

### 2. The transport: `runtime/RontoHttpServlet` + `RontoHttpServletInitializer`

Two classes, both in the spike, both close to shippable.

**`RontoHttpServletInitializer implements ServletContainerInitializer`, annotated
`@HandlesTypes(RontoHttpServer.Handler.class)`.** The container hands `onStartup`
every class in the war that implements `Handler`, which is precisely what the
JVM backend emits for an `http-handler` program -- so the war carries no name,
no parameter and no generated code. It initializes the class, instantiates it,
and registers the servlet:

```java
context.addServlet("rontolisp", new RontoHttpServlet(handler))
       .addMapping("/*");
```

Details that are load-bearing rather than incidental:

- **`Class.forName(name, true, loader)` is what runs the top level.** A
  container loads `@HandlesTypes` candidates WITHOUT initializing them (it must
  -- initializing every scanned class would be a disaster), so the initializer
  has to ask. Instantiating would trigger it too; do it explicitly anyway, so
  the ordering is stated rather than inherited from a side effect.
- **Today's compiler only puts the top level in `<clinit>` when the program has
  a `rontolisp:jvm-export`** (`JvmLispCompiler`'s
  `topLevelInClinit = !exportDecls.isEmpty()`); war mode forces the same flag
  on. This is not theoretical: the spike war built WITHOUT it deploys, finds the
  class, and 500s on every request with
  `NullPointerException: Cannot load from object array` -- the handler slot was
  never filled because the top level lives in `main` and nothing called it.
- **Failure shape, for the docs**: `_top$run` surfaces a condition nobody caught
  as `ExceptionInInitializerError`, which poisons the class permanently -- in a
  container that is a permanently broken context, not a retryable 500. It is the
  reactor's documented failure shape (`.kb/jvm-export.md`), reached here through
  the container. Catch it in `onStartup` and rethrow as `ServletException` so it
  fails the DEPLOYMENT, which is where a broken program belongs.
- **Zero matches, or two.** Zero means a war with no `http-handler` -- refused at
  compile time (below), so the initializer's message is a backstop. Two means
  two programs in one war; fail by name rather than pick one. Filter interfaces
  and abstract classes out of the handed set: containers differ on whether the
  annotated interface itself appears.
- **No `load-on-startup` needed**: `onStartup` already ran the top level at
  deploy, which is the thing `load-on-startup` would have been for. Set it
  anyway for the servlet itself; it costs a line.

**`RontoHttpServlet`** takes the `Handler` through its constructor -- no
`init()`, no reflection, nothing to look up. One `service` override plus a
ten-field `Request` fill.

- **`destroy()` does nothing.** The war holds no port and no thread.
- **`getServletInfo()`** answers the rontolisp version, so a container's manager
  page says what is deployed.
- **One handler slot per webapp, not per process.** `_httpHandlerFn` is a static
  field on the program class and each webapp has its own class loader, so two
  rontolisp wars in one container do NOT collide -- strictly better than the
  "one Clack server per process" the shim documents for the JDK transport
  (`.kb/clack.md`). Say so; someone will ask.
- Both classes compile against `jakarta.servlet-api` 6.0.0 and were verified on
  a Servlet 6.1 container. Between them they touch `HttpServlet`,
  `HttpServletRequest`, `HttpServletResponse`, `ServletException`,
  `ServletContainerInitializer`, `ServletContext`, `ServletRegistration` and
  `@HandlesTypes`.

**Portability caveat worth one line in the docs**: an embedded Jetty needs
`AnnotationConfiguration` added to the context before it runs initializers at
all (a standalone Jetty distribution enables the `annotations` module for a
deployed webapp on its own). Tomcat needs nothing. That is a property of
embedding Jetty, not of this war -- but the war E2E will hit it.

### 3. The invariant this bends, and how

`am.ik.rontolisp.runtime` imports nothing -- not the project, not the build's
`@Nullable` -- because its class files are COPIED into someone else's artifact
and anything they imported would become that artifact's dependency
(`.kb/jvm-export.md`, "What travels"; CLAUDE.md's package rules). Both adapter
classes import `jakarta.servlet`.

The exception is real but it is narrow, and stating it precisely is most of the
work:

- The two classes travel on a THIRD list, `WAR_RUNTIME_CLASS_FILES`, reached
  only by a `.war` output. A `.class` or `.jar` compile never emits them, so no
  existing artifact gains a dependency.
- The dependency it does add is satisfied by definition: a war runs in a servlet
  container, and a container that has no `jakarta.servlet` is not a container.
  This is the same argument `RontoHttpServer`'s `com.sun.net.httpserver` import
  already makes about the JDK, one module further out.
- `jakarta.servlet-api` enters the root pom in `provided` scope: never in the
  exec jar, never in a `.class`/`.jar` output, never in the native image.
- `resource-config.json` already globs `am/ik/rontolisp/runtime/.*\.class`, so
  the classes travel into the native binary as resources with no new entry --
  verify, do not assume. The service file is emitted as one constant string and
  needs no resource at all.

Tests that must move with it:

- `JvmRuntimeClassFilesTest` -- the union of the travelling lists gains a third
  member.
- `JvmHttpHandlerTravellingRuntimeTest` recomputes the closure of the emitted
  class and asserts it is self-contained on a bare `java -cp .`. The war closure
  is self-contained GIVEN a container; the test needs a war-mode arm that admits
  `jakarta/servlet/**` as provided, and it must keep failing for any other new
  outside reference.
- `PackageCycleTest` and whatever pins the package graph in CLAUDE.md.

### 4. The register arm: the directive must not block

`(rontolisp:http-handler 'name port)` compiles to
`RontoHttpServer.serve(port, new Prog())`, which binds and never returns
(`JvmHttpHandlerCompiler`). In war mode it must instead store the funcref and
RETURN, so `<clinit>` completes and the servlet gets its handler.

The mode is a reader feature, exactly the reactor's precedent
(`Features.WASM_REACTOR` / `:rontolisp-reactor`, selected in
`RontoLispCli.compileRecorded`): add `Features.JVM_SERVLET` =
`("rontolisp" "rontolisp-jvm" "unicode" "thread-support" "rontolisp-servlet")`,
selected when the output ends in `.war`. `.todo/532` is the reason it has to be
a FEATURE and not an internal compiler flag -- the Clack shim branches on
features and on nothing else.

The port argument is accepted and ignored (the container owns the port); warn
once on stderr if one was written, the way an ignored flag should behave.
`:raw-body` stays a compile-time constant and is unaffected.

`--optimize`: `handle` is already a root under `usesHttpHandler` and `<clinit>`
is an implicit one, so the shaker needs nothing new -- but `main` stops being a
root in war mode, so confirm the top-level chunks stay reachable through
`_top$run`.

### 5. The `%http-server-start` seam under war mode

`clack-handler-rontolisp` reaches `RontoHttpServer.startServer` through it
(`JvmHttpServerSeamCompiler`). `.todo/532` gives Clack its own leg, but decide
here what the seam itself does when someone calls it directly in a war compile:
either the same register-and-return (with `join` returning at once and `stop` a
no-op), or a refusal by name. Refusing is more honest -- a war cannot own a
port -- but the register spelling is what makes `.todo/532` a two-line shim
change. Pick one and write down why.

## Acceptance

- `java -jar $JAR examples/net/http-handler.lisp -o app.war` produces a war that
  deploys unmodified, with no configuration, on Tomcat AND on Jetty.
- An E2E (`WarE2eTest`, opt-in like the other served suites) compiles a war and
  deploys it into an embedded container, `test` scope. It must cover the rows
  the spike covered by hand -- `.todo/529`'s table -- with the octet-body row
  asserting RAW bytes, because the text spelling passes on a double-encode
  (`.kb/http-server.md`). Run it on BOTH containers if the second one is cheap:
  initializer discovery is the one thing here that is a container behavior
  rather than a spec guarantee, and it is exactly what a single-container test
  would stop noticing.
- A war built WITHOUT the `<clinit>` move must fail the E2E loudly, not subtly.
  Assert the deployment fails rather than that requests 500 -- that is what
  rethrowing from `onStartup` buys.
- Two compiles of one program produce byte-identical wars.
- `doc/{en,ja}/guides/http-handler.md` gains the war section, mirrored, with the
  `ExceptionInInitializerError` failure shape, the one-slot-per-webapp note and
  the "you may add your own `web.xml`" note. `.kb/http-server.md` gains the
  fifth transport; `.kb/jvm-export.md`'s "What travels" table gains the third
  row.
