# let

`(let [binding...] body...)`

Evaluates the bindings in order, each seeing the ones before it (Clojure's `let` is
sequential; it lowers to `let*`), then evaluates the body and answers its last value.
Binding patterns destructure: a vector pattern binds positionally through the seq
view, a map pattern through `:keys`/`:syms`/`:strs`, explicit locals, `:as` and `:or`
defaults, and nested patterns recurse (see [Syntax](syntax.md) for the patterns);
malformed shapes are named refusals.

```clojure
(println (let [x 1 y x] y))            ; 1
(println (let [[a & r] [1 2 3]] [a r])) ; [1 (2 3)]
(println (let [{:keys [a b]} {:a 1 :b 2}] [a b])) ; [1 2]
```
