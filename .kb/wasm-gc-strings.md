# WASM GC backend: strings as wasm-GC byte arrays

Scope: the **GC WASM backend only** (`codegen.wasm`). `--no-gc` (`NoGcWasmCompiler`), the
interpreter and the JVM backend are unaffected.

Invariant: a string's BYTES live on the wasm-GC heap (reclaimable), not in a linear-memory
bump heap that only grew ([[27-wasm-gc-heap-never-grows]]).

## Representation

```
TYPE_STRING (rec-group type 4) = struct { i32 id, i32 len, (ref null eq) data,
                                          (mut i32) ci, (mut i32) cb }
$str_bytes  (fixed type 36)    = (array (mut i8))          -- subtype of eq
```

- **id** -- canonical i32 identity, compared with `i32.eq` for
  `eq`/`eql`/symbol-identity/`_env_lookup`/`_lookup`/special-form dispatch. Interned name:
  the stable static/intern offset. Runtime string: a fresh monotonic counter.
- **len** -- stored BYTE length (`struct.get 1`); can exceed the character count on non-ASCII.
- **data** -- `$str_bytes` array with the SAME quote-framed bytes linear memory held: `"foo"`
  is 5 bytes (leading/trailing `0x22`), a symbol is bare, a keyword leads with `:`. So old
  `linear[id + i]` == new `array[i]`; discriminator is `array.get_u data 0 == 0x22` (string) /
  `0x3A` (keyword). Readers `ref.cast $str_bytes` before `array.get_u` / `array.len`
  (`WasmEmitHelper.emitStrBytesArray`). **Content is UTF-8** (literals via
  `String.getBytes(UTF_8)`; a mutable character vector normalizes through `_charvec_to_str`,
  emitting each `TYPE_CHAR` code point as 1-4 UTF-8 bytes).
- **ci / cb** -- the character-index CURSOR ("character `ci` starts at byte `cb`", seeded
  `(0, 1)` by both builders), which makes an index into a single-byte string one compare and
  any scan linear rather than quadratic. Mechanism, soundness and the store rule:
  [[string-index-cost]]. These two fields are the only reason TYPE_STRING is five wide; every
  `struct.new` for it lives in `_str_build` / `_str_fresh`.

## Character accessors (appended after `FUNC_CHARVEC_TO_STR` / `FUNC_CHARVEC_P`, `.kb/adjustable-arrays.md`)
- `FUNC_STR_CHAR_COUNT` `_str_char_count(str) -> i32` -- character count; every `(length s)`
  on a string reads through it.
- `FUNC_STR_CHAR_AT` `_str_char_at(str, i) -> i32` -- i-th code point; delegates the walk to
  `_str_char_byte_offset` and decodes the 1-4 byte sequence. `(aref TYPE_STRING i)` calls it
  directly; `(char s i)` / `(schar s i)` call `_str_char_ref` (`FUNC_STR_CHAR_REF`), which
  reads a mutable character vector's ELEMENT via `_charvec_p` -> `_arr_get` (never rendering
  it) and reaches `_str_char_at` only for the immutable representation. The caller boxes the
  i32 as `TYPE_CHAR`.
- `FUNC_STR_CHAR_BYTE_OFFSET` `_str_char_byte_offset(str, i) -> i32` -- byte offset where the
  i-th character's sequence starts, or `len - 1` (the closing quote) when i is at/past the
  count, so subseq's end walk lands on the terminator. `_subseq`'s string branch reads it twice.
- `FUNC_TO_MUT_STR` `_to_mut_str(v)` -- the flipped string PRODUCERS' mutable-result wrap
  (`.kb/string-write-runtime.md`): a QUOTE-FRAMED `TYPE_STRING` (byte 0 is `0x22` -- a symbol
  shares the type with bare bytes and must pass through) converts once via `_str_to_cv` into a
  fresh mutable character vector; anything else passes through. Emitted at a producer site
  only when `Ctx.mutableStringProducers` says the program contains one.

Neither offset helper walks from byte 0 (see the cursor above).

ASCII case-fold and byte-level equality on the raw bytes stay correct: an ASCII byte-equal
comparison matches character-for-character under UTF-8, and non-ASCII bytes fall outside the
A-Z / a-z range `_string_upcase` / `_string_downcase` / `_string_capitalize` shift.
`emitPrintChar`'s `emitGlyph` expands a code point to 1-4 UTF-8 bytes before `_write_str`.

**Per-character case fold is table-driven, not ASCII shifting.** `_char_upcase` /
`_char_downcase` (`WasmCaseFoldRuntimeBuilder`, `FUNC_CHAR_UPCASE` / `FUNC_CHAR_DOWNCASE`,
both on the shared `TYPE_LOOKUP = (i32) -> i32`) binary-search a compressed sorted
`(from:u32, to:u32, delta:i32)` range table in the static data segment (~16 KB combined at
Unicode 15: 690 upper, 674 lower ranges), generated at compile time from
`Character.toUpperCase(int)` / `toLowerCase(int)` so WASM matches the interpreter and JVM
byte-for-byte on every Unicode letter (incl. supplementary, e.g. Deseret).
`WasmCharCompiler.compileCaseFold` emits a single `call` + re-box as `TYPE_CHAR`. See
[[characters-code-points]].

## HEAP_PTR is a stack pointer; identity is a counter
`HEAP_PTR_ADDR` linear memory is now essentially only a reused byte-assembly scratch. Two
disciplines share it:
- **Transient (save on entry, pop on exit)** -- every runtime string build: assemble bytes at
  `start = HEAP_PTR`, call `_str_fresh(start, len)` (copies into a fresh `$str_bytes` +
  stamps `id = STRING_ID_CTR++`), do NOT advance HEAP_PTR. Since the scratch offset is reused,
  the COUNTER keeps distinct runtime strings and uninterned symbols (`gensym`/`make-symbol`)
  `eq`-distinct. `STRING_ID_CTR_ADDR` (cell 156) is seeded at `heapBase`, so runtime ids are
  always `>= heapBase >` every interned/rt-intern offset and never repeat.
- **Permanent (advance, never pop)** -- the interned-symbol byte pool. `_intern`
  (`WasmReadRuntimeBuilder.buildInternBody`) COPIES a first-seen token into stable heap
  storage at `HEAP_PTR` and advances it, so the intern record + canonical offset do not depend
  on the caller's transient bytes. HEAP_PTR's low-water = the interned high-water.

## The fixed helpers (always emitted, indices right after `FUNC_STR_BUILD`; `WasmStringRuntimeBuilder`)
- `FUNC_STR_BUILD` `_str_build(off,len)` -- id = off. INTERNED names: literals
  (`compileStringLiteral`), `t`/`nil`/`quote`/`function`/`lambda`/keywords, reader symbols
  (via `_intern`), `intern`. Two occurrences share a dedup/intern offset -> `eq`. **The
  boolean `t` does not build per site**: `emitTrue` calls `_t_sym` (`FUNC_T_SYM`), which lazily
  builds the "T" literal ONCE into a dedicated module global (always the last global) and
  returns the cached instance -- same id, same bytes, but a comparison returning true allocates
  nothing. Quoted `'t` sites still build through `compileStringLiteral` (id-equal, so `eq`
  holds across both paths).
- `FUNC_STR_FRESH` `_str_fresh(off,len)` -- id = counter++. RUNTIME strings:
  concatenate/subseq/case/trim, read string literals, read-line, the capture path
  (`princ-to-string`/`prin1-to-string`/`concatenate`), `gensym`/`make-symbol`, `uiop:getenv`,
  fetch response, the host `:string` boundary. Two runtime strings are built WITHOUT it:
  `_str_stream_contents` and `_iv_utf8_str` (`FUNC_IV_UTF8_STR`, the strict-UTF-8 decode of a
  packed octet vector) -- each does one `array.copy` GC-to-GC with no linear detour and stamps
  the counter id itself (`.kb/async-await.md`).
- `FUNC_STR_TO_MEM` `_str_to_mem(str,ptr)->len` -- copies a string's array (quotes included)
  into `linear[ptr..)`; the array->linear bridge for `open`/`load`, the reader input scratch
  (`read-from-string`/`read`, which RESERVE the scratch so parse-time interns/builds stack
  above the unparsed input), `intern`, the host `:string` boundary
  (`WasmExportCompiler.emitStringResult`, reused by imports), the fetch wire, `tcp` host, and
  a string INPUT stream's source copy.
- `FUNC_WRITE_STR_GC` `_write_str_gc(str,from,to,esc)` -- print path for a string value:
  appends `[from,to)` straight from the GC array to `CAPTURE_CUR` in capture mode (no linear
  staging, so it can never alias the capture buffer while printing inside a `*-to-string`
  capture) or stages into HEAP_PTR scratch + `_write_str` for stdout. `princ` passes
  `(1,len-1,0)`; a symbol (no frame) passes `(start,len,0)`. **`prin1` passes `(1,len-1,1)`**:
  with `esc = 1` the CALLEE writes the frame quotes itself around the content and precedes
  every embedded `"` / `\` with a `\` -- framing inside the callee is what keeps the two frame
  quotes from being escaped as content. Scratch is grown to the worst case `n*2+2`; the actual
  output count is what `CAPTURE_CUR` / `_write_str` sees. The string branch of `_print_val`
  makes the same leading-`"` string-vs-symbol test `_princ_val` does. The reader's un-escaping
  in `WasmReadRuntimeBuilder` is the mirror; the escape set is the reader's minus `\n`/`\t`
  (CL prints a newline literally). All-backend table: [core-representation.md](core-representation.md).

## String streams: OUTPUT holds GC bytes, INPUT a linear copy
An OUTPUT stream keeps no linear content: its record `[kind=1][slot][len]` names a per-stream
`$str_bytes` GC buffer through a module-global table (a `TYPE_HASH_BUCKETS` indexed by `slot`),
appends with `array.copy` and DOUBLES it when full (`_ostream_room`, `FUNC_OSTREAM_ROOM`).
Linear memory holds only the 12-byte record. `_close` hands the slot back to a free list
threaded through the table's own entries, so the live-stream count bounds the table.

An INPUT stream still copies its source string into a persistent linear buffer (`_str_to_mem`,
bump-advanced) once at open; `[kind=0][cursor][end]` walks that copy, and the string-stream
read-line finalizes via `_str_fresh`. That copy is per STREAM, not per read, which is what
makes `_read_line`'s linear scan work unchanged. Details: [[read-load-streams]].

## The host arena API pops to the intern high-water
A memory-exporting module exports `__ronto_alloc_mark`/`__ronto_alloc_reset` so a resident host
can reclaim the input buffer it `__ronto_alloc`'d. The reset is NOT a bare `HEAP_PTR = mark`:
it is `HEAP_PTR = max(mark, RT_INTERN_HEAP_ADDR)` (cell 172, the intern pool's high-water,
stored by `_intern` right after its permanent advance). Popping below it would dangle every
symbol interned during the call (`RT_INTERN_COUNT_ADDR` still points at those bytes). So a call
that interns a NEW symbol keeps the host's buffer and every other call pops all the way back.
A string INPUT stream's source copy is permanent too but NOT guarded -- the same trade the
component `cabi_post_*` already makes. That trade is also why an output stream's free list
lives in the TABLE (GC) with only its head in a fixed cell: a free list threaded through the
arena would hand out records the reset had already given back. Details:
[[wasm-export-no-wasi]].

## Grow guards kept
`emitGrowHeapTo` guards at string builders are KEPT: a single string can exceed the current
linear size, so the scratch grows ON DEMAND (once), not per build. Peak linear memory is
bounded by the largest single live string, not the sum of all builds.

## Component byte-safety
No `DataCount`/`array.new_data`/segment reorder: `_str_build`/`_str_fresh` copy from linear, so
no new core section. Adding `TYPE_STR_TO_MEM`/`TYPE_WRITE_STR_GC` shifts the export/import
wrapper TYPE base (`TYPE_WRITE_STR_GC + 1`) and the three helper FUNCTIONS shift
`FUNC_USER_BASE`, but the component embeds the core opaquely and binds by export name, so it is
unaffected. See [[wasi-component]].

## Verifying the leak fix
`(dotimes (i N) (grow "..." 10))` building ~16KB strings via concatenate-doubling on a wasm-GC
host (wasmtime, Node/V8; NOT Chicory/Endive -- no wasm-GC): peak RSS is FLAT vs N (N=50000 ->
~94MB, N=200000 -> ~91MB). A leak would scale linearly.

## `--simd` puts nothing in this memory
Under `--simd` (`.kb/vec.md` acceleration layer 3) packed float arrays become a `TYPE_VBLOCK`
over an `(array (mut v128))` of lane groups instead of `TYPE_F64ARR`/`TYPE_F32ARR`, but stay GC
objects and never touch linear memory (`array.get` yields a v128 with no `v128.load`).
`HEAP_PTR` is untouched and nothing in a `--simd` module grows without bound. `VEC_HEAP_PTR_ADDR`
(= 160) and its bump allocator were removed. Without `--simd` the module is byte-identical
either way.

## Component memory grows with the program
`WasmComponentBuilder.memModuleFor` reads the core module's `"mem"/"memory"` import declaration
(whose `min` pages equals the P1 own-memory declaration,
`max(4, (heapBase + 65535) / 65536 + 3)`) and rewrites the shared `mem.wasm` module's memory
section so its EXPORTED memory starts with at least that many pages. Active data segments are
copied to memory at instantiation BEFORE any function runs, so a program whose static data /
intern pool alone exceeds the mem module's default six pages (uax-15 loads a ~2.7MB
UnicodeData blob at load time via compile-time `with-open-file` file-inlining, `.kb/asdf.md`)
would otherwise trap out-of-bounds on the first byte written. The patched mem module keeps its
bump-allocator body byte-identical; only the `(memory (;0;) N)` count changes. When the core
module does not import `"mem"/"memory"`, the fallback returns the unchanged resource.

## Related
[[string-index-cost]], [[27-wasm-gc-heap-never-grows]], [[read-load-streams]],
[[symbol-runtime-api]], [[no-gc-scalar-wasm]] (unaffected; keeps its own heap reset in
[[88-no-gc-export-wrapper-heap-reset]]/[[89-no-gc-heap-mark-release]]).
