# destructuring-bind

`(destructuring-bind pattern form body...)`

Binds the variables of `pattern` to the corresponding parts of the value of `form` and evaluates the body. The pattern is a macro-style lambda list: patterns nest in required positions, and `&optional` (with defaults and supplied-p), `&rest`/`&body`, `&key` (with defaults and supplied-p), and `&aux` are supported -- also inside nested patterns. `&whole` and `&environment` are not supported. Matching is lenient: a missing position binds to nil and surplus elements are ignored (no mismatch error); only an undeclared keyword under `&key` signals, unless `&allow-other-keys` is given.

```lisp
(destructuring-bind (a (b c) &optional (d 10)) '(1 (2 3))
  (list a b c d)) ; => (1 2 3 10)
```

```lisp
(destructuring-bind (name &key (size 1) color) '(box :color red)
  (list name size color)) ; => (BOX 1 RED)
```
