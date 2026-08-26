# 535. A cons cell is an `Object[]`, so a list walk pays a cast per step and a third more cache footprint

Difficulty: High (the cons representation is load-bearing across the whole JVM
backend, and it is shared with function references -- `.kb/core-representation.md`,
"consp in JVM")

Spike sources: the directory of the same name. Found in the same 2026-08-26
comparison as `.todo/534`. `.todo/520` made the JVM
list walk JIT-compilable (15.99 -> 3.61 s wall clock on `.todo/517`'s `cdr` row);
this is what is left underneath that, and it is representation, not JIT.

## The defect

A cons cell is `Object[]{car, cdr}` and a function reference is also an
`Object[]`, told apart by `arr[0] instanceof Integer`. So every `cdr` step is a
checked array access rather than a field read:

```
_nthcdr:  0: iload 0 / ifle 25          ; n exhausted?
          5: aload 1 / ifnull 25        ; end of list?
         10: checkcast [Ljava/lang/Object;   <- per step
         15: iconst_1 / aaload          <- per step, plus the array bounds check
         19: iinc 0, -1 / goto 0
```

Two costs ride on that, and the measurement separates them.

## The measurement (compute only, ns per `cdr` step, 10^9 steps either way)

| list length | rontolisp JVM | Java `record Cons(long car, Cons cdr)` |
| --- | --- | --- |
| 1000 cells | 3.28 | 1.51 |
| 100 cells | 2.07 | -- (C2 hoists the invariant walk; unusable as a baseline) |

SBCL walks the 1000-cell list at 1.12 ns/step -- it is the one row where SBCL
beats hand-written Java, because its cons is 16 bytes against the JVM's object
header plus fields.

- **A fixed per-step cost of about 2 ns** survives when the whole list fits in
  L1 (the 100-cell row), against roughly 1.2-1.5 ns for a dependent L1 load.
  That is the cast and the bounds check.
- **The rest scales with footprint**: 3.28 vs 2.07 ns/step for the same 10^9
  steps over 10x the cells. By layout arithmetic an `Object[2]` is 32 bytes
  (16-byte header + 4-byte length + two 4-byte compressed refs, aligned) where a
  two-field object is 24, so a 1000-cell list is 32 KB against 24 KB and stops
  fitting where the Java shape still fits. Confirm the two sizes before acting
  on this -- it is computed here, not measured.

The `car` is boxed on top of that (`nth` returns a `Long`), which the boxed-cons
Java variant isolates: `record Cons(Long car, Cons cdr)` runs at 2.05 ns/step,
so boxing alone does not explain the gap to 3.28.

## What to build

The candidate is a dedicated two-field cons class on the JVM backend, which
removes the cast (a monomorphic `instanceof Cons` replaces the
`arr[0] instanceof Integer` disambiguation, and function references keep the
`Object[]`), the bounds check, and 8 bytes per cell. It touches everything that
builds, tests or destructures a cons in emitted code, plus the travelling
runtime rules (`.kb/jvm-export.md`) if the class has to travel with a compiled
artifact -- which is why this is High and why the cheaper half should be
measured first:

1. A/B the cast alone. If Pass 2 knows a value is a cons, `checkcast` is
   redundant on all but the first step of a walk; hoisting it out of `_nthcdr`
   and the other walk emitters is a small change that prices the 2 ns.
2. Only then decide whether the 8 bytes per cell are worth the representation
   change.

Do not start with the class swap.

## Acceptance

- The `cdr` row's compute-only cost within 2x of the hand-written Java cons
  walk (<= 3.0 s for 10^9 steps on this machine; it is 3.28 today, against
  Java's 1.51 and SBCL's 1.12).
- `consp`, `functionp` and the function-reference path stay correct and
  output-identical on all four backends -- `.kb/core-representation.md` updated
  in the same commit if the shared `Object[]` disambiguation changes.
- No regression on `.todo/517`'s other three rows, and no growth in the
  `JvmOsrBackedgeCorpusTest` count.
