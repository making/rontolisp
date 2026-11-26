# with-bindings

`(with-bindings binding-map body...)`
`(with-bindings* binding-map f args...)`

Binds each var of the map -- a var object, `#'*x*`, as the key -- to its value around
the body (`with-bindings`) or a call of `f` on the arguments (`with-bindings*`), with
dynamic extent, like [binding](binding.md): the same thread-scoped binding, its
binding-depth counter included, so `set!` and `thread-bound?` see it. Only `^:dynamic`
vars and the `clojure.core` specials bind; any other var is the oracle's
`IllegalStateException` (`Can't dynamically bind non-dynamic var: user/x`). The map may
be computed, since the var objects carry how to bind themselves: in a program naming
`with-bindings`, `with-bindings*` or `push-thread-bindings`, every dynamic var's site
does. `with-bindings*` also names a function value. See also
[push-thread-bindings](push-thread-bindings.md).

```clojure
(def ^:dynamic *indent* 0)
(defn show [] (str "indent " *indent*))
(println (with-bindings {#'*indent* 2} (show))) ; indent 2
(println (with-bindings* {#'*indent* 4} show)) ; indent 4
(println (show)) ; indent 0
```
