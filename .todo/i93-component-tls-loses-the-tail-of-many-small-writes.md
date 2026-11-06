# i93. The component's TLS loses the tail of many small writes

Difficulty: Medium

On `--component`, a `tls-upgrade`d socket that receives more than about 128 writes in a row
before a read can leave the last bytes unsent: the server never sees the whole request, closes
after its own timeout, and the read answers end of file. drakma hits it on every https request:
it writes the request byte by byte through chunga/flexi-streams (150 one-byte writes), so
`(drakma:http-request "https://1.1.1.1/" :verify :required)` fails with `No status line -
probably network error.` after 15 s. dexador is unaffected (one write per
request). The interpreter and the JVM answer the same program.

## Measured 2026-11-06 (wasmtime 49.0.0)

- `tls-connect "1.1.1.1" 443`, then the request written with `write-byte` per byte, then
  `read-line`: 111 writes answer `HTTP/1.1 301`; 127 and 129 writes answer `NIL` after 15 s; 128
  writes answer. Deterministic over three runs. One `write-string` of 361 bytes answers.
- Every write reported its byte accepted (`sock-stream-write` answered 1; no short write).
- Any host call between the writes hides it: drakma with `drakma:*header-stream*` bound to
  stdout or stderr, or a `format *error-output*` per write in sockets.lisp, answers 200. Binding
  it to a string output stream does not.

## Likely cause (host side, read in wasmtime-wasi-tls 49.0.0 `src/p3`)

The cleartext consumer (`util/tokio_streams.rs`, `AsyncWriteConsumer::poll_consume`) calls
tokio-rustls `poll_write` and reports `Completed` when the plaintext was accepted. tokio-rustls
accepts the plaintext and stops writing records when the ciphertext pipe (`util/pipe.rs`, a
tokio `duplex`) answers `Pending`; nothing calls `poll_flush` later, so the buffered records wait
for the next guest write. tokio's cooperative budget (128 operations per task poll) would make
the duplex answer `Pending` after about 128 operations without a yield, which fits the
threshold and the effect of an intervening host call. Not confirmed by instrumenting the host.

## Options

- Coalesce writes on a TLS entry in sockets.lisp: buffer the cleartext and write it at the next
  read, `listen` probe, close, `force-output`/`finish-output`, or past a size bound. Fewer guest
  writes also means fewer TLS records. Costs: `force-output`/`finish-output` on a socket are not
  routed today (`WasmSocketsRewrite`, and the three dispatch providers must agree,
  `StdinLibraryTest#theThreeDispatchSplicesDefineTheSameNamesAndShapes`), and a write followed by
  a wait on something else would be delayed until a flush point.
- Report it upstream (wasmtime-wasi-tls p3 never flushes after `poll_write`) and re-measure on
  the next wasmtime floor bump.

A pin needs a TLS peer the host trusts; `componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer`
has only an untrusted one, so the leg is opt-in like `componentTlsFetchesARealHostOverHttps`.
