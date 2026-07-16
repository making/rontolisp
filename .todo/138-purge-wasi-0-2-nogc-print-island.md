# 138. Purge the last WASI 0.2 island: the `--no-gc` print micro-adapter -> 0.3

**Decision (user, 2026-07-16):** accept that a PRINTING `--no-gc --component`
program requires the async wasmtime flags, in exchange for deleting every
WASI-0.2-era surface from the repo. With the wasi:http@0.3 cutover done
(`.todo/02` Phase 2), the nogc-print blob set is the ONLY remaining consumer of
`wasi:io@0.2` / `wasi:cli@0.2`.

**UPDATED 2026-07-17 for the todo-139 callback-async cutover (`6dc8d12`..
`d89df5b`), which landed AFTER this plan was written and improves its
trade-off:** the sync stream/future built-ins this plan budgeted the
`-W component-model-more-async-builtins=y` flag for are the 🚝-gated feature
the cutover ERADICATED; the replacement -- the ASYNC built-in variants plus a
blocking `waitable-set.wait` park -- is BASE component-model-async
(default-on in wasmtime 46, enabled by wasmCloud), legal from an async-typed
(0x43) plain lift, which blocks legally (todo-139 spike findings 1 and 8; the
GC `adapter.wat` cli path has been exactly this shape since Phase 6). So the
printing `--no-gc` component keeps **ZERO run flags** on wasmtime 46+ -- the
flag concession this todo planned to make is no longer needed. The flag
mentions below are corrected in place; the jco consequence (async lift =
uncallable from jco 1.25.2) is unchanged. NOTE: the sync
`canonStream/FutureRead/Write(+Utf8)` and stackful-lift encoders were DELETED
from `am.ik.wasm.ComponentWriter` in `d89df5b` -- bind the `*Async` variants
(+ `canonWaitableSetNew/Wait/Drop`, `canonWaitableJoin`); do not resurrect
the sync ones.

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

This todo was written to trade both away deliberately. Post-cutover (the
2026-07-17 note above) only trade 2 is actually lost: the async built-in +
`waitable-set.wait` pattern keeps the run flag-free on wasmtime 46+, and what
rises is the wasmtime FLOOR for a printing component (46+, where the 0.2-era
zero-flag artifact ran on much older hosts). The jco consequence stands.

## What changes

A printing `--no-gc --component` program's exports become **async lifts** (the
`0x43` functype, the GC `wasm-export :async t` shape -- plain `canonLift`
against an async functype, core signature UNCHANGED, results returned directly;
NOT the deleted stackful `async` canon option), because only an async-typed
task may block -- and the new bridge blocks in `waitable-set.wait` after the
ASYNC stream/future built-in variants (the post-cutover `adapter.wat` cli
pattern), all of which is base component-model-async. Run flags stay:

```
wasmtime run --invoke 'f(...)' prog.wasm    # wasmtime 46+ (cm-async default-on)
```

(no `-W gc` -- this is the no-gc backend; no async flag -- the cutover removed
the gated sync built-ins this plan originally budgeted
`-W component-model-more-async-builtins=y` for). A **print-free** `--no-gc`
component embeds nothing from the blob set and MUST stay byte-identical and
flag-free -- that is the existing gating rule (`NoGcWasmComponentBuilder`
selects the print set only when the program prints) and the acceptance bar
below.

## Work list

1. **Bridge over 0.3** -- rewrite the nogc-print helper WATs
   (`shim-nogc-print.wat` / `bridge-nogc-print.wat` / `fixup-nogc-print.wat`):
   `fd_write` (fd 1 only) = `stream-new` -> `stdout write-via-stream(readable)`
   -> async `stream-write` (+ `$await_waitable` park on BLOCKED) per iovec ->
   `stream-drop-w` -> async `future-read-cli` (+ park) + `future-drop-cli` --
   the POST-CUTOVER base `adapter.wat` cli path verbatim, including its
   per-adapter cached waitable-set + scratch slot (pick a scratch address
   compatible with the nogc layout). The
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
   `definedResultErr(cli error-code)` + `definedFuture`, bound via the ASYNC
   encoders `canonStreamWriteAsync`/`canonFutureReadAsync` plus the waitable
   trio -- the sync encoders no longer exist, see the 2026-07-17 note above).
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
7. **Docs** (en+ja mirrored): the `--no-gc` print sections KEEP the zero-flags
   claim (the wasmtime floor becomes 46+ for a printing component -- cm-async
   default-on -- state that instead); exports flip to async lifts (jco note);
   `.kb/no-gc-scalar-wasm.md`, `.kb/wasm-export-no-wasi.md`,
   `src/wasm-component/README.md` ("pure WASI 0.2" section), `.todo/112` gets a
   pointer noting its Finding-2 premise (0.3 print = gated flags) was dissolved
   by the todo-139 cutover, and only its jco half survives.

## Consequences to state in the docs

- **jco**: a printing `--no-gc` component's exports become async-lifted, which
  jco (1.25.2) cannot call -- the same limitation the GC `:async t` exports
  already have. The browser/webgl demos are unaffected (they run the
  Preview-1 module path with hand-written import objects, not components).
- Run flags: printing `--no-gc` components STAY at zero flags (post-cutover
  design; the wasmtime floor rises to 46+, where cm-async is default-on).
  Print-free ones are byte-identical and run on the old floor.

## Acceptance

- `rg '0\.2\.0' src/wasm-component src/main` hits nothing but
  `wasi:keyvalue@0.2.0-draft` (+ historical comments that explicitly say 0.2 is
  gone).
- A print-free `--no-gc --component` artifact is byte-identical to pre-todo.
- The printing `--no-gc` integration tests pass under a FLAG-FREE
  `wasmtime run` (wasmtime 46+, Docker suite), and
  `wasm-tools validate -f component-model,cm-async` stays green (the
  todo-139 validation recipe; `--features all` also passes).
- Full suite + native `CiSpecE2eTest` green (the ci-spec corpus has no
  component-print case, but the native binary compiles the blob set).
