# j64. wasm: the Starlark programs still over CPython: strings sorted, split and join, a loop's counter

Difficulty: High

`.kb/starlark-frontend.md`, "Where the time is" (2026-11-16): after j63 seven of the
eleven are under CPython on wasm and four are not -- `comps` 0.93 / 0.89, `methods`
2.00 / 1.09, `strings` 1.50 / 1.14, `sorting` 2.14 / 1.00 (`time.sh` of
`.todo/artefacts/j54-starlark-faster-than-cpython/`). The goal stays: each of them under
CPython on wasm.

**Profile first.** `.todo/artefacts/j63-starlark-under-cpython/profile.sh program 30
--callers` samples the machine code (`--offsets NAME#N` one function's instructions); the
guest profiler misattributes, and j63's own first plan -- the index helpers' heads at
their call sites -- was not where these programs spend their time. `parts/*.star` there
are the five cut into one feature a file, each to be set against `python3`.

What the samples of the build after j63 say is left (user seconds of a part, wasm against
CPython):

- **`sorted` of strings** (`parts/o3`: 1.07 against 0.35; ints are level, `o1` 0.54 / 0.43,
  by a key `o2` 0.40 / 0.34). `_str_order` is 54% of it and `%starlark-merge-strings` 31%;
  a third of `_str_order`'s samples are the four instructions that first load an operand's
  header -- two string structs, two byte arrays -- so a comparison is four cache misses
  and the walk's shape moves nothing. Not tried: a sort the backends own (a stable sort of
  a vector of immutable strings; `Arrays.sort` on the JVM, a merge over the array on
  wasm), the first bytes of each string read once into an integer and the radix sort
  ahead of the comparisons, a sample sort whose buckets fit the cache.
- **`split`** (`parts/m3`: 0.56 against 0.25; 31% of `methods`, 20% of `strings`): a piece
  is `%string-search` (`_str_search`: two `stringp`, up to four calls of
  `_str_char_byte_offset`, a scan and an index count that re-read the array's length each
  byte), `%substring` (`_subseq`: `_seq_len`, two offset calls, two allocations) and a
  push. One primitive that splits, the pieces located in one pass, is the size of the
  gap; so is `join` (`parts/s2a`: 0.54 against 0.19 -- a `write-string` a piece through
  `%stream-target`) and `list.pop()` (`parts/m1b`: 0.34 against 0.17).
- **`_string_concat`**, 18% of `strings`: a struct, a byte array and two `array.copy`,
  each a call into the engine. Short operands by a loop, as a slice's are since j63
  (`emitBuildCore`), not measured.
- **A loop's counter** (`comps`: 66% in its three loops, about 35 ns a round; every
  `for`): `next` / `index` is a dual local -- a shadow test at each read
  (`.kb/wasm-unboxed-locals.md`) --, the limit is unboxed each round and the element
  variable copied through `_ub_read`. What would make it exact: `valueRange` of
  `(length v)` (an array is shorter than 2^32), and an induction variable that a `while`'s
  test bounds by an exact local where `WasmCountedLoopCompiler.analyze` asks for a
  literal; `range(n)` needs a range for its stop, which is any integer here (Go refuses
  one outside 64 bits).
- **A dict's store** (`comps` 27%: `_ht_put` 18% itself, the growth 6%): an entry is a
  six-slot array and every slot read a bounds check; `make-hash-table :size` is ignored
  on wasm. **A string key is hashed at every look-up**, 21 ns of 53
  (`.kb/hash-tables.md`): a hash kept in a seventh field of `TYPE_STRING` is free on
  wasmtime's rounding.
- **A small defun's body at its call site** (j63's first item, not made): the wasm backend
  honouring `(declaim (inline f))` for a defun of required parameters and a small closed
  body, compiled as `(let ((p a) ..) body)` where no lexical name of the site shadows a
  free name of the body. The wrappers it removes were 41% of `dicts` before j63
  (`%starlark-dict-cell` 14%); the string methods, `get` and `setdefault` test their receivers
  by hand since; the other dict, list and set methods still call `-receiver` with a
  literal name. For the index helpers:
  1.15 -> 0.79 s of `listidx` hand-written (`.todo/artefacts/j62-wasm-fixnum-versions/`).
- **The radix pass** is 25 ns a step for some ten operations: a store's subscript reads
  an unboxed local boxed (`_ub_read`, a call), each access casts its array again and
  re-reads its length. `ms.lisp` / `ms2.lisp` of the artefacts: a merge sort over
  `(unsigned-byte 32)` vectors is no faster than the one over conses, for the same
  reasons -- a typed local for a declared array is what both wait for.
- `_str_lit`, 5% of `methods` and of `strings` (a call and a table read per literal
  evaluated); the collector, 9% of `methods`.
- **The JVM pays the radix sort's compilation**: warm, a pass over 50,000 keys is 1.4-2.4
  ms there, but C2 makes 109 KB of code of the pass's 495 bytes in 0.7 s and again after
  each branch it had not seen -- `sorting.star`'s 24 rounds are no faster on the JVM than
  before (2.72 -> 3.10 s; 2.9-3.6 against 3.2-4.0 over four runs each), 8.9 s against
  10.6 with C1 alone. What inlines so large is the packed
  vector's generic accessor (`_ivAref1`, `_ivAset1`, `_ivCheckRank`).
- **The bytes** a program that calls `sorted` pays: 9 KB of wasm for the radix sort and
  10 KB for the arms its packed vectors open in the library's generic accesses
  (`examples/starlark/targets.star` 218,785 -> 246,745 B with everything of j63). Over
  general vectors the sort is a fifth slower and those 10 KB smaller.

## Do

- `sorted` of strings and `split` / `join` first: they are `sorting`'s, `methods`' and
  `strings`' gaps, and each wants a primitive (`.kb/adding-primitives.md`) or a tighter
  one. Measure the candidate on `parts/` before making it.
- The loop's counter, for `comps` and for every program.
- Re-run `time.sh` over the eleven and record the table in `.kb/starlark-frontend.md`.
