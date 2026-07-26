# uax-15's derived tables are materialized eagerly at load, and nothing in the seed case reads them

After `.todo/179` closed, `examples/db/postgres-hello.lisp` runs in 4.2 s on the
interpreter and 2.2 s warm on `--component`. **Both figures are ~90% one thing:
uax-15 materializing its derived tables while it loads** -- work that program never
uses, because under `trust`/`password`/`md5` authentication `saslprep-normalize` is
never called, and even under SCRAM it returns early for printable-ASCII credentials.

## Measurement (2026-07-26, native binary, wasmtime 46.0.1, warm)

`get-internal-real-time` marks around each `ql:quickload`, plus one `normalize` call:

| | cl-ppcre load | uax-15 load | first `normalize` |
| --- | --- | --- | --- |
| `--component` | 3 ms | **2,092 ms** | 0 ms |
| interpreter | 72 ms | **3,741 ms** | 0 ms |

Whole-component wall time, warm, by program:

| program | module | wall |
| --- | --- | --- |
| `(print :hi)` | 204 KB | 0.00 s |
| `(ql:quickload "cl-ppcre")` | — | 0.03 s |
| `(ql:quickload "uax-15")` | 1.95 MB | 2.15 s |
| `(ql:quickload "cl-postgres")` | 5.98 MB | 2.23 s |
| `postgres-hello.lisp` (live PG 17) | 5.98 MB | 4.2 s cold / ~2.2 s warm |

Two things this settles, and both redirect effort away from where it looked like it
should go:

- **Module size is not the cost.** 204 KB -> 5.98 MB adds 0.08 s. Tree-shaking the
  third-party tree (`.todo/183`) would cut ~20% of a 6 MB module and buy roughly
  0.02 s here. Do it for the constant-pool and artifact-size reasons in that item,
  not for this program.
- **Interpreter macro re-expansion is not the cost either** (`.todo/182`): a `loop`
  expands once per execution of the loop form, not per iteration, so the 3.7 s is the
  scan and the hash writes, not expansion. That item stands on its own measurement
  (the `do-*` regex shapes), not this one.

`first normalize = 0 ms` is the whole finding: the tables are complete before anything
asks for one.

## What to do

Materialize each derived table on FIRST READ instead of at load. The consumers are
few and each is a single accessor (`.kb/asdf.md`, the derived-tables section):

| table | entries | read by |
| --- | --- | --- |
| `*canonical-combining-class*` | 922 | `get-canonical-combining-class` |
| `*canonical-decomp-map*` | 2,061 | `decompose`, `get-mapping` |
| `*compatible-decomp-map*` | 3,796 | `decompose`, `get-mapping` |
| `*canonical-comp-map*` | 945 | `compose`, `get-mapping` (built by a maphash over the two above) |
| `*unicode-letters*` | 21,765 + 7 range predicates | `unicode-letter-p` |

The shape is the one this codebase already uses twice -- `Environment.defineLazy` for
macro-time globals (todo 179 phase 1) and the `%LOAD-TIME-VALUE-N` slot fill
(`.kb/compiler-macros.md`) -- and once inside uax-15 itself: the illegal-character
lists are ALREADY expanded on demand inside `get-illegal-char-list` and cached. Doing
the same to the other five is the same rewrite in `eval/Uax15Tables`, one span at a
time, and it is a rewrite of the DERIVED forms only, so no upstream source changes.

Measure per table before assuming it splits evenly: `*unicode-letters*` is 3x the
entry count of the other four combined, so it may be most of the 2.1 s on its own --
and it has exactly one consumer (`unicode-letter-p`, which nothing in the loadable
set calls except `postmodern/util.lisp`). If it dominates, that one span is the whole
fix.

Expected: postgres-hello to ~0.15 s on the component and well under 1 s interpreted,
with `Uax15E2eTest` unchanged (it exercises every table, so laziness has to be
transparent, which is the point of the pin).

Second lever if laziness is not enough for a program that DOES normalize: the ~14 us
per entry is high, and the derived data is scanned out of decimal runs inside string
literals (forced by the JVM constant-pool ceiling, `.kb/jvm-method-size-limits.md`).
On wasm-GC that scan allocates while a growing live set exists, which is exactly the
~10x penalty measured as Finding 2 of `.todo/179`. A denser encoding, or building each
table into a pre-sized structure, would attack the constant -- but only after
laziness, since the cheapest work is the work not done.
