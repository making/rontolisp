# `rontolisp:fetch` in a `--native` output

Difficulty: High

A `--native` output refuses `rontolisp:fetch` at compile time, although the same program runs on
the interpreter and the JVM:

```
$ echo '(print (rontolisp:await (rontolisp:fetch "https://example.com")))' > f.lisp
$ ./target/rontolisp f.lisp --native -o f
Error: f.lisp:1:25: rontolisp:fetch is only available in WASM component mode (--component), not Preview 1 WASM
```

(checked 2026-09-25 on macOS aarch64). The module inside a native output is the wasm-GC Preview 1
module, and `WasmFetchCompiler` has no transport there (`.kb/fetch-http.md`). But the runner
is our own host: it already answers the `rlobjc` imports on macOS (`.kb/objc.md`, "--native"),
so it can answer an HTTP import too.

## Goal

`(await (fetch ...))` works in a `--native` output on every native target (linux x86_64 /
aarch64, macos aarch64 / x86_64), with the same observable behaviour the interpreter and JVM
give: status, headers, `:body` as a stream, options validated at `fetch` time, transport
failures at `await`. Pin it in the cross-backend corpus that already covers fetch, not in a
native-only test.

## Starting points

- **Import shape**: `--host-fetch` already lowers fetch onto two `rontolisp:wasm-import`s
  (`env.fetch` head JSON -> response head JSON, `env.readResponseBody` for the streamed body;
  `HostFetchLibrary`, `FetchResponseShape`). Reusing that lowering (under a runner-owned module
  name, the way `ObjcNativeLibrary` names `rlobjc`) keeps one Lisp-side implementation. It is
  currently gated on `--no-wasi`, and `refuseNativeConflicts` rejects `--host-fetch`; a native
  output keeps WASI Preview 1, so the gate has to learn a third case rather than the native
  path pretending to be `--no-wasi`.
- **Transport in the runner**: HTTPS needs TLS. Linux stubs are static musl, so a system TLS
  library is not available there; macOS could use the system stack (CFNetwork / NSURLSession
  through the `rlobjc` machinery, or Security.framework). Measure the stub size each option
  costs (`.kb/native-output.md` records every stub size) before choosing -- a pure-Rust client
  plus rustls on every platform versus a per-OS transport.
- **Blocking**: the Preview 1 future is settled at creation (`TYPE_P1_FUTURE`), so a
  synchronous host call is enough; nothing needs the component model's async.
- **macOS GUI programs**: a blocking request on thread 0 freezes a window the same way a
  blocking stdin read does (`.kb/objc.md`). Decide whether that is acceptable or whether the
  wait should pump events, and record it.
- A program that never references `fetch` must produce a byte-identical module and must not
  grow the stub's start-up path (the `rlobjc` precedent: dlopen on first call).
