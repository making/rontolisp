# `sort` is an insertion sort, so it is quadratic on every backend

Difficulty: Medium

`bench-report/` found it: `sort` over a 400,000-element vector is the one cell
in the whole table that TIMES OUT on all three rontolisp backends while SBCL
does it in 164 ms and ABCL in about two seconds.

Measured on the JVM backend, `(sort <vector of n integers> #'<)`:

| n | ms |
| ---: | ---: |
| 12,500 | 917 |
| 25,000 | 3,470 |
| 50,000 | 14,534 |

Doubling n quadruples the time. It is O(n^2), and at n = 400,000 that is hours.

## Where

`LispEvaluator.sortValues` is an insertion sort, and says so:

> Implemented with insertion sort calling the predicate, which keeps the
> ordering self-consistent.

`stable-sort` on the next screen does NOT do this -- it decorates into a
`java.util.List` and calls the JDK's merge sort, which is O(n log n) and already
stable. So the fast path exists in the same file; `sort` just does not take it.
Check whether the two compiled backends reach the same helper or carry their own
copy (`JvmExprCompiler` / `WasmExprCompiler` both dispatch `LispNames.SORT`) --
the timing says all three are quadratic, so whatever they call has to be fixed
together.

## What "self-consistent" was protecting

ANSI leaves `sort` unstable, so the freedom is real, but the reason given for
insertion sort is that it only ever asks the predicate `(pred a b)` and never
depends on a total order the predicate does not actually provide. A merge sort
asks the same question, so the property survives the change -- what does NOT
survive is the exact permutation of EQUAL elements, which is precisely what ANSI
declines to specify for `sort`. Anything in the test suite that pinned the
current permutation is pinning something the standard does not promise, and
should be rewritten to sort by a total key or to use `stable-sort`.

## Done when

- `sort` is O(n log n) on the interpreter, the JVM backend and both WASM
  backends, from one shared decision (the topic file names the pinning test, per
  the cross-backend rule in `CLAUDE.md`).
- `bench-report/results/benchmarks.md`'s `sort` row is a number in every column
  instead of three `timeout`s.
- A `.kb` file records the invariant: `sort` and `stable-sort` differ in the
  permutation of equal elements and in nothing else, least of all in complexity.
