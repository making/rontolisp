# symbol-package

`(symbol-package symbol)`

Lite: returns the same keyword shape [`find-package`](find-package.md) returns, so the two are `eq`-comparable: `:keyword` for a keyword, the qualifier of a package-qualified symbol, `:cl` for a standard symbol (`t`, `nil` and the exported-only standard names included), `:cl-user` otherwise, and `nil` for an uninterned (`#:`) symbol -- or for one [`unintern`](unintern.md) removed from its home package. The compiled backends have no package registry at run time and cannot tell `cl` from `cl-user`: they answer `:cl-user` for both, and keep the qualifier of an uninterned symbol.

```lisp
(symbol-package :foo) ; => :KEYWORD
```

```lisp
(symbol-package t) ; => :CL
```
