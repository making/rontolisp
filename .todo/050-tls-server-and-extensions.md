# TLS follow-ups: insecure mode (DONE), PEM input (DONE), WASM client (DONE), mutual TLS

Difficulty: Medium

`rontolisp:tls-connect` (client) and `rontolisp:tls-listen` (server, PKCS12
keystore + password; accepted via the plain `tcp-accept`) were added 2026-07
on the interpreter and JVM backends; `rontolisp:tls-upgrade` (client TLS over
an ALREADY-CONNECTED handle, the cl+ssl shim's substrate; same `:insecure`
mechanics) followed 2026-08-16 (todo-399). Follow-up status:

## Insecure mode (skip certificate verification) -- DONE (2026-07)

Shipped as `(rontolisp:tls-connect host port :insecure value)`; a non-nil
`value` skips cert-chain validation and endpoint identification. Interpreter
uses a trust-all `X509TrustManager`; the JVM makes the generated program class
itself implement `X509TrustManager` (no-arg `<init>` + the three trust methods,
gated on `usesTlsConnect`, kept as extra `--optimize` roots). Details in
`.kb/tcp-sockets.md`. NOTE: on the WASM component (below) `:insecure` has no
backing -- a non-nil value SIGNALS at run time (`wasi:tls@0.3.0-draft` exposes
no verification knob).

## PEM certificate/key input -- DONE (2026-07)

Shipped as `rontolisp:tls-listen-pem cert-file key-file port [host]`
(unencrypted PKCS#8 key). PEM parsing (`SocketSupport.pemToKeyStore`, exposed
via `TlsPemSupport`) runs at parse time: the interpreter reads at run time; the
JVM `TlsPemInliner` cli pre-pass parses literal paths at compile time, embeds a
Base64 PKCS12 blob and rewrites to the internal `%tls-listen-p12`. Details in
`.kb/tcp-sockets.md`.

## TLS clients on the WASM component -- DONE (2026-08-16, todo-410)

`tls-connect` / `tls-upgrade` run under `--component` over a wit-imported
`wasi:tls@0.3.0-draft` (`eval/tls.wit` + `eval/tls.lisp`, spliced by
`eval/TlsLibrary`; run with `-S tls=y`). The upgrade is the primitive there
(the host `connector` transforms wrap the socket's own streams, which is why
sockets.lisp defers its send-side plumbing to the first write), the entry is
upgraded IN PLACE (same fd), failures answer nil, and the interface's draft
status is contained: a WIT bump is a file edit, with the re-evaluation trigger
written into `.kb/tcp-sockets.md` (vendored from wasmtime v47.0.2). Preview 1
keeps the compile error (no wasi:tls host API exists for p1).

## TLS servers on WASM -- PERMANENTLY OUT (not a deferral)

The `wasi:tls` proposal defines only `client.wit`; there is no server/accept
interface in any draft, so `tls-listen` / `tls-listen-pem` are a compile error
on every WASM target and the `WasmExprCompiler` message says so ("client-only
by design"). Re-open ONLY if the proposal ever grows a server interface (the
re-evaluation trigger in `.kb/tcp-sockets.md` covers this).

## Mutual TLS (client-certificate authentication) -- TODO

`tls-listen` has no need-client-auth option and `tls-connect` cannot present a
client certificate (`javax.net.ssl.keyStore` system properties would work on
the client side today, undocumented). On the WASM component the draft exposes
no client-identity API either, so this is interpreter/JVM work when it comes;
the cl+ssl shim signals on `:key`/`:certificate`/`:password` until then.
