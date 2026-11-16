# j66. Starlark: the JVM has no arm for the bulk primitives; what a wasm program still pays an operation

Difficulty: High

`.kb/starlark-frontend.md`, "A literal is a global" (2026-11-16): the eleven programs
are under CPython on wasm and the component (`methods.star` by a twentieth, 1.04 against
1.10). Two things are left, neither of which that goal asked for.

## The JVM

`%string-split`, `%string-join`, `%string-count` and `%sort-by-keys` answer nil there
(`.kb/bulk-primitives.md`), so `sorted` is the merge over conses and `split` the loop:
`sorting.star` 3.38, `strings.star` 2.34, `methods.star` 1.83, `comps.star` 1.56 against
CPython's 0.87-1.15 (`time.sh` of `.todo/artefacts/j54-starlark-faster-than-cpython/`).
An arm needs the vector's representation in hand-emitted bytecode (`_arrayMake`'s
header; `Arrays.sort` over the keys for the sort). **Profile first** (JFR,
`jdk.ExecutionSample` every 1 ms): the last profile of the JVM column is older than the
library's last four rewrites.

## wasm: measured, not made

The sampler of `.todo/artefacts/j63-starlark-under-cpython/` over the build of
2026-11-16. What a function costs there is its count of type operations, about 1.5 ns
each (a cast, a test, a bounds-checked `array.get`: ten to fifteen machine instructions).

- **A hash table's look-up is a dozen of them**: `_ht_get` 27 ns for a fixnum key -- the
  record, the buckets and an entry are arrays of `(ref null eq)`, so every read is a
  bounds check and a cast (`.kb/hash-tables.md`, "Representation"). 34% of `dicts.star`,
  11% of `methods.star`. An entry as a struct with typed fields (the hash an i32, next,
  key and value) would read with no bounds check and one cast; the 25 `_ht_*` functions
  and the trie share the layout.
- **`%starlark-m-append` is some 300 machine instructions a call** (9% of
  `methods.star`): each of its three guards and the push casts the list's instance
  again and reads its slots array through a bounds check (`.kb/defstruct.md`). A
  structure reader that takes the slots array once for several reads, or casts of one
  local merged, would take a third of it; `%starlark-dict-cell`, `-m-get` and
  `-dict-store` are the same shape (13% of `dicts.star` in the first alone).
- **A `def` of one call is a call** (`push(xs, v)`, `lookup(d, k)`): the wasm inliner
  moves a body only into its one call site. `(declaim (inline f))`, or a size bound for
  a body of a few instructions.
- **`(i * 31) % m` by a variable is `_fx_mod`**, 7.5% of `dicts.star`: a call for two
  fixnums.
- **A literal named once is ten bytes larger than it was** (`.kb/wasm-gc-strings.md`, "A
  literal is one object": 112 of the 125 literals of `examples/proxy-wasm/gateway.lisp`,
  +1.3% of the module). A global and four instructions of `_lit_init` a literal; how
  many sites name a literal is not known when a site is emitted.

## Do

- The JVM arms, `sorted` first (`sorting.star` is the furthest from CPython there).
- Re-run `time.sh` over the eleven and record the table in `.kb/starlark-frontend.md`.
