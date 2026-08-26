# WASM GC backend: strings as wasm-GC byte arrays

Detail behind the CLAUDE.md constraint. Scope: the **GC WASM backend only**
(`codegen.wasm`). `--no-gc` (`NoGcWasmCompiler`), the interpreter, and the JVM
backend are unaffected -- their strings are already reclaimable.

## What changed and why

Originally a WASM string was `TYPE_STRING = struct { i32 offset, i32 length }`: the
struct was GC-managed but the BYTES lived in **linear memory** (the static data
segment for literals/interned symbols, a bump heap at `HEAP_PTR_ADDR` for runtime
strings). That linear string bump heap only ever grew (see
[[27-wasm-gc-heap-never-grows]]), so a resident reactor building strings across many
calls grew without bound. Now the bytes live on the wasm-GC heap and the engine
reclaims them like cons cells and closures.

## Representation

```
TYPE_STRING (rec-group type 4) = struct { i32 id, i32 len, (ref null eq) data,
                                          (mut i32) ci, (mut i32) cb }
$str_bytes  (fixed type 36)    = (array (mut i8))          -- subtype of eq
```

- **field 0 = id** -- the canonical i32 identity, compared with `i32.eq` for
  `eq`/`eql`/symbol-identity/`_env_lookup`/`_lookup`/special-form dispatch. For an
  interned name it is the string's stable static/intern offset; for a runtime string
  it is a fresh monotonic counter (see below). Every identity site is byte-for-byte
  unchanged from the old `offset` field.
- **field 1 = len** -- the stored byte length (kept as a plain i32; every LEN read
  stays `struct.get 1`).
- **field 2 = data** -- the `$str_bytes` GC array holding the SAME quote-framed bytes
  linear memory held: a string `"foo"` is 5 bytes `" f o o "` (leading/trailing
  `0x22`), a symbol is bare, a keyword leads with `:`. So old `linear[id + i]` == new
  `array[i]`; the discriminator is `array.get_u data 0 == 0x22` (string) / `0x3A`
  (keyword). Readers `ref.cast $str_bytes` before `array.get_u` / `array.len`
  (`WasmEmitHelper.emitStrBytesArray`). **String content is UTF-8**: source-literal
  bytes are already UTF-8 (the `StringTable` stores `String.getBytes(UTF_8)`), and a
  mutable character vector normalizes through `_charvec_to_str` which emits each
  `TYPE_CHAR` code point as its 1-4 byte UTF-8 sequence -- so `len` (field 1) is the
  BYTE length and can exceed the character count on non-ASCII input. Character-based
  accessors walk the byte data through three shared runtime helpers appended right
  after `FUNC_CHARVEC_TO_STR` and its shape half `FUNC_CHARVEC_P`
  (`.kb/adjustable-arrays.md`):

  - `FUNC_STR_CHAR_COUNT` `_str_char_count(str) -> i32` -- character count of a
    UTF-8-encoded string. Every `(length s)` on a string reads through it.
  - `FUNC_STR_CHAR_AT` `_str_char_at(str, i) -> i32` -- the i-th character's code
    point. Delegates the walk to `_str_char_byte_offset` and decodes the 1-4 byte
    UTF-8 sequence at the returned position. Every `(char s i)` / `(schar s i)` and
    `(aref TYPE_STRING i)` lowering routes through it (the caller boxes the returned
    i32 as a `TYPE_CHAR` struct).
  - `FUNC_STR_CHAR_BYTE_OFFSET` `_str_char_byte_offset(str, i) -> i32` -- byte
    offset within `$str_bytes` where the i-th character's UTF-8 sequence starts (or
    `len - 1`, the closing quote's position, when i is at or past the character
    count so subseq's end walk lands on the string terminator). `_subseq`'s string
    branch reads it twice to translate a character range into a byte range.

  **Neither of those two walks from byte 0**: fields 3/4 are the string's own
  character-index CURSOR ("character `ci` starts at byte `cb`", seeded `(0, 1)` by
  both builders), which makes an index into a single-byte string one compare and a
  scan of any string linear instead of quadratic. The mechanism, its soundness
  argument (a string's bytes never change after it is built) and the store rule are
  [[string-index-cost]]; the two extra fields are the only reason TYPE_STRING is
  five fields wide, and every `struct.new` for it lives in `_str_build` /
  `_str_fresh`.

  ASCII case-fold and byte-level equality on the raw byte data stay correct without
  further changes: an ASCII byte-equal string comparison matches character-for-character
  under UTF-8 canonical form, and non-ASCII bytes fall outside the A-Z / a-z range that
  `_string_upcase` / `_string_downcase` / `_string_capitalize` shift, so they pass
  through unchanged. `emitPrintChar`'s `emitGlyph` now also expands its code point to
  1-4 UTF-8 bytes before handing them to `_write_str`, so `#\A` prints as
  `A` on stdout and `#\U+00C5` prints as its two-byte UTF-8 encoding.

  Per-character case fold (the `(char-upcase ch)` / `(char-downcase ch)` builtins)
  used to shift only ASCII (a-z <-> A-Z, delta +/- 32) which silently misfolded a
  Latin-1 supplement letter and every non-ASCII alphabet. The `_char_upcase` /
  `_char_downcase` runtime helpers (`WasmCaseFoldRuntimeBuilder`, function indices
  `FUNC_CHAR_UPCASE` / `FUNC_CHAR_DOWNCASE`, both bound to the shared
  `TYPE_LOOKUP = (i32) -> i32` signature) binary-search a compressed sorted
  `(from:u32, to:u32, delta:i32)` range table baked into the static data
  segment (~16 KB combined at Unicode 15: 690 upper ranges, 674 lower ranges).
  The tables are generated at compile time from `Character.toUpperCase(int)` /
  `Character.toLowerCase(int)` so the WASM result matches the interpreter and
  the JVM compile path byte-for-byte on every Unicode letter -- including
  supplementary ones like Deseret. The old ASCII-only fold is fully retired
  (`WasmCharCompiler.compileCaseFold` now emits a single `call` to the helper
  and re-boxes the returned i32 as `TYPE_CHAR`). See [[characters-code-points]]
  for the cross-backend CHARACTER invariant this closes.

## HEAP_PTR is a stack pointer; identity is a counter (the leak fix)

`HEAP_PTR_ADDR` linear memory is now used essentially ONLY as a reused byte-assembly
scratch (cons/closures/symbols-as-structs are GC objects, not linear). Two disciplines
share it:

- **Transient (save on entry, pop on exit)** -- every runtime string build. A builder
  assembles bytes at `start = HEAP_PTR`, calls `_str_fresh(start, len)` (copies the
  bytes into a fresh `$str_bytes` array + stamps `id = STRING_ID_CTR++`), then does NOT
  advance HEAP_PTR. Because the scratch offset is reused across builds, the COUNTER --
  not the offset -- is what keeps distinct runtime strings and uninterned symbols
  (`gensym`/`make-symbol`) `eq`-distinct. `STRING_ID_CTR_ADDR` (cell 156) is seeded at
  `heapBase`, so runtime ids are always `>= heapBase >` every interned/rt-intern
  offset (identity with literals is preserved) and never repeat.
- **Permanent (advance, never pop)** -- the interned-symbol byte pool. `_intern`
  (`WasmReadRuntimeBuilder.buildInternBody`) now COPIES a first-seen token into stable
  heap storage at `HEAP_PTR` and advances it, so the intern record + returned canonical
  offset no longer depend on the caller's (transient) bytes. HEAP_PTR's low-water = the
  interned high-water, which grows only with distinct runtime symbols (legitimate,
  bounded), not with string-building activity.

The three fixed helpers (always emitted, indices right after `FUNC_STR_BUILD`; bodies
in `WasmStringRuntimeBuilder`):

- `FUNC_STR_BUILD` `_str_build(off,len)` -- id = off. INTERNED names: literals
  (`compileStringLiteral`), `t`/`nil`/`quote`/`function`/`lambda`/keyword symbols,
  reader symbols (via `_intern`), `intern`. Two occurrences share a dedup/intern
  offset -> `eq`. **The boolean `t` no longer builds per site**: `emitTrue`
  calls `_t_sym` (`FUNC_T_SYM`), which lazily builds the "T" literal ONCE into
  a dedicated module global (always the last global) and returns the cached
  instance -- same id (the intern offset of "T"), same bytes, so eq/print are
  unchanged, but a comparison returning true allocates nothing (todo 194
  stage 3: loop termination tests used to allocate a `$str_bytes` per
  iteration, ~8% of the PBKDF2 profile). Quoted `'t` sites still build through
  `compileStringLiteral` (id-equal, so `eq` holds across both paths).
- `FUNC_STR_FRESH` `_str_fresh(off,len)` -- id = counter++. RUNTIME strings:
  concatenate/subseq/case/trim, read string literals, read-line, the capture path
  (`princ-to-string`/`prin1-to-string`/`concatenate`), `gensym`/`make-symbol`, `uiop:getenv`,
  fetch response, the host `:string` boundary. (A string output stream's contents is
  the one runtime string built WITHOUT it -- `_str_stream_contents` copies GC array to
  GC array and stamps the same counter id itself, and since todo-371 `_iv_utf8_str`
  (`FUNC_IV_UTF8_STR`, the strict-UTF-8 decode of a packed octet vector) is the second:
  same reason, one `array.copy` from the `TYPE_I8ARR` with no linear detour.
  `.kb/async-await.md`.)
- `FUNC_STR_TO_MEM` `_str_to_mem(str,ptr)->len` -- copies a string's array (quotes
  included) into `linear[ptr..)`; the array->linear bridge for the paths that still
  need a linear pointer: `open`/`load` path, the reader input scratch
  (`read-from-string`/`read`, which RESERVE the scratch so parse-time interns/builds
  stack above the unparsed input), `intern`, the host `:string` boundary
  (`WasmExportCompiler.emitStringResult`, reused by imports), the fetch wire, `tcp`
  host, and a string INPUT stream's source copy (an output stream needs no linear
  pointer at all).
- `FUNC_WRITE_STR_GC` `_write_str_gc(str,from,to,esc)` -- the print path for a string
  value: appends `[from,to)` straight from the GC array to `CAPTURE_CUR` in capture
  mode (no linear staging, so it can never alias the capture buffer while printing
  inside a `*-to-string` capture) or stages into HEAP_PTR scratch + `_write_str` for
  stdout. `princ` passes `(1,len-1,0)` to strip quotes; a symbol (no frame) passes
  `(start,len,0)`. **`prin1` passes `(1,len-1,1)`**: with `esc = 1` the callee writes
  the frame quotes ITSELF around the content and precedes every embedded `"` / `\`
  with a `\` (the `*print-escape*` rule -- todo 216). Framing inside the callee is
  what keeps the two frame quotes from being escaped as content; the scratch is grown
  to the worst case `n*2+2` and the actual output count is what `CAPTURE_CUR` /
  `_write_str` sees. The string branch of `_print_val` therefore makes the same
  leading-`"` string-vs-symbol test `_princ_val` does. The reader's un-escaping in
  `WasmReadRuntimeBuilder` is the mirror; the escape set is the reader's minus
  `\n`/`\t` (CL prints a newline literally). See
  [core-representation.md](core-representation.md) for the all-backend table.

## String streams: the OUTPUT half holds GC bytes, the input half a linear copy

`WasmStringStreamRuntimeBuilder` chunks / input records referenced string bytes by
linear offset, which a GC array cannot provide.

An OUTPUT stream keeps none: its record `[kind=1][slot][len]` names a per-stream
`$str_bytes` GC buffer through a module-global table (a `TYPE_HASH_BUCKETS` indexed by
`slot`), appends into it with `array.copy` and DOUBLES it when it runs out
(`_ostream_room`, `FUNC_OSTREAM_ROOM`). So a write costs GC-heap bytes the engine
reclaims, amortised, and linear memory holds nothing but the 12-byte record. It used to
cost a PERSISTENT linear copy of the content plus a 12-byte chunk record PER WRITE
(~15 bytes per character on a `write-char` loop, never reclaimed before the enclosing
arena reset). `_close` hands the slot back to a free list threaded through the table's
own entries, so a resident reactor's live-stream count is what bounds the table.

An INPUT stream still copies its source string into a persistent linear buffer
(`_str_to_mem`, bump-advanced) once at open, and `[kind=0][cursor][end]` walks that
copy; the string-stream read-line finalizes via `_str_fresh`. That copy is per STREAM,
not per read, and it is what makes `_read_line`'s linear scan work unchanged. Details:
[[read-load-streams]].

## The host arena API pops to the intern high-water (todo 124)

A memory-exporting module exports `__ronto_alloc_mark`/`__ronto_alloc_reset` so a resident
host can reclaim the input buffer it `__ronto_alloc`'d (the engine never traces linear
memory). Because of the two disciplines above, the reset is NOT a bare
`HEAP_PTR = mark`: it is `HEAP_PTR = max(mark, RT_INTERN_HEAP_ADDR)` (cell 172, the intern
pool's high-water, stored by `_intern` right after its permanent advance). Popping below it
would free bytes the intern registry (`RT_INTERN_COUNT_ADDR`) still points at, dangling
every symbol interned during the call. So a call that interns a NEW symbol keeps the host's
buffer (the permanent bytes are stacked above it) and every other call pops all the way
back. A string INPUT stream's source copy (above) is permanent too but is NOT guarded --
same trade the component `cabi_post_*` already makes: a stream that escapes its call is the
caller's problem. That trade is also why an output stream's free list lives in the TABLE
(GC) and only its head in a fixed cell: a free list threaded through the arena would hand
out records the reset had already given back. Details: [[wasm-export-no-wasi]].

## Grow guards kept

The `emitGrowHeapTo` guards at string builders are KEPT: a single string can exceed
the current linear size, so the scratch is grown ON DEMAND (once), not per-build. What
is retired is the UNBOUNDED HEAP_PTR advance; peak linear memory is now bounded by the
largest single live string, not the sum of all builds.

## Component byte-safety

No `DataCount`/`array.new_data`/segment reorder: `_str_build`/`_str_fresh` copy from
linear, so no new core section. Adding `TYPE_STR_TO_MEM`/`TYPE_WRITE_STR_GC` shifts the
export/import wrapper TYPE base (`TYPE_WRITE_STR_GC + 1`) and adding the three helper
FUNCTIONS shifts `FUNC_USER_BASE`, but the component embeds the core opaquely and binds
by export name, so it is unaffected (re-verified: `--component` + serve output
identical). See [[wasi-component]].

## Verifying the leak fix

`(dotimes (i N) (grow "..." 10))` building ~16KB strings via concatenate-doubling on a
wasm-GC host (wasmtime, Node/V8; NOT Chicory/Endive -- no wasm-GC): peak RSS is
FLAT vs N (N=50000 -> ~94MB, N=200000 -> ~91MB). A leak would scale linearly (multi-GB).

## `--simd` does NOT put anything in this memory

Under `--simd` (todo-105, `.kb/vec.md` acceleration layer 3) packed float arrays change
representation -- a `TYPE_VBLOCK` over an `(array (mut v128))` of lane groups instead of a
`TYPE_F64ARR`/`TYPE_F32ARR` -- but they stay GC objects and never touch linear memory. (An
`(array (mut v128))` is a legal GC array; `array.get` yields a v128 without any
`v128.load`.) `HEAP_PTR` and everything this file describes are untouched, and nothing in a
`--simd` module grows without bound. todo-101 briefly did move them into a bump arena here
(`VEC_HEAP_PTR_ADDR` = 160); todo-105 removed that word and its allocator. Without `--simd`
the module is byte-identical either way.

## Component memory grows with the program (uax-15 unblock)

`WasmComponentBuilder.memModuleFor` reads the rontolisp core module's
`"mem"/"memory"` import declaration (whose `min` pages equals the P1 own-memory
declaration, `max(4, (heapBase + 65535) / 65536 + 3)`), and rewrites the shared
`mem.wasm` module's memory section so its EXPORTED memory starts with at least
that many pages. Active data segments are copied to memory at instantiation
BEFORE any function runs, so a program whose static data / intern pool alone
exceeds the mem module's default six pages (uax-15 loads a ~2.7MB UnicodeData
blob at load time via the compile-time `with-open-file` file-inlining --
`.kb/asdf.md`) would otherwise trap with an out-of-bounds memory access on the
very first byte written. The patched mem module keeps its bump-allocator body
byte-identical; only the `(memory (;0;) N)` count changes. When the core module
does not import a `"mem"/"memory"` (a non-rontolisp embedder or a small program
whose min stayed at four) the fallback returns the unchanged resource.

## Related
- [[string-index-cost]] -- the character-index cursor in fields 3/4, and the JVM
  half of the same invariant.
- [[27-wasm-gc-heap-never-grows]] -- the linear string heap this retires.
- [[read-load-streams]] -- string streams + the reader.
- [[symbol-runtime-api]] -- `intern`/`make-symbol`/`gensym` identity.
- [[no-gc-scalar-wasm]] -- the `--no-gc` backend (unaffected; keeps its own heap
  reset in [[88-no-gc-export-wrapper-heap-reset]]/[[89-no-gc-heap-mark-release]]).
