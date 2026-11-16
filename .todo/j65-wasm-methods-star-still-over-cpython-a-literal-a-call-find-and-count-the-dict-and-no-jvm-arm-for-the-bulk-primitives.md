# j65. wasm: `methods.star` still over CPython: a literal a call, `find` and `count`, the dict; the JVM has no arm for the bulk primitives

Difficulty: High

`.kb/starlark-frontend.md`, "A sequence at a time" (2026-11-16): after j64 ten of the
eleven are under CPython on wasm and `methods.star` is not -- 1.45 against 1.11
(`time.sh` of `.todo/artefacts/j54-starlark-faster-than-cpython/`), 1.35-1.41 against
1.05 in user seconds. `dicts.star` is under by a twentieth (0.87 / 0.92) and was level in
one run of three. The goal stays: each under CPython on wasm.

**Profile first** (`.todo/artefacts/j63-starlark-under-cpython/profile.sh program 30
--callers`). What the samples of the build after j64 say is left of `methods.star`:

- **`find` and `count`**, 16% (`_str_search` 15% with its `_str_char_byte_offset` calls):
  a `count` is one search an occurrence, a search up to four offset calls, the scan, and
  a second walk of the bytes passed to make a character index of the match. Not made: an
  arm for a haystack whose count is its bytes (an offset is `index + 1`, the answer `pos
  - 1`, no second walk); `count` as one walk.
- **`split`**, 12.5%, all of it `_str_split` now: some 25 objects a call for nine pieces
  (a byte array and a string struct a piece, the vector's four conses, dims and cell).
- **A literal evaluated is a call** (`_str_lit`, 6.4%; 4-5% of `strings`, 9-18% of the
  parts): `:none` a method answers, `:%unset` for each argument a call leaves out, a
  string argument. Not made: a literal as a global the module sets once (a start
  function; `.kb/wasm-gc-strings.md`, "A literal is one object", has what a site and the
  shaker would pay), `_t_sym` the same way.
- **The dict**: `_ht_get` 8%, `%starlark-dict-cell` 5.7%, `d[k] = v` through
  `%starlark-set-index` -> `-set-index-of` -> `-dict-store` 10%. In `dicts.star`
  `_ht_get` is 39%, `%starlark-dict-cell` itself 19% and `_hash` 9%: `make-hash-table
  :size` is ignored on wasm and a string key is hashed at every look-up (a hash kept in
  a seventh field of `TYPE_STRING`, `.kb/hash-tables.md`; every `struct.new` of one goes
  through `emitSeedCursor` or `emitCountedRuntimeString`). `(i * 31) % m` by a variable
  is `_fx_mod`, 8% of `dicts`.
- **`append` and `pop`** 7% each, through a `def` (`push(xs, v)`): the call of the `def`,
  then of the helper. The wasm backend honouring `(declaim (inline f))` (j63's item, not
  made) removes one of them.
- **`strip`** 5.3% (`member (char s i) cutset`: a character is an allocation on wasm),
  `startswith` / `endswith` 5.2%.

Measured and not the cost any more: a loop's counter (`comps.star` waited on the growth
of the vector its comprehension pushes into: 0.86 -> 0.66 s with the growth as one
`array.copy`); the radix pass and its bytes (the library's radix sort is gone).

**The JVM**: `%sort-by-keys`, `%string-split` and `%string-join` answer nil there, so
`sorted` is the merge over conses and `split` the loop: `sorting.star` 3.07, `methods.star`
1.72, `strings.star` 2.2-2.5, `comps.star` 1.65 against CPython's 0.9-1.15. An arm needs
the vector's representation in hand-emitted bytecode (`_arrayMake`'s header;
`Arrays.sort` over the keys for the sort).

## Do

- The literal first: it is in every program. Measure a hand-edited module (`global.get`
  for each `_str_lit` call of `methods.wasm`) before making it.
- `_str_search`'s arm and `count`; then the dict's store and a string key's hash.
- Re-run `time.sh` over the eleven and record the table in `.kb/starlark-frontend.md`.
