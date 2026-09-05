# TCP sockets (`rontolisp:tcp-*`), TLS (`rontolisp:tls-*`), the usocket shim and the cl+ssl shim

Built-ins: `tcp-connect`, `tcp-listen`, `tcp-accept`, `tcp-local-port`, `tcp-local-address`,
`tcp-peer-address`, `tcp-peer-port`, `tcp-set-timeout`; `tls-connect`, `tls-upgrade` (upgrades an
ALREADY-CONNECTED handle — the cl+ssl shim's substrate), `tls-listen` (keystore password),
`tls-listen-pem` (cert + key files).

They return bidirectional stream VALUES in the same handle space as file streams
(`LispLayout.Kinds.SOCKET` / `:SOCKET-SERVER`, `.kb/read-load-streams.md`), so
`read-line`/`write-line`/`write-string`/`write-char`/`read-char`/`read-byte`/`write-byte`/`close`
work on sockets on every backend. The print family to a socket is deliberately NOT part of that
surface anywhere. Blocking and synchronous, except that on `--component` a read inside an ASYNC body
is promoted to a real suspension point. Reads are byte-at-a-time on interpreter/JVM; the component
holds one host CHUNK per socket (documented divergence). `read-line` answers `nil` at peer close;
`read` does not work on sockets. Errors: interpreter/JVM signal, the WASM component returns `nil`.

## Per-backend mechanics
- Handle allocation is CONCURRENT on interpreter and JVM (`ConcurrentHashMap` + `AtomicLong`; the
  synchronized `_addStream`) — invariant and the crossed-PostgreSQL-handshake failure in
  `.kb/read-load-streams.md`.
- Interpreter `eval/SocketSupport.java`: the raw `Socket`/`ServerSocket` goes into
  `Map<Long, Closeable> streams` so `close` needs no special case; built-ins branch on
  `instanceof Socket` (`socketEntry`, on the ALREADY-resolved designator). It is the web
  substitution seam (`Target_SocketSupport` signals).
- JVM `JvmTcpCompiler` + `JvmSocketRuntimeBuilder`; `JvmIoRuntimeBuilder` grows `instanceof Socket`
  branches ONLY when `usesTcp`, so other programs keep byte-identical stream bodies. **Trap**: the
  `_writeString` arm is NOT in `_writeStr`, the sink shared with the print family — a branch one
  level down would ship a program that works on two backends and traps on `--component`. Same reason
  the interpreter's arm sits in the `write-string` built-in, not `emitTo`.
- WASM: component-only. Preview 1 lowers each tcp call site to a CALL-TIME error
  (`LispMacroExpander.callTimeUnsupportedStub`) so a spliced library's dead socket layer still
  compiles; under `--component` the names fall through to the ordinary call path and resolve against
  `eval/sockets.lisp` (wit-imported `wasi:sockets/types@0.3.0`, `eval/sockets.wit` = vendored
  types.wit plus transparent `sock-stream`/`sock-future`/`accept-stream` aliases), spliced by
  `eval/SocketsLibrary` (triggers on a tcp-* OR any `usocket:` reference). Handle is an integer
  >= 200 but the table is LISP state (`rontolisp::*sock-table*`); the old `sock.*` seam at core
  indices 8-11 is gone, so `FUNC_START` = `IMPORT_FUNC_COUNT` (8).
- JVM gate `usesSockets` = any `tcp-*` OR `tls-connect`/`tls-listen`/`%tls-listen-p12`.

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
error on EVERY WASM target PERMANENTLY (the proposal defines only `client.wit`); Preview 1 is a
compile error for the client half too.

- The UPGRADE is the primitive: `connector.receive` takes the socket's in-flight recv stream and
  answers the cleartext read stream; `connector.send` takes the read end of a fresh cleartext pair
  and answers the ciphertext stream, which goes STRAIGHT into `tcp-socket.send` — no guest-side pump.
  `tls-connect` = `tcp-connect` + `tls-upgrade`.
- That is why sockets.lisp DEFERS the send half (`%sock-plumb` takes only `receive()`;
  `%sock-ensure-tx` calls the at-most-once `tcp-socket.send` on the FIRST write): `tls-upgrade` must
  interpose before the send side is committed, so it requires a handle with no prior write.
- The entry is swapped onto the cleartext ends IN PLACE (`%sock-set-streams`), so here `tls-upgrade`
  answers the SAME fd where interpreter/JVM answer a NEW one.
- Errors nil; `connector.connect`'s err arm is an `own<error>` RESOURCE the handler releases
  (`%tls-err:error-drop`). `:insecure` non-nil SIGNALS at RUN time (the draft has no verification
  knob). Verification uses the HOST's anchors, so `javax.net.ssl.trustStore` has no effect.
- **`TlsLibrary.process` must run BEFORE `SocketsLibrary.process`** in every chain (the trigger is
  TEXTUAL and tls.lisp references `rontolisp:tcp-connect` + `%sock`) and fires the sockets trigger
  itself; a program textually naming the client tls names needs `-S tls=y` even for dead code.
- `wasmtime serve` does NOT host wasi:tls (measured on 47.0.3): the component compiles but
  instantiation fails with "resource implementation is missing". A served handler needing outbound
  https uses `rontolisp:fetch`.
- **Trigger**: the draft is experimental/non-semver — on every wasmtime floor bump re-diff
  `eval/tls.wit` against `crates/wasi-tls/src/p3/wit/deps/tls/`, and re-check for
  verification/client-cert/ALPN knobs or a server interface.

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
  drops the write end (FIN), the recv stream and the resource. IPv4 literals parsed in Lisp
  (`%sock-parse-ipv4`); `ip-name-lookup` is not wired.
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
  (component-only, right after the async-sugar rewrite, gated on the spliced `%io-read-line` defun) —
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
  rewrite onto `write-string` — `(write-char c [s])` and bounded
  `(write-string s stream :start a :end b)` (`lowerWriteStringBounds`) — both had that lowering, but
  it ran at `WasmExprCompiler` time, AFTER this pass. An unrecognized shape is deliberately left
  UNREWRITTEN so the error names the built-in the program wrote.
- **Two alternative dispatch providers; widening one without the other breaks every program on the
  other**: sockets.lisp and `stdin-dispatch.lisp` (`StdinLibrary`, for an async component reading
  stdin but never a socket). Exactly one is spliced and the rewrite picks its target by call SHAPE
  without knowing which it got. `./mvnw test` does NOT catch it;
  `StdinLibraryTest#theTwoDispatchSplicesDefineTheSameNamesAndShapes` compares the two
  name -> (required/optional) maps.
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
returners quote-frame the string). WASM: REAL in component mode — sockets.lisp reads
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
- WASM component: the primitive SIGNALS at call time — wasi:sockets@0.3.0 has no receive-timeout knob
  and stream reads are futures with no deadline argument; a silent no-op would install the exact hang
  the client set the timeout to avoid ("what has no backing SIGNALS"). Consequence: dexador's default
  `:read-timeout 10` dies at connect time until the caller passes `:read-timeout nil` or the
  scheduler grows a future-race/deadline primitive — which is also what would make `wait-for-input`
  real there.
- `usocket:socket-option` supports `:receive-timeout` ONLY; every other option signals naming the
  option, never accept-and-ignore — even `:tcp-nodelay` (though `socket-connect`'s `:nodelay` KEY is
  accepted-and-ignored, the shim's connect-knob convention). The GETTER answers from shim-side
  bookkeeping (`usocket::*%usock-timeouts*`); the setf converts seconds->ms through `%usock-guard`
  and the refusal happens BEFORE any bookkeeping.
- `usocket:wait-for-input` is a `listen`-based poll in pure Lisp (else poll every 10 ms until
  `:timeout`; 0 = one poll, none = forever; upstream's two values honoured). On the WASM backends it
  returns IMMEDIATELY claiming readiness, branching at RUN time on
  `(member :rontolisp-wasm *features*)` — the shim source is parsed once for all backends, so a
  reader feature cannot branch it. Both candidate behaviors are wrong for someone and the degenerate
  claim is wrong for fewer. Stream sockets only; wait-list objects are not reproduced.
- `listen` on Preview 1 is a CALL-time error (was a compile error): the shim is spliced UNPRUNED into
  every usocket program (`LibraryDefunPruner` excludes it), so wait-for-input's listen site is dead
  code that must build. Consequence: cl-postgres/postmodern/mito programs now COMPILE on Preview 1
  and fail loudly at the first socket call.
- The `WaitForLibrary` trigger widened to any usocket reference (the shim has an unconditional
  `sleep` site and the usocket splice runs AFTER WaitForLibrary); non-CLI component pipelines must
  run `WaitForLibrary.process` in their chain.
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
  rontolisp has NO name-resolution primitive on any backend, and resolving on interpreter/JVM would
  cost a real backend divergence in a library SPLICED INTO EVERY socket program. **Trigger**: the day
  a cross-backend resolver exists (`ip-name-lookup@0.3.0`), return upstream's real vector quad on all
  four.
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
  backend (`LispMacroExpander.expandUsocketGuard`): interpreter/JVM = `handler-case` +
  `%usock-resignal`, WASM = pass-through (the source is parsed once and cached for all backends, so
  the branch cannot be a reader feature). The re-signal always uses `socket-error`
  (`.kb/error-handling.md`).
- Not reproduced (no substrate): UDP, `socket-shutdown`, wait-list objects, `socket-server`.

## The cl+ssl shim (`cl+ssl` package, `cl-ssl.lisp` + `ShimLibraries`)
Every CL HTTP client reaches TLS through cl+ssl, and the real cl+ssl is a CFFI binding to OpenSSL,
unloadable here. The shim is the CLIENT side only, over `tls-upgrade` (interpreter, JVM, WASM
component; Preview 1 keeps the compile error). The `flexi-streams.lisp` pattern: a canonical-shape
resource next to `ShimLibraries`, the package seeded in `PackageRegistry`, the system in
`BuiltinSystems` + `ShimLibraries.RESOURCES` + `resource-config.json`.

- `make-ssl-client-stream stream :hostname h [:verify v] ...` is
  `(rontolisp:tls-upgrade stream h :insecure (if v nil t))`; `:verify` defaults from
  `(ssl-check-verify-p)`, which consults the `with-global-context`-bound `*ssl-global-context*` (a
  context IS its recorded `:verify-mode`; `+ssl-verify-none+` 0 / `+ssl-verify-peer+` 1), so
  dexador's `dex:*not-verify-ssl*`/`:insecure` lands on `:insecure`.
- `:hostname` is REQUIRED (verified against, and the SNI); nil signals rather than silently skipping
  verification.
- What has no backing SIGNALS: client certificates (`:key`/`:certificate`/`:password`,
  `use-certificate-chain-file`) and `make-context :verify-location` CA paths (only `:default`/nil
  pass). **Triggers**: wire `:verify-location` the day the primitive takes a CA path, client certs
  the day `tls-upgrade` grows a client identity.
- `with-global-context` is an ordinary shim `defmacro`; `ensure-initialized` is a no-op returning t.
  dexador's exact `(:import-from :cl+ssl ...)` list is the export set.

## Not supported
UDP; hostname resolution on WASM (which caps the component TLS story — a real `https://` host needs
its IP by hand plus `tls-upgrade` with the DNS name); TLS servers on WASM (permanent, client-only
proposal); mutual TLS; CONNECT timeouts; `--no-gc`; the browser playground; `(do () ...)`-style
empty do bindings in examples (pre-existing `expandDo` limitation).

## Pinning tests
`LispEvaluatorTest#tcp*`/`#usocket*`/`#tls*`/`#clSslShim*`;
`JvmLispCompilerTest#compileAndRunTcp*`/`#compileAndRunUsocket*`/`#compileAndRunTls*`/
`#compileAndRunClSslShim*` + the wrong-arg-count rejections;
`WasmLispCompilerTest#tcp*`/`#usocket*`/`#tls*`/`#fetchAndTcpInOneComponentProgramCompiles`/
`#httpHandlerWithTcpCompilesInServeMode`/`#promotedSocketReadHoistsOutOfADispatchDefunArgument`;
`WasmLispCompilerIntegrationTest#componentTcp*`/`#componentUsocket*` (a full loopback echo inside
the wasmtime container), `#componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer` (a
REAL handshake against an in-container `openssl s_server`), `#componentTlsFetchesARealHostOverHttps`
(opt-in `RONTOLISP_HTTP_E2E=1`); `WasmSocketsRewriteTest`;
`PackageResolverTest#usocketLibraryFormsAreAResolverFixedPoint`; `LoadInlinerTest`;
`LispEvaluatorAsdfTest`. The one test taking the shim to a real server is `ClPostgresE2eTest` (runs
by DEFAULT, all 13 legs; Docker is its only gate): verbatim cl-postgres over the usocket shim against
a Testcontainers PostgreSQL, on the interpreter, the JVM and a component. The single-threaded echo
choreography (listen 0 -> tcp-local-port -> connect -> write -> accept -> read) never deadlocks
because the connection waits in the listen backlog.
