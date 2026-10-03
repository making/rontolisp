# def

`(def name)` `(def name expr)`

Defines a top-level variable, lowering to a `setq` of the mangled name. Without a value
the var is unbound until a definition binds it, like the oracle's (see
[declare](declare.md) for the unbound root), and a bound root is left alone. Inside a body, `def` still sets the GLOBAL when the body runs --
it is not a local binding, which is what a `let` is for. The name is a `VARIABLE`, so
a head-position call to it is a `funcall` of the value cell.

The value evaluates against the OLD binding, so `(def p (memoize p))` after a
`(defn p ...)` captures the function cell. Top-level calls then hit the cache;
the `defun`'s own recursion stays a direct call -- use `^:dynamic` for a fully
memoized recursion (see [defn](defn.md)).

```clojure
(def dv 42)
(println dv)        ; 42
(def dv-no-val)
(println (bound? #'dv-no-val)) ; false

(defn dv-f [x] (* x 2))
(def dv-f (memoize dv-f))
(println [(dv-f 21) (dv-f 21)]) ; [42 42]
```
