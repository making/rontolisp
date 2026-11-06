# i96. `ClackE2eTest`'s tiny-routes legs fail on develop

Difficulty: Medium

`RONTOLISP_CLACK_E2E=1 ./mvnw test -Dtest=ClackE2eTest` (opt-in, network for the first quickload):
the bare-handler `clackup*` legs and the tiny-routes component leg pass; the tiny-routes interpreter,
JVM and war legs fail. Measured 2026-11-06 on `31217b571` (before i82) and after it, identically, so
neither the feature-push change nor how the cached libraries read is the cause. Two symptoms:

- **Interpreter** (`tinyRoutesRoundTripOnTheInterpreter`): every route but the POST `/echo` answers 500,
  `HTTP-HANDLER: handler failed: ... STREAM-ELEMENT-TYPE: The value NIL is not of type STREAM`. The
  request `rontolisp:fetch` sends for a GET carries `connection: Upgrade, HTTP2-Settings`,
  `upgrade: h2c` and `content-length: 0` (the JDK client's h2c upgrade; `HttpSupport.requestAsync`
  builds `HttpClient.newHttpClient()`, and the JVM runtime's `RontoFetch` builds its client the same
  way), so the Clack env holds `:content-length 0` beside `:raw-body nil` (`%http-body-stream` answers
  nil for an empty body). tiny-routes' `wrap-request-body` (`src/middleware/request-body.lisp`) reads
  the raw body whenever `content-length` is non-nil. Two halves to decide: whether fetch should send
  that upgrade at all, and whether `:raw-body` must be a stream whenever the request has a
  Content-Length (the Clack spec types it as a stream, and wookie's, toot's and hunchentoot's handlers
  always hand one; the shim's nil is an allocation saving, `.kb/clack.md`).
- **JVM** (`tinyRoutesRoundTripOnJvm`) and **war** (`tinyRoutesServesFromAWarOnTomcat`): the compile
  fails, `JvmLispCompiler: the generated class calls own methods it does not declare:
  [RONTOLISP::%HTTP-BODY-STREAM ... (called from handle), RONTOLISP::%HTTP-NORMALIZE-RESPONSE ...
  (called from handle)]` -- the emitted handler (`JvmHttpHandlerRuntimeBuilder`) calls two
  `http-server.lisp` defuns this program does not keep, while the bare-handler program keeps them
  (`HttpServerLibrary.process` returns the program unchanged when `usesServer` is false or a
  `%http-serve-request` defun is present; the defun pruner is the other suspect).

Reproduction without Maven: the program is `ClackE2eTest.TINY_ROUTES_APP` plus the
`tinyRoutesExercise` fetches; on the interpreter it prints `500` / `"Internal Server Error"` for
`/hello`, and `-o X.class` reproduces the compile error.

Done when: `ClackE2eTest` passes whole with `RONTOLISP_CLACK_E2E=1`.
