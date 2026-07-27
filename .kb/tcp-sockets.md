# TCP sockets (`rontolisp:tcp-*`), TLS (`rontolisp:tls-*`) and the usocket shim

Seven `rontolisp`-package built-ins — `tcp-connect` (host port), `tcp-listen`
(port &optional host), `tcp-accept` (listener), `tcp-local-port` (handle),
plus the address accessors `tcp-local-address` (handle) / `tcp-peer-address`
(handle) / `tcp-peer-port` (handle) — plus the encrypted variants
`tls-connect` (host port &optional :insecure value), `tls-listen` (keystore
password port &optional host) and `tls-listen-pem` (cert-file key-file port
&optional host; all three interpreter/JVM only, see below), that return
**bidirectional stream handles in the same handle space as file streams**, so
the standard stream built-ins (`read-line`, `write-line`, `read-byte`,
`write-byte`, `write-string`, `close`) work on sockets unchanged. Blocking,
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
- **WASM**: component-only (Preview 1 = compile error, inline in
  `WasmExprCompiler`; under `--component` the tcp names FALL THROUGH to the
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
  interface; see `.todo/050-tls-server-and-extensions.md`.
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
  over the chunk through three component-only intrinsics
  (`WasmStrByteCompiler`): `rontolisp::%str-byte-length` /
  `rontolisp::%str-byte-ref` (raw `$str_bytes` access) and
  `rontolisp::%str-from-byte` (a one-content-byte string, valid UTF-8 or not,
  which the raw-`$str_bytes` write path puts on the wire as exactly that byte
  -- `write-byte` of a value >= 128 used to emit a TWO-byte UTF-8 sequence,
  invisible in rontolisp-to-rontolisp loopback because the read side decoded it
  back). `read-byte` pops one wire byte; `read-line` accumulates bytes (so a
  UTF-8 line decodes exactly like the interpreter's byte-collecting readLine,
  chunk boundaries included); `read-char` assembles one UTF-8 sequence
  byte-wise (refills mid-sequence work). Pinned by
  `componentTcpBinaryBytesAreWireTransparent`.
- **Sequence ops and the eof-tolerant read-byte dispatch (cl-postgres'
  surface)**: `(read-sequence seq s)` / `(write-sequence seq s)` (2-arg forms)
  and `(read-byte s eof-error-p [eof-value])` are rewritten onto
  `%io-read-sequence` / `%io-write-sequence` / `%io-read-byte-eof` (sync
  context) or promoted onto `%read-sequence-future` / `%read-byte-eof-future`
  (async context; writes never promote). A non-socket designator falls through
  to the native expansions via the `%read-sequence-raw`/`%write-sequence-raw`
  aliases and the 3-arg `%read-byte-raw`, byte-identical semantics to the
  unrewritten forms -- this is what the earlier reverted attempt got wrong (it
  widened `%io-read-byte` itself and broke a top-level file read at EOF in the
  ci-spec corpus). Unrewritten, these calls compile to the NATIVE stream
  built-ins whose `fd_read`/`fd_write` on a socket fd (>= 200) walks off the
  preview1 adapter's 64-slot fd table -- the "unknown handle index" crash.
  Socket write-sequence elements are BYTES (integers), like the interpreter's
  per-element `write-byte` expansion. Pinned by
  `componentTcpSequenceOpsReachTheSocketDispatch`.
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
Preview 1 keeps the compile error like the other tcp built-ins.

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
  (`socket-send`/`socket-receive`), `socket-shutdown`, `wait-for-input`,
  `socket-server`, and restart-based retry (`handler-bind`/`restart-case`).

## Pinning tests

`LispEvaluatorTest#tcp*` / `#usocket*`, `JvmLispCompilerTest#compileAndRunTcp*`
/ `#compileTcpRejectsWrongArgCount` / `#compileAndRunUsocket*`,
`WasmLispCompilerTest#tcp*` / `#usocket*` /
`#fetchAndTcpInOneComponentProgramCompiles` / `#httpHandlerWithTcpCompilesInServeMode`,
`WasmLispCompilerIntegrationTest#componentTcp*` / `#componentUsocket*` (a full
loopback echo runs deterministically inside the wasmtime container — no opt-in
env var needed), `PackageResolverTest#usocketLibraryFormsAreAResolverFixedPoint`,
`LoadInlinerTest` (built-in system splice/dedup/quickload-skip) and
`LispEvaluatorAsdfTest` (built-in system on the interpreter). The one test that
takes the shim all the way to a real server is `ClPostgresE2eTest` (opt-in
`RONTOLISP_POSTGRES_E2E=1`): the verbatim cl-postgres over the usocket shim
against a Testcontainers PostgreSQL, on the interpreter, the JVM and a
component, with the Preview 1 compile error pinned alongside. The
self-contained single-threaded echo choreography (listen 0 → tcp-local-port →
connect → write → accept → read) never deadlocks because the connection waits
in the listen backlog and small payloads sit in kernel/stream buffers. The
rontolisp introspection list includes the seven tcp names — updating it
touches `LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest`, `ci-spec.yaml` and the
`rontolisp-list-functions` / `packages` doc pages.

## Not supported

UDP (`.todo/047-udp-sockets.md`), hostname resolution on WASM
(`.todo/048-wasm-tcp-hostname-lookup.md`), TLS servers /
insecure-mode / WASM TLS (`.todo/050-tls-server-and-extensions.md`), timeouts,
`--no-gc`, the browser playground, and `(do () ...)`-style empty do bindings
in examples (pre-existing `expandDo` limitation — the echo examples use a
dummy binding).
