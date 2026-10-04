# c67. Clojure: a macro argument holding a regex literal or `#(...)` fails to expand

Difficulty: Medium

Measured 2026-10-04 (exec jar at 545dd4232, interpreter) vs oracle `clj` 1.12.6, with
the macro

```clojure
(defmacro t [form] `(println '~form "=>" ~form))
```

| call | oracle | ronto |
|---|---|---|
| `(t (re-find #"a" "cat"))` | `(re-find #"a" cat) => a` | macro t answered an unreadable value: an unreadable symbol: :C%PATTERN |
| `(t (map #(inc %) [1 2]))` | `(map (fn* [p1__139#] (inc p1__139#)) [1 2]) => (2 3)` | `the parameter vector of takes its bindings in a vector: \|inc\|` |

A macro body runs at lower time (`eval/ClojureMacroTime`); the argument datum is handed to it
and its answer read back. A regex literal datum comes back as its lowered `(:C%PATTERN ...)`
wrapper, which the read-back refuses; an anonymous-fn datum comes back in a shape whose
parameter vector the `fn` lowering does not take (the oracle's reader answers `(fn* [p1__N#]
...)`). Both break any test-style macro (`is`, a tracing `t`) over such an argument. Fix the
datum round trip for both reader forms, then pin both calls in `clojure-spec.yaml`.
