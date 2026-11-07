# dexador on rontolisp

dexador 0.9.15 loads UNPATCHED through `(ql:quickload "dexador")`. Every dependency is the real
library except three built-in shims: `usocket`, `cl+ssl` (both [tcp-sockets.md](tcp-sockets.md))
and `babel` ([asdf.md](asdf.md), tier 1), plus one derived form, `TrivialMimesForms` (asdf.md,
tier 4). User guide: `doc/{en,ja}/guides/dexador.md`.

## What the shims owe it
- **A socket or a signal, a TLS stream or a signal.** dexador's request loop treats anything it
  gets back as a stream and retries a closed one: `(unless (open-stream-p stream)
  (try-again-without-reusing-stream))` reconnects, then `(go retry)`. A nil from the component's
  primitives (their failure convention) was therefore an endless loop, measured 2026-11-06: a
  refused port ended in `resource table has no free keys`, an untrusted certificate in
  `call stack exhausted`. `usocket:socket-connect`/`-listen`/`-accept` and
  `cl+ssl:make-ssl-client-stream` signal on that nil now (tcp-sockets.md, "The usocket shim" and
  "The cl+ssl shim"). Any new shim entry point a client calls must keep this.
- The verify knob: `dex:*not-verify-ssl*`/`:insecure` arrive as `make-context :verify-mode
  +ssl-verify-none+` under `with-global-context`; `:ca-path` is `:verify-location` and signals,
  as do the `:ssl-key-file`/`:ssl-cert-file` client-certificate keys.
- `listen` asking the kernel or the host (the `:want-stream` drain before a pooled connection is
  reused) and a real read deadline (`:read-timeout`, default 10 s): tcp-sockets.md, "Read
  deadlines".
- trivial-mimes' start-up table from a file readable at run time: the derived form.

## Measured 2026-11-06 (local servers, the exec jar of that day)
GET/HEAD, form/alist, JSON-with-header and multipart bodies, a 302, a gzip body, a cookie jar,
a 404 as `dex:http-request-not-found`, the pooled second request: the same output on the
interpreter, the JVM, `--component` and `--native`. HTTPS against a self-signed local server:
interpreter and JVM verify (the JDK trust store; `javax.net.ssl.trustStore` makes it pass) and
`:insecure t` passes; the component verifies against wasmtime's roots and `:insecure` signals;
`--native` signals at the call. A plain Preview 1 module compiles and the first request signals
naming `--native`. Component run flags: `-S tcp=y -S inherit-network=y -S tls=y`
(`-S tls=y` even for `http://`: the cl+ssl shim is always loaded and imports `wasi:tls`), plus
`-S allow-ip-name-lookup=y` for a host name. No `--dir`.
2026-11-07: an https POST past 16 KiB timed out on the component (`read-byte: Read timed out`, a
50 KB body to httpbin.org) and answers since the TLS output buffer ([tcp-sockets.md](tcp-sockets.md),
"WASM TLS").

## Coverage, and what is not run
- `examples/net/dexador-client.lisp` against `examples/net/httpbin.lisp`: compile legs only
  (`jvm-compile`, `wasm-component`) in `ExamplesE2eTest` -- they quickload dexador from the dist.
- The shim contracts: ci-spec standalone `cl-ssl-shim-verify-context-and-refusals` (four
  backends), the component tests named in tcp-sockets.md, `TrivialMimesE2eTest`.
- **No automatic test sends a dexador request**: that needs the Quicklisp download plus a server,
  and the corpus has neither. Re-measure by hand after touching the socket, TLS or Gray-stream
  layers: a Python `http.server` on 127.0.0.1 plus a program like the example, run on the five
  legs (`.kb/running-backends.md`).
- Non-goals: winhttp (`#+windows`), `:proxy`/SOCKS5.
