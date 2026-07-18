# rontolisp:fetch timeout options (:connect-timeout / :first-byte-timeout)

`rontolisp:fetch` has no timeout control on any backend: a stalled or
unreachable server hangs the calling program indefinitely.
`eval/HttpSupport.java` contains no timeout code (the `HttpClient` is built
with defaults), `JvmFetchRuntimeBuilder` likewise, and on `--component`
`http.lisp`'s `%fetch-send` passes `nil` where `request-new` accepts a
`request-options` resource — even though the vendored `http.wit` already
declares the full `request-options` type (connect / first-byte /
between-bytes timeouts).

## Proposal

Two new keyword options on the existing `rontolisp:fetch` options plist,
both in milliseconds:

- `:connect-timeout` — TCP/TLS connection establishment deadline
- `:first-byte-timeout` — deadline for the response to start arriving

No new syntax, no new function. Expiry signals a catchable condition (the
same condition type fetch already signals for transport errors), identically
on every backend that has fetch.

`:between-bytes-timeout` (stall detection between body chunks) is a possible
third option later; it is deferred because the JDK `HttpClient` has no direct
equivalent (it would need a wrapper on the body-stream reads), while the
component side would get it for free from the host.

## Mechanism per backend

- **Interpreter**: `HttpSupport` applies `HttpClient.Builder.connectTimeout`
  and `HttpRequest.Builder.timeout` (the JDK request timeout fires if the
  response has not begun — the first-byte semantics). Wrap
  `HttpTimeoutException`/`HttpConnectTimeoutException` into the existing
  fetch condition.
- **JVM**: the same two calls in `JvmFetchRuntimeBuilder`'s request-building
  code.
- **`--component`**: in `http.lisp` `%fetch-send`, when either key is
  present, construct the `request-options` resource, call
  `set-connect-timeout` / `set-first-byte-timeout` (WIT `duration` is
  nanoseconds), and pass it as `request-new`'s options argument. The types
  already exist in the vendored WIT, so this is only adding those members to
  `HttpLibrary`'s reachable-member set — no new `.wit` file, no core Java on
  this path. The host enforces the deadline; the error arrives as the
  existing `error-code` variant and is already signaled as a condition.
- **WASM P1 / `--no-gc`**: fetch is unsupported there today; unchanged.

## Verification

- Deterministic timeout tests: a local server (interpreter/JVM: JDK
  `HttpServer` that sleeps past the deadline; component: existing
  Docker/wasmtime harness) asserting the condition type and that a generous
  timeout does NOT fire.
- Byte-identity: a program that never passes the new keys must compile
  byte-identically on the wasm backends (reference-gated members).
- Docs: fetch reference page (en+ja) gains the two options with a runnable
  example; `-Drontolisp.doc.fix=true` pass.
