# WASM GC backend: strings as wasm-GC byte arrays

Scope: the **GC WASM backend only** (`codegen.wasm`). Invariant: a string's BYTES live on the
wasm-GC heap (reclaimable), not in a linear-memory bump heap that only grew
([[27-wasm-gc-heap-never-grows]]).

## Representation

```
TYPE_STRING (rec-group type 4) = struct { i32 id, i32 len, (ref null eq) data,
                                          (mut i32) ci, (mut i32) cb }
$str_bytes  (fixed type 36)    = (array (mut i8))          -- subtype of eq
```

- **id**: canonical identity compared with `i32.eq` -- the stable intern offset for a name, a
  monotonic counter for a runtime string. **len**: BYTE length, > the character count on non-ASCII.
- **data** holds the SAME quote-framed bytes linear memory held (`"foo"` is 5 bytes, a symbol bare,
  a keyword leading `:`), so `linear[id + i]` == `array[i]`. Discriminator
  `array.get_u data 0 == 0x22` (string) / `0x3A` (keyword); readers `ref.cast $str_bytes` first
  (`WasmEmitHelper.emitStrBytesArray`). Content is UTF-8 (`_charvec_to_str` normalizes).
- **ci / cb**: the character-index cursor ("character `ci` starts at byte `cb`", seeded `(0, 1)`)
  that keeps scans linear rather than quadratic ([[string-index-cost]]). Every `struct.new` for
  TYPE_STRING lives in `_str_build` / `_str_fresh`.

## Character accessors
`FUNC_STR_CHAR_COUNT` (every `(length s)`), `FUNC_STR_CHAR_AT` (`(aref TYPE_STRING i)` directly;
`(char s i)`/`(schar s i)` go through `_str_char_ref`, which reads a mutable character vector's
ELEMENT via `_charvec_p` -> `_arr_get` without rendering it), `FUNC_STR_CHAR_BYTE_OFFSET`
(answers `len - 1`, the closing quote, at/past the count so subseq's end walk lands on the
terminator), `FUNC_TO_MUT_STR` (the flipped producers' mutable-result wrap,
`.kb/string-write-runtime.md`, emitted only when `Ctx.mutableStringProducers` says so). Neither
offset helper walks from byte 0.

**Per-character case fold is table-driven, not ASCII shifting**: `_char_upcase`/`_char_downcase`
(`WasmCaseFoldRuntimeBuilder`, `TYPE_LOOKUP = (i32) -> i32`) binary-search a compressed
`(from:u32, to:u32, delta:i32)` table in static data (~16 KB at Unicode 15: 690 upper, 674 lower
ranges) generated from `Character.toUpperCase(int)`, so WASM matches the other backends on every
Unicode letter ([[characters-code-points]]). Byte-level ASCII fold and equality stay correct
under UTF-8.

## HEAP_PTR is a stack pointer; identity is a counter
Two disciplines share the linear scratch at `HEAP_PTR_ADDR`:
- **Transient (save on entry, pop on exit)** -- a runtime build assembles bytes at
  `start = HEAP_PTR`, calls `_str_fresh(start, len)` and does NOT advance. The COUNTER
  (`STRING_ID_CTR_ADDR` = 156, seeded at `heapBase` so ids never collide with an intern offset) is
  what keeps runtime strings and uninterned symbols `eq`-distinct.
- **Permanent (advance, never pop)** -- the interned-symbol pool: `_intern`
  (`WasmReadRuntimeBuilder.buildInternBody`) COPIES a first-seen token to stable storage.

## The fixed helpers (`WasmStringRuntimeBuilder`)
- `_str_build(off,len)` (`FUNC_STR_BUILD`) -- id = off, for INTERNED names; two occurrences share
  the offset, so `eq`. **The boolean `t` does not build per site**: `emitTrue` calls `_t_sym`
  (`FUNC_T_SYM`), which lazily builds "T" ONCE into the last module global; quoted `'t` still goes
  through `compileStringLiteral`, id-equal.
- `_str_fresh(off,len)` (`FUNC_STR_FRESH`) -- id = counter++, for RUNTIME strings. Two are built
  WITHOUT it: `_str_stream_contents` and `_iv_utf8_str` (`FUNC_IV_UTF8_STR`), each one GC-to-GC
  `array.copy` stamping the counter id itself.
- `_str_to_mem(str,ptr)->len` (`FUNC_STR_TO_MEM`) -- the array->linear bridge for `open`/`load`,
  the reader input scratch (RESERVED so parse-time interns stack above the unparsed input),
  `intern`, the host `:string` boundary (`WasmExportCompiler.emitStringResult`; a staged import
  PARAMETER goes through `WasmImportCompiler.emitStagedMemoryParam`, which ADVANCES `HEAP_PTR`
  past its region so several coexist -- [[wasm-import]]), the fetch wire,
  `tcp` host, and a string INPUT stream's source copy.
- `_lit_stage(src,len,delta)->ptr` -- the ONE staging that touches neither helper: a host
  import argument that is a LITERAL is `memory.copy`d from the data segment into a block
  reserved above the static data, at the call site, so the bytes never become a GC array
  to be unbuilt one instruction later ([[wasm-import]], "A literal `:string` argument does
  not round-trip"). Conditional, sized by the widest site, and absent from a module that
  declares no `:string` import.
- `_write_str_gc(str,from,to,esc)` (`FUNC_WRITE_STR_GC`) -- appends straight from the GC array to
  `CAPTURE_CUR` in capture mode (so it can never alias the capture buffer) or stages into scratch +
  `_write_str`. `princ` passes `(1,len-1,0)`, a symbol `(start,len,0)`, **`prin1` `(1,len-1,1)`** --
  with `esc = 1` the CALLEE writes the frame quotes, which is what keeps them from being escaped as
  content. Scratch grows to `n*2+2`. The escape set is the reader's minus `\n`/`\t`. All-backend
  table: [core-representation.md](core-representation.md).

## String streams
OUTPUT: record `[kind=1][slot][len]` (12 bytes, the only linear part) naming a per-stream
`$str_bytes` through a `TYPE_HASH_BUCKETS` module global, appended with `array.copy` and DOUBLED
when full (`_ostream_room`); `_close` returns the slot to a free list threaded through the table's
own entries. INPUT: still one linear copy of the source at open (`[kind=0][cursor][end]`), per
STREAM not per read, which is what keeps `_read_line`'s linear scan working ([[read-load-streams]]).

## The host arena API pops to the intern high-water
`__ronto_alloc_mark` / `__ronto_alloc_reset` reclaim a resident host's input buffer, but the reset
is `HEAP_PTR = max(mark, RT_INTERN_HEAP_ADDR)` (cell 172), NOT a bare `HEAP_PTR = mark`: popping
below it dangles every symbol interned during the call, so a call that interns a NEW symbol keeps
the host's buffer. A string INPUT stream's copy is permanent and NOT guarded -- the same trade
`cabi_post_*` makes, and the reason the output free list lives in the TABLE ([[wasm-export-no-wasi]]).

## The normalization is GATED on a charvec being possible at all
`_charvec_to_str` is inserted after the string operand of every string consumer and at the
entry of `_equal` / `_hash` / `_print_val` / `_princ_val` / `_str_to_mem` / `_string_concat` /
`_write_line` / `_write_stream_str` / the `equalp` key fold. One `:string` boundary is enough to
root the whole group, and the group is **1,961 bytes** in a module that never makes a character
vector. `Ctx.charvecPossible` decides whether ANY of those sites is emitted -- all of them or
none, since one call roots the same six functions (`_charvec_to_str` 517, `_charvec_p` 281, the
vector walk 398, the UTF-8 encode 533, `_str_from_mem` 87, and a data segment only they
addressed).

**The gate is an ALLOWLIST over the program's operators, and the obvious tighter answer is
WRONG.** Deriving it from the three CONSTRUCTORS -- a character-element-type `make-array`
(`WasmArrayCompiler.compileMake`'s marker), the `subseq` string lane (`_subseq_str` ->
`_str_to_cv`) and the flipped producers' wrap (`_to_mut_str`) -- reads as the precise question
and under-approximates, because those constructors are introduced by **Pass 2 lowerings the
source never spells**. Measured 2026-09-12, both with a constructor-name gate that saw nothing:
`(write-string "hello" t :start 1 :end 3)` and `(format t "~:d" 1000000)` each reach
`_subseq_str` through the injected `%subseq-runtime` and trapped with `wasm trap: cast failure`
at the un-normalized consumer. A missing normalization is a SILENT wrong answer at the host
boundary, so the gate closes only for a program whose every operator is on
`WasmLispCompiler.CHARVEC_FREE_OPERATORS` -- control flow, arithmetic, the type predicates, the
cons cell, the two `wasm-` directives -- plus its own defuns, MINUS any defun name that is a
`cl` function the backend intercepts as an operator instead of dispatching to
(`ClRedefinitionWarnings.redefinesClFunction`) -- a call there compiles to the standard
operator, so trusting the user's body would read code that never runs. An operator that list
has never heard of OPENS it. `-Drontolisp.debug.charvecgate=true` names the operator holding
it open.

The allowlist stays alongside the type-test fold (`.kb/wasm-ref-type-fold.md`): the normalization
is a CALL whose callee tests a marker, not a `ref.test` on a type of its own, so the fold cannot
retire it -- what the fold does retire is the printer arms for the types a program never builds.

Three things make the allowlist safe rather than lucky:
- **The only strings such a program holds are LITERALS, and a literal is never a character
  vector** (`.kb/string-write-runtime.md`). That is the proof, not the list's length.
- **Each constructor asserts the flag** (`WasmEmitHelper.requireCharvecPossible`), so an entry
  that turns out to lower to one fails the BUILD instead of shipping the wrong module.
- **An injected runtime body is compiled with the flag forced ON** -- the wrapper catalog and the
  shared sequence helpers, which a gate-closed program cannot reach (it spells no catalog name,
  and `anyNameResolvable` already opens the gate) -- so the tree shaker drops them whole. The one
  shape that would break that, the program CALLING such a helper, is a compile-time throw too
  (`requireNoCharvecHelper`). An allowlist entry must therefore neither construct a character
  vector nor lower to a helper that does.

Measured on the `.todo/789` reactor (two host imports, one taking two `:string`s, `fib`, two
exports; `--no-wasi --optimize=size`): **4,623 -> 2,725 bytes (-41%)**, code 4,019 -> 2,190 over
40 -> 34 functions, data 117 -> 94 -- the dead `"TRIVIAL-GARBAGE"` package-designator segment
was reachable ONLY from the normalization group, so it goes with it and needs no data-section
shake of its own. `examples/browser/webgl-triangle` (10 imports, GLSL crossing as `:string`
literals): **4,431 -> 2,487 (-44%)**. A program that does anything else with a string is
byte-identical: `hello_world` 588, `pi_approx` 4,826 (its `format` opens the gate),
`webgl-cube` 26,686, `webgl-galaxy` 20,281. Pins:
`WasmImportCompilerTest.theCharvecNormalizationIsAbsentFromAModuleThatCannotMakeOne` and
`.anOperatorThatLowersToAConstructorKeepsTheNormalization` (the pair, not an absolute size), and
the CONTENT against a Node host in `WasmStringParamBoundaryE2eTest`, whose fill-pointered
character-vector argument is exactly the case a wrong gate would corrupt.

## Other constraints
- `emitGrowHeapTo` guards at string builders are KEPT: the scratch grows ON DEMAND, so peak linear
  memory is bounded by the largest single live string, not the sum of all builds.
- No `DataCount`/`array.new_data`/segment reorder (the builders copy from linear). Adding
  `TYPE_STR_TO_MEM`/`TYPE_WRITE_STR_GC` shifts the wrapper TYPE base and `FUNC_USER_BASE`, but the
  component binds by export name ([[wasi-component]]).
- `--simd` puts NOTHING in linear memory: packed float arrays become `TYPE_VBLOCK` over
  `(array (mut v128))`, still GC. `VEC_HEAP_PTR_ADDR` (160) was removed; without `--simd` the
  module is byte-identical (`.kb/vec.md`).
- **Component memory grows with the program**: `WasmComponentBuilder.memModuleFor` reads the core
  module's `"mem"/"memory"` import (`min` = `max(4, (heapBase + 65535) / 65536 + 3)`) and rewrites
  the shared `mem.wasm`'s memory section -- active data segments are written BEFORE any function
  runs, so a program whose static data exceeds the default six pages would otherwise trap. Only the
  `(memory (;0;) N)` count changes.
- Leak pin: ~16KB strings grown in a loop have FLAT peak RSS vs N (50000 -> ~94MB,
  200000 -> ~91MB).

## Related
[[string-index-cost]], [[27-wasm-gc-heap-never-grows]], [[read-load-streams]],
[[symbol-runtime-api]], [[no-gc-scalar-wasm]].
