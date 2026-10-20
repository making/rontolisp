# bound-fn

`(bound-fn name? [params] body...)`
`(bound-fn* f)`

`bound-fn` is `bound-fn*` over `fn`: the fdecl travels untouched, so a docstring
or an arity the `fn` lowering refuses fails where the oracle's macroexpansion
fails. `bound-fn*` takes the function and answers a function calling it with the
creation-time bindings pushed -- every thread-bound program `^:dynamic` var and
core special plus the lexically enclosing [`with-local-vars`](with-local-vars.md)
cells -- over the caller's own bindings, so a call on another thread sees the
captured values. A var bound nowhere at creation stays out of the map, like the
oracle's frame; a `bound-fn*` created under a `with-local-vars` extent entered
dynamically (through a called function) does not see that cell. As values the
two stay macros (`(def x bound-fn)` refuses): macro var values come later, so
their pinning stays there.

```clojure
(def ^:dynamic *loud* false)
(defn status [] (if *loud* :loud :quiet))
(println (status)) ; :quiet
(binding [*loud* true]
  (def loud-status (bound-fn [] (status))))
(println (loud-status)) ; :loud
(println (status)) ; :quiet
```
