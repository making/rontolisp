# Primitives that take a whole sequence: `%string-split`, `%string-join`, `%string-count`, `%sort-by-keys`

**Invariant: each answers what its caller's own loop answers, or nil -- nothing decided --
and the loop runs.** A cost invariant, the shape of `%string-search`
(`.kb/string-search-runtime.md`): the wasm-GC backend and the JVM have an arm each, the
interpreter compiles the form to constant nil (its arguments are variables, so skipping
them is unobservable). No user source reads the names (`PackageRegistry.CL_INTERNALS`);
the Starlark library is the caller (`.kb/starlark-frontend.md`, "A sequence at a time").

| form | answers | nil on wasm and the JVM for |
|---|---|---|
| `(%string-split string separator limit)` | the pieces between the leftmost LIMIT occurrences of SEPARATOR that do not overlap (all when LIMIT is negative), in a fresh vector as `(make-array n :adjustable t :fill-pointer n)` makes one | an operand that is no string, an empty separator, a limit that is no fixnum |
| `(%string-join strings separator)` | the strings of a general vector as one immutable string, SEPARATOR between them | a vector without storage of its own, an element or separator that is no immutable string (a mutable character vector among the elements) |
| `(%string-count needle string start end)` | how many occurrences of NEEDLE that do not overlap, leftmost first, lie in STRING between the character indices START and END, which the caller checked | an operand that is no immutable string, an empty needle, a bound that is no fixnum |
| `(%sort-by-keys keys elements out descending)` | OUT, the ELEMENTS (the KEYS when ELEMENTS is nil) stored into it in the order of their keys, equal keys in the order they stood in | keys that are not all integers within 64 bits or all immutable strings; a vector without storage of its own; ELEMENTS or OUT shorter than KEYS. OUT is then as it was |

## wasm-GC (`WasmBulkRuntimeBuilder`, `FUNC_BULK_BASE`)

Four functions behind `_vec_sync`, each on a `TYPE_CALLABLE_BASE` signature, so no type
is added; shaken from a module that names none. Written with
`WasmJsonRuntimeBuilder.Asm` (its `Kind.INTS` is a `(ref null $i32arr)` local, added for
the sort). The block's fourth function, `_str_cat`, is no primitive of its own: it is
what a chain of `%string-concat` compiles to (`.kb/wasm-gc-strings.md`, "A chain of
concatenations is one string"). The block also holds `_lit_init`, the module's start
function, whose signature and body are the compiler's (`.kb/wasm-gc-strings.md`, "A
literal is one object").

- **A general vector is read through its cell's fields** (`CELL_STORAGE_FIELD`,
  `CELL_META_FIELD`, `.kb/adjustable-arrays.md`): the storage array, and the fill
  pointer or the storage's length. A cell with a null storage field -- a view, another
  rank -- is declined.
- **`_str_split`** walks the string twice: the occurrences are counted, the vector's
  storage made at that size, then each piece is one `$str_bytes` filled by `array.copy`
  and counted (`emitCountedRuntimeString`) by its bytes when the string's own count is
  its content's byte length. The vector is built as `make-array` builds it,
  `(dims . ((fill-pointer . (T . 0)) . data))` and `_vec_sync`, so a list takes it as its
  items with no copy. `_charvec_to_str` normalizes a mutable operand first.
- **A piece of at most eight bytes is made with its bytes in hand** (`piece`,
  `SHORT_PIECE`, the speed levels, 2026-11-16): a `br_table` over the length to one
  `array.new_fixed` of the quotes and the bytes read. `array.new_default` and
  `array.copy` are a call into the engine each (`memory_fill`, `memory_copy`), and were
  most of what a word cost; a longer piece is still made that way.
- **`_str_join`** measures (bytes, and characters while every operand has its count),
  then copies each operand once.
- **`_str_count`** is one walk of the string's bytes between the two bounds (`walk`, the
  loop `_str_split` counts with): a bound is its index and one in a string counted at its
  bytes, `_str_char_byte_offset`'s answer otherwise. A mutable operand declines.
- **`_sort_keys`** reads each key once into two i32 words that order as the key does: an
  integer's 64 bits with the sign bit flipped; of a string its first seven bytes and
  then its length saturating at eight -- two strings of equal words are equal or both
  longer than seven bytes. The keys' PLACES are sorted: a radix sort over the words from
  the low byte up, a byte every key agrees on skipped (`or ^ and` of the words), the
  byte complemented when descending; then each run of longer strings with equal words
  by a bottom-up merge over `_str_order`, a pair of runs already in order copied. The
  scratch is `(array (mut i32))` (`TYPE_I32ARR`) held in typed locals only, so no value
  of that type reaches a type test and `WasmRefTypeFolder` opens no packed-vector arm for
  it.

## JVM (`JvmBulkRuntimeBuilder`, 2026-11-16)

Private static methods of the generated class, hand-assembled, emitted in every class
and dropped by the shaker from one that calls none; with them the arms of
`%string-search` (`_strSearch`, `.kb/string-search-runtime.md`) and `%replace-bulk`
(`_bulkCopy`, `.kb/sequence-op-runtimes.md`). They call nothing of the array runtime,
so a program outside the array gate may carry them.

- **The two representations are read where they lie** (`_bulkStr`, `_bulkVec`,
  `_bulkLen`): a string is a `String` of two units or more that begins with the framing
  quote; a vector an `ArrayList` whose slot 0 is a header of length 3 (boxed elements),
  5 with no target (boxed, an element type remembered) or 6 (unboxed in the `long[]`
  of its last slot, `Long.MIN_VALUE` for nil -- what a plain `(make-array n)` is,
  `.kb/adjustable-arrays.md`), of one dimension. A view, a character vector, another
  rank, a host `ArrayList` decline. `out` must be a header of length 3.
- **A needle is found as UTF-16 units** (`_strFind`: `indexOf` of its first unit,
  `regionMatches` of the rest, nothing allocated): a match of units is a match of code
  points when the needle neither begins with a low surrogate nor ends with a high one
  (`_bulkWhole`); one that does declines. A bound is `_cpoff` of its character index.
- **`_strSplit`** cuts each piece from one `char[]` of the string, the framing quotes
  written over the unit before the piece and the one behind it (the opening quote or a
  separator's last unit; a separator's first or the closing quote -- none read again),
  so a piece is one `String` made in one pass. The vector is built as `make-array`
  builds it: `{{n}, n, "T"}` in slot 0.
- **`_strJoin`** measures, then appends each string once to a `StringBuilder` made at
  that size.
- **`_sortKeys`** reads each key once into a 64-bit word that orders unsigned as the
  key does: a fixnum with its sign bit flipped (a `BigInteger` declines); of a string
  its first seven units, each below 256, then its length saturating at eight, and from
  the first unit of 256 or more on every bit set (`_bulkWord`) -- so a word is its whole
  string when its low byte is below eight, and any other two strings of equal words go
  to a merge. Every word is complemented when the order is descending. The keys'
  PLACES are sorted by their words, the words moved beside them: by insertion up to 32
  keys (a radix pass is 256 counters), else by the radix sort wasm's arm is
  (`_bulkRadix`, one pass a call); then each run of equal words that are not their
  whole string by a bottom-up merge over `_strOrder` (`_bulkMerge`). Keys or elements
  in a `long[]` are read from it.
- **Measured** (`.kb/starlark-frontend.md`, "The JVM's arms"): `sorting.star` 3.24 ->
  1.30 s, `strings.star` 2.47 -> 1.83, `methods.star` 1.77 -> 1.42. A sort of 50,000
  ints and one of 50,000 strings are 5.1 ms the pair from the hundredth round on, 15 ms
  over the first 24 rounds and 11 over the next 72: `sorting.star` is 96 sorts, and its
  process is 5.2 s of CPU for 1.3 s of wall time with one collection of 12 ms -- the
  rest is the compiler's threads (Oracle GraalVM 25; with C2 as the top tier,
  `-XX:-UseJVMCICompiler`, 2.8 s of CPU and 1.10 s). With the radix pass a method of
  its own, `_sortKeys` and what it calls are 284 of the main thread's samples (1 ms
  each) where they were 395.
- **Bytes**: a class that reaches `search` or `replace` carries `_strSearch`,
  `_bulkCopy` and their helpers, 2,246 B (`print(1 + 2)` in Starlark 109,424 ->
  111,670); one that sorts, splits, joins and counts 5.5 KB (`sorting.star` 209,212 ->
  214,701). `(print (+ 1 2))` is unchanged at 6,193 B.

## Measured (2026-11-16, wasmtime 49, user seconds of the precompiled module)

- **A merge mispredicts, a radix pass does not.** `_sort_keys` first written as a merge
  over the two words: 50,000 ints 14.5 ms a sort, against 10 for the Lisp radix sort it
  replaced; as the radix sort above 3.7 ms. `parts/o1` (ints both ways) 0.49 -> 0.70 ->
  0.18 s, `parts/o3` (strings) 1.00 -> 0.38 -> 0.19 s; CPython 0.41 and 0.31.
- `parts/m3` (`split` of 43 characters into nine, 240,000 times) 0.53 -> 0.17 s (CPython
  0.21); `parts/s2a` (`join` of 1,200,000) 0.48 -> 0.31 s (0.17).
- **`array.copy` for a few bytes is no slower than a loop.** A byte loop up to sixteen in
  split and join: `m3` 0.19, `s2a` 0.33; `array.copy` always: 0.17, 0.31. The same loop in
  `_string_concat` for short operands made `strings.star` 1.07 -> 1.20 s and is not
  made. (`emitBuildCore` keeps its loop: not remeasured.)
- **A split's word made by `array.new_fixed`**: `parts/m3` 0.17 -> 0.15 s, `methods.star`
  1.03 -> 0.96 s (its `split` was 15% of the samples, two engine calls a piece).
  **`count` as one walk**: a Starlark `count` was a search an occurrence, 10.8% of
  `methods.star`; `parts/m5` (`startswith`, `endswith`, `find`, `count`) 0.33 -> 0.19 s
  with the search's own arm (`.kb/string-search-runtime.md`), CPython 0.23.
- wasmtime 49's default collector is the copying one: `-C collector=drc` runs
  `methods.star` in 6.9 s against 1.5.

## Tests

`starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths` (`fast_bulk`:
ints, negative, past 2^31 and past 64 bits; strings short and sharing a long prefix,
both ways, by a key with equal keys both ways; `split` by a separator of several
characters, with a limit, of characters of several bytes; `join` of none, one, seventy;
what a list made by `split` takes from `extend`, `append` and `pop`; `fast_strings`:
`count` over one-byte characters, several-byte ones and a string never counted, with
each bound, an empty needle and overlapping occurrences; a split of pieces of eight
bytes, nine and several-byte characters; `fast_wide`: strings of characters below and
above 256 and outside the BMP sorted, joined, split, counted and searched at 40 and at
6 -- either side of the size the JVM's sort changes its method at --, a tuple's vector
with False among its ints) on four backends, and the conformance corpus. The interpreter
runs the callers' loops there, so the same expected text pins both halves.
`.todo/artefacts/j66-starlark-jvm-bulk-arms/fuzz.star` prints 424 lines of the same over
six alphabets and seven int ranges; the interpreter, the JVM and wasm print the same.
