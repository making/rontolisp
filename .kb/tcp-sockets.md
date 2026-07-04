# TCP sockets (`rontolisp:tcp-*`) and TLS (`rontolisp:tls-*`)

Four `rontolisp`-package built-ins — `tcp-connect` (host port), `tcp-listen`
(port &optional host), `tcp-accept` (listener), `tcp-local-port` (handle) —
plus the encrypted variants `tls-connect` (host port &optional :insecure
value), `tls-listen` (keystore password port &optional host) and
`tls-listen-pem` (cert-file key-file port &optional host; all three
interpreter/JVM only, see below), that return **bidirectional stream handles in the same handle space
as file streams**, so the standard stream built-ins (`read-line`,
`write-line`, `read-byte`, `write-byte`, `close`) work on sockets unchanged. Blocking,
synchronous API (no promises). Reads are byte-at-a-time (no readahead buffer
is held between calls) and writes go out immediately (`write-line` flushes per
line, unlike buffered file writers) on every backend. `read-line` returns
`nil` at peer close; `read` (the s-expression reader) does not work on socket
handles. Error convention matches fetch: interpreter/JVM signal, the WASM
component returns `nil`.

## Per-backend mechanics

- **Interpreter** (`eval/SocketSupport.java`, registered in `Environment`'s
  stream section because the handle table is a local there): the raw
  `java.net.Socket` / `ServerSocket` is stored directly in the
  `Map<Long, Closeable> streams` table (`close` needs no special case); the
  stream built-ins branch on `instanceof Socket`. `SocketSupport` is the web
  substitution seam — `src/web/java/.../Target_SocketSupport.java` makes every
  operation signal "not supported in the browser playground".
- **JVM** (`JvmTcpCompiler` dispatch + `JvmSocketRuntimeBuilder` emitting
  `_tcpConnect`/`_tcpListen`/`_tcpAccept`/`_tcpLocalPort` plus
  `_addStream`/`_sockReadLine`/`_sockWriteLine`): the `_streams` entry is the
  raw `Socket`/`ServerSocket`. `JvmIoRuntimeBuilder` takes a nullable
  `SocketRuntime` and grows `instanceof Socket` branches in
  `_writeLine`/`_readLineStream`/`_readByte`/`_writeByte`/`_closeStream` ONLY
  when the program uses a tcp built-in (`usesTcp` in `JvmLispCompiler`) —
  non-socket programs keep byte-identical stream runtime bodies.
- **WASM**: component-only (Preview 1 = compile error, like fetch;
  `WasmTcpCompiler` gates on `ctx.component`). The handle is a preview1-style
  fd >= 200 serviced by the sockets adapter, so the core module's stream
  runtime is UNCHANGED — `fd_read`/`fd_write`/`fd_close` dispatch on fd >= 200
  inside `adapter-sock.wat`.

## TLS (`rontolisp:tls-connect` / `tls-listen` / `tls-listen-pem`)

Interpreter/JVM-only. The whole design leans on `SSLSocket` /
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
- **WASM**: `tls-connect` / `tls-listen` / `tls-listen-pem` (and the internal
  `%tls-listen-p12`) are a compile error in BOTH Preview 1 and `--component`
  mode (`WasmExprCompiler`) — TLS is interpreter/JVM only, no component
  fallback. NOTE (corrected 2026-07): wasmtime 46 *does* host
  `wasi:tls@0.3.0-draft` (p3), composable with the existing WASI-0.3 sockets
  streams, so a component-mode `tls-connect` is technically feasible — but the
  interface is **client-only** (no server API, so `tls-listen`/`tls-listen-pem`
  can never work on WASM) and explicitly experimental/non-semver (WIT churn
  between wasmtime releases, no cert/insecure knobs). Deferred pending a stable
  interface; see `.todo/50-tls-server-and-extensions.md`.
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
  `WasmLispCompilerTest#tlsConnectIsCompileErrorInBothWasmModes` /
  `#tlsListenIsCompileErrorInBothWasmModes` /
  `#tlsListenPemIsCompileErrorInBothWasmModes`.
- **Examples**: `examples/https-hello.lisp` and `examples/kv-server-tls.lisp`
  are the TLS twins of `http-hello.lisp` / `kv-server.lisp` (only the listen
  call differs; both headers carry the keytool one-liner that generates
  `tls-server.p12`).

## The WASM core seam (fixed import indices 8-13)

Core function indices 8-11 are reserved for `sock.tcp-connect` / `tcp-listen`
/ `tcp-accept` / `tcp-local-port` and 12-13 for `http.fetch-start` /
`fetch-await`, in EVERY mode, keeping `FUNC_START` at 14. Imports always
precede defined functions, so the sock range must come first: a sockets
component imports sock.* at 8-11 (fetch trap stubs defined at 12-13); a fetch
component imports sock.* at 8-11 **from the http adapter's four
errno-returning stub exports** (`adapter-http.wat`; never called — fetch+tcp
in one program is a compile error) and http.* at 12-13; everything else
defines all six as trap stubs. Signatures reuse existing type indices:
connect/listen are `(hostPtr, hostLen, port, fdOut) -> errno` (= fd_write's
4×i32→i32), accept/local-port are `(fd, out) -> errno` (= `_intern`'s
2×i32→i32). Results come back through the `SOCK_FD_ADDR` (0x40018)
out-pointer; errno != 0 yields `nil`.

## The sockets component variant

Third blob set (`uni-sock.wit` / `core-sock.wat` / `adapter-sock.wat` →
`import-block-sock.bin` / `adapter-sock.wasm`, regenerated by `regen.sh`;
`mem.wasm` is shared with the base): the base 9 import instances plus
`wasi:sockets/types@0.3.0` appended LAST (instance 9), pure WASI 0.3 (no 0.2
hybrid — wasmtime 46 hosts p3 sockets natively). `WasmComponentBuilder.buildSock`
wiring: next free component type 13; aliased types 13-17 (cli/fs error-codes,
descriptor, sockets error-code, tcp-socket); defined types 18-28 include
`own<tcp-socket>` (25), the `stream<own tcp-socket>` accept stream (26, its
own element-typed `stream.read`/`drop-readable` built-ins — see
`ComponentWriter.definedOwn`/`definedStreamOfType`) and the sockets-error-code
future (28, drop-only). Canonical options (from the `wasm-tools dump`
reference): create/bind/connect/listen/get-local-address lower with
memory+realloc+utf8, send plain, receive memory-only.

`adapter-sock.wat` socket table: 32 slots x 16 bytes at 0x50500
`{tcp-socket@0, recv-or-listen-stream@4, send-tx@8, kind@12}` (kind 1 =
connected, 2 = listener), fd = 200 + slot; scratch at 0x50090-0x500CC. At
connect/accept time the slot is plumbed eagerly: `receive()` yields the recv
stream (future dropped immediately, EOF is the stream status — the file-read
pattern), `stream.new` makes a pair whose read end goes to `send()` (callable
at most once) while the write end stays for `fd_write` (`stream.write` blocks
until accepted, so that future is dropped immediately too). `fd_close` drops
the write end (FIN), the recv stream and the resource. `connect` is an async
WIT function sync-lowered — it blocks cooperatively under the stackful lift.
IPv4 literals are parsed in the adapter (`$parse_ipv4`); hostname lookup
(`wasi:sockets/ip-name-lookup`) is not wired (`.todo`).

Run flags: the async flags plus `-S tcp=y -S inherit-network=y`. Unlike
wasi:http (absent without `-S http=y`, failing instantiation), wasmtime always
hosts wasi:sockets and gates it by permission: without the flags the component
instantiates and socket calls return errors → `nil`
(`componentTcpWithoutNetworkFlagsReturnsNil`).

## Pinning tests

`LispEvaluatorTest#tcp*`, `JvmLispCompilerTest#compileAndRunTcp*` /
`#compileTcpRejectsWrongArgCount`, `WasmLispCompilerTest#tcp*` /
`#fetchAndTcpInOneComponentProgramIsCompileError`,
`WasmLispCompilerIntegrationTest#componentTcp*` (a full loopback echo runs
deterministically inside the wasmtime container — no opt-in env var needed).
The self-contained single-threaded echo choreography (listen 0 →
tcp-local-port → connect → write → accept → read) never deadlocks because the
connection waits in the listen backlog and small payloads sit in kernel/stream
buffers. The rontolisp introspection list includes the four names — updating
it touches `LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest`, `ci-spec.yaml` and the
`rontolisp-list-functions` / `packages` doc pages.

## Not supported

UDP (`.todo/47-udp-sockets.md`), hostname resolution on WASM
(`.todo/48-wasm-tcp-hostname-lookup.md`), fetch+tcp in one component
(`.todo/49-combine-fetch-and-sockets-component.md`), TLS servers /
insecure-mode / WASM TLS (`.todo/50-tls-server-and-extensions.md`), timeouts,
`--no-gc`, the browser playground, and `(do () ...)`-style empty do bindings
in examples (pre-existing `expandDo` limitation — the echo examples use a
dummy binding).
