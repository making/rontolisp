# f77. `ClojureCoreLowering.callOf` passed the HotSpot huge-method limit and new `%clojure-*` functions lack namespace maps (f67 follow-up)

Difficulty: Medium

Found by the parent full suite (`./mvnw clean spring-javaformat:apply test`,
2026-10-13, tree `dc585b9cc`, 19613 run / 24 failures) after waves 1-3 landed:

- `HugeMethodTest.everyMethodOutsideTheRunOnceListIsSmallEnoughToCompile`:
  `am.ik.rontolisp.clojure.ClojureCoreLowering.callOf` is now over the 8000-bytecode
  `HugeMethodLimit` (f67 added the bit-op/seq/printing/namespace backlog arms to it).
  Split it the way the other listed methods are split (see the run-once list and
  `.kb/hot-path-method-size.md`).
- `ClojureLibraryTest.everyLibraryFunctionBuildingAKeywordOrSymbolFromAComputedSpellingMakesANamespaceMap`:
  every new f67 `%CLOJURE-*` function that builds a keyword or symbol from a computed
  spelling (e.g. `RONTOLISP::%CLOJURE-INTERN-V`) needs its namespace-map entry.

## Plan

1. Split `callOf` so `HugeMethodTest` passes; add the missing namespace maps.
2. Targeted runs: `HugeMethodTest`, `ClojureLibraryTest`, `ClojureSpecE2eTest`.
