# unintern

`(unintern symbol &optional package)`

Removes `symbol` from the member table of `package` (the current package by default): [`find-symbol`](find-symbol.md) stops answering it as the package's own, and when the package was the symbol's home, [`symbol-package`](symbol-package.md) answers `nil` from then on. An imported symbol loses only its redirect; either way it stops being external and shadowing. Returns `t` when the symbol was present, `nil` when it was not -- an inherited symbol, or one homed elsewhere and never imported. Uninterning a shadowing symbol that hid two different inherited symbols of the same name would leave a name conflict, so that signals a `package-error` and changes nothing.

Deviation from Common Lisp: a symbol is its spelling here, so interning the same name into the package again homes the old symbol again (Common Lisp mints a distinct symbol). On the compiled backends the removal is recorded for the packages the program creates with [`make-package`](make-package.md) (a read/compile-time package is frozen there, and `unintern` answers `nil`), and `symbol-package` keeps reading the qualifier off the spelling.

```lisp
(make-package :un-demo :use nil)
(defvar *un-sym* (intern "TEMP" :un-demo))
(unintern *un-sym* :un-demo) ; => T
(symbol-package *un-sym*) ; => NIL
(find-symbol "TEMP" :un-demo) ; => NIL
(unintern *un-sym* :un-demo) ; => NIL
```
