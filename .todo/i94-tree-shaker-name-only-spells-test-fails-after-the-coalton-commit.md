# i94. `WasmTreeShakerTest#aNameOnlyAFunctionNothingReachesSpellsHasNoCaseAndNoRow` fails since the coalton commit

Difficulty: Medium

`./mvnw test -Dtest=WasmTreeShakerTest` fails on develop (measured 2026-11-06 on `3b10dfd4b`,
deterministic):

```
aNameOnlyAFunctionNothingReachesSpellsHasNoCaseAndNoRow:654
expected: 875
 but was: 870
```

The test compiles a reactor with and without `defs` (`given`, plus `nobody` that only names
`'given`) at `OptimizeLevel.SIZE` and pins the two sizes equal. With `defs` the module is now 5
bytes smaller than without. It passed on `c9aa7fb78`; the only non-merge commit between that
and the first failing tip is `2f9bfd82e` ("Compile coalton's whole library on the compile
paths": literal objects, load-time-value vector, shared literal units, top-level splitting).

Find what makes the two programs lower differently (a literal or a top-level unit the extra
forms change, not the dead `given` itself), and fix the cause rather than the expected number.
If the measurement shows the pin is the wrong invariant, say so and rewrite the assertion
around what holds.
