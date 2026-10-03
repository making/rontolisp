# c18. Clojure call-site loops send a local bound to a function through the IFn dispatcher

Difficulty: Low

The spliced runtime workers take a real function and a local bound to one passes as
itself (`ClojureBindingLowering.realFnValue` / `holdsRealFun`, `.kb/clojure-frontend.md`,
"The IFn dispatcher stays at the call site"). The inline loops built through
`ClojureLowering.callFun` / `applyFun` see only the lowered symbol, not `isDirectVar`, so a
`let`-bound function still reaches `%clojure-call` there. Measured 2026-10-03 (raw wasm,
default optimize):

| program | dispatcher | bytes |
|---|---|---|
| `(let [f odd?] (println (every? f [1])))` | yes | 61,251 |
| `(println (every? odd? [1]))` | no | 34,113 |
| `(let [f inc] (println (sort-by f [2 1])))` | yes | 71,010 |
| `(let [f inc] (println ((comp f dec) 1)))` | yes | 60,886 |
| `(let [f odd?] (println (map f [1])))` | no | 44,122 |

## Plan

- Let the `callFun` callers that start from a datum (`every?` `some` `take-while`
  `drop-while` `sort` `sort-by` `group-by` `merge-with` `comp` `partial` `complement`
  `memoize` `trampoline`; grep `callFun(`/`applyFun(` over `fnValue`) decide directness
  with `holdsRealFun(ctx, datum, fun)` instead of `yieldsFun(fun)`. Never consult the
  scope from a generated parameter symbol: a value lambda's `c%NAME-fn` parameter can
  spell a user local's name.

## Pin

- `ClojureLoweringTest`: each program above lowers without `%CLOJURE-CALL`; a `def` of a
  set used the same way keeps it.
