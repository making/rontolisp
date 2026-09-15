# `--no-gc` (non-GC WASM lowering)

Opt-in (CLI `--no-gc`; `NoGcWasmCompiler.builder()`). A
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
boundary designator (`:bool` pins to BOOL); all other param types and **all let/`do`-bound local types**
(`Types.locals`) start at BOOL and only widen (BOOL -> INT -> FLOAT). `compileExpr`
consults `staticType` to insert promotions (`coerce`: `f64.convert_i64_s` /
`i64.trunc_s_f64`; BOOL<->INT is free, both are the i64 0/1); let/`do` locals are allocated at their widened type so `setq`
(`local.tee`) stays type-consistent. `i64` makes integer arithmetic exact to 2^63. `Ty.join`:
BOOL is the value bottom below INT and yields to whatever it meets; INT in turn yields to
STRING; FLOAT-vs-STRING is a type error; mixing a string with a number is rejected (except nil->"").
The predicates (`= < <= > >=`, `not`, `string=`, `char=`) and the `t`/`nil` literals answer
BOOL; arithmetic seeds at INT so a BOOL operand widens to INT on contact. Joining BOOL with
INT therefore answers INT -- `(princ (if p t 1))` prints `1` where the interpreter prints `T`,
a stated residual of the static lattice, not a silent agreement (`.todo/817`).

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

**A mixed integer/float comparison decides exactly** (`.todo/037`, 2026-09-15): the
float's exact binary value against the i64, like the interpreter -- so
`(= 9007199254740993 9007199254740992.0)` is NIL and
`(> 9007199254740993 9007199254740992.0)` is T, where the old f64 coercion
rounded both. Only a pair whose static types are exactly INT-ish (INT, BOOL, or a
character code point) and FLOAT takes the exact path; a VOID side keeps the old
join materialization, and two same-typed sides keep their single opcode. The
emission (`emitExactIntFloatCompare`, inlined per site, no shared helper -- this
backend hangs helpers off linear memory, which a pure-numeric module may not
have) decomposes the float from its raw bits (hidden bit, subnormal shape, sign
on the mantissa, either zero a plain zero; NaN unordered, infinities beyond every
i64): a non-negative exponent shifts the mantissa up and keeps the shift only
when `(g >>s exp) == mant` (a lost high bit never shifts back, so the check is
exact), otherwise the float is strictly beyond every i64 on the mantissa's side;
a negative exponent divides down with a truncating quotient plus remainder (a
differing quotient decides, an equal one falls back to the remainder against
zero), which never overflows at any magnitude, and `K >= 64` leaves `|f| < 1`.
The scratch triple (bits, mantissa, exponent) is allocated once per function
(`Fn.exactBits/Mant/Exp`) and the float rides the stack into the helper, so a
mixed site costs one local, not five -- ten thousand mixed comparisons in one
function still compile. `min`/`max` decide mixed rounds through the same helper
(`compileMixedMinMax`, keeping the left operand on a tie and the second on NaN,
like the interpreter) but still answer the joined f64 values, so an integer
winner prints as its correctly-rounded float -- the values provably coincide
with the old fold's (rounding is monotone: it collapses a strict gap to equal
bits, never reverses it). Pinned by
`WasmLispCompilerIntegrationTest#noGcIntFloatComparisonIsExactPast2Pow53` (past
2^53, both signs, minI64, infinities, NaN, let-carried, min/max parity) plus a
10,716-case no-GC-vs-interpreter differential sweep (comparisons textual, min/max
as doubles); ci-spec keeps the representable range only, since WASM-GC still
compares through f64 there.

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

**A literal only folded import sites use has no header.** A folded site (below) pushes the
content address and byte length as constants, so a spelling whose EVERY occurrence is a
folded site's `:string` argument is laid out as its bytes alone (`MemLayout.regions` holds
every literal's content address, `literals` only the headered ones' header address, so a
value use of a header-free literal fails loudly). Used any other way too -- `length`,
`print`, a value-position `princ`, an unfolded import -- it keeps its header and the
folded site points past it. The
printer's and `__ftoa`'s fragments are header pointers by contract and are never stripped.
- **Classified by count over one walk**: `collectCalls` tallies every literal occurrence
  over the same expanded forms that record the import sites, and a spelling is header-free
  when the folded sites' tally equals it.
- **The fold and the layout are circular**, resolved in one order: `chooseFoldedImports`
  is sized against the all-headered plan, then `withHeaderFree` re-lays the SAME literal
  order. Dropping headers only lowers addresses, so a sized constant can only shorten.
  Every gate (`printUsed`, `hostArena`, ...) is a property of the bodies, not the layout.
- `chooseFoldedImports` does not count the four bytes per literal folding now also saves;
  an import just the wrong side of its comparison stays unfolded. Left alone until a
  measurement asks (the accounting is whole-program: spellings are shared across imports).
- Measured 2026-09-13 on `.todo/artefacts/810-no-gc-dead-literal-length-headers/reactor.lisp`
  (eleven folded-only literals): `--optimize=size` 930 -> 886 raw, gzip 650 -> 626, data
  484 -> 440, code unchanged; `--optimize=off` 1353 -> 1309. Host output identical; that
  artefact's `probe/` matches the interpreter at both levels, and its no-fold control is
  byte-identical. With `.todo/811`'s range guard merged the same reactor is 900 -> 856
  (gzip 613; code 200, data 440): the two compose exactly. A lower `heapBase` can shorten
  its LEB128 in the global section.
- **A statement `(princ <literal>)` is two constants as well** (`.todo/814`,
  landed 2026-09-14). Printing funnels through `__write_stdout(ptr, len)`, and the
  generic string path computes the pair from the header at run time -- but for a
  literal both halves are compile-time constants, the same lowering `terpri`'s
  `"\n"` already uses. So `compileStatement` writes the region directly and leaves
  nothing behind (VOID, no DROP); a value-position `princ` of a literal writes the
  same two constants and leaves the header address as the value. `print` keeps the
  generic path (its quotes, escapes and trailing newline are a run loop, not one
  literal), and `princ-to-string` never prints.
  - **Statement position is carried down the reachability walk**: `collectCalls`
    threads a `stmt` flag mirroring exactly where the emitter's `compileStatement`
    runs (non-last `progn`/`%block`/body forms of `let`/`with-arena`,
    `while` bodies; tests, last forms, `if` branches, `setq` right-hand sides,
    call arguments and `print` are value positions), tallied per spelling in
    `printLiteralSites` beside `literalOccurrences`. A spelling whose folded
    import + print tallies equal its total is laid out header-free, and a folded
    site of a headered spelling points past it. A value use keeps the header by
    construction, so the value shape's address is always laid out (its generic
    fallback fails loudly if the two ever disagree).
  - Measured 2026-09-14 on `.todo/artefacts/814-no-gc-printed-literal-fold/`
    (`hello.lisp`: one statement princ plus `terpri`; `report.lisp`: nine
    statement princ sites over seven spellings): `--optimize=size` hello 217 ->
    206 (code 61 -> 54, data 50 -> 46), report 729 -> 642 (code 468 -> 411, data
    141 -> 112); `--optimize=off` hello 628 -> 608, report 1003 -> 851.
    Interpreter and `wasmtime` agree on both at both levels. The win is the code
    section, not the data header -- the opposite of 810, where the header IS the
    point -- and the two compose. The value shape measured separately on a
    3-statement + 4-value probe: statement-only 977 -> 960 off (651 -> 641 size),
    both shapes 960 -> 920 off (641 -> 609 size), so ~10/~8 bytes per value site;
    kept, since it always wins (no wrapper tradeoff) and stays sound through the
    same count comparison.
- `(concatenate 'string ...)` bump-allocates via `__alloc` (mut-i32 heap-pointer global 0)
  and copies via `__memcpy`. Only the STRING result family exists, so any other designator —
  or a computed one — is a compile error naming it
  (`.kb/concatenate-result-families.md`). Other primitives: `length`, `subseq` (no bounds
  check), `string=` (`__streq`), `char`, `princ-to-string` (`__itoa` / `__ftoa`).
- **A character IS its i64 code point**: `char-code`/`code-char` are identities, `char=` is
  numeric `=`, and `char` decodes the i-th CODE POINT (no longer the i-th byte), so
  `(char= (char s i) #\x)` matches the other backends on ASCII and beyond.
- **`length`, `char` and `subseq` count and index CHARACTERS, like the other backends**
  (fixed 2026-09-14; `.todo/813` measured the options and chose this one). They used to
  work the byte array directly -- for `"日本語"` (3 characters, 9 UTF-8 bytes) `--no-gc`
  answered `(length s)` 9, `(char-code (char s 0))` 230 (日's first byte) and
  `(length (subseq s 1))` 8 where the interpreter, the JVM and the wasm-GC backend answer
  3, 26085 and 2. The header stays the BYTE count (allocation, copies, printing and the
  host ABI all move bytes); only these three derive characters from it, through three
  helpers: `__strlen_cp` counts UTF-8 lead bytes, `__byte_offset` converts a character
  index to a byte offset (landing on a character boundary), `__char_at` decodes the
  sequence there by the 1- to 4-byte lead-byte ladder (`__char_at` is built on
  `__byte_offset`). `string=` stays a byte-wise compare (UTF-8 preserves code-point
  order) and `concatenate` a byte copy.
- **Each helper is gated on the operator that calls it** (`MemLayout.strlenUsed` /
  `byteOffsetUsed` / `charAtUsed`, settled in `planMemory` the way `printUsed` /
  `ftoaUsed` gate their helpers; a `length` over a packed float vector still reads the
  element-count header inline and gates nothing). All three reuse the already-interned
  `__alloc` / `__streq` shapes, so they cost code bytes but no type entries. Measured
  2026-09-14 against `2bb74f937` (`--no-gc --no-wasi`, both `--optimize` levels agree):
  a module that never indexes a string is byte-identical (the 805 `bench.lisp` reactor
  844 B, the 810 folded-literal reactor 847 B, a `concatenate`-only module 410 B -- all
  unchanged); a module that does pays per helper family -- `length`-only +69 B,
  `subseq` (+`length`) +201 B at `--optimize=size` (+194 at `--optimize=off`), `char`-only
  +272/+274. The per-site call is a `call` where the old lowering was a load, so sites
  stay the same size or shrink; the helpers carry the loop.
- **Indexing here is O(n), the one exception to `.kb/string-index-cost.md`.** `length`
  scans the bytes, `char`/`subseq` walk to the index -- there is no cursor on the
  `[len][bytes]` block and no breakpoint table beside it, so a left-to-right scan is
  still linear but a random index costs its distance from the string start. The
  backend's ASCII reactors (literals and `:string` parameters crossing the boundary,
  routing/parsing over ASCII text) never notice; a hot loop over a long wide string
  belongs on a backend with the cursor.
- The four helpers occupy function indices `internalCount+0..+3` (alloc, memcpy, streq,
  itoa), followed by whichever of the three gated UTF-8 helpers the module's operators
  call for (above), where `internalCount` counts the internal functions and the EMITTED wrappers --
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
- **A `:string` export parameter becomes the internal string in place** (landed
  2026-09-14; `.todo/815`). The host's side is unchanged -- call `__ronto_alloc(n)`,
  write `n` bytes at the pointer it returns, call the export with `(ptr, n)` -- but
  `__ronto_alloc` is no longer the bare bump allocator: it is `__alloc(n + 4) + 4`,
  a one-call wrapper holding four bytes back ahead of the returned pointer, and the
  export wrapper only stores the length at `ptr - 4` and hands `ptr - 4` to the
  internal function. No second allocation, no `__memcpy`, no scratch locals, and
  (nothing bumps) no heap-mark bracket on a scalar-return wrapper that otherwise
  allocates nothing. Under `--component` the canonical lowering goes through
  `cabi_realloc`, which reserves the same four bytes, so the same wrapper body
  serves both.
  - **Boundary contract: the pointer MUST come from `__ronto_alloc`** (or the
    canonical lowering). Any other pointer -- a literal's address, an interior
    pointer into a larger buffer, a `:string` result handed back -- has no header
    room in front of it, and the header store overwrites whatever four bytes sit
    there. The old copy accepted any pointer; the contract is what buys the bytes
    back, and it is stated in host-facing terms in
    `doc/*/guides/wasm-nogc.md` ("Reclaiming memory").
  - **`--reentrant` has no mirror problem here because the combination is refused**:
    the CLI rejects `--reentrant` with `--no-gc` (no suspending import to overlap
    on), and without overlap the host's block is live for the whole synchronous
    call either way -- the header write lands before the internal call reads it.
  - Measured 2026-09-14 on `size-report/programs/dom_reactor/dom_reactor.lisp` at
    `--no-gc --no-wasi --optimize=size` plus one `:string`-taking export (the
    `.todo/815` probe, before `847` / after `1,153`): the change takes the probe to
    **1,053** (`-100`: code `391 -> 297`, the wrapper's copy loop plus
    `__memcpy` shaken out; type `46 -> 40`; export `141` and global `7`
    unchanged -- the arena API surface itself is the one-time cost that stays).
    A second and third `:string` parameter still add almost nothing.
- **`emitRangeChecks`'s guard shape follows the width, not one fixed pattern.** `:s8`,
  `:s16`, `:s32` and `:u32` use the canon-compare shape `WasmExportCompiler.emitNarrowIntResult`
  (wasm-GC) already uses: narrowing to the declared width and widening back is the identity
  exactly when the value is in range, so `v != canon(v)` traps with ONE compare and no bound
  constant (`I64_EXTEND8_S` / `I64_EXTEND16_S` / `I32_WRAP_I64;I64_EXTEND_S_I32` /
  `I32_WRAP_I64;I64_EXTEND_U_I32`). `:u8` and `:u16` keep the single bound compare
  (`I64_GT_U` against the max) instead: their bound is a two- or three-byte constant, cheaper
  than the mask the canon form would need. `:u64` keeps the plain `I64_LT_S 0` sign check --
  only the sign can be wrong. Measured 2026-09-14 against `c972efa5d` (`.todo/811`): canon
  form is smaller for `:s8`/`:s16`/`:s32`/`:u32` (-10/-12/-15/-2 bytes) and larger for
  `:u8`/`:u16` (+3/+3), so those two are excluded. `emitTrapIf`'s two-bound-compare shape
  from before this measurement is gone for every type it used to serve except `:u64`'s
  single-sided check.
- **Wrapper auto-reset for scalar returns**: `__ronto_alloc` never frees. When the return
  type is a **non-memory scalar** (NOT `:string`/`:s-expr`), `Mem.used()` **and**
  `Mem.allocates()`, `compileWrapperBody` snapshots heap global 0 at entry (before arg
  boxing) and restores it just before `END`. Reclaims only wrapper-internal scratch — the
  host's own pre-call input buffer sits below the mark and stays live.
- **`Mem.allocates()`** is the module-level answer to "can anything bump the heap during a
  call": a `:string` import RESULT (copied into a fresh block), a
  string-producing op (`concatenate`/`subseq`/`princ-to-string`), a packed vector, or
  printing (`__itoa`/`__ftoa` allocate the text they return). A `:string` export
  PARAMETER is not on the list (its block arrives pre-allocated, in place), and a module
  whose only use of memory is reading its own literals — the host-facing reactor that passes text OUT —
  answers false, and then no wrapper carries the bracket and a scalar identity export can
  be a pass-through even though the module has memory.
- **Host arena API**: the exported `__ronto_alloc` (the `__alloc(n + 4) + 4` host
  allocator above, NOT the internal `__alloc`) plus `__ronto_alloc_mark () -> i32` /
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
same macros the other backends do. An expansion shared with this backend must be
DETERMINISTIC -- fixed temporary names, never an `MV_COUNTER` gensym: the fixpoint
re-expands every reached call on every pass, so a fresh name per expansion registers a new
local per pass and `changed` never settles (an infinite compile, first met by a
population-count `logcount` loop in 2026-09; the established shape is a fixed
`__name-` prefix, the `CHECK_TYPE_VAR` precedent). Reuses `WasmExportCompiler.parse`/`isExportForm`/
`paramWasmTypes`/`resultWasmTypes` + the `T_*` constants; composes with `--optimize`
(`WasmTreeShaker` is GC-agnostic, and `WasmPeephole` + `WasmInliner` + `WasmLocalSink` run in front of it here
exactly as they do on the GC backend -- this is the backend the move pays on, the browser reactor
of `.todo/804` going 1,090 -> 1,011 B and its code section 360 -> 300 in 17 -> 8 functions:
`.kb/optimize-dead-code-elimination.md`, "The single-call-site move"; the peepholes take the
`hello` Worker 507 -> 489 and `pi_approx --no-gc` 3,333 -> 3,289). The type-test fold and the
forwarder redirect are GC-side only; the move subsumes the second here, since a forwarder called
once is a body with one call site.

## Print / stdout
`print`/`princ`/`terpri` (no stream argument) work inside exported functions, byte-identical
to the interpreter (`.kb/core-representation.md`) -- including a COMPUTED boolean, which
writes `T`/`NIL` by name (`.todo/817`). `emitWriteStringEscaped` writes an escaped
string as RUNS, so nothing is allocated — a print must not move the bump heap.
- Gated by `Mem.printUsed` (a `usesPrintOp` scan): adds the ONE `(import
  "wasi_snapshot_preview1" "fd_write")` at type index 0, function index 0 — every other
  function index shifts by `Mem.funcBase()` = 1, and **ALL index math flows through the
  `Mem.funcIndex()`/`*Index()` accessors, nothing hardcodes the shift** — plus a
  `__write_stdout(ptr,len)` funnel, **the sole caller of the fd_write import**.
- The five print-pool fragments arrive one by one on first use (`PrintUse`, settled
  against the frozen inference result): `terpri` or any `print` gates `"\n"`; a `print`
  of a string gates `"\""` and `"\\"`; a boolean print gates `"T"`/`"NIL"` (a computed
  one both, a lone literal its own). A fragment written only as a region carries no
  `[len]` header -- `"\n"` in every literal-only printing module is that literal --
  while `__ftoa`'s specials and a `princ-to-string` boolean's `T`/`NIL` stay pinned
  headered (`.todo/816` parts 1-2; measured `hello_world-nogc` 211 -> 166 B at
  `--optimize=size`).
- `Mem.ftoaUsed` (a typed `rendersFloat` scan) additionally emits `__ftoa`, its five
  `__schub_*` helpers and the ~755-byte `SchubfachTables` blob after the literals
  (`Mem.schubBase`) — the Schubfach decimal shared with the GC backend
  (`WasmSchubfachRuntimeBuilder`), so float text is byte-identical at EVERY magnitude
  (`.kb/format.md`). NaN/Infinity/-Infinity are static literal headers, `-0.0` by sign bit.
- The 16-byte fd_write iov scratch (`Mem.iovAddr`) sits between the static data and
  `heapBase`; `print`/`princ` of an INT/FLOAT brackets the transient string in a mark/reset.
  Nothing else printing allocates, so `Mem.allocates()` is "prints an INT/FLOAT", not "prints":
  a literal-only module's wrappers carry no heap bracket, and with it gone nothing touches
  the heap pointer and the global section drops out under `--optimize=size` (`.todo/816`
  part 3). A stream argument and printing a packed array are compile errors.

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
  `stringLiteralsArePackedWithoutAlignmentPadding`,
  `exportedRontoAllocReservesTheStringHeader`,
  `stringParamWrapperWritesTheHeaderInPlace`,
  `aStringParamAloneCarriesNoHeapReset`,
`aLiteralOnlyFoldedImportSitesReadCarriesNoLengthHeader`,
`aLiteralAlsoReadAsAValueKeepsItsLengthHeader`,
`aStatementPrincOfALiteralWritesTwoConstants`,
`aValuePositionPrincOfALiteralWritesTwoConstantsAndLeavesTheValue`,
`aPrincAsTheLastFormOfAVoidExportWritesTwoConstantsAndLeavesTheValue`,
`printOfALiteralKeepsTheGenericPath`,
`aLiteralPrintedAndReadAsAValueKeepsItsLengthHeader`,
`aLiteralPrintedAndPassedToAFoldedImportCarriesNoLengthHeader`,
`aRuntimeFragmentSharedWithAFoldedSiteDropsItsHeaderLikeAProgramLiteral`,
`princToStringOfABooleanKeepsBothHeaders`,
`aComputedBooleanPrintsTAndNilByName`,
`aPrincOnlyModuleOmitsTheQuoteAndBooleanFragments`,
`aPrintOfAStringNeedsQuotesButNoBooleanFragments`,
`aLiteralOnlyPrintingModuleCarriesNoHeapBracketAndNoHeapGlobal`; the content
each shape hands the host is
`NoGcWasmImportE2eTest.aFoldedLiteralReachesTheHostIntactWhetherOrNotItKeepsItsHeader`,
and a spelling both printed and imported is
`NoGcWasmImportE2eTest.aPrintedLiteralReachesBothTheHostAndStdoutIntact`), the VOID group
(`aVoidImportCallLeavesNothingForItsCallerToDrop`, `aVoidBodyMakesAVoidExportAPassThrough`,
`theDeadNilOfACondTArmDoesNotDragAVoidChainBackToAnInteger`,
`aWhileLoopPushesNothingForTheFormAfterItToDrop`), the heap-reset trio (plus the
printing pair above),
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
`WasmLispCompilerIntegrationTest` (string primitives, print vs the interpreter,
`noGcPrintedLiteralsFoldAtBothLevels`, `noGcPrintedBooleansMatchTheInterpreter`, flat-heap
loops under a 2-page cap, WAVE invoke with no flags, the canonical string ABI, `--optimize`
composition, the print micro-adapter and its chunk cap). The `:string`-parameter side needs a
memory-writing host: `NoGcWasmExportStringParamE2eTest` (empty string, a UTF-8 string, the
same buffer twice, an allocation between two calls, stdout diffed against the interpreter,
flat heap over a pull loop, at both optimize levels); `examples/console/mandelbrot-nogc.lisp`
exercises the `:string`-result side by hand.

## Unfinished
`:s-expr` (cons/reader/printer runtime) is deferred (`.todo/023`); the `_start`
command-module stretch is `.todo/111`.
