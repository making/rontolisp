# progv

`(progv symbols values body...)`

Evaluates `symbols` and `values` (each a list), then dynamically binds each symbol to the corresponding value for the duration of `body`, restoring the previous values on exit. When `values` is shorter than `symbols`, each extra symbol is bound with no value: for the extent of `body` it is unbound, so reading it signals an `unbound-variable` naming it and [`boundp`](../functions/boundp.md) answers `nil`, and an assignment gives the binding a value. On the compiled backends a standard variable (`*print-base*`, `*standard-output*`, ...) is bound to `nil` there instead. Unlike [`let`](let.md), the symbols are computed at runtime and need not have been proclaimed special. Returns the value of the last body form.

`progv` runs on all backends. The compilers lower it to a dispatch over the program's statically known special variables, so a symbol that is a special of the program gets a true dynamic binding; any other symbol is bound so that `symbol-value` and `boundp` see it for the extent. Since any special can be among the symbols, a compiled program that uses `progv` tests every read of a special variable for a missing value. On the compiled backends a WASM program using `progv` compiles in exception-handling mode.

```lisp
(progv '(a b) '(1 2) (list (symbol-value 'a) (symbol-value 'b))) ; => (1 2)
(defvar *x* 1)
(progv '(*x*) '() (boundp '*x*))                                  ; => NIL
(progv '(*x*) '() (handler-case *x* (unbound-variable () :unbound))) ; => :UNBOUND
```
