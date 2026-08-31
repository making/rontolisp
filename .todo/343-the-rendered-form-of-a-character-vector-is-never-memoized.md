# The rendered form of a character vector is never memoized

Difficulty: High

Split out of `.todo/342`, which fixed the one caller that never wanted the
string at all (`stringp` asks `_charvec_p` for the marker shape now, in constant
time). Every OTHER caller of the normalizer still re-renders its argument on
EVERY call, because the rendered string is never written back into the cell:
`string=`/`string-equal`, `string-upcase`/`-downcase`/`-capitalize`, `subseq`,
`concatenate`, the `string-trim` family, `write-string`, `char`/`schar`,
`read-from-string`, `intern`, `make-symbol`, plus the entries of `_equal`,
`_hash`, `_print_val` and `_princ_val`. A loop comparing two `make-string`
buffers renders both, every iteration; and a `make-string` value keeps the
mutable representation for its whole life, so nothing ever retires the cost.

Not wasm-only this time: the JVM renders through `_strv(Object)` at the same
sites (`.kb/adjustable-arrays.md`). Only the interpreter is free of it -- its
character vector IS a mutable `LispString`, so there is nothing to render.

## Measure before designing

Todo 342's numbers say what ONE render costs (~1 ns per character per call, so
~8 us for an 8192-character vector) but not what a real program pays, and that
is the whole question:

- a microbench bounds it -- `(string= a b)` in a loop over two `make-string`
  buffers, the length swept the way 342 swept it;
- a real program says whether it matters. jzon and the ironclad slice are the
  string-heavy ones, and `.kb/json.md`'s parse path is the natural candidate
  (`.todo/185` records that `(char s i)` is separately O(i) on the compile
  paths, which compounds with this on exactly those loops -- fix or measure
  them together, or the attribution is guesswork).

## Why it is not a drive-by change

A character vector is MUTABLE, so a cached rendering has to be invalidated by
every write that can reach it: `(setf (aref v i) c)` / `%row-major-aset`,
`replace`, `fill`, `vector-push` / `-pop` / `-push-extend`, `(setf (fill-pointer
v))`, `adjust-array` / `%array-become`, and a write through a displaced alias
or through the array a `:displaced-to` targets. Missing one is SILENT WRONG
OUTPUT rather than slow output, which is the trade to weigh against the
measurement above -- and it has to be weighed for the JVM and both WASM
backends at once, since the cache would live in the shared representation.

Non-goal: the predicate. `.todo/342` closed that, and `_charvec_p` answers
without touching the cache either way.

## Re-scope (measured 2026-08-28 under `.todo/559`)

`char`/`schar` are listed above as callers to memoize for. They should be
REMOVED from this todo's scope: measurement shows a per-character index wants no
rendered string at all, cached or otherwise. `(aref v j)` on a character vector
is already an O(1) element read and is 47x faster than `(char v j)` on the same
object (JVM, n=2048: 92 ms vs 4330 ms over 200 scans); `(elt v j)` shares
`char`'s cost. Those three sites are a rendering to DELETE, not to cache, and
`.todo/559` carries the plan. What is left here is the callers that genuinely
want the whole string -- `string=`/`string-equal`, the case and trim families,
`concatenate`, `write-string`, `read-from-string`, `intern`, `make-symbol`, and
the `_equal` / `_hash` / `_print_val` / `_princ_val` entries.

DONE (2026-08-31, `.todo/559` step 1): the deletion happened. `char`/`schar`/`elt`
sites read the element through `_charRef` (JVM) / `_str_char_ref` (WASM) and no
longer call the normalizer at all -- JVM n=2048 scan 1153 ms -> 8 ms, WASM
2224 ms -> 9 ms (`.kb/string-index-cost.md`, "The character vector escaped the
invariant"). The per-character rows of any earlier measurement in this file are
therefore obsolete; only the once-per-call renders of the whole-string callers
listed above remain as this item's scope, and any memoization now has fewer
invalidation points to reach (the index sites never read a cache).

## Re-measured 2026-08-31, after `.todo/596` (charvec DENSITY went up)

UPDATE, same day (596 round 2): the `position`/`find` half of the section below is
FIXED, not pending -- `buildPositionScan` no longer coerces to a list at all (a list
walks its cons cursor, a string/vector scans by index; `.kb/seq-coerce-runtime.md`).
The tokenizer row went baseline 1,018 -> 29 ms on the JVM and 3,221 -> 52 on wasm p1
-- ~35x UNDER the old floor -- and the interpreter's 1,501 -> 1,078. What this item
still owns: (a) the whole-string RENDERS (the case/trim families' input, string=,
intern, hash, print -- one render per call), (b) the wrap-out costs of the producer
flip (upcase +70/110%, the wots capture and read-line loop at ~0.3-1.5 us per call
absolute, the concatenate accumulator idiom), and (c) the OTHER `seqAsListForm`
callers that still coerce per call -- `count`, `reduce`, the `:key` mapcar,
`every`/`some`, `remove`/`substitute` -- the same defect `buildPositionScan` just
shed, which can take the same shape.

596 flipped the remaining big producers (concatenate 'string, the case family,
format nil, the string-stream capture, read-line) to answer character vectors,
so the whole-string callers above now see charvecs from ordinary code, not just
make-string buffers. Corpus-shaped rows (Apple M4 Max, each its own defun,
baseline -> flipped, ms):

- WASM p1, `(position #\Newline s :start k)` tokenizer over a
  concatenate-built ~4,600-char source, 200 lines x 200 reps:
  **2,986 -> 7,180**. Two stacked costs: `buildPositionScan` COERCES the whole
  sequence TO A LIST per call (O(n) conses per position call -- pre-existing,
  and the row's absolute floor), and the coerce walk over a CHARVEC runs ~2.5x
  an immutable string's on wasm (measured directly: `(coerce s 'list)` x2,000
  over 4,600 chars: charvec 333 ms vs string 134; `(position #\Z s)` 364 vs
  178. JVM: 68 vs 58, 190 vs 126 -- the JVM gap is small because `_charRef`'s
  element read is cheap there; the wasm walk pays `_charvec_p` + `_arr_get`
  calls per element).
- The concatenate ACCUMULATOR idiom `(setq acc (concatenate 'string acc p))`
  pays render + re-convert per append: 50 appends x 200 reps, JVM 2 -> 23 ms,
  wasm 5 -> 28 ms (~1.5 us per append absolute; not quadratic).
- `string-upcase` of a 1,000-char charvec x2,000: JVM 25 -> 43 ms, wasm
  18 -> 37 (one render in + one convert out per call).

So this item's scope now has two distinct fixes worth separating when it is
picked up: (a) the whole-string RENDER per call (the original memoization
question, invalidation rules and all), and (b) the charvec ELEMENT-WALK lanes
-- `coerce 'list` / the position scan could read elements through
`_str_char_ref`-style access without `_arr_get`'s displacement-walk overhead,
or `buildPositionScan` could stop coercing a string to a list per call at all
(the bigger absolute win, independent of representation). The escape hatch a
program has today is `(string x)`: it renders a charvec to an ordinary
immutable string once (it lowers through `princ-to-string`), after which every
scan runs at the string lane's speed.
