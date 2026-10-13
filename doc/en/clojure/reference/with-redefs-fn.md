# with-redefs-fn

`(with-redefs-fn binding-map func)`

`func` called with each var of the map bound to its value, restored after -- the
validations and notifications of `bindRoot`, like the oracle's. A var with a registered
root setter stores through its value cell, so a direct call sees the rebound root; any
other var swaps its getter, so only var-mediated reads see it, like the oracle's static
link. As a value two arguments.

```clojure
(def doc-eg-r 1)
(println (with-redefs-fn {(var doc-eg-r) 99} (fn [] doc-eg-r))) ; 99
```
