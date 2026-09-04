# `--component` (WASI 0.3 / Preview 3 output) for the WASM compiler

Opt-in: CLI `--component`; `WasmLispCompiler(dynamic, component)`, threaded as a
`component` boolean. Default output stays a Preview 1 core module (no regression).

**Design**: the core module is emitted UNCHANGED from Preview 1 (still imports the twelve
`wasi_snapshot_preview1` functions, all `FUNC_*` indices stable); an **adapter** core
module implements them over WASI 0.3's `stream<u8>`/`future<T>` + async canonical ABI. The
`run` export is a **stackful** async `canon lift` (no callback), so the synchronous
`stream.*`/`future.*` built-ins block cooperatively and the adapter stays straight-line.

Hosts: `wasmtime run` (wasmtime 46+, `component-model-async` default-on -- the adapters call
the ASYNC non-blocking built-ins and park on a blocking `waitable-set.wait` on BLOCKED, all
base component-model-async, so neither the more-async-builtins nor the stackful-lift gate is
involved). A SERVE component runs under `wasmtime serve`: its `handle` is a CALLBACK async
lift against the core's REAL callback (`async_cb`) -- a pending handler returns the packed
WAIT code instead of blocking (`.kb/async-await.md`). wasmCloud (wash 2.5.2,
`dev.wasm_proposals: [gc, exception-handling, component-model-async]`) hosts them.

## The fixed surface is fixed only WITHOUT `--optimize`
With `--optimize` the wrapper follows the shaken core: the adapter is narrowed to the
preview1 entry points the core still imports and re-shaken, its surviving `"w"` imports
select the lowerings/built-ins/component types the wrapper emits, and
`ComponentImportBlock.prune` cuts the import blob to the interfaces those reach.
`(print "Hello World!")` imports THREE interfaces (`wasi:cli/{types,stdout,stderr}`) instead
of eleven; 2,138 B instead of 7,690. Consequences:

- Every "component instance N is X" / "type N is Y" statement below is the UNPRUNED shape;
  the code holds none as a constant -- the maps come back from `ComponentImportBlock.Pruned`.
- `adapter.wat` exports the two fd-polymorphic shims TWICE, as full `fd_write`/`fd_read` and
  as stdio-only `fd_write_stdio`/`fd_read_stdin`; the wrapper retains the narrow pair under
  the preview1 names when the core imports no `path_open` (the only writer of the adapter's
  fd table), which lets the whole `wasi:filesystem` surface leave a printing component.

Mechanics, blob grammar, the `--emit-wit` half, the deliberate serve exemption:
`.kb/optimize-dead-code-elimination.md`, "The component WRAPPER".

## Gotchas
- `wasi:cli` and `wasi:filesystem` expose DISTINCT `error-code` types -> separate future
  built-ins (`future-read-cli`/`-fs`); the fs one is string-bearing, so `future-read-fs`
  needs realloc.
- `read-directory` returns `stream<directory-entry>`, structurally distinct from
  `stream<u8>`: own read/drop built-ins, read carries realloc (each element owns a string
  name), plus cookie-as-entry-index and "a short round is NOT the end", which Preview 1 does
  not share (`.kb/directory-listing.md`).

**No preopened directory is an ERRNO, not a trap.** `$ensure_preopen` used to read the first
element of `wasi:filesystem/preopens` `get-directories` unconditionally; with no `--dir` that
list is EMPTY, so it cached handle 0 and the first `path_open` trapped in the host with
`unknown handle index 0` -- before any errno existed, hence before `probe-file`'s "answer nil
on a bad errno" branch or any `handler-case` could see it: the one file primitive documented
never to signal aborted the whole program (a library merely PROBING an optional file, e.g.
local-time reading `/etc/localtime`, could not be loaded). It now caches `-1` and
`$path_open` returns errno 76. The helper is now `$ensure_preopens`, caching the WHOLE table
(`.kb/read-load-streams.md`); the errno policy is unchanged, with "no preopen index `dirfd`"
standing where "no preopen at all" stood. **Trigger**: this is the adapter compensating for
the host -- if a future WASI 0.3 host makes an empty preopen list impossible, or defines
`open-at` on an invalid descriptor, the guard can go. Only visible WITHOUT `--dir`
(`CiSpecE2eTest` passes `--dir .`).

## Shared-memory map (fixed for non-serve)
One linear memory, three writers: the canonical-ABI allocator, the adapter's fixed scratch
(page 5: env/preopen buffers, stream/future handle cells, the 64-slot fd table), the core's
static data / intern table / heap.

- **The core's interned-string data starts at page 6** (`COMPONENT_DATA_BASE_OFFSET` =
  0x60000, component only; Preview 1 keeps 256, byte-identical). Before this, >~64 KB of
  interned data grew across the serve cabi window (0x10000), the core's env/socket scratch
  (pages 3-4) and the adapter's page-5 cells -- segment bytes install at instantiation, so
  the adapter's zero-initialized flag cells read back interned-string bytes and its first
  blocking wait died with "unknown handle index" inside `fd_write`.
- **The non-serve `cabi_realloc` (mem.wat) bumps the core's own HEAP_PTR cell (address 84)**
  -- one shared monotonic allocator. Advancing HEAP_PTR is a PERMANENT allocation in the
  core's discipline (transient string scratch sits above it and pops back), so host-lifted
  buffers cannot be overwritten and there is no fixed ABI window to outgrow; same contract as
  `__ronto_alloc` and the wasm-export modules' appended `cabi_realloc`. RINGING a fixed ABI
  window instead was tried and reverted -- "every canonical-ABI allocation is per-call
  transient" is false (the free-listed 8 KB socket/stdin read buffers are retained).
- **Serve is NOT unified**: `mem-http-client.wat` keeps its per-request-reset cabi cell/window
  at 0x10000 (`CABI_HP_CELL_ADDR`), because a resident host must reclaim request buffers per
  call. It can still collide with the core's page-3/4 scratch past ~128 KB of per-request ABI
  traffic (open residual).

## Index stability
Static function-import indices and the `FUNC_*` constants in `WasmLispCompiler` are identical
across modes. Preview1-style `random_get`/`clock_time_get`/`environ_*` imports exist in both;
the `environ_*` pair is dead weight under `--component` since `uiop:getenv` moved onto
`environment.lisp` over a wit-imported `wasi:cli/environment@0.3.0` (bound FROM the block off
serve, a user import under serve -- `.kb/time-environment-builtins.md`).
`WasmRandomCompiler` calls `random_get` in both modes (host entropy in Preview 1, the
adapter's `wasi:random` in component); `WasmTimeCompiler` branches on `Ctx.component`. The
0.2-era `FUNC_FETCH_*` slots and retired `FUNC_TCP_*` stubs are deleted, so `FUNC_START` is
`IMPORT_FUNC_COUNT` = 12.

## Assembly
`WasmComponentBuilder` (codegen.wasm) over `am.ik.wasm.ComponentWriter` (general
async-canon-ABI encoder). Fixed byte blobs (`import-block.bin`, `mem.wasm`, `adapter.wasm`)
load from classpath resources under `.../codegen/wasm/component/`, registered for native
image in `resource-config.json` (wildcard). **The blobs are generated** from
`src/wasm-component/` -- follow `src/wasm-component/README.md` (edit sources, `regen.sh`,
re-derive wiring constants from `wasm-tools dump`, re-test).

## Component-model function exports
`rontolisp:wasm-export` types: `:int`->`s32`, `:float`->`f64` (VT_F64 = 0x75; 0x74 is
`char`), `:bool`->`bool`, void, `:string`/`:s-expr`->`string`. The un-gated wrapper machinery
core-exports it; `WasmComponentBuilder.appendFuncExports` appends per-export
alias/type/lift/export sections after the `run` wiring (base: core func 23+ / type 24+ /
component func 12+; http: 53/46/33; sock: 34/31/19). Default is a **synchronous** `canonLift`
(NOT `run`'s stackful-async lift), so `wasmtime run --invoke 'name(args)'` (WAVE) works with
no experimental warning, co-existing with `wasi:cli/run`.

A scalar export lifts with no canonical options. A `:string`/`:s-expr` boundary lifts with
`(memory 0) (realloc ...) string-encoding=utf8 (post-return ...)`: memory 0 is the shared
mem.wasm memory, but the realloc is NOT mem.wasm's (its `$hp` is un-resettable and
grow-unguarded) -- `WasmLispCompiler` appends to the core a `cabi_realloc` delegating to the
grow-guarded `__ronto_alloc`, one retptr shim per string-RETURNING export (MAX_FLAT_RESULTS =
1 -> single i32 to an 8-byte (ptr,len) record, allocated BEFORE the wrapper call because the
GC wrapper stages its result at the un-advanced HEAP_PTR scratch), and one shared
`cabi_post_*` per flat-result signature. The post-return restores HEAP_PTR to a per-call
snapshot saved by `cabi_realloc`'s first call (`CABI_MARK_*` cells 160/164/168) **guarded by
the runtime intern count** (the serve-reset pattern): interning during a call ratchets (skips
the restore) so intern records never dangle. `:s-expr` rides the same ABI as printed text
(`exportNeedsReader` un-gated for component non-serve). Bodies in
`WasmExportRuntimeBuilder`; kind mapping `WasmExportCompiler.componentPostReturnKind`.

Purely additive: an export-free or scalar-export-only program's component is byte-identical.
Enforced in `WasmLispCompiler` (component && !serve): the export name must match the
component `label` grammar (lower-kebab-case, `COMPONENT_EXPORT_NAME`, fix with `:as`);
`"run"` is reserved. Runtime (documented, not statically checked): I/O inside a SYNC export
is a residual RISK, not a certain trap -- the adapter's I/O built-ins are async, so a host
accepting bytes immediately (stdout) never parks the task and the print SUCCEEDS
(`componentSyncExportWithIoWorksWhenTheHostDoesNotBlock`); only a host reporting BLOCKED
traps with "cannot block a synchronous task", which `:async t` removes. `--invoke` does not
run `run` first, so defvar-reading exports see uninitialized globals (matches Preview 1).

## `:async t` I/O exports
The option flips the export's component function type to the ASYNC form
(`ComponentWriter.asyncFuncTypeScalars`, tag 0x43 vs sync 0x40 -- the ONLY byte difference;
core module unchanged, same flat signature), making the lift **stackful async** like `run`,
so blocking stream/future built-ins (print, fetch, sockets) suspend instead of trapping.

Explicit opt-in, NOT auto-detected from I/O reachability: funcall/apply route through the
arity dispatchers, so a conservative call-graph analysis would flip nearly every export async
and break the sync byte-identity guarantee. Composes with the string ABI unchanged --
wasmtime accepts memory/realloc/utf8/**post-return** on an async-typed lift, so CABI_MARK
reclamation is identical (a stackful lift via the async TYPE, not a `canon lift async`
callback lift, which would have no post-return). In WIT the export is an `async func`.

**`:async t` is permanent, not a workaround**: WASI 0.3 has no synchronous write BY DESIGN
(`deps/cli/stdio.wit` is `write-via-stream: func(data: stream<u8>) -> future<...>`; 0.2's
`output-stream`/`blocking-write-and-flush` was replaced -- in 0.3 blocking is a property of
the task, not of a host function), so on a pure-0.3 import surface an I/O-bearing sync export
can NEVER work. Importing 0.2 stdio to route around it was REJECTED: it would be a permanent
0.2 island, and the repo now has NO WASI-0.2-era surface. Stackful was the only lift a
synchronous source language could use without CPS-ing the whole backend -- a constraint the
callback-async cutover has since lifted for `handle`, so both lifts coexist.

Host support: wasmtime 46 `--invoke` runs it (verified with print inside, fetch inside with
`-S http=y` over the wit-imported `wasi:http@0.3.0`, run co-existence, sync+async mixed).
jco 1.25.2 transpiles but cannot run it, via **TWO DISTINCT upstream gaps -- do not collapse
them**: (a) EXPORT-LIFT -- jco's driver assumes callback-style async and misreads the flat
result as a callback code ("invalid async return value [13]"); stackful async exports are
unimplemented. (b) IMPORT-SIDE -- jco's bundle references `FutureReadableEnd` (5x) /
`FutureEnd` (2x) / `FutureWritableEnd` (1x) and **defines none**, while the stream family
(`StreamEnd`/`StreamReadableEnd`/`StreamWritableEnd`/`InternalStream`/`HostStream`) is fully
emitted, so any call touching a `future<T>`-typed WASI import dies with `ReferenceError:
FutureReadableEnd is not defined` at `new InternalFuture` ->
`ComponentAsyncState.createFuture` -> `_trampoline0` -> `fd_write`. (b) is reached through
`wasi:cli/stdout.write-via-stream` and fires FIRST -- before any export lift -- for anything
that prints, `run` included; reproduced under every jco flag combination. Neither gap is
rontolisp's.

Preview 1 / `--no-wasi` ignore `:async` (Decl carries it; core exports do not lift);
`--no-gc --component` rejects it (`NoGcWasmCompiler.validateComponentExport`). Pinned by
`componentAsyncExport*` + `componentSyncExportWithIoWorksWhenTheHostDoesNotBlock` in
`WasmLispCompilerIntegrationTest`, `componentFetchInsideAsyncExport` (opt-in
`RONTOLISP_HTTP_E2E=1`), ci-spec `wasm-export-directive-does-not-disturb-run` (an `:async`
directive is inert on the other backends).

## Shapes that do NOT use this pipeline
- **`--component --no-wasi` (GC reactor component)**: `WasmComponentBuilder.buildReactor`
  wraps the ONE core module (imports nothing -- the Preview 1 no-WASI stubs -- and runs its
  top level from its core start section) with no import block, adapter, mem module or
  `wasi:cli/run`, with or without `--optimize`; only `wasm-export` functions are lifted (sync
  by default, canonical string ABI over the core's OWN memory/`cabi_realloc`). Mechanics and
  gating: `.kb/wasm-export-no-wasi.md`. The ONLY component shape whose `--invoke`d exports
  see top-level `defparameter` state.
- **`--no-gc --component`**: a print-free non-GC MVP core has zero imports, so
  `NoGcWasmComponentBuilder` wraps it adapter-free into a run-less reactor component of a few
  hundred bytes that `wasmtime run --invoke` executes with ZERO flags. Same per-export
  sync-lift wiring as `appendFuncExports` but instance 0 / all index spaces from 0;
  `:long` -> VT_S64 (valid on the GC path too, `.kb/wasm-bignum.md`), `:string` -> VT_STRING
  on both paths (`WasmExportCompiler.componentValType`). A `:string` export lifts with
  `canonLiftMemoryReallocUtf8PostReturn` (options in wasm-tools order, byte-pinned) over the
  module's own memory against core-exported `cabi_realloc` / `cabi_post_*` / retptr shims
  appended by `NoGcWasmCompiler.assemble()`; the post-return pops the bump heap to base. A
  PRINTING program additionally gets a minimal `wasi:cli/stdout@0.3.0` blob set
  (`import-block-nogc-print.bin` + `shim/bridge/fixup-nogc-print` core modules, sourced in
  `src/wasm-component/`): the bridge implements the core's single `fd_write` over
  `write-via-stream` + async stream/future built-ins with a blocking `waitable-set.wait`
  park, so every export of a printing program lifts against an ASYNC function type (the flip
  is automatic; user-level `:async` stays rejected). Zero run flags kept; wasmtime 46+ is the
  printing component's floor, and jco cannot call the async-lifted exports. The
  core-imports-bridge / bridge-reads-core-memory instantiation cycle is broken with the
  wit-component shim/fixup funcref-table pattern, keeping the printing core byte-identical to
  plain `--no-gc` output. fd 1 only. Details: `.kb/no-gc-scalar-wasm.md`.

## `--emit-wit`
`-o out.wasm --component --emit-wit` also writes `out.wit`, so hosts / binding generators
(`jco types out.wit`) need no `wasm-tools component wit`. `--emit-wit` without `--component`
(or without a `.wasm` output) is a clear CLI error; the flag is in `CliOptions.noValueKeys`
(the --simd dead-flag lesson).

Rendering is `codegen.wasm.WitEmitter` over the `am.ik.wit` document model (`.kb/wit.md`).
The FIXED part (world imports + the fixed `wasi:cli/run` / `wasi:http/handler@0.3.0` export +
trailing package definitions, `package root:component; world root`) is the per-variant
`WasiWitDefinitions` document -- variants `base`/`sockets`/`http-server`/`nogc`/`nogc-print`
(the standalone `http-client` and `http-server-client` variants were removed when fetch/serve
became the ONE `http.lisp` library over canon-lowered `wasi:http@0.3.0` user imports: a
non-serve fetch selects `base` and gains `wasi:http` through the user-import path; serve+fetch
is the same `http-server` variant as plain serve) -- GENERATED by the test-side
`WasiWitDefinitionsGenerator` from fixtures under `src/test/resources/.../component/wit/`
captured from `wasm-tools component wit`. Per-export items
(`export name: [async ]func(p0: s32, ...) -> t;`) are appended as `WitItem.ExportNamed` nodes
and printed by `am.ik.wit.WitPrinter`. Parameter names are the Decl's own (`p0`, `p1`, ... by
default; a `:param-names '(text)` list, or the world's names under `rontolisp:wit-export`)
and are the labels `WasmComponentBuilder`/`NoGcWasmComponentBuilder` encode into the lifted
functype; defaults unchanged, so pre-`:param-names` artifacts are byte-identical. Both
compilers record the text and expose it via `componentWit()` (`WasmLispCompiler` picks
http-server/sockets/base off the same `serve`/`emitHttpImport`/`emitSockImport` selection as
the blob wiring; `NoGcWasmCompiler` picks nogc/nogc-print off `mem.printUsed()`); the CLI
writes it beside the `.wasm`.

Output is BYTE-identical to `wasm-tools component wit` (1.252.0) for every non-http-server
variant; the http-server fixture deviates deliberately by restoring the handler interface's
`use types.{request, response, error-code};` clause, which that tool drops, printing WIT that
does not re-parse (upstream `deps/http/handler.wit` has it) -- the emitted file must be
consumable and must re-parse through our own `WitParser`. Fixtures come from reference
components built by the FULL pipeline (the run/handler interface definitions are wired by
`WasmComponentBuilder`, absent from the `uni*.wit` references), so regeneration is
three-phase: `regen.sh` -> rebuild the jar -> `src/wasm-component/regen-wit.sh` -> re-run
`WasiWitDefinitionsGenerator` + `spring-javaformat:apply`.

Pinned by `WasiWitDefinitionsTest` (per-variant byte pin), `WitEmitterTest` (line pins incl.
s64/:async/void/:as shapes), `WitOracleE2eTest` (live byte-diff against `wasm-tools` on PATH,
skipped elsewhere); wiring by `componentCompileRecordsTheWitText` (+ variant selection) in
`WasmExportCompilerTest`/`NoGcWasmCompilerTest`; CLI by `RontoLispCliTest`.

A program may instead declare `(rontolisp:wit-export "world.wit" :world w)`; the world is then
the authoritative export list, checked at compile time on EVERY backend and lowered into the
`wasm-export` directives it stands for. That does NOT make `--emit-wit` a consistency check of
the export side -- re-emitting reproduces the input world's exports by construction (world ->
Decl -> functype -> emitted world, a one-to-one type mapping), so a diff there tests OUR type
mapping, not the user's program. What `--emit-wit` uniquely reports is the IMPORT side, which
`wit-export` never reads. See `.kb/wit.md` ("the export side is a FIXPOINT, not a check")
before touching this, plus the two things a component's TYPE cannot carry (`///` docs; the
always-`root:component`/`root` naming), the "hand-written `wasm-export` or
`rontolisp:http-handler` alongside a world is a compile error" rule, the check list and
`--scaffold-wit`.

## Components in a browser (jco)
Measured on jco 1.25.2 / Chrome 149 / Node 22.16 / preview3-shim 0.2.0. Re-measure only
against a newer jco.

- **`--no-gc --component` = ZERO browser dependencies.** No imports in its world, so
  `jco transpile` emits a SINGLE self-contained ESM (~90 KiB, core wasm base64-inlined) with
  **no `import` statements at all** -- no shim, import map or polyfill. jco camelCases the
  kebab export name (`count-vowels` -> `countVowels`).
- **A PRINTING `--no-gc --component` no longer runs through jco** (deliberate): it imports
  `wasi:cli/stdout@0.3.0` (Node-only shim) and its exports are async lifts, hitting both jco
  gaps; on Node 22 it dies at `new WebAssembly.Suspending` (no JSPI).
- **wasm-GC `--component` LOADS AND COMPUTES in Chrome**; SYNC exports return correctly. The
  blockers are neither wasm-GC nor JSPI nor our lift -- they are the two jco gaps, and in a
  browser the IMPORT-side one (`FutureReadableEnd`) fires first, on the print.
- **`preview3-shim` HAS NO BROWSER BUILD** (0.2.0): `exports` has only a `node` condition, it
  ships only `dist/nodejs/`, and that code imports node builtins. A GC component in a browser
  needs a HAND-WRITTEN 0.3 shim (~90 lines). jco destructures exactly **9 names** at module
  top level and throws if any is missing: `environment.getEnvironment`,
  `stdout.writeViaStream`, `stderr.writeViaStream`, `stdin.readViaStream`,
  `monotonicClock.now`, `systemClock.now`, `preopens.getDirectories`, `types.Descriptor`,
  `random.getRandomU64` (from
  `@bytecodealliance/preview3-shim/{cli,clocks,filesystem,random}`). For a non-I/O export
  they only have to EXIST.
- Node 22.16 cannot import a transpiled GC component at all
  (`TypeError: WebAssembly.Suspending is not a constructor`); Chrome 149 has JSPI on by
  default. User-facing half: `doc/{en,ja}/compiling/wasm.md`.

## User WIT-interface imports (`canon lower`)
`rontolisp:wit-import` under `--component` becomes a component-level **instance import** whose
functions are `canon lower`ed into the core module
(`WasmComponentBuilder.appendUserImports`: instance type + import + per-function alias + lower
(memory 0 / realloc = mem.wasm's `cabi_realloc` = core func 0 / utf8, exactly when the call
touches linear memory) + one synthesized core instance per interface, passed as an extra
instantiation arg named by the interface's canonical id). Every downstream hardcoded index
(the `run` alias/lift/export and `appendFuncExports`) shifts by the user-import counts, so
**zero imports = zero shift = byte-identical**.

**Serve (`build`) has NO serve adapter and ONE block**: http.lisp IS the HTTP glue, so its own
`wasi:http/{types,client}@0.3.0` interfaces are the fixed surface, lowered FROM the import
block (`import-block-http-server.bin`, regenerated from the 0.3 `uni-http-server` world;
instances 0=clocks/types (dependency-hoisted by wait-for), 1=http/types, 2=http/client,
3=random, 4=system-clock, 5=monotonic-clock, 6=cli/types, 7=stdout, 8=stderr).
`WasmComponentBuilder.lowerFixedFromBlock` (shared with the base/sockets wait.lisp clocks
binding) emits every `appendUserImports` member kind against the block's instances -- sync
decls, async calls, drops, alias built-ins via `WitComponentLevelTypes` seeded with the
block's projections, task-returns, waitable builtins. `appendUserImports` there carries only
ADDITIONAL user interfaces (`WasmServeComponentBuilder.additionalImports` partitions the list,
and is what `WasmLispCompiler` passes to `WitEmitter` so the fixed surface is not
double-declared).

The builder lifts the core's `handle` wasm-export (`[i32 request] -> []`) with
`canon lift (memory, utf8, async)` against
`async func(request: own<request>) -> result<own<response>, error-code>`, built over the
block's NAMED aliases (type 2=request, 3=response, 4=http error-code -- anonymous structural
types in an exported functype fail validation with "instance not valid to be used as
export"), into `wasi:http/handler@0.3.0`; the response is delivered mid-task via
`canon task.return`. Serve+fetch selects the SAME block: http.lisp's two halves share the
`wasi:http` bindings (merged by `WasmComponentImportCompiler.mergeByIface`; duplicate core
imports deduped by `WasmLispCompiler`'s import-slot pass), and the preview1 bridge
(`adapter-http-server-p1.wat`, over the 0.3 service interfaces + stream/future built-ins) is
shared. The serve core module has no `cabi_realloc` of its own (`componentStringAbi` is
`component && !serve`) and needs none: the `canon lower` realloc is the shared memory
module's, core func 0, already aliased by the serve builder.

Guest-side marshalling, the instance-type encoder, the settled type tiers, the
`result`-as-envelope + Lisp-wrapper design, the member pruning that replaces the tree shaker
on this path, and the `pointer not aligned` trap (`__ronto_alloc` returns an unaligned
`HEAP_PTR` after interning): `.kb/wit.md` ("Component imports"). `examples/wit/keyvalue` runs
against wasmtime's REAL `wasi:keyvalue` host (`-S keyvalue=y`).

Encoders pinned by `ComponentWriterTest`; E2E by `WasmLispCompilerIntegrationTest`.
Limitations: `doc/{en,ja}/guides/wasm-component.md`.
