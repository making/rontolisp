# def

`(def name)` `(def name expr)`

Defines a top-level variable, lowering to a `setq` of the mangled name (`nil` without
a value). Inside a body, `def` still sets the GLOBAL when the body runs --
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
(println dv-no-val) ; nil

(defn dv-f [x] (* x 2))
(def dv-f (memoize dv-f))
(println [(dv-f 21) (dv-f 21)]) ; [42 42]
```
