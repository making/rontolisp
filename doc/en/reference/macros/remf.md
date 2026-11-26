# remf

`(remf place indicator)`

Removes the first key/value pair matching `indicator` from the property list stored in `place`, updating `place` in place. It returns `t` if a matching pair was found and removed, or nil otherwise. Because it both mutates the plist and reports whether anything changed, inspect the place afterwards to see the result.

```lisp
(let ((p (list :a 1 :b 2))) (remf p :a) p) ; => (:B 2)
```
