# TCP sockets (`rontolisp:tcp-*`), TLS (`rontolisp:tls-*`), the usocket shim and the cl+ssl shim

Built-ins: `tcp-connect`, `tcp-listen`, `tcp-accept`, `tcp-local-port`, `tcp-local-address`,
`tcp-peer-address`, `tcp-peer-port`, `tcp-set-timeout`; `tls-connect`, `tls-upgrade` (upgrades an
ALREADY-CONNECTED handle — the cl+ssl shim's substrate), `tls-listen` (keystore password),
`tls-listen-pem` (cert + key files).

They return bidirectional stream VALUES in the same handle space as file streams
(`LispLayout.Kinds.SOCKET` / `:SOCKET-SERVER`, `.kb/read-load-streams.md`), so
`read-line`/`write-line`/`write-string`/`write-char`/`read-char`/`read-byte`/`write-byte`/`close`/`terpri`/`fresh-line`
work on sockets on every backend. The print family to a socket is deliberately NOT part of that
surface anywhere. Blocking and synchronous, except that on `--component` a read inside an ASYNC body
is promoted to a real suspension point. Reads are byte-at-a-time on interpreter/JVM; the component
and a `--native` executable hold one host CHUNK per socket (documented divergence). `read-line`
answers `nil` at peer close; `read` does not work on sockets. Errors: interpreter/JVM and `--native`
signal (one text), the WASM component returns `nil` -- but a read past its deadline signals the
interpreter's text everywhere ("Read deadlines" below), and `usocket:socket-connect` signals on all
four (the usocket shim, below); a plain Preview 1 `.wasm` has no sockets (call-time error).

## Argument refusals (interpreter and JVM, one condition and text)
Every argument is checked, in argument order, before the host acts. A host, path or password that is
no string is the operator's `type-error` expecting `STRING`; a port outside `(INTEGER 0 65535)` and a
`tcp-set-timeout` count outside `(OR NULL (INTEGER 0))` likewise (ranges, bignums included: 70000 was the
JDK's `port out of range`, a `program-error` on the interpreter and a `simple-error` on the JVM; a count
past an int clamps on both, where the JVM's `l2i` turned 3e9 into "timeout can't be negative"). A
listener's host may be `nil` (every interface) on both -- the JVM cannot tell it from an absent one. A
handle naming no open entry of the operator's kind (a non-handle, a closed socket, a file stream, a
listener where a connection is needed) is `OP expects a {listener|socket or listener|connected socket}
handle`, a `simple-error` with no datum. JVM: the socket runtime's `_sockEntry`/`_sockStr`/`_sockPort`/
`_sockMs` (`%tls-listen-p12`'s report says `TLS-LISTEN-PEM`, the operator the inliner rewrote). Before 2026-10-11 the JVM leaked `ClassCastException`s and NPEs (`_streams` is null in a program with
no file stream) where the interpreter signalled.

## Host failures (interpreter and JVM, one text)
A host exception (an `IOException`; for TLS also a `GeneralSecurityException`) is a `simple-error` reading
`OP: cannot connect to H:P: MSG` (`tcp-connect`, `tls-connect`), `OP: cannot listen on H|*:P: MSG`
(`tcp-listen`, `tls-listen`, `tls-listen-pem`), `tls-upgrade: cannot upgrade the stream to TLS against H: MSG`,
or `OP: MSG` (`tcp-accept`, `tcp-set-timeout`, `read-line`, `read-char`, `read-byte`, `write-line`,
`write-string`, `write-byte`); MSG is the JDK's. The interpreter wraps in `SocketSupport`; the JVM in a
handler `emitHostFailure` appends to each socket helper (`_sockReadByte`/`_sockWriteByte` carry the byte
operators' arm, so `_readByte`/`_writeByte` call them). Before 2026-10-11 the JVM answered the bare
`Connection refused` / `Address already in use`. The handler frame records the caught exception as an `Object`:
`checkcast Throwable` before using it. Pinned by `HandleOperandFixture.HOST_FAILURE_PROGRAM`, whose refused
connects go to PORT 0 (nothing can listen there; macOS answers "Can't assign requested address", Linux
"Connection refused", so only the prefix is pinned). **Trap**: never test a refusal by closing an ephemeral
listener and reconnecting -- under the parallel suite another test bound the freed port, the connect succeeded,
and the then-unbounded `tls-connect` handshake hung `./mvnw test` for 8 hours (2026-10-23). A
bound-but-not-listening socket is no fix either: macOS DROPS the SYN (measured: `Operation timed out`). The
`*:P` conflict is taken against an every-interface listener: macOS's SO_REUSEADDR lets `*:P` bind beside a
`127.0.0.1:P` listener (Linux refuses), which read `:NO-ERROR` there. Still unwrapped on
the JVM: nothing known; an unknown host's text (`no-such-host.invalid`) is DNS-dependent and unpinned.
Both groups pinned by `HandleOperandFixture` ([error-handling.md](error-handling.md)).

## Per-backend mechanics
- Handle allocation is CONCURRENT on interpreter and JVM (`ConcurrentHashMap` + `AtomicLong`; the
  synchronized `_addStream`) — invariant and the crossed-PostgreSQL-handshake failure in
  `.kb/read-load-streams.md`.
- Interpreter `eval/SocketSupport.java`: the raw `Socket`/`ServerSocket` goes into
  `Map<Long, Closeable> streams` (`close` sends a connected socket's buffer first,
  `SocketSupport.close`); built-ins branch on
  `instanceof Socket` (`socketEntry`, on the ALREADY-resolved designator). It is the web
  substitution seam (`Target_SocketSupport` signals).
- JVM `JvmTcpCompiler` + `JvmSocketRuntimeBuilder`; `JvmIoRuntimeBuilder` grows `instanceof Socket`
  branches ONLY when `usesTcp`, so other programs keep byte-identical stream bodies. **Trap**: the
  `_writeString` arm is NOT in `_writeStr`, the sink shared with the print family — a branch one
  level down would ship a program that works on two backends and traps on `--component`. Same reason
  the interpreter's arm sits in the `write-string` built-in, not `emitTo`.
- WASM: a plain Preview 1 `.wasm` lowers each tcp call site to a CALL-TIME error
  (`LispMacroExpander.callTimeUnsupportedStub`) so a spliced library's dead socket layer still
  compiles; where a provider is spliced (`Ctx.socketsSpliced`, the `%io-read-line` marker --
  NOT the function table: a native taken as a value registers a wrapper under its own name whose
  body is the call site, so a table test looped `#'rontolisp:tcp-connect` on Preview 1) the names
  fall through to the ordinary call path
  and resolve against the spliced defuns. `--component`: `eval/sockets.lisp` (wit-imported
  `wasi:sockets/types@0.3.0`, `eval/sockets.wit` = vendored types.wit plus transparent
  `sock-stream`/`sock-future`/`accept-stream` aliases); `--native`: `eval/runner-sockets.lisp`
  ("--native: the runner's sockets" below). Both spliced by `eval/SocketsLibrary` (triggers on a
  tcp-* OR any `usocket:` reference; the runner shape under `CompileFrontend.Options.runnerHosted()`).
  Handle is an integer >= 200 but the table is LISP state (`rontolisp::*sock-table*`); the old
  `sock.*` seam at core indices 8-11 is gone, so `FUNC_START` = `IMPORT_FUNC_COUNT` (8).
- JVM gate `usesSockets` = any `tcp-*` OR `tls-connect`/`tls-listen`/`%tls-listen-p12`.
- **Every connected socket BUFFERS its writes, on all four backends** (interpreter, JVM and
  `--native` since 2026-11-09; the component's mechanics in "Inside sockets.lisp" below). 8 KiB
  per socket; flushed before every wait on ANY socket (a read, `listen`, accept, connect), by
  `force-output`/`finish-output`, by `close` (a failed send there does not stop the close), when
  full, and on every way out of the program. Interpreter: `SocketSupport.OUTPUT_BUFFERS`
  (socket -> `BufferedOutputStream`, a `ConcurrentHashMap`), `out`/`in`/`flushAll`; the end is
  `openStreamFlusher` (what the CLI calls however the program ends, quit included). JVM:
  `_sockBufs` (the field, made by the synchronized `_sockBufs()`), `_sockOut`/`_sockIn`/
  `_sockFlushAll`/`_sockDetach`/`_sockClose`; the end is `_flushStreams`, emitted for a socket
  program too (`main`'s return, `%host-exit`, the uncaught handler). `--native`: the runner's
  `Conn` (`tcp.rs`), the `flush` import, `flush_all` from `main` after `_start` however it ended.
  `tls-upgrade` sends and drops the plain socket's buffer before the handshake; the `SSLSocket`
  gets its own (fewer TLS records too). Unlike the component, `uiop:quit` sends what is buffered.
  **Trap (threads, interpreter/JVM)**: a thread that writes and then waits on something that is no
  socket (a join, a condition, `sleep`) leaves its bytes buffered until any thread next waits on a
  socket -- SBCL's buffered fd-streams behave the same; such a program owes a `force-output`.
  Pins: `TcpOutputBufferFixture` on every socket backend (`LispEvaluatorTest#tcpEchoRoundTripOnLoopback`,
  `JvmLispCompilerTest#compileAndRunTcpEchoRoundTripOnLoopback`, `NativeTcpTest`,
  `WasmLispCompilerIntegrationTest#componentTcpBuffersWritesUntilTheProgramWaitsOnASocket`) and
  the Rust loopback `tcp::tests` (the bytes wait in `Conn.out` until a wait sends them).
- **Why, measured 2026-11-09** (Linux, PostgreSQL 18-alpine in Docker reached at its bridge
  address, md5 auth; cl-postgres, 20 `exec-prepared` then 20 simple `exec-query` on one
  connection, ms): write-through interpreter 1796 / 1767, JVM 1676 / 1779, `--native` 1646 / 1640
  (about 85 ms a query: two delayed ACKs); buffered 95 / 59, 61 / 101, 5 / 4. cl-postgres writes a
  message with one `write-byte` per byte, so the first byte left alone and the rest waited for the
  peer's delayed ACK under Nagle. `TCP_NODELAY` was not taken: with byte-wise writes it sends a
  segment per byte, and with the buffer the stall is gone without it (usocket's `:nodelay` stays
  accepted-and-ignored, and the component cannot set it).

## --native: the runner's sockets (since 2026-10-24)
A `--native` executable is the Preview 1 module plus the runner, so the RUNNER owns the sockets
and the module owns the dispatch layer: `runner-sockets.lisp` is a SECOND PROVIDER of the
`%io-*`/`%...-future` names sockets.lisp provides a component, over fourteen `rltcp` imports the
network runner answers (`rontolisp-native/runner/src/tcp.rs`; `connect`/`listen` (host string,
port), `accept`, `read` (the `:bytes` caller-buffer shape), `write` (`:octets`: an octet vector
or a string's raw bytes, into the host's output buffer), `flush` (`force-output`/`finish-output`;
nil on a listener), `close`, `localPort`/`localAddress`/`peerAddress`/`peerPort`,
`setTimeout`, `readable`, `lastError`). The host is a `std::net` stream or listener per number; every call
BLOCKS the module's one thread (no scheduler on Preview 1), a host name resolves as the JDK
resolves one, and a failure answers -1 leaving the OS's text (`serve.rs`'s `reason`, no
`(os error N)`; a read past its deadline is `Read timed out`, the JDK's words).
- **Same names, same shapes, different blocking**: the `%...-future` twins are PLAIN defuns
  answering settled values (the degenerate Preview 1 `await` passes a non-future through), so
  `WasmSocketsRewrite`'s async promotion and sync dispatch both land on blocking code; nothing
  calls `%future-force`. The rewrite and `builtinDispatchAliases` run on every wasm-GC module and
  key on the spliced `%io-read-line`, no longer on `component`. `StdinLibrary` splices nothing
  (not a component): the non-socket arms go straight to the `%...-raw` aliases, which compile on
  both wasm-GC targets now (the `--component` gate on them and on `%str-byte-*`/`%str-from-byte`
  went on 2026-10-24).
- **Errors SIGNAL with the interpreter's texts** (`OP: cannot connect to H:P: MSG`, `OP: cannot
  listen on H|*:P: MSG`, `OP: MSG`, `OP expects a ... handle`), composed in Lisp from
  `lastError`; the port/host argument checks are messages, not `type-error`s. ONE signalling
  site, `rontolisp::%sock-fail`, whose NAME is an EH-mode trigger (`WasmLispCompiler.usesEhForm`,
  `LispNames.SOCK_FAIL_INTERNAL`, the usocket guard's pattern) -- a Preview 1 `%error` outside EH
  mode is a bare `unreachable` carrying no text, and the component gets EH mode from
  sockets.lisp's handler-case forms. Consequences: a usocket program's `%usock-guard` expands to
  the handler-case arm (`ctx.ehMode`), so the shim re-signals typed `usocket:socket-error` here as
  on the interpreter/JVM; an uncaught failure prints `Unhandled condition: tcp-connect: cannot
  connect to ...` and exits 134.
- **Reads**: one 8 KiB `(unsigned-byte 8)` chunk buffer per connected socket, filled by `read`,
  walked with `aref`; lines and characters assembled from bytes exactly as sockets.lisp does
  (`%str-from-byte`/`%string-concat`). `tcp-set-timeout` is REAL (`set_read_timeout`; a timed-out
  read signals `read-line: Read timed out` out of the stream built-in).
  `write-sequence` of a non-string vector copies the range into an octet vector
  (no per-byte string concat). TLS stays off (`tls-connect`/`tls-upgrade` keep the Preview 1
  call-time error; the runner's rustls is the fetch client's only).
- **`listen` asks the kernel** (since 2026-11-06; it saw the chunk only): bytes left in the chunk,
  else the runner's `readable`, `FIONREAD` > 0 -- the question the JDK's `available()` asks, so it
  consumes nothing, not even a pending error (a `peek` would: on Linux 6.8 after a reset,
  `recv(MSG_PEEK)` answered `ECONNRESET` and the next read EOF, where after `FIONREAD`'s 0 the
  read still answered `ECONNRESET`, measured 2026-11-06). nil at the eof mark, at the
  peer's close (FIN is no byte) and for a listener, as on the component. No future race is
  needed: every runner call blocks anyway. Measured on unpatched dexador (2026-11-06): a
  `:want-stream t` body of 108 KB read one line in and closed drains the rest through `listen`
  before the connection goes back to the pool; with the chunk-only `listen` the drain stopped at
  the chunk's end, and the next request found the pooled connection unusable and went out on a
  fresh one (the server saw a new client port; dexador's retry hid it).
- **The stub**: a module importing `rltcp` starts with `rlrun-net` like one importing `rlhttp`
  (`RontoLispCli`, `SocketsLibrary.RUNNER_IMPORT_MODULE`; the plain stub exits 1 naming it). The
  host lives under the runner's `net` feature (std::net adds no dependency; one network runner).
- Pins: `NativeTcpTest` (the imports; one executable running the loopback programs of the
  `componentTcp*`/`componentUsocket*` tests with the error texts, then the `LineStartFixture`,
  `UsocketWaitFixture` and `TcpOutputBufferFixture` programs every socket backend runs -- skips
  without the host pair),
  `NativeFetchTest#theOutputStartsWithTheRunnerItsModuleNeeds` (Linux), the Rust `tcp::tests`
  (`build.sh --test`), `StdinLibraryTest#theThreeDispatchSplicesDefineTheSameNamesAndShapes`.
  Not run anywhere automatically: cl-postgres over the shim from an executable (`ClPostgresE2eTest`
  has no native leg).

## TLS
Clients run on interpreter, JVM and the WASM component; the listeners are interpreter/JVM only,
permanently. On interpreter/JVM everything leans on `SSLSocket`/`SSLServerSocket` being
`Socket`/`ServerSocket` subclasses. Hence no `tls-accept`: plain `tcp-accept` accepts on a TLS
listener and the accepted `SSLSocket` handshakes lazily on first I/O.

- FRESH `SSLContext` per call, never the cached `SSLSocketFactory.getDefault()`, so
  `javax.net.ssl.trustStore` is re-read per connection.
  `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` before `startHandshake()` — the JDK
  does NOT verify hostnames by default.
- `tls-upgrade` transport is `SSLSocketFactory.createSocket(socket, host, port, true)`; the
  handshaken socket becomes a NEW stream-table entry (closing it closes both).
  `SocketSupport.upgradeTls` / `_tlsUpgrade`, under `usesTlsConnect`.
- `tls-connect` IS `new Socket(host, port)` + that same handshake (`SocketSupport.handshake` /
  `_tlsHandshake`, shared by both operators), as on the component. **Handshake deadline**: the
  handshake waits as long as the socket's `SO_TIMEOUT` allows. `tls-upgrade` uses whatever the
  caller set (`tcp-set-timeout`; none = unbounded, the caller's choice). `tls-connect` sets
  `LispNames.TLS_CONNECT_HANDSHAKE_TIMEOUT_MS` (10 s, Go's `TLSHandshakeTimeout` and Netty's
  default) on its fresh socket -- the caller has no handle to set one on first -- clears it to 0
  after the handshake, and on any failure CLOSES the socket before signalling (a timed-out
  `SSLSocket` stays open otherwise). A custom bound is `tcp-connect` + `tcp-set-timeout` +
  `tls-upgrade`; no `:timeout` option. The timeout's text is `... Read timed out` (pinned through
  `tls-upgrade` with a 50 ms deadline in `HOST_FAILURE_PROGRAM`; the 10 s default itself is not
  timed by any test). WASM component: the same bounds, enforced by racing `connector.connect`'s
  future against a `wait-for` timer under the entry's deadline (`tls-connect` sets 10000 and
  clears it after); losing answers `nil` (the component convention) and leaves the handshake
  subtask pending host-side -- no `subtask.cancel` is bound -- until the caller's close.
- `:insecure` non-nil skips BOTH chain validation and endpoint identification; the keyword must be
  LITERAL. Interpreter `SocketSupport.TrustAllManager`; on the JVM the GENERATED PROGRAM CLASS
  implements `X509TrustManager` (the JDK cannot take an anonymous TrustManager from hand-assembled
  bytecode), and its three methods are extra `--optimize` roots — JSSE reaches them invisibly to the
  tree-shaker.
- `tls-listen` takes a PKCS12 keystore + password. `tls-listen-pem` takes a PEM chain + an
  UNENCRYPTED PKCS#8 key; parsing (`SocketSupport.pemToKeyStore`, public `TlsPemSupport`) runs at
  PARSE time — the `cli` `TlsPemInliner` pre-pass (JVM branch only) rewrites LITERAL paths to
  `rontolisp:%tls-listen-p12 base64 password port [host]`; non-literal paths are a compile error.
- Fixture `TlsTestSupport` (package `am.ik.rontolisp`) generates one self-signed PKCS12 keystore per
  JVM with `keytool` (CN=localhost, SAN ip:127.0.0.1 + dns:localhost) and runs the peer on a
  background thread — a handshake needs a live peer, so the plain-TCP backlog trick does not apply.
  `pemFiles()` derives cert.pem + PKCS#8 key.pem in pure Java. Examples
  `examples/net/{https-hello,kv-server-tls}.lisp`.

### WASM TLS (component only)
`tls.lisp` (`eval/TlsLibrary`) over a wit-imported `wasi:tls@0.3.0-draft` (`eval/tls.wit`, vendored
from wasmtime v47.0.2). Run with `-S tls=y` on top of the tcp flags. The SERVER half is a compile
error on EVERY WASM target PERMANENTLY (the proposal defines only `client.wit`). On Preview 1,
`--native` included, the client pair is a CALL-time error (`WasmExprCompiler`, the tcp policy;
pinned by `RontolispNativeValueFixture.NO_SOCKET_PROGRAM`): it was a compile error on the premise
that no spliced library carries a live-but-unreached site, which dexador overturned -- its
`request` reaches the cl+ssl shim's `tls-upgrade` on the https branch, so every dexador program
was refused on Preview 1, `http://` ones included (measured 2026-11-06).

- The UPGRADE is the primitive: `connector.receive` takes the socket's in-flight recv stream and
  answers the cleartext read stream; `connector.send` takes the read end of a fresh cleartext pair
  and answers the ciphertext stream, which goes STRAIGHT into `tcp-socket.send` — no guest-side pump.
  `tls-connect` = `tcp-connect` + `tls-upgrade`.
- That is why sockets.lisp DEFERS the send half (`%sock-plumb` takes only `receive()`;
  `%sock-ensure-tx` calls the at-most-once `tcp-socket.send` on the FIRST write): `tls-upgrade` must
  interpose before the send side is committed, so it requires a handle with no prior write.
- The entry is swapped onto the cleartext ends IN PLACE (`%sock-set-streams`), so here `tls-upgrade`
  answers the SAME fd where interpreter/JVM answer a NEW one.
- **A TLS entry's buffered writes are PACED** (buffered since 2026-11-07, plain TCP too since
  2026-11-09: "A connected socket BUFFERS its writes" below; sockets.lisp, "the output buffer"):
  `tls-upgrade` marks the entry (slot 12) after the handshake, and each piece then goes out after
  a `(wait-for 1)`; while one task has a piece in flight, another flusher waits, so pieces keep
  buffer order. `force-output`/`finish-output` reach the flush through
  `%io-force-output`/`%io-finish-output` (routed by `WasmSocketsRewrite`; the other two providers
  answer the native identity). Real cl+ssl's buffer flushes at fewer points (force/finish-output,
  full, close), so a program correct against it is correct here. `tls-upgrade` refuses an entry
  with bytes still buffered as it refuses one already written to.
- **Why: wasmtime-wasi-tls 49 drops the tail of a write** (measured 2026-11-07, wasmtime 49.0.0,
  against 1.1.1.1 and httpbin.org). Unbuffered, a run of more than about 128 writes before a read
  lost its end (byte-wise: 121 writes answered, 131-181 timed out), and so did ONE write past
  16 KiB (a 1,000-byte POST body answered, 20 KB, 70 KB, 200 KB did not; dexador's 50 KB POST
  timed out too). Host cause, read in the source (`p3/util/tokio_streams.rs`, `p3/util/pipe.rs`,
  tokio-rustls 0.26 `common/mod.rs`, tokio `io/util/mem.rs`): the cleartext consumer
  (`AsyncWriteConsumer::poll_consume`) reports a write `Completed` when tokio-rustls' `poll_write`
  ACCEPTS the plaintext; tokio-rustls returns `Ready(n)` even when writing the records to the
  ciphertext pipe (a 16 KiB tokio `duplex`) answered `Pending` -- pipe full, or tokio's
  cooperative budget (128 operations per task poll; the duplex's `poll_write` takes one) used up.
  The records stay in rustls; nothing calls `poll_flush`, and `poll_read` never writes, so they
  wait for the next cleartext write. Confirmed from the guest side: byte-wise 181 writes with a
  10 ms sleep after write 60, 100, 150 or 170 answered, after write 20 (160 left) did not; 1 MB
  in 8 KiB pieces each after `(sleep 0.001)` answered, the same pieces without the sleep did not;
  `(sleep 0)` and `(wait-for 0)` are no yield (no host wait, budget not reset). So the fix has to
  make each piece fit an empty pipe (8 KiB plus record overhead < 16 KiB) under a fresh budget;
  buffering alone (no wait) still failed past 16 KiB. With it (same day): drakma
  `https://1.1.1.1/` 200 (was `No status line` after 15 s), drakma 40 KB string and 30 KB octet
  POSTs, dexador 50 KB POST, a 1 MB write: all answer, as on the interpreter.
- **Residual**: a peer slower than the 1 ms wait (the pipe not drained into TCP by then) can
  still strand the last piece; not observed. The upstream fix is the consumer reporting a write
  complete only after `poll_flush` is `Ready` (or a task driving the flush). **Trigger**: on every
  wasmtime floor bump re-run the opt-in `componentTlsFetchesARealHostOverHttps` with the buffer
  bypassed (write-through); when it passes, the wait and the buffer can go (keep the buffer only
  if fewer records are wanted -- byte-wise writes cost one record each).
- Errors nil; `connector.connect`'s err arm is an `own<error>` RESOURCE the handler releases
  (`%tls-err:error-drop`). `:insecure` non-nil SIGNALS at RUN time (the draft has no verification
  knob). Verification uses the HOST's anchors, so `javax.net.ssl.trustStore` has no effect.
- **`TlsLibrary.process` must run BEFORE `SocketsLibrary.process`** in every chain (the trigger is
  TEXTUAL and tls.lisp references `rontolisp:tcp-connect` + `%sock`) and fires the sockets trigger
  itself; a program textually naming the client tls names needs `-S tls=y` even for dead code.
- `wasmtime serve` does NOT host wasi:tls (measured on 47.0.3 and 49.0.0, `-S tls=y`): the component compiles but
  instantiation fails with "resource implementation is missing". A served handler needing outbound
  https uses `rontolisp:fetch`.
- **Trigger**: the draft is experimental/non-semver — on every wasmtime floor bump re-diff
  `eval/tls.wit` against `crates/wasi-tls/src/p3/wit/deps/tls/`, and re-check for
  verification/client-cert/ALPN knobs or a server interface. Last re-diff: 49.0.0 (2026-09-24), the three
  `.wit` files are the same blobs as 47.0.3's.

## The component mechanics (sockets.lisp over wasi:sockets@0.3.0)
A tcp program is the BASE variant plus one appended user import (`appendUserImports`), like fetch,
so fetch+tcp and serve+tcp compose in one component.

- **serve+tcp under `wasmtime serve` needs `-S cli=y`** (plus `-S tcp=y -S inherit-network=y`);
  without it instantiation reports "instance export `tcp-socket` has the wrong type", which reads
  like a missing host feature. Pinned by `httpHandlerConnectsTcpUnderWasmtimeServe` — the
  compile-level `httpHandlerWithTcpCompilesInServeMode` cannot catch a runtime composition failure.
- **Serve top-level init**: a serve component never lifts `run`, so nothing would execute the
  top level and every global would read back null. The `handle` wrapper
  (`WasmExportCompiler.emitBody`) runs `_start` once per instance under a serve-only `(mut i32)`
  flag (`serveInitGlobalIndex`, appended after every other global so non-serve output is
  byte-identical), inside the handle call's task context. Pinned by
  `httpHandlerReadsATopLevelGlobalUnderWasmtimeServe`.
- **"Once per instance" is a HOST decision**: `wasmtime serve` and Spin do BOUNDED reuse
  (`--max-instance-reuse-count` 1 for WASIp2, 128 for p3; `--max-instance-concurrent-reuse-count` 16
  for p3), wasmCloud `wash dev` gives each request a FRESH instance. Rule: treat top-level side
  effects as idempotent-or-per-request and keep durable state in the store, not in a global. The
  per-instance cost is why serve mode pre-grows a smaller GC heap
  (`.kb/wasm-gc-heap-pregrow.md`).
- wasmCloud: `wash dev` (2.5.2+) provides `wasi:sockets@0.3` (its "Host provides interfaces" log is
  a stale list naming 0.2). Loopback routes to a per-workload virtual network, so a host-side
  listener gets `:connection-refused` -> `nil`, and a nil sock reaching `(read-line nil)` is an
  uncatchable cast-failure TRAP; the listen host must be an explicit `"127.0.0.1"`. Install with
  `curl -fsSL https://wasmcloud.com/sh | bash` — do NOT fetch by tag; the SEPARATE `wasmCloud/wash`
  repo is stuck at `wash-v2.0.0-rc.7`, whose binaries advertise `wasi:http@0.2.0` only and reject a
  serve component (looks exactly like a rontolisp regression, is not).
- **Run flags**: sockets.lisp carries async-defuns and handler-case, so a component tcp program is
  asyncMode + EH mode. Unlike wasi:http, wasmtime always hosts wasi:sockets and gates it by
  permission: without the flags the component instantiates and socket calls return `nil`
  (`componentTcpWithoutNetworkFlagsReturnsNil`).

### Inside sockets.lisp
- Plumbing `%sock-plumb`: `receive()` yields the recv stream (its future dropped immediately — EOF is
  the stream status), `sock-stream-new` makes a pair whose read end goes to `send()`; `%io-close`
  drops the write end (FIN), the recv stream and the resource.
- **A connected socket BUFFERS its writes** (since 2026-11-09; sockets.lisp, "the output buffer"):
  an 8 KiB octet vector per connected entry (slots 9-12: buffer, fill, flushing, paced; a listener
  has none and writes through). Flushed by every socket-layer host wait, for EVERY entry with
  buffered bytes (`*sock-dirty*`, `%sock-flush-all-f`): `%sock-fill` (a read the chunk cannot
  answer), `listen`, `%tcp-accept-f`, `%tcp-connect-f` -- all entries, because a single-threaded
  program writes on one socket and then waits on its peer's end (the loopback echo choreography).
  Also by `force-output`/`finish-output` and `close` (that entry), a full buffer, and the program's
  end (`SocketsLibrary` appends `(rontolisp::%sock-flush-all)` to a component program). A plain
  write of 8 KiB or more goes out as it is after the buffered bytes (no copy); a TLS entry's never
  does (its pieces must fit wasi:tls's pipe). NOT flushed by a wait outside the socket layer
  (`sleep`, `wait-for`, stdin, fetch) nor by `uiop:quit`/`%host-exit`: a program that writes and
  then only sleeps sends at the next flush point, as with SBCL's buffered fd-streams (the
  interpreter, JVM and `--native` buffer at the same points, quit included). Flushing at the
  scheduler's own block point
  (`_sched_loop` before `waitable-set.wait`, and the serve callback's WAIT return) would cover
  every wait; not done -- runtime-built WASM calling a Lisp defun, re-entered by the flush's own
  write.
- **Why: Nagle meets delayed ACK** (measured 2026-11-09, `examples/db/shiori` as a component on
  sluice's wasi wicket, wasmtime 50, Linux container on OrbStack, PostgreSQL 18 in the next
  container, md5 auth). wasmtime-wasi 50 sets no `TCP_NODELAY` and wasi:sockets has no option for
  it, so write-through sent every piece cl-postgres writes as its own segment, each after the
  peer's delayed ACK of the one before: `/books` (6 statements) 0.47 s, first start (schema and
  sample data) 15.6 s; with delayed ACK off on PostgreSQL's side (`ip route change ... quickack 1`)
  0.025 s and 0.24 s. PostgreSQL's statement log showed 84 ms from each `parse` to its `bind` and
  43 ms to the next `parse`. A macOS host's loopback answered a page in about 0.1 s (no delayed
  ACK there). Pin: `componentTcpBuffersWritesUntilTheProgramWaitsOnASocket` (a JDK peer counts 1
  read for a line written byte-wise -- 2 before the buffer -- then the write past the buffer and
  the end-of-program flush; `TcpOutputBufferFixture`, run by every socket backend). The
  interpreter, the JVM (`new Socket`, no `setTcpNoDelay`, unbuffered
  socket `OutputStream`) and the `--native` runner (`write_all`, no `set_nodelay`) wrote through
  under the same Nagle until they buffered too ("Per-backend mechanics").
- **Addresses** (`%sock-addr-f`, 2026-11-06): an IPv4 literal (and `localhost`) is parsed in Lisp
  (`%sock-parse-ipv4`) and needs no lookup permission; any other name goes to
  `wasi:sockets/ip-name-lookup@0.3.0`'s `resolve-addresses` (the second `wit-import` of sockets.lisp,
  package `%sock-dns`; the interface is appended to sockets.wit). Its `async func` result
  `result<list<ip-address>, error-code>` lifted with no compiler change -- an `ip-address` arrives as
  `(:IPV4 a b c d)` / `(:IPV6 h1 ... h8)`. The first IPv4 address in the resolver's order is taken,
  the JDK's choice with its default IPv4 preference; a name with only IPv6 addresses is a
  `wit-error`, so `nil` (the sockets are IPv4 only). wasmtime grants the lookup with
  `-S allow-ip-name-lookup=y`; without it every name fails `permanent-resolver-failure` (`.invalid`
  answers `name-unresolvable`, `""` `invalid-argument`), and literals still connect. The lookup
  runs before `tcp-socket-create`, so a failed one leaves no socket behind. Every tcp component now
  imports the interface; wasmtime always hosts it (gated by the flag), other hosts unmeasured.
- `connect` is an `async func` over `%subtask-future`; `listen` returns
  `result<stream<tcp-socket>, ...>` and ACCEPT is one element read through the `accept-stream` alias
  — the ONE non-u8 stream lift (`validateAsyncAlias` admits resource-handle elements,
  `emitStreamRead` reads ONE 4-byte element, registry kind 2, `_sched_dispatch` lifts with
  `i32.load`).
- **Async-lower param SPILL**: wasmtime spills async-lowered params past 4 flats
  (`MAX_FLAT_PARAMS_ASYNC`; sync cap 16) and `connect`'s self + flattened `ip-socket-address` is 14,
  so `FlatSig` carries `spilledParams`/`paramsAreaSize`, `WitCanonicalAbi.spillLayout` computes the
  tuple layout and `emitAsyncStartBody` stores each argument via `emitLowerAt`.
- **Two surfaces**: sync tcp defuns force their async internals with `%future-force` (a binding of
  `_sched_loop`, OFF_SCHED_LOOP); ASYNC bodies get the await promotion from `WasmSocketsRewrite`
  (every wasm-GC module, right after the async-sugar rewrite, gated on the spliced `%io-read-line` defun) —
  `(read-line s)` -> `(await (%read-line-future s))`, `tcp-connect`/`tcp-accept` ->
  `%tcp-connect-f`/`%tcp-accept-f` (nil-on-failure, so both surfaces agree). In SYNC context the same
  calls rewrite onto the `%io-*` dispatch defuns, which branch: socket handle -> chunked reads /
  blocking `sock-stream-write`; a nil (stdin) READ designator -> the `%stdin-*-or-raw-f` helpers
  (stdin.lisp or its serve-mode stub, spliced by `StdinLibrary` whenever sockets.lisp is); anything
  else -> the `%...-raw` aliases of the NATIVE built-ins. A wrong-arity call is left unrewritten so
  it errors under its public name.
- **The chunk buffer walks BYTES, never characters**: a char-based cursor collapsed a valid
  multi-byte sequence in binary data (a PostgreSQL BackendKeyData secret) into one char, shifting the
  rest of the stream. The `stream<u8>` read lifts the chunk as a packed `(unsigned-byte 8)` vector
  (`.kb/wit.md`) so the cursor is plain `length`/`aref`; the component-only
  `%str-byte-length`/`%str-byte-ref` (`WasmStrByteCompiler`) survive for the WRITE side with
  `%str-from-byte` — `write-byte` of >= 128 used to emit a TWO-byte UTF-8 sequence, invisible in
  rontolisp-to-rontolisp loopback. `read-char` assembles one UTF-8 sequence byte-wise (mid-sequence
  refills work). Pinned by `componentTcpBinaryBytesAreWireTransparent`.
- **Sequence ops and eof-tolerant reads** (cl-postgres' surface): `read-sequence`/`write-sequence`
  with `:start`/`:end` and `(read-byte|read-char|read-line s eof-error-p [eof-value])` rewrite onto
  `%io-read-sequence`/`%io-write-sequence`/`%io-read-{byte,char,line}-eof` (sync) or the matching
  `%...-future` (async; writes never promote). Bounds travel POSITIONALLY (`nil` = unspecified,
  normalized inside the dispatch defun). A non-socket designator falls through via the `-raw` aliases,
  byte-identical to the unrewritten forms — widening `%io-read-byte` itself instead broke a top-level
  file read at EOF. Socket write-sequence elements are BYTES, except a STRING sequence, which goes
  out as its characters.
- **EVERY shape a socket-capable built-in accepts needs an entry in `WasmSocketsRewrite`.**
  Unrewritten, the call compiles to the NATIVE built-in whose `fd_read`/`fd_write` on a socket fd
  (>= 200) walks off the preview1 adapter's 64-slot fd table — the `unknown handle index 0` trap,
  firing MID-MESSAGE (server logs `incomplete message from client`), so it reads like a dropped
  `wasi:io` resource. The exotic shapes are load-bearing because of pass ORDER:
  `GrayStreamsLibrary.process` runs BEFORE this rewrite and re-spells every stream call site with
  EVERY optional argument filled in. Two shapes have no dispatch defun and are LOWERED inside the
  rewrite onto `write-string` — `(write-char c [s])`, bounded
  `(write-string s stream :start a :end b)` (`lowerWriteStringBounds`) and `(terpri [s])`
  (wrapped in a progn so terpri keeps answering nil, the stream-less form defaulting to
  `*standard-output*` at the original call site) — the first two had that lowering, but
  it ran at `WasmExprCompiler` time, AFTER this pass. An unrecognized shape is deliberately left
  UNREWRITTEN so the error names the built-in the program wrote.
- **Three alternative dispatch providers; widening one without the others breaks every program on
  the others**: sockets.lisp, `stdin-dispatch.lisp` (`StdinLibrary`, for an async component reading
  stdin but never a socket) and `runner-sockets.lisp` (`--native`). Exactly one is spliced and the
  rewrite picks its target by call SHAPE without knowing which it got. `./mvnw test` does NOT catch
  it; `StdinLibraryTest#theThreeDispatchSplicesDefineTheSameNamesAndShapes` compares the three
  name -> (required/optional) maps.
- **`terpri` on a socket carries the same arm** (measured 2026-09-23, all four): the
  interpreter branches on `socketEntry` inside the `terpri` built-in, the JVM routes
  through `_writeString` (`JvmTerpriCompiler` — NOT `_writeStr`, the print-family sink),
  and the component lowers onto `%io-write-string`. It answers nil and puts one `0a` on
  the wire; bounded `write-line`'s lowering (write-string + terpri) needs both halves.
- **`fresh-line` on a socket** (2026-11-05, was a `simple-error`, a `type-error` and a trap): the
  interpreter's and `_freshLine`'s socket arm, `%io-fresh-line` on `--component` and `--native`, over the line
  start each write keeps ([standard-output-redirect.md](standard-output-redirect.md), "A stream's line start"). The
  native `_fresh_line_stream` holds no row for a socket fd, so `#'fresh-line` applied to one there, a first-class
  use like the others out of contract, answers nil.
- **`write-string`/`write-char`/`read-char` on a socket are real on all four backends**, all
  treating socket I/O as BYTES — never through a `Reader`/decoder that would buffer ahead and swallow
  bytes a following `read-byte`/`read-line` owes the caller; an invalid lead byte stands alone as
  U+FFFD. Cross-backend pin: one program answering `(65 195 135 66 504 90 121)`.
- **A bare `(read-char sock)` / `(read-byte sock)` at peer close signals `end-of-file`** on all three
  (CL's default `eof-error-p` t), landing on BOTH entries a bare shape reaches — the promoted
  `%read-{char,byte}-future` and the sync `%io-read-{char,byte}`, which inherit it by forcing those
  futures. The nil convention stays BELOW in `%sock-read-{char,byte}-f`, because the `-eof-future`
  forms need the raw nil to apply the CALLER's eof arguments. `read-line` still answers nil at peer
  close (its `eof-error-p` defaults to nil).
- **The two rewrites MEET in an async body**: a write is never promoted, so
  `(write-line (read-line s))` is `(%io-write-line (await (%read-line-future s)))`.
  `WasmAwaitNormalizer` must hoist that await out of a `%`-named head's argument, which it refused
  while reading any `%` member as an internal form; `WasmSocketsRewrite.strictDispatchMembers()`
  tells it these `%` heads are ORDINARY defuns — **keep the two in step when adding a dispatch
  defun**.
- print-family to a socket is not wired on the component; `(format nil ...)` + `write-line` is the
  pattern.

## Address accessors
For the usocket shim's `get-local-*`/`get-peer-*`. Interpreter
`SocketSupport.localAddress`/`peerAddress`/`peerPort` (null/-1 for a wrong-kind entry ->
`Environment` signals); JVM `_tcpLocalAddress`/`_tcpPeerAddress`/`_tcpPeerPort` (the address
returners quote-frame the string; a wrong-kind entry is `_sockEntry`'s refusal, the interpreter's text). WASM: REAL in component mode — sockets.lisp reads
`get-local-address`/`get-remote-address` (an `ip-socket-address` variant->record->tuple lift) and
formats the dotted quad in Lisp; a wrong-kind handle or host error yields `nil`. `tcp-local-port`
reads the real bound port the same way. Preview 1: the call-time error.

## Read deadlines and the usocket timeout/wait decisions
`(rontolisp:tcp-set-timeout handle milliseconds)` (nil clears) sets a per-socket READ deadline;
`(setf (usocket:socket-option s :receive-timeout) seconds)` rides it.

- Interpreter/JVM: REAL via `Socket.setSoTimeout` (`SocketSupport.setTimeout` / `_tcpSetTimeout`,
  same `usesSockets` gate). A timed-out read throws `SocketTimeoutException` out of the ORDINARY
  stream built-in, so it surfaces as a catchable plain `error`, NOT `usocket:timeout-error`. The
  deadline lives on the raw socket, so it keeps governing a `tls-upgrade`d connection; listener
  handles are rejected.
- WASM component (since 2026-11-06; it SIGNALED before, wasi:sockets@0.3.0 having no receive
  timeout): REAL. The deadline is the entry's ninth slot; `%sock-fill` starts the read, and only
  a read the host could not answer at once (a pending future) races a `wait-for` timer through
  `%future-race` ([async-await.md](async-await.md), "`%future-race`"). When the timer wins, the
  read is WITHDRAWN (`sock-stream-cancel-read`, the synchronous `stream.cancel-read`): a chunk that
  landed meanwhile is still taken, an empty withdrawal (status 2) signals `OP: Read timed out`
  with the operator threaded down as runner-sockets.lisp does. Nothing stays in flight, so the
  socket reads on afterwards, as after `SO_TIMEOUT`. The losing timer is left to fire (exit is
  not delayed by it, measured 2026-11-06); a fast stream under a long deadline therefore holds one
  pending timer per blocked read until each expires. 0 clears, like `SO_TIMEOUT`; a bad count is a
  `simple-error`, a non-socket handle answers nil (the component convention). Measured on
  unpatched dexador (2026-11-06): its default `:read-timeout 10` runs there -- two keep-alive
  requests over one pooled connection, `https://1.1.1.1/`, and a stalled origin under
  `:read-timeout 1` failing with `read-byte: Read timed out`, as on the interpreter. By host name
  (same day, `-S allow-ip-name-lookup=y`): `http://example.com/` and `https://example.com/` answer
  200, `http://no-such-host.invalid/` signals `tcp-connect: cannot connect to ...`.
- **A read left in flight is a trap waiting for the close**: dropping a recv stream with a read in
  flight traps `cannot remove busy stream`, and dropping only the `tcp-socket` does NOT end the read
  (it waits on the peer; both measured 2026-11-06). That is why the loser is withdrawn at once rather
  than kept for the next read, and why `%io-close` withdraws whatever another task still has in
  flight (a read, or an accept on a listener) after taking the entry out of the table and marking
  its end -- the withdrawal wakes that reader synchronously, and one that still found the socket
  open would start a fresh read on the stream being dropped.
- **`listen` on the component is a probe** (it saw only the chunk before): bytes left in the chunk,
  else a read started and, when the host has nothing, withdrawn at once (`%sock-probe-f`); a chunk
  that comes is buffered for the next read. It answers what the host has SURFACED: bytes that need
  the host to run first -- a peer's bytes still on their way, even from a socket of the same
  program -- show after the program next waits (measured 2026-11-06: right after a peer write in
  the same component, the probe answered nil until a `sleep`). So a poll that waits between probes
  sees data and a tight spin never does; dexador's drain loop and wait-for-input below both wait.
  A listener answers nil.
- `usocket:socket-option` supports `:receive-timeout` ONLY; every other option signals naming the
  option, never accept-and-ignore — even `:tcp-nodelay` (though `socket-connect`'s `:nodelay` KEY is
  accepted-and-ignored, the shim's connect-knob convention). The GETTER answers from shim-side
  bookkeeping (`usocket::*%usock-timeouts*`); the setf converts seconds->ms through `%usock-guard`
  and the refusal happens BEFORE any bookkeeping.
- `usocket:wait-for-input` is a `listen`-based poll in pure Lisp (else poll every 10 ms until
  `:timeout`; 0 = one poll, none = forever; upstream's two values honoured), REAL on every backend
  with sockets and ONE shape on all of them since 2026-11-06: `listen` asks the kernel on the
  interpreter, the JVM and `--native`, the host on the component, whose `sleep` blocks on the
  scheduler between probes. A `--native` executable used to return IMMEDIATELY claiming readiness
  (a run-time `(member :rontolisp-native *features*)` branch, its listen seeing the chunk only),
  retired with the honest `listen` above; the 10 ms wait there is Preview 1's `sleep`, which blocks
  in `poll_oneoff` since 2026-11-06 ([time-environment-builtins.md](time-environment-builtins.md)). Racing the probes
  against the timeout instead of polling was weighed and not built: the poll is the interpreter's
  own shape and granularity, and it needs no backend-only path in the shim. A socket at end of
  file is not ready (`listen` answers nil there), where upstream's `select` reports it; stream
  sockets only; wait-list objects are not reproduced. Pinned on all four socket backends by
  `UsocketWaitFixture` (the deadline, the quiet wait, `listen` before and after data and at the
  peer's close): `LispEvaluatorTest#usocketReceiveTimeoutAndWaitForInputAreReal`, its JVM twin,
  `WasmLispCompilerIntegrationTest#componentUsocketReceiveTimeoutAndWaitForInputAreReal`,
  `NativeTcpTest`.
- `listen` on a plain Preview 1 module is a CALL-time error for any stream but a string one (was a
  compile error): the shim is spliced UNPRUNED into every usocket program (`LibraryDefunPruner`
  excludes it), so wait-for-input's listen site is dead code that must build. A Preview 1 module
  has sockets only under the runner (`--native`, above), so this is no socket gap: the module
  holds no socket for a probe to answer about. Consequence: cl-postgres/postmodern/mito programs
  COMPILE on Preview 1 and fail loudly at the first socket call.
- The `WaitForLibrary` trigger widened to any usocket reference (the shim has an unconditional
  `sleep` site and the usocket splice runs AFTER WaitForLibrary), and on 2026-11-06 to any tcp or
  client-tls one (`SocketsLibrary.references`, `TlsLibrary.references`: their libraries race a
  `wait-for` timer and splice later too); non-CLI component pipelines must run
  `WaitForLibrary.process` in their chain, or a deadline compiles to a call-time
  `RONTOLISP:WAIT-FOR is undefined`.
- `socket-server` was split out rather than shipped keys-ignored: silently ignoring `:in-new-thread`
  blocks a caller expecting to continue.

## The usocket shim (`usocket` package, `usocket.lisp` + `UsocketLibrary`)
A usocket-compatible API over the tcp built-ins, targeting the surface cl-postgres uses. The
linalg/vec pattern: `eval/usocket.lisp` (canonical package shape) + `eval/UsocketLibrary` (cached
`forms()`, Walker detection, `process()` splice with a dedup guard).

- A socket IS its handle: `socket-stream` = identity, `socket-close` = `close`; `socket-listen` flips
  usocket's host-first order onto `tcp-listen` and must OMIT the host argument for the wildcard case.
  `socket-connect` rejects `:protocol :datagram`; every entry point takes `&allow-other-keys`.
- `usocket:*wildcard-host*`/`*auto-port*` are defparameters, so `LispEvaluator.evalSymbolRef` has a
  variable-read lazy-load hook besides the `resolveFunction` one. `boundp`/`symbol-value` are NOT
  hooked (lite edge).
- `get-local-name`/`get-peer-name` end in a literal `(values address port)`, which flows through the
  multiple-values tier's user-function channel.
- `host-to-hostname`/`get-host-by-name` are pure Lisp over `format`/`elt`/`ash`, so they answer on
  Preview 1 too. `host-to-hostname` is REAL for every designator upstream accepts;
  `get-host-by-name` is LITE — it renders through `host-to-hostname` instead of resolving, because
  rontolisp has NO name-resolution primitive (the component's lookup lives inside sockets.lisp's
  connect/bind), and resolving on some backends only would cost a real backend divergence in a
  library SPLICED INTO EVERY socket program. Neither dexador nor drakma calls it. **Trigger**: the day
  a resolver primitive exists on all four, return upstream's real vector quad there.
- The four `with-*` macros (`with-client-socket`, `with-connected-socket`, `with-server-socket`,
  `with-socket-listener`) are `LispMacroExpander.expandUsocketWith*` expansions (the
  `rontolisp:with-arena` pattern: dispatched on the qualified name, registered only as usocket
  externals, no CL_MACROS entry), backend-parameterized — interpreter/JVM wrap the body in
  `unwind-protect`, the WASM call sites pass `unwindProtect = false`.
- Built-in ASDF system: `eval/BuiltinSystems` maps `"usocket"` -> `UsocketLibrary::forms`.
  `LoadInliner.spliceSystem` splices the FORMS, not mark-only — an `:import-from :usocket` +
  bare-name consumer would evade the Walker, which runs before `PackageResolver`. Chain wiring:
  `RontoLispCli`, `RontoPlayground`, corpus tests, `AsdfLibraryE2eSupport`, `resource-config.json`.
- Typed conditions: usocket.lisp defines the hierarchy (`socket-condition` with a `message` slot,
  `socket-error`, `connection-refused-error`, ...) and wraps
  `socket-connect`/`socket-listen`/`socket-accept` in `(usocket::%usock-guard form)`, expanded per
  backend (`LispMacroExpander.expandUsocketGuard`): `handler-case` + `%usock-resignal` on
  interpreter/JVM and in a wasm EH-mode module (every component socket program), pass-through
  otherwise (the source is parsed once and cached for all backends, so the branch cannot be a reader
  feature). The re-signal always uses `socket-error` (`.kb/error-handling.md`).
- `socket-connect`, `socket-listen` and `socket-accept` signal `socket-error` when their primitive
  answers nil, which only the component does (2026-11-06; `usocket::%usock-failed`, the
  interpreter's text without the host's reason: `tcp-connect: cannot connect to H:P`,
  `tcp-listen: cannot listen on H|*:P`, `tcp-accept: no connection was accepted`). Before, the nil
  socket flowed on into the library: cl-postgres given an unresolvable host died as a bare
  `wasm trap: cast failure`, and dexador against a refused port reconnected until wasmtime's
  resource table was full (its `(unless (open-stream-p stream) ...)` retry loop). Pinned by
  `componentUsocketEchoOverLoopback` (connect).
- Not reproduced (no substrate): UDP, `socket-shutdown`, wait-list objects, `socket-server`.

## The cl+ssl shim (`cl+ssl` package, `cl-ssl.lisp` + `ShimLibraries`)
Every CL HTTP client reaches TLS through cl+ssl, and the real cl+ssl is a CFFI binding to OpenSSL,
unloadable here. The shim is the CLIENT side only, over `tls-upgrade` (interpreter, JVM, WASM
component; on Preview 1, `--native` included, a call-time error). Its one consumer measured end to
end is dexador ([dexador.md](dexador.md)). The `flexi-streams.lisp` pattern: a canonical-shape
resource next to `ShimLibraries`, the package seeded in `PackageRegistry`, the system in
`BuiltinSystems` + `ShimLibraries.RESOURCES` + `resource-config.json`.

- `make-ssl-client-stream stream :hostname h [:verify v] ...` is
  `(rontolisp:tls-upgrade stream h :insecure (if v nil t))`; `:verify` defaults from
  `(ssl-check-verify-p)`, which consults the `with-global-context`-bound `*ssl-global-context*` (a
  context IS its recorded `:verify-mode`; `+ssl-verify-none+` 0 / `+ssl-verify-peer+` 1), so
  dexador's `dex:*not-verify-ssl*`/`:insecure` lands on `:insecure`.
- `:hostname` is REQUIRED (verified against, and the SNI); nil signals rather than silently skipping
  verification.
- **`make-ssl-client-stream` answers a stream or signals, on every backend** (upstream's contract).
  The component's `tls-upgrade` answers nil for a failed handshake, so the shim signals
  `tls-upgrade: cannot upgrade the stream to TLS against H` (the interpreter's text without the
  host's reason). Before (measured 2026-11-06), dexador took the nil for a stream, and its retry
  loop reconnected until `call stack exhausted`. Pinned by
  `componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer` (line 5).
- What has no backing SIGNALS: client certificates (`:key`/`:certificate`/`:password`,
  `use-certificate-chain-file`) and `make-context :verify-location` CA paths (only `:default`/nil
  pass). **Triggers**: wire `:verify-location` the day the primitive takes a CA path, client certs
  the day `tls-upgrade` grows a client identity.
- `with-global-context` is an ordinary shim `defmacro`; `ensure-initialized` is a no-op returning t.
  dexador's exact `(:import-from :cl+ssl ...)` list is the export set.
- The context, the verify default and the refusals are pure Lisp, pinned on all four backends by
  the standalone ci-spec case `cl-ssl-shim-verify-context-and-refusals` -- standalone because
  loading cl+ssl makes a component import `wasi:tls` (the standalone component run passes
  `-S tls=y`) and splices sockets.lisp, which no corpus case should pay.

## Not supported
UDP; IPv6 on the WASM component (literals, and a name with only IPv6 addresses); TLS on `--native` and TLS servers on WASM (permanent, client-only
proposal); mutual TLS; CONNECT timeouts (the OS bounds a TCP connect); `--no-gc`; a plain Preview 1
`.wasm`; the browser playground;
`(do () ...)`-style empty do bindings in examples (pre-existing `expandDo` limitation).

## Socket tests
- **A test listens on the address its client connects to: `127.0.0.1`.** macOS lets `127.0.0.1:P`
  bind beside `*:P` (Linux refuses) and the more specific listener takes the loopback connection, so a
  parallel test binding `127.0.0.1:P` took a wildcard test's connection and its accept waited forever
  (`compileAndRunUsocketAddressAccessors`, 22 min in `Net.accept`, 2026-10-23). Two `127.0.0.1:P` binds
  conflict, so a specific listener cannot lose its connection. A wildcard listener in a test only
  listens. Java-side peers bind loopback too (`TlsTestSupport`, `StreamHandleConcurrencySupport`).
- **No test picks a port by closing a probe listener** (the freed port may be another test's by then):
  refusals connect to port 0; a `tls-listen` server binds port 0 and writes the port to a file its Java
  client waits on (`TlsTestSupport.reportPortForm`).
- **Hang guard**: a test that blocks on a socket is `@SocketTest` (`testsupport`) = `@Test` +
  `@Timeout(5 min, SEPARATE_THREAD)`. The default `SAME_THREAD` mode only interrupts, and a platform
  thread in `ServerSocket.accept` ignores that (measured 2026-10-23: still blocked after the interrupt;
  the JDK closes the socket on interrupt only for a virtual thread), so the guard abandons the thread.
  Component tests are bounded by `HostWasmtime`'s 300 s process timeout, `TlsTestSupport`'s helper
  threads by a 120 s socket timeout.
- Not changed: listeners a test does not place -- the `http-handler` directive's server (`*:P`, the
  directive takes no host) and Testcontainers' mapped ports -- can still lose a loopback connection to
  a test's `127.0.0.1:P` listener; that fails the tests involved, and the guard bounds the socket side.
  A host argument for the directive is a product change this rare flake does not pay for.

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
`LispEvaluatorAsdfTest`; `NativeTcpTest` (the `--native` runner, "--native: the runner's sockets").
The one test taking the shim to a real server is `ClPostgresE2eTest` (runs
by DEFAULT, all 13 legs; Docker is its only gate): verbatim cl-postgres over the usocket shim against
a Testcontainers PostgreSQL, on the interpreter, the JVM and a component -- the component by the
server's network ALIAS, so its leg is also the name-lookup pin (Docker's DNS; the refusals,
`.invalid` and an IPv6-only name are `componentTcpConnectRefusedOrUnresolvedReturnsNil`). The single-threaded echo
choreography (listen 0 -> tcp-local-port -> connect -> write -> accept -> read) never deadlocks
because the connection waits in the listen backlog.
