# apropos-list

`(apropos-list string &optional package)`

The accessible symbols whose name contains `string` as a substring, compared
case-insensitively, in `package` -- or, by default, in any registered package.
Each answer is spelled the way code spells it, like
[`find-all-symbols`](find-all-symbols.md).

```lisp
(apropos-list "CAR" :cl) ; => (CAR MAPCAR)
```
