# resolve

`(resolve sym)`

Answers the var a symbol names in the current namespace, the class a class name spells,
or `nil`. A quoted symbol resolves while the program lowers, to the var `#'name` lowers
to: a program var, a `clojure.core` var this front end implements, or a core macro. Any
other name answers `nil`, a core var this front end lacks included, so a library that
chooses code by the `clojure.core` it runs on (a `compile-if` over `resolve`) chooses the
code that lowers here. A computed symbol resolves only in a macro body; at run time it
throws an `UnsupportedOperationException`. `(resolve env sym)` is refused. Also names a
function value.

```clojure
(defn greet [] "hi")
(println (resolve 'greet) (resolve 'inc) (resolve 'nowhere))
```

```
#'user/greet #'clojure.core/inc nil
```
