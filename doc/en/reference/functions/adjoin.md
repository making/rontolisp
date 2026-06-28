# adjoin

`(adjoin item list)`

Returns `list` unchanged if `item` is already a member (compared with `eql`); otherwise returns a new list with `item` prepended. It is the non-destructive way to add an element to a set without creating duplicates.

```lisp
(adjoin 1 '(2 3)) ; => (1 2 3)
```
