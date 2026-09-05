# `--component` (WASI 0.3 / Preview 3 output) for the WASM compiler

Opt-in: CLI `--component`; `WasmLispCompiler(dynamic, component)`. Default output stays a
Preview 1 core module (no regression).

**Design**: the core module is emitted UNCHANGED from Preview 1 (still imports the twelve
`wasi_snapshot_preview1` functions, all `FUNC_*` indices stable, `FUNC_START` =
`IMPORT_FUNC_COUNT` = 12); an **adapter** core module implements them over WASI 0.3's
`stream<u8>`/`future<T>` + async canonical ABI. The `run` export is a **stackful** async
`canon lift` (no callback), so the synchronous `stream.*`/`future.*` built-ins block
cooperatively and the adapter stays straight-line. A SERVE component's `handle` is instead a
CALLBACK async lift against the core's real `async_cb` (`.kb/async-await.md`). Hosts:
`wasmtime run` 46+, `wasmtime serve`, wasmCloud (wash 2.5.2, `dev.wasm_proposals: [gc,
exception-handling, component-model-async]`).

Assembly: `WasmComponentBuilder` (codegen.wasm) over `am.ik.wasm.ComponentWriter`. Fixed byte
blobs (`import-block.bin`, `mem.wasm`, `adapter.wasm`) load from classpath resources under
`.../codegen/wasm/component/`, registered in `resource-config.json`. **The blobs are
generated** from `src/wasm-component/` -- follow its `README.md`.

## The fixed surface is fixed only WITHOUT `--optimize`
With `--optimize` the adapter is narrowed to the preview1 entry points the core still imports
and re-shaken, its surviving `"w"` imports select the lowerings the wrapper emits, and
`ComponentImportBlock.prune` cuts the import blob to the interfaces those reach
(`(print "Hello World!")`: three interfaces instead of eleven, 2,138 B instead of 7,690).
Every "instance N is X" / "type N is Y" below is the UNPRUNED shape; the maps come back from
`ComponentImportBlock.Pruned`, never a constant. `adapter.wat` exports the fd-polymorphic
shims twice (`fd_write`/`fd_read`, stdio-only `fd_write_stdio`/`fd_read_stdin`) and the
wrapper retains the narrow pair when the core imports no `path_open`.
`.kb/optimize-dead-code-elimination.md`, "The component WRAPPER".

## Gotchas
- `wasi:cli` and `wasi:filesystem` expose DISTINCT `error-code` types -> separate future
  built-ins (`future-read-cli`/`-fs`); the fs one is string-bearing, so it needs realloc.
- `read-directory` returns `stream<directory-entry>`, structurally distinct from `stream<u8>`:
  own read/drop built-ins, read carries realloc, cookie-as-entry-index, and "a short round is
  NOT the end" (`.kb/directory-listing.md`).
- **No preopened directory is an ERRNO, not a trap**: `$ensure_preopens` caches `-1` for an
  empty preopens list and `$path_open` returns errno 76, so `probe-file` can answer nil. The
  adapter is compensating for the host; a host making an empty preopen list impossible retires
  the guard. Only visible WITHOUT `--dir`.

## Shared-memory map (fixed for non-serve)
One linear memory, three writers: the canonical-ABI allocator, the adapter's fixed scratch
(page 5: env/preopen buffers, stream/future handle cells, the 64-slot fd table), the core's
static data / intern table / heap.

- **The core's interned-string data starts at page 6** (`COMPONENT_DATA_BASE_OFFSET` =
  0x60000, component only; Preview 1 keeps 256, byte-identical). Otherwise interned data grows
  across the adapter's page-5 cells and the first blocking wait dies with "unknown handle
  index" inside `fd_write`.
- **The non-serve `cabi_realloc` (mem.wat) bumps the core's own HEAP_PTR cell (address 84)**
  -- one shared monotonic allocator, so there is no fixed ABI window to outgrow. A ringed
  fixed window was tried and reverted: "every canonical-ABI allocation is per-call transient"
  is false (the free-listed 8 KB socket/stdin read buffers are retained).
- **Serve is NOT unified**: `mem-http-client.wat` keeps its per-request-reset cabi cell/window
  at 0x10000 (`CABI_HP_CELL_ADDR`); it can still collide with the core's page-3/4 scratch past
  ~128 KB of per-request ABI traffic (open residual).

## Component-model function exports
`rontolisp:wasm-export` types: `:int`->`s32`, `:float`->`f64` (VT_F64 = 0x75; 0x74 is
`char`), `:bool`->`bool`, void, `:string`/`:s-expr`->`string`.
`WasmComponentBuilder.appendFuncExports` appends per-export alias/type/lift/export sections
after the `run` wiring (base: core func 23+ / type 24+ / component func 12+; http: 53/46/33;
sock: 34/31/19). Default is a **synchronous** `canonLift`, co-existing with `wasi:cli/run`.

A `:string`/`:s-expr` boundary lifts with `(memory 0) (realloc ...) string-encoding=utf8
(post-return ...)`. The realloc is NOT mem.wasm's (`$hp` is un-resettable and grow-unguarded):
`WasmLispCompiler` appends a core `cabi_realloc` delegating to `__ronto_alloc`, one retptr
shim per string-RETURNING export (MAX_FLAT_RESULTS = 1), one shared `cabi_post_*` per
flat-result signature. The post-return restores HEAP_PTR to a per-call snapshot (`CABI_MARK_*`
cells 160/164/168) **guarded by the runtime intern count**, so intern records never dangle.
Bodies in `WasmExportRuntimeBuilder`; kind mapping
`WasmExportCompiler.componentPostReturnKind`.

Purely additive: an export-free or scalar-export-only program's component is byte-identical.
Export names must match the component `label` grammar (`COMPONENT_EXPORT_NAME`, fix with
`:as`); `"run"` is reserved. Documented, not statically checked: I/O inside a SYNC export only
traps on a host reporting BLOCKED, which `:async t` removes; `--invoke` does not run `run`
first, so defvar-reading exports see uninitialized globals.

## `:async t` I/O exports
Flips the export's component function type to the ASYNC form
(`ComponentWriter.asyncFuncTypeScalars`, tag 0x43 vs sync 0x40 -- the ONLY byte difference;
core module unchanged), making the lift stackful async like `run`. Explicit opt-in, NOT
auto-detected: funcall/apply route through the arity dispatchers, so a conservative call-graph
analysis would flip nearly every export and break the sync byte-identity guarantee.

**`:async t` is permanent, not a workaround**: WASI 0.3 has no synchronous write BY DESIGN
(blocking is a property of the task), so on a pure-0.3 import surface an I/O-bearing sync
export can NEVER work; importing 0.2 stdio was REJECTED as a permanent 0.2 island. jco 1.25.2
transpiles but cannot run it, via **TWO DISTINCT upstream gaps -- do not collapse them**: (a)
EXPORT-LIFT, jco assumes callback-style async and misreads the flat result; (b) IMPORT-SIDE,
jco references `FutureReadableEnd`/`FutureEnd`/`FutureWritableEnd` and defines none, so any
`future<T>`-typed WASI import dies with `ReferenceError`. (b) fires FIRST, through
`wasi:cli/stdout.write-via-stream`, for anything that prints.

Preview 1 / `--no-wasi` ignore `:async`; `--no-gc --component` rejects it
(`NoGcWasmCompiler.validateComponentExport`). Pinned by `componentAsyncExport*` +
`componentSyncExportWithIoWorksWhenTheHostDoesNotBlock` in `WasmLispCompilerIntegrationTest`,
`componentFetchInsideAsyncExport` (opt-in `RONTOLISP_HTTP_E2E=1`), ci-spec
`wasm-export-directive-does-not-disturb-run`.

## Shapes that do NOT use this pipeline
- **`--component --no-wasi` (GC reactor)**: `WasmComponentBuilder.buildReactor` wraps the ONE
  core module with no import block, adapter, mem module or `wasi:cli/run`; only `wasm-export`
  functions are lifted, sync by default over the core's OWN memory/`cabi_realloc`
  (`.kb/wasm-export-no-wasi.md`). The ONLY component shape whose `--invoke`d exports see
  top-level `defparameter` state.
- **`--no-gc --component`**: `NoGcWasmComponentBuilder` wraps a zero-import core adapter-free
  into a run-less reactor component that `wasmtime run --invoke` runs with ZERO flags. Same
  sync-lift wiring but instance 0 / all index spaces from 0; `:long` -> VT_S64, `:string` ->
  VT_STRING (`WasmExportCompiler.componentValType`) lifted with
  `canonLiftMemoryReallocUtf8PostReturn` (options in wasm-tools order, byte-pinned) against
  shims appended by `NoGcWasmCompiler.assemble()`. A PRINTING program additionally gets
  `import-block-nogc-print.bin` + shim/bridge/fixup core modules implementing `fd_write` over
  `write-via-stream`, so its exports lift ASYNC automatically (user `:async` stays rejected);
  the instantiation cycle is broken with the wit-component shim/fixup funcref-table pattern,
  keeping the printing core byte-identical to plain `--no-gc`. fd 1 only.
  `.kb/no-gc-scalar-wasm.md`.

## `--emit-wit`
`-o out.wasm --component --emit-wit` also writes `out.wit`; without `--component` (or without
a `.wasm` output) it is a clear CLI error, and the flag is in `CliOptions.noValueKeys` (the
--simd dead-flag lesson). Rendering is `codegen.wasm.WitEmitter` over the `am.ik.wit` document
model (`.kb/wit.md`); the FIXED part is the per-variant `WasiWitDefinitions` document
(`base`/`sockets`/`http-server`/`nogc`/`nogc-print`), GENERATED by the test-side
`WasiWitDefinitionsGenerator` from fixtures under `src/test/resources/.../component/wit/`.
Per-export items are `WitItem.ExportNamed` nodes printed by `am.ik.wit.WitPrinter`; parameter
names are the Decl's own (`p0`, ... by default, or `:param-names`, or the world's names) and
are the labels encoded into the lifted functype. `componentWit()` exposes the text.

Output is BYTE-identical to `wasm-tools component wit` (1.252.0) for every non-http-server
variant; the http-server fixture deviates DELIBERATELY by restoring the handler interface's
`use types.{request, response, error-code};` clause, which that tool drops. Regeneration is
three-phase: `regen.sh` -> rebuild the jar -> `src/wasm-component/regen-wit.sh` -> re-run
`WasiWitDefinitionsGenerator` + `spring-javaformat:apply`. Pinned by `WasiWitDefinitionsTest`,
`WitEmitterTest`, `WitOracleE2eTest`, `componentCompileRecordsTheWitText`, `RontoLispCliTest`.

`(rontolisp:wit-export "world.wit" :world w)` makes the world the authoritative export list.
That does NOT make `--emit-wit` a consistency check of the export side -- re-emitting
reproduces the input world's exports by construction. What `--emit-wit` uniquely reports is
the IMPORT side, which `wit-export` never reads (`.kb/wit.md`, "the export side is a FIXPOINT,
not a check").

## Components in a browser (jco)
Measured on jco 1.25.2 / Chrome 149 / Node 22.16 / preview3-shim 0.2.0; re-measure only
against a newer jco. `--no-gc --component` has ZERO browser dependencies (no imports, so
`jco transpile` emits a single self-contained ESM; jco camelCases the kebab export name); a
PRINTING one no longer runs through jco, deliberately. wasm-GC `--component` LOADS AND
COMPUTES in Chrome and SYNC exports return correctly -- the blockers are neither wasm-GC nor
JSPI nor our lift. **`preview3-shim` HAS NO BROWSER BUILD** (0.2.0), so a GC component in a
browser needs a hand-written 0.3 shim; jco destructures exactly **9 names** at module top
level and throws if any is missing (`environment.getEnvironment`, `stdout.writeViaStream`,
`stderr.writeViaStream`, `stdin.readViaStream`, `monotonicClock.now`, `systemClock.now`,
`preopens.getDirectories`, `types.Descriptor`, `random.getRandomU64`) -- for a non-I/O export
they only have to EXIST. Node 22.16 has no JSPI. User half: `doc/{en,ja}/compiling/wasm.md`.

## User WIT-interface imports (`canon lower`)
`rontolisp:wit-import` under `--component` becomes a component-level **instance import** whose
functions are `canon lower`ed into the core module (`appendUserImports`: instance type +
import + per-function alias + lower (memory 0 / realloc = mem.wasm's `cabi_realloc` = core
func 0 / utf8, exactly when the call touches linear memory) + one synthesized core instance
per interface). Every downstream hardcoded index shifts by the user-import counts, so **zero
imports = zero shift = byte-identical**.

**Serve (`build`) has NO serve adapter and ONE block**: http.lisp IS the HTTP glue, so its
`wasi:http/{types,client}@0.3.0` interfaces are the fixed surface, lowered FROM
`import-block-http-server.bin` (instances 0=clocks/types, 1=http/types, 2=http/client,
3=random, 4=system-clock, 5=monotonic-clock, 6=cli/types, 7=stdout, 8=stderr).
`WasmComponentBuilder.lowerFixedFromBlock` emits every `appendUserImports` member kind against
the block's instances; `appendUserImports` there carries only ADDITIONAL user interfaces
(`WasmServeComponentBuilder.additionalImports` partitions the list and is what `WitEmitter`
gets, so the fixed surface is not double-declared).

The builder lifts the core's `handle` wasm-export with `canon lift (memory, utf8, async)`
against `async func(request: own<request>) -> result<own<response>, error-code>`, built over
the block's NAMED aliases (type 2=request, 3=response, 4=http error-code -- anonymous
structural types in an exported functype fail validation), into `wasi:http/handler@0.3.0`; the
response is delivered mid-task via `canon task.return`. Serve+fetch selects the SAME block
(bindings merged by `WasmComponentImportCompiler.mergeByIface`, shared preview1 bridge
`adapter-http-server-p1.wat`). The serve core has no `cabi_realloc` of its own
(`componentStringAbi` is `component && !serve`) and needs none.

Guest-side marshalling, the settled type tiers, the `result`-as-envelope design, the member
pruning that replaces the tree shaker on this path, and the `pointer not aligned` trap:
`.kb/wit.md` ("Component imports"). `examples/wit/keyvalue` runs against wasmtime's REAL
`wasi:keyvalue` host (`-S keyvalue=y`). Encoders pinned by `ComponentWriterTest`, E2E by
`WasmLispCompilerIntegrationTest`; limitations in `doc/{en,ja}/guides/wasm-component.md`.
