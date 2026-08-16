# 399. `https://` needs a cl+ssl shim over TLS on an ALREADY-CONNECTED stream

Difficulty: High

Found by the dexador spike (`.todo/396`). Every CL HTTP client reaches TLS
through **cl+ssl**, and cl+ssl is a CFFI binding to OpenSSL -- unloadable here
and not worth making loadable:

```
error: .../cffi-20260101-git/cffi.asd: unsupported form in .asd file ...
       (ERROR "Sorry, this Lisp is not yet supported.  Patches welcome!")
```

dexador degrades to `:dexador-no-ssl` (`(error "SSL not supported...")`), which
is what the spike ran with. That leaves `http://` only -- not a useful HTTP
client.

## Why the existing TLS is not enough

rontolisp HAS TLS: `rontolisp:tls-connect` on the interpreter and the JVM
(`.kb/tcp-sockets.md`). It does not fit, because the shape differs:

- `tls-connect host port` opens a NEW connection and hands back a handshaken
  stream.
- cl+ssl's `make-ssl-client-stream stream :hostname h ...` UPGRADES a stream
  that is already connected.

dexador (and every other client) does the second: `usocket:socket-connect` ->
optional proxy CONNECT -> `make-ssl-stream` over that socket's stream. There is
no way to express that over `tls-connect`, so the shim needs a new primitive.

## The work

1. **A TLS-upgrade primitive.** `rontolisp:tls-upgrade stream host &key
   insecure` (name TBD): wrap an already-connected TCP stream in TLS as a
   client, verifying against `host`. On the JVM this is exactly
   `SSLSocketFactory.createSocket(socket, host, port, true)`, so the JVM and
   interpreter halves are small next to the existing `_tlsConnect`
   (`.kb/tcp-sockets.md`); the socket-table plumbing is already there. Both WASM
   backends stay a compile error, as `tls-connect` is today -- see the
   `wasi:tls@0.3.0-draft` note in `.kb/tcp-sockets.md` and `.todo/050`.
2. **A `cl+ssl` shim system** (the `usocket.lisp` / `flexi-streams.lisp`
   pattern in `ShimLibraries`): `ensure-initialized` (no-op), `make-context`,
   `with-global-context`, `+ssl-verify-none+` / `+ssl-verify-peer+`,
   `ssl-check-verify-p`, `use-certificate-chain-file`, and
   `make-ssl-client-stream` over the primitive. Client certificates
   (`:key`/`:certificate`/`:password`) have no backing today: SIGNAL on them
   rather than accept-and-ignore -- silently unauthenticated is worse than a
   message. Same for `:ca-path`, unless the JVM truststore knob
   (`javax.net.ssl.trustStore`, already read per `.kb/tcp-sockets.md`) is wired
   up as its meaning.
3. `dex:*not-verify-ssl*` / `:insecure` must reach the primitive's `insecure`
   flag, so the existing `tls-connect :insecure` semantics carry over verbatim.
4. Pin: a `https://` request against a local TLS server (the `tls-connect`
   tests already stand one up on a background thread) on interpreter and JVM,
   and a clear compile-time message naming the flag on both WASM targets.
5. `.kb/tcp-sockets.md` gets the primitive and the divergence WITH ITS REASON
   (no `wasi:tls` in the pinned wasmtime), so the next visitor can tell whether
   the reason still holds.

## Note

This shim is not dexador-only: drakma, plump-based scrapers, and any
`usocket`+`cl+ssl` client stack want the same two names. Scope it as "cl+ssl,
client side" rather than "dexador's TLS".
