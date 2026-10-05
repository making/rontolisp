# d27. A `subseq` bound past the int range wraps silently

Difficulty: Medium

`(subseq "hello" 0 b)` / `(subseq "hello" b)` with `b` = `(expt 2 32)` read at run time:

| backend | `(subseq "hello" 0 b)` | `(subseq "hello" b)` |
|---|---|---|
| SBCL | `type-error`, datum `(0 . 4294967296)` | `type-error`, datum `(4294967296)` |
| interpreter | `""` | `"hello"` |
| JVM | `""` | `"hello"` |
| wasm P1, component | `wasm trap: cast failure` (uncatchable) | same |

The interpreter's `Environment.requireIndex` casts the `long` with `(int)`, the JVM lanes
narrow with `l2i` (`JvmSubseqCompiler.unboxIndex`, `.unboxEnd`, `.emitResolveEnd`), so
`2^32` becomes `0` and `2^32 + 3` becomes `3`: a wrong answer, not a refusal. On wasm a
fixnum is an i31, so a bound of `2^30` or more is a bignum and `castI31GetS` traps. The
bounded string operators lower onto `subseq` and inherit it.

A bound outside `[0, (length seq)]` must reach the bounds check as itself: refuse with the
`type-error` `.kb/subseq-runtime.md` "Bounds check" describes, datum the given integer
(its report printing the integer, not its low 32 bits). Same unbox sites as d26 (a
non-integer bound); doing both together is natural. Pin a `2^32` and a `2^62` bound
in `SubseqBoundsFixture` and ci-spec `subseq-refuses-a-bad-range-in-every-representation`.
