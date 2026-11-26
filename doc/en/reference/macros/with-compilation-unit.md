# with-compilation-unit

`(with-compilation-unit (options...) body...)`

Evaluates the body forms in order and returns the last one's value -- a `progn`
around the body. The option list (`:override`, and any implementation
extension) is accepted and ignored.

A `progn` is the whole implementation, and a legitimate one. The options only
control how an enclosing unit's deferred-warning report is merged into this one,
and there is no [`compile-file`](../functions/compile-file.md) here to defer a
warning from: a rontolisp program is compiled whole in one pass, and a loaded
file is spliced into it. Libraries that wrap an operation sequence in one -- ASDF
wraps every build -- get the dynamic extent they ask for.

```lisp
(with-compilation-unit (:override t) 1 2 3) ; => 3
```
