# g03. HashMap.putAll of a Lisp map aborts the clojure-spec corpus on host legs

Difficulty: Medium

Found while closing g02 (2026-10-13): the full `clojure-spec.yaml` corpus run
aborts on the INTERPRETER and JVM legs at the
`put-all-stores-into-the-hash-map-where-the-host-is-not` case with

```text
error calling java.util.HashMap.putAll: java.lang.IllegalArgumentException:
No matching method putAll found taking 1 args for java.util.HashMap
```

so every later case never runs there (2 failures: the two host-leg execution
containers; both wasm legs stay green). The same calls succeed in isolation on
both backends (g02 probe, clj-style lets over `(java.util.HashMap.)` with an
empty, a uniform, a mixed-keyword-and-string, and a host map: sizes 0, 1, 2, 1
on both), so the failure is order/state-dependent -- the corpus case binds
`(java.util.HashMap. 1024 0.25)` and the first failing call is
`(.putAll {:a 1 "s" 2})` on it. Suspect area: f90's coercion rewrite
(`b352e51df`, same day) around the kindless-argument path (a hash table has no
kind; `JavaInterop`/`JavaOverloads.singleFixed` forced conversion,
`CHOICES` memos) interacting with corpus order.

## Plan

1. Reproduce in-corpus (the case alone passes; bisect the prefix that poisons
   it -- receiver construction `(HashMap. 1024 0.25)` vs earlier map-interop
   memos).
2. Fix the matching/conversion with the three-copy rule
   (`.kb/java-interop.md`: interpreter, bridge template, direct sites, plus
   `JavaBridgeTemplateParityTest`).
3. Re-run the full corpus on all four backends.
