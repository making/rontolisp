# Upgrade `rontolisp:fetch` (and serve) from wasi:http@0.2 to async wasi:http@0.3

**Status:** blocked on upstream. Tracked here so the hybrid arrangement is not forgotten.

## Why HTTP is on 0.2 today

The `--component` output is a native WASI 0.3 (Preview 3) component for every
interface area **except HTTP**. A program that does HTTP -- outgoing
(`rontolisp:fetch`) or incoming (`rontolisp:http-handler`) -- is a **hybrid**:
the base I/O (cli / clocks / filesystem / random) is WASI 0.3, but it
additionally imports **`wasi:http@0.2` + `wasi:io@0.2` (poll/streams)**, driven
synchronously by `pollable.block`.

This is because **async `wasi:http@0.3` does not exist upstream yet**:

- The `WebAssembly/wasi-http` repo's `v0.3.0-rc-*` tags and `main` are all still
  packaged as `wasi:http@0.2.7` / `0.2.8`, using `wasi:io/streams@0.2`
  (`input-stream`/`output-stream` resources), `wasi:io/poll`, and a
  resource-based `future-incoming-response` -- **not** the component-model
  `stream<u8>` / `future<T>` async ABI.
- `wasmtime` 46 only hosts `wasi:http@0.2` (`-S http=y`).

So there is no async http ABI to target and no host to run it. (Upstream last
checked 2026-06-25; unchanged as of this rewrite.)

## How HTTP arrives today -- the arrangement to preserve (NOT WAT adapters any more)

This is the part the old version of this file got wrong. The hand-written WAT
adapters and the core `http.fetch-start`/`fetch-await` seam are **gone** (todo
136 for fetch, todo 135 for serve). Both halves are now **Lisp over
`canon lower`ed `wasi:http` user imports** -- there is no HTTP-specific blob or
codegen to migrate, only a WIT file and a type mapping:

- `fetch` = **`src/main/resources/am/ik/rontolisp/eval/fetch.lisp`** (spliced by
  `eval/FetchLibrary` when a `--component` program references `rontolisp:fetch`),
  which `rontolisp:wit-import`s `wasi:io/{poll,error,streams}` +
  `wasi:http/{types,outgoing-handler}` from **`fetch.wit`** and drives them from
  Lisp. `WasmFetchCompiler` / `WasmAwaitCompiler` are now compile-time validators
  that fall through to the `fetch.lisp` defun (their `FUNC_FETCH_*` core slots are
  permanent trap stubs).
- serve = **`serve.lisp`** (spliced by `eval/ServeLibrary`), which imports
  `wasi:io/{error,streams}` + `wasi:http/types` from the **same `fetch.wit`** and
  EXPORTS `wasi:http/incoming-handler`. serve+fetch splices both libraries over
  one merged `wasi:http/types` import.
- The lowering machinery is the generic one every `rontolisp:wit-import` uses:
  `WasmComponentImportCompiler` (guest-side canonical-ABI marshalling of the flat
  0.2 types) + `WasmServeComponentBuilder` / `WasmComponentBuilder.appendUserImports`
  (the component-level `canon lower`). `.kb/wit.md`, `.kb/fetch-http.md`.

So "on 0.2" now means exactly two things: **`fetch.wit` describes `wasi:http@0.2`
/ `wasi:io@0.2`**, and **`WasmComponentImportCompiler` can only marshal the flat
(non-async) canonical types** those interfaces use. The migration is contained to
those two.

## What to do when upstream ships async wasi:http@0.3

1. **Swap the WIT.** Replace `fetch.wit`'s `wasi:http@0.2` / `wasi:io@0.2`
   definitions with the async `wasi:http@0.3.0` WIT (+ any deps). The bodies
   become `stream<u8>`, the response becomes `future<...>`, and `wasi:io` (poll /
   streams / error) disappears -- the async built-in types subsume it. Adjust
   `fetch.lisp` / `serve.lisp` to the new shapes (e.g. `handle` returning a
   `future<incoming-response>` read with `future.read` instead of
   `pollable.block` + `future-incoming-response.get`; bodies read/written through
   `stream.read`/`stream.write` instead of `input-stream`/`output-stream`). The
   promise API (`then`/`await` over the returned future) is unaffected in shape.
   Re-check `%fetch-method-variant` / `%fetch-scheme-keyword` /
   `%serve-method-string` against the 0.3 `method` / `scheme` variants.

2. **Teach `WasmComponentImportCompiler` the async built-ins -- the real work,
   and the shared enabler.** The user `canon lower` path marshals only the FLAT
   canonical types today (prim / `list<u8>` / handle / option / result / variant /
   record / tuple); it has no case for `stream<u8>` or `future<T>`, whose lowering
   is a different mechanism -- the **async canonical built-ins**
   `stream.new` / `stream.read` / `stream.write` (+ the readable/writable drops)
   and `future.new` / `future.read`. The byte encoders for these ALREADY EXIST
   (`am.ik.wasm.ComponentWriter.canonStream{New,Read,Write,DropReadable,DropWritable}`
   / `canonFuture{New,Read}`), but the ONLY place that wires them is
   `WasmComponentBuilder`'s hand-assembled base adapter (`adapter.wat`'s `"w"`
   group), with hardcoded stream/future type indices and per-error-code future
   built-ins (`-cli` vs `-fs`). `WasmComponentImportCompiler` (and the type gate /
   `appendUserImports` around it) must:
   - recognize a `stream<u8>` / `future<T>` in a wit-import function's params /
     results (as it recognizes `list<u8>` = byte string today), deriving the
     stream/future component-type index from the WIT rather than hardcoding it;
   - emit the matching `canon stream.*` / `future.read` lowering into the guest,
     and generate the Lisp-visible read/write wrappers `fetch.lisp` / `serve.lisp`
     call -- the async counterpart of the flat marshalling in
     `WasmComponentImportCompiler.Gen`.
   This is why 0.3 http is worth doing THROUGH the wit-import pipeline rather than
   a bespoke adapter: **the same async-canon-lower support is the prerequisite for
   two other cleanups** --
   - **externalizing sockets as a Lisp library** (a `sockets.lisp` over a
     `wit-import`ed `wasi:sockets@0.3`, deleting `adapter-sockets.wat`, the fetch
     pattern applied to TCP -- held today for exactly this reason, see the serve
     todo's session notes); and
   - **eventually deleting `adapter.wat`** itself: it is the preview1-to-0.3 base
     adapter, and the ONLY thing hand-wiring the async built-ins today (it
     implements `fd_write`/`fd_read`/`path_open`/`fd_close` over
     `stream.new`/`read`/`write` + `future.read` over `wasi:cli` + `wasi:filesystem`).
     Once the canon-lower path speaks the async ABI, that plumbing could be Lisp
     too.

3. **Drop the flags/notes that were only for 0.2.** Remove the `-S http=y`
   requirement note once the host no longer gates HTTP behind it, and the
   "`wasi:io` 0.2 island" language in `.kb/fetch-http.md` (the component becomes
   uniformly 0.3).

No core seam, `WasmFetchCompiler`, `WasmAwaitCompiler`, `buildHttp`,
`adapter-http-client.wat`, or import-block regeneration is involved any more --
those were deleted with the WAT adapters. The migration is a WIT swap plus one
new capability in the shared `canon lower` path.
