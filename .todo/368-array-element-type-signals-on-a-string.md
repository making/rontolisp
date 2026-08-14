# `array-element-type` signals on a string

Difficulty: Low

```lisp
(array-element-type "abc")
;; CL:        CHARACTER  (a string IS a vector, and a vector IS an array)
;; rontolisp: ARRAY-ELEMENT-TYPE expects an array, got "abc"
```

The other array predicates already accept a string (`vectorp`, `length`, `aref`,
`elt`), so this one arm is inconsistent rather than a missing feature. Whatever
the check is (an "is this a vector object" test that does not admit the string
representation), it should answer `CHARACTER` for a string -- rontolisp has one
character type (`.kb/uiop.md`), so there is no discrimination to make.

Check the same argument test on `array-dimensions`/`array-rank`/
`array-total-size`/`adjustable-array-p`/`array-has-fill-pointer-p` while there,
and pin one case per backend plus a `ci-spec.yaml` line.

**Found 2026-08-14 writing `.todo/354`**: `uiop:base-string-p` is
`(eq 'base-char (array-element-type string))` upstream. It answers `t` here for a
different and correct reason (one character type, so upstream's own `(and)` arm
applies) and does not call `array-element-type` at all -- but the reason it
cannot is this bug, not the type lattice.
