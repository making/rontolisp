# mod and rem of a ratio diverge across the four backends

Difficulty: Medium

`(mod 7/2 3)` answers three different things:

```lisp
(let ((r 7/2)) (print (mod r 3)) (print (rem r 3)))
```

| backend | answer |
| --- | --- |
| interpreter | `Unhandled condition: Expected integer, got: 7/2` |
| JVM | `ClassCastException: [Ljava.math.BigInteger; cannot be cast to java.math.BigInteger` |
| WASM (both) | `1/2`, `1/2` |

ANSI defines `mod`/`rem` on any REAL, so the WASM answer is the right one and
the other two are gaps: `LispEvaluator`'s integer check is too narrow, and the
JVM's `_mod`/`_rem` fall through their `Long` fast path into `_big`, which
cannot cast a ratio's `BigInteger[2]` representation.

Found beside the float arm, which was the same shape of hole and is fixed:
`.kb/jvm-double-arithmetic.md`, "`_mod` and `_rem` over floats". The fix is the
same one level up -- a ratio prologue on both helpers (`_ratnum`/`_ratden`
cross-multiplication, then `_rat`), and the matching arm in the interpreter --
plus a ci-spec case pinning all four backends, which is what would have caught
this.

`floor`/`truncate`/`ceiling` of a ratio already work on every backend, so the
divergence is `mod`/`rem` only.
