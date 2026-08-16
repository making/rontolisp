# 410. Component-mode TLS over `wasi:tls@0.3.0-draft`

Difficulty: High

`rontolisp:tls-connect` and `rontolisp:tls-upgrade` are a **compile error on
both WASM backends** today (`WasmExprCompiler`), so `https://` -- and with it
the `cl+ssl` shim, and with it every real dexador program -- is
interpreter/JVM only. `.kb/tcp-sockets.md` (the WASM bullet of the TLS
section) and `.todo/050` both record the deferral and its two reasons:
`wasi:tls` is client-only, and it is explicitly experimental / non-semver.

**Decision (2026-08-16): implement it anyway, on the draft interface.** The
client-only half is exactly the half that matters here -- an HTTP *client* is
what `tls-upgrade` exists for -- and shipping against a draft that churns is
accepted, provided the churn is contained where a WIT bump is a file edit and
the divergence is written down. This supersedes the "deferred pending a stable
interface" line in `.kb/tcp-sockets.md` and in `.todo/050`'s "TLS on the WASM
backend" section; both must be rewritten by this item, not left contradicting
the code.

## Scope

- `--component` mode (WASI 0.3) ONLY. Preview 1 stays a compile error --
  there is no wasi:tls for p1, and P1's socket story is `.todo/405`.
- `tls-connect` and `tls-upgrade`: both are clients, both land.
- `tls-listen` / `tls-listen-pem` / `%tls-listen-p12`: stay a compile error on
  every WASM target **forever**, and the error message must say why (the
  proposal has only `client.wit`; there is no server/accept interface in any
  draft). Do not let the message read like a not-yet.

## The shape the work should take

Per `.todo/050`'s re-check, this is NOT compiler work: since the `wit-import`
pipeline landed, a new host interface costs a `.wit` file plus a Lisp library
(`.kb/wasi-component.md`, `.kb/wit.md`). `sockets.lisp`, `http.lisp` and
`examples/wit/keyvalue` are the three existing instances of this shape.

- `wit-import` of wasi-tls's `wit-0.3.0-draft/client.wit`.
- A `tls.lisp` library over the existing stream built-ins. `connector.send` /
  `receive` transform `stream<u8>` <-> `stream<u8>` -- the same currency
  `sockets.lisp` already speaks, so `tls-upgrade`'s "wrap an
  already-connected handle" is the NATURAL operation here (unlike on the JVM,
  where `tls-connect` is the primitive and `tls-upgrade` the derived one).
  Check whether the component-mode `tls-connect` is best expressed as
  `tcp-connect` + `tls-upgrade` rather than as a second entry point.
- The handshaken handle must enter the same stream table as a plain socket, so
  every stream built-in works on it unchanged -- that is the invariant the
  interpreter/JVM sides already hold (`.kb/tcp-sockets.md`).
- `-S tls=y` joins `-S tcp=y -S inherit-network=y` on the run command;
  wasmtime 46+. Every doc/example/README/test-support run line that gains TLS
  needs it, and the wasmtime image floor (`WasmtimeSupport.IMAGE`, >= 47) must
  be re-checked against what the draft interface needs.

## What has no answer on this interface, and must not be faked

`:insecure` (skip verification), client certificates and ALPN are **not
exposed** by the draft. `tls-connect`/`tls-upgrade` accept `:insecure` on the
other two backends, so decide and document ONE of: signal at compile time on
the component path when `:insecure` is non-nil, or signal at run time. Silently
ignoring it would make a program that asks not to verify certificates verify
them -- or the reverse, which is worse. The cl+ssl shim routes
`dex:*not-verify-ssl*` through this argument, so whatever is chosen surfaces
there too and belongs in the shim's docs.

## Definition of done

- An `https://` program compiles and runs on `--component`, verified against a
  real host (the existing `TlsTestSupport` fixture, and `example.com` by hand
  as the tls-upgrade work did).
- `cl+ssl:make-ssl-client-stream` compiles on `--component`; a dexador
  `(dex:get "https://...")` runs there.
- The three-backend claim in `.todo/396`'s baseline table and in the
  `doc/{en,ja}` TLS pages is updated to match, en/ja in the same commit.
- `.kb/tcp-sockets.md`: the WASM bullet rewritten from "deferred" to what
  actually ships, **with the re-evaluation trigger the draft demands** -- name
  the wasi-tls revision and the wasmtime version the import was written
  against, so the next visitor can tell whether a WIT bump is due.
- `.todo/050`'s WASM section reduced to what is still open (the server side,
  permanently; mutual TLS).
- `WasmLispCompilerTest#tlsConnectIsCompileErrorInBothWasmModes` and
  `#tlsUpgrade*` no longer describe the truth -- they must be re-pointed at
  Preview 1 only, not deleted, and the listen-family pins kept as-is.
- `ci-spec.yaml` coverage if the fixture can be reached from the E2E driver;
  if it cannot, say so in the todo history row rather than leaving it silent.

## Parent

`.todo/396` (dexador support) -- this is what makes its "all four backends
over `http://`, interpreter+JVM over `https://`" definition of done widen to
`https://` on the component backend too.
