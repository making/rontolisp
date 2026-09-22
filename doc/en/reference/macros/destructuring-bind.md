# destructuring-bind

`(destructuring-bind pattern form body...)`

Binds the variables of `pattern` to the corresponding parts of the value of `form` and evaluates the body. The pattern is a macro-style lambda list: patterns nest in required positions, and `&optional` (with defaults and supplied-p), `&rest`/`&body`, `&key` (with defaults and supplied-p), and `&aux` are supported -- also inside nested patterns. A dotted tail is shorthand for `&rest` (`((a &rest b) . rest)` binds `rest` to everything past the first element). `&whole` (as the pattern's first element) binds its variable to the whole source list; `&environment` is not supported. An element past the pattern -- at a level with neither a dotted tail, `&rest`/`&body` nor `&key` to take it -- signals a `program-error`, as does an undeclared keyword under `&key` unless `&allow-other-keys` is given. A missing position still binds to nil (no error).

```lisp
(destructuring-bind (a (b c) &optional (d 10)) '(1 (2 3))
  (list a b c d)) ; => (1 2 3 10)
```

```lisp
(destructuring-bind (name &key (size 1) color) '(box :color red)
  (list name size color)) ; => (BOX 1 RED)
```

```lisp
(handler-case (destructuring-bind (a b) '(1 2 3) (list a b))
  (program-error () :too-many)) ; => :TOO-MANY
```
