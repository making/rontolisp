# eval

`(eval form)`

Evaluates a form given as data. It runs only while the program lowers: in a macro body,
or in a function the body calls, the form lowers in the namespace of the expansion,
without the call site's locals, and evaluates over the definitions above the call site.
A compiled program carries no lowering, so `eval` at run time throws an
`UnsupportedOperationException`. Also names a function value.

```clojure
(defmacro compile-if [test then else] (if (eval test) then else))
(def limit 3)
(println (compile-if (> limit 2) :big :small))
(println (compile-if (resolve 'clojure.core/inc) :has :lacks))
```

```
:big
:has
```
