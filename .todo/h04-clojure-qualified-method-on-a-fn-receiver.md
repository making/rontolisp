# Clojure: a qualified instance method on a fn receiver (`Runnable/.run`) fails

Difficulty: Low

```clojure
(println (Runnable/.run (fn [] (println 1))) (java.util.concurrent.Callable/.call (fn [] 3)))
;; clj 1.12.6: 1, then nil 3
;; rontolisp (interpreter, 2026-10-29): java:call expects a java object as the first argument, got #<lambda>
```

`(.run f)` / `(.call f)` answer (the value-receiver path); the 1.12 `Class/.m` spelling
(`ClojureInteropLowering.memberCall` -> `instanceCallLoweredWithClass`) sends the fn to
`java:call` instead. Route a value receiver of `Class/.m` through the same arms `(.m x)`
takes, on all four backends, with a clojure-spec case.
