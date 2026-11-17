# j75. Starlark: a build graph in the example's shape is over CPython; the dict look-up, the collector, what the JVM compiles

Difficulty: High

`.kb/starlark-frontend.md`, "A program a user wrote" (2026-11-17): the eleven programs
are under CPython on wasm, and `graph.star`
(`.todo/artefacts/j67-starlark-a-program-a-user-wrote/`, `examples/starlark/targets.star`
at 2,000 targets) is not: CPython 0.48 s, wasm 1.12, the component 1.09, the JVM 1.80.
Its parts against CPython (wasm, user seconds): the targets made 0.24 / 0.22,
`build_order` 0.27 / 0.11, `rebuilt_by` 0.33 / 0.15, `describe` 0.10 / 0.08, `max`,
`format(**d)` and `sorted` 0.10 / 0.05. Everything below is measured and not made.

## wasm

- **A dict look-up by a string is a quarter of the program.** `%starlark-dict-cell` 10%
  itself, some 110 machine instructions for a string key: `integerp`'s two failed
  struct tests, `stringp`'s `ref.test` and then `ref.cast` of the same value (one
  `br_on_cast` in the backend's `stringp`, for every language), its bytes' cast and a
  bounds-checked byte, the table's accessor, `gethash`'s three tests of its operand.
  `_ht_get` 10%: the entry as a struct with typed fields, below. `_string_eq` 5%: a
  store into a small record compares the new key with each key before it (three keys
  of one length are three calls a dict made); a first byte compared inline, or the
  hash both strings keep, would end most of them.
- **The copying collector is a fifth of the run** (0.92 s with `-C collector=null`
  against 1.16), and a whole-program time moves with where its collections fall, so
  compare builds on the parts or at several sizes. What a value allocates: a dict's item
  is an entry and a cons, a list an instance, its slots, a cell, a header and the data.
- **`build_order` and `rebuilt_by`** are the furthest from CPython (2.5 and 2.2 times, the
  targets made once included): in the first `users.get(name, [])`
  makes a list a call, `ready.pop(0)`, `needs[user] -= 1` and the test after it are four
  look-ups.
- Carried from `.todo/j67`, not reached: a hash table's entry as a struct (`_ht_get` 27
  ns for a fixnum key; the 25 `_ht_*` functions and the trie share the layout); a
  structure reader that takes an instance's slots once (`%starlark-m-append`,
  `-dict-cell`, `-m-get`, `-dict-store`); a `def` of one call inlined at each call site
  (`wasmtime compile -C inlining=y` does not: 1.09 -> 1.16-1.25 s); `(i * 31) % m` by a
  variable as `_fx_mod`; a literal named once ten bytes larger than it was.

## The JVM

- **A second-long program waits on the compiler.** `graph.star`: 1.9 s of wall time and
  10 s of CPU, 18 top-tier compilations of 60 ms or more, 5.6 s between them, each of
  the five functions compiled two to four times at 60-100 KB of code. `comps.star`: a
  round is 7 ms compiled where CPython's is 11-12, and a second of its 1.5 is spent
  before that; `run` (2,273 bytes, five loops) is compiled on-stack three times, 450-660
  ms and 165-198 KB once every loop has run. Untried: a loop of a function as a method
  of its own (a smaller unit compiled sooner, and one type check not seen costing one
  loop its code), what of the inlined helpers makes 2,273 bytes 190 KB.
- **Compiled, `graph.star` is still over CPython**: 8.0 s against 5.4 at ten times the
  rounds. The main thread's samples: `make_targets` 15% itself,
  `%starlark-concat-pieces` 7%, `String.concat` 6% (a framed string is `substring`,
  `substring`, `concat`), `build_order` 7%, `_length` 4%, `RontoHashTable$Flat.find` 4%,
  `%starlark-list-p` 3%.
- `_vectorPushExtend`'s shorter store and one `ensureCapacity` were measured and not
  made (800 rounds of `comps.star` 4% less, its 80 rounds and `methods.star` no better).
- The interpreter has no arm of any bulk primitive.

## Do

- Profile first (`profile.sh` of `.todo/artefacts/j63-starlark-under-cpython/` on wasm,
  JFR with `jfrtop.py` and `jfrcomp.py` on the JVM), make the change by hand in the
  output and measure it before it is made in the compiler.
- On wasm the look-up first: `stringp`, then the entry.
- Re-run `time.sh` over the eleven and `graph.star` and record the table in
  `.kb/starlark-frontend.md`.
