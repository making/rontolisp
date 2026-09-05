# `--no-gc` (non-GC WASM lowering)

Opt-in (CLI `--no-gc`; `NoGcWasmCompiler(optimize, simd[, component[, noWasi]])`). A
**separate backend class** `codegen.wasm.NoGcWasmCompiler`, dispatched from
`RontoLispCli.compileToFile` — NOT a flag threaded through `WasmLispCompiler`, so the GC path
stays untouched. Emits a **plain MVP module**: no rec group, no `struct`/`array`/`i31`/
`eqref`, no linear memory unless the program uses strings, the single `fd_write` import only
when it prints.

"non-GC" is the **value model** (unboxed `i64`/`f64`/linear-memory pointers), ORTHOGONAL to
hardware SIMD: the `simd` ctor arg toggles `vec:` kernels between scalar linear-memory loops
and native v128; the loop bodies live once in `codegen.wasm.WasmVecLoops`, shared with the
wasm-GC `--simd` kernels (`.kb/vec.md`).

`--no-gc` is the ONLY WASM target that keeps packed arrays in linear memory. Four static
pointer kinds: rank-1 `F64VEC`/`F32VEC` (`[count:i32][data]`) and rank-2 `F64MAT`/`F32MAT`
(`[rows:i32][cols:i32][data]` row-major), consumed by `vec:matvec`/`matvec-into`. Rank >= 3,
rank-2 `#d`/`#f` literals and `array-dimensions` are compile errors; the rank is static.

## Value model and inference
`inferTypes` is a **monotone fixpoint** over the call graph: exported params pinned to the
boundary designator; all other param types, **all let/`do`-bound local types**
(`Types.locals`) and all return types start at INT and only widen to FLOAT. `compileExpr`
consults `staticType` to insert promotions (`coerce`: `f64.convert_i64_s` /
`i64.trunc_s_f64`); let/`do` locals are allocated at their widened type so `setq`
(`local.tee`) stays type-consistent. `i64` makes integer arithmetic exact to 2^63. `Ty.join`:
INT doubles as the bottom and yields to STRING; FLOAT-vs-STRING is a type error; mixing a
string with a number is rejected (except nil->"").

## Arithmetic
`mod`/`rem` native per type (FLOAT = the EXACT `WasmFmodRuntimeBuilder` reduction, inlined at
the site), `min`/`max` INT fold via `select`, bitwise on `i64`.

**Rounding is the one place this backend cannot match the other four** — the float floor
family answers the EXACT quotient elsewhere, a bignum past 2^63, and there is no bignum tier
here by design (`.kb/wasm-bignum.md`): `(floor 1d300)` TRAPS (`i64.trunc_s_f64`, not the
saturating form); `(truncate 1d18 7.0)` keeps `142857142857142864` where the exact one is
`142857142857142857`; `(floor -3.0 (/ 1.0 0.0))` stays `0` where the others answer `-1`
(`compileRounding` rounds the f64 quotient, no `_f64_fdiv` to intercept). The REMAINDER side
is unaffected. `ffloor`/`fceiling`/`fround`/`ftruncate` inherit all of it through
`LispMacroExpander.expandFFamily`; no separate no-gc lowering exists or is needed.

## Iteration and control
`dotimes`/`do`/`do*` expand to `let`/`while`/`%block`(`BLOCK_INTERNAL`)/`setq`/`return`.
`while` is a `block`/`loop` pair leaving nil (i64 0); `%block` is a **typed** wasm block whose
result = join(normal completion, every enclosing `return` value); `return` is a `br` at depth
`Fn.ctrlDepth - blockMarker` (depth bumped by `if` +1, `while` +2, `%block` +1); `setq` is a
`local.tee` to a param/let slot (no globals).

## Strings
A string is an `i32` pointer to `[len:i32 LE][UTF-8 bytes]`; literals are 4-byte aligned from
`STR_DATA_BASE`=8, so addr 0 is always a valid zero-length string (the empty string /
nil-in-string-context).
- `(concatenate 'string ...)` bump-allocates via `__alloc` (mut-i32 heap-pointer global 0)
  and copies via `__memcpy`. Only the STRING result family exists, so any other designator —
  or a computed one — is a compile error naming it
  (`.kb/concatenate-result-families.md`). Other primitives: `length`, `subseq` (no bounds
  check), `string=` (`__streq`), `char`, `princ-to-string` (`__itoa` / `__ftoa`).
- **A character IS its i64 code point**: `char-code`/`code-char` are identities, `char=` is
  numeric `=`, so `(char= (char s i) #\x)` matches the other backends.
- The four helpers occupy function indices `internalCount+0..+3` (alloc, memcpy, streq,
  itoa). Memory + helpers are emitted **only when the module uses strings** (`Mem.used`;
  `usesStringOp`), so a pure-numeric module stays byte-identical. `usesMemory` modules also
  export `memory` and `__ronto_alloc`.

## Boundary (host ABI)
`:int`/`:bool` are `i32`, `:float` `f64`, `:string` a `(ptr,len)` i32 pair. Wrappers convert
host<->internal, so a returned value outside i32 wraps even though internals are i64. With no
conversion needed and `Mem.used()` false, the wrapper is elided and the export names the
internal function directly (`isPassThroughExport`, `.kb/wasm-export-no-wasi.md`). Two
documented divergences (README "Non-GC Output"): no rational type, and `0` is false.
- **Wrapper auto-reset for scalar returns**: `__ronto_alloc` never frees. When the return
  type is a **non-memory scalar** (NOT `:string`/`:s-expr`) **and** `Mem.used()`,
  `compileWrapperBody` snapshots heap global 0 at entry (before arg boxing) and restores it
  just before `END`. Reclaims only wrapper-internal scratch — the host's own pre-call input
  buffer sits below the mark and stays live.
- **Host arena API**: `__ronto_alloc_mark () -> i32` / `__ronto_alloc_reset (i32 mark)` over
  the same heap global, emitted ONLY when `mem.used()`, appended after the four string
  helpers (`--no-gc` has no fixed-index invariant). Take the mark BEFORE the host's
  `__ronto_alloc` input buffer, reset AFTER reading the result. Caveats: reset only to a mark
  taken BEFORE live data, and a `:string`-RETURNING export's bytes must be read out BEFORE
  the reset. Example: `examples/count-vowels/`.
- **`rontolisp:with-arena`** closes the intra-call hole. Cross-backend it is
  `LispMacroExpander.expandWithArena` lowering to `progn` (ci-spec
  `with-arena-is-observationally-a-progn`); here `compileWithArena` resets to the mark, and
  for a reference result `__memcpy`s it DOWN to the mark first (`emitRefByteSize`).
  Memoryless module: a plain progn. Escape contract (documented, not enforced): nothing
  allocated inside may be reachable after except the body's value; a `return` unwinding
  across the boundary skips the pop (leak, not corruption).

## Scope and pipeline
Only `(rontolisp:wasm-export ...)` functions with boundary types
`:int`/`:float`/`:bool`/`:string`/`:void`. Top level may contain ONLY defuns + export
directives (a pure-compute reactor; the `_start` command-module stretch is `.todo/111`).
`collectCalls` (BFS from export targets, throwing `UnsupportedOperationException` naming the
op + function for cons/char/symbol/hash/`eval`/I/O/list iteration/global-`setq`/free var,
yielding reachable defuns in discovery order with stable indices) -> `inferTypes` fixpoint ->
`compileExpr` per body + a host wrapper. The three share one dispatch shape and expand the
same macros the other backends do. Reuses `WasmExportCompiler.parse`/`isExportForm`/
`paramWasmTypes`/`resultWasmTypes` + the `T_*` constants; composes with `--optimize`
(`WasmTreeShaker` is GC-agnostic).

## Print / stdout
`print`/`princ`/`terpri` (no stream argument) work inside exported functions, byte-identical
to the interpreter (`.kb/core-representation.md`). `emitWriteStringEscaped` writes an escaped
string as RUNS, so nothing is allocated — a print must not move the bump heap.
- Gated by `Mem.printUsed` (a `usesPrintOp` scan): adds the ONE `(import
  "wasi_snapshot_preview1" "fd_write")` at type index 0, function index 0 — every other
  function index shifts by `Mem.funcBase()` = 1, and **ALL index math flows through the
  `Mem.funcIndex()`/`*Index()` accessors, nothing hardcodes the shift** — plus a
  `__write_stdout(ptr,len)` funnel, **the sole caller of the fd_write import**.
- `Mem.ftoaUsed` (a typed `rendersFloat` scan) additionally emits `__ftoa`, its five
  `__schub_*` helpers and the ~755-byte `SchubfachTables` blob after the literals
  (`Mem.schubBase`) — the Schubfach decimal shared with the GC backend
  (`WasmSchubfachRuntimeBuilder`), so float text is byte-identical at EVERY magnitude
  (`.kb/format.md`). NaN/Infinity/-Infinity are static literal headers, `-0.0` by sign bit.
- The 16-byte fd_write iov scratch (`Mem.iovAddr`) sits between the static data and
  `heapBase`; `print` of an INT/FLOAT brackets the transient string in a mark/reset.
- Value-model limits: literal `t`/`nil` print by name; a COMPUTED boolean prints as its 0/1
  integer; a stream argument and printing a packed array are compile errors.

## `--no-gc --component`
A third ctor arg wraps the finished module via `NoGcWasmComponentBuilder` — a pure POST stage
(a scalar-only program's embedded core module is byte-identical to the non-component output):
`core module 0 -> core instance 0 -> per-export alias/funcTypeScalars/sync canonLift/export`,
NO import block, adapter, mem module or `wasi:cli/run`, so `wasmtime run --invoke` works with
ZERO flags. A 4-export program is ~400 bytes; `:long` maps to VT_S64 (0x78).
- **`:string` exports** lift through the canonical string ABI (VT_STRING 0x73) over the
  module's OWN exported memory, appended in component mode with a `:string` boundary ONLY: a
  `cabi_realloc` core export; a retptr shim per `:string`-RETURNING export (MAX_FLAT_RESULTS
  = 1, so the lifted function returns ONE i32 at an 8-byte `(ptr,len)` record); ONE
  `cabi_post_<i32|i64|f64|void>` per flat-result signature, resetting heap global 0 to
  `heapBase`. Options in wasm-tools' order `(memory 0) (realloc N) string-encoding=utf8
  (post-return M)` (`ComponentWriter.canonLiftMemoryReallocUtf8PostReturn`, byte-pinned
  against `wasm-tools dump`); scalar exports keep the optionless lift.
- **Print — the print micro-adapter (WASI 0.3)**, gated on `mem.printUsed()`: a WASI 0.3
  stdout import block (`import-block-nogc-print.bin`) plus three fixed core modules from
  `src/wasm-component/*-nogc-print.wat` (`regen.sh`). `bridge-nogc-print.wat` implements the
  core's `fd_write` as one full stream cycle per call, parking on `waitable-set.wait` when a
  built-in reports BLOCKED (-1).
  - Only an async-typed task may block, so **EVERY export of a printing program lifts against
    an ASYNC function type** (`asyncFuncTypeScalars`, tag 0x43). Default-on in wasmtime 46+,
    so zero run flags; what rose is the wasmtime FLOOR. **jco 1.25.2 can no longer call the
    exports** — keep programs print-free for jco/browser targets. User-level `:async` stays
    rejected.
  - The instantiation cycle (bridge reads the CORE's memory, core imports fd_write from the
    bridge) is broken with the wit-component **shim/fixup** pattern
    (`shim-nogc-print.wat`'s funcref table `$imports` + slot 0, `fixup-nogc-print.wat`'s
    active element segment patching in the real fd_write last). The core module stays
    byte-identical to the plain `--no-gc` printing output.
  - Bridge contract with `__write_stdout`: fd 1, ONE iovec at the core's reserved 16-byte
    scratch, reused once ptr/len are in locals — event pair at iov..iov+8, the `future.read`
    retptr at iov+8 (read before nwritten overwrites the cell); the waitable-set handle is a
    bridge GLOBAL. Any other fd returns errno 8.
  - `rg '@0\.2\.0' src/wasm-component src/main` must hit nothing but
    `wasi:keyvalue@0.2.0-draft` (and `am.ik.wit` examples).
- Component-mode-only compile errors (in `compile()`, not codegen): non-kebab export names
  (`WasmExportCompiler.COMPONENT_EXPORT_NAME`) and `:async`. `:s-expr` stays rejected for ALL
  `--no-gc` by `validateScalarTypes`. `--optimize` composes (shake before the wrap; the cabi
  exports are roots). `--emit-wit` writes the WIT world (`nogc`/`nogc-print` templates off
  `mem.printUsed()`; `.kb/wasi-component.md`).

## `--no-wasi`
A PRINTING program's `fd_write` import is replaced by an internal discarding SINK at the same
function index 0 (`WasmIoRuntimeBuilder.buildNoWasiFdWriteSinkBody`, the GC backend's body
verbatim), so the module keeps ZERO imports while `Mem.funcBase()` stays 1; a print-free
program is a byte-exact no-op. Under `--component` the wrap consequently never wires the
print micro-adapter (`printAdapter = printUsed && !noWasi` selects the build AND the WIT
template): a printing program takes the print-FREE shape (one core module, no import block,
SYNC lifts). **Do NOT re-split this from the GC half.**

## Tests
`NoGcWasmCompilerTest` (structural, no Docker): the heap-reset trio,
`stringModuleExportsTheHostArenaApi`, `printGatesTheFdWriteImportOnAndOff`,
`componentWrapsThePlainCoreModuleVerbatim`,
`componentStringExportAppendsTheCanonicalStringAbi`,
`componentSharesOnePostReturnPerFlatResultSignature`, `componentPrintWiresTheMicroAdapter`,
`noWasiReplacesTheFdWriteImportWithADiscardingSink`,
`componentNoWasiPrintingProgramTakesThePrintFreeShape`. Runtime parity: the `noGc*` cases in
`WasmLispCompilerIntegrationTest` (string primitives, print vs the interpreter, flat-heap
loops under a 2-page cap, WAVE invoke with no flags, the canonical string ABI, `--optimize`
composition, the print micro-adapter and its chunk cap). The `:string`-parameter side needs a
memory-writing host, exercised by `examples/console/mandelbrot-nogc.lisp`.

## Unfinished
`:s-expr` (cons/reader/printer runtime) is deferred (`.todo/023`); the `_start`
command-module stretch is `.todo/111`.
