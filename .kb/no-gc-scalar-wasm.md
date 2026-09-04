# `--no-gc` (non-GC WASM lowering)

Opt-in (CLI `--no-gc`; `NoGcWasmCompiler(optimize, simd[, component[, noWasi]])`, shorter
ctors delegating `false`). A **separate backend class** `codegen.wasm.NoGcWasmCompiler`,
dispatched from `RontoLispCli.compileToFile` for `.wasm` output — NOT a flag threaded
through `WasmLispCompiler`, so the GC path (fixed `FUNC_*` indices, ~200-function
runtime) stays untouched. Emits a **plain MVP module**: no rec group, no
`struct`/`array`/`i31`/`eqref`, no import (no linear memory unless the program uses
strings; the single `fd_write` import only when it prints).

"non-GC" is the **value model** (unboxed `i64`/`f64`/linear-memory pointers), ORTHOGONAL
to hardware SIMD: the `simd` ctor arg toggles `vec:` kernels between plain scalar
linear-memory loops (default, no `0xFD`, runs on a SIMD-less runtime) and native v128
(`f64x2`/`f32x4`; CLI `--no-gc --simd`). Full `vec:` mechanics: `.kb/vec.md`
("Acceleration layer 2"). The four v128 and four scalar loop bodies live once in
`codegen.wasm.WasmVecLoops`, shared with the wasm-GC `--simd` kernels; this backend
allocates its `Fn` locals in the original order and delegates.

`--no-gc` is the ONLY WASM target that keeps packed arrays in linear memory. Four static
pointer kinds: rank-1 `F64VEC`/`F32VEC` (`[count:i32][data]`) and rank-2
`F64MAT`/`F32MAT` (`[rows:i32][cols:i32][data]` row-major — built by a rank-2
`make-array` (`(list d n)` or `'(d n)`), indexed by two-subscript `aref`/`aset` and flat
`row-major-aref`, consumed by `vec:matvec`/`matvec-into`). Rank >= 3, rank-2 `#d`/`#f`
literals and `array-dimensions` are compile errors. The rank is static (no runtime
discriminator).

## Value model and inference

Each value's wasm type is chosen by **static type inference**: integers `i64`, floats
`f64`, strings `i32` (linear-memory pointer). `inferTypes` is a **monotone fixpoint**
over the call graph: exported params pinned to the boundary designator
(`:int`/`:bool`->INT, `:float`->FLOAT); all other param types, **all let/`do`-bound
local types** (`Types.locals`, by name per function) and all return types start at INT
and only widen to FLOAT (a `CallSink` accumulates the join of call-site arg types into
callee params; a `setq`/let-init widens the target local). `compileExpr` returns the
`Ty` it emitted and consults `staticType` (a frozen read-only `typeOf`) to insert
promotions where INT meets FLOAT (`coerce`: `f64.convert_i64_s` / `i64.trunc_s_f64`);
let/`do` locals are allocated at their widened type so `setq` (`local.tee`) stays
type-consistent. `i64` makes integer arithmetic exact to 2^63 (wider than the GC `i31`
and an all-`f64` lowering's 2^53).

`Ty.join` lattice: INT doubles as the inference bottom and yields to STRING;
FLOAT-vs-STRING is a type error; `coerce`/`compileCoerced` rejects mixing a string with
a number (except nil->"").

## Arithmetic

- `mod`/`rem` native per type (INT: `i64.rem_s`, mod = `((a rem b)+b) rem b`; FLOAT: the
  EXACT reduction of `WasmFmodRuntimeBuilder`, inlined at the site because this backend
  emits helper functions only behind linear memory — `.kb/linalg-simd.md`).
- `min`/`max` INT fold via `select` (no `i64.min`).
- `sqrt` is `f64.sqrt` (always FLOAT). Bitwise `logand`/`logior`/`logxor`/`lognot`/`ash`
  map to `i64.and`/`or`/`xor`/(xor -1)/(`shl` vs `shr_s` picked by `select` on the sign).
- **Rounding is the one place this backend cannot match the other four.** The float
  floor family answers the EXACT quotient elsewhere, which is a bignum past 2^63, and
  there is no bignum tier here by design (`.kb/wasm-bignum.md`). So:
  - `(floor 1d300)` TRAPS (`i64.trunc_s_f64`, not the saturating form the GC backend
    uses);
  - `(truncate 1d18 7.0)` keeps the rounded-double quotient `142857142857142864` where
    the exact one is `142857142857142857` (an exact quotient needs the shifted mantissa
    product, which overflows i64 past ~10 of exponent spread);
  - an INFINITE divisor: `(floor a b)` lowers to `(floor (/ a b))` and rounds the f64
    quotient directly (`compileRounding`), with no `_f64_fdiv` to intercept, so
    `(floor -3.0 (/ 1.0 0.0))` stays `0` where the other backends now answer `-1`.
  - The REMAINDER side is unaffected: the exact `fmod` is inlined here.
  - `ffloor`/`fceiling`/`fround`/`ftruncate` inherit all of it exactly: each is
    `LispMacroExpander.expandFFamily` lowering to `(float (op number [divisor]))`, so
    `(ffloor 1d300)` traps inside the inner call and `(ftruncate 1d18 7.0)` carries the
    same rounded-double quotient. No separate no-gc lowering exists or is needed.

## Iteration and control

`dotimes`/`do`/`do*` expand (via `LispMacroExpander`) to
`let`/`while`/`%block`(`BLOCK_INTERNAL`)/`setq`/`return`, all handled directly. `while`
is a `block`/`loop` pair leaving nil (i64 0); `%block` is a **typed** wasm block whose
result = join(normal completion, every enclosing `return` value); `return` is a `br` at
depth `Fn.ctrlDepth - blockMarker` (control depth bumped by `if` +1, `while` +2,
`%block` +1); `setq` is a `local.tee` to a param/let slot (no globals).

## Strings

A string is an `i32` pointer to a linear-memory header `[len:i32 LE][UTF-8 bytes]`.
Literals are laid out 4-byte-aligned in a data segment from `STR_DATA_BASE`=8, so addr 0
is always a valid zero-length string (the empty string / nil-in-string-context, used to
type-check `cond`'s `(if t body nil)` expansion via `compileCoerced`).

- `(concatenate 'string ...)` sums operand lengths, bump-allocates `[len][bytes]` via
  `__alloc` (mut-i32 heap-pointer global 0, grows whole pages) and copies via `__memcpy`
  (byte loop, no bulk-memory). Only the STRING result family exists here (no cons cells,
  no general array type), so `concatenate` with any other literal designator — or a
  computed one — is a compile error naming it (`.kb/concatenate-result-families.md`).
- Primitives: `length` (loads the header word), `subseq` (alloc + memcpy of the slice,
  end defaults to the length, no bounds check), `string=` (`__streq`: pointer-equal fast
  path then byte loop), `char` (a byte load), `princ-to-string` (INT via `__itoa` —
  digit count, alloc, backwards fill, `-` sign; STRING passes through; FLOAT via
  `__ftoa`).
- **A character IS its i64 code point**, no separate type: `char-code`/`code-char` are
  identities, `char=` is numeric `=`, `LispChar` literals compile as their code, so the
  portable `(char= (char s i) #\x)` idiom matches the other backends.
- The four helpers occupy function indices `internalCount+0`..`+3` (alloc, memcpy,
  streq, itoa), appended after the wrappers. Memory + helpers are emitted **only when
  the module uses strings** (`Mem.used` = any string literal, `:string` boundary type,
  or a string-producing op — `usesStringOp` scans concatenate/subseq/princ-to-string so
  `(length (princ-to-string n))` works with no literal), so a pure-numeric module stays
  byte-identical to the original.
- `usesMemory` modules also export `memory` and `__ronto_alloc` so a host writes
  `:string` inputs and reads `:string` results.

## Boundary (host ABI)

`:int`/`:bool` are `i32`, `:float` `f64`, `:string` a `(ptr,len)` i32 pair (param:
copied into a fresh internal header via `__alloc`+`__memcpy`; result: internal ptr ->
`(ptr+4, load len)`). Wrappers convert host<->internal
(`i64.extend`/`i32.wrap`/`f64.convert`/`i32.trunc`), so a returned value outside i32
wraps even though internals are i64. When no conversion is needed at all (`:long`/`:float`
params AND return matching the inferred i64/f64) and `Mem.used()` is false, the wrapper
is elided and the export names the internal function directly (`isPassThroughExport`;
`.kb/wasm-export-no-wasi.md`).

Two documented divergences (README "Non-GC Output"): no rational type (`/` is float
division), and `0` is false in a boolean context (full CL treats only `nil` as false).

**Wrapper auto-reset for scalar returns**: `__ronto_alloc` never frees, so the fresh
internal `:string`-param copy plus concatenate/subseq/princ-to-string scratch would leak
per call. When the export's return type is a **non-memory scalar**
(`:int`/`:long`/`:float`/`:bool`/`:void` — NOT `:string`/`:s-expr`) **and** `Mem.used()`,
`compileWrapperBody` snapshots heap global 0 into an i32 wrapper local at entry
(`global.get 0; local.set mark`, emitted first, before arg boxing) and restores it just
before `END` (`local.get mark; global.set 0`, leaving the host result on top). This
reclaims only wrapper-internal scratch — the host's own pre-call `__ronto_alloc` input
buffer sits below the mark and stays live. A `:string`/`:s-expr` return never resets.
No index/global/section change; composes with `--optimize`.

## Host arena API

`__ronto_alloc_mark () -> i32` (`global.get 0; end`) and `__ronto_alloc_reset (i32 mark)`
(`local.get 0; global.set 0; end`) over the same heap global, letting a resident host
bracket its OWN pre-call allocation. Emitted + exported ONLY when `mem.used()`, appended
after the four string helpers (`markIndex`=itoa+1, `resetIndex`+1 in the `Mem` record) —
`--no-gc` has no fixed-index invariant so appending renumbers nothing. Recipe:
`mark = __ronto_alloc_mark()` BEFORE the host's `__ronto_alloc(len)` input buffer,
`__ronto_alloc_reset(mark)` AFTER reading the result (verified flat: count-vowels
`:string`->`:int`, 100000 Node calls, memory 65536 both before and after, vs 2031616
without).

Two contract caveats (manual stack, not GC): reset only to a mark taken BEFORE live
data, and a `:string`-RETURNING export (which the wrapper auto-reset does NOT reset)
must have its bytes read out BEFORE `__ronto_alloc_reset`. Boundary-type-agnostic.
Example/recipe: `examples/count-vowels/` (Node + Endive resident loops).

## `rontolisp:with-arena`

`(rontolisp:with-arena () body...)` closes the intra-call hole (nothing is freed WITHIN
one export call). Cross-backend it is a `LispMacroExpander.expandWithArena` lowering to
`progn` (interpreter/JVM/wasm-GC dispatch it; ci-spec
`with-arena-is-observationally-a-progn`). This backend compiles it natively
(`compileWithArena`): snapshot heap global 0, run the body, then — scalar result: plain
reset, value rides the stack; reference result (STRING/F64VEC/F32VEC/F64MAT/F32MAT):
compute byte size from the header (`emitRefByteSize`), and if the pointer >= mark,
`__memcpy` it DOWN to the mark (dst <= src, so the forward byte copy is a safe memmove)
and set the heap just past the copy, else plain reset. On a memoryless module
(`!mem.used()`) it is a plain progn.

Escape contract (documented, not enforced): nothing allocated inside may be reachable
after, except the body's value; a `return` unwinding across the boundary skips the pop
(leak, not corruption).

## Scope and pipeline

Only `(rontolisp:wasm-export ...)` functions with boundary types
`:int`(i32)/`:float`(f64)/`:bool`(i32 0/1)/`:string`((ptr,len))/`:void`. Top level may
contain ONLY defuns + export directives (a pure-compute reactor, no
`_start`/`_initialize`; the `_start` command-module stretch is unbuilt, `.todo/111`).

1. `collectCalls` — BFS from export targets, validating eligibility (throws
   `UnsupportedOperationException` naming the offending op + function for
   cons/char/symbol/hash/`eval`/I/O/`dolist`-or-list-iteration/global-`setq`/free var;
   string literals + `(concatenate 'string ...)` are eligible; a `setq` target must be a
   bound param or let/`do` local), yielding reachable defuns in discovery order with
   stable indices (an unreached ineligible defun is silently dropped).
2. `inferTypes` fixpoint.
3. `compileExpr` emits each reachable body + a host wrapper.

`collectCalls`, `typeOf` and `compileExpr` share one dispatch shape and expand the same
macros the other backends do (`cond`/`and`/`or`/`when`/`unless`/`let*`/`1+`/`1-`/
`zerop`/`plusp`/`minusp`/`evenp`/`oddp`/`dotimes`/`do`/`do*`, variadic comparisons via
`expandComparison`) down to the core (`if`/`let`/`progn`/`while`/`%block`/`setq`/`return`
+ numeric/comparison/bitwise/`not` primitives). Reuses
`WasmExportCompiler.parse`/`isExportForm`/`paramWasmTypes`/`resultWasmTypes` + the `T_*`
constants. Composes with `--optimize` (`WasmTreeShaker` is GC-agnostic and decodes the
memory/global/`memory.grow`/`block`/`loop` opcodes the string path emits).

## Print / stdout

`print`/`princ`/`terpri` (no stream argument) work inside exported functions,
byte-identical to the interpreter (`print` = prin1 text + trailing newline, strings
quoted; `princ` = display text; both return their argument; `terpri` = newline, nil). A
`print`ed STRING is escaped like every other backend's readable renderer (every embedded
`"` / `\` preceded by `\`): `emitWriteStringEscaped` writes the content as RUNS (one
`__write_stdout` per unescaped stretch, then the one-byte `\` literal), so nothing is
allocated — a print must not move the bump heap — and an escape-free string costs
exactly one write. `"\\"` joins the print literal pool alongside `"\n"`/`"\""`/`"T"`/`"NIL"`.
Cross-backend table: `.kb/core-representation.md`.

- Gated like strings: `Mem.printUsed` (a `usesPrintOp` scan) adds the ONE
  `(import "wasi_snapshot_preview1" "fd_write")` (type index 0, function index 0 — every
  other function index shifts by `Mem.funcBase()` = 1; ALL index math flows through the
  `Mem.funcIndex()`/`*Index()` accessors, nothing hardcodes the shift) plus a
  `__write_stdout(ptr,len)` funnel, **the sole caller of the fd_write import**. A
  print-free program keeps 0 imports and byte-identical output (pinned both directions
  in `printGatesTheFdWriteImportOnAndOff`).
- `Mem.ftoaUsed` (a typed `rendersFloat` scan: print/princ/princ-to-string with a
  FLOAT-inferred arg) additionally emits `__ftoa(f64)->i32` plus its five `__schub_*`
  helpers (`Mem.schub*Index()`) and the ~755-byte `SchubfachTables` blob after the
  literals (`Mem.schubBase`): the Schubfach shortest round-trip decimal shared with the
  GC backend (`WasmSchubfachRuntimeBuilder`), so float text is byte-identical to the
  interpreter at EVERY magnitude (`.kb/format.md`, "The float printer"). NaN / Infinity
  / -Infinity return static literal headers, `-0.0` by sign bit. This also un-errors
  `princ-to-string` of a float.
- The 16-byte fd_write iov scratch (`Mem.iovAddr`) sits between the static data and
  `heapBase`. `print`/`princ` of an INT/FLOAT brackets the transient `__itoa`/`__ftoa`
  string in a heap-pointer mark/reset, so a print loop stays flat.
- Value-model limits: literal `t`/`nil` print by name; a COMPUTED boolean prints as its
  0/1 integer; a stream argument and printing a packed array are compile errors.

## `--no-gc --component` (compact typed component)

A third ctor arg wraps the finished module via `NoGcWasmComponentBuilder` — a pure POST
stage (codegen untouched; a scalar-only program's embedded core module is byte-identical
to the non-component output). The wrap is
`core module 0 -> core instance 0 (no args) -> per-export alias/funcTypeScalars/sync canonLift/export`
— NO import block, adapter, mem module, or `wasi:cli/run` (a run-less reactor;
`wasmtime run --invoke 'name(args)' out.wasm` works with ZERO flags; also verified via
jco transpile on Node, where `:long` surfaces as BigInt). A 4-export program is ~400
bytes. `:long` maps to VT_S64 (0x78) via `WasmExportCompiler.componentValType`.

**`:string` exports** lift through the canonical string ABI (`:string` -> VT_STRING 0x73)
over the module's OWN exported memory. `assemble()` appends — component mode + a
`:string` boundary ONLY, so scalar-only components and every non-component output stay
byte-identical:

- a `cabi_realloc` core export (`local.get 3; call __alloc`; bump alloc ignores
  old/align, 4-align satisfies the string's align 1);
- a retptr shim per `:string`-RETURNING export (canonical ABI MAX_FLAT_RESULTS = 1, so
  the lifted core function returns ONE i32 pointing at an 8-byte `(ptr,len)` record; the
  shim forwards params to the untouched two-value wrapper, `__alloc`s the record and is
  core-exported under the export name in place of the wrapper);
- ONE `cabi_post_<i32|i64|f64|void>` post-return per flat-result signature, shared
  across exports. It resets heap global 0 all the way to `heapBase` — nothing in a
  `--no-gc` instance outlives one export call, so this frees host-lowered args + wrapper
  copies + result and a resident instance stays flat (200k Node calls on the raw core,
  memory 65536 -> 65536; both wasmtime and jco DO call post-return).

String-involving exports lift with the four canonical options in wasm-tools' order
`(memory 0) (realloc N) string-encoding=utf8 (post-return M)`
(`ComponentWriter.canonLiftMemoryReallocUtf8PostReturn`, byte-pinned against
`wasm-tools dump`); scalar exports in the same module keep the optionless lift. UTF-8
multi-byte round-trips intact.

**Print — the print micro-adapter (WASI 0.3)**: a PRINTING program is not a compile
error. Gated on `mem.printUsed()` (a print-free component keeps the adapter-free bytes),
`NoGcWasmComponentBuilder` prepends a WASI 0.3 stdout import block
(`import-block-nogc-print.bin`: `wasi:cli/types` dependency-hoisted first = import
instance 0 with the `error-code` enum aliased at component type 1, then `wasi:cli/stdout`
= instance 1; block types 0-2, first free type 3) and wires three fixed core modules
(sourced in `src/wasm-component/*-nogc-print.wat`, regenerated by `regen.sh`):

- `bridge-nogc-print.wat` implements the core's single `fd_write` import as one full
  stream cycle per call — `stream.new` -> `wasi:cli/stdout.write-via-stream(readable)` ->
  ASYNC `stream.write` of the whole iovec -> `stream.drop-writable` -> ASYNC
  `future.read` + `future.drop-readable` — parking on a blocking `waitable-set.wait` when
  a built-in reports BLOCKED (-1). The builder defines
  `stream<u8>`/`result<_, error-code>`/`future` as component types 3-5, lowers
  `write-via-stream` (core func 0, optionless) and emits the async built-ins + waitable
  trio (core funcs 1-8, memory 0 = the core's own aliased memory), grouped as the
  bridge's `"w"` import.
- Only an async-typed task may block, so EVERY export of a printing program lifts against
  an ASYNC function type (`asyncFuncTypeScalars`, tag 0x43 — the GC `:async t` shape;
  same flat core signature, same `canonLift`, post-return survives). This is base
  component-model-async, default-on in wasmtime 46+, so a printing component keeps ZERO
  run flags; what rose is the wasmtime FLOOR (46+). **jco 1.25.2 can no longer call the
  exports** (async lifts are the same jco gap the GC `:async t` exports have; the WASI
  0.3 shim is Node-only besides — keep programs print-free for jco/browser targets).
  User-level `:async` stays rejected (the lift flip is automatic, not a knob).
- The bridge must read the iovec out of the CORE's own exported memory while the core
  imports fd_write from the bridge, so the instantiation cycle is broken with the
  wit-component **shim/fixup** pattern (`shim-nogc-print.wat`: a funcref-table `$imports`
  + an fd_write forwarding through slot 0, instantiated first, the core instantiates
  against it; `fixup-nogc-print.wat`: an active element segment patches the bridge's real
  fd_write into the slot last). The core module stays byte-identical to the plain
  `--no-gc` printing output.
- Bridge contract with its sole caller `__write_stdout`: fd 1, ONE iovec at the core's
  reserved 16-byte scratch, which the bridge reuses once ptr/len are in locals — the
  waitable-set event pair `{waitable, payload}` at iov..iov+8, the `future.read`
  `result<_, error-code>` retptr at iov+8 (read before nwritten overwrites the cell); the
  cached waitable-set handle is a bridge GLOBAL, so no nogc-layout scratch address
  exists. Any other fd returns errno 8 (fd 2 is unreachable — `--no-gc` rejects
  `warn`/every non-print I/O — so `wasi:cli/stderr` stays out of the block).
- Encoders `ComponentWriter.aliasCoreTable`/`coreInstanceFromExports` (mixed core sorts),
  byte-pinned against `wasm-tools dump`. A tiny printing scalar program is ~2.2 KB.
- The 0.2-era `deps/io-0.2`/`deps/cli-0.2` are DELETED —
  `rg '@0\.2\.0' src/wasm-component src/main` must hit nothing but
  `wasi:keyvalue@0.2.0-draft` (and version-grammar examples in `am.ik.wit`).
- Print + `:string` composes: the memory is aliased ONCE by the print wiring and the
  string lifts reuse it.

Component-mode-only compile errors (validated in `compile()`, not codegen): non-kebab
export names (`WasmExportCompiler.COMPONENT_EXPORT_NAME`) and `:async`. `:s-expr` stays
rejected for ALL `--no-gc` by `validateScalarTypes`. Internal string use
(module-private memory) is fine. `--optimize` composes (shake runs before the wrap; the
cabi exports are roots; the fd_write import survives via `__write_stdout`).
`--component` is rejected with the GC backend's own component path in `RontoLispCli`.

The CLI `--emit-wit` option writes the component's WIT world next to the `.wasm`
(`nogc`/`nogc-print` templates picked off `mem.printUsed()`, recorded via
`NoGcWasmCompiler.componentWit()`); `.kb/wasi-component.md` ("--emit-wit").

## `--no-wasi`

A PRINTING program's single `fd_write` import is replaced by an internal discarding SINK
defined at the same function index 0 (`WasmIoRuntimeBuilder.buildNoWasiFdWriteSinkBody`,
the GC backend's `--no-wasi` body verbatim: `*nwritten = iovs[0].len`, errno 0 — output
lost, nothing traps; `.kb/wasm-export-no-wasi.md`), so the module keeps ZERO imports
while `Mem.funcBase()` stays 1 and every planned index holds. A print-free program never
had the import, so the flag is a byte-exact no-op there.

Under `--component` the wrap consequently never wires the print micro-adapter
(`printAdapter = printUsed && !noWasi` selects the build AND the `nogc` WIT template): a
printing program takes the print-FREE shape — ONE core module, no import block, SYNC
lifts — rather than merely losing its imports, restoring all four properties the adapter
costs (bytes ~2KB->~0.6KB, one module, sync `func` in WIT, jco output runs on bare node
with no preview3-shim). Output-only, exactly the GC asymmetry. **Do NOT re-split this
from the GC half** — the two reactor paths answer "what does a module with no WASI do
when it prints?" and the answer must not differ.

## Tests

`NoGcWasmCompilerTest` (structural, no Docker: MVP shape via a mini-parser, eligibility
errors, string memory/data/export sections, the fd_write import pin both directions, the
funcBase index shift, with-arena option/error cases), specifically:

- `scalarReturnExportResetsTheBumpHeapAtWrapperExit` (incl. `:long`),
  `stringReturnExportDoesNotResetTheHeap`, `pureNumericExportEmitsNoHeapReset`
- `stringModuleExportsTheHostArenaApi`, `pureNumericModuleOmitsTheHostArenaApi`,
  `hostArenaApiBodiesAreTheHeapPointerGetAndSet`
- `printGatesTheFdWriteImportOnAndOff`
- `componentWrapsThePlainCoreModuleVerbatim` (incl. a <1KB size pin),
  `componentLongExportsUseS64`, `componentStringExportAppendsTheCanonicalStringAbi`,
  `componentSharesOnePostReturnPerFlatResultSignature`,
  `componentWithoutStringExportsOmitsTheStringAbi`, `componentPrintWiresTheMicroAdapter`
  (4 core modules, verbatim printing core, <2.5KB pin),
  `componentPrintFreeProgramsCarryNoneOfThePrintMachinery`,
  `componentPrintComposesWithStringExports` (exactly one memory alias)
- `noWasiReplacesTheFdWriteImportWithADiscardingSink`,
  `componentNoWasiPrintingProgramTakesThePrintFreeShape`

`--no-gc` cases in `WasmLispCompilerIntegrationTest` (`wasmtime --invoke`, parity with
the interpreter): `noGcSupportsStringConcatenationAtTheBoundary` (returned `:string`
length), `noGcSupportsStringPrimitives` (length/subseq/string=/char/char-code/char=/
princ-to-string through scalar boundaries), `noGcPrintWritesToStdoutMatchingTheInterpreter`
(wasmtime stdout for int/float/IEEE-edges/string/bool), `noGcPrintLoopKeepsTheHeapFlat`
and `noGcWithArenaKeepsALoopFlatWhereTheBareLoopGrows` (both under a 2-page
`wasmtime -W max-memory-size` cap), `noGcComponentExportsCallableViaWaveInvokeWithNoFlags`
(all five scalar designators through WAVE, zero flags),
`noGcComponentStringExportsLiftThroughTheCanonicalAbi`,
`noGcComponentHonorsAsAliasAndComposesWithOptimize`,
`noGcComponentPrintWorksInsideAsyncLiftedExportsViaTheMicroAdapter`,
`noGcComponentPrintCrossesTheBridgeChunkCap` (a >4096-byte princ).

The `:string`-parameter side of the ABI needs a memory-writing host (Node/playground),
exercised by `examples/console/mandelbrot-nogc.lisp` (returns the rendered grid as a
`:string`).

## Unfinished

`:s-expr` (cons/reader/printer runtime) is deferred (`.todo/023`); the `_start`
command-module stretch is `.todo/111`.
