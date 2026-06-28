# revappend

`(revappend list tail)`

Returns a new list consisting of the elements of `list` in reverse order followed by `tail`. It is equivalent to `(append (reverse list) tail)` but done in one pass. `list` is copied (not modified); `tail` is shared with the result, becoming its final segment.

```lisp
(revappend '(1 2 3) '(4 5)) ; => (3 2 1 4 5)
```
