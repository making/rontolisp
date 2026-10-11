# f52. JVM bridge: a `java:reify` left to run time reads no `AFunction.compare` for `:value` or several interfaces

Difficulty: Low

A `java:reify` implementing `java.util.Comparator`, ending in `:functional` and
`:java-false`, reads its `compare` function's answer as Clojure's `AFunction.compare`
(`JavaImplementation.readsComparison`: any interface list holding `Comparator`, stand-in or
not). The interpreter and a compiled program's generated class do; the bridge's
`javaReify` -- a reify left to run time, e.g. by a computed `:through` -- does only for a
lone `Comparator` without `:value` (`comparison` needs `interfaces.length == 1`, and the
`standIn` path passes `false`). Measured 2026-10-11 (`compare` of `(lambda (a b) (< a b))`
on 1 2, `through` applying the function):

| reify | interpreter | JVM, `:through #'through` | JVM, `:through *g*` (bridge) |
|---|---|---|---|
| `"java.util.Comparator" :value 'v` | `-1` | `-1` | `java:reify: cannot return T as int from java.util.Comparator.compare` |
| `'("java.util.Comparator" "java.lang.Runnable")` | `-1` | `-1` | the same |

## Plan

1. A row per case in a program both `JavaInteropTest` and `JvmJavaInteropCompilerTest` run,
   the bridge's through a computed `:through`.
2. `javaReify`: `comparison` as `readsComparison` rules (the markers and any interface
   `Comparator`), handed to `standIn` too; a `JavaBridgeTemplateParityTest` row over the
   interface lists.
