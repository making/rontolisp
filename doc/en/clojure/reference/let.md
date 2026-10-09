# let

`(let [binding...] body...)`

Evaluates the bindings in order, each seeing the ones before it (Clojure's `let` is
sequential; it lowers to `let*`), then evaluates the body and answers its last value.
Binding patterns destructure: a vector pattern binds positionally through the seq
view, a map pattern through `:keys`/`:syms`/`:strs` (also `:ns/keys`, `::keys`), explicit
locals, `:as` and `:or` defaults, and nested patterns recurse (see [Syntax](syntax.md) for the patterns);
malformed shapes are named refusals. A map pattern reads a seq as the map its keyword
arguments stand for ([seq-to-map-for-destructuring](seq-to-map-for-destructuring.md)), a
vector as itself.

```clojure
(println (let [x 1 y x] y))            ; 1
(println (let [[a & r] [1 2 3]] [a r])) ; [1 (2 3)]
(println (let [{:keys [a b]} {:a 1 :b 2}] [a b])) ; [1 2]
(println (let [[x & {:keys [a]}] [1 :a 2]] [x a])) ; [1 2]
```
