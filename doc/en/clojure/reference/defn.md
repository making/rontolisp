# defn

`(defn name docstring? [params...] body...)`
`(defn name docstring? ([params...] body...)+`

Defines a function. The multi-arity spelling is one clause per arity plus a dispatch on
the argument count; a single variadic clause (`&` rest) takes any count past its fixed
parameters, and any other count signals (`wrong number of arguments passed to: f`). At
most one variadic clause and one clause per arity. Parameters destructure, vector and map
patterns alike; a map pattern after `&` takes keyword arguments, as pairs or one map
([seq-to-map-for-destructuring](seq-to-map-for-destructuring.md)). The name lowers to a direct call, so recursion is a call, not a value
lookup; a head-position use of a *value* binding (a `def`, a parameter) is the funcall
instead. A `recur` in the body jumps back to the enclosing clause with new argument
values.

A `^:dynamic` name holds its function in the var instead: the definition keeps its
direct call shape (so `recur` still jumps straight to it), but calls go through the
var's value, so `binding` rebinds them with dynamic extent (see
[binding](binding.md)). Any other `defn` without the marker stays refused by
`binding`, like the oracle's non-dynamic error.

A definition may use a name defined below it: the file is pre-scanned for every top-level
`def`/`defn` (and `declare`) name. Inside a body, `defn` works only in statement
position, and a multi-arity one only at the top level.

Redefining a `defn` with `def` captures the function: `(def p (memoize p))`
evaluates its value against the OLD `FUNCTION` binding, so top-level calls hit
the cache while the `defun`'s own recursion stays direct.

Redefining a name with another `defn` gives each definition its own function: the
call sites below each definition call the newest, while a `(def g f)` between two
definitions keeps the older one (`(g)` answers the old value, `(f)` the new one).
Each namespace versions its own names. A function defined above a redefinition
still calls the older definition; only `^:dynamic` names (whose calls go through
the var) see the newest from every call site.

```clojure
(defn fact [n]
  (if (< n 2) 1 (* n (fact (- n 1)))))
(println (fact 5)) ; 120

(defn area
  ([] 0)
  ([w h] (* w h)))
(println (area 3 4)) ; 12

(defn resty [x & xs] xs)
(println (resty 1 2 3)) ; (2 3)
```
