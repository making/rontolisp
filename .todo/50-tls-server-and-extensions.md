# TLS follow-ups: insecure mode (DONE), PEM input (DONE), mutual TLS, WASM support

`rontolisp:tls-connect` (client) and `rontolisp:tls-listen` (server, PKCS12
keystore + password; accepted via the plain `tcp-accept`) were added 2026-07
on the interpreter and JVM backends. Follow-up status:

## Insecure mode (skip certificate verification) -- DONE (2026-07)

Shipped as `(rontolisp:tls-connect host port :insecure value)`; a non-nil
`value` skips cert-chain validation and endpoint identification. Interpreter
uses a trust-all `X509TrustManager`; the JVM makes the generated program class
itself implement `X509TrustManager` (no-arg `<init>` + the three trust methods,
gated on `usesTlsConnect`, kept as extra `--optimize` roots). Details in
`.kb/tcp-sockets.md`.

## PEM certificate/key input -- DONE (2026-07)

Shipped as `rontolisp:tls-listen-pem cert-file key-file port [host]`
(unencrypted PKCS#8 key). PEM parsing (`SocketSupport.pemToKeyStore`, exposed
via `TlsPemSupport`) runs at parse time: the interpreter reads at run time; the
JVM `TlsPemInliner` cli pre-pass parses literal paths at compile time, embeds a
Base64 PKCS12 blob and rewrites to the internal `%tls-listen-p12`. Details in
`.kb/tcp-sockets.md`.

## Mutual TLS (client-certificate authentication) -- TODO

`tls-listen` has no need-client-auth option and `tls-connect` cannot present a
client certificate (`javax.net.ssl.keyStore` system properties would work on
the client side today, undocumented).

## TLS on the WASM backend -- DEFERRED (feasibility re-checked 2026-07)

Correction to the earlier note (which said "wasmtime hosts no TLS for WASI
0.3"): **wasmtime 46 DOES host `wasi:tls@0.3.0-draft`** (a p3, component-model-
async interface over `stream<u8>`), enabled with `-S tls=y` alongside the
existing `-S tcp=y -S inherit-network=y`. Its `connector.send`/`receive`
transform `stream<u8>` <-> `stream<u8>`, the same currency as the existing
`adapter-sock.wat` plumbing, so a component-mode `tls-connect` is technically
feasible without any 0.2 hybrid machinery.

Why it is deferred anyway:

1. **Client-only.** The `wasi:tls` proposal (WASI Phase 1) has only
   `client.wit`; there is NO server/accept interface in any draft. So
   `tls-listen` / `tls-listen-pem` can never work on WASM -- and every TLS
   example in this project (`https-hello.lisp`, `kv-server-tls.lisp`) is a
   server.
2. **Experimental / non-semver.** wasmtime's `crates/wasi-tls/src/p3` says
   verbatim it is "under heavy development ... not ready for production ... no
   patch releases for wasip3 fixes." The WIT will churn between wasmtime
   releases, and no ALPN / client-cert / insecure knobs are exposed yet.
3. **Large effort.** A p3 wasi:tls `tls-connect` needs a new tls import
   instance, the `connector` resource type, ~4 new stream built-ins wiring
   cleartext<->ciphertext through the tcp socket streams, and async `connect`
   lowering under the stackful lift -- comparable in size to the whole tcp
   sockets component feature.

Decision: keep `tls-connect` / `tls-listen` / `tls-listen-pem` a WASM compile
error for now; revisit a component-mode `tls-connect` when `wasi:tls` stabilizes
(leaves Phase 1 / gains a semver-stable wasmtime host). Investigation sources:
wasmtime release-46.0.0 `crates/wasi-tls` (p2 + p3 impls, `src/commands/run.rs`
TLS wiring), `WebAssembly/wasi-tls` (`wit-0.3.0-draft/client.wit`, no server.wit).
