# TCP sockets (`rontolisp:tcp-*`), TLS (`rontolisp:tls-*`), the usocket shim and the cl+ssl shim

Seven `rontolisp`-package built-ins — `tcp-connect` (host port), `tcp-listen`
(port &optional host), `tcp-accept` (listener), `tcp-local-port` (handle),
plus the address accessors `tcp-local-address` (handle) / `tcp-peer-address`
(handle) / `tcp-peer-port` (handle) — plus the encrypted variants
`tls-connect` (host port &optional :insecure value), `tls-upgrade` (stream
host &optional :insecure value; upgrades an ALREADY-CONNECTED handle, the
cl+ssl shim's substrate), `tls-listen` (keystore
password port &optional host) and `tls-listen-pem` (cert-file key-file port
&optional host; the two clients run on interpreter/JVM AND the WASM component,
the two listeners on interpreter/JVM only — see the TLS section), that return
**bidirectional stream handles in the same handle space as file streams**, so
the standard stream built-ins (`read-line`, `write-line`, `write-string`,
`write-char`, `read-char`, `read-byte`, `write-byte`, `close`) work on sockets
unchanged on every backend -- the character three joined the list on the
interpreter and the JVM in todo-264 (they were component-only before; the
bullet near the end of "The component mechanics" has the mechanism and the ONE
edge that still differs). The print family (`print`/`princ`/`prin1`/`format` to
a socket) is deliberately NOT part of that surface on any backend. Blocking,
synchronous API (no promises) -- except that on `--component` a read inside an
ASYNC body is promoted to a real suspension point (below). Reads are
byte-at-a-time (no readahead buffer is held between calls) on the
interpreter/JVM; the component holds one host CHUNK per socket (a documented
divergence -- reads still observe the same bytes through the one handle).
Writes go out immediately (`write-line` flushes per line, unlike buffered file
writers) on every backend. `read-line` returns `nil` at peer close; `read`
(the s-expression reader) does not work on socket handles. Error convention
matches fetch: interpreter/JVM signal, the WASM component returns `nil`.

## Per-backend mechanics

- **Handle allocation is CONCURRENT** on the interpreter and the JVM: a served
  request runs on its own virtual thread, so two `tcp-connect`s can allocate at
  the same instant. Both backends allocate through a thread-safe table (a
  `ConcurrentHashMap` + `AtomicLong`; the synchronized `_addStream`) -- the
  invariant, the failure it fixes (.todo/193: crossed PostgreSQL handshakes) and
  the rule for new socket built-ins live in `.kb/read-load-streams.md`.
- **Interpreter** (`eval/SocketSupport.java`, registered in `Environment`'s
  stream section because the handle table is a local there): the raw
  `java.net.Socket` / `ServerSocket` is stored directly in the
  `Map<Long, Closeable> streams` table (`close` needs no special case); the
  stream built-ins branch on `instanceof Socket` (the `socketEntry` helper next
  to `emitTo`, applied to the ALREADY-resolved designator).
  `SocketSupport.writeString`/`readChar` are the todo-264 additions.
  `SocketSupport` is the web
  substitution seam — `src/web/java/.../Target_SocketSupport.java` makes every
  operation signal "not supported in the browser playground".
- **JVM** (`JvmTcpCompiler` dispatch + `JvmSocketRuntimeBuilder` emitting
  `_tcpConnect`/`_tcpListen`/`_tcpAccept`/`_tcpLocalPort` plus
  `_addStream`/`_sockReadLine`/`_sockWriteLine`/`_sockWriteString`/
  `_sockReadChar`): the `_streams` entry is the
  raw `Socket`/`ServerSocket`. `JvmIoRuntimeBuilder` takes a nullable
  `SocketRuntime` and grows `instanceof Socket` branches in
  `_writeLine`/`_readLineStream`/`_writeString`/`_readChar`/`_readByte`/
  `_writeByte`/`_closeStream` ONLY
  when the program uses a tcp built-in (`usesTcp` in `JvmLispCompiler`) —
  non-socket programs keep byte-identical stream runtime bodies (which is why
  the two arms that need an extra local raise `maxLocals` only in that mode).
  **The `_writeString` arm is deliberately NOT in `_writeStr`**, the sink
  `_writeString` shares with the print family: `print`/`princ` to a socket has
  no dispatch on `--component` (it would reach the native `fd_write` on a
  socket fd and trap), so putting the branch one level down would ship a
  program that works on two backends and traps on the third. Same reason the
  interpreter's arm sits in the `write-string` built-in and not in `emitTo`.
- **WASM**: component-only (Preview 1 lowers each tcp call site to a
  CALL-TIME error via `LispMacroExpander.callTimeUnsupportedStub` -- since
  2026-07-28/todo-195, so a spliced library whose socket layer is dead code
  still compiles (s-sql over cl-postgres); before that it was a compile
  error. Under `--component` the tcp names FALL THROUGH to the
  ordinary call path -- the wait-for pattern -- and resolve against the
  spliced sockets.lisp defuns). The whole implementation is
  `eval/sockets.lisp` over a wit-imported `wasi:sockets/types@0.3.0`
  (`eval/sockets.wit`, the vendored types.wit plus transparent
  `sock-stream`/`sock-future`/`accept-stream` aliases), spliced by
  `eval/SocketsLibrary` (triggers on a tcp-* OR any `usocket:` reference --
  the usocket splice runs later in the pipeline). The handle is still an
  integer >= 200, but the table is LISP state (`rontolisp::*sock-table*`:
  fd -> socket resource + raw recv/send stream handles + chunk buffer). See
  "The component mechanics" below; there is no hand-written sockets adapter
  and no dedicated blob variant anymore.

## TLS (`rontolisp:tls-connect` / `tls-upgrade` / `tls-listen` / `tls-listen-pem`)

The clients (`tls-connect`/`tls-upgrade`) run on interpreter, JVM AND the WASM
component (the WASM bullet below); the listeners are interpreter/JVM only,
permanently. On the interpreter/JVM the whole design leans on `SSLSocket` /
`SSLServerSocket` being `java.net.Socket` / `ServerSocket` subclasses: the
handshaken socket (and the TLS listener) goes into the same stream table as a
plain entry and every existing `instanceof` branch (and the JVM `_sock*` /
`_tcpAccept` / `_tcpLocalPort` helpers) works on it unchanged — no new stream
runtime code on either backend. That is also why there is no `tls-accept`:
the plain `tcp-accept` accepts on a TLS listener, and the accepted
`SSLSocket` handshakes lazily on its first read/write (which is what makes a
handshake failure surface at first-I/O time, not accept time).

- Both backends initialize a **fresh `SSLContext` per call**
  (`SSLContext.getInstance("TLS")` + `init(null, null, null)`), NOT the
  process-wide cached `SSLSocketFactory.getDefault()`, so the
  `javax.net.ssl.trustStore` system properties are re-read on every
  connection — this is what makes the loopback tests (and user trust-store
  overrides) work without JVM restarts. HTTPS-style endpoint identification
  (`SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`) is enabled
  before `startHandshake()`; the JDK does NOT verify hostnames by default.
- **`tls-upgrade` (todo-399, the cl+ssl shim's substrate)**: `(tls-upgrade
  stream host [:insecure value])` wraps an ALREADY-CONNECTED socket handle in
  TLS as a client — the shape `cl+ssl:make-ssl-client-stream` has (a client
  library connects, possibly issues a proxy CONNECT, THEN starts TLS), which
  `tls-connect` cannot express. Same fresh-`SSLContext`/endpoint-id/`:insecure`
  policy as `tls-connect`; the transport is
  `SSLSocketFactory.createSocket(socket, host, port, true)` over the existing
  connection, and the handshaken `SSLSocket` goes in as a NEW stream-table
  entry (the original handle still names the raw socket underneath — closing
  the new one closes both, autoClose). Interpreter:
  `SocketSupport.upgradeTls`; JVM: `_tlsUpgrade` (the `usesTlsConnect`
  trust-all gate covers it too, so `:insecure` shares the
  program-class-as-X509TrustManager mechanism and the `--optimize` roots);
  WASM: same compile error as the rest of the family (below).
- **`tls-connect` `:insecure`**: `(tls-connect host port :insecure value)` —
  a non-nil `value` skips BOTH cert-chain validation and endpoint
  identification. The option keyword must be the literal `:insecure` (like
  `open`'s `:direction`); the value is a runtime expression. Interpreter:
  `SocketSupport.TrustAllManager` (a trust-all `X509TrustManager`) + no
  endpoint id. JVM: the JDK cannot take an anonymous TrustManager from
  hand-assembled bytecode, so the **generated program class itself implements
  `X509TrustManager`** (a no-arg `<init>` + the three trust methods, emitted
  in `JvmLispCompiler` only when `usesTlsConnect`); `_tlsConnect` does
  `new Prog()` and passes it to `SSLContext.init`. Those three methods are
  reached only through the interface (JSSE), invisible to the tree-shaker, so
  they are extra `--optimize` roots (`checkClientTrusted` / `checkServerTrusted`
  / `getAcceptedIssuers`).
- **`tls-listen` config**: a **PKCS12 keystore file + password** (keeps both
  backends on plain `KeyStore`/`KeyManagerFactory`/`SSLContext` calls).
  Failures (missing keystore, wrong password, busy port) signal on both
  backends — no nil-on-failure, since there is no WASM variant.
- **`tls-listen-pem` (PEM cert + key)**: takes `cert-file key-file port
  [host]` — a PEM cert chain + an **unencrypted PKCS#8** key (`BEGIN PRIVATE
  KEY`; algorithm detected by trying RSA/EC/DSA/EdDSA `KeyFactory`). PEM
  parsing (`SocketSupport.pemToKeyStore`, exposed via the public
  `TlsPemSupport`) is too big to hand-assemble, so it runs at **parse time**:
  the interpreter reads the files at run time (`SocketSupport.listenTlsPem`,
  paths may be runtime values), while on the compile path the `cli`
  `TlsPemInliner` pre-pass (JVM branch of `RontoLispCli` only, so WASM still
  sees `tls-listen-pem`) parses the (literal-only) paths at compile time,
  serializes the keystore to a Base64 PKCS12 blob and rewrites the call to the
  internal `rontolisp:%tls-listen-p12 base64 password port [host]` built-in.
  `%tls-listen-p12` loads the keystore from a `ByteArrayInputStream` and shares
  `tls-listen`'s SSLContext/server-socket tail (`emitKmfToServerSocket` on the
  JVM); non-literal PEM paths on the compile path are a compile error.
- **Interpreter**: `SocketSupport.connectTls` / `listenTls` / `listenTlsPem` /
  `listenTlsP12`, registered in `Environment` next to the tcp functions; the
  web playground substitution (`Target_SocketSupport`) adds matching
  signal-only methods.
- **JVM**: `_tlsConnect` / `_tlsListen` / `_tlsListenP12` in
  `JvmSocketRuntimeBuilder`, dispatched through `JvmTcpCompiler`. The
  socket-runtime emission gate in `JvmLispCompiler` is `usesSockets` = any
  `tcp-*` OR `tls-connect`/`tls-listen`/`%tls-listen-p12`, so a tls-only
  program gets the full socket runtime (and the stream built-ins grow their
  socket branches).
- **WASM: the CLIENT half is `--component`-only and REAL since todo-410
  (2026-08-16)** — `tls-connect` / `tls-upgrade` are the `tls.lisp` library
  (spliced by `eval/TlsLibrary`) over a wit-imported `wasi:tls@0.3.0-draft`
  (`eval/tls.wit`, vendored from wasmtime v47.0.2's
  `crates/wasi-tls/src/p3/wit`), the sockets.lisp pattern. Run with
  `-S tls=y` on top of the tcp flags. The server half (`tls-listen` /
  `tls-listen-pem` / `%tls-listen-p12`) is a compile error on EVERY WASM
  target **permanently** — the proposal defines only `client.wit`, there is
  no server/accept interface in any draft, and the `WasmExprCompiler`
  message says so ("client-only by design"), deliberately not reading like a
  not-yet. Preview 1 keeps a compile error for the client half too (no
  wasi:tls host API exists for p1; unlike the tcp call-time stubs, no
  spliced library carries a dead `tls-connect` call site — the cl+ssl shim's
  `tls-upgrade` site is prunable). Key mechanics:
  - **The upgrade IS the primitive there** (the mirror image of the JVM):
    `connector.receive` takes the socket's in-flight recv stream and answers
    the cleartext read stream; `connector.send` takes the read end of a
    fresh cleartext pair and answers the ciphertext stream, which goes
    STRAIGHT into `tcp-socket.send` — no guest-side pump (wasmtime's own
    `p3_tls_sample_application` is the reference wiring). `tls-connect` is
    literally `tcp-connect` + `tls-upgrade`.
  - **That wiring is why sockets.lisp DEFERS the send half of its plumbing**
    (`%sock-plumb` takes only `receive()`; `%sock-ensure-tx` makes the write
    pair and calls the at-most-once `tcp-socket.send` on the FIRST write):
    `tls-upgrade` must interpose the transform before the socket's send side
    is committed, so it requires a handle with no prior write (answers `nil`
    otherwise) — the deferral is peer-invisible (nothing went out before the
    first write either way) and atomic (no await between test and store).
  - **The entry is swapped onto the cleartext ends IN PLACE**
    (`%sock-set-streams`), so on this backend `tls-upgrade` answers the SAME
    fd it was given (interpreter/JVM answer a NEW handle over the same
    connection) and every stream built-in / `WasmSocketsRewrite` dispatch
    keeps working on it unchanged — the handshaken handle lives in the same
    `*sock-table*` as a plain socket, the invariant the other backends hold.
    The swap happens BEFORE the handshake await, so a close after a failed
    handshake drops handles the guest owns, never a transferred one.
  - **Error convention**: nil on failure (the tcp/fetch convention);
    `connector.connect`'s err arm is an `own<error>` RESOURCE whose handle
    the handler releases (`%tls-err:error-drop`). **`:insecure` with a
    non-nil value SIGNALS at run time** (the draft exposes no verification
    knob — silently verifying where the caller asked not to, or the reverse,
    is worse than an error; run time because the value is a runtime
    expression, and the cl+ssl shim's verify path passes a literal
    `:insecure nil`, which passes through). Verification runs against the
    host's OWN trust anchors: wasmtime's default rustls provider compiles in
    the webpki (Mozilla) roots — `javax.net.ssl.trustStore` and any CA file
    have no effect, which is why the deterministic E2E pins the REJECTION of
    a self-signed server and the trusted success path is an opt-in test
    against a real host (an IP-SAN certificate, since `tcp-connect` takes
    IPv4 literals only until `.todo/048`).
  - **Splice trigger is textual** (`rontolisp:tls-connect`/`tls-upgrade`
    references, the sockets precedent), and tls.lisp references
    `rontolisp:tcp-connect` + the `%sock` package, so `TlsLibrary.process`
    runs BEFORE `SocketsLibrary.process` in every chain (CLI, test
    pipelines) and fires the sockets trigger itself. Consequence: a
    component program that textually references the client tls names imports
    `wasi:tls` and needs `-S tls=y` even when the calls are dead code
    (tls.lisp is not in `LibraryDefunPruner`'s prunable set).
  - **`wasmtime serve` does NOT host wasi:tls (measured on 47.0.3)**: a
    serve+tls component compiles (the imports ride
    `WasmServeComponentBuilder.additionalImports` like any user interface)
    but fails instantiation with "component imports instance
    `wasi:tls/types@0.3.0-draft` ... resource implementation is missing" --
    serve.rs never links wasi-tls, even with `-S tls=y` on the command line
    (`wasmtime run` does, in run.rs). Not ours to fix: a served handler that
    needs outbound https uses `rontolisp:fetch` (wasi:http, where the HOST
    does TLS). Re-check on wasmtime bumps alongside the WIT diff below.
  - **Re-evaluation trigger (the draft's price)**: `tls.wit` was vendored
    from wasmtime **v47.0.2** and verified against wasmtime 47.0.3 (local)
    and the 47.0.2 test image; the interface is explicitly experimental /
    non-semver ("no patch releases for wasip3 fixes"), so on every wasmtime
    floor bump re-diff `eval/tls.wit` against
    `crates/wasi-tls/src/p3/wit/deps/tls/` at the new tag — a WIT change is
    a file edit here, not compiler work. Also re-check whether the draft
    gained verification/client-cert/ALPN knobs (then the `:insecure` signal
    and the cl+ssl shim gates should be revisited) or a server interface
    (then the "permanent" compile error stops being true).
- **Tests**: `TlsTestSupport` (shared, package `am.ik.rontolisp`) generates
  one self-signed PKCS12 keystore per JVM with the JDK `keytool`
  (CN=localhost, SAN ip:127.0.0.1 + dns:localhost so endpoint identification
  passes on loopback). A TLS handshake needs a live peer — the plain-TCP
  backlog trick does not apply — so the fixture runs the peer on a background
  thread: a one-shot echo `SSLServerSocket` for the `tls-connect` tests
  (client trust via `withTrustStore`, which points `javax.net.ssl.trustStore`
  at the keystore) and a one-shot echo TLS *client* with connect-retry for
  the `tls-listen` tests (port picked up front via `freePort()` — the port
  must be embedded in the program text). `TlsTestSupport.pemFiles()` derives a
  cert.pem + unencrypted-PKCS#8 key.pem from that same keystore in pure Java
  (same cert, so the existing echo client trusts a PEM-configured server
  unchanged) for the `tls-listen-pem` / `%tls-listen-p12` tests. Pinning:
  `LispEvaluatorTest#tls*` (incl. `tlsConnectInsecure*`, `tlsListenPem*`),
  `JvmLispCompilerTest#compileAndRunTls*` (incl. `*Insecure*`,
  `*TlsListenP12*`) / `#compileTlsRejectsWrongArgCount`,
  `WasmLispCompilerTest#tlsConnectIsCompileErrorOnPreview1` /
  `#tlsUpgradeIsCompileErrorOnPreview1` /
  `#tlsClientProgramCompilesAsComponentWithWasiTlsImports` /
  `#tlsFreeSocketComponentImportsNoWasiTls` /
  `#tlsListenIsCompileErrorOnEveryWasmTarget` /
  `#tlsListenPemIsCompileErrorOnEveryWasmTarget`. The component E2E cannot use
  this fixture (wasmtime's rustls trusts only its compiled-in webpki roots):
  `WasmLispCompilerIntegrationTest#componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer`
  pins the untrusted-rejection path against an in-container `openssl s_server`
  (a REAL handshake attempt, deterministic), and
  `#componentTlsFetchesARealHostOverHttps` (opt-in `RONTOLISP_HTTP_E2E=1`)
  pins the trusted success path against 1.1.1.1's IP-SAN certificate.
- **Examples**: `examples/net/https-hello.lisp` and `examples/net/kv-server-tls.lisp`
  are the TLS twins of `http-hello.lisp` / `kv-server.lisp` (only the listen
  call differs; both headers carry the keytool one-liner that generates
  `tls-server.p12`).

## The retired core seam (deleted)

Core function indices 8-11 were the old `sock.*` adapter seam. The seam is
GONE (sockets are a wit-imported Lisp library now); the four never-called trap
stubs that briefly preserved the old indices were collapsed too, so `FUNC_START`
is `IMPORT_FUNC_COUNT` (8) and defined functions follow the imports directly.

## The component mechanics (sockets.lisp over wasi:sockets@0.3.0)

The old third blob set (`uni-sockets.wit` / `core-sockets.wat` /
`adapter-sockets.wat`) and `WasmComponentBuilder.buildSock` are DELETED. A tcp
program is the BASE variant plus one appended user import
(`appendUserImports`), exactly like fetch -- which is why fetch+tcp and
serve+tcp now compose in one component (the old compile errors are gone).

**serve+tcp RUNS under `wasmtime serve` -- but only with `-S cli=y`** (fixed
and verified 2026-07-17): without `-S cli=y` the serve linker reports
"instance export `tcp-socket` has the wrong type: resource implementation is
missing" at instantiation, an error that reads like a missing host feature
and produced a WRONG host-blame diagnosis for a day (`wasmtime run` needs no
such flag). With `-S cli=y -S tcp=y -S inherit-network=y` the component
instantiates and the tcp calls work. The trap that remained after the flag
("cast failure" on the first tcp call) was OURS -- the serve top-level-init
bug below -- and is fixed; runtime coverage is
`httpHandlerConnectsTcpUnderWasmtimeServe` (the compile-level
`httpHandlerWithTcpCompilesInServeMode` proved unable to catch a runtime
composition failure). wasmCloud `wash dev` (2.5.2) hosts the component AND provides
`wasi:sockets@0.3` -- its "Host provides interfaces" startup log is a stale
hardcoded list naming 0.2 only; do not read it as the feature set. What
differs from wasmtime: a loopback destination is routed to a per-workload
in-memory virtual network, never the machine's real 127.0.0.1, so the old
probe's connect to a host-side listener got `:connection-refused` (hence
`nil`), while non-loopback destinations use the real network and work.
http-api's `POST /task` 500 was the nil sock reaching `(read-line nil)` -- a
cast-failure TRAP, uncatchable on wasm-GC (handler-case sees signaled
conditions only). Both halves run inside one `wash dev` when service-leet is
compiled `--component` (`wasi:cli/run@0.3.0` = wash's v2 service shape) and
registered as `.wash/config.yaml` `dev.service_file` -- but the listen host
must be an explicit `"127.0.0.1"`: wash's p3 bind check rejects the
`tcp-listen` default 0.0.0.0 before its loopback rewrite. Full mechanics and
the verification log: `.todo/145`.

**The serve top-level-init bug (fixed 2026-07-17)**: a serve component never
lifts `run`, and after the hand-written serve adapter died (which used to run
`run` once as init) NOTHING executed the program's top level -- every
defvar/defparameter global (user or spliced-library) read back null inside a
served handler, and the first arithmetic on one trapped with "cast failure"
(sockets.lisp's `(+ rontolisp::*sock-next-fd* 1)` in `%sock-register` was how
it surfaced). Fix: the `handle` wrapper (`WasmExportCompiler.emitBody`) runs
`_start` once per instance under a new serve-only `(mut i32)` init flag
(`serveInitGlobalIndex`, appended after every other global -- non-serve
output is byte-identical), inside the handle call's task context, so a
top-level suspension drives through the blocking event loop exactly as under
`wasmtime run`. Pinned by `httpHandlerReadsATopLevelGlobalUnderWasmtimeServe`.

**"Once per instance" is a HOST decision, and the hosts differ**
(measured 2026-07-27 with `examples/db/postgres-web.lisp`, a served handler over
a top-level PostgreSQL connection; Spin added 2026-07-30 with a top-level
`defvar` hit counter; the wasmtime line corrected 2026-08-04, todo-259):
**`wasmtime serve` and Spin both do BOUNDED reuse -- 128 requests per instance,
the same number because Spin inherits wasmtime's default.** `wasmtime serve
--max-instance-reuse-count` documents it outright ("defaults to 1 for WASIp2
components and 128 for WASIp3 components"), and it reproduces: 20167 requests
against a handler that prints at top level AND in the handler logged 165
top-level runs (~122 requests each; instances retire early under concurrency),
while 20 sequential curls logged exactly ONE. Spin's own knob is the same flag
(`DEFAULT_WASIP3_MAX_INSTANCE_REUSE_COUNT` in its `trigger-http`), verified by
the counter climbing 1..128 and then resetting on a visibly newer instance.
wasmCloud `wash dev` (2.5.2) is the third point: each request gets a FRESH
instance, so the top level runs again every request and its side effects repeat
(`--max-instance-reuse-count 1` reproduces that shape under wasmtime exactly).
**Re-verified 2026-08-04 on wash 2.6.1** with the `defvar` counter: it reads 1 on
every request, no exceptions -- the fresh-instance row stands.
**Install wash with `curl -fsSL https://wasmcloud.com/sh | bash`** (it resolves
`wasmcloud/wasmCloud`'s `releases/latest`). Do NOT go looking for the binary by
tag: that repo's wash releases are tagged plain `vX.Y.Z` (v2.6.1), while the
SEPARATE `wasmCloud/wash` repo is stuck at `wash-v2.0.0-rc.7` -- and rc.7/rc.8
advertise `wasi:http@0.2.0` ONLY and reject a serve component during interface
extraction ("`stream` requires the component model async feature"), before
`dev.wasm_proposals` can apply. 2.6.1 lists `wasi:http/handler,types@0.3.0`
among its host interfaces and runs the component; an rc.x binary looks exactly
like a rontolisp regression and is not one.
A `drop table` + `create table` startup pair therefore emptied the table on each
wasmCloud request while accumulating normally under wasmtime -- the example uses
`create table if not exists` for exactly this reason. **The earlier reading
"`wasmtime serve` keeps ONE instance for the whole server run" was a
small-sample artifact**: the probe made far fewer than 128 requests. Under load
a global there neither persists for the run nor resets every request, which is
the WORST case for a program that assumes either.
`--max-instance-concurrent-reuse-count` (default 16 for a p3 component, on
wasmtime serve and Spin alike) additionally lets 16 requests share one instance
concurrently; wash does not.
The per-instance cost of that lifetime is real and is why serve mode pre-grows
a smaller GC heap -- `.kb/wasm-gc-heap-pregrow.md`.
The rule for a served program: treat top-level side effects as
idempotent-or-per-request, and keep durable state in the store, not in a global.
External state (a PostgreSQL row) survives; a defvar does not.

- **Plumbing (sockets.lisp `%sock-plumb`, the old adapter's `$plumb` in
  Lisp)**: at connect/accept time `receive()` yields the recv stream (its
  future dropped immediately -- EOF is the stream status), `sock-stream-new`
  makes a pair whose read end goes to `send()` (called at most once) while
  the write end stays in the entry for the write built-ins; `%io-close` drops
  the write end (FIN), the recv stream and the resource. IPv4 literals are
  parsed in Lisp (`%sock-parse-ipv4`); hostname lookup
  (`wasi:sockets/ip-name-lookup`) is still not wired.
- **connect** is an `async func` -> the binding is an async-defun over
  `%subtask-future`; `listen` returns `result<stream<tcp-socket>, ...>` and
  **accept = one element read of that stream** through the `accept-stream`
  alias built-in -- the ONE non-u8 stream lift: `validateAsyncAlias` admits
  resource-handle elements (read/drop-readable only), `emitStreamRead` reads
  ONE 4-byte element and lifts the opaque handle (registry kind 2; the
  scheduler's `_sched_dispatch` lifts it with `i32.load` instead of
  `_str_from_mem`).
- **The async-lower param SPILL**: wasmtime spills async-lowered params past
  4 flats (`MAX_FLAT_PARAMS_ASYNC`; the sync cap is 16) -- `connect`'s self +
  flattened `ip-socket-address` is 14 -- so `FlatSig` carries
  `spilledParams`/`paramsAreaSize`, `WitCanonicalAbi.spillLayout` computes the
  tuple layout and `emitAsyncStartBody` stores each argument via `emitLowerAt`
  into one allocated area, passing `(argptr[, retptr])`.
- **The two surfaces**: the sync tcp defuns force their async internals with
  `rontolisp::%future-force` (a thin binding of `_sched_loop`, the blocking
  scheduler drive -- OFF_SCHED_LOOP); ASYNC bodies instead get the
  await-shaped promotion from `WasmSocketsRewrite` (component-only, runs in
  `WasmLispCompiler.compile()` right after the async-sugar rewrite, gated on
  the spliced `%io-read-line` defun, mirroring `LispAsync`'s context rules):
  `(read-line s)` -> `(rontolisp:await (rontolisp::%read-line-future s))`,
  and `tcp-connect`/`tcp-accept` promote onto `%tcp-connect-f`/`%tcp-accept-f`
  (which carry the nil-on-failure convention, so both surfaces agree). A
  PENDING accept/read therefore no longer stalls the instance -- a concurrent
  `wait-for` timer fires while an accept waits (the promotion goal of the
  sockets/stdin canon-lower migration). In sync
  context the same calls are rewritten onto the `%io-*` dispatch defuns
  (`read-line`/`read-char`/`read-byte`/`write-line`/`write-byte`/
  `write-string`/`close`), which branch: socket handle -> the Lisp entry's
  chunked stream reads / blocking `sock-stream-write`; a nil (stdin) READ
  designator -> the `%stdin-*-or-raw-f` helpers (stdin.lisp's wit-imported
  stdin machinery, or its serve-mode raw-passthrough stub -- `StdinLibrary`
  splices one of the two whenever sockets.lisp is spliced; the stdin
  mechanics live in `.kb/read-load-streams.md`); anything else -> the
  `%...-raw` internal aliases of the NATIVE built-ins (no rewrite recursion).
  A wrong-arity call is left unrewritten so it errors under its public name.
- **The chunk buffer walks BYTES, never characters (todo-177's root cause)**: a
  GC string's `$str_bytes` hold UTF-8 and the character accessors
  (`length`/`char`) decode them, but a socket chunk's bytes ARE the wire --
  binary data that happens to form a valid multi-byte sequence (a PostgreSQL
  BackendKeyData secret) collapsed into one char under the old char-based
  cursor, shifting the rest of the stream and hanging the driver on a read for
  bytes it had already swallowed. sockets.lisp therefore keeps a BYTE cursor
  over the chunk -- since todo-370 the `stream<u8>` read lifts the chunk as a
  packed `(unsigned-byte 8)` vector (`.kb/wit.md`), so the cursor is plain
  `length`/`aref`; before, the chunk was a byte string walked through the
  component-only `rontolisp::%str-byte-length` / `%str-byte-ref` intrinsics
  (`WasmStrByteCompiler`), which survive for the WRITE side together with
  `rontolisp::%str-from-byte` (a one-content-byte string, valid UTF-8 or not,
  which the raw-`$str_bytes` write path puts on the wire as exactly that byte
  -- `write-byte` of a value >= 128 used to emit a TWO-byte UTF-8 sequence,
  invisible in rontolisp-to-rontolisp loopback because the read side decoded it
  back). `read-byte` pops one wire byte; `read-line` accumulates bytes (so a
  UTF-8 line decodes exactly like the interpreter's byte-collecting readLine,
  chunk boundaries included); `read-char` assembles one UTF-8 sequence
  byte-wise (refills mid-sequence work). Pinned by
  `componentTcpBinaryBytesAreWireTransparent`.
- **Sequence ops and the eof-tolerant reads (cl-postgres' surface)**:
  `(read-sequence seq s [:start a] [:end b])` / `(write-sequence seq s [:start
  a] [:end b])` and `(read-byte|read-char|read-line s eof-error-p [eof-value])`
  are rewritten onto `%io-read-sequence` / `%io-write-sequence` /
  `%io-read-byte-eof` / `%io-read-char-eof` / `%io-read-line-eof` (sync context)
  or promoted onto the matching `%...-future` (async context; writes never
  promote). The bounds travel as POSITIONAL arguments (`nil` = "unspecified",
  normalized to `0` / `(length seq)` inside the dispatch defun, against the same
  sequence both arms see). A non-socket designator falls through to the native
  expansions via the `%read-sequence-raw`/`%write-sequence-raw` aliases and the
  3-arg `%read-byte-raw`/`%read-char-raw`/`%read-line-raw`, byte-identical
  semantics to the unrewritten forms -- this is what the earlier reverted attempt
  got wrong (it widened `%io-read-byte` itself and broke a top-level file read at
  EOF in the ci-spec corpus). The eof arms signal the same `end-of-file` class
  the native lowering does (`LispMacroExpander.endOfFileSignal`), so
  `handler-case` behaves identically whether or not the designator turned out to
  be a socket. Socket write-sequence elements are BYTES (integers), like the
  interpreter's per-element `write-byte` expansion -- except for a STRING
  sequence, which goes out as its own characters (what the native
  `write-sequence` expansion does for a string). Pinned by
  `componentTcpSequenceOpsReachTheSocketDispatch` and
  `componentTcpGrayStreamsFallThroughShapesReachTheSocketDispatch`.
- **EVERY shape a socket-capable built-in accepts needs an entry in
  `WasmSocketsRewrite`, not just the shapes user code tends to write (todo-263)**:
  unrewritten, these calls compile to the NATIVE stream built-ins whose
  `fd_read`/`fd_write` on a socket fd (>= 200) walks off the preview1 adapter's
  64-slot fd table -- the `unknown handle index 0` trap, and it fires MID-MESSAGE
  (the server logs `incomplete message from client`), so it reads like a dropped
  `wasi:io` stream resource rather than a missing rewrite. The reason the exotic
  shapes are load-bearing is pass ORDER: `GrayStreamsLibrary.process` runs BEFORE
  this rewrite and normalizes every possibly-CLOS-instance stream call site onto
  its `%gray-*-dispatch` helper, whose non-instance fall-through arm re-spells
  the built-in with EVERY optional argument filled in -- `(write-sequence seq s
  :start start :end (if end end (length sequence)))`, `(read-line s eof-error-p
  eof-value)`, ... So in any Gray-protocol program -- which is every
  `(ql:quickload "cl-postgres")` program -- the keyworded / eof-carrying shape is
  what the socket call ACTUALLY is. That gap kept the four `--component` legs of
  `ClPostgresE2eTest` `@Disabled`: cl-postgres' `write-bytes` does
  `(write-sequence bytes socket)`, the Gray arm turned it into the keyworded
  form, and only the 2-arg form had a dispatch target. Two shapes have no
  dispatch defun of their own and are LOWERED inside the rewrite instead, onto
  the plain `write-string` it does cover: `(write-char c [s])`
  (`LispMacroExpander.expandWriteChar`) and a bounded `(write-string s stream
  :start a :end b)` (`lowerWriteStringBounds`) -- both already had that lowering,
  but it ran at `WasmExprCompiler` time, i.e. AFTER this pass, which is exactly
  why they reached the native built-in. An unrecognized shape (a non-literal
  keyword, a stray positional) is deliberately left UNREWRITTEN so the error
  names the built-in the program wrote. Shape table pinned by
  `WasmSocketsRewriteTest`; `expandReadLineCompat` accepts the
  `rontolisp::%read-line-raw` alias for the same reason (the alias has to admit
  every shape the public name does, or the fall-through arm is a compile error).
- **The rewrite has TWO alternative dispatch providers, and widening one without
  the other breaks every program on the other** -- sockets.lisp, and
  `stdin-dispatch.lisp` for an async component that reads stdin but never touches
  a socket (`StdinLibrary`, `.kb/read-load-streams.md`). Exactly one is spliced,
  the rewrite keys on the same `%io-read-line` marker either way, and it picks its
  target by call SHAPE without knowing which file it got -- so a name or an
  argument shape only one file defines is `the function RONTOLISP::%IO-... is
  undefined` (or an arity error) for the other splice. The bounded sequence ops
  and the eof-carrying read-char/read-line landed in sockets.lisp first and broke
  the whole `ci-spec.yaml` corpus's `--component` leg, which `./mvnw test` does
  NOT catch (`CiSpecE2eTest` needs `-Drontolisp.binary`); the same omission had
  already cost `%io-listen`, missing from stdin-dispatch.lisp since it was
  written. `StdinLibraryTest#theTwoDispatchSplicesDefineTheSameNamesAndShapes`
  compares the two name -> (required/optional) maps so the next widening cannot
  land in one file only.
- **`write-string` / `write-char` / `read-char` on a socket: real on all four
  backends since todo-264** (they were component-only, through
  `%io-write-string` / `%io-read-char`, until 2026-08-06). The interpreter and
  the JVM grew the matching arms (see their bullets above); `write-char` needed
  none of its own anywhere, because it lowers to `write-string` on every backend
  (`LispMacroExpander.expandWriteChar`), and neither did the bounded
  `write-string ... :start :end` (`lowerWriteStringBounds`). All three
  interpret a socket read/write as BYTES on the wire, so `read-char` assembles
  ONE UTF-8 sequence byte-wise -- never through a `Reader`/decoder that would
  buffer ahead and swallow bytes a following `read-byte`/`read-line` owes the
  caller -- and an invalid lead byte stands alone and decodes to U+FFFD on all
  three. The cross-backend pin is one program answering
  `(65 195 135 66 504 90 121)` on the interpreter
  (`LispEvaluatorTest#tcpCharacterOpsOnSocket`), the JVM
  (`JvmLispCompilerTest#compileAndRunTcpCharacterOpsOnSocket`) and the component
  (`componentTcpBinaryBytesAreWireTransparent`, the same wire bytes).
  **A bare `(read-char sock)` / `(read-byte sock)` AT PEER CLOSE signals
  `end-of-file` on all three** (CL's default `eof-error-p` t, the same contract
  their file streams have, and the same the component's own non-socket
  designators have). The component was the odd one out until 2026-08-06 -- its
  socket reads all answer nil at EOF and the bare shapes handed that through --
  and the fix had to land on BOTH entries a bare shape reaches: the
  async-promoted `%read-char-future` / `%read-byte-future` (whose socket arms now
  turn a nil read into `(error 'end-of-file)`) and the sync `%io-read-char` /
  `%io-read-byte`, which inherit it by forcing those same futures. The nil
  convention stays BELOW those entries, in `%sock-read-char-f` /
  `%sock-read-byte-f`, because `%read-char-eof-future` /
  `%read-byte-eof-future` need the raw nil to apply the CALLER's eof arguments
  -- `(read-char sock nil :eof)` is `:eof` on all three, the shape the
  Gray-streams fall-through arm and every real driver spell. `read-line` is the
  one read that still answers nil at peer close everywhere, and only because
  rontolisp's `read-line` defaults `eof-error-p` to nil (the todo that closed
  this gap scoped only `read-char`; `read-byte` had the identical divergence,
  from the identical hand-through, and was fixed in the same pass rather than
  left to re-trap). Pinned by
  `LispEvaluatorTest#tcpReadCharAtPeerCloseHonoursTheEofArguments`, its JVM twin
  `compileAndRunTcpReadCharAtPeerCloseHonoursTheEofArguments`, and
  `componentTcpBareReadCharSignalsAtPeerClose` (which pins the sync defun body
  and the async top level separately -- they take different dispatch entries).
- **The two rewrites MEET in an async body, and the `%` prefix is a naming
  convention there, not a marker of specialness**: a write is never promoted,
  so it takes the `%io-*` dispatch head even in async context, while a read in
  its arguments becomes an await -- `(write-line (read-line s))` (the
  echo-client shape) is `(%io-write-line (await (%read-line-future s)))`.
  `WasmAwaitNormalizer` therefore has to hoist that await out of a `%`-named
  head's argument, which it refused to do while it read any `%` member as one
  of the internal forms with non-value positions (`%block`, `%error`, ...) --
  so the example failed to compile with "await in this position", while the
  identical `(princ (read-line s))` compiled because `princ` keeps its name.
  `WasmSocketsRewrite.strictDispatchMembers()` is what tells the normalizer
  these particular `%` heads are ORDINARY defuns; keep the two in step when
  adding a dispatch defun. (Pinned by `WasmLispCompilerTest`
  `promotedSocketReadHoistsOutOfADispatchDefunArgument`.)
- **EH/async run flags**: sockets.lisp carries async-defuns and handler-case,
  so a component tcp program is asyncMode + EH mode -- run with
  `-W gc=y -W exceptions=y -S tcp=y -S inherit-network=y`. Unlike wasi:http
  (absent without `-S http=y`, failing instantiation), wasmtime always hosts
  wasi:sockets and gates it by permission: without the `-S` flags the
  component instantiates and socket calls return errors -> `nil`
  (`componentTcpWithoutNetworkFlagsReturnsNil`).
- **print-family to a socket** is NOT wired on the component (the documented
  socket surface is the stream built-ins above); `(format nil ...)` +
  `write-line` is the pattern, and what every example uses.

## The address accessors (`tcp-local-address` / `tcp-peer-address` / `tcp-peer-port`)

Added for the usocket shim's `get-local-*`/`get-peer-*`. Interpreter:
`SocketSupport.localAddress`/`peerAddress`/`peerPort` (null/-1 for a
wrong-kind entry → `Environment` signals). JVM: `JvmSocketRuntimeBuilder`'s
`_tcpLocalAddress`/`_tcpPeerAddress`/`_tcpPeerPort` — the address returners
quote-frame the string (the `_sockReadLine` `"\"".concat(...)` tail). WASM:
**REAL in component mode since the sockets.lisp migration** — sockets.lisp
reads `get-local-address`/`get-remote-address` (an `ip-socket-address`
variant→record→tuple lift) and formats the dotted quad in Lisp; a wrong-kind
handle or a host error yields `nil` (the component's error convention), so
spliced usocket programs keep compiling AND now report real peers. `tcp-local-port`
reads the real bound port the same way (ephemeral `tcp-listen 0` included).
Preview 1 lowers to the call-time error like the other tcp built-ins.

## Read deadlines: `rontolisp:tcp-set-timeout` and the usocket timeout/wait decisions

`(rontolisp:tcp-set-timeout handle milliseconds)` (nil clears; non-negative
integer ms, the `wait-for` convention) sets a per-socket READ deadline;
`(setf (usocket:socket-option s :receive-timeout) seconds)` — the portable
usocket spelling every client uses, dexador on every connection — rides it.
The decisions and their reasons, taken together (they were one todo):

- **Interpreter/JVM: REAL**, via `Socket.setSoTimeout`
  (`SocketSupport.setTimeout` / `JvmSocketRuntimeBuilder._tcpSetTimeout`,
  gated by the same `usesSockets`). A timed-out read then throws
  `SocketTimeoutException` out of the ORDINARY stream built-in it happened in,
  so it surfaces as a catchable plain `error` ("read-line: Read timed out"),
  NOT as `usocket:timeout-error` — the reads do not go through usocket
  functions and per-read wrapping in the shim would tax every socket program;
  documented lite edge on the doc pages. The deadline lives on the raw
  socket, so it keeps governing a `tls-upgrade`d connection. Listener handles
  are rejected (a read deadline; an accept deadline has no consumer).
- **WASM component: the primitive SIGNALS at call time** (a sockets.lisp
  defun) — wasi:sockets@0.3.0 has no receive-timeout knob and the stream
  reads are futures with no deadline argument; a silent no-op would install
  the exact failure mode (a hang) the client set the timeout to avoid, the
  cl+ssl "what has no backing SIGNALS" rule. Consequence for dexador: its
  DEFAULT `:read-timeout 10` dies at connect time on the component until
  either the caller passes `:read-timeout nil` (dexador guards the setf with
  `(when read-timeout ...)`) or the scheduler grows a future-race/deadline
  primitive — `.todo/415`, which is also what would make `wait-for-input`
  real there. Preview 1: the call-time stub like every tcp built-in.
- **`usocket:socket-option`** supports `:receive-timeout` ONLY; every other
  option signals naming the option (never accept-and-ignore — even
  `:tcp-nodelay`, although `socket-connect`'s `:nodelay` KEY is still
  accepted-and-ignored, the shim's pre-existing connect-knob convention). The
  GETTER answers from shim-side bookkeeping (`usocket::*%usock-timeouts*`, an
  alist the setf maintains; the shim is the only writer so it is
  authoritative; entries survive socket-close — a handle is never reused).
  The setf converts seconds→ms and calls the primitive through
  `%usock-guard`, so an interpreter/JVM failure is a typed
  `usocket:socket-error` and the component's signal passes through raw; the
  refusal happens BEFORE the bookkeeping records anything.
- **`usocket:wait-for-input` is a `listen`-based poll**, pure Lisp in
  usocket.lisp: compute the ready set via `listen` (the kernel receive buffer
  on interpreter/JVM; the chunk readahead buffer on the component — listen's
  own documented divergence), and when none is ready poll every 10 ms
  (`sleep 0.01`) until `:timeout` (0 = one poll; none = forever). Upstream's
  two values are honoured (`:ready-only`, remaining time). **On the WASM
  backends it instead returns IMMEDIATELY claiming readiness** (branching at
  RUN time on `(member :rontolisp-wasm *features*)` — the shim source is
  parsed once for all backends, so a reader feature cannot branch it, but the
  runtime `*features*` list is seeded per backend): the two candidate
  behaviors there are both wrong for someone, and the degenerate claim is
  wrong for strictly fewer — the dominant wait-then-read loop behaves
  IDENTICALLY (reads block anyway), while an honest poll would sleep-spin
  forever on data waiting host-side that component `listen` cannot see.
  `:ready-only` claims the full list in that case. Stream sockets only (a
  listener probes through `listen`, which signals on it — no backend has a
  non-blocking accept probe); wait-list objects are not reproduced.
- **`listen` on Preview 1 became a CALL-time error** (was a compile error) as
  part of this: the shim is spliced UNPRUNED into every usocket program
  (`LibraryDefunPruner` deliberately excludes it), so wait-for-input's listen
  call site is dead code that must build — the todo-195 policy, the same
  transition tcp-connect made. Consequence: a cl-postgres/postmodern/mito
  program now COMPILES on Preview 1 and fails loudly at its first socket call
  at RUN time (their `failsToCompileOnWasmPreview1` pins became
  `preview1ModuleCompilesAndFailsLoudlyAtTheFirstSocketCall`). This does NOT
  decide `.todo/405` (a real P1 probe); it only moves the refusal to call
  time uniformly.
- **The WaitForLibrary trigger widened to any usocket reference** (the
  SocketsLibrary precedent): the shim carries an unconditional `sleep` call
  site the component must resolve against wait.lisp, and the usocket splice
  runs AFTER WaitForLibrary in every pipeline — so the trigger has to fire on
  what is visible before the splice. Every component usocket program now
  imports wasi:clocks. Non-CLI component pipelines (tests) must run
  `WaitForLibrary.process` in their chain or the compile fails on the shim's
  `sleep`.
- **`socket-server` (todo-114 Tier 2) was split out, not shipped as the
  keys-ignored sketch**: silently ignoring `:in-new-thread` blocks a caller
  that expects to continue — the same lying-no-op shape the timeout decision
  rejects — and since todo-227 the thread primitives exist on
  interpreter/JVM, so an honest implementation is possible and is its own
  item (`.todo/416`, incl. the WASM story for a shim that would then
  reference `rontolisp:make-thread`).

Pinned by `LispEvaluatorTest#usocketSocketOptionReceiveTimeoutIsARealReadDeadline`
/ `#usocketSetfSocketOptionAsTheFirstReferenceLoadsLibraryAndSignalsTyped` (the
interpreter's lazy-load third trigger, `ensureUsocketSetfPlaceLoaded` — a
program whose FIRST usocket touch is the setf place) /
`#usocketWaitForInputPollsThroughListen`, their JVM twins
(`compileAndRunUsocket...`), `WasmLispCompilerTest#listenInPreview1ModeIsACallTimeError`
/ the widened `#tcpBuiltinsInPreview1ModeAreCallTimeErrors` /
`#tcpBuiltinsCompileInComponentMode`, and
`WasmLispCompilerIntegrationTest#componentUsocketSocketOptionRefusesAndWaitForInputClaimsReadiness`.

## The usocket shim (`usocket` package, `usocket.lisp` + `UsocketLibrary`)

A usocket-compatible API over the tcp built-ins, targeting the surface
Postmodern's cl-postgres uses (`socket-connect` + `:element-type` +
`socket-stream`). The linalg/vec Lisp-source-library pattern:
`src/main/resources/am/ik/rontolisp/eval/usocket.lisp` (canonical package
shape, resolver fixed point pinned by
`PackageResolverTest#usocketLibraryFormsAreAResolverFixedPoint`) +
`eval/UsocketLibrary` (cached `forms()`, Walker detection, `process()`
splice). Key mechanics:

- **A socket IS its handle**: `socket-stream` = identity, `socket-close` =
  `close`; `socket-listen` flips usocket's host-first order onto `tcp-listen`
  and must OMIT the host argument for the wildcard case (`tcp-listen` rejects
  an explicit nil host). `socket-connect` rejects `:protocol :datagram`;
  every entry point takes `&key ... &allow-other-keys` and ignores the
  compatibility knobs.
- **Variables** `usocket:*wildcard-host*`/`*auto-port*` are library
  defparameters, so `LispEvaluator.evalSymbolRef` has a variable-read
  lazy-load hook in addition to the `resolveFunction` one (a program whose
  FIRST usocket reference is a variable read; function-call-first programs
  work through the function hook because `evalCons` resolves the function
  before evaluating arguments). `boundp`/`symbol-value` are NOT hooked (lite
  edge).
- **`get-local-name`/`get-peer-name`** end in a literal `(values address
  port)`, which flows through the multiple-values tier's user-function
  channel, so `multiple-value-bind` receives both values on every backend.
- **`host-to-hostname`/`get-host-by-name`** (todo-226, for clack's
  `clack.handler:run`, which normalizes its `:address` through the pair before
  handing it to a backend handler) are pure Lisp over `format`/`elt`/`ash`, so
  unlike everything else in the shim they answer on Preview 1 too.
  `host-to-hostname` is REAL for every designator shape upstream accepts (nil →
  `"0.0.0.0"`, string → itself, vector quad / list of four octets and a
  host-byte-order 32-bit integer → the dotted quad). `get-host-by-name` is
  LITE -- it renders through `host-to-hostname` instead of resolving, because
  **rontolisp has no name-resolution primitive on any backend**; the composite
  is then an identity on the address, and the `tcp-connect`/`tcp-listen` the
  address eventually reaches resolves it inside the host anyway.
  **Why it is not resolved on the interpreter/JVM, where `InetAddress` could**:
  that would buy nothing (both sides hand the result straight back to a socket
  call that resolves it regardless) and would cost a real backend divergence in
  a library that is SPLICED INTO EVERY socket program. Re-evaluation trigger:
  the day a cross-backend resolver exists -- `.todo/048` (wiring
  `wasi:sockets/ip-name-lookup@0.3.0`) is what that waits on -- this should
  return upstream's real vector quad on all four, not on two of them. Pinned by
  `LispEvaluatorTest.usocketHostToHostname*`,
  `JvmLispCompilerTest.compileAndRunUsocketHostToHostnameRendersEveryDesignatorShape`
  and `WasmLispCompilerIntegrationTest.usocketHostToHostnameRendersEveryDesignatorShape`.
- **The four `with-*` macros** (`with-client-socket`, `with-connected-socket`,
  `with-server-socket` (alias), `with-socket-listener`) are built-in
  `LispMacroExpander.expandUsocketWith*` expansions (the
  `rontolisp:with-arena` pattern: dispatched on the qualified name in
  `LispEvaluator.evalCons` + `Jvm/WasmExprCompiler`, registered only as
  usocket package externals, no CL_MACROS entry). Since todo-116 the
  expansions are backend-parameterized: interpreter/JVM wrap the body in
  `unwind-protect` (close on EVERY exit); the WASM call sites pass
  `unwindProtect = false` and keep the close-after-body shape (`let` +
  result-var + close; `unwind-protect` does not compile there).
- **Built-in ASDF system**: `eval/BuiltinSystems` maps `"usocket"` →
  `UsocketLibrary::forms`. `LoadInliner.spliceSystem` splices the forms (NOT
  mark-only — an `:import-from :usocket` + bare-name consumer would evade the
  Walker, which runs before `PackageResolver`) and the quickload branch skips
  `downloadQuicklisp`; the interpreter's `loadSystem`/`quickload` short-circuit
  to `ensureUsocketLoaded()`. `UsocketLibrary.process` has a dedup guard
  (skip when the program already carries `(defun usocket:socket-connect ...)`)
  so the outer pre-pass never prepends a second copy after the LoadInliner
  hook spliced one.
- **Chain wiring**: `RontoLispCli` (outermost `UsocketLibrary.process`),
  `RontoPlayground` (both compile paths), corpus tests,
  `AsdfLibraryE2eSupport`, native-image `resource-config.json`.
- **Typed conditions (todo-116)**: usocket.lisp defines the condition
  hierarchy (`socket-condition` with a `message` slot + echo `:report`,
  `socket-error`, `connection-refused-error`, ...) and wraps
  `socket-connect`/`socket-listen`/`socket-accept` in the internal
  `(usocket::%usock-guard form)` — expanded per backend
  (`LispMacroExpander.expandUsocketGuard`): interpreter/JVM = `handler-case`
  + `usocket::%usock-resignal` (re-signals as `usocket:socket-error`, message
  preserved so uncaught output is unchanged), WASM = pass-through (the shim
  source is parsed once and cached for all backends, so the branch cannot be
  a reader feature). The re-signal always uses `socket-error` (the subtypes
  are defined but not auto-selected). See `.kb/error-handling.md`.
- **Not reproduced** (rontolisp has no substrate): UDP
  (`socket-send`/`socket-receive`), `socket-shutdown`, wait-list objects, and
  `socket-server` (split out to `.todo/416` -- see the read-deadlines section
  above; `socket-option` and `wait-for-input` ARE reproduced there).
  (Restart-based retry -- `handler-bind`/`restart-case` -- IS available since
  todo-196; usocket.lisp simply does not use it.)

## The cl+ssl shim (`cl+ssl` package, `cl-ssl.lisp` + `ShimLibraries`)

Todo-399. Every CL HTTP client (dexador, drakma, any usocket+cl+ssl stack)
reaches TLS through cl+ssl, and the real cl+ssl is a CFFI binding to OpenSSL
— unloadable here (cffi's `.asd` errors "Sorry, this Lisp is not yet
supported"). The shim is the CLIENT side only, over `tls-upgrade`
(interpreter, JVM and the WASM component since todo-410 — the WASM mechanics,
incl. the run-time `:insecure` signal the shim's verify-none path surfaces,
are in the TLS section above; Preview 1 keeps the compile error). The
`flexi-streams.lisp` pattern: a canonical-shape resource next to
`ShimLibraries`, the package seeded in `PackageRegistry`, the system in
`BuiltinSystems` + `ShimLibraries.RESOURCES` + the native-image
`resource-config.json`. Key decisions:

- **`make-ssl-client-stream stream :hostname h [:verify v] ...`** is
  `(rontolisp:tls-upgrade stream h :insecure (if v nil t))`; `:verify`
  defaults from `(ssl-check-verify-p)` exactly like upstream, which consults
  the `with-global-context`-bound internal `*ssl-global-context*` (a context
  IS its recorded `:verify-mode`; `+ssl-verify-none+` 0 / `+ssl-verify-peer+`
  1 as upstream). That is the whole path a client's insecure knob takes:
  dexador's `dex:*not-verify-ssl*`/`:insecure` becomes `make-context
  :verify-mode +ssl-verify-none+` plus `:verify nil`, and both spellings land
  on the primitive's `:insecure`.
- **`:hostname` is REQUIRED** (it is what the certificate is verified
  against, and the SNI); nil signals rather than silently skipping
  verification.
- **What has no backing SIGNALS, never accept-and-ignore**: client
  certificates (`:key`/`:certificate`/`:password`,
  `use-certificate-chain-file`) — silently unauthenticated is worse than a
  message — and `make-context :verify-location` CA paths (only `:default`/nil
  pass; the message names the `javax.net.ssl.trustStore` properties, which
  the fresh-per-call `SSLContext` re-reads). Re-evaluation trigger: wire
  `:verify-location` up the day the primitive can take a CA path, and client
  certs the day `tls-upgrade` grows a client identity.
- `with-global-context` is an ordinary shim `defmacro` (unlike the usocket
  `with-*`s, which predate shim defmacros and carry a per-backend
  unwind-protect split); `ensure-initialized` is a no-op returning t.
- dexador's exact `(:import-from :cl+ssl ...)` list is the export set;
  `*ssl-global-context*` stays internal, as upstream.

## Pinning tests

`LispEvaluatorTest#tcp*` (incl. `#tcpCharacterOpsOnSocket` /
`#tcpReadCharAtPeerCloseHonoursTheEofArguments`) / `#usocket*` / `#tlsUpgrade*`
/ `#clSslShim*` (the https-over-a-local-TLS-server pins, incl. the
verify-none-context insecure path and the signal-on-no-backing gates),
`JvmLispCompilerTest#compileAndRunTcp*` (incl.
`#compileAndRunTcpCharacterOpsOnSocket` /
`#compileAndRunTcpReadCharAtPeerCloseHonoursTheEofArguments`)
/ `#compileTcpRejectsWrongArgCount` / `#compileAndRunUsocket*` /
`#compileAndRunTlsUpgrade*` / `#compileAndRunClSslShim*` /
`#compileTlsUpgradeRejectsWrongArgCount`,
`WasmLispCompilerTest#tcp*` / `#usocket*` / `#tls*` (the Preview-1 compile
errors, the component compile + import pins, the permanent listen-family pins) /
`#fetchAndTcpInOneComponentProgramCompiles` / `#httpHandlerWithTcpCompilesInServeMode`,
`WasmLispCompilerIntegrationTest#componentTcp*` / `#componentUsocket*` (a full
loopback echo runs deterministically inside the wasmtime container — no opt-in
env var needed), `WasmSocketsRewriteTest` (the socket rewrite's SHAPE table, the
todo-263 gate — a shape with no dispatch target compiles to the native built-in
and traps on a socket fd), `PackageResolverTest#usocketLibraryFormsAreAResolverFixedPoint`,
`LoadInlinerTest` (built-in system splice/dedup/quickload-skip) and
`LispEvaluatorAsdfTest` (built-in system on the interpreter). The one test that
takes the shim all the way to a real server is `ClPostgresE2eTest` (runs by
DEFAULT since todo-262, all 13 legs since todo-263; Docker is its only gate): the verbatim cl-postgres over the usocket shim
against a Testcontainers PostgreSQL, on the interpreter, the JVM and a
component, with the Preview 1 behavior (call-time errors since todo-195) pinned alongside in `WasmLispCompilerTest`. The
self-contained single-threaded echo choreography (listen 0 → tcp-local-port →
connect → write → accept → read) never deadlocks because the connection waits
in the listen backlog and small payloads sit in kernel/stream buffers. The
rontolisp introspection list includes the seven tcp names — updating it
touches `LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest`, `ci-spec.yaml` and the
`rontolisp-list-functions` / `packages` doc pages.

## Not supported

UDP (`.todo/047-udp-sockets.md`), hostname resolution on WASM
(`.todo/048-wasm-tcp-hostname-lookup.md` — it also caps the component TLS
story: a real-world `https://` host needs its IP by hand plus `tls-upgrade`
with the DNS name), TLS servers on WASM (permanent — client-only proposal),
mutual TLS (`.todo/050-tls-server-and-extensions.md`), CONNECT timeouts (read
deadlines exist on the interpreter/JVM -- `tcp-set-timeout` above; the
component's is `.todo/415`), `--no-gc`, the browser playground, and
`(do () ...)`-style empty do bindings
in examples (pre-existing `expandDo` limitation — the echo examples use a
dummy binding).
