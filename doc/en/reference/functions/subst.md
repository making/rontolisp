# subst

`(subst new old tree &key test key)`

Non-destructive tree substitution: returns a copy of `tree` with every subtree or leaf matching `old` replaced by `new`. A match is decided by `(funcall test old (funcall key subtree))`; `:test` defaults to `eql` (so by default only atoms match) and `:key` defaults to the subtree itself. Unchanged subtrees are shared with the original, not copied.

```lisp
(subst 'x 'a '(a (b a) c)) ; => (x (b x) c)
```

```lisp
(subst 9 '(m) '(f (m) g) :test #'equal) ; => (f 9 g)
```
