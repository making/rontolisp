# TLS follow-ups: insecure mode, PEM input, mutual TLS, WASM support

`rontolisp:tls-connect` (client) and `rontolisp:tls-listen` (server, PKCS12
keystore + password; accepted via the plain `tcp-accept`) were added 2026-07
on the interpreter and JVM backends. Remaining work:

## Insecure mode (skip certificate verification)

`(rontolisp:tls-connect host port :insecure t)`-style opt-out for dev /
self-signed servers. Interpreter is trivial (trust-all `X509TrustManager`,
no endpoint identification). The JVM backend cannot emit an anonymous
TrustManager class: the clean route is to make the generated program class
itself implement `X509TrustManager` (3 methods + a no-arg `<init>`, all
gated on the program using the insecure form) and pass `new Prog()` to
`SSLContext.init`. Until then the documented workaround is the
`javax.net.ssl.trustStore` system properties, which `_tlsConnect` re-reads
per call.

## PEM certificate/key input for tls-listen

`tls-listen` takes a PKCS12 keystore because PKCS12 keeps both backends on
plain `KeyStore` calls; PEM (what certbot etc. hand out) requires parsing
(Base64 + `PKCS8EncodedKeySpec` + a KeyFactory-algorithm loop) that is
sizable in hand-assembled JVM bytecode — consider whether the
template-class route is justified (`.kb/template-class-embedding.md`), or
document `openssl pkcs12 -export` as the permanent answer.

## Mutual TLS (client-certificate authentication)

`tls-listen` has no need-client-auth option and `tls-connect` cannot present
a client certificate (`javax.net.ssl.keyStore` system properties would work
on the client side today, undocumented).

## TLS on the WASM component backend

Blocked on the host: `wasi:tls` is a 0.2-draft proposal and wasmtime's
support (behind `-S tls`) targets the 0.2 stream world, while our sockets
component is pure WASI 0.3 (`.kb/wasi-component.md`). Revisit when a
p3-native wasi:tls lands in wasmtime; until then the tls built-ins are a
compile error in both WASM modes (`WasmExprCompiler`).
