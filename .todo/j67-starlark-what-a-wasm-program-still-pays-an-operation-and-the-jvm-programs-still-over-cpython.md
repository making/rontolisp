# j67. Starlark: what a wasm program still pays an operation; the JVM's programs still over CPython

Difficulty: High

`.kb/starlark-frontend.md`, "The JVM's arms" (2026-11-16): the bulk primitives have
their JVM arms, and the eleven programs are under CPython on wasm and the component. On
the JVM five are not (`time.sh` of `.todo/artefacts/j54-starlark-faster-than-cpython/`):
`comps.star` 1.69 against 0.92, `strings.star` 1.90 / 1.14, `methods.star` 1.39 / 1.17,
`sorting.star` 1.33 / 0.99, and `bench.star`, which is the start-up. Everything below is
measured and not made; none of it is owed to a goal that is not met on wasm.

## The JVM

JFR, `jdk.ExecutionSample` every 1 ms (`.todo/artefacts/j66-starlark-jvm-bulk-arms/`,
`jfrtop.py`), percent of the main thread's samples.

- **`comps.star` waits on no primitive**: `_vectorPushExtend` 23% (a `Long` made for the
  fill pointer a push; the growth an `ArrayList.add` a slot, with `ArrayList.grow` 3%
  beside the vector's own doubling, which one `ensureCapacity` ahead of the loop would
  take), a dict comprehension's store 20% (half of it `RontoHashTable$Flat.grow` and its
  `Arrays.fill`: the table is grown from nothing 80 times), the loops' own body 31%,
  `_ubRead` 7%, `Long.valueOf` 5% (`.kb/starlark-frontend.md`, "Against CPython", "The
  JVM boxes").
- **`strings.star`**: `String.concat` 30-42% -- a framed string is `substring`,
  `substring`, `concat`, 20 ns for `"item"` and a number; one `StringBuilder` at its
  size is 36 and a `char[]` 26, so a chain as wasm's `_str_cat` gains nothing while a
  string keeps its quotes. `Long.toString` 13%.
- **`methods.star`**: `run`'s own body 20%, `ArrayList.add` 15%, `split` 14%, `lookup`
  11%, `push` 11%, `d[k] = v` 8%, `pop` 8%, `_bulkCopy` 4.5% (two calls of 1,200,000
  elements).
- **`sorting.star`**: `_sortKeys` 32%, the vectors a `sorted` makes and the copy a key
  function is kept from 15% (`%starlark-copy-vector` unboxes into a `long[]` what the
  answer boxes again), `min` and `max` 12%, `reversed` 9%. The compiler's threads are
  four of the process's five seconds of CPU (`.kb/bulk-primitives.md`, "JVM").
- The interpreter has no arm of any bulk primitive.

## wasm

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

- Pick by what a program a user wrote waits on, not by these eleven: profile first
  (JFR on the JVM, the sampler on wasm), make the change by hand in the output and
  measure it before it is made in the compiler.
- On the JVM, `comps.star` first (it is the furthest from CPython): the push and the
  table's growth.
- Re-run `time.sh` over the eleven and record the table in `.kb/starlark-frontend.md`.
