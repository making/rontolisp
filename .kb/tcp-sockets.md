# TCP sockets (`rontolisp:tcp-*`), TLS (`rontolisp:tls-*`), the usocket shim and the cl+ssl shim

Built-ins: `tcp-connect` (host port), `tcp-listen` (port &optional host), `tcp-accept`
(listener), `tcp-local-port`, `tcp-local-address`, `tcp-peer-address`, `tcp-peer-port`,
`tcp-set-timeout`; `tls-connect` (host port &optional :insecure v), `tls-upgrade` (stream host
&optional :insecure v -- upgrades an ALREADY-CONNECTED handle, the cl+ssl shim's substrate),
`tls-listen` (keystore password port &optional host), `tls-listen-pem` (cert-file key-file port
&optional host).

They return bidirectional stream VALUES in the same handle space as file streams
(`LispLayout.Kinds.SOCKET` / `:SOCKET-SERVER`, `.kb/read-load-streams.md`), so `read-line`,
`write-line`, `write-string`, `write-char`, `read-char`, `read-byte`, `write-byte`, `close` work
on sockets on every backend. The print family to a socket is deliberately NOT part of that
surface anywhere. Blocking and synchronous (no promises), except that on `--component` a read
inside an ASYNC body is promoted to a real suspension point. Reads are byte-at-a-time on
interpreter/JVM; the component holds one host CHUNK per socket (documented divergence). Writes go
out immediately. `read-line` answers `nil` at peer close; `read` (the s-expression reader) does
not work on sockets. Errors: interpreter/JVM signal, the WASM component returns `nil`.

## Per-backend mechanics

- Handle allocation is CONCURRENT on interpreter and JVM (one virtual thread per served request);
  both allocate through a thread-safe table (`ConcurrentHashMap` + `AtomicLong`; the synchronized
  `_addStream`). Invariant and the crossed-PostgreSQL-handshake failure: `.kb/read-load-streams.md`.
- Interpreter `eval/SocketSupport.java` (registered in `Environment`'s stream section, where the
  handle table lives): the raw `Socket`/`ServerSocket` goes into `Map<Long, Closeable> streams`,
  so `close` needs no special case, and the stream built-ins branch on `instanceof Socket` (the
  `socketEntry` helper next to `emitTo`, on the ALREADY-resolved designator). `SocketSupport` is
  the web substitution seam (`src/web/java/.../Target_SocketSupport.java` signals for everything).
- JVM: `JvmTcpCompiler` dispatch + `JvmSocketRuntimeBuilder`
  (`_tcpConnect`/`_tcpListen`/`_tcpAccept`/`_tcpLocalPort`/`_addStream`/`_sockReadLine`/
  `_sockWriteLine`/`_sockWriteString`/`_sockReadChar`); `_streams` holds the raw socket.
  `JvmIoRuntimeBuilder` takes a nullable `SocketRuntime` and grows `instanceof Socket` branches in
  `_writeLine`/`_readLineStream`/`_writeString`/`_readChar`/`_readByte`/`_writeByte`/`_closeStream`
  ONLY when `usesTcp`, so other programs keep byte-identical stream bodies (and only that mode
  raises `maxLocals` for the two arms needing an extra local). Trap: the `_writeString` arm is NOT
  in `_writeStr`, the sink shared with the print family -- `princ` to a socket has no dispatch on
  `--component` (it would reach native `fd_write` on a socket fd and trap), so a branch one level
  down would ship a program that works on two backends and traps on the third. Same reason the
  interpreter's arm sits in the `write-string` built-in, not `emitTo`.
- WASM: component-only. Preview 1 lowers each tcp call site to a CALL-TIME error
  (`LispMacroExpander.callTimeUnsupportedStub`) so a spliced library whose socket layer is dead
  code still compiles; under `--component` the names FALL THROUGH to the ordinary call path and
  resolve against spliced sockets.lisp defuns. Implementation: `eval/sockets.lisp` over a
  wit-imported `wasi:sockets/types@0.3.0` (`eval/sockets.wit` = vendored types.wit plus transparent
  `sock-stream`/`sock-future`/`accept-stream` aliases), spliced by `eval/SocketsLibrary` (triggers
  on a tcp-* OR any `usocket:` reference). Handle is still an integer >= 200 but the table is LISP
  state (`rontolisp::*sock-table*`: fd -> socket resource + raw recv/send handles + chunk buffer).
  No hand-written adapter, no dedicated blob variant; the old `sock.*` seam at core indices 8-11 is
  gone, so `FUNC_START` = `IMPORT_FUNC_COUNT` (8).

## TLS

Clients run on interpreter, JVM and the WASM component; the listeners are interpreter/JVM only,
permanently. On interpreter/JVM everything leans on `SSLSocket`/`SSLServerSocket` being
`Socket`/`ServerSocket` subclasses: the handshaken socket enters the same stream table and every
existing branch works unchanged. Hence no `tls-accept`: plain `tcp-accept` accepts on a TLS
listener and the accepted `SSLSocket` handshakes lazily on first I/O (so handshake failures
surface there, not at accept).

- FRESH `SSLContext` per call (`getInstance("TLS")` + `init(null,null,null)`), never the cached
  `SSLSocketFactory.getDefault()`, so `javax.net.ssl.trustStore` is re-read per connection.
  `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` before `startHandshake()` -- the JDK
  does NOT verify hostnames by default.
- `tls-upgrade` transport is `SSLSocketFactory.createSocket(socket, host, port, true)`; the
  handshaken socket becomes a NEW stream-table entry (the old handle still names the raw socket;
  closing the new one closes both, autoClose). Interpreter `SocketSupport.upgradeTls`, JVM
  `_tlsUpgrade` (covered by the `usesTlsConnect` gate).
- `:insecure` non-nil skips BOTH chain validation and endpoint identification. The keyword must be
  LITERAL (like `open`'s `:direction`); the value is a runtime expression. Interpreter:
  `SocketSupport.TrustAllManager`. JVM: the JDK cannot take an anonymous TrustManager from
  hand-assembled bytecode, so the GENERATED PROGRAM CLASS implements `X509TrustManager` (no-arg
  `<init>` + the three methods, emitted only under `usesTlsConnect`) and `_tlsConnect` passes
  `new Prog()`. Those methods are reached only through JSSE, invisible to the tree-shaker, so they
  are extra `--optimize` roots (`checkClientTrusted`/`checkServerTrusted`/`getAcceptedIssuers`).
- `tls-listen` takes a PKCS12 keystore + password (keeps both backends on plain
  `KeyStore`/`KeyManagerFactory`/`SSLContext`); failures signal on both (no WASM variant to match).
- `tls-listen-pem` takes a PEM chain + an UNENCRYPTED PKCS#8 key (`BEGIN PRIVATE KEY`; algorithm
  found by trying RSA/EC/DSA/EdDSA `KeyFactory`). PEM parsing (`SocketSupport.pemToKeyStore`, public
  `TlsPemSupport`) is too big to hand-assemble, so it runs at PARSE time: the interpreter reads the
  files at run time, while the `cli` `TlsPemInliner` pre-pass (JVM branch of `RontoLispCli` only, so
  WASM still sees `tls-listen-pem`) parses the LITERAL-only paths at compile time and rewrites the
  call to `rontolisp:%tls-listen-p12 base64 password port [host]`. Non-literal paths on the compile
  path are a compile error.
- JVM socket-runtime gate `usesSockets` = any `tcp-*` OR
  `tls-connect`/`tls-listen`/`%tls-listen-p12`.

### WASM TLS (component only)

`tls.lisp` (spliced by `eval/TlsLibrary`) over a wit-imported `wasi:tls@0.3.0-draft`
(`eval/tls.wit`, vendored from wasmtime v47.0.2 `crates/wasi-tls/src/p3/wit`). Run with `-S tls=y`
on top of the tcp flags. The SERVER half is a compile error on EVERY WASM target PERMANENTLY (the
proposal defines only `client.wit`; the message says "client-only by design"). Preview 1 is a
compile error for the client half too.

- The UPGRADE is the primitive (mirror of the JVM): `connector.receive` takes the socket's
  in-flight recv stream and answers the cleartext read stream; `connector.send` takes the read end
  of a fresh cleartext pair and answers the ciphertext stream, which goes STRAIGHT into
  `tcp-socket.send` -- no guest-side pump. `tls-connect` = `tcp-connect` + `tls-upgrade`.
- That is why sockets.lisp DEFERS the send half (`%sock-plumb` takes only `receive()`;
  `%sock-ensure-tx` makes the write pair and calls the at-most-once `tcp-socket.send` on the FIRST
  write): `tls-upgrade` must interpose before the send side is committed, so it requires a handle
  with no prior write (else `nil`). Peer-invisible and atomic (no await between test and store).
- The entry is swapped onto the cleartext ends IN PLACE (`%sock-set-streams`), so here
  `tls-upgrade` answers the SAME fd (interpreter/JVM answer a NEW one) and every built-in /
  `WasmSocketsRewrite` dispatch keeps working. The swap precedes the handshake await, so a close
  after a failed handshake drops handles the guest owns.
- Errors: nil on failure; `connector.connect`'s err arm is an `own<error>` RESOURCE the handler
  releases (`%tls-err:error-drop`). `:insecure` non-nil SIGNALS at RUN time (the draft has no
  verification knob; the cl+ssl verify path passes a literal `:insecure nil`, which passes
  through). Verification uses the HOST's anchors (wasmtime's rustls compiles in webpki roots), so
  `javax.net.ssl.trustStore` has no effect.
- Splice trigger is TEXTUAL, and tls.lisp references `rontolisp:tcp-connect` + `%sock`, so
  `TlsLibrary.process` must run BEFORE `SocketsLibrary.process` in every chain and fires the
  sockets trigger itself. Consequence: a component program textually naming the client tls names
  imports `wasi:tls` and needs `-S tls=y` even for dead code (tls.lisp is not prunable).
- `wasmtime serve` does NOT host wasi:tls (measured on 47.0.3): the component compiles (imports
  ride `WasmServeComponentBuilder.additionalImports`) but instantiation fails with "resource
  implementation is missing" -- serve.rs never links wasi-tls even with `-S tls=y` (`wasmtime run`
  does). A served handler needing outbound https uses `rontolisp:fetch`.
- Re-evaluation trigger: the draft is explicitly experimental/non-semver, so on every wasmtime floor
  bump re-diff `eval/tls.wit` against `crates/wasi-tls/src/p3/wit/deps/tls/` (a WIT change is a file
  edit here, not compiler work). Also re-check for verification/client-cert/ALPN knobs (revisit the
  `:insecure` signal and the cl+ssl gates) or a server interface (then "permanent" stops holding).

TLS fixture: `TlsTestSupport` (package `am.ik.rontolisp`) generates one self-signed PKCS12 keystore
per JVM with `keytool` (CN=localhost, SAN ip:127.0.0.1 + dns:localhost so endpoint identification
passes on loopback). A handshake needs a live peer -- the plain-TCP backlog trick does not apply --
so the fixture runs the peer on a background thread: a one-shot echo `SSLServerSocket` for
`tls-connect` (client trust via `withTrustStore`), and a one-shot echo TLS *client* with
connect-retry for `tls-listen` (port from `freePort()`, since it must be embedded in the program
text). `pemFiles()` derives cert.pem + PKCS#8 key.pem from the same keystore in pure Java.
Examples: `examples/net/https-hello.lisp`, `examples/net/kv-server-tls.lisp`.

## The component mechanics (sockets.lisp over wasi:sockets@0.3.0)

A tcp program is the BASE variant plus one appended user import (`appendUserImports`), like fetch,
which is why fetch+tcp and serve+tcp compose in one component.

**serve+tcp under `wasmtime serve` needs `-S cli=y`** (plus `-S tcp=y -S inherit-network=y`);
without it instantiation reports "instance export `tcp-socket` has the wrong type: resource
implementation is missing", which reads like a missing host feature. Runtime coverage:
`httpHandlerConnectsTcpUnderWasmtimeServe` (the compile-level
`httpHandlerWithTcpCompilesInServeMode` cannot catch a runtime composition failure).

**Serve top-level init**: a serve component never lifts `run`, so nothing would execute the
program's top level -- every global would read back null in a handler and the first arithmetic on
one traps with "cast failure". The `handle` wrapper (`WasmExportCompiler.emitBody`) runs `_start`
once per instance under a serve-only `(mut i32)` flag (`serveInitGlobalIndex`, appended after every
other global so non-serve output is byte-identical), inside the handle call's task context so a
top-level suspension drives the blocking event loop. Pinned by
`httpHandlerReadsATopLevelGlobalUnderWasmtimeServe`.

**"Once per instance" is a HOST decision.** `wasmtime serve` and Spin do BOUNDED reuse -- 128
requests per instance (`--max-instance-reuse-count` defaults to 1 for WASIp2, 128 for WASIp3; Spin
inherits it), plus `--max-instance-concurrent-reuse-count` (default 16 for p3) letting 16 requests
share one instance concurrently. wasmCloud `wash dev` gives each request a FRESH instance, so the
top level and its side effects repeat every request. Rule: treat top-level side effects as
idempotent-or-per-request and keep durable state in the store, not in a global -- under load a
global neither persists for the run nor resets per request, the worst case for a program assuming
either (hence `create table if not exists` in `examples/db/postgres-web.lisp`). The per-instance
cost is why serve mode pre-grows a smaller GC heap (`.kb/wasm-gc-heap-pregrow.md`).

wasmCloud notes: `wash dev` (2.5.2+) hosts the component AND provides `wasi:sockets@0.3` -- its
"Host provides interfaces" log is a stale hardcoded list naming 0.2 only. Loopback destinations are
routed to a per-workload in-memory virtual network, never the real 127.0.0.1, so connecting to a
host-side listener gets `:connection-refused` -> `nil`; non-loopback works. A nil sock reaching
`(read-line nil)` is a cast-failure TRAP, uncatchable on wasm-GC. The listen host must be an
explicit `"127.0.0.1"`: wash's p3 bind check rejects the `tcp-listen` default 0.0.0.0 before its
loopback rewrite. Install with `curl -fsSL https://wasmcloud.com/sh | bash`; do NOT fetch by tag --
`wasmcloud/wasmCloud`'s wash releases are tagged plain `vX.Y.Z` while the SEPARATE `wasmCloud/wash`
repo is stuck at `wash-v2.0.0-rc.7`, whose binaries advertise `wasi:http@0.2.0` only and reject a
serve component during interface extraction (looks exactly like a rontolisp regression, is not).

### Inside sockets.lisp

- **Plumbing** (`%sock-plumb`): at connect/accept `receive()` yields the recv stream (its future
  dropped immediately -- EOF is the stream status), `sock-stream-new` makes a pair whose read end
  goes to `send()` (at most once) while the write end stays in the entry; `%io-close` drops the
  write end (FIN), the recv stream and the resource. IPv4 literals parsed in Lisp
  (`%sock-parse-ipv4`); `wasi:sockets/ip-name-lookup` is not wired.
- **connect** is an `async func` -> an async-defun over `%subtask-future`; `listen` returns
  `result<stream<tcp-socket>, ...>` and ACCEPT is one element read of that stream through the
  `accept-stream` alias -- the ONE non-u8 stream lift: `validateAsyncAlias` admits resource-handle
  elements (read/drop-readable only) and `emitStreamRead` reads ONE 4-byte element, lifting the
  opaque handle (registry kind 2; `_sched_dispatch` lifts it with `i32.load` instead of
  `_str_from_mem`).
- **Async-lower param SPILL**: wasmtime spills async-lowered params past 4 flats
  (`MAX_FLAT_PARAMS_ASYNC`; sync cap 16) and `connect`'s self + flattened `ip-socket-address` is 14,
  so `FlatSig` carries `spilledParams`/`paramsAreaSize`, `WitCanonicalAbi.spillLayout` computes the
  tuple layout and `emitAsyncStartBody` stores each argument via `emitLowerAt` into one allocated
  area, passing `(argptr[, retptr])`.
- **Two surfaces**: sync tcp defuns force their async internals with `rontolisp::%future-force` (a
  binding of `_sched_loop`, OFF_SCHED_LOOP); ASYNC bodies get the await promotion from
  `WasmSocketsRewrite` (component-only, in `WasmLispCompiler.compile()` right after the async-sugar
  rewrite, gated on the spliced `%io-read-line` defun, mirroring `LispAsync`'s context rules):
  `(read-line s)` -> `(rontolisp:await (rontolisp::%read-line-future s))`, and
  `tcp-connect`/`tcp-accept` promote onto `%tcp-connect-f`/`%tcp-accept-f` (nil-on-failure, so both
  surfaces agree), so a PENDING accept/read no longer stalls the instance. In SYNC context the same
  calls are rewritten onto the `%io-*` dispatch defuns, which branch: socket handle -> the entry's
  chunked reads / blocking `sock-stream-write`; a nil (stdin) READ designator -> the
  `%stdin-*-or-raw-f` helpers (stdin.lisp, or its serve-mode raw-passthrough stub -- `StdinLibrary`
  splices one of the two whenever sockets.lisp is spliced, `.kb/read-load-streams.md`); anything
  else -> the `%...-raw` aliases of the NATIVE built-ins (no rewrite recursion). A wrong-arity call
  is left unrewritten so it errors under its public name.
- **The chunk buffer walks BYTES, never characters**: a socket chunk's bytes ARE the wire, and a
  char-based cursor collapsed a valid multi-byte sequence in binary data (a PostgreSQL
  BackendKeyData secret) into one char, shifting the rest of the stream. The `stream<u8>` read lifts
  the chunk as a packed `(unsigned-byte 8)` vector (`.kb/wit.md`) so the cursor is plain
  `length`/`aref`; the component-only `rontolisp::%str-byte-length`/`%str-byte-ref`
  (`WasmStrByteCompiler`) survive for the WRITE side with `rontolisp::%str-from-byte` (a
  one-content-byte string, valid UTF-8 or not, which the raw-`$str_bytes` write path puts on the
  wire as exactly that byte -- `write-byte` of >= 128 used to emit a TWO-byte UTF-8 sequence,
  invisible in rontolisp-to-rontolisp loopback because the read side decoded it back). `read-byte`
  pops one wire byte; `read-line` accumulates bytes (so a UTF-8 line decodes like the interpreter's
  byte-collecting readLine, chunk boundaries included); `read-char` assembles one UTF-8 sequence
  byte-wise (mid-sequence refills work). Pinned by `componentTcpBinaryBytesAreWireTransparent`.
- **Sequence ops and eof-tolerant reads (cl-postgres' surface)**: `read-sequence`/`write-sequence`
  with `:start`/`:end` and `(read-byte|read-char|read-line s eof-error-p [eof-value])` rewrite onto
  `%io-read-sequence`/`%io-write-sequence`/`%io-read-byte-eof`/`%io-read-char-eof`/
  `%io-read-line-eof` (sync) or the matching `%...-future` (async; writes never promote). Bounds
  travel POSITIONALLY (`nil` = unspecified, normalized to `0`/`(length seq)` inside the dispatch
  defun against the same sequence both arms see). A non-socket designator falls through via
  `%read-sequence-raw`/`%write-sequence-raw` and the 3-arg `%read-byte-raw`/`%read-char-raw`/
  `%read-line-raw`, byte-identical to the unrewritten forms -- widening `%io-read-byte` itself
  instead broke a top-level file read at EOF in the ci-spec corpus. The eof arms signal the same
  `end-of-file` class the native lowering does (`LispMacroExpander.endOfFileSignal`). Socket
  write-sequence elements are BYTES, except a STRING sequence, which goes out as its characters.
  Pinned by `componentTcpSequenceOpsReachTheSocketDispatch`,
  `componentTcpGrayStreamsFallThroughShapesReachTheSocketDispatch`.
- **EVERY shape a socket-capable built-in accepts needs an entry in `WasmSocketsRewrite`.**
  Unrewritten, the call compiles to the NATIVE built-in whose `fd_read`/`fd_write` on a socket fd
  (>= 200) walks off the preview1 adapter's 64-slot fd table -- the `unknown handle index 0` trap,
  firing MID-MESSAGE (server logs `incomplete message from client`), so it reads like a dropped
  `wasi:io` resource. The exotic shapes are load-bearing because of pass ORDER:
  `GrayStreamsLibrary.process` runs BEFORE this rewrite and normalizes every possibly-CLOS-instance
  stream call site onto `%gray-*-dispatch`, whose non-instance fall-through re-spells the built-in
  with EVERY optional argument filled in -- so in any Gray-protocol program (every
  `(ql:quickload "cl-postgres")` program) the keyworded / eof-carrying shape is what the socket call
  ACTUALLY is. Two shapes have no dispatch defun and are LOWERED inside the rewrite onto
  `write-string`: `(write-char c [s])` (`LispMacroExpander.expandWriteChar`) and bounded
  `(write-string s stream :start a :end b)` (`lowerWriteStringBounds`) -- both had that lowering, but
  it ran at `WasmExprCompiler` time, AFTER this pass. An unrecognized shape is deliberately left
  UNREWRITTEN so the error names the built-in the program wrote. Pinned by `WasmSocketsRewriteTest`;
  `expandReadLineCompat` accepts the `rontolisp::%read-line-raw` alias for the same reason.
- **Two alternative dispatch providers; widening one without the other breaks every program on the
  other**: sockets.lisp, and `stdin-dispatch.lisp` for an async component that reads stdin but never
  touches a socket (`StdinLibrary`). Exactly one is spliced, the rewrite keys on the same
  `%io-read-line` marker and picks its target by call SHAPE without knowing which file it got, so a
  name or shape only one file defines is `the function RONTOLISP::%IO-... is undefined` for the
  other. `./mvnw test` does NOT catch it (`CiSpecE2eTest` needs `-Drontolisp.binary`).
  `StdinLibraryTest#theTwoDispatchSplicesDefineTheSameNamesAndShapes` compares the two
  name -> (required/optional) maps.
- **`write-string`/`write-char`/`read-char` on a socket are real on all four backends.**
  `write-char` needs no arm anywhere (it lowers to `write-string`), nor does bounded
  `write-string`. All three treat socket I/O as BYTES, so `read-char` assembles one UTF-8 sequence
  byte-wise -- never through a `Reader`/decoder that would buffer ahead and swallow bytes a
  following `read-byte`/`read-line` owes the caller -- and an invalid lead byte stands alone as
  U+FFFD. Cross-backend pin: one program answering `(65 195 135 66 504 90 121)` on the interpreter
  (`LispEvaluatorTest#tcpCharacterOpsOnSocket`), the JVM
  (`compileAndRunTcpCharacterOpsOnSocket`) and the component
  (`componentTcpBinaryBytesAreWireTransparent`).
- **A bare `(read-char sock)` / `(read-byte sock)` at peer close signals `end-of-file`** on all
  three (CL's default `eof-error-p` t). It had to land on BOTH entries a bare shape reaches: the
  promoted `%read-char-future`/`%read-byte-future` (socket arms turn a nil read into
  `(error 'end-of-file)`) and the sync `%io-read-char`/`%io-read-byte`, which inherit it by forcing
  those futures. The nil convention stays BELOW, in `%sock-read-char-f`/`%sock-read-byte-f`, because
  `%read-char-eof-future`/`%read-byte-eof-future` need the raw nil to apply the CALLER's eof
  arguments -- `(read-char sock nil :eof)` is `:eof` on all three. `read-line` still answers nil at
  peer close everywhere, because its `eof-error-p` defaults to nil. Pinned by
  `LispEvaluatorTest#tcpReadCharAtPeerCloseHonoursTheEofArguments`, its JVM twin, and
  `componentTcpBareReadCharSignalsAtPeerClose` (sync defun body and async top level separately --
  different dispatch entries).
- **The two rewrites MEET in an async body**: a write is never promoted, so it takes the `%io-*`
  head even in async context while a read in its arguments becomes an await --
  `(write-line (read-line s))` is `(%io-write-line (await (%read-line-future s)))`.
  `WasmAwaitNormalizer` must hoist that await out of a `%`-named head's argument, which it refused
  while reading any `%` member as an internal form with non-value positions (`%block`, `%error`),
  so the example failed with "await in this position" while `(princ (read-line s))` compiled.
  `WasmSocketsRewrite.strictDispatchMembers()` tells the normalizer these `%` heads are ORDINARY
  defuns -- keep the two in step when adding a dispatch defun. Pinned by
  `WasmLispCompilerTest.promotedSocketReadHoistsOutOfADispatchDefunArgument`.
- **Run flags**: sockets.lisp carries async-defuns and handler-case, so a component tcp program is
  asyncMode + EH mode; run with `-S tcp=y -S inherit-network=y`. Unlike wasi:http (absent without
  `-S http=y`, failing instantiation), wasmtime always hosts wasi:sockets and gates it by
  permission: without the flags the component instantiates and socket calls return `nil`
  (`componentTcpWithoutNetworkFlagsReturnsNil`).
- print-family to a socket is not wired on the component; `(format nil ...)` + `write-line` is the
  pattern.

## Address accessors

For the usocket shim's `get-local-*`/`get-peer-*`. Interpreter:
`SocketSupport.localAddress`/`peerAddress`/`peerPort` (null/-1 for a wrong-kind entry ->
`Environment` signals). JVM: `_tcpLocalAddress`/`_tcpPeerAddress`/`_tcpPeerPort`, the address
returners quote-framing the string (the `_sockReadLine` `"\"".concat(...)` tail). WASM: REAL in
component mode -- sockets.lisp reads `get-local-address`/`get-remote-address` (an
`ip-socket-address` variant->record->tuple lift) and formats the dotted quad in Lisp; a wrong-kind
handle or host error yields `nil`. `tcp-local-port` reads the real bound port the same way
(ephemeral `tcp-listen 0` included). Preview 1: the call-time error.

## Read deadlines and the usocket timeout/wait decisions

`(rontolisp:tcp-set-timeout handle milliseconds)` (nil clears; non-negative integer ms, the
`wait-for` convention) sets a per-socket READ deadline;
`(setf (usocket:socket-option s :receive-timeout) seconds)` rides it.

- Interpreter/JVM: REAL, via `Socket.setSoTimeout` (`SocketSupport.setTimeout` /
  `_tcpSetTimeout`, same `usesSockets` gate). A timed-out read throws `SocketTimeoutException` out
  of the ORDINARY stream built-in, so it surfaces as a catchable plain `error` ("read-line: Read
  timed out"), NOT `usocket:timeout-error` -- the reads do not go through usocket functions and
  per-read wrapping would tax every socket program. The deadline lives on the raw socket, so it
  keeps governing a `tls-upgrade`d connection. Listener handles are rejected.
- WASM component: the primitive SIGNALS at call time -- wasi:sockets@0.3.0 has no receive-timeout
  knob and stream reads are futures with no deadline argument; a silent no-op would install the
  exact hang the client set the timeout to avoid ("what has no backing SIGNALS"). Consequence:
  dexador's default `:read-timeout 10` dies at connect time on the component until the caller passes
  `:read-timeout nil` (dexador guards the setf with `(when read-timeout ...)`) or the scheduler
  grows a future-race/deadline primitive -- which is also what would make `wait-for-input` real
  there. Preview 1: the call-time stub.
- `usocket:socket-option` supports `:receive-timeout` ONLY; every other option signals naming the
  option, never accept-and-ignore -- even `:tcp-nodelay` (though `socket-connect`'s `:nodelay` KEY
  is still accepted-and-ignored, the shim's connect-knob convention). The GETTER answers from
  shim-side bookkeeping (`usocket::*%usock-timeouts*`, an alist the setf maintains; the shim is the
  only writer, and entries survive close since a handle is never reused). The setf converts
  seconds->ms and calls the primitive through `%usock-guard`, so an interpreter/JVM failure is a
  typed `usocket:socket-error` and the component's signal passes through raw; the refusal happens
  BEFORE any bookkeeping.
- `usocket:wait-for-input` is a `listen`-based poll, pure Lisp: ready set via `listen` (kernel
  receive buffer on interpreter/JVM, the chunk readahead buffer on the component), else poll every
  10 ms (`sleep 0.01`) until `:timeout` (0 = one poll; none = forever); upstream's two values are
  honoured (`:ready-only`, remaining time). On the WASM backends it returns IMMEDIATELY claiming
  readiness, branching at RUN time on `(member :rontolisp-wasm *features*)` -- the shim source is
  parsed once for all backends so a reader feature cannot branch it, but `*features*` is seeded per
  backend. Both candidate behaviors are wrong for someone and the degenerate claim is wrong for
  fewer: the dominant wait-then-read loop behaves identically (reads block anyway) while an honest
  poll would sleep-spin forever on data waiting host-side that component `listen` cannot see.
  `:ready-only` claims the full list there. Stream sockets only (a listener probes through `listen`,
  which signals on it); wait-list objects are not reproduced.
- `listen` on Preview 1 is a CALL-time error (was a compile error): the shim is spliced UNPRUNED
  into every usocket program (`LibraryDefunPruner` excludes it), so wait-for-input's listen site is
  dead code that must build. Consequence: cl-postgres/postmodern/mito programs now COMPILE on
  Preview 1 and fail loudly at the first socket call at RUN time.
- The `WaitForLibrary` trigger widened to any usocket reference: the shim has an unconditional
  `sleep` site the component must resolve against wait.lisp, and the usocket splice runs AFTER
  WaitForLibrary, so the trigger must fire on what is visible before the splice. Every component
  usocket program imports wasi:clocks; non-CLI component pipelines (tests) must run
  `WaitForLibrary.process` in their chain.
- `socket-server` was split out rather than shipped keys-ignored: silently ignoring
  `:in-new-thread` blocks a caller expecting to continue, and the thread primitives now exist on
  interpreter/JVM so an honest implementation is possible.

Pins: `LispEvaluatorTest#usocketSocketOptionReceiveTimeoutIsARealReadDeadline`,
`#usocketSetfSocketOptionAsTheFirstReferenceLoadsLibraryAndSignalsTyped` (the interpreter's
lazy-load third trigger, `ensureUsocketSetfPlaceLoaded`), `#usocketWaitForInputPollsThroughListen`
+ JVM twins; `WasmLispCompilerTest#listenInPreview1ModeIsACallTimeError`,
`#tcpBuiltinsInPreview1ModeAreCallTimeErrors`, `#tcpBuiltinsCompileInComponentMode`;
`WasmLispCompilerIntegrationTest#componentUsocketSocketOptionRefusesAndWaitForInputClaimsReadiness`.

## The usocket shim (`usocket` package, `usocket.lisp` + `UsocketLibrary`)

A usocket-compatible API over the tcp built-ins, targeting the surface cl-postgres uses
(`socket-connect` + `:element-type` + `socket-stream`). The linalg/vec library pattern:
`src/main/resources/am/ik/rontolisp/eval/usocket.lisp` (canonical package shape; resolver fixed
point pinned by `PackageResolverTest#usocketLibraryFormsAreAResolverFixedPoint`) +
`eval/UsocketLibrary` (cached `forms()`, Walker detection, `process()` splice).

- A socket IS its handle: `socket-stream` = identity, `socket-close` = `close`; `socket-listen`
  flips usocket's host-first order onto `tcp-listen` and must OMIT the host argument for the
  wildcard case (`tcp-listen` rejects an explicit nil host). `socket-connect` rejects
  `:protocol :datagram`; every entry point takes `&key ... &allow-other-keys`.
- `usocket:*wildcard-host*`/`*auto-port*` are library defparameters, so `LispEvaluator.evalSymbolRef`
  has a variable-read lazy-load hook besides the `resolveFunction` one (function-call-first programs
  work through the function hook because `evalCons` resolves the function before the arguments).
  `boundp`/`symbol-value` are NOT hooked (lite edge).
- `get-local-name`/`get-peer-name` end in a literal `(values address port)`, which flows through the
  multiple-values tier's user-function channel, so `multiple-value-bind` gets both values on every
  backend.
- `host-to-hostname`/`get-host-by-name` are pure Lisp over `format`/`elt`/`ash`, so unlike the rest
  of the shim they answer on Preview 1 too. `host-to-hostname` is REAL for every designator upstream
  accepts (nil -> `"0.0.0.0"`, string -> itself, vector quad / list of four octets /
  host-byte-order 32-bit integer -> dotted quad). `get-host-by-name` is LITE -- it renders through
  `host-to-hostname` instead of resolving, because rontolisp has NO name-resolution primitive on any
  backend, and the socket call the address reaches resolves it inside the host anyway. Not resolved
  on interpreter/JVM (where `InetAddress` could) because it buys nothing and costs a real backend
  divergence in a library SPLICED INTO EVERY socket program. Re-evaluation trigger: the day a
  cross-backend resolver exists (wiring `wasi:sockets/ip-name-lookup@0.3.0`), return upstream's real
  vector quad on all four. Pinned by `LispEvaluatorTest.usocketHostToHostname*`,
  `JvmLispCompilerTest.compileAndRunUsocketHostToHostnameRendersEveryDesignatorShape`,
  `WasmLispCompilerIntegrationTest.usocketHostToHostnameRendersEveryDesignatorShape`.
- The four `with-*` macros (`with-client-socket`, `with-connected-socket`, `with-server-socket`
  (alias), `with-socket-listener`) are built-in `LispMacroExpander.expandUsocketWith*` expansions
  (the `rontolisp:with-arena` pattern: dispatched on the qualified name in `LispEvaluator.evalCons`
  + `Jvm/WasmExprCompiler`, registered only as usocket externals, no CL_MACROS entry). They are
  backend-parameterized: interpreter/JVM wrap the body in `unwind-protect`; the WASM call sites pass
  `unwindProtect = false` and keep the close-after-body shape (`let` + result-var + close).
- Built-in ASDF system: `eval/BuiltinSystems` maps `"usocket"` -> `UsocketLibrary::forms`.
  `LoadInliner.spliceSystem` splices the FORMS (not mark-only -- an `:import-from :usocket` +
  bare-name consumer would evade the Walker, which runs before `PackageResolver`) and the quickload
  branch skips `downloadQuicklisp`; the interpreter's `loadSystem`/`quickload` short-circuit to
  `ensureUsocketLoaded()`. `UsocketLibrary.process` has a dedup guard (skip when the program already
  carries `(defun usocket:socket-connect ...)`).
- Chain wiring: `RontoLispCli` (outermost `UsocketLibrary.process`), `RontoPlayground` (both compile
  paths), corpus tests, `AsdfLibraryE2eSupport`, native-image `resource-config.json`.
- Typed conditions: usocket.lisp defines the hierarchy (`socket-condition` with a `message` slot +
  echo `:report`, `socket-error`, `connection-refused-error`, ...) and wraps
  `socket-connect`/`socket-listen`/`socket-accept` in `(usocket::%usock-guard form)`, expanded per
  backend (`LispMacroExpander.expandUsocketGuard`): interpreter/JVM = `handler-case` +
  `usocket::%usock-resignal` (re-signals as `usocket:socket-error`, message preserved so uncaught
  output is unchanged), WASM = pass-through (the source is parsed once and cached for all backends,
  so the branch cannot be a reader feature). The re-signal always uses `socket-error` (subtypes are
  defined but not auto-selected). `.kb/error-handling.md`.
- Not reproduced (no substrate): UDP (`socket-send`/`socket-receive`), `socket-shutdown`, wait-list
  objects, `socket-server`.

## The cl+ssl shim (`cl+ssl` package, `cl-ssl.lisp` + `ShimLibraries`)

Every CL HTTP client reaches TLS through cl+ssl, and the real cl+ssl is a CFFI binding to OpenSSL,
unloadable here (cffi's `.asd` errors "Sorry, this Lisp is not yet supported"). The shim is the
CLIENT side only, over `tls-upgrade` (interpreter, JVM and the WASM component; Preview 1 keeps the
compile error). The `flexi-streams.lisp` pattern: a canonical-shape resource next to
`ShimLibraries`, the package seeded in `PackageRegistry`, the system in `BuiltinSystems` +
`ShimLibraries.RESOURCES` + the native-image `resource-config.json`.

- `make-ssl-client-stream stream :hostname h [:verify v] ...` is
  `(rontolisp:tls-upgrade stream h :insecure (if v nil t))`; `:verify` defaults from
  `(ssl-check-verify-p)` like upstream, which consults the `with-global-context`-bound internal
  `*ssl-global-context*` (a context IS its recorded `:verify-mode`; `+ssl-verify-none+` 0 /
  `+ssl-verify-peer+` 1). So dexador's `dex:*not-verify-ssl*`/`:insecure` becomes
  `make-context :verify-mode +ssl-verify-none+` plus `:verify nil`, both landing on `:insecure`.
- `:hostname` is REQUIRED (the certificate is verified against it, and it is the SNI); nil signals
  rather than silently skipping verification.
- What has no backing SIGNALS, never accept-and-ignore: client certificates
  (`:key`/`:certificate`/`:password`, `use-certificate-chain-file`) and `make-context
  :verify-location` CA paths (only `:default`/nil pass; the message names the
  `javax.net.ssl.trustStore` properties the fresh-per-call `SSLContext` re-reads). Re-evaluation
  trigger: wire `:verify-location` the day the primitive takes a CA path, client certs the day
  `tls-upgrade` grows a client identity.
- `with-global-context` is an ordinary shim `defmacro` (unlike the usocket `with-*`s, which predate
  shim defmacros); `ensure-initialized` is a no-op returning t. dexador's exact
  `(:import-from :cl+ssl ...)` list is the export set; `*ssl-global-context*` stays internal.

## Pinning tests

`LispEvaluatorTest#tcp*` / `#usocket*` / `#tls*` / `#clSslShim*` (https over a local TLS server,
incl. the verify-none-context insecure path and the signal-on-no-backing gates);
`JvmLispCompilerTest#compileAndRunTcp*` / `#compileTcpRejectsWrongArgCount` /
`#compileAndRunUsocket*` / `#compileAndRunTls*` / `#compileAndRunClSslShim*` /
`#compileTlsUpgradeRejectsWrongArgCount` / `#compileTlsRejectsWrongArgCount`;
`WasmLispCompilerTest#tcp*` / `#usocket*` / `#tls*` (Preview-1 compile errors, component
compile+import pins, the permanent listen-family pins) / `#fetchAndTcpInOneComponentProgramCompiles`
/ `#httpHandlerWithTcpCompilesInServeMode`; `WasmLispCompilerIntegrationTest#componentTcp*` /
`#componentUsocket*` (a full loopback echo runs deterministically inside the wasmtime container),
`#componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer` (a REAL handshake against an
in-container `openssl s_server`), `#componentTlsFetchesARealHostOverHttps` (opt-in
`RONTOLISP_HTTP_E2E=1`, 1.1.1.1's IP-SAN certificate); `WasmSocketsRewriteTest`;
`PackageResolverTest#usocketLibraryFormsAreAResolverFixedPoint`; `LoadInlinerTest`;
`LispEvaluatorAsdfTest`. The one test taking the shim to a real server is `ClPostgresE2eTest` (runs
by DEFAULT, all 13 legs; Docker is its only gate): verbatim cl-postgres over the usocket shim
against a Testcontainers PostgreSQL, on the interpreter, the JVM and a component. The
single-threaded echo choreography (listen 0 -> tcp-local-port -> connect -> write -> accept -> read)
never deadlocks because the connection waits in the listen backlog and small payloads sit in
kernel/stream buffers.

## Not supported

UDP; hostname resolution on WASM (which caps the component TLS story -- a real `https://` host needs
its IP by hand plus `tls-upgrade` with the DNS name); TLS servers on WASM (permanent, client-only
proposal); mutual TLS; CONNECT timeouts (read deadlines exist on interpreter/JVM); `--no-gc`; the
browser playground; `(do () ...)`-style empty do bindings in examples (pre-existing `expandDo`
limitation -- the echo examples use a dummy binding).
