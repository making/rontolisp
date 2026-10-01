# def

`(def name)` `(def name expr)`

Defines a top-level variable, lowering to a `setq` of the mangled name (`nil` without
a value). Inside a body, `def` still sets the GLOBAL when the body runs --
it is not a local binding, which is what a `let` is for. The name is a `VARIABLE`, so
a head-position call to it is a `funcall` of the value cell.

```clojure
(def dv 42)
(println dv)        ; 42
(def dv-no-val)
(println dv-no-val) ; nil
```
