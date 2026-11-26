# package-used-by-list

`(package-used-by-list package)`

Every package whose use list names `package`, as the keywords [`find-package`](find-package.md) answers — the inverse of [`package-use-list`](package-use-list.md). An unknown designator signals.

The interpreter reads its live registry; the compiled backends answer from the same compile-time table `package-use-list` uses.

```lisp
(defpackage #:provider (:use #:cl) (:export #:thing))
(defpackage #:consumer (:use #:cl #:provider))
(package-used-by-list '#:provider) ; => (:CONSUMER)
```
