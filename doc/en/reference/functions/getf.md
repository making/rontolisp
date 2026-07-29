# getf

`(getf plist indicator &optional default)`

Returns the value following `indicator` in a property list (a flat list of alternating indicators and values). If the indicator is absent, `default` is returned -- `nil` when it is omitted. `getf` is a function, so `default` is evaluated whether or not the indicator is found. An indicator that IS present but whose value is `nil` yields `nil`, not the default. It is the partner of `remf`. `(setf (getf ...) value)` is not supported; use `remf` to delete a property.

```lisp
(getf '(:a 1 :b 2) :b) ; => 2
```

```lisp
(getf '(:a 1) :on-delete :restrict) ; => :RESTRICT
```
