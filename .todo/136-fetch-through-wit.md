# `rontolisp:fetch` through WIT — delete the biggest hand-written blob

**Status:** open, unstarted. **Large, and the biggest single blob win.** **UNBLOCKED
2026-07-14**: `.todo/133` landed, so `set-method(method)` / `set-scheme(option<scheme>)`
cross the component import boundary (verified against wasmtime's real `wasi:http` host —
`WasmLispCompilerIntegrationTest.componentImportLowersAVariantParameter`). The
user-facing API does not change at all.

Two things todo 133 leaves you:

- `fields.set(name, value: list<list<u8>>)` and `from-list` do NOT cross (a `list<T>`
  argument is still a compile error). Build headers with `fields.append(name,
  value: list<u8>)`, which does.
- The new collision guard (`WasmComponentBuilder.rejectAdapterImportCollisions`) rejects a
  `wit-import` of an interface the blob already imports. So `fetch.lisp` must **replace**
  the `buildHttp` blob's `wasi:http` / `wasi:io` imports, not sit beside them — which is
  the invariant that keeps this migration honest, but it does mean the "keep the WAT
  adapter alive while the Lisp path is built" plan below needs the two paths selected
  exclusively (they already are: `emitHttpImport`).

## What goes away

| artifact | size | what it is |
|---|---|---|
| `adapter-http-client.wasm` | 5.1 KB | hand-written WAT implementing `fetch-start` / `fetch-await` over `wasi:http@0.2` + `wasi:io@0.2` |
| `import-block-http-client.bin` | 5.3 KB | captured component type/import sections for those interfaces |
| `mem-http-client.wasm` | 132 B | the 16-page memory variant fetch needs |
| the `H_*` wiring constants in `WasmComponentBuilder` | ~30 hand-derived indices | re-read out of `wasm-tools dump` every time the blob changes |

That is **~10.5 KB of the ~12 KB of component blobs**, plus a whole `regen.sh` variant and
the `buildHttp` branch. What replaces it is a `fetch.lisp`.

## The shape: a Lisp-source library, exactly like `usocket.lisp`

**Users write nothing new.** `rontolisp:fetch` stays a built-in; there is no `wit-import`
in user code. The precedent is `json.lisp` / `url.lisp` / `usocket.lisp` / `linalg.lisp`:
the compiler notices the program references `rontolisp:fetch` and splices the library in.

```lisp
;;; fetch.lisp -- spliced only when the program calls rontolisp:fetch.
(rontolisp:wit-import <wasi:http/types@0.2.0>)
(rontolisp:wit-import <wasi:http/outgoing-handler@0.2.0>)
(rontolisp:wit-import <wasi:io/streams@0.2.0>)
(rontolisp:wit-import <wasi:io/poll@0.2.0>)

(defun rontolisp:fetch (url &rest options)
  ...)                                  ; the logic now in adapter-http-client.wat
```

The WIT text for a BUILT-IN library cannot be a file path (there is no user file to point
at). Use the internal `rontolisp::%component-import` form, which **already carries the WIT
text inline** — it was built that way for the browser playground (no filesystem), and it
serves this case unchanged.

Scope: the **WASM component leg only**. The interpreter and the JVM keep their
`HttpClient` implementation (`.kb/fetch-http.md`); Preview 1 WASM has no fetch at all
today. So the splice is component-path-only, like `VecLibrary`'s `--no-gc` exclusion.

## The blocker that WAS (`.todo/133`, landed 2026-07-14)

Read function by function against `src/wasm-component/deps/http/types.wit`, the entire
fetch surface crosses the component import boundary **today** — handles, `option<handle>`
(`outgoing-handler.handle`'s `options`), `list<u8>` (`blocking-write-and-flush`), `string`
(`fields.append`, `set-path-with-query`), and the deeply nested
`option<result<result<incoming-response, error-code>, _>>` that
`future-incoming-response.get` returns (results lift recursively).

The exceptions were `outgoing-request.set-method(method)` and `set-scheme(option<scheme>)`,
whose arguments are **variants** — `.todo/133`, and nothing else. Both cross now, verified
against wasmtime's real `wasi:http` host.

## The real risk, and how to retire it

fetch **works today**. This rewrite changes every http-client component's bytes and could
regress a shipped feature. So:

- keep the WAT adapter path alive behind the existing `emitHttpImport` selection while the
  Lisp path is built;
- the oracle is **output parity** against the current adapter across the existing fetch
  E2E (`WasmLispCompilerIntegrationTest`'s fetch cases, the `RONTOLISP_HTTP_E2E=1`
  opt-ins, `examples/net/http-*`), plus the promise API (`fetch-start`/`fetch-await` back
  a `rontolisp:await`-able promise — preserve that, do not quietly make fetch synchronous);
- only then delete the blobs, the `H_*` constants, the `buildHttp` branch and the
  `src/wasm-component` sources.

Also watch the **`--component` + serve + fetch** combination (`http-server-client`), which
exists today as its own blob variant and would collapse into "serve + the fetch library".

## Why it is worth it

This is the **self-hosting test of the whole IDL bet**: `.todo/124` claims a new host
interface should cost a `.wit` file rather than core code. fetch is core code implementing
a host interface. If rontolisp can re-implement its own built-in over its own WIT pipeline,
the claim is demonstrated rather than asserted — and every future host interface arrives
the same way, with no blob, no `regen.sh`, and no hand-derived indices.
