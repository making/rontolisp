# destructuring-bind

`(destructuring-bind pattern form body...)`

Binds the variables of `pattern` to the corresponding parts of the value of `form` and evaluates the body. The pattern is a macro-style lambda list: `&optional` (with defaults and supplied-p), `&rest`/`&body`, `&key` (with defaults and supplied-p), and `&aux` are supported -- also inside nested patterns. A pattern can stand in a required position, after `&rest`/`&body`, as the variable of an `&optional` spec (`((a b) default supplied-p)`, the default destructured like a supplied value), and as the variable of a keyword spec (`((:k (a b)) default)`), but not under `&aux`; `()` is the pattern that takes nothing. A dotted tail is shorthand for `&rest` (`((a &rest b) . rest)` binds `rest` to everything past the first element). `&whole` (as the pattern's first element) binds its variable to the whole source list; `&environment` is not supported. An element past the pattern -- at a level with neither a dotted tail, `&rest`/`&body` nor `&key` to take it -- signals a `program-error`, as does a required element the list runs out before, and an undeclared keyword under `&key` unless `&allow-other-keys` is given.

```lisp
(destructuring-bind (a (b c) &optional (d 10)) '(1 (2 3))
  (list a b c d)) ; => (1 2 3 10)
```

```lisp
(destructuring-bind (name &key (size 1) color) '(box :color red)
  (list name size color)) ; => (BOX 1 RED)
```

```lisp
(destructuring-bind (a &optional ((b c) '(1 2)) (d b)) '(0)
  (list a b c d)) ; => (0 1 2 1)
```

```lisp
(handler-case (destructuring-bind (a b) '(1 2 3) (list a b))
  (program-error () :too-many)) ; => :TOO-MANY
```
