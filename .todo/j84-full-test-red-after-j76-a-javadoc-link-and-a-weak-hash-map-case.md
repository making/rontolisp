# j84. Full test red after j76: a Javadoc link and a weak-hash-map case

Difficulty: Low

Full `./mvnw test` on develop `81fa1469c` (2026-11-17): 22,724 tests, 2 failures.

1. `JavadocLinkTest.everyJavadocReferenceResolves`:
   `src/main/java/am/ik/rontolisp/eval/JavaLibrary.java:33` cites `{@link #process(List)}`;
   j76 changed the signature to `process(List, boolean)`. Deterministic.
2. `ClojureSpecE2eTest` case
   `a-weak-hash-map-atomic-reference-and-array-list-are-portable-where-the-host-is-not`, JVM leg:
   the third line expected `:w nil 2 false nil true`, got `nil nil 0 true nil true` -- the
   `WeakHashMap`'s entries were collected before the `remove` line (load average 56 at the
   time). The case (from `0a4c7ea82`) asserts on entries only weakly reachable, so it is
   flaky by construction; the earlier two full runs that day passed it. Make the case hold
   its keys strongly (bind them) or otherwise remove the dependence on GC timing, without
   losing what the case pins.
