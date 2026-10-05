# symbol-package

`(symbol-package symbol)`

Lite: returns the same keyword shape [`find-package`](find-package.md) returns, so the two are `eq`-comparable: `:keyword` for a keyword, the qualifier of a package-qualified symbol, `:cl` for a standard symbol (`t`, `nil` and the exported-only standard names included), `:cl-user` otherwise, and `nil` for an uninterned (`#:`) symbol -- or for one [`unintern`](unintern.md) removed from its home package. The compiled backends read the home off the symbol's spelling, telling a standard symbol from a `cl-user` one by a table of the standard names the program then carries; they keep the home of a symbol `unintern` removed.

```lisp
(symbol-package :foo) ; => :KEYWORD
```

```lisp
(symbol-package t) ; => :CL
```

```lisp
(symbol-package (read-from-string "mapcar")) ; => :CL
```
