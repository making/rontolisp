# TCP sockets (`rontolisp:tcp-*`), TLS (`rontolisp:tls-*`), the usocket shim and the cl+ssl shim

Handles are bidirectional stream VALUES in the file-stream handle space (`LispLayout.Kinds.SOCKET` /
`:SOCKET-SERVER`, `.kb/read-load-streams.md`). The print family to a socket is deliberately NOT
part of the surface anywhere; `read` does not work on sockets. Blocking and synchronous, except
that on `--component` a read inside an ASYNC body is a real suspension point. Errors:
interpreter/JVM and `--native` signal (one text), the WASM component returns `nil` -- but a read past
its deadline signals the interpreter's text everywhere ("Read deadlines"), and
`usocket:socket-connect` signals on all four. A plain Preview 1 `.wasm` has no sockets (call-time
error); a `--no-wasi` module has its host's under `--host-sockets` ("A reactor's sockets").

## Argument refusals (interpreter and JVM, one condition and text)
Every argument is checked, in argument order, before the host acts. A non-string host, path or
password, a port outside `(INTEGER 0 65535)` and a `tcp-set-timeout` count outside
`(OR NULL (INTEGER 0))` are the operator's `type-error` (a count past an int clamps on both; the
JVM's `l2i` would turn 3e9 negative). A listener's host may be `nil` (every interface) on both --
the JVM cannot tell it from an absent one. A handle naming no open entry of the operator's kind is
`OP expects a {listener|socket or listener|connected socket} handle`, a `simple-error` with no
datum (`%tls-listen-p12`'s report says `TLS-LISTEN-PEM`, the operator the inliner rewrote).

## Host failures (interpreter and JVM, one text)
A host exception (`IOException`; for TLS also `GeneralSecurityException`) is a `simple-error`:
`OP: cannot connect to H:P: MSG`, `OP: cannot listen on H|*:P: MSG`,
`tls-upgrade: cannot upgrade the stream to TLS against H: MSG`, or `OP: MSG`; MSG is the JDK's. JVM:
the handler frame records the caught exception as an `Object` -- `checkcast Throwable` before use.
Pinned by `HandleOperandFixture.HOST_FAILURE_PROGRAM`, whose refused connects go to PORT 0 (macOS
answers "Can't assign requested address", Linux "Connection refused": only the prefix is pinned).
**Trap**: never test a refusal by closing an ephemeral listener and reconnecting -- under the
parallel suite another test bound the freed port and the then-unbounded `tls-connect` handshake hung
`./mvnw test` for 8 hours. A bound-but-not-listening socket is no fix: macOS DROPS the SYN. The
`*:P` conflict is taken against an every-interface listener: macOS's SO_REUSEADDR lets `*:P` bind
beside a `127.0.0.1:P` listener (Linux refuses). An unknown host's text is DNS-dependent and
unpinned. Both groups pinned by `HandleOperandFixture` ([error-handling.md](error-handling.md)).

## Per-backend mechanics
- Handle allocation is CONCURRENT on interpreter and JVM -- invariant and the
  crossed-PostgreSQL-handshake failure in `.kb/read-load-streams.md`.
- Interpreter `eval/SocketSupport.java` is the web substitution seam (`Target_SocketSupport` signals).
- JVM: `JvmIoRuntimeBuilder` grows `instanceof Socket` branches ONLY when `usesTcp`, so other
  programs keep byte-identical stream bodies. **Trap**: the `_writeString` arm is NOT in
  `_writeStr`, the sink shared with the print family -- a branch one level down would ship a program
  that works on two backends and traps on `--component`. Same reason the interpreter's arm sits in
  the `write-string` built-in, not `emitTo`.
- WASM: a plain Preview 1 `.wasm` lowers each tcp call site to a CALL-TIME error so a spliced
  library's dead socket layer still compiles. Whether a provider (`eval/sockets.lisp` on
  `--component`, `eval/runner-sockets.lisp` on `--native`) is spliced is keyed on the `%io-read-line`
  marker, NOT the function table: a native taken as a value registers a wrapper under its own name
  whose body is the call site, so a table test looped `#'rontolisp:tcp-connect` on Preview 1.
- **Every connected socket BUFFERS its writes, on all four backends**: 8 KiB; flushed before every
  wait on ANY socket (a read its input buffer cannot answer, `listen`, accept, connect), by
  `force-output`/`finish-output`, by `close` (a failed send does not stop the close), when full, and
  on every way out of the program -- `uiop:quit` included except on the component.
  **Trap (threads, interpreter/JVM)**: a thread that writes and then waits on something that is no
  socket (a join, a condition, `sleep`) leaves its bytes buffered until any thread next waits on a
  socket -- as SBCL's buffered fd-streams; such a program owes a `force-output`.
  Pins: `TcpOutputBufferFixture` (it reads a line of a million octets too) on every socket backend (`LispEvaluatorTest#tcpEchoRoundTripOnLoopback`,
  `JvmLispCompilerTest#compileAndRunTcpEchoRoundTripOnLoopback`, `NativeTcpTest`,
  `WasmLispCompilerIntegrationTest#componentTcpBuffersWritesUntilTheProgramWaitsOnASocket`) and
  the Rust loopback `tcp::tests`.
- **Why**: cl-postgres writes one `write-byte` per byte; write-through, the rest of each message
  waited for the peer's delayed ACK under Nagle (40 queries: about 1,700 ms write-through, 5-100 ms
  buffered). `TCP_NODELAY` was not taken: with byte-wise writes it sends a segment per byte
  (usocket's `:nodelay` stays accepted-and-ignored; the component cannot set it).

## --native: the runner's sockets
The RUNNER owns the sockets (`rontolisp-native/runner/src/tcp.rs`, `std::net`, the `rltcp`
imports) and the module owns the dispatch layer: `runner-sockets.lisp` is a SECOND PROVIDER of the
`%io-*`/`%...-future` names sockets.lisp provides a component. Every call BLOCKS the module's one
thread; a failure leaves the OS's text (a read past its deadline is `Read timed out`, the JDK's
words).
- `wait` is `poll(2)` over sockets and an `rlhttp` server's bell, after every buffered write is sent
  (the bell's half pinned by `tcp::tests`'s `a_wait_answers_the_first_socket_that_has_something`).
  Its one consumer is the Java subset's scheduler ([java-frontend.md](java-frontend.md),
  "java.net.Socket and ServerSocket"); a program that names no Java socket does not import it.
- **Same names, same shapes, different blocking**: the `%...-future` twins are PLAIN defuns
  answering settled values (the degenerate Preview 1 `await` passes a non-future through), so
  `WasmSocketsRewrite`'s async promotion and sync dispatch both land on blocking code.
- **Errors SIGNAL with the interpreter's texts**; the port/host argument checks are messages, not
  `type-error`s. ONE signalling site, `rontolisp::%sock-fail`,
  whose NAME is an EH-mode trigger (`WasmLispCompiler.usesEhForm`, `LispNames.SOCK_FAIL_INTERNAL`)
  -- a Preview 1 `%error` outside EH mode is a bare `unreachable` carrying no text. Consequently the
  usocket shim's `%usock-guard` expands to its handler-case arm and re-signals typed
  `usocket:socket-error`; an uncaught failure prints `Unhandled condition: tcp-connect: cannot
  connect to ...` and exits 134.
- TLS stays off (the client pair keeps the Preview 1 call-time error; the runner's rustls is the
  fetch client's only).
- **`listen` asks the kernel**: bytes left in the chunk, else `FIONREAD` > 0 -- the JDK's
  `available()` question, consuming nothing, not even a pending error (a `peek` would: on Linux 6.8
  after a reset, `recv(MSG_PEEK)` answered `ECONNRESET` and the next read EOF). nil at the eof mark,
  at the peer's close and for a listener, as on the component. Why: dexador drains a
  `:want-stream t` body through `listen` before the connection returns to the pool; a chunk-only
  `listen` stopped at the chunk's end and left the pooled connection unusable (its retry hid it).
- A module importing `rltcp` starts with `rlrun-net` like one importing `rlhttp`.
- Pins: `NativeTcpTest` (the imports; the loopback programs of the `componentTcp*`/`componentUsocket*`
  tests with the error texts, then the `LineStartFixture`, `UsocketWaitFixture` and
  `TcpOutputBufferFixture` programs -- skips without the host pair),
  `NativeFetchTest#theOutputStartsWithTheRunnerItsModuleNeeds` (Linux), the Rust `tcp::tests`
  (`build.sh --test`), `StdinLibraryTest#theThreeDispatchSplicesDefineTheSameNamesAndShapes`.
  Not run anywhere automatically: cl-postgres over the shim from an executable (`ClPostgresE2eTest`'s
  one native leg is the Java program, [jdbc-postgresql.md](jdbc-postgresql.md)).

## A reactor's sockets (`--no-wasi --host-sockets`)
**Invariant: a `--no-wasi` core module built `--host-sockets` runs the `--native` provider
(`runner-sockets.lisp`, read with `Features.HOST_SOCKETS`) over a second host, the `tcp.*` imports
(`compiler/HostSockets`: fields, shapes, which suspend), which `--emit-js-glue` answers over
`connect()` of `cloudflare:sockets` (`hostSockets(connect, owner)`); the dispatch defuns are the
one source, and the runner's variant reads form for form as before.** Without the flag a
`--no-wasi` tcp site is the call-time error, its text naming the flag.

- **What differs from the runner's host, all in `#+rontolisp-host-sockets` forms**: no bind, listen
  or accept (refused in the module, `.. the host of a --no-wasi module only connects
  (--host-sockets)`); `tcp-connect` is socket + `begin` + wait + `end`; **every wait is one import**,
  `tcp.wait(hosts, millis)`, which a read with nothing queued (`%sock-fill`, for the entry's
  deadline: `OP: Read timed out`) and a connect wait in; `read(h, max)` only takes what is queued,
  and answers nothing both at the peer's close and at a failure, `lastError` empty for the close;
  `poll(hosts)` is the wait with no wait, synchronous.
- **Only `wait`, `flush`, `close` suspend** (JSPI). So every export is promising
  (`WasmLispCompiler.hostSocketImports` joins `maySuspend`, and widens to any export as a canonical
  import does: a stream's read is a front end's dispatch), the re-entry guard is on, `--reentrant`
  is refused, and a socket waited for while the module loads traps -- unless `--host-timer`, whose
  `_initialize` is promising.
- **What a JavaScript host has for the module comes only while the module is suspended.** A poll
  finds what earlier suspensions surfaced; `%sock-host-wait` turns every 256th empty zero-wait into
  a real `wait(.., 0)` (a `setTimeout` turn) so a thread that only spins cannot starve one waiting
  for a socket. `usocket:wait-for-input`'s poll sleeps, which a reactor refuses without
  `--host-timer`.
- **The host keeps ONE read ahead** (`pump`): a chunk waits in the glue until the module took it,
  so an unread peer is not read into JS memory. A chunk larger than `max` is handed out in pieces:
  the generic `:bytes` reader keeps ONE remainder for the whole import and drops it when the
  arguments change, which two sockets would lose data through.
- **Trap (workerd, measured under `wrangler dev` 4.168.0): no pending promise may outlive its
  request.** A bell promise kept between calls and resolved by the next request's code was
  cancelled (`A promise was resolved or rejected from a different request context ..`), the second
  request a 500. A wait's promise is made in the call and dropped with it (`waiters`), and a
  socket that is over wakes nobody.
- **Trap (workerd): `socket.close()` of a socket still connecting does not settle** until the
  connect ends: awaited, a connect past its limit hung the call and every call queued behind it.
  The platform's close is not awaited; what was written is (`sent`).
- **A socket ends with its request under `worker()`** (the platform's rule; decided: the program
  sees it by name, at its first use): `sockets.expire("Socket closed: the request that opened it
  has ended")` in the call's `finally`. Under `--host-timer`, where calls take turns, a socket is
  the call's that was in the module when it was made (`owner`, `lisp.context`) and ends with that
  one; one made while the module loaded ends with the first call. A host driving `instantiate`
  with `defaultHost(hostSockets(connect))` expires nothing.
- **The module's clock is written again after every suspension** (the glue's `bind`, every marked
  import): a deadline is `%nano-time`'s, and frozen within a call a read past its deadline waited
  for ever.
- **What workerd answers** (same measurement): a refused connect and a name not found are both
  `proxy request failed, cannot connect to the specified address` (so `Sockets.failure` makes
  "cannot connect" a `ConnectException`); `SocketInfo` gives no local address (`getLocalPort` 0);
  `allowHalfOpen: true` keeps the output open at the peer's end and `writer.close()` is the
  half-close.
- **TLS: `tls-upgrade` is the primitive** (`tcp.upgrade(h, name)`, begun like a connect: waited for,
  then `end`), `tls-connect` = the host connect + the upgrade under the 10 s bound, as on the
  component. The entry is swapped IN PLACE (the same fd answered, the component's convention; the
  interpreter/JVM answer a new handle). Unread input in the clear is dropped (the interpreter's
  `detach`). `:insecure` non-nil signals by name (no knob). Failures signal the interpreter's texts
  with the platform's words. The provider's tls defuns are `#+rontolisp-host-sockets`, and the
  splice fires on a tls name alone only for this host; `WasmExprCompiler` lets the tls call through
  on `noWasi && !reactorComponent && socketsSpliced`.
- **Every connect is `secureTransport: "starttls"`**: the module cannot know at a connect whether
  it will be upgraded, and `startTls()` of an `"off"` socket throws (`TypeError: The
  `secureTransport` socket option must be set to 'starttls' ..`). Measured 2026-11-26 under `wrangler
  dev` 4.168.0: a `"starttls"` socket used in the clear echoes and half-closes as an `"off"` one.
- **Trap (workerd): `startTls()` needs both stream locks released**, else it answers a socket whose
  `opened` never settles (an uncaught `.. is currently locked to a writer`); **`releaseLock()` with a
  read in flight throws** (`Cannot call releaseLock() on a reader with outstanding read promises.`),
  and the pump always has one in flight after a reply. So the upgrade cancels that read
  (`reader.cancel()`; the read rejects `Stream was cancelled.`) -- measured: the connection stays, and
  the handshake and an HTTP exchange over `startTls()` then succeed (example.com, badssl.com). A
  pump callback whose reader is no longer the entry's is ignored.
- **What workerd answers for TLS** (same measurement): `expectedServerHostname` IS what the
  certificate is verified against (and the SNI) although workerd logs `NOSENTRY startTls called with
  unsupported expectedServerHostname option` once per process. A failed handshake (untrusted issuer,
  wrong name) rejects the new socket's `opened`, reads and writes with `internal error; reference =
  <id>`, the reason only in wrangler's log (`TLS handshake failed: invalid peer certificate: ..`);
  `secureTransport: "on"` fails the same certificates as a refused connect, `proxy request failed,
  cannot connect to the specified address` (why the upgrade, not `"on"`, is the primitive).
  `startTls()` before `opened` works; a second throws. The generated glue under `wrangler dev`:
  `tls-connect` and `tcp-connect` + `tls-upgrade` to example.com:443 answer, a wrong name and a
  self-signed certificate signal.
- Sizes (2026-11-26): the Lisp connect-and-read reactor +334 B (`%sock-host-connect`, shared by both
  connects), `hello-jdbc`'s module at `=size` 1,032,444 -> 1,032,874; the glue +1.7 KB; a program
  without sockets, Preview 1 and component socket programs byte-identical.
- **Measured** 2026-11-25 (node 24.22.3 over `node:net`, load 15-30; a Java handler): a MiB through
  an 8 KiB array 11-20 ms (16-17 suspensions, node's 64 KiB chunks), 2,000 round trips of 100 bytes
  230-330 ms (two suspensions a trip), connect + line + reply + close 1.0-2.4 ms (a JDK 2.1-2.3).
  Under `wrangler dev`: a MiB 177 ms. Sizes, default `--optimize` raw / `gzip -9`: a Java server
  with twelve socket routes 752,064 / 200,143 -> 778,793 / 208,832 with the flag (`=size` 598,970
  -> 615,992); a Lisp connect-and-read 22,059 -> 58,448; `hello-servlet` built with the flag and no
  socket is the same 763,973 B. A program without sockets, and every non-Java socket program on the
  other targets, is byte-identical; a Java socket program gained `Sockets.failure`'s "cannot
  connect" (class +205 B, Preview 1 +102, component +68).
- Pins: `StdinLibraryTest#theThreeDispatchSplicesDefineTheSameNamesAndShapes` (the dispatch shapes
  and the imports against `HostSockets.FIELDS`),
  `HostGlueEmitterTest#theHostsSocketsAreWrittenOverConnectAndEndWithTheirRequest`,
  `WasmHostGlueE2eTest#aModulesSocketsAreItsHostsConnect` (Lisp: tcp-*, usocket, a deadline, the
  refusals, an expired socket, a STARTTLS upgrade, `tls-connect`, a wrong name, `:insecure`; node,
  `TcpLinePeer.NODE_CONNECT` over `node:tls`, whose readable lets go of the connection on a cancel as
  workerd's does, trusting `RONTOLISP_TEST_CA`), `FetchSpecE2eTest#javaHttpServer`'s
  `peers` (every leg and the JDK; the reactor's two deviations), `ClPostgresE2eTest#aJdbcProgramAHostCallsReachesTheServerThroughItsHostsConnect`
  (the driver, with and without `--host-timer`), `RontoLispCliTest` (the flag's refusals). By hand
  under `wrangler dev`: `examples/cloudflare-workers/hello-jdbc/`.

## TLS
Clients run on interpreter, JVM and the WASM component; the listeners are interpreter/JVM only,
permanently. On interpreter/JVM everything leans on `SSLSocket`/`SSLServerSocket` being
`Socket`/`ServerSocket` subclasses. Hence no `tls-accept`: plain `tcp-accept` accepts on a TLS
listener and the accepted `SSLSocket` handshakes lazily on first I/O.

- FRESH `SSLContext` per call, never the cached `SSLSocketFactory.getDefault()`, so
  `javax.net.ssl.trustStore` is re-read per connection. Endpoint identification `"HTTPS"` is set
  before `startHandshake()` -- the JDK does NOT verify hostnames by default.
- `tls-upgrade` answers a NEW stream-table entry (closing it closes both); the component and a
  reactor's host sockets answer the same one.
- **Handshake deadline**: the socket's `SO_TIMEOUT`. `tls-upgrade` uses whatever the caller set
  (none = unbounded). `tls-connect` sets `LispNames.TLS_CONNECT_HANDSHAKE_TIMEOUT_MS` (10 s, Go's
  `TLSHandshakeTimeout` and Netty's default) -- the caller has no handle to set one on first --
  clears it after, and on any failure CLOSES the socket before signalling (a timed-out `SSLSocket`
  stays open otherwise). A custom bound is `tcp-connect` + `tcp-set-timeout` + `tls-upgrade`. Pinned
  through `tls-upgrade` with a 50 ms deadline in `HOST_FAILURE_PROGRAM`; the 10 s default is not
  timed by any test. Component: the same bounds by racing `connector.connect` against a `wait-for`
  timer; losing answers `nil` and leaves the handshake subtask pending host-side (no
  `subtask.cancel` is bound) until the caller's close.
- `:insecure` non-nil skips BOTH chain validation and endpoint identification; the keyword must be
  LITERAL. On the JVM the GENERATED PROGRAM CLASS implements `X509TrustManager` (the JDK cannot take
  an anonymous TrustManager from hand-assembled bytecode), and its three methods are extra
  `--optimize` roots -- JSSE reaches them invisibly to the tree-shaker.
- `tls-listen-pem` parses at PARSE time (`TlsPemInliner`, JVM branch only, LITERAL paths onto
  `%tls-listen-p12`); non-literal paths are a compile error.
- `TlsTestSupport` runs the peer on a background thread: a handshake needs a live peer, so the
  plain-TCP backlog trick does not apply.

### WASM TLS (component only)
`tls.lisp` (`eval/TlsLibrary`) over a wit-imported `wasi:tls@0.3.0-draft` (`eval/tls.wit`, vendored
from wasmtime v47.0.2). Run with `-S tls=y` on top of the tcp flags. The SERVER half is a compile
error on EVERY WASM target PERMANENTLY (the proposal defines only `client.wit`). On Preview 1,
`--native` included, the client pair is a CALL-time error, not a compile error, because a spliced
library carries live-but-unreached sites: dexador's `request` reaches the cl+ssl shim's
`tls-upgrade` on the https branch, and an `http://` program must still build (pinned by
`RontolispNativeValueFixture.NO_SOCKET_PROGRAM`).

- The UPGRADE is the primitive: `connector.receive` takes the socket's in-flight recv stream;
  `connector.send` answers the ciphertext stream, which goes STRAIGHT into `tcp-socket.send` -- no
  guest-side pump. `tls-connect` = `tcp-connect` + `tls-upgrade`.
- That is why sockets.lisp DEFERS the send half (`%sock-ensure-tx` calls the at-most-once
  `tcp-socket.send` on the FIRST write): `tls-upgrade` must interpose before the send side is
  committed, so it refuses a handle already written to or with bytes still buffered.
- The entry is swapped onto the cleartext ends IN PLACE, so here `tls-upgrade` answers the SAME fd
  where interpreter/JVM answer a NEW one.
- **A TLS entry's buffered writes are PACED**: after the handshake each piece goes out after a
  `(wait-for 1)`; while one task has a piece in flight, another flusher waits, so pieces keep buffer
  order. Real cl+ssl's buffer flushes at fewer points (force/finish-output, full, close), so a
  program correct against it is correct here.
- **Why: wasmtime-wasi-tls drops the tail of a write** (49.0.0 and 51.0.0, against 1.1.1.1 and
  httpbin.org). Unbuffered, more than about 128 writes before a read, or ONE write past 16 KiB, lost
  the end. Host cause (`p3/util/tokio_streams.rs`, `p3/util/pipe.rs`): the cleartext consumer
  reports a write `Completed` when tokio-rustls ACCEPTS the plaintext, even when writing the records
  into the 16 KiB ciphertext `duplex` answered `Pending` (pipe full, or tokio's 128-operation
  budget used up); the records wait in rustls for the next cleartext write. `(sleep 0)` and
  `(wait-for 0)` are no yield. So each piece must fit an empty pipe (8 KiB plus record overhead
  < 16 KiB) under a fresh budget; buffering alone still failed past 16 KiB.
- **Residual**: a peer slower than the 1 ms wait can still strand the last piece; not observed. The
  upstream fix is the consumer reporting a write complete only after `poll_flush` is `Ready`.
  **Trigger**: on every wasmtime floor bump re-run the opt-in `componentTlsFetchesARealHostOverHttps`
  with the buffer bypassed (after the handshake, `(rplaca (nthcdr 9 e) nil)` and the same at 12 on
  `%sock-entry`'s entry); when it passes, the wait and the buffer can go (keep the buffer only if
  fewer records are wanted -- byte-wise writes cost one record each).
- Errors nil; `connector.connect`'s err arm is an `own<error>` RESOURCE the handler releases.
  `:insecure` non-nil SIGNALS at RUN time (the draft has no verification knob). Verification uses
  the HOST's anchors, so `javax.net.ssl.trustStore` has no effect.
- **`TlsLibrary.process` must run BEFORE `SocketsLibrary.process`** in every chain (the trigger is
  TEXTUAL and tls.lisp references `rontolisp:tcp-connect` + `%sock`) and fires the sockets trigger
  itself; a program textually naming the client tls names needs `-S tls=y` even for dead code.
- `wasmtime serve` does NOT host wasi:tls (47.0.3, 49.0.0, 51.0.0, with or without `-S tls=y`):
  instantiation fails with "component imports instance `wasi:tls/types@0.3.0-draft`, but a matching
  implementation was not found in the linker". A served handler needing outbound https uses
  `rontolisp:fetch`; a served program cannot speak TLS on a socket of its own, so a `wasmtime serve`
  deployment reaches PostgreSQL in the clear or not at all.
- **Trigger**: the draft is experimental/non-semver -- on every wasmtime floor bump re-diff
  `eval/tls.wit` against `crates/wasi-tls/src/p3/wit/deps/tls/` and re-check for
  verification/client-cert/ALPN knobs or a server interface. Through 51.0.0 the three `.wit` files
  are the blobs of 47.0.3 (`client.wit` 8f282dbe, `types.wit` fc6c4b10, `world.wit` 599d4968).

## The component mechanics (sockets.lisp over wasi:sockets@0.3.0)
A tcp program is the BASE variant plus one appended user import (`appendUserImports`), like fetch,
so fetch+tcp and serve+tcp compose in one component.

- **serve+tcp under `wasmtime serve` needs `-S cli=y`** (plus `-S inherit-network=y`);
  without it instantiation reports "instance export `tcp-socket` has the wrong type", which reads
  like a missing host feature. Pinned by `httpHandlerConnectsTcpUnderWasmtimeServe` -- the
  compile-level `httpHandlerWithTcpCompilesInServeMode` cannot catch a runtime composition failure.
- **Serve top-level init**: a serve component never lifts `run`, so the `handle` wrapper runs
  `_start` once per instance. Pinned by `httpHandlerReadsATopLevelGlobalUnderWasmtimeServe`.
- **"Once per instance" is a HOST decision**: `wasmtime serve` and Spin do BOUNDED reuse
  (`--max-instance-reuse-count` 1 for WASIp2, 128 for p3; `--max-instance-concurrent-reuse-count` 16
  for p3), wasmCloud `wash dev` gives each request a FRESH instance. Rule: treat top-level side
  effects as idempotent-or-per-request and keep durable state in the store, not in a global. The
  per-instance cost is why serve mode pre-grows a smaller GC heap (`.kb/wasm-gc-heap-pregrow.md`).
- wasmCloud `wash dev` (2.5.2+) provides `wasi:sockets@0.3` (its "Host provides interfaces" log is a
  stale list naming 0.2). Loopback routes to a per-workload virtual network, so a host-side listener
  is refused (`nil`) and a nil sock reaching `(read-line nil)` is an uncatchable cast-failure TRAP;
  the listen host must be an explicit `"127.0.0.1"`. Install with
  `curl -fsSL https://wasmcloud.com/sh | bash` -- NOT by tag: the SEPARATE `wasmCloud/wash` repo is
  stuck at `wash-v2.0.0-rc.7`, which advertises `wasi:http@0.2.0` only and rejects a serve component
  (looks exactly like a rontolisp regression, is not).
- A failed connect, listen or accept keeps the host's `error-code` in `*sock-last-error*` (the Java
  subset's sockets put it in a JDK's words); the tcp-* surface still answers nil.
- **Run flags**: a component tcp program is asyncMode + EH mode. Unlike wasi:http, wasmtime always
  hosts wasi:sockets and gates it by permission: without the flags the component instantiates and
  socket calls return `nil` (`componentTcpWithoutNetworkFlagsReturnsNil`).

### Inside sockets.lisp
- **A connected socket BUFFERS its writes** (sockets.lisp, "the output buffer"): every socket-layer
  host wait flushes EVERY entry with buffered bytes, because a single-threaded program writes on one
  socket and then waits on its peer's end (the loopback echo choreography). A plain write of 8 KiB or
  more goes out as it is after the buffered bytes; a TLS entry's never does. NOT flushed by a wait
  outside the socket layer (`sleep`, `wait-for`, stdin, fetch) nor by `uiop:quit`/`%host-exit`.
  Flushing at the scheduler's own block point (`_sched_loop` before `waitable-set.wait`, the serve
  callback's WAIT return) would cover every wait; not done -- runtime-built WASM calling a Lisp
  defun, re-entered by the flush's own write.
- **Why: Nagle meets delayed ACK**: wasmtime-wasi sets no `TCP_NODELAY` and wasi:sockets has no
  option for it (`examples/db/shiori` on Linux: `/books` 0.47 s write-through, 0.025 s with delayed
  ACK off; macOS loopback has no delayed ACK, so the stall does not show there). Pin:
  `componentTcpBuffersWritesUntilTheProgramWaitsOnASocket` (`TcpOutputBufferFixture`).
- **Addresses**: an IPv4 literal (and `localhost`) needs no lookup permission; any other name goes
  to `wasi:sockets/ip-name-lookup@0.3.0` (wasmtime: `-S allow-ip-name-lookup=y`, else
  `permanent-resolver-failure`). The first IPv4 address in the resolver's order is taken (the JDK's
  choice with its default IPv4 preference); an IPv6-only name is `nil`. Every tcp component imports
  the interface; wasmtime always hosts it, other hosts unmeasured.
- ACCEPT is the ONE non-u8 stream lift (`accept-stream`; `validateAsyncAlias` admits
  resource-handle elements).
- **Async-lower param SPILL**: wasmtime spills async-lowered params past 4 flats
  (`MAX_FLAT_PARAMS_ASYNC`; sync cap 16) and `connect`'s self + flattened `ip-socket-address` is 14,
  so `FlatSig` carries `spilledParams`/`paramsAreaSize` (`WitCanonicalAbi.spillLayout`).
- **Two surfaces**: sync tcp defuns force their async internals with `%future-force`; ASYNC bodies
  get the await promotion from `WasmSocketsRewrite` (writes never promote); in SYNC context the same
  calls rewrite onto the `%io-*` dispatch defuns, which send a non-socket designator to the NATIVE
  built-ins (`%...-raw`), byte-identical to the unrewritten forms -- widening `%io-read-byte` itself
  broke a top-level file read at EOF. A wrong-arity call is left unrewritten so it errors under its
  public name.
- **The chunk buffer walks BYTES, never characters**: a char-based cursor collapsed a valid
  multi-byte sequence in binary data (a PostgreSQL BackendKeyData secret) into one char, shifting the
  rest of the stream. On the WRITE side `write-byte` of >= 128 must send one octet, not a UTF-8
  sequence -- a bug invisible in rontolisp-to-rontolisp loopback. Pinned by
  `componentTcpBinaryBytesAreWireTransparent`. Socket write-sequence elements are BYTES, except a
  STRING sequence, which goes out as its characters.
- **EVERY shape a socket-capable built-in accepts needs an entry in `WasmSocketsRewrite`.**
  Unrewritten, the call compiles to the NATIVE built-in whose `fd_read`/`fd_write` on a socket fd
  (>= 200) walks off the preview1 adapter's 64-slot fd table -- the `unknown handle index 0` trap,
  firing MID-MESSAGE (server logs `incomplete message from client`), so it reads like a dropped
  `wasi:io` resource. The exotic shapes are load-bearing because of pass ORDER:
  `GrayStreamsLibrary.process` runs BEFORE this rewrite and re-spells every stream call site with
  EVERY optional argument filled in. `(write-char c [s])`, bounded `write-string`
  (`lowerWriteStringBounds`) and `(terpri [s])` (in a progn so it keeps answering nil) are LOWERED
  inside the rewrite onto `write-string`: a lowering at `WasmExprCompiler` time runs AFTER this pass.
  An unrecognized shape is deliberately left UNREWRITTEN so the error names the built-in the program
  wrote.
- **Three alternative dispatch providers; widening one without the others breaks every program on
  the others**: sockets.lisp, `stdin-dispatch.lisp` (`StdinLibrary`, an async component reading
  stdin but never a socket) and `runner-sockets.lisp` (`--native`, and `--host-sockets`' reading of
  it). Exactly one is spliced and the
  rewrite picks its target by call SHAPE without knowing which it got. `./mvnw test` does NOT catch
  it; `StdinLibraryTest#theThreeDispatchSplicesDefineTheSameNamesAndShapes` compares the three
  name -> (required/optional) maps.
- **`terpri` on a socket** answers nil and puts one `0a` on the wire on all four; the JVM routes
  through `_writeString` (`JvmTerpriCompiler`, NOT `_writeStr`). Bounded `write-line`'s lowering
  (write-string + terpri) needs both halves.
- **`fresh-line` on a socket** runs over the line start each write keeps
  ([standard-output-redirect.md](standard-output-redirect.md), "A stream's line start"). The native
  `_fresh_line_stream` holds no row for a socket fd, so `#'fresh-line` applied to one there (a
  first-class use, out of contract) answers nil.
- **`write-string`/`write-char`/`read-char` on a socket are real on all four backends**, treating
  socket I/O as BYTES -- never through a `Reader`/decoder that would buffer ahead and swallow bytes a
  following `read-byte`/`read-line` owes the caller. Cross-backend pin: one program answering
  `(65 195 135 66 504 90 121)`. **Deviation (measured 2026-11-26)**: `read-char` takes the
  octets its lead's high bits count whatever they are -- `ff 7a 0a` reads U+FFFD then EOF on the
  interpreter/JVM, code 2073250 on the WASM targets (the loose character walk); SBCL and a file
  stream read U+FFFD, `z`, newline.
- **A bare `(read-char sock)` / `(read-byte sock)` at peer close signals `end-of-file`** (CL's default
  `eof-error-p` t) on both entries a bare shape reaches -- the promoted `%read-{char,byte}-future`
  and the sync `%io-read-{char,byte}`. The nil convention stays BELOW in `%sock-read-{char,byte}-f`,
  because the `-eof-future` forms need the raw nil to apply the CALLER's eof arguments. `read-line`
  answers nil at peer close.
- **A read is promoted to an await only where the provider's futures are async bodies**
  (`WasmSocketsRewrite.promotesReads`: `%read-line-future` is an `async-defun`, sockets.lisp and
  stdin-dispatch.lisp). The runner's (runner-sockets.lisp) are plain defuns, and an `await` of
  what is no future answers its primary alone ([async-await.md](async-await.md), "Multiple
  values"): at top level on `--native`, `(multiple-value-list (read-line s nil :eof))` was ONE
  value. There every context takes the `%io-*` dispatch.
- **The two rewrites MEET in an async body**: `(write-line (read-line s))` is
  `(%io-write-line (await (%read-line-future s)))`. `WasmAwaitNormalizer` must hoist that await out
  of a `%`-named head's argument; `WasmSocketsRewrite.strictDispatchMembers()` tells it these `%`
  heads are ORDINARY defuns -- **keep the two in step when adding a dispatch defun**.
- print-family to a socket is not wired on the component; `(format nil ...)` + `write-line` is the
  pattern.

## What has come, in one read
**Invariant: a read of several octets into an `(unsigned-byte 8)` vector costs a host read, not a
call a byte, on all four socket backends.** Two surfaces:
- **`rontolisp::%tcp-read-some socket octets start end wait`** (internal,
  `LispNames.TCP_READ_SOME_INTERNAL`): with `wait` at least one octet once one has come, then what
  else has; without, only what is at hand. Component and `--native` copy the chunk with `replace`
  (`%sock-take-chunk`, one `array.copy`), then take another chunk the host has at once (the
  component's `listen` probe, the runner's `FIONREAD`). Callers: the Java subset's `read` and
  `available()` ([java-frontend.md](java-frontend.md)).
- **`read-sequence` of an octet vector on a socket**: interpreter `SocketSupport.readFully`, JVM
  `_sockReadSeq`; component and `--native` `%sock-read-seq-f` a chunk range at a time. A packed
  buffer of another width still takes the element loop on every backend.
- **Measured** (a MiB through an 8 KiB array, ms, a byte a call -> this): interpreter 5,263 ->
  2,100 (the rest is the interpreted loop), JVM 1,970 -> 230, `--native` 506 -> 42, component
  1,121 -> 47; JDK 6. JDBC, 10,000 rows: 3,250 / 2,100 / 520 / 1,150 -> 105 / 26 / 16 / 18. An
  element loop instead of `replace` read the MiB in 76-80 ms on the WASM targets and was 3,881 B of
  code where `replace`'s call is 350.
- **cl-postgres gains nothing from it**: its text values are read by
  `cl-postgres-trivial-utf-8:read-utf-8-string`, one `read-byte` a byte; `read-sequence` is only its
  `read-bytes` (bytea, authentication). That is the read-byte path's cost ("A byte at a time").
- Pins: `TcpReadSomeFixture` (a byte, a read with no wait on a quiet socket, a waited read of three
  octets, a `read-sequence` of 20,000 octets across chunks ending short at the peer's close, the end)
  on every socket backend: `LispEvaluatorTest#tcpByteOpsOnSocket`,
  `JvmLispCompilerTest#compileAndRunTcpByteOpsOnSocket`,
  `WasmLispCompilerIntegrationTest#componentTcpSequenceOpsReachTheSocketDispatch`, `NativeTcpTest`;
  the Java reads by `FetchSpecE2eTest#javaSockets`.

## A socket made before its connect, half a connection, an accept's deadline
**Invariant: a socket exists before its connect -- bound first or not -- and its connect
takes a time limit and ends at a close from another thread, on all four socket backends.**
The consumer is the Java subset's `Socket` ([java-frontend.md](java-frontend.md),
"java.net.Socket and ServerSocket"); the public `tcp-connect` is unchanged. Internal primitives:
- **`(rontolisp::%tcp-socket host port)`**: bound for an integer `port` (a nil host every
  interface), not bound for a nil one; a failed bind `%tcp-socket: cannot bind to H|*:P: MSG` (a
  component: nil, the reason in `*sock-last-error*`).
- **`(rontolisp::%tcp-connect-socket socket host port millis)`**: connects in place within `millis`
  (nil: the host's limit), `tcp-connect: cannot connect to H:P: Connect timed out` at the limit.
  `--native`: a non-blocking connect over `socket2` (already linked through tokio: `rlrun-net`
  +16 KiB); a name connects to its first IPv4 address, as `InetSocketAddress` does. Component: the
  `connect` future races a `wait-for` timer and a future the entry's close settles. **A socket
  lent to its connect cannot be dropped**: on wasmtime 51 a `tcp-socket-drop` under a pending
  connect traps `cannot remove owned resource while borrowed`, and no `subtask.cancel` is bound, so
  a connect the limit or a close ended is left to run and `%sock-drop-after` drops the socket when
  it ends (for a dropped SYN on Linux, some two minutes later; program exit is not delayed).
- **`(rontolisp::%tcp-shutdown socket output)`**: the output after what is buffered (the peer reads
  its end) or the input (a read under way answers the end).
- **`(rontolisp::%tcp-option socket option value)`** (`LispNames.TCP_OPTION_INTERNAL`): a
  CONNECTED socket's option given to the host -- 0 nodelay, 1 keep-alive, 2 linger (seconds,
  -1 off), 3 OOB inline, 4 TOS -- or 5, a byte of urgent data after what is buffered. Integer
  codes so the JVM helper (`_tcpOption`) is two unboxes and one `RontoSockets.option` call.
  `--native`: `socket2::SockRef` over the stream (no new crate). Component: wasi:sockets@0.3.0
  has keep-alive (enabled, idle, interval, count), the buffer sizes and the hop limit only,
  so 1 is `set-keep-alive-enabled`, 0/2/3/4 answer nil unchanged (the decision: kept and
  answered back by the caller, where a refusal would break pgjdbc's `tcpNoDelay`), 5 signals
  `%tcp-option: a component's socket sends no urgent data` (sending it inline would put a
  byte into the stream the peer does not read).
- **`tcp-set-timeout` of a listener is its accept deadline** on all four, `tcp-accept: Accept timed
  out` past it. `--native`: the runner's listener polls before its accept (in the module it put
  `wait` into every accepting program, which `NativeTcpTest` pins out). A refused handle reads
  `TCP-SET-TIMEOUT expects a socket or listener handle`.
- **`--native` caps a count of milliseconds at 2^30 - 1** (`%sock-millis`): past 2^30 an integer is
  no fixnum and an `:s32` import argument traps ([wasm-import.md](wasm-import.md)).
- Pins: `java-socket-spec.yaml` on the four legs and the JDK, `UsocketWaitFixture` (an accept past a
  listener's deadline, every socket backend), the runner's `tcp::tests` (a bound socket connected by
  name, a connect held by a full queue, its close, a refused one, a half-close, an accept's deadline,
  each option read back through `socket2`, urgent data inline after a buffered byte).

## A byte at a time
**Invariant: a `read-byte` the socket's buffer can answer asks the host nothing, on all four socket
backends.** cl-postgres reads every text value and every message's framing one `read-byte` a byte.
- **Interpreter and JVM: `runtime/RontoSocketInput`**, the one class both read through: one host
  read when the 8 KiB buffer is empty, which first flushes every socket output buffer -- so a read the
  buffer answers does not flush, as on the component. Every read of the socket goes through it, so
  none swallows bytes another owes the caller.
- **Component and `--native`: `%sock-take-byte`**, the chunk declared
  `(simple-array (unsigned-byte 8) (*))` (a direct read, `.kb/declarations-type-checks.md`). On the
  component the sync `%io-read-byte`/`%io-read-byte-eof` take it before `%future-force`: two
  resumable calls a byte were most of the read. **`%sock-entry` remembers the last stream VALUE it
  resolved** (every `remhash` of the table forgets it): the table lookup and the designator walk were
  a third of a byte.
- On the component a quarter of what a byte still costs is the call site's `%check-stream-runtime`
  (the operand check every stream operator pays, not the socket layer's).
- **Measured** (ms, before -> after; interpreter / JVM / `--native` / component). 1.2 MB by a bare
  `(read-byte s nil :eof)` loop: 1,950 / 1,260 / 320 / 825 -> 480 / 28 / 90 / 84 (decoding the same
  octets from a vector: 700 / 59 / 48 / 46). cl-postgres, 10,000 rows: 3,530 / 1,670 / 420 / 1,040
  -> 1,900 / 350 / 190 / 185. Component steps: the sync arm alone 818 -> 310, the remembered entry
  and one walk 136, the declared chunk 84. A program without sockets is byte-identical.
- **A line is taken a RUN at a time** (`%sock-read-line-f`, component and `--native`): what the
  chunk holds up to the next `\n` (or its end) becomes ONE string of raw octets through
  `%str-from-octets` (`WasmStrByteCompiler`: a `$str_bytes`, the quotes and one `array.copy`, no
  UTF-8 check, so a sequence split across chunks stays as the wire has it), the runs are joined by
  pairwise passes once and then decoded by `%str-decode-raw`, and a trailing `\r` is held back
  until the next run shows it is not the one before the `\n`. It was one `%string-concat` per byte, quadratic. **Measured** 2026-11-25 (wasmtime 51,
  one loopback line, ms, before -> after; component / `--native`): 5,000 octets 14 / 11 -> 0 / 0,
  10,000 21 / 23 -> 0 / 0, 20,000 42 / 49 -> 0 / 1, 40,000 170 / 196 -> 2 / 1, 400,000 15,338 /
  18,869 -> 13 / 10, 4,000,000 -> 137 / 99. Lines with a `\r` at every chunk edge, an empty line,
  an invalid and a split multi-byte sequence and a missing final newline answer the same values
  before and after. A component with `read-line` is 5.6 KB (2.8%) larger (199,742 -> 205,361); one
  without it differs only in the numbering of its function ids. `%sock-read-char-f` assembles a
  character the old way (at most four octets); `%stdin-read-line-f` is untouched.
- Pins: `TcpReadSomeFixture` (a byte of three, then `listen` and a read with no wait answering the
  two the buffer holds and the host no longer does) on every socket backend; the byte-wise
  read-char/read-line pins above; `ClPostgresE2eTest`.
- **A line is decoded once whole** (`%str-decode-raw`, `_str_decode` in
  `WasmBulkRuntimeBuilder`'s block, shaken from a module with no site): a raw string whose octets
  are well-formed UTF-8 (the strict walk `_iv_utf8_str` validates with) is answered ITSELF; else a
  fresh string with each malformed sequence one U+FFFD, as many octets per replacement as the
  prelude's `%octets-to-string-replacing` takes -- the JDK decoder the interpreter's
  `SocketSupport.readLine` is. A `$str_bytes` holding malformed octets is not a string: the
  character walk read `ff` as a 4-octet lead, past the end (trap) or into the next character.
  Measured 2026-11-26 on the four backends, 14 lines (`c0af`, `e080bf`, `eda080`, `ed9fbf`,
  `f09f98`, `e28241`, `f4908080`, `f09f9880`, `80bf`, `f888808080`, `e180e18080`, `df`, `efbfbd`,
  `410d`, then `c3` and the close): identical. SBCL's `:replacement` differs on the overlong and
  out-of-range forms (`c0af`, `e080bf`, `f4908080`, `80bf` one U+FFFD each, `f888808080`
  `(32768 65533)`) and keeps the `\r`; the four follow the JDK, as `read-char.md` documents.
  Cost: the walk takes an ASCII octet in a few instructions (`asciiStep`; `_iv_utf8_str` keeps
  its bytes): a 4 MB line 11 ms (27 ms through the sequence arms); `read-line` of 4 MB, ms, 3
  runs, before -> after: component 77-92 -> 89-92, `--native` 74-120 -> 82-95. A component with
  `read-line` 201,145 -> 202,049 B, the `--native` core +922 B (the executable one 4 KiB page);
  a program without a socket `read-line` byte-identical on all three WASM outputs except at
  `--optimize=off` (+905 B, nothing shaken).
- Pins: `TcpOutputBufferFixture` (an invalid octet before a sequence split across two writes and
  before ASCII, a last line the peer's half-close ends, the end of input, each with its
  missing-newline-p, at top level) on every socket backend.

## Address accessors
For the usocket shim's `get-local-*`/`get-peer-*`. A wrong-kind entry is the refusal text on
interpreter/JVM; WASM component: REAL (the dotted quad formatted in Lisp), a wrong-kind handle or
host error yields `nil`. Preview 1: the call-time error.

## Read deadlines and the usocket timeout/wait decisions
`tcp-set-timeout` (nil clears) sets a per-socket READ deadline, and on a listener its ACCEPT
deadline ("A socket made before its connect"); `(setf (usocket:socket-option s :receive-timeout)
seconds)` rides it.

- Interpreter/JVM: `Socket.setSoTimeout`. A timed-out read throws `SocketTimeoutException` out of
  the ORDINARY stream built-in, so it surfaces as a catchable plain `error`, NOT
  `usocket:timeout-error`. The deadline lives on the raw socket, so it keeps governing a
  `tls-upgrade`d connection.
- WASM component (wasi:sockets@0.3.0 has no receive timeout): REAL. Only a read the host could not
  answer at once races a `wait-for` timer through `%future-race`
  ([async-await.md](async-await.md), "`%future-race`"). When the timer wins, the read is WITHDRAWN
  (`stream.cancel-read`): a chunk that landed meanwhile is still taken, an empty withdrawal signals
  `OP: Read timed out`. Nothing stays in flight, so the socket reads on afterwards, as after
  `SO_TIMEOUT`. The losing timer is left to fire (exit is not delayed); a fast stream under a long
  deadline holds one pending timer per blocked read until each expires. 0 clears; a bad count is a
  `simple-error`, a non-socket handle answers nil.
- **A read left in flight is a trap waiting for the close**: dropping a recv stream with a read in
  flight traps `cannot remove busy stream`, and dropping only the `tcp-socket` does NOT end the read
  (it waits on the peer). That is why the loser is withdrawn at once rather than kept for the next
  read, and why `%io-close` withdraws whatever another task still has in flight (a read, or an accept)
  after taking the entry out of the table and marking its end -- the withdrawal wakes that reader
  synchronously, and one that still found the socket open would start a fresh read on the stream
  being dropped.
- **`listen` on the component is a probe**: bytes left in the chunk, else a read started and, when
  the host has nothing, withdrawn at once (`%sock-probe-f`); a chunk that comes is buffered. It
  answers what the host has SURFACED: a peer's bytes still on their way, even from a socket of the
  same program, show only after the program next waits (right after a peer write in the same
  component the probe answered nil until a `sleep`). So a poll that waits between probes sees data
  and a tight spin never does. A listener answers nil.
- `usocket:socket-option` supports `:receive-timeout` ONLY; every other option signals naming the
  option, never accept-and-ignore -- even `:tcp-nodelay` (though `socket-connect`'s `:nodelay` KEY is
  accepted-and-ignored, the shim's connect-knob convention). The refusal happens BEFORE any
  bookkeeping.
- `usocket:wait-for-input` is a `listen`-based poll in pure Lisp (every 10 ms), ONE shape on every
  socket backend. Racing the probes against the timeout was weighed and not built: the poll is the interpreter's own
  shape and granularity and needs no backend-only path in the shim. A socket at end of file is not
  ready (`listen` answers nil there), where upstream's `select` reports it; stream sockets only;
  wait-list objects are not reproduced. Pinned on all four by `UsocketWaitFixture` (the deadline,
  the quiet wait, `listen` before and after data and at the peer's close):
  `LispEvaluatorTest#usocketReceiveTimeoutAndWaitForInputAreReal`, its JVM twin,
  `WasmLispCompilerIntegrationTest#componentUsocketReceiveTimeoutAndWaitForInputAreReal`,
  `NativeTcpTest`.
- `listen` on a plain Preview 1 module is a CALL-time error for any stream but a string one: the shim
  is spliced UNPRUNED into every usocket program (`LibraryDefunPruner` excludes it), so
  wait-for-input's listen site is dead code that must build. Consequence: cl-postgres/postmodern/mito
  programs COMPILE on Preview 1 and fail loudly at the first socket call.
- The `WaitForLibrary` trigger fires on any usocket, tcp or client-tls reference (their libraries
  race a `wait-for` timer and splice later); non-CLI component pipelines must run
  `WaitForLibrary.process` in their chain, or a deadline compiles to a call-time
  `RONTOLISP:WAIT-FOR is undefined`.
- `socket-server` was split out rather than shipped keys-ignored: silently ignoring `:in-new-thread`
  blocks a caller expecting to continue.

## The usocket shim (`usocket` package, `usocket.lisp` + `UsocketLibrary`)
A usocket-compatible API over the tcp built-ins, targeting the surface cl-postgres uses.

- A socket IS its handle. `socket-listen` must OMIT the host argument of `tcp-listen` for the
  wildcard case. Every entry point takes `&allow-other-keys`.
- `usocket:*wildcard-host*`/`*auto-port*` are defparameters, so `LispEvaluator.evalSymbolRef` has a
  variable-read lazy-load hook besides the `resolveFunction` one. `boundp`/`symbol-value` are NOT
  hooked (lite edge).
- `get-host-by-name` is LITE -- it renders through `host-to-hostname` instead of resolving, because
  rontolisp has NO name-resolution primitive (the component's lookup lives inside sockets.lisp's
  connect/bind), and resolving on some backends only would be a backend divergence in a library
  SPLICED INTO EVERY socket program. Neither dexador nor drakma calls it. **Trigger**: the day a
  resolver primitive exists on all four, return upstream's real vector quad there.
- The four `with-*` macros are backend-parameterized expansions: no `unwind-protect` on WASM.
- Built-in ASDF system `"usocket"`: `LoadInliner.spliceSystem` splices the FORMS, not mark-only --
  an `:import-from :usocket` + bare-name consumer would evade the Walker, which runs before
  `PackageResolver`.
- Typed conditions: `%usock-guard` is expanded per backend (`LispMacroExpander.expandUsocketGuard`)
  because the source is parsed once and cached for all backends, so the branch cannot be a reader
  feature. The re-signal always uses `socket-error` (`.kb/error-handling.md`).
- The same three signal `socket-error` when their primitive answers nil, which only the component
  does (the interpreter's text without the host's reason). Without it, cl-postgres given an
  unresolvable host died as a bare `wasm trap: cast failure`, and dexador against a refused port
  reconnected until wasmtime's resource table was full. Pinned by `componentUsocketEchoOverLoopback`.
- Not reproduced: UDP, `socket-shutdown` (`%tcp-shutdown` exists; no consumer measured), wait-list
  objects, `socket-server`.

## The cl+ssl shim (`cl+ssl` package, `cl-ssl.lisp` + `ShimLibraries`)
Every CL HTTP client reaches TLS through cl+ssl, and the real cl+ssl is a CFFI binding to OpenSSL,
unloadable here. The shim is the CLIENT side only, over `tls-upgrade` (on Preview 1, `--native`
included, a call-time error). Its one consumer measured end to end is dexador
([dexador.md](dexador.md)).

- A context IS its recorded `:verify-mode`, so dexador's `dex:*not-verify-ssl*`/`:insecure` lands on
  `tls-upgrade`'s `:insecure`.
- `:hostname` is REQUIRED (verified against, and the SNI); nil signals rather than silently skipping
  verification.
- **`make-ssl-client-stream` answers a stream or signals, on every backend** (upstream's contract).
  The component's `tls-upgrade` answers nil for a failed handshake, so the shim signals
  `tls-upgrade: cannot upgrade the stream to TLS against H`; dexador took the nil for a stream and
  its retry loop reconnected until `call stack exhausted`. Pinned by
  `componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer`.
- What has no backing SIGNALS: client certificates (`:key`/`:certificate`/`:password`,
  `use-certificate-chain-file`) and `make-context :verify-location` CA paths (only `:default`/nil
  pass). **Triggers**: wire `:verify-location` the day the primitive takes a CA path, client certs
  the day `tls-upgrade` grows a client identity.
- dexador's exact `(:import-from :cl+ssl ...)` list is the export set.
- The context, the verify default and the refusals are pure Lisp, pinned on all four backends by
  the standalone ci-spec case `cl-ssl-shim-verify-context-and-refusals` -- standalone because
  loading cl+ssl makes a component import `wasi:tls` (the standalone component run passes
  `-S tls=y`) and splices sockets.lisp, which no corpus case should pay.

## Not supported
UDP; IPv6 on the WASM component (literals, and a name with only IPv6 addresses); TLS on `--native`
and TLS servers on WASM (permanent, client-only proposal); mutual TLS; a CONNECT timeout on
`tcp-connect` (the OS bounds it; `%tcp-connect-socket` takes one); `--no-gc`; a plain Preview 1
`.wasm`; a listener and TLS on a `--no-wasi` module; the browser playground.

## Socket tests
- **A test listens on the address its client connects to: `127.0.0.1`.** macOS lets `127.0.0.1:P`
  bind beside `*:P` (Linux refuses) and the more specific listener takes the loopback connection, so a
  parallel test binding `127.0.0.1:P` took a wildcard test's connection and its accept waited forever
  (`compileAndRunUsocketAddressAccessors`, 22 min in `Net.accept`). Two `127.0.0.1:P` binds conflict,
  so a specific listener cannot lose its connection. A wildcard listener in a test only listens.
  Java-side peers bind loopback too (`TlsTestSupport`, `StreamHandleConcurrencySupport`).
- **No test picks a port by closing a probe listener** (the freed port may be another test's by then):
  refusals connect to port 0; a `tls-listen` server binds port 0 and writes the port to a file its Java
  client waits on (`TlsTestSupport.reportPortForm`).
- **Hang guard**: a test that blocks on a socket is `@SocketTest` (`testsupport`) = `@Test` +
  `@Timeout(5 min, SEPARATE_THREAD)`. The default `SAME_THREAD` mode only interrupts, and a platform
  thread in `ServerSocket.accept` ignores that (the JDK closes the socket on interrupt only for a
  virtual thread), so the guard abandons the thread. Component tests are bounded by `HostWasmtime`'s
  300 s process timeout, `TlsTestSupport`'s helper threads by a 120 s socket timeout.
- Not changed: listeners a test does not place -- the `http-handler` directive's server (`*:P`, the
  directive takes no host) and Testcontainers' mapped ports -- can still lose a loopback connection to
  a test's `127.0.0.1:P` listener; the guard bounds the socket side. A host argument for the
  directive is a product change this rare flake does not pay for.

## Pinning tests
`HandleOperandFixture` (`ThreadTest`/`JvmThreadTest`); `LispEvaluatorTest#tcp*`/`#usocket*`/`#tls*`/`#clSslShim*` (the two handshakes);
`JvmLispCompilerTest#compileAndRunTcp*`/`#compileAndRunUsocket*`/`#compileAndRunTls*`/
`#compileAndRunClSslShim*` + the wrong-arg-count rejections;
`WasmLispCompilerTest#tcp*`/`#usocket*`/`#tls*`/`#socketBuiltinsInPreview1ModeAreCallTimeErrors`/`#fetchAndTcpInOneComponentProgramCompiles`/
`#httpHandlerWithTcpCompilesInServeMode`/`#promotedSocketReadHoistsOutOfADispatchDefunArgument`;
`WasmLispCompilerIntegrationTest#componentTcp*`/`#componentUsocket*` (a full loopback echo inside
the wasmtime container; `#componentTcpReadDeadlineListenProbeAndWithdrawnReads` and
`#componentUsocketReceiveTimeoutAndWaitForInputAreReal` -- `UsocketWaitFixture`, shared with the
other three socket backends -- the deadline, the probe, the withdrawn reads and the handshake bound), `#componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer` (a
REAL handshake against an in-container `openssl s_server`), `#componentTlsFetchesARealHostOverHttps`
(opt-in `RONTOLISP_HTTP_E2E=1`: a byte-wise request and a 20 KB one against 1.1.1.1, the TLS output
buffer's pin -- no peer the host trusts can run in the container); `WasmSocketsRewriteTest`;
`PackageResolverTest#usocketLibraryFormsAreAResolverFixedPoint`; `LoadInlinerTest`;
`LispEvaluatorAsdfTest`; `NativeTcpTest` (the `--native` runner, "--native: the runner's sockets");
`FetchSpecE2eTest#javaSockets` (the Java subset's `Socket` and `ServerSocket` over these on the
interpreter, the JVM, `--native` and the component, the JDK the oracle, and the runner's `wait`).
The one test taking the shim to a real server is `ClPostgresE2eTest` (runs by DEFAULT, all 13 legs;
Docker is its only gate): verbatim cl-postgres over the usocket shim against a Testcontainers
PostgreSQL, on the interpreter, the JVM and a component -- the component by the server's network
ALIAS, so its leg is also the name-lookup pin (the refusals, `.invalid` and an IPv6-only name are
`componentTcpConnectRefusedOrUnresolvedReturnsNil`). The single-threaded echo choreography (listen 0
-> tcp-local-port -> connect -> write -> accept -> read) never deadlocks because the connection
waits in the listen backlog.
