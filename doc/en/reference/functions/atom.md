# atom

`(atom object)`

Returns `t` if `object` is not a cons cell, otherwise `nil`. Everything that is not a cons -- numbers, symbols, strings, characters, and `nil` -- is an atom, so `(atom nil)` is `t`. It is the exact complement of `consp`. Works in all three backends.

```lisp
(atom 1) ; => t
```

```lisp
(atom '(1 2)) ; => nil
```
