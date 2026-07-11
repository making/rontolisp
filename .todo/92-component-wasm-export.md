# 92. Host-callable `wasm-export` under `--component` (WASI 0.3 component exports)

## STATUS: Tiers 1+2+3 DONE (2026-07-11); only the optional `.wit` output remains

Tier 3 (`:async t` I/O exports) is implemented and verified: print and fetch
inside a component export work under `wasmtime run --invoke` instead of
trapping. Pure-compute exports stay sync (byte-identical output).

- **Open decisions taken**:
  - Selection = an explicit **`:async t` directive option**, NOT automatic I/O
    call-graph detection: funcall/apply route through the arity dispatchers, so
    a conservative reachability analysis would flip nearly every export async
    and silently break the sync byte-identity guarantee; explicit opt-in also
    matches the WIT contract (the author declares `async func`).
  - WIT visibility / callability was measured FIRST via a wasm-tools
    print -> sed -> parse round trip on a Tier-2 component (before any Java
    change): flipping ONLY the export functype to `(func async ...)` makes
    wasmtime 46 run the export as a stackful async task -- print inside works,
    `--invoke` renders the result normally, run co-exists, `wasm-tools
    validate` (cm-async flags) passes.
  - `:string` x async: the premise "async lifts have no post-return" turned out
    NOT to apply -- this is a stackful lift via the async TYPE with plain sync
    canonical options (like `run`), not a `canon lift async` callback lift, so
    wasmtime accepts and calls the Tier-2
    memory/realloc/utf8/**post-return** options unchanged. The CABI_MARK
    saved-mark reclamation needed NO replacement.
  - Slicing collapsed: scalar and `:string` async both ride one mechanism (the
    async flag on the Decl + one async type encoder), implemented together.
- **Implementation** (deliberately small): `WasmExportCompiler.Decl` gained an
  `async` flag (`:async t/nil`, quoted forms accepted; anything else = clear
  error), `ComponentWriter.asyncFuncTypeScalars` encodes the async functype
  (tag 0x43; sync golden bytes with the tag flipped, pinned in
  `ComponentWriterTest`), `WasmComponentBuilder.appendFuncExports` picks the
  async type per export. The CORE MODULE is untouched by `:async` -- the
  component type section is the only byte difference. Preview 1 / `--no-wasi`
  / plain `--no-gc` ignore the option; `--no-gc --component` rejects it
  (`NoGcWasmCompiler.validateComponentExport`: no async adapter in the
  reactor); serve unaffected.
- **fetch call-site bug found + fixed en route** (blocking fetch-in-export):
  `WasmFetchCompiler` read the URL/body string's field 0 as a linear pointer,
  but since the [[27]] GC-string redesign that id is an IDENTITY (counter for
  runtime-built strings) -- only the accidentally-aligned FIRST fresh string
  worked (two fresh URLs = silent wrong-URL fetch, demonstrated 204/200 swap;
  an export's `_str_from_mem` argument never worked). Now the call site stages
  URL + body via `_str_to_mem` into heap scratch (new fixed cells
  `FETCH_URL_PTR/LEN_ADDR`, `FETCH_REQ_BODY_PTR/LEN_ADDR` at 0x4001C-0x40028),
  ADVANCES HEAP_PTR past the copies so `_fetch_ser_headers`' scratch cannot
  clobber them, and pops it after `fetch-start` returns. Headers were already
  staged correctly. This changes the bytes of every fetch-using component
  (bug fix); export-free non-fetch shapes stay byte-identical.
- **Byte identity** proven by stash dance across ELEVEN shapes: export-free
  base/sock/serve components + Preview 1, scalar-export component + Preview 1,
  `:string` component + Preview 1, `--no-gc --component` `:string` -- all
  byte-identical; the http and serve+fetch components differ exactly by the
  fetch staging fix (verified semantically: run 204, serve+fetch curl 204,
  POST body/header round trips).
- **Verified** (wasmtime 46.0.1): `--invoke` on an `:async t` scalar export
  with print inside ("42\n42"), `:async` `:string` with print (UTF-8 世界
  round trip), fetch(:string url)->:int inside an async export with
  `-S http=y` (httpbin 204 + local-server E2E), sync+async mixed in one
  component, run co-existence, sync-export-with-I/O still traps (pinned),
  `:async nil` byte-identical to omitted.
- **jco 1.25.2 / Node 23.7 (JSPI)**: transpile works and TYPES the async
  export correctly (`noisyMul(p0, p1): Promise<number>` vs sync
  `pureAdd(...): number`; needs `@bytecodealliance/preview3-shim`), sync
  exports still call fine, but CALLING an async export fails upstream: jco's
  generated `_driverLoop` assumes callback-style async ("failed to unpack
  callback result ... invalid async return value [13]" -- the flat result
  misread as a callback code); stackful async exports are unimplemented in
  jco. Same gap family as printing inside a transpiled `run`
  (`FutureReadableEnd is not defined`, reproduced on an UNCHANGED Tier-2-shape
  component). wasmtime `--invoke` is the documented path for async exports.
- Tests: `ComponentWriterTest.asyncFuncTypeScalarsEncoding`,
  `WasmExportCompilerTest` (parse, async-vs-sync type bytes, `:async nil`
  identity), `NoGcWasmCompilerTest.componentRejectsAsyncExport`,
  `WasmLispCompilerIntegrationTest.componentAsyncExport*` +
  `componentSyncExportWithIoStillTraps` +
  `componentFetchWithRuntimeBuiltUrls` + `componentFetchInsideAsyncExport`
  (last two opt-in `RONTOLISP_HTTP_E2E=1`), ci-spec
  `wasm-export-directive-does-not-disturb-run` extended with an `:async`
  export.

Remaining (optional): emit a generated `.wit` world so hosts / jco can
generate bindings without introspecting the component; revisit jco once
stackful async exports land upstream.

## Historical record: Tiers 1+2 (DONE 2026-07-11)

Tier 2 (`:string`/`:s-expr` via the canonical string ABI) is implemented and
verified -- same UX as todo-93 Tier 2, just with the GC flags:
`(rontolisp:wasm-export 'greet :params '(:string) :returns :string)` becomes
`func(p0: string) -> string` and `wasmtime run -W gc=y
-W component-model-more-async-builtins=y --invoke 'greet("世界")' comp.wasm`
round-trips the string.

- **Memory identity** (open decision resolved): the GC component's lift uses
  `(memory 0)` = the shared mem.wasm memory aliased at build start -- the SAME
  instance the rontolisp core imports (`(import "mem" "memory")`), so the
  wrapper's `(ptr,len)` and the host-lowered argument bytes share one address
  space. Confirmed on `wasm-tools print` of a built component.
- **cabi_realloc** (resolved): mem.wasm's own `cabi_realloc` (core func 0) was
  NOT reused for the lift -- its bump pointer `$hp` is a private global in the
  base blob (only mem-http.wat exports `hp`, for the serve reset), so a
  post-return could never rewind it (would need blob regen), and it has no
  grow guard. Instead `WasmLispCompiler` appends a `cabi_realloc` to the core
  module itself that delegates to the existing grow-guarded `__ronto_alloc`
  (host-lowered argument bytes land in the core's own bump heap) and is
  core-exported + aliased from instance 3 by `appendFuncExports`.
- **Retptr shim** (resolved): appended to the core module (todo-93 pattern; no
  separate core module needed). GC-specific twist: the record is allocated
  through `cabi_realloc` BEFORE the wrapper call -- the GC wrapper stages its
  string result at the un-advanced `HEAP_PTR` scratch (`emitStringResult`), so
  allocating the 8-byte record after the call would overwrite the staged
  bytes. `MAX_FLAT_RESULTS = 1` applies identically to GC.
- **Heap reclamation** (resolved): todo-93's "pop to heapBase" is impossible on
  GC (permanent intern advances + cross-call state), so the post-return is a
  **per-call saved-mark restore, intern-count-guarded** (the serve-reset
  pattern): the appended `cabi_realloc` snapshots `HEAP_PTR` + the runtime
  intern count into the `CABI_MARK_ACTIVE/HEAP/INTERN` cells (160/164/168,
  zero-initialized = inactive) on its FIRST call of an invocation; `cabi_post_*`
  restores `HEAP_PTR` only when the intern count is unchanged (interned tokens
  are permanent heap copies referenced in place -- popping them would dangle
  the intern records), then clears the mark. A call that interns simply keeps
  its allocations (ratchet; correctness verified on a resident jco instance
  mixing `intern` + `:s-expr` reads). Measured flat: jco/Node 23 (JSPI),
  10k calls with a 100KB `:string` argument = +28MB RSS total (V8-side noise);
  an unfreed linear heap would be ~1GB. 1M small calls: RSS 69->79MB,
  JS heapUsed flat at 6MB.
- **`:s-expr` included** (resolved): lifts as WIT `string` carrying the printed
  s-expression; rides the exact same ABI (`componentPostReturnKind` maps it to
  the i32 retptr kind). The only extra wiring was un-gating `exportNeedsReader`
  for component non-serve (`WasmLispCompiler`) and `componentValType(T_S_EXPR)
  -> VT_STRING`. Verified: `swap("(1 2)")` -> `"(2 1)"`, `sum-expr("(40 2)")`
  -> 42, including on a resident jco instance (per-call reader interning
  ratchets, stays correct).
- **Index bases** (resolved): the fixed `appendFuncExports` bases (23/24/12,
  http 53/46/33, sock 34/31/19) are unchanged; when a string export is present
  the realloc + post-return aliases are inserted BEFORE the per-export aliases
  (core-func space only; types/lifts/exports stay 1:1 with the export
  ordinal). Existing Tier-1 tests needed no changes.
- Byte-identity proven by stash dance across NINE artifact shapes: export-free
  base/http/sock/serve components + Preview 1, scalar-export-only component +
  Preview 1, `--no-gc --component` `:string` (todo 93 untouched), Preview 1
  `:string` module.
- Verified: wasmtime 46.0.1 `--invoke` for `:string`->`:int`,
  `:string`->`:string` (UTF-8 multi-byte 世界), no-arg -> `:string`,
  `:string`->void, `:s-expr` both ways, 120KB string round trip (memory.grow
  path), co-existence with `run`; http (fetch) and sock variants with a
  `:string` export validate under `wasm-tools validate`; jco 1.25.2 transpile
  + Node 23 (`--experimental-wasm-jspi`, JSPI needed for the stackful-async
  `run` lift, Node 22 lacks it) string round trip.
- Tests: `WasmExportCompilerTest` (structural: string ABI helpers appear for a
  :string export, absent otherwise; post-return sharing per signature),
  `WasmLispCompilerIntegrationTest` (WAVE E2E incl. UTF-8 + `:s-expr` + run
  co-existence), ci-spec `wasm-export-directive-does-not-disturb-run` extended
  with a `:string` export.

Remaining: Tier 3 (async-lifted I/O exports + generated `.wit`), below.

Tier 1 (scalar `:int`/`:float`/`:bool`/void, synchronous `canonLift`) is
implemented and verified:

- Decisions taken: co-exist with `run` (no reactor `_initialize` needed -- the
  active data segments seed HEAP_PTR/intern-base at instantiation, so
  pure-compute exports work without `_start`); NO blob regen (verified: exports
  are appended programmatically as SEC_ALIAS/SEC_TYPE/SEC_CANON/SEC_EXPORT after
  the `run` wiring; an export-free program's component is byte-identical,
  proven by stash dance for base/http/serve/P1 variants).
- Wiring: `WasmLispCompiler` un-gates the wrapper build + core-exports the
  wrappers in component mode; `WasmExportCompiler.componentExport` maps
  designators to `ComponentWriter.VT_*` (NOTE: f64 = 0x75, 0x74 is `char` --
  the one encoder bug hit); `WasmComponentBuilder.appendFuncExports` emits the
  per-export sync lift (base next-free indices: core func 23 / type 24 /
  component func 12; http 53/46/33; sock 34/31/19). New encoder:
  `ComponentWriter.funcTypeScalars` (+ `VT_F64`), pinned in
  `ComponentWriterTest`.
- Validation: `:string`/`:s-expr` and non-kebab export names are clear compile
  errors (component non-serve only); `:long` keeps the existing GC rejection.
- Verified: `wasmtime run ... --invoke 'sumsquared(2, 3)'` (WAVE, no
  experimental warning), all four scalar shapes + `:as` alias + run co-existence
  in `WasmLispCompilerIntegrationTest`; ci-spec case
  `wasm-export-directive-does-not-disturb-run`. jco untested (not installed).
- **Empirical finding (drives Tier 3)**: I/O inside a sync-lifted export traps
  at runtime with "cannot block a synchronous task" (the adapter's fd_write
  blocks on `stream.write`, legal only in an async task). So Tier-1 exports are
  pure-compute by construction; documented in doc/ + `.kb/wasi-component.md`.
- `--invoke` does not run the top level first: a defvar-reading export sees
  uninitialized globals (traps on the null deref) -- same as Preview 1
  `--invoke`; documented.


## Goal

Let `(rontolisp:wasm-export 'name :params '(...) :returns ...)` produce a
**component-model function export** when compiling with `--component`, so a host
can call it through the canonical ABI (WAVE syntax) instead of only through a
Preview-1 / `--no-gc` core-module export.

Payoff:

- `wasmtime --invoke 'sumsquared(2, 3)' comp.wasm` (WAVE syntax) works, and the
  `--invoke ... experimental` stderr warnings that core-module `--invoke`
  emits go away (WAVE invoke on a component is the supported path).
- Typed signatures are visible to any component host (jco, wasmCloud, etc.), not
  just an untyped raw core `(func)`.

## Current state (why it does NOT work today)

- `wasm-export` is **Preview-1 core module only**. Gated off under `--component`:
  - `WasmLispCompiler.java:1176` `if ((!this.component || this.serve) && !exportDecls.isEmpty())`
    -- export wrapper funcs are not built in component mode.
  - `WasmLispCompiler.java:845` `exportNeedsReader = (!this.component) && ...`
  - `WasmLispCompiler.java:1908` component mode exports only `run`.
- `WasmComponentBuilder` wraps the unchanged core module with **fixed byte blobs**
  (`import-block.bin` / `mem.wasm` / `adapter.wasm`) and lifts exactly ONE export:
  `wasi:cli/run@0.3.0` (`run`), as a **stackful async** `canonLift`.
- Root cause: `wasm-export` is a *core-module ABI* mechanism (`:int`<->i31/i32,
  `:float`<->f64, `:string`/`:s-expr`<->`(ptr,len)` in linear memory). In the
  component model a core export is invisible to the host -- only what a WIT world
  lifts via the canonical ABI is callable. rontolisp's component output is a
  fixed WASI *command* component with no machinery to synthesize per-export lift
  glue or a custom world.

Confirmed empirically (2026-07-07): `--component` build of a `wasm-export`
program then `wasmtime run -W gc=y ... --invoke 'sumsquared(2,3)'` ->
`No exported func named 'sumsquared' in component.`

## Good news: the encoder primitives already exist

`am.ik.wasm.ComponentWriter` already has: `canonLift`, `funcTypeResult`,
value types `VT_S32`/`VT_S64`/`VT_U32`/.../`VT_BOOL`/`VT_STRING`,
`aliasCoreFunc`, `aliasCoreMemory`, `canonLowerMemoryUtf8` /
`canonLowerMemoryReallocUtf8`, and the SEC_EXPORT / SEC_CANON / SEC_TYPE section
ids. So this is *wiring*, not new encoder infrastructure.

## Tier 1 -- scalar-only exports (MVP, do this first)

Support `:int`/`:float`/`:bool`/`:void` only. Small, self-contained.

1. **Un-gate wrapper generation under component** (mirror how `serve` already
   un-gates): keep building the `ExportPlan` wrapper funcs (they bridge the
   internal calling convention to a plain core signature -- reuse as-is), and
   have the core module **core-export** each wrapper so the component can alias +
   lift it. Touch the `(!this.component ...)` gates at
   `WasmLispCompiler.java:1158/1176/1908` (and keep `run` too).
2. **Type mapping** (GC backend): `:int`->`VT_S32`, `:float`->`VT_F64`,
   `:bool`->`VT_BOOL`, `:void`->no result. (`:long`=i64 stays `--no-gc`-only;
   reject under component GC. `:string`/`:s-expr` -> clear "not yet supported
   under --component" error until Tier 2.)
3. **Wire `WasmComponentBuilder`**: for each `ExportPlan` -> `aliasCoreFunc`
   the core wrapper, build a component func type via `funcTypeResult`, emit a
   **synchronous** `canonLift` (NOT the stackful-async lift `run` uses -- a
   pure-compute scalar export needs no memory/realloc/async), add a SEC_EXPORT
   entry under the export name (`Decl.exportName()`, honors `:as`).
4. **World / init decision**: simplest is to **co-exist with `run`** (host can
   call either). Cleaner alternative: a reactor-style component (no `run`,
   `_initialize` init) analogous to `--no-wasi`. Pick one; co-exist is less work.
5. **Likely NO blob regen**: exports are added programmatically via SEC_EXPORT +
   SEC_CANON, not through `import-block.bin` (which only declares *imports*). So
   Tier 1 should not need `src/wasm-component/` regeneration. Verify this
   assumption early.

Caveat to verify: whether the export is callable without `_start`/init having
run (state init). Pure-compute scalar exports should be fine; if globals need
seeding, prefer the reactor `_initialize` shape.

## Tier 2 -- string / s-expr exports (DONE 2026-07-11; see the STATUS record)

Original sketch, kept for history. Note the plan's "use mem.wasm's
`cabi_realloc`" idea was rejected during implementation (un-resettable `$hp`,
no grow guard) in favor of a core-appended realloc over `__ronto_alloc`.

- `:string`/`:s-expr` cross core as `(ptr, len)`; component `string` needs the
  canonical lift options (memory/realloc/utf8/post-return).
- rontolisp strings are quote-framed bytes / GC strings, so the
  reader/printer bridge (the `exportNeedsReader` path) is threaded into the
  component path too.

## Tier 3 -- async I/O exports + WIT output

- An export that transitively does I/O must be lifted through the stackful async
  adapter like `run` (pure-compute exports stay sync).
- Optionally emit a generated `.wit` world so hosts / jco can generate bindings.

## Testing

- `ComponentWriterTest`: pin any new encoder helper output.
- E2E: build a component with a scalar export; call it via
  `wasmtime run -W gc=y ... --invoke 'sumsquared(2, 3)'` AND via `jco`. Add a
  "component export" case to the 4-backend verification (CLAUDE.md) /
  `ci-spec.yaml` if it fits the driver.

## Files

- `codegen/wasm/WasmLispCompiler.java` (gates 845/1158/1176/1908, ExportPlan
  routing into component export encoding)
- `codegen/wasm/WasmComponentBuilder.java` (per-export canonLift + SEC_EXPORT
  wiring)
- `codegen/wasm/WasmExportCompiler.java` (type-designator validation for the
  component path; reject `:string`/`:s-expr`/`:long` in Tier 1)
- `am.ik.wasm.ComponentWriter` (only if a missing lift variant is needed)
- Docs: `doc/{en,ja}/compiling/wasm.md`,
  `doc/{en,ja}/reference/functions/rontolisp-wasm-export.md`,
  `.kb/wasm-export-no-wasi.md`, `.kb/wasi-component.md`.

## Related

- **#93** (the non-GC, adapter-free variant: `--no-gc --component` compact
  export -- do after this; reuses the per-export canonLift + type-mapping here.
  Together #92 + #93 = "tiny typed component-model export" as a rontolisp
  selling point).
- `.kb/wasm-export-no-wasi.md` (current Preview-1 export mechanics + `--no-wasi`)
- `.kb/wasi-component.md` (`--component` design, fixed blobs, `run` lift)
