# equal

`(equal x y)`

Structural equality: cons cells are compared recursively by their car and cdr, and strings are compared character by character; for everything else it behaves like `eql`. Returns `t` or `nil`. Works in all three backends.

```lisp
(equal '(1 2 (3)) '(1 2 (3))) ; => T
```

```lisp
(equal "abc" "abc") ; => T
```
