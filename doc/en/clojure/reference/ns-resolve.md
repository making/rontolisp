# ns-resolve

`(ns-resolve ns sym)` / `(ns-resolve ns env sym)`

`clojure.core/ns-resolve`: what [resolve](resolve.md) of the symbol answers in the namespace --
the var, the class a class name loads, or nil. A quoted symbol resolves while the program
lowers, in a namespace given as a quoted name, `(find-ns 'n)`, `(the-ns 'n)` or `*ns*` (the
namespace the call lowers in). A namespace that does not exist fails like the oracle's:
`(find-ns 'n)`'s nil a `NullPointerException`, a name `No namespace: n found`. An environment
holding the symbol (a macro's `&env` holding a local of its name) answers nil. A computed
namespace or symbol resolves inside a macro body only; when the program runs it is refused,
like `resolve` of one (a nil namespace is the `NullPointerException` first). As a value a
function of two or three arguments.

```clojure
(defn helper [] 1)
(println (ns-resolve 'user 'helper) (ns-resolve 'clojure.core 'inc) (ns-resolve 'user 'nowhere)) ; #'user/helper #'clojure.core/inc nil
(println (try (ns-resolve (find-ns 'absent.lib) 'x) (catch Exception e :no-such-namespace))) ; :no-such-namespace
```
