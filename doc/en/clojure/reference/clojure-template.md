# clojure.template

Expression templates: substituting values for the argument symbols of an expression. Require
`clojure.template` to use them; it is Clojure source written for rontolisp from the documented
behavior of Clojure's namespace, and runs the same on every backend.

| Var | Behavior |
|---|---|
| `apply-template` | `(apply-template argv expr values)`: `expr` with each symbol of `argv` replaced, at any depth, by the value at the same position in `values`; every member of `argv` must be a symbol |
| `do-template` | `(do-template argv expr & values)`: a `do` block holding one copy of `expr` per group of values, each group as long as `argv` (a short last group is dropped) |

```clojure
(require '[clojure.template :as t])
(t/apply-template '[x y] '(+ x (* y y)) '[1 2])
; => (+ 1 (* 2 2))
(macroexpand '(t/do-template [x y] (+ y x) 2 4 3 5))
; => (do (+ 4 2) (+ 5 3))
```

```clojure
(require '[clojure.template :refer [do-template]])
(do-template [op n] (println (op n 10)) + 1 - 2 * 3)
```

```
11
-8
30
```
