# getf

`(getf plist indicator)`

Returns the value following `indicator` in a property list (a flat list of alternating indicators and values), or `nil` if the indicator is absent. It is the partner of `remf`. This implementation takes exactly two arguments -- there is no `&optional default`, so a missing indicator always yields `nil`. `(setf (getf ...) value)` is not supported either; use `remf` to delete a property.

```lisp
(getf '(:a 1 :b 2) :b) ; => 2
```
