# rontolisp:fetch timeouts: where they are refused, and their consumers

Difficulty: Medium

State after k05 (2026-11-19, `.kb/fetch-http.md`, "Timeouts"): `:connect-timeout` and
`:first-byte-timeout` (milliseconds) are in on the interpreter, the JVM, a component under
`wasmtime run` and a `--native` output, carried only by a program that names one, pinned
by `fetch-spec.yaml`; the Java subset's `HttpRequest.Builder.timeout` and
`HttpClient.Builder.connectTimeout` hand them to fetch. What is left is where a timeout
is refused by name, and two consumers.

## Where a timeout is refused

- **A component that serves** (`rontolisp:http-handler`, a Java `HttpServer`, a Clack
  application under `wasmtime serve`): its `wasi:http/types` members are fixed by
  `import-block-http-server.bin`, which declares no `request-options` constructor or
  setter, so a fetch with a timeout there signals at the call (`%fetch-untimed`). Making
  it work is adding the three members to `src/wasm-component/core-http-server.wat`,
  regenerating the block (`regen.sh`) and re-deriving the instance and type constants of
  `WasmServeComponentBuilder` from `wasm-tools dump`. That changes the bytes of EVERY
  served component, so measure what it costs first; a second block selected only by a
  timed program would keep the others identical.
- **The `--no-wasi --host-fetch` reactor**: refused at the call. The request record's two
  deadlines do not reach a host (`FetchResponseShape.hostRequestFields`). A JavaScript
  host can honour the first byte with an `AbortController` aborted by a timer that is
  cleared once the head is in (`AbortSignal.timeout` would also cut the body); it has
  nothing for the connection. `HostGlueEmitter` would write that, the checked-in
  `examples/cloudflare-workers/dog-fetcher/src/worker.js` follows, and
  `WasmHostFetchBodyE2eTest` pins it under node.
- **The browser playground**: the request fails at the await (`Target_HttpSupport`). The
  page's broker would abort the same way.

## Not made

- **A connect timeout that passes has no pin**: no local origin holds a connection open
  unanswered. Asked by hand of a blackholed address on four legs (k05's `Hang.java`). A
  listener whose backlog is full is one way to hold a connect on Linux; it has to be
  shown not to flake first.
- **`:between-bytes-timeout`**: wasi:http has it; the JDK client has no equivalent (a
  wrapper on the body reads), the runner would arm its `Deadline` per body read.
- **A program that builds the keyword at run time** and never spells it gets no timeout
  (the gate reads the program's symbols).

## Consumer: `rontolisp.http-client`

babashka.http-client's request option `:timeout` (milliseconds, or a `java.time.Duration`) is
`HttpRequest.Builder.timeout`, the first-byte deadline. Measured on clj 1.12.6 against an origin
holding its head 2 s: `:timeout 300` throws `java.net.http.HttpTimeoutException` `request timed
out` (under `:async`, an `ExecutionException` over it at the deref); `:timeout 5000` answers.
`:connect-timeout` is a client option there (`client`, refused here). The client refuses
`:timeout` by name (`%clojure-http-refuse-options` in `clojure.lisp`); with
`:first-byte-timeout` it passes the option through and throws the oracle's class and words
(the texts each transport gives are in `.kb/fetch-http.md`, "Timeouts"; the Java client's
`TimedWire.failure` maps them), a case in `clojure-http-spec.yaml` on the four legs --
which makes that corpus a timed program, so keep one program naming no timeout.
