# mapl

`(mapl function list)`

Like [`maplist`](maplist.md), but `function` is applied to the successive cdrs (tails) of `list` for its side effects only, and the original `list` is returned rather than a list of the results. Single-list form only.

The argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error.

```lisp
(mapl #'identity '(1 2 3)) ; => (1 2 3)
```
