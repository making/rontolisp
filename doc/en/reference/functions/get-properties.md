# get-properties

`(get-properties plist indicator-list)`

Searches `plist` for the first property whose indicator is `eq` to one of `indicator-list`, and returns THREE values: the indicator found, its value, and the tail of `plist` starting at that indicator. All three are nil when nothing matches, which is how a stored nil is told apart from an absent property -- the difference from [`getf`](getf.md).

```lisp
(multiple-value-list (get-properties '(a 1 b 2) '(b))) ; => (B 2 (B 2))
```

```lisp
(multiple-value-list (get-properties '(a 1) '(z))) ; => (NIL NIL NIL)
```
