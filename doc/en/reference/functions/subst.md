# subst

`(subst new old tree &key test test-not key)`

Non-destructive tree substitution: returns a copy of `tree` with every subtree or leaf matching `old` replaced by `new`. A match is decided by `(funcall test old (funcall key subtree))`; `:test` defaults to `eql` (so by default only atoms match) and `:key` defaults to the subtree itself. `:test-not` is the negated test, used when no `:test` is given. Unchanged subtrees are shared with the original, not copied. [`subst-if`](subst-if.md) matches by predicate instead of by item.

```lisp
(subst 'x 'a '(a (b a) c)) ; => (X (B X) C)
```

```lisp
(subst 9 '(m) '(f (m) g) :test #'equal) ; => (F 9 G)
```
