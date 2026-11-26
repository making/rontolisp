# tree-equal

`(tree-equal tree-1 tree-2 &key test test-not)`

Returns `t` when the two cons trees have the same shape and every pair of corresponding leaves matches. A cons only ever matches a cons, so a tree and a leaf in the same position differ. Leaves compare with `:test` (default `eql`) or, with `:test-not`, match exactly where that function answers false. Two distinct strings with the same characters are not `eql`, so compare string leaves with `:test #'equal` or `#'string=`.

```lisp
(tree-equal '(1 (2 3)) '(1 (2 3))) ; => T
```

```lisp
(tree-equal '(1 (2)) '(1 2)) ; => NIL
```

```lisp
(tree-equal '("a" ("b")) '("A" ("B")) :test #'string-equal) ; => T
```
