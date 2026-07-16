# 138. Purge the last WASI 0.2 island: the `--no-gc` print micro-adapter -> 0.3

**Decision (user, 2026-07-16):** accept that a PRINTING `--no-gc --component`
program requires the async wasmtime flags, in exchange for deleting every
WASI-0.2-era surface from the repo. With the wasi:http@0.3 cutover done
(`.todo/02` Phase 2), the nogc-print blob set is the ONLY remaining consumer of
`wasi:io@0.2` / `wasi:cli@0.2`.

## Why the island exists (do not skip -- this reverses a recorded decision)

`.todo/112` (WILL NOT DO, 2026-07-13) established that **WASI 0.3 has no
synchronous write, by design** -- `wasi:cli/stdout@0.3.0` is
`write-via-stream: func(data: stream<u8>) -> future<result<_, error-code>>`,
and the stream/future built-ins block, which only an async TASK may do. The
nogc-print micro-adapter (todo 93) therefore stayed on 0.2's synchronous
`output-stream.blocking-write-and-flush` so that:

1. a printing `--no-gc` component runs under wasmtime with **zero flags**, and
2. its exports stay SYNC lifts (callable from jco, todo-92-era finding: jco
   1.25.2 cannot call async-lifted exports).

This todo trades both away deliberately. 112's Finding 1 (the island is
permanent, not a waiting game) is exactly why deleting it costs the flags: the
island cannot expire on its own.

## What changes

A printing `--no-gc --component` program's exports become **async lifts** (the
`0x43` functype, the GC `wasm-export :async t` shape -- plain `canonLift`
against an async functype, core signature UNCHANGED, results returned directly;
NOT the serve-style stackful `async` canon option, so
`-W component-model-async-stackful=y` is NOT needed), because the 0.3
stream/future built-ins the new bridge calls may only block inside an async
task. Run flags become:

```
wasmtime run -W component-model-more-async-builtins=y  --invoke 'f(...)' prog.wasm
```

(no `-W gc` -- this is the no-gc backend; the sync stream/future built-ins are
what the flag gates). A **print-free** `--no-gc` component embeds nothing from
the blob set and MUST stay byte-identical and flag-free -- that is the existing
gating rule (`NoGcWasmComponentBuilder` selects the print set only when the
program prints) and the acceptance bar below.

## Work list

1. **Bridge over 0.3** -- rewrite the nogc-print helper WATs
   (`shim-nogc-print.wat` / `bridge-nogc-print.wat` / `fixup-nogc-print.wat`):
   `fd_write` (fd 1 only) = `stream-new` -> `stdout write-via-stream(readable)`
   -> `stream-write` per iovec -> `stream-drop-w` -> `future-read-cli` +
   `future-drop-cli` -- the base `adapter.wat` cli path verbatim. The
   shim/fixup trampoline pattern stays: the core EXPORTS its own memory (no
   shared mem module on this variant) and the lowered import + the built-ins
   need that memory, the same instantiate-before-memory cycle the trio exists
   for today -- only the imported function set changes.
2. **World + block**: `uni-nogc-print.wit` -> `import wasi:cli/stdout@0.3.0;`
   (pulls `wasi:cli/types@0.3.0` implicitly for the error-code enum);
   `core-nogc-print.wat` stub re-derived. `regen.sh` regenerates
   `import-block-nogc-print.bin`; re-derive every wiring constant in
   `NoGcWasmComponentBuilder` from a fresh `wasm-tools dump` (instance indices,
   the first-free type index, the stream/future built-in canons -- model on
   `WasmServeComponentBuilder`'s cli block: `definedStream(u8)` +
   `definedResultErr(cli error-code)` + `definedFuture`).
3. **Async lift**: `NoGcWasmComponentBuilder` lifts every export of a PRINTING
   program against the async functype (`asyncFuncTypeScalars` /
   `asyncFuncTypeOf`); a print-free program's sync lifts are untouched.
   Decide + pin: post-return still fires after an async lift (the GC todo-92
   finding says yes -- "post-return survives async").
4. **Delete the 0.2 deps**: `src/wasm-component/deps/io-0.2`,
   `src/wasm-component/deps/cli-0.2`. After this, `rg '@0\.2\.0'` over
   `src/wasm-component` must only hit `wasi:keyvalue@0.2.0-draft` mentions --
   which are OUT OF SCOPE (a host-defined DRAFT interface version wasmtime
   itself ships, not WASI-0.2-era io; the examples and docs keep it).
5. **Fixtures + definitions**: `regen-wit.sh` (the nogc-print capture),
   `WasiWitDefinitions` regeneration (drops `wasiIoV020NogcPrint` /
   `wasiCliV020NogcPrint`), `WitEmitterTest` / `WasiWitDefinitionsTest`.
6. **Tests**: `NoGcWasmCompilerTest` (38 "0.2" mentions today) + the
   integration tests that run printing `--no-gc` components gain the flag and
   the async-functype expectations; add one pin that a print-free component is
   byte-identical to before this todo.
7. **Docs** (en+ja mirrored): the `--no-gc` print sections lose the
   "zero flags" claim and gain `-W component-model-more-async-builtins=y`;
   `.kb/no-gc-scalar-wasm.md`, `.kb/wasm-export-no-wasi.md`,
   `src/wasm-component/README.md` ("pure WASI 0.2" section), `.todo/112` gets a
   pointer noting its trade-off was consciously reversed here.

## Consequences to state in the docs

- **jco**: a printing `--no-gc` component's exports become async-lifted, which
  jco (1.25.2) cannot call -- the same limitation the GC `:async t` exports
  already have. The browser/webgl demos are unaffected (they run the
  Preview-1 module path with hand-written import objects, not components).
- Run flags: printing `--no-gc` components go from ZERO flags to
  `-W component-model-more-async-builtins=y`. Print-free ones stay at zero.

## Acceptance

- `rg '0\.2\.0' src/wasm-component src/main` hits nothing but
  `wasi:keyvalue@0.2.0-draft` (+ historical comments that explicitly say 0.2 is
  gone).
- A print-free `--no-gc --component` artifact is byte-identical to pre-todo.
- The printing `--no-gc` integration tests pass under
  `wasmtime run -W component-model-more-async-builtins=y` (Docker suite), and
  `wasm-tools validate --features all` stays green.
- Full suite + native `CiSpecE2eTest` green (the ci-spec corpus has no
  component-print case, but the native binary compiles the blob set).
