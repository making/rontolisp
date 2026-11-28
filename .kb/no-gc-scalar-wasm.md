# `--no-gc` (non-GC WASM lowering)

Opt-in (CLI `--no-gc`; `NoGcWasmCompiler.builder()`). A **separate backend class**
`codegen.wasm.NoGcWasmCompiler`, dispatched from `RontoLispCli.compileToFile` -- NOT a flag
threaded through `WasmLispCompiler`, so the GC path stays untouched. Emits a **plain MVP
module**: no rec group, no `struct`/`array`/`i31`/`eqref`, no linear memory unless the program
uses strings, `fd_write` only when it prints, a host function only where declared ("Host
imports").

"non-GC" is the **value model** (unboxed `i64`/`f64`/linear-memory pointers), ORTHOGONAL to
SIMD: the `simd` ctor arg toggles `vec:` kernels between scalar loops and v128; the loop
bodies live once in `codegen.wasm.WasmVecLoops`, shared with wasm-GC `--simd` (`.kb/vec.md`).

`--no-gc` is the ONLY WASM target keeping packed arrays in linear memory: rank-1
`F64VEC`/`F32VEC` (`[count:i32][data]`), rank-2 `F64MAT`/`F32MAT` (`[rows][cols][data]`), for
`vec:matvec`. Rank >= 3, rank-2 `#d`/`#f` literals and `array-dimensions` are compile errors.

The **type section carries each distinct signature once** (`TypeTable`): a wasm function type
is only named by index, so folding renumbers nothing else. `fd_write`'s signature is interned
FIRST so it stays type 0, which the import entry names.

## Value model and inference
`inferTypes` is a **monotone fixpoint** over the call graph: exported params pinned to the
boundary designator; all other params and **all let/`do` locals** (`Types.locals`) start at
BOOL and only widen (BOOL -> INT -> FLOAT); `compileExpr` inserts promotions from
`staticType`, and locals are allocated at the widened type so `local.tee` stays consistent.
`i64` makes integers exact to 2^63. INT yields to STRING; FLOAT-vs-STRING and string-vs-number
are type errors (except nil->""). Predicates and `t`/`nil` answer BOOL; arithmetic seeds INT.
**Stated residual**: BOOL joined with INT is INT, so `(princ (if p t 1))` prints `1` where the
interpreter prints `T`. An exact module's nil meeting an integer is `NINT` instead, its seeds
`NEVER` ("Exact mode").

**VOID is the TRUE bottom** (`join(VOID, X) = X`; an exact module's `NEVER` is below it, "Exact
mode"): a form leaving the stack untouched. It meets a value only in `coerce`, which
materializes nil in the consumer's representation (`pushNil`); `coerce(X, VOID)` is the one
place a `drop` is decided. Void are exactly: `while`, `terpri`,
an empty `progn`, a call to a `:void` import. It spreads through the return fixpoint
(`returns` seeds at VOID), except a function exported under a value-returning designator seeds
at INT.
- A **local slot is never VOID** (`slotTy`).
- A **constant `if` test takes only the selected branch into the result type**, and an ABSENT
  else contributes VOID, not INT zero. Both load-bearing: every `cond` ends in
  `(if t ... nil)`, and that dead nil would drag an all-void chain back to INT. Both arms are
  still WALKED (the walk records call sites and widens locals).
- **`typeOf` and `compileExpr` must answer the SAME type for every form**, or the module fails
  validation. Pairs that move together: `while`, `terpri`, `progn`, `%block`/`return`, both
  `if` rules.
- Result: zero `i64.const 0` / `drop` pairs, so a void export reaches `isPassThroughExport`
  (an `:s32` param still needs its wrapper's `i64.extend_i32_s`).

### There is no i32 tier, and there must not be one
**Rejected** (a spike). With INT as `i32`, **every
boundary value can fit `:s32` while an INTERMEDIATE crosses 2^31**: `(mod (* n n) 1000)` of
65536 answers 0 for 296, `(mod (fact 13) 10000)` 3504 for 800 -- silently wrong on exactly the
shapes this backend is advertised for. No boundary guard sees an intermediate; range analysis
fails too (`(+ a b)` of two `:s32` needs 33 bits; widening gives top at `fib`'s first `+`).
Wrap point stays 2^63. A narrow tier would have to be an explicit user DECLARATION.

## Arithmetic
`mod`/`rem` native per type (FLOAT = the exact `WasmFmodRuntimeBuilder` reduction inlined),
`min`/`max` INT fold via `select`, bitwise on `i64`.

**A mixed integer/float comparison decides exactly** (the float's exact binary value against
the i64, like the interpreter): `(= 9007199254740993 9007199254740992.0)` is NIL. Only a pair
statically INT-ish vs FLOAT takes it. `emitExactIntFloatCompare` is inlined per site, no shared
helper, because this backend hangs helpers off linear memory, which a numeric module may lack.
It decomposes the float from its bits; the positive-exponent shift is kept only when
`(g >>s exp) == mant` (exact), the negative side divides with quotient + remainder (never
overflows). Scratch locals once per function (`Fn.exactBits/Mant/Exp`). `min`/`max` decide
through the same helper (`compileMixedMinMax`: left on a tie, second on NaN) but answer the
joined f64 (provably the old fold's values; rounding is monotone). Pinned by
`WasmLispCompilerIntegrationTest#noGcIntFloatComparisonIsExactPast2Pow53`; ci-spec keeps the
representable range only (`--no-gc` has no ratios). wasm-GC: `.kb/wasm-bignum.md`.

**Rounding is the one place this backend cannot match the other four** (no bignum tier by
design, `.kb/wasm-bignum.md`): `(floor 1d300)` TRAPS (`i64.trunc_s_f64`); `(truncate 1d18 7.0)`
answers `142857142857142864` (exact: `...857`); `(floor -3.0 (/ 1.0 0.0))` is `0` (others `-1`).
Remainders are unaffected; `ffloor` etc. inherit it via `LispMacroExpander.expandFFamily`.

**`(%mask-signed-field k x)`** (every Java integral operation): the house `+`/`-`/`*`/left shift
keep the low 64 bits of the exact result, so the field of the wrapped i64 is the exact one: 64
is nothing, 32 `i32.wrap_i64` + `i64.extend_i32_s`, 16/8 one `i64.extend16_s`/`extend8_s` (2
bytes against the shift pair's 6; the boundary's canon compare already needs the sign-extension
ops), any other width the shift pair. Two shapes keep what the wrap loses: `(truncate a b)`
under the mask divides wrapping (a divisor of -1 negates: `Long.MIN_VALUE / -1`), and
`(ash (logand x 2^64-1) (- (logand n 63)))` (a `long >>>`; the bignum mask is no i64,
`LispMacroExpander.unsignedShift`) is `i64.shr_u`. **`(truncate a b)` of two integers is `i64.div_s`**, exact past 2^53 where
`(truncate (/ a b))` divides as doubles (`9007199254740993 / 1` is ...992 there); MIN / -1 and a
zero divisor trap. `floor`/`ceiling`/`round` of two integers divide as doubles (stated residual,
inexact past 2^53). `%single-float` is `f32.demote_f64` + `f64.promote_f32`, as on wasm-GC.

## Iteration and control
`dotimes`/`do`/`do*` expand to `let`/`while`/`%block`/`setq`/`return`. `while` is VOID (the nil
other backends answer is never read; where it is, the join materializes it). `%block` is a
**typed** block whose result joins normal completion and every `return` (`0x40` when VOID);
`return` is a `br` at `Fn.ctrlDepth - blockMarker` (`if` +1, `while` +2, `%block` +1); `setq`
is a `local.tee` of a local, `global.set` of a global. **A `block` keeps its name** (`Fn.blocks`):
`return-from name` leaves the innermost of that name, `return` the innermost nil one (`%block`),
never a named block (a Java `break`/`continue` is a `return-from`).

A **predicate feeds `if`/`br_if` directly**: `emitPredicate` records that the i32->i64 widening
is the last byte written and `takeFlag` takes it back when the consumer is a branch (the test is
positional, so it cannot misfire). A CONSTANT test decides at compile time.

## Strings
A string is an `i32` pointer to `[len:i32 LE][UTF-8 bytes]`; literals laid out back to back,
UNALIGNED (an unaligned `i32.load` is legal), from `STR_DATA_BASE`=8, so addr 0 is a valid empty
string (nil in string context). The Schubfach tables after the literals keep alignment (hot
i64/f64 reads).

**Bytes 4 to 7 of the memory are the program's** (documented): the one address a program knows
without a global, through `"rontolisp:memory"`
(`NoGcWasmImportE2eTest.theMemoryModuleReadsWhatAHostAnsweredThroughOutPointers`).

**A string in test position is its pointer** (`emitTruthy`/`emitFalsy` take the i32): address 0
is nil, every other string (empty ones included) true.

**`cond` and `or` take a temporary per test** (`expandCondPerTest`): `Types.locals` types a local
by NAME per function, so a shared `__cond` for a boolean and a string `or` was a type error. The
name derives from the test's printed form (re-expansion agrees). Two user `let`s of one name and
different types in one function are still refused.

**A literal only folded import sites use has no header.** A folded site pushes content address
and byte length as constants, so a spelling whose EVERY occurrence is a folded `:string`
argument (or a statement `princ`, below) is laid out as bytes alone (`MemLayout.regions` vs
`literals`; a value use of a header-free literal fails loudly). The printer's and `__ftoa`'s
fragments are header pointers by contract.
- **Classified by count over one walk**: `collectCalls` tallies every literal occurrence;
  header-free when the folded/print tallies equal the total.
- **The fold and the layout are circular**, resolved in one order: `chooseFoldedImports` is
  sized against the all-headered plan, then `withHeaderFree` re-lays the SAME order. Dropping
  headers only lowers addresses, so a sized constant can only shorten.
- `chooseFoldedImports` does not count the per-literal bytes folding saves (whole-program
  accounting); left until a measurement asks.
- **A statement `(princ <literal>)` is two constants**: `compileStatement` writes the region via
  `__write_stdout(ptr, len)` and leaves nothing (VOID); a value-position `princ` of a literal
  does the same and leaves the header address. `print` keeps the generic path.
  - **Statement position is carried down the reachability walk**: `collectCalls` threads a
    `stmt` flag mirroring exactly where `compileStatement` runs, tallied in
    `printLiteralSites` beside `literalOccurrences`. Must stay in step with the emitter.
- `(concatenate 'string ...)` bump-allocates via `__alloc` (heap pointer = mut-i32 global 0) and
  `__memcpy`. Only the STRING result family exists (`.kb/concatenate-result-families.md`).
  `subseq` has no bounds check.
- **A character IS its i64 code point**: `char-code`/`code-char` identities, `char=` numeric.
- **`length`, `char` and `subseq` count CHARACTERS, like the other backends**; the header stays
  the BYTE count (allocation, copy, print and host ABI move bytes). Helpers `__strlen_cp`,
  `__byte_offset`, `__char_at`; `string=` stays byte-wise (UTF-8 preserves code-point order).
  Each helper is gated on its operator (`MemLayout.strlenUsed` / `byteOffsetUsed` /
  `charAtUsed`) and reuses interned types, so a module that never indexes a string is
  byte-identical.
- **Indexing here is O(n), the one exception to `.kb/string-index-cost.md`**: no cursor or
  breakpoint table beside `[len][bytes]`. The backend's ASCII reactors never notice; a hot loop
  over a long wide string belongs on a backend with the cursor.
- Helper indices: `internalCount+0..+3` (alloc, memcpy, streq, itoa) then the gated UTF-8
  helpers; `internalCount` counts internal functions and EMITTED wrappers, assigned in
  `placeFunctions` -- nothing assumes one wrapper per export. Memory + helpers only when the
  module uses strings (`Mem.used`), so a numeric module stays byte-identical.

## Boundary (host ABI)
`:int`/`:bool` are `i32`, `:float` `f64`, `:string` a `(ptr,len)` i32 pair; a returned value
outside i32 wraps. With no conversion and nothing able to allocate (`Mem.allocates()` false) the
wrapper is elided (`isPassThroughExport`, `.kb/wasm-export-no-wasi.md`); identities are
`:s64`/INT, `:float`/FLOAT, `:void`/VOID. Documented divergences: no rational type, `0` is false.
- **A `:string` export parameter becomes the internal string in place**: exported
  `__ronto_alloc(n)` is `__alloc(n + 4) + 4`, and the wrapper stores the length at `ptr - 4` and
  passes `ptr - 4` -- no copy, no heap bracket. `cabi_realloc` reserves the same four bytes, so
  one wrapper body serves `--component` too.
  - **Boundary contract: the pointer MUST come from `__ronto_alloc`** (or the canonical
    lowering); any other pointer has its preceding four bytes overwritten. Stated for hosts in
    `doc/*/guides/wasm-nogc.md` ("Reclaiming memory").
  - `--reentrant` with `--no-gc` is refused by the CLI, so no overlap problem.
- **`emitRangeChecks`'s guard follows the width**: `:s8`/`:s16`/`:s32`/`:u32` use the
  canon-compare `v != canon(v)` (as wasm-GC `WasmExportCompiler.emitNarrowIntResult`); `:u8`/`:u16`
  keep one `I64_GT_U` bound compare (cheaper than the mask); `:u64` the sign check only.
- **Wrapper auto-reset for scalar returns**: `__ronto_alloc` never frees. For a non-memory scalar
  return with `Mem.used()` and `Mem.allocates()`, `compileWrapperBody` snapshots heap global 0 at
  entry and restores it before `END` (the host's input buffer is below the mark). **Not** when
  the program owns the heap through `rontolisp:memory` (`Mem.programOwnsHeap`): its allocator
  export's answer would be handed out again (a Proxy-Wasm `proxy_on_memory_allocate` read back
  garbage).
- **`Mem.allocates()`** = can anything bump the heap in a call: a `:string` import RESULT, a
  string-producing op, a packed vector, or printing an INT/FLOAT. A `:string` export PARAMETER
  does not (in place); a module only reading its own literals answers false, so a scalar
  identity export can pass through though the module has memory.
- **Host arena API**: exported `__ronto_alloc` plus `__ronto_alloc_mark () -> i32` /
  `__ronto_alloc_reset (i32 mark)` over the same heap global, exported ONLY when the boundary
  gives the host something to do with the heap (`Mem.hostArena`): an export takes or returns a
  `:string`, or a reached import returns one. Take the mark BEFORE the host's input buffer,
  reset AFTER reading a `:string` result. Example: `examples/count-vowels/`. A program that
  exports its own allocator (`exportsItsOwnAllocator`, Proxy-Wasm) keeps the bodies for its own
  calls (`arenaHelpers`) but drops the export entries.
- **`rontolisp:with-arena`** closes the intra-call hole. Cross-backend it is a `progn`
  (`LispMacroExpander.expandWithArena`; ci-spec `with-arena-is-observationally-a-progn`); here
  `compileWithArena` resets to the mark, first `__memcpy`ing a reference result DOWN to it
  (`emitRefByteSize`). Escape contract (documented, not enforced): only the body's value may
  survive; a `return` across it skips the pop (leak, not corruption).

## Host imports (`rontolisp:wasm-import`)
Accepted here too: a host-driven reactor is a fraction of its wasm-GC size on this backend,
the shape it exists for.

**Everything above the codegen is shared**: `compiler/WasmImportDirective` parses,
`WasmImportCompiler.parse`/`hostParamTypes`/`hostResultTypes` settle the signature,
`am.ik.wasm.WasmImportInjector` resolves `PLACEHOLDER_FUNC_BASE` (`.kb/wasm-import.md`). Only the
wrapper body is this backend's own.

- **Each import is a synthetic internal function** in the defuns' index space: `inferTypes`
  PINS both sides from the designators, and `compileUserCall` emits an ordinary `call`.
- **Type vocabulary**: `WasmImportCompiler.SCALAR_PARAM_TYPES` = `BoundaryType.witName() != null`
  (fixed-width integers, `:float`, `:bool`, `:string`, `:void` result) -- exactly what a WIT
  world can spell, so one world serves both core-module backends. `:s-expr`/`:bytes` refused.
- **Marshalling is nearly empty**; `:void` answers nothing (`(...) -> ()`). A `:string` ARGUMENT
  is `(ptr+4, [ptr])` of a block the module holds -- no staging, no copy, so none of the wasm-GC
  aliasing (`.kb/wasm-import.md`). The pointer is BORROWED and durable (possibly a deduplicated
  literal, `StringTable`): the contract is READ-ONLY, and a writing host corrupts every use of
  those bytes permanently (on wasm-GC it is dead scratch instead; both in
  `doc/*/guides/wasm-host-boundary.md`). Only a `:string` RESULT copies into a fresh block.
- **The boundary carries the value exactly or traps, both directions**: an argument with a
  narrow/unsigned type is range-guarded (`emitBoundaryRangeGuard`); a result only for `:u64`.
- **Only REACHED imports are imported**, ordinals in declaration order.
- **Refusals**: `:async t` (a future); `rontolisp:wit-import` lowers to this, an `async func`
  under `:blocking t` only ("An `async func`: a call that waits").
- Host types are appended after every other type entry; the injector prepends imports ahead of
  `fd_write`. Pins: `NoGcWasmCompilerTest` (`aHostImportBecomesTheModulesOnlyImportEntry`,
  `theHostImportsPrecedeAPrintingModulesFdWrite`, `aDeclaredButUncalledImportCostsTheModuleNothing`,
  `aScalarImportNeedsNeitherMemoryNorAnAllocator`, the refusals, the six
  `...TakesTheWrapperWithIt` / `...KeepsItsWrapper` / `theFoldIsDeclined...` pairs) and the
  node host `NoGcWasmImportE2eTest` (every type both ways, guards, flatness loops, wit-import
  byte identity, `aLiteralStringArgumentReachesTheHostWithoutTheWrapper`).

### A literal `:string` argument is two constants, and the wrapper goes with it
For a LITERAL the wrapper's `(content ptr, len)` computation is two compile-time constants, so
the SITE pushes them and calls the host function itself; the pointer is the same permanent
literal block, so the read-only contract is unchanged. The `--no-gc` half of
`.kb/wasm-import.md` ("A literal `:string` argument does not round-trip"); nothing shared but the
idea. `WasmImportCompiler.canLowerLiteralCallSite` is deliberately not reused: the GC side's
vocabulary is `:s32` only.

- **Whole-program and per IMPORT, never per site**: only the last folded site removes the
  wrapper, so the fold is taken only when EVERY reached site qualifies. No first-class
  functions here, so the reached sites are every reference; the wrapper is simply never emitted.
- **A thin forwarder is where the literal is**: `(defun set-text (id text) (js-set-text id
  text))` puts a parameter at the import site. `findForwarders` reads through a defun whose
  whole body hands its parameters in order to an import (chains resolved) and that no export
  names; when the fold takes all its sites, the forwarder is not emitted either. Folding import
  sites alone saves nothing (the byte-level single-call-site move substitutes after emission,
  out of sight): the win is entirely through forwarders.
- **Measured, not assumed**: `chooseFoldedImports` emits both sides and compares bytes (length
  constants and per-site marshalling vs wrapper and forwarders), so the pass cannot grow a
  module. Uncounted: the wrapper's possibly shared type entry and a site's scratch local.
- **Never folded**: a `:string` RESULT, an import without `:string` parameters, an EXPORTED
  forwarder (the host can call it).

## Scope and pipeline
Only `(rontolisp:wasm-export ...)` functions with `:int`/`:float`/`:bool`/`:string`/`:void`; a
program exporting nothing is refused (a command, which `.todo/111` would make). The residue
`PackageResolver` leaves for a package declaration (quoted symbol, `(setq *package* :P)`) is
DROPPED -- otherwise no `defpackage`, hence no `rontolisp:wit-import`, could reach this backend.
`collectCalls` (BFS from exports and the start function; `UnsupportedOperationException` naming op
+ function) -> `inferTypes` -> `compileExpr` + wrapper, expanding the same macros as the other
backends. **A definition this backend cannot compile is refused where it is reached**
(`refusedDefuns`: a lambda-list keyword, a `format` past the four directives): a spliced library
keeps whatever the program names anywhere, reached or not.

**Before any pass reads a body** (`NoGcInternalForms`): `%string-concat` is `concatenate`,
`%decimal-piece`/`%princ-piece` `princ-to-string`, `%substring` `subseq`, `%normalize-string` its
argument, `%string-code` its expansion, `write-line`/`write-string` of the standard output a
`princ` (`write-line` + `terpri`), `(funcall #'f x)` `(f x)` -- the scans (`usesStringOp`,
`collectLiterals`, the print gates) read the forms as written.

**A refusal is located** (`noteLocated`, the CLI's `CompileDiagnostics` prefix): the innermost
form a file locates around it; inside a function no file locates (a library's, a generated one)
at the call through which the walk first reached it (`firstCalls`), the message naming that
chain (`, called from 'Calc.parse(String)'`). Only file-located forms are noted: a library read
with no file would fill the slot with a location that prints nothing.

**Trap: an expansion shared with this backend must be DETERMINISTIC** -- fixed temporary names
(a `__name-` prefix, like `CHECK_TYPE_VAR`), never an `MV_COUNTER` gensym: the fixpoint
re-expands every call per pass, a fresh name adds a local per pass, and `changed` never settles
(infinite compile).

`--optimize` composes (`WasmTreeShaker` is GC-agnostic; `WasmPeephole` + `WasmInliner` +
`WasmLocalSink` run as on GC). This backend is where the single-call-site move pays
(`.kb/optimize-dead-code-elimination.md`, "The single-call-site move"); it subsumes the GC-only
forwarder redirect.

## The top level
**Invariant: past the defuns and directives, a top-level form is a global's definition, a
structure's, or code the module runs once at instantiation, in its START FUNCTION; a program
whose top level only gives globals literal values has none, so every older program is
byte-identical** (`NoGcTopLevel`; measured: the 9 compiling `--no-gc` examples, `--optimize`,
`=off` and `--component`).
- **A global is what every backend takes for one** (`GlobalVarCollector`: a definer's name, a
  name assigned where no binding holds it), a mutable wasm global after the heap pointer (global
  0 where the module has memory), declared only where a reached body reads or assigns it. Its
  type is the join of every value assigned to it, in the same fixpoint (`Types.globals`).
- **A literal no code runs ahead of is the global's initial value** (`defvar`'s only for a
  variable with none); once a form runs code, every later assignment runs after it, in the
  start function. Not detected: a read of a global before its definition runs reads its initial
  value (every backend signals unbound-variable there).
- **Start section, not `_initialize`** (the `--no-wasi` wasm-GC reactor's): this backend's hosts
  are hand-written (no `--emit-js-glue`), and one that forgot `_initialize` would read every
  static as zero -- silently. A start function needs no host; wasmtime and a JS host run it at
  instantiation, a component at its core's. The price: a JS host's import called from it runs
  before `instantiate` returns, so it cannot read the module's memory through the instance yet
  (printing at the top level of a WASI module under wasmtime works: measured).
- **A special is never bound** (`let` or a parameter of a `defvar`'s name): it binds
  dynamically, which needs a save/restore around every exit.
- **A string or packed array stored into a global outside the start function is refused**
  unless it is a literal, nil or another global: a call gives its memory back when it ends (the
  wrapper's reset, the host's `__ronto_alloc_reset`), so the global would hold freed memory. What
  the start function allocates is never given back.
- `define-condition`, `declaim`, `proclaim` and a constant form do nothing here.

## A structure nothing reads; an error is a trap
**Invariant: no condition and no heap object exists here, so a structure is made only where the
program never reads it, and `error` ends the call in a trap; every operation that would read
one is refused by name.** Together they compile a Java `throw` with no Java name in the backend:
`(%java-throw (X.new msg) #'Throwable.toString())` makes a structure nothing reads and signals
it, which traps.
- `NoGcStructures`: the functions a top-level `defstruct` defines, by
  `LispMacroExpander.defstructDefinedNames` (every spelling). A constructor runs its operands and
  answers `Ty.OPAQUE` 1; a slot write (`(setf (acc o) v)`, `(funcall #'(setf acc) v o)`) runs
  them and answers the value; an accessor read, the predicate, the copier are refused. `OPAQUE`
  is an i64 (1 or the nil 0): held, passed, returned, tested for nil; joined with a number or a
  reference kind, compared, printed: refused. A function object `#'f` is one too, reaching
  nothing; a `funcall` of one is refused.
- `error`: the arguments that can do something run (not a literal, a keyword, a quoted datum, a
  function object or a variable read), then `unreachable`; typed VOID. A condition handler
  (`handler-case`, `handler-bind`, `ignore-errors`) is refused by name: it could take an error a
  trap ends.
- A trapped call leaves the instance as it stood (statics half-updated, the heap bumped past its
  mark): a host calls it no more, the reactor contract of `.kb/java-frontend.md` ("A server as a
  reactor").

## Exact mode: a program written for Common Lisp
**Invariant: the scalar dialect reads nil as 0, which a program written for this backend may
rely on; a lowered language's program and its run time are written for Common Lisp, so their
module answers as Common Lisp does or refuses** (`Builder.exact`; the CLI sets it from
`CompileFrontend.Result.lowered`: an entry source that is no Common Lisp, or a program holding
`java.lisp`, `JavaLibrary.runs`). Measured 2026-11-25, refusing a test of a number in the
dialect too: 3 of the 9 compiling `--no-gc` examples refused (the Proxy-Wasm SDK's "nil or a
pointer" tests, exact under zero-is-false since a pointer is never 0), so the dialect stays.
- **Nil meeting an integer is `Ty.NINT`, an i64 whose nil is `Long.MIN_VALUE`** (`NIL_INT`):
  where nil (`VOID`, `BOOL`) and an `INT` flow into one value something reads -- a slot, an
  `if`'s branches, a block's result, a parameter, a return, a global. An integer is itself, so a
  read as one costs nothing; the nil test is `x rotl 1 == 1` (6 B; the dialect's `!= 0`, 3 B),
  the nil `1 rotr 1` (5 B; its `i64.const` 11, which a global's initializer, a constant
  expression, takes). So an exact module's `INT` is never nil and its test is true
  (`neverNil`); a `FLOAT`'s test stays refused (`refuseNumberTest`): its nil is its 0.0, and no
  nil-or-float was asked for.
  - **Traps** (`coerce(Fn, Ty, Ty)`, one scratch per function): an integer entering one traps on
    `MIN_VALUE`, the one integer it shadows; one read as an integer (an operand, an `INT`
    parameter of a host import, `:s64`) traps on nil, the type error every other backend
    signals; read as a float (a join with a `FLOAT`, an operand) nil is the 0.0 every float's
    nil is; a boolean entering one is 1 for `t`. A narrow boundary type's range guard traps on
    `MIN_VALUE` already, `:bool` is the nil test, and `eql` compares the i64s (nil eql nil).
  - **Rejected**: a flag beside the value (two values for every slot, parameter and result: a
    result is multi-value, no MVP module), and nil kept 0 with the integers moved
    (`n xor MIN_VALUE`: 12 B at every read, which narrowing makes the common use).
- **A value nothing reads keeps the dialect's join** (`typeOf(.., discard)`, `compileExpr(..,
  discard)`, the same forms on both sides: a statement, the last form or branches of one, a block
  whose value is dropped and the values it returns, the body of a function no position reads --
  `valueUsed`, grown in the inference fixpoint from the value exports). Without it every
  `if (c) x = v;` -- `(if c (setq x v))`, an `INT` and an absent else -- and every void method
  ending in one held a nil-or-integer nothing read.
- **`NEVER`** (exact only; the dialect types the same forms `VOID`, and both emit alike):
  `error`, and a function every path of which ends in one. Below `VOID`, so `(if c (throw) n)`
  is `INT` with no nil. Every return and parameter seeds at it: a `BOOL` seed is a real nil
  there, and joined with the `INT` a later pass finds it made a nil-or-integer of every `:s32`
  export. A slot left `NEVER` (a parameter no call passes a value that returns) is settled to
  `BOOL` after the fixpoint; a value export left `VOID`/`NEVER` traps in its wrapper (`:bool`:
  false).
- **Narrowing**: in a branch of an `if` whose test shows a local not nil -- the variable, a
  `not`'s operand false, a conjunction (`and`, an `if` whose else is nil) true -- and which
  assigns it nowhere (`assigns`: a `setq` the backend's macros reach, the one way a local
  changes with no closure), the local reads as `INT` (`narrowed`, `narrowLocals`): `(if x x d)`,
  `(or x d)`, `(and x (< x r))` read it free. `(if b b n)` of a `BOOL` is its `t` there, which
  meets an integer as the dialect's `INT` does, `t` as 1 (`nilMeetsInt`): `substring(from)`'s
  `(if end end (length s))` over the nil it passes is byte-identical. Not done: a `while` body's.
- **`position`, `search`, `digit-char-p`** (exact only, `NoGcInternalForms.normalize(form,
  exact)`; the dialect refuses them, an index 0 being its nil). `search` of a string in a string
  (`:start2`, `:end2`, `:from-end`) is `%no-gc-search`, the `__search` helper: bytes match where
  characters do (a needle starts at a lead byte), the index is the lead bytes before the match,
  a bound outside the hay traps once `__strlen_cp` has counted it. `position` of a character is
  the search of its one-character string, given back by a `with-arena` around it: O(n), where a
  `char` loop is O(n^2) here. `digit-char-p` is an ASCII digit's weight below the radix, as
  wasm-GC reads it (`.todo/269`); a radix no literal in 2..36 is checked. Arguments bind in the
  order written; `:test`, `:key` and the rest are refused by name.
- **Measured 2026-11-26**: every older module byte-identical -- the 37 modules the 15 programs
  of `examples/` and `size-report/` that compile `--no-gc` make at the default `--optimize`,
  `=off` and `--component` (the exact ones `hello-java`'s `Worker`, `java/no-wasi`'s `Calc`,
  `java/shop`'s `Money`), `JavaReactorTest`'s `Tally`, and a 24-method Java probe of statement
  ifs, `break`/`continue` loops, switches, throws and void methods; every other program of the
  four trees and the test resources refused as before, by the same message. What it
  makes, a reactor of one method each (raw / gzip -9; `--no-wasi`'s raw): `substring(int, int)`
  1,700 / 1,000 (5,715), `compareTo` 1,274 / 803 (4,379), a `double` in a `+` 4,224 / 2,576
  (8,449), `Integer.parseInt` 3,956 / 1,922 (11,789), `indexOf(String)` 1,089 / 693 (5,446);
  those with `lastIndexOf`, `indexOf(char)`, `contains`, `Long.parseLong` and `Character.digit`
  in one class 10,212 / 5,117 (25,488).
- **An index outside a packed vector traps** (`emitElementAddr`: one unsigned compare against
  the `[count]` header); the dialect does not check.
- **A nil `:string` result traps** (no host string is nil), as the wasm-GC reactor's illegal
  cast; the dialect answers the empty string.
- A boolean read as a string or a packed array (a parameter only nil is passed to) is address 0,
  `t` a trap, in both modes (`coerce`): t is no sequence.

## Print / stdout
`format` to `t`/`nil` with a literal `~a`/`~d`/`~%`/`~~` control lowers to these and
`concatenate` (`.kb/format.md`). `print`/`princ`/`terpri` (no stream) are byte-identical to the
interpreter, a computed boolean writing `T`/`NIL`. `emitWriteStringEscaped` writes runs, so a
print never moves the bump heap.
- Gated by `Mem.printUsed`: ONE `fd_write` import at type 0, function 0, shifting every other
  index by `Mem.funcBase()` = 1. **ALL index math flows through `Mem.funcIndex()`/`*Index()`;
  nothing hardcodes the shift.** `__write_stdout(ptr,len)` is **the sole caller of the fd_write
  import**.
- Print-pool fragments arrive on first use (`PrintUse`); a fragment written only as a region has
  no header, while `__ftoa`'s specials and `princ-to-string`'s `T`/`NIL` stay headered.
- `Mem.ftoaUsed` emits `__ftoa` + `__schub_*` + `SchubfachTables` (`Mem.schubBase`), shared with
  GC (`WasmSchubfachRuntimeBuilder`): float text byte-identical at every magnitude.
- The fd_write iov scratch (`Mem.iovAddr`, 16 bytes) sits between static data and `heapBase`;
  printing an INT/FLOAT brackets its transient string in a mark/reset. So `Mem.allocates()` means
  "prints an INT/FLOAT", and a literal-only printing module has no heap global under
  `--optimize=size`. A stream argument or printing a packed array is a compile error.

## `--no-gc --component`
`NoGcWasmComponentBuilder` is a pure POST stage (a scalar-only program's core module is
byte-identical): per-export alias/type/sync `canonLift`/export, no import block, adapter or
`wasi:cli/run`, so `wasmtime run --invoke` needs ZERO flags. `:long` -> VT_S64.
- **`:string` exports** lift through the canonical string ABI over the module's OWN memory: a
  `cabi_realloc` export, a retptr shim per `:string`-returning export (MAX_FLAT_RESULTS = 1),
  ONE `cabi_post_<i32|i64|f64|void>` per flat-result signature resetting the heap to `heapBase`.
  Option order as wasm-tools (`ComponentWriter.canonLiftMemoryReallocUtf8PostReturn`, byte-pinned
  against `wasm-tools dump`).
- **Print -- the print micro-adapter (WASI 0.3)**, gated on `mem.printUsed()`: import block
  `import-block-nogc-print.bin` plus three fixed core modules `src/wasm-component/*-nogc-print.wat`
  (`regen.sh`); `bridge-nogc-print.wat` implements `fd_write` as one stream cycle per call,
  parking on `waitable-set.wait` when BLOCKED.
  - Only an async-typed task may block, so **EVERY export of a printing program lifts as ASYNC**
    (`asyncFuncTypeScalars`). Needs wasmtime 46+; **jco 1.25.2 cannot call these exports** --
    keep jco/browser programs print-free.
  - The instantiation cycle (bridge reads core memory, core imports fd_write from bridge) is
    broken with the wit-component **shim/fixup** pattern; the core stays byte-identical to plain
    `--no-gc`.
  - Bridge contract with `__write_stdout`: fd 1, ONE iovec at the core's 16-byte scratch, reused
    once ptr/len are in locals (event pair at iov..iov+8, `future.read` retptr at iov+8, read
    before nwritten overwrites it); waitable-set handle a bridge global; other fds errno 8.
  - `rg '@0\.2\.0' src/wasm-component src/main` must hit nothing but `wasi:keyvalue@0.2.0-draft`
    (and `am.ik.wit` examples).
- **Host imports**: REACHED imports become instance imports, one instance per `:from`, typed
  under `:param-names` (only this wrap reads it). Scalar-only: `canon lower` (no options) AHEAD of
  the core. A `:string` argument/result needs the core's memory (and `cabi_realloc` for a result),
  which cannot exist before the core, so those go through a shim + fixup GENERATED from the
  signatures (`am.ik.wasm.WasmShimModules`); scalar ones stay direct (wit-component's rule). A
  `:string` RESULT takes the retptr shape in the CORE (`coreParamTypes`), the one import shape
  whose core is NOT byte-identical to Preview 1; `NoGcWasmComponentBuilder.needsRealloc` is the
  one predicate both compiler and wrap use.
  - Names checked by `validateComponentImport` (`:from` a label or WIT interface id,
    `INTERFACE_ID`; `:as` and params labels; one binding per `(module, field)`).
    `WitImportDirective` under `Backend.WASM_NO_GC_COMPONENT` lowers with the CANONICAL id, the
    WIT label verbatim and WIT parameter names, refusing resources and non-scalars; Preview 1
    keeps bare-name / camelCase. `--emit-wit` (`WitEmitter.emitNoGc`) is byte-identical to
    `wasm-tools component wit`.
  - Every builder index is a CURSOR, so with no imports every older output is byte-identical.
    Print composes: two shim tables, two fixups.
  - Size: a component is little more than its core, far below wasm-GC `--component` of the same
    world. Hosts: `wasmtime --invoke` cannot host a user import; jco (`--map env=./env.js`, no
    JSPI needed, unlike the GC component's async `wasi:cli/run`) and composition (`wac plug` /
    `wasm-tools compose` with a provider, e.g. a rontolisp `--no-gc --component` that
    `wit-export`s the interface -- the E2E).
- Component-mode-only compile errors (in `compile()`): non-kebab export names
  (`WasmExportCompiler.COMPONENT_EXPORT_NAME`), non-component import names, a wait no task can
  make ("An `async func`"). `:s-expr` is
  rejected for all `--no-gc` (`validateScalarTypes`). `--optimize` shakes before the wrap (cabi
  exports are roots). `--emit-wit`: `nogc`/`nogc-print` templates (`.kb/wasi-component.md`).

### An `async func`: a call that waits
**Invariant: this value model has no future, so an `async func` is here what its call is to the
caller -- a call that waits and answers the value -- and the one spelling of that on every
backend is `:blocking t` (`.kb/wit.md`); the binding that answers a future stays refused.**
- **Import**: `:blocking t` on `rontolisp:wasm-import` (`Decl.blocking`, read by the wrap alone,
  as `:param-names`; with `:async t` a parse error), which `wit-import :blocking t` writes for an
  `async func` under `--no-gc --component`. The instance type declares the function `async`
  (0x43 for 0x40) and the lower is the SYNC one: the caller's task waits in the host.
- **Export**: `:async t` (a world's `async func`) is the lift's function type; core signature,
  lift and post-return are a sync export's. A printing program's exports are async whatever the
  directive says (the world's `func` is then not what the component exports: older than this).
- **Only the task of an async-typed export may wait**: an export that is none, or the top level
  (the start function runs in no task), reaching a `:blocking t` import is refused with the path
  (`refuseWaitsOutsideAnAsyncTask`; exact, the calls being all there is with no first-class
  function), located at the import's call. Measured 2026-11-26, wasmtime 51: both trap `cannot
  block a synchronous task before returning` once the function does wait, and answer when it
  returns at once (`wait-for` of 0) -- refused all the same, a wait being the host's to decide.
- **A core module** (Preview 1, `--no-wasi`): `:blocking t` and `:async t` change no byte and
  `wit-import :blocking t` is the GC module's plain import. No async ABI and none needed: a JSPI
  host suspends the call around the module (`Suspending` import, `promising` export; node 24,
  a 110 B module waiting on a timer). No re-entry guard: the host makes one call at a time.
- **Measured 2026-11-26** (wasmtime 51, zero flags): a component sleeping on
  `wasi:clocks/monotonic-clock@0.3.0`'s `wait-for` and answering the nanoseconds 425 B (gzip
  269; `nap(20)` 21.3 ms), the same source on the GC component 2,836 B; a Java `@WitExport`
  world of one async and one sync export 190 B. The types cost no byte. jco 1.42.0 transpiles
  and calls an async export over a `:blocking t` import its `--map` answers with an `async`
  function (node 24.22). Byte-identical before and after: the 37 modules the 15 programs of
  `examples/` and `size-report/` make `--no-gc` (default, `--optimize=off`, `--component`), and
  every refusal of the trees' 378 sources the same message but
  `examples/wit/world/analyzer.lisp`'s, which moved from its `async func` export to its `dolist`.
- **Rejected: a future handle the program awaits** (an async lower, `waitable-set.wait` at the
  await). It runs -- a hand-written probe overlapped two 20 ms waits in 21.3 ms -- and costs a
  program that uses none nothing, but one that does pays the waitable-set family, a memory for
  the event pair and every result (an async lower returns through memory), a subtask to drop,
  some 390 B over the blocking shape for a result-less call, and the type lattice a future kind
  per payload; and `:blocking t` already is the binding the front end with no futures (Java)
  writes. Revisit when a `--no-gc` program needs two waits to overlap.
- **Element types**: a `stream<T>` / `future<T>` is no scalar and is refused at the WIT line
  with every other compound type.
- **Java**: an `async func` export lifts async and waits where it reaches an `async func`
  `@WitImport` (`:blocking t`); a `Thread.sleep` stays refused (it needs a mutex). The lookup is
  the binding, a structure nothing reads (`.kb/java-frontend.md`, "WIT imports").
- Pins: `NoGcWasmCompilerTest.aComponentTypesWhatWaitsAsAsyncAndRefusesAWaitNoTaskCanMake` /
  `.anAsyncImportIsRefusedBecauseThereIsNoFutureToAnswerWith`,
  `WitImportDirectiveTest.theNoGcBackendBindsAnAsyncFuncOnlyAsACallThatWaits`,
  `WitExportDirectiveTest.lowersAnAsyncFuncIntoAnAsyncExport`,
  `NoGcWasmComponentImportE2eTest.scalarsStringsAndAWaitCrossBetweenTwoComposedComponents` (two
  tasks waiting: the consumer's for the provider's, that one for the clock),
  `NoGcWasmImportE2eTest.aWitImportedInterfaceIsTheHandWrittenImportBlock` (JSPI),
  `WitOracleE2eTest.noGcComponentImportWitsMatchWasmToolsByteForByte`,
  `JavaWitImportTest.aClassImplementsTheWorldAComponentExports`.

## `--no-wasi`
A printing program's `fd_write` import becomes an internal discarding SINK at function index 0
(`WasmIoRuntimeBuilder.buildNoWasiFdWriteSinkBody`, the GC body verbatim): zero imports,
`Mem.funcBase()` still 1; a print-free program is byte-exact. Under `--component`,
`printAdapter = printUsed && !noWasi` selects build AND WIT template, so a printing program takes
the print-FREE shape. **Do NOT re-split this from the GC half.** User imports are kept (only the
WASI one is sunk).

## Tests
`NoGcWasmCompilerTest` (structural, no Docker): the module-surface group
(`theTypeSectionWritesEachSignatureOnce`, `distinctSignaturesStillGetTheirOwnTypeEntry`,
`aModuleThatOnlyPassesItsOwnLiteralsOutOmitsTheArenaApi`,
`aStringReturningImportKeepsTheArenaApi`, `aWrapperThatCannotAllocateCarriesNoHeapBracket`,
`aComparisonFeedsTheBranchWithoutBeingWidenedFirst`, `theConstantTrueArmOfACondEmitsNoTest`,
`stringLiteralsArePackedWithoutAlignmentPadding`, `exportedRontoAllocReservesTheStringHeader`,
`stringParamWrapperWritesTheHeaderInPlace`, `aStringParamAloneCarriesNoHeapReset`,
`aLiteralOnlyFoldedImportSitesReadCarriesNoLengthHeader`,
`aLiteralAlsoReadAsAValueKeepsItsLengthHeader`, `aStatementPrincOfALiteralWritesTwoConstants`,
`aValuePositionPrincOfALiteralWritesTwoConstantsAndLeavesTheValue`,
`aPrincAsTheLastFormOfAVoidExportWritesTwoConstantsAndLeavesTheValue`,
`printOfALiteralKeepsTheGenericPath`, `aLiteralPrintedAndReadAsAValueKeepsItsLengthHeader`,
`aLiteralPrintedAndPassedToAFoldedImportCarriesNoLengthHeader`,
`aRuntimeFragmentSharedWithAFoldedSiteDropsItsHeaderLikeAProgramLiteral`,
`princToStringOfABooleanKeepsBothHeaders`, `aComputedBooleanPrintsTAndNilByName`,
`aPrincOnlyModuleOmitsTheQuoteAndBooleanFragments`,
`aPrintOfAStringNeedsQuotesButNoBooleanFragments`,
`aLiteralOnlyPrintingModuleCarriesNoHeapBracketAndNoHeapGlobal`; the content each shape hands the
host is `NoGcWasmImportE2eTest.aFoldedLiteralReachesTheHostIntactWhetherOrNotItKeepsItsHeader`,
and a spelling both printed and imported is
`NoGcWasmImportE2eTest.aPrintedLiteralReachesBothTheHostAndStdoutIntact`), the VOID group
(`aVoidImportCallLeavesNothingForItsCallerToDrop`, `aVoidBodyMakesAVoidExportAPassThrough`,
`theDeadNilOfACondTArmDoesNotDragAVoidChainBackToAnInteger`,
`aWhileLoopPushesNothingForTheFormAfterItToDrop`), the heap-reset trio,
`stringModuleExportsTheHostArenaApi`, `printGatesTheFdWriteImportOnAndOff`,
`componentWrapsThePlainCoreModuleVerbatim`, `componentStringExportAppendsTheCanonicalStringAbi`,
`componentSharesOnePostReturnPerFlatResultSignature`, `componentPrintWiresTheMicroAdapter`,
`noWasiReplacesTheFdWriteImportWithADiscardingSink`,
`componentNoWasiPrintingProgramTakesThePrintFreeShape`, the host-import group
(`aHostImportBecomesTheModulesOnlyImportEntry`, `theHostImportsPrecedeAPrintingModulesFdWrite`,
`aDeclaredButUncalledImportCostsTheModuleNothing`, `aScalarImportNeedsNeitherMemoryNorAnAllocator`,
`aStringBoundaryOnAnImportPullsInTheMemoryAndOnlyAResultTheAllocator`, the refusals,
`aConsumedPackageDeclarationIsDroppedRatherThanRefused`), the component-import group
(`aScalarComponentImportIsAnInstanceImportLoweredAheadOfTheCore`,
`aStringComponentImportGoesThroughAGeneratedShimAndFixup`,
`aStringResultComponentImportTakesTheReturnPointerAbiAndPullsInRealloc`,
`componentPrintComposesWithTheImportShim`, `componentImportNamesFollowTheComponentModelGrammar`,
`anUncalledComponentImportDeclaresNothing`) and `WitOracleE2eTest.noGcComponentImportWitsMatch...`.

Runtime: host imports need a JS host on node, `NoGcWasmImportE2eTest` (a preloaded wasm host has
its own memory and cannot read a `:string`'s bytes). Component imports:
`NoGcWasmComponentImportE2eTest` (wasmtime + wasm-tools; composes with a rontolisp provider).
Parity: the `noGc*` cases in `WasmLispCompilerIntegrationTest` (incl.
`noGcPrintedLiteralsFoldAtBothLevels`, `noGcPrintedBooleansMatchTheInterpreter`, flat-heap loops
under a 2-page cap, WAVE invoke, canonical string ABI, print micro-adapter). The
`:string`-parameter side: `NoGcWasmExportStringParamE2eTest` (memory-writing host, both optimize
levels); `examples/console/mandelbrot-nogc.lisp` exercises a `:string` result by hand.

The top level, structures, `error`, named blocks and exact mode: `NoGcWasmCompilerTest`
`theTopLevelIsGlobalsAndAStartFunction` (the initial value, the start section),
`rejectsSpecialVariableBinding`, `aSetfPlaceIsRefusedOnTheNoGcBackend`,
`rejectsProgramWithNoExports`; `WasmLispCompilerIntegrationTest`
`noGcTopLevelHoldsGlobalsAndRunsTheStartFunction` (a computed initial value, a structure, a
`return-from`, the trap) and `noGcUsesExactI64IntegerArithmetic` (the i64 quotient); a lowered
program end to end, `JavaReactorTest.aReactorOfScalarValuesCompilesWithNoGcAndWhatLeavesTheSubsetIsRefusedByName`
(the nil-or-integer members against a JDK, an index 0 among them); the nil-or-integer's
byte-identity and representation, `NoGcWasmCompilerTest.anExactModuleHoldsANilOrIntegerOnlyWhereItsValueIsRead`.

## Unfinished
`:s-expr` (cons/reader/printer runtime) is deferred (`.todo/023`); the `_start` command-module
stretch is `.todo/111`.
