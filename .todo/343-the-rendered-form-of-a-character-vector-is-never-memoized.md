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
