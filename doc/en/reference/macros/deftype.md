# deftype

`(deftype name lambda-list body...)`

Accepted as a parsed no-op returning `nil`: there is no type-definition registry, so the defined name is NOT usable in later `check-type`/`typecase` tests (which fail naming the unsupported specifier). This supports the common library shape where a `deftype`d name only appears inside (equally no-op) `declaim`/`declare` declarations.

```lisp
(deftype array-index (&optional (length 1000)) `(integer 0 (,length))) ; => nil
```
