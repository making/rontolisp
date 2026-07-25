# symbol-package

`(symbol-package symbol)`

Lite: returns the same keyword shape [`find-package`](find-package.md) returns, so the two are `eq`-comparable: `:keyword` for a keyword, the qualifier of a package-qualified symbol, `:cl` for a standard symbol, `:cl-user` otherwise, and `nil` for an uninterned (`#:`) symbol. The compiled backends have no package registry at run time and cannot tell `cl` from `cl-user`: they answer `:cl-user` for both.

```lisp
(symbol-package :foo) ; => :KEYWORD
```
