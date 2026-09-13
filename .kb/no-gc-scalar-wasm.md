# `--no-gc` (non-GC WASM lowering)

Opt-in (CLI `--no-gc`; `NoGcWasmCompiler(optimize, simd[, component[, noWasi]])`). A
**separate backend class** `codegen.wasm.NoGcWasmCompiler`, dispatched from
`RontoLispCli.compileToFile` — NOT a flag threaded through `WasmLispCompiler`, so the GC path
stays untouched. Emits a **plain MVP module**: no rec group, no `struct`/`array`/`i31`/
`eqref`, no linear memory unless the program uses strings, the single `fd_write` import only
when it prints, and a host function only where the program declares one ("Host imports").

"non-GC" is the **value model** (unboxed `i64`/`f64`/linear-memory pointers), ORTHOGONAL to
hardware SIMD: the `simd` ctor arg toggles `vec:` kernels between scalar linear-memory loops
and native v128; the loop bodies live once in `codegen.wasm.WasmVecLoops`, shared with the
wasm-GC `--simd` kernels (`.kb/vec.md`).

`--no-gc` is the ONLY WASM target that keeps packed arrays in linear memory. Four static
pointer kinds: rank-1 `F64VEC`/`F32VEC` (`[count:i32][data]`) and rank-2 `F64MAT`/`F32MAT`
(`[rows:i32][cols:i32][data]` row-major), consumed by `vec:matvec`/`matvec-into`. Rank >= 3,
rank-2 `#d`/`#f` literals and `array-dimensions` are compile errors; the rank is static.

The **type section carries each distinct signature once** (`TypeTable`, interned in
emission order). A wasm function type is structural and only ever named by INDEX -- by a
`func` entry or an import entry -- so folding the duplicates renumbers nothing else. It
used to be one entry per function, which on a 20-function module meant 24 entries of which
12 were distinct (129 -> 61 bytes, 2026-09-13). The printing module's `fd_write` signature
is interned FIRST so it stays type 0, which is what the import entry names.

## Value model and inference
`inferTypes` is a **monotone fixpoint** over the call graph: exported params pinned to the
boundary designator; all other param types and **all let/`do`-bound local types**
(`Types.locals`) start at INT and only widen to FLOAT. `compileExpr`
consults `staticType` to insert promotions (`coerce`: `f64.convert_i64_s` /
`i64.trunc_s_f64`); let/`do` locals are allocated at their widened type so `setq`
(`local.tee`) stays type-consistent. `i64` makes integer arithmetic exact to 2^63. `Ty.join`:
INT doubles as the numeric bottom and yields to STRING; FLOAT-vs-STRING is a type error; mixing a
string with a number is rejected (except nil->"").

**VOID is the fourth point and the TRUE bottom** (`join(VOID, X) = X`): a form that leaves
the stack untouched. It is not part of the arithmetic lattice -- it never meets INT/FLOAT
in an expression, only in "what does this statement leave behind" -- and the ONE place it
meets a value is `coerce`, which materializes the nil it stands for in the consumer's own
representation (`pushNil`: the i64/f64 zero, or the address-0 empty-string header). The
other direction, `coerce(X, VOID)`, is the single place a `drop` is decided, which is what
makes a void statement free. Four things are void, and nothing else is:
`while`, `terpri`, an empty `progn`, and a call to a `:void` host import. It then SPREADS
through the return fixpoint -- `returns` seeds at VOID rather than INT, so a function all
of whose paths are void is itself `(...) -> ()` -- with one pin: a function exported under
a VALUE-returning designator seeds at INT, because it owes the host a value even when its
body is a bare `while`.
- A **local slot is never VOID** (`slotTy`): storage holds the nil, and the initializer
  materializes it.
- A **constant `if` test takes only the branch it selects into the result type**, and an
  ABSENT else contributes VOID rather than the INT zero an explicit `nil` would. Both
  halves are load-bearing: the macro expander ends every `cond` in `(if t ... nil)`, and
  without the first rule that dead nil drags an all-void chain back to INT and puts an
  `i64.const 0` in every arm. Both arms are still WALKED whatever the test says -- the
  walk is what records call sites and widens locals.
- `typeOf` and `compileExpr` must answer the SAME type for every form, or the emitted
  stack does not match the declared block/function type and the module fails validation.
  The pairs that have to move together are `while`, `terpri`, `progn`, `%block`/`return`
  and both `if` rules.
- Measured 2026-09-13 on `.todo/artefacts/805-.../bench.lisp`: **953 -> 931 B**
  (-2.3%) at `--optimize=size`, 1,378 -> 1,350 at `--optimize=off`, with ZERO
  `i64.const 0` / `drop` pairs left in the module and `InitApp` reaching
  `isPassThroughExport`. `AppendLogMessage` keeps its wrapper and always would have:
  its `:s32` parameter needs the `i64.extend_i32_s` no pass-through can skip. A
  pure-numeric program pays the same way through `while`: the
  `(let ((acc 0)) (dotimes ...) acc)` shape is 116 -> 106 B.

### There is no i32 tier, and there must not be one
Measured 2026-09-13 and **rejected**; the spike, its script and the demonstration are in
`.todo/artefacts/806-no-gc-internal-void/`. Making INT an `i32` module-wide (every
`i64.*` opcode its `i32.*` twin, the extend/wrap pairs gone) was worth 1,383 -> 1,310 B on
the 20-function reactor, and 1,260 with `:s32` pass-through exports -- and it is not
available at any price: **every boundary value can fit `:s32` while an INTERMEDIATE
crosses 2^31**, so `(mod (* n n) 1000)` of 65536 answers 0 for 296 and
`(mod (fact 13) 10000)` answers 3504 for 800. Silently wrong, on exactly the shapes
`doc/*/guides/wasm-nogc.md` advertises this backend for. No boundary guard can see an
intermediate, and a range analysis does not rescue it either: it would have to bound every
integer-valued expression in a body, `(+ a b)` of two `:s32` already needs 33 bits, and
interval widening gives top at `fib`'s first recursive `+`. It succeeds exactly where the
tier is worth about two bytes (comparisons, indices) and fails wherever the bytes are.
Today's wrap point is 2^63, documented; 2^31 is reached by `13!`. If a narrow tier is ever
wanted it has to be an explicit user DECLARATION, so the wrap point is the user's stated
contract the way `:s32` already is at the boundary.

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
`while` is a `block`/`loop` pair leaving NOTHING -- it is VOID, because the nil the other
backends answer there is never read as a value, and where it is the join materializes it;
`%block` is a **typed** wasm block whose
result = join(normal completion, every enclosing `return` value), the empty blocktype
`0x40` when that join is VOID; `return` is a `br` at depth
`Fn.ctrlDepth - blockMarker` (depth bumped by `if` +1, `while` +2, `%block` +1); `setq` is a
`local.tee` to a param/let slot (no globals).

A **predicate feeds `if`/`br_if` directly**: a comparison, `not`/`null` and `string=` each
produce the i32 0/1 that a branch consumes, and only widen it because the VALUE domain is
i64. `emitPredicate` records that the widening is the last byte written and `takeFlag`
takes it back off when the consumer turns out to be a branch, so neither the widening nor
the `!= 0` that would undo it is emitted (the test is positional, so it can never fire on
anything else). A CONSTANT test decides the branch at compile time instead: the macro
expander ends every `cond` in `(if t ... nil)`, and that arm now costs nothing -- neither
its test NOR its dead `nil`, which does not enter the result type either ("Value model and
inference", VOID).

## Strings
A string is an `i32` pointer to `[len:i32 LE][UTF-8 bytes]`; literals are laid out back to
back (NOT aligned) from `STR_DATA_BASE`=8, so addr 0 is always a valid zero-length string
(the empty string / nil-in-string-context). The `i32.load align=2` that reads a length
header is a HINT in wasm -- an unaligned address is legal and every engine serves it -- so
the padding that used to 4-align each block bought nothing and is gone (2026-09-13; 14
bytes on the `.todo/artefacts/805-.../bench.lisp` reactor, output identical). The
Schubfach tables after the literals DO keep their alignment: those are i64/f64 table reads
in the float renderer's inner loop.
- `(concatenate 'string ...)` bump-allocates via `__alloc` (mut-i32 heap-pointer global 0)
  and copies via `__memcpy`. Only the STRING result family exists, so any other designator —
  or a computed one — is a compile error naming it
  (`.kb/concatenate-result-families.md`). Other primitives: `length`, `subseq` (no bounds
  check), `string=` (`__streq`), `char`, `princ-to-string` (`__itoa` / `__ftoa`).
- **A character IS its i64 code point**: `char-code`/`code-char` are identities, `char=` is
  numeric `=`, so `(char= (char s i) #\x)` matches the other backends.
- The four helpers occupy function indices `internalCount+0..+3` (alloc, memcpy, streq,
  itoa), where `internalCount` counts the internal functions and the EMITTED wrappers --
  `planMemory` answers the layout and the three gates, `placeFunctions` assigns the
  indices once the wrapper count is known, and nothing assumes one wrapper per export
  directive. Memory + helpers are emitted **only when the module uses strings**
  (`Mem.used`; `usesStringOp`), so a pure-numeric module stays byte-identical.
  A `usesMemory` module exports `memory`; the allocator family is separately gated
  (`Mem.hostArena`, "Boundary" below).

## Boundary (host ABI)
`:int`/`:bool` are `i32`, `:float` `f64`, `:string` a `(ptr,len)` i32 pair. Wrappers convert
host<->internal, so a returned value outside i32 wraps even though internals are i64. With no
conversion needed and nothing in the module able to allocate (`Mem.allocates()` false), the
wrapper is elided and the export names the internal function directly
(`isPassThroughExport`, `.kb/wasm-export-no-wasi.md`). The identities are `:s64`/INT,
`:float`/FLOAT and **`:void`/VOID** -- the last one only reachable because a void body's
internal function is itself `(...) -> ()`; a `:void` export of a value-answering body still
takes a wrapper, whose whole content is the `drop`. Two documented divergences (README
"Non-GC Output"): no rational type, and `0` is false.
- **Wrapper auto-reset for scalar returns**: `__ronto_alloc` never frees. When the return
  type is a **non-memory scalar** (NOT `:string`/`:s-expr`), `Mem.used()` **and**
  `Mem.allocates()`, `compileWrapperBody` snapshots heap global 0 at entry (before arg
  boxing) and restores it just before `END`. Reclaims only wrapper-internal scratch — the
  host's own pre-call input buffer sits below the mark and stays live.
- **`Mem.allocates()`** is the module-level answer to "can anything bump the heap during a
  call": a `:string` export PARAMETER or import RESULT (both copy into a fresh block), a
  string-producing op (`concatenate`/`subseq`/`princ-to-string`), a packed vector, or
  printing (`__itoa`/`__ftoa` allocate the text they return). A module whose only use of
  memory is reading its own literals — the host-facing reactor that passes text OUT —
  answers false, and then no wrapper carries the bracket and a scalar identity export can
  be a pass-through even though the module has memory.
- **Host arena API**: `__ronto_alloc` plus `__ronto_alloc_mark () -> i32` /
  `__ronto_alloc_reset (i32 mark)` over the same heap global, appended after the four
  string helpers (`--no-gc` has no fixed-index invariant). All three are exported ONLY when
  the BOUNDARY DECLARATIONS give a host something to do with the heap (`Mem.hostArena`):
  an export takes a `:string` (the host allocates the input buffer here), a reached import
  RETURNS a `:string` (the host writes the result bytes here), or an export RETURNS a
  `:string` (the pointer escapes, so that wrapper cannot auto-reset and only the host can
  pop). Anything else is an API nothing can call — 124 bytes of it on the
  `.todo/artefacts/805-.../bench.lisp` reactor. The mark/reset BODIES are gated with the
  exports (they exist only to be exported); `__alloc` itself is still emitted with the
  other three helpers and drops out through the tree shaker when nothing calls it. Take the
  mark BEFORE the host's `__ronto_alloc` input buffer, reset AFTER reading the result.
  Caveats: reset only to a mark taken BEFORE live data, and a `:string`-RETURNING export's
  bytes must be read out BEFORE the reset. Example: `examples/count-vowels/`.
- **`rontolisp:with-arena`** closes the intra-call hole. Cross-backend it is
  `LispMacroExpander.expandWithArena` lowering to `progn` (ci-spec
  `with-arena-is-observationally-a-progn`); here `compileWithArena` resets to the mark, and
  for a reference result `__memcpy`s it DOWN to the mark first (`emitRefByteSize`).
  Memoryless module: a plain progn. Escape contract (documented, not enforced): nothing
  allocated inside may be reachable after except the body's value; a `return` unwinding
  across the boundary skips the pop (leak, not corruption).

## Host imports (`rontolisp:wasm-import`)
The directive is taken here too, and this backend is what a host-driven module WANTS: the
`.todo/789` measurement reactor -- two host imports (one taking two `:string`s), a fixnum
recursion, two exports -- is **528 bytes** at `--no-gc --no-wasi --optimize=size` against **1,658** for the
same source on wasm-GC (measured 2026-09-12, after `790`/`791` landed). Refusing it used to cost
exactly that 3.1x, on the shape this backend exists for.

**Everything above the codegen is shared, nothing is re-derived**: `compiler/WasmImportDirective`
parses, `WasmImportCompiler.parse`/`hostParamTypes`/`hostResultTypes` settle the host signature,
and `am.ik.wasm.WasmImportInjector` resolves the `PLACEHOLDER_FUNC_BASE` encoding on the finished
module (`.kb/wasm-import.md`). Only the WRAPPER BODY is this backend's own, because only the
value model differs.

- **Each import is a synthetic internal function** in the same BFS index space as the defuns:
  `collectCalls` accepts its name as an eligible callee, `inferTypes` PINS both sides from the
  declared designators (it joins the `boundary` map exports use, and its body is never walked),
  and `compileUserCall` then emits an ordinary `call` after coercing to those types. Nothing in
  the call path knows it is an import.
- **The type vocabulary follows the house integer**: `WasmImportCompiler.SCALAR_PARAM_TYPES`,
  derived as `BoundaryType.witName() != null` -- the whole fixed-width integer family, `:float`,
  `:bool`, `:string`, and `:void` as a result. `:s-expr`/`:bytes` are refused by name. That the
  set is exactly "the types a WIT world can spell" is why one world serves both core-module
  backends.
- **Marshalling is nearly empty**: `:s64`/`:float` are the identity; a narrower integer is
  `i32.wrap_i64` behind the guard below; `:bool` is one `i64.ne 0` out / `i32.eqz;i32.eqz` in;
  `:void` answers NOTHING -- the forwarder's type is `(...) -> ()` and the call site pushes
  nothing for anyone to drop. A `:string` ARGUMENT is `(ptr+4, [ptr])` of a block the module
  already holds -- **no staging, no copy, and therefore none of the aliasing the wasm-GC
  wrapper had to fix** (`.kb/wasm-import.md`); a LITERAL argument does not even compute
  that pair, and takes the wrapper with it (below). That pointer is BORROWED and durable, not
  scratch: it can be the module's own literal storage (`StringTable` dedups identical
  spellings into one block) or another live block, valid for the whole life of the instance
  -- the contract is READ-ONLY, and a host that writes through it corrupts every other use
  of the same bytes, permanently. This is the same hazard `.todo/789`'s item 2 was rejected
  over on the wasm-GC side, where the argument pointer is short-lived staged scratch instead
  of durable module memory, so a write there reaches memory already dead by the time the
  call returns -- same question, different backend, different blast radius; both halves are
  written down together in `doc/*/guides/wasm-host-boundary.md`. Only a `:string` RESULT
  copies: the host's `(ptr,len)` into a fresh `[len][bytes]` block via `__alloc`/`__memcpy`.
- **The boundary carries the value exactly or traps, in BOTH directions** -- the export
  wrapper's rule with the directions swapped. An ARGUMENT leaves the house `i64`, so a narrow
  or unsigned declared type is range-guarded (the same `emitBoundaryRangeGuard`); a RESULT
  arrives into it, so only `:u64` is.
- **Only REACHED imports are imported.** A declared-but-uncalled host function is never enqueued,
  so it costs neither an import entry nor a byte at any optimize level -- the rule every other
  function here follows. Ordinals are assigned in DECLARATION order among the reached ones.
- **Refusals**: `:async t` (a settled future is still a future, and there is no value for one).
  `rontolisp:wit-import` lowers to this and is accepted, except an `async func`. Under
  `--component` the reached imports become the component's own (below, "Host imports").
- The host types are appended AFTER every other type-section entry (nothing renumbers), and the
  injector prepends the entries ahead of a printing program's `fd_write`, which shifts with
  everything else. Pins: `NoGcWasmCompilerTest` (`aHostImportBecomesTheModulesOnlyImportEntry`,
  `theHostImportsPrecedeAPrintingModulesFdWrite`, `aDeclaredButUncalledImportCostsTheModuleNothing`,
  `aScalarImportNeedsNeitherMemoryNorAnAllocator`, the refusals, and the six
  `...TakesTheWrapperWithIt` / `...KeepsItsWrapper` / `theFoldIsDeclined...` pairs below)
  and the node host in `NoGcWasmImportE2eTest` (every type both ways, both guards, both
  flatness loops, the wit-import byte identity, and
  `aLiteralStringArgumentReachesTheHostWithoutTheWrapper` for the fold's CONTENT).

### A literal `:string` argument is two constants, and the wrapper goes with it
The wrapper's whole body for a `:string` parameter is `local.get p; i32.const 4; i32.add;
local.get p; i32.load` -- ten bytes turning a `[len][bytes]` header pointer into the
`(content ptr, len)` pair. For a LITERAL both halves are compile-time constants (the block
sits at a fixed address in the static data segment), so the SITE pushes two constants and
calls the host function itself. Nothing is staged and nothing is copied: the pointer is the
module's own permanent literal block, the same one the wrapper would have computed, so the
borrowed/read-only contract above is unchanged word for word. This is the `--no-gc` half of
the wasm-GC lowering in `.kb/wasm-import.md` ("A literal `:string` argument does not
round-trip") -- different mechanism, nothing shared but the idea: there the saving is a
byte-loop round trip through a GC array and the bytes land in a reserved staging block,
here there is nothing to stage and the saving IS the wrapper.
`WasmImportCompiler.canLowerLiteralCallSite` is deliberately not reused: the GC side's
parameter vocabulary is `:s32` only where this one takes the whole fixed-width family, so
the two questions have different answers.

- **Whole-program and per IMPORT, never per site.** A folded site does not remove the
  wrapper by itself -- only the last one does -- so a mixed import would pay the length
  constants at its literal sites AND keep the wrapper, a pure loss. The fold is taken only
  when EVERY reached site qualifies. This backend has no first-class functions
  (`#'name`/`funcall` are compile errors), so the reached call sites are every reference a
  wrapper can have: when they all fold it is simply never emitted -- at every optimize
  level, without waiting for the tree shaker.
- **A thin forwarder is where the literal is.** `(defun set-text (id text) (js-set-text id
  text))` -- the shape a host-facing module is actually written in -- puts a PARAMETER at
  the import call site and the literal one frame up. `findForwarders` reads through it: a
  defun whose whole body is one call handing its own parameters, in order, to an import
  (chains resolved) and which no export names IS that call, so its own sites are where the
  fold looks; when the fold takes them all, the forwarder is not emitted either, and the
  program that called the import directly compiles to the same bytes.
- **This is the half `800` could not hand over.** The single-call-site move is a BYTE-level
  pass, so the literals it substitutes into a wrapper's address arithmetic arrive after
  emission, where no source-level lowering can see them -- which is why this item read as
  worth zero for as long as it was scoped to import call sites alone. Measured 2026-09-13
  on `.todo/artefacts/805-no-gc-literal-import-call-sites/bench.lisp` (four DOM imports
  behind four forwarders, seven literals crossing out): folding import call sites alone is
  **0 bytes**, every literal being at a forwarder site; reading through the forwarders is
  **1,011 -> 953** (-5.7%; 8 functions -> 5, code 300 -> 256, types 47 -> 36) at
  `--optimize=size` and **1,483 -> 1,378** at `--optimize=off`, with the node host's output
  identical byte for byte.
- **Measured, not assumed.** Each folded site costs the length constant per `:string`
  argument plus the marshalling the wrapper held ONCE (`:bool`'s comparison, a narrow
  integer's range guard, the result boxing); against that stand the wrapper's body and
  function entry and every forwarder's. `chooseFoldedImports` emits both sides and compares
  bytes, so enough sites of a wide enough import decline the fold and the pass cannot grow
  a module. Two terms are left out, about a byte each and opposite in sign: the wrapper's
  type entry (which may be another function's too, so it is not counted as saved) and a
  folded site's scratch local.
- **Never folded**: a `:string` RESULT (copying the host's bytes into a fresh block through
  the allocator is a wrapper's worth of code a call site would only repeat), an import with
  no `:string` parameter at all (the scalars marshal the same either way), and an EXPORTED
  forwarder (the host can call it with a string of its own, which is a site the fold cannot
  see).

## Scope and pipeline
Only `(rontolisp:wasm-export ...)` functions with boundary types
`:int`/`:float`/`:bool`/`:string`/`:void`. Top level may contain ONLY defuns + export/import
directives (a host-driven reactor; the `_start` command-module stretch is `.todo/111`). The ONE
other thing tolerated there is the residue `PackageResolver` leaves where a consumed package
declaration stood -- a quoted symbol (`defpackage`) or `(setq *package* :P)` (`in-package`) --
which is DROPPED: there is no top-level init body to evaluate a value in and no `*package*` to
assign, and without this a user `defpackage` (hence any `rontolisp:wit-import`, whose lowering
writes one) could not reach this backend at all. The refusal message for anything else names the
SUBSET, not the other backend: a program is usually one form away from fitting, and "use the
default GC backend" is an answer that costs ~3x the bytes.
`collectCalls` (BFS from export targets, throwing `UnsupportedOperationException` naming the
op + function for cons/char/symbol/hash/`eval`/I/O/list iteration/global-`setq`/free var,
yielding reachable defuns in discovery order with stable indices) -> `inferTypes` fixpoint ->
`compileExpr` per body + a host wrapper. The three share one dispatch shape and expand the
same macros the other backends do. Reuses `WasmExportCompiler.parse`/`isExportForm`/
`paramWasmTypes`/`resultWasmTypes` + the `T_*` constants; composes with `--optimize`
(`WasmTreeShaker` is GC-agnostic, and `WasmPeephole` + `WasmInliner` run in front of it here
exactly as they do on the GC backend -- this is the backend the move pays on, the browser reactor
of `.todo/804` going 1,090 -> 1,011 B and its code section 360 -> 300 in 17 -> 8 functions:
`.kb/optimize-dead-code-elimination.md`, "The single-call-site move"; the peepholes take the
`hello` Worker 507 -> 489 and `pi_approx --no-gc` 3,333 -> 3,289). The type-test fold and the
forwarder redirect are GC-side only; the move subsumes the second here, since a forwarder called
once is a body with one call site.

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
- **Host imports** (`.todo/794`, landed 2026-09-12): the REACHED `rontolisp:wasm-import`s
  become component-model instance imports, one imported instance per `:from` module, typed by
  the reached functions under their `:param-names` (`WasmImportDirective` parses the option;
  only this wrap reads it). A scalar-only import is `alias -> canon lower (no options) -> core
  instance` AHEAD of the core's instantiation. A `:string` argument or result makes the lower
  name the core's own memory (and `cabi_realloc` for a result), which cannot exist before the
  core does -- the wit-component instantiation cycle -- so those imports go through a shim +
  fixup pair GENERATED from the signatures (`am.ik.wasm.WasmShimModules`: a funcref table
  exported as `$imports`, trampolines `"0".."n-1"`, a fixup whose element segment patches the
  lowered functions in once memory is aliased). Only the string-involving imports take the
  table (wit-component's rule); the scalar ones stay direct. A `:string` RESULT takes the
  canonical retptr shape in the CORE (`coreParamTypes`: host params + trailing `i32`, no
  results; the wrapper `__alloc`s the 8-byte record and reads `(ptr,len)` back), so that is the
  one import shape whose core is NOT byte-identical to the Preview 1 module (there the result
  is the two-value `(ptr,len)`); it also gates `cabi_realloc` on
  (`NoGcWasmComponentBuilder.needsRealloc`, the one predicate both compiler and wrap use).
  Names follow the component grammar and are checked by `validateComponentImport`: `:from` a
  label or a WIT interface id (`INTERFACE_ID`), `:as` and every param name a label; two
  bindings of one `(module, field)` are refused (an instance type declares a name once).
  `WitImportDirective` lowers under `Backend.WASM_NO_GC_COMPONENT` with the interface's
  CANONICAL id as `:from`, the WIT label verbatim as `:as` and the WIT parameter names as
  `:param-names` (so a composed provider type-checks down to the names), and refuses
  resources and non-scalar types at the WIT line -- Preview 1 `--no-gc` keeps the bare-name /
  camelCase lowering and its bytes. `--emit-wit` renders the imports (`WitEmitter.emitNoGc`:
  an id prints as `import ns:pkg/iface;` + package block, a label as the inline
  `import env: interface {...}`), byte-identical to `wasm-tools component wit`. Every index in
  the builder is a CURSOR; with no imports every cursor starts where the fixed indices were, so
  every pre-existing output is byte-identical (verified against the previous jar on the
  scalar / string / print / print+string shapes). Print composes: the fixed print shim and the
  generated import shim are two tables the core instantiates against, two fixups.
  **Measured 2026-09-12** (`--optimize=size`): the `.todo/789` reactor (two `:string` imports)
  is 1,027 B as a component (530 B core; wit-component's own wrap of the same core, stripped,
  is 1,041 B) against 2,650 B for the wasm-GC `--component` of the same WIT world -- NOT the
  ~110 KB `.todo/794` assumed, which is jco's transpiled JS runtime and is paid by both. A
  scalar-only import costs ~90 B of import block, a string-involving set ~400 B (shim +
  fixup + wiring). `wasmtime --invoke` cannot host a user import at all (no linker entry): the
  hosts are jco (`--map env=./env.js`; jco 1.33 / node 24 runs it with NO JSPI, where the GC
  component of the same world needs `--experimental-wasm-jspi` for its async `wasi:cli/run`
  lift) and composition (`wac plug` / `wasm-tools compose` with a provider component -- a
  rontolisp `--no-gc --component` that `wit-export`s the interface works, and that is the E2E).
- Component-mode-only compile errors (in `compile()`, not codegen): non-kebab export names
  (`WasmExportCompiler.COMPONENT_EXPORT_NAME`), non-component import names, and `:async`.
  `:s-expr` stays rejected for ALL `--no-gc` by `validateScalarTypes`. `--optimize` composes
  (shake before the wrap; the cabi exports are roots). `--emit-wit` writes the WIT world
  (`nogc`/`nogc-print` templates off `mem.printUsed()`, plus the reached imports;
  `.kb/wasi-component.md`).

## `--no-wasi`
A PRINTING program's `fd_write` import is replaced by an internal discarding SINK at the same
function index 0 (`WasmIoRuntimeBuilder.buildNoWasiFdWriteSinkBody`, the GC backend's body
verbatim), so the module keeps ZERO imports while `Mem.funcBase()` stays 1; a print-free
program is a byte-exact no-op. Under `--component` the wrap consequently never wires the
print micro-adapter (`printAdapter = printUsed && !noWasi` selects the build AND the WIT
template): a printing program takes the print-FREE shape (one core module, no import block,
SYNC lifts). **Do NOT re-split this from the GC half.** `--no-wasi` says nothing about USER
imports on this backend: a `--no-gc --component --no-wasi` program keeps its host imports (only
the WASI one is sunk).

## Tests
`NoGcWasmCompilerTest` (structural, no Docker): the module-surface group
(`theTypeSectionWritesEachSignatureOnce`, `distinctSignaturesStillGetTheirOwnTypeEntry`,
`aModuleThatOnlyPassesItsOwnLiteralsOutOmitsTheArenaApi`,
`aStringReturningImportKeepsTheArenaApi`, `aWrapperThatCannotAllocateCarriesNoHeapBracket`,
`aComparisonFeedsTheBranchWithoutBeingWidenedFirst`, `theConstantTrueArmOfACondEmitsNoTest`,
`stringLiteralsArePackedWithoutAlignmentPadding`), the VOID group
(`aVoidImportCallLeavesNothingForItsCallerToDrop`, `aVoidBodyMakesAVoidExportAPassThrough`,
`theDeadNilOfACondTArmDoesNotDragAVoidChainBackToAnInteger`,
`aWhileLoopPushesNothingForTheFormAfterItToDrop`), the heap-reset trio,
`stringModuleExportsTheHostArenaApi`, `printGatesTheFdWriteImportOnAndOff`,
`componentWrapsThePlainCoreModuleVerbatim`,
`componentStringExportAppendsTheCanonicalStringAbi`,
`componentSharesOnePostReturnPerFlatResultSignature`, `componentPrintWiresTheMicroAdapter`,
`noWasiReplacesTheFdWriteImportWithADiscardingSink`,
`componentNoWasiPrintingProgramTakesThePrintFreeShape`, and the host-import group
(`aHostImportBecomesTheModulesOnlyImportEntry`, `theHostImportsPrecedeAPrintingModulesFdWrite`,
`aDeclaredButUncalledImportCostsTheModuleNothing`, `aScalarImportNeedsNeitherMemoryNorAnAllocator`,
`aStringBoundaryOnAnImportPullsInTheMemoryAndOnlyAResultTheAllocator`, the refusals,
`aConsumedPackageDeclarationIsDroppedRatherThanRefused`), the component-import group
(`aScalarComponentImportIsAnInstanceImportLoweredAheadOfTheCore`,
`aStringComponentImportGoesThroughAGeneratedShimAndFixup`,
`aStringResultComponentImportTakesTheReturnPointerAbiAndPullsInRealloc`,
`componentPrintComposesWithTheImportShim`, `componentImportNamesFollowTheComponentModelGrammar`,
`anUncalledComponentImportDeclaresNothing`) and `WitOracleE2eTest.noGcComponentImportWitsMatch...`.
Host imports at RUNTIME are a JS host on node: `NoGcWasmImportE2eTest` -- nothing smaller can
read a `:string` argument's bytes or write a `:string` result's, since a preloaded wasm host has
its own linear memory. Component imports at runtime: `NoGcWasmComponentImportE2eTest` (wasmtime +
wasm-tools on PATH) composes the consumer with a rontolisp provider and invokes the result --
scalars and strings both ways, the wit-import lowering's byte identity with the hand-written
block, and a printing consumer through both shims. Runtime parity: the `noGc*` cases in
`WasmLispCompilerIntegrationTest` (string primitives, print vs the interpreter, flat-heap
loops under a 2-page cap, WAVE invoke with no flags, the canonical string ABI, `--optimize`
composition, the print micro-adapter and its chunk cap). The `:string`-parameter side needs a
memory-writing host, exercised by `examples/console/mandelbrot-nogc.lisp`.

## Unfinished
`:s-expr` (cons/reader/printer runtime) is deferred (`.todo/023`); the `_start`
command-module stretch is `.todo/111`.
